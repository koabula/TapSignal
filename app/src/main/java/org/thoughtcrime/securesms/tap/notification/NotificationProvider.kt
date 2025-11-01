package org.thoughtcrime.securesms.tap.notification

/**
 * 推送服务提供者接口
 */
interface NotificationProvider {
    
    val providerType: String
    
    suspend fun deploy(apiKey: String, region: String): DeployResult
    
    fun getWebhookConfig(): WebhookConfig
    
    suspend fun connect(
        userId: String,
        onNotification: (NotificationMessage) -> Unit
    ): ConnectionResult
    
    suspend fun disconnect()
    
    suspend fun healthCheck(): HealthStatus
}

data class DeployResult(
    val success: Boolean,
    val webhookUrl: String? = null,
    val pushServiceInfo: PushServiceInfo? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun success(webhookUrl: String, pushServiceInfo: PushServiceInfo): DeployResult {
            return DeployResult(
                success = true,
                webhookUrl = webhookUrl,
                pushServiceInfo = pushServiceInfo
            )
        }
        
        fun failure(errorMessage: String): DeployResult {
            return DeployResult(
                success = false,
                errorMessage = errorMessage
            )
        }
    }
}

data class PushServiceInfo(
    val endpoint: String,
    val region: String,
    val credentials: Map<String, String>,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun validate(): Boolean {
        return endpoint.isNotEmpty() && region.isNotEmpty()
    }
}

data class ConnectionResult(
    val success: Boolean,
    val connectionId: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun success(connectionId: String): ConnectionResult {
            return ConnectionResult(
                success = true,
                connectionId = connectionId
            )
        }
        
        fun failure(errorMessage: String): ConnectionResult {
            return ConnectionResult(
                success = false,
                errorMessage = errorMessage
            )
        }
    }
}

data class HealthStatus(
    val isHealthy: Boolean,
    val latencyMs: Long = 0L,
    val lastCheckTime: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val details: Map<String, Any> = emptyMap()
) {
    companion object {
        fun healthy(latencyMs: Long = 0L): HealthStatus {
            return HealthStatus(
                isHealthy = true,
                latencyMs = latencyMs
            )
        }
        
        fun unhealthy(errorMessage: String): HealthStatus {
            return HealthStatus(
                isHealthy = false,
                errorMessage = errorMessage
            )
        }
    }
    
    fun isStale(maxAgeMs: Long): Boolean {
        return System.currentTimeMillis() - lastCheckTime > maxAgeMs
    }
}

