package org.thoughtcrime.securesms.tap.notification

/**
 * 标准通知消息格式
 */
data class NotificationMessage(
    val type: String,
    val senderId: String,
    val timestamp: Long,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        const val TYPE_NEW_MESSAGE = "new_message"
        const val TYPE_HEARTBEAT = "heartbeat"
        
        fun newMessage(senderId: String, metadata: Map<String, Any> = emptyMap()): NotificationMessage {
            return NotificationMessage(
                type = TYPE_NEW_MESSAGE,
                senderId = senderId,
                timestamp = System.currentTimeMillis(),
                metadata = metadata
            )
        }
    }
    
    fun validate(): Boolean {
        return type.isNotEmpty() && senderId.isNotEmpty() && timestamp > 0
    }
}

