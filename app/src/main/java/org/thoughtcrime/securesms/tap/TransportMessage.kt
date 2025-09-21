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
        val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        
        /**
         * 生成新的消息ID
         */
        fun generateMessageId(): String = UUID.randomUUID().toString()
        
        /**
         * 序列化消息为二进制格式（统一使用二进制格式）
         */
        fun serialize(message: TransportMessage): ByteArray {
            return serializeToBinary(message)
        }
        
        /**
         * 序列化消息为二进制格式（TaP专用格式，用于特殊场景）
         */
        fun serializeToBinary(message: TransportMessage): ByteArray {
            return try {
                val output = java.io.ByteArrayOutputStream()
                
                // 写入版本信息
                output.write(message.version.toByteArray(Charsets.UTF_8))
                output.write(0) // null terminator
                
                // 写入messageId长度和内容
                val messageIdBytes = message.messageId.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(messageIdBytes.size))
                output.write(messageIdBytes)
                
                // 写入timestamp
                output.write(longToBytes(message.timestamp))
                
                // 写入senderId长度和内容
                val senderIdBytes = message.senderId.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(senderIdBytes.size))
                output.write(senderIdBytes)
                
                // 写入recipientId长度和内容
                val recipientIdBytes = message.recipientId.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(recipientIdBytes.size))
                output.write(recipientIdBytes)
                
                // 写入messageType
                output.write(intToBytes(message.messageType.ordinal))
                
                // 写入signalCiphertext长度和内容
                val ciphertextBytes = message.signalCiphertext.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(ciphertextBytes.size))
                output.write(ciphertextBytes)
                
                // 写入signalCiphertextType
                output.write(intToBytes(message.signalCiphertextType))
                
                // 写入contentMetadata（JSON格式）
                val metadataJson = objectMapper.writeValueAsString(message.contentMetadata)
                val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(metadataBytes.size))
                output.write(metadataBytes)
                
                // 写入attachments数量
                output.write(intToBytes(message.attachments.size))
                
                // 写入每个attachment
                for (attachment in message.attachments) {
                    val attachmentJson = objectMapper.writeValueAsString(attachment)
                    val attachmentBytes = attachmentJson.toByteArray(Charsets.UTF_8)
                    output.write(intToBytes(attachmentBytes.size))
                    output.write(attachmentBytes)
                }
                
                output.toByteArray()
            } catch (e: Exception) {
                throw TransportException(
                    TransportError.INVALID_FORMAT,
                    "消息二进制序列化失败: ${e.message}",
                    e
                )
            }
        }
        
        /**
         * Int转4字节（大端序）
         */
        private fun intToBytes(value: Int): ByteArray {
            return byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }
        
        /**
         * Long转8字节（大端序）
         */
        private fun longToBytes(value: Long): ByteArray {
            return byteArrayOf(
                (value shr 56).toByte(),
                (value shr 48).toByte(),
                (value shr 40).toByte(),
                (value shr 32).toByte(),
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }
        
        /**
         * 从二进制格式反序列化消息
         */
        fun deserialize(data: ByteArray): TransportMessage {
            return deserializeFromBinary(data)
        }
        
        /**
         * 从二进制格式严格反序列化消息
         * 
         * 此方法实现严格的二进制格式解析，按照既定的字段顺序和长度进行读取，
         * 不允许任何猜测式补全或格式回退。如果格式不符合规范，将抛出异常。
         * 
         * 二进制格式规范：
         * 1. version: UTF-8字符串 + null terminator (0x00)
         * 2. messageId: 4字节长度 + UTF-8字符串内容
         * 3. timestamp: 8字节长整型（大端序）
         * 4. senderId: 4字节长度 + UTF-8字符串内容
         * 5. recipientId: 4字节长度 + UTF-8字符串内容
         * 6. messageType: 4字节整型（枚举序号，大端序）
         * 7. signalCiphertext: 4字节长度 + UTF-8字符串内容
         * 8. signalCiphertextType: 4字节整型（大端序）
         * 9. contentMetadata: 4字节长度 + JSON字符串内容
         * 10. attachments: 4字节数量 + 每个attachment的(4字节长度 + JSON字符串内容)
         */
        fun deserializeFromBinary(data: ByteArray): TransportMessage {
            return try {
                var offset = 0
                
                // 1. 读取版本信息（以null terminator结尾）
                var versionEndIndex = -1
                for (i in offset until data.size) {
                    if (data[i] == 0.toByte()) {
                        versionEndIndex = i
                        break
                    }
                }
                if (versionEndIndex == -1) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：版本信息缺少null terminator"
                    )
                }
                if (versionEndIndex == offset) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：版本信息为空"
                    )
                }
                val version = String(data, offset, versionEndIndex - offset, Charsets.UTF_8)
                offset = versionEndIndex + 1
                
                // 2. 读取messageId
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：messageId长度字段不完整"
                    )
                }
                val messageIdLength = bytesToInt(data, offset)
                offset += 4
                
                if (messageIdLength <= 0 || messageIdLength > 200) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：messageId长度无效 ($messageIdLength)"
                    )
                }
                if (offset + messageIdLength > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：messageId内容不完整"
                    )
                }
                val messageId = String(data, offset, messageIdLength, Charsets.UTF_8)
                offset += messageIdLength
                
                // 3. 读取timestamp
                if (offset + 8 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：timestamp字段不完整"
                    )
                }
                val timestamp = bytesToLong(data, offset)
                offset += 8
                
                // 验证timestamp合理性
                val currentTime = System.currentTimeMillis()
                if (timestamp <= 0 || timestamp > currentTime + 300000) { // 允许5分钟的时间偏差
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：timestamp无效 ($timestamp)"
                    )
                }
                
                // 4. 读取senderId
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：senderId长度字段不完整"
                    )
                }
                val senderIdLength = bytesToInt(data, offset)
                offset += 4
                
                if (senderIdLength <= 0 || senderIdLength > 200) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：senderId长度无效 ($senderIdLength)"
                    )
                }
                if (offset + senderIdLength > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：senderId内容不完整"
                    )
                }
                val senderId = String(data, offset, senderIdLength, Charsets.UTF_8)
                offset += senderIdLength
                
                // 5. 读取recipientId
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：recipientId长度字段不完整"
                    )
                }
                val recipientIdLength = bytesToInt(data, offset)
                offset += 4
                
                if (recipientIdLength <= 0 || recipientIdLength > 200) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：recipientId长度无效 ($recipientIdLength)"
                    )
                }
                if (offset + recipientIdLength > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：recipientId内容不完整"
                    )
                }
                val recipientId = String(data, offset, recipientIdLength, Charsets.UTF_8)
                offset += recipientIdLength
                
                // 6. 读取messageType
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：messageType字段不完整"
                    )
                }
                val messageTypeOrdinal = bytesToInt(data, offset)
                offset += 4
                
                if (messageTypeOrdinal < 0 || messageTypeOrdinal >= TransportMessageType.values().size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：messageType无效 ($messageTypeOrdinal)"
                    )
                }
                val messageType = TransportMessageType.values()[messageTypeOrdinal]
                
                // 7. 读取signalCiphertext
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：signalCiphertext长度字段不完整"
                    )
                }
                val ciphertextLength = bytesToInt(data, offset)
                offset += 4
                
                if (ciphertextLength <= 0 || ciphertextLength > 10 * 1024 * 1024) { // 最大10MB
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：signalCiphertext长度无效 ($ciphertextLength)"
                    )
                }
                if (offset + ciphertextLength > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：signalCiphertext内容不完整"
                    )
                }
                val signalCiphertext = String(data, offset, ciphertextLength, Charsets.UTF_8)
                offset += ciphertextLength
                
                // 8. 读取signalCiphertextType
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：signalCiphertextType字段不完整"
                    )
                }
                val signalCiphertextType = bytesToInt(data, offset)
                offset += 4
                
                // 9. 读取contentMetadata
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：contentMetadata长度字段不完整"
                    )
                }
                val metadataLength = bytesToInt(data, offset)
                offset += 4
                
                if (metadataLength <= 0 || metadataLength > 1024 * 1024) { // 最大1MB
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：contentMetadata长度无效 ($metadataLength)"
                    )
                }
                if (offset + metadataLength > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：contentMetadata内容不完整"
                    )
                }
                val metadataJson = String(data, offset, metadataLength, Charsets.UTF_8)
                offset += metadataLength
                
                val contentMetadata = try {
                    objectMapper.readValue(metadataJson, TransportContentMetadata::class.java)
                } catch (e: Exception) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：contentMetadata JSON解析失败",
                        e
                    )
                }
                
                // 10. 读取attachments
                if (offset + 4 > data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：attachments数量字段不完整"
                    )
                }
                val attachmentCount = bytesToInt(data, offset)
                offset += 4
                
                if (attachmentCount < 0 || attachmentCount > 100) { // 最大100个附件
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：attachments数量无效 ($attachmentCount)"
                    )
                }
                
                val attachments = mutableListOf<TransportAttachment>()
                for (i in 0 until attachmentCount) {
                    if (offset + 4 > data.size) {
                        throw TransportException(
                            TransportError.INVALID_FORMAT,
                            "二进制消息格式错误：attachment[$i]长度字段不完整"
                        )
                    }
                    
                    val attachmentLength = bytesToInt(data, offset)
                    offset += 4
                    
                    if (attachmentLength <= 0 || attachmentLength > 1024 * 1024) { // 最大1MB JSON
                        throw TransportException(
                            TransportError.INVALID_FORMAT,
                            "二进制消息格式错误：attachment[$i]长度无效 ($attachmentLength)"
                        )
                    }
                    if (offset + attachmentLength > data.size) {
                        throw TransportException(
                            TransportError.INVALID_FORMAT,
                            "二进制消息格式错误：attachment[$i]内容不完整"
                        )
                    }
                    
                    val attachmentJson = String(data, offset, attachmentLength, Charsets.UTF_8)
                    offset += attachmentLength
                    
                    val attachment = try {
                        objectMapper.readValue(attachmentJson, TransportAttachment::class.java)
                    } catch (e: Exception) {
                        throw TransportException(
                            TransportError.INVALID_FORMAT,
                            "二进制消息格式错误：attachment[$i] JSON解析失败",
                            e
                        )
                    }
                    attachments.add(attachment)
                }
                
                // 验证是否消费了所有数据（不允许有多余数据）
                if (offset != data.size) {
                    throw TransportException(
                        TransportError.INVALID_FORMAT,
                        "二进制消息格式错误：存在未消费的数据 (${data.size - offset} bytes)"
                    )
                }
                
                TransportMessage(
                    version = version,
                    messageId = messageId,
                    timestamp = timestamp,
                    senderId = senderId,
                    recipientId = recipientId,
                    messageType = messageType,
                    signalCiphertext = signalCiphertext,
                    signalCiphertextType = signalCiphertextType,
                    contentMetadata = contentMetadata,
                    attachments = attachments
                )
                
            } catch (e: TransportException) {
                throw e
            } catch (e: Exception) {
                throw TransportException(
                    TransportError.INVALID_FORMAT,
                    "二进制消息反序列化失败: ${e.message}",
                    e
                )
            }
        }
        
        /**
         * 4字节转Int（大端序）
         */
        private fun bytesToInt(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 24) or
                   ((data[offset + 1].toInt() and 0xFF) shl 16) or
                   ((data[offset + 2].toInt() and 0xFF) shl 8) or
                   (data[offset + 3].toInt() and 0xFF)
        }
        
        /**
         * 8字节转Long（大端序）
         */
        private fun bytesToLong(data: ByteArray, offset: Int): Long {
            var result = 0L
            for (i in 0 until 8) {
                result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
            }
            return result
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