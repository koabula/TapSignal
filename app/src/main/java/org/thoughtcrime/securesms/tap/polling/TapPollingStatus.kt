package org.thoughtcrime.securesms.tap.polling

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Tap轮询状态
 * 
 * 提供轮询系统的整体状态信息，包括运行状态、任务统计、性能指标等。
 * 用于监控和管理整个轮询系统的健康状况。
 */
data class TapPollingStatus(
    /**
     * 轮询系统是否正在运行
     */
    val isRunning: Boolean,
    
    /**
     * 当前活跃的轮询目标数量
     */
    val activePollingTargets: Int,
    
    /**
     * 总轮询目标数量
     */
    val totalPollingTargets: Int,
    
    /**
     * 平均轮询间隔（毫秒）
     */
    val averagePollingInterval: Long,
    
    /**
     * 最后轮询时间戳
     */
    val lastPollingTime: Long,
    
    /**
     * 轮询统计信息
     */
    val pollingStatistics: TapPollingStatistics,
    
    /**
     * 资源使用情况
     */
    val resourceUsage: PollingResourceUsage,
    
    /**
     * 系统启动时间
     */
    val systemStartTime: Long = System.currentTimeMillis()
) {
    
    /**
     * 获取系统运行时间（毫秒）
     */
    fun getUptimeMs(): Long {
        return System.currentTimeMillis() - systemStartTime
    }
    
    /**
     * 获取活跃目标占比
     */
    fun getActiveTargetRatio(): Double {
        if (totalPollingTargets == 0) return 0.0
        return activePollingTargets.toDouble() / totalPollingTargets.toDouble()
    }
    
    /**
     * 是否处于健康状态
     */
    fun isHealthy(): Boolean {
        return isRunning && 
               pollingStatistics.getOverallSuccessRate() > 0.8 && // 成功率大于80%
               resourceUsage.memoryUsageKB < 150 * 1024 // 内存使用率低于150MB
    }
    
    /**
     * 获取状态摘要
     */
    fun getSummary(): String {
        return "TapPolling[running=$isRunning, targets=$activePollingTargets/$totalPollingTargets, " +
                "avgInterval=${averagePollingInterval}ms, " +
                "successRate=${String.format("%.1f", pollingStatistics.getOverallSuccessRate() * 100)}%, " +
                "uptime=${getUptimeMs()/1000}s]"
    }
}

/**
 * Tap轮询统计信息 - 简化版本
 * 
 * 收集和汇总轮询系统的基础统计数据
 */
data class TapPollingStatistics(
    /**
     * 系统级统计
     */
    val totalPolls: Long,
    val successfulPolls: Long, 
    val failedPolls: Long,
    val messagesFound: Long,
    val averageResponseTime: Long,
    
    /**
     * 各Provider的统计信息
     */
    val providerStatistics: Map<String, ProviderPollingStats>,
    
    /**
     * 活跃度级别统计
     */
    val activityLevelStats: Map<TransportActivityLevel, ActivityLevelStats>,
    
    /**
     * 最近1小时的统计快照
     */
    val lastHourStats: RecentPollingStats,
    
    /**
     * 最近24小时的统计快照  
     */
    val last24HourStats: RecentPollingStats
) {
    
    /**
     * 获取总体成功率
     */
    fun getOverallSuccessRate(): Double {
        if (totalPolls == 0L) return 0.0
        return successfulPolls.toDouble() / totalPolls.toDouble()
    }
    
    /**
     * 获取消息发现率（每次轮询找到消息的概率）
     */
    fun getMessageDiscoveryRate(): Double {
        if (successfulPolls == 0L) return 0.0
        return messagesFound.toDouble() / successfulPolls.toDouble()
    }
    
    /**
     * 获取最高性能的Provider
     */
    fun getBestPerformingProvider(): String? {
        return providerStatistics.maxByOrNull { it.value.getSuccessRate() }?.key
    }
    
    /**
     * 获取最低性能的Provider
     */
    fun getWorstPerformingProvider(): String? {
        return providerStatistics.minByOrNull { it.value.getSuccessRate() }?.key
    }
}

/**
 * Provider轮询统计信息
 */
