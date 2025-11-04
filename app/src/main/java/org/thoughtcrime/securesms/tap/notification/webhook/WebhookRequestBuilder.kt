package org.thoughtcrime.securesms.tap.notification.webhook

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import org.thoughtcrime.securesms.tap.notification.WebhookRequest
import org.thoughtcrime.securesms.tap.notification.utils.JsonSerializer
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
        version: String = "2.0"
    ): WebhookRequest? {
        return try {
            // 使用JsonSerializer确保JSON序列化顺序一致
            val notificationMap = JsonSerializer.buildNotificationMap(
                type = notification.type,
                senderId = notification.senderId,
                timestamp = notification.timestamp,
                metadata = notification.metadata
            )
            
            val bodyForSignature = JsonSerializer.buildSignatureBody(version, notificationMap)
            
            val signature = validator.generateSignature(bodyForSignature, secret)
            
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
        version: String = "2.0"
    ): String? {
        return try {
            // 1) 使用稳定序列化构造用于签名的主体（仅一次），确保与最终请求体完全一致
            val notificationMap = JsonSerializer.buildNotificationMap(
                type = notification.type,
                senderId = notification.senderId,
                timestamp = notification.timestamp,
                metadata = notification.metadata
            )

            val signatureBodyJson = JsonSerializer.buildSignatureBody(version, notificationMap)

            val signature = validator.generateSignature(signatureBodyJson, secret)

            if (signature.isEmpty()) {
                Log.e(TAG, "生成签名失败")
                return null
            }

            // 2) 复用 signatureBodyJson，直接追加 signature 字段，避免二次序列化差异
            val signatureBodyObj = JSONObject(signatureBodyJson)
            signatureBodyObj.put("signature", signature)
            signatureBodyObj.toString()
        } catch (e: Exception) {
            Log.e(TAG, "构建Webhook请求JSON失败", e)
            null
        }
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

