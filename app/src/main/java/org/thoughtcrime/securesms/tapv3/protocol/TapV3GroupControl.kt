package org.thoughtcrime.securesms.tapv3.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TapV3GroupControl.Offer::class, name = "offer"),
    JsonSubTypes.Type(value = TapV3GroupControl.Accept::class, name = "accept"),
    JsonSubTypes.Type(value = TapV3GroupControl.Disable::class, name = "disable")
)
sealed class TapV3GroupControl {

    data class Offer(
        @JsonProperty("version")
        val version: Int = 3,
        @JsonProperty("handshakeInfo")
        val handshakeInfo: TapV3HandshakeInfo
    ) : TapV3GroupControl()

    data class Accept(
        @JsonProperty("handshakeInfo")
        val handshakeInfo: TapV3HandshakeInfo
    ) : TapV3GroupControl()

    data class Disable(
        @JsonProperty("reason")
        val reason: String?
    ) : TapV3GroupControl()

    companion object {
        private val objectMapper: ObjectMapper by lazy {
            ObjectMapper().registerKotlinModule()
        }

        fun serialize(message: TapV3GroupControl): ByteArray {
            return objectMapper.writeValueAsBytes(message)
        }

        fun deserialize(data: ByteArray): TapV3GroupControl {
            return objectMapper.readValue(data, TapV3GroupControl::class.java)
        }
    }
}
