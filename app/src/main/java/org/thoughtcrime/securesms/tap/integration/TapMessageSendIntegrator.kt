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
// 移除不再需要的导入
// Base64, SessionCipher, SignalProtocolAddress, SignalServiceAddress, Content, DataMessage
// 这些已经移动到TapSignalServiceAdapter中
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment

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
     * 检查是否可以使用Tap传输层发送消息
     * 
     * @param recipientId 接收方ID
     * @return 是否可以使用Tap传输层
     */
    fun canUseTapForSending(recipientId: org.thoughtcrime.securesms.recipients.RecipientId): Boolean {
        return try {
            Log.d(TAG, "检查Tap传输层可用性: recipientId=$recipientId")
            
            // 获取接收方ACI
            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId)
            val recipientAci = recipient.requireAci().toString()
            
            // 1. 检查是否有活跃的私聊传输通道（只检查私聊v2 mode）
            val hasActiveChannel = channelManager.hasActivePrivateChannel(recipientAci)
            if (!hasActiveChannel) {
                Log.d(TAG, "没有活跃的私聊传输通道: recipientId=$recipientId")
                return false
            }
            
            // 2. 检查传输管理器是否已初始化，如果未初始化则尝试按需初始化
            if (!transportManager.isInitialized()) {
                Log.w(TAG, "传输管理器未初始化，尝试按需初始化: recipientId=$recipientId")
                try {
                    val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
                    tapInitializer.initializeSync(false)
                    
                    // 再次检查初始化状态
                    if (!transportManager.isInitialized()) {
                        Log.w(TAG, "传输管理器按需初始化失败: recipientId=$recipientId")
                        return false
                    }
                    Log.i(TAG, "传输管理器按需初始化成功: recipientId=$recipientId")
                } catch (initException: Exception) {
                    Log.e(TAG, "传输管理器按需初始化异常: recipientId=$recipientId", initException)
                    return false
                }
            }
            
            // 3. 检查是否有可用的Provider
            val enabledProviders = transportManager.getEnabledProviders()
            if (enabledProviders.isEmpty()) {
                Log.w(TAG, "没有启用的Provider: recipientId=$recipientId")
                return false
            }
            
            Log.i(TAG, "Tap传输层可用: recipientId=$recipientId, providers=${enabledProviders.size}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "检查Tap传输层可用性失败: recipientId=$recipientId", e)
            false
        }
    }
    
    /**
     * 集成的消息发送方法
     * 
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @return 发送结果的Future
     */
    fun sendMessage(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): CompletableFuture<IntegratedTapSendResult> {
        Log.i(TAG, "发送消息: messageId=$messageId, recipient=${recipient.id}")
        
        return CompletableFuture.supplyAsync {
            try {
                // 检查是否有可用的私聊传输通道（只检查私聊v2 mode）
                val hasActiveChannel = channelManager.hasActivePrivateChannel(recipient.requireAci().toString())
                
                if (hasActiveChannel) {
                    Log.i(TAG, "使用Tap传输层发送: messageId=$messageId")
                    runBlocking {
                        executeTapSend(messageId, recipient, outgoingMessage)
                    }
                } else {
                    Log.w(TAG, "没有可用的Tap通道: messageId=$messageId")
                    IntegratedTapSendResult.Failed("没有可用的Tap传输通道")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "消息发送失败: messageId=$messageId", e)
                IntegratedTapSendResult.Failed("发送异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行Tap传输层发送
     */
    private suspend fun executeTapSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): IntegratedTapSendResult {
        return try {
            Log.i(TAG, "执行Tap传输层发送: messageId=$messageId")
            
            // 1. 使用TapSignalServiceAdapter进行Signal加密+Tap传输
            val adapter = TapSignalServiceAdapter.getInstance(context)
            val sendResult = adapter.sendWithSignalEncryption(messageId, recipient, outgoingMessage)
            
            when (sendResult) {
                is TapSignalSendResult.Success -> {
                    Log.i(TAG, "Tap传输层发送成功: messageId=$messageId, path=${sendResult.transportPath}")
                    IntegratedTapSendResult.Success(
                        method = IntegratedSendMethod.TAP_TRANSPORT,
                        path = sendResult.transportPath ?: "unknown",
                        metadata = mapOf(
                            "provider" to (sendResult.providerType ?: "unknown"),
                            "transportResult" to sendResult.toString()
                        )
                    )
                }
                
                is TapSignalSendResult.Failed -> {
                    Log.e(TAG, "Tap传输层发送失败: messageId=$messageId, reason=${sendResult.reason}")
                    IntegratedTapSendResult.Failed("Tap发送失败: ${sendResult.reason}")
                }
                
                is TapSignalSendResult.RetryLater -> {
                    Log.w(TAG, "Tap传输层发送需要重试: messageId=$messageId, reason=${sendResult.reason}")
                    IntegratedTapSendResult.RetryScheduled("Tap发送重试: ${sendResult.reason}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Tap传输层发送异常: messageId=$messageId", e)
            IntegratedTapSendResult.Failed("Tap发送异常: ${e.message}")
        }
    }
    
    // buildTransportMessage方法已移动到TapSignalServiceAdapter中
    
    // determineMessageType方法已移动到TapSignalServiceAdapter中
    
    // processAttachments方法已移动到TapSignalServiceAdapter中
    
    // 所有加密相关方法已移动到TapSignalServiceAdapter中
    // calculateAttachmentHash, calculateUriAttachmentHash, encryptMessageWithSignalProtocol,
    // createSignalDataMessageContent, getRecipientDeviceId, EncryptedMessageData
}

/**
 * 集成发送方法枚举
 */
enum class IntegratedSendMethod {
    TAP_TRANSPORT
}

/**
 * 集成TAP发送结果
 */
sealed class IntegratedTapSendResult {
    data class Success(
        val method: IntegratedSendMethod,
        val path: String,
        val metadata: Map<String, Any> = emptyMap()
    ) : IntegratedTapSendResult()
    
    data class Failed(val reason: String) : IntegratedTapSendResult()
    data class RetryScheduled(val message: String) : IntegratedTapSendResult()
} 