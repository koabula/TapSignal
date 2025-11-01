package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * 腾讯云IoT Hub推送服务提供者实现
 */
class TencentIoTHubNotificationProvider(
    private val context: Context,
    private val secretId: String,
    private val secretKey: String,
    private val defaultRegion: String = "ap-guangzhou"
) : NotificationProvider {

    companion object {
        private const val TAG = "TencentIoTHubProvider"
        const val PROVIDER_TYPE = "tencent-iot"
        private const val TOPIC_PREFIX = "tap/notifications"
    }

    override val providerType: String = PROVIDER_TYPE

    private var iotClient: TencentIoTHubClient? = null
    private var deployer: TencentIoTHubDeployer? = null
    private var webhookConfig: WebhookConfig? = null
    private var currentUserId: String? = null
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageListenerJob: Job? = null

    override suspend fun deploy(apiKey: String, region: String): DeployResult {
        return try {
            val effectiveRegion = region.ifEmpty { defaultRegion }
            Log.i(TAG, "Starting deployment for region: $effectiveRegion")
            
            val tencentDeployer = TencentIoTHubDeployer(context, secretId, secretKey, effectiveRegion)
            deployer = tencentDeployer

            val webhookUrl = tencentDeployer.deployWebhook()
            Log.d(TAG, "Webhook deployed: $webhookUrl")

            val pushServiceInfo = tencentDeployer.deployPushService()
            Log.d(TAG, "Push service deployed: ${pushServiceInfo.endpoint}")

            val triggerInfo = tencentDeployer.setupEventTrigger()
            Log.d(TAG, "Event trigger configured: ${triggerInfo.triggerName}")

            val secret = generateNotifySecret()
            val topicId = pushServiceInfo.credentials["topicId"] 
                ?: throw Exception("Topic ID not found in push service info")
            
            val envUpdateResult = tencentDeployer.updateWebhookEnvironment(secret, topicId)
            if (!envUpdateResult) {
                Log.e(TAG, "Failed to update webhook environment variables")
                throw Exception("Failed to configure webhook environment variables")
            }
            
            webhookConfig = WebhookConfig(
                webhookUrl = webhookUrl,
                notifySecret = secret,
                topicId = topicId
            )

            val config = NotificationConfig(
                provider = PROVIDER_TYPE,
                webhookUrl = webhookUrl,
                notifySecret = secret,
                pushServiceInfo = pushServiceInfo,
                deployedAt = System.currentTimeMillis()
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
                val topicId = it.pushServiceInfo.credentials["topicId"]
                    ?: throw IllegalStateException("Topic ID not found in configuration")
                WebhookConfig(
                    webhookUrl = it.webhookUrl,
                    notifySecret = it.notifySecret,
                    topicId = topicId,
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

            val productId = config.pushServiceInfo.credentials["productId"]
                ?: return ConnectionResult.failure("Product ID not found in configuration")
            
            val deviceName = config.pushServiceInfo.credentials["deviceName"]
                ?: return ConnectionResult.failure("Device name not found in configuration")
            
            val deviceSecret = config.pushServiceInfo.credentials["deviceSecret"]
                ?: return ConnectionResult.failure("Device secret not found in configuration")

            val topicId = config.pushServiceInfo.credentials["topicId"]
                ?: return ConnectionResult.failure("Topic ID not found in configuration")
            
            val certificatePem = config.pushServiceInfo.credentials["certificatePem"]
            val privateKeyPem = config.pushServiceInfo.credentials["privateKeyPem"]
            
            val client = TencentIoTHubClient(
                context = context,
                endpoint = config.pushServiceInfo.endpoint,
                productId = productId,
                deviceName = deviceName,
                deviceSecret = deviceSecret,
                certificatePem = certificatePem,
                privateKeyPem = privateKeyPem
            )
            iotClient = client

            Log.i(TAG, "Connecting to Tencent IoT Hub...")
            val connectResult = client.connect()
            if (connectResult.isFailure) {
                return ConnectionResult.failure(connectResult.exceptionOrNull()?.message ?: "Connection failed")
            }

            val topic = getNotificationTopic(topicId)
            Log.i(TAG, "Subscribing to topic: $topic")
            val subscribeResult = client.subscribe(topic)
            if (subscribeResult.isFailure) {
                client.disconnect()
                return ConnectionResult.failure(subscribeResult.exceptionOrNull()?.message ?: "Subscribe failed")
            }

            startMessageListener(client)

            Log.i(TAG, "Connected successfully")
            ConnectionResult.success("$productId$deviceName")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            ConnectionResult.failure(e.message ?: "Connection error")
        }
    }

    override suspend fun disconnect() {
        try {
            Log.i(TAG, "Disconnecting from Tencent IoT Hub")
            
            messageListenerJob?.cancel()
            messageListenerJob = null
            
            iotClient?.disconnect()
            iotClient?.cleanup()
            iotClient = null
            
            currentUserId = null
            notificationCallback = null
            
            Log.i(TAG, "Disconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val client = iotClient
            if (client == null) {
                return HealthStatus.unhealthy("Not connected")
            }

            val startTime = System.currentTimeMillis()
            val testTopic = "$TOPIC_PREFIX/health/${UUID.randomUUID()}"
            val testPayload = """{"type":"heartbeat","timestamp":${System.currentTimeMillis()}}""".toByteArray()

            val publishResult = client.publish(testTopic, testPayload)
            val latency = System.currentTimeMillis() - startTime

            if (publishResult.isSuccess) {
                HealthStatus.healthy(latency)
            } else {
                HealthStatus.unhealthy(publishResult.exceptionOrNull()?.message ?: "Health check failed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            HealthStatus.unhealthy(e.message ?: "Health check error")
        }
    }

    private fun startMessageListener(client: TencentIoTHubClient) {
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

    private fun getNotificationTopic(topicId: String): String {
        return "$TOPIC_PREFIX/$topicId"
    }

    private fun generateNotifySecret(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    fun cleanup() {
        scope.cancel()
        runBlocking {
            disconnect()
        }
    }
}

