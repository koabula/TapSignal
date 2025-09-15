package org.thoughtcrime.securesms.coscomm.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

/**
 * COS消息数据结构
 * 用于在COS存储中传输的消息格式，纯传输层数据结构
 * 不包含任何Double Ratchet相关的排序信息，依赖Signal原生的消息处理机制
 */
data class CosMessage(
    @JsonProperty("version")
    val version: String = "1.0",
    
    @JsonProperty("messageId")
    val messageId: String,
    
    @JsonProperty("timestamp")
    val timestamp: Long,
    
    @JsonProperty("senderId")
    val senderId: String,
    
    @JsonProperty("recipientId")
    val recipientId: String,
    
    @JsonProperty("messageType")
    val messageType: MessageType,
    
    @JsonProperty("signalCiphertext")
    val signalCiphertext: String, // Base64编码的Signal原生密文
    
    @JsonProperty("signalCiphertextType")
    val signalCiphertextType: Int = 2, // Signal密文类型，默认为WHISPER_TYPE(2)，确保兼容性
    
    @JsonProperty("contentMetadata")
    val contentMetadata: ContentMetadata,
    
    @JsonProperty("attachmentInfo")
    val attachmentInfo: AttachmentInfo? = null
) {
    companion object {
        const val CURRENT_VERSION = "1.0"
        
        /**
         * 生成新的消息ID
         */
        fun generateMessageId(): String = UUID.randomUUID().toString()
    }
}



/**
 * 内容元数据
 * 包含加密和压缩相关信息
 */
data class ContentMetadata(
    @JsonProperty("originalSize")
    val originalSize: Long, // 原始内容大小
    
    @JsonProperty("compressionType")
    val compressionType: CompressionType = CompressionType.NONE,
    
    @JsonProperty("encryptionAlgorithm")
    val encryptionAlgorithm: String = "AES-256-GCM"
)

/**
 * 附件信息
 * 当消息包含附件时使用
 */
data class AttachmentInfo(
    @JsonProperty("fileName")
    val fileName: String, // 加密的文件名
    
    @JsonProperty("mimeType")
    val mimeType: String = "application/octet-stream", // 统一使用二进制类型隐藏真实类型
    
    @JsonProperty("size")
    val size: Long, // 附件大小
    
    @JsonProperty("attachmentId")
    val attachmentId: String, // UUID格式的附件ID
    
    @JsonProperty("fileHash")
    val fileHash: String? = null, // SHA-256哈希值，用于完整性验证
    
    @JsonProperty("cosPath")
    val cosPath: String? = null // COS存储路径，用于确保发送端和接收端路径一致
) {
    companion object {
        /**
         * 生成新的附件ID
         */
        fun generateAttachmentId(): String = UUID.randomUUID().toString()
    }
}

/**
 * 消息类型枚举
 */
enum class MessageType {
    @JsonProperty("text")
    TEXT,
    
    @JsonProperty("attachment")
    ATTACHMENT,
    
    @JsonProperty("typing")
    TYPING,
    
    @JsonProperty("receipt")
    RECEIPT,
    
    @JsonProperty("call")
    CALL
}

/**
 * 压缩类型枚举
 */
enum class CompressionType {
    @JsonProperty("none")
    NONE,
    
    @JsonProperty("gzip")
    GZIP
}
