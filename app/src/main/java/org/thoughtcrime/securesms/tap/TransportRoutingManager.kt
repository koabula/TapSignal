package org.thoughtcrime.securesms.tap

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * 传输路由管理器
 * 
 * 负责智能路由决策，根据网络状况、消息类型、接收者状态、Provider性能等
 * 因素选择最佳的传输Provider，并提供路由优化和统计功能。
 */
class TransportRoutingManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportRoutingManager"
        
        @Volatile
        private var INSTANCE: TransportRoutingManager? = null
        
        /**
         * 获取TransportRoutingManager单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportRoutingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportRoutingManager(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建TransportRoutingManager实例: ${it.hashCode()}")
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
                Log.d(TAG, "重置TransportRoutingManager实例")
            }
        }
        
        // 路由评分权重
        private const val WEIGHT_PERFORMANCE = 0.3
        private const val WEIGHT_RELIABILITY = 0.25
        private const val WEIGHT_CHANNEL_STATUS = 0.2
        private const val WEIGHT_PROVIDER_PREFERENCE = 0.15
        private const val WEIGHT_MESSAGE_TYPE_FIT = 0.1
        
        // Provider性能评分缓存有效期
        const val PERFORMANCE_CACHE_TIMEOUT_MS = 60000L // 1分钟
    }
    
    // 路由策略和统计
    private var routingPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT
    private val routingStats = ConcurrentHashMap<String, ProviderRoutingStats>()
    private val performanceCache = ConcurrentHashMap<String, ProviderPerformanceStats>()
    private val recipientPreferences = ConcurrentHashMap<String, ProviderPreference>()
    
    // 线程安全
    private val routingLock = ReentrantReadWriteLock()
    private val statsLock = ReentrantReadWriteLock()
    
    // 状态管理
    private var isInitialized: Boolean = false
    
    /**
     * 初始化路由管理器
     */
    suspend fun initialize(policy: TransportRoutingPolicy): Boolean {
        return withContext(Dispatchers.IO) {
            routingLock.write {
                try {
                    if (isInitialized) {
                        Log.w(TAG, "路由管理器已经初始化")
                        return@withContext true
                    }
                    
                    Log.i(TAG, "初始化路由管理器，策略: ${policy.displayName}")
                    
                    routingPolicy = policy
                    
                    // 加载历史统计数据
                    loadRoutingStats()
                    
                    isInitialized = true
                    Log.i(TAG, "路由管理器初始化完成")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "初始化路由管理器失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 选择最佳传输提供者
     */
    suspend fun selectBestProvider(
        recipientId: String,
        message: TransportMessage,
        availableProviders: List<TransportProvider>
    ): TransportProvider? {
        return withContext(Dispatchers.IO) {
            routingLock.read {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "路由管理器未初始化")
                        return@withContext null
                    }
                    
                    if (availableProviders.isEmpty()) {
                        Log.w(TAG, "没有可用的传输提供者")
                        return@withContext null
                    }
                    
                    Log.d(TAG, "选择最佳Provider，可用数量: ${availableProviders.size}, 策略: ${routingPolicy.displayName}")
                    
                    // 根据路由策略进行选择
                    val selectedProvider = when (routingPolicy) {
                        TransportRoutingPolicy.TRANSPORT_FIRST -> selectTransportFirstProvider(availableProviders)
                        TransportRoutingPolicy.SIGNAL_FIRST -> null // Signal优先时不使用传输服务
                        TransportRoutingPolicy.INTELLIGENT -> selectIntelligentProvider(recipientId, message, availableProviders)
                        TransportRoutingPolicy.TRANSPORT_ONLY -> selectTransportOnlyProvider(availableProviders)
                        TransportRoutingPolicy.SIGNAL_ONLY -> null // 仅Signal时不使用传输服务
                    }
                    
                    if (selectedProvider != null) {
                        Log.d(TAG, "选择Provider: ${selectedProvider.providerType}")
                        recordProviderSelection(selectedProvider.providerType, recipientId)
                    } else {
                        Log.w(TAG, "未找到合适的Provider")
                    }
                    
                    selectedProvider
                    
                } catch (e: Exception) {
                    Log.e(TAG, "选择最佳Provider失败", e)
                    null
                }
            }
        }
    }
    
    /**
     * 检查是否应该使用传输服务发送
     */
    fun shouldUseTransport(recipientId: String, message: TransportMessage): Boolean {
        routingLock.read {
            return when (routingPolicy) {
                TransportRoutingPolicy.TRANSPORT_FIRST, 
                TransportRoutingPolicy.TRANSPORT_ONLY -> true
                TransportRoutingPolicy.SIGNAL_FIRST,
                TransportRoutingPolicy.SIGNAL_ONLY -> false
                TransportRoutingPolicy.INTELLIGENT -> shouldUseTransportIntelligent(recipientId, message)
            }
        }
    }
    
    /**
     * 记录路由结果
     */
    suspend fun recordRoutingResult(
        providerType: String,
        recipientId: String,
        success: Boolean,
        responseTime: Long,
        errorType: TransportError? = null
    ) {
        withContext(Dispatchers.IO) {
            statsLock.write {
                try {
                    // 更新Provider统计
                    val stats = routingStats.computeIfAbsent(providerType) {
                        ProviderRoutingStats(providerType)
                    }
                    
                    stats.totalAttempts++
                    stats.lastAttemptTime = System.currentTimeMillis()
                    
                    if (success) {
                        stats.successfulAttempts++
                        stats.totalResponseTime += responseTime
                        stats.averageResponseTime = stats.totalResponseTime / stats.successfulAttempts
                    } else {
                        stats.failedAttempts++
                        errorType?.let { error ->
                            stats.errorCounts[error] = stats.errorCounts.getOrDefault(error, 0) + 1
                        }
                    }
                    
                    // 更新性能缓存
                    updatePerformanceCache(providerType, success, responseTime)
                    
                    Log.d(TAG, "记录路由结果: $providerType, 成功: $success, 响应时间: ${responseTime}ms")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "记录路由结果失败", e)
                }
            }
        }
    }
    
    /**
     * 更新接收者Provider偏好
     */
    fun updateRecipientPreference(
        recipientId: String, 
        providerType: String, 
        preference: PreferenceLevel
    ) {
        routingLock.write {
            val currentPreference = recipientPreferences[recipientId] ?: ProviderPreference(recipientId)
            currentPreference.preferences[providerType] = preference
            recipientPreferences[recipientId] = currentPreference
            
            Log.d(TAG, "更新接收者Provider偏好: $recipientId -> $providerType: $preference")
        }
    }
    
    /**
     * 获取路由统计信息
     */
    fun getRoutingStatistics(): TransportRoutingStatistics {
        statsLock.read {
            val providerStats = routingStats.values.map { stats ->
                ProviderRoutingStatistics(
                    providerType = stats.providerType,
                    totalAttempts = stats.totalAttempts,
                    successfulAttempts = stats.successfulAttempts,
                    failedAttempts = stats.failedAttempts,
                    averageResponseTime = stats.averageResponseTime,
                    successRate = if (stats.totalAttempts > 0) {
                        stats.successfulAttempts.toDouble() / stats.totalAttempts
                    } else 0.0,
                    errorDistribution = stats.errorCounts.toMap()
                )
            }.toList()
            
            return TransportRoutingStatistics(
                currentPolicy = routingPolicy,
                totalRoutingAttempts = routingStats.values.sumOf { it.totalAttempts },
                totalSuccessfulRouting = routingStats.values.sumOf { it.successfulAttempts },
                providerStatistics = providerStats
            )
        }
    }
    
    /**
     * 更新路由策略
     */
    fun updateRoutingPolicy(newPolicy: TransportRoutingPolicy) {
        routingLock.write {
            if (routingPolicy != newPolicy) {
                Log.i(TAG, "更新路由策略: ${routingPolicy.displayName} -> ${newPolicy.displayName}")
                routingPolicy = newPolicy
            }
        }
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始清理路由管理器资源")
                
                routingLock.write {
                    routingStats.clear()
                    performanceCache.clear()
                    recipientPreferences.clear()
                    isInitialized = false
                }
                
                Log.i(TAG, "路由管理器资源清理完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "清理路由管理器资源失败", e)
            }
        }
    }
    
    /**
     * 选择最佳传输通道
     */
    suspend fun selectBestChannel(
        availableChannels: List<TransportChannel>,
        message: TransportMessage
    ): TransportChannel? {
        return withContext(Dispatchers.IO) {
            routingLock.read {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "路由管理器未初始化")
                        return@withContext null
                    }
                    
                    if (availableChannels.isEmpty()) {
                        Log.w(TAG, "没有可用的传输通道")
                        return@withContext null
                    }
                    
                    // 过滤活跃的通道
                    val activeChannels = availableChannels.filter { it.isActive() }
                    if (activeChannels.isEmpty()) {
                        Log.w(TAG, "没有活跃的传输通道")
                        return@withContext null
                    }
                    
                    Log.d(TAG, "选择最佳通道，可用数量: ${activeChannels.size}")
                    
                    // 根据优先级、成功率和最后活跃时间选择最佳通道
                    val selectedChannel = activeChannels.maxByOrNull { channel ->
                        val priorityScore = channel.priority * 0.4
                        val successRateScore = channel.getSuccessRate() * 0.4
                        val freshnessScore = (1.0 / (1.0 + channel.getTimeSinceLastActive() / 60000.0)) * 0.2
                        priorityScore + successRateScore + freshnessScore
                    }
                    
                    if (selectedChannel != null) {
                        Log.d(TAG, "选择通道: ${selectedChannel.channelId}, Provider: ${selectedChannel.providerType}")
                    }
                    
                    selectedChannel
                    
                } catch (e: Exception) {
                    Log.e(TAG, "选择最佳通道失败", e)
                    null
                }
            }
        }
    }

    /**
     * 记录发送结果
     */
    fun recordSendResult(providerType: String, result: TransportResult) {
        try {
            statsLock.write {
                val stats = routingStats.computeIfAbsent(providerType) { 
                    ProviderRoutingStats(providerType) 
                }
                
                if (result is TransportResult.Success) {
                    stats.recordSuccess()
                } else if (result is TransportResult.Failed) {
                    stats.recordFailure(result.error)
                }
                
                routingStats[providerType] = stats
            }
            
            Log.d(TAG, "记录发送结果: provider=$providerType, success=${result is TransportResult.Success}")
            
        } catch (e: Exception) {
            Log.e(TAG, "记录发送结果失败", e)
        }
    }
    
    /**
     * 评估可用的Provider
     * @param availableChannels 可用通道列表
     * @return Provider类型和评分的配对列表
     */
    suspend fun evaluateProviders(availableChannels: List<TransportChannel>): List<Pair<String, Double>> {
        return withContext(Dispatchers.IO) {
            try {
                val providerScores = mutableMapOf<String, Double>()
                
                availableChannels.forEach { channel ->
                    val providerType = channel.providerType
                    val stats = routingStats[providerType]
                    
                    val baseScore = if (channel.isActive()) 1.0 else 0.5
                    val successRateScore = channel.getSuccessRate() * 0.4
                    val priorityScore = (channel.priority / 10.0) * 0.3
                    val performanceScore = stats?.let { it.getSuccessRate() } ?: 0.5
                    val freshnessScore = (1.0 / (1.0 + channel.getTimeSinceLastActive() / 60000.0)) * 0.2
                    
                    val totalScore = baseScore + successRateScore + priorityScore + performanceScore * 0.1 + freshnessScore
                    
                    providerScores[providerType] = maxOf(
                        providerScores.getOrDefault(providerType, 0.0),
                        totalScore
                    )
                }
                
                providerScores.toList()
                
            } catch (e: Exception) {
                Log.e(TAG, "评估Provider失败", e)
                emptyList()
            }
        }
    }
    
    // 私有辅助方法
    
    /**
     * 传输优先策略Provider选择
     */
    private suspend fun selectTransportFirstProvider(providers: List<TransportProvider>): TransportProvider? {
        // 按可靠性和性能排序，选择最佳的
        val scoredProviders = providers.map { provider ->
            provider to calculateProviderScore(provider.providerType, null, null)
        }.sortedByDescending { it.second }
        
        return scoredProviders.firstOrNull()?.first
    }
    
    /**
     * 传输专用策略Provider选择
     */
    private suspend fun selectTransportOnlyProvider(providers: List<TransportProvider>): TransportProvider? {
        // 类似传输优先，但排除Signal相关的Provider
        val transportProviders = providers.filter { it.providerType != "signal" }
        return selectTransportFirstProvider(transportProviders)
    }
    
    /**
     * 智能Provider选择
     */
    private suspend fun selectIntelligentProvider(
        recipientId: String,
        message: TransportMessage,
        providers: List<TransportProvider>
    ): TransportProvider? {
        // 为每个Provider计算综合评分
        val scoredProviders = providers.map { provider ->
            val score = calculateProviderScore(provider.providerType, recipientId, message)
            provider to score
        }.sortedByDescending { it.second }
        
        Log.d(TAG, "Provider评分排序: ${scoredProviders.map { "${it.first.providerType}:${String.format("%.2f", it.second)}" }}")
        
        return scoredProviders.firstOrNull()?.first
    }
    
    /**
     * 计算Provider综合评分
     */
    private suspend fun calculateProviderScore(
        providerType: String,
        recipientId: String?,
        message: TransportMessage?
    ): Double {
        var score = 0.0
        
        // 性能评分
        val performanceScore = calculatePerformanceScore(providerType)
        score += performanceScore * WEIGHT_PERFORMANCE
        
        // 可靠性评分
        val reliabilityScore = calculateReliabilityScore(providerType)
        score += reliabilityScore * WEIGHT_RELIABILITY
        
        // 通道状态评分
        val channelScore = calculateChannelScore(providerType, recipientId)
        score += channelScore * WEIGHT_CHANNEL_STATUS
        
        // Provider偏好评分
        val preferenceScore = calculatePreferenceScore(providerType, recipientId)
        score += preferenceScore * WEIGHT_PROVIDER_PREFERENCE
        
        // 消息类型适配评分
        val messageTypeScore = calculateMessageTypeScore(providerType, message)
        score += messageTypeScore * WEIGHT_MESSAGE_TYPE_FIT
        
        return score.coerceIn(0.0, 1.0)
    }
    
    /**
     * 计算性能评分
     */
    private fun calculatePerformanceScore(providerType: String): Double {
        val perfStats = performanceCache[providerType]
        if (perfStats == null || perfStats.isExpired()) {
            return 0.5 // 默认中等评分
        }
        
        // 基于平均响应时间和成功率计算性能评分
        val responseTimeScore = when {
            perfStats.averageResponseTime <= 1000 -> 1.0    // 1秒以内优秀
            perfStats.averageResponseTime <= 3000 -> 0.8    // 3秒以内良好
            perfStats.averageResponseTime <= 10000 -> 0.6   // 10秒以内一般
            else -> 0.3                                      // 超过10秒较差
        }
        
        val successRateScore = perfStats.successRate
        
        return (responseTimeScore + successRateScore) / 2.0
    }
    
    /**
     * 计算可靠性评分
     */
    private fun calculateReliabilityScore(providerType: String): Double {
        val stats = routingStats[providerType] ?: return 0.5
        
        if (stats.totalAttempts == 0) {
            return 0.5 // 没有历史数据，给默认评分
        }
        
        val successRate = stats.successfulAttempts.toDouble() / stats.totalAttempts
        val recentSuccessRate = calculateRecentSuccessRate(stats)
        
        // 综合历史成功率和近期成功率
        return (successRate * 0.3 + recentSuccessRate * 0.7).coerceIn(0.0, 1.0)
    }
    
    /**
     * 计算通道状态评分
     */
    private suspend fun calculateChannelScore(providerType: String, recipientId: String?): Double {
        if (recipientId == null) {
            return 0.5
        }
        
        val channelManager = TransportChannelManager.getInstance(context)
        val channel = channelManager.getActiveChannel(recipientId, providerType)
        
        return when {
            channel == null -> 0.3                        // 没有通道
            channel.isActive() -> 0.9                     // 活跃通道
            channel.isAvailable() -> 0.7                  // 可用通道
            channel.isFailed() -> 0.1                     // 失败通道
            else -> 0.5                                    // 其他状态
        }
    }
    
    /**
     * 计算Provider偏好评分
     */
    private fun calculatePreferenceScore(providerType: String, recipientId: String?): Double {
        if (recipientId == null) {
            return 0.5
        }
        
        val preference = recipientPreferences[recipientId]?.preferences?.get(providerType)
        return when (preference) {
            PreferenceLevel.HIGHLY_PREFERRED -> 1.0
            PreferenceLevel.PREFERRED -> 0.8
            PreferenceLevel.NEUTRAL -> 0.5
            PreferenceLevel.NOT_PREFERRED -> 0.2
            PreferenceLevel.BLOCKED -> 0.0
            null -> 0.5
        }
    }
    
    /**
     * 计算消息类型适配评分
     */
    private fun calculateMessageTypeScore(providerType: String, message: TransportMessage?): Double {
        if (message == null) {
            return 0.5
        }
        
        // 根据Provider特性和消息类型计算适配度
        return when (providerType) {
            "cos" -> when (message.messageType) {
                TransportMessageType.MEDIA_MESSAGE -> 0.9      // COS适合大文件
                TransportMessageType.TEXT_MESSAGE -> 0.7       // 文本消息也可以
                TransportMessageType.CONTROL_MESSAGE -> 0.8    // 控制消息适合
                TransportMessageType.RATCHET_UPDATE -> 0.6     // 密钥更新一般
                TransportMessageType.CALL_MESSAGE -> 0.7       // 通话消息也适合
            }
            "email" -> when (message.messageType) {
                TransportMessageType.TEXT_MESSAGE -> 0.8       // 邮件适合文本
                TransportMessageType.MEDIA_MESSAGE -> 0.6      // 媒体文件有大小限制
                TransportMessageType.CONTROL_MESSAGE -> 0.7    // 控制消息可以
                TransportMessageType.RATCHET_UPDATE -> 0.5     // 密钥更新不太适合
                TransportMessageType.CALL_MESSAGE -> 0.6       // 通话消息可以通过邮件
            }
            "ipfs" -> when (message.messageType) {
                TransportMessageType.MEDIA_MESSAGE -> 1.0      // IPFS非常适合大文件
                TransportMessageType.TEXT_MESSAGE -> 0.6       // 文本消息可以但不是最优
                TransportMessageType.CONTROL_MESSAGE -> 0.7    // 控制消息适合
                TransportMessageType.RATCHET_UPDATE -> 0.8     // 密钥更新适合分布式
                TransportMessageType.CALL_MESSAGE -> 0.8       // 通话消息适合分布式
            }
            "git" -> when (message.messageType) {
                TransportMessageType.TEXT_MESSAGE -> 0.7       // Git适合文本
                TransportMessageType.CONTROL_MESSAGE -> 0.9    // 控制消息很适合
                TransportMessageType.RATCHET_UPDATE -> 0.8     // 密钥更新适合版本控制
                TransportMessageType.MEDIA_MESSAGE -> 0.4      // 大文件不太适合
                TransportMessageType.CALL_MESSAGE -> 0.7       // 通话消息可以版本控制
            }
            "nas" -> when (message.messageType) {
                TransportMessageType.MEDIA_MESSAGE -> 0.8      // NAS适合大文件存储
                TransportMessageType.TEXT_MESSAGE -> 0.6       // 文本消息可以
                TransportMessageType.CONTROL_MESSAGE -> 0.5    // 控制消息一般
                TransportMessageType.RATCHET_UPDATE -> 0.5     // 密钥更新一般
                TransportMessageType.CALL_MESSAGE -> 0.6       // 通话消息可以存储
            }
            else -> 0.5
        }
    }
    
    /**
     * 智能判断是否应该使用传输服务
     */
    private fun shouldUseTransportIntelligent(recipientId: String, message: TransportMessage): Boolean {
        // 基于消息类型、大小、网络状况等因素决定
        val messageSize = message.encryptedContent.size + message.attachments.sumOf { it.size }
        
        // 大文件倾向于使用传输服务
        if (messageSize > 1024 * 1024) { // 1MB以上
            return true
        }
        
        // 媒体消息倾向于使用传输服务
        if (message.messageType == TransportMessageType.MEDIA_MESSAGE) {
            return true
        }
        
        // 检查是否有可用的高性能传输通道
        val channelManager = TransportChannelManager.getInstance(context)
        val activeChannels = channelManager.getActiveChannels(recipientId)
        val hasHighPerformanceChannel = activeChannels.any { channel ->
            val stats = performanceCache[channel.providerType]
            stats?.successRate ?: 0.0 > 0.8
        }
        
        return hasHighPerformanceChannel
    }
    
    /**
     * 计算近期成功率
     */
    private fun calculateRecentSuccessRate(stats: ProviderRoutingStats): Double {
        // 这里可以实现基于时间窗口的近期成功率计算
        // 简化实现：如果最后一次尝试是成功的，给予更高的权重
        val recentWindow = 3600000L // 1小时
        val currentTime = System.currentTimeMillis()
        
        return if (currentTime - stats.lastAttemptTime < recentWindow) {
            // 近期有活动，基于当前成功率
            if (stats.totalAttempts > 0) {
                stats.successfulAttempts.toDouble() / stats.totalAttempts
            } else 0.5
        } else {
            // 长时间没有活动，降低评分
            0.3
        }
    }
    
    /**
     * 更新性能缓存
     */
    private fun updatePerformanceCache(providerType: String, success: Boolean, responseTime: Long) {
        val stats = performanceCache.computeIfAbsent(providerType) {
            ProviderPerformanceStats(providerType)
        }
        
        stats.totalAttempts++
        stats.lastUpdateTime = System.currentTimeMillis()
        
        if (success) {
            stats.successfulAttempts++
            stats.totalResponseTime += responseTime
            stats.averageResponseTime = stats.totalResponseTime / stats.successfulAttempts
        }
        
        stats.successRate = stats.successfulAttempts.toDouble() / stats.totalAttempts
    }
    
    /**
     * 记录Provider选择
     */
    private fun recordProviderSelection(providerType: String, recipientId: String) {
        val stats = routingStats.computeIfAbsent(providerType) {
            ProviderRoutingStats(providerType)
        }
        stats.selectionCount++
    }
    
    /**
     * 加载路由统计数据
     */
    private fun loadRoutingStats() {
        // 这里可以从持久化存储加载历史统计数据
        // 简化实现：从内存开始
        Log.d(TAG, "加载路由统计数据")
    }
}

