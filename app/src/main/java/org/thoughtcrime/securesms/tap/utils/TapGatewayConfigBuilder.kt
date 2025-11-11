package org.thoughtcrime.securesms.tap.utils

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConfig

/**
 * 构建Gateway配置映射，供TapTokenExchangeMessage携带。
 */
object TapGatewayConfigBuilder {

    private val TAG = Log.tag(TapGatewayConfigBuilder::class.java)

    fun build(notificationConfig: NotificationConfig?): Map<String, Any>? {
        val config = notificationConfig ?: return null
        return try {
            if (!config.validate()) {
                Log.w(TAG, "NotificationConfig未通过验证，无法构建gatewayConfig")
                return null
            }

            val endpoint = config.pushServiceInfo.endpoint.takeIf { it.isNotBlank() } ?: return null
            val region = config.pushServiceInfo.region.takeIf { it.isNotBlank() } ?: return null
            val provider = config.provider.takeIf { it.isNotBlank() } ?: "unknown"

            val metadata = config.pushServiceInfo.metadata
            val offlineBucket = (metadata["offlineBucket"] as? String)
                ?: (metadata["connectionsBucket"] as? String)
                ?: (metadata["userBucketName"] as? String)
            val presignDelegation = when (val raw = metadata["presignDelegation"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> false
            }

            val sanitizedMetadata = metadata.filterValues { value ->
                value is String || value is Number || value is Boolean
            }

            buildMap<String, Any> {
                put("endpoint", endpoint)
                put("region", region)
                put("provider", provider)
                offlineBucket?.let { put("offlineBucket", it) }
                if (presignDelegation) {
                    put("presignDelegation", true)
                }
                if (sanitizedMetadata.isNotEmpty()) {
                    put("metadata", sanitizedMetadata)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "构建gatewayConfig失败", e)
            null
        }
    }
}
