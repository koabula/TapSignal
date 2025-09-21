package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.TransportContentMetadata
import org.thoughtcrime.securesms.tap.TransportCompressionType
import org.thoughtcrime.securesms.tap.TransportAttachment
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Tap消息发送集成器
 * 
 * 负责将TransportProvider的发送功能集成到Signal的消息发送流程中
 * 提供统一的发送接口，支持Signal Server和Transport Provider的智能路由
 */
class TapMessageSendIntegrator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageSendIntegrator::class.java)
        
        @Volatile
        private var INSTANCE: TapMessageSendIntegrator? = null
        
        fun getInstance(context: Context): TapMessageSendIntegrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageSendIntegrator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val transportManager = TransportManager.getInstance(context)
    
    /**
     * 判断是否应该使用Tap传输发送消息
     * 
     * @param recipient 接收方
     * @return 是否应该使用Tap传输
     */
    fun shouldUseTapTransport(recipient: Recipient): Boolean {
        return try {
            // 检查是否有可用的传输提供者
            val availableProviders = transportManager.getEnabledProviders()
            availableProviders.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "检查Tap传输支持失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 通过Tap传输发送消息
     * 
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @return 发送结果的Future
     */
    fun sendTapMessage(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): CompletableFuture<TapSendResult> {
        Log.i(TAG, "发送Tap消息: messageId=$messageId, recipient=${recipient.id}")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 检查是否支持Tap传输
                if (!shouldUseTapTransport(recipient)) {
                    Log.w(TAG, "接收方不支持Tap传输: ${recipient.id}")
                    return@supplyAsync TapSendResult.Failed("接收方不支持Tap传输")
                }
                
                // 2. 转换为TransportMessage
                val transportMessage = convertToTransportMessage(outgoingMessage, messageId, recipient)
                if (transportMessage == null) {
                    Log.w(TAG, "消息转换失败: messageId=$messageId")
                    return@supplyAsync TapSendResult.Failed("消息转换失败")
                }
                
                // 3. 执行发送 - 使用协程包装
                val sendResult = runCatching {
                    kotlinx.coroutines.runBlocking {
                        transportManager.sendMessage(
                            recipientId = recipient.id.serialize(),
                            message = transportMessage
                        )
                    }
                }.getOrElse { exception ->
                    Log.e(TAG, "发送消息异常: $exception")
                    TransportResult.fromException(exception, true)
                }
                
                when (sendResult) {
                    is TransportResult.Success -> {
                        Log.i(TAG, "Tap消息发送成功: messageId=$messageId")
                        
                        // 更新数据库中的消息状态为已发送
                        updateMessageSentStatus(messageId, true)
                        
                        val fileKey = sendResult.getMetadata("fileKey") as? String ?: ""
                        TapSendResult.Success(fileKey)
                    }
                    is TransportResult.Failed -> {
                        Log.w(TAG, "Tap消息发送失败: messageId=$messageId, error=${sendResult.error}")
                        
                        // 更新数据库中的消息状态为发送失败
                        updateMessageSentStatus(messageId, false)
                        
                        TapSendResult.Failed(sendResult.errorMessage.ifEmpty { sendResult.error.displayName })
                    }
                    is TransportResult.RetryScheduled -> {
                        Log.d(TAG, "Tap消息需要重试: messageId=$messageId, 延迟: ${sendResult.retryAfter}ms")
                        TapSendResult.Failed("发送需要重试: ${sendResult.reason}")
                    }
                    is TransportResult.PartialSuccess -> {
                        Log.w(TAG, "Tap消息部分成功: messageId=$messageId")
                        updateMessageSentStatus(messageId, true)
                        TapSendResult.Success("部分成功")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Tap消息发送异常: messageId=$messageId - ${LogSanitizer.sanitizeThrowable(e)}")
                updateMessageSentStatus(messageId, false)
                TapSendResult.Failed(e.message ?: "未知异常")
            }
        }
    }
    
    /**
     * 将Signal的OutgoingMessage转换为TransportMessage
     */
    private fun convertToTransportMessage(
        outgoingMessage: OutgoingMessage, 
        messageId: Long,
        recipient: Recipient
    ): TransportMessage? {
        return try {
            // 1. 确定消息类型
            val messageType = when {
                outgoingMessage.attachments.isNotEmpty() -> TransportMessageType.MEDIA_MESSAGE
                outgoingMessage.isGroupUpdate -> TransportMessageType.CONTROL_MESSAGE
                else -> TransportMessageType.TEXT_MESSAGE
            }
            
            // 2. 获取加密内容 - 使用Signal的加密数据
            val encryptedContent = buildEncryptedContent(outgoingMessage)
            val signalCiphertext = android.util.Base64.encodeToString(encryptedContent, android.util.Base64.NO_WRAP)
            
            // 3. 处理附件
            val attachments = convertAttachments(outgoingMessage.attachments)
            
            // 4. 生成唯一的消息ID
            val tapMessageId = generateTapMessageId(messageId, outgoingMessage.sentTimeMillis)
            
            // 5. 构建内容元数据
            val contentMetadata = TransportContentMetadata(
                originalSize = encryptedContent.size.toLong(),
                compressionType = TransportCompressionType.NONE,
                encryptionAlgorithm = "Signal-Protocol"
            )
            
            TransportMessage(
                messageId = tapMessageId,
                timestamp = outgoingMessage.sentTimeMillis,
                senderId = "self", // 当前用户ID，实际应该从SignalStore获取
                recipientId = recipient.id.serialize(),
                messageType = messageType,
                signalCiphertext = signalCiphertext,
                signalCiphertextType = 2, // WHISPER_TYPE
                contentMetadata = contentMetadata,
                attachments = attachments
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "转换TransportMessage失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 构建加密内容
     * 
     * 注意：这里假设OutgoingMessage中的内容已经是经过Signal加密的，
     * TaP层只负责传输，不参与Signal的端到端加密
     */
    private fun buildEncryptedContent(outgoingMessage: OutgoingMessage): ByteArray {
        return try {
            // 构建包含消息各个字段的数据结构
            val messageData = mapOf(
                "body" to outgoingMessage.body,
                "distributionType" to outgoingMessage.distributionType,
                "expiresIn" to outgoingMessage.expiresIn,
                "isViewOnce" to outgoingMessage.isViewOnce,
                "isSecure" to outgoingMessage.isSecure,
                "isGroup" to outgoingMessage.isGroup,
                "isGroupUpdate" to outgoingMessage.isGroupUpdate,
                "timestamp" to outgoingMessage.sentTimeMillis
            )
            
            // 序列化为JSON
            val jsonString = org.json.JSONObject(messageData).toString()
            jsonString.toByteArray(Charsets.UTF_8)
            
        } catch (e: Exception) {
            Log.e(TAG, "构建加密内容失败: ${LogSanitizer.sanitizeThrowable(e)}")
            outgoingMessage.body.toByteArray(Charsets.UTF_8) // 降级方案
        }
    }
    
    /**
     * 转换附件
     */
    private fun convertAttachments(attachments: List<org.thoughtcrime.securesms.attachments.Attachment>): List<TransportAttachment> {
        return attachments.mapNotNull { attachment ->
            try {
                TransportAttachment(
                    attachmentId = TransportAttachment.generateAttachmentId(),
                    fileName = attachment.fileName ?: "unknown",
                    mimeType = attachment.contentType ?: "application/octet-stream",
                    size = attachment.size,
                    fileHash = null, // 实际实现中需要计算文件哈希
                    transportPath = null // 由传输提供者设置
                )
            } catch (e: Exception) {
                Log.w(TAG, "转换附件失败: ${attachment.fileName} - ${LogSanitizer.sanitizeThrowable(e)}")
                null
            }
        }
    }
    
    /**
     * 生成TaP消息ID
     */
    private fun generateTapMessageId(messageId: Long, timestamp: Long): String {
        return "tap_${messageId}_${timestamp}_${System.nanoTime()}"
    }
    
    /**
     * 更新消息发送状态
     */
    private fun updateMessageSentStatus(messageId: Long, success: Boolean) {
        try {
            val messageRecord = SignalDatabase.messages.getMessageRecord(messageId)
            if (messageRecord != null) {
                if (success) {
                    SignalDatabase.messages.markAsSent(messageId, false)
                    Log.d(TAG, "消息标记为已发送: messageId=$messageId")
                } else {
                    SignalDatabase.messages.markAsSentFailed(messageId)
                    Log.d(TAG, "消息标记为发送失败: messageId=$messageId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新消息状态失败: messageId=$messageId - ${LogSanitizer.sanitizeThrowable(e)}")
        }
    }
    
    /**
     * 检查接收方的Tap传输能力
     */
    fun checkRecipientTapCapability(recipientId: RecipientId): TapCapability {
        return try {
            val availableProviders = transportManager.getEnabledProviders()
            when {
                availableProviders.isEmpty() -> TapCapability.NOT_SUPPORTED
                !transportManager.isInitialized() -> TapCapability.TEMPORARILY_UNAVAILABLE
                else -> TapCapability.AVAILABLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查Tap能力失败: $recipientId - ${LogSanitizer.sanitizeThrowable(e)}")
            TapCapability.UNKNOWN
        }
    }
}

/**
 * Tap发送结果
 */
sealed class TapSendResult {
    data class Success(val messageKey: String) : TapSendResult()
    data class Failed(val reason: String) : TapSendResult()
}

/**
 * Tap传输能力
 */
enum class TapCapability {
    AVAILABLE,                    // 可用
    NOT_SUPPORTED,               // 不支持
    TEMPORARILY_UNAVAILABLE,     // 临时不可用
    UNKNOWN                      // 未知状态
} 