data class ProviderPollingStats(
    val providerType: String,
    val totalPolls: Long,
    val successfulPolls: Long,
    val failedPolls: Long,
    val messagesFound: Long,
    val averageResponseTime: Long,
    val activeTargets: Int,
    val totalTargets: Int
) {
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        if (totalPolls == 0L) return 0.0
        return successfulPolls.toDouble() / totalPolls.toDouble()
    }
    
    /**
     * 获取消息发现率
     */
    fun getMessageDiscoveryRate(): Double {
        if (successfulPolls == 0L) return 0.0
        return messagesFound.toDouble() / successfulPolls.toDouble()
    }
    
    /**
     * 获取活跃目标占比
     */
    fun getActiveTargetRatio(): Double {
        if (totalTargets == 0) return 0.0
        return activeTargets.toDouble() / totalTargets.toDouble()
    }
}

/**
 * 活跃度级别统计
 */
data class ActivityLevelStats(
    val activityLevel: TransportActivityLevel,
    val targetCount: Int,
    val totalPolls: Long,
    val successfulPolls: Long,
    val averageInterval: Long
) {
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        if (totalPolls == 0L) return 0.0
        return successfulPolls.toDouble() / totalPolls.toDouble()
    }
}

/**
 * 最近时间段轮询统计
 */
data class RecentPollingStats(
    val timeRangeMs: Long,  // 统计时间范围（毫秒）
    val totalPolls: Long,
    val successfulPolls: Long,
    val messagesFound: Long,
    val averageResponseTime: Long,
    val peakPollingRate: Double,    // 峰值轮询频率（次/秒）
    val averagePollingRate: Double  // 平均轮询频率（次/秒）
) {
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        if (totalPolls == 0L) return 0.0
        return successfulPolls.toDouble() / totalPolls.toDouble()
    }
    
    /**
     * 获取消息发现率
     */
    fun getMessageDiscoveryRate(): Double {
        if (successfulPolls == 0L) return 0.0
        return messagesFound.toDouble() / successfulPolls.toDouble()
    }
}

/**
 * 轮询资源使用情况
 */
data class PollingResourceUsage(
    /**
     * 内存使用量（KB）
     */
    val memoryUsageKB: Long,
    
    /**
     * 活跃线程数
     */
    val activeThreads: Int,
    
    /**
     * 等待队列长度
     */
    val queueLength: Int
) {
    
    /**
     * 是否资源使用过高
     */
    fun isResourceUsageHigh(): Boolean {
        return memoryUsageKB > 100 * 1024 // 100MB
    }
    
    /**
     * 获取资源使用等级
     */
    fun getUsageLevel(): ResourceUsageLevel {
        return when {
            memoryUsageKB > 150 * 1024 -> ResourceUsageLevel.HIGH
            memoryUsageKB > 80 * 1024 -> ResourceUsageLevel.MEDIUM
            else -> ResourceUsageLevel.LOW
        }
    }
}

/**
 * 时间窗口统计
 * 
 * 维护滑动时间窗口内的统计数据
 */
private class TimeWindowStats(private val windowSizeMs: Long) {
    companion object {
        private val MAX_RECORDS = TapPollingConstants.Statistics.MAX_POLL_RECORDS // 最大记录数，防止内存溢出
        private val MEMORY_PRESSURE_THRESHOLD = TapPollingConstants.Statistics.MEMORY_PRESSURE_THRESHOLD_KB * 1024L // 内存压力阈值
        private val CLEANUP_TRIGGER_RATIO = 0.8 // 达到80%容量时触发清理
    }
    
    // 轮询记录环形缓冲区
    private val pollRecords = ArrayDeque<PollRecord>(MAX_RECORDS)
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
    
    // 内存使用统计
    @Volatile
    private var lastCleanupTime = System.currentTimeMillis()
    private val cleanupIntervalMs = 300000L // 5分钟清理间隔
    
    /**
     * 记录一次轮询
     */
    fun recordPoll(timestamp: Long, success: Boolean, responseTimeMs: Long, messageCount: Int) {
        lock.write {
            // 检查内存压力并执行必要的清理
            if (shouldPerformMemoryPressureCleanup(timestamp)) {
                performMemoryPressureCleanup(timestamp)
            }
            
            // 清理过期记录
            cleanupExpiredRecords(timestamp)
            
            // 添加新记录
            if (pollRecords.size >= MAX_RECORDS) {
                pollRecords.removeFirst()
            }
            
            pollRecords.addLast(PollRecord(
                timestamp = timestamp,
                success = success,
                responseTimeMs = responseTimeMs,
                messageCount = messageCount
            ))
        }
    }
    
