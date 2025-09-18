package org.thoughtcrime.securesms.tap

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * 传输通道管理器
 * 
 * 负责传输通道的生命周期管理，包括通道建立、维护、清理和状态监控。
 * 提供通道的创建、获取、更新和统计功能。
 */
class TransportChannelManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportChannelManager"
        
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
    
    // 通道存储和索引
    private val channels = ConcurrentHashMap<String, TransportChannel>()
    private val recipientChannels = ConcurrentHashMap<String, MutableSet<String>>()
    private val providerChannels = ConcurrentHashMap<String, MutableSet<String>>()
    
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
        return withContext(Dispatchers.IO) {
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
                    
                    // 存储通道
                    channels[channelId] = channel
                    
                    // 更新索引
                    recipientChannels.computeIfAbsent(recipientId) { mutableSetOf() }.add(channelId)
                    providerChannels.computeIfAbsent(providerType) { mutableSetOf() }.add(channelId)
                    
                    Log.d(TAG, "建立通道: $channelId, 接收者: $recipientId, 提供者: $providerType")
                    
                    // 异步激活通道
                    managerScope.launch {
                        activateChannel(channelId)
                    }
                    
                    channel
                    
                } catch (e: Exception) {
                    Log.e(TAG, "建立通道失败", e)
                    null
                }
            }
        }
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
            val channelIds = recipientChannels[recipientId] ?: return emptyList()
            // 先获取所有通道快照，避免在读锁内调用可能修改状态的方法
            val channelSnapshots = channelIds.mapNotNull { channelId ->
                channels[channelId]
            }
            
            // 在读锁外进行状态检查和排序
            return channelSnapshots.filter { channel ->
                try {
                    channel.isActive()
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
        withContext(Dispatchers.IO) {
            channelLock.write {
                val channel = channels[channelId] ?: return@withContext
                val updatedChannel = channel.recordSuccess().copy(
                    lastActiveAt = System.currentTimeMillis()
                )
                channels[channelId] = updatedChannel
                
                // 如果通道之前不是活跃状态，激活它
                if (!channel.isActive()) {
                    channels[channelId] = updatedChannel.updateStatus(TransportChannelStatus.ACTIVE)
                }
                
                Log.d(TAG, "通道操作成功: $channelId")
            }
        }
    }
    
    /**
     * 更新通道失败
     */
    suspend fun updateChannelFailure(channelId: String, error: TransportError) {
        withContext(Dispatchers.IO) {
            channelLock.write {
                val channel = channels[channelId] ?: return@withContext
                val updatedChannel = channel.recordFailure(error)
                channels[channelId] = updatedChannel
                
                Log.w(TAG, "通道操作失败: $channelId, 错误: $error, 失败次数: ${updatedChannel.failureCount}")
                
                // 如果失败次数过多，标记通道为失败状态
                if (updatedChannel.failureCount >= config.maxFailureCount) {
                    channels[channelId] = updatedChannel.updateStatus(TransportChannelStatus.FAILED)
                    Log.w(TAG, "通道失败次数过多，标记为失败: $channelId")
                }
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
            // 这里需要根据具体的Provider类型创建相应的元数据
            // 暂时返回一个基础的实现，具体实现需要在Provider层完成
            when (providerType) {
                "cos" -> {
                    // 从Token池获取访问Token
                    val tokenPool = TransportTokenPool.getInstance(context)
                    val token = tokenPool.getValidReceivedToken(recipientId, providerType)
                    
                    // 创建COS元数据（这里需要实际的COS配置信息）
                    // 暂时返回null，实际实现需要配合COS Provider
                    null
                }
                else -> {
                    // 其他Provider的元数据创建
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建通道元数据失败: $providerType", e)
            null
        }
    }
    
    /**
     * 激活通道
     */
    private suspend fun activateChannel(channelId: String) {
        try {
            delay(100) // 短暂延迟，模拟建立过程
            
            channelLock.write {
                val channel = channels[channelId]
                if (channel != null && channel.status == TransportChannelStatus.ESTABLISHING) {
                    channels[channelId] = channel.updateStatus(TransportChannelStatus.ACTIVE)
                    Log.d(TAG, "通道激活成功: $channelId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "激活通道失败: $channelId", e)
            
            channelLock.write {
                val channel = channels[channelId]
                if (channel != null) {
                    channels[channelId] = channel.updateStatus(TransportChannelStatus.FAILED)
                }
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
                    Log.d(TAG, "移除通道: ${channel.channelId}")
                }
            }
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