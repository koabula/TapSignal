package org.thoughtcrime.securesms.tapv3.protocol

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import java.util.concurrent.TimeUnit

class TapV3ControlMessageSender private constructor(
    private val context: Context
) {
    
    fun sendHandshakeRequest(
        recipientId: String,
        request: TapV3ControlMessage.HandshakeRequest
    ): TapV3Result<Unit> {
        return sendControlMessage(recipientId, request, "TAP_V3_REQ:")
    }
    
    fun sendHandshakeResponse(
        recipientId: String,
        response: TapV3ControlMessage.HandshakeResponse
    ): TapV3Result<Unit> {
        return sendControlMessage(recipientId, response, "TAP_V3_RESP:")
    }
    
    fun sendHandshakeAck(
        recipientId: String,
        ack: TapV3ControlMessage.HandshakeAck
    ): TapV3Result<Unit> {
        return sendControlMessage(recipientId, ack, "TAP_V3_ACK:")
    }
    
    fun sendKeyRotation(
        recipientId: String,
        keyRotation: TapV3ControlMessage.KeyRotation
    ): TapV3Result<Unit> {
        return sendControlMessage(recipientId, keyRotation, "TAP_V3_KEY_ROTATION:")
    }
    
    fun sendChannelClose(
        recipientId: String,
        channelClose: TapV3ControlMessage.ChannelClose
    ): TapV3Result<Unit> {
        return sendControlMessage(recipientId, channelClose, "TAP_V3_CLOSE:")
    }
    
    private fun sendControlMessage(
        recipientId: String,
        message: TapV3ControlMessage,
        prefix: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = TapV3ControlMessage.serialize(message)
            val base64 = Base64.encodeToString(serialized, Base64.NO_WRAP)
            val messageBody = "$prefix$base64"
            
            val recipient = try {
                val recipientIdObj = getRecipientIdFromString(recipientId)
                Recipient.resolved(recipientIdObj)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve recipient: ${recipientId.take(8)}...", e)
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Invalid recipient ID: ${e.message}",
                    e
                )
            }
            
            if (!recipient.isRegistered) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Recipient not registered on Signal"
                )
            }
            
            val outgoingMessage = OutgoingMessage(
                recipient = recipient,
                body = messageBody,
                attachments = emptyList(),
                timestamp = System.currentTimeMillis(),
                expiresIn = 0L,
                viewOnce = false,
                isSecure = true,
                mentions = emptyList(),
                bodyRanges = null
            )
            
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            
            // 只调用 MessageSender.send()，它内部会处理消息的插入
            // 不要手动调用 insertMessageOutbox()，否则会导致重复插入和唯一约束冲突
            MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null,
                null
            )
            
            Log.i(TAG, "Sent Tap v3 control message: $prefix to ${recipientId.take(8)}...")
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send control message", e)
            TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Failed to send control message: ${e.message}",
                e
            )
        }
    }
    
    /**
     * 从字符串获取RecipientId对象
     * 支持多种输入格式: ACI UUID、纯数字ID、"RecipientId::数字"格式
     */
    private fun getRecipientIdFromString(recipientId: String): RecipientId {
        return try {
            when {
                // 处理 "RecipientId::数字" 格式
                recipientId.startsWith("RecipientId::") -> {
                    val idNumber = recipientId.removePrefix("RecipientId::")
                    RecipientId.from(idNumber.toLong())
                }
                // 处理纯数字格式
                recipientId.all { it.isDigit() } -> {
                    RecipientId.from(recipientId.toLong())
                }
                // ACI格式（UUID样式），需要通过ACI反查RecipientId
                recipientId.contains("-") && recipientId.length >= 32 -> {
                    val aci = ACI.parseOrThrow(recipientId)
                    SignalDatabase.recipients.getByAci(aci).orElseThrow {
                        IllegalArgumentException("Cannot find RecipientId for ACI: ${recipientId.take(8)}...")
                    }
                }
                // 其他情况，尝试作为数字处理
                else -> {
                    RecipientId.from(recipientId.toLong())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RecipientId: ${recipientId.take(8)}...", e)
            throw IllegalArgumentException("Cannot parse RecipientId: ${recipientId.take(8)}...", e)
        }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3ControlMessageSender::class.java)
        
        @Volatile
        private var INSTANCE: TapV3ControlMessageSender? = null
        
        fun getInstance(context: Context): TapV3ControlMessageSender {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3ControlMessageSender(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
