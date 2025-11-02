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
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.sts.StsClient
import aws.sdk.kotlin.services.sts.model.GetCallerIdentityRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.*
import java.util.UUID

/**
 * AWS API Gateway推送服务部署器
 * 负责自动化部署Lambda函数、API Gateway WebSocket、DynamoDB和事件触发器
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
        private const val TABLE_NAME = "tap-ws-connections"
        private const val CONFIG_BUCKET_PREFIX = "tap-notification-config"
        private const val CONFIG_KEY = "notification-config.json"
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
    
    private var configBucketName: String? = null
    private var deploymentInfo: NotificationDeployment? = null
    private var cachedAccountId: String? = null
    private var cachedRoleArn: String? = null
    private var apiGatewayId: String? = null
    private var apiGatewayEndpoint: String? = null

    override suspend fun deployWebhook(): String {
        return try {
            Log.i(TAG, "Deploying webhook Lambda function...")

            val lambda = getLambdaClient()
            val functionName = "$WEBHOOK_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"

            val zipData = loadAsset(WEBHOOK_ASSET_NAME)
            
            if (apiGatewayEndpoint == null) {
                throw Exception("Must deploy push service before webhook")
            }
            
            val createRequest = CreateFunctionRequest {
                this.functionName = functionName
                this.runtime = Runtime.Nodejs20X
                this.role = createOrGetLambdaExecutionRole()
                this.handler = "index.handler"
                this.code = FunctionCode {
                    this.zipFile = zipData
                }
                this.timeout = 30
                this.memorySize = 256
                this.environment = Environment {
                    variables = mapOf(
                        "LOG_LEVEL" to "INFO",
                        "CONNECTIONS_TABLE" to TABLE_NAME,
                        "API_GATEWAY_ENDPOINT" to apiGatewayEndpoint!!
                    )
                }
            }

            val createResponse = lambda.createFunction(createRequest)
            Log.d(TAG, "Lambda function created: ${createResponse.functionArn}")

            waitForFunctionActive(lambda, functionName)

            val urlConfig = lambda.createFunctionUrlConfig(
                CreateFunctionUrlConfigRequest {
                    this.functionName = functionName
                    this.authType = FunctionUrlAuthType.None
                    this.cors = Cors {
                        allowOrigins = listOf("*")
                        allowMethods = listOf("POST")
                        allowHeaders = listOf("*")
                    }
                }
            )

            val webhookUrl = urlConfig.functionUrl ?: throw Exception("Failed to get function URL")
            Log.i(TAG, "Webhook deployed successfully: $webhookUrl")

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

    override suspend fun deployPushService(): PushServiceInfo {
        return try {
            Log.i(TAG, "Deploying AWS API Gateway WebSocket push service...")

            createDynamoDBTable()
            
            val roleArn = createOrGetLambdaExecutionRole()
            
            val connectFunctionArn = deployLambdaFunction(
                name = CONNECT_FUNCTION_NAME,
                assetName = CONNECT_ASSET_NAME,
                handler = "index.handler",
                envVars = mapOf(
                    "LOG_LEVEL" to "INFO",
                    "CONNECTIONS_TABLE" to TABLE_NAME
                ),
                roleArn = roleArn
            )
            
            val disconnectFunctionArn = deployLambdaFunction(
                name = DISCONNECT_FUNCTION_NAME,
                assetName = DISCONNECT_ASSET_NAME,
                handler = "index.handler",
                envVars = mapOf(
                    "LOG_LEVEL" to "INFO",
                    "CONNECTIONS_TABLE" to TABLE_NAME
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
                    "apiGatewayId" to apiId,
                    "tableName" to TABLE_NAME
                ),
                metadata = mapOf(
                    "provider" to "aws-api-gateway",
                    "connectFunction" to connectFunctionArn,
                    "disconnectFunction" to disconnectFunctionArn
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy push service", e)
            throw Exception("Push service deployment failed: ${e.message}", e)
        }
    }

    override suspend fun setupEventTrigger(): TriggerInfo {
        return try {
            Log.i(TAG, "Setting up S3 event trigger...")

            val lambda = getLambdaClient()
            val functionName = "$TRIGGER_FUNCTION_NAME-${UUID.randomUUID().toString().take(8)}"

            val zipData = loadAsset(TRIGGER_ASSET_NAME)
            
            val createRequest = CreateFunctionRequest {
                this.functionName = functionName
                this.runtime = Runtime.Nodejs20X
                this.role = createOrGetLambdaExecutionRole()
                this.handler = "index.handler"
                this.code = FunctionCode {
                    this.zipFile = zipData
                }
                this.timeout = 60
                this.memorySize = 256
                this.environment = Environment {
                    variables = mapOf(
                        "CONFIG_BUCKET" to getOrCreateConfigBucket(),
                        "CONFIG_KEY" to CONFIG_KEY
                    )
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

    suspend fun updateWebhookEnvironment(secret: String, userId: String): Boolean {
        return try {
            val webhookFunctionName = deploymentInfo?.webhookFunctionName
            if (webhookFunctionName == null) {
                Log.w(TAG, "Webhook function name not found, cannot update environment")
                return false
            }
            
            Log.i(TAG, "Updating webhook Lambda environment variables...")
            
            val lambda = getLambdaClient()
            
            lambda.updateFunctionConfiguration(
                UpdateFunctionConfigurationRequest {
                    this.functionName = webhookFunctionName
                    this.environment = Environment {
                        variables = mapOf(
                            "LOG_LEVEL" to "INFO",
                            "CONNECTIONS_TABLE" to TABLE_NAME,
                            "API_GATEWAY_ENDPOINT" to (apiGatewayEndpoint ?: ""),
                            "NOTIFY_SECRET" to secret
                        )
                    }
                }
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
                deploymentStatus = DeploymentStatus.DEPLOYED
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

    private suspend fun createDynamoDBTable() {
        try {
            Log.i(TAG, "Creating DynamoDB table: $TABLE_NAME")
            
            val dynamoDB = getDynamoDbClient()
            
            try {
                dynamoDB.describeTable(
                    DescribeTableRequest {
                        tableName = TABLE_NAME
                    }
                )
                Log.d(TAG, "DynamoDB table already exists: $TABLE_NAME")
                return
            } catch (e: Exception) {
                Log.d(TAG, "Table does not exist, creating...")
            }
            
            dynamoDB.createTable(
                CreateTableRequest {
                    tableName = TABLE_NAME
                    keySchema = listOf(
                        KeySchemaElement {
                            attributeName = "userId"
                            keyType = KeyType.Hash
                        }
                    )
                    attributeDefinitions = listOf(
                        AttributeDefinition {
                            attributeName = "userId"
                            attributeType = ScalarAttributeType.S
                        }
                    )
                    billingMode = BillingMode.PayPerRequest
                    timeToLiveSpecification = TimeToLiveSpecification {
                        enabled = true
                        attributeName = "ttl"
                    }
                }
            )
            
            Log.i(TAG, "DynamoDB table created successfully")
            
            var attempts = 0
            while (attempts < 30) {
                try {
                    val desc = dynamoDB.describeTable(
                        DescribeTableRequest {
                            tableName = TABLE_NAME
                        }
                    )
                    if (desc.table?.tableStatus == TableStatus.Active) {
                        Log.d(TAG, "Table is active")
                        return
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Waiting for table to become active...")
                }
                delay(2000)
                attempts++
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DynamoDB table", e)
            throw Exception("DynamoDB table creation failed: ${e.message}", e)
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
                            "s3:ListBucket"
                          ],
                          "Resource": "*"
                        },
                        {
                          "Effect": "Allow",
                          "Action": [
                            "dynamodb:PutItem",
                            "dynamodb:GetItem",
                            "dynamodb:DeleteItem",
                            "dynamodb:Query"
                          ],
                          "Resource": "arn:aws:dynamodb:$region:$accountId:table/$TABLE_NAME"
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

    private suspend fun getOrCreateConfigBucket(): String {
        if (configBucketName != null) {
            return configBucketName!!
        }

        val bucketName = "$CONFIG_BUCKET_PREFIX-${UUID.randomUUID().toString().take(8)}"
        Log.i(TAG, "Creating S3 bucket: $bucketName")

        try {
            val s3 = getS3Client()
            
            val headRequest = HeadBucketRequest {
                bucket = bucketName
            }
            
            try {
                s3.headBucket(headRequest)
                Log.d(TAG, "Bucket already exists: $bucketName")
            } catch (e: Exception) {
                val createRequest = CreateBucketRequest {
                    bucket = bucketName
                    if (region != "us-east-1") {
                        createBucketConfiguration = CreateBucketConfiguration {
                            locationConstraint = BucketLocationConstraint.fromValue(region)
                        }
                    }
                }
                
                s3.createBucket(createRequest)
                Log.i(TAG, "S3 bucket created successfully: $bucketName")
                
                val versioningRequest = PutBucketVersioningRequest {
                    bucket = bucketName
                    versioningConfiguration = VersioningConfiguration {
                        status = BucketVersioningStatus.Enabled
                    }
                }
                s3.putBucketVersioning(versioningRequest)
                Log.d(TAG, "Bucket versioning enabled")
                
                val encryptionRequest = PutBucketEncryptionRequest {
                    bucket = bucketName
                    serverSideEncryptionConfiguration = ServerSideEncryptionConfiguration {
                        rules = listOf(
                            ServerSideEncryptionRule {
                                applyServerSideEncryptionByDefault = ServerSideEncryptionByDefault {
                                    sseAlgorithm = ServerSideEncryption.Aes256
                                }
                                bucketKeyEnabled = true
                            }
                        )
                    }
                }
                s3.putBucketEncryption(encryptionRequest)
                Log.d(TAG, "Bucket encryption enabled")
            }
            
            configBucketName = bucketName
            return bucketName
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create S3 bucket", e)
            throw Exception("S3 bucket creation failed: ${e.message}", e)
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
                
                inlinePoliciesResponse.policyNames?.forEach { policyName ->
                    val deleteInlinePolicyRequest = DeleteRolePolicyRequest {
                        roleName = name
                        policyName = policyName
                    }
                    iam.deleteRolePolicy(deleteInlinePolicyRequest)
                    Log.d(TAG, "Deleted inline policy: $policyName")
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
        return try {
            Log.i(TAG, "Deleting DynamoDB table: $name")
            
            val dynamodb = getDynamoDbClient()
            
            val deleteRequest = aws.sdk.kotlin.services.dynamodb.model.DeleteTableRequest {
                tableName = name
            }
            
            dynamodb.deleteTable(deleteRequest)
            
            Log.i(TAG, "DynamoDB table deletion initiated: $name")
            
            var attempts = 0
            val maxAttempts = 30
            while (attempts < maxAttempts) {
                try {
                    val describeRequest = DescribeTableRequest {
                        tableName = name
                    }
                    dynamodb.describeTable(describeRequest)
                    delay(2000)
                    attempts++
                } catch (e: Exception) {
                    Log.i(TAG, "DynamoDB table deleted successfully: $name")
                    return@try true
                }
            }
            
            Log.w(TAG, "DynamoDB table deletion timeout: $name")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete DynamoDB table: $name", e)
            false
        }
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
