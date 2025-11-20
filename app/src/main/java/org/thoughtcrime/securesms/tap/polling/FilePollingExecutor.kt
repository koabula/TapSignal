package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件轮询执行器 (已废弃)
 * 
 * 此类已被废弃，V2模式下完全依赖 WebSocket 推送 + 内联消息机制。
 * 仅保留作为历史兼容性，在 TapPollingService.ENABLE_POLLING = false 时不会被调用。
 * 
 * V2 架构:
 * - 实时通知: WebSocket 推送 + 内联消息 (notification.metadata.message)
 * - 离线通知: 离线轮询 (tap-offline/)
 * - 不再需要: 周期性文件列举 + 轮询
 * 
 * @deprecated V2模式不再使用轮询,此类将在未来版本中移除
 */
@Deprecated(
    message = "V2模式使用 WebSocket 推送,不再需要文件轮询",
    level = DeprecationLevel.WARNING
)
class FilePollingExecutor(
    private val context: Context,
    private val transportManager: TransportManager,
    private val channelManager: TransportChannelManager,
    private val messageDeduplicator: TransportMessageDeduplicator,
    private val messageProcessor: TapMessageProcessor,
    private val errorHandler: TransportErrorHandler
) {
    
    companion object {
        private const val TAG = "FilePollingExecutor"
    }
    
    // 数据库访问
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
    // 文件处理失败跟踪
    private val fileProcessingFailures = ConcurrentHashMap<String, FileProcessingFailure>()
    
    /**
     * 执行单次文件轮询
     */
    suspend fun executePolling(taskInfo: PollingTaskInfo): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.v(TAG, "开始执行轮询: ${taskInfo.getSummary()}")
            
            // 获取Provider
            val provider = transportManager.getProvider(taskInfo.metadata.providerType)
            if (provider == null) {
                Log.e(TAG, "Provider不可用: ${taskInfo.metadata.providerType}")
                val responseTime = System.currentTimeMillis() - startTime
                return PollingExecutionResult.failure("Provider不可用", responseTime)
            }
            
            // 检查是否应该跳过轮询
            val channel = channelManager.getActiveChannel(
                taskInfo.recipientId, 
                taskInfo.metadata.providerType
            )
            
            if (shouldSkipPolling(taskInfo, channel)) {
                Log.d(TAG, "跳过轮询: ${taskInfo.recipientId}")
                val responseTime = System.currentTimeMillis() - startTime
                return PollingExecutionResult.success(0, responseTime)
            }
            
            // 获取轮询状态
            val pollingState = pollingStateTable.getPollingState(
                taskInfo.recipientId, 
                taskInfo.metadata.providerType
            )
            
            // 执行文件轮询
            val pollingResult = performFilePolling(provider, taskInfo, pollingState)
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // 更新轮询状态
            updatePollingState(taskInfo, pollingResult)
            
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
     * 清理文件处理失败记录
     */
    fun cleanupFileProcessingFailures(currentTime: Long) {
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
     * 获取文件处理失败统计
     */
    fun getFileProcessingFailureStatistics(): FileProcessingFailureStats {
        val totalFailures = fileProcessingFailures.size
        val recentFailures = fileProcessingFailures.values.count { 
            System.currentTimeMillis() - it.lastFailureTime < 3600000L // 1小时内
        }
        
        return FileProcessingFailureStats(
            totalFailures = totalFailures,
            recentFailures = recentFailures,
            oldestFailureTime = fileProcessingFailures.values.minOfOrNull { it.lastFailureTime } ?: 0L
        )
    }
    
    // === 私有方法实现 ===
    
    /**
     * 执行文件轮询
     */
    private suspend fun performFilePolling(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        pollingState: org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState?
    ): FilePollingResult {
        return try {
            // 轮询messages和attachments目录
            val basePath = taskInfo.metadata.getReceiveMetadata().path
            val pollingPaths = listOf("${basePath}messages/", "${basePath}attachments/")
            
            val allFiles = mutableListOf<FileInfo>()
            for (path in pollingPaths) {
                val listResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                    errorHandler.executeWithRetry({
                        provider.listFiles(path, taskInfo.metadata)
                    }, ErrorContext(
                        providerType = taskInfo.metadata.providerType,
                        operationType = "listFiles",
                        targetId = taskInfo.recipientId,
                        channelId = "${taskInfo.metadata.providerType}:${taskInfo.recipientId}",
                        metadata = mapOf("pollingPath" to path)
                    ))
                }
                
                if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                    allFiles.addAll(listResult.files)
                }
            }
            
                         if (allFiles.isEmpty()) {
                Log.d(TAG, "未找到文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            // 处理文件列表
            processFileList(provider, taskInfo, allFiles, pollingState)
            
        } catch (e: Exception) {
            Log.e(TAG, "文件轮询异常: ${taskInfo.recipientId}", e)
            FilePollingResult.failure(e.message ?: "UNKNOWN_ERROR", needsRetry = true)
        }
    }
    
    /**
     * 处理文件列表
     */
    private suspend fun processFileList(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        files: List<FileInfo>,
        pollingState: org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState?
    ): FilePollingResult {
        val allFiles = FileInfo.sortByTime(files, ascending = true)
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
            val fileResult = processIndividualFile(provider, taskInfo, file)
            
            when (fileResult.status) {
                FileProcessStatus.SUCCESS -> {
                    messagesProcessed++
                    newProcessedFiles.add(file.name)
                    clearFileProcessingFailure(file.name, taskInfo.recipientId)
                    Log.d(TAG, "文件处理成功: ${file.name}")
                }
                
                FileProcessStatus.FAILED_RETRY -> {
                    val shouldRetry = recordFileProcessingFailure(
                        file.name, 
                        taskInfo.recipientId, 
                        fileResult.error
                    )
                    if (!shouldRetry) {
                        newProcessedFiles.add(file.name)
                        Log.w(TAG, "文件处理失败超过重试次数，跳过: ${file.name}")
                    } else {
                        Log.w(TAG, "文件处理失败，将重试: ${file.name}, error=${fileResult.error}")
                    }
                }
                
                FileProcessStatus.FAILED_SKIP -> {
                    newProcessedFiles.add(file.name)
                    Log.d(TAG, "文件处理失败但跳过: ${file.name}")
                }
                
                FileProcessStatus.DOWNLOAD_FAILED -> {
                    // 下载失败，不标记已处理，下次继续尝试
                    Log.w(TAG, "文件下载失败: ${file.name}")
                }
            }
        }
        
        // 更新任务信息中的已处理文件列表
        taskInfo.lastProcessedFiles = newProcessedFiles
        
        return FilePollingResult.success(newProcessedFiles, messagesProcessed)
    }
    
    /**
     * 处理单个文件
     */
    private suspend fun processIndividualFile(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        file: FileInfo
    ): FileProcessResult {
        return try {
            // 下载文件
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
            
            if (downloadResult !is TransportResult.Success || downloadResult.data == null) {
                return FileProcessResult(FileProcessStatus.DOWNLOAD_FAILED, "下载失败")
            }
            
            // 解析消息
            val message = provider.parseTransportMessage(downloadResult.data, file, taskInfo.metadata)
            if (message == null) {
                return FileProcessResult(FileProcessStatus.FAILED_SKIP, "文件解析失败，可能不是消息文件")
            }
            
            // 处理消息
            val processResult = messageProcessor.processTapTransportMessage(message)
            when (processResult) {
                is TapProcessResult.Success -> {
                    FileProcessResult(FileProcessStatus.SUCCESS, "")
                }
                is TapProcessResult.Failed -> {
                    FileProcessResult(FileProcessStatus.FAILED_RETRY, processResult.error)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理文件时发生异常: ${file.name}", e)
            FileProcessResult(FileProcessStatus.FAILED_RETRY, e.message ?: "处理异常")
        }
    }
    
    /**
     * 更新轮询状态
     */
    private fun updatePollingState(taskInfo: PollingTaskInfo, result: FilePollingResult) {
        try {
            // 仅在发现新消息时更新数据库，避免不必要的数据库写入
            if (result.isSuccess && result.messagesFound > 0) {
                pollingStateTable.recordSuccessfulPoll(
                    taskInfo.recipientId,
                    taskInfo.metadata.providerType,
                    result.processedFiles,
                    result.messagesFound
                )
                Log.d(TAG, "发现${result.messagesFound}条新消息，已更新数据库: ${taskInfo.recipientId}")
            } else if (result.isSuccess) {
                // 轮询成功但无新消息，仅记录日志，不写数据库
                Log.v(TAG, "轮询成功，无新消息: ${taskInfo.recipientId}")
            } else {
                // 轮询失败，记录日志但不写数据库
                Log.w(TAG, "轮询失败: ${taskInfo.recipientId}, error=${result.error}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新轮询状态失败: ${taskInfo.recipientId}", e)
        }
    }
    
    /**
     * 判断是否应该跳过轮询
     */
    private fun shouldSkipPolling(taskInfo: PollingTaskInfo, channel: TransportChannel?): Boolean {
        // 检查通道状态
        if (channel?.status == TransportChannelStatus.FAILED ||
            channel?.status == TransportChannelStatus.CLOSED) {
            Log.d(TAG, "跳过轮询: 通道状态异常 - recipient=${taskInfo.recipientId}, status=${channel.status}")
            return true
        }
        
        // 检查活跃度级别
        val activityLevel = calculateActivityLevel(channel)
        if (activityLevel == TransportActivityLevel.DORMANT) {
            Log.d(TAG, "跳过轮询: 通信已休眠 - recipient=${taskInfo.recipientId}")
            return true
        }
        
        return false
    }
    
    /**
     * 计算传输活跃度级别
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
     * 记录文件处理失败
     */
    private fun recordFileProcessingFailure(fileName: String, recipientId: String, error: String): Boolean {
        val failureKey = "${recipientId}:${fileName}"
        val currentTime = System.currentTimeMillis()
        
        val existingFailure = fileProcessingFailures[failureKey]
        val newFailureCount = (existingFailure?.failureCount ?: 0) + 1
        
        // 检查是否在退避期内
        if (existingFailure != null && 
            currentTime - existingFailure.lastFailureTime < TapPollingConstants.ErrorBackoff.FILE_RETRY_BACKOFF_MS) {
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
}

/**
 * 文件处理结果
 */
private data class FileProcessResult(
    val status: FileProcessStatus,
    val error: String
)

/**
 * 文件处理状态
 */
private enum class FileProcessStatus {
    SUCCESS,        // 成功处理
    FAILED_RETRY,   // 失败但可重试
    FAILED_SKIP,    // 失败但跳过（如解析失败）
    DOWNLOAD_FAILED // 下载失败
}

/**
 * 文件处理失败统计
 */
data class FileProcessingFailureStats(
    val totalFailures: Int,      // 总失败数
    val recentFailures: Int,     // 最近失败数
    val oldestFailureTime: Long  // 最旧失败时间
) 