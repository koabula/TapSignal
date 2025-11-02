package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * 腾讯云API网关推送服务提供者实现
 * 使用WebSocket实现实时推送通知
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
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageListenerJob: Job? = null

    override suspend fun deploy(apiKey: String, region: String): DeployResult {
        return try {
            val effectiveRegion = region.ifEmpty { defaultRegion }
            Log.i(TAG, "Starting deployment for region: $effectiveRegion")
            
            val tencentDeployer = TencentApiGatewayDeployer(context, secretId, secretKey, effectiveRegion)
            deployer = tencentDeployer

            val pushServiceInfo = tencentDeployer.deployPushService()
            Log.d(TAG, "Push service deployed: ${pushServiceInfo.endpoint}")

            val webhookUrl = tencentDeployer.deployWebhook()
            Log.d(TAG, "Webhook deployed: $webhookUrl")

            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            val cosConfig = configManager.getProviderConfig("cos")
            val bucketName = cosConfig?["bucketName"] as? String

            val triggerInfo = tencentDeployer.setupEventTrigger(userBucketName = bucketName)
            Log.d(TAG, "Event trigger configured: ${triggerInfo.triggerName}")

            if (bucketName != null && triggerInfo.triggerArn != null) {
                Log.i(TAG, "Configuring COS event notification for bucket: $bucketName")
                val cosEventConfigured = tencentDeployer.configureCosEventNotification(
                    userBucketName = bucketName,
                    userBucketRegion = effectiveRegion,
                    triggerFunctionName = triggerInfo.triggerName,
                    filterPrefix = "v2-channels/"
                )
                if (cosEventConfigured) {
                    Log.i(TAG, "COS event notification configured successfully")
                } else {
                    Log.w(TAG, "Failed to configure COS event notification - manual setup may be required")
                }
            } else {
                Log.w(TAG, "Bucket name or trigger ARN missing, COS event not configured automatically")
            }

            val secret = generateNotifySecret()
            val apiGatewayId = pushServiceInfo.credentials["apiGatewayId"] 
                ?: throw Exception("API Gateway ID not found in push service info")
            
            val envUpdateResult = tencentDeployer.updateWebhookEnvironment(secret)
            if (!envUpdateResult) {
                Log.e(TAG, "Failed to update webhook environment variables")
                throw Exception("Failed to configure webhook environment variables")
            }
            
            val userId = generateUserId()
            webhookConfig = WebhookConfig(
                webhookUrl = webhookUrl,
                notifySecret = secret,
                userId = userId,
                version = "2.0"
            )

            val config = NotificationConfig(
                provider = PROVIDER_TYPE,
                webhookUrl = webhookUrl,
                notifySecret = secret,
                pushServiceInfo = pushServiceInfo,
                deployedAt = System.currentTimeMillis(),
                version = "2.0"
            )
            tencentDeployer.saveConfiguration(config)

            Log.i(TAG, "Deployment completed successfully")
            DeployResult.success(webhookUrl, pushServiceInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Deployment failed", e)
            DeployResult.failure(e.message ?: "Deployment failed")
        }
    }

    override fun getWebhookConfig(): WebhookConfig {
        if (webhookConfig == null) {
            val config = runBlocking {
                deployer?.loadConfiguration()
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

            val config = deployer?.loadConfiguration()
                ?: return ConnectionResult.failure("Configuration not found. Please deploy first.")

            val wsEndpoint = config.pushServiceInfo.endpoint
            if (!wsEndpoint.startsWith("wss://")) {
                return ConnectionResult.failure("Invalid WebSocket endpoint: $wsEndpoint")
            }
            
            val client = TencentWebSocketClient(
                endpoint = wsEndpoint,
                userId = userId
            )
            wsClient = client

            Log.i(TAG, "Connecting to Tencent API Gateway WebSocket...")
            val connectResult = client.connect()
            if (connectResult.isFailure) {
                return ConnectionResult.failure(connectResult.exceptionOrNull()?.message ?: "Connection failed")
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
            Log.i(TAG, "Disconnecting from Tencent API Gateway")
            
            messageListenerJob?.cancel()
            messageListenerJob = null
            
            wsClient?.disconnect()
            wsClient?.cleanup()
            wsClient = null
            
            currentUserId = null
            notificationCallback = null
            
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
            val messageChannel = client.getMessageChannel()
            try {
                while (isActive) {
                    val message = messageChannel.receive()
                    Log.d(TAG, "Received notification: type=${message.type}, senderId=${message.senderId}")
                    notificationCallback?.invoke(message)
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