/**
 * Provider偏好级别
 */
enum class PreferenceLevel(val displayName: String, val score: Double) {
    HIGHLY_PREFERRED("高度偏好", 1.0),
    PREFERRED("偏好", 0.8),
    NEUTRAL("中性", 0.5),
    NOT_PREFERRED("不偏好", 0.2),
    BLOCKED("屏蔽", 0.0)
}

/**
 * Provider偏好设置
 */
data class ProviderPreference(
    val recipientId: String,
    val preferences: MutableMap<String, PreferenceLevel> = mutableMapOf()
)

/**
 * Provider路由统计
 */
data class ProviderRoutingStats(
    val providerType: String,
    var totalAttempts: Int = 0,
    var successfulAttempts: Int = 0,
    var failedAttempts: Int = 0,
    var selectionCount: Int = 0,
    var totalResponseTime: Long = 0L,
    var averageResponseTime: Long = 0L,
    var lastAttemptTime: Long = 0L,
    val errorCounts: MutableMap<TransportError, Int> = mutableMapOf()
) {
    
    /**
     * 记录成功
     */
    fun recordSuccess() {
        totalAttempts++
        successfulAttempts++
        lastAttemptTime = System.currentTimeMillis()
    }
    
    /**
     * 记录失败
     */
    fun recordFailure(error: TransportError) {
        totalAttempts++
        failedAttempts++
        errorCounts[error] = errorCounts.getOrDefault(error, 0) + 1
        lastAttemptTime = System.currentTimeMillis()
    }
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        return if (totalAttempts > 0) successfulAttempts.toDouble() / totalAttempts else 0.0
    }
}

