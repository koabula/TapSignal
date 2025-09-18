package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.TransportChannel
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.TransportToken
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.max

/**
 * 智能轮询策略
 * 
 * 核心轮询算法实现，负责根据多维度因素计算最优轮询间隔：
 * 1. 错误退避策略 - 避免无效轮询
 * 2. Token过期检查 - 处理权限失效
 * 3. Provider特定优化 - 不同服务不同策略  
 * 4. 活跃度计算 - 根据通信频率调整
 * 5. 消息大小和类型优化 - 智能负载平衡
 */
class TapIntelligentPollingStrategy(private val context: Context) {
    
    companion object {
        private const val TAG = "TapIntelligentPollingStrategy"
        
        // 活跃度判断阈值
        private const val ACTIVE_THRESHOLD_MINUTES = 5L        // 5分钟内有活动视为活跃
        private const val INACTIVE_THRESHOLD_HOURS = 1L        // 1小时内有活动视为非活跃
        private const val BACKGROUND_THRESHOLD_HOURS = 24L     // 24小时内有活动视为后台
        private const val SUSPENDED_THRESHOLD_DAYS = 7L        // 7天内有活动视为暂停
        
        // 错误处理常量
        private const val MAX_CONSECUTIVE_ERRORS = 10          // 最大连续错误次数
        private const val ERROR_BACKOFF_BASE = 2000L          // 错误退避基础时间: 2秒
        private const val MAX_ERROR_BACKOFF = 180000L         // 最大错误退避时间: 3分钟
        
        // Provider特定优化间隔
        private const val EMAIL_BASE_INTERVAL = 60000L        // 邮件Provider基础间隔: 1分钟
        private const val IPFS_BASE_INTERVAL = 30000L         // IPFS Provider基础间隔: 30秒
        private const val GIT_BASE_INTERVAL = 120000L         // Git Provider基础间隔: 2分钟
        private const val NAS_BASE_INTERVAL = 30000L          // NAS Provider基础间隔: 30秒
        
        // Token过期提前预警时间
        private const val TOKEN_EXPIRY_WARNING_MS = 300000L   // 5分钟
        
        // 网络质量调整系数
        private const val NETWORK_QUALITY_EXCELLENT = 0.8     // 网络极佳 - 减少20%间隔
        private const val NETWORK_QUALITY_GOOD = 1.0          // 网络良好 - 标准间隔
        private const val NETWORK_QUALITY_POOR = 1.5          // 网络较差 - 增加50%间隔
        private const val NETWORK_QUALITY_BAD = 2.0           // 网络很差 - 增加100%间隔
    }
    
    /**
     * 计算智能轮询间隔（核心算法）
     * 
     * 这是整个轮询系统的核心算法，综合考虑多个因素：
     * @param recipientId 接收者ID
     * @param metadata 传输元数据
     * @param channel 传输通道（可选）
     * @param errorCount 连续错误次数
     * @param networkQuality 网络质量（可选）
     * @return 计算得出的轮询间隔（毫秒）
     */
    fun calculatePollingInterval(
        recipientId: String, 
        metadata: TransportMetadata,
        channel: TransportChannel?,
        errorCount: Int = 0,
        networkQuality: NetworkQuality = NetworkQuality.UNKNOWN
    ): Long {
        Log.d(TAG, "计算轮询间隔: recipient=$recipientId, provider=${metadata.providerType}, errors=$errorCount")
        
        try {
            // 1. 错误退避策略（最高优先级）
            if (errorCount > 0) {
                val backoffTime = calculateErrorBackoff(errorCount)
                Log.d(TAG, "应用错误退避策略: errorCount=$errorCount, backoff=${backoffTime}ms")
                return backoffTime
            }
            
            // 2. Token过期检查
            if (isTokenNearExpiry(metadata.token)) {
                Log.d(TAG, "Token即将过期，降低轮询频率")
                return TransportActivityLevel.BACKGROUND.baseIntervalMs
            }
            
            // 3. Provider特定基础间隔
            val baseInterval = getProviderBaseInterval(metadata.providerType)
            Log.d(TAG, "Provider基础间隔: ${metadata.providerType} -> ${baseInterval}ms")
            
            // 4. 活跃度计算和调整
            val activityLevel = calculateActivityLevel(channel)
            val activityMultiplier = getActivityMultiplier(activityLevel)
            Log.d(TAG, "活跃度调整: level=$activityLevel, multiplier=$activityMultiplier")
            
            // 5. 消息大小和类型优化
            val sizeMultiplier = calculateSizeMultiplier(channel)
            Log.d(TAG, "消息大小调整: multiplier=$sizeMultiplier")
            
            // 6. 网络质量调整
            val networkMultiplier = getNetworkQualityMultiplier(networkQuality)
            Log.d(TAG, "网络质量调整: quality=$networkQuality, multiplier=$networkMultiplier")
            
            // 7. 系统负载调整（简化实现）
            val loadMultiplier = getSystemLoadMultiplier()
            Log.d(TAG, "系统负载调整: multiplier=$loadMultiplier")
            
            // 综合计算最终间隔
            val calculatedInterval = (baseInterval * activityMultiplier * sizeMultiplier * networkMultiplier * loadMultiplier).toLong()
            
            // 应用最小和最大间隔限制
            val finalInterval = applyIntervalLimits(calculatedInterval, metadata.providerType)
            
            Log.d(TAG, "最终轮询间隔: ${finalInterval}ms (原始: ${calculatedInterval}ms)")
            return finalInterval
            
        } catch (e: Exception) {
            Log.e(TAG, "计算轮询间隔时发生错误", e)
            // 发生错误时返回默认间隔
            return getProviderBaseInterval(metadata.providerType)
        }
    }
    
