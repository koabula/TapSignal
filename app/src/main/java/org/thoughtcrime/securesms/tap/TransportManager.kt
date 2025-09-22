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
                    
                    // 初始化Provider管理器（内部注册默认工厂并加载配置）
                    if (!providerManager.initialize()) {
                        Log.e(TAG, "Provider管理器初始化失败")
                        return@withContext false
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
        return providerManager.getProvider(providerType)
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
        return active.filter { provider -> currentConfig.isProviderEnabled(provider.providerType) }
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
                
                Log.d(TAG, "路由消息: messageId=${message.messageId}, recipientId=$recipientId")
                
                // 使用路由管理器选择最佳提供者
                val bestProvider = routingManager.selectBestProvider(
                    recipientId = recipientId,
                    message = message,
                    availableProviders = getEnabledProviders()
                )
                
                if (bestProvider == null) {
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        false,
                        "没有可用的传输提供者"
                    )
                }
                
                // 获取或创建传输通道
                val channel = channelManager.value.getOrCreateChannel(
                    recipientId = recipientId,
                    providerType = bestProvider.providerType,
                    provider = bestProvider
                )
                
                if (channel == null) {
                    return@withContext TransportResult.failure(
                        TransportError.CHANNEL_ERROR,
                        true,
                        "无法创建传输通道"
                    )
                }
                
                // 获取传输元数据
                val metadata = channel.metadata
                if (metadata == null) {
                    return@withContext TransportResult.failure(
                        TransportError.INVALID_METADATA,
                        true,
                        "无法获取传输元数据"
                    )
                }
                
                // 执行消息发送
                val result = bestProvider.push(message, metadata)
                
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
                providerManager.createProviderFromConfig(providerType, config)
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
            
            Log.d(TAG, "轮询通道消息: ${channel.channelId} (${channel.providerType})")
            
            // 使用新接口：listFiles + downloadFile
            val listResult = provider.listFiles(channel.metadata.getReceiveMetadata().path, channel.metadata)
            when (listResult) {
                is TransportResult.Success -> {
                    val fileInfos = listResult.data as? List<*> ?: emptyList<Any>()
                    val messages = mutableListOf<TransportMessage>()
                    
                    Log.d(TAG, "找到文件数量: ${fileInfos.size}")
                    
                    // 下载所有文件
                    for (fileInfo in fileInfos) {
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
                    }
                    
                    messages
                }
                is TransportResult.Failed -> {
                    channelManager.value.updateChannelFailure(channel.channelId, listResult.error)
                    Log.w(TAG, "列出文件失败: ${listResult.error}")
                    emptyList()
                }
                is TransportResult.RetryScheduled -> {
                    // 轮询重试由轮询服务处理
                    Log.d(TAG, "列出文件需要重试: ${listResult.retryAfter}ms")
                    emptyList()
                }
                is TransportResult.PartialSuccess -> {
                    // 处理部分成功的情况
                    channelManager.value.updateChannelSuccess(channel.channelId)
                    Log.d(TAG, "列出文件部分成功")
                    emptyList()
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