    /**
     * 检查是否需要执行内存压力清理
     */
    private fun shouldPerformMemoryPressureCleanup(currentTime: Long): Boolean {
        // 检查时间间隔
        if (currentTime - lastCleanupTime < cleanupIntervalMs) {
            return false
        }
        
        // 检查记录数量阈值
        if (pollRecords.size >= (MAX_RECORDS * CLEANUP_TRIGGER_RATIO).toInt()) {
            return true
        }
        
        // 检查系统内存使用情况
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        
        return usedMemory > MEMORY_PRESSURE_THRESHOLD
    }
    
    /**
     * 执行内存压力清理
     */
    private fun performMemoryPressureCleanup(currentTime: Long) {
        try {
            val targetSize = (MAX_RECORDS * 0.5).toInt() // 清理到50%容量
            val removeCount = pollRecords.size - targetSize
            
            if (removeCount > 0) {
                // 优先移除最老的记录
                repeat(removeCount.coerceAtMost(pollRecords.size)) {
                    if (pollRecords.isNotEmpty()) {
                        pollRecords.removeFirst()
                    }
                }
            }
            
            lastCleanupTime = currentTime
            
        } catch (e: Exception) {
            // 静默处理清理异常，避免影响主要功能
        }
    }
    
    /**
     * 获取时间窗口统计
     */
    fun getStats(): RecentPollingStats {
        return lock.read {
            val currentTime = System.currentTimeMillis()
            cleanupExpiredRecords(currentTime)
            
            if (pollRecords.isEmpty()) {
                return@read RecentPollingStats(
                    timeRangeMs = windowSizeMs,
                    totalPolls = 0L,
                    successfulPolls = 0L,
                    messagesFound = 0L,
                    averageResponseTime = 0L,
                    peakPollingRate = 0.0,
                    averagePollingRate = 0.0
                )
            }
            
            val windowStart = currentTime - windowSizeMs
            val validRecords = pollRecords.filter { it.timestamp >= windowStart }
            
            val totalPolls = validRecords.size.toLong()
            val successfulPolls = validRecords.count { it.success }.toLong()
            val messagesFound = validRecords.sumOf { it.messageCount }.toLong()
            
            val averageResponseTime = if (validRecords.isNotEmpty()) {
                validRecords.filter { it.responseTimeMs > 0 }
                    .map { it.responseTimeMs }
                    .average()
                    .let { if (it.isNaN()) 0L else it.toLong() }
            } else {
                0L
            }
            
            // 计算轮询速率
            val effectiveTimeRange = if (validRecords.isNotEmpty()) {
                minOf(windowSizeMs, currentTime - validRecords.first().timestamp)
            } else {
                windowSizeMs
            }
            
            val averageRate = if (effectiveTimeRange > 0) {
                totalPolls.toDouble() * 1000.0 / effectiveTimeRange.toDouble()
            } else {
                0.0
            }
            
            // 计算峰值轮询速率
            val peakRate = calculatePeakRate(validRecords, TapPollingConstants.Statistics.PEAK_RATE_WINDOW_MS)
            
            RecentPollingStats(
                timeRangeMs = effectiveTimeRange,
                totalPolls = totalPolls,
                successfulPolls = successfulPolls,
                messagesFound = messagesFound,
                averageResponseTime = averageResponseTime,
                peakPollingRate = peakRate,
                averagePollingRate = averageRate
            )
        }
    }
    
