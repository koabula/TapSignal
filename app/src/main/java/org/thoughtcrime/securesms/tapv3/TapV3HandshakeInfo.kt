package org.thoughtcrime.securesms.tapv3

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class TapV3HandshakeInfo(
    @JsonProperty("version")
    val version: Int = TapV3Constants.VERSION,
    
    @JsonProperty("unifiedPushEndpoint")
    val unifiedPushEndpoint: String,
    
    @JsonProperty("kPush")
    val kPush: ByteArray,
    
    @JsonProperty("keyVersion")
    val keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL,
    
    @JsonProperty("ipfsGateways")
    val ipfsGateways: List<String>,
    
    @JsonProperty("capabilities")
    val capabilities: Set<String> = setOf("inline", "ipfs", "multi-attachment")
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TapV3HandshakeInfo
        if (version != other.version) return false
        if (unifiedPushEndpoint != other.unifiedPushEndpoint) return false
        if (!kPush.contentEquals(other.kPush)) return false
        if (keyVersion != other.keyVersion) return false
        if (ipfsGateways != other.ipfsGateways) return false
        if (capabilities != other.capabilities) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + unifiedPushEndpoint.hashCode()
        result = 31 * result + kPush.contentHashCode()
        result = 31 * result + keyVersion
        result = 31 * result + ipfsGateways.hashCode()
        result = 31 * result + capabilities.hashCode()
        return result
    }
    
    companion object {
        private val objectMapper = ObjectMapper()
        
        fun serialize(info: TapV3HandshakeInfo): ByteArray {
            return objectMapper.writeValueAsBytes(info)
        }
        
        fun deserialize(data: ByteArray): TapV3HandshakeInfo {
            return objectMapper.readValue(data)
        }
    }
}
