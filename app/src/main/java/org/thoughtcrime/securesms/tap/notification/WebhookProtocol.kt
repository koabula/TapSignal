package org.thoughtcrime.securesms.tap.notification

/**
 * Webhook协议定义
 */
data class WebhookRequest(
    val version: String = "1.0",
    val notification: NotificationMessage,
    val signature: String
) {
    fun validate(): Boolean {
        return version.isNotEmpty() && notification.validate() && signature.isNotEmpty()
    }
}

data class WebhookResponse(
    val statusCode: Int,
    val delivered: Int = 0,
    val message: String = ""
) {
    companion object {
        fun success(delivered: Int = 1): WebhookResponse {
            return WebhookResponse(
                statusCode = 200,
                delivered = delivered,
                message = "ok"
            )
        }
        
        fun error(statusCode: Int, message: String): WebhookResponse {
            return WebhookResponse(
                statusCode = statusCode,
                delivered = 0,
                message = message
            )
        }
    }
    
    fun isSuccess(): Boolean = statusCode in 200..299
}

data class WebhookConfig(
    val webhookUrl: String,
    val notifySecret: String,
    val topicId: String,
    val version: String = "1.0"
) {
    fun validate(): Boolean {
        return webhookUrl.isNotEmpty() && 
               webhookUrl.startsWith("https://") && 
               notifySecret.isNotEmpty() &&
               topicId.isNotEmpty()
    }
}

