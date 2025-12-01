package org.thoughtcrime.securesms.tapv3.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "messageType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TapV3ControlMessage.HandshakeRequest::class, name = "handshake_request"),
    JsonSubTypes.Type(value = TapV3ControlMessage.HandshakeResponse::class, name = "handshake_response"),
    JsonSubTypes.Type(value = TapV3ControlMessage.HandshakeAck::class, name = "handshake_ack"),
    JsonSubTypes.Type(value = TapV3ControlMessage.KeyRotation::class, name = "key_rotation"),
    JsonSubTypes.Type(value = TapV3ControlMessage.ChannelClose::class, name = "channel_close")
)
sealed class TapV3ControlMessage {
    
    abstract val timestamp: Long
    
    data class HandshakeRequest(
        @JsonProperty("handshakeInfo")
        val handshakeInfo: TapV3HandshakeInfo,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage()
    
    data class HandshakeResponse(
        @JsonProperty("handshakeInfo")
        val handshakeInfo: TapV3HandshakeInfo,
        @JsonProperty("accepted")
        val accepted: Boolean,
        @JsonProperty("reason")
        val reason: String? = null,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage()
    
    data class HandshakeAck(
        @JsonProperty("success")
        val success: Boolean,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage()
    
    data class KeyRotation(
        @JsonProperty("newKPush")
        val newKPush: ByteArray,
        @JsonProperty("newKeyVersion")
        val newKeyVersion: Int,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KeyRotation
            if (!newKPush.contentEquals(other.newKPush)) return false
            if (newKeyVersion != other.newKeyVersion) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = newKPush.contentHashCode()
            result = 31 * result + newKeyVersion
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    data class ChannelClose(
        @JsonProperty("reason")
        val reason: String,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage()
    
    companion object {
        private val objectMapper = ObjectMapper()
        
        fun serialize(message: TapV3ControlMessage): ByteArray {
            return objectMapper.writeValueAsBytes(message)
        }
        
        fun deserialize(data: ByteArray): TapV3ControlMessage {
            return objectMapper.readValue(data)
        }
    }
}
