package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.TransportErrorHandler
import org.thoughtcrime.securesms.tap.ErrorContext





/**
 * Tap轮询服务 - 简化版本
 * 
 * 提供每联系人独立调度的基础轮询功能，专注于：
 * 1. 每联系人独立轮询调度
 * 2. 基础错误处理和退避
 * 3. 活跃度级别调整
 * 4. 资源安全管理
 */
class TapPollingService(private val context: Context) {
    
    companion object {
        private const val TAG = "TapPollingService"
        
        @Volatile
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
    
    // 核心依赖组件
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val errorHandler = TransportErrorHandler.getInstance(context)
    
    // 数据库访问
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
    // 轮询任务管理
    private val pollingTasks = ConcurrentHashMap<String, PollingTaskInfo>()
    private val pollingLock = ReentrantReadWriteLock()
    
    // 线程池管理
    @Volatile
    private var pollingExecutor: ScheduledThreadPoolExecutor? = null
    @Volatile
    private var cleanupTask: ScheduledFuture<*>? = null
    
    // 服务状态
    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var serviceScope: CoroutineScope? = null
    
    // 文件处理失败跟踪
    private val fileProcessingFailures = ConcurrentHashMap<String, FileProcessingFailure>()
    
    // 设备性能检测
    private val deviceCapabilityProvider = DeviceCapabilityProvider(context)
    
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
                
                // 根据设备性能计算最优线程池配置
                val threadPoolConfig = deviceCapabilityProvider.calculateOptimalThreadPoolSize()
                val deviceSummary = deviceCapabilityProvider.getDeviceSummary()
                
                Log.i(TAG, "设备性能: $deviceSummary")
                Log.i(TAG, "线程池配置: ${threadPoolConfig.getSummary()}")
                
                // 创建自适应线程池
                pollingExecutor = ScheduledThreadPoolExecutor(
                    threadPoolConfig.corePoolSize,
                    { r -> 
                        Thread(r, "TapPolling-${Thread.currentThread().id}").apply {
                            isDaemon = true
                            priority = if (threadPoolConfig.isConservative()) {
                                Thread.MIN_PRIORITY + 1 // 低性能设备使用较低优先级
                            } else {
                                Thread.NORM_PRIORITY
                            }
                        }
                    }
                ).apply {
                    maximumPoolSize = threadPoolConfig.maxPoolSize
                    setKeepAliveTime(threadPoolConfig.keepAliveSeconds, TimeUnit.SECONDS)
                    allowCoreThreadTimeOut(true)
                    
                    // 根据设备性能选择拒绝策略
                    setRejectedExecutionHandler(
                        if (threadPoolConfig.isConservative()) {
                            ThreadPoolExecutor.DiscardOldestPolicy() // 低性能设备丢弃最旧任务
                        } else {
                            ThreadPoolExecutor.CallerRunsPolicy()    // 高性能设备由调用线程执行
                        }
                    )
                }
                
                // 创建协程作用域
                serviceScope = CoroutineScope(
                    Dispatchers.IO + SupervisorJob() + CoroutineName("TapPollingService")
                )
                
                // 启动清理任务
                startCleanupTask()
                
                isRunning.set(true)
                Log.i(TAG, "Tap轮询服务启动成功")
                true
                
            } catch (e: Exception) {
                when (e) {
                    is SecurityException -> Log.e(TAG, "安全权限不足，无法启动轮询服务", e)
                    is OutOfMemoryError -> Log.e(TAG, "内存不足，无法启动轮询服务", e)
                    else -> Log.e(TAG, "启动轮询服务失败: ${e.javaClass.simpleName}", e)
                }
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
                
                // 优雅停止
                gracefulShutdown()
                
                // 清理资源
                cleanup()
                
                Log.i(TAG, "Tap轮询服务已停止")
                
            } catch (e: Exception) {
                Log.e(TAG, "停止轮询服务时发生错误: ${e.javaClass.simpleName}", e)
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
                taskInfo.task?.cancel(false)
            }
            
