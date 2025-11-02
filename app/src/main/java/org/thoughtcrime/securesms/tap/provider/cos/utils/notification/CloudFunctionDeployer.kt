package org.thoughtcrime.securesms.tap.provider.cos.utils.notification

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.*
import org.thoughtcrime.securesms.tap.notification.provider.aws.AwsApiGatewayDeployer
import org.thoughtcrime.securesms.tap.notification.provider.tencent.TencentApiGatewayDeployer
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig

/**
 * 云函数部署器
 * 
 * 负责部署云函数F_A（事件触发器函数）
 * 支持AWS Lambda和腾讯云云函数
 */
class CloudFunctionDeployer(
    private val context: Context,
    private val cosConfig: CosConfig
) {
    
    companion object {
        private val TAG = Log.tag(CloudFunctionDeployer::class.java)
    }
    
    private var deployer: NotificationDeployer? = null
    
    /**
     * 部署云函数F_A（事件触发器）
     * 
     * @return TriggerInfo 触发器信息
     */
    suspend fun deployTriggerFunction(): TriggerInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始部署云函数F_A: provider=${cosConfig.provider}")
                
                val notificationDeployer = getOrCreateDeployer()
                    ?: throw Exception("无法创建Deployer: 不支持的provider ${cosConfig.provider}")
                
                val triggerInfo = notificationDeployer.setupEventTrigger()
                
                if (triggerInfo.validate()) {
                    Log.i(TAG, "云函数F_A部署成功: ${triggerInfo.triggerName}")
                    triggerInfo
                } else {
                    Log.e(TAG, "云函数F_A部署失败: 验证失败")
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "部署云函数F_A失败", e)
                null
            }
        }
    }
    
    /**
     * 配置云函数F_A的环境变量
     * 
     * @param configBucket 配置bucket名称
     * @param configKey 配置文件key
     * @return 配置是否成功
     */
    suspend fun configureFunctionEnvironment(
        configBucket: String,
        configKey: String = "notification-config.json"
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "配置云函数环境变量: bucket=$configBucket, key=$configKey")
                
                when (cosConfig.provider) {
                    CosConfig.Provider.AWS -> {
                        configureAwsLambdaEnvironment(configBucket, configKey)
                    }
                    CosConfig.Provider.TENCENT -> {
                        configureTencentFunctionEnvironment(configBucket, configKey)
                    }
                    else -> {
                        Log.w(TAG, "不支持的provider: ${cosConfig.provider}")
                        false
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "配置云函数环境变量失败", e)
                false
            }
        }
    }
    
    /**
     * 测试云函数部署
     * 
     * @return TestResult 测试结果
     */
    suspend fun testFunctionDeployment(): TestResult {
        return try {
            Log.i(TAG, "测试云函数部署")
            
            val notificationDeployer = getOrCreateDeployer()
                ?: return TestResult.failure("无法创建Deployer")
            
            notificationDeployer.testDeployment()
            
        } catch (e: Exception) {
            Log.e(TAG, "测试云函数部署失败", e)
            TestResult.failure(e.message ?: "测试失败")
        }
    }
    
    /**
     * 部署完整的推送服务（包括webhook和trigger）
     * 
     * @return NotificationConfig 部署后的配置
     */
    suspend fun deployFullNotificationService(): NotificationConfig? {
        val tracker = DeploymentTracker(context, getProviderType())
        val rollback = DeploymentRollback(context, getProviderType())
        
        // Check for existing partial deployment
        if (tracker.hasPartialDeployment()) {
            Log.w(TAG, "发现部分部署，尝试回滚")
            val notificationDeployer = getOrCreateDeployer()
            if (notificationDeployer != null) {
                val rollbackResult = rollback.rollback(notificationDeployer)
                if (rollbackResult.success) {
                    Log.i(TAG, "回滚成功，重新开始部署")
                } else {
                    Log.e(TAG, "回滚失败，继续尝试部署")
                }
            }
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始部署完整推送服务")
                tracker.startDeployment()
                
                val notificationDeployer = getOrCreateDeployer()
                    ?: throw Exception("无法创建Deployer")
                
                // Step 1: Deploy Webhook
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.DEPLOYING_WEBHOOK,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                val webhookUrl = notificationDeployer.deployWebhook()
                Log.d(TAG, "Webhook部署完成: $webhookUrl")
                
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.WEBHOOK_DEPLOYED,
                        timestamp = System.currentTimeMillis(),
                        success = true,
                        data = mapOf("webhookUrl" to webhookUrl)
                    )
                )
                
                // Step 2: Deploy Push Service (API Gateway)
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.DEPLOYING_API_GATEWAY,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                val pushServiceInfo = notificationDeployer.deployPushService()
                Log.d(TAG, "推送服务部署完成: ${pushServiceInfo.endpoint}")
                
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.API_GATEWAY_DEPLOYED,
                        timestamp = System.currentTimeMillis(),
                        success = true,
                        data = mapOf("endpoint" to pushServiceInfo.endpoint)
                    )
                )
                
                // Step 3: Deploy Event Trigger
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.DEPLOYING_TRIGGER,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                val triggerInfo = notificationDeployer.setupEventTrigger()
                Log.d(TAG, "事件触发器部署完成: ${triggerInfo.triggerName}")
                
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.TRIGGER_DEPLOYED,
                        timestamp = System.currentTimeMillis(),
                        success = true,
                        data = mapOf("triggerName" to triggerInfo.triggerName)
                    )
                )
                
                // Step 4: Configure Services
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.CONFIGURING,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                val notifySecret = generateNotifySecret()
                
                val config = NotificationConfig(
                    provider = getProviderType(),
                    webhookUrl = webhookUrl,
                    notifySecret = notifySecret,
                    pushServiceInfo = pushServiceInfo,
                    deployedAt = System.currentTimeMillis()
                )
                
                // Save configuration
                notificationDeployer.saveConfiguration(config)
                Log.i(TAG, "推送服务配置已保存")
                
                // Update webhook environment variables
                if (cosConfig.provider == CosConfig.Provider.AWS && deployer is AwsApiGatewayDeployer) {
                    val awsDeployer = deployer as AwsApiGatewayDeployer
                    val topicId = pushServiceInfo.credentials["topicId"] ?: ""
                    awsDeployer.updateWebhookEnvironment(notifySecret, topicId)
                    Log.d(TAG, "Webhook环境变量已更新")
                } else if (cosConfig.provider == CosConfig.Provider.TENCENT && deployer is TencentApiGatewayDeployer) {
                    val tencentDeployer = deployer as TencentApiGatewayDeployer
                    val topicId = pushServiceInfo.credentials["topicId"] ?: ""
                    tencentDeployer.updateWebhookEnvironment(notifySecret, topicId)
                    Log.d(TAG, "Webhook环境变量已更新")
                }
                
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.CONFIGURED,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                // Step 5: Mark deployment as complete
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = DeploymentTracker.DeploymentStep.DEPLOYED,
                        timestamp = System.currentTimeMillis(),
                        success = true
                    )
                )
                
                Log.i(TAG, "完整推送服务部署成功")
                config
                
            } catch (e: Exception) {
                Log.e(TAG, "部署完整推送服务失败", e)
                
                // Record failure checkpoint
                val currentStep = tracker.getCurrentStep()
                tracker.recordCheckpoint(
                    DeploymentTracker.DeploymentCheckpoint(
                        step = currentStep,
                        timestamp = System.currentTimeMillis(),
                        success = false,
                        errorMessage = e.message
                    )
                )
                
                // Attempt rollback
                Log.w(TAG, "尝试回滚已部署的资源")
                val notificationDeployer = getOrCreateDeployer()
                if (notificationDeployer != null) {
                    val rollbackResult = rollback.rollback(notificationDeployer)
                    if (rollbackResult.success) {
                        Log.i(TAG, "回滚成功：删除了 ${rollbackResult.deletedResources.size} 个资源")
                    } else {
                        Log.e(TAG, "回滚失败：${rollbackResult.failedResources.size} 个资源删除失败")
                    }
                }
                
                null
            }
        }
    }
    
    /**
     * 加载已保存的配置
     */
    suspend fun loadConfiguration(): NotificationConfig? {
        return try {
            Log.d(TAG, "加载推送服务配置")
            
            val notificationDeployer = getOrCreateDeployer()
                ?: return null
            
            notificationDeployer.loadConfiguration()
            
        } catch (e: Exception) {
            Log.e(TAG, "加载推送服务配置失败", e)
            null
        }
    }
    
    /**
     * 保存配置
     */
    suspend fun saveConfiguration(config: NotificationConfig): Boolean {
        return try {
            Log.i(TAG, "保存推送服务配置")
            
            if (!config.validate()) {
                Log.w(TAG, "配置验证失败")
                return false
            }
            
            val notificationDeployer = getOrCreateDeployer()
                ?: return false
            
            notificationDeployer.saveConfiguration(config)
            Log.i(TAG, "推送服务配置保存成功")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "保存推送服务配置失败", e)
            false
        }
    }
    
    /**
     * 获取或创建Deployer实例
     */
    private fun getOrCreateDeployer(): NotificationDeployer? {
        if (deployer != null) {
            return deployer
        }
        
        deployer = when (cosConfig.provider) {
            CosConfig.Provider.AWS -> {
                AwsApiGatewayDeployer(
                    context = context,
                    accessKeyId = cosConfig.secretId,
                    secretAccessKey = cosConfig.secretKey,
                    region = cosConfig.region
                )
            }
            CosConfig.Provider.TENCENT -> {
                TencentApiGatewayDeployer(
                    context = context,
                    secretId = cosConfig.secretId,
                    secretKey = cosConfig.secretKey,
                    region = cosConfig.region
                )
            }
            else -> {
                Log.w(TAG, "不支持的provider: ${cosConfig.provider}")
                null
            }
        }
        
        return deployer
    }
    
    /**
     * 获取Provider类型字符串
     */
    private fun getProviderType(): String {
        return when (cosConfig.provider) {
            CosConfig.Provider.AWS -> "aws-api-gateway"
            CosConfig.Provider.TENCENT -> "tencent-api-gateway"
            else -> "unknown"
        }
    }
    
    /**
     * 配置AWS Lambda环境变量
     */
    private suspend fun configureAwsLambdaEnvironment(
        configBucket: String,
        configKey: String
    ): Boolean {
        return try {
            Log.d(TAG, "配置AWS Lambda环境变量")
            
            // AWS Lambda环境变量在创建函数时已经配置
            // 这里可以进行额外的配置或更新
            
            Log.d(TAG, "AWS Lambda环境变量配置完成")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "配置AWS Lambda环境变量失败", e)
            false
        }
    }
    
    /**
     * 配置腾讯云云函数环境变量
     */
    private suspend fun configureTencentFunctionEnvironment(
        configBucket: String,
        configKey: String
    ): Boolean {
        return try {
            Log.i(TAG, "配置腾讯云云函数环境变量")
            
            if (deployer !is TencentApiGatewayDeployer) {
                Log.w(TAG, "Deployer不是TencentApiGatewayDeployer类型")
                return false
            }
            
            val tencentDeployer = deployer as TencentApiGatewayDeployer
            
            // 获取当前配置以获取notifySecret和topicId
            val config = tencentDeployer.loadConfiguration()
            if (config == null) {
                Log.w(TAG, "无法加载配置，无法更新环境变量")
                return false
            }
            
            val notifySecret = config.notifySecret
            val topicId = config.pushServiceInfo.credentials["topicId"] ?: ""
            
            // 更新Webhook函数环境变量
            val webhookSuccess = tencentDeployer.updateWebhookEnvironment(notifySecret, topicId)
            
            if (webhookSuccess) {
                Log.i(TAG, "腾讯云云函数环境变量配置成功")
            } else {
                Log.w(TAG, "腾讯云云函数环境变量配置部分失败")
            }
            
            webhookSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "配置腾讯云云函数环境变量失败", e)
            false
        }
    }
    
    /**
     * 生成notifySecret
     */
    private fun generateNotifySecret(): String {
        return try {
            val random = java.security.SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "生成notifySecret失败，使用UUID", e)
            java.util.UUID.randomUUID().toString().replace("-", "")
        }
    }
    
    /**
     * 保存联系人webhook配置 (统一接口)
     * 
     * 通过CosTransportProvider的ContactWebhookManager保存
     */
    suspend fun saveContactWebhookConfig(config: ContactNotificationConfig): Boolean {
        return try {
            Log.i(TAG, "通过统一接口保存联系人webhook配置")
            
            val cosClient = org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory.createClient(cosConfig, context)
            val webhookManager = ContactWebhookManager(context, cosClient)
            
            webhookManager.saveContactConfig(config)
            
        } catch (e: Exception) {
            Log.e(TAG, "保存联系人webhook配置失败", e)
            false
        }
    }
    
    /**
     * 加载联系人webhook配置 (统一接口)
     */
    suspend fun loadContactWebhookConfig(contactId: String): ContactNotificationConfig? {
        return try {
            val cosClient = org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory.createClient(cosConfig, context)
            val webhookManager = ContactWebhookManager(context, cosClient)
            
            webhookManager.loadContactConfig(contactId)
            
        } catch (e: Exception) {
            Log.e(TAG, "加载联系人webhook配置失败", e)
            null
        }
    }
    
    /**
     * 配置事件触发器 (统一接口)
     */
    suspend fun configureEventTrigger(triggerFunctionIdentifier: String): Boolean {
        return try {
            Log.i(TAG, "通过统一接口配置事件触发器")
            
            val cosClient = org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory.createClient(cosConfig, context)
            val configurator = CosEventTriggerConfigurator(context, cosConfig, cosClient)
            
            configurator.configureEventTrigger(triggerFunctionIdentifier)
            
        } catch (e: Exception) {
            Log.e(TAG, "配置事件触发器失败", e)
            false
        }
    }
    
    /**
     * 测试事件触发器 (统一接口)
     */
    suspend fun testEventTrigger(): Boolean {
        return try {
            val cosClient = org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory.createClient(cosConfig, context)
            val configurator = CosEventTriggerConfigurator(context, cosConfig, cosClient)
            
            configurator.testEventTrigger()
            
        } catch (e: Exception) {
            Log.e(TAG, "测试事件触发器失败", e)
            false
        }
    }
    
    /**
     * 获取部署状态摘要
     */
    fun getDeploymentSummary(): Map<String, Any> {
        return try {
            mapOf(
                "provider" to getProviderType(),
                "deployed" to (deployer != null),
                "region" to cosConfig.region
            )
        } catch (e: Exception) {
            Log.w(TAG, "获取部署状态摘要失败", e)
            emptyMap()
        }
    }
    
    /**
     * 清理Deployer资源
     */
    fun cleanup() {
        try {
            when (deployer) {
                is AwsApiGatewayDeployer -> {
                    (deployer as AwsApiGatewayDeployer).cleanup()
                }
                is TencentApiGatewayDeployer -> {
                    (deployer as TencentApiGatewayDeployer).cleanup()
                }
            }
            deployer = null
        } catch (e: Exception) {
            Log.w(TAG, "清理Deployer资源失败", e)
        }
    }
}

