package org.thoughtcrime.securesms.tapv3.protocol

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
    
    @JsonDeserialize(using = HandshakeRequestDeserializer::class)
    data class HandshakeRequest(
        @JsonProperty("handshakeInfo")
        val handshakeInfo: TapV3HandshakeInfo,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage()
    
    @JsonDeserialize(using = HandshakeResponseDeserializer::class)
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
    
    @JsonDeserialize(using = KeyRotationDeserializer::class)
    data class KeyRotation(
        @get:JsonIgnore
        val newKPush: ByteArray,
        @JsonProperty("newKeyVersion")
        val newKeyVersion: Int,
        @JsonProperty("timestamp")
        override val timestamp: Long = System.currentTimeMillis()
    ) : TapV3ControlMessage() {
        
        @get:JsonProperty("newKPush")
        val newKPushBase64: String
            get() = Base64.encodeToString(newKPush, Base64.NO_WRAP)
        
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
        private val objectMapper: ObjectMapper by lazy {
            ObjectMapper().registerKotlinModule()
        }
        
        fun serialize(message: TapV3ControlMessage): ByteArray {
            return objectMapper.writeValueAsBytes(message)
        }
        
        fun deserialize(data: ByteArray): TapV3ControlMessage {
            return objectMapper.readValue(data)
        }
    }
}

/**
 * HandshakeRequest 自定义反序列化器,处理 TapV3HandshakeInfo 中 kPush 的 base64 转换
 */
class HandshakeRequestDeserializer : JsonDeserializer<TapV3ControlMessage.HandshakeRequest>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TapV3ControlMessage.HandshakeRequest {
        val node = p.codec.readTree<JsonNode>(p)
        val handshakeInfoNode = node.get("handshakeInfo")
        
        val handshakeInfo = TapV3HandshakeInfo.fromJson(
            version = handshakeInfoNode.get("version").asInt(),
            unifiedPushEndpoint = handshakeInfoNode.get("unifiedPushEndpoint").asText(),
            kPushBase64 = handshakeInfoNode.get("kPush").asText(),
            keyVersion = handshakeInfoNode.get("keyVersion").asInt(),
            ipfsGateways = handshakeInfoNode.get("ipfsGateways").map { it.asText() },
            capabilities = handshakeInfoNode.get("capabilities").map { it.asText() }.toSet()
        )
        
        val timestamp = node.get("timestamp")?.asLong() ?: System.currentTimeMillis()
        
        return TapV3ControlMessage.HandshakeRequest(
            handshakeInfo = handshakeInfo,
            timestamp = timestamp
        )
    }
}

/**
 * HandshakeResponse 自定义反序列化器
 */
class HandshakeResponseDeserializer : JsonDeserializer<TapV3ControlMessage.HandshakeResponse>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TapV3ControlMessage.HandshakeResponse {
        val node = p.codec.readTree<JsonNode>(p)
        val handshakeInfoNode = node.get("handshakeInfo")
        
        val handshakeInfo = TapV3HandshakeInfo.fromJson(
            version = handshakeInfoNode.get("version").asInt(),
            unifiedPushEndpoint = handshakeInfoNode.get("unifiedPushEndpoint").asText(),
            kPushBase64 = handshakeInfoNode.get("kPush").asText(),
            keyVersion = handshakeInfoNode.get("keyVersion").asInt(),
            ipfsGateways = handshakeInfoNode.get("ipfsGateways").map { it.asText() },
            capabilities = handshakeInfoNode.get("capabilities").map { it.asText() }.toSet()
        )
        
        val accepted = node.get("accepted").asBoolean()
        val reason = node.get("reason")?.asText()
        val timestamp = node.get("timestamp")?.asLong() ?: System.currentTimeMillis()
        
        return TapV3ControlMessage.HandshakeResponse(
            handshakeInfo = handshakeInfo,
            accepted = accepted,
            reason = reason,
            timestamp = timestamp
        )
    }
}

/**
 * KeyRotation 自定义反序列化器,处理 newKPush 的 base64 转换
 */
class KeyRotationDeserializer : JsonDeserializer<TapV3ControlMessage.KeyRotation>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TapV3ControlMessage.KeyRotation {
        val node = p.codec.readTree<JsonNode>(p)
        
        val newKPushBase64 = node.get("newKPush").asText()
        val newKPush = Base64.decode(newKPushBase64, Base64.NO_WRAP)
        val newKeyVersion = node.get("newKeyVersion").asInt()
        val timestamp = node.get("timestamp")?.asLong() ?: System.currentTimeMillis()
        
        return TapV3ControlMessage.KeyRotation(
            newKPush = newKPush,
            newKeyVersion = newKeyVersion,
            timestamp = timestamp
        )
    }
}
