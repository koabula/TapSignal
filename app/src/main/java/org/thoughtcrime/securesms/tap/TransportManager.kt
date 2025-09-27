package org.thoughtcrime.securesms.tap

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.factory.DefaultTransportProviderFactory
import org.thoughtcrime.securesms.tap.FileInfo

/**
 * 传输管理器 - 核心传输层管理组件
 * 
 * 负责传输提供者的注册、管理和消息路由，是整个Tap架构的核心。
 * 采用线程安全的单例模式，提供全局统一的传输服务访问点。
 */
class TransportManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportManager"
        
        // 使用lazy委托实现线程安全的单例
        @Volatile
        private var INSTANCE: TransportManager? = null
        
        /**
         * 获取TransportManager单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportManager(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建TransportManager实例: ${it.hashCode()}")
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
                Log.d(TAG, "重置TransportManager实例")
            }
        }
    }
    
    // 核心组件
    private val transportConfig = TransportProviderConfigManager.getInstance(context)
    private val channelManager = lazy { TransportChannelManager.getInstance(context) }
    private val tokenPool = lazy { TransportTokenPool.getInstance(context) }
    private val routingManager = TransportRoutingManager.getInstance(context)
    
    // 新的子管理器
    private val providerManager = TransportProviderManager.getInstance(context)
    private val messageRouter = TransportMessageRouter.getInstance(context)
    
    // 配置和状态
    private var currentConfig: TransportConfig = TransportConfig()
    private var routingPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT
    private var isInitialized: Boolean = false
    private val initializationLock = ReentrantReadWriteLock()
    
    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("TransportManager")
    )
    
    /**
     * 初始化传输管理器
     */
    suspend fun initialize(config: TransportConfig = TransportConfig()): Boolean {
        return withContext(Dispatchers.IO) {
            initializationLock.write {
                try {
                    if (isInitialized) {
                        Log.w(TAG, "传输管理器已经初始化")
                        return@withContext true
                    }
                    
                    Log.i(TAG, "初始化传输管理器...")
                    
                    // 验证配置
                    val validationResult = config.validate()
                    if (!validationResult.isValid && validationResult is TransportConfigValidationResult.Invalid) {
                        Log.e(TAG, "传输配置无效: ${validationResult.joinToString(", ")}")
                        return@withContext false
                    }
                    
                    currentConfig = config
                    
                    // 初始化子组件
                    channelManager.value.initialize(config.channelConfig)
                    tokenPool.value.initialize(config.tokenConfig)
                    routingPolicy = config.routingPolicy
                    
                    // 初始化路由管理器
                    val routingInitResult = routingManager.initialize(config.routingPolicy)
                    if (!routingInitResult) {
                        Log.e(TAG, "路由管理器初始化失败")
                        return@withContext false
                    }
                    
                    // 初始化Provider管理器（内部注册默认工厂并加载配置）
                    Log.d(TAG, "开始初始化Provider管理器...")
                    val providerInitResult = providerManager.initialize()
                    Log.d(TAG, "Provider管理器初始化结果: $providerInitResult")
                    
                    if (!providerInitResult) {
                        // 增强错误诊断
                        Log.e(TAG, "Provider管理器初始化失败 - 详细诊断:")
                        try {
                            val providerRegistry = org.thoughtcrime.securesms.tap.ProviderRegistry.getInstance(context)
                            val registeredProviderTypes = providerRegistry.getAvailableProviderTypes()
                            Log.e(TAG, "  - 已注册Provider数量: ${registeredProviderTypes.size}")
                            registeredProviderTypes.forEach { providerType ->
                                val registrar = providerRegistry.getProviderRegistrar(providerType)
                                Log.e(TAG, "    * $providerType: ${registrar?.javaClass?.simpleName ?: "未知"}")
                            }
                            
                            // 检查Provider配置
                            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                            val configuredProviders = configManager.getConfiguredProviders()
                            Log.e(TAG, "  - 已配置Provider数量: ${configuredProviders.size}")
                            configuredProviders.forEach { providerType ->
                                Log.e(TAG, "    * 配置的Provider: $providerType")
                            }
                            
                        } catch (diagnosisException: Exception) {
                            Log.e(TAG, "Provider诊断异常", diagnosisException)
                        }
                        
                        // 尝试强制重新初始化ProviderRegistry
                        Log.w(TAG, "尝试强制重新初始化ProviderRegistry...")
                        try {
                            val providerRegistry = org.thoughtcrime.securesms.tap.ProviderRegistry.getInstance(context)
                            // 使用ProviderRegistry的resetInstance方法
                            val resetMethod = org.thoughtcrime.securesms.tap.ProviderRegistry::class.java.getDeclaredMethod("resetInstance")
                            resetMethod.isAccessible = true
                            resetMethod.invoke(null)
                            
                            // 重新获取实例并初始化
                            val newProviderRegistry = org.thoughtcrime.securesms.tap.ProviderRegistry.getInstance(context)
                            newProviderRegistry.initialize()
                            
                            // 重新初始化
                            val retryResult = providerManager.initialize()
                            Log.i(TAG, "Provider管理器重新初始化结果: $retryResult")
                            
                            if (!retryResult) {
                                Log.e(TAG, "Provider管理器重新初始化仍然失败")
                                return@withContext false
                            }
                        } catch (retryException: Exception) {
                            Log.e(TAG, "Provider管理器重新初始化异常", retryException)
                            return@withContext false
                        }
                    }
                    
                    // 从持久化存储恢复启用的Provider配置
                    val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                    val persistedEnabledProviders = tapValues.getEnabledProviders()
                    
                    // 自动启用已注册的活跃Provider，确保检查和发送阶段的一致性
                    val activeProviders = providerManager.getActiveProviders()
                    if (activeProviders.isNotEmpty()) {
                        val activeProviderTypes = activeProviders.map { it.providerType }.toSet()
                        val finalEnabledProviders = persistedEnabledProviders + activeProviderTypes
                        
                        currentConfig = currentConfig.copy(enabledProviders = finalEnabledProviders)
                        
                        // 持久化最新的启用Provider列表
                        tapValues.setEnabledProviders(finalEnabledProviders)
                        
                        Log.i(TAG, "恢复启用Provider: ${persistedEnabledProviders.joinToString(", ")}")
                        Log.i(TAG, "自动启用活跃Provider: ${activeProviderTypes.joinToString(", ")}")
                        Log.i(TAG, "最终启用Provider: ${finalEnabledProviders.joinToString(", ")}")
                    } else if (persistedEnabledProviders.isNotEmpty()) {
                        // 只有持久化的Provider，没有活跃的Provider
                        currentConfig = currentConfig.copy(enabledProviders = persistedEnabledProviders)
                        Log.i(TAG, "恢复启用Provider（无活跃Provider）: ${persistedEnabledProviders.joinToString(", ")}")
                    }
                    
                    // 输出初始化后的provider状态
                    val availableProviders = getAvailableProviders()
                    val enabledProviders = getEnabledProviders()
                    Log.i(TAG, "传输管理器初始化完成，可用Provider数量: ${availableProviders.size}，已启用: ${enabledProviders.size}")
                    availableProviders.forEach { provider ->
                        Log.d(TAG, "可用Provider: ${provider.providerType} - ${provider.displayName}")
                    }
                    
                    isInitialized = true
                    Log.i(TAG, "传输管理器初始化完成")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "初始化传输管理器失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 获取传输提供者
     */
    fun getProvider(providerType: String): TransportProvider? {
        val normalizedProviderType = providerType.lowercase()
        
        // 首先从已注册的provider中获取
        var provider = providerManager.getProvider(normalizedProviderType)
        
        // 如果provider不存在且管理器已初始化，尝试按需创建
        if (provider == null && isInitialized()) {
            Log.d(TAG, "Provider不存在，尝试按需创建: $normalizedProviderType")
            provider = createProviderIfConfigured(normalizedProviderType)
            if (provider != null) {
                Log.i(TAG, "按需创建Provider成功: $normalizedProviderType")
            } else {
                Log.w(TAG, "按需创建Provider失败: $normalizedProviderType")
            }
        }
        
        return provider
    }
    
    /**
     * 检查传输管理器是否已初始化
     */
    fun isInitialized(): Boolean {
        return initializationLock.read {
            isInitialized
        }
    }
    
    /**
     * 获取所有可用提供者
     */
    fun getAvailableProviders(): List<TransportProvider> {
        return providerManager.getActiveProviders()
    }
    
    /**
     * 获取所有已启用的提供者
     */
    fun getEnabledProviders(): List<TransportProvider> {
        // 以ProviderManager的活跃Provider为准，同时检查当前配置启用状态
        val active = providerManager.getActiveProviders()
        Log.d(TAG, "获取启用Provider: 活跃Provider数量=${active.size}, 配置启用列表=${currentConfig.enabledProviders}")
        
        // 当未配置任何启用项时，默认启用所有活跃Provider，避免启用态与活跃态脱节
        if (currentConfig.enabledProviders.isEmpty()) {
            Log.d(TAG, "配置启用列表为空，自动启用所有活跃Provider: ${active.map { it.providerType }}")
            
            // 自动同步配置状态，避免重复检查
            if (active.isNotEmpty()) {
                val activeProviderTypes = active.map { it.providerType }.toSet()
                currentConfig = currentConfig.copy(enabledProviders = activeProviderTypes)
                
                // 持久化到存储
                try {
                    val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                    tapValues.setEnabledProviders(activeProviderTypes)
                    Log.d(TAG, "自动启用Provider配置已持久化: ${activeProviderTypes.joinToString(", ")}")
                } catch (e: Exception) {
                    Log.w(TAG, "自动启用Provider配置持久化失败", e)
                }
            }
            
            return active
        }
        
        val filtered = active.filter { provider -> currentConfig.isProviderEnabled(provider.providerType) }
        Log.d(TAG, "根据配置过滤Provider: 结果=${filtered.map { it.providerType }}")
        
        // 检查是否存在活跃但未启用的Provider，自动同步
        val activeProviderTypes = active.map { it.providerType }.toSet()
        val enabledProviderTypes = currentConfig.enabledProviders
        val missingEnabledTypes = activeProviderTypes - enabledProviderTypes
        
        if (missingEnabledTypes.isNotEmpty()) {
            Log.w(TAG, "发现活跃但未启用的Provider，自动添加到启用列表: ${missingEnabledTypes.joinToString(", ")}")
            val updatedEnabledTypes = enabledProviderTypes + missingEnabledTypes
            currentConfig = currentConfig.copy(enabledProviders = updatedEnabledTypes)
            
            // 持久化更新
            try {
                val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                tapValues.setEnabledProviders(updatedEnabledTypes)
                Log.i(TAG, "Provider启用状态已自动同步并持久化: ${updatedEnabledTypes.joinToString(", ")}")
            } catch (e: Exception) {
                Log.w(TAG, "Provider启用状态同步持久化失败", e)
            }
            
            // 重新过滤，返回所有活跃Provider
            return active
        }
        
        return filtered
    }
    
    /**
     * 发送消息（自动路由）
     */
    suspend fun sendMessage(recipientId: String, message: TransportMessage): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        false,
                        "传输管理器未初始化"
                    )
                }
                
                Log.d(TAG, "发送消息到: $recipientId, 消息类型: ${message.messageType}")
                
                // 通过路由器发送
                messageRouter.sendMessage(message, recipientId)
                
            } catch (e: Exception) {
                Log.e(TAG, "发送消息异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }
    
    /**
     * 轮询消息
     */
    suspend fun pollMessages(recipientId: String? = null): List<TransportMessage> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    Log.w(TAG, "传输管理器未初始化，跳过轮询")
                    return@withContext emptyList()
                }
                
                val messages = mutableListOf<TransportMessage>()
                val channels = if (recipientId != null) {
                    channelManager.value.getActiveChannels(recipientId)
                } else {
                    channelManager.value.getAllActiveChannels()
                }
                
                Log.d(TAG, "开始轮询消息，通道数: ${channels.size}")
                
                // 并发轮询所有活跃通道
                val pollingJobs = channels.map { channel ->
                    async {
                        pollChannelMessages(channel)
                    }
                }
                
                // 等待所有轮询任务完成
                pollingJobs.awaitAll().forEach { channelMessages ->
                    messages.addAll(channelMessages)
                }
                
                Log.d(TAG, "轮询完成，获得消息: ${messages.size}")
                messages
                
            } catch (e: Exception) {
                Log.e(TAG, "轮询消息异常", e)
                // 记录异常统计，用于监控和调试
                when (e) {
                    is SecurityException -> Log.w(TAG, "轮询权限问题，检查Token状态")
                    is java.net.SocketTimeoutException -> Log.d(TAG, "轮询网络超时，稍后重试")
                    is CancellationException -> Log.d(TAG, "轮询任务被取消")
                    else -> Log.w(TAG, "轮询遇到未知异常: ${e.javaClass.simpleName}")
                }
                emptyList()
            }
        }
    }
    
    /**
     * 获取传输统计信息
     */
    suspend fun getTransportStatistics(): TransportStatistics {
        return withContext(Dispatchers.IO) {
            try {
                val channelStats = channelManager.value.getChannelStatistics()
                val tokenStats = tokenPool.value.getTokenStatistics()
                val routingStats = routingManager.getRoutingStatistics()
                val providerStats = providerManager.getProviderStatistics()
                
                TransportStatistics(
                    totalProviders = providerStats.totalProviders,
                    enabledProviders = getEnabledProviders().size,
                    activeChannels = channelStats.activeChannels,
                    totalChannels = channelStats.totalChannels,
                    validTokens = tokenStats.validTokens,
                    totalTokens = tokenStats.totalTokens,
                    routingStats = routingStats
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取传输统计信息失败", e)
                TransportStatistics()
            }
        }
    }
    
    /**
     * 更新传输配置
     */
    suspend fun updateConfig(newConfig: TransportConfig): Boolean {
        return withContext(Dispatchers.IO) {
            // 先验证配置，避免在锁内进行复杂操作
            val validationResult = newConfig.validate()
            if (!validationResult.isValid && validationResult is TransportConfigValidationResult.Invalid) {
                Log.e(TAG, "新配置无效: ${validationResult.joinToString(", ")}")
                return@withContext false
            }
            
            val oldConfig = initializationLock.write {
                val old = currentConfig
                currentConfig = newConfig
                old
            }
            
            try {
                // 在锁外更新子组件配置，避免嵌套锁死锁
                val updateTasks = listOf(
                    async { channelManager.value.updateConfig(newConfig.channelConfig) },
                    async { tokenPool.value.updateConfig(newConfig.tokenConfig) }
                )
                
                // 更新路由策略
                routingPolicy = newConfig.routingPolicy
                routingManager.updateRoutingPolicy(newConfig.routingPolicy)
                
                updateTasks.awaitAll()
                
                // 处理Provider启用状态变化
                handleProviderStatusChange(oldConfig, newConfig)
                
                Log.i(TAG, "传输配置更新完成")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "更新传输配置失败，回滚配置", e)
                // 回滚配置
                initializationLock.write {
                    currentConfig = oldConfig
                }
                false
            }
        }
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始清理传输管理器资源")
                
                // 取消协程作用域
                managerScope.cancel()
                
                // 清理子组件
                channelManager.value.cleanup()
                tokenPool.value.cleanup()
                providerManager.cleanup()
                
                initializationLock.write {
                    isInitialized = false
                }
                
                Log.i(TAG, "传输管理器资源清理完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "清理传输管理器资源失败", e)
            }
        }
    }
    
    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): TransportConfig {
        initializationLock.read {
            return currentConfig.copy()
        }
    }
    
    /**
     * 路由消息到合适的传输提供者
     */
    suspend fun routeMessage(message: TransportMessage, recipientId: String): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        false,
                        "传输管理器未初始化"
                    )
                }
                
                // recipientId格式标准化和诊断
                Log.d(TAG, "原始recipientId: $recipientId")
                
                // 检查recipientId格式
                val isUUID = try {
                    java.util.UUID.fromString(recipientId)
                    true
                } catch (e: Exception) {
                    false
                }
                
                val isRecipientIdFormat = recipientId.startsWith("RecipientId::")
                Log.d(TAG, "recipientId格式检查: isUUID=$isUUID, isRecipientIdFormat=$isRecipientIdFormat")
                
                val normalizedRecipientId = when {
                    isUUID -> {
                        // 已经是UUID格式，直接使用
                        Log.d(TAG, "recipientId已是UUID格式，直接使用")
                        recipientId
                    }
                    isRecipientIdFormat -> {
                        // RecipientId格式，需要转换为ACI
                        try {
                            val recipientIdNumber = recipientId.substringAfter("RecipientId::").toLong()
                            val recipientObj = org.thoughtcrime.securesms.recipients.Recipient.resolved(
                                org.thoughtcrime.securesms.recipients.RecipientId.from(recipientIdNumber)
                            )
                            val aci = recipientObj.requireServiceId().toString()
                            Log.d(TAG, "RecipientId格式转换: $recipientId -> $aci")
                            aci
                        } catch (e: Exception) {
                            Log.e(TAG, "RecipientId格式转换失败: $recipientId", e)
                            recipientId
                        }
                    }
                    else -> {
                        // 其他格式，可能是数字ID，尝试转换
                        try {
                            val recipientIdNumber = recipientId.toLong()
                            val recipientObj = org.thoughtcrime.securesms.recipients.Recipient.resolved(
                                org.thoughtcrime.securesms.recipients.RecipientId.from(recipientIdNumber)
                            )
                            val aci = recipientObj.requireServiceId().toString()
                            Log.d(TAG, "数字ID格式转换: $recipientId -> $aci")
                            aci
                        } catch (e: Exception) {
                            Log.w(TAG, "无法识别的recipientId格式，直接使用: $recipientId")
                            recipientId
                        }
                    }
                }
                
                Log.d(TAG, "标准化后recipientId: $normalizedRecipientId")
                
                if (normalizedRecipientId != recipientId) {
                    Log.i(TAG, "recipientId已标准化: $recipientId -> $normalizedRecipientId")
                }
                
                Log.d(TAG, "开始消息路由诊断: messageId=${message.messageId}, 原始recipientId=$recipientId, 标准化recipientId=$normalizedRecipientId")
                
                // 详细的系统状态检查
                Log.d(TAG, "路由诊断 - 系统状态:")
                Log.d(TAG, "  - 初始化状态: $isInitialized")
                Log.d(TAG, "  - 配置启用Provider: ${currentConfig.enabledProviders}")
                Log.d(TAG, "  - ProviderManager状态: 已初始化")
                
                // Provider状态详细检查
                val activeProviders = providerManager.getActiveProviders()
                Log.d(TAG, "  - 活跃Provider数量: ${activeProviders.size}")
                activeProviders.forEach { provider ->
                    Log.d(TAG, "    * ${provider.providerType}: ${provider.displayName}")
                }
                
                // 通道状态检查（使用标准化的recipientId）
                val hasActiveChannel = channelManager.value.hasActiveChannel(normalizedRecipientId)
                Log.d(TAG, "  - 通道状态: hasActiveChannel=$hasActiveChannel (使用标准化recipientId)")
                
                // 获取可用Provider并记录详细信息
                var enabledProviders = getEnabledProviders()
                Log.d(TAG, "当前启用Provider列表: ${enabledProviders.map { "${it.providerType}(${it.javaClass.simpleName})" }}")
                
                // 如果没有启用的Provider，但有活跃的Provider，则自动启用活跃Provider
                if (enabledProviders.isEmpty()) {
                    val activeProviders = providerManager.getActiveProviders()
                    Log.w(TAG, "没有启用的Provider，但有 ${activeProviders.size} 个活跃Provider，尝试自动修复")
                    
                    if (activeProviders.isNotEmpty()) {
                        val activeProviderTypes = activeProviders.map { it.providerType }.toSet()
                        currentConfig = currentConfig.copy(enabledProviders = activeProviderTypes)
                        enabledProviders = getEnabledProviders()
                        
                        // 持久化自动修复的结果
                        val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                        tapValues.setEnabledProviders(activeProviderTypes)
                        
                        Log.i(TAG, "自动修复完成，已启用活跃Provider: ${activeProviderTypes.joinToString(", ")}")
                        Log.i(TAG, "自动修复诊断: 配置已持久化，重新获取启用Provider数量=${enabledProviders.size}")
                    } else {
                        Log.w(TAG, "自动修复失败: 没有活跃Provider可以启用")
                    }
                } else {
                    Log.d(TAG, "Provider状态正常: 启用Provider数量=${enabledProviders.size}")
                }
                
                // 使用路由管理器选择最佳提供者（使用标准化的recipientId）
                val bestProvider = routingManager.selectBestProvider(
                    recipientId = normalizedRecipientId,
                    message = message,
                    availableProviders = enabledProviders
                )
                
                if (bestProvider == null) {
                    // === 实时故障诊断 ===
                    Log.e(TAG, "=== selectBestProvider失败，开始实时诊断 ===")
                    Log.e(TAG, "诊断时间点: 路由选择失败时")
                    Log.e(TAG, "传入参数:")
                    Log.e(TAG, "  - recipientId: $normalizedRecipientId")
                    Log.e(TAG, "  - 原始recipientId: $recipientId")
                    Log.e(TAG, "  - messageId: ${message.messageId}")
                    Log.e(TAG, "  - enabledProviders数量: ${enabledProviders.size}")
                    
                    // 实时检查通道状态
                    val immediateChannels = channelManager.value.getActiveChannels(normalizedRecipientId)
                    Log.e(TAG, "实时通道检查:")
                    Log.e(TAG, "  - getActiveChannels()返回数量: ${immediateChannels.size}")
                    immediateChannels.forEach { channel ->
                        Log.e(TAG, "    * 通道: ${channel.channelId}")
                        Log.e(TAG, "      - 状态: ${channel.status}")
                        Log.e(TAG, "      - Provider: ${channel.providerType}")
                        Log.e(TAG, "      - recipientId: ${channel.recipientId}")
                        Log.e(TAG, "      - isActive(): ${channel.isActive()}")
                        Log.e(TAG, "      - metadata != null: ${channel.metadata != null}")
                    }
                    
                    // 检查是否是recipientId格式问题
                    if (normalizedRecipientId != recipientId) {
                        Log.e(TAG, "尝试用原始recipientId查询:")
                        val channelsWithOriginalId = channelManager.value.getActiveChannels(recipientId)
                        Log.e(TAG, "  - 原始ID查询结果: ${channelsWithOriginalId.size}个通道")
                    }
                    
                    // 检查所有活跃通道中是否有相关的
                    val allActiveChannels = channelManager.value.getAllActiveChannels()
                    val relatedChannels = allActiveChannels.filter { 
                        it.recipientId == normalizedRecipientId || it.recipientId == recipientId 
                    }
                    Log.e(TAG, "在所有活跃通道中查找:")
                    Log.e(TAG, "  - 总活跃通道数: ${allActiveChannels.size}")
                    Log.e(TAG, "  - 相关通道数: ${relatedChannels.size}")
                    relatedChannels.forEach { channel ->
                        Log.e(TAG, "    * 相关通道: ${channel.channelId}, recipientId=${channel.recipientId}")
                    }
                    
                    // 检查enabledProviders是否与通道的Provider匹配
                    enabledProviders.forEach { provider ->
                        Log.e(TAG, "启用Provider检查: ${provider.providerType}")
                        val matchingChannels = immediateChannels.filter { it.providerType == provider.providerType }
                        Log.e(TAG, "  - 匹配此Provider的通道数: ${matchingChannels.size}")
                    }
                    
                    // 详细的错误诊断
                    Log.e(TAG, "=== Provider选择失败详细诊断 ===")
                    Log.e(TAG, "启用Provider数量: ${enabledProviders.size}")
                    Log.e(TAG, "活跃Provider数量: ${activeProviders.size}")
                    Log.e(TAG, "配置启用列表: ${currentConfig.enabledProviders}")
                    Log.e(TAG, "系统初始化状态: $isInitialized")
                    Log.e(TAG, "通道状态: hasActiveChannel=$hasActiveChannel")
                    
                    if (enabledProviders.isEmpty() && activeProviders.isNotEmpty()) {
                        Log.e(TAG, "问题分析: 有活跃Provider但启用列表为空，可能是配置同步问题")
                        Log.e(TAG, "活跃Provider详情: ${activeProviders.map { "${it.providerType}:${it.javaClass.simpleName}" }}")
                    } else if (activeProviders.isEmpty()) {
                        Log.e(TAG, "问题分析: 没有活跃Provider，可能是Provider初始化问题")
                    } else if (immediateChannels.isNotEmpty() && enabledProviders.isNotEmpty()) {
                        Log.e(TAG, "问题分析: 有通道有Provider但选择失败，可能是selectBestProvider逻辑问题")
                        Log.e(TAG, "需要检查routingManager.selectBestProvider方法的实现")
                    } else {
                        Log.e(TAG, "问题分析: Provider选择逻辑异常")
                    }
                    
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        false,
                        "没有可用的传输提供者 (详见日志)"
                    )
                }
                
                // === selectBestProvider成功，开始后续步骤诊断 ===
                Log.d(TAG, "=== selectBestProvider成功 ===")
                Log.d(TAG, "选择的Provider: ${bestProvider.providerType}")
                Log.d(TAG, "开始创建/获取通道...")
                
                // 获取或创建传输通道（使用标准化的recipientId）
                val channel = channelManager.value.getOrCreateChannel(
                    recipientId = normalizedRecipientId,
                    providerType = bestProvider.providerType,
                    provider = bestProvider
                )
                
                Log.d(TAG, "=== getOrCreateChannel结果 ===")
                Log.d(TAG, "channel: ${if (channel != null) channel.channelId else "null"}")
                
                if (channel == null) {
                    Log.e(TAG, "=== 通道创建/获取失败详细诊断 ===")
                    Log.e(TAG, "bestProvider: ${bestProvider.providerType}")
                    Log.e(TAG, "normalizedRecipientId: $normalizedRecipientId")
                    Log.e(TAG, "原始recipientId: $recipientId")
                    
                    // 检查是否已有通道但状态不对
                    val existingChannels = channelManager.value.getActiveChannels(normalizedRecipientId)
                    Log.e(TAG, "现有活跃通道数: ${existingChannels.size}")
                    existingChannels.forEach { ch ->
                        Log.e(TAG, "  - ${ch.channelId}: status=${ch.status}, provider=${ch.providerType}, isActive=${ch.isActive()}")
                    }
                    
                    // 检查所有通道（包括非活跃的）
                    val allChannels = channelManager.value.getAllActiveChannels()
                    val relevantChannels = allChannels.filter { 
                        it.recipientId == normalizedRecipientId || it.recipientId == recipientId 
                    }
                    Log.e(TAG, "所有相关通道数: ${relevantChannels.size}")
                    relevantChannels.forEach { ch ->
                        Log.e(TAG, "  - 所有通道: ${ch.channelId}, recipientId=${ch.recipientId}, status=${ch.status}")
                    }
                    
                    return@withContext TransportResult.failure(
                        TransportError.CHANNEL_ERROR,
                        true,
                        "无法创建传输通道"
                    )
                }
                
                // 获取传输元数据
                Log.d(TAG, "=== metadata检查 ===")
                Log.d(TAG, "通道信息: channelId=${channel.channelId}, status=${channel.status}")
                
                val metadata = channel.metadata
                Log.d(TAG, "metadata != null: ${metadata != null}")
                
                if (metadata == null) {
                    Log.e(TAG, "=== metadata为null的详细分析 ===")
                    Log.e(TAG, "通道详情:")
                    Log.e(TAG, "  - channelId: ${channel.channelId}")
                    Log.e(TAG, "  - status: ${channel.status}")
                    Log.e(TAG, "  - providerType: ${channel.providerType}")
                    Log.e(TAG, "  - recipientId: ${channel.recipientId}")
                    Log.e(TAG, "  - createdAt: ${java.util.Date(channel.createdAt)}")
                    Log.e(TAG, "  - lastActiveAt: ${java.util.Date(channel.lastActiveAt)}")
                    Log.e(TAG, "  - isActive(): ${channel.isActive()}")
                    Log.e(TAG, "  - priority: ${channel.priority}")
                    
                    return@withContext TransportResult.failure(
                        TransportError.INVALID_METADATA,
                        true,
                        "无法获取传输元数据"
                    )
                }
                
                // 执行消息发送
                Log.d(TAG, "=== 开始provider.push ===")
                Log.d(TAG, "provider: ${bestProvider.providerType}")
                Log.d(TAG, "message详情:")
                Log.d(TAG, "  - messageId: ${message.messageId}")
                Log.d(TAG, "  - messageType: ${message.messageType}")
                Log.d(TAG, "  - encryptedContent大小: ${message.encryptedContent.size}")
                Log.d(TAG, "  - attachments数量: ${message.attachments.size}")
                Log.d(TAG, "metadata详情:")
                Log.d(TAG, "  - metadata类型: ${metadata.javaClass.simpleName}")
                
                val result = try {
                    bestProvider.push(message, metadata)
                } catch (e: Exception) {
                    Log.e(TAG, "=== provider.push异常详细诊断 ===")
                    Log.e(TAG, "provider: ${bestProvider.providerType}")
                    Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
                    Log.e(TAG, "异常消息: ${e.message}")
                    Log.e(TAG, "异常堆栈: ${e.stackTrace.take(5).joinToString("\n") { "  at $it" }}")
                    
                    // 检查Provider状态
                    Log.e(TAG, "Provider状态检查:")
                    try {
                        Log.e(TAG, "  - providerType: ${bestProvider.providerType}")
                        Log.e(TAG, "  - displayName: ${bestProvider.displayName}")
                        Log.e(TAG, "  - description: ${bestProvider.description}")
                        Log.e(TAG, "  - supportsAuth: ${bestProvider.supportsAuth}")
                        Log.e(TAG, "  - maxMessageSize: ${bestProvider.maxMessageSize}")
                    } catch (configEx: Exception) {
                        Log.e(TAG, "  - 获取provider信息失败: ${configEx.message}")
                    }
                    
                    throw e
                }
                
                Log.d(TAG, "=== provider.push成功 ===")
                Log.d(TAG, "result类型: ${result.javaClass.simpleName}")
                when (result) {
                    is TransportResult.Success -> Log.d(TAG, "推送成功")
                    is TransportResult.Failed -> Log.w(TAG, "推送失败: ${result.error}")
                    is TransportResult.PartialSuccess -> Log.w(TAG, "部分成功")
                    is TransportResult.RetryScheduled -> Log.w(TAG, "已调度重试")
                }
                
                // 更新通道统计
                when (result) {
                    is TransportResult.Success -> {
                        channelManager.value.updateChannelSuccess(channel.channelId)
                        Log.i(TAG, "消息路由成功: messageId=${message.messageId}, provider=${bestProvider.providerType}")
                    }
                    
                    is TransportResult.Failed -> {
                        channelManager.value.updateChannelFailure(channel.channelId, result.error)
                        Log.w(TAG, "消息路由失败: messageId=${message.messageId}, error=${result.error}")
                    }
                    
                    is TransportResult.RetryScheduled -> {
                        Log.w(TAG, "消息路由重试: messageId=${message.messageId}, retryAfter=${result.retryAfter}")
                    }
                    
                    is TransportResult.PartialSuccess -> {
                        channelManager.value.updateChannelSuccess(channel.channelId)
                        Log.w(TAG, "消息路由部分成功: messageId=${message.messageId}")
                    }
                }
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "消息路由异常: messageId=${message.messageId}, recipientId=$recipientId", e)
                TransportResult.failure(
                    TransportError.NETWORK_ERROR,
                    true,
                    "路由异常: ${e.message}"
                )
            }
        }
    }
    
    // 私有辅助方法
    
    /**
     * 加载已配置的Providers
     */
    private fun loadConfiguredProviders() {
        try {
            val providerConfigs = transportConfig.getAllConfigs()
            val enabledProviders = transportConfig.getEnabledProviders()
            
            Log.d(TAG, "加载已配置的Providers，数量: ${providerConfigs.size}")
            
            providerConfigs.forEach { (providerType, config) ->
                if (enabledProviders.contains(providerType)) {
                    val provider = providerManager.createProviderFromConfig(providerType, config)
                    if (provider != null) {
                        providerManager.registerProvider(provider)
                        Log.i(TAG, "加载Provider: $providerType")
                    } else {
                        Log.w(TAG, "无法创建Provider: $providerType")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "加载已配置的Providers失败", e)
        }
    }
    
    // 该方法已移动到TransportProviderManager中
    
    /**
     * 如果已配置则创建Provider
     */
    private fun createProviderIfConfigured(providerType: String): TransportProvider? {
        return try {
            val config = transportConfig.getProviderConfig(providerType)
            if (config != null && transportConfig.isProviderEnabled(providerType)) {
                Log.d(TAG, "找到Provider配置且已启用: $providerType")
                val provider = providerManager.createProviderFromConfig(providerType, config)
                if (provider != null) {
                    // 确保创建的provider被注册到管理器中
                    providerManager.registerProvider(provider)
                    Log.i(TAG, "Provider创建并注册成功: $providerType")
                    provider
                } else {
                    Log.w(TAG, "Provider创建失败: $providerType")
                    null
                }
            } else {
                if (config == null) {
                    Log.d(TAG, "Provider配置不存在: $providerType")
                } else {
                    Log.d(TAG, "Provider未启用: $providerType")
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "创建Provider失败: $providerType", e)
            null
        }
    }
    
    /**
     * 轮询单个通道的消息
     */
    private suspend fun pollChannelMessages(channel: TransportChannel): List<TransportMessage> {
        return try {
            val provider = getProvider(channel.providerType)
            if (provider == null) {
                Log.w(TAG, "Provider不存在: ${channel.providerType}")
                return emptyList()
            }
            
            Log.d(TAG, "轮询通道消息: ${channel.channelId} (${channel.providerType})")
            
            // 使用新接口：listFiles + downloadFile - 轮询messages和attachments目录
            val basePath = channel.metadata.getReceiveMetadata().path
            val pollingPaths = listOf("${basePath}messages/", "${basePath}attachments/")
            
            val allFileInfos = mutableListOf<Any>()
            for (path in pollingPaths) {
                val listResult = provider.listFiles(path, channel.metadata)
                when (listResult) {
                    is TransportResult.Success -> {
                        val fileInfos = listResult.data as? List<*> ?: emptyList<Any?>()
                        // 过滤掉null元素，只添加非null的FileInfo对象
                        fileInfos.filterNotNull().forEach { fileInfo ->
                            if (fileInfo is FileInfo) {
                                allFileInfos.add(fileInfo)
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "列举文件失败: path=$path")
                    }
                }
            }
            
            val messages = mutableListOf<TransportMessage>()
            Log.d(TAG, "找到文件数量: ${allFileInfos.size}")
            
            // 下载所有文件
            for (fileInfo in allFileInfos) {
                if (fileInfo is FileInfo) {
                    try {
                        val downloadResult = provider.downloadFile(fileInfo, channel.metadata)
                        when (downloadResult) {
                            is TransportResult.Success -> {
                                downloadResult.message?.let { message ->
                                    messages.add(message)
                                    Log.d(TAG, "下载消息成功: ${message.messageId}")
                                }
                            }
                            is TransportResult.Failed -> {
                                Log.w(TAG, "下载文件失败: ${fileInfo.name}, 错误: ${downloadResult.error}")
                            }
                            else -> {
                                Log.d(TAG, "下载文件结果: ${downloadResult.javaClass.simpleName}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "下载文件异常: ${fileInfo.name}", e)
                    }
                }
            }
            
            if (messages.isNotEmpty()) {
                channelManager.value.updateChannelSuccess(channel.channelId)
                // 更新Provider健康状态
                providerManager.updateProviderHealth(channel.providerType, true)
            }
            
            messages
        } catch (e: Exception) {
            Log.e(TAG, "轮询通道消息失败: ${channel.channelId}", e)
            channelManager.value.updateChannelFailure(
                channel.channelId, 
                TransportError.NETWORK_ERROR
            )
            emptyList()
        }
    }
    
    /**
     * 处理Provider状态变化
     */
    private suspend fun handleProviderStatusChange(oldConfig: TransportConfig, newConfig: TransportConfig) {
        // 新禁用的Providers：停用其通道
        val newlyDisabled = oldConfig.enabledProviders - newConfig.enabledProviders
        newlyDisabled.forEach { providerType ->
            Log.i(TAG, "禁用Provider: $providerType")
            channelManager.value.deactivateProviderChannels(providerType)
            providerManager.removeProvider(providerType)
        }
        
        // 新启用的Providers：创建并注册
        val newlyEnabled = newConfig.enabledProviders - oldConfig.enabledProviders
        newlyEnabled.forEach { providerType ->
            Log.i(TAG, "启用Provider: $providerType")
            val config = transportConfig.getProviderConfig(providerType)
            if (config != null) {
                providerManager.createProvider(providerType, config)
            } else {
                Log.w(TAG, "Provider配置不存在: $providerType")
            }
        }
    }
}

/**
 * 传输统计信息
 */
data class TransportStatistics(
    /** 总Provider数量 */
    val totalProviders: Int = 0,
    
    /** 已启用Provider数量 */
    val enabledProviders: Int = 0,
    
    /** 活跃通道数量 */
    val activeChannels: Int = 0,
    
    /** 总通道数量 */
    val totalChannels: Int = 0,
    
    /** 有效Token数量 */
    val validTokens: Int = 0,
    
    /** 总Token数量 */
    val totalTokens: Int = 0,
    
    /** 路由统计信息 */
    val routingStats: TransportRoutingStatistics = TransportRoutingStatistics()
) {
    
    /**
     * 计算Provider启用率
     */
    fun getProviderEnabledRate(): Double {
        return if (totalProviders > 0) enabledProviders.toDouble() / totalProviders else 0.0
    }
    
    /**
     * 计算通道活跃率
     */
    fun getChannelActiveRate(): Double {
        return if (totalChannels > 0) activeChannels.toDouble() / totalChannels else 0.0
    }
    
    /**
     * 计算Token有效率
     */
    fun getTokenValidRate(): Double {
        return if (totalTokens > 0) validTokens.toDouble() / totalTokens else 0.0
    }
} 