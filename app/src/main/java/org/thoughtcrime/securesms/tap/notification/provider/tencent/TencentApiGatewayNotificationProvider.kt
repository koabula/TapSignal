package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * 腾讯云函数URL推送服务提供者实现
 * 使用函数URL WebSocket实现实时推送通知（替代API网关）
 */
class TencentApiGatewayNotificationProvider(
    private val context: Context,
    private val secretId: String,
    private val secretKey: String,
    private val defaultRegion: String = "ap-guangzhou"
) : NotificationProvider {

    companion object {
        private const val TAG = "TencentApiGatewayProvider"
        const val PROVIDER_TYPE = "tencent-api-gateway"
    }

    override val providerType: String = PROVIDER_TYPE

    private var wsClient: TencentWebSocketClient? = null
    private var deployer: TencentApiGatewayDeployer? = null
    private var webhookConfig: WebhookConfig? = null
    private var currentUserId: String? = null
    private var currentEndpoint: String? = null
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageListenerJob: Job? = null

    /**
     * 获取deployer实例（用于触发云函数）
     */
    fun getDeployer(): TencentApiGatewayDeployer? {
        return deployer
    }

    /**
     * 触发推送通知（客户端直接调用云函数）
     * 
     * @param remotePath 上传文件的路径
     * @param bucketName COS bucket名称
     * @return 是否成功触发
     */
    suspend fun triggerNotification(remotePath: String, bucketName: String): Boolean {
        return try {
            val deployerInstance = deployer
            if (deployerInstance == null) {
                Log.w(TAG, "Deployer not initialized, cannot trigger notification")
                return false
            }
            
            deployerInstance.invokeTriggerFunction(remotePath, bucketName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger notification", e)
            false
        }
    }

    override suspend fun deploy(apiKey: String, region: String): DeployResult {
        return try {
            val effectiveRegion = region.ifEmpty { defaultRegion }
            Log.i(TAG, "Starting deployment for region: $effectiveRegion")
            
            // P0修复：部署前先断开所有旧的WebSocket连接
            try {
                Log.i(TAG, "Cleaning up old WebSocket connections before deployment...")
                disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up old connections", e)
            }
            
            val tencentDeployer = TencentApiGatewayDeployer(context, secretId, secretKey, effectiveRegion)
            deployer = tencentDeployer

            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            val cosConfig = configManager.getProviderConfig("cos")
            val bucketName: String? = cosConfig?.get("bucketName") as? String
            
            // 设置用户bucket名称（用于配置存储）
            tencentDeployer.setUserBucketName(bucketName)
            
            val pushServiceInfo = tencentDeployer.deployPushService(userBucketName = bucketName)
            Log.d(TAG, "Push service deployed: ${pushServiceInfo.endpoint}")

            val webhookUrl = tencentDeployer.deployWebhook()
            Log.d(TAG, "Webhook deployed: $webhookUrl")

            // 部署触发器函数（客户端直调用作推送触发），不再配置COS事件通知
            val triggerInfo = tencentDeployer.setupEventTrigger(userBucketName = bucketName)
            Log.d(TAG, "Trigger function deployed (client-invocation mode): ${triggerInfo.triggerName}")

            val secret = generateNotifySecret()
            val userId = generateUserId()
            
            // 获取WebSocket函数URL（用于推送）
            val wsFunctionUrl = pushServiceInfo.endpoint.replace("wss://", "https://") // WebSocket函数URL的HTTP版本
            
            val envUpdateResult = tencentDeployer.updateWebhookEnvironment(
                secret, 
                userId, 
                bucketName,
                wsFunctionUrl // 传入WebSocket函数URL用于推送
            )
            if (!envUpdateResult) {
                Log.e(TAG, "Failed to update webhook environment variables")
                throw Exception("Failed to configure webhook environment variables")
            }
            
            webhookConfig = WebhookConfig(
                webhookUrl = webhookUrl,
                notifySecret = secret,
                userId = userId,
                version = "2.0"
            )

            // 将 userId 持久化到 pushServiceInfo.metadata，确保重启后一致
            val pushServiceInfoWithUser = pushServiceInfo.copy(
                metadata = pushServiceInfo.metadata + mapOf("userId" to userId)
            )

            val config = NotificationConfig(
                provider = PROVIDER_TYPE,
                webhookUrl = webhookUrl,
                notifySecret = secret,
                pushServiceInfo = pushServiceInfoWithUser,
                deployedAt = System.currentTimeMillis(),
                version = "2.0"
            )
            tencentDeployer.saveConfiguration(config)

            // 方案一：部署成功后同时保存到本地数据库，确保初始化时能找到配置
            try {
                val notificationConfigManager = NotificationConfigManager.getInstance(context)
                val saveLocalSuccess = notificationConfigManager.saveLocalConfig(config)
                if (saveLocalSuccess) {
                    Log.i(TAG, "配置已保存到本地数据库: provider=${config.provider}")
                } else {
                    Log.w(TAG, "配置保存到COS成功，但保存到本地数据库失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存配置到本地数据库异常", e)
                // 不影响部署结果，因为配置已保存到COS
            }

            Log.i(TAG, "Deployment completed successfully")
            DeployResult.success(webhookUrl, userId, pushServiceInfoWithUser)

        } catch (e: Exception) {
            Log.e(TAG, "Deployment failed", e)
            DeployResult.failure(e.message ?: "Deployment failed")
        }
    }

    override fun getWebhookConfig(): WebhookConfig {
        if (webhookConfig == null) {
            // 方案1修复：优先从NotificationConfigManager加载配置（不依赖deployer）
            val configManager = NotificationConfigManager.getInstance(context)
            val config = runBlocking {
                configManager.getLocalConfig()
            }
            
            webhookConfig = config?.let {
                val userId = it.pushServiceInfo.metadata["userId"] as? String
                    ?: generateUserId()
                WebhookConfig(
                    webhookUrl = it.webhookUrl,
                    notifySecret = it.notifySecret,
                    userId = userId,
                    version = it.version
                )
            }
            
            // Fallback：如果仍然没有配置，尝试从deployer加载（向后兼容）
            if (webhookConfig == null && deployer != null) {
                val deployerConfig = runBlocking {
                    deployer?.loadConfiguration()
                }
                webhookConfig = deployerConfig?.let {
                    val userId = it.pushServiceInfo.metadata["userId"] as? String
                        ?: generateUserId()
                    WebhookConfig(
                        webhookUrl = it.webhookUrl,
                        notifySecret = it.notifySecret,
                        userId = userId,
                        version = it.version
                    )
                }
            }
        }
        return webhookConfig ?: throw IllegalStateException("Webhook not configured. Please deploy first.")
    }

    override suspend fun connect(
        userId: String,
        onNotification: (NotificationMessage) -> Unit
    ): ConnectionResult {
        return try {
            currentUserId = userId
            notificationCallback = onNotification

            // 方案1修复：优先从NotificationConfigManager加载配置（不依赖deployer）
            val configManager = NotificationConfigManager.getInstance(context)
            var config = configManager.getLocalConfig()
            
            // Fallback：如果仍然没有配置，尝试从deployer加载（向后兼容）
            if (config == null) {
                val deployerInstance = deployer
                if (deployerInstance != null) {
                    config = deployerInstance.loadConfiguration()
                }
            }
            
            if (config == null) {
                return ConnectionResult.failure("Configuration not found. Please deploy first.")
            }

            val wsEndpoint = config.pushServiceInfo.endpoint
            if (!wsEndpoint.startsWith("wss://")) {
                return ConnectionResult.failure("Invalid WebSocket endpoint: $wsEndpoint")
            }
            
            // P0修复：总是先断开旧连接，确保只有一个活跃连接
            // P1修复：在断开前保存回调，断开后恢复
            val savedCallback = notificationCallback
            val savedUserId = currentUserId
            
            if (wsClient != null) {
                try {
                    val oldEndpoint = currentEndpoint ?: "unknown"
                    Log.i(TAG, "Disconnecting existing WebSocket connection: $oldEndpoint")
                    disconnect()
                    // 短暂延迟，确保旧连接完全清理
                    delay(500)
                    
                    // 恢复回调和userId，避免在重连过程中丢失
                    if (savedCallback != null && savedUserId != null) {
                        Log.d(TAG, "Restoring callback after disconnect")
                        notificationCallback = savedCallback
                        currentUserId = savedUserId
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting old endpoint", e)
                }
            }
            
            // 若端点变更，记录日志
            if (currentEndpoint != null && currentEndpoint != wsEndpoint) {
                Log.i(TAG, "WebSocket endpoint changed: ${currentEndpoint} -> $wsEndpoint")
            }
            currentEndpoint = wsEndpoint
            
            val client = TencentWebSocketClient(
                endpoint = wsEndpoint,
                userId = userId
            )
            wsClient = client

            Log.i(TAG, "Connecting to Tencent Function URL WebSocket...")
            Log.d(TAG, "WebSocket endpoint: ${wsEndpoint.substringBefore("?")}...")
            
            // P2修复：详细记录连接尝试信息
            try {
                val connectResult = client.connect()
                if (connectResult.isFailure) {
                    val exception = connectResult.exceptionOrNull()
                    val errorMessage = exception?.message ?: "Connection failed"
                    val errorType = exception?.javaClass?.simpleName ?: "Unknown"
                    
                    Log.e(TAG, "WebSocket connection failed", exception)
                    Log.e(TAG, "Error type: $errorType, Message: $errorMessage")
                    
                    // P2修复：提供更详细的错误信息
                    val detailedError = when {
                        errorMessage.contains("400") -> "$errorMessage (Bad Request - 请检查WebSocket是否已启用)"
                        errorMessage.contains("101") -> "$errorMessage (协议升级失败 - 请确认函数URL支持WebSocket)"
                        else -> errorMessage
                    }
                    
                    return ConnectionResult.failure(detailedError)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during WebSocket connection", e)
                return ConnectionResult.failure("Connection exception: ${e.message}")
            }

            startMessageListener(client)

            Log.i(TAG, "Connected successfully with user ID: $userId")
            ConnectionResult.success(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            ConnectionResult.failure(e.message ?: "Connection error")
        }
    }

    override suspend fun disconnect() {
        try {
            Log.i(TAG, "Disconnecting from Tencent Function URL WebSocket")
            
            // 取消消息监听（等待协程完成）
            messageListenerJob?.let { job ->
                Log.d(TAG, "Cancelling message listener job")
                job.cancel()
                try {
                    job.join() // 等待协程完全退出
                    Log.d(TAG, "Message listener job cancelled successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Error waiting for message listener job to finish", e)
                }
            }
            messageListenerJob = null
            
            // 断开并清理WebSocket客户端
            wsClient?.let { client ->
                try {
                    client.disconnect()
                    client.cleanup()
                } catch (e: Exception) {
                    Log.w(TAG, "Error during WebSocket cleanup", e)
                }
            }
            wsClient = null
            
            // P1修复：延后清理状态，确保消息监听器完全退出后再清空
            // 此时messageListenerJob已经cancel并join，不会再使用callback
            currentUserId = null
            notificationCallback = null
            currentEndpoint = null
            
            Log.i(TAG, "Disconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val client = wsClient
            if (client == null) {
                return HealthStatus.unhealthy("Not connected")
            }

            val startTime = System.currentTimeMillis()
            val heartbeatPayload = """{"type":"heartbeat","timestamp":${System.currentTimeMillis()}}"""

            val sendResult = client.sendMessage(heartbeatPayload)
            val latency = System.currentTimeMillis() - startTime

            if (sendResult.isSuccess) {
                HealthStatus.healthy(latency)
            } else {
                HealthStatus.unhealthy(sendResult.exceptionOrNull()?.message ?: "Health check failed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            HealthStatus.unhealthy(e.message ?: "Health check error")
        }
    }

    private fun startMessageListener(client: TencentWebSocketClient) {
        messageListenerJob?.cancel()
        messageListenerJob = scope.launch {
            // 保存回调到局部变量，避免在disconnect时被清空
            val callback = notificationCallback
            if (callback == null) {
                Log.e(TAG, "Notification callback is null, cannot start message listener")
                return@launch
            }
            
            val messageChannel = client.getMessageChannel()
            try {
                while (isActive) {
                    val message = messageChannel.receive()
                    Log.d(TAG, "Received notification: type=${message.type}, senderId=${message.senderId}")
                    
                    // P0修复：在调用回调前后添加日志
                    try {
                        Log.d(TAG, "Invoking notification callback for message: type=${message.type}")
                        callback.invoke(message)
                        Log.d(TAG, "Notification callback invoked successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking notification callback", e)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in message listener", e)
                }
            }
        }
    }

    private fun generateNotifySecret(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun generateUserId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    fun cleanup() {
        scope.cancel()
        runBlocking {
            disconnect()
        }
    }
}

