package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportConfig
import kotlinx.coroutines.runBlocking
import org.whispersystems.signalservice.api.push.ServiceId

/**
 * Tap消息处理器
 * 负责处理通过Tap传输层接收的控制消息和普通消息
 * 替代原来的CosSignalMessageProcessor
 */
class TapMessageProcessor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageProcessor::class.java)
        
        @Volatile
        private var INSTANCE: TapMessageProcessor? = null
        
        fun getInstance(context: Context): TapMessageProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val channelManager = TransportChannelManager.getInstance(context)
    private val tokenPool = TransportTokenPool.getInstance(context)
    
    /**
     * 检查是否为Tap传输层控制消息
     * 
     * @param messageBody 消息体
     * @return 是否为Tap控制消息
     */
    fun isTapMessage(messageBody: String): Boolean {
        return messageBody.startsWith("TAP_MSG:") || 
               messageBody.startsWith("TAP_REQ:") || 
               messageBody.startsWith("TAP_RESP:") ||
               messageBody.startsWith("TAP_REVOKE:")
    }
    
    /**
     * 处理Tap传输层控制消息
     * 
     * @param senderId 发送者ID
     * @param messageBody 消息体
     * @return 处理结果
     */
    fun processTapMessage(senderId: String, messageBody: String): TapProcessResult {
        Log.i(TAG, "处理Tap传输层控制消息: senderId=$senderId, bodyLength=${messageBody.length}")
        
        return try {
            when {
                messageBody.startsWith("TAP_REQ:") -> {
                    runBlocking { processChannelRequest(senderId, messageBody.substring(8)) }
                }
                messageBody.startsWith("TAP_RESP:") -> {
                    runBlocking { processChannelResponse(senderId, messageBody.substring(9)) }
                }
                messageBody.startsWith("TAP_REVOKE:") -> {
                    runBlocking { processChannelRevoke(senderId, messageBody.substring(11)) }
                }
                messageBody.startsWith("TAP_MSG:") -> {
                    runBlocking { processControlMessage(senderId, messageBody.substring(8)) }
                }
                else -> {
                    Log.w(TAG, "未知的Tap控制消息类型: senderId=$senderId")
                    TapProcessResult.Failed("未知的Tap控制消息类型")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Tap控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道请求
     */
    private suspend fun processChannelRequest(senderId: String, requestData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道请求: senderId=$senderId")
        
        return try {
            // 解析请求数据
            val requestInfo = parseChannelRequest(requestData)
            if (requestInfo == null) {
                Log.w(TAG, "无法解析通道请求数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析请求数据")
            }
            
            // 检查是否已有活跃通道
            if (channelManager.hasActiveChannel(senderId)) {
                Log.i(TAG, "已存在活跃通道，更新配置: senderId=$senderId")
                val updated = channelManager.updateChannelConfig(senderId, requestInfo.config)
                if (updated) {
                    TapProcessResult.Success("通道配置已更新")
                } else {
                    TapProcessResult.Failed("通道配置更新失败")
                }
            } else {
                // 创建新的传输通道
                Log.i(TAG, "创建新的传输通道: senderId=$senderId")
                val channelResult = channelManager.createChannel(
                    recipientId = senderId,
                    config = requestInfo.config,
                    token = requestInfo.token
                )
                
                if (channelResult != null) {
                    TapProcessResult.Success("传输通道创建成功")
                } else {
                    TapProcessResult.Failed("传输通道创建失败")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通道请求异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道响应
     */
    private suspend fun processChannelResponse(senderId: String, responseData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道响应: senderId=$senderId")
        
        return try {
            // 解析响应数据
            val responseInfo = parseChannelResponse(responseData)
            if (responseInfo == null) {
                Log.w(TAG, "无法解析通道响应数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析响应数据")
            }
            
            // 激活传输通道
            val activated = channelManager.activateChannelPublic(senderId, responseInfo.token)
            if (activated) {
                Log.i(TAG, "传输通道激活成功: senderId=$senderId")
                TapProcessResult.Success("传输通道激活成功")
            } else {
                Log.w(TAG, "传输通道激活失败: senderId=$senderId")
                TapProcessResult.Failed("传输通道激活失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通道响应异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道撤销
     */
    private suspend fun processChannelRevoke(senderId: String, revokeData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道撤销: senderId=$senderId")
        
        return try {
            // 撤销传输通道
            val revoked = channelManager.revokeChannel(senderId)
            if (revoked) {
                Log.i(TAG, "传输通道撤销成功: senderId=$senderId")
                TapProcessResult.Success("传输通道撤销成功")
            } else {
                Log.w(TAG, "传输通道撤销失败: senderId=$senderId")
                TapProcessResult.Failed("传输通道撤销失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通道撤销异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理其他控制消息
     */
    private suspend fun processControlMessage(senderId: String, controlData: String): TapProcessResult {
        Log.i(TAG, "处理Tap控制消息: senderId=$senderId")
        
        return try {
            // 这里可以处理其他类型的控制消息
            // 例如：心跳、状态同步等
            Log.d(TAG, "收到Tap控制消息: senderId=$senderId, data=$controlData")
            TapProcessResult.Success("控制消息处理完成")
        } catch (e: Exception) {
            Log.e(TAG, "处理控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 解析通道请求数据
     */
    private fun parseChannelRequest(requestData: String): ChannelRequestInfo? {
        return try {
            // 简单的解析实现（实际应该使用JSON或其他结构化格式）
            val parts = requestData.split("|")
            if (parts.size >= 2) {
                val configData = parts[0]
                val tokenData = parts[1]
                
                // 构建默认配置对象（实际应该根据configData解析）
                val config = org.thoughtcrime.securesms.tap.TransportChannelConfig(
                    maxChannels = 10, // 默认值
                    channelTimeoutMs = 30000L, // 默认值
                    heartbeatIntervalMs = 60000L, // 默认值
                    cleanupIntervalMs = 3600000L, // 默认值
                    maxFailureCount = 5, // 默认值
                    priorityRange = 1..10 // 默认值
                )
                
                ChannelRequestInfo(config, tokenData)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析通道请求数据失败", e)
            null
        }
    }
    
    /**
     * 解析通道响应数据
     */
    private fun parseChannelResponse(responseData: String): ChannelResponseInfo? {
        return try {
            // 简单的解析实现
            ChannelResponseInfo(responseData)
        } catch (e: Exception) {
            Log.e(TAG, "解析通道响应数据失败", e)
            null
        }
    }
    
    /**
     * 检查消息是否重复
     */
    fun isDuplicateMessage(messageId: String, recipientId: String): Boolean {
        return try {
            val duplicationKey = "${messageId}:${recipientId}"
            
            // 查询去重表
            val database = org.thoughtcrime.securesms.database.SignalDatabase.rawDatabase
            database.rawQuery(
                "SELECT COUNT(*) FROM transport_processed_messages WHERE duplication_key LIKE ?",
                arrayOf("$duplicationKey:%")
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(0)
                    val isDuplicate = count > 0
                    
                    if (isDuplicate) {
                        Log.d(TAG, "发现重复消息: messageId=$messageId, recipientId=$recipientId")
                    }
                    
                    return isDuplicate
                } else {
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查消息重复失败: messageId=$messageId, recipientId=$recipientId", e)
            // 出错时保守处理，假设不重复以避免丢失消息
            false
        }
    }
    
    /**
     * 处理接收到的传输消息
     */
    suspend fun processIncomingMessage(message: org.thoughtcrime.securesms.tap.TransportMessage, recipientId: String): Boolean {
        return try {
            Log.i(TAG, "处理接收消息: messageId=${message.messageId}, recipientId=$recipientId")
            
            // 检查消息重复性
            if (isDuplicateMessage(message.messageId, recipientId)) {
                Log.d(TAG, "跳过重复消息: ${message.messageId}")
                return true
            }
            
            // 获取发送者Recipient
            val senderRecipient = getSenderRecipient(message.senderId)
            if (senderRecipient == null) {
                Log.w(TAG, "无法获取发送者信息: ${message.senderId}")
                return false
            }
            
            // 解码Signal密文
            val signalCiphertext = try {
                android.util.Base64.decode(message.signalCiphertext, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "解码Signal密文失败: messageId=${message.messageId}", e)
                return false
            }
            
            // 创建IncomingMessage对象
            val incomingMessage = createIncomingMessage(message, senderRecipient, message.timestamp, message.timestamp)
            
            // 获取线程ID
            val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(senderRecipient)
            
            // 插入到Signal数据库
            val insertResult = org.thoughtcrime.securesms.database.SignalDatabase.messages.insertMessageInbox(
                retrieved = incomingMessage,
                candidateThreadId = threadId
            )
            
            if (insertResult.isPresent) {
                val result = insertResult.get()
                Log.i(TAG, "消息成功插入数据库: messageId=${message.messageId}, dbId=${result.messageId}")
                
                // 标记消息为已处理（去重）
                markMessageAsProcessed(message.messageId, recipientId, message.timestamp)
                
                // 更新线程
                org.thoughtcrime.securesms.database.SignalDatabase.threads.update(threadId, true)
                
                // 通知UI更新
                org.thoughtcrime.securesms.dependencies.AppDependencies.messageNotifier.updateNotification(
                    context, 
                    org.thoughtcrime.securesms.notifications.v2.ConversationId.forConversation(threadId)
                )
                
                // 触发相关后台任务
                schedulePostProcessingJobs(result, senderRecipient)
                
                return true
            } else {
                Log.w(TAG, "消息插入数据库失败: messageId=${message.messageId}")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理接收消息失败: messageId=${message.messageId}", e)
            false
        }
    }
    
    /**
     * 创建Signal的IncomingMessage对象
     */
    private fun createIncomingMessage(
        message: org.thoughtcrime.securesms.tap.TransportMessage,
        senderRecipient: org.thoughtcrime.securesms.recipients.Recipient,
        timestamp: Long,
        serverTimestamp: Long
    ): org.thoughtcrime.securesms.mms.IncomingMessage {
        
        // 根据消息类型创建相应的附件
        val attachments = mutableListOf<org.thoughtcrime.securesms.attachments.Attachment>()
        
        // 处理附件 - 注意TransportAttachment没有data、type、width、height属性
        message.attachments.forEach { attachment ->
            try {
                // 创建临时文件 - 由于没有直接的二进制数据，我们需要从传输路径获取
                val tempFile = java.io.File.createTempFile("tap_attachment_", ".tmp", context.cacheDir)
                
                // 创建UriAttachment - 使用正确的构造函数参数顺序
                val uriAttachment = org.thoughtcrime.securesms.attachments.UriAttachment(
                    android.net.Uri.fromFile(tempFile),
                    attachment.mimeType, // 使用mimeType替代type
                    org.thoughtcrime.securesms.database.AttachmentTable.TRANSFER_PROGRESS_DONE,
                    attachment.size,
                    0, // width - TransportAttachment没有此属性，使用0
                    0, // height - TransportAttachment没有此属性，使用0  
                    attachment.fileName, // 使用fileName替代null
                    null, // fastPreflightId
                    false, // voiceNote
                    false, // borderless
                    false, // videoGif
                    false, // quote
                    null, // caption
                    null, // stickerLocator
                    null, // blurHash
                    null, // audioHash
                    null  // transformProperties
                )
                
                attachments.add(uriAttachment)
            } catch (e: Exception) {
                Log.w(TAG, "处理附件失败: ${attachment.attachmentId}", e) // 使用attachmentId替代id
            }
        }
        
        // 确定消息体 - TransportMessage使用signalCiphertext，需要解密才能获得真实消息内容
        // 这里我们只能根据消息类型提供占位符文本，真实内容需要通过Signal的解密流程获得
        val messageBody = when (message.messageType) {
            org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE -> "文本消息"
            org.thoughtcrime.securesms.tap.TransportMessageType.MEDIA_MESSAGE -> "媒体消息"
            org.thoughtcrime.securesms.tap.TransportMessageType.CONTROL_MESSAGE -> "控制消息"
            org.thoughtcrime.securesms.tap.TransportMessageType.RATCHET_UPDATE -> "密钥更新消息"
            org.thoughtcrime.securesms.tap.TransportMessageType.CALL_MESSAGE -> "通话消息"
        }
        
        // 创建IncomingMessage - 使用正确的参数顺序
        return org.thoughtcrime.securesms.mms.IncomingMessage(
            type = org.thoughtcrime.securesms.database.MessageType.NORMAL,
            from = senderRecipient.id,
            sentTimeMillis = timestamp,
            serverTimeMillis = serverTimestamp,
            receivedTimeMillis = System.currentTimeMillis(),
            body = messageBody,
            attachments = attachments,
            isUnidentified = false,
            serverGuid = null
        )
    }
    
    /**
     * 获取发送者Recipient对象
     */
    private fun getSenderRecipient(senderId: String): org.thoughtcrime.securesms.recipients.Recipient? {
        return try {
            // 尝试解析为E164号码
            if (senderId.startsWith("+")) {
                val externalRecipient = org.thoughtcrime.securesms.recipients.Recipient.external(senderId)
                if (externalRecipient != null) {
                    // externalRecipient.id 已经是 RecipientId 类型，无需再次包装
                    org.thoughtcrime.securesms.recipients.Recipient.resolved(externalRecipient.id)
                } else {
                    Log.w(TAG, "无法创建外部Recipient: $senderId")
                    null
                }
            } else {
                // 尝试解析为UUID（ACI）- 使用正确的ServiceId导入
                val serviceId = ServiceId.parseOrThrow(senderId)
                val recipientId = org.thoughtcrime.securesms.recipients.RecipientId.from(serviceId)
                org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析发送者ID失败: $senderId", e)
            null
        }
    }
    
    /**
     * 标记消息为已处理（用于去重）
     */
    private fun markMessageAsProcessed(messageId: String, recipientId: String, timestamp: Long) {
        try {
            val duplicationKey = "${messageId}:${recipientId}:${timestamp}"
            
            // 插入到去重表
            val database = org.thoughtcrime.securesms.database.SignalDatabase.rawDatabase
            database.execSQL(
                "INSERT OR IGNORE INTO transport_processed_messages (duplication_key, processed_timestamp, created_at) VALUES (?, ?, ?)",
                arrayOf(duplicationKey, timestamp, System.currentTimeMillis())
            )
            
            Log.d(TAG, "标记消息已处理: $duplicationKey")
        } catch (e: Exception) {
            Log.e(TAG, "标记消息已处理失败: messageId=$messageId", e)
        }
    }
    
    /**
     * 调度后处理任务
     */
    private fun schedulePostProcessingJobs(
        insertResult: org.thoughtcrime.securesms.database.MessageTable.InsertResult,
        senderRecipient: org.thoughtcrime.securesms.recipients.Recipient
    ) {
        try {
            // 触发附件下载任务（如果有附件）- 需要attachmentId参数
            if (insertResult.messageId > 0) {
                // 获取消息的附件并为每个附件创建下载任务
                val attachments = org.thoughtcrime.securesms.database.SignalDatabase.attachments.getAttachmentsForMessage(insertResult.messageId)
                attachments.forEach { attachment ->
                    org.thoughtcrime.securesms.dependencies.AppDependencies.jobManager.add(
                        org.thoughtcrime.securesms.jobs.AttachmentDownloadJob(insertResult.messageId, attachment.attachmentId, true)
                    )
                }
            }
            
            // 触发profile刷新任务 - 使用公共API
            org.thoughtcrime.securesms.jobs.RetrieveProfileJob.enqueue(senderRecipient.id, false)
            
            Log.d(TAG, "已调度后处理任务: messageId=${insertResult.messageId}")
        } catch (e: Exception) {
            Log.e(TAG, "调度后处理任务失败", e)
        }
    }
}

/**
 * 通道请求信息
 */
private data class ChannelRequestInfo(
    val config: org.thoughtcrime.securesms.tap.TransportChannelConfig,
    val token: String
)

/**
 * 通道响应信息
 */
private data class ChannelResponseInfo(
    val token: String
)

/**
 * Tap处理结果
 */
sealed class TapProcessResult {
    data class Success(val message: String) : TapProcessResult()
    data class Failed(val reason: String) : TapProcessResult()
} 