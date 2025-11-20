package org.thoughtcrime.securesms.tap.polling

import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.TransportMetadata
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 轮询任务调度器 (已废弃)
 * 
 * 此类已被废弃,V2模式下完全依赖 WebSocket 推送,不再需要周期性轮询调度。
 * 仅保留作为历史兼容,在 TapPollingService.ENABLE_POLLING = false 时不会被使用。
 * 
 * @deprecated V2模式使用 WebSocket 推送,不需要轮询调度
 */
@Deprecated(
    message = "V2模式使用 WebSocket 推送,不需要轮询调度",
    level = DeprecationLevel.WARNING
)
class PollingTaskScheduler(
    private val pollingExecutor: ScheduledThreadPoolExecutor,
    private val deviceCapabilityProvider: DeviceCapabilityProvider
) {
    
    companion object {
        private const val TAG = "PollingTaskScheduler"
    }
    
    // 任务管理
    private val pollingTasks = ConcurrentHashMap<String, PollingTaskInfo>()
    private val schedulerLock = ReentrantReadWriteLock()
    
    // 状态管理
    private val isActive = AtomicBoolean(false)
    
    /**
     * 启动调度器
     */
    fun start(): Boolean {
        return schedulerLock.write {
            if (isActive.compareAndSet(false, true)) {
                Log.i(TAG, "轮询任务调度器已启动")
                true
            } else {
                Log.w(TAG, "轮询任务调度器已在运行")
                false
            }
        }
    }
    
    /**
     * 停止调度器
     */
    fun stop() {
        schedulerLock.write {
            if (isActive.compareAndSet(true, false)) {
                Log.i(TAG, "停止轮询任务调度器...")
                
                // 取消所有任务
                val tasks = pollingTasks.values.toList()
                tasks.forEach { taskInfo ->
                    taskInfo.cleanup()
                }
                pollingTasks.clear()
                
                Log.i(TAG, "轮询任务调度器已停止，清理了${tasks.size}个任务")
            }
        }
    }
    
    /**
     * 添加轮询任务
     */
    fun addTask(
        recipientId: String, 
        metadata: TransportMetadata
    ): Boolean {
        if (!isActive.get()) {
            Log.w(TAG, "调度器未启动，无法添加任务")
            return false
        }
        
        return schedulerLock.write {
            if (pollingTasks.containsKey(recipientId)) {
                Log.w(TAG, "任务已存在: $recipientId")
                return@write false
            }
            
            try {
                // 创建任务信息
                val taskInfo = PollingTaskInfo.create(recipientId, metadata)
                
                // 计算初始轮询间隔
                val initialInterval = calculateInitialInterval(metadata)
                taskInfo.setCurrentInterval(initialInterval)
                
                // 调度任务执行
                val scheduledTask = scheduleTaskExecution(taskInfo) { task ->
                    // 这是一个简单的任务执行占位符
                    // 实际使用时应该连接到具体的轮询执行器
                    Log.d(TAG, "执行轮询任务: ${task.recipientId}")
                }
                taskInfo.task = scheduledTask
                taskInfo.setStatus(PollingTaskStatus.RUNNING)
                
                // 添加到任务列表
                pollingTasks[recipientId] = taskInfo
                
                Log.i(TAG, "任务添加成功: recipient=$recipientId, interval=${initialInterval}ms")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "添加任务失败: recipient=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 移除轮询任务
     */
    fun removeTask(recipientId: String): Boolean {
        return schedulerLock.write {
            val taskInfo = pollingTasks.remove(recipientId)
            if (taskInfo != null) {
                taskInfo.cleanup()
                Log.i(TAG, "任务移除成功: $recipientId")
                true
            } else {
                Log.w(TAG, "任务不存在: $recipientId")
                false
            }
        }
    }
    
    /**
     * 调整任务轮询间隔
     */
    fun adjustTaskInterval(recipientId: String, changeType: IntervalChangeType): Boolean {
        return schedulerLock.write {
            val taskInfo = pollingTasks[recipientId]
            if (taskInfo == null) {
                Log.w(TAG, "调整间隔失败，任务不存在: $recipientId")
                return@write false
            }
            
            try {
                val currentInterval = taskInfo.getCurrentInterval()
                val newInterval = calculateNewInterval(currentInterval, changeType, taskInfo.metadata.providerType)
                
                if (newInterval != currentInterval) {
                    // 取消当前任务
                    taskInfo.task?.cancel(false)
                    
                                         // 更新间隔并重新调度
                     taskInfo.setCurrentInterval(newInterval)
                     val newTask = scheduleTaskExecution(taskInfo) { task ->
                         // 重新调度时使用相同的执行器占位符
                         Log.d(TAG, "执行重新调度的轮询任务: ${task.recipientId}")
                     }
                     taskInfo.task = newTask
                    
                    Log.d(TAG, "任务间隔调整成功: $recipientId, ${currentInterval}ms -> ${newInterval}ms")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "调整任务间隔失败: $recipientId", e)
                false
            }
        }
    }
    
    /**
     * 获取所有活跃任务
     */
    fun getActiveTasks(): List<PollingTaskInfo> {
        return schedulerLock.read {
            pollingTasks.values.filter { 
                it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING 
            }
        }
    }
    
    /**
     * 获取任务信息
     */
    fun getTaskInfo(recipientId: String): PollingTaskInfo? {
        return schedulerLock.read {
            pollingTasks[recipientId]
        }
    }
    
    /**
     * 获取所有任务统计
     */
    fun getTaskStatistics(): SchedulerStatistics {
        return schedulerLock.read {
            val totalTasks = pollingTasks.size
            val activeTasks = pollingTasks.values.count { 
                it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING 
            }
            val errorTasks = pollingTasks.values.count { 
                it.status == PollingTaskStatus.ERROR_SUSPENDED 
            }
            
            SchedulerStatistics(
                totalTasks = totalTasks,
                activeTasks = activeTasks,
                errorTasks = errorTasks,
                averageInterval = if (activeTasks > 0) {
                    getActiveTasks().map { it.getCurrentInterval() }.average().toLong()
                } else {
                    0L
                }
            )
        }
    }
    
    /**
     * 执行任务清理
     */
    fun performTaskCleanup() {
        schedulerLock.write {
            val currentTime = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()
            
            pollingTasks.forEach { (recipientId, taskInfo) ->
                when {
                    // 清理长时间错误暂停的任务
                    taskInfo.status == PollingTaskStatus.ERROR_SUSPENDED && 
                            currentTime - taskInfo.getLastSuccessTime() > TapPollingConstants.PollingService.ERROR_TASK_CLEANUP_MS -> {
                        Log.i(TAG, "清理长期错误任务: $recipientId")
                        toRemove.add(recipientId)
                    }
                    
                    // 清理休眠状态的任务
                    taskInfo.getActivityLevel() == TransportActivityLevel.DORMANT &&
                            currentTime - taskInfo.getLastPollTime() > TapPollingConstants.PollingService.DORMANT_TASK_CLEANUP_MS -> {
                        Log.i(TAG, "清理休眠任务: $recipientId")
                        toRemove.add(recipientId)
                    }
                }
            }
            
            // 执行清理
            toRemove.forEach { recipientId ->
                pollingTasks[recipientId]?.cleanup()
                pollingTasks.remove(recipientId)
            }
            
            if (toRemove.isNotEmpty()) {
                Log.d(TAG, "任务清理完成: 移除${toRemove.size}个任务")
            }
        }
    }
    
    // === 私有辅助方法 ===
    
    /**
     * 调度任务执行
     */
    private fun scheduleTaskExecution(
        taskInfo: PollingTaskInfo,
        executor: (PollingTaskInfo) -> Unit
    ): ScheduledFuture<*>? {
        // 添加初始延迟抖动，防止冷启动风暴
        val jitterMs = (Math.random() * TapPollingConstants.PollingService.INITIAL_DELAY_JITTER_MAX_MS).toLong()
        
        return pollingExecutor.scheduleWithFixedDelay(
            { executor(taskInfo) },
            jitterMs,
            taskInfo.getCurrentInterval(),
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 计算初始轮询间隔
     */
    private fun calculateInitialInterval(metadata: TransportMetadata): Long {
        val baseInterval = TapPollingConstants.ProviderIntervals.getBaseInterval(metadata.providerType)
        
        // 根据设备性能调整基础间隔
        val performanceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
        val performanceMultiplier = when (performanceLevel) {
            DevicePerformanceLevel.LOW -> 2.0      // 低性能设备降低频率
            DevicePerformanceLevel.MEDIUM -> 1.5   // 中等性能设备适当降频
            DevicePerformanceLevel.HIGH -> 1.0     // 高性能设备保持标准频率
        }
        
        val adjustedInterval = (baseInterval * performanceMultiplier).toLong()
        
        // 应用Provider限制
        val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(metadata.providerType)
        return adjustedInterval.coerceIn(minInterval, maxInterval)
    }
    
    /**
     * 计算新的轮询间隔
     */
    private fun calculateNewInterval(currentInterval: Long, changeType: IntervalChangeType, providerType: String): Long {
        val calculatedInterval = when (changeType) {
            IntervalChangeType.INCREASE -> (currentInterval * 1.5).toLong()
            IntervalChangeType.DECREASE -> (currentInterval * 0.7).toLong()
            IntervalChangeType.RESET -> TapPollingConstants.ProviderIntervals.getBaseInterval(providerType)
            IntervalChangeType.ERROR_BACKOFF -> (currentInterval * 2.0).toLong()
            IntervalChangeType.LOW_POWER -> (currentInterval * 3.0).toLong()
            IntervalChangeType.REEVALUATE -> currentInterval
        }
        
        // 应用Provider限制
        val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(providerType)
        return calculatedInterval.coerceIn(minInterval, maxInterval)
    }
}

/**
 * 调度器统计信息
 */
data class SchedulerStatistics(
    val totalTasks: Int,      // 总任务数
    val activeTasks: Int,     // 活跃任务数
    val errorTasks: Int,      // 错误任务数
    val averageInterval: Long // 平均轮询间隔
) {
    
    /**
     * 获取任务健康度（0.0-1.0）
     */
    fun getTaskHealthRatio(): Double {
        if (totalTasks == 0) return 1.0
        return (totalTasks - errorTasks).toDouble() / totalTasks.toDouble()
    }
    
    /**
     * 获取统计摘要
     */
    fun getSummary(): String {
        return "Scheduler[total=$totalTasks, active=$activeTasks, error=$errorTasks, " +
                "avgInterval=${averageInterval}ms, health=${String.format("%.1f", getTaskHealthRatio() * 100)}%]"
    }
} 