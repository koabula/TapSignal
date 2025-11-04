package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import kotlinx.coroutines.*
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult
import org.thoughtcrime.securesms.tap.polling.TapPollingConstants
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * 推送通知下载执行器
 * 
 * 参考FilePollingExecutor的实现，提供完整的下载流程：
 * 1. list objects (获取文件列表)
 * 2. filter (过滤出新文件)
 * 3. download (下载文件)
 * 4. parse and process (解析和处理消息)
 * 
 * 专门用于推送通知触发的下载，复用polling模块的核心逻辑
 */
class NotificationDownloadExecutor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(NotificationDownloadExecutor::class.java)
        
        @Volatile
        private var instance: NotificationDownloadExecutor? = null
        
        fun getInstance(context: Context): NotificationDownloadExecutor {
            return instance ?: synchronized(this) {
                instance ?: NotificationDownloadExecutor(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
    
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val errorHandler = TransportErrorHandler.getInstance(context)
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
    // 文件处理失败跟踪
    private val fileProcessingFailures = ConcurrentHashMap<String, FileProcessingFailure>()
    
    /**
     * 执行推送触发的直接下载（新方案）
     * 从WebSocket通知中提取完整的文件路径，直接下载该文件
     * 
     * @param notification 推送通知消息
     * @return 下载执行结果
     */
    suspend fun executeDirectDownload(notification: NotificationMessage): NotificationDownloadResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // P0修复：在下载执行器入口添加详细日志
            Log.i(TAG, "[推送下载] executeDirectDownload called: senderId=${notification.senderId}, type=${notification.type}")
            
            // 1. 从notification.metadata中提取文件路径
            val fileKey = notification.metadata["key"] as? String
            if (fileKey == null) {
                Log.e(TAG, "[推送下载失败] 推送通知缺少文件key")
                Log.e(TAG, "[诊断] notification内容: type=${notification.type}, " +
                    "senderId=${notification.senderId}, timestamp=${notification.timestamp}, " +
                    "metadata=${notification.metadata}")
                return NotificationDownloadResult.failure(
                    "推送通知缺少文件key",
                    System.currentTimeMillis() - startTime
                )
            }
            
            val bucketName = notification.metadata["bucket"] as? String
            Log.i(TAG, "[推送下载] 开始直接下载推送文件: key=$fileKey, bucket=$bucketName")
            
            // 2. 从senderId提取纯hash，查找recipientId和通道
            val pureHashId = if (notification.senderId.contains("_")) {
                notification.senderId.split("_").firstOrNull() ?: notification.senderId
            } else {
                notification.senderId
            }
            
            Log.i(TAG, "[推送下载] 解析senderId: 原始=${notification.senderId}, 纯hash=$pureHashId")
            
            val recipientId = findRecipientIdByHash(pureHashId)
            if (recipientId == null) {
                // 增强诊断：列出所有通道的peerHashedId
                val channelTable = SignalDatabase.transportChannels
                val allChannels = channelTable.getAllChannels()
                Log.e(TAG, "[推送下载失败] 未找到匹配的recipientId: pureHashId=$pureHashId")
                Log.e(TAG, "[诊断] 数据库中的通道总数: ${allChannels.size}")
                
                allChannels.forEachIndexed { index, channelData ->
                    if (channelData.metadata is org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata) {
                        val cosMetadata = channelData.metadata as org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                        Log.e(TAG, "[诊断] 通道[$index]: " +
                            "recipientId=${channelData.recipientId}, " +
                            "peerHashedId=${cosMetadata.peerHashedId}, " +
                            "peerBucketName=${cosMetadata.peerBucketName}, " +
                            "providerType=${cosMetadata.providerType}, " +
                            "peerReceivePath=${cosMetadata.peerReceivePath}")
                    } else {
                        Log.e(TAG, "[诊断] 通道[$index]: " +
                            "recipientId=${channelData.recipientId}, " +
                            "metadata类型=${channelData.metadata?.javaClass?.name ?: "null"}")
                    }
                }
                
                return NotificationDownloadResult.failure(
                    "未找到匹配的recipientId",
                    System.currentTimeMillis() - startTime
                )
            }
            
            Log.i(TAG, "[推送下载] 成功找到recipientId: pureHashId=$pureHashId -> recipientId=$recipientId")
            
            val channel = channelManager.getActiveChannels(recipientId).firstOrNull()
            if (channel == null || channel.metadata == null) {
                Log.e(TAG, "[推送下载失败] 未找到活跃通道: recipientId=$recipientId")
                Log.e(TAG, "[诊断] 尝试查询该recipientId的所有通道状态")
                val allChannelsForRecipient = channelManager.getActiveChannels(recipientId)
                Log.e(TAG, "[诊断] recipientId=$recipientId 的活跃通道数: ${allChannelsForRecipient.size}")
                return NotificationDownloadResult.failure(
                    "未找到活跃通道",
                    System.currentTimeMillis() - startTime
                )
            }
            
            val metadata = channel.metadata!!
            Log.d(TAG, "[推送下载] 找到通道: channelId=${channel.channelId}, providerType=${metadata.providerType}")
            
            // 3. 获取Provider
            val provider = transportManager.getProvider(metadata.providerType)
            if (provider == null) {
                Log.e(TAG, "[推送下载失败] Provider不可用: ${metadata.providerType}")
                return NotificationDownloadResult.failure(
                    "Provider不可用",
                    System.currentTimeMillis() - startTime
                )
            }
            
            Log.d(TAG, "[推送下载] Provider准备就绪: ${metadata.providerType}")
            
            // 4. 提取文件名并检查是否已处理（重复检查）
            val fileName = fileKey.substringAfterLast("/")
            val pollingState = pollingStateTable.getPollingState(recipientId, metadata.providerType)
            val processedFiles = pollingState?.processedFiles ?: emptySet()
            
            if (processedFiles.contains(fileName)) {
                Log.d(TAG, "文件已处理，跳过: $fileName")
                return NotificationDownloadResult.success(0, 0, System.currentTimeMillis() - startTime)
            }
            
            // 时间测试点：T5 - 完成准备，即将下载密文
            val msgId = try {
                val fileNameWithoutExt = fileName.substringBeforeLast(".")
                val parts = fileNameWithoutExt.split("_")
                if (parts.isNotEmpty() && parts[0].toLongOrNull() != null) {
                    parts[0]
                } else {
                    notification.timestamp.toString()
                }
            } catch (e: Exception) {
                notification.timestamp.toString()
            }
            Log.d(TAG, "[TapTimeTest] T5_LIST_END | msgId=$msgId | timestamp=${System.currentTimeMillis()}")
            
            // 5. 直接下载该文件
            val fileInfo = FileInfo(
                name = fileName,
                path = fileKey,
                size = 0,
                lastModified = notification.timestamp
            )
            
            val fileResult = downloadAndProcessFile(provider, metadata, recipientId, fileInfo)
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // 6. 处理下载结果
            when (fileResult.status) {
                FileProcessStatus.SUCCESS -> {
                    // 下载成功，更新数据库
                    pollingStateTable.recordSuccessfulPoll(
                        recipientId,
                        metadata.providerType,
                        setOf(fileName),
                        1
                    )
                    clearFileProcessingFailure(fileName, recipientId)
                    
                    // Phase 2: 检查并下载附件
                    val attachmentResult = downloadAttachmentsIfNeeded(
                        provider, metadata, recipientId, fileKey, notification
                    )
                    
                    Log.i(TAG, "直接下载成功: $fileName, 附件=${attachmentResult.attachmentsProcessed}")
                    
                    NotificationDownloadResult.success(
                        1,
                        1 + attachmentResult.attachmentsProcessed,
                        responseTime
                    )
                }
                
                FileProcessStatus.FAILED_SKIP -> {
                    // 标记为已处理，避免重复
                    pollingStateTable.recordSuccessfulPoll(
                        recipientId,
                        metadata.providerType,
                        setOf(fileName),
                        0
                    )
                    NotificationDownloadResult.success(0, 1, responseTime)
                }
                
                else -> {
                    recordFileProcessingFailure(fileName, recipientId, fileResult.error)
                    NotificationDownloadResult.failure(fileResult.error, responseTime)
                }
            }
            
        } catch (e: TimeoutCancellationException) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.w(TAG, "直接下载超时")
            NotificationDownloadResult.failure("TIMEOUT", responseTime)
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "直接下载过程中发生错误", e)
            NotificationDownloadResult.failure(e.message ?: "UNKNOWN_ERROR", responseTime)
        }
    }
    
    /**
     * Phase 2: 检查并下载附件
     * 从message文件路径推导attachment路径
     */
    private suspend fun downloadAttachmentsIfNeeded(
        provider: TransportProvider,
        metadata: TransportMetadata,
        recipientId: String,
        messageKey: String,
        notification: NotificationMessage
    ): AttachmentDownloadResult {
        return try {
            // 从message key推导attachments目录
            // 例如: v2-channels/{channelId}/outbox/messages/xxx.dat
            //   -> v2-channels/{channelId}/outbox/attachments/
            val attachmentsPath = messageKey
                .replace("/messages/", "/attachments/")
                .substringBeforeLast("/") + "/"
            
            Log.d(TAG, "检查附件目录: $attachmentsPath")
            
            // 列举attachments目录
            val listResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                errorHandler.executeWithRetry({
                    provider.listFiles(attachmentsPath, metadata)
                }, ErrorContext(
                    providerType = metadata.providerType,
                    operationType = "listFiles",
                    targetId = recipientId,
                    channelId = "${metadata.providerType}:${recipientId}",
                    metadata = mapOf("pollingPath" to attachmentsPath)
                ))
            }
            
            if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                Log.d(TAG, "没有附件文件")
                return AttachmentDownloadResult(0)
            }
            
            val allAttachments = listResult.files
            
            // 过滤出与当前消息相关的附件
            // 附件命名规则: {timestamp}_{messageId}_{index}.bin
            val messageFileName = messageKey.substringAfterLast("/").substringBeforeLast(".")
            val relatedAttachments = allAttachments.filter { attachment ->
                attachment.name.startsWith(messageFileName)
            }
            
            if (relatedAttachments.isEmpty()) {
                Log.d(TAG, "没有相关附件")
                return AttachmentDownloadResult(0)
            }
            
            Log.i(TAG, "找到${relatedAttachments.size}个附件，开始下载")
            
            // 下载所有相关附件
            var successCount = 0
            for (attachment in relatedAttachments) {
                val attachmentResult = downloadAndProcessFile(provider, metadata, recipientId, attachment)
                if (attachmentResult.status == FileProcessStatus.SUCCESS) {
                    successCount++
                    // 更新数据库
                    pollingStateTable.recordSuccessfulPoll(
                        recipientId,
                        metadata.providerType,
                        setOf(attachment.name),
                        0
                    )
                }
            }
            
            Log.i(TAG, "附件下载完成: 成功=$successCount, 总数=${relatedAttachments.size}")
            AttachmentDownloadResult(successCount)
            
        } catch (e: Exception) {
            Log.e(TAG, "下载附件失败", e)
            AttachmentDownloadResult(0)
        }
    }
    
    /**
     * 执行推送触发的下载（旧方案，保留用于兼容）
     * 
     * @param senderId 发送者ID (ACI hash)
     * @return 下载执行结果
     */
    suspend fun executeNotificationDownload(senderId: String): NotificationDownloadResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.i(TAG, "开始执行推送触发下载: senderId=$senderId")
            
            // 1. 查找该senderId的活跃通道
            val channel = findActiveChannel(senderId)
            if (channel == null) {
                Log.w(TAG, "未找到活跃通道: senderId=$senderId")
                return NotificationDownloadResult.failure(
                    "未找到活跃通道",
                    System.currentTimeMillis() - startTime
                )
            }
            
            val metadata = channel.metadata
            if (metadata == null) {
                Log.w(TAG, "通道metadata为空: senderId=$senderId")
                return NotificationDownloadResult.failure(
                    "通道metadata为空",
                    System.currentTimeMillis() - startTime
                )
            }
            
            // 2. 获取Provider
            val provider = transportManager.getProvider(metadata.providerType)
            if (provider == null) {
                Log.e(TAG, "Provider不可用: ${metadata.providerType}")
                return NotificationDownloadResult.failure(
                    "Provider不可用",
                    System.currentTimeMillis() - startTime
                )
            }
            
            // 3. 获取轮询状态（用于过滤已处理文件）
            val pollingState = pollingStateTable.getPollingState(
                channel.recipientId,
                metadata.providerType
            )
            
            // 4. 执行完整的下载流程 (list -> filter -> download)
            val downloadResult = performCompleteDownload(
                provider,
                metadata,
                channel.recipientId,
                pollingState
            )
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // 5. 更新轮询状态
            if (downloadResult.isSuccess && downloadResult.messagesProcessed > 0) {
                updatePollingState(channel.recipientId, metadata.providerType, downloadResult)
            }
            
            NotificationDownloadResult(
                isSuccess = downloadResult.isSuccess,
                messagesProcessed = downloadResult.messagesProcessed,
                filesProcessed = downloadResult.filesProcessed.size,
                responseTime = responseTime,
                error = downloadResult.error
            )
            
        } catch (e: TimeoutCancellationException) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.w(TAG, "下载超时: senderId=$senderId")
            NotificationDownloadResult.failure("TIMEOUT", responseTime)
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "下载过程中发生错误: senderId=$senderId", e)
            NotificationDownloadResult.failure(e.message ?: "UNKNOWN_ERROR", responseTime)
        }
    }
    
    /**
     * 执行完整的下载流程
     * 参考FilePollingExecutor的performFileBasedPolling实现
     */
    private suspend fun performCompleteDownload(
        provider: TransportProvider,
        metadata: TransportMetadata,
        recipientId: String,
        pollingState: org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState?
    ): DownloadResult {
        return try {
            // 1. List: 获取文件列表
            val basePath = metadata.getReceiveMetadata().path
            val pollingPaths = listOf("${basePath}messages/", "${basePath}attachments/")
            
            Log.d(TAG, "列举文件: paths=$pollingPaths, recipientId=$recipientId")
            
            val allFiles = mutableListOf<FileInfo>()
            for (path in pollingPaths) {
                val listResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                    errorHandler.executeWithRetry({
                        provider.listFiles(path, metadata)
                    }, ErrorContext(
                        providerType = metadata.providerType,
                        operationType = "listFiles",
                        targetId = recipientId,
                        channelId = "${metadata.providerType}:${recipientId}",
                        metadata = mapOf("pollingPath" to path)
                    ))
                }
                
                if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                    allFiles.addAll(listResult.files)
                }
            }
            
            if (allFiles.isEmpty()) {
                Log.d(TAG, "未找到文件: recipientId=$recipientId")
                return DownloadResult.success(emptySet(), 0)
            }
            
            Log.d(TAG, "找到文件数量: ${allFiles.size}, recipientId=$recipientId")
            
            // 2. Filter: 过滤出新文件
            val sortedFiles = FileInfo.sortByTime(allFiles, ascending = true)
            val processedFiles = pollingState?.processedFiles ?: emptySet()
            
            val newFiles = sortedFiles.filter { file ->
                !processedFiles.contains(file.name) && 
                file.lastModified > (pollingState?.lastProcessedTime ?: 0) &&
                shouldRetryFileProcessing(file.name, recipientId)
            }
            
            if (newFiles.isEmpty()) {
                Log.d(TAG, "没有新文件: recipientId=$recipientId")
                return DownloadResult.success(emptySet(), 0)
            }
            
            Log.i(TAG, "找到新文件数量: ${newFiles.size}, recipientId=$recipientId")
            
            // 时间测试点：T5 - 完成列举消息（下载密文之前）
            // 从第一个新文件名中提取timestamp作为msgId (格式: timestamp_messageId.dat)
            val firstFile = newFiles.firstOrNull()
            if (firstFile != null) {
                val msgId = try {
                    val fileName = firstFile.name.substringBeforeLast(".")
                    val parts = fileName.split("_")
                    if (parts.isNotEmpty() && parts[0].toLongOrNull() != null) {
                        parts[0] // timestamp在前
                    } else {
                        firstFile.lastModified.toString() // fallback
                    }
                } catch (e: Exception) {
                    firstFile.lastModified.toString()
                }
                Log.d(TAG, "[TapTimeTest] T5_LIST_END | msgId=$msgId | timestamp=${System.currentTimeMillis()}")
            }
            
            // 3. Download: 下载并处理文件
            var messagesProcessed = 0
            val newProcessedFiles = mutableSetOf<String>()
            
            for (file in newFiles) {
                val fileResult = downloadAndProcessFile(provider, metadata, recipientId, file)
                
                when (fileResult.status) {
                    FileProcessStatus.SUCCESS -> {
                        messagesProcessed++
                        newProcessedFiles.add(file.name)
                        clearFileProcessingFailure(file.name, recipientId)
                        Log.d(TAG, "文件处理成功: ${file.name}")
                    }
                    
                    FileProcessStatus.FAILED_RETRY -> {
                        val shouldRetry = recordFileProcessingFailure(
                            file.name,
                            recipientId,
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
                        Log.w(TAG, "文件下载失败: ${file.name}")
                    }
                }
            }
            
            DownloadResult.success(newProcessedFiles, messagesProcessed)
            
        } catch (e: Exception) {
            Log.e(TAG, "下载流程异常: recipientId=$recipientId", e)
            DownloadResult.failure(e.message ?: "UNKNOWN_ERROR")
        }
    }
    
    /**
     * 下载并处理单个文件
     * 参考FilePollingExecutor的processIndividualFile实现
     */
    private suspend fun downloadAndProcessFile(
        provider: TransportProvider,
        metadata: TransportMetadata,
        recipientId: String,
        file: FileInfo
    ): FileProcessResult {
        return try {
            // 下载文件
            val downloadResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                errorHandler.executeWithRetry({
                    provider.downloadFile(file, metadata)
                }, ErrorContext(
                    providerType = metadata.providerType,
                    operationType = "downloadFile",
                    targetId = recipientId,
                    channelId = "${metadata.providerType}:${recipientId}",
                    metadata = mapOf("fileName" to file.name)
                ))
            }
            
            if (downloadResult !is TransportResult.Success || downloadResult.data == null) {
                return FileProcessResult(FileProcessStatus.DOWNLOAD_FAILED, "下载失败")
            }
            
            // 解析消息
            val message = provider.parseTransportMessage(downloadResult.data, file, metadata)
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
     * 查找活跃通道
     */
    private fun findActiveChannel(senderId: String): TransportChannel? {
        // Step 1: 解析senderId，提取纯hash部分
        // Lambda发送的格式可能是: {hash}_{timestamp}
        val pureHashId = if (senderId.contains("_")) {
            senderId.split("_").firstOrNull() ?: senderId
        } else {
            senderId
        }
        
        if (pureHashId != senderId) {
            Log.d(TAG, "解析senderId: 原始=$senderId, 纯hash=$pureHashId")
        }
        
        // Step 2: 通过hash查找recipientId (ACI)
        val recipientId = findRecipientIdByHash(pureHashId)
        if (recipientId == null) {
            Log.w(TAG, "未找到匹配的recipientId: pureHashId=$pureHashId")
            return null
        }
        
        Log.d(TAG, "找到recipientId: pureHashId=$pureHashId -> recipientId=$recipientId")
        
        // Step 3: 通过recipientId查找通道
        val channels = channelManager.getActiveChannels(recipientId)
        if (channels.isNotEmpty()) {
            Log.d(TAG, "找到活跃通道: recipientId=$recipientId")
            return channels.firstOrNull()
        }
        
        Log.w(TAG, "未找到活跃通道: recipientId=$recipientId")
        return null
    }
    
    /**
     * 通过peerHashedId查找recipientId
     * 遍历所有通道，匹配metadata中的peerHashedId
     */
    private fun findRecipientIdByHash(hashId: String): String? {
        try {
            Log.d(TAG, "[查找通道] 开始通过peerHashedId查找: hashId=$hashId")
            
            val channelTable = SignalDatabase.transportChannels
            val allChannels = channelTable.getAllChannels()
            
            Log.d(TAG, "[查找通道] 数据库中总通道数: ${allChannels.size}")
            
            var cosChannelCount = 0
            for (channelData in allChannels) {
                if (channelData.metadata is org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata) {
                    cosChannelCount++
                    val cosMetadata = channelData.metadata as org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                    
                    Log.v(TAG, "[查找通道] 检查通道: recipientId=${channelData.recipientId}, " +
                        "peerHashedId=${cosMetadata.peerHashedId}, 匹配=${cosMetadata.peerHashedId == hashId}")
                    
                    if (cosMetadata.peerHashedId == hashId) {
                        Log.i(TAG, "[查找通道] 找到匹配: hashId=$hashId -> recipientId=${channelData.recipientId}")
                        return channelData.recipientId
                    }
                }
            }
            
            Log.w(TAG, "[查找通道] 未找到匹配: hashId=$hashId, 共检查了${cosChannelCount}个COS通道")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "[查找通道] 异常: hashId=$hashId", e)
            return null
        }
    }
    
    /**
     * 更新轮询状态
     */
    private fun updatePollingState(
        recipientId: String,
        providerType: String,
        result: DownloadResult
    ) {
        try {
            if (result.isSuccess && result.messagesProcessed > 0) {
                pollingStateTable.recordSuccessfulPoll(
                    recipientId,
                    providerType,
                    result.filesProcessed,
                    result.messagesProcessed
                )
                Log.d(TAG, "发现${result.messagesProcessed}条新消息，已更新数据库: recipientId=$recipientId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新轮询状态失败: recipientId=$recipientId", e)
        }
    }
    
    /**
     * 记录文件处理失败
     */
    private fun recordFileProcessingFailure(
        fileName: String,
        recipientId: String,
        error: String
    ): Boolean {
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
}

/**
 * 推送下载结果
 */
data class NotificationDownloadResult(
    val isSuccess: Boolean,
    val messagesProcessed: Int,
    val filesProcessed: Int,
    val responseTime: Long,
    val error: String? = null
) {
    companion object {
        fun success(messagesProcessed: Int, filesProcessed: Int, responseTime: Long) = 
            NotificationDownloadResult(
                isSuccess = true,
                messagesProcessed = messagesProcessed,
                filesProcessed = filesProcessed,
                responseTime = responseTime
            )
        
        fun failure(error: String, responseTime: Long) = 
            NotificationDownloadResult(
                isSuccess = false,
                messagesProcessed = 0,
                filesProcessed = 0,
                responseTime = responseTime,
                error = error
            )
    }
}

/**
 * 下载结果（内部使用）
 */
private data class DownloadResult(
    val isSuccess: Boolean,
    val filesProcessed: Set<String>,
    val messagesProcessed: Int,
    val error: String? = null
) {
    companion object {
        fun success(filesProcessed: Set<String>, messagesProcessed: Int) =
            DownloadResult(
                isSuccess = true,
                filesProcessed = filesProcessed,
                messagesProcessed = messagesProcessed
            )
        
        fun failure(error: String) =
            DownloadResult(
                isSuccess = false,
                filesProcessed = emptySet(),
                messagesProcessed = 0,
                error = error
            )
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
    SUCCESS,
    FAILED_RETRY,
    FAILED_SKIP,
    DOWNLOAD_FAILED
}

/**
 * 文件处理失败信息
 */
data class FileProcessingFailure(
    val fileName: String,
    val recipientId: String,
    val failureCount: Int,
    val lastFailureTime: Long,
    val lastError: String
)

/**
 * 附件下载结果
 */
private data class AttachmentDownloadResult(
    val attachmentsProcessed: Int
)

