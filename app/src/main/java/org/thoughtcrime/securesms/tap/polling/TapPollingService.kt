package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.database.SignalDatabase
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
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    
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
     * 轮询单个目标的消息
     */
    private suspend fun pollSingleTarget(taskInfo: PollingTaskInfo): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return withTimeout(POLLING_TIMEOUT_MS) {
            try {
                Log.d(TAG, "开始轮询目标: recipient=${taskInfo.recipientId}, provider=${taskInfo.metadata.providerType}")
                
                val provider = transportManager.getProvider(taskInfo.metadata.providerType)
                if (provider == null) {
                    Log.w(TAG, "Provider不可用: ${taskInfo.metadata.providerType}")
                    val responseTime = System.currentTimeMillis() - startTime
                    return@withTimeout PollingExecutionResult.failure("Provider不可用", responseTime)
                }
                
                // 获取远程文件列表
                val listResult = provider.listFiles(taskInfo.metadata.path, taskInfo.metadata)
                if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                    Log.d(TAG, "未发现新文件: ${taskInfo.recipientId}")
                    val responseTime = System.currentTimeMillis() - startTime
                    return@withTimeout PollingExecutionResult.success(0, responseTime)
                }
                
                // 从数据库获取已处理的文件列表（持久化去重）
                val processedFiles = getProcessedFilesFromDatabase(taskInfo.recipientId, taskInfo.metadata.providerType)
                
                // 过滤出未处理的新文件
                val newFiles = listResult.files.filter { file ->
                    !processedFiles.contains(file.name) && file.isMessageFile()
                }.sortedBy { it.lastModified } // 按时间顺序处理
                
                Log.d(TAG, "找到新文件数量: ${newFiles.size}, recipient: ${taskInfo.recipientId}")
                
                var messagesProcessed = 0
                val newProcessedFiles = mutableSetOf<String>()
                
                // 按时间顺序处理每个新文件
                for (file in newFiles) {
                    try {
                        val downloadResult = withTimeout(POLLING_TIMEOUT_MS) {
                            provider.downloadFile(file, taskInfo.metadata)
                        }
                        
                        if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                            // 验证文件内容是否为有效的TaP消息
                            if (!file.validateMessageFileContent(downloadResult.data)) {
                                Log.w(TAG, "文件内容验证失败，跳过: ${file.name}")
                                continue
                            }
                            
                            // 解析并处理消息
                            val message = parseTransportMessage(downloadResult.data, file)
                            if (message != null) {
                                // 使用去重器处理消息（基于coscomm的去重机制）
                                val messagesToProcess = messageDeduplicator.processMessages(taskInfo.recipientId, listOf(message))
                                
                                if (messagesToProcess.isNotEmpty()) {
                                    // 将去重后的消息传递给Signal主程序处理
                                    for (processedMessage in messagesToProcess) {
                                        deliverMessageToSignal(processedMessage, taskInfo)
                                        messagesProcessed++
                                    }
                                    
                                    // 标记消息为已处理（持久化去重状态）
                                    messageDeduplicator.markMessagesAsProcessed(messagesToProcess, taskInfo.recipientId)
                                    
                                    Log.d(TAG, "消息处理成功: ${file.name}, 处理数量: ${messagesToProcess.size}")
                                } else {
                                    Log.d(TAG, "消息已处理过，跳过: ${file.name}")
                                }
                            } else {
                                Log.w(TAG, "消息解析失败: ${file.name}")
                                continue
                            }
                            // 成功处理的文件标记为已处理
                            newProcessedFiles.add(file.name)
                        } else {
                            Log.w(TAG, "文件下载失败: ${file.name}")
                            // 下载失败的文件不标记为已处理，下次继续尝试
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理文件失败: ${file.name}", e)
                        // 处理失败的文件不标记为已处理
                    }
                }
                
                // 保存处理结果到任务信息（用于数据库更新）
                taskInfo.lastProcessedFiles = newProcessedFiles
                
                val responseTime = System.currentTimeMillis() - startTime
                PollingExecutionResult.success(messagesProcessed, responseTime)
                
            } catch (e: TimeoutCancellationException) {
                val responseTime = System.currentTimeMillis() - startTime
                PollingExecutionResult.retry("轮询超时", responseTime)
            } catch (e: Exception) {
                val responseTime = System.currentTimeMillis() - startTime
                PollingExecutionResult.failure(e.message ?: "未知错误", responseTime)
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
            val listResult = withTimeout(POLLING_TIMEOUT_MS) {
                provider.listFiles(taskInfo.metadata.path, taskInfo.metadata)
            }
            
            if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                Log.d(TAG, "未找到文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            // 过滤新文件（未处理的文件）
            val allFiles = FileInfo.sortByTime(listResult.files, ascending = true)
            val processedFiles = pollingState?.processedFiles ?: emptySet()
            
            val newFiles = allFiles.filter { file ->
                !processedFiles.contains(file.name) && 
                file.isMessageFile() &&
                file.lastModified > (pollingState?.lastProcessedTime ?: 0)
            }
            
            if (newFiles.isEmpty()) {
                Log.d(TAG, "没有新文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            Log.d(TAG, "找到新文件数量: ${newFiles.size}, recipient: ${taskInfo.recipientId}")
            
            var messagesProcessed = 0
            val newProcessedFiles = mutableSetOf<String>()
            
            // 按时间顺序处理每个新文件
            for (file in newFiles) {
                try {
                    val downloadResult = withTimeout(POLLING_TIMEOUT_MS) {
                        provider.downloadFile(file, taskInfo.metadata)
                    }
                    
                    if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                        // 验证文件内容是否为有效的TaP消息
                        if (!file.validateMessageFileContent(downloadResult.data)) {
                            Log.w(TAG, "文件内容验证失败，跳过: ${file.name}")
                            continue
                        }
                        
                        // 解析并处理消息
                        val message = parseTransportMessage(downloadResult.data, file)
                        if (message != null) {
                            // 将消息传递给Signal主程序处理
                            deliverMessageToSignal(message, taskInfo)
                            messagesProcessed++
                            Log.d(TAG, "消息处理成功: ${file.name}")
                        }
                        newProcessedFiles.add(file.name)
                    } else {
                        Log.w(TAG, "文件下载失败: ${file.name}")
                        // 下载失败的文件不标记为已处理，下次继续尝试
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理文件失败: ${file.name}", e)
                    // 处理失败的文件不标记为已处理
                }
            }
            
            FilePollingResult.success(newProcessedFiles, messagesProcessed)
            
        } catch (e: TimeoutCancellationException) {
            FilePollingResult.failure("轮询超时", needsRetry = true)
        } catch (e: Exception) {
            FilePollingResult.failure(e.message ?: "未知错误", needsRetry = true)
        }
    }
    
    /**
     * 解析传输消息 - 使用统一的JSON格式，兼容coscomm模块
     * 
     * 支持两种格式：
     * 1. 新的JSON格式（基于coscomm的CosMessage）
     * 2. 旧的二进制格式（向后兼容）
     */
    private fun parseTransportMessage(data: ByteArray, fileInfo: FileInfo): TransportMessage? {
        return try {
            Log.d(TAG, "开始解析传输消息: ${fileInfo.name}, size=${data.size}")
            
            // 首先尝试JSON格式解析
            val jsonResult = parseJsonMessage(data, fileInfo)
            if (jsonResult != null) {
                Log.d(TAG, "成功解析JSON格式消息: messageId=${jsonResult.messageId}")
                return jsonResult
            }
            
            // 回退到旧的二进制格式（向后兼容）
            Log.d(TAG, "尝试解析旧的二进制格式: ${fileInfo.name}")
            return parseLegacyBinaryMessage(data, fileInfo)
            
        } catch (e: Exception) {
            Log.e(TAG, "解析传输消息失败: ${fileInfo.name} - ${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 解析JSON格式消息（兼容coscomm模块）
     */
    private fun parseJsonMessage(data: ByteArray, fileInfo: FileInfo): TransportMessage? {
        return try {
            // 将字节数据转换为JSON字符串
            val jsonString = String(data, Charsets.UTF_8)
            
            // 使用TransportMessage的反序列化方法
            val message = TransportMessage.deserialize(data)
            
            // 从文件名中提取发送者和接收者信息（如果JSON中没有）
            val fileNameParts = parseFileNameForRecipients(fileInfo.name)
            val finalMessage = if (message.senderId.isBlank() || message.recipientId.isBlank()) {
                // 补充缺失的发送者/接收者信息
                message.copy(
                    senderId = fileNameParts?.senderId ?: message.senderId,
                    recipientId = fileNameParts?.recipientId ?: message.recipientId
                )
            } else {
                message
            }
            
            Log.d(TAG, "成功解析JSON消息: messageId=${finalMessage.messageId}, type=${finalMessage.messageType}")
            finalMessage
            
        } catch (e: Exception) {
            Log.d(TAG, "JSON格式解析失败，尝试其他格式: ${e.message}")
            null
        }
    }
    
    /**
     * 解析旧的二进制格式消息（向后兼容）
     */
    private fun parseLegacyBinaryMessage(data: ByteArray, fileInfo: FileInfo): TransportMessage? {
        return try {
            if (data.size < 20) {
                Log.w(TAG, "二进制消息文件格式无效：文件过小: ${fileInfo.name}")
                return null
            }
            
            // 从文件名中提取基本信息作为回退方案
            val fileNameInfo = parseFileNameForRecipients(fileInfo.name)
            val messageId = fileNameInfo?.messageId ?: java.util.UUID.randomUUID().toString()
            val timestamp = fileNameInfo?.timestamp ?: fileInfo.lastModified
            
            // 对于旧格式，将整个数据视为加密内容
            val signalCiphertext = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            
            // 创建默认的内容元数据
            val contentMetadata = TransportContentMetadata(
                originalSize = data.size.toLong(),
                compressionType = TransportCompressionType.NONE
            )
            
            val message = TransportMessage(
                messageId = messageId,
                timestamp = timestamp,
                senderId = fileNameInfo?.senderId ?: "",
                recipientId = fileNameInfo?.recipientId ?: "",
                messageType = TransportMessageType.TEXT_MESSAGE,
                signalCiphertext = signalCiphertext,
                contentMetadata = contentMetadata
            )
            
            Log.d(TAG, "成功解析二进制格式消息: messageId=$messageId")
            message
            
        } catch (e: Exception) {
            Log.e(TAG, "解析二进制格式消息失败: ${fileInfo.name}", e)
            null
        }
    }
    
    /**
     * 从文件名中解析发送者和接收者信息
     * 
     * 支持的文件名格式：
     * - messageId_timestamp.dat
     * - senderId_messageId_timestamp.dat
     * - senderId_recipientId_messageId_timestamp.dat
     */
    private fun parseFileNameForRecipients(fileName: String): FileNameInfo? {
        return try {
            val baseName = fileName.substringBeforeLast('.')
            val parts = baseName.split('_')
            
            when (parts.size) {
                2 -> {
                    // messageId_timestamp格式
                    FileNameInfo(
                        messageId = parts[0],
                        timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = "",
                        recipientId = ""
                    )
                }
                3 -> {
                    // senderId_messageId_timestamp格式
                    FileNameInfo(
                        messageId = parts[1],
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = ""
                    )
                }
                4 -> {
                    // senderId_recipientId_messageId_timestamp格式
                    FileNameInfo(
                        messageId = parts[2],
                        timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = parts[1]
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析文件名失败: $fileName", e)
            null
        }
    }
    
    /**
     * 文件名信息数据类
     */
    private data class FileNameInfo(
        val messageId: String,
        val timestamp: Long,
        val senderId: String,
        val recipientId: String
    )
    
    /**
     * 字节数组转int（大端序）
     */
    private fun bytesToInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
               ((data[offset + 1].toInt() and 0xFF) shl 16) or
               ((data[offset + 2].toInt() and 0xFF) shl 8) or
               (data[offset + 3].toInt() and 0xFF)
    }
    
    /**
     * 字节数组转long（大端序）
     */
    private fun bytesToLong(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 56) or
               ((data[offset + 1].toLong() and 0xFF) shl 48) or
               ((data[offset + 2].toLong() and 0xFF) shl 40) or
               ((data[offset + 3].toLong() and 0xFF) shl 32) or
               ((data[offset + 4].toLong() and 0xFF) shl 24) or
               ((data[offset + 5].toLong() and 0xFF) shl 16) or
               ((data[offset + 6].toLong() and 0xFF) shl 8) or
               (data[offset + 7].toLong() and 0xFF)
    }
    
    /**
     * 将消息传递给Signal主程序处理
     */
    private suspend fun deliverMessageToSignal(message: TransportMessage, taskInfo: PollingTaskInfo) {
        try {
            Log.i(TAG, "开始传递消息到Signal: messageId=${message.messageId}, recipient=${taskInfo.recipientId}")
            
            // 1. 检查消息去重
            val messageProcessor = org.thoughtcrime.securesms.tap.integration.TapMessageProcessor.getInstance(context)
            if (messageProcessor.isDuplicateMessage(message.messageId, taskInfo.recipientId)) {
                Log.d(TAG, "跳过重复消息: messageId=${message.messageId}")
                return
            }
            
            // 2. 通过TapMessageProcessor处理消息
            val processResult = messageProcessor.processIncomingMessage(message, taskInfo.recipientId)
            
            if (processResult) {
                Log.i(TAG, "消息成功传递到Signal: messageId=${message.messageId}")
            } else {
                Log.w(TAG, "消息传递失败: messageId=${message.messageId}")
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
                if (taskInfo.consecutiveErrors.get() >= MAX_RETRY_ATTEMPTS) {
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
     * 从数据库获取已处理文件列表（去重）
     */
    private fun getProcessedFilesFromDatabase(recipientId: String, providerType: String): Set<String> {
        return try {
            val pollingState = pollingStateTable.getPollingState(recipientId, providerType)
            pollingState?.processedFiles ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "从数据库获取已处理文件列表失败: $recipientId", e)
            emptySet()
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