    /**
     * 计算峰值轮询速率（双指针滑窗优化）
     */
    private fun calculatePeakRate(records: List<PollRecord>, peakWindowMs: Long): Double {
        if (records.isEmpty()) return 0.0
        
        var maxRate = 0.0
        var left = 0
        var right = 0
        
        // 双指针滑动窗口
        while (right < records.size) {
            // 扩展右边界
            val windowStart = records[left].timestamp
            val windowEnd = records[right].timestamp
            
            if (windowEnd - windowStart <= peakWindowMs) {
                // 窗口大小合适，计算当前窗口的速率
                val windowDuration = windowEnd - windowStart
                if (windowDuration > 0) {
                    val windowSize = right - left + 1
                    val rate = windowSize.toDouble() * 1000.0 / windowDuration.toDouble()
                    maxRate = maxOf(maxRate, rate)
                }
                right++
            } else {
                // 窗口过大，收缩左边界
                left++
                if (left > right) {
                    right = left
                }
            }
        }
        
        return maxRate
    }
    
    /**
     * 清理过期记录
     */
    private fun cleanupExpiredRecords(currentTime: Long) {
        val cutoffTime = currentTime - windowSizeMs
        while (pollRecords.isNotEmpty() && pollRecords.first().timestamp < cutoffTime) {
            pollRecords.removeFirst()
        }
    }
    
    /**
     * 重置统计
     */
    fun reset() {
        lock.write {
            pollRecords.clear()
        }
    }
    
    /**
     * 轮询记录
     */
    private data class PollRecord(
        val timestamp: Long,
        val success: Boolean,
        val responseTimeMs: Long,
        val messageCount: Int
    )
}

/**
 * 资源使用等级
 */
enum class ResourceUsageLevel {
    LOW,     // 低使用率
    MEDIUM,  // 中等使用率
    HIGH     // 高使用率
}

/**
 * 轮询统计收集器
 * 
 * 实时收集和计算轮询系统的统计数据，支持动态更新和历史数据维护。
 */
class PollingStatisticsCollector {
    
    // 系统级计数器
    private val totalPolls = AtomicLong(0)
    private val successfulPolls = AtomicLong(0)
    private val failedPolls = AtomicLong(0)
    private val messagesFound = AtomicLong(0)
    private val totalResponseTime = AtomicLong(0)
    private val responseTimeRecords = AtomicLong(0)
    
    // 按Provider分类的统计
    private val providerStats = ConcurrentHashMap<String, ProviderStatCounter>()
    
    // 按活跃度级别分类的统计
    private val activityLevelStats = ConcurrentHashMap<TransportActivityLevel, ActivityLevelCounter>()
    
    // 系统启动时间
    private val systemStartTime = System.currentTimeMillis()
    
    // 时间窗口统计
    private val hourlyStats = TimeWindowStats(TapPollingConstants.Statistics.HOURLY_STATS_WINDOW_MS) // 1小时窗口
    private val dailyStats = TimeWindowStats(TapPollingConstants.Statistics.DAILY_STATS_WINDOW_MS) // 24小时窗口
    
    // 初始化状态
    private var isInitialized = false
    
    /**
     * 初始化轮询统计收集器
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }
        
        try {
            // 重置所有计数器
            totalPolls.set(0)
            successfulPolls.set(0)
            failedPolls.set(0)
            messagesFound.set(0)
            totalResponseTime.set(0)
            responseTimeRecords.set(0)
            
            // 清理分类统计
            providerStats.clear()
            activityLevelStats.clear()
            
            // 验证系统启动时间
            if (systemStartTime <= 0) {
                throw IllegalStateException("系统启动时间无效")
            }
            
            isInitialized = true
            return true
            
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * 记录轮询结果
     */
    fun recordPoll(
        providerType: String,
        activityLevel: TransportActivityLevel,
        success: Boolean,
        responseTimeMs: Long,
        messageCount: Int = 0
    ) {
        // 更新系统级统计
        totalPolls.incrementAndGet()
        if (success) {
            successfulPolls.incrementAndGet()
            messagesFound.addAndGet(messageCount.toLong())
        } else {
            failedPolls.incrementAndGet()
        }
        
        if (responseTimeMs > 0) {
            totalResponseTime.addAndGet(responseTimeMs)
            responseTimeRecords.incrementAndGet()
        }
        
        // 更新Provider统计
        providerStats.computeIfAbsent(providerType) { ProviderStatCounter(it) }
            .recordPoll(success, responseTimeMs, messageCount)
        
        // 更新活跃度级别统计
        activityLevelStats.computeIfAbsent(activityLevel) { ActivityLevelCounter(it) }
            .recordPoll(success)
        
        // 更新时间窗口统计
        val currentTime = System.currentTimeMillis()
        hourlyStats.recordPoll(currentTime, success, responseTimeMs, messageCount)
        dailyStats.recordPoll(currentTime, success, responseTimeMs, messageCount)
    }
    
