package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.CompletableFuture

/**
 * 消息发送路由管理器
 * 负责决定消息是通过Signal Server还是COS发送，并提供统一的发送接口
 */
class MessageSendRoutingManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(MessageSendRoutingManager::class.java)
        
        @Volatile
        private var INSTANCE: MessageSendRoutingManager? = null
        
        fun getInstance(context: Context): MessageSendRoutingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageSendRoutingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val cosMessageSendManager = CosMessageSendManager.getInstance(context)
    private val cosChannelManager = CosChannelManager.getInstance(context)
    private val sendStatusTracker = MessageSendStatusTracker.getInstance(context)
    private val configManager = CosSendConfigManager.getInstance(context)
    private val statisticsManager = CosSendStatisticsManager.getInstance(context)
    
    /**
     * 路由消息发送
     * 根据COS通道状态决定发送方式
     * 
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @param fallbackToSignal 是否允许回退到Signal Server
     * @return 路由决策结果
     */
    fun routeMessageSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        fallbackToSignal: Boolean = true
    ): MessageSendRoute {
        Log.i(TAG, "路由消息发送: messageId=$messageId, recipient=${recipient.id}")
        
        return try {
            // 0. 检查COS发送是否启用
            if (!configManager.isCosSeendEnabled) {
                Log.d(TAG, "COS发送已禁用，使用Signal Server发送")
                return MessageSendRoute.SignalServer("COS发送已禁用")
            }

            // 1. 检查是否为群组消息（暂不支持COS群组发送）
            if (recipient.isGroup) {
                Log.d(TAG, "群组消息，使用Signal Server发送")
                return MessageSendRoute.SignalServer("群组消息暂不支持COS发送")
            }

            // 2. 检查COS通道状态
            val shouldUseCos = cosMessageSendManager.shouldUseCosForSending(recipient.id)
            if (!shouldUseCos) {
                Log.d(TAG, "COS通道不可用，使用Signal Server发送")
                return MessageSendRoute.SignalServer("COS通道不可用")
            }

            // 3. 检查是否为COS控制消息（请求、响应等）
            if (isCosControlMessage(outgoingMessage)) {
                Log.d(TAG, "COS控制消息，强制使用Signal Server发送")
                return MessageSendRoute.SignalServer("COS控制消息必须通过Signal Server发送")
            }

            // 4. 检查消息类型是否支持COS发送
            if (!isSupportedForCos(outgoingMessage)) {
                Log.d(TAG, "消息类型不支持COS发送，使用Signal Server发送")
                return MessageSendRoute.SignalServer("消息类型不支持COS发送")
            }

            // 5. 决定使用COS发送
            Log.i(TAG, "使用COS发送消息: messageId=$messageId")
            MessageSendRoute.COS(fallbackToSignal && configManager.isAutoFallbackEnabled)
            
        } catch (e: Exception) {
            Log.e(TAG, "路由决策异常: messageId=$messageId", e)
            if (fallbackToSignal) {
                MessageSendRoute.SignalServer("路由决策异常: ${e.message}")
            } else {
                MessageSendRoute.Failed("路由决策失败: ${e.message}")
            }
        }
    }
    
    /**
     * 执行COS消息发送
     *
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @param allowFallback 是否允许回退到Signal Server
     * @return 发送结果的Future
     */
    fun executeCosMessageSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        allowFallback: Boolean = true
    ): CompletableFuture<MessageSendExecutionResult> {
        Log.i(TAG, "执行COS消息发送: messageId=$messageId")

        // 🔧 修复：检查是否处于COS v2模式，如果是则禁用回退
        val isV2Mode = isCosV2Mode(recipient.id.toString())
        val actualAllowFallback = if (isV2Mode) {
            Log.i(TAG, "检测到COS v2模式，禁用回退到Signal Server: messageId=$messageId")
            false
        } else {
            allowFallback
        }

        // 记录发送开始
        sendStatusTracker.onSendStarted(messageId, MessageSendMethod.COS)
        statisticsManager.recordCosSendStart(messageId)

        return cosMessageSendManager.sendMessageViaCos(messageId, recipient, outgoingMessage)
            .thenApply { result ->
                when (result) {
                    is CosSendResult.Success -> {
                        Log.i(TAG, "COS消息发送成功: messageId=$messageId")
                        sendStatusTracker.onSendSuccess(messageId, MessageSendMethod.COS, result.messagePath)
                        statisticsManager.recordCosSendSuccess(messageId, estimateMessageSize(outgoingMessage))
                        MessageSendExecutionResult.Success(MessageSendMethod.COS, result.messagePath)
                    }
                    is CosSendResult.Failure -> {
                        Log.w(TAG, "COS消息发送失败: messageId=$messageId, error=${result.errorMessage}")
                        sendStatusTracker.onSendFailure(messageId, MessageSendMethod.COS, result.errorMessage)
                        statisticsManager.recordCosSendFailure(messageId, result.errorMessage)

                        if (actualAllowFallback) {
                            Log.i(TAG, "COS发送失败，准备回退到Signal Server: messageId=$messageId")
                            statisticsManager.recordFallbackToSignal(messageId, result.errorMessage)
                            MessageSendExecutionResult.FallbackRequired(result.errorMessage)
                        } else {
                            if (isV2Mode) {
                                Log.w(TAG, "COS v2模式下发送失败，不允许回退: messageId=$messageId")
                            }
                            MessageSendExecutionResult.Failed(result.errorMessage)
                        }
                    }
                }
            }
            .exceptionally { throwable ->
                Log.e(TAG, "COS消息发送异常: messageId=$messageId", throwable)
                val errorMessage = throwable.message ?: "未知异常"
                sendStatusTracker.onSendFailure(messageId, MessageSendMethod.COS, errorMessage)
                
                if (actualAllowFallback) {
                    MessageSendExecutionResult.FallbackRequired(errorMessage)
                } else {
                    if (isV2Mode) {
                        Log.w(TAG, "COS v2模式下发送异常，不允许回退: messageId=$messageId")
                    }
                    MessageSendExecutionResult.Failed(errorMessage)
                }
            }
    }

    /**
     * 检查是否为COS控制消息
     * COS控制消息（请求、响应等）必须通过Signal Server发送
     */
    private fun isCosControlMessage(outgoingMessage: OutgoingMessage): Boolean {
        val messageBody = outgoingMessage.body

        // 检查是否包含COS消息前缀
        val cosMessagePrefix = org.thoughtcrime.securesms.coscomm.processor.CosSignalMessageProcessor.COS_MESSAGE_PREFIX
        if (messageBody.startsWith(cosMessagePrefix)) {
            Log.d(TAG, "检测到COS控制消息，消息前缀: ${cosMessagePrefix}")
            return true
        }

        return false
    }

    /**
     * 检查消息类型是否支持COS发送
     */
    private fun isSupportedForCos(outgoingMessage: OutgoingMessage): Boolean {
        // 检查消息大小
        val messageSize = estimateMessageSize(outgoingMessage)
        if (messageSize > configManager.maxMessageSize) {
            Log.w(TAG, "消息过大，不支持COS发送: size=$messageSize, limit=${configManager.maxMessageSize}")
            return false
        }

        // 检查附件大小
        val attachmentSize = outgoingMessage.attachments.sumOf { it.size }
        if (attachmentSize.compareTo(configManager.maxAttachmentSize) > 0) {
            Log.w(TAG, "附件过大，不支持COS发送: size=$attachmentSize, limit=${configManager.maxAttachmentSize}")
            return false
        }
        
        // 检查附件类型支持
        if (outgoingMessage.attachments.isNotEmpty()) {
            val unsupportedAttachments = outgoingMessage.attachments.filter { attachment ->
                !isSupportedAttachmentType(attachment.contentType)
            }
            if (unsupportedAttachments.isNotEmpty()) {
                Log.w(TAG, "包含不支持的附件类型: ${unsupportedAttachments.map { it.contentType }}")
                return false
            }
        }
        
        // 检查特殊消息类型
        // 注意：OutgoingMessage可能没有这些字段，需要根据实际情况调整
        // if (outgoingMessage.isSecureMessage && outgoingMessage.isEndSession) {
        //     Log.w(TAG, "会话结束消息不支持COS发送")
        //     return false
        // }

        // 检查是否为紧急消息（如通话消息）
        // if (outgoingMessage.isUrgent) {
        //     Log.w(TAG, "紧急消息不支持COS发送")
        //     return false
        // }
        
        return true
    }
    
    /**
     * 检查附件类型是否支持COS传输
     */
    private fun isSupportedAttachmentType(contentType: String?): Boolean {
        if (contentType == null) return false
        
        val mimeType = contentType.lowercase()
        return when {
            // 图片类型
            mimeType.startsWith("image/") -> {
                mimeType in listOf(
                    "image/jpeg", "image/jpg", "image/png", "image/gif", 
                    "image/webp", "image/bmp", "image/tiff"
                )
            }
            // 视频类型
            mimeType.startsWith("video/") -> {
                mimeType in listOf(
                    "video/mp4", "video/avi", "video/mov", "video/wmv",
                    "video/mkv", "video/flv", "video/webm", "video/3gp"
                )
            }
            // 音频类型
            mimeType.startsWith("audio/") -> {
                mimeType in listOf(
                    "audio/mp3", "audio/mpeg", "audio/wav", "audio/aac",
                    "audio/ogg", "audio/flac", "audio/m4a", "audio/wma"
                )
            }
            // 文档类型
            mimeType.startsWith("application/") -> {
                mimeType in listOf(
                    "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip", "application/x-zip-compressed",
                    "application/json", "application/xml"
                )
            }
            // 文本类型
            mimeType.startsWith("text/") -> {
                mimeType in listOf("text/plain", "text/html", "text/csv")
            }
            // 其他支持的类型
            else -> false
        }
    }
    
    /**
     * 估算消息大小
     */
    private fun estimateMessageSize(outgoingMessage: OutgoingMessage): Long {
        var size = 0L
        
        // 消息体大小
        size += outgoingMessage.body.toByteArray(Charsets.UTF_8).size
        
        // 附件大小
        size += outgoingMessage.attachments.sumOf { it.size }

        // 链接预览大小（估算）
        size += outgoingMessage.linkPreviews.size * 1024 // 每个预览估算1KB

        // 联系人信息大小（估算）
        size += outgoingMessage.sharedContacts.size * 512 // 每个联系人估算512B
        
        return size
    }
    
    /**
     * 获取COS通道统计信息
     */
    fun getCosChannelStatistics(recipientId: RecipientId): CosChannelStatistics? {
        return try {
            val channel = cosChannelManager.getChannel(recipientId.toString())
            // 转换ChannelStatistics到CosChannelStatistics
            channel?.statistics?.let { stats ->
                CosChannelStatistics(
                    messagesSent = stats.messagesSent.toLong(),
                    messagesReceived = stats.messagesReceived.toLong(),
                    bytesTransferred = stats.totalDataSent + stats.totalDataReceived,
                    lastActivityTime = stats.lastSyncTime,
                    errorCount = stats.errorCount.toLong(),
                    successRate = if (stats.messagesSent > 0) {
                        (stats.messagesSent - stats.errorCount).toDouble() / stats.messagesSent.toDouble()
                    } else {
                        0.0
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取COS通道统计信息失败: recipientId=$recipientId", e)
            null
        }
    }
    
    /**
     * 强制刷新COS通道状态
     */
    fun refreshCosChannelStatus(recipientId: RecipientId): Boolean {
        return try {
            // TODO: 实现refreshChannelStatus方法
            // cosChannelManager.refreshChannelStatus(recipientId.serialize())
            true
        } catch (e: Exception) {
            Log.w(TAG, "刷新COS通道状态失败: recipientId=$recipientId", e)
            false
        }
    }

    /**
     * 检测是否处于COS v2模式
     * v2模式的特征：使用永久凭证的活跃COS通道
     */
    private fun isCosV2Mode(recipientId: String): Boolean {
        return try {
            // 检查是否启用永久凭证
            val isPermanentCredentialEnabled = org.thoughtcrime.securesms.cos.CosConfigStorage.isPermanentCredentialEnabled(context)
            if (!isPermanentCredentialEnabled) {
                return false
            }

            // 检查是否有活跃的COS通道
            val channel = cosChannelManager.getChannel(recipientId)
            if (channel?.status != org.thoughtcrime.securesms.coscomm.data.ChannelStatus.ACTIVE) {
                return false
            }

            // 检查通道是否使用永久凭证（通过检查sessionToken是否为空）
            val myAccessInfo = channel.myAccessInfo
            val theirAccessInfo = channel.theirAccessInfo

            val isMyCredentialPermanent = myAccessInfo?.sessionToken.isNullOrEmpty()
            val isTheirCredentialPermanent = theirAccessInfo?.sessionToken.isNullOrEmpty()

            // 只有双方都使用永久凭证才算v2模式
            val isV2Mode = isMyCredentialPermanent && isTheirCredentialPermanent

            Log.d(TAG, "COS v2模式检测: recipientId=$recipientId, " +
                      "permanentEnabled=$isPermanentCredentialEnabled, " +
                      "channelActive=${channel?.status}, " +
                      "myPermanent=$isMyCredentialPermanent, " +
                      "theirPermanent=$isTheirCredentialPermanent, " +
                      "isV2Mode=$isV2Mode")

            isV2Mode
        } catch (e: Exception) {
            Log.e(TAG, "检测COS v2模式时发生异常: recipientId=$recipientId", e)
            false
        }
    }
}

/**
 * 消息发送路由决策结果
 */
sealed class MessageSendRoute {
    /**
     * 使用Signal Server发送
     */
    data class SignalServer(val reason: String) : MessageSendRoute()
    
    /**
     * 使用COS发送
     */
    data class COS(val allowFallback: Boolean) : MessageSendRoute()
    
    /**
     * 发送失败
     */
    data class Failed(val reason: String) : MessageSendRoute()
}

/**
 * 消息发送执行结果
 */
sealed class MessageSendExecutionResult {
    /**
     * 发送成功
     */
    data class Success(val method: MessageSendMethod, val path: String?) : MessageSendExecutionResult()
    
    /**
     * 需要回退到Signal Server
     */
    data class FallbackRequired(val reason: String) : MessageSendExecutionResult()
    
    /**
     * 发送失败
     */
    data class Failed(val reason: String) : MessageSendExecutionResult()
}

/**
 * 消息发送方法
 */
enum class MessageSendMethod {
    SIGNAL_SERVER,
    COS
}
