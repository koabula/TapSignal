package org.thoughtcrime.securesms.coscomm.service

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.*
import org.thoughtcrime.securesms.coscomm.utils.CosPathManager
import org.thoughtcrime.securesms.cos.CosFileInfo
// CamPoolManager已删除，使用SubAccountPoolManager
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * COS消息轮询服务
 * 后台服务定期检查新消息，支持并发轮询和错误处理重试机制
 *
 * 修复内容：
 * 1. 实现基于消息ID的去重机制
 * 2. 修复轮询状态同步问题
 * 3. 完善资源管理和线程安全
 * 4. 添加消息处理状态持久化
 */
class CosPollingService(private val context: Context) {

    companion object {
        private val TAG = Log.tag(CosPollingService::class.java)

        // 并发配置
        private const val CORE_POOL_SIZE = 2
        private const val MAX_POOL_SIZE = 8
        private const val KEEP_ALIVE_TIME = 60L
        private const val QUEUE_CAPACITY = 100

        // 轮询配置
        private const val INITIAL_DELAY = 5000L        // 初始延迟5秒
        private const val MIN_POLLING_INTERVAL = 1000L // 最小轮询间隔1秒
        private const val MAX_POLLING_INTERVAL = 600000L // 最大轮询间隔10分钟

        // 批量处理配置 - 移除BATCH_SIZE限制，下载所有新消息
        private const val BATCH_TIMEOUT = 60000L      // 批量操作超时60秒（增加超时时间）
        private const val MAX_DOWNLOAD_RETRIES = 3    // 最大下载重试次数
        private const val RETRY_DELAY_BASE = 2000L    // 重试延迟基础时间2秒

        // 消息去重配置
        private const val MESSAGE_CACHE_SIZE = 10000   // 消息ID缓存大小
        private const val MESSAGE_CACHE_EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L // 7天过期

        // 持久化存储键名
        private const val PREF_NAME = "cos_polling_service"
        private const val KEY_PROCESSED_MESSAGES = "processed_messages"
        private const val KEY_LAST_POLLING_TIMES = "last_polling_times"
        private const val KEY_FAILED_DOWNLOADS = "failed_downloads"
        private const val KEY_ATTEMPTED_MESSAGES = "attempted_messages"
        private const val KEY_FILENAME_TO_MESSAGEID_MAPPING = "filename_to_messageid_mapping"
        private const val KEY_PERMANENT_FAILURE_LIST = "permanent_failure_list"
    }

    // 核心组件
    private val subAccountPoolManager = org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager.getInstance(context)
    private val cosMessageService = CosMessageService.getInstance(context)
    private val pollingStrategy = IntelligentPollingStrategy(context)
    private val cosMessageProcessor by lazy {
        org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor.getInstance(context)
    }