            // 等待线程池安全关闭
            pollingExecutor?.let { executor ->
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        Log.w(TAG, "轮询任务未在30秒内完成，强制停止")
                        val unfinishedTasks = executor.shutdownNow()
                        Log.w(TAG, "强制停止了 ${unfinishedTasks.size} 个未完成任务")
                        
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
            Log.e(TAG, "优雅停止轮询任务时出错: ${e.javaClass.simpleName}", e)
        }
    }
    
    /**
     * 添加轮询目标
     */
    fun addPollingTarget(recipientId: String, metadata: TransportMetadata): Boolean {
        if (!isRunning.get()) {
            Log.w(TAG, "轮询服务未运行，无法添加轮询目标")
            return false
        }
        
        return try {
            Log.d(TAG, "添加轮询目标: recipient=$recipientId, provider=${metadata.providerType}")
            
            pollingLock.write {
                if (pollingTasks.containsKey(recipientId)) {
                    Log.w(TAG, "轮询目标已存在: $recipientId")
                    return@write false
                }
                
                // 创建轮询任务信息
                val taskInfo = PollingTaskInfo.create(recipientId, metadata)
                
                // 计算初始轮询间隔
                val channel = channelManager.getActiveChannel(recipientId, metadata.providerType)
                val initialInterval = calculatePollingInterval(metadata, channel)
                taskInfo.setCurrentInterval(initialInterval)
                
                // 调度轮询任务
                val scheduledTask = schedulePollingTask(taskInfo)
                taskInfo.task = scheduledTask
                taskInfo.setStatus(PollingTaskStatus.RUNNING)
                
                // 添加到任务列表
                pollingTasks[recipientId] = taskInfo
                
                Log.i(TAG, "轮询目标添加成功: recipient=$recipientId, interval=${initialInterval}ms")
                true
            }
        } catch (e: Exception) {
            when (e) {
                is IllegalArgumentException -> Log.e(TAG, "添加轮询目标失败，参数无效: recipient=$recipientId", e)
                is IllegalStateException -> Log.e(TAG, "添加轮询目标失败，状态异常: recipient=$recipientId", e)
                else -> Log.e(TAG, "添加轮询目标失败: recipient=$recipientId, ${e.javaClass.simpleName}", e)
            }
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
                
                if (taskInfo.metadata.providerType != providerType) {
                    Log.w(TAG, "Provider类型不匹配: expected=$providerType, actual=${taskInfo.metadata.providerType}")
                    return@write false
                }
                
                Log.d(TAG, "移除轮询目标: recipient=$recipientId, provider=$providerType")
                
                taskInfo.cleanup()
                pollingTasks.remove(recipientId)
                
                Log.i(TAG, "轮询目标移除成功: $recipientId")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "移除轮询目标失败: recipient=$recipientId, ${e.javaClass.simpleName}", e)
                false
            }
        }
    }
    
    /**
     * 获取轮询状态
     */
    fun getPollingStatus(): TapPollingStatus {
        return pollingLock.read {
            val activeTasks = pollingTasks.values.filter { 
                it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING 
            }
            val totalTasks = pollingTasks.size
            
            val averageInterval = if (activeTasks.isNotEmpty()) {
                activeTasks.map { it.getCurrentInterval() }.average().toLong()
            } else {
                0L
            }
            
            val lastPollingTime = pollingTasks.values.maxOfOrNull { it.getLastPollTime() } ?: 0L
            
            TapPollingStatus(
                isRunning = isRunning.get(),
                activePollingTargets = activeTasks.size,
                totalPollingTargets = totalTasks,
                averagePollingInterval = averageInterval,
                lastPollingTime = lastPollingTime,
                pollingStatistics = createSimpleStatistics(),
                resourceUsage = PollingResourceUsage(
                    memoryUsageKB = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 },
                    activeThreads = pollingExecutor?.activeCount ?: 0,
                    queueLength = pollingExecutor?.queue?.size ?: 0
                ),
                systemStartTime = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 获取当前轮询统计信息
     */
    fun getCurrentStatistics(): TapPollingStatistics {
        return pollingLock.read {
            createSimpleStatistics()
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 调度轮询任务
     */
    private fun schedulePollingTask(taskInfo: PollingTaskInfo): ScheduledFuture<*>? {
        // 添加初始延迟抖动，防止冷启动风暴
        val jitterMs = (Math.random() * TapPollingConstants.PollingService.INITIAL_DELAY_JITTER_MAX_MS).toLong()
        
        return pollingExecutor?.scheduleWithFixedDelay(
            { executePollingTask(taskInfo) },
            jitterMs,
            taskInfo.getCurrentInterval(),
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
        
        // 尝试获取执行权，如果已在执行中则跳过
        if (!taskInfo.tryStartExecution()) {
            Log.d(TAG, "轮询任务已在执行中，跳过: ${taskInfo.recipientId}")
            return
        }
        
        serviceScope?.launch {
            try {
                taskInfo.setStatus(PollingTaskStatus.POLLING)
                taskInfo.updatePollTime()
                
                Log.v(TAG, "执行轮询: ${taskInfo.getSummary()}")
                
                // 检查是否应该跳过轮询
                val channel = channelManager.getActiveChannel(
                    taskInfo.recipientId, 
                    taskInfo.metadata.providerType
                )
                
                if (shouldSkipPolling(taskInfo.recipientId, taskInfo.metadata, channel)) {
                    Log.d(TAG, "跳过轮询: ${taskInfo.recipientId}")
                    taskInfo.setStatus(PollingTaskStatus.PAUSED)
                    return@launch
                }
                
                // 执行实际轮询
                val result = performSinglePoll(taskInfo)
                
                // 处理轮询结果
                handlePollingResult(taskInfo, result)
                
                // 记录任务级别的响应时间
                taskInfo.statistics.recordResponseTime(result.responseTime)
                
                taskInfo.setStatus(PollingTaskStatus.RUNNING)
                
            } catch (e: Exception) {
                Log.e(TAG, "轮询任务执行失败: ${taskInfo.recipientId}", e)
                handlePollingError(taskInfo, e)
            } finally {
                // 确保在任何情况下都释放执行门闩
                taskInfo.finishExecution()
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
                val responseTime = System.currentTimeMillis() - startTime
                return PollingExecutionResult.failure("Provider不可用", responseTime)
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
            val listResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
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
                file.lastModified > (pollingState?.lastProcessedTime ?: 0) &&
                shouldRetryFileProcessing(file.name, taskInfo.recipientId)
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
                    val downloadResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
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
                                // 仅在成功处理消息后标记文件已处理
                                newProcessedFiles.add(file.name)
                                // 清除失败记录（如果存在）
                                clearFileProcessingFailure(file.name, taskInfo.recipientId)
                                Log.d(TAG, "消息处理成功: ${file.name}")
                            } else {
                                // 消息处理失败，记录失败并判断是否可重试
                                val error = (processResult as? TapProcessResult.Failed)?.error ?: "Unknown processing error"
                                val shouldRetry = recordFileProcessingFailure(file.name, taskInfo.recipientId, error)
                                if (!shouldRetry) {
                                    // 超过重试次数，标记为已处理避免无限重试
                                    newProcessedFiles.add(file.name)
                                    Log.w(TAG, "消息处理失败超过重试次数，跳过: ${file.name}")
                                } else {
                                    Log.w(TAG, "消息处理失败，将重试: ${file.name}, error=$error")
                                }
                            }
                        } else {
                            // 文件解析失败，可能不是消息文件，标记已处理避免重复尝试
                            newProcessedFiles.add(file.name)
                            Log.d(TAG, "文件解析失败，可能不是消息文件: ${file.name}")
                        }
                    } else {
                        // 下载失败，不标记已处理，下次继续尝试
                        Log.w(TAG, "文件下载失败: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理文件时发生异常: ${file.name}", e)
                    // 异常情况下记录失败，判断是否可重试
                    val shouldRetry = recordFileProcessingFailure(file.name, taskInfo.recipientId, e.message ?: "Exception during processing")
                    if (!shouldRetry) {
                        // 超过重试次数，标记为已处理
                        newProcessedFiles.add(file.name)
                    }
                }
            }
            
            // 更新任务信息中的已处理文件列表
            taskInfo.lastProcessedFiles = newProcessedFiles
            
            FilePollingResult.success(newProcessedFiles, messagesProcessed)
            
        } catch (e: Exception) {
            Log.e(TAG, "文件轮询异常: ${taskInfo.recipientId}", e)
            FilePollingResult.failure(e.message ?: "UNKNOWN_ERROR", needsRetry = true)
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
            }
            
            result.needsRetry -> {
                // 需要重试
                Log.d(TAG, "轮询需要重试: ${taskInfo.recipientId}, reason=${result.error}")
                // 保持当前状态，等待下次轮询
            }
            
            else -> {
                // 轮询失败
                taskInfo.recordError()
                
                // 检查是否需要暂停轮询
                if (taskInfo.consecutiveErrors.get() >= TapPollingConstants.ErrorBackoff.MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "轮询连续失败次数过多，暂停轮询: ${taskInfo.recipientId}")
                    removePollingTarget(taskInfo.recipientId, taskInfo.metadata.providerType)
                }
            }
        }
    }
    
    /**
     * 从数据库获取已处理的文件列表
     */
    private fun getProcessedFilesFromDatabase(recipientId: String, providerType: String): Set<String> {
        return try {
            // 使用专门的轮询状态表获取已处理文件列表
            val pollingState = pollingStateTable.getPollingState(recipientId, providerType)
            pollingState?.processedFiles ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "获取已处理文件列表失败: recipientId=$recipientId", e)
            emptySet()
        }
    }
    
    /**
     * 处理轮询错误
     */
    private fun handlePollingError(taskInfo: PollingTaskInfo, error: Throwable) {
        taskInfo.recordError()
        taskInfo.setStatus(PollingTaskStatus.ERROR_SUSPENDED)
        
        Log.e(TAG, "轮询任务出现严重错误，暂停任务: ${taskInfo.recipientId}", error)
        
        // 取消当前任务
        taskInfo.task?.cancel(false)
        taskInfo.task = null
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        cleanupTask = pollingExecutor?.scheduleWithFixedDelay(
            { performCleanup() },
            TapPollingConstants.PollingService.CLEANUP_INTERVAL_MS,
            TapPollingConstants.PollingService.CLEANUP_INTERVAL_MS,
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
                
                // 清理文件处理失败记录 - 修复内存泄漏
                cleanupFileProcessingFailures(currentTime)
                
                Log.d(TAG, "清理任务完成: 移除${toRemove.size}个任务")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理任务失败", e)
        }
    }
    
    /**
     * 清理文件处理失败记录，防止内存泄漏
     */
    private fun cleanupFileProcessingFailures(currentTime: Long) {
        try {
            val keysToRemove = mutableListOf<String>()
            val maxFailureRecords = TapPollingConstants.PollingService.MAX_FILE_FAILURE_RECORDS
            val failureExpiryTime = TapPollingConstants.ErrorBackoff.FILE_FAILURE_EXPIRY_MS
            
            // 按时间清理过期记录
            fileProcessingFailures.forEach { (key, failure) ->
                if (currentTime - failure.lastFailureTime > failureExpiryTime) {
                    keysToRemove.add(key)
                }
            }
            
            // 如果记录数量超过限制，清理最老的记录
            if (fileProcessingFailures.size > maxFailureRecords) {
                val sortedFailures = fileProcessingFailures.toList()
                    .sortedBy { it.second.lastFailureTime }
                
                val excessCount = fileProcessingFailures.size - maxFailureRecords
                for (i in 0 until excessCount) {
                    keysToRemove.add(sortedFailures[i].first)
                }
            }
            
            // 执行清理
            keysToRemove.forEach { key ->
                fileProcessingFailures.remove(key)
            }
            
            if (keysToRemove.isNotEmpty()) {
                Log.d(TAG, "清理文件处理失败记录: ${keysToRemove.size}条")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理文件处理失败记录时出错", e)
        }
    }
    
    /**
     * 清理所有资源
     */
    private fun cleanup() {
        try {
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
     * 记录文件处理失败
     * @param fileName 文件名
     * @param recipientId 接收者ID
     * @param error 错误信息
     * @return true 如果应该重试，false 如果已达到重试上限
     */
    private fun recordFileProcessingFailure(fileName: String, recipientId: String, error: String): Boolean {
        val failureKey = "${recipientId}:${fileName}"
        val currentTime = System.currentTimeMillis()
        
        val existingFailure = fileProcessingFailures[failureKey]
        val newFailureCount = (existingFailure?.failureCount ?: 0) + 1
        
        // 检查是否在退避期内
        if (existingFailure != null && 
            currentTime - existingFailure.lastFailureTime < TapPollingConstants.ErrorBackoff.FILE_RETRY_BACKOFF_MS) {
            // 仍在退避期内，不重试
            return false
        }
        
        val failure = FileProcessingFailure(
            fileName = fileName,
            recipientId = recipientId,
            failureCount = newFailureCount,
            lastFailureTime = currentTime,
            lastError = error
        )
        
        fileProcessingFailures[failureKey] = failure
        
        // 如果失败次数超过上限，不再重试
        if (newFailureCount >= TapPollingConstants.ErrorBackoff.MAX_FILE_RETRY_ATTEMPTS) {
            Log.w(TAG, "文件处理失败次数达到上限: $fileName, count=$newFailureCount")
            return false
        }
        
        return true
    }
    
    /**
     * 清除文件处理失败记录
     */
    private fun clearFileProcessingFailure(fileName: String, recipientId: String) {
        val failureKey = "${recipientId}:${fileName}"
        fileProcessingFailures.remove(failureKey)
    }
    
    /**
     * 检查文件是否应该重试处理
     */
    private fun shouldRetryFileProcessing(fileName: String, recipientId: String): Boolean {
        val failureKey = "${recipientId}:${fileName}"
        val failure = fileProcessingFailures[failureKey] ?: return true
        
        val currentTime = System.currentTimeMillis()
        
        // 检查是否超过重试次数
        if (failure.failureCount >= TapPollingConstants.ErrorBackoff.MAX_FILE_RETRY_ATTEMPTS) {
            return false
        }
        
        // 检查是否过了退避时间
        return currentTime - failure.lastFailureTime >= TapPollingConstants.ErrorBackoff.FILE_RETRY_BACKOFF_MS
    }
    
    // === PollingIntervalCallback 实现 ===
    
    /**
     * 调整单个目标的轮询间隔
     */
    fun adjustPollingInterval(recipientId: String, changeType: IntervalChangeType): Boolean {
        return pollingLock.write {
            try {
                val taskInfo = pollingTasks[recipientId]
                if (taskInfo == null) {
                    Log.w(TAG, "调整轮询间隔失败，任务不存在: $recipientId")
                    return@write false
                }
                
                val currentInterval = taskInfo.getCurrentInterval()
                val newInterval = calculateNewInterval(currentInterval, changeType, taskInfo.metadata.providerType)
                
                if (newInterval != currentInterval) {
                    Log.d(TAG, "动态调整轮询间隔: recipient=$recipientId, changeType=$changeType, ${currentInterval}ms -> ${newInterval}ms")
                    
                    // 取消当前任务
                    taskInfo.task?.cancel(false)
                    
                    // 更新间隔
                    taskInfo.setCurrentInterval(newInterval)
                    
                    // 重新调度任务
                    val newTask = schedulePollingTask(taskInfo)
                    taskInfo.task = newTask
                    
                    return@write true
                } else {
                    Log.d(TAG, "轮询间隔无需调整: recipient=$recipientId, interval=${currentInterval}ms")
                    return@write false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "动态调整轮询间隔失败: recipient=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 全局轮询调整
     */
    fun adjustGlobalPolling(changeType: IntervalChangeType) {
        pollingLock.write {
            try {
                Log.d(TAG, "全局轮询调整: changeType=$changeType, 影响任务数=${pollingTasks.size}")
                
                val adjustedCount = pollingTasks.values.count { taskInfo ->
                    val currentInterval = taskInfo.getCurrentInterval()
                    val newInterval = when (changeType) {
                        IntervalChangeType.REEVALUATE -> {
                            // 重新评估时，使用智能策略为每个任务单独计算
                            val channel = channelManager.getActiveChannel(taskInfo.recipientId, taskInfo.metadata.providerType)
                            calculatePollingInterval(taskInfo.metadata, channel)
                        }
                        IntervalChangeType.RESET -> {
                            // 重置到Provider特定的默认间隔
                            getProviderDefaultInterval(taskInfo.metadata.providerType)
                        }
                        else -> {
                            calculateNewInterval(currentInterval, changeType, taskInfo.metadata.providerType)
                        }
                    }
                    
                    if (newInterval != currentInterval) {
                        // 取消当前任务
                        taskInfo.task?.cancel(false)
                        
                        // 更新间隔
                        taskInfo.setCurrentInterval(newInterval)
                        
                        // 重新调度任务
                        val newTask = schedulePollingTask(taskInfo)
                        taskInfo.task = newTask
                        
                        true
                    } else {
                        false
                    }
                }
                
                Log.i(TAG, "全局轮询调整完成: changeType=$changeType, 调整任务数=$adjustedCount")
                
            } catch (e: Exception) {
                Log.e(TAG, "全局轮询调整失败", e)
            }
        }
    }
    
    /**
     * 获取Provider特定的默认间隔
     */
    private fun getProviderDefaultInterval(providerType: String): Long {
        return TapPollingConstants.ProviderIntervals.getBaseInterval(providerType)
    }
    
    /**
     * 根据变化类型计算新的轮询间隔
     * @param currentInterval 当前间隔
     * @param changeType 变化类型
     * @param providerType Provider类型，用于获取特定限制
     */
    private fun calculateNewInterval(currentInterval: Long, changeType: IntervalChangeType, providerType: String = ""): Long {
        val calculatedInterval = when (changeType) {
            IntervalChangeType.INCREASE -> {
                // 增加间隔（降低频率）
                (currentInterval * 1.5).toLong()
            }
            IntervalChangeType.DECREASE -> {
                // 减少间隔（提高频率）
                (currentInterval * 0.7).toLong()
            }
            IntervalChangeType.RESET -> {
                // 重置时返回Provider默认间隔
                getProviderDefaultInterval(providerType)
            }
            IntervalChangeType.ERROR_BACKOFF -> {
                // 错误退避
                (currentInterval * 2.0).toLong()
            }
            IntervalChangeType.LOW_POWER -> {
                // 低电量模式
                (currentInterval * 3.0).toLong()
            }
            IntervalChangeType.REEVALUATE -> {
                // 重新评估时返回当前间隔，实际调整在全局调整方法中处理
                return currentInterval
            }
        }
        
        // 应用Provider特定的限制，如果没有指定Provider则使用全局限制
        return if (providerType.isNotEmpty()) {
            val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(providerType)
            calculatedInterval.coerceIn(minInterval, maxInterval)
        } else {
            // 回退到保守的全局限制
            val globalMin = TapPollingConstants.PollingService.GLOBAL_MIN_INTERVAL_MS
            val globalMax = when (changeType) {
                IntervalChangeType.ERROR_BACKOFF -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_10MIN_MS
                IntervalChangeType.LOW_POWER -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_15MIN_MS
                else -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_5MIN_MS
            }
            calculatedInterval.coerceIn(globalMin, globalMax)
        }
    }

    /**
     * 计算轮询间隔 - 简化版本
     */
    private fun calculatePollingInterval(metadata: TransportMetadata, channel: TransportChannel?): Long {
        // 获取Provider特定的基础间隔
        val baseInterval = TapPollingConstants.ProviderIntervals.getBaseInterval(metadata.providerType)
        
        // 根据活跃度调整
        val activityLevel = calculateActivityLevel(channel)
        val activityMultiplier = when (activityLevel) {
            TransportActivityLevel.ACTIVE -> TapPollingConstants.ActivityMultipliers.ACTIVE_MULTIPLIER
            TransportActivityLevel.INACTIVE -> TapPollingConstants.ActivityMultipliers.INACTIVE_MULTIPLIER
            TransportActivityLevel.BACKGROUND -> TapPollingConstants.ActivityMultipliers.BACKGROUND_MULTIPLIER
            TransportActivityLevel.SUSPENDED -> TapPollingConstants.ActivityMultipliers.SUSPENDED_MULTIPLIER
            TransportActivityLevel.DORMANT -> TapPollingConstants.ActivityMultipliers.DORMANT_MULTIPLIER
        }
        
        val calculatedInterval = (baseInterval * activityMultiplier).toLong()
        
        // 应用Provider限制
        val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(metadata.providerType)
        return calculatedInterval.coerceIn(minInterval, maxInterval)
    }
    
    /**
     * 计算传输活跃度级别 - 简化版本
     */
    private fun calculateActivityLevel(channel: TransportChannel?): TransportActivityLevel {
        if (channel == null) {
            return TransportActivityLevel.INACTIVE
        }
        
        val currentTime = System.currentTimeMillis()
        val lastActiveTime = channel.lastActiveAt
        val timeDiffMs = currentTime - lastActiveTime
        
        return when {
            timeDiffMs <= TapPollingConstants.ActivityThresholds.ACTIVE_THRESHOLD_MS -> TransportActivityLevel.ACTIVE
            timeDiffMs <= TapPollingConstants.ActivityThresholds.INACTIVE_THRESHOLD_MS -> TransportActivityLevel.INACTIVE
            timeDiffMs <= TapPollingConstants.ActivityThresholds.BACKGROUND_THRESHOLD_MS -> TransportActivityLevel.BACKGROUND
            timeDiffMs <= TapPollingConstants.ActivityThresholds.SUSPENDED_THRESHOLD_MS -> TransportActivityLevel.SUSPENDED
            else -> TransportActivityLevel.DORMANT
        }
    }
    
    /**
     * 判断是否应该跳过轮询 - 简化版本
     */
    private fun shouldSkipPolling(recipientId: String, metadata: TransportMetadata, channel: TransportChannel?): Boolean {
        // 检查通道状态
        if (channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FAILED ||
            channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.CLOSED) {
            Log.d(TAG, "跳过轮询: 通道状态异常 - recipient=$recipientId, status=${channel.status}")
            return true
        }
        
        // 检查活跃度级别
        val activityLevel = calculateActivityLevel(channel)
        if (activityLevel == TransportActivityLevel.DORMANT) {
            Log.d(TAG, "跳过轮询: 通信已休眠 - recipient=$recipientId")
            return true
        }
        
        return false
    }
    
    /**
     * 创建简单的统计信息
     */
    private fun createSimpleStatistics(): TapPollingStatistics {
        val totalPolls = pollingTasks.values.sumOf { it.statistics.totalPolls.get() }
        val successfulPolls = pollingTasks.values.sumOf { it.statistics.successfulPolls.get() }
        val failedPolls = pollingTasks.values.sumOf { it.statistics.failedPolls.get() }
        val messagesFound = pollingTasks.values.sumOf { it.statistics.messagesFound.get() }
        
        val averageResponseTime = if (totalPolls > 0) {
            pollingTasks.values
                .map { it.getAverageResponseTime() }
                .filter { it > 0 }
                .average()
                .let { if (it.isNaN()) 0L else it.toLong() }
        } else {
            0L
        }
        
        // 计算各Provider的目标数量
        val providerTargetCounts = pollingTasks.values.groupBy { it.metadata.providerType }
            .mapValues { (_, tasks) ->
                val activeCount = tasks.count { it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING }
                activeCount to tasks.size
            }
        
        // 计算活跃度级别的目标数量
        val activityTargetCounts = pollingTasks.values.groupBy { it.getActivityLevel() }
            .mapValues { (_, tasks) -> tasks.size }
        
        return TapPollingStatistics(
            totalPolls = totalPolls,
            successfulPolls = successfulPolls,
            failedPolls = failedPolls,
            messagesFound = messagesFound,
            averageResponseTime = averageResponseTime,
            providerStatistics = providerTargetCounts.mapValues { (providerType, counts) ->
                ProviderPollingStats(
                    providerType = providerType,
                    totalPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.totalPolls.get() },
                    successfulPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.successfulPolls.get() },
                    failedPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.failedPolls.get() },
                    messagesFound = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.messagesFound.get() },
                    averageResponseTime = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .map { it.getAverageResponseTime() }
                        .filter { it > 0 }
                        .average()
                        .let { if (it.isNaN()) 0L else it.toLong() },
                    activeTargets = counts.first,
                    totalTargets = counts.second
                )
            },
            activityLevelStats = activityTargetCounts.mapValues { (activityLevel, targetCount) ->
                ActivityLevelStats(
                    activityLevel = activityLevel,
                    targetCount = targetCount,
                    totalPolls = pollingTasks.values.filter { it.getActivityLevel() == activityLevel }
                        .sumOf { it.statistics.totalPolls.get() },
                    successfulPolls = pollingTasks.values.filter { it.getActivityLevel() == activityLevel }
                        .sumOf { it.statistics.successfulPolls.get() },
                    averageInterval = activityLevel.baseIntervalMs
                )
            },
            lastHourStats = RecentPollingStats(
                timeRangeMs = 3600000L,
                totalPolls = 0L,
                successfulPolls = 0L,
                messagesFound = 0L,
                averageResponseTime = 0L,
                peakPollingRate = 0.0,
                averagePollingRate = 0.0
            ),
            last24HourStats = RecentPollingStats(
                timeRangeMs = 86400000L,
                totalPolls = 0L,
                successfulPolls = 0L,
                messagesFound = 0L,
                averageResponseTime = 0L,
                peakPollingRate = 0.0,
                averagePollingRate = 0.0
            )
        )
    }
} 

/**
 * 间隔变化类型
 */
enum class IntervalChangeType {
    INCREASE,       // 增加间隔（降低频率）
    DECREASE,       // 减少间隔（提高频率）
    RESET,          // 重置到默认间隔
    ERROR_BACKOFF,  // 错误退避
    LOW_POWER,      // 低电量模式
    REEVALUATE      // 重新评估
} 