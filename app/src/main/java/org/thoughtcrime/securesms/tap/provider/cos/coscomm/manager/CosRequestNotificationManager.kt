package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.ConcurrentHashMap

/**
 * COS请求通知管理器
 * 负责管理COS请求相关的通知显示和用户交互
 */
class CosRequestNotificationManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosRequestNotificationManager::class.java)
        
        @Volatile
        private var INSTANCE: CosRequestNotificationManager? = null
        
        fun getInstance(context: Context): CosRequestNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosRequestNotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // 通知渠道ID
        private const val CHANNEL_ID = "cos_requests"
        private const val CHANNEL_NAME = "COS通信请求"
        private const val CHANNEL_DESCRIPTION = "COS通信请求和响应通知"
        
        // 通知ID基础值
        private const val NOTIFICATION_ID_BASE = 10000
    }
    
    private val notificationManager = NotificationManagerCompat.from(context)
    private val pendingRequests: MutableMap<String, CosRequestNotification> = ConcurrentHashMap()
    
    init {
        createNotificationChannel()
    }
    
    /**
     * 显示COS请求通知
     * @param senderId 发送方ID
     * @param cosRequest COS请求
     */
    fun showRequestNotification(senderId: String, cosRequest: CosRequest) {
        Log.i(TAG, "显示COS请求通知: senderId=$senderId, requestId=${cosRequest.requestId}")
        
        try {
            val sender = Recipient.resolved(RecipientId.from(senderId))
            val senderName = sender.getDisplayName(context)
            
            val notification = CosRequestNotification(
                requestId = cosRequest.requestId,
                senderId = senderId,
                senderName = senderName,
                durationType = cosRequest.durationType,
                message = cosRequest.message,
                timestamp = cosRequest.timestamp
            )
            
            // 保存到待处理请求列表
            pendingRequests[cosRequest.requestId] = notification
            
            // 创建通知
            val notificationBuilder = createRequestNotificationBuilder(notification)
            val notificationId = getNotificationId(cosRequest.requestId)
            
            notificationManager.notify(notificationId, notificationBuilder.build())
            
            Log.i(TAG, "COS请求通知已显示: requestId=${cosRequest.requestId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示COS请求通知失败", e)
        }
    }
    
    /**
     * 显示COS响应通知
     * @param senderId 发送方ID
     * @param cosResponse COS响应
     */
    fun showResponseNotification(senderId: String, cosResponse: CosResponse) {
        Log.i(TAG, "显示COS响应通知: senderId=$senderId, requestId=${cosResponse.requestId}")
        
        try {
            val sender = Recipient.resolved(RecipientId.from(senderId))
            val senderName = sender.getDisplayName(context)
            
            val notificationBuilder = createResponseNotificationBuilder(senderName, cosResponse)
            val notificationId = getNotificationId(cosResponse.requestId)
            
            notificationManager.notify(notificationId, notificationBuilder.build())
            
            // 从待处理列表中移除
            pendingRequests.remove(cosResponse.requestId)
            
            Log.i(TAG, "COS响应通知已显示: requestId=${cosResponse.requestId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示COS响应通知失败", e)
        }
    }
    
    /**
     * 显示COS撤销通知
     * @param senderId 发送方ID
     * @param cosRevocation COS撤销
     */
    fun showRevocationNotification(senderId: String, cosRevocation: CosRevocation) {
        Log.i(TAG, "显示COS撤销通知: senderId=$senderId, requestId=${cosRevocation.requestId}")
        
        try {
            val sender = Recipient.resolved(RecipientId.from(senderId))
            val senderName = sender.getDisplayName(context)
            
            val notificationBuilder = createRevocationNotificationBuilder(senderName, cosRevocation)
            val notificationId = getNotificationId(cosRevocation.requestId)
            
            notificationManager.notify(notificationId, notificationBuilder.build())
            
            // 从待处理列表中移除
            pendingRequests.remove(cosRevocation.requestId)
            
            Log.i(TAG, "COS撤销通知已显示: requestId=${cosRevocation.requestId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示COS撤销通知失败", e)
        }
    }
    
    /**
     * 取消通知
     * @param requestId 请求ID
     */
    fun cancelNotification(requestId: String) {
        val notificationId = getNotificationId(requestId)
        notificationManager.cancel(notificationId)
        pendingRequests.remove(requestId)
        
        Log.i(TAG, "已取消通知: requestId=$requestId")
    }
    
    /**
     * 获取待处理的请求
     */
    fun getPendingRequests(): List<CosRequestNotification> {
        return pendingRequests.values.toList()
    }
    
    /**
     * 获取特定的待处理请求
     */
    fun getPendingRequest(requestId: String): CosRequestNotification? {
        return pendingRequests[requestId]
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                enableLights(true)
            }
            
            val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            systemNotificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建请求通知构建器
     */
    private fun createRequestNotificationBuilder(notification: CosRequestNotification): NotificationCompat.Builder {
        val title = "COS通信请求"
        val content = "${notification.senderName} 请求建立COS通信通道"
        val bigText = buildString {
            append("发送方: ${notification.senderName}\n")
            append("访问时长: ${getDurationDisplayName(notification.durationType)}\n")
            if (!notification.message.isNullOrBlank()) {
                append("说明: ${notification.message}\n")
            }
            append("请选择接受或拒绝此请求")
        }
        
        // TODO: 创建接受和拒绝的PendingIntent
        // val acceptIntent = createAcceptIntent(notification.requestId, notification.senderId)
        // val rejectIntent = createRejectIntent(notification.requestId, notification.senderId)
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(true)
            // .addAction(R.drawable.ic_check, "接受", acceptIntent)
            // .addAction(R.drawable.ic_close, "拒绝", rejectIntent)
    }
    
    /**
     * 创建响应通知构建器
     */
    private fun createResponseNotificationBuilder(senderName: String, cosResponse: CosResponse): NotificationCompat.Builder {
        val title = if (cosResponse.accepted) "COS请求已接受" else "COS请求已拒绝"
        val content = if (cosResponse.accepted) {
            "$senderName 接受了您的COS通信请求"
        } else {
            "$senderName 拒绝了您的COS通信请求"
        }
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
    }
    
    /**
     * 创建撤销通知构建器
     */
    private fun createRevocationNotificationBuilder(senderName: String, cosRevocation: CosRevocation): NotificationCompat.Builder {
        val title = "COS通信已撤销"
        val content = "$senderName 撤销了COS通信通道"
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
    }
    
    /**
     * 获取通知ID
     */
    private fun getNotificationId(requestId: String): Int {
        return NOTIFICATION_ID_BASE + requestId.hashCode()
    }
    
    /**
     * 获取时长显示名称
     */
    private fun getDurationDisplayName(durationType: CosDuration): String {
        return when (durationType) {
            CosDuration.ONE_HOUR -> "1小时"
            CosDuration.ONE_DAY -> "1天"
            CosDuration.ONE_WEEK -> "1周"
            CosDuration.ONE_MONTH -> "1个月"
            CosDuration.PERMANENT -> "永久"
        }
    }
}

/**
 * COS请求通知数据
 */
data class CosRequestNotification(
    val requestId: String,
    val senderId: String,
    val senderName: String,
    val durationType: CosDuration,
    val message: String?,
    val timestamp: Long
)