    /**
     * 判断是否应该跳过轮询
     * 
     * @param recipientId 接收者ID  
     * @param metadata 传输元数据
     * @param channel 传输通道
     * @return true表示应该跳过轮询
     */
    fun shouldSkipPolling(
        recipientId: String,
        metadata: TransportMetadata,
        channel: TransportChannel?
    ): Boolean {
        // 1. 检查Token是否已过期
        if (isTokenExpired(metadata.token)) {
            Log.d(TAG, "跳过轮询: Token已过期 - recipient=$recipientId")
            return true
        }
        
        // 2. 检查通道状态
        if (channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FAILED ||
            channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.CLOSED) {
            Log.d(TAG, "跳过轮询: 通道状态异常 - recipient=$recipientId, status=${channel.status}")
            return true
        }
        
        // 3. 检查活跃度级别
        val activityLevel = calculateActivityLevel(channel)
        if (activityLevel == TransportActivityLevel.DORMANT) {
            Log.d(TAG, "跳过轮询: 通信已休眠 - recipient=$recipientId")
            return true
        }
        
        // 4. 检查Provider可用性（简化实现）
        if (!isProviderAvailable(metadata.providerType)) {
            Log.d(TAG, "跳过轮询: Provider不可用 - provider=${metadata.providerType}")
            return true
        }
        
        return false
    }
    
    /**
     * 计算传输活跃度级别
     * 
     * @param channel 传输通道
     * @return 活跃度级别
     */
    fun calculateActivityLevel(channel: TransportChannel?): TransportActivityLevel {
        if (channel == null) {
            return TransportActivityLevel.INACTIVE
        }
        
        val currentTime = System.currentTimeMillis()
        val lastActiveTime = channel.lastActiveAt
        val timeDiffMs = currentTime - lastActiveTime
        
        // 基于最后活动时间计算活跃度
        val timeBasedLevel = when {
            timeDiffMs <= ACTIVE_THRESHOLD_MINUTES * 60 * 1000L -> TransportActivityLevel.ACTIVE
            timeDiffMs <= INACTIVE_THRESHOLD_HOURS * 60 * 60 * 1000L -> TransportActivityLevel.INACTIVE  
            timeDiffMs <= BACKGROUND_THRESHOLD_HOURS * 60 * 60 * 1000L -> TransportActivityLevel.BACKGROUND
            timeDiffMs <= SUSPENDED_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L -> TransportActivityLevel.SUSPENDED
            else -> TransportActivityLevel.DORMANT
        }
        
        // TODO: 可以结合消息频率等其他因素进一步优化
        return timeBasedLevel
    }
    
    /**
     * 获取Provider特定的基础间隔
     * 
     * @param providerType Provider类型
     * @return 基础轮询间隔（毫秒）
     */
    private fun getProviderBaseInterval(providerType: String): Long {
        return when (providerType.lowercase()) {
            "cos" -> TransportActivityLevel.ACTIVE.baseIntervalMs     // 5秒
            "email" -> EMAIL_BASE_INTERVAL                            // 1分钟
            "ipfs" -> IPFS_BASE_INTERVAL                              // 30秒
            "git" -> GIT_BASE_INTERVAL                                // 2分钟
            "nas" -> NAS_BASE_INTERVAL                                // 30秒
            else -> {
                Log.w(TAG, "未知的Provider类型: $providerType，使用默认间隔")
                TransportActivityLevel.INACTIVE.baseIntervalMs       // 30秒
            }
        }
    }
    
