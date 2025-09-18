package org.thoughtcrime.securesms.tap

/**
 * 传输消息数据结构
 * 
 * 用于封装在传输层传递的消息内容，包含加密后的消息数据、类型、时间戳和附件等信息。
 * 该数据结构与具体的传输服务解耦，可以在不同的TransportProvider之间通用。
 */
data class TransportMessage(
    /** 消息唯一标识符 */
    val messageId: String,
    
    /** 加密后的消息内容 */
    val encryptedContent: ByteArray,
    
    /** 消息类型 */
    val messageType: TransportMessageType,
    
    /** 消息时间戳（毫秒） */
    val timestamp: Long,
    
    /** 附件列表，默认为空 */
    val attachments: List<TransportAttachment> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportMessage

        if (messageId != other.messageId) return false
        if (!encryptedContent.contentEquals(other.encryptedContent)) return false
        if (messageType != other.messageType) return false
        if (timestamp != other.timestamp) return false
        if (attachments != other.attachments) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + encryptedContent.contentHashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + attachments.hashCode()
        return result
    }
}

/**
 * 传输消息类型枚举
 * 
 * 定义了在传输层支持的各种消息类型，用于指导传输层的处理策略和优化。
 */
enum class TransportMessageType {
    /** 文本消息 - 包含文本内容的普通消息 */
    TEXT_MESSAGE,
    
    /** 媒体消息 - 包含图片、视频、音频等媒体文件的消息 */
    MEDIA_MESSAGE,
    
    /** 控制消息 - 系统控制和状态同步消息 */
    CONTROL_MESSAGE,
    
    /** 密钥轮转更新 - Key Ratcheting相关的安全更新消息 */
    RATCHET_UPDATE
}

/**
 * 传输附件数据结构
 * 
 * 用于表示消息中的附件，包含加密后的附件数据和相关元信息。
 */
data class TransportAttachment(
    /** 附件唯一标识符 */
    val attachmentId: String,
    
    /** 加密后的附件数据 */
    val encryptedData: ByteArray,
    
    /** 附件MIME类型 */
    val mimeType: String,
    
    /** 附件大小（字节） */
    val size: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportAttachment

        if (attachmentId != other.attachmentId) return false
        if (!encryptedData.contentEquals(other.encryptedData)) return false
        if (mimeType != other.mimeType) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attachmentId.hashCode()
        result = 31 * result + encryptedData.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
} 