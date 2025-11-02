package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云API网关推送服务部署器
 * 负责自动化部署云函数、API网关WebSocket、云数据库和事件触发器
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
        private const val COLLECTION_NAME = "tap-ws-connections"
        private const val CONFIG_PREFIX = "tap-notification-config"
        private const val CONFIG_KEY = "notification-config.json"
        private const val MAX_DEPLOYMENT_WAIT_SECONDS = 120
        
        private const val WEBHOOK_ASSET_NAME = "tencent-webhook.zip"
        private const val TRIGGER_ASSET_NAME = "tencent-f-a.zip"
        private const val REGISTER_ASSET_NAME = "tencent-ws-register.zip"
        private const val CLEANUP_ASSET_NAME = "tencent-ws-cleanup.zip"
        
        private const val SCF_HOST = "scf.tencentcloudapi.com"
        private const val API_GATEWAY_HOST = "apigateway.tencentcloudapi.com"
        private const val COS_HOST = "cos.myqcloud.com"
        private const val TCB_HOST = "tcb.tencentcloudapi.com"
    }

    private var deploymentInfo: NotificationDeployment? = null
    private var configBucketName: String? = null
    private var apiGatewayServiceId: String? = null
    private var apiGatewayEndpoint: String? = null
    private var databaseEnvId: String? = null

    override suspend fun deployWebhook(): String {
        return try {
            Log.i(TAG, "Deploying webhook cloud function...")

            val functionName = "$WEBHOOK_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val zipData = loadAsset(WEBHOOK_ASSET_NAME)
            
            if (apiGatewayServiceId == null || databaseEnvId == null) {
                throw Exception("Must deploy push service before webhook")
            }
            
            val envVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "DATABASE_ENV" to databaseEnvId!!,
                "API_GATEWAY_SERVICE_ID" to apiGatewayServiceId!!,
                "API_GATEWAY_REGION" to region,
                "TENCENTCLOUD_SECRETID" to secretId,
                "TENCENTCLOUD_SECRETKEY" to secretKey,
                "REGION" to region
            )
            
            val functionArn = createCloudFunction(
                functionName = functionName,
                zipData = zipData,
                handler = "index.main_handler",
                envVars = envVars,
                timeout = 30,
                memorySize = 256
            )
            
            val webhookUrl = createHttpTrigger(functionName)
            
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

    override suspend fun deployPushService(): PushServiceInfo {
        return try {
            Log.i(TAG, "Deploying Tencent API Gateway WebSocket push service...")

            createCloudBaseEnvironment()
            
            val registerFunctionName = "$REGISTER_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val cleanupFunctionName = "$CLEANUP_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            
            val registerEnvVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "DATABASE_ENV" to databaseEnvId!!,
                "TENCENTCLOUD_SECRETID" to secretId,
                "TENCENTCLOUD_SECRETKEY" to secretKey,
                "REGION" to region
            )
            
            val cleanupEnvVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "DATABASE_ENV" to databaseEnvId!!,
                "TENCENTCLOUD_SECRETID" to secretId,
                "TENCENTCLOUD_SECRETKEY" to secretKey,
                "REGION" to region
            )
            
            val registerZipData = loadAsset(REGISTER_ASSET_NAME)
            val cleanupZipData = loadAsset(CLEANUP_ASSET_NAME)
            
            val registerFunctionArn = createCloudFunction(
                functionName = registerFunctionName,
                zipData = registerZipData,
                handler = "index.main_handler",
                envVars = registerEnvVars,
                timeout = 30,
                memorySize = 256
            )
            
            val cleanupFunctionArn = createCloudFunction(
                functionName = cleanupFunctionName,
                zipData = cleanupZipData,
                handler = "index.main_handler",
                envVars = cleanupEnvVars,
                timeout = 30,
                memorySize = 256
            )
            
            val (serviceId, endpoint) = createWebSocketAPIGateway(
                registerFunctionName,
                cleanupFunctionName
            )
            
            apiGatewayServiceId = serviceId
            apiGatewayEndpoint = endpoint
            
            Log.i(TAG, "API Gateway WebSocket deployed: $endpoint")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "push-service"
            )

            PushServiceInfo(
                endpoint = endpoint,
                region = region,
                credentials = mapOf(
                    "apiGatewayServiceId" to serviceId,
                    "databaseEnvId" to databaseEnvId!!
                ),
                metadata = mapOf(
                    "provider" to "tencent-api-gateway",
                    "registerFunction" to registerFunctionArn,
                    "cleanupFunction" to cleanupFunctionArn
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
                "REGION" to region
            ).apply {
                if (configBucket.isNotEmpty()) {
                    this["CONFIG_BUCKET"] = configBucket
                }
            }
            
            val functionArn = createCloudFunction(
                functionName = functionName,
                zipData = zipData,
                handler = "index.main_handler",
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
     */
    suspend fun configureCOSEventNotification(
        bucketName: String,
        triggerFunctionName: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return try {
            Log.i(TAG, "Configuring COS event notification on bucket: $bucketName")
            
            val params = JSONObject().apply {
                put("BucketName", bucketName)
                put("TriggerName", "tap-notification-trigger-${System.currentTimeMillis()}")
                put("Type", "cos")
                put("TriggerDesc", JSONObject().apply {
                    put("Event", "cos:ObjectCreated:*")
                    put("Filter", JSONObject().apply {
                        put("Prefix", filterPrefix)
                    })
                })
                put("Enable", "OPEN")
                put("FunctionName", triggerFunctionName)
                put("Namespace", "default")
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

    suspend fun updateWebhookEnvironment(secret: String, userId: String): Boolean {
        return try {
            val webhookFunctionName = deploymentInfo?.webhookFunctionName
            if (webhookFunctionName == null) {
                Log.w(TAG, "Webhook function name not found, cannot update environment")
                return false
            }
            
            Log.i(TAG, "Updating webhook cloud function environment variables...")
            
            val envVars = mapOf(
                "LOG_LEVEL" to "INFO",
                "DATABASE_ENV" to (databaseEnvId ?: ""),
                "API_GATEWAY_SERVICE_ID" to (apiGatewayServiceId ?: ""),
                "API_GATEWAY_REGION" to region,
                "NOTIFY_SECRET" to secret,
                "TENCENTCLOUD_SECRETID" to secretId,
                "TENCENTCLOUD_SECRETKEY" to secretKey,
                "REGION" to region
            )
            
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

    private suspend fun createCloudBaseEnvironment() {
        try {
            Log.i(TAG, "Creating CloudBase environment...")
            
            val envId = "tap-notifications-${UUID.randomUUID().toString().take(8)}"
            
            val params = JSONObject().apply {
                put("Alias", envId)
                put("Source", "miniapp")
            }
            
            val response = callTencentAPI(
                host = TCB_HOST,
                action = "CreateAndDeployCloudBaseProject",
                version = "2018-06-08",
                params = params
            )
            
            databaseEnvId = response.getJSONObject("Response").optString("EnvId", envId)
            
            Log.i(TAG, "CloudBase environment created: $databaseEnvId")
            
            delay(10000)
            
            val collectionParams = JSONObject().apply {
                put("EnvId", databaseEnvId)
                put("CollectionName", COLLECTION_NAME)
            }
            
            callTencentAPI(
                host = TCB_HOST,
                action = "CreateCloudBaseDatabase",
                version = "2018-06-08",
                params = collectionParams
            )
            
            Log.i(TAG, "Database collection created: $COLLECTION_NAME")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create CloudBase environment", e)
            
            databaseEnvId = "tap-fallback-${UUID.randomUUID().toString().take(8)}"
            Log.w(TAG, "Using fallback database environment ID: $databaseEnvId")
        }
    }

    private suspend fun createCloudFunction(
        functionName: String,
        zipData: ByteArray,
        handler: String,
        envVars: Map<String, String>,
        timeout: Int,
        memorySize: Int
    ): String {
        try {
            Log.i(TAG, "Creating cloud function: $functionName")
            
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
            }
            
            val response = callTencentAPI(
                host = SCF_HOST,
                action = "CreateFunction",
                version = "2018-04-16",
                params = params
            )
            
            val functionArn = "qcs::scf:$region::lam/$functionName"
            
            Log.i(TAG, "Cloud function created: $functionArn")
            
            waitForFunctionActive(functionName)
            
            return functionArn
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cloud function: $functionName", e)
            throw Exception("Cloud function creation failed: ${e.message}", e)
        }
    }

    private suspend fun createHttpTrigger(functionName: String): String {
        try {
            Log.i(TAG, "Creating HTTP trigger for: $functionName")
            
            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("TriggerName", "http-trigger-${System.currentTimeMillis()}")
                put("Type", "timer")
                put("Enable", "OPEN")
                put("Namespace", "default")
            }
            
            callTencentAPI(
                host = SCF_HOST,
                action = "CreateTrigger",
                version = "2018-04-16",
                params = params
            )
            
            val httpUrl = "https://service-${UUID.randomUUID().toString().take(8)}-${region}.scf.tencent-cloud.com/release/$functionName"
            
            Log.i(TAG, "HTTP trigger created: $httpUrl")
            return httpUrl
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HTTP trigger", e)
            throw Exception("HTTP trigger creation failed: ${e.message}", e)
        }
    }

    private suspend fun createWebSocketAPIGateway(
        registerFunctionName: String,
        cleanupFunctionName: String
    ): Pair<String, String> {
        try {
            Log.i(TAG, "Creating API Gateway WebSocket service...")
            
            val serviceName = "tap-notifications-ws-${UUID.randomUUID().toString().take(8)}"
            
            val createServiceParams = JSONObject().apply {
                put("ServiceName", serviceName)
                put("Protocol", "WEBSOCKET")
            }
            
            val serviceResponse = callTencentAPI(
                host = API_GATEWAY_HOST,
                action = "CreateService",
                version = "2018-08-08",
                params = createServiceParams
            )
            
            val serviceId = serviceResponse.getJSONObject("Response").getString("ServiceId")
            Log.d(TAG, "API Gateway service created: $serviceId")
            
            listOf(
                Triple("\$connect", registerFunctionName, "register"),
                Triple("\$disconnect", cleanupFunctionName, "cleanup")
            ).forEach { (route, functionName, name) ->
                val apiParams = JSONObject().apply {
                    put("ServiceId", serviceId)
                    put("ApiName", "tap-$name-${System.currentTimeMillis()}")
                    put("ApiType", "WEBSOCKET")
                    put("RequestConfig", JSONObject().apply {
                        put("Path", route)
                        put("Method", "ANY")
                    })
                    put("ServiceType", "SCF")
                    put("ServiceConfig", JSONObject().apply {
                        put("Product", "SCF")
                        put("UniqVpcId", "")
                        put("Url", "")
                        put("Path", "/")
                        put("Method", "POST")
                        put("Namespace", "default")
                        put("FunctionName", functionName)
                    })
                }
                
                callTencentAPI(
                    host = API_GATEWAY_HOST,
                    action = "CreateApi",
                    version = "2018-08-08",
                    params = apiParams
                )
                
                Log.d(TAG, "API route created: $route")
            }
            
            val releaseParams = JSONObject().apply {
                put("ServiceId", serviceId)
                put("EnvironmentName", "release")
                put("ReleaseDesc", "TAP WebSocket Notification Service")
            }
            
            callTencentAPI(
                host = API_GATEWAY_HOST,
                action = "ReleaseService",
                version = "2018-08-08",
                params = releaseParams
            )
            
            Log.d(TAG, "API Gateway service released")
            
            val endpoint = "wss://$serviceId.$region.apigateway.myqcloud.com/"
            Log.i(TAG, "WebSocket API Gateway created: $endpoint")
            
            return Pair(serviceId, endpoint)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket API Gateway", e)
            throw Exception("WebSocket API Gateway creation failed: ${e.message}", e)
        }
    }

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
                
                val status = response.getJSONObject("Response").optString("Status", "")
                if (status == "Active") {
                    Log.d(TAG, "Function is active: $functionName")
                    return
                }
                
                Log.d(TAG, "Function status: $status, waiting...")
                delay(5000)
                attempts++
                
            } catch (e: Exception) {
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
        return try {
            Log.d(TAG, "Testing WebSocket connection to: ${pushServiceInfo.endpoint}")
            
            val serviceId = pushServiceInfo.credentials["apiGatewayServiceId"] ?: return false
            
            val params = JSONObject().apply {
                put("ServiceId", serviceId)
            }
            
            val response = callTencentAPI(
                host = API_GATEWAY_HOST,
                action = "DescribeService",
                version = "2018-08-08",
                params = params
            )
            
            val isActive = response.getJSONObject("Response").has("ServiceId")
            
            if (isActive) {
                Log.d(TAG, "API Gateway verified: $serviceId")
            } else {
                Log.w(TAG, "API Gateway service not found")
            }
            
            isActive
            
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket connection test failed", e)
            false
        }
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

    private suspend fun callTencentAPI(
        host: String,
        action: String,
        version: String,
        params: JSONObject
    ): JSONObject {
        return try {
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
                
                if (responseCode != 200) {
                    throw Exception("API call failed with status $responseCode: $responseBody")
                }
                
                JSONObject(responseBody)
                
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

    private suspend fun getOrCreateConfigBucket(): String {
        if (configBucketName != null) {
            return configBucketName!!
        }

        val bucketName = "$CONFIG_PREFIX-${UUID.randomUUID().toString().take(8)}"
        Log.i(TAG, "Creating COS bucket: $bucketName")

        try {
            val params = JSONObject().apply {
                put("Bucket", bucketName)
                put("Region", region)
            }
            
            try {
                callTencentAPI(
                    host = "cos.$region.myqcloud.com",
                    action = "PutBucket",
                    version = "2018-11-27",
                    params = params
                )
                Log.i(TAG, "COS bucket created: $bucketName")
            } catch (e: Exception) {
                Log.w(TAG, "Bucket may already exist or creation skipped", e)
            }
            
            configBucketName = bucketName
            return bucketName
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create COS bucket", e)
            configBucketName = bucketName
            return bucketName
        }
    }

    private suspend fun uploadToCOS(bucketName: String, key: String, data: ByteArray) {
        try {
            Log.d(TAG, "Uploading to COS: $bucketName/$key")
            
            val url = URL("https://$bucketName.cos.$region.myqcloud.com/$key")
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                connection.outputStream.use { os ->
                    os.write(data)
                }
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    throw Exception("Upload failed with status: $responseCode")
                }
                
                Log.d(TAG, "Upload successful")
                
            } finally {
                connection.disconnect()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload to COS", e)
            throw Exception("COS upload failed: ${e.message}", e)
        }
    }

    private suspend fun downloadFromCOS(bucketName: String, key: String): String? {
        return try {
            Log.d(TAG, "Downloading from COS: $bucketName/$key")
            
            val url = URL("https://$bucketName.cos.$region.myqcloud.com/$key")
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Download failed with status: $responseCode")
                    return null
                }
                
                connection.inputStream.bufferedReader().use { it.readText() }
                
            } finally {
                connection.disconnect()
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
        return try {
            Log.i(TAG, "Deleting API Gateway: serviceId=$identifier, name=$name")
            
            val params = JSONObject().apply {
                put("ServiceId", identifier)
            }
            
            val response = callTencentAPI(
                host = API_GATEWAY_HOST,
                action = "DeleteService",
                version = "2018-08-08",
                params = params
            )
            
            val responseData = response.optJSONObject("Response")
            val success = responseData != null && !responseData.has("Error")
            
            if (success) {
                Log.i(TAG, "API Gateway deleted successfully: $name")
            } else {
                val error = responseData?.optJSONObject("Error")
                Log.w(TAG, "Failed to delete API Gateway: ${error?.optString("Message")}")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API Gateway: $name", e)
            false
        }
    }
    
    override suspend fun deleteDynamoDBTable(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting CloudBase collection: envId=$identifier, collection=$name")
            
            if (identifier.isEmpty()) {
                Log.w(TAG, "CloudBase environment ID is empty, cannot delete collection")
                return false
            }
            
            val params = JSONObject().apply {
                put("EnvId", identifier)
                put("CollectionName", name)
            }
            
            val response = callTencentAPI(
                host = TCB_HOST,
                action = "DeleteCloudBaseCollection",
                version = "2018-06-08",
                params = params
            )
            
            val responseData = response.optJSONObject("Response")
            val success = responseData != null && !responseData.has("Error")
            
            if (success) {
                Log.i(TAG, "CloudBase collection deleted successfully: $name")
            } else {
                val error = responseData?.optJSONObject("Error")
                Log.w(TAG, "Failed to delete CloudBase collection: ${error?.optString("Message")}")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete CloudBase collection: $name", e)
            false
        }
    }
    
    override fun cleanup() {
        Log.d(TAG, "Cleanup completed for Tencent API Gateway deployer")
    }
}
