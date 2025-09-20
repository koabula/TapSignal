package org.thoughtcrime.securesms.tap.polling

import org.thoughtcrime.securesms.tap.TransportMetadata
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 轮询任务信息
 * 
 * 封装每个轮询任务的完整信息，包括任务状态、统计数据和配置参数。
 * 支持任务的动态调整和错误处理。
 */
data class PollingTaskInfo(
    /**
     * 接收者ID - 唯一标识轮询目标
     */
    val recipientId: String,
    
    /**
     * 传输元数据 - 包含轮询所需的连接信息
     */
    val metadata: TransportMetadata,
    
    /**
     * 定时任务 - 可为null表示任务未启动或已停止
     */
    var task: ScheduledFuture<*>? = null,
    
    /**
     * 当前轮询间隔（毫秒）
     */
    var currentInterval: Long = metadata.providerType.let { getDefaultInterval(it) },
    
    /**
     * 最后轮询时间戳
     */
    val lastPollTime: AtomicLong = AtomicLong(0L),
    
    /**
     * 连续错误次数
     */
    val consecutiveErrors: AtomicInteger = AtomicInteger(0),
    
    /**
     * 当前活跃度级别
     */
    var activityLevel: TransportActivityLevel = TransportActivityLevel.INACTIVE,
    
    /**
     * 轮询状态
     */
    var status: PollingTaskStatus = PollingTaskStatus.CREATED,
    
    /**
     * 任务创建时间
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * 上次成功轮询时间
     */
    val lastSuccessTime: AtomicLong = AtomicLong(0L),
    
    /**
     * 轮询统计信息
     */
    val statistics: PollingTaskStatistics = PollingTaskStatistics(),
    
    /**
     * 最近一次轮询处理的文件列表（用于更新数据库状态）
     */
    var lastProcessedFiles: Set<String> = emptySet()
) {
    
    companion object {
        /**
         * 根据Provider类型获取默认轮询间隔
         */
        private fun getDefaultInterval(providerType: String): Long {
            return when (providerType) {
                "cos" -> 5000L      // COS: 5秒
                "email" -> 60000L   // Email: 1分钟
                "ipfs" -> 30000L    // IPFS: 30秒
                "git" -> 120000L    // Git: 2分钟
                "nas" -> 30000L     // NAS: 30秒
                else -> 30000L      // 默认: 30秒
            }
        }
        
        /**
         * 创建新的轮询任务信息
         */
        fun create(recipientId: String, metadata: TransportMetadata): PollingTaskInfo {
            return PollingTaskInfo(
                recipientId = recipientId,
                metadata = metadata,
                currentInterval = getDefaultInterval(metadata.providerType)
            )
        }
    }
    
    /**
     * 更新轮询时间
     */
    fun updatePollTime() {
        lastPollTime.set(System.currentTimeMillis())
        statistics.incrementTotalPolls()
    }
    
    /**
     * 记录成功轮询
     */
    fun recordSuccess() {
        lastSuccessTime.set(System.currentTimeMillis())
        consecutiveErrors.set(0)
        statistics.incrementSuccessfulPolls()
    }
    
    /**
     * 记录轮询错误
     */
    fun recordError() {
        consecutiveErrors.incrementAndGet()
        statistics.incrementFailedPolls()
    }
    
    /**
     * 记录找到的消息数量
     */
    fun recordMessagesFound(count: Int) {
        statistics.addMessagesFound(count)
    }
    
    /**
     * 是否处于错误状态
     */
    fun isInErrorState(): Boolean {
        return consecutiveErrors.get() > 0
    }
    
    /**
     * 是否应该暂停轮询（错误过多）
     */
    fun shouldSuspendPolling(maxErrors: Int = 10): Boolean {
        return consecutiveErrors.get() >= maxErrors
    }
    
    /**
     * 获取错误退避时间
     */
    fun getErrorBackoffTime(baseBackoffMs: Long = 2000L, maxBackoffMs: Long = 180000L): Long {
        val errorCount = consecutiveErrors.get()
        if (errorCount <= 0) return 0L
        
        val backoffMultiplier = Math.pow(2.0, errorCount.coerceAtMost(10).toDouble()).toLong()
        val backoffTime = baseBackoffMs * backoffMultiplier
        return backoffTime.coerceAtMost(maxBackoffMs)
    }
    
    /**
     * 更新活跃度级别
     */
    fun updateActivityLevel(newLevel: TransportActivityLevel): Boolean {
        if (activityLevel != newLevel) {
            activityLevel = newLevel
            return true
        }
        return false
    }
    
    /**
     * 计算平均响应时间
     */
    fun getAverageResponseTime(): Long {
        return statistics.getAverageResponseTime()
    }
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        val totalPolls = statistics.totalPolls.get()
        if (totalPolls == 0L) return 0.0
        
        val successfulPolls = statistics.successfulPolls.get()
        return successfulPolls.toDouble() / totalPolls.toDouble()
    }
    
    /**
     * 清理任务资源
     */
    fun cleanup() {
        task?.cancel(false)
        task = null
        status = PollingTaskStatus.STOPPED
    }
    
    /**
     * 获取任务摘要信息
     */
    fun getSummary(): String {
        return "PollingTask[recipient=$recipientId, provider=${metadata.providerType}, " +
                "status=$status, interval=${currentInterval}ms, errors=${consecutiveErrors.get()}, " +
                "activity=$activityLevel, successRate=${String.format("%.2f", getSuccessRate() * 100)}%]"
    }
}

