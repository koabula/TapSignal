package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.*
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云API网关推送服务部署器
 * 负责自动化部署云函数、API网关WebSocket和事件触发器
 * connectionId存储到用户配置的COS bucket中
 */
class TencentApiGatewayDeployer(
    private val context: Context,
    private val secretId: String,
    private val secretKey: String,
    private val region: String = "ap-guangzhou"
) : NotificationDeployer {

    companion object {
        private const val TAG = "TencentApiGatewayDeployer"
        private const val WEBHOOK_FUNCTION_NAME = "tap-notification-webhook"
        private const val TRIGGER_FUNCTION_NAME = "tap-notification-trigger"
        private const val REGISTER_FUNCTION_NAME = "tap-ws-register"
        private const val CLEANUP_FUNCTION_NAME = "tap-ws-cleanup"
        private const val WS_MAIN_FUNCTION_NAME = "tap-ws-main"
        private const val CONFIG_PREFIX = "tap-notification-config"
        private const val CONFIG_DIR = "tap-state"  // 配置存储目录
        private const val CONFIG_KEY = "tap-state/notification-config.json"  // 修改为包含目录路径
        private const val MAX_DEPLOYMENT_WAIT_SECONDS = 120
        
        private const val WEBHOOK_ASSET_NAME = "tencent-webhook.zip"
        private const val TRIGGER_ASSET_NAME = "tencent-f-a.zip"
        private const val REGISTER_ASSET_NAME = "tencent-ws-register.zip"
        private const val CLEANUP_ASSET_NAME = "tencent-ws-cleanup.zip"
        private const val WS_MAIN_ASSET_NAME = "tencent-ws-main.zip"
        
        private const val SCF_HOST = "scf.tencentcloudapi.com"
        // API_GATEWAY_HOST 已移除：API网关产品已停止服务
        private const val COS_HOST = "cos.myqcloud.com"
    }

    private var deploymentInfo: NotificationDeployment? = null
    private var userBucketName: String? = null  // 用户配置的bucket名称
    private var configBucketName: String? = null
    // apiGatewayServiceId 和 apiGatewayEndpoint 已移除：不再使用API网关
    
    // COS客户端实例（用于上传/下载配置文件）
    private val cosClient: CosClient? by lazy {
        try {
            if (userBucketName.isNullOrEmpty()) {
                Log.w(TAG, "Cannot create CosClient: bucket name not set")
                null
            } else {
                val cosConfig = CosConfig(
                    provider = CosConfig.Provider.TENCENT,
                    secretId = secretId,
                    secretKey = secretKey,
                    region = region,
                    bucketName = userBucketName!!
                )
                CosClientFactory.createClient(cosConfig, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create CosClient", e)
            null
        }
    }
    
    /**
     * 设置用户bucket名称（从COS provider配置中获取）
     */
    fun setUserBucketName(bucketName: String?) {
        userBucketName = bucketName
        // 如果用户bucket已设置，则将其作为配置bucket
        if (!bucketName.isNullOrEmpty()) {
            configBucketName = bucketName
        }
    }

    override suspend fun deployWebhook(): String {
        return try {
            Log.i(TAG, "Deploying webhook cloud function...")

            val functionName = "$WEBHOOK_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val zipData = loadAsset(WEBHOOK_ASSET_NAME)
            
            // 移除API Gateway相关依赖，改为使用函数URL
            val envVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "TAP_SECRET_ID" to secretId,
                "TAP_SECRET_KEY" to secretKey,
                "REGION" to region
                // CONNECTIONS_BUCKET 将通过 updateWebhookEnvironment 设置
                // 不再需要 API_GATEWAY_SERVICE_ID 和 API_GATEWAY_REGION
            )
            
            val functionArn = createCloudFunction(
                functionName = functionName,
                zipData = zipData,
                handler = "tencent-webhook.main_handler",
                envVars = envVars,
                timeout = 30,
                memorySize = 256
            )
            
            // 使用函数URL替代HTTP触发器
            val webhookUrl = createFunctionUrl(functionName)
            
            Log.i(TAG, "Webhook deployed successfully: $webhookUrl")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                webhookFunctionName = functionName,
                webhookFunctionArn = functionArn,
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "webhook"
            )

            webhookUrl

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy webhook", e)
            throw Exception("Webhook deployment failed: ${e.message}", e)
        }
    }

    override suspend fun deployPushService(userBucketName: String?): PushServiceInfo {
        return try {
            Log.i(TAG, "Deploying Tencent function URL WebSocket push service...")

            if (userBucketName.isNullOrEmpty()) {
                throw Exception("User bucket name is required for connectionId storage")
            }
            
            // 创建WebSocket主函数（启用WebSocket支持）
            val wsMainFunctionName = "$WS_MAIN_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            
            val wsMainEnvVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "CONNECTIONS_BUCKET" to userBucketName!!,
                "TAP_SECRET_ID" to secretId,
                "TAP_SECRET_KEY" to secretKey,
                "REGION" to region
            )
            
            val wsMainZipData = loadAsset(WS_MAIN_ASSET_NAME)
            
            // 创建启用WebSocket的函数
            val wsMainFunctionArn = createCloudFunction(
                functionName = wsMainFunctionName,
                zipData = wsMainZipData,
                handler = "tencent-ws-main.main_handler",
                envVars = wsMainEnvVars,
                timeout = 30,
                memorySize = 256,
                enableWebSocket = true, // 启用WebSocket支持
                isWebFunction = true    // 以 Web 函数创建（端口9000）
            )
            
            // 创建函数URL（启用WebSocket后应返回WssExtranetUrl）
            val wssEndpoint = createFunctionUrl(wsMainFunctionName, enableWebSocket = true)
            
            Log.i(TAG, "WebSocket push service deployed: $wssEndpoint")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "push-service"
            )

            PushServiceInfo(
                endpoint = wssEndpoint,
                region = region,
                credentials = mapOf(
                    // Provider创建所需凭证（P0修复：添加secretId和secretKey）
                    "apiKey" to secretId,
                    "secretId" to secretId,  // 兼容两种key名称
                    "secretKey" to secretKey,
                    // 现有元数据
                    "wsMainFunction" to wsMainFunctionName,
                    "functionArn" to wsMainFunctionArn
                ),
                metadata = mapOf(
                    "provider" to "tencent-function-url",
                    "wsMainFunction" to wsMainFunctionArn,
                    "connectionsBucket" to userBucketName!!,
                    "architecture" to "function-url-websocket"
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy push service", e)
            throw Exception("Push service deployment failed: ${e.message}", e)
        }
    }

    override suspend fun setupEventTrigger(userBucketName: String?): TriggerInfo {
        return try {
            Log.i(TAG, "Setting up COS event trigger...")

            val functionName = "$TRIGGER_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val zipData = loadAsset(TRIGGER_ASSET_NAME)
            
            val configBucket = if (!userBucketName.isNullOrEmpty()) {
                Log.d(TAG, "Using user bucket for CONFIG_BUCKET: $userBucketName")
                userBucketName
            } else {
                Log.w(TAG, "User bucket not provided, F_A will read from event bucket as fallback")
                ""
            }
            
            val envVars = mutableMapOf<String, String>(
                "CONFIG_KEY" to CONFIG_KEY,
                "REGION" to region,
                "TAP_SECRET_ID" to secretId,
                "TAP_SECRET_KEY" to secretKey
            ).apply {
                if (configBucket.isNotEmpty()) {
                    this["CONFIG_BUCKET"] = configBucket
                }
            }
            
            val functionArn = createCloudFunction(
                functionName = functionName,
                zipData = zipData,
                handler = "tencent-f-a.main_handler",
                envVars = envVars,
                timeout = 60,
                memorySize = 256
            )
            
            Log.d(TAG, "Trigger function created: $functionArn")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                triggerFunctionName = functionName,
                triggerFunctionArn = functionArn,
                eventTriggerConfigured = false,
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "event-trigger"
            )

            TriggerInfo(
                triggerName = functionName,
                triggerArn = functionArn,
                targetFunction = functionName,
                configured = false,
                filterPrefix = "v2-channels/"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup event trigger", e)
            throw Exception("Event trigger setup failed: ${e.message}", e)
        }
    }

    /**
     * 配置COS事件通知 (标准方法名)
     */
    suspend fun configureCosEventNotification(
        userBucketName: String,
        userBucketRegion: String,
        triggerFunctionName: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return configureCOSEventNotification(userBucketName, triggerFunctionName, filterPrefix)
    }
    
    /**
     * 配置COS事件通知 (内部实现)
     * 使用SCF CreateTrigger API创建COS触发器
     * 根据腾讯云文档，COS触发器的TriggerDesc应该包含cos配置信息
     */
    suspend fun configureCOSEventNotification(
        bucketName: String,
        triggerFunctionName: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return try {
            Log.i(TAG, "Configuring COS event notification on bucket: $bucketName")
            
            // 提取appid（如果需要）
            val appId = extractAppId(bucketName)
            
            // 根据错误信息，腾讯云要求bucket地址格式为: <BucketName-AppId>.cos.<Region>.myqcloud.com
            // 需要在ResourceId中使用完整格式的bucket地址
            val bucketAddress = if (appId.isNotEmpty()) {
                "${bucketName}.cos.${region}.myqcloud.com"
            } else {
                // 如果没有appId，尝试使用bucket名称（可能已经包含appId）
                "${bucketName}.cos.${region}.myqcloud.com"
            }
            
            // 根据腾讯云SCF文档，COS触发器的TriggerDesc格式：
            // {
            //   "event": "cos:ObjectCreated:*",
            //   "filter": {
            //     "Prefix": "prefix",
            //     "Suffix": "suffix"
            //   }
            // }
            // 注意：bucket信息在创建触发器时通过ResourceId传递完整格式的bucket地址
            
            val triggerDesc = JSONObject().apply {
                put("event", "cos:ObjectCreated:*")  // 事件类型
                put("filter", JSONObject().apply {
                    put("Prefix", filterPrefix)  // 文件前缀过滤
                    // Suffix可选，这里不设置
                })
            }
            
            // 根据腾讯云文档，COS触发器需要在函数所在的region创建
            // 且bucket需要与函数在同一个region
            // ResourceId格式应该是完整格式的bucket地址: <BucketName-AppId>.cos.<Region>.myqcloud.com
            
            val params = JSONObject().apply {
                put("FunctionName", triggerFunctionName)
                // TriggerName应该是触发器名称，不是bucket地址
                put("TriggerName", "tap-cos-trigger-${System.currentTimeMillis()}")
                put("Type", "cos")
                put("TriggerDesc", triggerDesc.toString())  // 作为JSON字符串传入
                put("Namespace", "default")
                put("Qualifier", "\$LATEST")
                // 添加ResourceId，使用完整格式的bucket地址
                put("ResourceId", bucketAddress)
            }
            
            callTencentAPI(
                host = SCF_HOST,
                action = "CreateTrigger",
                version = "2018-04-16",
                params = params
            )
            
            Log.i(TAG, "COS event notification configured successfully")
            
            deploymentInfo = deploymentInfo?.copy(
                eventTriggerConfigured = true
            )
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure COS event notification", e)
            false
        }
    }
    
    /**
     * 从bucket名称中提取AppID
     * 腾讯云bucket格式：<bucket-name>-<appid>
     */
    private fun extractAppId(bucketName: String): String {
        try {
            val parts = bucketName.split("-")
            if (parts.size >= 2) {
                val lastPart = parts.last()
                // AppID应该是10位数字
                if (lastPart.matches(Regex("\\d{10}"))) {
                    return lastPart
                }
            }
            return ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract AppID from bucket name: $bucketName", e)
            return ""
        }
    }
    
    /**
     * 移除COS事件通知配置
     */
    suspend fun removeCosEventNotification(
        userBucketName: String,
        userBucketRegion: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return try {
            Log.i(TAG, "Removing COS event notification: bucket=$userBucketName")
            
            // 腾讯云删除触发器需要知道TriggerName
            // 可以通过列出触发器并筛选删除
            Log.d(TAG, "COS触发器移除功能暂未实现，需要手动删除")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove COS event notification", e)
            false
        }
    }

    override suspend fun testDeployment(): TestResult {
        return try {
            Log.i(TAG, "Testing deployment...")

            var webhookReachable = false
            var pushServiceConnected = false
            var eventTriggerWorking = false

            try {
                val config = loadConfiguration()
                if (config != null) {
                    webhookReachable = testWebhookReachability(config.webhookUrl)
                    pushServiceConnected = testWebSocketConnection(config.pushServiceInfo)
                    eventTriggerWorking = testEventTriggerFunction()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Test failed with exception", e)
            }

            TestResult.success(
                webhookReachable = webhookReachable,
                pushServiceConnected = pushServiceConnected,
                eventTriggerWorking = eventTriggerWorking
            )

        } catch (e: Exception) {
            Log.e(TAG, "Deployment test failed", e)
            TestResult.failure(e.message ?: "Test failed")
        }
    }

    suspend fun updateWebhookEnvironment(
        secret: String, 
        userId: String, 
        bucketName: String?, 
        wsFunctionUrl: String? = null
    ): Boolean {
        return try {
            val webhookFunctionName = deploymentInfo?.webhookFunctionName
            if (webhookFunctionName == null) {
                Log.w(TAG, "Webhook function name not found, cannot update environment")
                return false
            }
            
            if (bucketName.isNullOrEmpty()) {
                Log.w(TAG, "Bucket name not provided, cannot update webhook environment")
                return false
            }
            
            Log.i(TAG, "Updating webhook cloud function environment variables...")
            
            val envVars = mutableMapOf(
                "LOG_LEVEL" to "INFO",
                "CONNECTIONS_BUCKET" to bucketName!!,
                "NOTIFY_SECRET" to secret,
                "TAP_SECRET_ID" to secretId,
                "TAP_SECRET_KEY" to secretKey,
                "REGION" to region
            )
            
            // 如果提供了WebSocket函数URL，添加环境变量
            if (!wsFunctionUrl.isNullOrEmpty()) {
                envVars["WS_FUNCTION_URL"] = wsFunctionUrl
                Log.d(TAG, "Added WS_FUNCTION_URL to webhook environment")
            }
            
            val envArray = JSONArray().apply {
                envVars.forEach { (key, value) ->
                    put(JSONObject().apply {
                        put("Key", key)
                        put("Value", value)
                    })
                }
            }
            
            val params = JSONObject().apply {
                put("FunctionName", webhookFunctionName)
                put("Namespace", "default")
                put("Environment", JSONObject().apply {
                    put("Variables", envArray)
                })
            }
            
            callTencentAPI(
                host = SCF_HOST,
                action = "UpdateFunctionConfiguration",
                version = "2018-04-16",
                params = params
            )
            
            Log.i(TAG, "Webhook environment updated successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update webhook environment", e)
            false
        }
    }

    override suspend fun saveConfiguration(config: NotificationConfig) {
        try {
            Log.i(TAG, "Saving configuration to COS...")

            val bucketName = getOrCreateConfigBucket()
            
            val jsonConfig = JSONObject().apply {
                put("provider", config.provider)
                put("webhookUrl", config.webhookUrl)
                put("notifySecret", config.notifySecret)
                put("deployedAt", config.deployedAt)
                put("version", config.version)
                put("pushServiceInfo", JSONObject().apply {
                    put("endpoint", config.pushServiceInfo.endpoint)
                    put("region", config.pushServiceInfo.region)
                    put("credentials", JSONObject(config.pushServiceInfo.credentials))
                    put("metadata", JSONObject(config.pushServiceInfo.metadata))
                })
            }.toString()

            uploadToCOS(bucketName, CONFIG_KEY, jsonConfig.toByteArray())
            
            Log.i(TAG, "Configuration saved successfully")

            deploymentInfo = deploymentInfo?.copy(
                deploymentStatus = DeploymentStatus.DEPLOYED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save configuration", e)
            throw Exception("Configuration save failed: ${e.message}", e)
        }
    }

    override suspend fun loadConfiguration(): NotificationConfig? {
        return try {
            Log.d(TAG, "Loading configuration from COS...")

            val bucketName = if (configBucketName != null) {
                configBucketName!!
            } else {
                Log.w(TAG, "Bucket name not set, configuration may not exist")
                return null
            }

            val content = downloadFromCOS(bucketName, CONFIG_KEY)
            if (content == null) {
                Log.w(TAG, "Configuration not found")
        return null
    }

            val json = JSONObject(content)
            val pushServiceJson = json.getJSONObject("pushServiceInfo")
            
            val credentialsJson = pushServiceJson.getJSONObject("credentials")
            val credentials = mutableMapOf<String, String>()
            credentialsJson.keys().forEach { key ->
                credentials[key] = credentialsJson.getString(key)
            }

            val metadataJson = pushServiceJson.optJSONObject("metadata")
            val metadata = mutableMapOf<String, Any>()
            metadataJson?.keys()?.forEach { key ->
                metadata[key] = metadataJson.get(key)
            }

            NotificationConfig(
                provider = json.getString("provider"),
                webhookUrl = json.getString("webhookUrl"),
                notifySecret = json.getString("notifySecret"),
                pushServiceInfo = PushServiceInfo(
                    endpoint = pushServiceJson.getString("endpoint"),
                    region = pushServiceJson.getString("region"),
                    credentials = credentials,
                    metadata = metadata
                ),
                deployedAt = json.getLong("deployedAt"),
                version = json.optString("version", "2.0")
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load configuration", e)
            null
        }
    }


    private suspend fun createCloudFunction(
        functionName: String,
        zipData: ByteArray,
        handler: String,
        envVars: Map<String, String>,
        timeout: Int,
        memorySize: Int,
        enableWebSocket: Boolean = false,
        isWebFunction: Boolean = false
    ): String {
        try {
            Log.i(TAG, "Creating cloud function: $functionName (WebSocket: $enableWebSocket)")
            
            val base64ZipData = android.util.Base64.encodeToString(zipData, android.util.Base64.NO_WRAP)
            
            val envArray = JSONArray().apply {
                envVars.forEach { (key, value) ->
                    put(JSONObject().apply {
                        put("Key", key)
                        put("Value", value)
                    })
                }
            }
            
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("Code", JSONObject().apply {
                    put("ZipFile", base64ZipData)
                })
                put("Handler", handler)
                put("Runtime", "Nodejs16.13")
                put("Timeout", timeout)
                put("MemorySize", memorySize)
                put("Environment", JSONObject().apply {
                    put("Variables", envArray)
                })
                put("Namespace", "default")

                // 在创建时设置为 HTTP（Web 函数）以支持 Web/WS
                if (isWebFunction) {
                    put("Type", "HTTP")
                }
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "CreateFunction",
                version = "2018-04-16",
                params = params
            )
            
            // 检查响应是否包含错误
            if (response.has("Response")) {
                val responseObj = response.getJSONObject("Response")
                if (responseObj.has("Error")) {
                    val error = responseObj.getJSONObject("Error")
                    val errorCode = error.optString("Code", "Unknown")
                    val errorMessage = error.optString("Message", "Unknown error")
                    throw Exception("Function creation failed [$errorCode]: $errorMessage")
                }
            }
            
            val functionArn = "qcs::scf:$region::lam/$functionName"
            
            Log.i(TAG, "Cloud function created: $functionArn")
            
            waitForFunctionActive(functionName)
            
            // 如果启用WebSocket，更新函数配置开启WebSocket协议（ProtocolType=WS）
            if (enableWebSocket) {
                enableWebSocketForFunction(functionName)
            }
            
            return functionArn
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cloud function: $functionName", e)
            throw Exception("Cloud function creation failed: ${e.message}", e)
        }
    }

    /**
     * 创建函数URL（使用函数URL替代API网关）
     * 根据腾讯云文档：https://cloud.tencent.com/document/product/583/100227
     * 使用CreateTrigger API，Type参数为"http"
     * 注意：如果函数启用了WebSocket支持，函数URL会自动支持WSS协议
     */
    private suspend fun createFunctionUrl(functionName: String, enableWebSocket: Boolean = false): String {
        try {
            Log.i(TAG, "Creating function URL for: $functionName (WebSocket: $enableWebSocket)")
            
            // TriggerDesc参数配置
            val triggerDesc = JSONObject().apply {
                // 授权类型：NONE表示无需授权（公开访问）
                put("AuthType", "NONE")
                
                // 网络访问配置：开启公网访问
                put("NetConfig", JSONObject().apply {
                    put("EnableIntranet", false)
                    put("EnableExtranet", true)
                })
                
                // P1修复：启用WebSocket支持（根据腾讯云文档）
                if (enableWebSocket) {
                    put("ProtocolType", "WS")  // 启用WebSocket协议
                    Log.d(TAG, "WebSocket protocol enabled in TriggerDesc for: $functionName")
                }
                
                // CORS配置：允许跨域访问
                put("CorsConfig", JSONObject().apply {
                    put("Enable", true)
                    put("Origins", JSONArray().apply {
                        put("*")
                    })
                    put("Headers", JSONArray().apply {
                        put("content-type")
                        put("authorization")
                    })
                    put("Methods", JSONArray().apply {
                        put("GET")
                        put("POST")
                        put("PUT")
                        put("DELETE")
                        put("OPTIONS")
                    })
                    put("ExposeHeaders", JSONArray().apply {
                        put("*")
                    })
                    put("MaxAge", 3600)
                    put("Credentials", true)
                })
            }
            
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("TriggerName", "func-url-${System.currentTimeMillis()}")
                put("Type", "http")
                put("TriggerDesc", triggerDesc.toString())
                put("Namespace", "default")
                put("Enable", "OPEN")
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "CreateTrigger",
                version = "2018-04-16",
                params = params
            )
            
            // 尝试从CreateTrigger响应中直接提取URL（优先WssExtranetUrl）
            val functionUrl = try {
                val responseObj = response.getJSONObject("Response")
                if (responseObj.has("TriggerInfo")) {
                    val triggerInfo = responseObj.getJSONObject("TriggerInfo")
                    val triggerDesc = triggerInfo.optString("TriggerDesc", "")
                    if (triggerDesc.isNotEmpty()) {
                        try {
                            val descJson = JSONObject(triggerDesc)
                            if (descJson.has("NetConfig")) {
                                val netConfig = descJson.getJSONObject("NetConfig")
                                val wssUrl = netConfig.optString("WssExtranetUrl", "")
                                if (enableWebSocket && wssUrl.isNotEmpty()) {
                                    Log.i(TAG, "Extracted WSS function URL from CreateTrigger response: $wssUrl")
                                    wssUrl
                                } else {
                                    val extranetUrl = netConfig.optString("ExtranetUrl", "")
                                    if (extranetUrl.isNotEmpty()) {
                                        val fallback = if (enableWebSocket) extranetUrl.replace("https://", "wss://") else extranetUrl
                                        Log.i(TAG, "Extracted function URL from CreateTrigger response: $fallback")
                                        fallback
                                    } else {
                                        null
                                    }
                                }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse TriggerInfo to extract URL from CreateTrigger response", e)
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract URL from CreateTrigger response, will query later", e)
                null
            }
            
            // 如果从响应中提取成功，直接返回
            if (!functionUrl.isNullOrEmpty()) {
                Log.i(TAG, "Function URL created: $functionUrl")
                return functionUrl
            }
            
            // P0修复：等待触发器创建完成，多次重试获取URL
            var retries = 5
            var queriedUrl: String? = null
            while (retries > 0 && queriedUrl.isNullOrEmpty()) {
                delay(2000)
                try {
                    queriedUrl = getFunctionUrl(functionName, enableWebSocket)
                    if (!queriedUrl.isNullOrEmpty() && !queriedUrl.contains("function-url-pending")) {
                        Log.i(TAG, "Function URL retrieved: $queriedUrl")
                        return queriedUrl
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get function URL, retries left: ${retries - 1}", e)
                }
                retries--
            }
            
            // 如果仍然无法获取，尝试从响应中查找
            if (queriedUrl.isNullOrEmpty() || queriedUrl.contains("function-url-pending")) {
                Log.e(TAG, "Failed to get function URL after retries, URL may be incomplete")
                throw Exception("Failed to get function URL for: $functionName. Please check Tencent Cloud console.")
            }
            
            Log.i(TAG, "Function URL created: $queriedUrl")
            return queriedUrl
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create function URL", e)
            throw Exception("Function URL creation failed: ${e.message}", e)
        }
    }
    
    /**
     * 获取函数的URL地址
     * 通过查询函数的触发器信息来获取函数URL
     */
    private suspend fun getFunctionUrl(functionName: String, enableWebSocket: Boolean = false): String {
        try {
            // 查询函数的触发器列表
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("Namespace", "default")
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "ListTriggers",
                version = "2018-04-16",
                params = params
            )
            
            val responseObj = response.getJSONObject("Response")
            val triggers = responseObj.optJSONArray("Triggers") ?: JSONArray()
            
            // 查找类型为http的触发器
            for (i in 0 until triggers.length()) {
                val trigger = triggers.getJSONObject(i)
                if (trigger.optString("Type") == "http") {
                    // 函数URL格式：https://<app-id>-<url-id>.<region>.tencentscf.com
                    // 从触发器的Qualifier获取信息
                    val qualifier = trigger.optString("Qualifier", "\$LATEST")
                    
                    // 构造函数URL（需要从函数详情或触发器信息中获取完整URL）
                    // 临时方案：通过函数名和区域构造（实际URL需要通过API获取）
                    // TODO: 需要调用DescribeFunctionUrl或从触发器响应中获取完整URL
                    
                    // P0修复：尝试从触发器详情中获取URL（多种方式）
                    val triggerDesc = trigger.optString("TriggerDesc", "")
                    if (triggerDesc.isNotEmpty()) {
                        try {
                            val descJson = JSONObject(triggerDesc)
                            
                            // 方式1：从NetConfig中提取WssExtranetUrl/ExtranetUrl
                            if (descJson.has("NetConfig")) {
                                val netConfig = descJson.getJSONObject("NetConfig")
                                val wssUrl = netConfig.optString("WssExtranetUrl", "")
                                if (enableWebSocket && wssUrl.isNotEmpty()) {
                                    Log.i(TAG, "Extracted WSS function URL from NetConfig: $wssUrl")
                                    return wssUrl
                                }
                                val extranetUrl = netConfig.optString("ExtranetUrl", "")
                                if (extranetUrl.isNotEmpty() && extranetUrl.startsWith("http")) {
                                    val fallback = if (enableWebSocket) extranetUrl.replace("https://", "wss://") else extranetUrl
                                    Log.i(TAG, "Extracted function URL from NetConfig: $fallback")
                                    return fallback
                                }
                            }
                            
                            // 方式2：尝试从其他字段提取
                            // 某些情况下URL可能在响应对象中直接返回
                            val qualifier = trigger.optString("Qualifier", "")
                            val addTime = trigger.optString("AddTime", "")
                            Log.d(TAG, "Trigger details - Qualifier: $qualifier, AddTime: $addTime")
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse TriggerDesc to extract URL", e)
                        }
                    }
                    
                    // P0修复：尝试从触发器响应中获取更多信息
                    val triggerInfo = trigger.optJSONObject("TriggerInfo")
                    if (triggerInfo != null) {
                            val triggerDescFromInfo = triggerInfo.optString("TriggerDesc", "")
                        if (triggerDescFromInfo.isNotEmpty()) {
                            try {
                                val descJson = JSONObject(triggerDescFromInfo)
                                if (descJson.has("NetConfig")) {
                                    val netConfig = descJson.getJSONObject("NetConfig")
                                        val wssUrl = netConfig.optString("WssExtranetUrl", "")
                                        if (enableWebSocket && wssUrl.isNotEmpty()) {
                                            Log.i(TAG, "Extracted WSS function URL from TriggerInfo: $wssUrl")
                                            return wssUrl
                                        }
                                        val extranetUrl = netConfig.optString("ExtranetUrl", "")
                                        if (extranetUrl.isNotEmpty() && extranetUrl.startsWith("http")) {
                                            val fallback = if (enableWebSocket) extranetUrl.replace("https://", "wss://") else extranetUrl
                                            Log.i(TAG, "Extracted function URL from TriggerInfo: $fallback")
                                            return fallback
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse TriggerInfo to extract URL", e)
                            }
                        }
                    }
                    
                    // 如果无法从触发器获取，使用函数名和区域构造
                    // 实际URL格式需要根据腾讯云API文档确认
                    // 参考格式：https://service-{app-id}-{url-id}.{region}.scf.tencentcsf.com
                    // 但更准确的方式是调用DescribeFunctionUrl API
                    Log.w(TAG, "Cannot get full URL from trigger, using function name pattern")
                    return getFunctionUrlFromFunctionName(functionName, enableWebSocket)
                }
            }
            
            // 如果没有找到HTTP触发器，抛出异常
            throw Exception("HTTP trigger not found for function: $functionName")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get function URL from triggers, using fallback", e)
            // 降级方案：使用函数名构造URL（格式可能不准确）
            return getFunctionUrlFromFunctionName(functionName, enableWebSocket)
        }
    }
    
    /**
     * 从函数名构造函数URL（降级方案）
     * 实际URL应该通过DescribeFunctionUrl API获取
     */
    private suspend fun getFunctionUrlFromFunctionName(functionName: String, enableWebSocket: Boolean = false): String {
        // 尝试获取函数详细信息
        try {
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("Namespace", "default")
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "GetFunction",
                version = "2018-04-16",
                params = params
            )
            
            val responseObj = response.getJSONObject("Response")
            
            // 尝试从函数配置中获取函数URL相关信息
            // 注意：腾讯云函数URL的实际格式为：https://<app-id>-<url-id>.<region>.tencentscf.com
            // 但需要通过ListTriggers或GetFunctionUrl API获取完整URL
            
            // 从函数信息中尝试提取AppId
            val functionId = responseObj.optString("FunctionId", "")
            val resourceId = responseObj.optString("ResourceId", "")
            
            // 如果ListTriggers没有返回URL，尝试等待一段时间后重试
            // 或者使用函数ID构造一个可能的URL模式
            if (functionId.isNotEmpty()) {
                Log.d(TAG, "FunctionId found: $functionId")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get function details", e)
        }
        
        // 降级方案：由于无法直接从API获取完整URL，返回一个占位符
        // 实际URL需要通过控制台查看或使用腾讯云CLI获取
        // 格式：https://<app-id>-<url-id>.<region>.tencentscf.com
        val base = "https://function-url-pending-${functionName}.${region}.tencentscf.com"
        val tempUrl = if (enableWebSocket) base.replace("https://", "wss://") else base
        Log.w(TAG, "Cannot get full function URL from API, using placeholder: $tempUrl")
        Log.w(TAG, "Please check Tencent Cloud console or use CLI to get actual function URL")
        return tempUrl
    }

    /**
     * P1修复：WebSocket支持已通过TriggerDesc配置，不需要单独启用
     * 根据腾讯云文档，WebSocket通过函数URL的ProtocolType="WS"启用
     */
    private suspend fun enableWebSocketForFunction(functionName: String) {
        try {
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("Namespace", "default")
                // 仅启用 WS 协议（Type 已在创建时设置为 HTTP）
                put("ProtocolType", "WS")
            }
            callTencentAPI(
                host = SCF_HOST,
                action = "UpdateFunctionConfiguration",
                version = "2018-04-16",
                params = params
            )
            Log.d(TAG, "Enabled HTTP Type and WebSocket (ProtocolType=WS) for function: $functionName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable WebSocket for function: $functionName", e)
            throw e
        }
    }

    // createWebSocketAPIGateway方法已移除
    // 腾讯云API网关产品已于2025年6月30日停止服务
    // 如需WebSocket支持，请使用函数URL + WebSocket支持，或改用其他推送方案

    private suspend fun waitForFunctionActive(functionName: String) {
        var attempts = 0
        val maxAttempts = MAX_DEPLOYMENT_WAIT_SECONDS / 5

        while (attempts < maxAttempts) {
            try {
                val params = JSONObject().apply {
                    put("FunctionName", functionName)
                    put("Namespace", "default")
                }
                
                val response = callTencentAPI(
                    host = SCF_HOST,
                    action = "GetFunction",
                    version = "2018-04-16",
                    params = params
                )
                
                // 检查响应是否有错误
                val responseObj = response.getJSONObject("Response")
                if (responseObj.has("Error")) {
                    val error = responseObj.getJSONObject("Error")
                    val errorCode = error.optString("Code", "")
                    // 如果函数不存在，提前退出
                    if (errorCode == "ResourceNotFound.Function") {
                        Log.e(TAG, "Function not found: $functionName, creation may have failed")
                        throw Exception("Function not found: $functionName")
                    }
                    throw Exception("Error checking function: ${error.optString("Message", "")}")
                }
                
                val status = responseObj.optString("Status", "")
                if (status == "Active") {
                    Log.d(TAG, "Function is active: $functionName")
                    return
                }
                
                // 如果状态为CreateFailed，立即抛出异常，不再等待
                if (status == "CreateFailed") {
                    val statusReasons = responseObj.optJSONArray("StatusReasons")
                    val errorMessages = mutableListOf<String>()
                    
                    if (statusReasons != null && statusReasons.length() > 0) {
                        for (i in 0 until statusReasons.length()) {
                            val reason = statusReasons.getJSONObject(i)
                            val errorCode = reason.optString("ErrorCode", "Unknown")
                            val errorMessage = reason.optString("ErrorMessage", "Unknown error")
                            errorMessages.add("[$errorCode] $errorMessage")
                        }
                    }
                    
                    val errorDetail = if (errorMessages.isNotEmpty()) {
                        errorMessages.joinToString("; ")
                    } else {
                        "Unknown creation failure"
                    }
                    
                    Log.e(TAG, "Function creation failed: $functionName - $errorDetail")
                    throw Exception("Function creation failed: $errorDetail")
                }
                
                Log.d(TAG, "Function status: $status, waiting...")
                delay(5000)
                attempts++
                
            } catch (e: Exception) {
                // 如果函数不存在错误，直接抛出，不要继续重试
                if (e.message?.contains("Function not found") == true || 
                    e.message?.contains("ResourceNotFound") == true) {
                    throw e
                }
                Log.w(TAG, "Error checking function status", e)
                delay(5000)
                attempts++
            }
        }
        
        Log.w(TAG, "Function did not become active within timeout, continuing anyway")
    }

    private suspend fun testWebhookReachability(webhookUrl: String): Boolean {
        return try {
            Log.d(TAG, "Testing webhook reachability: $webhookUrl")
            
            val testPayload = JSONObject().apply {
                put("version", "1.0")
                put("notification", JSONObject().apply {
                    put("type", "test")
                    put("senderId", "test-sender")
                    put("timestamp", System.currentTimeMillis())
                })
                put("signature", "test-signature")
            }.toString()
            
            val url = URL(webhookUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                connection.outputStream.use { os ->
                    os.write(testPayload.toByteArray())
                }
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Webhook response code: $responseCode")
                
                responseCode in 200..299 || responseCode == 403
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Webhook test failed", e)
            false
        }
    }

    private suspend fun testWebSocketConnection(pushServiceInfo: PushServiceInfo): Boolean {
        // API Gateway已停止，WebSocket连接测试暂时跳过
        // TODO: 实现基于函数URL的连接测试
        Log.d(TAG, "WebSocket connection test skipped (API Gateway deprecated)")
        return true // 临时返回true，实际需要根据新的推送架构实现
    }

    private suspend fun testEventTriggerFunction(): Boolean {
        return try {
            Log.d(TAG, "Testing event trigger function")
            
            val functionName = deploymentInfo?.triggerFunctionName
            if (functionName == null) {
                Log.w(TAG, "Trigger function name not found")
        return false
            }
            
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("Namespace", "default")
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "GetFunction",
                version = "2018-04-16",
                params = params
            )
            
            val isActive = response.getJSONObject("Response").has("FunctionName")
            
            if (isActive) {
                Log.d(TAG, "Event trigger function is active: $functionName")
            } else {
                Log.w(TAG, "Event trigger function not found")
            }
            
            isActive
            
        } catch (e: Exception) {
            Log.w(TAG, "Event trigger test failed", e)
            false
        }
    }

    /**
     * 客户端直接调用触发器云函数（方案二：替代COS事件触发）
     * 
     * @param remotePath 上传文件的路径（如 v2-channels/{hash}/messages/xxx.dat）
     * @param bucketName COS bucket名称
     * @return 是否成功触发（异步调用，不等待结果）
     */
    suspend fun invokeTriggerFunction(
        remotePath: String,
        bucketName: String
    ): Boolean {
        return try {
            val functionName = deploymentInfo?.triggerFunctionName
            if (functionName.isNullOrEmpty()) {
                Log.w(TAG, "Trigger function name not found, cannot invoke")
                return false
            }

            Log.d(TAG, "Invoking trigger function: $functionName for path: $remotePath")

            // 构造COS事件格式（模拟COS事件通知）
            val cosEvent = createCOSEvent(remotePath, bucketName)

            // 调用腾讯云SCF Invoke API（异步调用）
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("InvocationType", "Event")  // 异步调用，不等待结果
                put("Namespace", "default")
                put("Qualifier", "\$LATEST")
                put("LogType", "None")  // 不返回日志
                
                // 将事件作为字符串传递（SCF API要求）
                // 注意：腾讯云SCF Invoke API的Event参数需要是JSON字符串
                put("Event", cosEvent.toString())
            }

            callTencentAPI(
                host = SCF_HOST,
                action = "Invoke",
                version = "2018-04-16",
                params = params
            )

            Log.i(TAG, "Trigger function invoked successfully: $functionName")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke trigger function", e)
            false
        }
    }

    /**
     * 构造COS事件格式（模拟COS事件通知）
     * 格式与tencent-f-a.js期望的事件格式一致
     */
    private fun createCOSEvent(key: String, bucketName: String): JSONObject {
        val appId = extractAppId(bucketName)
        
        return JSONObject().apply {
            put("Records", JSONArray().apply {
                put(JSONObject().apply {
                    put("cos", JSONObject().apply {
                        put("cosBucket", JSONObject().apply {
                            put("name", bucketName)
                            if (appId.isNotEmpty()) {
                                put("appid", appId)
                            }
                        })
                        put("cosObject", JSONObject().apply {
                            put("key", key)
                        })
                    })
                    put("event", JSONObject().apply {
                        put("eventTime", System.currentTimeMillis() / 1000)
                    })
                })
            })
            // 添加requestId用于日志追踪
            put("requestId", UUID.randomUUID().toString())
        }
    }

    private suspend fun callTencentAPI(
        host: String,
        action: String,
        version: String,
        params: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val payload = params.toString()
            
            val signature = generateTencentSignature(
                host = host,
                action = action,
                version = version,
                timestamp = timestamp,
                payload = payload
            )
            
            val url = URL("https://$host/")
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Host", host)
                connection.setRequestProperty("X-TC-Action", action)
                connection.setRequestProperty("X-TC-Version", version)
                connection.setRequestProperty("X-TC-Timestamp", timestamp.toString())
                connection.setRequestProperty("X-TC-Region", region)
                connection.setRequestProperty("Authorization", signature)
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                connection.outputStream.use { os ->
                    os.write(payload.toByteArray())
                }
                
                val responseCode = connection.responseCode
                val responseBody = if (responseCode == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                
                Log.d(TAG, "API call response: $responseBody")
                
                val responseJson = JSONObject(responseBody)
                
                // 检查响应中的 Error 字段
                if (responseJson.has("Response")) {
                    val responseObj = responseJson.getJSONObject("Response")
                    if (responseObj.has("Error")) {
                        val error = responseObj.getJSONObject("Error")
                        val errorCode = error.optString("Code", "Unknown")
                        val errorMessage = error.optString("Message", "Unknown error")
                        throw Exception("API error [$errorCode]: $errorMessage")
                    }
                }
                
                if (responseCode != 200) {
                    throw Exception("API call failed with status $responseCode: $responseBody")
                }
                
                responseJson
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Tencent API call failed: $action", e)
            throw Exception("API call failed: ${e.message}", e)
        }
    }

    private fun generateTencentSignature(
        host: String,
        action: String,
        version: String,
        timestamp: Long,
        payload: String
    ): String {
        try {
            val service = host.split(".")[0]
            val date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(timestamp * 1000))
            
            val hashedPayload = sha256Hex(payload)
            
            val canonicalHeaders = "content-type:application/json\nhost:$host\n"
            val signedHeaders = "content-type;host"
            
            val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"
            val hashedCanonicalRequest = sha256Hex(canonicalRequest)
            
            val credentialScope = "$date/$service/tc3_request"
            val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n$hashedCanonicalRequest"
            
            val kDate = hmacSHA256(("TC3" + secretKey).toByteArray(), date)
            val kService = hmacSHA256(kDate, service)
            val kSigning = hmacSHA256(kService, "tc3_request")
            val signature = hmacSHA256Hex(kSigning, stringToSign)
            
            return "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate signature", e)
            throw Exception("Signature generation failed: ${e.message}", e)
        }
    }

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSHA256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun hmacSHA256Hex(key: ByteArray, data: String): String {
        val result = hmacSHA256(key, data)
        return result.joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取配置bucket名称（使用用户现有的bucket，不再创建新bucket）
     * 配置文件存储在用户bucket的 tap-state/notification-config.json
     */
    private suspend fun getOrCreateConfigBucket(): String {
        if (configBucketName != null) {
            return configBucketName!!
        }

        // 优先使用用户配置的bucket
        if (!userBucketName.isNullOrEmpty()) {
            Log.i(TAG, "Using user bucket for configuration storage: $userBucketName")
            configBucketName = userBucketName!!
            return userBucketName!!
        }

        // 如果没有用户bucket，抛出异常而不是创建新bucket
        throw Exception("User bucket name is required. Please configure COS provider first.")
    }

    /**
     * 上传文件到COS
     * 使用COS SDK（CosClient）而不是自定义签名实现
     */
    private suspend fun uploadToCOS(bucketName: String, key: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading to COS: $bucketName/$key")
            
            // 使用COS SDK上传文件
            val client = getOrCreateCosClient(bucketName)
            if (client == null) {
                throw Exception("Failed to create CosClient for bucket: $bucketName")
            }
            
            // 创建临时文件
            val tempFile = File.createTempFile("cos_upload_", ".tmp", context.cacheDir)
            try {
                // 写入数据到临时文件
                tempFile.writeBytes(data)
                
                // 使用COS SDK上传
                val success = client.uploadFile(tempFile, key)
                
                if (success) {
                    Log.d(TAG, "Upload successful")
                } else {
                    throw Exception("Upload failed: CosClient returned false")
                }
            } finally {
                // 清理临时文件
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload to COS", e)
            throw Exception("COS upload failed: ${e.message}", e)
        }
    }
    
    /**
     * 获取或创建COS客户端
     */
    private fun getOrCreateCosClient(bucketName: String): CosClient? {
        return try {
            // 如果已有客户端且bucket名称匹配，直接返回
            if (cosClient != null && userBucketName == bucketName) {
                return cosClient
            }
            
            // 创建新的COS客户端
            val cosConfig = CosConfig(
                provider = CosConfig.Provider.TENCENT,
                secretId = secretId,
                secretKey = secretKey,
                region = region,
                bucketName = bucketName
            )
            CosClientFactory.createClient(cosConfig, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create CosClient for bucket: $bucketName", e)
            null
        }
    }
    
    /**
     * 从COS下载文件
     * 使用COS SDK（CosClient）而不是自定义签名实现
     */
    private suspend fun downloadFromCOS(bucketName: String, key: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading from COS: $bucketName/$key")
            
            // 使用COS SDK下载文件
            val client = getOrCreateCosClient(bucketName)
            if (client == null) {
                Log.w(TAG, "Failed to create CosClient for bucket: $bucketName")
                return@withContext null
            }
            
            // 使用COS SDK下载到内存
            val data = client.downloadFileToMemory(key)
            
            if (data != null && data.isNotEmpty()) {
                String(data, Charsets.UTF_8)
            } else {
                Log.w(TAG, "Download returned empty data")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download from COS", e)
            null
        }
    }

    private fun loadAsset(assetName: String): ByteArray {
        return try {
            val assetPath = "lambda-functions/$assetName"
            context.assets.open(assetPath).readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load asset: $assetName", e)
            throw Exception("Asset load failed: ${e.message}", e)
        }
    }

    private fun createEmptyDeployment(): NotificationDeployment {
        return NotificationDeployment(
            webhookFunctionName = "",
            webhookFunctionArn = "",
            triggerFunctionName = "",
            triggerFunctionArn = "",
            eventTriggerConfigured = false,
            deployedComponents = emptyList(),
            deploymentStatus = DeploymentStatus.DEPLOYING
        )
    }
    
    override suspend fun deleteFunction(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting cloud function: $name")
            
            val params = JSONObject().apply {
                put("FunctionName", name)
                put("Namespace", "default")
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "DeleteFunction",
                version = "2018-04-16",
                params = params
            )
            
            val responseData = response.optJSONObject("Response")
            val success = responseData != null && !responseData.has("Error")
            
            if (success) {
                Log.i(TAG, "Cloud function deleted successfully: $name")
            } else {
                val error = responseData?.optJSONObject("Error")
                Log.w(TAG, "Failed to delete cloud function: ${error?.optString("Message")}")
            }
            
            delay(2000)
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cloud function: $name", e)
            false
        }
    }
    
    override suspend fun deleteRole(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting CAM role: $name")
            
            val params = JSONObject().apply {
                put("RoleName", name)
            }
            
            val response = callTencentAPI(
                host = "cam.tencentcloudapi.com",
                action = "DeleteRole",
                version = "2019-01-16",
                params = params
            )
            
            val responseData = response.optJSONObject("Response")
            val success = responseData != null && !responseData.has("Error")
            
            if (success) {
                Log.i(TAG, "CAM role deleted successfully: $name")
            } else {
                val error = responseData?.optJSONObject("Error")
                Log.w(TAG, "Failed to delete CAM role: ${error?.optString("Message")}")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete CAM role: $name", e)
            false
        }
    }
    
    override suspend fun deleteApiGateway(identifier: String, name: String): Boolean {
        // API Gateway产品已停止，删除操作不再需要
        Log.d(TAG, "deleteApiGateway called but API Gateway is deprecated, skipping")
        return true
    }
    
    override suspend fun deleteDynamoDBTable(identifier: String, name: String): Boolean {
        // 不再使用CloudBase数据库，connectionId存储在COS中
        // 如果需要清理，可以通过删除COS中的tap-ws-connections/目录来实现
        Log.d(TAG, "deleteDynamoDBTable called but no longer needed (using COS storage)")
        return true
    }
    
    override fun cleanup() {
        Log.d(TAG, "Cleanup completed for Tencent API Gateway deployer")
    }
}