    /**
     * 错误退避算法（指数退避）
     * 
     * @param errorCount 连续错误次数
     * @return 退避时间（毫秒）
     */
    private fun calculateErrorBackoff(errorCount: Int): Long {
        if (errorCount <= 0) return 0L
        
        // 限制错误次数以避免溢出
        val limitedErrorCount = min(errorCount, 15)
        
        // 指数退避：2^n * base_time
        val backoffMultiplier = 2.0.pow(limitedErrorCount.toDouble()).toLong()
        val backoffTime = ERROR_BACKOFF_BASE * backoffMultiplier
        
        // 应用最大退避时间限制
        val finalBackoff = min(backoffTime, MAX_ERROR_BACKOFF)
        
        Log.d(TAG, "错误退避计算: errorCount=$errorCount, backoff=${finalBackoff}ms")
        return finalBackoff
    }
    
    /**
     * 检查Token是否即将过期
     * 
     * @param token 传输Token
     * @return true表示即将过期
     */
    private fun isTokenNearExpiry(token: TransportToken?): Boolean {
        if (token == null) return false
        
        val currentTime = System.currentTimeMillis()
        val expiryWarningTime = token.expirationTime - TOKEN_EXPIRY_WARNING_MS
        
        return currentTime >= expiryWarningTime
    }
    
    /**
     * 检查Token是否已过期
     * 
     * @param token 传输Token
     * @return true表示已过期
     */
    private fun isTokenExpired(token: TransportToken?): Boolean {
        return token?.isExpired ?: false
    }
    
    /**
     * 根据活跃度级别获取间隔调整系数
     * 
     * @param activityLevel 活跃度级别
     * @return 调整系数
     */
    private fun getActivityMultiplier(activityLevel: TransportActivityLevel): Double {
        return when (activityLevel) {
            TransportActivityLevel.ACTIVE -> 1.0      // 标准频率
            TransportActivityLevel.INACTIVE -> 1.5    // 降低33%频率
            TransportActivityLevel.BACKGROUND -> 2.0  // 降低50%频率
            TransportActivityLevel.SUSPENDED -> 4.0   // 降低75%频率
            TransportActivityLevel.DORMANT -> 8.0     // 降低87.5%频率
        }
    }
    
    /**
     * 根据消息大小计算间隔调整系数
     * 
     * @param channel 传输通道
     * @return 调整系数
     */
    private fun calculateSizeMultiplier(channel: TransportChannel?): Double {
        // 简化实现：如果有大量数据传输，稍微降低轮询频率
        // 实际实现中可以根据历史传输数据大小来调整
        
        if (channel == null) return 1.0
        
        // TODO: 实现基于历史消息大小的智能调整
        // 这里提供一个简化的示例逻辑
        
        return 1.0  // 暂时返回标准系数
    }
    
    /**
     * 根据网络质量获取调整系数
     * 
     * @param networkQuality 网络质量
     * @return 调整系数
     */
    private fun getNetworkQualityMultiplier(networkQuality: NetworkQuality): Double {
        return when (networkQuality) {
            NetworkQuality.EXCELLENT -> NETWORK_QUALITY_EXCELLENT
            NetworkQuality.GOOD -> NETWORK_QUALITY_GOOD
            NetworkQuality.FAIR -> NETWORK_QUALITY_POOR
            NetworkQuality.POOR -> NETWORK_QUALITY_BAD
            NetworkQuality.UNKNOWN -> NETWORK_QUALITY_GOOD // 默认假设网络良好
        }
    }
    
