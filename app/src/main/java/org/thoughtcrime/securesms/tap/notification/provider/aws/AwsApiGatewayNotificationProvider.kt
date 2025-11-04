package org.thoughtcrime.securesms.tap.notification.provider.aws

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * AWS API Gateway推送服务提供者实现
 * 使用WebSocket实现实时推送通知
 */
class AwsApiGatewayNotificationProvider(
    private val context: Context,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val defaultRegion: String = "us-east-1"
) : NotificationProvider {

    companion object {
        private const val TAG = "AwsApiGatewayProvider"
        const val PROVIDER_TYPE = "aws-api-gateway"
    }

    override val providerType: String = PROVIDER_TYPE

    private var wsClient: AwsWebSocketClient? = null
    private var deployer: AwsApiGatewayDeployer? = null
    private var webhookConfig: WebhookConfig? = null
    private var currentUserId: String? = null
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var messageListenerJob: Job? = null
    private var currentEndpoint: String? = null

    /**
     * 获取deployer实例（用于触发云函数）
     */
    fun getDeployer(): AwsApiGatewayDeployer? {
        return deployer
    }

    /**
     * 触发推送通知（客户端直接调用云函数）
     * 
     * @param remotePath 上传文件的路径
     * @param bucketName S3 bucket名称
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
            
            val awsDeployer = AwsApiGatewayDeployer(context, accessKeyId, secretAccessKey, effectiveRegion)
            deployer = awsDeployer

            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            val cosConfig = configManager.getProviderConfig("cos")
            val bucketName: String? = cosConfig?.get("bucketName") as? String
            
            awsDeployer.setUserBucketName(bucketName)
            
            val pushServiceInfo = awsDeployer.deployPushService(userBucketName = bucketName)
            Log.d(TAG, "Push service deployed: ${pushServiceInfo.endpoint}")

            val webhookUrl = awsDeployer.deployWebhook()
            Log.d(TAG, "Webhook deployed: $webhookUrl")

            val triggerInfo = awsDeployer.setupEventTrigger(userBucketName = bucketName)
            Log.d(TAG, "Event trigger configured: ${triggerInfo.triggerName}")

            // 方案二：客户端直接触发云函数，不需要配置S3事件触发器
            // S3事件触发器配置已标记为可选，如果配置失败也不影响功能
            if (bucketName != null && triggerInfo.triggerArn != null) {
                Log.i(TAG, "Attempting to configure S3 event notification (optional, client-triggered mode is primary)")
                val s3EventConfigured = awsDeployer.configureS3EventNotification(
                    userBucketName = bucketName,
                    triggerFunctionArn = triggerInfo.triggerArn!!,
                    filterPrefix = "v2-channels/"
                )
                if (s3EventConfigured) {
                    Log.i(TAG, "S3 event notification configured successfully (fallback mode)")
                } else {
                    Log.w(TAG, "S3 event notification configuration failed - using client-triggered mode (normal)")
                }
            } else {
                Log.d(TAG, "Using client-triggered mode for notifications (primary method)")
            }

            val secret = generateNotifySecret()
            val userId = generateUserId()
            val apiGatewayId = pushServiceInfo.credentials["apiGatewayId"] 
                ?: throw Exception("API Gateway ID not found in push service info")
            
            val envUpdateResult = awsDeployer.updateWebhookEnvironment(secret, userId, bucketName)
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
            awsDeployer.saveConfiguration(config)

            // 方案一：部署成功后同时保存到本地数据库，确保初始化时能找到配置
            try {
                val notificationConfigManager = NotificationConfigManager.getInstance(context)
                val saveLocalSuccess = notificationConfigManager.saveLocalConfig(config)
                if (saveLocalSuccess) {
                    Log.i(TAG, "配置已保存到本地数据库: provider=${config.provider}")
                } else {
                    Log.w(TAG, "配置保存到S3成功，但保存到本地数据库失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存配置到本地数据库异常", e)
                // 不影响部署结果，因为配置已保存到S3
            }

            Log.i(TAG, "Deployment completed successfully")
            DeployResult.success(webhookUrl, pushServiceInfo)

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
            if (wsClient != null) {
                try {
                    val oldEndpoint = currentEndpoint ?: "unknown"
                    Log.i(TAG, "Disconnecting existing WebSocket connection: $oldEndpoint")
                    disconnect()
                    // 短暂延迟，确保旧连接完全清理
                    delay(500)
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting old endpoint", e)
                }
            }
            
            // 若端点变更，记录日志
            if (currentEndpoint != null && currentEndpoint != wsEndpoint) {
                Log.i(TAG, "WebSocket endpoint changed: ${currentEndpoint} -> $wsEndpoint")
            }
            currentEndpoint = wsEndpoint

            val client = AwsWebSocketClient(
                endpoint = wsEndpoint,
                userId = userId
            )
            wsClient = client

            Log.i(TAG, "Connecting to AWS API Gateway WebSocket...")
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
            Log.i(TAG, "Disconnecting from AWS API Gateway")
            
            // 取消消息监听
            messageListenerJob?.cancel()
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
            
            // 清理状态
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

    private fun startMessageListener(client: AwsWebSocketClient) {
        messageListenerJob?.cancel()
        messageListenerJob = scope.launch {
            val messageChannel = client.getMessageChannel()
            try {
                while (isActive) {
                    val message = messageChannel.receive()
                    Log.d(TAG, "Received notification: type=${message.type}, senderId=${message.senderId}")
                    
                    // P1修复: 收到推送通知后，立即触发消息下载
                    handleNotificationMessage(message)
                    
                    // 保留原有的回调机制
                    notificationCallback?.invoke(message)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in message listener", e)
                }
            }
        }
    }
    
    /**
     * P1修复: 处理推送通知消息，触发下载流程
     */
    private suspend fun handleNotificationMessage(message: NotificationMessage) {
        try {
            Log.i(TAG, "[推送下载] 收到推送通知，准备下载消息")
            Log.d(TAG, "  - 通知类型: ${message.type}")
            Log.d(TAG, "  - 发送者ID(hash): ${message.senderId}")
            Log.d(TAG, "  - 时间戳: ${message.timestamp}")
            
            // 只处理新消息通知
            if (message.type != NotificationMessage.TYPE_NEW_MESSAGE) {
                Log.d(TAG, "[推送下载] 忽略非新消息通知: ${message.type}")
                return
            }
            
            // 从senderId（hash）解析出完整ACI
            // senderId是对方的hashId，需要通过本地配置或数据库查询对应的ACI
            val senderAci = resolveSenderAciFromHashId(message.senderId)
            if (senderAci == null) {
                Log.w(TAG, "[推送下载] 无法解析发送者ACI: hashId=${message.senderId}")
                return
            }
            
            Log.d(TAG, "[推送下载] 解析得到发送者ACI: $senderAci")
            
            // P1修复: 触发下载逻辑
            // 当前实现：确保轮询服务运行，让下一次轮询周期自动下载新消息
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            
            // 确保轮询服务已启动
            val pollingStatus = pollingService.getPollingStatus()
            if (!pollingStatus.isRunning) {
                Log.i(TAG, "[推送下载] 轮询服务未激活，启动轮询服务")
                val started = pollingService.startPolling()
                if (!started) {
                    Log.w(TAG, "[推送下载] 启动轮询服务失败")
                    return
                }
            }
            
            // 调整轮询间隔以快速获取新消息
            try {
                pollingService.adjustPollingInterval(
                    senderAci, 
                    org.thoughtcrime.securesms.tap.polling.IntervalChangeType.DECREASE
                )
                Log.d(TAG, "[推送下载] 已调整轮询间隔，加快消息获取")
            } catch (e: Exception) {
                Log.w(TAG, "[推送下载] 调整轮询间隔失败", e)
            }
            
            Log.i(TAG, "[推送下载] 推送通知处理完成，轮询服务将自动下载新消息: senderAci=$senderAci")
            
        } catch (e: Exception) {
            Log.e(TAG, "[推送下载] 处理推送通知失败", e)
        }
    }
    
    /**
     * P1修复: 从hashId解析发送者ACI
     * 通过查询数据库中的所有通道来反向查找
     */
    private suspend fun resolveSenderAciFromHashId(hashId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 直接从数据库查询所有通道
                val channelTable = org.thoughtcrime.securesms.database.SignalDatabase.transportChannels
                val allChannels = channelTable.getAllChannels()
                
                for (channelData in allChannels) {
                    // 解析metadata，查找匹配的peerHashedId
                    if (channelData.metadata is org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata) {
                        val cosMetadata = channelData.metadata as org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                        if (cosMetadata.peerHashedId == hashId) {
                            Log.d(TAG, "[推送下载] 找到匹配的通道: hashId=$hashId -> recipientId=${channelData.recipientId}")
                            return@withContext channelData.recipientId
                        }
                    }
                }
                
                Log.w(TAG, "[推送下载] 未找到匹配的ACI: hashId=$hashId")
                null
                
            } catch (e: Exception) {
                Log.e(TAG, "[推送下载] 解析发送者ACI失败: hashId=$hashId", e)
                null
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

