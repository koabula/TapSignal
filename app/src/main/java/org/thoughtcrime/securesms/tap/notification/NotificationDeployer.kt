package org.thoughtcrime.securesms.tap.notification

/**
 * 推送服务部署接口
 */
interface NotificationDeployer {
    
    suspend fun deployWebhook(): String
    
    suspend fun deployPushService(userBucketName: String? = null): PushServiceInfo
    
    suspend fun setupEventTrigger(userBucketName: String? = null): TriggerInfo
    
    suspend fun testDeployment(): TestResult
    
    suspend fun saveConfiguration(config: NotificationConfig)
    
    suspend fun loadConfiguration(): NotificationConfig?
    
    suspend fun deleteFunction(identifier: String, name: String): Boolean
    
    suspend fun deleteRole(identifier: String, name: String): Boolean
    
    suspend fun deleteApiGateway(identifier: String, name: String): Boolean
    
    suspend fun deleteDynamoDBTable(identifier: String, name: String): Boolean
    
    fun cleanup()
}

data class TriggerInfo(
    val triggerName: String,
    val triggerArn: String? = null,
    val targetFunction: String,
    val configured: Boolean,
    val filterPrefix: String? = null
) {
    fun validate(): Boolean {
        return triggerName.isNotEmpty() && targetFunction.isNotEmpty() && configured
    }
}

data class TestResult(
    val success: Boolean,
    val webhookReachable: Boolean = false,
    val pushServiceConnected: Boolean = false,
    val eventTriggerWorking: Boolean = false,
    val errorMessage: String? = null,
    val details: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(
            webhookReachable: Boolean,
            pushServiceConnected: Boolean,
            eventTriggerWorking: Boolean
        ): TestResult {
            return TestResult(
                success = webhookReachable && pushServiceConnected && eventTriggerWorking,
                webhookReachable = webhookReachable,
                pushServiceConnected = pushServiceConnected,
                eventTriggerWorking = eventTriggerWorking
            )
        }
        
        fun failure(errorMessage: String): TestResult {
            return TestResult(
                success = false,
                errorMessage = errorMessage
            )
        }
    }
}

data class NotificationConfig(
    val provider: String,
    val webhookUrl: String,
    val notifySecret: String,
    val pushServiceInfo: PushServiceInfo,
    val deployedAt: Long,
    val version: String = "1.0"
) {
    fun validate(): Boolean {
        return provider.isNotEmpty() && 
               webhookUrl.isNotEmpty() && 
               notifySecret.isNotEmpty() && 
               pushServiceInfo.validate()
    }
}

data class ContactNotificationConfig(
    val contactId: String,
    val platform: String,
    val webhookUrl: String,
    val notifySecret: String,
    val userId: String,
    val lastUpdated: Long,
    val verified: Boolean = false,
    val gatewayEndpoint: String? = null,
    val gatewayRegion: String? = null,
    val gatewayProvider: String? = null,
    val offlineBucket: String? = null,
    val presignDelegation: Boolean = false,
    val gatewayMetadata: Map<String, Any> = emptyMap()
) {
    fun validate(): Boolean {
        return contactId.isNotEmpty() && 
               platform.isNotEmpty() && 
               webhookUrl.isNotEmpty() && 
               notifySecret.isNotEmpty() &&
               userId.isNotEmpty()
    }
}

data class NotificationDeployment(
    val webhookFunctionName: String,
    val webhookFunctionArn: String,
    val triggerFunctionName: String,
    val triggerFunctionArn: String,
    val eventTriggerConfigured: Boolean,
    val deployedComponents: List<String>,
    val deploymentStatus: DeploymentStatus
) {
    fun isFullyDeployed(): Boolean {
        return eventTriggerConfigured && deploymentStatus == DeploymentStatus.DEPLOYED
    }
}

enum class DeploymentStatus {
    NOT_DEPLOYED,
    DEPLOYING,
    DEPLOYED,
    FAILED,
    NEEDS_UPDATE
}

