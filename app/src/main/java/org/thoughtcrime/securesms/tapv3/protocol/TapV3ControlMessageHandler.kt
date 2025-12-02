package org.thoughtcrime.securesms.tapv3.protocol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.util.JsonUtils
import org.whispersystems.signalservice.api.push.ServiceId.ACI

class TapV3ControlMessageHandler private constructor(
    private val context: Context
) {
    
    private val handshakeManager = TapV3HandshakeManager.getInstance(context)
    
    // 存储待确认的握手请求
    private val pendingHandshakeRequests = mutableMapOf<String, PendingHandshakeRequest>()
    
    data class PendingHandshakeRequest(
        val senderId: String,
        val request: TapV3ControlMessage.HandshakeRequest,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun handleControlMessage(
        messageBody: String,
        senderId: String
    ): TapV3Result<Unit> {
        return when {
            messageBody.startsWith("TAP_V3_REQ:") -> {
                handleHandshakeRequest(messageBody.substring(11), senderId)
            }
            messageBody.startsWith("TAP_V3_RESP:") -> {
                handleHandshakeResponse(messageBody.substring(12), senderId)
            }
            messageBody.startsWith("TAP_V3_ACK:") -> {
                handleHandshakeAck(messageBody.substring(11), senderId)
            }
            messageBody.startsWith("TAP_V3_KEY_ROTATION:") -> {
                handleKeyRotation(messageBody.substring(20), senderId)
            }
            messageBody.startsWith("TAP_V3_CLOSE:") -> {
                handleChannelClose(messageBody.substring(13), senderId)
            }
            else -> {
                Log.w(TAG, "Unknown Tap v3 control message type: ${messageBody.take(20)}")
                TapV3Result.Success(Unit)
            }
        }
    }
    
    private fun handleHandshakeRequest(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeRequest) {
                Log.e(TAG, "Expected HandshakeRequest but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake request from ${senderId.take(8)}...")
            
            // 存储待确认的握手请求
            synchronized(pendingHandshakeRequests) {
                pendingHandshakeRequests[senderId] = PendingHandshakeRequest(
                    senderId = senderId,
                    request = message
                )
            }
            
            // 显示用户确认通知
            showHandshakeConfirmNotification(senderId, message)
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake request", e)
            TapV3Result.Success(Unit)
        }
    }
    
    /**
     * 显示握手确认通知
     */
    private fun showHandshakeConfirmNotification(
        senderId: String,
        request: TapV3ControlMessage.HandshakeRequest
    ) {
        try {
            // 获取发送者信息
            val recipientId = getRecipientIdFromAci(senderId)
            val senderRecipient = if (recipientId != null) {
                Recipient.resolved(recipientId)
            } else {
                null
            }
            val senderName = senderRecipient?.getDisplayName(context) ?: senderId.take(8)
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 创建通知渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Tap v3 Handshake",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Tap v3 handshake request notifications"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // 创建接受按钮的Intent
            val acceptIntent = Intent(context, TapV3HandshakeReceiver::class.java).apply {
                action = ACTION_ACCEPT_HANDSHAKE
                putExtra(EXTRA_SENDER_ID, senderId)
            }
            val acceptPendingIntent = PendingIntent.getBroadcast(
                context,
                (senderId + "accept").hashCode(),
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建拒绝按钮的Intent
            val rejectIntent = Intent(context, TapV3HandshakeReceiver::class.java).apply {
                action = ACTION_REJECT_HANDSHAKE
                putExtra(EXTRA_SENDER_ID, senderId)
            }
            val rejectPendingIntent = PendingIntent.getBroadcast(
                context,
                (senderId + "reject").hashCode(),
                rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 构建通知
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Tap v3 Mode Request")
                .setContentText("$senderName wants to establish Tap v3 mode")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$senderName wants to establish Tap v3 mode with you. This will allow messages to be sent via UnifiedPush and IPFS instead of Signal servers."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(R.drawable.v2_media_check, "Accept", acceptPendingIntent)
                .addAction(R.drawable.symbol_x_white_24, "Reject", rejectPendingIntent)
                .build()
            
            notificationManager.notify(senderId.hashCode(), notification)
            
            Log.i(TAG, "Handshake confirmation notification shown: senderId=${senderId.take(8)}..., senderName=$senderName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show handshake confirmation notification", e)
            // 如果无法显示通知，自动接受握手请求（降级处理）
            acceptHandshake(senderId)
        }
    }
    
    /**
     * 接受握手请求
     */
    fun acceptHandshake(senderId: String) {
        val pendingRequest = synchronized(pendingHandshakeRequests) {
            pendingHandshakeRequests.remove(senderId)
        }
        
        if (pendingRequest == null) {
            Log.w(TAG, "No pending handshake request for sender: ${senderId.take(8)}...")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = handshakeManager.handleHandshakeRequest(senderId, pendingRequest.request)
                
                if (result.isFailure()) {
                    Log.e(TAG, "Failed to handle handshake request: ${(result as TapV3Result.Failure).message}")
                } else {
                    Log.i(TAG, "Successfully handled handshake request")
                }
                
                // 关闭通知
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(senderId.hashCode())
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting handshake", e)
            }
        }
    }
    
    /**
     * 拒绝握手请求
     */
    fun rejectHandshake(senderId: String) {
        synchronized(pendingHandshakeRequests) {
            pendingHandshakeRequests.remove(senderId)
        }
        
        Log.i(TAG, "User rejected handshake request from: ${senderId.take(8)}...")
        
        // 关闭通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(senderId.hashCode())
        
        // TODO: 可以考虑发送拒绝响应给对方
    }
    
    /**
     * 从ACI获取RecipientId
     */
    private fun getRecipientIdFromAci(aci: String): RecipientId? {
        return try {
            val serviceId = ACI.parseOrThrow(aci)
            SignalDatabase.recipients.getByAci(serviceId).orElse(null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get RecipientId from ACI: ${aci.take(8)}...", e)
            null
        }
    }
    
    private fun handleHandshakeResponse(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeResponse) {
                Log.e(TAG, "Expected HandshakeResponse but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake response from ${senderId.take(8)}...")
            val result = handshakeManager.handleHandshakeResponse(senderId, message)
            
            if (result.isFailure()) {
                Log.e(TAG, "Failed to handle handshake response: ${(result as TapV3Result.Failure).message}")
            } else {
                Log.i(TAG, "Successfully handled handshake response")
            }
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake response", e)
            TapV3Result.Success(Unit)
        }
    }
    
    private fun handleHandshakeAck(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeAck) {
                Log.e(TAG, "Expected HandshakeAck but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake ack from ${senderId.take(8)}...")
            val result = handshakeManager.handleHandshakeAck(senderId, message)
            
            if (result.isFailure()) {
                Log.e(TAG, "Failed to handle handshake ack: ${(result as TapV3Result.Failure).message}")
            } else {
                Log.i(TAG, "Successfully handled handshake ack, channel established")
            }
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake ack", e)
            TapV3Result.Success(Unit)
        }
    }
    
    private fun handleKeyRotation(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        Log.d(TAG, "Key rotation received from ${senderId.take(8)}... (not yet implemented)")
        return TapV3Result.Success(Unit)
    }
    
    private fun handleChannelClose(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        Log.d(TAG, "Channel close received from ${senderId.take(8)}... (not yet implemented)")
        return TapV3Result.Success(Unit)
    }
    
    fun isControlMessage(messageBody: String): Boolean {
        return messageBody.startsWith("TAP_V3_REQ:") ||
               messageBody.startsWith("TAP_V3_RESP:") ||
               messageBody.startsWith("TAP_V3_ACK:") ||
               messageBody.startsWith("TAP_V3_KEY_ROTATION:") ||
               messageBody.startsWith("TAP_V3_CLOSE:")
    }
    
    companion object {
        private val TAG = Log.tag(TapV3ControlMessageHandler::class.java)
        
        const val NOTIFICATION_CHANNEL_ID = "tap_v3_handshake"
        const val ACTION_ACCEPT_HANDSHAKE = "org.thoughtcrime.securesms.tapv3.ACCEPT_HANDSHAKE"
        const val ACTION_REJECT_HANDSHAKE = "org.thoughtcrime.securesms.tapv3.REJECT_HANDSHAKE"
        const val EXTRA_SENDER_ID = "senderId"
        
        @Volatile
        private var INSTANCE: TapV3ControlMessageHandler? = null
        
        fun getInstance(context: Context): TapV3ControlMessageHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3ControlMessageHandler(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

/**
 * 处理Tap v3握手确认/拒绝的广播接收器
 */
class TapV3HandshakeReceiver : BroadcastReceiver() {
    
    companion object {
        private val TAG = Log.tag(TapV3HandshakeReceiver::class.java)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val senderId = intent.getStringExtra(TapV3ControlMessageHandler.EXTRA_SENDER_ID) ?: return
        
        Log.i(TAG, "Received handshake action: action=${intent.action}, senderId=${senderId.take(8)}...")
        
        when (intent.action) {
            TapV3ControlMessageHandler.ACTION_ACCEPT_HANDSHAKE -> {
                Log.i(TAG, "User accepted Tap v3 handshake from: ${senderId.take(8)}...")
                TapV3ControlMessageHandler.getInstance(context).acceptHandshake(senderId)
            }
            TapV3ControlMessageHandler.ACTION_REJECT_HANDSHAKE -> {
                Log.i(TAG, "User rejected Tap v3 handshake from: ${senderId.take(8)}...")
                TapV3ControlMessageHandler.getInstance(context).rejectHandshake(senderId)
            }
        }
    }
}
