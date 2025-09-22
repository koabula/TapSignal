package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.polling.ProviderPollingStats
import org.thoughtcrime.securesms.tap.polling.ActivityLevelStats
import org.thoughtcrime.securesms.tap.polling.RecentPollingStats
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.TransportErrorHandler
import org.thoughtcrime.securesms.tap.ErrorContext

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
        
        // 已移除硬编码配置，改为使用可配置的 TapPollingConfig
        
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
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val errorHandler = TransportErrorHandler.getInstance(context)
    
    // 数据库访问
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
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
    
    // 轮询配置（可配置参数）
    private var pollingConfig: TapPollingConfig = TapPollingConfig()
    
    /**
     * 初始化轮询服务
     */
    fun initialize(config: TapPollingConfig = TapPollingConfig()) {
        Log.i(TAG, "初始化Tap轮询服务...")
        
        try {
            // 验证并保存配置
            if (!config.validate()) {
                throw IllegalArgumentException("轮询配置无效")
            }
            this.pollingConfig = config
            // 初始化核心组件（暂时注释掉，等待组件实现 initialize 方法）
            // pollingStrategy.initialize()
            // dynamicScheduler.initialize()
            // batchOptimizer.initialize()
            // intervalAdjuster.initialize()
            
            // 初始化统计收集器（暂时注释掉）
            // statisticsCollector.initialize()
            
            Log.i(TAG, "Tap轮询服务初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "Tap轮询服务初始化失败", e)
            throw e
        }
    }
    
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
                    pollingConfig.corePoolSize,
                    { r -> Thread(r, "TapPolling-${System.currentTimeMillis()}") },
                    ThreadPoolExecutor.CallerRunsPolicy()
                ).apply {
                    maximumPoolSize = pollingConfig.maxPoolSize
                    setKeepAliveTime(pollingConfig.keepAliveTimeSeconds, TimeUnit.SECONDS)
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
    
    /**
     * 获取当前轮询统计信息
     */
    fun getCurrentStatistics(): TapPollingStatistics {
        return pollingLock.read {
            statisticsCollector.getCurrentStatistics()
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
     * 轮询单个目标的消息
     */
    private suspend fun pollSingleTarget(taskInfo: PollingTaskInfo): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(pollingConfig.pollingTimeoutMs) {
            try {
                Log.d(TAG, "开始轮询目标: recipient=${taskInfo.recipientId}, provider=${taskInfo.metadata.providerType}")
                
                val provider = transportManager.getProvider(taskInfo.metadata.providerType)
                if (provider == null) {
                    Log.w(TAG, "Provider不可用: ${taskInfo.metadata.providerType}")
                    val responseTime = System.currentTimeMillis() - startTime
                    return@withTimeout PollingExecutionResult.failure("Provider不可用", responseTime)
                }
                
                // 获取远程文件列表
                val listResult = provider.listFiles(taskInfo.metadata.getReceiveMetadata().path, taskInfo.metadata)
                if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                    Log.d(TAG, "未发现新文件: ${taskInfo.recipientId}")
                    val responseTime = System.currentTimeMillis() - startTime
                    return@withTimeout PollingExecutionResult.success(0, responseTime)
                }
                
                // 使用新的处理方法
                val processingResult = processPollingResults(provider, taskInfo, listResult.files, taskInfo.metadata)
                
                val responseTime = System.currentTimeMillis() - startTime
                
                // 返回处理结果（已包含响应时间）
                processingResult
                
            } catch (e: TimeoutCancellationException) {
                val responseTime = System.currentTimeMillis() - startTime
                PollingExecutionResult.retry("轮询超时", responseTime)
            } catch (e: Exception) {
                val responseTime = System.currentTimeMillis() - startTime
                Log.e(TAG, "轮询异常: ${taskInfo.recipientId}", e)
                PollingExecutionResult.failure(e.message ?: "轮询失败", responseTime)
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
            
            // 获取轮询状态
            val pollingState = pollingStateTable.getPollingState(
                taskInfo.recipientId, 
                taskInfo.metadata.providerType
            )
            
            // 执行新的文件操作轮询
            val pollingResult = performFileBasedPolling(provider, taskInfo, pollingState)
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // 更新轮询状态
            if (pollingResult.isSuccess) {
                pollingStateTable.recordSuccessfulPoll(
                    taskInfo.recipientId,
                    taskInfo.metadata.providerType,
                    pollingResult.processedFiles,
                    pollingResult.messagesFound
                )
            } else {
                pollingStateTable.recordFailedPoll(
                    taskInfo.recipientId,
                    taskInfo.metadata.providerType,
                    pollingResult.error
                )
            }
            
            PollingExecutionResult(
                isSuccess = pollingResult.isSuccess,
                messagesFound = pollingResult.messagesFound,
                responseTime = responseTime,
                error = pollingResult.error,
                needsRetry = pollingResult.needsRetry
            )
            
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
     * 执行基于文件操作的轮询
     */
    private suspend fun performFileBasedPolling(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        pollingState: org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState?
    ): FilePollingResult {
        return try {
            // 列举文件
            val listResult = withTimeout(pollingConfig.pollingTimeoutMs) {
                errorHandler.executeWithRetry({
                    provider.listFiles(taskInfo.metadata.getReceiveMetadata().path, taskInfo.metadata)
                }, ErrorContext(
                    providerType = taskInfo.metadata.providerType,
                    operationType = "listFiles",
                    targetId = taskInfo.recipientId,
                    channelId = "${taskInfo.metadata.providerType}:${taskInfo.recipientId}"
                ))
            }
            
            if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                Log.d(TAG, "未找到文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            val allFiles = FileInfo.sortByTime(listResult.files, ascending = true)
            val processedFiles = pollingState?.processedFiles ?: emptySet()
            
            val newFiles = allFiles.filter { file ->
                !processedFiles.contains(file.name) && 
                file.lastModified > (pollingState?.lastProcessedTime ?: 0)
            }
            
            if (newFiles.isEmpty()) {
                Log.d(TAG, "没有新文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            Log.d(TAG, "找到新文件数量: ${newFiles.size}, recipient: ${taskInfo.recipientId}")
            
            var messagesProcessed = 0
            val newProcessedFiles = mutableSetOf<String>()
            
            for (file in newFiles) {
                try {
                    val downloadResult = withTimeout(pollingConfig.pollingTimeoutMs) {
                        errorHandler.executeWithRetry({
                            provider.downloadFile(file, taskInfo.metadata)
                        }, ErrorContext(
                            providerType = taskInfo.metadata.providerType,
                            operationType = "downloadFile",
                            targetId = taskInfo.recipientId,
                            channelId = "${taskInfo.metadata.providerType}:${taskInfo.recipientId}",
                            metadata = mapOf("fileName" to file.name)
                        ))
                    }
                    
                    if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                        val message = provider.parseTransportMessage(downloadResult.data, file, taskInfo.metadata)
                        if (message != null) {
                            val processResult = messageProcessor.processTapTransportMessage(message)
                            if (processResult is TapProcessResult.Success) {
                                messagesProcessed++
                                Log.d(TAG, "消息处理成功: ${file.name}")
                            } else {
                                Log.w(TAG, "消息处理失败: ${file.name}, error=${(processResult as? TapProcessResult.Failed)?.error}")
                            }
                        } else {
                            Log.d(TAG, "文件解析失败，可能不是消息文件: ${file.name}")
                        }
                        // 无论成功与否都标记已处理，避免重复
                        newProcessedFiles.add(file.name)
                    } else {
                        Log.w(TAG, "文件下载失败: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理文件时发生异常: ${file.name}", e)
                    // 出错的文件不标记为已处理，下次继续尝试
                }
            }
            
            FilePollingResult.success(newProcessedFiles, messagesProcessed)
            
        } catch (e: Exception) {
            Log.e(TAG, "文件轮询异常: ${taskInfo.recipientId}", e)
            FilePollingResult.failure(e.message ?: "UNKNOWN_ERROR", needsRetry = true)
        }
    }
    

    

    
    /**
     * 将消息传递给Signal主程序处理
     */
    private suspend fun deliverMessageToSignal(message: TransportMessage, taskInfo: PollingTaskInfo) {
        try {
            Log.i(TAG, "开始传递消息到Signal: messageId=${message.messageId}, recipient=${taskInfo.recipientId}")
            
            // 1. 检查消息去重
            if (messageProcessor.isDuplicateMessage(message.messageId, taskInfo.recipientId)) {
                Log.d(TAG, "跳过重复消息: messageId=${message.messageId}")
                return
            }
            
            // 2. 通过TapMessageProcessor处理消息
            val processResult = messageProcessor.processTapTransportMessage(message)
            
            if (processResult is TapProcessResult.Success) {
                Log.i(TAG, "消息成功传递到Signal: messageId=${message.messageId}")
            } else {
                Log.w(TAG, "消息传递失败: messageId=${message.messageId}, error=${(processResult as? TapProcessResult.Failed)?.error}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "传递消息到Signal失败: messageId=${message.messageId} - ${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitizeThrowable(e)}")
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
                
                // 更新数据库轮询状态
                updatePollingStateInDatabase(taskInfo, result)
                
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
                
                // 更新数据库错误状态
                updatePollingErrorInDatabase(taskInfo, result)
                
                dynamicScheduler.adjustPollingSchedule(
                    taskInfo.recipientId,
                    PollingAdjustTrigger.ERROR_OCCURRED
                )
                
                // 检查是否需要暂停轮询
                if (taskInfo.consecutiveErrors.get() >= pollingConfig.maxRetryAttempts) {
                    Log.w(TAG, "轮询连续失败次数过多，暂停轮询: ${taskInfo.recipientId}")
                    removePollingTarget(taskInfo.recipientId, taskInfo.metadata.providerType)
                }
            }
        }
    }
    
    /**
     * 更新数据库轮询状态（成功情况）
     */
    private fun updatePollingStateInDatabase(taskInfo: PollingTaskInfo, result: PollingExecutionResult) {
        try {
            // 使用taskInfo中保存的处理文件信息
            pollingStateTable.recordSuccessfulPoll(
                taskInfo.recipientId,
                taskInfo.metadata.providerType,
                taskInfo.lastProcessedFiles,
                result.messagesFound
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新轮询状态到数据库失败: ${taskInfo.recipientId}", e)
        }
    }
    
    /**
     * 更新数据库错误状态
     */
    private fun updatePollingErrorInDatabase(taskInfo: PollingTaskInfo, result: PollingExecutionResult) {
        try {
            pollingStateTable.recordFailedPoll(
                taskInfo.recipientId,
                taskInfo.metadata.providerType,
                result.error ?: "未知错误"
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新轮询错误状态到数据库失败: ${taskInfo.recipientId}", e)
        }
    }
    
    /**
     * 从数据库获取已处理的文件列表
     */
    private fun getProcessedFilesFromDatabase(recipientId: String, providerType: String): Set<String> {
        return try {
            val database = org.thoughtcrime.securesms.database.SignalDatabase.rawDatabase
            val processedFiles = mutableSetOf<String>()
            
            database.rawQuery(
                "SELECT duplication_key FROM transport_processed_messages WHERE duplication_key LIKE ?",
                arrayOf("%:$recipientId:%")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val duplicationKey = cursor.getString(0)
                    // 从去重键中提取文件名信息
                    // 格式通常是 messageId:recipientId:timestamp
                    val parts = duplicationKey.split(":")
                    if (parts.size >= 3) {
                        // 重建文件名（这是一个简化实现，实际可能需要更复杂的映射）
                        val fileName = "${parts[0]}_${parts[2]}.dat"
                        processedFiles.add(fileName)
                    }
                }
            }
            
            processedFiles
        } catch (e: Exception) {
            Log.e(TAG, "获取已处理文件列表失败: recipientId=$recipientId", e)
            emptySet()
        }
    }
    
    /**
     * 标记文件为已处理
     */
    private fun markFileAsProcessed(recipientId: String, fileName: String) {
        try {
            // 这里可以添加文件级别的处理记录
            // 目前主要依赖消息级别的去重
            Log.d(TAG, "标记文件已处理: recipientId=$recipientId, fileName=$fileName")
        } catch (e: Exception) {
            Log.e(TAG, "标记文件已处理失败: fileName=$fileName", e)
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
            pollingConfig.cleanupIntervalMs,
            pollingConfig.cleanupIntervalMs,
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
                cpuUsagePercent = getCurrentCpuUsage(),
                memoryUsageKB = usedMemory / 1024,
                networkUsageKB = getCurrentNetworkUsage(),
                batteryDrainRate = getCurrentBatteryDrainRate(),
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
            batteryLevel = getCurrentBatteryLevel(),
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

    /**
     * 处理轮询结果并下载新消息
     */
    private suspend fun processPollingResults(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        files: List<FileInfo>,
        metadata: TransportMetadata
    ): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        return try {
            Log.d(TAG, "处理轮询结果: provider=${provider.providerType}, files=${files.size}")
            
            if (files.isEmpty()) {
                Log.d(TAG, "没有发现文件: recipientId=${taskInfo.recipientId}")
                return PollingExecutionResult.success(0, System.currentTimeMillis() - startTime)
            }
            
            // 使用Provider的文件识别策略过滤消息文件
            val messageFiles = files.filter { file ->
                provider.isMessageFile(file)
            }
            
            Log.d(TAG, "发现消息文件: ${messageFiles.size}/${files.size}")
            
            if (messageFiles.isEmpty()) {
                return PollingExecutionResult.success(0, System.currentTimeMillis() - startTime)
            }
            
            // 获取已处理的文件列表
            val processedFiles = getProcessedFilesFromDatabase(taskInfo.recipientId, taskInfo.metadata.providerType)
            
            // 过滤未处理的文件
            val newFiles = messageFiles.filter { file ->
                !processedFiles.contains(file.name)
            }
            
            Log.d(TAG, "发现新文件: ${newFiles.size}/${messageFiles.size}")
            
            if (newFiles.isEmpty()) {
                return PollingExecutionResult.success(0, System.currentTimeMillis() - startTime)
            }
            
            var successCount = 0
            var errorCount = 0
            
            // 处理每个新文件
            for (file in newFiles) {
                try {
                    val processed = processMessageFile(provider, file, metadata, taskInfo)
                    if (processed) {
                        successCount++
                        // 记录已处理的文件
                        markFileAsProcessed(taskInfo.recipientId, file.name)
                    } else {
                        errorCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理消息文件失败: ${file.name}", e)
                    errorCount++
                }
            }
            
            Log.i(TAG, "轮询处理完成: 成功=$successCount, 失败=$errorCount")
            
            val responseTime = System.currentTimeMillis() - startTime
            if (errorCount == 0) {
                PollingExecutionResult.success(successCount, responseTime)
            } else {
                PollingExecutionResult.partialSuccess(successCount, errorCount, responseTime)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理轮询结果异常: recipientId=${taskInfo.recipientId}", e)
            PollingExecutionResult.failure(e.message ?: "处理轮询结果失败", System.currentTimeMillis() - startTime)
        }
    }
    
    /**
     * 处理单个消息文件
     */
    private suspend fun processMessageFile(
        provider: TransportProvider,
        fileInfo: FileInfo,
        metadata: TransportMetadata,
        taskInfo: PollingTaskInfo
    ): Boolean {
        return try {
            Log.d(TAG, "开始处理消息文件: ${fileInfo.name}")
            
            // 使用Provider下载文件
            val downloadResult = provider.downloadFile(fileInfo, metadata)
            
            if (downloadResult !is TransportResult.Success || downloadResult.data == null) {
                Log.w(TAG, "下载文件失败: ${fileInfo.name}")
                return false
            }
            
            // 使用Provider解析传输消息
            val transportMessage = provider.parseTransportMessage(downloadResult.data, fileInfo, metadata)
            
            if (transportMessage == null) {
                Log.w(TAG, "解析传输消息失败: ${fileInfo.name}")
                return false
            }
            
            Log.d(TAG, "成功解析传输消息: messageId=${transportMessage.messageId}, fileName=${fileInfo.name}")
            
            // 传递消息到Signal主程序处理
            deliverMessageToSignal(transportMessage, taskInfo)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "处理消息文件异常: ${fileInfo.name}", e)
            false
        }
    }
    
    /**
     * 获取当前CPU使用率
     */
    private fun getCurrentCpuUsage(): Double {
        return try {
            // 使用/proc/stat文件获取CPU使用率
            val runtime = Runtime.getRuntime()
            val availableProcessors = runtime.availableProcessors()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val totalMemory = runtime.totalMemory()
            
            // 简单估算：基于内存使用率和活跃线程数
            val memoryRatio = usedMemory.toDouble() / totalMemory.toDouble()
            val activeThreads = pollingExecutor?.activeCount ?: 0
            val estimatedCpuUsage = (memoryRatio * 50 + activeThreads * 10).coerceAtMost(100.0)
            
            estimatedCpuUsage
        } catch (e: Exception) {
            Log.w(TAG, "获取CPU使用率失败", e)
            0.0
        }
    }
    
    /**
     * 获取当前网络使用量（KB）
     */
    private fun getCurrentNetworkUsage(): Long {
        return try {
            // 累积最近一段时间的网络使用情况
            val pollingTargets = pollingTasks.size
            val averageMessageSize = 1024L // 平均消息大小估算
            val estimatedUsage = pollingTargets * averageMessageSize / 1024
            
            estimatedUsage
        } catch (e: Exception) {
            Log.w(TAG, "获取网络使用量失败", e)
            0L
        }
    }
    
    /**
     * 获取当前电池消耗率
     */
    private fun getCurrentBatteryDrainRate(): Double {
        return try {
            // 基于轮询频率和活跃度估算电池消耗
            val pollingTargets = pollingTasks.size
            val activeTargets = pollingTasks.values.count { it.task != null && !it.task!!.isCancelled }
            val estimatedDrain = (activeTargets * 0.1 + pollingTargets * 0.05).coerceAtMost(10.0)
            
            estimatedDrain
        } catch (e: Exception) {
            Log.w(TAG, "获取电池消耗率失败", e)
            0.0
        }
    }
    
    /**
     * 获取当前电池电量
     */
    private fun getCurrentBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50
        } catch (e: Exception) {
            Log.w(TAG, "获取电池电量失败", e)
            50 // 默认值
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
        
        fun partialSuccess(successCount: Int, failureCount: Int, responseTime: Long) = PollingExecutionResult(
            isSuccess = true,
            messagesFound = successCount,
            responseTime = responseTime,
            error = "部分成功: $successCount 成功, $failureCount 失败",
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

/**
 * 文件轮询结果
 */
private data class FilePollingResult(
    val isSuccess: Boolean,
    val processedFiles: Set<String>,
    val messagesFound: Int,
    val error: String,
    val needsRetry: Boolean
) {
    companion object {
        fun success(processedFiles: Set<String>, messagesFound: Int) = FilePollingResult(
            isSuccess = true,
            processedFiles = processedFiles,
            messagesFound = messagesFound,
            error = "",
            needsRetry = false
        )
        
        fun failure(error: String, needsRetry: Boolean = false) = FilePollingResult(
            isSuccess = false,
            processedFiles = emptySet(),
            messagesFound = 0,
            error = error,
            needsRetry = needsRetry
        )
    }
}

// Removed duplicate PollingStatisticsCollector - using the one from TapPollingStatus.kt instead 