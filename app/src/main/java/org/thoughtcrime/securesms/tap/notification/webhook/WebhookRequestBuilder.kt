package org.thoughtcrime.securesms.tap.notification.webhook

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import org.thoughtcrime.securesms.tap.notification.WebhookRequest
import org.json.JSONObject

/**
 * Webhook请求构造器
 */
class WebhookRequestBuilder {
    
    companion object {
        private val TAG = Log.tag(WebhookRequestBuilder::class.java)
    }
    
    private val validator = WebhookSignatureValidator()
    
    fun buildRequest(
        notification: NotificationMessage,
        secret: String,
        version: String = "1.0"
    ): WebhookRequest? {
        return try {
            val bodyJson = buildNotificationJson(notification)
            val signature = validator.generateSignature(bodyJson, secret)
            
            if (signature.isEmpty()) {
                Log.e(TAG, "生成签名失败")
                return null
            }
            
            WebhookRequest(
                version = version,
                notification = notification,
                signature = signature
            )
        } catch (e: Exception) {
            Log.e(TAG, "构建Webhook请求失败", e)
            null
        }
    }
    
    fun buildRequestJson(
        notification: NotificationMessage,
        secret: String,
        version: String = "1.0"
    ): String? {
        return try {
            val notificationJson = buildNotificationJson(notification)
            val signature = validator.generateSignature(notificationJson, secret)
            
            if (signature.isEmpty()) {
                Log.e(TAG, "生成签名失败")
                return null
            }
            
            JSONObject().apply {
                put("version", version)
                put("notification", JSONObject(notificationJson))
                put("signature", signature)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "构建Webhook请求JSON失败", e)
            null
        }
    }
    
    private fun buildNotificationJson(notification: NotificationMessage): String {
        return JSONObject().apply {
            put("type", notification.type)
            put("senderId", notification.senderId)
            put("timestamp", notification.timestamp)
            
            if (notification.metadata.isNotEmpty()) {
                val metadataJson = JSONObject()
                notification.metadata.forEach { (key, value) ->
                    metadataJson.put(key, value)
                }
                put("metadata", metadataJson)
            }
        }.toString()
    }
    
    fun parseRequest(requestBody: String): WebhookRequest? {
        return try {
            val json = JSONObject(requestBody)
            val version = json.optString("version", "1.0")
            val signature = json.getString("signature")
            
            val notificationJson = json.getJSONObject("notification")
            val notification = parseNotification(notificationJson)
            
            WebhookRequest(
                version = version,
                notification = notification,
                signature = signature
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析Webhook请求失败", e)
            null
        }
    }
    
    private fun parseNotification(json: JSONObject): NotificationMessage {
        val type = json.getString("type")
        val senderId = json.getString("senderId")
        val timestamp = json.getLong("timestamp")
        
        val metadata = mutableMapOf<String, Any>()
        if (json.has("metadata")) {
            val metadataJson = json.getJSONObject("metadata")
            metadataJson.keys().forEach { key ->
                metadata[key] = metadataJson.get(key)
            }
        }
        
        return NotificationMessage(
            type = type,
            senderId = senderId,
            timestamp = timestamp,
            metadata = metadata
        )
    }
}

