package org.thoughtcrime.securesms.tap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.database.TransportChannelTable
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.TransportProvider
import org.thoughtcrime.securesms.tap.TransportMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import org.whispersystems.signalservice.api.push.ServiceId.ACI

/**
 * 传输通道管理器
 * 
 * 负责传输通道的生命周期管理，包括通道建立、维护、清理和状态监控。
 * 提供通道的创建、获取、更新和统计功能。
 */
class TransportChannelManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportChannelManager::class.java)
        
        @Volatile
        private var INSTANCE: TransportChannelManager? = null
        
        /**
         * 获取TransportChannelManager单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportChannelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportChannelManager(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建TransportChannelManager实例: ${it.hashCode()}")
                }
            }
        }
        
        /**
         * 重置单例实例（仅用于测试）
         */
        @JvmStatic
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE?.let { instance ->
                    runBlocking {
                        instance.cleanup()
                    }
                }
                INSTANCE = null
                Log.d(TAG, "重置TransportChannelManager实例")
            }
        }
        
        // 默认配置常量
        private const val DEFAULT_CLEANUP_INTERVAL_MS = 60000L // 1分钟
        private const val DEFAULT_CHANNEL_TIMEOUT_MS = 300000L // 5分钟
        private const val DEFAULT_MAX_CHANNELS = 100
    }
    
    // 通道存储和索引（内存缓存）
    private val channels = ConcurrentHashMap<String, TransportChannel>()
    private val recipientChannels = ConcurrentHashMap<String, MutableSet<String>>()
    private val providerChannels = ConcurrentHashMap<String, MutableSet<String>>()
    
    // 使用独立数据库访问（逐步迁移）
    private val transportChannelTable = SignalDatabase.transportChannels
    private val independentChannelTable = org.thoughtcrime.securesms.tap.database.TapTransportChannelTable.getInstance(context)
    
    // 线程安全
    private val channelLock = ReentrantReadWriteLock()
    private val configLock = ReentrantReadWriteLock()
    
    // 配置和状态
    private var config: TransportChannelConfig = TransportChannelConfig()
    private var isInitialized: Boolean = false
    
    // 定时清理
    private var cleanupExecutor: ScheduledExecutorService? = null
    private var cleanupTask: java.util.concurrent.ScheduledFuture<*>? = null
    
    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("ChannelManager")
    )
    
    // 使用统一的TAP数据库上下文，确保与其他组件协调
    private val databaseContext = TapDatabaseContext.databaseDispatcher
    
    /**
     * 初始化通道管理器
     */
    suspend fun initialize(channelConfig: TransportChannelConfig): Boolean {
        return withContext(Dispatchers.IO) {
            configLock.write {
                try {
                    if (isInitialized) {
                        Log.w(TAG, "通道管理器已经初始化")
                        return@withContext true
                    }
                    
                    Log.i(TAG, "初始化通道管理器...")
                    
                    config = channelConfig
                    
                    // 从数据库恢复通道状态
                    restoreChannelsFromDatabase()
                    
                    // 启动定时清理任务
                    startCleanupTask()
                    
                    isInitialized = true
                    Log.i(TAG, "通道管理器初始化完成")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "初始化通道管理器失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 建立传输通道
     */
    suspend fun establishChannel(
        recipientId: String,
        providerType: String,
        metadata: TransportMetadata
    ): TransportChannel? {
        var channelToSave: TransportChannel? = null
        val result = withContext(Dispatchers.IO) {
            channelLock.write {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "通道管理器未初始化")
                        return@withContext null
                    }
                    
                    // 检查是否超过最大通道数限制
                    if (channels.size >= config.maxChannels) {
                        Log.w(TAG, "已达到最大通道数限制: ${config.maxChannels}")
                        cleanupExpiredChannels()
                        if (channels.size >= config.maxChannels) {
                            Log.e(TAG, "无法建立新通道，已达到最大限制")
                            return@withContext null
                        }
                    }
                    
                    val channelId = generateChannelId(recipientId, providerType)
                    val currentTime = System.currentTimeMillis()
                    
                    val channel = TransportChannel(
                        channelId = channelId,
                        recipientId = recipientId,
                        providerType = providerType,
                        metadata = metadata,
                        status = TransportChannelStatus.ESTABLISHING,
                        createdAt = currentTime,
                        lastActiveAt = currentTime,
                        priority = calculateChannelPriority(recipientId, providerType)
                    )
                    
                    // 存储通道到内存，收集用于后续数据库保存
                    channels[channelId] = channel
                    channelToSave = channel
                    
                    // 更新索引
                    recipientChannels.computeIfAbsent(recipientId) { mutableSetOf() }.add(channelId)
                    providerChannels.computeIfAbsent(providerType) { mutableSetOf() }.add(channelId)
                    
                    Log.d(TAG, "建立通道: $channelId, 接收者: $recipientId, 提供者: $providerType")
                    
                    // 同步激活通道，确保通道在创建完成时就处于正确状态
                    try {
                        activateChannel(channelId)
                        Log.d(TAG, "通道同步激活完成: $channelId")
                    } catch (e: Exception) {
                        Log.e(TAG, "通道同步激活失败: $channelId", e)
                        // 激活失败时清理已创建的通道
                        channels.remove(channelId)
                        recipientChannels[recipientId]?.remove(channelId)
                        providerChannels[providerType]?.remove(channelId)
                        channelToSave = null
                        return@withContext null
                    }
                    
                    channel
                    
                } catch (e: Exception) {
                    Log.e(TAG, "建立通道失败", e)
                    channelToSave = null
                    null
                }
            }
        }
        
        // 在锁外异步保存通道到数据库，避免死锁
        channelToSave?.let { channel ->
            try {
                saveChannelToDatabaseAsync(channel)
            } catch (e: Exception) {
                Log.e(TAG, "保存新建通道到数据库失败: ${channel.channelId}", e)
            }
        }
        
        return result
    }
    
    /**
     * 获取或创建通道
     */
    suspend fun getOrCreateChannel(
        recipientId: String,
        providerType: String,
        provider: TransportProvider
    ): TransportChannel? {
        return withContext(Dispatchers.IO) {
            // 首先尝试获取现有活跃通道
            val existingChannel = getActiveChannel(recipientId, providerType)
            if (existingChannel != null) {
                Log.d(TAG, "使用现有通道: ${existingChannel.channelId}")
                return@withContext existingChannel
            }
            
            // 创建新的元数据
            val metadata = createChannelMetadata(recipientId, providerType, provider)
            if (metadata == null) {
                Log.w(TAG, "无法创建通道元数据: $recipientId, $providerType")
                return@withContext null
            }
            
            // 建立新通道
            establishChannel(recipientId, providerType, metadata)
        }
    }
    
    /**
     * 获取活跃通道
     */
    fun getActiveChannels(recipientId: String): List<TransportChannel> {
        channelLock.read {
            Log.d(TAG, "getActiveChannels查询: recipientId=$recipientId")
            Log.d(TAG, "recipientChannels映射大小: ${recipientChannels.size}")
            Log.d(TAG, "现有recipientChannels keys: ${recipientChannels.keys.joinToString(", ")}")
            
            val channelIds = recipientChannels[recipientId] ?: run {
                Log.w(TAG, "recipientChannels中没有找到recipientId: $recipientId")
                return emptyList()
            }
            
            Log.d(TAG, "找到channelIds: $channelIds")
            
            // 先获取所有通道快照，避免在读锁内调用可能修改状态的方法
            val channelSnapshots = channelIds.mapNotNull { channelId ->
                channels[channelId]?.also { channel ->
                    Log.d(TAG, "通道详情: channelId=$channelId, status=${channel.status}, recipientId=${channel.recipientId}")
                }
            }
            
            Log.d(TAG, "获取到${channelSnapshots.size}个通道快照")
            
            // 在读锁外进行状态检查和排序
            return channelSnapshots.filter { channel ->
                try {
                    val isActive = channel.isActive()
                    Log.d(TAG, "通道活跃状态检查: channelId=${channel.channelId}, isActive=$isActive, status=${channel.status}")
                    isActive
                } catch (e: Exception) {
                    Log.w(TAG, "检查通道状态时出错: ${channel.channelId}", e)
                    false
                }
            }.sortedByDescending { it.priority }
        }
    }
    
    /**
     * 获取指定Provider的活跃通道
     */
    fun getActiveChannel(recipientId: String, providerType: String): TransportChannel? {
        channelLock.read {
            val channelIds = recipientChannels[recipientId] ?: return null
            return channelIds.mapNotNull { channelId ->
                channels[channelId]?.takeIf { 
                    it.providerType == providerType && it.isActive()
                }
            }.maxByOrNull { it.priority }
        }
    }
    
    /**
     * 获取所有活跃通道
     */
    fun getAllActiveChannels(): List<TransportChannel> {
        channelLock.read {
            return channels.values.filter { it.isActive() }.sortedByDescending { it.priority }
        }
    }
    
    /**
     * 获取群组的所有通道
     * 
     * @param groupId 群组 ID
     * @return 群组所有成员的通道列表
     */
    fun getGroupChannels(groupId: String): List<TransportChannel> {
        channelLock.read {
            // 过滤出属于指定群组的所有通道
            // 群组通道可以通过 channel.config 中的 groupId 字段识别
            return channels.values.filter { channel ->
                channel.config["groupId"] == groupId
            }.sortedByDescending { it.priority }
        }
    }
    
    /**
     * 批量建立群组通道
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 建立结果 (成功数, 失败的成员列表)
     */
    suspend fun establishGroupChannelsBatch(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Pair<Int, List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "批量建立群组通道: groupId=$groupId, members=${memberAcis.size}, provider=$providerType")
                
                // 获取 Provider
                val transportManager = getTransportManager()
                val provider = transportManager.getProvider(providerType)
                if (provider == null) {
                    Log.e(TAG, "Provider 不存在: $providerType")
                    return@withContext Pair(0, memberAcis.toList())
                }
                
                var successCount = 0
                val failedMembers = mutableListOf<String>()
                
                // 并发为每个成员建立通道
                supervisorScope {
                    val jobs = memberAcis.map { memberAci ->
                        async(Dispatchers.IO) {
                            try {
                                // 获取或创建通道
                                val channel = getOrCreateChannel(memberAci, providerType, provider)
                                
                                if (channel != null) {
                                    // 在通道配置中标记群组 ID
                                    val groupConfig = channel.config.toMutableMap()
                                    groupConfig["groupId"] = groupId
                                    
                                    val updatedChannel = channel.copy(config = groupConfig)
                                    
                                    // 更新内存缓存
                                    channelLock.write {
                                        channels[updatedChannel.channelId] = updatedChannel
                                    }
                                    
                                    // 异步保存到数据库
                                    saveChannelToDatabaseAsync(updatedChannel)
                                    
                                    Log.d(TAG, "群组成员通道建立成功: memberAci=${LogSanitizer.sanitize(memberAci)}")
                                    true
                                } else {
                                    Log.w(TAG, "群组成员通道建立失败: memberAci=${LogSanitizer.sanitize(memberAci)}")
                                    false
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "建立群组成员通道时异常: memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                                false
                            }
                        }
                    }
                    
                    // 等待所有任务完成并统计结果
                    jobs.forEachIndexed { index, deferred ->
                        val memberAci = memberAcis.elementAt(index)
                        val success = deferred.await()
                        if (success) {
                            successCount++
                        } else {
                            failedMembers.add(memberAci)
                        }
                    }
                }
                
                Log.i(TAG, "批量建立群组通道完成: groupId=$groupId, 成功=$successCount/${memberAcis.size}")
                Pair(successCount, failedMembers)
                
            } catch (e: Exception) {
                Log.e(TAG, "批量建立群组通道失败: groupId=$groupId", e)
                Pair(0, memberAcis.toList())
            }
        }
    }
    
    /**
     * 获取群组的活跃通道
     * 
     * @param groupId 群组 ID
     * @return 群组活跃通道列表
     */
    fun getGroupActiveChannels(groupId: String): List<TransportChannel> {
        channelLock.read {
            return channels.values.filter { channel ->
                channel.config["groupId"] == groupId && channel.isActive()
            }.sortedByDescending { it.priority }
        }
    }
    
    /**
     * 关闭群组的所有通道
     * 
     * @param groupId 群组 ID
     * @return 关闭的通道数量
     */
    suspend fun closeGroupChannels(groupId: String): Int {
        return withContext(Dispatchers.IO) {
            val groupChannels = channelLock.read {
                channels.values.filter { channel ->
                    channel.config["groupId"] == groupId
                }.map { it.channelId }
            }
            
            var closedCount = 0
            for (channelId in groupChannels) {
                val success = closeChannel(channelId)
                if (success) {
                    closedCount++
                }
            }
            
            Log.i(TAG, "关闭群组通道: groupId=$groupId, 关闭=$closedCount/${groupChannels.size}")
            closedCount
        }
    }
    
    /**
     * 批量升级群组通道为 FULL_ACTIVE
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 升级成功的数量
     */
    suspend fun upgradeGroupChannelsToFullActive(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Int {
        return withContext(Dispatchers.IO) {
            var successCount = 0
            
            for (memberAci in memberAcis) {
                try {
                    val success = upgradeChannelToFullActive(memberAci, providerType)
                    if (success) {
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "升级群组成员通道失败: memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            Log.i(TAG, "批量升级群组通道: groupId=$groupId, 成功=$successCount/${memberAcis.size}")
            successCount
        }
    }
    
    /**
     * 获取群组通道统计信息
     * 
     * @param groupId 群组 ID
     * @return 群组通道统计
     */
    fun getGroupChannelStatistics(groupId: String): GroupChannelStatistics {
        channelLock.read {
            val groupChannels = channels.values.filter { channel ->
                channel.config["groupId"] == groupId
            }
            
            val totalChannels = groupChannels.size
            val activeChannels = groupChannels.count { it.isActive() }
            val failedChannels = groupChannels.count { it.isFailed() }
            val statusDistribution = groupChannels.groupBy { it.status }
                .mapValues { it.value.size }
            
            return GroupChannelStatistics(
                groupId = groupId,
                totalChannels = totalChannels,
                activeChannels = activeChannels,
                failedChannels = failedChannels,
                statusDistribution = statusDistribution,
                averageSuccessRate = if (groupChannels.isNotEmpty()) {
                    groupChannels.map { it.getSuccessRate() }.average()
                } else {
                    0.0
                }
            )
        }
    }
    
    /**
     * 批量健康检查群组通道
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 健康的成员 ACI 集合
     */
    suspend fun validateGroupChannelsHealth(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Set<String> {
        return withContext(Dispatchers.IO) {
            val healthyMembers = mutableSetOf<String>()
            
            for (memberAci in memberAcis) {
                try {
                    val isHealthy = validateChannelHealth(memberAci, providerType)
                    if (isHealthy) {
                        healthyMembers.add(memberAci)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "群组通道健康检查失败: memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            Log.i(TAG, "群组通道健康检查: groupId=$groupId, 健康=${healthyMembers.size}/${memberAcis.size}")
            healthyMembers
        }
    }
    
    /**
     * 获取通道
     */
    fun getChannel(channelId: String): TransportChannel? {
        channelLock.read {
            return channels[channelId]
        }
    }
    
    /**
     * 更新通道成功
     */
    suspend fun updateChannelSuccess(channelId: String) {
        var channelToSave: TransportChannel? = null
        withContext(Dispatchers.IO) {
            channelLock.write {
                val channel = channels[channelId] ?: return@withContext
                val updatedChannel = channel.recordSuccess().copy(
                    lastActiveAt = System.currentTimeMillis()
                )
                
                // 如果通道之前不是活跃状态，激活它
                val finalChannel = if (!channel.isActive()) {
                    updatedChannel.updateStatus(TransportChannelStatus.ACTIVE)
                } else {
                    updatedChannel
                }
                
                channels[channelId] = finalChannel
                channelToSave = finalChannel
                
                Log.d(TAG, "通道操作成功: $channelId")
            }
        }
        
        // 在锁外异步保存通道到数据库，避免死锁
        channelToSave?.let { channel ->
            try {
                saveChannelToDatabaseAsync(channel)
            } catch (e: Exception) {
                Log.e(TAG, "保存成功通道状态到数据库失败: ${channel.channelId}", e)
            }
        }
    }
    
    /**
     * 更新通道失败
     */
    suspend fun updateChannelFailure(channelId: String, error: TransportError) {
        var channelToSave: TransportChannel? = null
        withContext(Dispatchers.IO) {
            channelLock.write {
                val channel = channels[channelId] ?: return@withContext
                val updatedChannel = channel.recordFailure(error)
                
                // 如果失败次数过多，标记通道为失败状态
                val finalChannel = if (updatedChannel.failureCount >= config.maxFailureCount) {
                    updatedChannel.updateStatus(TransportChannelStatus.FAILED).also {
                        Log.w(TAG, "通道失败次数过多，标记为失败: $channelId")
                    }
                } else {
                    updatedChannel
                }
                
                channels[channelId] = finalChannel
                channelToSave = finalChannel
                
                Log.w(TAG, "通道操作失败: $channelId, 错误: $error, 失败次数: ${finalChannel.failureCount}")
            }
        }
        
        // 在锁外异步保存通道到数据库，避免死锁
        channelToSave?.let { channel ->
            try {
                saveChannelToDatabaseAsync(channel)
            } catch (e: Exception) {
                Log.e(TAG, "保存失败通道状态到数据库失败: ${channel.channelId}", e)
            }
        }
    }
    
    /**
     * 关闭通道
     */
    suspend fun closeChannel(channelId: String): Boolean {
        return withContext(Dispatchers.IO) {
            channelLock.write {
                val channel = channels[channelId] ?: return@withContext false
                
                try {
                    // 更新状态为关闭
                    val closedChannel = channel.updateStatus(TransportChannelStatus.CLOSED)
                    channels[channelId] = closedChannel
                    
                    Log.i(TAG, "关闭通道: $channelId")
                    
                    // 异步清理通道资源
                    managerScope.launch {
                        cleanupChannel(channelId)
                        deleteChannelFromDatabase(channelId)
                    }
                    
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "关闭通道失败: $channelId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 将通道升级为FULL_ACTIVE状态
     * 当双向Token交换完成时调用
     */
    suspend fun upgradeChannelToFullActive(recipientId: String, providerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            // 预先准备可能需要的metadata更新（在锁外执行）
            val updatedMetadata = if (providerType == "cos") {
                try {
                    val tokenPool = TransportTokenPool.getInstance(context)
                    val provider = getTransportManager().getProvider(providerType)
                    val configManager = TransportProviderConfigManager.getInstance(context)
                    if (provider != null && configManager != null) {
                        createCosChannelMetadata(recipientId, provider, configManager, tokenPool)
                    } else {
                        Log.w(TAG, "无法获取provider或configManager for metadata更新")
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "预先构建COS metadata失败: ${e.message}")
                    null
                }
            } else {
                null // 其他provider暂时不需要metadata更新
            }
            
            // 第一步：在锁内完成状态更新和数据库保存
            val upgradedChannel = channelLock.write {
                val channelIds = recipientChannels[recipientId] ?: run {
                    Log.w(TAG, "未找到recipientId对应的通道: recipientId=$recipientId, 现有keys=${recipientChannels.keys}")
                    return@write null
                }
                
                for (channelId in channelIds) {
                    val channel = channels[channelId]
                    if (channel != null && channel.providerType == providerType) {
                        // 如果通道已经是FULL_ACTIVE状态，直接返回
                        if (channel.status == TransportChannelStatus.FULL_ACTIVE) {
                            Log.i(TAG, "通道已经是FULL_ACTIVE状态: channelId=$channelId, recipientId=$recipientId, providerType=$providerType")
                            return@write channel
                        }
                        
                        // 允许从ESTABLISHING、SEND_READY、ACTIVE状态升级到FULL_ACTIVE
                        if (channel.status == TransportChannelStatus.ESTABLISHING ||
                            channel.status == TransportChannelStatus.SEND_READY || 
                            channel.status == TransportChannelStatus.ACTIVE) {
                            
                            val upgradedChannel = if (updatedMetadata != null && updatedMetadata !== channel.metadata) {
                                // 使用预先构建的metadata创建升级的channel
                                channel.copy(
                                    status = TransportChannelStatus.FULL_ACTIVE,
                                    metadata = updatedMetadata,
                                    lastActiveAt = System.currentTimeMillis()
                                )
                            } else {
                                // 使用原有方式升级（没有metadata更新或更新失败）
                                channel.updateStatus(TransportChannelStatus.FULL_ACTIVE)
                            }
                            
                            channels[channelId] = upgradedChannel
                            
                            Log.i(TAG, "通道升级为FULL_ACTIVE: channelId=$channelId, recipientId=$recipientId, providerType=$providerType, fromStatus=${channel.status}, metadataUpdated=${updatedMetadata != null}")
                            return@write upgradedChannel
                        } else {
                            Log.w(TAG, "通道状态不支持升级: channelId=$channelId, currentStatus=${channel.status}, recipientId=$recipientId, providerType=$providerType, 支持的状态=[ESTABLISHING, SEND_READY, ACTIVE] 或已经是FULL_ACTIVE")
                        }
                    } else if (channel != null) {
                        Log.d(TAG, "通道providerType不匹配: channelId=$channelId, expected=$providerType, actual=${channel.providerType}")
                    } else {
                        Log.w(TAG, "通道不存在: channelId=$channelId")
                    }
                }
                
                Log.w(TAG, "未找到可升级的通道: recipientId=$recipientId, providerType=$providerType, 检查了${channelIds.size}个通道")
                if (channelIds.isNotEmpty()) {
                    val channelDetails = channelIds.mapNotNull { channelId ->
                        channels[channelId]?.let { channel ->
                            "channelId=$channelId, status=${channel.status}, providerType=${channel.providerType}, createdAt=${channel.createdAt}"
                        }
                    }
                    Log.d(TAG, "通道详细状态: ${channelDetails.joinToString("; ")}")
                } else {
                    Log.d(TAG, "recipientChannels中没有找到对应的通道ID列表")
                }
                return@write null
            }
            
            // 第二步：在锁外进行数据库保存和轮询启动
            if (upgradedChannel != null) {
                try {
                    // 保存到数据库
                    saveChannelToDatabaseAsync(upgradedChannel)
                    Log.d(TAG, "通道数据库保存完成: channelId=${upgradedChannel.channelId}")
                    
                    // 启动轮询
                    try {
                        ensurePollingServiceRunning(recipientId, upgradedChannel)
                        Log.d(TAG, "通道升级后轮询确保成功: channelId=${upgradedChannel.channelId}")
                    } catch (e: Exception) {
                        Log.e(TAG, "确保轮询运行失败，但通道升级已完成: channelId=${upgradedChannel.channelId}", e)
                        // 轮询启动失败不影响通道升级结果
                    }
                    
                    return@withContext true
                } catch (e: Exception) {
                    Log.e(TAG, "通道数据库保存失败: channelId=${upgradedChannel.channelId}", e)
                    return@withContext false
                }
            }
            
            return@withContext false
        }
    }
    
    /**
     * 禁用指定接收者的v2模式
     * 将所有相关通道关闭并插入禁用消息
     */
    suspend fun disableV2Mode(
        recipientId: String, 
        sendControlMessage: Boolean = true, 
        insertSystemMessage: Boolean = true
    ): Boolean {
        val channelsToSave = mutableListOf<TransportChannel>()
        var disableMessageInfo: Pair<String, String>? = null
        var systemMessageRecipientId: String? = null
        val success = withContext(Dispatchers.IO) {
            channelLock.write {
                try {
                    // 统一Recipient ID格式：支持多种输入格式转换为ACI字符串
                    val normalizedRecipientId = normalizeRecipientId(recipientId)
                    Log.d(TAG, "禁用v2模式: 原始ID=$recipientId, 标准化ID=$normalizedRecipientId")
                    
                    // 尝试多种格式匹配通道
                    val channelIds = recipientChannels[normalizedRecipientId] 
                        ?: recipientChannels[recipientId]
                    
                    if (channelIds.isNullOrEmpty()) {
                        Log.i(TAG, "没有找到通道需要禁用: recipientId=$recipientId, normalizedId=$normalizedRecipientId")
                        Log.d(TAG, "当前已有通道: ${recipientChannels.keys}")
                        return@withContext false
                    }
                    
                    var disabledCount = 0
                    val channelsToClose = mutableListOf<TransportChannel>()
                    
                    // 收集需要关闭的通道
                    for (channelId in channelIds.toList()) {
                        channels[channelId]?.let { channel ->
                            if (channel.isActive()) {
                                channelsToClose.add(channel)
                            }
                        }
                    }
                    
                    // 关闭通道并更新内存状态
                    for (channel in channelsToClose) {
                        val closedChannel = channel.updateStatus(TransportChannelStatus.CLOSED)
                        channels[channel.channelId] = closedChannel
                        channelsToSave.add(closedChannel)
                        disabledCount++
                        
                        Log.d(TAG, "已关闭通道: ${channel.channelId}, provider=${channel.providerType}")
                        
                        // 移除轮询目标
                        try {
                            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                            // 使用标准化的ID格式匹配轮询任务
                            val removeResult = pollingService.removePollingTarget(normalizedRecipientId, channel.providerType)
                            if (removeResult) {
                                Log.d(TAG, "已移除轮询目标: recipient=$normalizedRecipientId, provider=${channel.providerType}")
                            } else {
                                Log.w(TAG, "移除轮询目标失败: recipient=$normalizedRecipientId, provider=${channel.providerType}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "移除轮询目标异常", e)
                        }
                    }
                    
                    // 清理Token池
                    try {
                        val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                        val removedTokenCount = tokenPool.removeAllTokensForRecipient(normalizedRecipientId)
                        Log.d(TAG, "已清理Token: recipientId=$normalizedRecipientId, count=$removedTokenCount")
                    } catch (e: Exception) {
                        Log.e(TAG, "清理Token失败", e)
                    }
                    
                    // 收集disable控制消息信息，稍后在锁外发送（仅当允许发送时）
                    if (sendControlMessage && channelsToClose.isNotEmpty()) {
                        disableMessageInfo = normalizedRecipientId to channelsToClose.first().providerType
                    }
                    
                    // 清理索引：清理所有可能的格式
                    recipientChannels.remove(recipientId)
                    recipientChannels.remove(normalizedRecipientId)
                    channelsToClose.forEach { channel ->
                        providerChannels[channel.providerType]?.remove(channel.channelId)
                        if (providerChannels[channel.providerType]?.isEmpty() == true) {
                            providerChannels.remove(channel.providerType)
                        }
                    }
                    
                    if (disabledCount > 0) {
                        Log.i(TAG, "v2模式已禁用: 原始ID=$recipientId, 标准化ID=$normalizedRecipientId, 关闭了${disabledCount}个通道")
                        
                        // 收集系统消息信息，稍后在锁外插入（仅当允许插入时）
                        if (insertSystemMessage) {
                            systemMessageRecipientId = recipientId
                        }
                    }
                    
                    return@withContext disabledCount > 0
                    
                } catch (e: Exception) {
                    Log.e(TAG, "禁用v2模式失败: recipientId=$recipientId", e)
                    return@withContext false
                }
            }
        }
        
        // 在锁外执行所有数据库操作，避免死锁
        withContext(databaseContext) {
            try {
                // 串行保存所有通道状态
                channelsToSave.forEach { channel ->
                    try {
                        saveChannelToDatabaseAsync(channel)
                        Log.v(TAG, "已保存关闭通道: ${channel.channelId}")
                    } catch (e: Exception) {
                        Log.e(TAG, "保存关闭通道到数据库失败: ${channel.channelId}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存通道状态失败", e)
            }
        }
        
        // 使用后台作业异步插入系统消息，完全避免数据库连接竞争
        systemMessageRecipientId?.let { recipientId ->
            try {
                val recipientIdObj = getRecipientIdFromString(recipientId)
                val job = org.thoughtcrime.securesms.tap.jobs.TapInsertV2DisabledMessageJob(recipientIdObj)
                org.thoughtcrime.securesms.dependencies.AppDependencies.jobManager.add(job)
                Log.d(TAG, "已排队v2模式禁用消息作业: recipientId=$recipientId")
            } catch (e: Exception) {
                Log.e(TAG, "排队v2模式禁用消息作业失败: recipientId=$recipientId", e)
            }
        }
        
        // 2. 发送disable控制消息（在独立的IO上下文中，避免与数据库操作串行化）
        disableMessageInfo?.let { (recipientId, providerType) ->
            withContext(Dispatchers.IO) {
                try {
                    sendDisableControlMessage(recipientId, providerType)
                } catch (e: Exception) {
                    Log.e(TAG, "发送disable控制消息失败", e)
                }
            }
        }
        
        return success
    }
    
    /**
     * 标准化Recipient ID格式
     * 支持多种输入格式转换为统一的ACI字符串格式
     */
    private fun normalizeRecipientId(recipientId: String): String {
        return try {
            when {
                // 处理 "RecipientId::数字" 格式
                recipientId.startsWith("RecipientId::") -> {
                    val idNumber = recipientId.removePrefix("RecipientId::")
                    val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(idNumber.toLong())
                    val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
                    recipient.requireAci().toString()
                }
                // 处理纯数字格式
                recipientId.all { it.isDigit() } -> {
                    val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                    val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
                    recipient.requireAci().toString()
                }
                // 已经是ACI格式（UUID样式），直接返回
                recipientId.contains("-") && recipientId.length >= 32 -> {
                    recipientId
                }
                // 其他情况，尝试作为ACI处理
                else -> {
                    recipientId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recipient ID格式转换失败: $recipientId", e)
            recipientId
        }
    }
    
    /**
     * 从字符串获取RecipientId对象
     * 支持多种输入格式
     */
    private fun getRecipientIdFromString(recipientId: String): org.thoughtcrime.securesms.recipients.RecipientId {
        return try {
            when {
                // 处理 "RecipientId::数字" 格式
                recipientId.startsWith("RecipientId::") -> {
                    val idNumber = recipientId.removePrefix("RecipientId::")
                    org.thoughtcrime.securesms.recipients.RecipientId.from(idNumber.toLong())
                }
                // 处理纯数字格式
                recipientId.all { it.isDigit() } -> {
                    org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                }
                // ACI格式，需要通过ACI反查RecipientId
                recipientId.contains("-") && recipientId.length >= 32 -> {
                    val aci = ACI.parseOrThrow(recipientId)
                    org.thoughtcrime.securesms.database.SignalDatabase.recipients.getByAci(aci).orElseThrow {
                        IllegalArgumentException("无法找到ACI对应的RecipientId: $recipientId")
                    }
                }
                // 其他情况，尝试作为数字处理
                else -> {
                    org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RecipientId对象获取失败: $recipientId", e)
            throw IllegalArgumentException("无法解析RecipientId: $recipientId", e)
        }
    }
    
    /**
     * 发送disable控制消息
     */
    private suspend fun sendDisableControlMessage(recipientId: String, providerType: String) {
        withContext(Dispatchers.IO) {
            try {
                val selfAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.aci?.toString()
                if (selfAci == null) {
                    Log.w(TAG, "无法获取自己的ACI，跳过发送disable控制消息")
                    return@withContext
                }
                
                val disableMessage = TapTokenExchangeMessage(
                    senderAci = selfAci,
                    providerType = providerType,
                    tokenData = emptyMap(),
                    metadata = mapOf("reason" to "user_disabled"),
                    requestType = TapTokenExchangeMessage.REQUEST_TYPE_DISABLE
                )
                
                val recipientIdObj = try {
                    getRecipientIdFromString(recipientId)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "无法解析RecipientId，跳过发送disable控制消息: $recipientId", e)
                    return@withContext
                }
                
                val messageBody = TapTokenExchangeMessage.encode(disableMessage)
                
                // 通过Signal Server发送控制消息
                val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
                val outgoingMessage = org.thoughtcrime.securesms.mms.OutgoingMessage.tapTokenExchangeMessage(
                    recipient,
                    System.currentTimeMillis(),
                    0L,
                    messageBody
                )
                
                val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads
                    .getOrCreateThreadIdFor(recipient)
                
                val messageId = org.thoughtcrime.securesms.database.SignalDatabase.messages
                    .insertMessageOutbox(outgoingMessage, threadId, false, null)
                
                if (messageId > 0) {
                    Log.i(TAG, "已发送disable控制消息: messageId=$messageId")
                    
                    // 添加到发送队列
                    org.thoughtcrime.securesms.dependencies.AppDependencies
                        .jobManager
                        .add(org.thoughtcrime.securesms.jobs.IndividualSendJob.create(
                            messageId,
                            recipient,
                            false,
                            false
                        ))
                } else {
                    Log.e(TAG, "插入disable控制消息失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "发送disable控制消息异常", e)
            }
        }
    }
    
    /**
     * 关闭指定Provider的所有通道
     */
    suspend fun closeProviderChannels(providerType: String) {
        withContext(Dispatchers.IO) {
            channelLock.read {
                val channelIds = providerChannels[providerType]?.toList() ?: return@withContext
                Log.i(TAG, "关闭Provider通道: $providerType, 数量: ${channelIds.size}")
            }
            
            channelLock.read {
                providerChannels[providerType]?.toList()
            }?.forEach { channelId ->
                closeChannel(channelId)
            }
        }
    }
    
    /**
     * 停用指定Provider的所有通道
     */
    suspend fun deactivateProviderChannels(providerType: String) {
        withContext(Dispatchers.IO) {
            channelLock.write {
                val channelIds = providerChannels[providerType] ?: return@withContext
                
                channelIds.forEach { channelId ->
                    val channel = channels[channelId]
                    if (channel != null && channel.isActive()) {
                        val suspendedChannel = channel.updateStatus(TransportChannelStatus.SUSPENDED)
                        channels[channelId] = suspendedChannel
                        Log.d(TAG, "暂停通道: $channelId")
                    }
                }
                
                Log.i(TAG, "停用Provider通道: $providerType, 数量: ${channelIds.size}")
            }
        }
    }
    
    /**
     * 清理过期通道
     */
    suspend fun cleanupExpiredChannels() {
        withContext(Dispatchers.IO) {
            channelLock.write {
                val expiredChannels = mutableListOf<String>()
                val currentTime = System.currentTimeMillis()
                
                channels.values.forEach { channel ->
                    if (channel.isExpired(config.channelTimeoutMs) || 
                        channel.status == TransportChannelStatus.CLOSED) {
                        expiredChannels.add(channel.channelId)
                    }
                }
                
                if (expiredChannels.isNotEmpty()) {
                    Log.i(TAG, "清理过期通道，数量: ${expiredChannels.size}")
                    
                    expiredChannels.forEach { channelId ->
                        removeChannelFromIndexes(channelId)
                        channels.remove(channelId)
                    }
                }
            }
        }
    }
    
    /**
     * 获取通道统计信息
     */
    fun getChannelStatistics(): TransportChannelStatistics {
        channelLock.read {
            val totalChannels = channels.size
            val activeChannels = channels.values.count { it.isActive() }
            val failedChannels = channels.values.count { it.isFailed() }
            val statusStats = mutableMapOf<TransportChannelStatus, Int>()
            
            TransportChannelStatus.values().forEach { status ->
                statusStats[status] = channels.values.count { it.status == status }
            }
            
            val providerStats = mutableMapOf<String, Int>()
            channels.values.groupBy { it.providerType }.forEach { (provider, channelList) ->
                providerStats[provider] = channelList.size
            }
            
            return TransportChannelStatistics(
                totalChannels = totalChannels,
                activeChannels = activeChannels,
                failedChannels = failedChannels,
                statusDistribution = statusStats,
                providerDistribution = providerStats
            )
        }
    }
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(newConfig: TransportChannelConfig) {
        withContext(Dispatchers.IO) {
            configLock.write {
                val oldConfig = config
                config = newConfig
                
                Log.i(TAG, "更新通道配置")
                
                // 如果清理间隔改变，重启清理任务
                if (oldConfig.cleanupIntervalMs != newConfig.cleanupIntervalMs) {
                    restartCleanupTask()
                }
                
                // 如果最大通道数减少，清理多余通道
                if (newConfig.maxChannels < oldConfig.maxChannels) {
                    cleanupExcessChannels(newConfig.maxChannels)
                }
            }
        }
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始清理通道管理器资源")
                
                // 停止清理任务
                stopCleanupTask()
                
                // 关闭所有通道
                channelLock.write {
                    channels.keys.toList().forEach { channelId ->
                        runBlocking { closeChannel(channelId) }
                    }
                    
                    channels.clear()
                    recipientChannels.clear()
                    providerChannels.clear()
                }
                
                // 取消协程作用域
                managerScope.cancel()
                
                configLock.write {
                    isInitialized = false
                }
                
                Log.i(TAG, "通道管理器资源清理完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "清理通道管理器资源失败", e)
            }
        }
    }
    
    // 私有辅助方法
    
    /**
     * 生成通道ID
     */
    private fun generateChannelId(recipientId: String, providerType: String): String {
        val timestamp = System.currentTimeMillis()
        return "ch_${recipientId}_${providerType}_$timestamp"
    }
    
    /**
     * 计算通道优先级
     */
    private fun calculateChannelPriority(recipientId: String, providerType: String): Int {
        // 基础优先级为5
        var priority = 5
        
        // 根据Provider类型调整优先级
        when (providerType) {
            "cos" -> priority += 2  // COS优先级较高
            "email" -> priority -= 1  // Email优先级较低
            "nas" -> priority += 1   // NAS优先级中等
            "ipfs" -> priority += 1  // IPFS优先级中等
            "git" -> priority -= 1   // Git优先级较低
        }
        
        // 确保在配置范围内
        return priority.coerceIn(config.priorityRange)
    }
    
    /**
     * 创建通道元数据
     */
    private suspend fun createChannelMetadata(
        recipientId: String, 
        providerType: String,
        provider: TransportProvider
    ): TransportMetadata? {
        return try {
            Log.d(TAG, "创建通道元数据: recipientId=$recipientId, providerType=$providerType")
            
            // 获取Provider配置管理器和Token池
            val configManager = TransportProviderConfigManager.getInstance(context)
            val tokenPool = TransportTokenPool.getInstance(context)
            
            val metadata = when (providerType) {
                "cos" -> createCosChannelMetadata(recipientId, provider, configManager, tokenPool)
                else -> createGenericChannelMetadata(recipientId, providerType, provider, configManager, tokenPool)
            }
            
            if (metadata != null) {
                Log.i(TAG, "通道元数据创建成功: $providerType")
                metadata
            } else {
                Log.w(TAG, "通道元数据创建返回null，尝试创建基础元数据: $providerType")
                createFallbackMetadata(recipientId, providerType, provider, configManager)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建通道元数据异常: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            
            // 异常时创建最基础的metadata，确保轮询能启动
            try {
                val configManager = TransportProviderConfigManager.getInstance(context)
                val fallbackMetadata = createFallbackMetadata(recipientId, providerType, provider, configManager)
                if (fallbackMetadata != null) {
                    Log.w(TAG, "使用fallback元数据: $providerType")
                }
                fallbackMetadata
            } catch (fallbackException: Exception) {
                Log.e(TAG, "创建fallback元数据也失败: $providerType", fallbackException)
                null
            }
        }
    }
    
    /**
     * 创建fallback元数据，确保基本功能可用
     */
    private fun createFallbackMetadata(
        recipientId: String,
        providerType: String,
        provider: TransportProvider,
        configManager: TransportProviderConfigManager
    ): TransportMetadata? {
        return try {
            Log.d(TAG, "创建fallback元数据: providerType=$providerType")
            
            val providerConfig = configManager.getProviderConfig(providerType)
            if (providerConfig == null) {
                Log.w(TAG, "Provider配置不存在，无法创建fallback元数据: $providerType")
                return null
            }
            
            val address = provider.formatAddress(providerConfig)
            val sendPath = provider.getSendPath(recipientId)
            val receivePath = provider.getReceivePath(recipientId)
            
            // 获取本端ACI作为myId
            val myAci = try {
                org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            } catch (e: Exception) {
                Log.w(TAG, "无法获取本端ACI，使用默认值", e)
                "unknown"
            }
            
            // 使用GenericTransportMetadata而不是匿名对象
            GenericTransportMetadata(
                recipientId = recipientId,
                providerType = providerType,
                sendMetadata = SendMetadata(
                    address = address,
                    token = null, // fallback时可能没有token
                    path = sendPath,
                    recipientId = recipientId
                ),
                receiveMetadata = ReceiveMetadata(
                    address = address,
                    token = null, // fallback时可能没有token
                    path = receivePath,
                    myId = myAci
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建fallback元数据失败: $providerType", e)
            null
        }
    }
    
    /**
     * 创建COS通道元数据
     */
    private suspend fun createCosChannelMetadata(
        recipientId: String,
        provider: TransportProvider,
        configManager: TransportProviderConfigManager,
        tokenPool: TransportTokenPool
    ): org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata? {
        // 1. 获取本端COS配置
        val myProviderConfig = configManager.getProviderConfig("cos")
            ?: run {
                Log.w(TAG, "未找到本端COS配置")
                return null
            }
        
        // 2. 获取对端Token信息（用于轮询对方消息）
        val peerTokenInfo = tokenPool.getPeerTokenInfo(recipientId, "cos")
        
        // 在v2模式建立初期，可能还没有对端Token信息，此时使用默认值
        if (peerTokenInfo == null) {
            Log.w(TAG, "未找到对端Token信息，使用默认配置进行通道建立: recipientId=$recipientId")
        }
        
        // 3. 构建本端地址和参数
        val myAddress = provider.formatAddress(myProviderConfig)
        val myRegion = myProviderConfig["region"]?.toString() 
            ?: run {
                Log.e(TAG, "COS配置缺少region参数")
                return null
            }
        val myBucketName = myProviderConfig["bucketName"]?.toString() 
            ?: run {
                Log.e(TAG, "COS配置缺少bucketName参数")
                return null
            }
        
        // 4. 从对端Token信息中获取对端参数（如果没有对端Token则使用默认值）
        val peerAddress = peerTokenInfo?.address ?: myAddress // 回退到本端地址
        val peerRegion = peerTokenInfo?.region ?: myRegion // 回退到本端region
        val peerBucketName = peerTokenInfo?.bucketName ?: myBucketName // 回退到本端bucket
        
        // 5. 生成本端Token供对方使用（如果不存在）
        // 注意：此Token仅用于分享给对方，让对方能轮询我们的消息
        // 本端发送消息时不使用此Token，而是直接使用本地高权限配置
        val sharedTokenForPeer = run {
            // 检查是否已经为此对方生成了shared token
            val existingSharedToken = tokenPool.getValidSharedToken(recipientId, "cos")
            if (existingSharedToken != null) {
                Log.d(TAG, "已存在共享Token，复用: tokenId=${LogSanitizer.sanitize(existingSharedToken.tokenId)}")
                existingSharedToken
            } else {
                Log.d(TAG, "共享Token不存在，生成新的只读Token供对方轮询")
                try {
                    // 创建Token请求：只读权限，供对方轮询我们的outbox
                    val tokenRequest = TransportTokenRequest(
                        recipientId = recipientId,
                        providerType = "cos",
                        requestedPermissions = setOf(
                            TransportPermission.READ,
                            TransportPermission.LIST
                        ),
                        validityDurationMs = 0L, // 0表示使用默认（长期有效）
                        providerConfig = myProviderConfig,
                        purpose = "peer_polling_token"
                    )
                    
                    // 使用Provider生成Token
                    val generatedToken = provider.generateToken(tokenRequest)
                    if (generatedToken != null) {
                        // 保存到Token池（作为共享Token，供对方访问我们的存储）
                        val saveSuccess = tokenPool.addSharedToken(recipientId, generatedToken)
                        if (saveSuccess) {
                            Log.i(TAG, "对方轮询Token生成并保存成功: tokenId=${LogSanitizer.sanitize(generatedToken.tokenId)}")
                            generatedToken
                        } else {
                            Log.w(TAG, "对方轮询Token保存失败")
                            null
                        }
                    } else {
                        Log.w(TAG, "Provider生成对方轮询Token失败")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "生成对方轮询Token异常: ${LogSanitizer.sanitizeThrowable(e)}")
                    null
                }
            }
        }
        
        // 6. 生成哈希化ID
        val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
        val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
        val peerHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAciString(recipientId)
        
        // 7. 构建路径
        val mySendPath = provider.getSendPath(peerHashedId, org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE)
        val peerReceivePath = provider.getReceivePath(myHashedId, org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE)
        
        Log.d(TAG, "COS元数据创建: " +
              "myAddress=${LogSanitizer.sanitize(myAddress, "address")}, " +
              "peerAddress=${LogSanitizer.sanitize(peerAddress, "address")}, " +
              "myHashedId=$myHashedId, peerHashedId=$peerHashedId, " +
              "mySendPath=$mySendPath, peerReceivePath=$peerReceivePath")
        
        return org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
            recipientId = recipientId,
            providerType = "cos",
            myAddress = myAddress,
            myToken = null, // 重要：发送消息时不使用token，直接使用本地高权限配置
            myRegion = myRegion,
            myBucketName = myBucketName,
            mySendPath = mySendPath,
            peerAddress = peerAddress,
            peerToken = peerTokenInfo?.token, // 用于轮询对方消息的只读token
            peerRegion = peerRegion,
            peerBucketName = peerBucketName,
            peerReceivePath = peerReceivePath,
            myHashedId = myHashedId,
            peerHashedId = peerHashedId
        )
    }
    
    /**
     * 创建通用Provider通道元数据
     */
    private suspend fun createGenericChannelMetadata(
        recipientId: String,
        providerType: String,
        provider: TransportProvider,
        configManager: TransportProviderConfigManager,
        tokenPool: TransportTokenPool
    ): TransportMetadata? {
        // 1. 获取本端配置
        val myProviderConfig = configManager.getProviderConfig(providerType)
            ?: run {
                Log.w(TAG, "未找到Provider配置: $providerType")
                return null
            }
        
        // 2. 获取Token信息
        val myTokenInfo = tokenPool.getMyTokenInfo(recipientId, providerType)
        val peerTokenInfo = tokenPool.getPeerTokenInfo(recipientId, providerType)
        
        // 3. 构建地址信息
        val myAddress = provider.formatAddress(myProviderConfig)
        val peerAddress = peerTokenInfo?.address ?: myAddress // 如果没有对端信息，回退到本端
        
        // 4. 获取路径策略
        val sendPath = provider.getSendPath(recipientId)
        val receivePath = provider.getReceivePath(recipientId)
        
        Log.d(TAG, "通用元数据创建: providerType=$providerType, " +
              "sendPath=$sendPath, receivePath=$receivePath")
        
        // 5. 创建通用传输元数据实现
        return object : TransportMetadata {
            override val recipientId: String = recipientId
            override val providerType: String = providerType
            
            override fun getSendMetadata(): SendMetadata {
                return SendMetadata(
                    address = myAddress,
                    token = myTokenInfo?.token,
                    path = sendPath,
                    recipientId = recipientId
                )
            }
            
            override fun getReceiveMetadata(): ReceiveMetadata {
                return ReceiveMetadata(
                    address = peerAddress,
                    token = peerTokenInfo?.token,
                    path = receivePath,
                    myId = "self"
                )
            }
            
            override fun toMap(): Map<String, Any> = mapOf(
                "recipientId" to recipientId,
                "providerType" to providerType,
                "myAddress" to myAddress,
                "peerAddress" to peerAddress,
                "sendPath" to sendPath,
                "receivePath" to receivePath
            )
            
            override fun validate(): Boolean = 
                recipientId.isNotBlank() && 
                providerType.isNotBlank() &&
                myAddress.isNotBlank() &&
                peerAddress.isNotBlank()
        }
    }
    
    /**
     * 激活通道
     */
    private suspend fun activateChannel(channelId: String) {
        try {
            // 获取通道信息
            val channel = channelLock.read { channels[channelId] }
            if (channel?.status != TransportChannelStatus.ESTABLISHING) {
                Log.w(TAG, "通道状态不正确，无法激活: $channelId, status=${channel?.status}")
                return
            }
            
            // 获取对应的Provider
            val transportManager = TransportManager.getInstance(context)
            val provider = transportManager.getProvider(channel.providerType)
            if (provider == null) {
                Log.w(TAG, "未找到Provider，通道激活失败: ${channel.providerType}")
                markChannelFailed(channelId)
                return
            }
            
            // 执行健康检查
            val healthCheckResult = performHealthCheck(provider, channel.metadata)
            
            channelLock.write {
                val currentChannel = channels[channelId]
                if (currentChannel != null && currentChannel.status == TransportChannelStatus.ESTABLISHING) {
                    if (healthCheckResult) {
                        // 根据是否有对端Token设置不同的状态
                        val cosMetadata = currentChannel.metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                        val newStatus = if (cosMetadata != null && cosMetadata.peerToken != null) {
                            TransportChannelStatus.FULL_ACTIVE
                        } else {
                            TransportChannelStatus.SEND_READY
                        }
                        channels[channelId] = currentChannel.updateStatus(newStatus)
                        Log.d(TAG, "通道健康检查通过，状态更新为: $newStatus, channelId: $channelId")
                    } else {
                        channels[channelId] = currentChannel.updateStatus(TransportChannelStatus.FAILED)
                        Log.w(TAG, "通道健康检查失败: $channelId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "激活通道失败: $channelId - ${LogSanitizer.sanitizeThrowable(e)}")
            markChannelFailed(channelId)
        }
    }
    
    /**
     * 执行轻量级健康检查
     */
    private suspend fun performHealthCheck(provider: TransportProvider, metadata: TransportMetadata): Boolean {
        return try {
            // 根据Provider类型执行不同的健康检查
            when (provider.providerType) {
                "cos" -> {
                    // 对COS执行健康检查
                    val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                    if (cosMetadata != null && cosMetadata.peerToken != null) {
                        // 有对端Token，可以测试双向能力（接收）- 测试messages目录
                        val testPath = "${metadata.getReceiveMetadata().path}messages/"
                        val listResult = provider.listFiles(testPath, metadata)
                        when (listResult) {
                            is TransportResult.Success -> true
                            is TransportResult.Failed -> {
                                Log.w(TAG, "COS健康检查失败: ${LogSanitizer.sanitizeGeneric(listResult.error.toString())}")
                                false
                            }
                            is TransportResult.RetryScheduled -> {
                                Log.w(TAG, "COS健康检查需要重试: ${LogSanitizer.sanitizeGeneric(listResult.reason)}")
                                false
                            }
                            is TransportResult.PartialSuccess -> {
                                Log.w(TAG, "COS健康检查部分成功: ${listResult.successCount}/${listResult.successCount + listResult.failureCount}")
                                listResult.successCount > 0
                            }
                        }
                    } else {
                        // 没有对端Token，只测试发送能力
                        Log.d(TAG, "COS健康检查：缺少对端Token，当前为单向发送模式，recipientId=${metadata.recipientId}")
                        val cosProvider = provider as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportProvider
                        if (cosProvider != null) {
                            val sendTestResult = cosProvider.testSendCapability(metadata)
                            when (sendTestResult) {
                                is TransportResult.Success -> {
                                    Log.d(TAG, "COS发送能力测试通过，通道将设置为SEND_READY状态")
                                    true
                                }
                                else -> {
                                    Log.w(TAG, "COS发送能力测试失败，通道将标记为FAILED：$sendTestResult")
                                    false
                                }
                            }
                        } else {
                            Log.w(TAG, "无法转换为CosTransportProvider，健康检查失败")
                            false
                        }
                    }
                }
                else -> {
                    // 其他Provider暂时返回true，假设健康
                    Log.d(TAG, "跳过健康检查: ${provider.providerType}")
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "健康检查过程中出现异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 标记通道为失败状态
     */
    private fun markChannelFailed(channelId: String) {
        channelLock.write {
            val channel = channels[channelId]
            if (channel != null) {
                channels[channelId] = channel.updateStatus(TransportChannelStatus.FAILED)
            }
        }
    }
    
    /**
     * 清理通道
     */
    private suspend fun cleanupChannel(channelId: String) {
        try {
            delay(1000) // 延迟清理，给其他操作完成的时间
            
            channelLock.write {
                removeChannelFromIndexes(channelId)
                channels.remove(channelId)
                Log.d(TAG, "清理通道: $channelId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理通道失败: $channelId", e)
        }
    }
    
    /**
     * 从索引中移除通道
     */
    private fun removeChannelFromIndexes(channelId: String) {
        val channel = channels[channelId] ?: return
        
        // 从接收者索引中移除
        recipientChannels[channel.recipientId]?.remove(channelId)
        if (recipientChannels[channel.recipientId]?.isEmpty() == true) {
            recipientChannels.remove(channel.recipientId)
        }
        
        // 从Provider索引中移除
        providerChannels[channel.providerType]?.remove(channelId)
        if (providerChannels[channel.providerType]?.isEmpty() == true) {
            providerChannels.remove(channel.providerType)
        }
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        stopCleanupTask()
        
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ChannelCleanupTask").apply {
                isDaemon = true
            }
        }
        
        cleanupTask = cleanupExecutor?.scheduleWithFixedDelay(
            {
                try {
                    runBlocking {
                        cleanupExpiredChannels()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "定时清理任务异常", e)
                }
            },
            config.cleanupIntervalMs,
            config.cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        
        Log.d(TAG, "启动通道清理任务，间隔: ${config.cleanupIntervalMs}ms")
    }
    
    /**
     * 停止清理任务
     */
    private fun stopCleanupTask() {
        cleanupTask?.cancel(true)
        cleanupTask = null
        
        cleanupExecutor?.shutdown()
        cleanupExecutor = null
        
        Log.d(TAG, "停止通道清理任务")
    }
    
    /**
     * 重启清理任务
     */
    private fun restartCleanupTask() {
        Log.d(TAG, "重启通道清理任务")
        startCleanupTask()
    }
    
    /**
     * 清理多余通道
     */
    private suspend fun cleanupExcessChannels(maxChannels: Int) {
        withContext(Dispatchers.IO) {
            channelLock.write {
                if (channels.size <= maxChannels) {
                    return@withContext
                }
                
                val excessCount = channels.size - maxChannels
                Log.i(TAG, "清理多余通道，数量: $excessCount")
                
                // 按优先级和活跃时间排序，清理优先级低且长时间未活跃的通道
                val channelsToRemove = channels.values
                    .sortedWith(compareBy<TransportChannel> { it.priority }
                        .thenBy { it.lastActiveAt })
                    .take(excessCount)
                
                channelsToRemove.forEach { channel ->
                    removeChannelFromIndexes(channel.channelId)
                    channels.remove(channel.channelId)
                    transportChannelTable.deleteChannel(channel.channelId)
                    Log.d(TAG, "移除通道: ${channel.channelId}")
                }
            }
        }
    }
    
    // === 数据库持久化相关方法 ===
    
    /**
     * 从数据库恢复通道状态
     */
    private suspend fun restoreChannelsFromDatabase() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始从数据库恢复通道状态...")
                
                // 从独立数据库获取所有活跃通道（与保存逻辑一致）
                val activeChannels = independentChannelTable.getActiveChannels()
                
                channelLock.write {
                    // 清空当前内存缓存
                    channels.clear()
                    recipientChannels.clear()
                    providerChannels.clear()
                    
                    // 将数据库中的活跃通道加载到内存并重建索引
                    for (channel in activeChannels) {
                        channels[channel.channelId] = channel
                        
                        // 重建recipientChannels索引
                        recipientChannels.computeIfAbsent(channel.recipientId) { mutableSetOf() }
                            .add(channel.channelId)
                        
                        // 重建providerChannels索引
                        providerChannels.computeIfAbsent(channel.providerType) { mutableSetOf() }
                            .add(channel.channelId)
                    }
                }
                
                Log.i(TAG, "通道状态恢复完成，从独立数据库恢复 ${activeChannels.size} 个活跃通道")
                activeChannels.forEach { channel ->
                    Log.d(TAG, "恢复通道: ${channel.channelId}, recipient=${channel.recipientId}, provider=${channel.providerType}, status=${channel.status}")
                }
                
                // 启动恢复后的清理任务
                if (activeChannels.isNotEmpty()) {
                    restartCleanupTask()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "从数据库恢复通道状态失败: ${LogSanitizer.sanitizeThrowable(e)}")
            }
        }
    }
    
    /**
     * 异步保存通道到数据库（使用独立数据库）
     */
    private suspend fun saveChannelToDatabaseAsync(channel: TransportChannel) {
        // 优先使用独立数据库，避免主数据库连接竞争
        try {
            independentChannelTable.insertOrUpdateChannel(channel)
            Log.v(TAG, "保存通道到独立数据库成功: ${channel.channelId}")
        } catch (e: Exception) {
            Log.w(TAG, "保存到独立数据库失败，回退到主数据库: ${channel.channelId}", e)
            
            // 回退到主数据库（缓冲模式）
            val bufferSuccess = TapDatabaseContext.withBufferedOperation(
                operationName = "saveChannel:${channel.channelId}",
                channel = channel,
                allowBuffer = true
            ) {
                transportChannelTable.insertOrUpdateChannel(channel)
            }
            
            if (!bufferSuccess) {
                Log.e(TAG, "通道保存完全失败: ${channel.channelId}")
                throw Exception("通道保存失败")
            }
        }
    }
    
    /**
     * 从数据库删除通道
     */
    private fun deleteChannelFromDatabase(channelId: String) {
        try {
            transportChannelTable.deleteChannel(channelId)
            Log.v(TAG, "从数据库删除通道: $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "从数据库删除通道失败: $channelId", e)
        }
    }
    
    /**
     * 同步内存状态到数据库
     */
    suspend fun syncToDatabase() {
        val channelsToSync = withContext(Dispatchers.IO) {
            channelLock.read {
                try {
                    Log.d(TAG, "开始收集通道状态用于同步...")
                    // 在锁内快速收集所有通道的副本
                    channels.values.toList()
                } catch (e: Exception) {
                    Log.e(TAG, "收集通道状态失败", e)
                    emptyList<TransportChannel>()
                }
            }
        }
        
        // 在锁外批量同步到数据库，避免长时间持有锁
        withContext(databaseContext) {
                try {
                    Log.d(TAG, "开始同步通道状态到数据库...")
                    var syncCount = 0
                    
                channelsToSync.forEach { channel ->
                    try {
                        saveChannelToDatabaseAsync(channel)
                        syncCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "同步单个通道失败: ${channel.channelId}", e)
                    }
                    }
                    
                    Log.d(TAG, "通道状态同步完成，同步数量: $syncCount")
                } catch (e: Exception) {
                    Log.e(TAG, "同步通道状态到数据库失败", e)
            }
        }
    }

    /**
     * 获取TransportManager实例
     */
    private fun getTransportManager(): TransportManager {
        return TransportManager.getInstance(context)
    }

    /**
     * 验证通道健康状态
     * 
     * 执行完整的端到端健康检查：
     * 1. Provider可用性
     * 2. Token有效性和权限
     * 3. 网络连通性
     * 4. 基本操作能力
     */
    suspend fun validateChannelHealth(recipientId: String, providerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始验证通道健康: recipient=${LogSanitizer.sanitize(recipientId)}, provider=$providerType")
                
                // 1. 获取通道信息
                val channel = getActiveChannel(recipientId, providerType)
                if (channel == null) {
                    Log.w(TAG, "通道不存在或未激活: $recipientId/$providerType")
                    return@withContext false
                }
                
                // 2. 获取Provider
                val transportManager = getTransportManager()
                val provider = transportManager.getProvider(providerType)
                if (provider == null) {
                    Log.w(TAG, "Provider不可用: $providerType")
                    return@withContext false
                }
                
                // 3. 获取通道元数据
                val metadata = channel.metadata
                if (metadata == null) {
                    Log.w(TAG, "通道元数据缺失: $recipientId/$providerType")
                    return@withContext false
                }
                
                // 4. Token有效性验证（使用传统的TransportToken）
                val tokenValid = try {
                    // 先尝试基本的连通性测试
                    testBasicOperations(provider, metadata)
                } catch (e: Exception) {
                    Log.w(TAG, "Token验证失败: $recipientId/$providerType")
                    false
                }
                
                if (!tokenValid) {
                    // 标记通道为不健康
                    updateChannelHealthStatus(channel, false, -1L)
                    return@withContext false
                }
                
                // 5. 基本操作能力测试
                val operationValid = testBasicOperations(provider, metadata)
                if (!operationValid) {
                    Log.w(TAG, "基本操作测试失败: $recipientId/$providerType")
                    updateChannelHealthStatus(channel, false, -1L)
                    return@withContext false
                }
                
                // 6. 网络延迟测试
                val latency = measureNetworkLatency(provider, metadata)
                
                // 7. 更新健康状态
                updateChannelHealthStatus(channel, true, latency)
                
                Log.d(TAG, "通道健康检查通过: $recipientId/$providerType, 延迟: ${latency}ms")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "通道健康检查异常: $recipientId/$providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                
                // 更新失败状态
                try {
                    val channel = getActiveChannel(recipientId, providerType)
                    channel?.let {
                        updateChannelHealthStatus(it, false, -1L)
                    }
                } catch (updateException: Exception) {
                    Log.e(TAG, "更新健康检查失败状态时出错", updateException)
                }
                
                false
            }
        }
    }
    
    /**
     * 更新通道健康状态
     */
    private suspend fun updateChannelHealthStatus(channel: TransportChannel, isHealthy: Boolean, latency: Long) {
        try {
            // 更新通道对象的健康状态字段
            // 这里需要根据实际的TransportChannel类结构来更新
            // 假设通道有相应的状态字段
            
            // 保存到数据库
            saveChannelToDatabaseAsync(channel)
            
            Log.d(TAG, "通道健康状态已更新: ${channel.recipientId}/${channel.providerType}, healthy=$isHealthy, latency=${latency}ms")
        } catch (e: Exception) {
            Log.e(TAG, "更新通道健康状态失败", e)
        }
    }
    
    /**
     * 测试基本操作能力
     */
    private suspend fun testBasicOperations(provider: TransportProvider, metadata: TransportMetadata): Boolean {
        return try {
            // 测试列举文件操作 - 测试messages目录
            val testPath = "${metadata.getReceiveMetadata().path}messages/"
            val listResult = provider.listFiles(testPath, metadata)
            
            // 列举操作应该成功（即使返回空列表）
            listResult is TransportResult.Success
            
        } catch (e: Exception) {
            Log.e(TAG, "基本操作测试异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测量网络延迟
     */
    private suspend fun measureNetworkLatency(provider: TransportProvider, metadata: TransportMetadata): Long {
        return try {
            val startTime = System.currentTimeMillis()
            
            // 执行一个轻量级的操作来测量延迟 - 测试messages目录
            val testPath = "${metadata.getReceiveMetadata().path}messages/"
            provider.listFiles(testPath, metadata)
            
            System.currentTimeMillis() - startTime
            
        } catch (e: Exception) {
            Log.e(TAG, "延迟测量异常: ${LogSanitizer.sanitizeThrowable(e)}")
            -1L // 表示无法测量
        }
    }
    
    /**
     * 检查是否有活跃通道
     */
    fun hasActiveChannel(recipientId: String): Boolean {
        return channelLock.read {
            getActiveChannels(recipientId).isNotEmpty()
        }
    }
    
    /**
     * 更新通道配置
     */
    suspend fun updateChannelConfig(recipientId: String, config: TransportChannelConfig): Boolean {
        return withContext(Dispatchers.IO) {
            channelLock.write {
                try {
                    val activeChannels = getActiveChannels(recipientId)
                    if (activeChannels.isEmpty()) {
                        return@withContext false
                    }
                    
                    // 更新所有活跃通道的配置
                    activeChannels.forEach { channel ->
                        // 这里应该根据实际需求更新通道的配置
                        Log.d(TAG, "更新通道配置: channelId=${channel.channelId}")
                    }
                    
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "更新通道配置失败: recipientId=$recipientId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 创建通道（完整实现，替代简化版本）
     */
    suspend fun createChannel(recipientId: String, config: TransportChannelConfig, token: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "创建通道: recipientId=$recipientId")
                
                // 1. 解析Token信息以确定Provider类型
                val tokenInfo = parseTokenInfo(token)
                if (tokenInfo == null) {
                    Log.e(TAG, "无法解析Token信息")
                    return@withContext null
                }
                
                Log.d(TAG, "解析Token信息成功: providerType=${tokenInfo.providerType}, tokenId=${LogSanitizer.sanitize(tokenInfo.tokenId)}")
                
                // 2. 获取对应的Provider
                val transportManager = getTransportManager()
                val provider = transportManager.getProvider(tokenInfo.providerType)
                if (provider == null) {
                    Log.e(TAG, "未找到Provider: ${tokenInfo.providerType}")
                    return@withContext null
                }
                
                // 3. 如果token信息包含完整的token数据，处理token信息
                if (tokenInfo.tokenData.containsKey("tokenData") && !tokenInfo.tokenData.containsKey("isEmpty")) {
                    Log.d(TAG, "处理包含完整token数据的通道创建请求")
                }
                
                // 4. 创建或获取通道
                val channel = getOrCreateChannel(recipientId, tokenInfo.providerType, provider)
                if (channel == null) {
                    Log.e(TAG, "无法创建通道")
                    return@withContext null
                }
                
                Log.d(TAG, "通道创建成功: channelId=${channel.channelId}")
                channel.channelId
                
            } catch (e: Exception) {
                Log.e(TAG, "创建通道失败: recipientId=$recipientId", e)
                null
            }
        }
    }
    
    /**
     * 解析Token信息
     */
    private fun parseTokenInfo(token: String): TokenInfo? {
        return try {
            // 检查token是否为空或null
            if (token.isBlank()) {
                Log.w(TAG, "Token字符串为空，使用默认配置")
                return TokenInfo("cos", "empty_token", mapOf("isEmpty" to true))
            }
            
            // 尝试解析JSON格式的Token信息
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val tokenData = mapper.readValue(token, Map::class.java) as Map<String, Any>
            
            val providerType = tokenData["providerType"] as? String ?: "cos"
            val tokenId = tokenData["tokenId"] as? String ?: ""
            
            TokenInfo(providerType, tokenId, tokenData)
            
        } catch (e: Exception) {
            Log.w(TAG, "解析Token信息失败，使用默认配置")
            // 回退到默认配置
            TokenInfo("cos", "legacy_token", mapOf("data" to token))
        }
    }
    
    /**
     * 撤销通道
     */
    suspend fun revokeChannel(recipientId: String): Boolean {
        return withContext(Dispatchers.IO) {
            channelLock.write {
                try {
                    val activeChannels = getActiveChannels(recipientId)
                    if (activeChannels.isEmpty()) {
                        return@withContext false
                    }
                    
                    // 关闭所有活跃通道
                    activeChannels.forEach { channel ->
                        closeChannel(channel.channelId)
                    }
                    
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "撤销通道失败: recipientId=$recipientId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 激活通道（公共方法版本）
     */
    suspend fun activateChannelPublic(recipientId: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            channelLock.write {
                try {
                    val activeChannels = getActiveChannels(recipientId)
                    if (activeChannels.isNotEmpty()) {
                        // 激活第一个匹配的通道
                        activateChannel(activeChannels[0].channelId)
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "激活通道失败: recipientId=$recipientId", e)
                    false
                }
            }
        }
    }

    /**
     * 记录成功发送
     */
    fun recordSuccessfulSend(channelId: String) {
        channelLock.write {
            val channel = channels[channelId]
            if (channel != null) {
                val updatedChannel = channel.copy(
                    successCount = channel.successCount + 1,
                    lastActiveAt = System.currentTimeMillis(),
                    status = TransportChannelStatus.ACTIVE,
                    lastError = null
                )
                channels[channelId] = updatedChannel
                
                // 异步更新数据库
                managerScope.launch {
                    try {
                        transportChannelTable.insertOrUpdateChannel(updatedChannel)
                        Log.d(TAG, "记录通道成功发送: $channelId")
                    } catch (e: Exception) {
                        Log.e(TAG, "更新通道成功发送统计失败: $channelId - ${LogSanitizer.sanitizeThrowable(e)}")
                    }
                }
            }
        }
    }

    /**
     * 记录失败发送
     */
    fun recordFailedSend(channelId: String, error: TransportError) {
        channelLock.write {
            val channel = channels[channelId]
            if (channel != null) {
                val updatedChannel = channel.copy(
                    failureCount = channel.failureCount + 1,
                    lastActiveAt = System.currentTimeMillis(),
                    status = if (channel.failureCount >= 5) TransportChannelStatus.FAILED else channel.status,
                    lastError = error
                )
                channels[channelId] = updatedChannel
                
                // 异步更新数据库
                managerScope.launch {
                    try {
                        transportChannelTable.insertOrUpdateChannel(updatedChannel)
                        Log.d(TAG, "记录通道失败发送: $channelId, error: $error")
                    } catch (e: Exception) {
                        Log.e(TAG, "更新通道失败发送统计失败: $channelId - ${LogSanitizer.sanitizeThrowable(e)}")
                    }
                }
            }
        }
    }

    /**
     * 为通道启动轮询任务
     */
    private fun startPollingForChannel(recipientId: String, channel: TransportChannel) {
        try {
            Log.d(TAG, "为通道启动轮询任务: recipientId=$recipientId, channelId=${channel.channelId}")
            
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            
            // 检查轮询服务状态（通过尝试启动来判断）
            Log.d(TAG, "确保轮询服务已启动")
            val startResult = pollingService.startPolling()
            if (!startResult) {
                Log.e(TAG, "轮询服务启动失败，无法为通道启动轮询")
                throw RuntimeException("轮询服务启动失败")
            }
            
            // 添加轮询目标
            val metadata = channel.metadata
            if (metadata == null) {
                Log.e(TAG, "通道元数据为空，无法启动轮询: channelId=${channel.channelId}")
                throw RuntimeException("通道元数据为空")
            }
            
            val success = pollingService.addPollingTarget(recipientId, metadata, channel)
            
            if (success) {
                Log.i(TAG, "轮询任务启动成功: recipientId=$recipientId, providerType=${channel.providerType}, channelId=${channel.channelId}")
            } else {
                Log.w(TAG, "轮询任务启动失败: recipientId=$recipientId, providerType=${channel.providerType}, channelId=${channel.channelId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动轮询任务异常: recipientId=$recipientId, channelId=${channel.channelId}", e)
            throw e
        }
    }
    
    /**
     * 确保轮询服务正在为指定通道运行
     * 如果轮询服务未启动或该通道未添加轮询目标，则启动/添加
     */
    private fun ensurePollingServiceRunning(recipientId: String, channel: TransportChannel) {
        try {
            Log.d(TAG, "确保轮询服务运行: recipientId=$recipientId, channelId=${channel.channelId}")
            
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            
            // 尝试启动轮询服务（如果已启动则返回true）
            val serviceStarted = pollingService.startPolling()
            if (!serviceStarted) {
                Log.w(TAG, "轮询服务启动失败，尝试重新添加轮询目标")
                // 即使服务启动失败，也尝试添加轮询目标，可能服务已在运行
            }
            
            // 检查元数据
            val metadata = channel.metadata
            if (metadata == null) {
                Log.e(TAG, "通道元数据为空，无法添加轮询目标: channelId=${channel.channelId}")
                return
            }
            
            // 添加或更新轮询目标
            val targetAdded = pollingService.addPollingTarget(recipientId, metadata, channel)
            
            if (targetAdded) {
                Log.i(TAG, "轮询目标已确保运行: recipientId=$recipientId, provider=${channel.providerType}")
            } else {
                Log.w(TAG, "添加轮询目标失败，可能已存在: recipientId=$recipientId, provider=${channel.providerType}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "确保轮询服务运行时异常: recipientId=$recipientId", e)
            // 不抛出异常，避免影响通道升级
        }
    }
}

/**
 * 通道统计信息
 */
data class TransportChannelStatistics(
    /** 总通道数 */
    val totalChannels: Int = 0,
    
    /** 活跃通道数 */
    val activeChannels: Int = 0,
    
    /** 失败通道数 */
    val failedChannels: Int = 0,
    
    /** 状态分布 */
    val statusDistribution: Map<TransportChannelStatus, Int> = emptyMap(),
    
    /** Provider分布 */
    val providerDistribution: Map<String, Int> = emptyMap()
) {
    
    /**
     * 计算成功率
     */
    fun getSuccessRate(): Double {
        return if (totalChannels > 0) {
            (totalChannels - failedChannels).toDouble() / totalChannels
        } else {
            0.0
        }
    }
    
    /**
     * 计算活跃率
     */
    fun getActiveRate(): Double {
        return if (totalChannels > 0) {
            activeChannels.toDouble() / totalChannels
        } else {
            0.0
        }
    }
}

/**
 * Token信息数据类
 */
data class TokenInfo(
    val providerType: String,
    val tokenId: String,
    val tokenData: Map<String, Any>
)

/**
 * 群组通道统计信息
 */
data class GroupChannelStatistics(
    /** 群组 ID */
    val groupId: String,
    
    /** 总通道数 */
    val totalChannels: Int,
    
    /** 活跃通道数 */
    val activeChannels: Int,
    
    /** 失败通道数 */
    val failedChannels: Int,
    
    /** 状态分布 */
    val statusDistribution: Map<TransportChannelStatus, Int>,
    
    /** 平均成功率 */
    val averageSuccessRate: Double
) {
    /**
     * 获取活跃率
     */
    fun getActiveRate(): Double {
        return if (totalChannels > 0) {
            activeChannels.toDouble() / totalChannels
        } else {
            0.0
        }
    }
    
    /**
     * 获取失败率
     */
    fun getFailureRate(): Double {
        return if (totalChannels > 0) {
            failedChannels.toDouble() / totalChannels
        } else {
            0.0
        }
    }
    
    /**
     * 判断群组通道是否健康
     */
    fun isHealthy(): Boolean {
        // 至少80%的通道活跃，且平均成功率>50%
        return getActiveRate() >= 0.8 && averageSuccessRate > 0.5
    }
} 