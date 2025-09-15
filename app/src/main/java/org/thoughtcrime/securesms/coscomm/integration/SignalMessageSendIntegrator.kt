package org.thoughtcrime.securesms.coscomm.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.manager.*
import org.thoughtcrime.securesms.coscomm.data.CosChannelStatistics
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.CompletableFuture

/**
 * Signal消息发送集成器
 * 负责将COS发送功能集成到Signal的消息发送流程中
 * 提供统一的发送接口，支持Signal Server和COS的智能路由
 */
class SignalMessageSendIntegrator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(SignalMessageSendIntegrator::class.java)
        
        @Volatile
        private var INSTANCE: SignalMessageSendIntegrator? = null
        
        fun getInstance(context: Context): SignalMessageSendIntegrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SignalMessageSendIntegrator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val routingManager = MessageSendRoutingManager.getInstance(context)
    private val statusTracker = MessageSendStatusTracker.getInstance(context)
    
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
        signalSenderCallback: SignalSenderCallback? = null
    ): CompletableFuture<IntegratedSendResult> {
        Log.i(TAG, "集成发送消息: messageId=$messageId, recipient=${recipient.id}, forceSignalServer=$forceSignalServer")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 如果强制使用Signal Server，直接调用原生发送
                if (forceSignalServer) {
                    Log.d(TAG, "强制使用Signal Server发送: messageId=$messageId")
                    return@supplyAsync executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                }
                
                // 2. 进行路由决策
                val route = routingManager.routeMessageSend(messageId, recipient, outgoingMessage, true)
                
                when (route) {
                    is MessageSendRoute.SignalServer -> {
                        Log.i(TAG, "路由到Signal Server: messageId=$messageId, reason=${route.reason}")
                        executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                    }
                    
                    is MessageSendRoute.COS -> {
                        Log.i(TAG, "路由到COS: messageId=$messageId, allowFallback=${route.allowFallback}")
                        executeCosWithFallback(messageId, recipient, outgoingMessage, route.allowFallback, signalSenderCallback)
                    }
                    
                    is MessageSendRoute.Failed -> {
                        Log.e(TAG, "路由失败: messageId=$messageId, reason=${route.reason}")
                        IntegratedSendResult.Failed(route.reason)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "集成发送异常: messageId=$messageId", e)
                IntegratedSendResult.Failed(e.message ?: "未知异常")
            }
        }
    }
    
    /**
     * 检查是否可以使用COS发送
     */
    fun canUseCosForSending(recipientId: RecipientId): Boolean {
        return try {
            routingManager.routeMessageSend(
                messageId = -1, // 临时ID，仅用于检查
                recipient = Recipient.resolved(recipientId),
                outgoingMessage = createDummyMessage(),
                fallbackToSignal = false
            ) is MessageSendRoute.COS
        } catch (e: Exception) {
            Log.w(TAG, "检查COS发送能力失败: recipientId=$recipientId", e)
            false
        }
    }
    
    /**
     * 获取消息发送状态
     */
    fun getMessageSendStatus(messageId: Long): MessageSendStatus? {
        return statusTracker.getSendStatus(messageId)
    }
    
    /**
     * 重试失败的消息
     */
    fun retryMessage(messageId: Long): CompletableFuture<IntegratedSendResult> {
        Log.i(TAG, "重试消息: messageId=$messageId")
        
        return CompletableFuture.supplyAsync {
            try {
                val status = statusTracker.getSendStatus(messageId)
                if (status == null) {
                    return@supplyAsync IntegratedSendResult.Failed("找不到消息发送状态")
                }
                
                if (!statusTracker.retryMessage(messageId)) {
                    return@supplyAsync IntegratedSendResult.Failed("消息不允许重试")
                }
                
                // 根据原发送方法重试
                when (status.method) {
                    MessageSendMethod.COS -> {
                        // 重新执行COS发送流程
                        // 这里需要重新获取消息和接收方信息
                        IntegratedSendResult.RetryScheduled("COS重试已安排")
                    }
                    MessageSendMethod.SIGNAL_SERVER -> {
                        // 重新执行Signal Server发送流程
                        IntegratedSendResult.RetryScheduled("Signal Server重试已安排")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重试消息异常: messageId=$messageId", e)
                IntegratedSendResult.Failed(e.message ?: "重试异常")
            }
        }
    }
    
    /**
     * 取消消息发送
     */
    fun cancelMessage(messageId: Long) {
        Log.i(TAG, "取消消息发送: messageId=$messageId")
        statusTracker.cancelMessage(messageId)
    }
    
    /**
     * 获取COS通道统计信息
     */
    fun getCosChannelStatistics(recipientId: RecipientId): CosChannelStatistics? {
        return routingManager.getCosChannelStatistics(recipientId)
    }
    
    /**
     * 执行COS发送并支持回退
     */
    private fun executeCosWithFallback(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        allowFallback: Boolean,
        signalSenderCallback: SignalSenderCallback?
    ): IntegratedSendResult {
        return try {
            val cosResult = routingManager.executeCosMessageSend(messageId, recipient, outgoingMessage, allowFallback).get()
            
            when (cosResult) {
                is MessageSendExecutionResult.Success -> {
                    Log.i(TAG, "COS发送成功: messageId=$messageId")
                    IntegratedSendResult.Success(cosResult.method, cosResult.path)
                }
                
                is MessageSendExecutionResult.FallbackRequired -> {
                    if (allowFallback) {
                        Log.i(TAG, "COS发送失败，回退到Signal Server: messageId=$messageId, reason=${cosResult.reason}")
                        executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
                    } else {
                        Log.w(TAG, "COS发送失败且不允许回退: messageId=$messageId, reason=${cosResult.reason}")
                        IntegratedSendResult.Failed(cosResult.reason)
                    }
                }
                
                is MessageSendExecutionResult.Failed -> {
                    Log.e(TAG, "COS发送失败: messageId=$messageId, reason=${cosResult.reason}")
                    IntegratedSendResult.Failed(cosResult.reason)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "COS发送异常: messageId=$messageId", e)
            if (allowFallback) {
                Log.i(TAG, "COS发送异常，回退到Signal Server: messageId=$messageId")
                executeSignalServerSend(messageId, recipient, outgoingMessage, signalSenderCallback)
            } else {
                IntegratedSendResult.Failed(e.message ?: "COS发送异常")
            }
        }
    }
    
    /**
     * 执行Signal Server发送
     */
    private fun executeSignalServerSend(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        signalSenderCallback: SignalSenderCallback?
    ): IntegratedSendResult {
        return try {
            Log.i(TAG, "执行Signal Server发送: messageId=$messageId")
            
            // 记录Signal Server发送开始
            statusTracker.onSendStarted(messageId, MessageSendMethod.SIGNAL_SERVER)
            
            // 调用Signal原生发送逻辑
            val result = signalSenderCallback?.sendMessage(messageId, recipient, outgoingMessage)
                ?: throw IllegalStateException("Signal发送回调未提供")
            
            // 根据结果更新状态
            if (result.isSuccess) {
                statusTracker.onSendSuccess(messageId, MessageSendMethod.SIGNAL_SERVER, null)
                IntegratedSendResult.Success(MessageSendMethod.SIGNAL_SERVER, null)
            } else {
                statusTracker.onSendFailure(messageId, MessageSendMethod.SIGNAL_SERVER, result.errorMessage ?: "Signal发送失败")
                IntegratedSendResult.Failed(result.errorMessage ?: "Signal发送失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signal Server发送异常: messageId=$messageId", e)
            statusTracker.onSendFailure(messageId, MessageSendMethod.SIGNAL_SERVER, e.message ?: "Signal发送异常")
            IntegratedSendResult.Failed(e.message ?: "Signal发送异常")
        }
    }
    
    /**
     * 创建虚拟消息用于检查
     */
    private fun createDummyMessage(): OutgoingMessage {
        // 使用OutgoingMessage的text工厂方法
        return OutgoingMessage.text(
            threadRecipient = Recipient.UNKNOWN,
            body = "",
            expiresIn = 0L,
            sentTimeMillis = System.currentTimeMillis()
        )
    }
}

/**
 * Signal发送回调接口
 * 用于调用Signal原生发送逻辑
 */
interface SignalSenderCallback {
    fun sendMessage(messageId: Long, recipient: Recipient, outgoingMessage: OutgoingMessage): SignalSendResult
}

/**
 * Signal发送结果
 */
data class SignalSendResult(
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

/**
 * 集成发送结果
 */
sealed class IntegratedSendResult {
    /**
     * 发送成功
     */
    data class Success(val method: MessageSendMethod, val path: String?) : IntegratedSendResult()
    
    /**
     * 发送失败
     */
    data class Failed(val reason: String) : IntegratedSendResult()
    
    /**
     * 重试已安排
     */
    data class RetryScheduled(val message: String) : IntegratedSendResult()
}
