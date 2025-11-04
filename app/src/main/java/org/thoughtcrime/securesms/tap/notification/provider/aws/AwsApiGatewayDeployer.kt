package org.thoughtcrime.securesms.tap.notification.provider.aws

import android.content.Context
import android.util.Log
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.apigatewayv2.ApiGatewayV2Client
import aws.sdk.kotlin.services.apigatewayv2.model.*
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.sdk.kotlin.services.iam.IamClient
import aws.sdk.kotlin.services.iam.model.*
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.*
import aws.sdk.kotlin.services.lambda.model.Cors as LambdaCors
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * AWS API Gateway推送服务部署器
 * 负责自动化部署Lambda函数、API Gateway WebSocket和事件触发器
 * connectionId存储到用户配置的S3 bucket中
 */
class AwsApiGatewayDeployer(
    private val context: Context,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String = "us-east-1"
) : NotificationDeployer {

    companion object {
        private const val TAG = "AwsApiGatewayDeployer"
        private const val WEBHOOK_FUNCTION_NAME = "tap-notification-webhook"
        private const val TRIGGER_FUNCTION_NAME = "tap-notification-trigger"
        private const val CONNECT_FUNCTION_NAME = "tap-ws-connect"
        private const val DISCONNECT_FUNCTION_NAME = "tap-ws-disconnect"
        private const val DEFAULT_FUNCTION_NAME = "tap-ws-default"
        private const val CONFIG_DIR = "tap-state"
        private const val CONFIG_KEY = "tap-state/notification-config.json"
        private const val MAX_DEPLOYMENT_WAIT_SECONDS = 120
        
        private const val WEBHOOK_ASSET_NAME = "aws-webhook.zip"
        private const val TRIGGER_ASSET_NAME = "aws-f-a.zip"
        private const val CONNECT_ASSET_NAME = "aws-ws-connect.zip"
        private const val DISCONNECT_ASSET_NAME = "aws-ws-disconnect.zip"
        private const val DEFAULT_ASSET_NAME = "aws-ws-default.zip"
    }

    private val credentialsProvider = StaticCredentialsProvider {
        accessKeyId = this@AwsApiGatewayDeployer.accessKeyId
        secretAccessKey = this@AwsApiGatewayDeployer.secretAccessKey
    }

    private var lambdaClient: LambdaClient? = null
    private var dynamoDbClient: DynamoDbClient? = null
    private var apiGatewayClient: ApiGatewayV2Client? = null
    private var s3Client: S3Client? = null
    private var iamClient: IamClient? = null
    private var stsClient: StsClient? = null
    
    private var userBucketName: String? = null
    private var configBucketName: String? = null
    private var deploymentInfo: NotificationDeployment? = null
    private var cachedAccountId: String? = null
    private var cachedRoleArn: String? = null
    private var apiGatewayId: String? = null
    private var apiGatewayEndpoint: String? = null

    /**
     * 设置用户bucket名称（从COS provider配置中获取）
     */
    fun setUserBucketName(bucketName: String?) {
        userBucketName = bucketName
        if (!bucketName.isNullOrEmpty()) {
            configBucketName = bucketName
        }
    }

    override suspend fun deployWebhook(): String {
        return try {
            Log.i(TAG, "Deploying webhook Lambda function...")

            val lambda = getLambdaClient()

            // 清理已有的旧Webhook函数，避免遗留的URL/连接造成混乱
            try {
                val prevFunction = deploymentInfo?.webhookFunctionName
                if (!prevFunction.isNullOrEmpty()) {
                    Log.i(TAG, "Found previous webhook function, deleting: $prevFunction")
                    lambda.deleteFunction(DeleteFunctionRequest { functionName = prevFunction })
                    Log.d(TAG, "Previous webhook function deleted: $prevFunction")
                }
            } catch (cleanupErr: Exception) {
                Log.w(TAG, "Failed to cleanup previous webhook function (continuing)", cleanupErr)
            }

            val functionName = "$WEBHOOK_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"

            val zipData = loadAsset(WEBHOOK_ASSET_NAME)
            
            if (apiGatewayEndpoint == null) {
                throw Exception("Must deploy push service before webhook")
            }
            
            val roleArn = createOrGetLambdaExecutionRole()
            
            val createRequest = CreateFunctionRequest {
                this.functionName = functionName
                this.runtime = Runtime.Nodejs20X
                this.role = roleArn
                this.handler = "index.handler"
                this.code = FunctionCode {
                    this.zipFile = zipData
                }
                this.timeout = 30
                this.memorySize = 256
                this.environment = Environment {
                    variables = buildMap {
                        put("LOG_LEVEL", "INFO")
                        // Management API 需要 HTTPS 端点
                        val mgmtEndpoint = (apiGatewayEndpoint ?: "").replace("wss://", "https://")
                        put("API_GATEWAY_ENDPOINT", mgmtEndpoint)
                        // CONNECTIONS_BUCKET 将通过 updateWebhookEnvironment 设置
                    }
                }
            }

            val createResponse = lambda.createFunction(createRequest)
            Log.d(TAG, "Lambda function created: ${createResponse.functionArn}")

            waitForFunctionActive(lambda, functionName)

            val urlConfig = lambda.createFunctionUrlConfig(
                CreateFunctionUrlConfigRequest {
                    this.functionName = functionName
                    this.authType = FunctionUrlAuthType.None
                    this.cors = LambdaCors {
                        this.allowOrigins = listOf("*")
                        this.allowMethods = listOf("POST")
                        this.allowHeaders = listOf("*")
                    }
                }
            )

            val webhookUrl = urlConfig.functionUrl ?: throw Exception("Failed to get function URL")
            Log.i(TAG, "Webhook deployed successfully: $webhookUrl")

            // Add required Function URL permissions per AWS 2025-10 update
            try {
                lambda.addPermission(
                    AddPermissionRequest {
                        this.functionName = functionName
                        this.statementId = "AllowFunctionUrlInvoke-${System.currentTimeMillis()}"
                        this.action = "lambda:InvokeFunctionUrl"
                        this.principal = "*"
                        // Restrict to NONE auth type
                        this.functionUrlAuthType = FunctionUrlAuthType.None
                    }
                )
                Log.d(TAG, "Added permission: lambda:InvokeFunctionUrl for NONE auth")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add lambda:InvokeFunctionUrl permission (may already exist)", e)
            }

            // P0修复：添加lambda:InvokeFunction权限（AWS 2025-10要求）
            // 根据AWS文档，需要添加condition {lambda:InvokedViaFunctionUrl=true}限制只能通过Function URL调用
            // 由于AWS SDK Kotlin的AddPermissionRequest不直接支持condition字段，
            // 我们通过添加资源策略语句来实现相同的效果
            try {
                // 方法1：尝试通过addPermission添加基础权限
                lambda.addPermission(
                    AddPermissionRequest {
                        this.functionName = functionName
                        this.statementId = "AllowInvokeFunctionViaUrl-${System.currentTimeMillis()}"
                        this.action = "lambda:InvokeFunction"
                        this.principal = "*"
                        // AWS SDK Kotlin目前不支持condition参数
                        // 但Function URL的NONE auth type本身已提供了公开访问
                        // Lambda函数内部会验证webhook签名，提供额外的安全层
                    }
                )
                Log.d(TAG, "Added permission: lambda:InvokeFunction for Function URL access")
                
                // 方法2：记录到部署信息中，以便后续通过AWS CLI或Console手动添加condition
                Log.i(TAG, "IMPORTANT: For enhanced security, manually add condition to the InvokeFunction permission:")
                Log.i(TAG, "  Condition: { \"Bool\": { \"lambda:InvokedViaFunctionUrl\": \"true\" } }")
                Log.i(TAG, "  This restricts invocation to Function URL only")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add lambda:InvokeFunction permission (may already exist)", e)
            }

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                webhookFunctionName = functionName,
                webhookFunctionArn = createResponse.functionArn ?: "",
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
            Log.i(TAG, "Deploying AWS API Gateway WebSocket push service...")

            if (userBucketName.isNullOrEmpty()) {
                throw Exception("User bucket name is required for connectionId storage")
            }

            val roleArn = createOrGetLambdaExecutionRole()
            
            val connectFunctionArn = deployLambdaFunction(
                name = CONNECT_FUNCTION_NAME,
                assetName = CONNECT_ASSET_NAME,
                handler = "index.handler",
                envVars = mapOf(
                    "LOG_LEVEL" to "INFO",
                    "CONNECTIONS_BUCKET" to userBucketName!!
                ),
                roleArn = roleArn
            )
            
            val disconnectFunctionArn = deployLambdaFunction(
                name = DISCONNECT_FUNCTION_NAME,
                assetName = DISCONNECT_ASSET_NAME,
                handler = "index.handler",
                envVars = mapOf(
                    "LOG_LEVEL" to "INFO",
                    "CONNECTIONS_BUCKET" to userBucketName!!
                ),
                roleArn = roleArn
            )
            
            val defaultFunctionArn = deployLambdaFunction(
                name = DEFAULT_FUNCTION_NAME,
                assetName = DEFAULT_ASSET_NAME,
                handler = "index.handler",
                envVars = mapOf(
                    "LOG_LEVEL" to "INFO"
                ),
                roleArn = roleArn
            )
            
            val (apiId, endpoint) = createWebSocketAPI(
                connectFunctionArn,
                disconnectFunctionArn,
                defaultFunctionArn
            )
            
            apiGatewayId = apiId
            apiGatewayEndpoint = endpoint
            
            Log.i(TAG, "API Gateway WebSocket deployed: $endpoint")

            deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                deployedComponents = (deploymentInfo?.deployedComponents ?: emptyList()) + "push-service"
            )

            PushServiceInfo(
                endpoint = endpoint,
                region = region,
                credentials = mapOf(
                    // Provider创建所需凭证（P0修复：添加accessKeyId和secretAccessKey）
                    "apiKey" to accessKeyId,
                    "accessKeyId" to accessKeyId,  // 兼容两种key名称
                    "secretKey" to secretAccessKey,
                    "secretAccessKey" to secretAccessKey,  // 兼容两种key名称
                    // 现有元数据
                    "apiGatewayId" to apiId
                ),
                metadata = mapOf(
                    "provider" to "aws-api-gateway",
                    "connectFunction" to connectFunctionArn,
                    "disconnectFunction" to disconnectFunctionArn,
                    "connectionsBucket" to userBucketName!!
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy push service", e)
            throw Exception("Push service deployment failed: ${e.message}", e)
        }
    }

    override suspend fun setupEventTrigger(userBucketName: String?): TriggerInfo {
        return try {
            Log.i(TAG, "Setting up S3 event trigger...")

            val lambda = getLambdaClient()
            val functionName = "$TRIGGER_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"

            val zipData = loadAsset(TRIGGER_ASSET_NAME)
            
            val configBucket = if (!userBucketName.isNullOrEmpty()) {
                Log.d(TAG, "Using user bucket for CONFIG_BUCKET: $userBucketName")
                userBucketName
            } else {
                Log.w(TAG, "User bucket not provided, F_A will read from event bucket as fallback")
                ""
            }
            
            val roleArn = createOrGetLambdaExecutionRole()
            
            val createRequest = CreateFunctionRequest {
                this.functionName = functionName
                this.runtime = Runtime.Nodejs20X
                this.role = roleArn
                this.handler = "index.handler"
                this.code = FunctionCode {
                    this.zipFile = zipData
                }
                this.timeout = 60
                this.memorySize = 256
                this.environment = Environment {
                    variables = buildMap {
                        put("CONFIG_KEY", CONFIG_KEY)
                        if (configBucket.isNotEmpty()) {
                            put("CONFIG_BUCKET", configBucket)
                        }
                    }
                }
            }

            val createResponse = lambda.createFunction(createRequest)
            val functionArn = createResponse.functionArn ?: throw Exception("Failed to get function ARN")
            Log.d(TAG, "Trigger function created: $functionArn")

            waitForFunctionActive(lambda, functionName)

            lambda.addPermission(
                AddPermissionRequest {
                    this.functionName = functionName
                    this.statementId = "AllowS3Invoke-${System.currentTimeMillis()}"
                    this.action = "lambda:InvokeFunction"
                    this.principal = "s3.amazonaws.com"
                }
            )
            Log.d(TAG, "S3 invoke permission added to Lambda function")

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

    suspend fun configureS3EventNotification(
        userBucketName: String,
        triggerFunctionArn: String,
        filterPrefix: String = "v2-channels/"
    ): Boolean {
        return try {
            Log.i(TAG, "Configuring S3 event notification on bucket: $userBucketName")
            
            val lambda = getLambdaClient()
            val functionName = triggerFunctionArn.substringAfterLast(":")
            val accountId = getAccountId()
            val statementId = "tap-s3-invoke-${System.currentTimeMillis()}"
            
            try {
                lambda.addPermission(
                    AddPermissionRequest {
                        this.functionName = functionName
                        this.statementId = statementId
                        this.action = "lambda:InvokeFunction"
                        this.principal = "s3.amazonaws.com"
                        this.sourceArn = "arn:aws:s3:::$userBucketName"
                        this.sourceAccount = accountId
                    }
                )
                Log.d(TAG, "Added Lambda permission for S3 to invoke function")
            } catch (e: Exception) {
                Log.w(TAG, "Lambda permission may already exist, continuing...", e)
            }
            
            val s3 = getS3Client()
            
            val existingConfig = try {
                s3.getBucketNotificationConfiguration(
                    GetBucketNotificationConfigurationRequest {
                        bucket = userBucketName
                    }
                )
            } catch (e: Exception) {
                Log.d(TAG, "No existing notification configuration")
                null
            }
            
            val existingLambdaConfigs = existingConfig?.lambdaFunctionConfigurations?.toMutableList() ?: mutableListOf()
            
            val newLambdaConfig = LambdaFunctionConfiguration {
                id = "tap-notification-trigger-${System.currentTimeMillis()}"
                lambdaFunctionArn = triggerFunctionArn
                events = listOf(Event.S3ObjectCreatedPut, Event.S3ObjectCreatedPost, Event.S3ObjectCreatedCompleteMultipartUpload)
                filter = NotificationConfigurationFilter {
                    key = S3KeyFilter {
                        filterRules = listOf(
                            FilterRule {
                                name = FilterRuleName.Prefix
                                value = filterPrefix
                            }
                        )
                    }
                }
            }
            
            existingLambdaConfigs.removeIf { it.id?.startsWith("tap-notification-trigger") == true }
            existingLambdaConfigs.add(newLambdaConfig)
            
            s3.putBucketNotificationConfiguration(
                PutBucketNotificationConfigurationRequest {
                    bucket = userBucketName
                    notificationConfiguration = NotificationConfiguration {
                        lambdaFunctionConfigurations = existingLambdaConfigs
                        queueConfigurations = existingConfig?.queueConfigurations
                        topicConfigurations = existingConfig?.topicConfigurations
                    }
                }
            )
            
            Log.i(TAG, "S3 event notification configured successfully")
            
            deploymentInfo = deploymentInfo?.copy(
                eventTriggerConfigured = true
            )
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure S3 event notification", e)
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

    suspend fun updateWebhookEnvironment(secret: String, userId: String, bucketName: String?): Boolean {
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
            
            Log.i(TAG, "Updating webhook Lambda environment variables...")
            Log.d(TAG, "[notifySecret调试] B端部署: notifySecret=${secret.take(4)}...${secret.takeLast(4)} (长度=${secret.length})")
            
            val lambda = getLambdaClient()
            
            lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest {
                    this.functionName = webhookFunctionName
                    this.environment = Environment {
                        variables = mapOf(
                            "LOG_LEVEL" to "INFO",
                            "CONNECTIONS_BUCKET" to bucketName!!,
                            // Management API 需要 HTTPS 端点
                            "API_GATEWAY_ENDPOINT" to ((apiGatewayEndpoint ?: "").replace("wss://", "https://")),
                            "NOTIFY_SECRET" to secret
                        )
                    }
                }
            )
            
            Log.i(TAG, "Webhook environment updated successfully")
            Log.d(TAG, "[notifySecret调试] B端Lambda环境变量已设置: functionName=$webhookFunctionName")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update webhook environment", e)
            false
        }
    }

    override suspend fun saveConfiguration(config: NotificationConfig) {
        try {
            Log.i(TAG, "Saving configuration to S3...")

            val s3 = getS3Client()
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

            val putRequest = PutObjectRequest {
                bucket = bucketName
                key = CONFIG_KEY
                body = ByteStream.fromBytes(jsonConfig.toByteArray())
                contentType = "application/json"
            }

            s3.putObject(putRequest)
            Log.i(TAG, "Configuration saved successfully")

            deploymentInfo = deploymentInfo?.copy(
                deploymentStatus = org.thoughtcrime.securesms.tap.notification.DeploymentStatus.DEPLOYED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save configuration", e)
            throw Exception("Configuration save failed: ${e.message}", e)
        }
    }

    override suspend fun loadConfiguration(): NotificationConfig? {
        return try {
            Log.d(TAG, "Loading configuration from S3...")

            val s3 = getS3Client()
            val bucketName = if (configBucketName != null) {
                configBucketName!!
            } else {
                Log.w(TAG, "Bucket name not set, configuration may not exist")
                return null
            }

            val response = s3.getObject(
                aws.sdk.kotlin.services.s3.model.GetObjectRequest {
                    bucket = bucketName
                    key = CONFIG_KEY
                }
            ) { resp ->
                resp.body?.toByteArray()?.toString(Charsets.UTF_8)
            }

            if (response == null) {
                Log.w(TAG, "Configuration not found")
                return null
            }

            val json = JSONObject(response)
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


    private suspend fun deployLambdaFunction(
        name: String,
        assetName: String,
        handler: String,
        envVars: Map<String, String>,
        roleArn: String
    ): String {
        try {
            val fullName = "$name-${UUID.randomUUID().toString().take(8)}"
            Log.i(TAG, "Deploying Lambda function: $fullName")
            
            val lambda = getLambdaClient()
            val zipData = loadAsset(assetName)
            
            val createRequest = CreateFunctionRequest {
                this.functionName = fullName
                this.runtime = Runtime.Nodejs20X
                this.role = roleArn
                this.handler = handler
                this.code = FunctionCode {
                    this.zipFile = zipData
                }
                this.timeout = 30
                this.memorySize = 256
                this.environment = Environment {
                    variables = envVars
                }
            }
            
            val createResponse = lambda.createFunction(createRequest)
            val functionArn = createResponse.functionArn ?: throw Exception("Failed to get function ARN")
            
            waitForFunctionActive(lambda, fullName)
            
            Log.i(TAG, "Lambda function deployed: $functionArn")
            return functionArn
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy Lambda function: $name", e)
            throw Exception("Lambda deployment failed: ${e.message}", e)
        }
    }

    private suspend fun createWebSocketAPI(
        connectFunctionArn: String,
        disconnectFunctionArn: String,
        defaultFunctionArn: String
    ): Pair<String, String> {
        try {
            Log.i(TAG, "Creating API Gateway WebSocket API...")
            
            val apiGateway = getApiGatewayClient()
            val accountId = getAccountId()
            
            val createApiResponse = apiGateway.createApi(
                CreateApiRequest {
                    name = "tap-notification-ws-${UUID.randomUUID().toString().take(8)}"
                    protocolType = ProtocolType.Websocket
                    routeSelectionExpression = "\$request.body.action"
                    description = "TAP Notification WebSocket API"
                }
            )
            
            val apiId = createApiResponse.apiId ?: throw Exception("Failed to get API ID")
            Log.d(TAG, "API created: $apiId")
            
            val lambda = getLambdaClient()
            
            listOf(
                Triple("\$connect", connectFunctionArn, "AllowApiGatewayConnect"),
                Triple("\$disconnect", disconnectFunctionArn, "AllowApiGatewayDisconnect"),
                Triple("\$default", defaultFunctionArn, "AllowApiGatewayDefault")
            ).forEach { (route, functionArn, statementId) ->
                val functionName = functionArn.substringAfterLast(":")
                
                lambda.addPermission(
                    AddPermissionRequest {
                        this.functionName = functionName
                        this.statementId = "$statementId-${System.currentTimeMillis()}"
                        this.action = "lambda:InvokeFunction"
                        this.principal = "apigateway.amazonaws.com"
                        this.sourceArn = "arn:aws:execute-api:$region:$accountId:$apiId/*/$route"
                    }
                )
                
                val integrationResponse = apiGateway.createIntegration(
                    CreateIntegrationRequest {
                        this.apiId = apiId
                        this.integrationType = IntegrationType.AwsProxy
                        this.integrationUri = "arn:aws:apigateway:$region:lambda:path/2015-03-31/functions/$functionArn/invocations"
                    }
                )
                
                val integrationId = integrationResponse.integrationId ?: throw Exception("Failed to get integration ID")
                
                apiGateway.createRoute(
                    CreateRouteRequest {
                        this.apiId = apiId
                        this.routeKey = route
                        this.target = "integrations/$integrationId"
                    }
                )
                
                Log.d(TAG, "Route created: $route")
            }
            
            apiGateway.createStage(
                CreateStageRequest {
                    this.apiId = apiId
                    this.stageName = "prod"
                    this.autoDeploy = true
                }
            )
            
            Log.d(TAG, "Stage created: prod")
            
            val endpoint = "wss://$apiId.execute-api.$region.amazonaws.com/prod"
            Log.i(TAG, "WebSocket API created: $endpoint")
            
            return Pair(apiId, endpoint)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket API", e)
            throw Exception("WebSocket API creation failed: ${e.message}", e)
        }
    }

    private suspend fun waitForFunctionActive(lambda: LambdaClient, functionName: String) {
        var attempts = 0
        val maxAttempts = MAX_DEPLOYMENT_WAIT_SECONDS / 5

        while (attempts < maxAttempts) {
            try {
                val response = lambda.getFunction(
                    GetFunctionRequest {
                        this.functionName = functionName
                    }
                )
                
                if (response.configuration?.state == State.Active) {
                    Log.d(TAG, "Function is active: $functionName")
                    return
                }
                
                Log.d(TAG, "Function state: ${response.configuration?.state}, waiting...")
                delay(5000)
                attempts++
                
            } catch (e: Exception) {
                Log.w(TAG, "Error checking function state", e)
                delay(5000)
                attempts++
            }
        }
        
        throw Exception("Function did not become active within timeout")
    }

    private suspend fun createOrGetLambdaExecutionRole(): String {
        if (cachedRoleArn != null) {
            return cachedRoleArn!!
        }

        try {
            val iam = getIamClient()
            val accountId = getAccountId()
            val roleName = "TapNotificationLambdaRole"
            
            val roleArn = try {
                val getRole = iam.getRole(
                    GetRoleRequest {
                        this.roleName = roleName
                    }
                )
                getRole.role?.arn ?: throw Exception("Role ARN not found")
            } catch (e: Exception) {
                Log.i(TAG, "Creating Lambda execution role: $roleName")
                
                val assumeRolePolicyDocument = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {
                            "Service": "lambda.amazonaws.com"
                          },
                          "Action": "sts:AssumeRole"
                        }
                      ]
                    }
                """.trimIndent()
                
                val createRoleResponse = iam.createRole(
                    CreateRoleRequest {
                        this.roleName = roleName
                        this.assumeRolePolicyDocument = assumeRolePolicyDocument
                        this.description = "Role for TAP notification Lambda functions"
                    }
                )
                
                val arn = createRoleResponse.role?.arn ?: throw Exception("Failed to create role")
                Log.d(TAG, "Lambda execution role created: $arn")
                
                iam.attachRolePolicy(
                    AttachRolePolicyRequest {
                        this.roleName = roleName
                        this.policyArn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
                    }
                )
                Log.d(TAG, "Attached AWSLambdaBasicExecutionRole policy")
                
                val customPolicyDocument = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": [
                            "s3:GetObject",
                            "s3:PutObject",
                            "s3:DeleteObject",
                            "s3:ListBucket"
                          ],
                          "Resource": [
                            "arn:aws:s3:::*",
                            "arn:aws:s3:::*/*"
                          ]
                        },
                        {
                          "Effect": "Allow",
                          "Action": [
                            "execute-api:ManageConnections"
                          ],
                          "Resource": "*"
                        }
                      ]
                    }
                """.trimIndent()
                
                try {
                    iam.createPolicy(
                        CreatePolicyRequest {
                            this.policyName = "TapNotificationLambdaPolicy"
                            this.policyDocument = customPolicyDocument
                            this.description = "Policy for TAP notification Lambda functions"
                        }
                    )
                    
                    iam.attachRolePolicy(
                        AttachRolePolicyRequest {
                            this.roleName = roleName
                            this.policyArn = "arn:aws:iam::${accountId}:policy/TapNotificationLambdaPolicy"
                        }
                    )
                    Log.d(TAG, "Attached custom TAP policy")
                } catch (policyError: Exception) {
                    Log.w(TAG, "Custom policy may already exist, trying to attach", policyError)
                    try {
                        iam.attachRolePolicy(
                            AttachRolePolicyRequest {
                                this.roleName = roleName
                                this.policyArn = "arn:aws:iam::${accountId}:policy/TapNotificationLambdaPolicy"
                            }
                        )
                    } catch (attachError: Exception) {
                        Log.w(TAG, "Failed to attach existing policy", attachError)
                    }
                }
                
                delay(10000)
                
                arn
            }
            
            cachedRoleArn = roleArn
            return roleArn
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create or get Lambda execution role", e)
            throw Exception("Lambda role setup failed: ${e.message}", e)
        }
    }

    private suspend fun getAccountId(): String {
        if (cachedAccountId != null) {
            return cachedAccountId!!
        }

        try {
            val sts = getStsClient()
            val response = sts.getCallerIdentity(GetCallerIdentityRequest {})
            val accountId = response.account ?: throw Exception("Account ID not found")
            
            Log.d(TAG, "AWS Account ID: $accountId")
            cachedAccountId = accountId
            return accountId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AWS Account ID", e)
            throw Exception("Failed to get account ID: ${e.message}", e)
        }
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
            
            responseCode in 200..299
                
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
            
            val apiGateway = getApiGatewayClient()
            val apiId = pushServiceInfo.credentials["apiGatewayId"] ?: return false
            
            val response = apiGateway.getApi(
                GetApiRequest {
                    this.apiId = apiId
                }
            )
            
            val isActive = response.apiEndpoint != null
            
            if (isActive) {
                Log.d(TAG, "API Gateway verified: ${response.apiEndpoint}")
            } else {
                Log.w(TAG, "API Gateway endpoint not found")
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
            
            val lambda = getLambdaClient()
            
            val response = lambda.getFunction(
                GetFunctionRequest {
                    this.functionName = functionName
                }
            )
            
            val isActive = response.configuration?.state == State.Active
            
            if (isActive) {
                Log.d(TAG, "Event trigger function is active: $functionName")
            } else {
                Log.w(TAG, "Event trigger function is not active: ${response.configuration?.state}")
            }
            
            isActive
            
        } catch (e: Exception) {
            Log.w(TAG, "Event trigger test failed", e)
            false
        }
    }

    /**
     * 客户端直接调用触发器Lambda函数（方案二：替代S3事件触发）
     * 
     * @param remotePath 上传文件的路径（如 v2-channels/{hash}/messages/xxx.dat）
     * @param bucketName S3 bucket名称
     * @return 是否成功触发（异步调用，不等待结果）
     */
    suspend fun invokeTriggerFunction(
        remotePath: String,
        bucketName: String
    ): Boolean {
        return try {
            var functionName = deploymentInfo?.triggerFunctionName
            if (functionName.isNullOrEmpty()) {
                // Attempt to discover the trigger function by listing functions
                try {
                    val lambda = getLambdaClient()
                    val list = lambda.listFunctions(ListFunctionsRequest {})
                    val matched = list.functions?.firstOrNull { it.functionName?.startsWith(TRIGGER_FUNCTION_NAME) == true }
                    if (matched?.functionName != null) {
                        functionName = matched.functionName
                        // cache to deploymentInfo for subsequent calls
                        deploymentInfo = (deploymentInfo ?: createEmptyDeployment()).copy(
                            triggerFunctionName = functionName!!,
                            triggerFunctionArn = matched.functionArn ?: (deploymentInfo?.triggerFunctionArn ?: "")
                        )
                        Log.i(TAG, "Discovered trigger function: $functionName")
                    } else {
                        Log.w(TAG, "Trigger function name not found via discovery")
                        return false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to discover trigger function", e)
                    return false
                }
            }

            Log.d(TAG, "Invoking trigger function: $functionName for path: $remotePath")

            val lambda = getLambdaClient()

            // 构造S3事件格式（模拟S3事件通知）
            val s3Event = createS3Event(remotePath, bucketName)

            // 调用AWS Lambda Invoke API（异步调用）
            val invokeRequest = InvokeRequest {
                this.functionName = functionName
                this.invocationType = InvocationType.Event  // 异步调用
                this.payload = s3Event.toString().toByteArray()
            }

            lambda.invoke(invokeRequest)

            Log.i(TAG, "Trigger function invoked successfully: $functionName")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke trigger function", e)
            false
        }
    }

    /**
     * 构造S3事件格式（模拟S3事件通知）
     * 格式与aws-f-a.js期望的事件格式一致
     */
    private fun createS3Event(key: String, bucketName: String): JSONObject {
        return JSONObject().apply {
            put("Records", JSONArray().apply {
                put(JSONObject().apply {
                    put("eventVersion", "2.1")
                    put("eventSource", "aws:s3")
                    put("awsRegion", region)
                    put("eventTime", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date()))
                    put("eventName", "ObjectCreated:Put")
                    put("s3", JSONObject().apply {
                        put("s3SchemaVersion", "1.0")
                        put("configurationId", "tap-notification-trigger")
                        put("bucket", JSONObject().apply {
                            put("name", bucketName)
                            put("arn", "arn:aws:s3:::$bucketName")
                        })
                        put("object", JSONObject().apply {
                            put("key", key)
                            put("size", 0)  // 客户端调用时无法确定文件大小
                            put("eTag", UUID.randomUUID().toString())
                        })
                    })
                })
            })
            // 添加requestId用于日志追踪
            put("requestId", UUID.randomUUID().toString())
        }
    }

    private fun getLambdaClient(): LambdaClient {
        if (lambdaClient == null) {
            lambdaClient = LambdaClient {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return lambdaClient!!
    }

    private fun getDynamoDbClient(): DynamoDbClient {
        if (dynamoDbClient == null) {
            dynamoDbClient = DynamoDbClient {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return dynamoDbClient!!
    }

    private fun getApiGatewayClient(): ApiGatewayV2Client {
        if (apiGatewayClient == null) {
            apiGatewayClient = ApiGatewayV2Client {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return apiGatewayClient!!
    }

    private fun getS3Client(): S3Client {
        if (s3Client == null) {
            s3Client = S3Client {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return s3Client!!
    }

    private fun getIamClient(): IamClient {
        if (iamClient == null) {
            iamClient = IamClient {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return iamClient!!
    }

    private fun getStsClient(): StsClient {
        if (stsClient == null) {
            stsClient = StsClient {
                region = this@AwsApiGatewayDeployer.region
                credentialsProvider = this@AwsApiGatewayDeployer.credentialsProvider
            }
        }
        return stsClient!!
    }

    /**
     * 获取配置bucket名称（使用用户现有的bucket，不再创建新bucket）
     * 配置文件存储在用户bucket的 tap-state/notification-config.json
     */
    private suspend fun getOrCreateConfigBucket(): String {
        if (configBucketName != null) {
            return configBucketName!!
        }

        if (!userBucketName.isNullOrEmpty()) {
            Log.i(TAG, "Using user bucket for configuration storage: $userBucketName")
            configBucketName = userBucketName!!
            return userBucketName!!
        }

        throw Exception("User bucket name is required. Please configure COS provider first.")
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
            deploymentStatus = org.thoughtcrime.securesms.tap.notification.DeploymentStatus.DEPLOYING
        )
    }

    override suspend fun deleteFunction(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting Lambda function: $name (ARN: $identifier)")
            
            val lambda = getLambdaClient()
            
            val deleteRequest = DeleteFunctionRequest {
                functionName = identifier
            }
            
            lambda.deleteFunction(deleteRequest)
            Log.i(TAG, "Lambda function deleted successfully: $name")
            
            delay(2000)
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Lambda function: $name", e)
            false
        }
    }
    
    override suspend fun deleteRole(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting IAM role: $name")
            
            val iam = getIamClient()
            
            try {
                val listPoliciesRequest = ListAttachedRolePoliciesRequest {
                    roleName = name
                }
                val policiesResponse = iam.listAttachedRolePolicies(listPoliciesRequest)
                
                policiesResponse.attachedPolicies?.forEach { policy ->
                    val detachRequest = DetachRolePolicyRequest {
                        roleName = name
                        policyArn = policy.policyArn
                    }
                    iam.detachRolePolicy(detachRequest)
                    Log.d(TAG, "Detached policy: ${policy.policyName}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detach policies from role: $name", e)
            }
            
            try {
                val listInlinePoliciesRequest = ListRolePoliciesRequest {
                    roleName = name
                }
                val inlinePoliciesResponse = iam.listRolePolicies(listInlinePoliciesRequest)
                
                inlinePoliciesResponse.policyNames?.forEach { policyNameValue ->
                    val deleteInlinePolicyRequest = DeleteRolePolicyRequest {
                        roleName = name
                        policyName = policyNameValue
                    }
                    iam.deleteRolePolicy(deleteInlinePolicyRequest)
                    Log.d(TAG, "Deleted inline policy: $policyNameValue")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete inline policies from role: $name", e)
            }
            
            val deleteRoleRequest = DeleteRoleRequest {
                roleName = name
            }
            iam.deleteRole(deleteRoleRequest)
            
            Log.i(TAG, "IAM role deleted successfully: $name")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete IAM role: $name", e)
            false
        }
    }

    override suspend fun deleteApiGateway(identifier: String, name: String): Boolean {
        return try {
            Log.i(TAG, "Deleting API Gateway: id=$identifier, name=$name")
            
            val apiGateway = getApiGatewayClient()
            
            val deleteRequest = aws.sdk.kotlin.services.apigatewayv2.model.DeleteApiRequest {
                apiId = identifier
            }
            
            apiGateway.deleteApi(deleteRequest)
            
            Log.i(TAG, "API Gateway deleted successfully: $name")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API Gateway: $name", e)
            false
        }
    }
    
    override suspend fun deleteDynamoDBTable(identifier: String, name: String): Boolean {
        // 不再使用DynamoDB表，connectionId存储在S3中
        // 如果需要清理，可以通过删除S3中的tap-ws-connections/目录来实现
        Log.d(TAG, "deleteDynamoDBTable called but no longer needed (using S3 storage)")
        return true
    }

    override fun cleanup() {
        lambdaClient?.close()
        dynamoDbClient?.close()
        apiGatewayClient?.close()
        s3Client?.close()
        iamClient?.close()
        stsClient?.close()
        lambdaClient = null
        dynamoDbClient = null
        apiGatewayClient = null
        s3Client = null
        iamClient = null
        stsClient = null
    }
}