    /**
     * 获取系统负载调整系数
     * 
     * @return 调整系数
     */
    private fun getSystemLoadMultiplier(): Double {
        // 简化实现：检查系统资源使用情况
        try {
            // 获取可用内存
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val memoryUsageRatio = usedMemory.toDouble() / totalMemory.toDouble()
            
            // 基于内存使用率调整轮询频率
            return when {
                memoryUsageRatio > 0.9 -> 2.0  // 内存使用超过90%，降低频率
                memoryUsageRatio > 0.7 -> 1.5  // 内存使用超过70%，适度降低频率
                else -> 1.0                    // 内存充足，标准频率
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取系统负载信息失败", e)
            return 1.0
        }
    }
    
    /**
     * 应用间隔限制
     * 
     * @param calculatedInterval 计算得出的间隔
     * @param providerType Provider类型
     * @return 限制后的间隔
     */
    private fun applyIntervalLimits(calculatedInterval: Long, providerType: String): Long {
        // 每个Provider都有最小和最大间隔限制
        val limits = getProviderIntervalLimits(providerType)
        return max(limits.minInterval, min(calculatedInterval, limits.maxInterval))
    }
    
    /**
     * 获取Provider的间隔限制
     * 
     * @param providerType Provider类型
     * @return 间隔限制
     */
    private fun getProviderIntervalLimits(providerType: String): IntervalLimits {
        return when (providerType.lowercase()) {
            "cos" -> IntervalLimits(1000L, 300000L)      // 1秒 - 5分钟
            "email" -> IntervalLimits(10000L, 3600000L)   // 10秒 - 1小时
            "ipfs" -> IntervalLimits(5000L, 600000L)      // 5秒 - 10分钟
            "git" -> IntervalLimits(30000L, 7200000L)     // 30秒 - 2小时
            "nas" -> IntervalLimits(5000L, 600000L)       // 5秒 - 10分钟
            else -> IntervalLimits(5000L, 600000L)        // 默认: 5秒 - 10分钟
        }
    }
    
    /**
     * 检查Provider是否可用
     * 
     * @param providerType Provider类型
     * @return true表示可用
     */
    private fun isProviderAvailable(providerType: String): Boolean {
        // 简化实现：假设所有Provider都可用
        // 实际实现中应该检查网络连接、服务状态等
        return true
    }
    
    /**
     * 获取轮询统计信息
     * 
     * @return 轮询统计信息
     */
    fun getPollingStatistics(): TapPollingStatistics? {
        // 这个方法将在集成到TapPollingService后实现
        // 目前返回null作为占位符
        return null
    }
    
    /**
     * 优化建议
     * 
     * 基于当前轮询表现提供优化建议
     */
    fun getOptimizationSuggestions(taskInfo: PollingTaskInfo): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        // 检查错误率
        if (taskInfo.getSuccessRate() < 0.8) {
            suggestions.add(OptimizationSuggestion(
                type = OptimizationType.INCREASE_INTERVAL,
                reason = "成功率较低 (${String.format("%.1f", taskInfo.getSuccessRate() * 100)}%)，建议降低轮询频率",
                suggestedInterval = taskInfo.currentInterval * 2
            ))
        }
        
        // 检查响应时间
        val avgResponseTime = taskInfo.getAverageResponseTime()
        if (avgResponseTime > 10000) { // 超过10秒
            suggestions.add(OptimizationSuggestion(
                type = OptimizationType.INCREASE_INTERVAL,
                reason = "平均响应时间过长 (${avgResponseTime}ms)，建议降低轮询频率",
                suggestedInterval = (taskInfo.currentInterval * 1.5).toLong()
            ))
        }
        
        // 检查活跃度
        if (taskInfo.activityLevel.isLessActiveThan(TransportActivityLevel.BACKGROUND)) {
            suggestions.add(OptimizationSuggestion(
                type = OptimizationType.REDUCE_FREQUENCY,
                reason = "通信不活跃，建议降低轮询频率或暂停轮询",
                suggestedInterval = taskInfo.activityLevel.baseIntervalMs
            ))
        }
        
        return suggestions
    }
}

/**
 * 网络质量枚举
 */
enum class NetworkQuality {
    EXCELLENT,  // 网络极佳
    GOOD,       // 网络良好
    FAIR,       // 网络一般
    POOR,       // 网络较差
    UNKNOWN     // 未知
}

/**
 * 间隔限制
 */
private data class IntervalLimits(
    val minInterval: Long,
    val maxInterval: Long
)

/**
 * 优化建议
 */
data class OptimizationSuggestion(
    val type: OptimizationType,
    val reason: String,
    val suggestedInterval: Long? = null,
    val priority: OptimizationPriority = OptimizationPriority.MEDIUM
)

/**
 * 优化类型
 */
enum class OptimizationType {
    INCREASE_INTERVAL,     // 增加轮询间隔
    DECREASE_INTERVAL,     // 减少轮询间隔  
    REDUCE_FREQUENCY,      // 降低轮询频率
    PAUSE_POLLING,         // 暂停轮询
    CHANGE_STRATEGY        // 更改策略
}

/**
 * 优化优先级
 */
enum class OptimizationPriority {
    HIGH,    // 高优先级
    MEDIUM,  // 中优先级
    LOW      // 低优先级
} 