package org.thoughtcrime.securesms.tap.polling

import org.thoughtcrime.securesms.tap.TransportMetadata
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 轮询任务信息
 * 
 * 封装每个轮询任务的完整信息，包括任务状态、统计数据和配置参数。
 * 支持任务的动态调整和错误处理。
 * 所有可变状态都使用原子变量保证线程安全。
 */
class PollingTaskInfo(
    /**
     * 接收者ID - 唯一标识轮询目标
     */
    val recipientId: String,
    
    /**
     * 传输元数据 - 包含轮询所需的连接信息
     */
    val metadata: TransportMetadata,
    
    /**
     * 任务创建时间
     */
    val createdAt: Long = System.currentTimeMillis()
) {
    
    companion object {
        /**
         * 根据Provider类型获取默认轮询间隔
         */
        private fun getDefaultInterval(providerType: String): Long {
            return TapPollingConstants.ProviderIntervals.getBaseInterval(providerType)
        }
        
        /**
         * 创建新的轮询任务信息
         */
        fun create(recipientId: String, metadata: TransportMetadata): PollingTaskInfo {
            return PollingTaskInfo(
                recipientId = recipientId,
                metadata = metadata
            ).apply {
                setCurrentInterval(getDefaultInterval(metadata.providerType))
                setActivityLevel(TransportActivityLevel.INACTIVE)
                setStatus(PollingTaskStatus.CREATED)
            }
        }
    }
    
    // === 线程安全的状态变量 ===
    
    /**
     * 定时任务引用 - 使用原子引用保证线程安全
     */
    private val taskRef = AtomicReference<ScheduledFuture<*>?>(null)
    var task: ScheduledFuture<*>?
        get() = taskRef.get()
        set(value) = taskRef.set(value)
    
    /**
     * 当前轮询间隔（毫秒）
     */
    private val currentIntervalMs = AtomicLong(getDefaultInterval(metadata.providerType))
    
    /**
     * 最后轮询时间戳
     */
    private val lastPollTimeMs = AtomicLong(0L)
    
    /**
     * 连续错误次数
     */
    val consecutiveErrors = AtomicInteger(0)
    
    /**
     * 当前活跃度级别
     */
    private val currentActivityLevel = AtomicReference(TransportActivityLevel.INACTIVE)
    
    /**
     * 轮询状态
     */
    private val currentStatus = AtomicReference(PollingTaskStatus.CREATED)
    
    /**
     * 上次成功轮询时间
     */
    private val lastSuccessTimeMs = AtomicLong(0L)
    
    /**
     * 最近一次轮询处理的文件列表（用于更新数据库状态）
     */
    private val processedFilesRef = AtomicReference<Set<String>>(emptySet())
    var lastProcessedFiles: Set<String>
        get() = processedFilesRef.get()
        set(value) = processedFilesRef.set(value)
    
    /**
     * 执行门闩 - 防止同一任务重叠执行
     */
    private val isExecuting = AtomicBoolean(false)
    
    /**
     * 轮询统计信息 - 线程安全
     */
    val statistics: PollingTaskStatistics = PollingTaskStatistics()
    
    // === 线程安全的访问方法 ===
    
    /**
     * 获取当前轮询间隔
     */
    fun getCurrentInterval(): Long = currentIntervalMs.get()
    
    /**
     * 设置当前轮询间隔
     */
    fun setCurrentInterval(interval: Long) {
        currentIntervalMs.set(interval)
    }
    
    /**
     * 获取最后轮询时间
     */
    fun getLastPollTime(): Long = lastPollTimeMs.get()
    
    /**
     * 获取活跃度级别
     */
    fun getActivityLevel(): TransportActivityLevel = currentActivityLevel.get()
    
    /**
     * 设置活跃度级别
     */
    fun setActivityLevel(level: TransportActivityLevel) {
        currentActivityLevel.set(level)
    }
    
    /**
     * 获取轮询状态
     */
    val status: PollingTaskStatus
        get() = currentStatus.get()
    
    /**
     * 设置轮询状态
     */
    fun setStatus(status: PollingTaskStatus) {
        currentStatus.set(status)
    }
    
    /**
     * 获取上次成功时间
     */
    fun getLastSuccessTime(): Long = lastSuccessTimeMs.get()
    
    // === 业务方法 ===
    
    /**
     * 更新轮询时间
     */
    fun updatePollTime() {
        lastPollTimeMs.set(System.currentTimeMillis())
        statistics.incrementTotalPolls()
    }
    
    /**
     * 记录成功轮询
     */
    fun recordSuccess() {
        lastSuccessTimeMs.set(System.currentTimeMillis())
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
    fun getErrorBackoffTime(
        baseBackoffMs: Long = TapPollingConstants.ErrorBackoff.BASE_BACKOFF_MS, 
        maxBackoffMs: Long = TapPollingConstants.ErrorBackoff.MAX_BACKOFF_MS
    ): Long {
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
        val oldLevel = currentActivityLevel.get()
        if (oldLevel != newLevel) {
            currentActivityLevel.set(newLevel)
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
        setStatus(PollingTaskStatus.STOPPED)
    }
    
    /**
     * 获取任务摘要信息
     */
    fun getSummary(): String {
        return "PollingTask[recipient=$recipientId, provider=${metadata.providerType}, " +
                "status=${currentStatus.get()}, interval=${currentIntervalMs.get()}ms, errors=${consecutiveErrors.get()}, " +
                "activity=${currentActivityLevel.get()}, successRate=${String.format("%.2f", getSuccessRate() * 100)}%]"
    }
    
    /**
     * 尝试开始执行轮询任务
     * @return true 如果成功获取执行权，false 如果任务已在执行中
     */
    fun tryStartExecution(): Boolean {
        return isExecuting.compareAndSet(false, true)
    }
    
    /**
     * 结束轮询任务执行
     */
    fun finishExecution() {
        isExecuting.set(false)
    }
    
    /**
     * 检查任务是否正在执行中
     */
    fun isCurrentlyExecuting(): Boolean {
        return isExecuting.get()
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
 * 轮询任务统计信息 - 线程安全版本
 */
class PollingTaskStatistics {
    
    /**
     * 总轮询次数
     */
    val totalPolls = AtomicLong(0L)
    
    /**
     * 成功轮询次数
     */
    val successfulPolls = AtomicLong(0L)
    
    /**
     * 失败轮询次数
     */
    val failedPolls = AtomicLong(0L)
    
    /**
     * 找到的消息总数
     */
    val messagesFound = AtomicLong(0L)
    
    /**
     * 响应时间总和（用于计算平均值）
     */
    private val totalResponseTimeMs = AtomicLong(0L)
    
    /**
     * 有响应时间记录的轮询次数
     */
    private val responseTimeRecords = AtomicLong(0L)
    
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
        if (responseTimeMs > 0) {
            totalResponseTimeMs.addAndGet(responseTimeMs)
            responseTimeRecords.incrementAndGet()
        }
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