/**
 * 轮询任务状态
 */
enum class PollingTaskStatus {
    /**
     * 已创建但未启动
     */
    CREATED,
    
    /**
     * 正在运行
     */
    RUNNING,
    
    /**
     * 已暂停
     */
    PAUSED,
    
    /**
     * 因错误暂停
     */
    ERROR_SUSPENDED,
    
    /**
     * 已停止
     */
    STOPPED,
    
    /**
     * 正在执行轮询
     */
    POLLING
}

/**
 * 轮询任务统计信息
 */
data class PollingTaskStatistics(
    /**
     * 总轮询次数
     */
    val totalPolls: AtomicLong = AtomicLong(0L),
    
    /**
     * 成功轮询次数
     */
    val successfulPolls: AtomicLong = AtomicLong(0L),
    
    /**
     * 失败轮询次数
     */
    val failedPolls: AtomicLong = AtomicLong(0L),
    
    /**
     * 找到的消息总数
     */
    val messagesFound: AtomicLong = AtomicLong(0L),
    
    /**
     * 响应时间总和（用于计算平均值）
     */
    val totalResponseTimeMs: AtomicLong = AtomicLong(0L),
    
    /**
     * 有响应时间记录的轮询次数
     */
    val responseTimeRecords: AtomicLong = AtomicLong(0L)
) {
    
    /**
     * 增加总轮询次数
     */
    fun incrementTotalPolls() {
        totalPolls.incrementAndGet()
    }
    
    /**
     * 增加成功轮询次数
     */
    fun incrementSuccessfulPolls() {
        successfulPolls.incrementAndGet()
    }
    
    /**
     * 增加失败轮询次数
     */
    fun incrementFailedPolls() {
        failedPolls.incrementAndGet()
    }
    
    /**
     * 增加找到的消息数量
     */
    fun addMessagesFound(count: Int) {
        messagesFound.addAndGet(count.toLong())
    }
    
    /**
     * 记录响应时间
     */
    fun recordResponseTime(responseTimeMs: Long) {
        totalResponseTimeMs.addAndGet(responseTimeMs)
        responseTimeRecords.incrementAndGet()
    }
    
    /**
     * 获取平均响应时间
     */
    fun getAverageResponseTime(): Long {
        val records = responseTimeRecords.get()
        if (records == 0L) return 0L
        
        return totalResponseTimeMs.get() / records
    }
    
    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        val total = totalPolls.get()
        if (total == 0L) return 0.0
        
        return successfulPolls.get().toDouble() / total.toDouble()
    }
    
    /**
     * 重置统计信息
     */
    fun reset() {
        totalPolls.set(0L)
        successfulPolls.set(0L)
        failedPolls.set(0L)
        messagesFound.set(0L)
        totalResponseTimeMs.set(0L)
        responseTimeRecords.set(0L)
    }
} 