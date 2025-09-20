package org.thoughtcrime.securesms.tap

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.*

/**
 * 传输消息数据结构
 * 
 * 兼容coscomm模块的CosMessage格式，用于封装在传输层传递的消息内容，
 * 包含加密后的消息数据、类型、时间戳和附件等信息。
 * 该数据结构与具体的传输服务解耦，可以在不同的TransportProvider之间通用。
 */
data class TransportMessage(
    /** 版本信息，用于向前兼容 */
    @JsonProperty("version")
    val version: String = CURRENT_VERSION,
    
    /** 消息唯一标识符 */
    @JsonProperty("messageId")
    val messageId: String,
    
    /** 消息时间戳（毫秒） */
    @JsonProperty("timestamp")
    val timestamp: Long,
    
    /** 发送者ID */
    @JsonProperty("senderId")
    val senderId: String,
    
    /** 接收者ID */
    @JsonProperty("recipientId") 
    val recipientId: String,
    
    /** 消息类型 */
    @JsonProperty("messageType")
    val messageType: TransportMessageType,
    
    /** Base64编码的Signal原生密文 */
    @JsonProperty("signalCiphertext")
    val signalCiphertext: String,
    
    /** Signal密文类型，默认为WHISPER_TYPE(2) */
    @JsonProperty("signalCiphertextType")
    val signalCiphertextType: Int = 2,
    
    /** 内容元数据 */
    @JsonProperty("contentMetadata")
    val contentMetadata: TransportContentMetadata,
    
    /** 附件列表，默认为空 */
    @JsonProperty("attachments")
    val attachments: List<TransportAttachment> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = "1.0"
        
        // Jackson ObjectMapper配置
        private val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        
        /**
         * 生成新的消息ID
         */
        fun generateMessageId(): String = UUID.randomUUID().toString()
        
        /**
         * 序列化消息为二进制格式
         */
        fun serialize(message: TransportMessage): ByteArray {
            return try {
                val jsonString = objectMapper.writeValueAsString(message)
                jsonString.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                throw TransportException(
                    TransportError.INVALID_FORMAT,
                    "消息序列化失败: ${e.message}",
                    e
                )
            }
        }
        
        /**
         * 从二进制格式反序列化消息
         */
        fun deserialize(data: ByteArray): TransportMessage {
            return try {
                val jsonString = String(data, Charsets.UTF_8)
                objectMapper.readValue<TransportMessage>(jsonString)
            } catch (e: Exception) {
                throw TransportException(
                    TransportError.INVALID_FORMAT,
                    "消息反序列化失败: ${e.message}",
                    e
                )
            }
        }
    }
    
    // 保留原有的二进制字段，用于向后兼容
    @Deprecated("使用signalCiphertext替代", ReplaceWith("signalCiphertext"))
    val encryptedContent: ByteArray
        get() = android.util.Base64.decode(signalCiphertext, android.util.Base64.NO_WRAP)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportMessage

        if (version != other.version) return false
        if (messageId != other.messageId) return false
        if (timestamp != other.timestamp) return false
        if (senderId != other.senderId) return false
        if (recipientId != other.recipientId) return false
        if (messageType != other.messageType) return false
        if (signalCiphertext != other.signalCiphertext) return false
        if (signalCiphertextType != other.signalCiphertextType) return false
        if (contentMetadata != other.contentMetadata) return false
        if (attachments != other.attachments) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + messageType.hashCode()
        result = 31 * result + signalCiphertext.hashCode()
        result = 31 * result + signalCiphertextType.hashCode()
        result = 31 * result + contentMetadata.hashCode()
        result = 31 * result + attachments.hashCode()
        return result
    }
}

/**
 * 传输消息类型枚举
 * 
 * 定义了在传输层支持的各种消息类型，用于指导传输层的处理策略和优化。
 * 兼容coscomm模块的MessageType枚举。
 */
enum class TransportMessageType {
    /** 文本消息 - 包含文本内容的普通消息 */
    @JsonProperty("text")
    TEXT_MESSAGE,
    
    /** 媒体消息 - 包含图片、视频、音频等媒体文件的消息 */
    @JsonProperty("attachment")
    MEDIA_MESSAGE,
    
    /** 控制消息 - 系统控制和状态同步消息 */
    @JsonProperty("typing")
    CONTROL_MESSAGE,
    
    /** 密钥轮转更新 - Key Ratcheting相关的安全更新消息 */
    @JsonProperty("receipt")
    RATCHET_UPDATE,
    
    /** 通话消息 */
    @JsonProperty("call")
    CALL_MESSAGE;
    
    companion object {
        /**
         * 从coscomm的MessageType转换
         */
        fun fromCosMessageType(cosType: String): TransportMessageType {
            return when (cosType.lowercase()) {
                "text" -> TEXT_MESSAGE
                "attachment" -> MEDIA_MESSAGE
                "typing" -> CONTROL_MESSAGE
                "receipt" -> RATCHET_UPDATE
                "call" -> CALL_MESSAGE
                else -> TEXT_MESSAGE
            }
        }
    }
}

/**
 * 传输内容元数据
 * 
 * 兼容coscomm模块的ContentMetadata格式。
 */
data class TransportContentMetadata(
    /** 原始内容大小 */
    @JsonProperty("originalSize")
    val originalSize: Long,
    
    /** 压缩类型 */
    @JsonProperty("compressionType")
    val compressionType: TransportCompressionType = TransportCompressionType.NONE,
    
    /** 加密算法 */
    @JsonProperty("encryptionAlgorithm")
    val encryptionAlgorithm: String = "AES-256-GCM"
)

/**
 * 传输压缩类型枚举
 */
enum class TransportCompressionType {
    @JsonProperty("none")
    NONE,
    
    @JsonProperty("gzip")
    GZIP
}

/**
 * 传输附件数据结构
 * 
 * 兼容coscomm模块的AttachmentInfo格式。
 */
data class TransportAttachment(
    /** 附件唯一标识符 */
    @JsonProperty("attachmentId")
    val attachmentId: String,
    
    /** 加密的文件名 */
    @JsonProperty("fileName")
    val fileName: String,
    
    /** 附件MIME类型，统一使用二进制类型隐藏真实类型 */
    @JsonProperty("mimeType")
    val mimeType: String = "application/octet-stream",
    
    /** 附件大小（字节） */
    @JsonProperty("size")
    val size: Long,
    
    /** SHA-256哈希值，用于完整性验证 */
    @JsonProperty("fileHash")
    val fileHash: String? = null,
    
    /** 传输服务存储路径 */
    @JsonProperty("transportPath")
    val transportPath: String? = null
) {
    companion object {
        /**
         * 生成新的附件ID
         */
        fun generateAttachmentId(): String = UUID.randomUUID().toString()
    }
    
    // 保留原有字段，用于向后兼容
    @Deprecated("使用fileName替代", ReplaceWith("fileName"))
    val encryptedData: ByteArray
        get() = ByteArray(0) // 不再直接存储二进制数据
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportAttachment

        if (attachmentId != other.attachmentId) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (size != other.size) return false
        if (fileHash != other.fileHash) return false
        if (transportPath != other.transportPath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attachmentId.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (fileHash?.hashCode() ?: 0)
        result = 31 * result + (transportPath?.hashCode() ?: 0)
        return result
    }
} 