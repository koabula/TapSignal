package org.thoughtcrime.securesms.tapv3

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TapV3Payload.Inline::class, name = "inline"),
    JsonSubTypes.Type(value = TapV3Payload.IpfsRefs::class, name = "ipfs"),
    JsonSubTypes.Type(value = TapV3Payload.GroupMessage::class, name = "group")
)
sealed class TapV3Payload {
    
    data class Inline(
        @JsonProperty("encrypted")
        val encrypted: ByteArray
    ) : TapV3Payload() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Inline
            return encrypted.contentEquals(other.encrypted)
        }

        override fun hashCode(): Int {
            return encrypted.contentHashCode()
        }
    }
    
    data class IpfsRefs(
        @JsonProperty("messageCid")
        val messageCid: String?,
        @JsonProperty("attachments")
        val attachments: List<AttachmentRef>
    ) : TapV3Payload()
    
    data class GroupMessage(
        @JsonProperty("groupId") val groupId: String,
        @JsonProperty("senderAci") val senderAci: String,
        @JsonProperty("encryptedContent") val encryptedContent: ByteArray?,
        @JsonProperty("contentCid") val contentCid: String?,
        @JsonProperty("attachments") val attachments: List<AttachmentRef>,
        @JsonProperty("encryptionKey") val encryptionKey: ByteArray,
        @JsonProperty("timestamp") val timestamp: Long
    ) : TapV3Payload() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GroupMessage
            if (groupId != other.groupId) return false
            if (senderAci != other.senderAci) return false
            if (!encryptedContent.contentEquals(other.encryptedContent)) return false
            if (contentCid != other.contentCid) return false
            if (attachments != other.attachments) return false
            if (!encryptionKey.contentEquals(other.encryptionKey)) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + senderAci.hashCode()
            result = 31 * result + (encryptedContent?.contentHashCode() ?: 0)
            result = 31 * result + (contentCid?.hashCode() ?: 0)
            result = 31 * result + attachments.hashCode()
            result = 31 * result + encryptionKey.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    data class AttachmentRef(
        @JsonProperty("cid")
        val cid: String,
        @JsonProperty("size")
        val size: Long,
        @JsonProperty("mimeType")
        val mimeType: String?
    )
    
    companion object {
        private val objectMapper = ObjectMapper()
        
        fun serialize(payload: TapV3Payload): ByteArray {
            return objectMapper.writeValueAsBytes(payload)
        }
        
        fun deserialize(data: ByteArray): TapV3Payload {
            return objectMapper.readValue(data)
        }
    }
}
