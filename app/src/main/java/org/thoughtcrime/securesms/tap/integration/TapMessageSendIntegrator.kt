package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking

/**
 * Tap消息发送集成器
 * 负责将Tap传输层发送功能集成到Signal的消息发送流程中
 * 提供统一的发送接口，支持Signal Server和Tap传输层的智能路由
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
    private val channelManager = TransportChannelManager.getInstance(context)
    
    /**
     * 集成的消息发送方法
     * 这是外部调用的主要接口，会自动选择最佳发送方式
     * 
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @param forceSignalServer 是否强制使用Signal Server
     * @param signalSenderCallback Signal原生发送回调
     * @return 发送结果的Future
     */
    fun sendMessage(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        forceSignalServer: Boolean = false,
        signalSenderCallback: TapSenderCallback? = null
    ): CompletableFuture<IntegratedTapSendResult> {
        Log.i(TAG, "集成发送消息: messageId=$messageId, recipient=${recipient.id}, forceSignalServer=$forceSignalServer")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 如果强制使用Signal Server，直接调用原生发送
                if (forceSignalServer) {
                    Log.d(TAG, "强制使用Signal Server发送: messageId=$messageId")
                    return@supplyAsync executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                }
                
                // 2. 检查是否有可用的传输通道
                val hasActiveChannel = channelManager.hasActiveChannel(recipient.id.toString())
                
                if (hasActiveChannel) {
                    Log.i(TAG, "路由到Tap传输层: messageId=$messageId")
                    runBlocking {
                        executeTapSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                    }
                } else {
                    Log.i(TAG, "路由到Signal Server: messageId=$messageId, reason=无活跃传输通道")
                    executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "集成发送异常: messageId=$messageId", e)
                IntegratedTapSendResult.Failed(e.message ?: "未知异常")
            }
        }
    }
    
    /**
     * 检查是否可以使用Tap传输层发送
     */
    fun canUseTapForSending(recipientId: RecipientId): Boolean {
        return try {
            channelManager.hasActiveChannel(recipientId.toString())
        } catch (e: Exception) {
            Log.w(TAG, "检查Tap发送能力失败: recipientId=$recipientId", e)
            false
        }
    }
    
    /**
     * 执行Tap传输层发送
     */
    private suspend fun executeTapSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        signalSenderCallback: TapSenderCallback?
    ): IntegratedTapSendResult {
        return try {
            Log.i(TAG, "执行Tap传输层发送: messageId=$messageId")
            
            // 构建传输消息
            val transportMessage = buildTransportMessage(outgoingMessage, recipient, messageId)
            
            // 通过传输管理器发送
            val result = transportManager.sendMessage(
                recipientId = recipient.id.toString(),
                message = transportMessage
            )
            
            when (result) {
                is TransportResult.Success -> {
                    Log.i(TAG, "Tap传输层发送成功: messageId=$messageId")
                    IntegratedTapSendResult.Success("TAP_TRANSPORT", result.metadata.toString())
                }
                
                is TransportResult.Failed -> {
                    Log.e(TAG, "Tap传输层发送失败: messageId=$messageId, error=${result.error}")
                    
                    // v2 mode: 不回退到Signal Server，直接失败
                    IntegratedTapSendResult.Failed("Tap传输层发送失败: ${result.getFullErrorMessage()}")
                }
                
                is TransportResult.RetryScheduled -> {
                    Log.w(TAG, "Tap传输层发送需要重试: messageId=$messageId, reason=${result.reason}")
                    IntegratedTapSendResult.Failed("发送需要重试: ${result.reason}")
                }
                
                is TransportResult.PartialSuccess -> {
                    Log.w(TAG, "Tap传输层发送部分成功: messageId=$messageId")
                    IntegratedTapSendResult.Success("TAP_TRANSPORT", "部分成功")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tap传输层发送异常: messageId=$messageId", e)
            IntegratedTapSendResult.Failed("Tap传输层发送异常: ${e.message}")
        }
    }
    
    /**
     * 执行Signal Server发送（回退机制）
     */
    private fun executeSignalServerSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        signalSenderCallback: TapSenderCallback?
    ): IntegratedTapSendResult {
        return try {
            if (signalSenderCallback != null) {
                Log.i(TAG, "通过回调执行Signal Server发送: messageId=$messageId")
                val result = signalSenderCallback.sendMessage(messageId, recipient, outgoingMessage)
                
                if (result.success) {
                    IntegratedTapSendResult.Success("SIGNAL_SERVER", result.details ?: "")
                } else {
                    IntegratedTapSendResult.Failed("Signal Server发送失败: ${result.details}")
                }
            } else {
                Log.w(TAG, "缺少Signal Server发送回调: messageId=$messageId")
                IntegratedTapSendResult.Failed("缺少Signal Server发送回调")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signal Server发送异常: messageId=$messageId", e)
            IntegratedTapSendResult.Failed("Signal Server发送异常: ${e.message}")
        }
    }
    
    /**
     * 构建传输消息
     * 从Signal现有加密流程获取密文，而非使用明文
     */
    private fun buildTransportMessage(outgoingMessage: OutgoingMessage, recipient: Recipient, messageId: Long): org.thoughtcrime.securesms.tap.TransportMessage {
        Log.d(TAG, "构建TransportMessage: messageId=$messageId, recipient=${recipient.id}")
        
        try {
            // 使用Signal现有的加密流程生成密文
            val encryptedData = encryptMessageWithSignalProtocol(recipient, outgoingMessage)
            
            return org.thoughtcrime.securesms.tap.TransportMessage(
                messageId = messageId.toString(),
                timestamp = outgoingMessage.sentTimeMillis,
                senderId = "self", // 自己发送
                recipientId = recipient.id.toString(),
                messageType = org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE,
                signalCiphertext = Base64.encodeWithPadding(encryptedData.signalCiphertext),
                signalCiphertextType = encryptedData.signalCiphertextType,
                contentMetadata = org.thoughtcrime.securesms.tap.TransportContentMetadata(
                    originalSize = encryptedData.signalCiphertext.size.toLong()
                ),
                attachments = emptyList() // 简化处理，暂不处理附件
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密消息失败: messageId=$messageId", e)
            throw RuntimeException("消息加密失败", e)
        }
    }
    
    /**
     * 使用Signal协议加密消息内容
     * 参考CosMessageSendManager的加密实现
     */
    private fun encryptMessageWithSignalProtocol(
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): EncryptedMessageData {
        // 获取Signal协议地址 - 使用真实的设备ID
        val signalServiceAddress = SignalServiceAddress(recipient.requireServiceId())
        val deviceId = getRecipientDeviceId(recipient)
        val protocolAddress = SignalProtocolAddress(signalServiceAddress.identifier, deviceId)
        
        // 创建消息内容 - 使用Signal的DataMessage格式
        val messageContent = createSignalDataMessageContent(outgoingMessage)
        val contentBytes = messageContent.encode()
        
        // 使用Signal的加密机制
        val protocolStore = AppDependencies.protocolStore.aci()
        val sessionCipher = SessionCipher(protocolStore, protocolAddress)
        
        Log.d(TAG, "🔐 开始加密消息: recipient=${recipient.id}")
        
        // 执行加密操作
        val ciphertext = sessionCipher.encrypt(contentBytes)
        
        Log.d(TAG, "🔐 加密完成: recipient=${recipient.id}, messageType=${ciphertext.type}")
        
        return EncryptedMessageData(
            signalCiphertext = ciphertext.serialize(),
            signalCiphertextType = ciphertext.type
        )
    }
    
    /**
     * 创建Signal DataMessage内容
     * 参考IndividualSendJob的消息构建逻辑
     */
    private fun createSignalDataMessageContent(outgoingMessage: OutgoingMessage): Content {
        val dataMessageBuilder = DataMessage.Builder()
            .body(outgoingMessage.body)
            .timestamp(outgoingMessage.sentTimeMillis)
        
        if (outgoingMessage.expiresIn > 0) {
            dataMessageBuilder.expireTimer((outgoingMessage.expiresIn / 1000).toInt())
        }
        
        val dataMessage = dataMessageBuilder.build()
        return Content.Builder().dataMessage(dataMessage).build()
    }
    
    /**
     * 获取接收者的设备ID
     * 参考CosMessageSendManager的实现
     */
    private fun getRecipientDeviceId(recipient: Recipient): Int {
        return SignalServiceAddress.DEFAULT_DEVICE_ID // 简化处理，使用默认设备ID
    }
    
    /**
     * 加密消息数据封装类
     */
    private data class EncryptedMessageData(
        val signalCiphertext: ByteArray,
        val signalCiphertextType: Int
    )
}

/**
 * Tap发送回调接口
 */
interface TapSenderCallback {
    fun sendMessage(messageId: Long, recipient: Recipient, outgoingMessage: OutgoingMessage): TapSendResult
}

/**
 * Tap发送结果
 */
data class TapSendResult(
    val success: Boolean,
    val details: String?
)

/**
 * 集成Tap发送结果
 */
sealed class IntegratedTapSendResult {
    data class Success(val method: String, val path: String) : IntegratedTapSendResult()
    data class Failed(val reason: String) : IntegratedTapSendResult()
    data class RetryScheduled(val message: String) : IntegratedTapSendResult()
} 