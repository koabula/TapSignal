package org.thoughtcrime.securesms.tap.polling

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

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
               resourceUsage.cpuUsagePercent < 50.0 // CPU使用率低于50%
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
 * Tap轮询统计信息
 * 
 * 收集和汇总轮询系统的各种统计数据，支持按Provider类型分类统计。
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
     * CPU使用率百分比
     */
    val cpuUsagePercent: Double,
    
    /**
     * 内存使用量（KB）
     */
    val memoryUsageKB: Long,
    
    /**
     * 网络使用量（KB）
     */
    val networkUsageKB: Long,
    
    /**
     * 电池耗电率（毫安/小时）
     */
    val batteryDrainRate: Double,
    
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
        return cpuUsagePercent > 70.0 || memoryUsageKB > 100 * 1024 // 100MB
    }
    
    /**
     * 获取资源使用等级
     */
    fun getUsageLevel(): ResourceUsageLevel {
        return when {
            cpuUsagePercent > 80.0 || memoryUsageKB > 150 * 1024 -> ResourceUsageLevel.HIGH
            cpuUsagePercent > 50.0 || memoryUsageKB > 80 * 1024 -> ResourceUsageLevel.MEDIUM
            else -> ResourceUsageLevel.LOW
        }
    }
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
    }
    
    /**
     * 获取当前统计快照
     */
    fun getCurrentStatistics(): TapPollingStatistics {
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
            providerStatistics = providerStats.mapValues { it.value.toStats() },
            activityLevelStats = activityLevelStats.mapValues { it.value.toStats() },
            lastHourStats = calculateRecentStats(3600000L), // 1小时
            last24HourStats = calculateRecentStats(86400000L) // 24小时
        )
    }
    
    /**
     * 计算最近时间段的统计
     */
    private fun calculateRecentStats(timeRangeMs: Long): RecentPollingStats {
        // 这里简化实现，实际应该维护时间窗口数据
        val currentTime = System.currentTimeMillis()
        val systemUptime = currentTime - systemStartTime
        val effectiveRange = minOf(timeRangeMs, systemUptime)
        
        val averageRate = if (effectiveRange > 0) {
            totalPolls.get().toDouble() * 1000.0 / effectiveRange.toDouble()
        } else {
            0.0
        }
        
        return RecentPollingStats(
            timeRangeMs = effectiveRange,
            totalPolls = totalPolls.get(),
            successfulPolls = successfulPolls.get(),
            messagesFound = messagesFound.get(),
            averageResponseTime = if (responseTimeRecords.get() > 0) {
                totalResponseTime.get() / responseTimeRecords.get()
            } else {
                0L
            },
            peakPollingRate = averageRate * 1.5, // 简化实现，实际应该跟踪峰值
            averagePollingRate = averageRate
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
    
    fun toStats(): ProviderPollingStats {
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
            activeTargets = 0, // 需要从外部传入
            totalTargets = 0   // 需要从外部传入
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
    
    fun toStats(): ActivityLevelStats {
        return ActivityLevelStats(
            activityLevel = activityLevel,
            targetCount = 0, // 需要从外部传入
            totalPolls = totalPolls.get(),
            successfulPolls = successfulPolls.get(),
            averageInterval = activityLevel.baseIntervalMs
        )
    }
} 