    // 线程池和调度器
    private val pollingExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(CORE_POOL_SIZE)
    private val processingExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        CORE_POOL_SIZE,
        MAX_POOL_SIZE,
        KEEP_ALIVE_TIME,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        ThreadFactory { r -> Thread(r, "CosPolling-${System.currentTimeMillis()}") }
    )

    // 状态管理
    private val isRunning = AtomicBoolean(false)
    private val lastPollingTime = AtomicLong(0)
    private val pollingTasks: MutableMap<String, ScheduledFuture<*>> = ConcurrentHashMap()

    // 统计信息
    private val pollingStats = PollingStatistics()

    // 消息去重管理
    private val rwLock = ReentrantReadWriteLock()
    private val processedMessageIds: MutableMap<String, Long> = ConcurrentHashMap()
    private val lastPollingTimes: MutableMap<String, Long> = ConcurrentHashMap()
    
    // 已尝试处理的消息记录（包括解密失败的消息）
    private val attemptedMessageIds: MutableMap<String, Long> = ConcurrentHashMap()
    
    // 🔧 修复：添加文件名ID到消息内容ID的映射，解决状态管理混乱问题
    private val fileNameToMessageIdMapping: MutableMap<String, String> = ConcurrentHashMap()

    // 失败下载重试管理
    private val failedDownloads: MutableMap<String, FailedDownloadInfo> = ConcurrentHashMap()
    
    // 🆕 永久失败消息排除列表
    private val permanentFailureList: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // JSON序列化工具
    private val objectMapper = ObjectMapper()
    private val messageMapType = object : TypeReference<MutableMap<String, Long>>() {}
    private val failedDownloadMapType = object : TypeReference<MutableMap<String, FailedDownloadInfo>>() {}
    // 🔧 修复：为文件名到消息ID映射添加正确的类型引用
    private val stringMapType = object : TypeReference<MutableMap<String, String>>() {}

    // SharedPreferences存储
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // 初始化标志
    private val isInitialized = AtomicBoolean(false)
    
    /**
     * 初始化轮询服务
     */
    private fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            Log.d(TAG, "初始化COS轮询服务")
            loadPersistedData()
            Log.d(TAG, "COS轮询服务初始化完成")
        }
    }

    /**
     * 加载持久化数据
     */
    private fun loadPersistedData() {
        rwLock.write {
            try {
                Log.d(TAG, "加载持久化的轮询数据")

                // 加载已处理消息ID
                val processedJson = sharedPreferences.getString(KEY_PROCESSED_MESSAGES, null)
                if (!processedJson.isNullOrEmpty()) {
                    val processedMap: MutableMap<String, Long> = objectMapper.readValue(processedJson, messageMapType)
                    processedMessageIds.clear()
                    processedMessageIds.putAll(processedMap)
                    Log.d(TAG, "加载已处理消息ID: ${processedMessageIds.size}个")
                }

                // 加载最后轮询时间
                val pollingTimesJson = sharedPreferences.getString(KEY_LAST_POLLING_TIMES, null)
                if (!pollingTimesJson.isNullOrEmpty()) {
                    val pollingTimesMap: MutableMap<String, Long> = objectMapper.readValue(pollingTimesJson, messageMapType)
                    lastPollingTimes.clear()
                    lastPollingTimes.putAll(pollingTimesMap)
                    Log.d(TAG, "加载最后轮询时间: ${lastPollingTimes.size}个")
                }

                // 加载已尝试处理消息 ID
                val attemptedJson = sharedPreferences.getString(KEY_ATTEMPTED_MESSAGES, null)
                if (!attemptedJson.isNullOrEmpty()) {
                    val attemptedMap: MutableMap<String, Long> = objectMapper.readValue(attemptedJson, messageMapType)
                    attemptedMessageIds.clear()
                    attemptedMessageIds.putAll(attemptedMap)
                    Log.d(TAG, "加载已尝试处理消息 ID: ${attemptedMessageIds.size}个")
                }

                // 加载失败下载记录
                val failedDownloadsJson = sharedPreferences.getString(KEY_FAILED_DOWNLOADS, null)
                if (!failedDownloadsJson.isNullOrEmpty()) {
                    val failedDownloadsMap: MutableMap<String, FailedDownloadInfo> = objectMapper.readValue(failedDownloadsJson, failedDownloadMapType)
                    failedDownloads.clear()
                    failedDownloads.putAll(failedDownloadsMap)
                    Log.d(TAG, "加载失败下载记录: ${failedDownloads.size}个")
                }
                
                // 🔧 修复：加载文件名到消息ID的映射关系
                val mappingJson = sharedPreferences.getString(KEY_FILENAME_TO_MESSAGEID_MAPPING, null)
                if (!mappingJson.isNullOrEmpty()) {
                    val mappingMap: MutableMap<String, String> = objectMapper.readValue(mappingJson, stringMapType)
                    fileNameToMessageIdMapping.clear()
                    fileNameToMessageIdMapping.putAll(mappingMap)
                    Log.d(TAG, "加载文件名到消息ID映射: ${fileNameToMessageIdMapping.size}个")
                }

                // 🆕 加载永久失败消息列表
                val permanentFailureListJson = sharedPreferences.getString(KEY_PERMANENT_FAILURE_LIST, null)
                if (!permanentFailureListJson.isNullOrEmpty()) {
                    val permanentFailureSet: Set<String> = objectMapper.readValue(permanentFailureListJson, object : TypeReference<Set<String>>() {})
                    permanentFailureList.clear()
                    permanentFailureList.addAll(permanentFailureSet)
                    Log.d(TAG, "加载永久失败消息列表: ${permanentFailureList.size}个")
                }

            } catch (e: Exception) {
                Log.e(TAG, "加载持久化数据失败", e)
                processedMessageIds.clear()
                lastPollingTimes.clear()
            }
        }
    }

    /**
     * 持久化数据
     */
    private fun persistData() {
        rwLock.read {
            try {
                val editor = sharedPreferences.edit()

                // 持久化已处理消息ID
                val processedJson = objectMapper.writeValueAsString(processedMessageIds)
                editor.putString(KEY_PROCESSED_MESSAGES, processedJson)

                // 持久化最后轮询时间
                val pollingTimesJson = objectMapper.writeValueAsString(lastPollingTimes)
                editor.putString(KEY_LAST_POLLING_TIMES, pollingTimesJson)

                // 持久化已尝试处理消息 ID
                val attemptedJson = objectMapper.writeValueAsString(attemptedMessageIds)
                editor.putString(KEY_ATTEMPTED_MESSAGES, attemptedJson)

                // 持久化失败下载记录
                val failedDownloadsJson = objectMapper.writeValueAsString(failedDownloads)
                editor.putString(KEY_FAILED_DOWNLOADS, failedDownloadsJson)
                
                // 🔧 修复：持久化文件名到消息ID的映射关系
                val mappingJson = objectMapper.writeValueAsString(fileNameToMessageIdMapping)
                editor.putString(KEY_FILENAME_TO_MESSAGEID_MAPPING, mappingJson)
                
                // 🆕 持久化永久失败消息列表
                val permanentFailureJson = objectMapper.writeValueAsString(permanentFailureList)
                editor.putString(KEY_PERMANENT_FAILURE_LIST, permanentFailureJson)

                editor.apply()

                Log.d(TAG, "轮询数据持久化完成")

            } catch (e: Exception) {
                Log.e(TAG, "持久化数据失败", e)
            }
        }
    }

    /**
     * 清理过期数据
     */
    private fun cleanExpiredData() {
        val currentTime = System.currentTimeMillis()
        val expiredThreshold = currentTime - MESSAGE_CACHE_EXPIRE_MS

        // 清理过期的消息ID
        val expiredMessageIds = processedMessageIds.entries.filter { (_, timestamp) ->
            timestamp < expiredThreshold
        }.map { it.key }

        expiredMessageIds.forEach { messageId ->
            processedMessageIds.remove(messageId)
        }

        if (expiredMessageIds.isNotEmpty()) {
            Log.d(TAG, "清理过期消息ID: ${expiredMessageIds.size}个")
        }

        // 清理过期的已尝试处理消息 ID
        val expiredAttemptedIds = attemptedMessageIds.entries.filter { (_, timestamp) ->
            timestamp < expiredThreshold
        }.map { it.key }

        expiredAttemptedIds.forEach { messageId ->
            attemptedMessageIds.remove(messageId)
        }

        if (expiredAttemptedIds.isNotEmpty()) {
            Log.d(TAG, "清理过期已尝试处理消息 ID: ${expiredAttemptedIds.size}个")
        }

        // 限制缓存大小
        if (processedMessageIds.size > MESSAGE_CACHE_SIZE) {
            val sortedEntries = processedMessageIds.entries.sortedBy { it.value }
            val toRemove = sortedEntries.take(processedMessageIds.size - MESSAGE_CACHE_SIZE)
            toRemove.forEach { (messageId, _) ->
                processedMessageIds.remove(messageId)
            }
            Log.d(TAG, "限制缓存大小，移除: ${toRemove.size}个")
        }

        // 清理过期的失败下载记录（超过24小时且超过最大重试次数）
        val expiredFailedDownloads = failedDownloads.entries.filter { (messageId, failedInfo) ->
            val timeSinceFirstFailure = currentTime - failedInfo.firstFailureTime
            val shouldRemove = timeSinceFirstFailure > 24 * 3600 * 1000L && failedInfo.retryCount >= MAX_DOWNLOAD_RETRIES
            
            if (shouldRemove) {
                // 🆕 添加到永久失败列表
                permanentFailureList.add(messageId)
                Log.d(TAG, "将过期失败消息添加到永久失败列表: messageId=$messageId")
            }
            
            shouldRemove
        }.map { it.key }

        expiredFailedDownloads.forEach { messageId ->
            failedDownloads.remove(messageId)
        }

        if (expiredFailedDownloads.isNotEmpty()) {
            Log.d(TAG, "清理过期失败下载记录: ${expiredFailedDownloads.size}个")
        }
        
        // 🆕 清理过期的永久失败列表（超过7天）
        val sevenDaysAgo = currentTime - 7 * 24 * 3600 * 1000L
        val expiredPermanentFailures = permanentFailureList.filter { messageId ->
            // 简单的启发式：如果消息ID看起来很旧（基于时间戳），则清理
            try {
                val timestamp = extractTimestampFromMessageId(messageId)
                timestamp > 0 && timestamp < sevenDaysAgo
            } catch (e: Exception) {
                false // 如果无法解析时间戳，保留在列表中
            }
        }
        
        expiredPermanentFailures.forEach { messageId ->
            permanentFailureList.remove(messageId)
        }
        
        if (expiredPermanentFailures.isNotEmpty()) {
            Log.d(TAG, "清理过期永久失败记录: ${expiredPermanentFailures.size}个")
        }
    }

    /**
     * 启动轮询服务
     */
    fun startPolling() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "启动COS消息轮询服务")

            // 确保已初始化
            initialize()

            // 延迟启动，避免应用启动时的资源竞争
            pollingExecutor.schedule({
                initializePollingTasks()
            }, INITIAL_DELAY, TimeUnit.MILLISECONDS)

            Log.i(TAG, "COS消息轮询服务已启动")
        } else {
            Log.w(TAG, "轮询服务已在运行中")
        }
    }
    
    /**
     * 停止轮询服务
     */
    fun stopPolling() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "停止COS消息轮询服务")

            // 取消所有轮询任务
            pollingTasks.values.forEach { task ->
                try {
                    task.cancel(false)
                } catch (e: Exception) {
                    Log.w(TAG, "取消轮询任务异常", e)
                }
            }
            pollingTasks.clear()

            // 持久化当前状态
            persistData()

            Log.i(TAG, "COS消息轮询服务已停止")
        }
    }

    /**
     * 重启轮询服务
     */
    fun restartPolling() {
        Log.i(TAG, "重启COS消息轮询服务")
        stopPolling()

        // 等待确保完全停止
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "重启等待被中断", e)
        }

        startPolling()
    }

    /**
     * 关闭轮询服务（释放所有资源）
     */
    fun shutdown() {
        Log.i(TAG, "关闭COS轮询服务")

        try {
            // 停止轮询
            stopPolling()

            // 关闭线程池
            pollingExecutor.shutdown()
            processingExecutor.shutdown()

            // 等待线程池关闭
            if (!pollingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollingExecutor.shutdownNow()
            }

            if (!processingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow()
            }

            // 重置状态
            isInitialized.set(false)

            Log.i(TAG, "COS轮询服务关闭完成")

        } catch (e: Exception) {
            Log.e(TAG, "关闭轮询服务异常", e)
        }
    }
    
    /**
     * 初始化轮询任务
     */
    private fun initializePollingTasks() {
        Log.d(TAG, "初始化轮询任务")

        val activeSubAccountEntries = subAccountPoolManager.getAllValidReceivedSubAccounts().filter { it.isActive }
        Log.i(TAG, "发现${activeSubAccountEntries.size}个活跃子账户条目")

        activeSubAccountEntries.forEach { entry ->
            schedulePollingTaskForSubAccount(entry)
        }

        // 启动定期任务重新评估轮询策略
        schedulePollingReassessment()
    }
    
    /**
     * 为指定子账户条目安排轮询任务
     */
    private fun schedulePollingTaskForSubAccount(subAccountEntry: SubAccountEntry) {
        val recipientId = subAccountEntry.recipientId

        // 创建临时的CamPoolEntry用于兼容现有的轮询策略
        val tempCamEntry = CamPoolEntry.create(recipientId, subAccountEntry.accessInfo)

        // 检查是否应该跳过轮询
        if (pollingStrategy.shouldSkipPolling(recipientId, tempCamEntry)) {
            Log.d(TAG, "跳过轮询任务: recipientId=$recipientId")
            return
        }

        // 计算轮询间隔
        val pollingInterval = pollingStrategy.calculatePollingInterval(recipientId, tempCamEntry)
        val clampedInterval = pollingInterval.coerceIn(MIN_POLLING_INTERVAL, MAX_POLLING_INTERVAL)

        Log.d(TAG, "安排轮询任务: recipientId=$recipientId, interval=${clampedInterval}ms")

        // 取消现有任务（如果存在）
        pollingTasks[recipientId]?.cancel(false)

        // 创建新的轮询任务
        val pollingTask = pollingExecutor.scheduleWithFixedDelay({
            try {
                executePollingTask(recipientId, tempCamEntry)
            } catch (e: Exception) {
                Log.e(TAG, "轮询任务执行异常: recipientId=$recipientId", e)
                handlePollingError(recipientId, e)
            }
        }, clampedInterval, clampedInterval, TimeUnit.MILLISECONDS)
        
        pollingTasks[recipientId] = pollingTask
    }
    
    /**
     * 执行单个轮询任务
     */
    private fun executePollingTask(recipientId: String, camEntry: CamPoolEntry) {
        if (!isRunning.get()) {
            Log.d(TAG, "服务已停止，跳过轮询: recipientId=$recipientId")
            return
        }
        
        Log.d(TAG, "执行轮询任务: recipientId=$recipientId")
        pollingStats.incrementPollingAttempts()
        
        // 异步执行轮询操作
        val pollingFuture = CompletableFuture.supplyAsync({
            performPolling(recipientId, camEntry)
        }, processingExecutor)
        
        // 设置超时处理
        try {
            val result = pollingFuture.get(BATCH_TIMEOUT, TimeUnit.MILLISECONDS)
            handlePollingResult(recipientId, result)
        } catch (e: TimeoutException) {
            Log.w(TAG, "轮询超时: recipientId=$recipientId")
            pollingFuture.cancel(true)
            handlePollingError(recipientId, e)
        } catch (e: Exception) {
            Log.e(TAG, "轮询执行异常: recipientId=$recipientId", e)
            handlePollingError(recipientId, e)
        }
    }
    
    /**
     * 执行实际的轮询操作
     */
    private fun performPolling(recipientId: String, camEntry: CamPoolEntry): PollingResult {
        Log.d(TAG, "开始轮询消息: recipientId=$recipientId")
        
        try {
            // 列举对方COS存储桶中的新消息
            val listResult = cosMessageService.listMessages(recipientId).get()
            
            when (listResult) {
                is CosListResult.Success -> {
                    val newMessages = filterNewMessages(recipientId, listResult.files)
                    Log.d(TAG, "发现${newMessages.size}条新消息: recipientId=$recipientId")
                    
                    if (newMessages.isNotEmpty()) {
                        // 下载并处理新消息
                        val downloadedMessages = downloadMessages(recipientId, newMessages)
                        return PollingResult.Success(downloadedMessages)
                    } else {
                        return PollingResult.NoNewMessages
                    }
                }
                is CosListResult.Failure -> {
                    Log.w(TAG, "列举消息失败: recipientId=$recipientId, error=${listResult.error}")
                    return PollingResult.Error(listResult.error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "轮询操作异常: recipientId=$recipientId", e)
            return PollingResult.Error(e.message ?: "未知错误")
        }
    }
    
    /**
     * 过滤出新消息（修复版：解决消息重复下载问题）
     * 🔧 修复：防止解密失败的消息被重复下载，导致Double Ratchet状态破坏
     */
    private fun filterNewMessages(recipientId: String, allFiles: List<CosFileInfo>): List<CosFileInfo> {
        return rwLock.write {
            try {
                Log.d(TAG, "过滤新消息: recipientId=$recipientId, 总文件数=${allFiles.size}")

                val lastPollingTime = lastPollingTimes[recipientId] ?: 0L
                val currentTime = System.currentTimeMillis()

                val messageFiles = allFiles.filter { file ->
                    CosPathManager.isMessageFilePath(file.key)
                }

                Log.d(TAG, "消息文件数: ${messageFiles.size}")

                val newMessages = messageFiles.filter { file ->
                    val fileNameId = extractMessageIdFromFileName(file.key)
                    
                    // 使用文件名映射获得真实消息ID（若已建立），但不据此直接跳过
                    val actualMessageId = fileNameToMessageIdMapping[fileNameId] ?: fileNameId
                    
                    // 状态检查
                    val isProcessed = processedMessageIds.containsKey(actualMessageId)
                    val isAttempted = attemptedMessageIds.containsKey(actualMessageId)
                    val hasFileNameMapping = fileNameToMessageIdMapping.containsKey(fileNameId)
                    val isAfterLastPolling = file.lastModified > lastPollingTime
                    val isValidFile = file.size > 0

                    // 永久失败与超限检查
                    if (permanentFailureList.contains(fileNameId)) {
                        Log.d(TAG, "跳过永久失败消息: fileNameId=$fileNameId")
                        return@filter false
                    }
                    val failedInfo = failedDownloads[fileNameId]
                    if (failedInfo != null && failedInfo.retryCount >= MAX_DOWNLOAD_RETRIES) {
                        Log.d(TAG, "永久跳过失败消息: messageId=$fileNameId, retryCount=${failedInfo.retryCount}")
                        permanentFailureList.add(fileNameId)
                        Log.d(TAG, "添加到永久失败列表: messageId=$fileNameId")
                        return@filter false
                    }

                    // 失败记录重试判定
                    val shouldRetryFailed = failedInfo != null && shouldRetryFailedDownload(failedInfo)

                    // 已尝试处理但失败（或未知结果）的消息：基于重试窗口决定是否放行
                    val allowRetryAttempted = if (isAttempted && !isProcessed) {
                        val attemptTime = attemptedMessageIds[actualMessageId] ?: 0L
                        val timeSinceAttempt = currentTime - attemptTime
                        val allow = timeSinceAttempt > RETRY_DELAY_BASE
                        if (allow) {
                            Log.d(TAG, "允许重试已尝试消息: messageId=$actualMessageId, elapsed=${timeSinceAttempt}ms")
                        } else {
                            Log.d(TAG, "过滤未到重试窗口的已尝试消息: messageId=$actualMessageId, elapsed=${timeSinceAttempt}ms")
                        }
                        allow
                    } else false

                    Log.d(TAG, "检查消息: fileNameId=$fileNameId, actualMessageId=$actualMessageId, isProcessed=$isProcessed, isAttempted=$isAttempted, hasMapping=$hasFileNameMapping, isAfterLastPolling=$isAfterLastPolling, isValidFile=$isValidFile, fileSize=${file.size}")

                    // 放行条件：
                    // 1) 从未处理/未尝试且是新文件，或
                    // 2) 记录为失败且到达失败重试窗口，或
                    // 3) 已尝试但未处理且到了attempted重试窗口
                    (!isProcessed && !isAttempted && isAfterLastPolling && isValidFile)
                        || shouldRetryFailed
                        || allowRetryAttempted
                }

                // 更新最后轮询时间
                lastPollingTimes[recipientId] = currentTime

                Log.d(TAG, "过滤结果: 新消息${newMessages.size}个")

                // 如果有新消息，记录详细信息
                if (newMessages.isNotEmpty()) {
                    newMessages.forEach { file ->
                        val fileNameId = extractMessageIdFromFileName(file.key)
                        val failedInfo = failedDownloads[fileNameId]
                        if (failedInfo != null) {
                            Log.i(TAG, "发现重试消息: recipientId=$recipientId, file=${file.key}, retryCount=${failedInfo.retryCount}")
                        } else {
                            Log.i(TAG, "发现新消息: recipientId=$recipientId, file=${file.key}, size=${file.size}, lastModified=${file.lastModified}")
                        }
                    }
                }

                newMessages

            } catch (e: Exception) {
                Log.e(TAG, "过滤新消息异常: recipientId=$recipientId", e)
                emptyList()
            }
        }
    }

    /**
     * 从文件名中提取消息ID
     */
    private fun extractMessageIdFromFileName(fileName: String): String {
        try {
            // 文件名格式: {sequence}_{message_number}_{chain_number}_{random}.json
            // 或者: {messageId}.json
            val baseName = fileName.substringAfterLast("/").substringBeforeLast(".json")

            // 如果包含下划线，说明是新格式，使用整个baseName作为messageId
            // 如果不包含下划线，直接使用baseName
            return if (baseName.contains("_")) {
                baseName // 整个文件名（不含扩展名）作为messageId
            } else {
                baseName // 直接使用baseName
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取消息ID失败: fileName=$fileName", e)
            return fileName // 失败时返回完整文件名
        }
    }
    
    /**
     * 🆕 从消息ID中提取时间戳（启发式方法）
     * 消息ID格式通常为: 0000000042_00042_000_5d7180a
     * 尝试从文件名中解析时间信息
     */
    private fun extractTimestampFromMessageId(messageId: String): Long {
        return try {
            // 尝试从文件名中提取序列号或时间信息
            // 文件名格式: {sequence}_{message_number}_{chain_number}_{random}
            val parts = messageId.split("_")
            if (parts.isNotEmpty()) {
                // 第一部分是序列号（如0000000042）
                val sequenceStr = parts[0].removePrefix("0") // 移除前导零
                val sequence = sequenceStr.toLongOrNull() ?: 0L
                
                // 如果序列号很小（比如 < 1000），可能是旧消息
                // 这里返回一个虚拟的时间戳，让它看起来很旧
                if (sequence > 0 && sequence < 1000000) {
                    // 假设序列号是基于时间的，返回一个较旧的时间戳
                    System.currentTimeMillis() - (1000000 - sequence) * 60 * 1000L // 根据序列号计算相对时间
                } else {
                    System.currentTimeMillis() // 返回当前时间，不会被清理
                }
            } else {
                System.currentTimeMillis() // 无法解析，返回当前时间
            }
        } catch (e: Exception) {
            System.currentTimeMillis() // 解析失败，返回当前时间
        }
    }

    /**
     * 标记消息为已处理（公共方法，用于测试）
     */
    fun markMessageAsProcessed(messageId: String) {
        rwLock.write {
            processedMessageIds[messageId] = System.currentTimeMillis()
            Log.d(TAG, "标记消息已处理: messageId=$messageId")

            // 定期清理过期数据
            if (processedMessageIds.size % 100 == 0) {
                cleanExpiredData()
            }
        }
    }
    
    /**
     * 标记消息为已尝试处理（公共方法，用于测试）
     */
    fun markMessageAsAttempted(messageId: String) {
        rwLock.write {
            attemptedMessageIds[messageId] = System.currentTimeMillis()
            Log.d(TAG, "标记消息为已尝试处理: messageId=$messageId")

            // 定期清理过期数据
            if (attemptedMessageIds.size % 100 == 0) {
                cleanExpiredData()
            }
        }
    }
    
    /**
     * 🔧 修复：检查消息是否已处理（用于MessageDeduplicationManager同步状态）
     */
    fun isMessageProcessed(messageId: String): Boolean {
        return rwLock.read {
            processedMessageIds.containsKey(messageId)
        }
    }
    
    /**
     * 标记消息为已处理（原私有方法保留）
     */
    private fun markMessageAsProcessedInternal(messageId: String) {
        rwLock.write {
            processedMessageIds[messageId] = System.currentTimeMillis()
            Log.d(TAG, "标记消息已处理: messageId=$messageId")

            // 定期清理过期数据
            if (processedMessageIds.size % 100 == 0) {
                cleanExpiredData()
            }
        }
    }
    
    /**
     * 下载消息列表 - 修复版本：下载所有新消息，失败消息支持重试
     * 🔧 修复：完善文件名ID与消息内容ID的映射管理，确保状态管理一致性
     */
    private fun downloadMessages(recipientId: String, messageFiles: List<CosFileInfo>): List<CosMessage> {
        val downloadedMessages = mutableListOf<CosMessage>()

        Log.i(TAG, "开始下载所有新消息: recipientId=$recipientId, 消息文件数=${messageFiles.size}")

        // 移除BATCH_SIZE限制，处理所有消息文件
        messageFiles.forEach { fileInfo ->
            try {
                val fileNameId = extractMessageIdFromFileName(fileInfo.key)

                // 🔧 修复：检查映射关系和处理状态的一致性
                if (fileNameToMessageIdMapping.containsKey(fileNameId)) {
                    val existingMessageId = fileNameToMessageIdMapping[fileNameId]!!
                    
                    // 检查消息是否真正被处理过
                    val isProcessed = processedMessageIds.containsKey(existingMessageId)
                    val isAttempted = attemptedMessageIds.containsKey(existingMessageId)
                    
                    if (isProcessed) {
                        // 消息已成功处理，可以安全跳过
                        Log.d(TAG, "跳过已处理的消息: fileNameId=$fileNameId, messageId=$existingMessageId")
                        return@forEach
                    } else if (isAttempted) {
                        // 消息曾尝试处理但失败，检查重试条件
                        val attemptTime = attemptedMessageIds[existingMessageId] ?: 0L
                        val timeSinceAttempt = System.currentTimeMillis() - attemptTime
                        val shouldRetry = timeSinceAttempt > RETRY_DELAY_BASE
                        
                        if (!shouldRetry) {
                            Log.d(TAG, "跳过重试等待期的消息: fileNameId=$fileNameId, messageId=$existingMessageId")
                            return@forEach
                        } else {
                            Log.w(TAG, "重新处理失败消息: fileNameId=$fileNameId, messageId=$existingMessageId")
                            // 继续下载，不跳过
                        }
                    } else {
                        // 🔧 关键修复：有映射但从未处理过的消息，重新下载处理
                        Log.w(TAG, "发现映射不一致的消息，重新下载: fileNameId=$fileNameId, messageId=$existingMessageId")
                        // 清理错误的映射关系，重新下载
                        fileNameToMessageIdMapping.remove(fileNameId)
                    }
                }

                // 检查是否是需要重试的失败下载
                val failedInfo = failedDownloads[fileNameId]
                
                // 🔧 修复：永久跳过超过最大重试次数的消息
                if (failedInfo != null && failedInfo.retryCount >= MAX_DOWNLOAD_RETRIES) {
                    Log.d(TAG, "超过最大重试次数: messageId=$fileNameId, retryCount=${failedInfo.retryCount}")
                    return@forEach
                }
                
                if (failedInfo != null && !shouldRetryFailedDownload(failedInfo)) {
                    Log.d(TAG, "跳过重试下载: fileNameId=$fileNameId, 重试次数=${failedInfo.retryCount}")
                    return@forEach
                }

                val downloadResult = cosMessageService.downloadMessage(recipientId, fileInfo.key).get()
                when (downloadResult) {
                    is CosDownloadResult.Success -> {
                        downloadedMessages.add(downloadResult.message)
                        Log.i(TAG, "成功下载消息: recipientId=$recipientId, messageId=${downloadResult.message.messageId}")

                        // 🔧 修复：建立文件名ID和消息内容ID的映射关系
                        updateFileNameToMessageIdMapping(fileNameId, downloadResult.message.messageId)
                        
                        // 🔧 修复：下载成功后不立即标记为已尝试处理，让消息进入正常处理链路
                        // 在实际消息处理成功后再标记状态，避免过早过滤导致处理链路中断
                        failedDownloads.remove(fileNameId)
                        Log.d(TAG, "已下载消息，等待上层处理: fileName=$fileNameId, contentMessageId=${downloadResult.message.messageId}")
                    }
                    is CosDownloadResult.Failure -> {
                        Log.w(TAG, "下载消息失败: recipientId=$recipientId, file=${fileInfo.key}, error=${downloadResult.error}")

                        // 🔧 修复：增强失败处理，对特定错误提供更多信息
                        val detailedError = "${downloadResult.error} (file: ${fileInfo.key})"
                        
                        // 检查是否是客户端创建失败相关的错误
                        val isClientCreationError = downloadResult.error.contains("无法创建临时COS客户端") || 
                                                   downloadResult.error.contains("池化客户端和主客户端都失败")
                        
                        if (isClientCreationError) {
                            Log.w(TAG, "⚠️ 检测到客户端创建失败，下次轮询将重试: messageId=$fileNameId")
                            // 对于客户端创建失败，使用更短的重试延迟
                            recordFailedDownload(fileNameId, detailedError, isQuickRetry = true)
                        } else {
                            // 普通下载失败，使用正常重试策略
                            recordFailedDownload(fileNameId, detailedError)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "下载消息异常: recipientId=$recipientId, file=${fileInfo.key}", e)

                // 异常情况下记录失败信息，不标记为已处理
                val fileNameId = extractMessageIdFromFileName(fileInfo.key)
                val detailedError = "${e.message ?: "未知异常"} (exception: ${e.javaClass.simpleName})"
                
                // 🔧 修复：检查是否是网络或客户端相关错误，给予快速重试机会
                val isNetworkError = e is java.net.ConnectException ||
                                   e is java.net.UnknownHostException ||
                                   e is java.util.concurrent.TimeoutException ||
                                   e.message?.contains("网络") == true ||
                                   e.message?.contains("超时") == true
                
                recordFailedDownload(fileNameId, detailedError, isQuickRetry = isNetworkError)
            }
        }

        // 持久化处理状态
        if (downloadedMessages.isNotEmpty() || failedDownloads.isNotEmpty()) {
            persistData()
        }

        Log.i(TAG, "消息下载完成: recipientId=$recipientId, 成功=${downloadedMessages.size}, 总数=${messageFiles.size}")
        return downloadedMessages
    }
    
    /**
     * 🔧 新增：更新文件名ID到消息内容ID的映射关系
     * 确保映射关系的一致性和状态管理的同步
     * @param fileNameId 文件名ID（从文件名提取）
     * @param contentMessageId 消息内容ID（从消息内容中获取）
     */
    private fun updateFileNameToMessageIdMapping(fileNameId: String, contentMessageId: String) {
        rwLock.write {
            // 检查是否已经存在映射关系
            val existingMapping = fileNameToMessageIdMapping[fileNameId]
            if (existingMapping != null) {
                if (existingMapping == contentMessageId) {
                    Log.d(TAG, "映射关系已存在且一致: fileNameId=$fileNameId, contentMessageId=$contentMessageId")
                    return@write
                } else {
                    Log.w(TAG, "映射关系冲突: fileNameId=$fileNameId, existing=$existingMapping, new=$contentMessageId")
                    // 更新为新的映射关系
                }
            }
            
            // 建立或更新映射关系
            fileNameToMessageIdMapping[fileNameId] = contentMessageId
            Log.d(TAG, "建立映射关系: fileNameId=$fileNameId -> contentMessageId=$contentMessageId")
            
            // 🔧 修复：同步检查和清理过旧的状态
            // 如果文件名ID在attemptedMessageIds中，但实际的contentMessageId不在，需要同步
            if (attemptedMessageIds.containsKey(fileNameId) && !attemptedMessageIds.containsKey(contentMessageId)) {
                val attemptTime = attemptedMessageIds[fileNameId]!!
                attemptedMessageIds[contentMessageId] = attemptTime
                Log.d(TAG, "同步已尝试状态: fileNameId=$fileNameId -> contentMessageId=$contentMessageId")
            }
            
            // 如果文件名ID在processedMessageIds中，但实际的contentMessageId不在，需要同步
            if (processedMessageIds.containsKey(fileNameId) && !processedMessageIds.containsKey(contentMessageId)) {
                val processTime = processedMessageIds[fileNameId]!!
                processedMessageIds[contentMessageId] = processTime
                Log.d(TAG, "同步已处理状态: fileNameId=$fileNameId -> contentMessageId=$contentMessageId")
            }
        }
    }
    
    /**
     * 处理轮询结果
     */
    private fun handlePollingResult(recipientId: String, result: PollingResult) {
        when (result) {
            is PollingResult.Success -> {
                Log.i(TAG, "轮询成功: recipientId=$recipientId, messages=${result.messages.size}")
                pollingStats.incrementSuccessfulPolling()

                // 重置错误计数
                subAccountPoolManager.resetPollingErrors(recipientId).onError { exception ->
                    Log.w(TAG, "重置轮询错误失败: ${exception.message}")
                }

                // 更新最后轮询时间
                lastPollingTime.set(System.currentTimeMillis())

                // 🔧 修复：只处理真正的新消息，过滤已处理的消息
                if (result.messages.isNotEmpty()) {
                    val newMessages = filterAlreadyProcessedMessages(recipientId, result.messages)
                    if (newMessages.isNotEmpty()) {
                        Log.i(TAG, "发现新消息需要处理: recipientId=$recipientId, new=${newMessages.size}, total=${result.messages.size}")
                        processReceivedMessages(recipientId, newMessages)
                    } else {
                        Log.d(TAG, "所有消息都已处理过，跳过: recipientId=$recipientId")
                    }
                }
            }
            is PollingResult.NoNewMessages -> {
                Log.d(TAG, "无新消息: recipientId=$recipientId")
                pollingStats.incrementSuccessfulPolling()
                subAccountPoolManager.resetPollingErrors(recipientId).onError { exception ->
                    Log.w(TAG, "重置轮询错误失败: ${exception.message}")
                }
                lastPollingTime.set(System.currentTimeMillis())
            }
            is PollingResult.Error -> {
                Log.w(TAG, "轮询失败: recipientId=$recipientId, error=${result.error}")
                pollingStats.incrementFailedPolling()
                subAccountPoolManager.incrementPollingErrors(recipientId).onError { exception ->
                    Log.w(TAG, "增加轮询错误失败: ${exception.message}")
                }
            }
        }
    }
    
    /**
     * 记录失败下载信息（增强版）
     * @param messageId 消息 ID
     * @param error 错误信息
     * @param isQuickRetry 是否是快速重试类型的错误（网络错误、客户端创建失败等）
     */
    private fun recordFailedDownload(messageId: String, error: String, isQuickRetry: Boolean = false) {
        rwLock.write {
            val currentTime = System.currentTimeMillis()
            val existingInfo = failedDownloads[messageId]

            if (existingInfo != null) {
                // 更新现有失败记录
                val updatedInfo = existingInfo.copy(
                    retryCount = existingInfo.retryCount + 1,
                    lastFailureTime = currentTime,
                    lastError = error
                )
                failedDownloads[messageId] = updatedInfo
                Log.d(TAG, "更新失败下载记录: messageId=$messageId, retryCount=${updatedInfo.retryCount}, isQuickRetry=$isQuickRetry")
            } else {
                // 创建新的失败记录
                val newInfo = FailedDownloadInfo(
                    messageId = messageId,
                    firstFailureTime = currentTime,
                    lastFailureTime = currentTime,
                    retryCount = 1,
                    lastError = error
                )
                failedDownloads[messageId] = newInfo
                Log.d(TAG, "创建失败下载记录: messageId=$messageId, isQuickRetry=$isQuickRetry")
            }

            // 🔧 修复：对于快速重试类型的错误，记录标记以便优先重试
            if (isQuickRetry) {
                // 在失败信息中添加标记，表示这是可以快速重试的错误
                val updatedInfo = failedDownloads[messageId]?.copy(
                    lastError = "[QUICK_RETRY] $error"
                )
                if (updatedInfo != null) {
                    failedDownloads[messageId] = updatedInfo
                    Log.d(TAG, "标记为快速重试: messageId=$messageId")
                }
            }
        }
    }

    /**
     * 检查是否应该重试失败的下载（增强版）
     */
    private fun shouldRetryFailedDownload(failedInfo: FailedDownloadInfo): Boolean {
        val currentTime = System.currentTimeMillis()

        // 超过最大重试次数
        if (failedInfo.retryCount >= MAX_DOWNLOAD_RETRIES) {
            Log.d(TAG, "超过最大重试次数: messageId=${failedInfo.messageId}, retryCount=${failedInfo.retryCount}")
            return false
        }

        // 🔧 修复：检查是否是快速重试类型的错误
        val isQuickRetryError = failedInfo.lastError.contains("[QUICK_RETRY]") ||
                              failedInfo.lastError.contains("无法创建临时COS客户端") ||
                              failedInfo.lastError.contains("池化客户端和主客户端都失败") ||
                              failedInfo.lastError.contains("网络") ||
                              failedInfo.lastError.contains("超时")

        // 计算重试延迟（指数退避）
        val baseDelay = if (isQuickRetryError) {
            // 对于快速重试类型错误，使用更短的延迟
            RETRY_DELAY_BASE / 4  // 500ms
        } else {
            RETRY_DELAY_BASE      // 2000ms
        }
        
        val retryDelay = baseDelay * Math.pow(2.0, (failedInfo.retryCount - 1).toDouble()).toLong()
        val timeSinceLastFailure = currentTime - failedInfo.lastFailureTime

        if (timeSinceLastFailure < retryDelay) {
            Log.d(TAG, "重试延迟未到: messageId=${failedInfo.messageId}, 需要等待=${retryDelay - timeSinceLastFailure}ms, isQuickRetry=$isQuickRetryError")
            return false
        }

        Log.d(TAG, "可以重试下载: messageId=${failedInfo.messageId}, retryCount=${failedInfo.retryCount}, isQuickRetry=$isQuickRetryError")
        return true
    }

    /**
     * 处理轮询错误（改进错误分类和处理）
     */
    private fun handlePollingError(recipientId: String, error: Throwable) {
        Log.e(TAG, "轮询错误: recipientId=$recipientId", error)
        pollingStats.incrementFailedPolling()

        // 分析错误类型
        val errorType = classifyPollingError(error)
        Log.w(TAG, "轮询错误分类: recipientId=$recipientId, type=$errorType")

        // 根据错误类型决定处理策略 - 改进恢复机制
        when (errorType) {
            PollingErrorType.PERMISSION_DENIED -> {
                Log.w(TAG, "权限错误，增加错误计数但不完全停止轮询: recipientId=$recipientId")
                // 不再直接停止轮询，而是增加错误计数，让智能轮询策略处理
            }
            PollingErrorType.CREDENTIAL_EXPIRED -> {
                Log.w(TAG, "凭证过期，标记子账户为过期但保持轮询以便恢复: recipientId=$recipientId")
                subAccountPoolManager.markSubAccountExpired(recipientId)
                // 不直接返回，继续执行重新安排逻辑
            }
            PollingErrorType.NETWORK_ERROR -> {
                Log.w(TAG, "网络错误，将重试轮询: recipientId=$recipientId")
                // 继续执行重新安排逻辑
            }
            PollingErrorType.UNKNOWN_ERROR -> {
                Log.w(TAG, "未知错误，将重试轮询: recipientId=$recipientId")
                // 继续执行重新安排逻辑
            }
        }

        subAccountPoolManager.incrementPollingErrors(recipientId).onError { exception ->
            Log.w(TAG, "增加轮询错误失败: ${exception.message}")
        }

        // 重新安排轮询任务（应用错误退避策略）
        val subAccountEntry = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        if (subAccountEntry != null) {
            schedulePollingTaskForSubAccount(subAccountEntry)
        }
    }

    /**
     * 分类轮询错误类型
     */
    private fun classifyPollingError(error: Throwable): PollingErrorType {
        val errorMessage = error.message?.lowercase() ?: ""

        return when {
            errorMessage.contains("permission") || errorMessage.contains("access denied") ||
            errorMessage.contains("forbidden") || errorMessage.contains("unauthorized") -> {
                PollingErrorType.PERMISSION_DENIED
            }
            errorMessage.contains("expired") || errorMessage.contains("invalid credentials") ||
            errorMessage.contains("token") -> {
                PollingErrorType.CREDENTIAL_EXPIRED
            }
            errorMessage.contains("network") || errorMessage.contains("timeout") ||
            errorMessage.contains("connection") || error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException -> {
                PollingErrorType.NETWORK_ERROR
            }
            else -> PollingErrorType.UNKNOWN_ERROR
        }
    }

    /**
     * 轮询错误类型枚举
     */
    enum class PollingErrorType {
        PERMISSION_DENIED,    // 权限错误
        CREDENTIAL_EXPIRED,   // 凭证过期
        NETWORK_ERROR,        // 网络错误
        UNKNOWN_ERROR         // 未知错误
    }
    
    /**
     * 过滤已处理的消息，只返回新消息
     * 修复双发问题：极简过滤逻辑，完全依赖Signal原生Double Ratchet机制处理重复、排序和重试
     */
    private fun filterAlreadyProcessedMessages(recipientId: String, allMessages: List<CosMessage>): List<CosMessage> {
        return rwLock.read {
            Log.d(TAG, "过滤已处理消息: recipientId=$recipientId, 消息总数=${allMessages.size}")
            
            // 极简过滤：只排除已成功处理的消息，其他全部交给Signal原生机制
            val filteredMessages = allMessages.filter { message ->
                val isProcessed = processedMessageIds.containsKey(message.messageId)
                
                if (isProcessed) {
                    Log.v(TAG, "跳过已处理消息: messageId=${message.messageId}")
                    false
                } else {
                    Log.d(TAG, "待处理消息: messageId=${message.messageId}")
                    true
                }
            }
            
            Log.i(TAG, "消息过滤结果: recipientId=$recipientId, 原始=${allMessages.size}, 过滤后=${filteredMessages.size}")
            
            // 移除重试等待期检查，让Signal原生ProtocolDuplicateMessageException机制处理重复消息
            filteredMessages
        }
    }
    
    /**
     * 处理接收到的消息
     * 修复双发问题：不预先标记消息状态，让Signal原生机制处理重复和排序
     */
    private fun processReceivedMessages(recipientId: String, messages: List<CosMessage>) {
        Log.i(TAG, "处理接收到的消息: recipientId=$recipientId, count=${messages.size}")

        // 提交到处理线程池异步处理
        processingExecutor.submit {
            try {
                // 直接处理消息，不预先标记状态，让Signal原生ProtocolDuplicateMessageException处理重复
                val processingFuture = cosMessageProcessor.processReceivedMessages(recipientId, messages)
                val result = processingFuture.get()

                when (result) {
                    is org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor.ProcessingResult.Success -> {
                        Log.i(TAG, "消息处理成功: recipientId=$recipientId, processed=${result.processedCount}/${result.totalCount}")
                        
                        // 只标记成功处理的消息为已处理，失败的消息交给Signal原生机制重试
                        if (result.processedMessages.isNotEmpty()) {
                            result.processedMessages.forEach { message ->
                                markMessageAsProcessedInternal(message.messageId)
                            }
                            Log.d(TAG, "已标记${result.processedMessages.size}条消息为已处理: recipientId=$recipientId")
                            persistData()
                        }
                    }
                    is org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor.ProcessingResult.NoNewMessages -> {
                        Log.d(TAG, "无新消息需要处理: recipientId=$recipientId")
                    }
                    is org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor.ProcessingResult.Error -> {
                        Log.e(TAG, "消息处理失败: recipientId=$recipientId, error=${result.error}")
                        // 不标记失败消息，允许重试
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理消息异常: recipientId=$recipientId", e)
                // 异常情况下不标记消息状态，允许重试
            }
        }
    }
    
    /**
     * 安排轮询策略重新评估
     */
    private fun schedulePollingReassessment() {
        pollingExecutor.scheduleWithFixedDelay({
            try {
                reassessPollingStrategy()
            } catch (e: Exception) {
                Log.e(TAG, "重新评估轮询策略异常", e)
            }
        }, 300000L, 300000L, TimeUnit.MILLISECONDS) // 每5分钟重新评估一次
    }
    
    /**
     * 重新评估轮询策略
     */
    private fun reassessPollingStrategy() {
        if (!isRunning.get()) return

        Log.d(TAG, "重新评估轮询策略")

        val activeSubAccountEntries = subAccountPoolManager.getAllValidReceivedSubAccounts().filter { it.isActive }

        // 移除已失效的轮询任务
        val currentRecipients = pollingTasks.keys.toSet()
        val activeRecipients = activeSubAccountEntries.map { it.recipientId }.toSet()

        (currentRecipients - activeRecipients).forEach { recipientId ->
            Log.d(TAG, "移除失效轮询任务: recipientId=$recipientId")
            pollingTasks[recipientId]?.cancel(false)
            pollingTasks.remove(recipientId)
        }

        // 为新的或需要更新的子账户条目安排轮询任务
        activeSubAccountEntries.forEach { entry ->
            schedulePollingTaskForSubAccount(entry)
        }
        
        Log.d(TAG, "轮询策略重新评估完成，当前活跃任务数: ${pollingTasks.size}")
    }

    /**
     * 停止特定联系人的轮询任务
     */
    fun stopPollingForRecipient(recipientId: String) {
        Log.i(TAG, "停止特定联系人的轮询任务: recipientId=$recipientId")

        pollingTasks[recipientId]?.let { task ->
            try {
                task.cancel(false)
                pollingTasks.remove(recipientId)
                Log.i(TAG, "轮询任务已停止: recipientId=$recipientId")
            } catch (e: Exception) {
                Log.w(TAG, "停止轮询任务异常: recipientId=$recipientId", e)
            }
        } ?: run {
            Log.d(TAG, "未找到轮询任务: recipientId=$recipientId")
        }
    }

    /**
     * 强制重新评估轮询策略（公共方法）
     */
    fun forceReevaluatePollingStrategy() {
        Log.i(TAG, "强制重新评估轮询策略")
        reassessPollingStrategy()
    }

    /**
     * 获取轮询服务状态
     */
    fun getPollingStatus(): PollingStatus {
        return rwLock.read {
            PollingStatus(
                isRunning = isRunning.get(),
                isInitialized = isInitialized.get(),
                activeTaskCount = pollingTasks.size,
                lastPollingTime = lastPollingTime.get(),
                processedMessageCount = processedMessageIds.size,
                lastPollingTimes = lastPollingTimes.toMap(),
                statistics = pollingStats.getSnapshot()
            )
        }
    }

    /**
     * 获取去重统计信息
     */
    fun getDeduplicationStatistics(): DeduplicationStatistics {
        return rwLock.read {
            val currentTime = System.currentTimeMillis()
            val recentMessages = processedMessageIds.values.count { timestamp ->
                (currentTime - timestamp) < 24 * 60 * 60 * 1000L // 24小时内
            }

            DeduplicationStatistics(
                totalProcessedMessages = processedMessageIds.size,
                recentProcessedMessages = recentMessages,
                cacheSize = processedMessageIds.size,
                lastCleanupTime = currentTime,
                failedDownloadsCount = failedDownloads.size,
                retryableFailedDownloads = failedDownloads.values.count { it.retryCount < MAX_DOWNLOAD_RETRIES },
                attemptedMessagesCount = attemptedMessageIds.size
            )
        }
    }

    /**
     * 清理处理状态（用于测试或重置）
     */
    fun clearProcessingState(): Boolean {
        return rwLock.write {
            try {
                processedMessageIds.clear()
                lastPollingTimes.clear()
                failedDownloads.clear()
                attemptedMessageIds.clear()
                fileNameToMessageIdMapping.clear()  // 🔧 修复：清理映射关系

                // 清理持久化数据
                sharedPreferences.edit()
                    .remove(KEY_PROCESSED_MESSAGES)
                    .remove(KEY_LAST_POLLING_TIMES)
                    .remove(KEY_FAILED_DOWNLOADS)
                    .remove(KEY_ATTEMPTED_MESSAGES)
                    .remove(KEY_FILENAME_TO_MESSAGEID_MAPPING)  // 🔧 修复：清理映射数据
                    .apply()

                Log.i(TAG, "轮询处理状态已清理")
                true
            } catch (e: Exception) {
                Log.e(TAG, "清理处理状态失败", e)
                false
            }
        }
    }
    
    /**
     * 轮询结果密封类
     */
    sealed class PollingResult {
        data class Success(val messages: List<CosMessage>) : PollingResult()
        object NoNewMessages : PollingResult()
        data class Error(val error: String) : PollingResult()
    }
    
    /**
     * 轮询状态数据类
     */
    data class PollingStatus(
        val isRunning: Boolean,
        val isInitialized: Boolean,
        val activeTaskCount: Int,
        val lastPollingTime: Long,
        val processedMessageCount: Int,
        val lastPollingTimes: Map<String, Long>,
        val statistics: PollingStatistics.Snapshot
    )

    /**
     * 去重统计信息
     */
    data class DeduplicationStatistics(
        val totalProcessedMessages: Int,
        val recentProcessedMessages: Int,
        val cacheSize: Int,
        val lastCleanupTime: Long,
        val failedDownloadsCount: Int,
        val retryableFailedDownloads: Int,
        val attemptedMessagesCount: Int
    )
    
    /**
     * 轮询统计信息
     */
    class PollingStatistics {
        private val pollingAttempts = AtomicLong(0)
        private val successfulPolling = AtomicLong(0)
        private val failedPolling = AtomicLong(0)
        
        fun incrementPollingAttempts() = pollingAttempts.incrementAndGet()
        fun incrementSuccessfulPolling() = successfulPolling.incrementAndGet()
        fun incrementFailedPolling() = failedPolling.incrementAndGet()
        
        fun getSnapshot(): Snapshot {
            return Snapshot(
                pollingAttempts = pollingAttempts.get(),
                successfulPolling = successfulPolling.get(),
                failedPolling = failedPolling.get()
            )
        }
        
        data class Snapshot(
            val pollingAttempts: Long,
            val successfulPolling: Long,
            val failedPolling: Long
        ) {
            val successRate: Double
                get() = if (pollingAttempts > 0) successfulPolling.toDouble() / pollingAttempts else 0.0
        }
    }

    /**
     * 失败下载信息数据类
     */
    data class FailedDownloadInfo(
        val messageId: String,
        val firstFailureTime: Long,
        val lastFailureTime: Long,
        val retryCount: Int,
        val lastError: String
    )
}