/**
 * Provider性能统计
 */
data class ProviderPerformanceStats(
    val providerType: String,
    var totalAttempts: Int = 0,
    var successfulAttempts: Int = 0,
    var totalResponseTime: Long = 0L,
    var averageResponseTime: Long = 0L,
    var successRate: Double = 0.0,
    var lastUpdateTime: Long = System.currentTimeMillis()
) {
    
    /**
     * 检查缓存是否过期
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastUpdateTime > TransportRoutingManager.PERFORMANCE_CACHE_TIMEOUT_MS
    }
}

/**
 * 传输路由统计信息
 */
data class TransportRoutingStatistics(
    /** 当前路由策略 */
    val currentPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT,
    
    /** 总路由尝试次数 */
    val totalRoutingAttempts: Int = 0,
    
    /** 总成功路由次数 */
    val totalSuccessfulRouting: Int = 0,
    
    /** 各Provider的路由统计 */
    val providerStatistics: List<ProviderRoutingStatistics> = emptyList()
) {
    
    /**
     * 计算整体路由成功率
     */
    fun getOverallSuccessRate(): Double {
        return if (totalRoutingAttempts > 0) {
            totalSuccessfulRouting.toDouble() / totalRoutingAttempts
        } else {
            0.0
        }
    }
    
    /**
     * 获取最佳性能的Provider
     */
    fun getBestPerformingProvider(): ProviderRoutingStatistics? {
        return providerStatistics.filter { it.totalAttempts > 0 }
            .maxByOrNull { it.successRate * 0.7 + (1.0 / it.averageResponseTime) * 0.3 }
    }
}

/**
 * 单个Provider路由统计
 */
data class ProviderRoutingStatistics(
    /** Provider类型 */
    val providerType: String,
    
    /** 总尝试次数 */
    val totalAttempts: Int,
    
    /** 成功次数 */
    val successfulAttempts: Int,
    
    /** 失败次数 */
    val failedAttempts: Int,
    
    /** 平均响应时间 */
    val averageResponseTime: Long,
    
    /** 成功率 */
    val successRate: Double,
    
    /** 错误分布 */
    val errorDistribution: Map<TransportError, Int>
) {
    
    /**
     * 计算性能评分
     */
    fun getPerformanceScore(): Double {
        if (totalAttempts == 0) return 0.0
        
        val responseTimeScore = when {
            averageResponseTime <= 1000 -> 1.0
            averageResponseTime <= 3000 -> 0.8
            averageResponseTime <= 10000 -> 0.6
            else -> 0.3
        }
        
        return (successRate * 0.7 + responseTimeScore * 0.3).coerceIn(0.0, 1.0)
    }
} 