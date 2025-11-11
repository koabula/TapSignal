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

/**
 * 推送服务配置
 * 
 * 存储本地推送服务的全局配置信息。
 * 
 * @property provider 云服务提供商 (aws/tencent)
 * @property webhookUrl 本地Webhook接收URL，供其他用户的Lambda调用
 * @property notifySecret 本地签名密钥，用于验证incoming请求
 * @property pushServiceInfo 推送服务详细信息(Lambda函数、API Gateway等)
 * @property deployedAt 部署时间戳
 * @property version 配置版本
 * @property websocketManagementEndpoint 本地WebSocket管理端点(API Gateway Management API)，
 *           用于本地Webhook Handler调用PostToConnection推送消息。
 *           注意：这是本地配置，所有联系人共享，不需要存储在每个联系人配置中。
 *           格式: https://{api-id}.execute-api.{region}.amazonaws.com/{stage}
 */
data class NotificationConfig(
    val provider: String,
    val webhookUrl: String,
    val notifySecret: String,
    val pushServiceInfo: PushServiceInfo,
    val deployedAt: Long,
    val version: String = "1.0",
    val websocketManagementEndpoint: String? = null
) {
    fun validate(): Boolean {
        return provider.isNotEmpty() && 
               webhookUrl.isNotEmpty() && 
               notifySecret.isNotEmpty() && 
               pushServiceInfo.validate()
    }
}

/**
 * 联系人推送服务配置
 * 
 * 存储对方的推送服务配置信息，用于向对方发送消息通知。
 * 
 * @property contactId 联系人ID (ACI)
 * @property platform 平台类型 (aws/tencent)
 * @property webhookUrl 对方的Webhook接收URL，用于接收来自本地Lambda_A的推送通知
 * @property notifySecret 签名密钥，用于验证推送请求的真实性
 * @property userId 对方的用户ID，用于WebSocket连接查找
 * @property lastUpdated 配置最后更新时间
 * @property verified 配置是否已验证
 * @property websocketManagementEndpoint 【已废弃】对方的WebSocket管理端点。
 *           注意：此字段已废弃，不应再使用。WebSocket管理端点应存储在全局NotificationConfig中，
 *           因为它是本地配置，所有联系人共享，不需要为每个联系人重复存储。
 *           保留此字段仅为向后兼容，新代码应使用NotificationConfig.websocketManagementEndpoint。
 * @property gatewayRegion 【待移除】Gateway区域信息，仅用于Phase 3之前的兼容
 * @property gatewayProvider 【待移除】Gateway提供商，仅用于Phase 3之前的兼容
 * @property offlineBucket 【待移除】离线消息bucket，Phase 3将统一使用Gateway管理
 * @property presignDelegation 【待移除】预签名委托标志，Phase 3将移除
 * @property gatewayMetadata 【待移除】Gateway元数据，Phase 3将移除
 */
data class ContactNotificationConfig(
    val contactId: String,
    val platform: String,
    val webhookUrl: String,
    val notifySecret: String,
    val userId: String,
    val lastUpdated: Long,
    val verified: Boolean = false,
    @Deprecated("使用 NotificationConfig.websocketManagementEndpoint 替代")
    val websocketManagementEndpoint: String? = null,
    @Deprecated("Phase 3将移除此字段")
    val gatewayRegion: String? = null,
    @Deprecated("Phase 3将移除此字段")
    val gatewayProvider: String? = null,
    @Deprecated("Phase 3将移除此字段")
    val offlineBucket: String? = null,
    @Deprecated("Phase 3将移除此字段")
    val presignDelegation: Boolean = false,
    @Deprecated("Phase 3将移除此字段")
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