    /**
     * 获取当前统计快照
     */
    fun getCurrentStatistics(
        providerTargetCounts: Map<String, Pair<Int, Int>> = emptyMap(), // providerType -> (active, total)
        activityTargetCounts: Map<TransportActivityLevel, Int> = emptyMap() // activityLevel -> targetCount
    ): TapPollingStatistics {
        val averageResponseTime = if (responseTimeRecords.get() > 0) {
            totalResponseTime.get() / responseTimeRecords.get()
        } else {
            0L
        }
        
        return TapPollingStatistics(
            totalPolls = totalPolls.get(),
            successfulPolls = successfulPolls.get(),
            failedPolls = failedPolls.get(),
            messagesFound = messagesFound.get(),
            averageResponseTime = averageResponseTime,
            providerStatistics = providerStats.mapValues { (providerType, counter) ->
                val targetCounts = providerTargetCounts[providerType] ?: (0 to 0)
                counter.toStats(targetCounts.first, targetCounts.second)
            },
            activityLevelStats = activityLevelStats.mapValues { (activityLevel, counter) ->
                val targetCount = activityTargetCounts[activityLevel] ?: 0
                counter.toStats(targetCount)
            },
            lastHourStats = hourlyStats.getStats(),
            last24HourStats = dailyStats.getStats()
        )
    }
    

    
    /**
     * 重置统计信息
     */
    fun reset() {
        totalPolls.set(0)
        successfulPolls.set(0)
        failedPolls.set(0)
        messagesFound.set(0)
        totalResponseTime.set(0)
        responseTimeRecords.set(0)
        providerStats.clear()
        activityLevelStats.clear()
        hourlyStats.reset()
        dailyStats.reset()
    }
}

/**
 * Provider统计计数器
 */
private class ProviderStatCounter(private val providerType: String) {
    private val totalPolls = AtomicLong(0)
    private val successfulPolls = AtomicLong(0)
    private val failedPolls = AtomicLong(0)
    private val messagesFound = AtomicLong(0)
    private val totalResponseTime = AtomicLong(0)
    private val responseTimeRecords = AtomicLong(0)
    
    fun recordPoll(success: Boolean, responseTimeMs: Long, messageCount: Int) {
        totalPolls.incrementAndGet()
        if (success) {
            successfulPolls.incrementAndGet()
            messagesFound.addAndGet(messageCount.toLong())
        } else {
            failedPolls.incrementAndGet()
        }
        
        if (responseTimeMs > 0) {
            totalResponseTime.addAndGet(responseTimeMs)
            responseTimeRecords.incrementAndGet()
        }
    }
    
    fun toStats(activeTargets: Int = 0, totalTargets: Int = 0): ProviderPollingStats {
        val averageResponseTime = if (responseTimeRecords.get() > 0) {
            totalResponseTime.get() / responseTimeRecords.get()
        } else {
            0L
        }
        
        return ProviderPollingStats(
            providerType = providerType,
            totalPolls = totalPolls.get(),
            successfulPolls = successfulPolls.get(),
            failedPolls = failedPolls.get(),
            messagesFound = messagesFound.get(),
            averageResponseTime = averageResponseTime,
            activeTargets = activeTargets,
            totalTargets = totalTargets
        )
    }
}

/**
 * 活跃度级别统计计数器
 */
private class ActivityLevelCounter(private val activityLevel: TransportActivityLevel) {
    private val totalPolls = AtomicLong(0)
    private val successfulPolls = AtomicLong(0)
    
    fun recordPoll(success: Boolean) {
        totalPolls.incrementAndGet()
        if (success) {
            successfulPolls.incrementAndGet()
        }
    }
    
    fun toStats(targetCount: Int = 0): ActivityLevelStats {
        return ActivityLevelStats(
            activityLevel = activityLevel,
            targetCount = targetCount,
            totalPolls = totalPolls.get(),
            successfulPolls = successfulPolls.get(),
            averageInterval = activityLevel.baseIntervalMs
        )
    }
} 