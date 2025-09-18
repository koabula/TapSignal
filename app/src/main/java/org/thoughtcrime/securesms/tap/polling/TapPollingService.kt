package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.*
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * Tap轮询服务
 * 
 * 主轮询服务，提供每联系人独立调度的智能轮询功能。
 * 集成所有轮询优化组件，实现高效、智能的消息轮询机制。
 * 
 * 核心特性：
 * 1. 每联系人独立轮询调度
 * 2. 智能轮询策略自动优化
 * 3. 动态资源调度和负载均衡
 * 4. 批处理优化提高效率
 * 5. 自适应学习持续改进
 * 6. 完整的错误处理和恢复
 * 7. 丰富的监控和统计信息
 */
class TapPollingService(private val context: Context) {
    
    companion object {
        private const val TAG = "TapPollingService"
        
        // 并发配置
        private const val CORE_POOL_SIZE = 2
        private const val MAX_POOL_SIZE = 8
        private const val KEEP_ALIVE_TIME = 60L // 秒
        
        // 智能轮询间隔（毫秒）
        private const val ACTIVE_POLLING_INTERVAL = 5000L      // 活跃对话: 5秒
        private const val INACTIVE_POLLING_INTERVAL = 30000L   // 非活跃对话: 30秒
        private const val BACKGROUND_POLLING_INTERVAL = 60000L // 后台模式: 60秒
        private const val SUSPENDED_POLLING_INTERVAL = 300000L // 暂停模式: 5分钟
        
        // 系统配置
        private const val POLLING_TIMEOUT_MS = 30000L          // 轮询超时: 30秒
        private const val MAX_RETRY_ATTEMPTS = 3               // 最大重试次数
        private const val CLEANUP_INTERVAL_MS = 300000L        // 清理间隔: 5分钟
        
        private var INSTANCE: TapPollingService? = null
        
        /**
         * 获取单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TapPollingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapPollingService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val tokenPool = TransportTokenPool.getInstance(context)
    
    // 轮询优化组件
    private val pollingStrategy = TapIntelligentPollingStrategy(context)
    private val dynamicScheduler = DynamicPollingScheduler(context)
    private val batchOptimizer = BatchPollingOptimizer(context)
    private val intervalAdjuster = AdaptiveIntervalAdjuster(context)
    
    // 轮询任务管理
    private val pollingTasks = ConcurrentHashMap<String, PollingTaskInfo>()
    private val pollingLock = ReentrantReadWriteLock()
    
    // 线程池和调度器
    private var pollingExecutor: ScheduledThreadPoolExecutor? = null
    private var cleanupTask: ScheduledFuture<*>? = null
    
    // 服务状态
    private val isRunning = AtomicBoolean(false)
    private var serviceScope: CoroutineScope? = null
    
    // 统计收集器
    private val statisticsCollector = PollingStatisticsCollector()
    
    /**
     * 启动轮询服务
     */
    fun startPolling(): Boolean {
        return pollingLock.write {
            try {
                if (isRunning.get()) {
                    Log.w(TAG, "轮询服务已经运行")
                    return@write true
                }
                
                Log.i(TAG, "启动Tap轮询服务...")
                
                // 创建线程池
                pollingExecutor = ScheduledThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    { r -> Thread(r, "TapPolling-${System.currentTimeMillis()}") },
                    ThreadPoolExecutor.CallerRunsPolicy()
                ).apply {
                    maximumPoolSize = MAX_POOL_SIZE
                    setKeepAliveTime(KEEP_ALIVE_TIME, TimeUnit.SECONDS)
                    allowCoreThreadTimeOut(true)
                }
                
                // 创建协程作用域
                serviceScope = CoroutineScope(
                    Dispatchers.IO + SupervisorJob() + CoroutineName("TapPollingService")
                )
                
                // 启动动态调度器
                dynamicScheduler.start()
                
                // 启动清理任务
                startCleanupTask()
                
                isRunning.set(true)
                Log.i(TAG, "Tap轮询服务启动成功")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "启动轮询服务失败", e)
                cleanup()
                false
            }
        }
    }
    
    /**
     * 停止轮询服务
     */
    fun stopPolling() {
        pollingLock.write {
            try {
                if (!isRunning.get()) {
                    Log.w(TAG, "轮询服务未运行")
                    return@write
                }
                
                Log.i(TAG, "停止Tap轮询服务...")
                
                isRunning.set(false)
                
                // 优雅停止：等待正在执行的任务完成
                gracefulShutdown()
                
                // 清理资源
                cleanup()
                
                Log.i(TAG, "Tap轮询服务已停止")
                
            } catch (e: Exception) {
                Log.e(TAG, "停止轮询服务时发生错误", e)
            }
        }
    }
    
    /**
     * 优雅停止轮询任务
     */
    private fun gracefulShutdown() {
        try {
            // 取消所有轮询任务
            val tasks = pollingTasks.values.toList()
            Log.d(TAG, "取消 ${tasks.size} 个轮询任务")
            
            tasks.forEach { taskInfo ->
                taskInfo.task?.cancel(false) // 不中断正在运行的任务
            }
            
            // 等待线程池安全关闭
            pollingExecutor?.let { executor ->
                executor.shutdown()
                try {
                    // 等待30秒让任务自然结束
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        Log.w(TAG, "轮询任务未在30秒内完成，强制停止")
                        executor.shutdownNow()
                        // 再等待10秒
                        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                            Log.e(TAG, "无法停止轮询线程池")
                        }
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    executor.shutdownNow()
                }
            }
            
            // 清理任务信息
            pollingTasks.clear()
            
        } catch (e: Exception) {
            Log.e(TAG, "优雅停止轮询任务时出错", e)
        }
    }
    
    /**
     * 添加轮询目标（支持每联系人独立调度）
     */
    fun addPollingTarget(recipientId: String, metadata: TransportMetadata): Boolean {
        if (!isRunning.get()) {
            Log.w(TAG, "轮询服务未运行，无法添加轮询目标")
            return false
        }
        
        return try {
            Log.d(TAG, "添加轮询目标: recipient=$recipientId, provider=${metadata.providerType}")
            
            pollingLock.write {
                // 检查是否已存在
                if (pollingTasks.containsKey(recipientId)) {
                    Log.w(TAG, "轮询目标已存在: $recipientId")
                    return@write false
                }
                
                // 创建轮询任务信息
                val taskInfo = PollingTaskInfo.create(recipientId, metadata)
                
                // 计算初始轮询间隔
                val channel = channelManager.getActiveChannel(recipientId, metadata.providerType)
                val initialInterval = pollingStrategy.calculatePollingInterval(
                    recipientId, metadata, channel
                )
                taskInfo.currentInterval = initialInterval
                
                // 调度轮询任务
                val scheduledTask = schedulePollingTask(taskInfo)
                taskInfo.task = scheduledTask
                taskInfo.status = PollingTaskStatus.RUNNING
                
                // 添加到任务列表
                pollingTasks[recipientId] = taskInfo
                
                Log.i(TAG, "轮询目标添加成功: recipient=$recipientId, interval=${initialInterval}ms")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加轮询目标失败: recipient=$recipientId", e)
            false
        }
    }
    
    /**
     * 移除轮询目标
     */
    fun removePollingTarget(recipientId: String, providerType: String): Boolean {
        return pollingLock.write {
            try {
                val taskInfo = pollingTasks[recipientId]
                if (taskInfo == null) {
                    Log.w(TAG, "轮询目标不存在: $recipientId")
                    return@write false
                }
                
                // 检查Provider类型是否匹配
                if (taskInfo.metadata.providerType != providerType) {
                    Log.w(TAG, "Provider类型不匹配: expected=$providerType, actual=${taskInfo.metadata.providerType}")
                    return@write false
                }
                
                Log.d(TAG, "移除轮询目标: recipient=$recipientId, provider=$providerType")
                
                // 取消任务
                taskInfo.cleanup()
                pollingTasks.remove(recipientId)
                
                Log.i(TAG, "轮询目标移除成功: $recipientId")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "移除轮询目标失败: recipient=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 动态调整轮询间隔（基于活跃度变化）
     */
    fun adjustPollingInterval(recipientId: String, newInterval: Long): Boolean {
        return pollingLock.write {
            try {
                val taskInfo = pollingTasks[recipientId]
                if (taskInfo == null) {
                    Log.w(TAG, "轮询任务不存在: $recipientId")
                    return@write false
                }
                
                if (taskInfo.currentInterval == newInterval) {
                    Log.d(TAG, "轮询间隔无变化，跳过调整: $recipientId")
                    return@write true
                }
                
                Log.d(TAG, "调整轮询间隔: recipient=$recipientId, ${taskInfo.currentInterval}ms -> ${newInterval}ms")
                
                // 取消当前任务
                taskInfo.task?.cancel(false)
                
                // 更新间隔
                taskInfo.currentInterval = newInterval
                
                // 重新调度任务
                val newTask = schedulePollingTask(taskInfo)
                taskInfo.task = newTask
                
                // 通知动态调度器
                dynamicScheduler.adjustPollingSchedule(recipientId, PollingAdjustTrigger.USER_ACTIVE)
                
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "调整轮询间隔失败: recipient=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 获取轮询状态和统计信息
     */
    fun getPollingStatus(): TapPollingStatus {
        return pollingLock.read {
            val currentTime = System.currentTimeMillis()
            val activeTasks = pollingTasks.values.filter { 
                it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING 
            }
            val totalTasks = pollingTasks.size
            
            val averageInterval = if (activeTasks.isNotEmpty()) {
                activeTasks.map { it.currentInterval }.average().toLong()
            } else {
                0L
            }
            
            val lastPollingTime = pollingTasks.values.maxOfOrNull { it.lastPollTime.get() } ?: 0L
            
            TapPollingStatus(
                isRunning = isRunning.get(),
                activePollingTargets = activeTasks.size,
                totalPollingTargets = totalTasks,
                averagePollingInterval = averageInterval,
                lastPollingTime = lastPollingTime,
                pollingStatistics = statisticsCollector.getCurrentStatistics(),
                resourceUsage = getCurrentResourceUsage(),
                systemStartTime = currentTime
            )
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 调度轮询任务
     */
    private fun schedulePollingTask(taskInfo: PollingTaskInfo): ScheduledFuture<*>? {
        return pollingExecutor?.scheduleWithFixedDelay(
            { executePollingTask(taskInfo) },
            0L,
            taskInfo.currentInterval,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 执行轮询任务
     */
    private fun executePollingTask(taskInfo: PollingTaskInfo) {
        if (!isRunning.get()) {
            return
        }
        
        serviceScope?.launch {
            try {
                taskInfo.status = PollingTaskStatus.POLLING
                taskInfo.updatePollTime()
                
                Log.v(TAG, "执行轮询: ${taskInfo.getSummary()}")
                
                // 检查是否应该跳过轮询
                val channel = channelManager.getActiveChannel(
                    taskInfo.recipientId, 
                    taskInfo.metadata.providerType
                )
                
                if (pollingStrategy.shouldSkipPolling(taskInfo.recipientId, taskInfo.metadata, channel)) {
                    Log.d(TAG, "跳过轮询: ${taskInfo.recipientId}")
                    taskInfo.status = PollingTaskStatus.PAUSED
                    return@launch
                }
                
                // 执行实际轮询
                val result = performSinglePoll(taskInfo)
                
                // 处理轮询结果
                handlePollingResult(taskInfo, result)
                
                // 记录统计信息
                statisticsCollector.recordPoll(
                    taskInfo.metadata.providerType,
                    taskInfo.activityLevel,
                    result.isSuccess,
                    result.responseTime,
                    result.messagesFound
                )
                
                taskInfo.status = PollingTaskStatus.RUNNING
                
            } catch (e: Exception) {
                Log.e(TAG, "轮询任务执行失败: ${taskInfo.recipientId}", e)
                handlePollingError(taskInfo, e)
            }
        }
    }
    
    /**
     * 执行单次轮询
     */
    private suspend fun performSinglePoll(taskInfo: PollingTaskInfo): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // 获取Provider
            val provider = transportManager.getProvider(taskInfo.metadata.providerType)
            if (provider == null) {
                Log.e(TAG, "Provider不可用: ${taskInfo.metadata.providerType}")
                return PollingExecutionResult.failure("Provider不可用", startTime)
            }
            
            // 执行Pull操作
            val transportResult = withTimeout(POLLING_TIMEOUT_MS) {
                provider.pull(taskInfo.metadata)
            }
            
            val responseTime = System.currentTimeMillis() - startTime
            
            when (transportResult) {
                is TransportResult.Success -> {
                    val messagesFound = if (transportResult.message != null) 1 else 0
                    PollingExecutionResult.success(messagesFound, responseTime)
                }
                is TransportResult.Failed -> {
                    PollingExecutionResult.failure(transportResult.error.name, responseTime)
                }
                is TransportResult.RetryScheduled -> {
                    PollingExecutionResult.retry(transportResult.reason, responseTime)
                }
                is TransportResult.PartialSuccess -> {
                    val messagesFound = transportResult.successCount
                    if (transportResult.isCompleteSuccess()) {
                        PollingExecutionResult.success(messagesFound, responseTime)
                    } else {
                        PollingExecutionResult.failure("部分成功: ${transportResult.successCount}/${transportResult.successCount + transportResult.failureCount}", responseTime)
                    }
                }
            }
            
        } catch (e: TimeoutCancellationException) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.w(TAG, "轮询超时: ${taskInfo.recipientId}")
            PollingExecutionResult.failure("TIMEOUT", responseTime)
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "轮询过程中发生错误: ${taskInfo.recipientId}", e)
            PollingExecutionResult.failure(e.message ?: "UNKNOWN_ERROR", responseTime)
        }
    }
    
    /**
     * 处理轮询结果
     */
    private fun handlePollingResult(taskInfo: PollingTaskInfo, result: PollingExecutionResult) {
        when {
            result.isSuccess -> {
                // 轮询成功
                taskInfo.recordSuccess()
                taskInfo.recordMessagesFound(result.messagesFound)
                
                // 如果找到消息，通知动态调度器
                if (result.messagesFound > 0) {
                    dynamicScheduler.adjustPollingSchedule(
                        taskInfo.recipientId, 
                        PollingAdjustTrigger.MESSAGE_RECEIVED
                    )
                } else {
                    // 连续空轮询
                    dynamicScheduler.adjustPollingSchedule(
                        taskInfo.recipientId,
                        PollingAdjustTrigger.CONSECUTIVE_EMPTY
                    )
                }
                
                // 自适应学习
                intervalAdjuster.learnFromExperience(
                    taskInfo.recipientId,
                    createPollingFeatures(taskInfo),
                    taskInfo.currentInterval,
                    createPollingPerformance(taskInfo)
                )
                
            }
            
            result.needsRetry -> {
                // 需要重试
                Log.d(TAG, "轮询需要重试: ${taskInfo.recipientId}, reason=${result.error}")
                // 保持当前状态，等待下次轮询
            }
            
            else -> {
                // 轮询失败
                taskInfo.recordError()
                dynamicScheduler.adjustPollingSchedule(
                    taskInfo.recipientId,
                    PollingAdjustTrigger.ERROR_OCCURRED
                )
                
                // 检查是否需要暂停轮询
                if (taskInfo.shouldSuspendPolling()) {
                    Log.w(TAG, "轮询错误过多，暂停轮询: ${taskInfo.recipientId}")
                    taskInfo.status = PollingTaskStatus.ERROR_SUSPENDED
                    taskInfo.task?.cancel(false)
                    taskInfo.task = null
                }
            }
        }
    }
    
    /**
     * 处理轮询错误
     */
    private fun handlePollingError(taskInfo: PollingTaskInfo, error: Throwable) {
        taskInfo.recordError()
        taskInfo.status = PollingTaskStatus.ERROR_SUSPENDED
        
        Log.e(TAG, "轮询任务出现严重错误，暂停任务: ${taskInfo.recipientId}", error)
        
        // 取消当前任务
        taskInfo.task?.cancel(false)
        taskInfo.task = null
        
        // 通知动态调度器
        dynamicScheduler.adjustPollingSchedule(
            taskInfo.recipientId,
            PollingAdjustTrigger.ERROR_OCCURRED
        )
    }
    
    /**
     * 批量轮询指定Provider的所有目标
     */
    private suspend fun batchPollProvider(providerType: String) {
        val providerTasks = pollingTasks.values.filter { 
            it.metadata.providerType == providerType && it.status == PollingTaskStatus.RUNNING 
        }
        
        if (providerTasks.isEmpty()) return
        
        Log.d(TAG, "批量轮询: provider=$providerType, tasks=${providerTasks.size}")
        
        try {
            // 使用批处理优化器
            val batchGroups = batchOptimizer.batchSimilarPollingTasks(providerTasks)
            
            // 并发执行批处理组
            batchGroups.forEach { batchGroup ->
                serviceScope?.launch {
                    batchOptimizer.executeBatchPolling(batchGroup) { task ->
                        val result = performSinglePoll(task)
                        if (result.isSuccess) {
                            PollingResult.success(result.messagesFound)
                        } else {
                            PollingResult.failure(Exception(result.error))
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "批量轮询失败: provider=$providerType", e)
        }
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        cleanupTask = pollingExecutor?.scheduleWithFixedDelay(
            { performCleanup() },
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 执行清理任务
     */
    private fun performCleanup() {
        try {
            pollingLock.write {
                val currentTime = System.currentTimeMillis()
                val toRemove = mutableListOf<String>()
                
                // 清理异常和过期的任务
                pollingTasks.forEach { (recipientId, taskInfo) ->
                    when {
                        // 清理长时间错误暂停的任务
                        taskInfo.status == PollingTaskStatus.ERROR_SUSPENDED && 
                                currentTime - taskInfo.lastSuccessTime.get() > 24 * 60 * 60 * 1000L -> {
                            Log.i(TAG, "清理长期错误任务: $recipientId")
                            toRemove.add(recipientId)
                        }
                        
                        // 清理休眠状态的任务
                        taskInfo.activityLevel == TransportActivityLevel.DORMANT &&
                                currentTime - taskInfo.lastPollTime.get() > 7 * 24 * 60 * 60 * 1000L -> {
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
                
                Log.d(TAG, "清理任务完成: 移除${toRemove.size}个任务")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理任务失败", e)
        }
    }
    
    /**
     * 清理所有资源
     */
    private fun cleanup() {
        try {
            // 停止动态调度器
            dynamicScheduler.stop()
            
            // 取消清理任务
            cleanupTask?.cancel(false)
            cleanupTask = null
            
            // 取消所有轮询任务
            pollingTasks.values.forEach { it.cleanup() }
            pollingTasks.clear()
            
            // 关闭协程作用域
            serviceScope?.cancel()
            serviceScope = null
            
            // 关闭线程池
            pollingExecutor?.shutdown()
            try {
                if (pollingExecutor?.awaitTermination(5, TimeUnit.SECONDS) == false) {
                    pollingExecutor?.shutdownNow()
                }
            } catch (e: InterruptedException) {
                pollingExecutor?.shutdownNow()
                Thread.currentThread().interrupt()
            }
            pollingExecutor = null
            
        } catch (e: Exception) {
            Log.e(TAG, "资源清理时发生错误", e)
        }
    }
    
    /**
     * 获取当前资源使用情况
     */
    private fun getCurrentResourceUsage(): PollingResourceUsage {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            
            PollingResourceUsage(
                cpuUsagePercent = 30.0, // 简化实现
                memoryUsageKB = usedMemory / 1024,
                networkUsageKB = 0L, // 简化实现
                batteryDrainRate = 0.0, // 简化实现
                activeThreads = pollingExecutor?.activeCount ?: 0,
                queueLength = pollingExecutor?.queue?.size ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "获取资源使用信息失败", e)
            PollingResourceUsage(0.0, 0L, 0L, 0.0, 0, 0)
        }
    }
    
    /**
     * 创建轮询特征
     */
    private fun createPollingFeatures(taskInfo: PollingTaskInfo): PollingFeatures {
        val currentTime = System.currentTimeMillis()
        val hourOfDay = ((currentTime % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)).toInt()
        val dayOfWeek = ((currentTime / (24 * 60 * 60 * 1000)) % 7).toInt()
        
        return PollingFeatures(
            recipientId = taskInfo.recipientId,
            hourOfDay = hourOfDay,
            dayOfWeek = dayOfWeek,
            userActivityScore = calculateUserActivityScore(taskInfo),
            networkQuality = getCurrentNetworkQuality(),
            batteryLevel = 50, // 简化实现
            recentMessageFrequency = calculateRecentMessageFrequency(taskInfo)
        )
    }
    
    /**
     * 创建轮询性能
     */
    private fun createPollingPerformance(taskInfo: PollingTaskInfo): PollingPerformance {
        return PollingPerformance(
            successRate = taskInfo.getSuccessRate(),
            averageResponseTime = taskInfo.getAverageResponseTime(),
            messagesFound = taskInfo.statistics.messagesFound.get().toInt(),
            errorCount = taskInfo.consecutiveErrors.get()
        )
    }
    
    /**
     * 计算用户活跃度分数
     */
    private fun calculateUserActivityScore(taskInfo: PollingTaskInfo): Double {
        return when (taskInfo.activityLevel) {
            TransportActivityLevel.ACTIVE -> 1.0
            TransportActivityLevel.INACTIVE -> 0.7
            TransportActivityLevel.BACKGROUND -> 0.5
            TransportActivityLevel.SUSPENDED -> 0.3
            TransportActivityLevel.DORMANT -> 0.1
        }
    }
    
    /**
     * 获取当前网络质量
     */
    private fun getCurrentNetworkQuality(): NetworkQuality {
        // 简化实现
        return NetworkQuality.GOOD
    }
    
    /**
     * 计算最近消息频率
     */
    private fun calculateRecentMessageFrequency(taskInfo: PollingTaskInfo): Double {
        val recentMessages = taskInfo.statistics.messagesFound.get()
        val recentPolls = taskInfo.statistics.totalPolls.get()
        
        return if (recentPolls > 0) {
            recentMessages.toDouble() / recentPolls.toDouble()
        } else {
            0.0
        }
    }
}

/**
 * 轮询执行结果
 */
private data class PollingExecutionResult(
    val isSuccess: Boolean,
    val messagesFound: Int,
    val responseTime: Long,
    val error: String,
    val needsRetry: Boolean
) {
    companion object {
        fun success(messagesFound: Int, responseTime: Long) = PollingExecutionResult(
            isSuccess = true,
            messagesFound = messagesFound,
            responseTime = responseTime,
            error = "",
            needsRetry = false
        )
        
        fun failure(error: String, responseTime: Long) = PollingExecutionResult(
            isSuccess = false,
            messagesFound = 0,
            responseTime = responseTime,
            error = error,
            needsRetry = false
        )
        
        fun retry(reason: String, responseTime: Long) = PollingExecutionResult(
            isSuccess = false,
            messagesFound = 0,
            responseTime = responseTime,
            error = reason,
            needsRetry = true
        )
    }
} 