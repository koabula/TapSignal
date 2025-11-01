package org.thoughtcrime.securesms.tap.notification.provider.aws

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * AWS IoT Core推送服务提供者实现
 */
class AwsIoTNotificationProvider(
    private val context: Context,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val defaultRegion: String = "us-east-1"
) : NotificationProvider {

    companion object {
        private const val TAG = "AwsIoTNotificationProvider"
        const val PROVIDER_TYPE = "aws-iot"
        private const val TOPIC_PREFIX = "tap/notifications"
    }

    override val providerType: String = PROVIDER_TYPE

    private var iotClient: AwsIoTClient? = null
    private var deployer: AwsIoTDeployer? = null
    private var webhookConfig: WebhookConfig? = null
    private var currentUserId: String? = null
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageListenerJob: Job? = null

    override suspend fun deploy(apiKey: String, region: String): DeployResult {
        return try {
            val effectiveRegion = region.ifEmpty { defaultRegion }
            Log.i(TAG, "Starting deployment for region: $effectiveRegion")
            
            val awsDeployer = AwsIoTDeployer(context, accessKeyId, secretAccessKey, effectiveRegion)
            deployer = awsDeployer

            val webhookUrl = awsDeployer.deployWebhook()
            Log.d(TAG, "Webhook deployed: $webhookUrl")

            val pushServiceInfo = awsDeployer.deployPushService()
            Log.d(TAG, "Push service deployed: ${pushServiceInfo.endpoint}")

            val triggerInfo = awsDeployer.setupEventTrigger()
            Log.d(TAG, "Event trigger configured: ${triggerInfo.triggerName}")

            val secret = generateNotifySecret()
            val topicId = pushServiceInfo.credentials["topicId"] 
                ?: throw Exception("Topic ID not found in push service info")
            
            val envUpdateResult = awsDeployer.updateWebhookEnvironment(secret, topicId)
            if (!envUpdateResult) {
                Log.w(TAG, "Failed to update webhook environment variables")
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
            awsDeployer.saveConfiguration(config)

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

            val clientId = "tap-client-${userId.take(8)}-${UUID.randomUUID().toString().take(8)}"
            
            val certificatePem = config.pushServiceInfo.credentials["certificatePem"]
                ?: return ConnectionResult.failure("Certificate not found in configuration")
            
            val privateKeyPem = config.pushServiceInfo.credentials["privateKeyPem"]
                ?: return ConnectionResult.failure("Private key not found in configuration")

            val topicId = config.pushServiceInfo.credentials["topicId"]
                ?: return ConnectionResult.failure("Topic ID not found in configuration")
            
            val client = AwsIoTClient(
                context = context,
                endpoint = config.pushServiceInfo.endpoint,
                region = config.pushServiceInfo.region,
                clientId = clientId,
                certificatePem = certificatePem,
                privateKeyPem = privateKeyPem
            )
            iotClient = client

            Log.i(TAG, "Connecting to AWS IoT Core...")
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

            Log.i(TAG, "Connected successfully with client ID: $clientId")
            ConnectionResult.success(clientId)

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            ConnectionResult.failure(e.message ?: "Connection error")
        }
    }

    override suspend fun disconnect() {
        try {
            Log.i(TAG, "Disconnecting from AWS IoT Core")
            
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

    private fun startMessageListener(client: AwsIoTClient) {
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

