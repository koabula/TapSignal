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
    val version: Int = 1
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
    }
} 