package org.thoughtcrime.securesms.tap

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * 传输管理器 - 核心传输层管理组件
 * 
 * 负责传输提供者的注册、管理和消息路由，是整个Tap架构的核心。
 * 采用单例模式，提供全局统一的传输服务访问点。
 */
class TransportManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportManager"
        private var INSTANCE: TransportManager? = null
        
        /**
         * 获取TransportManager单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val transportConfig = TransportProviderConfigManager.getInstance(context)
    private val channelManager = lazy { TransportChannelManager.getInstance(context) }
    private val tokenPool = lazy { TransportTokenPool.getInstance(context) }
    private val routingManager = lazy { TransportRoutingManager.getInstance(context) }
    
    // Provider管理
    private val providers = ConcurrentHashMap<String, TransportProvider>()
    private val providerFactories = ConcurrentHashMap<String, TransportProviderFactory>()
    private val providerLock = ReentrantReadWriteLock()
    
    // 配置和状态
    private var currentConfig: TransportConfig = TransportConfig()
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
                        Log.e(TAG, "传输配置无效: ${validationResult.errors.joinToString(", ")}")
                        return@withContext false
                    }
                    
                    currentConfig = config
                    
                    // 初始化子组件
                    channelManager.value.initialize(config.channelConfig)
                    tokenPool.value.initialize(config.tokenConfig)
                    routingManager.value.initialize(config.routingPolicy)
                    
                    // 加载已配置的Providers
                    loadConfiguredProviders()
                    
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
     * 注册传输提供者
     */
    fun registerProvider(provider: TransportProvider): Boolean {
        providerLock.write {
            return try {
                if (providers.containsKey(provider.providerType)) {
                    Log.w(TAG, "传输提供者已存在: ${provider.providerType}")
                    false
                } else {
                    providers[provider.providerType] = provider
                    Log.i(TAG, "注册传输提供者: ${provider.providerType}")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "注册传输提供者失败: ${provider.providerType}", e)
                false
            }
        }
    }
    
    /**
     * 注册传输提供者工厂
     */
    fun registerProviderFactory(factory: TransportProviderFactory): Boolean {
        providerLock.write {
            return try {
                factory.supportedProviderTypes.forEach { providerType ->
                    providerFactories[providerType] = factory
                    Log.i(TAG, "注册传输提供者工厂: $providerType")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "注册传输提供者工厂失败", e)
                false
            }
        }
    }
    
    /**
     * 注销传输提供者
     */
    fun unregisterProvider(providerType: String): Boolean {
        providerLock.write {
            return try {
                val provider = providers.remove(providerType)
                if (provider != null) {
                    // 清理相关资源
                    managerScope.launch {
                        try {
                            provider.cleanup()
                            channelManager.value.closeProviderChannels(providerType)
                        } catch (e: Exception) {
                            Log.w(TAG, "清理Provider资源时出错: $providerType", e)
                        }
                    }
                    Log.i(TAG, "注销传输提供者: $providerType")
                    true
                } else {
                    Log.w(TAG, "传输提供者不存在: $providerType")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "注销传输提供者失败: $providerType", e)
                false
            }
        }
    }
    
    /**
     * 获取传输提供者
     */
    fun getProvider(providerType: String): TransportProvider? {
        providerLock.read {
            return providers[providerType] ?: createProviderIfConfigured(providerType)
        }
    }
    
    /**
     * 获取所有可用提供者
     */
    fun getAvailableProviders(): List<TransportProvider> {
        providerLock.read {
            return providers.values.toList()
        }
    }
    
    /**
     * 获取所有已启用的提供者
     */
    fun getEnabledProviders(): List<TransportProvider> {
        providerLock.read {
            return providers.values.filter { provider ->
                currentConfig.isProviderEnabled(provider.providerType)
            }
        }
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
                
                // 使用路由管理器选择最佳提供者
                val bestProvider = routingManager.value.selectBestProvider(
                    recipientId = recipientId,
                    message = message,
                    availableProviders = getEnabledProviders()
                )
                
                if (bestProvider == null) {
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "没有可用的传输提供者"
                    )
                }
                
                Log.d(TAG, "选择传输提供者: ${bestProvider.providerType}")
                
                // 获取或建立通道
                val channel = channelManager.value.getOrCreateChannel(
                    recipientId = recipientId,
                    providerType = bestProvider.providerType,
                    provider = bestProvider
                )
                
                if (channel == null || !channel.isAvailable()) {
                    return@withContext TransportResult.failure(
                        TransportError.NETWORK_ERROR,
                        true,
                        "无法建立传输通道"
                    )
                }
                
                // 执行消息发送
                val result = bestProvider.push(message, channel.metadata)
                
                // 更新通道状态
                when (result) {
                    is TransportResult.Success -> {
                        channelManager.value.updateChannelSuccess(channel.channelId)
                        Log.d(TAG, "消息发送成功: ${message.messageId}")
                    }
                    is TransportResult.Failed -> {
                        channelManager.value.updateChannelFailure(channel.channelId, result.error)
                        Log.w(TAG, "消息发送失败: ${message.messageId}, 错误: ${result.error}")
                    }
                    is TransportResult.RetryScheduled -> {
                        Log.d(TAG, "消息需要重试: ${message.messageId}, 延迟: ${result.retryAfter}ms")
                    }
                    is TransportResult.PartialSuccess -> {
                        channelManager.value.updateChannelSuccess(channel.channelId)
                        Log.w(TAG, "消息部分成功: ${message.messageId}")
                    }
                }
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "发送消息异常", e)
                val isRetryable = when (e) {
                    is SecurityException,
                    is IllegalArgumentException,
                    is IllegalStateException -> false // 配置或权限问题不可重试
                    is java.net.UnknownHostException,
                    is java.net.SocketTimeoutException,
                    is java.net.ConnectException,
                    is java.io.IOException -> true // 网络问题可重试
                    is InterruptedException,
                    is CancellationException -> false // 取消操作不重试
                    else -> {
                        // 未知异常，根据消息内容判断
                        val message = e.message?.lowercase() ?: ""
                        when {
                            message.contains("permission") || 
                            message.contains("unauthorized") ||
                            message.contains("forbidden") -> false
                            message.contains("timeout") ||
                            message.contains("connection") ||
                            message.contains("network") -> true
                            else -> false // 保守策略：未知错误不重试
                        }
                    }
                }
                TransportResult.fromException(e, isRetryable)
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
                val routingStats = routingManager.value.getRoutingStatistics()
                
                TransportStatistics(
                    totalProviders = providers.size,
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
                Log.e(TAG, "新配置无效: ${validationResult.errors.joinToString(", ")}")
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
                    async { tokenPool.value.updateConfig(newConfig.tokenConfig) },
                    async { routingManager.value.updateRoutingPolicy(newConfig.routingPolicy) }
                )
                
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
                
                // 清理所有Providers
                providerLock.write {
                    providers.values.forEach { provider ->
                        try {
                            runBlocking { provider.cleanup() }
                        } catch (e: Exception) {
                            Log.w(TAG, "清理Provider失败: ${provider.providerType}", e)
                        }
                    }
                    providers.clear()
                    providerFactories.clear()
                }
                
                // 清理子组件
                channelManager.value.cleanup()
                tokenPool.value.cleanup()
                
                // 取消协程作用域
                managerScope.cancel()
                
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
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        initializationLock.read {
            return isInitialized
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
                    val provider = createProviderFromConfig(providerType, config)
                    if (provider != null) {
                        registerProvider(provider)
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
    
    /**
     * 从配置创建Provider实例
     */
    private fun createProviderFromConfig(providerType: String, config: Map<String, Any>): TransportProvider? {
        providerLock.read {
            val factory = providerFactories[providerType]
            return factory?.createProvider(providerType, config)
        }
    }
    
    /**
     * 如果已配置则创建Provider
     */
    private fun createProviderIfConfigured(providerType: String): TransportProvider? {
        return try {
            val config = transportConfig.getProviderConfig(providerType)
            if (config != null && transportConfig.isProviderEnabled(providerType)) {
                createProviderFromConfig(providerType, config)
            } else {
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
            
            val result = provider.pull(channel.metadata)
            when (result) {
                is TransportResult.Success -> {
                    channelManager.value.updateChannelSuccess(channel.channelId)
                    listOfNotNull(result.message)
                }
                is TransportResult.Failed -> {
                    channelManager.value.updateChannelFailure(channel.channelId, result.error)
                    emptyList()
                }
                is TransportResult.RetryScheduled -> {
                    // 轮询重试由轮询服务处理
                    emptyList()
                }
                is TransportResult.PartialSuccess -> {
                    channelManager.value.updateChannelSuccess(channel.channelId)
                    // 从PartialSuccess的results中提取成功的消息
                    result.getSuccesses().mapNotNull { it.message }
                }
            }
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
        // 找出新禁用的Providers
        val newlyDisabled = oldConfig.enabledProviders - newConfig.enabledProviders
        newlyDisabled.forEach { providerType ->
            Log.i(TAG, "禁用Provider: $providerType")
            channelManager.value.deactivateProviderChannels(providerType)
        }
        
        // 找出新启用的Providers
        val newlyEnabled = newConfig.enabledProviders - oldConfig.enabledProviders
        newlyEnabled.forEach { providerType ->
            Log.i(TAG, "启用Provider: $providerType")
            // 如果Provider未注册，尝试从配置创建
            if (!providers.containsKey(providerType)) {
                val config = transportConfig.getProviderConfig(providerType)
                if (config != null) {
                    val provider = createProviderFromConfig(providerType, config)
                    if (provider != null) {
                        registerProvider(provider)
                    }
                }
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