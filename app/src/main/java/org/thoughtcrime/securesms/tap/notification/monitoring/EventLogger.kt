package org.thoughtcrime.securesms.tap.notification.monitoring

import android.content.Context
import org.signal.core.util.logging.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 推送通知事件日志记录器
 * 
 * 记录推送通知系统的关键事件，便于调试和问题排查
 */
class EventLogger private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(EventLogger::class.java)
        private const val MAX_EVENTS_IN_MEMORY = 500
        private const val LOG_FILE_NAME = "notification_events.log"
        
        @Volatile
        private var instance: EventLogger? = null
        
        fun getInstance(context: Context): EventLogger {
            return instance ?: synchronized(this) {
                instance ?: EventLogger(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val events = ConcurrentLinkedQueue<NotificationEvent>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    
    /**
     * 记录连接事件
     */
    fun logConnection(success: Boolean, userId: String, errorMessage: String? = null) {
        val event = NotificationEvent(
            type = if (success) EventType.CONNECTION_SUCCESS else EventType.CONNECTION_FAILED,
            timestamp = System.currentTimeMillis(),
            userId = userId,
            details = if (errorMessage != null) mapOf("error" to errorMessage) else emptyMap()
        )
        addEvent(event)
    }
    
    /**
     * 记录断开连接事件
     */
    fun logDisconnection(userId: String, reason: String? = null) {
        val event = NotificationEvent(
            type = EventType.DISCONNECTION,
            timestamp = System.currentTimeMillis(),
            userId = userId,
            details = if (reason != null) mapOf("reason" to reason) else emptyMap()
        )
        addEvent(event)
    }
    
    /**
     * 记录通知接收事件
     */
    fun logNotificationReceived(senderId: String, userId: String, latencyMs: Long) {
        val event = NotificationEvent(
            type = EventType.NOTIFICATION_RECEIVED,
            timestamp = System.currentTimeMillis(),
            userId = userId,
            senderId = senderId,
            details = mapOf("latency_ms" to latencyMs)
        )
        addEvent(event)
    }
    
    /**
     * 记录通知发送事件
     */
    fun logNotificationSent(recipientId: String) {
        val event = NotificationEvent(
            type = EventType.NOTIFICATION_SENT,
            timestamp = System.currentTimeMillis(),
            recipientId = recipientId
        )
        addEvent(event)
    }
    
    /**
     * 记录Webhook调用事件
     */
    fun logWebhookCall(success: Boolean, recipientId: String, latencyMs: Long = 0, errorMessage: String? = null) {
        val event = NotificationEvent(
            type = if (success) EventType.WEBHOOK_SUCCESS else EventType.WEBHOOK_FAILED,
            timestamp = System.currentTimeMillis(),
            recipientId = recipientId,
            details = buildMap {
                put("latency_ms", latencyMs)
                if (errorMessage != null) {
                    put("error", errorMessage)
                }
            }
        )
        addEvent(event)
    }
    
    /**
     * 记录部署事件
     */
    fun logDeployment(success: Boolean, provider: String, details: Map<String, Any> = emptyMap()) {
        val event = NotificationEvent(
            type = if (success) EventType.DEPLOYMENT_SUCCESS else EventType.DEPLOYMENT_FAILED,
            timestamp = System.currentTimeMillis(),
            details = details + mapOf("provider" to provider)
        )
        addEvent(event)
    }
    
    /**
     * 记录错误事件
     */
    fun logError(errorType: String, errorMessage: String, userId: String? = null) {
        val event = NotificationEvent(
            type = EventType.ERROR,
            timestamp = System.currentTimeMillis(),
            userId = userId,
            details = mapOf(
                "error_type" to errorType,
                "error_message" to errorMessage
            )
        )
        addEvent(event)
    }
    
    /**
     * 添加事件到队列
     */
    private fun addEvent(event: NotificationEvent) {
        events.add(event)
        
        // 限制内存中的事件数量
        while (events.size > MAX_EVENTS_IN_MEMORY) {
            events.poll()
        }
        
        // 记录到Signal日志
        val logMessage = formatEvent(event)
        when (event.type) {
            EventType.ERROR, EventType.CONNECTION_FAILED, EventType.WEBHOOK_FAILED, EventType.DEPLOYMENT_FAILED ->
                Log.w(TAG, logMessage)
            else ->
                Log.d(TAG, logMessage)
        }
    }
    
    /**
     * 格式化事件为字符串
     */
    private fun formatEvent(event: NotificationEvent): String {
        val time = dateFormat.format(Date(event.timestamp))
        val parts = mutableListOf<String>()
        parts.add("[${event.type.name}]")
        parts.add(time)
        
        event.userId?.let { parts.add("userId=$it") }
        event.senderId?.let { parts.add("senderId=$it") }
        event.recipientId?.let { parts.add("recipientId=$it") }
        
        if (event.details.isNotEmpty()) {
            val detailsStr = event.details.entries.joinToString(", ") { "${it.key}=${it.value}" }
            parts.add(detailsStr)
        }
        
        return parts.joinToString(" ")
    }
    
    /**
     * 获取最近的事件
     */
    fun getRecentEvents(limit: Int = 100): List<NotificationEvent> {
        return events.toList().takeLast(limit)
    }
    
    /**
     * 导出事件日志到文件
     */
    fun exportToFile(): File? {
        return try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            val jsonArray = JSONArray()
            events.forEach { event ->
                val json = JSONObject().apply {
                    put("type", event.type.name)
                    put("timestamp", event.timestamp)
                    put("time", dateFormat.format(Date(event.timestamp)))
                    event.userId?.let { put("userId", it) }
                    event.senderId?.let { put("senderId", it) }
                    event.recipientId?.let { put("recipientId", it) }
                    if (event.details.isNotEmpty()) {
                        put("details", JSONObject(event.details))
                    }
                }
                jsonArray.put(json)
            }
            
            logFile.writeText(jsonArray.toString(2))
            Log.i(TAG, "事件日志已导出到: ${logFile.absolutePath}")
            
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "导出事件日志失败", e)
            null
        }
    }
    
    /**
     * 清空事件日志
     */
    fun clear() {
        events.clear()
        Log.i(TAG, "事件日志已清空")
    }
}

/**
 * 通知事件
 */
data class NotificationEvent(
    val type: EventType,
    val timestamp: Long,
    val userId: String? = null,
    val senderId: String? = null,
    val recipientId: String? = null,
    val details: Map<String, Any> = emptyMap()
)

/**
 * 事件类型
 */
enum class EventType {
    CONNECTION_SUCCESS,
    CONNECTION_FAILED,
    DISCONNECTION,
    NOTIFICATION_RECEIVED,
    NOTIFICATION_SENT,
    WEBHOOK_SUCCESS,
    WEBHOOK_FAILED,
    DEPLOYMENT_SUCCESS,
    DEPLOYMENT_FAILED,
    ERROR
}

