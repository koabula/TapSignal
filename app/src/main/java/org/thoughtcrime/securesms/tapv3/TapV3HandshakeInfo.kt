package org.thoughtcrime.securesms.tapv3

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager

data class TapV3HandshakeInfo(
    @JsonProperty("version")
    val version: Int = TapV3Constants.VERSION,
    
    @JsonProperty("unifiedPushEndpoint")
    val unifiedPushEndpoint: String,
    
    @get:JsonIgnore
    val kPush: ByteArray,
    
    @JsonProperty("keyVersion")
    val keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL,
    
    @JsonProperty("ipfsGateways")
    val ipfsGateways: List<String>,
    
    @JsonProperty("capabilities")
    val capabilities: Set<String> = setOf("inline", "ipfs", "multi-attachment")
) {
    
    @get:JsonProperty("kPush")
    val kPushBase64: String
        get() = Base64.encodeToString(kPush, Base64.NO_WRAP)
    
    companion object {
        private val objectMapper: ObjectMapper by lazy {
            ObjectMapper().registerKotlinModule()
        }
        
        fun create(context: Context): TapV3HandshakeInfo {
            val kPushManager = KPushManager.getInstance(context)
            val pushEndpointManager = PushEndpointManager.getInstance(context)

            val myKPush = kPushManager.getOrCreateMyKPush()
            val myKPushVersion = kPushManager.getMyKPushVersion()
            val myEndpoint = pushEndpointManager.getMyEndpoint() ?: ""
            
            // TODO: Get configured gateways dynamically
            val gateways = listOf("https://gateway.pinata.cloud/ipfs/", "https://w3s.link/ipfs/") 

            return TapV3HandshakeInfo(
                unifiedPushEndpoint = myEndpoint,
                kPush = myKPush,
                keyVersion = myKPushVersion,
                ipfsGateways = gateways
            )
        }
        
        fun serialize(info: TapV3HandshakeInfo): ByteArray {
            return objectMapper.writeValueAsBytes(info)
        }
        
        fun deserialize(data: ByteArray): TapV3HandshakeInfo {
            val node = objectMapper.readTree(data)
            return fromJson(
                version = node.get("version").asInt(),
                unifiedPushEndpoint = node.get("unifiedPushEndpoint").asText(),
                kPushBase64 = node.get("kPush").asText(),
                keyVersion = node.get("keyVersion").asInt(),
                ipfsGateways = node.get("ipfsGateways").map { it.asText() },
                capabilities = node.get("capabilities").map { it.asText() }.toSet()
            )
        }
        
        fun fromJson(
            version: Int,
            unifiedPushEndpoint: String,
            kPushBase64: String,
            keyVersion: Int,
            ipfsGateways: List<String>,
            capabilities: Set<String>
        ): TapV3HandshakeInfo {
            return TapV3HandshakeInfo(
                version = version,
                unifiedPushEndpoint = unifiedPushEndpoint,
                kPush = Base64.decode(kPushBase64, Base64.NO_WRAP),
                keyVersion = keyVersion,
                ipfsGateways = ipfsGateways,
                capabilities = capabilities
            )
        }
    }
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
    
}
