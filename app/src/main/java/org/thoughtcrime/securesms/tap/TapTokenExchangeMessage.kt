package org.thoughtcrime.securesms.tap

import org.thoughtcrime.securesms.util.JsonUtils
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Tap Token交换消息数据结构
 * 用于在Signal消息中传输Tap Token交换信息
 */
data class TapTokenExchangeMessage(
    @JsonProperty("senderAci")
    val senderAci: String,
    
    @JsonProperty("providerType") 
    val providerType: String,
    
    @JsonProperty("tokenData")
    val tokenData: Map<String, Any>,
    
    @JsonProperty("metadata")
    val metadata: Map<String, Any>,
    
    @JsonProperty("requestType")
    val requestType: String, // "OFFER" 或 "ACCEPT"
    
    @JsonProperty("version")
    val version: Int = 1,
    
    // 推送服务配置 (Phase 5新增)
    @JsonProperty("webhookConfig")
    val webhookConfig: Map<String, Any>? = null,

    // Gateway配置 (Phase 2新增)
    @JsonProperty("gatewayConfig")
    val gatewayConfig: Map<String, Any>? = null
) {
    
    companion object {
        const val REQUEST_TYPE_OFFER = "OFFER"
        const val REQUEST_TYPE_ACCEPT = "ACCEPT"
        const val REQUEST_TYPE_CONFIRM = "CONFIRM"
        const val REQUEST_TYPE_DISABLE = "DISABLE"
        
        // 群组相关消息类型
        const val REQUEST_TYPE_GROUP_OFFER = "GROUP_OFFER"      // 群组提议
        const val REQUEST_TYPE_GROUP_ACCEPT = "GROUP_ACCEPT"    // 接受提议
        const val REQUEST_TYPE_GROUP_ACTIVATE = "GROUP_ACTIVATE" // 全员激活通知
        const val REQUEST_TYPE_GROUP_DISABLE = "GROUP_DISABLE"  // 禁用v2 mode
        
        // 推送服务相关消息类型 (Phase 5新增)
        const val REQUEST_TYPE_WEBHOOK_UPDATE = "WEBHOOK_UPDATE"  // Webhook配置更新
        
        const val TAP_TOKEN_EXCHANGE_PREFIX = "TAP_TOKEN_EXCHANGE:"
        
        /**
         * 将Token交换消息编码为JSON字符串
         */
        fun encode(tokenExchange: TapTokenExchangeMessage): String {
            return TAP_TOKEN_EXCHANGE_PREFIX + JsonUtils.toJson(tokenExchange)
        }
        
        /**
         * 从JSON字符串解码Token交换消息
         */
        fun decode(message: String): TapTokenExchangeMessage? {
            return if (message.startsWith(TAP_TOKEN_EXCHANGE_PREFIX)) {
                try {
                    val jsonData = message.substring(TAP_TOKEN_EXCHANGE_PREFIX.length)
                    JsonUtils.fromJson(jsonData, TapTokenExchangeMessage::class.java)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
        
        /**
         * 检查消息是否为Token交换消息
         */
        fun isTapTokenExchangeMessage(message: String): Boolean {
            return message.startsWith(TAP_TOKEN_EXCHANGE_PREFIX)
        }
        
        /**
         * 创建包含webhook配置的Token交换消息
         * 
         * @param senderAci 发送者ACI
         * @param providerType Provider类型
         * @param tokenData Token数据
         * @param metadata 元数据
         * @param requestType 请求类型
         * @param webhookUrl Webhook URL
         * @param notifySecret 通知密钥
         * @param userId 用户ID
         * @return Token交换消息
         */
        fun createWithWebhook(
            senderAci: String,
            providerType: String,
            tokenData: Map<String, Any>,
            metadata: Map<String, Any>,
            requestType: String,
            webhookUrl: String?,
            notifySecret: String?,
            userId: String?,
            gatewayConfig: Map<String, Any>? = null
        ): TapTokenExchangeMessage {
            val webhookConfig = if (webhookUrl != null && notifySecret != null && userId != null) {
                mapOf(
                    "webhookUrl" to webhookUrl,
                    "notifySecret" to notifySecret,
                    "userId" to userId,
                    "version" to "2.0"
                )
            } else {
                null
            }
            
            return TapTokenExchangeMessage(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = tokenData,
                metadata = metadata,
                requestType = requestType,
                version = 1,
                webhookConfig = webhookConfig,
                gatewayConfig = gatewayConfig
            )
        }
        
        /**
         * 创建Webhook更新消息
         * 
         * @param senderAci 发送者ACI
         * @param providerType Provider类型
         * @param webhookUrl Webhook URL
         * @param notifySecret 通知密钥
         * @param userId 用户ID
         * @return Token交换消息
         */
        fun createWebhookUpdate(
            senderAci: String,
            providerType: String,
            webhookUrl: String,
            notifySecret: String,
            userId: String,
            gatewayConfig: Map<String, Any>? = null
        ): TapTokenExchangeMessage {
            return TapTokenExchangeMessage(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "updateType" to "webhook",
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = REQUEST_TYPE_WEBHOOK_UPDATE,
                version = 1,
                webhookConfig = mapOf(
                    "webhookUrl" to webhookUrl,
                    "notifySecret" to notifySecret,
                    "userId" to userId,
                    "version" to "2.0"
                ),
                gatewayConfig = gatewayConfig
            )
        }
    }
    
    /**
     * 提取Webhook配置
     * 
     * @return Webhook配置，如果不存在则返回null
     */
    fun extractWebhookConfig(): WebhookConfigData? {
        return if (webhookConfig != null) {
            try {
                val url = webhookConfig["webhookUrl"] as? String
                val secret = webhookConfig["notifySecret"] as? String
                val uid = webhookConfig["userId"] as? String
                val ver = webhookConfig["version"] as? String ?: "2.0"
                
                if (url != null && secret != null && uid != null) {
                    WebhookConfigData(url, secret, uid, ver)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 检查是否包含Webhook配置
     */
    fun hasWebhookConfig(): Boolean {
        return webhookConfig != null && extractWebhookConfig() != null
    }

    /**
     * 提取Gateway配置
     */
    fun extractGatewayConfig(): GatewayConfigData? {
        val config = gatewayConfig ?: return null
        return try {
            val endpoint = config["endpoint"] as? String ?: return null
            val region = config["region"] as? String ?: return null
            val provider = config["provider"] as? String ?: "unknown"
            val offlineBucket = config["offlineBucket"] as? String
            val presignDelegation = when (val raw = config["presignDelegation"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> false
            }
            val metadata = (config["metadata"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                if (k is String && (v is String || v is Number || v is Boolean)) {
                    k to v
                } else null
            }?.toMap() ?: emptyMap()

            GatewayConfigData(
                endpoint = endpoint,
                region = region,
                provider = provider,
                offlineBucket = offlineBucket,
                presignDelegation = presignDelegation,
                metadata = metadata
            )
        } catch (e: Exception) {
            null
        }
    }

    fun hasGatewayConfig(): Boolean = extractGatewayConfig() != null
}

/**
 * Webhook配置数据
 */
data class WebhookConfigData(
    val webhookUrl: String,
    val notifySecret: String,
    val userId: String,
    val version: String
) {
    fun validate(): Boolean {
        return webhookUrl.isNotEmpty() && 
               webhookUrl.startsWith("https://") &&
               notifySecret.isNotEmpty() &&
               userId.isNotEmpty()
    }
}

data class GatewayConfigData(
    val endpoint: String,
    val region: String,
    val provider: String,
    val offlineBucket: String? = null,
    val presignDelegation: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun validate(): Boolean {
        return endpoint.isNotEmpty() &&
               region.isNotEmpty() &&
               provider.isNotEmpty()
    }
}
