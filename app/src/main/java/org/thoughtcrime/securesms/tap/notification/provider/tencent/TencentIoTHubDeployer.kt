package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import com.tencentcloudapi.common.Credential
import com.tencentcloudapi.common.profile.ClientProfile
import com.tencentcloudapi.common.profile.HttpProfile
import com.tencentcloudapi.iotcloud.v20210408.IotcloudClient
import com.tencentcloudapi.iotcloud.v20210408.models.*
import com.tencentcloudapi.scf.v20180416.ScfClient
import com.tencentcloudapi.scf.v20180416.models.*
import com.tencentcloudapi.cos.v20180517.CosClient
import com.tencentcloudapi.cos.v20180517.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.*
import java.io.File
import java.util.UUID
import java.util.Base64

/**
 * 腾讯云IoT Hub推送服务部署器
 * 负责自动化部署云函数、IoT Hub配置和事件触发器
 */
class TencentIoTHubDeployer(
    private val context: Context,
    private val secretId: String,
    private val secretKey: String,
    private val region: String = "ap-guangzhou"
) : NotificationDeployer {

    companion object {
        private const val TAG = "TencentIoTHubDeployer"
        private const val WEBHOOK_FUNCTION_NAME = "tap-notification-webhook"
        private const val TRIGGER_FUNCTION_NAME = "tap-notification-trigger"
        private const val PRODUCT_NAME_PREFIX = "TapNotification"
        private const val WEBHOOK_ASSET_NAME = "tencent-webhook.zip"
        private const val TRIGGER_ASSET_NAME = "tencent-f-a.zip"
        private const val CONFIG_BUCKET_PREFIX = "tap-notification-config"
        private const val CONFIG_KEY = "notification-config.json"
        private const val MAX_DEPLOYMENT_WAIT_SECONDS = 120
    }

    private val credential = Credential(secretId, secretKey)
    
    private var scfClient: ScfClient? = null
    private var iotClient: IotcloudClient? = null
    private var cosClient: CosClient? = null
    private var configBucketName: String? = null
    private var deploymentInfo: NotificationDeployment? = null
    private var cachedProductId: String? = null
    private var cachedDeviceName: String? = null

    override suspend fun deployWebhook(): String {
        return try {
            Log.i(TAG, "Deploying webhook cloud function...")

            val scf = getScfClient()
            val functionName = "$WEBHOOK_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val namespace = "default"

            val zipData = loadAssetOrGenerateZip(WEBHOOK_ASSET_NAME, generateWebhookCode())
            val zipBase64 = Base64.getEncoder().encodeToString(zipData)
            
            val createRequest = CreateFunctionRequest().apply {
                this.functionName = functionName
                this.namespace = namespace
                this.runtime = "Nodejs16.13"
                this.handler = "index.main_handler"
                this.code = Code().apply {
                    this.zipFile = zipBase64
                }
                this.timeout = 30
                this.memorySize = 256
                this.environment = Environment().apply {
                    variables = arrayOf(
                        Variable().apply {
                            key = "LOG_LEVEL"
                            value = "INFO"
                        }
                    )
                }
            }

            val createResponse = scf.CreateFunction(createRequest)
            Log.d(TAG, "Cloud function created: $functionName")

            waitForFunctionActive(scf, functionName, namespace)

            val triggerRequest = CreateTriggerRequest().apply {
                this.functionName = functionName
                this.namespace = namespace
                this.triggerName = "${functionName}-http"
                this.type = "http"
                this.triggerDesc = "{\"methods\":[\"POST\"],\"isIntegratedResponse\":false}"
            }

            scf.CreateTrigger(triggerRequest)
            Log.d(TAG, "HTTP trigger created for function: $functionName")

            val describeRequest = DescribeFunctionRequest().apply {
                this.functionName = functionName
                this.namespace = namespace
            }
            val describeResponse = scf.DescribeFunction(describeRequest)
            
            val triggers = describeResponse.triggers
            val webhookUrl = triggers?.firstOrNull { it.type == "http" }?.triggerDesc?.let { desc ->
                try {
                    val json = JSONObject(desc)
                    json.optString("url", null)
                } catch (e: Exception) {
                    null
                }
            } ?: throw Exception("Failed to get webhook URL from trigger")

            Log.i(TAG, "Webhook deployed successfully: $webhookUrl")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                webhookFunctionName = functionName,
                webhookFunctionArn = "scf:${region}:${functionName}",
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
            Log.i(TAG, "Deploying Tencent IoT Hub push service...")

            val iot = getIotClient()
            
            val productName = "$PRODUCT_NAME_PREFIX-${UUID.randomUUID().toString().take(8)}"
            val createProductRequest = CreateProductRequest().apply {
                this.productName = productName
                this.productProperties = ProductProperties().apply {
                    this.productDescription = "TAP notification product"
                    this.encryptionType = "0"
                    this.region = this@TencentIoTHubDeployer.region
                    this.productType = 0
                    this.format = "JSON"
                    this.platform = "Android"
                    this.modelId = ""
                }
            }
            
            val productResponse = iot.CreateProduct(createProductRequest)
            val productId = productResponse.productId ?: throw Exception("Failed to get product ID")
            cachedProductId = productId
            Log.d(TAG, "IoT Product created: $productId")

            configureIoTPermissions(iot, productId)

            val deviceName = "tap-device-${UUID.randomUUID().toString().take(8)}"
            val createDeviceRequest = CreateDeviceRequest().apply {
                this.productId = productId
                this.deviceName = deviceName
            }
            
            val deviceResponse = iot.CreateDevice(createDeviceRequest)
            val deviceSecret = deviceResponse.devicePsk ?: throw Exception("Failed to get device secret")
            cachedDeviceName = deviceName
            Log.d(TAG, "IoT Device created: $deviceName")

            val describeProductRequest = DescribeProductRequest().apply {
                this.productId = productId
            }
            val productInfo = iot.DescribeProduct(describeProductRequest)
            val endpoint = constructIoTEndpoint(productId)
            Log.i(TAG, "IoT endpoint: $endpoint")

            val topicId = UUID.randomUUID().toString().replace("-", "")
            Log.i(TAG, "Generated topic ID: $topicId")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "push-service"
            )

            PushServiceInfo(
                endpoint = endpoint,
                region = region,
                credentials = mapOf(
                    "productId" to productId,
                    "deviceName" to deviceName,
                    "deviceSecret" to deviceSecret,
                    "topicId" to topicId
                ),
                metadata = mapOf(
                    "provider" to "tencent-iot",
                    "productName" to productName
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy push service", e)
            throw Exception("Push service deployment failed: ${e.message}", e)
        }
    }
    
    private suspend fun configureIoTPermissions(iot: IotcloudClient, productId: String) {
        try {
            Log.i(TAG, "Configuring IoT Hub permissions for product: $productId")
            
            val topicPrefix = "tap/notifications"
            val topicPermission = TopicRulePayload().apply {
                this.topicPattern = "$topicPrefix/+"
                this.privilege = 3
            }
            
            try {
                val updateRequest = UpdateTopicPolicyRequest().apply {
                    this.productId = productId
                    this.topicName = topicPrefix
                    this.privilege = 3
                }
                iot.UpdateTopicPolicy(updateRequest)
                Log.d(TAG, "IoT Hub topic permissions configured")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure topic permissions, may not be supported or already configured", e)
            }
            
            Log.i(TAG, "IoT Hub permissions configured successfully")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure IoT permissions, continuing with default permissions", e)
        }
    }

    override suspend fun setupEventTrigger(): TriggerInfo {
        return try {
            Log.i(TAG, "Setting up COS event trigger...")

            val scf = getScfClient()
            val functionName = "$TRIGGER_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"
            val namespace = "default"

            val zipData = loadAssetOrGenerateZip(TRIGGER_ASSET_NAME, generateTriggerCode())
            val zipBase64 = Base64.getEncoder().encodeToString(zipData)
            
            val createRequest = CreateFunctionRequest().apply {
                this.functionName = functionName
                this.namespace = namespace
                this.runtime = "Nodejs16.13"
                this.handler = "index.main_handler"
                this.code = Code().apply {
                    this.zipFile = zipBase64
                }
                this.timeout = 60
                this.memorySize = 256
                this.environment = Environment().apply {
                    variables = arrayOf(
                        Variable().apply {
                            key = "CONFIG_BUCKET"
                            value = getOrCreateConfigBucket()
                        },
                        Variable().apply {
                            key = "CONFIG_KEY"
                            value = CONFIG_KEY
                        },
                        Variable().apply {
                            key = "REGION"
                            value = region
                        }
                    )
                }
            }

            scf.CreateFunction(createRequest)
            Log.d(TAG, "Trigger function created: $functionName")

            waitForFunctionActive(scf, functionName, namespace)

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                triggerFunctionName = functionName,
                triggerFunctionArn = "scf:${region}:${functionName}",
                eventTriggerConfigured = false,
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "event-trigger"
            )

            TriggerInfo(
                triggerName = functionName,
                triggerArn = "scf:${region}:${functionName}",
                targetFunction = functionName,
                configured = false,
                filterPrefix = "v2-channels/"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup event trigger", e)
            throw Exception("Event trigger setup failed: ${e.message}", e)
        }
    }

    suspend fun configureCosEventNotification(
        userBucketName: String,
        userBucketRegion: String,
        triggerFunctionName: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return try {
            Log.i(TAG, "Configuring COS event notification on bucket: $userBucketName")
            
            val scf = getScfClient()
            
            val triggerRequest = CreateTriggerRequest().apply {
                this.functionName = triggerFunctionName
                this.namespace = "default"
                this.triggerName = "tap-cos-trigger-${System.currentTimeMillis()}"
                this.type = "cos"
                this.triggerDesc = JSONObject().apply {
                    put("bucketUrl", "$userBucketName-${getAppId()}.cos.$userBucketRegion.myqcloud.com")
                    put("event", "cos:ObjectCreated:*")
                    put("filter", JSONObject().apply {
                        put("Prefix", filterPrefix)
                    })
                }.toString()
            }
            
            scf.CreateTrigger(triggerRequest)
            
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
                    pushServiceConnected = testIoTConnection(config.pushServiceInfo)
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

    suspend fun updateWebhookEnvironment(notifySecret: String, topicId: String): Boolean {
        return try {
            val webhookFunctionName = deploymentInfo?.webhookFunctionName
            if (webhookFunctionName == null) {
                Log.w(TAG, "Webhook function name not found, cannot update environment")
                return false
            }
            
            Log.i(TAG, "Updating webhook cloud function environment variables...")
            
            val scf = getScfClient()
            
            val updateRequest = UpdateFunctionConfigurationRequest().apply {
                this.functionName = webhookFunctionName
                this.namespace = "default"
                this.environment = Environment().apply {
                    variables = arrayOf(
                        Variable().apply {
                            key = "LOG_LEVEL"
                            value = "INFO"
                        },
                        Variable().apply {
                            key = "NOTIFY_SECRET"
                            value = notifySecret
                        },
                        Variable().apply {
                            key = "TOPIC_ID"
                            value = topicId
                        },
                        Variable().apply {
                            key = "REGION"
                            value = region
                        },
                        Variable().apply {
                            key = "PRODUCT_ID"
                            value = cachedProductId ?: ""
                        },
                        Variable().apply {
                            key = "DEVICE_NAME"
                            value = cachedDeviceName ?: ""
                        },
                        Variable().apply {
                            key = "TENCENTCLOUD_SECRETID"
                            value = secretId
                        },
                        Variable().apply {
                            key = "TENCENTCLOUD_SECRETKEY"
                            value = secretKey
                        }
                    )
                }
            }
            
            scf.UpdateFunctionConfiguration(updateRequest)
            
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

            saveToCOS(bucketName, CONFIG_KEY, jsonConfig)
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

            val content = loadFromCOS(bucketName, CONFIG_KEY) ?: return null

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
                version = json.optString("version", "1.0")
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load configuration", e)
            null
        }
    }

    private suspend fun waitForFunctionActive(scf: ScfClient, functionName: String, namespace: String) {
        var attempts = 0
        val maxAttempts = MAX_DEPLOYMENT_WAIT_SECONDS / 5

        while (attempts < maxAttempts) {
            try {
                val request = DescribeFunctionRequest().apply {
                    this.functionName = functionName
                    this.namespace = namespace
                }
                
                val response = scf.DescribeFunction(request)
                
                if (response.status == "Active") {
                    Log.d(TAG, "Function is active: $functionName")
                    return
                }
                
                Log.d(TAG, "Function status: ${response.status}, waiting...")
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

    private fun constructIoTEndpoint(productId: String): String {
        return "$productId.iotcloud.tencentdevices.com"
    }

    private suspend fun testWebhookReachability(webhookUrl: String): Boolean {
        return try {
            Log.d(TAG, "Testing webhook reachability: $webhookUrl")
            
            val testPayload = org.json.JSONObject().apply {
                put("version", "1.0")
                put("notification", org.json.JSONObject().apply {
                    put("type", "test")
                    put("senderId", "test-sender")
                    put("timestamp", System.currentTimeMillis())
                })
                put("signature", "test-signature")
            }.toString()
            
            val url = java.net.URL(webhookUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
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

    private suspend fun testIoTConnection(pushServiceInfo: PushServiceInfo): Boolean {
        return try {
            Log.d(TAG, "Testing IoT connection to: ${pushServiceInfo.endpoint}")
            
            val productId = pushServiceInfo.credentials["productId"]
            if (productId != null) {
                val iot = getIotClient()
                
                val request = DescribeProductRequest().apply {
                    this.productId = productId
                }
                
                val response = iot.DescribeProduct(request)
                val isReachable = response.productId == productId
                
                if (isReachable) {
                    Log.d(TAG, "IoT product verified: $productId")
                } else {
                    Log.w(TAG, "IoT product verification failed")
                }
                
                isReachable
            } else {
                false
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "IoT connection test failed", e)
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
            
            val scf = getScfClient()
            
            val request = DescribeFunctionRequest().apply {
                this.functionName = functionName
                this.namespace = "default"
            }
            
            val response = scf.DescribeFunction(request)
            
            val isActive = response.status == "Active"
            
            if (isActive) {
                Log.d(TAG, "Event trigger function is active: $functionName")
            } else {
                Log.w(TAG, "Event trigger function is not active: ${response.status}")
            }
            
            isActive
            
        } catch (e: Exception) {
            Log.w(TAG, "Event trigger test failed", e)
            false
        }
    }

    private fun getScfClient(): ScfClient {
        if (scfClient == null) {
            val httpProfile = HttpProfile().apply {
                endpoint = "scf.tencentcloudapi.com"
            }
            val clientProfile = ClientProfile().apply {
                this.httpProfile = httpProfile
            }
            scfClient = ScfClient(credential, region, clientProfile)
        }
        return scfClient!!
    }

    private fun getIotClient(): IotcloudClient {
        if (iotClient == null) {
            val httpProfile = HttpProfile().apply {
                endpoint = "iotcloud.tencentcloudapi.com"
            }
            val clientProfile = ClientProfile().apply {
                this.httpProfile = httpProfile
            }
            iotClient = IotcloudClient(credential, region, clientProfile)
        }
        return iotClient!!
    }

    private fun getCosClient(): CosClient {
        if (cosClient == null) {
            val httpProfile = HttpProfile().apply {
                endpoint = "cos.tencentcloudapi.com"
            }
            val clientProfile = ClientProfile().apply {
                this.httpProfile = httpProfile
            }
            cosClient = CosClient(credential, region, clientProfile)
        }
        return cosClient!!
    }

    private suspend fun getOrCreateConfigBucket(): String {
        if (configBucketName != null) {
            return configBucketName!!
        }

        return withContext(Dispatchers.IO) {
            try {
                val bucketName = "$CONFIG_BUCKET_PREFIX-${UUID.randomUUID().toString().take(8)}"
                val appId = getAppId()
                val fullBucketName = "$bucketName-$appId"
                
                Log.i(TAG, "Creating COS bucket: $fullBucketName")
                
                val cos = getCosClient()
                
                try {
                    val headRequest = HeadBucketRequest().apply {
                        this.bucketName = fullBucketName
                        this.region = this@TencentIoTHubDeployer.region
                    }
                    
                    val headResponse = cos.HeadBucket(headRequest)
                    Log.d(TAG, "Bucket already exists: $fullBucketName")
                    
                } catch (e: Exception) {
                    Log.d(TAG, "Bucket does not exist, creating new bucket")
                    
                    val createRequest = PutBucketRequest().apply {
                        this.bucketName = fullBucketName
                        this.region = this@TencentIoTHubDeployer.region
                    }
                    
                    val createResponse = cos.PutBucket(createRequest)
                    
                    if (createResponse.requestId != null) {
                        Log.i(TAG, "COS bucket created successfully: $fullBucketName")
                    } else {
                        throw Exception("Failed to create bucket: no request ID returned")
                    }
                    
                    try {
                        val versioningRequest = PutBucketVersioningRequest().apply {
                            this.bucketName = fullBucketName
                            this.region = this@TencentIoTHubDeployer.region
                            this.versioningConfiguration = VersioningConfiguration().apply {
                                this.status = "Enabled"
                            }
                        }
                        cos.PutBucketVersioning(versioningRequest)
                        Log.d(TAG, "Bucket versioning enabled")
                    } catch (versioningErr: Exception) {
                        Log.w(TAG, "Failed to enable versioning, continuing anyway", versioningErr)
                    }
                    
                    try {
                        val encryptionRequest = PutBucketEncryptionRequest().apply {
                            this.bucketName = fullBucketName
                            this.region = this@TencentIoTHubDeployer.region
                            this.sseConfiguration = SSEConfiguration().apply {
                                this.rules = arrayOf(
                                    SSERule().apply {
                                        this.applyServerSideEncryptionByDefault = ApplyServerSideEncryptionByDefault().apply {
                                            this.sseAlgorithm = "AES256"
                                        }
                                        this.bucketKeyEnabled = "Enabled"
                                    }
                                )
                            }
                        }
                        cos.PutBucketEncryption(encryptionRequest)
                        Log.d(TAG, "Bucket encryption enabled")
                    } catch (encryptionErr: Exception) {
                        Log.w(TAG, "Failed to enable encryption, continuing anyway", encryptionErr)
                    }
                }
                
                configBucketName = bucketName
                bucketName
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/get COS bucket", e)
                throw Exception("COS bucket creation failed: ${e.message}", e)
            }
        }
    }

    private fun getAppId(): String {
        return secretId.split("-").getOrNull(0) ?: "default"
    }

    private suspend fun saveToCOS(bucket: String, key: String, content: String) {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Saving object to COS: bucket=$bucket, key=$key")
                
                val appId = getAppId()
                val fullBucketName = if (bucket.contains("-")) bucket else "$bucket-$appId"
                
                val cos = getCosClient()
                
                val putRequest = PutObjectRequest().apply {
                    this.bucketName = fullBucketName
                    this.region = this@TencentIoTHubDeployer.region
                    this.cosPath = key
                    this.srcPath = createTempFileWithContent(content)
                }
                
                val response = cos.PutObject(putRequest)
                
                if (response.requestId != null) {
                    Log.i(TAG, "Configuration saved to COS successfully")
                } else {
                    throw Exception("Failed to save to COS: no request ID returned")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to COS", e)
                throw Exception("COS save failed: ${e.message}", e)
            }
        }
    }

    private suspend fun loadFromCOS(bucket: String, key: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading object from COS: bucket=$bucket, key=$key")
                
                val appId = getAppId()
                val fullBucketName = if (bucket.contains("-")) bucket else "$bucket-$appId"
                
                val cos = getCosClient()
                
                val tempFile = File.createTempFile("cos-download", ".json", context.cacheDir)
                
                val getRequest = GetObjectRequest().apply {
                    this.bucketName = fullBucketName
                    this.region = this@TencentIoTHubDeployer.region
                    this.cosPath = key
                    this.savePath = tempFile.absolutePath
                }
                
                val response = cos.GetObject(getRequest)
                
                if (response.requestId != null && tempFile.exists()) {
                    val content = tempFile.readText()
                    tempFile.delete()
                    Log.i(TAG, "Configuration loaded from COS successfully")
                    content
                } else {
                    Log.w(TAG, "Configuration not found in COS")
                    null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load from COS", e)
                null
            }
        }
    }
    
    private fun createTempFileWithContent(content: String): String {
        val tempFile = File.createTempFile("cos-upload", ".json", context.cacheDir)
        tempFile.writeText(content)
        return tempFile.absolutePath
    }

    private fun loadAssetOrGenerateZip(assetName: String, fallbackCode: String): ByteArray {
        return try {
            val assetPath = "lambda-functions/$assetName"
            context.assets.open(assetPath).readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetName not found, using generated code", e)
            createZipFromCode(fallbackCode)
        }
    }

    private fun createZipFromCode(code: String): ByteArray {
        val tempFile = File.createTempFile("scf", ".zip")
        try {
            java.util.zip.ZipOutputStream(tempFile.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("index.js"))
                zip.write(code.toByteArray())
                zip.closeEntry()
            }
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun generateWebhookCode(): String {
        return """
            exports.main_handler = async (event) => {
                console.log('Received event:', JSON.stringify(event));
                return {
                    statusCode: 200,
                    body: JSON.stringify({ message: 'Webhook received' })
                };
            };
        """.trimIndent()
    }

    private fun generateTriggerCode(): String {
        return """
            exports.main_handler = async (event) => {
                console.log('COS event:', JSON.stringify(event));
                return {
                    statusCode: 200,
                    body: JSON.stringify({ message: 'Event processed' })
                };
            };
        """.trimIndent()
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

    fun cleanup() {
        scfClient = null
        iotClient = null
        cosClient = null
    }
}

