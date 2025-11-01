package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.provider.aws.AwsIoTNotificationProvider

/**
 * 推送服务提供商工厂
 */
class NotificationProviderFactory {
    
    companion object {
        private val TAG = Log.tag(NotificationProviderFactory::class.java)
        
        const val PROVIDER_AWS_IOT = "aws-iot"
        const val PROVIDER_TENCENT_CLOUDBASE = "tencent-cloudbase"
        
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
        providerType: String, 
        config: Map<String, Any>,
        context: Context
    ): NotificationProvider? {
        return try {
            when (providerType) {
                PROVIDER_AWS_IOT -> {
                    val accessKeyId = config["apiKey"] as? String 
                        ?: return null.also { Log.w(TAG, "AWS IoT Provider 缺少 apiKey") }
                    val secretAccessKey = config["secretKey"] as? String
                        ?: return null.also { Log.w(TAG, "AWS IoT Provider 缺少 secretKey") }
                    val region = config["region"] as? String ?: "us-east-1"
                    
                    AwsIoTNotificationProvider(context, accessKeyId, secretAccessKey, region).also {
                        Log.i(TAG, "AWS IoT Provider 创建成功")
                    }
                }
                PROVIDER_TENCENT_CLOUDBASE -> {
                    Log.w(TAG, "腾讯云 CloudBase Provider 尚未实现")
                    null
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
        return setOf(PROVIDER_AWS_IOT, PROVIDER_TENCENT_CLOUDBASE)
    }
    
    fun isProviderSupported(providerType: String): Boolean {
        return getSupportedProviders().contains(providerType)
    }
    
    fun detectProviderType(apiKey: String): String? {
        return when {
            apiKey.startsWith("AKIA") || apiKey.contains("aws") -> PROVIDER_AWS_IOT
            apiKey.contains("tencent") || apiKey.contains("cloudbase") -> PROVIDER_TENCENT_CLOUDBASE
            else -> {
                Log.w(TAG, "无法检测API Key类型")
                null
            }
        }
    }
}

