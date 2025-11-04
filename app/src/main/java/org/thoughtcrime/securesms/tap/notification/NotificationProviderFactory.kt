package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.provider.aws.AwsApiGatewayNotificationProvider
import org.thoughtcrime.securesms.tap.notification.provider.tencent.TencentApiGatewayNotificationProvider

/**
 * 推送服务提供商工厂
 */
class NotificationProviderFactory {
    
    companion object {
        private val TAG = Log.tag(NotificationProviderFactory::class.java)
        
        const val PROVIDER_AWS_API_GATEWAY = "aws-api-gateway"
        const val PROVIDER_TENCENT_API_GATEWAY = "tencent-api-gateway"
        
        @Volatile
        private var instance: NotificationProviderFactory? = null
        
        fun getInstance(): NotificationProviderFactory {
            return instance ?: synchronized(this) {
                instance ?: NotificationProviderFactory().also { instance = it }
            }
        }
    }
    
    private val providers = mutableMapOf<String, NotificationProvider>()
    
    fun createProvider(
        context: Context,
        providerType: String,
        credentials: Map<String, String> = emptyMap()
    ): NotificationProvider? {
        return try {
            when (providerType) {
                PROVIDER_AWS_API_GATEWAY -> {
                    val accessKeyId = credentials["apiKey"] ?: credentials["accessKeyId"]
                    if (accessKeyId == null) {
                        Log.e(TAG, "AWS API Gateway Provider 缺少必需的凭证: apiKey/accessKeyId")
                        Log.e(TAG, "当前credentials中的keys: ${credentials.keys}")
                        return null
                    }
                    val secretAccessKey = credentials["secretKey"] ?: credentials["secretAccessKey"]
                    if (secretAccessKey == null) {
                        Log.e(TAG, "AWS API Gateway Provider 缺少必需的凭证: secretKey/secretAccessKey")
                        Log.e(TAG, "当前credentials中的keys: ${credentials.keys}")
                        return null
                    }
                    val region = credentials["region"] ?: "us-east-1"
                    
                    AwsApiGatewayNotificationProvider(context, accessKeyId, secretAccessKey, region).also {
                        Log.i(TAG, "AWS API Gateway Provider 创建成功: region=$region")
                    }
                }
                PROVIDER_TENCENT_API_GATEWAY -> {
                    val secretId = credentials["apiKey"] ?: credentials["secretId"]
                    if (secretId == null) {
                        Log.e(TAG, "腾讯云 API Gateway Provider 缺少必需的凭证: apiKey/secretId")
                        Log.e(TAG, "当前credentials中的keys: ${credentials.keys}")
                        return null
                    }
                    val secretKey = credentials["secretKey"]
                    if (secretKey == null) {
                        Log.e(TAG, "腾讯云 API Gateway Provider 缺少必需的凭证: secretKey")
                        Log.e(TAG, "当前credentials中的keys: ${credentials.keys}")
                        return null
                    }
                    val region = credentials["region"] ?: "ap-guangzhou"
                    
                    TencentApiGatewayNotificationProvider(context, secretId, secretKey, region).also {
                        Log.i(TAG, "腾讯云 API Gateway Provider 创建成功: region=$region")
                    }
                }
                else -> {
                    Log.w(TAG, "不支持的推送服务类型: $providerType")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建推送服务提供商失败: $providerType", e)
            null
        }
    }
    
    fun getProvider(providerType: String): NotificationProvider? {
        return providers[providerType]
    }
    
    fun registerProvider(providerType: String, provider: NotificationProvider): Boolean {
        return try {
            providers[providerType] = provider
            Log.i(TAG, "推送服务提供商已注册: $providerType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "注册推送服务提供商失败: $providerType", e)
            false
        }
    }
    
    fun unregisterProvider(providerType: String): Boolean {
        return try {
            providers.remove(providerType)
            Log.i(TAG, "推送服务提供商已注销: $providerType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "注销推送服务提供商失败: $providerType", e)
            false
        }
    }
    
    fun getSupportedProviders(): Set<String> {
        return setOf(
            PROVIDER_AWS_API_GATEWAY,
            PROVIDER_TENCENT_API_GATEWAY
        )
    }
    
    fun isProviderSupported(providerType: String): Boolean {
        return getSupportedProviders().contains(providerType)
    }
    
    fun detectProviderType(apiKey: String): String? {
        return when {
            // AWS AccessKeyId: AKIA(长期凭证)或ASIA(临时凭证)开头
            apiKey.startsWith("AKIA") || apiKey.startsWith("ASIA") -> PROVIDER_AWS_API_GATEWAY
            // 腾讯云SecretId: AKID开头
            apiKey.startsWith("AKID") && apiKey.length >= 30 -> PROVIDER_TENCENT_API_GATEWAY
            else -> {
                Log.w(TAG, "无法检测API Key类型: ${apiKey.take(4)}...")
                null
            }
        }
    }
}

