package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.webhook.WebhookRequestBuilder
import org.thoughtcrime.securesms.tap.notification.webhook.WebhookSignatureValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 配置测试执行器
 * 
 * 负责测试推送服务配置的各个组件
 */
class ConfigTestExecutor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(ConfigTestExecutor::class.java)
        private const val TEST_TIMEOUT_MS = 30000L
        private const val HTTP_CONNECT_TIMEOUT_SECONDS = 10L
        private const val HTTP_READ_TIMEOUT_SECONDS = 15L
    }
    
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 测试推送服务部署
     * 
     * @param deployer 推送服务部署器
     * @return 测试结果
     */
    suspend fun testDeployment(deployer: NotificationDeployer): ConfigTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始测试推送服务部署")
                
                withTimeout(TEST_TIMEOUT_MS) {
                    val testResult = deployer.testDeployment()
                    
                    if (testResult.success) {
                        Log.i(TAG, "推送服务部署测试成功")
                        ConfigTestResult.success(
                            "deployment",
                            "推送服务部署测试通过",
                            mapOf(
                                "webhookReachable" to testResult.webhookReachable,
                                "pushServiceConnected" to testResult.pushServiceConnected,
                                "eventTriggerWorking" to testResult.eventTriggerWorking
                            )
                        )
                    } else {
                        Log.w(TAG, "推送服务部署测试失败: ${testResult.errorMessage}")
                        ConfigTestResult.failure(
                            "deployment",
                            testResult.errorMessage ?: "部署测试失败"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "推送服务部署测试异常", e)
                ConfigTestResult.failure("deployment", "测试异常: ${e.message}")
            }
        }
    }
    
    /**
     * 测试Webhook连通性
     * 
     * @param webhookConfig Webhook配置
     * @return 测试结果
     */
    suspend fun testWebhookConnectivity(webhookConfig: WebhookConfig): ConfigTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始测试Webhook连通性: ${webhookConfig.webhookUrl}")
                
                if (!webhookConfig.validate()) {
                    return@withContext ConfigTestResult.failure(
                        "webhook",
                        "Webhook配置验证失败"
                    )
                }
                
                withTimeout(TEST_TIMEOUT_MS) {
                    // 构造测试通知消息
                    val testNotification = NotificationMessage(
                        type = "test",
                        senderId = "test_sender",
                        timestamp = System.currentTimeMillis(),
                        metadata = mapOf(
                            "test" to true,
                            "source" to "ConfigTestExecutor"
                        )
                    )
                    
                    // 构造Webhook请求
                    val requestBuilder = WebhookRequestBuilder()
                    val requestJson = requestBuilder.buildRequestJson(
                        notification = testNotification,
                        secret = webhookConfig.notifySecret
                    )
                    
                    if (requestJson == null) {
                        return@withContext ConfigTestResult.failure(
                            "webhook",
                            "构造请求失败"
                        )
                    }
                    
                    // 发送HTTP请求
                    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
                    val requestBody = requestJson.toRequestBody(jsonMediaType)
                    
                    val request = Request.Builder()
                        .url(webhookConfig.webhookUrl)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "Signal-Tap-Test/2.0")
                        .build()
                    
                    val response = httpClient.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        Log.i(TAG, "Webhook连通性测试成功: ${response.code}")
                        ConfigTestResult.success(
                            "webhook",
                            "Webhook连接成功",
                            mapOf(
                                "statusCode" to response.code,
                                "responseTime" to System.currentTimeMillis()
                            )
                        )
                    } else {
                        Log.w(TAG, "Webhook连通性测试失败: ${response.code} ${response.message}")
                        ConfigTestResult.failure(
                            "webhook",
                            "Webhook返回错误: ${response.code}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Webhook连通性测试异常", e)
                ConfigTestResult.failure("webhook", "连接失败: ${e.message}")
            }
        }
    }
    
    /**
     * 测试推送服务连接
     * 
     * @param provider 推送服务提供者
     * @param userId 用户ID
     * @return 测试结果
     */
    suspend fun testPushServiceConnection(
        provider: NotificationProvider,
        userId: String
    ): ConfigTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始测试推送服务连接")
                
                withTimeout(TEST_TIMEOUT_MS) {
                    // 尝试连接推送服务
                    val startTime = System.currentTimeMillis()
                    val connectionResult = provider.connect(userId) { notification ->
                        // 测试通知回调
                        Log.d(TAG, "收到测试通知: ${notification.type}")
                    }
                    val connectionTime = System.currentTimeMillis() - startTime
                    
                    if (connectionResult.success) {
                        // 连接成功后立即断开
                        provider.disconnect()
                        
                        Log.i(TAG, "推送服务连接测试成功: ${connectionTime}ms")
                        ConfigTestResult.success(
                            "push_service",
                            "推送服务连接成功",
                            mapOf(
                                "connectionTime" to connectionTime,
                                "connectionId" to (connectionResult.connectionId ?: "unknown")
                            )
                        )
                    } else {
                        Log.w(TAG, "推送服务连接测试失败: ${connectionResult.errorMessage}")
                        ConfigTestResult.failure(
                            "push_service",
                            connectionResult.errorMessage ?: "连接失败"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "推送服务连接测试异常", e)
                ConfigTestResult.failure("push_service", "连接异常: ${e.message}")
            }
        }
    }
    
    /**
     * 测试推送服务健康状态
     * 
     * @param provider 推送服务提供者
     * @return 测试结果
     */
    suspend fun testPushServiceHealth(provider: NotificationProvider): ConfigTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始测试推送服务健康状态")
                
                withTimeout(TEST_TIMEOUT_MS) {
                    val healthStatus = provider.healthCheck()
                    
                    if (healthStatus.isHealthy) {
                        Log.i(TAG, "推送服务健康状态良好: latency=${healthStatus.latencyMs}ms")
                        ConfigTestResult.success(
                            "health",
                            "推送服务健康",
                            mapOf(
                                "latencyMs" to healthStatus.latencyMs,
                                "lastCheckTime" to healthStatus.lastCheckTime,
                                "details" to healthStatus.details
                            )
                        )
                    } else {
                        Log.w(TAG, "推送服务健康状态不佳: ${healthStatus.errorMessage}")
                        ConfigTestResult.failure(
                            "health",
                            healthStatus.errorMessage ?: "健康检查失败"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "推送服务健康检查异常", e)
                ConfigTestResult.failure("health", "健康检查异常: ${e.message}")
            }
        }
    }
    
    /**
     * 执行完整的配置测试
     * 
     * @param provider 推送服务提供者
     * @param deployer 推送服务部署器
     * @param userId 用户ID
     * @return 批量测试结果
     */
    suspend fun runFullTest(
        provider: NotificationProvider,
        deployer: NotificationDeployer,
        userId: String
    ): BatchConfigTestResult {
        Log.i(TAG, "开始执行完整配置测试")
        
        val results = mutableMapOf<String, ConfigTestResult>()
        
        // 1. 测试部署
        results["deployment"] = testDeployment(deployer)
        
        // 2. 测试Webhook连通性
        val webhookConfig = provider.getWebhookConfig()
        results["webhook"] = testWebhookConnectivity(webhookConfig)
        
        // 3. 测试推送服务连接
        results["connection"] = testPushServiceConnection(provider, userId)
        
        // 4. 测试推送服务健康
        results["health"] = testPushServiceHealth(provider)
        
        val allPassed = results.values.all { it.success }
        
        Log.i(TAG, "完整配置测试完成: ${if (allPassed) "全部通过" else "部分失败"}")
        
        return BatchConfigTestResult(
            success = allPassed,
            results = results,
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * 配置测试结果
 */
data class ConfigTestResult(
    val success: Boolean,
    val testType: String,
    val message: String,
    val details: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun success(
            testType: String,
            message: String,
            details: Map<String, Any> = emptyMap()
        ): ConfigTestResult {
            return ConfigTestResult(
                success = true,
                testType = testType,
                message = message,
                details = details
            )
        }
        
        fun failure(testType: String, message: String): ConfigTestResult {
            return ConfigTestResult(
                success = false,
                testType = testType,
                message = message
            )
        }
    }
}

/**
 * 批量配置测试结果
 */
data class BatchConfigTestResult(
    val success: Boolean,
    val results: Map<String, ConfigTestResult>,
    val timestamp: Long
) {
    fun getFailedTests(): List<String> {
        return results.filter { !it.value.success }.keys.toList()
    }
    
    fun getSuccessfulTests(): List<String> {
        return results.filter { it.value.success }.keys.toList()
    }
    
    fun getSummary(): String {
        val total = results.size
        val passed = results.values.count { it.success }
        return "测试通过: $passed/$total"
    }
}

