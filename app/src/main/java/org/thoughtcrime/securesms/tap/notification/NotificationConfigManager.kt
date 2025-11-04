package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportProviderConfigManager
import org.thoughtcrime.securesms.tap.WebhookConfigData
import java.security.MessageDigest

/**
 * 推送服务配置管理器
 * 
 * 提供高级的webhook配置管理功能,包括批量管理、验证、同步等
 */
class NotificationConfigManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(NotificationConfigManager::class.java)
        
        @Volatile
        private var instance: NotificationConfigManager? = null
        
        fun getInstance(context: Context): NotificationConfigManager {
            return instance ?: synchronized(this) {
                instance ?: NotificationConfigManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val mutex = Mutex()
    private val configManager = TransportProviderConfigManager.getInstance(context)
    
    @Volatile
    private var cachedConfig: NotificationConfig? = null
    
    /**
     * 保存本地推送服务配置
     * 
     * @param config 推送服务配置
     * @return 保存是否成功
     */
    suspend fun saveLocalConfig(config: NotificationConfig): Boolean {
        return mutex.withLock {
            try {
                Log.i(TAG, "保存本地推送服务配置: provider=${config.provider}")
                
                if (!config.validate()) {
                    Log.w(TAG, "推送服务配置验证失败")
                    return@withLock false
                }
                
                val saved = configManager.saveNotificationConfig(config)
                if (saved) {
                    cachedConfig = config
                    Log.d(TAG, "配置保存成功，缓存已更新")
                }
                saved
            } catch (e: Exception) {
                Log.e(TAG, "保存本地推送服务配置失败", e)
                false
            }
        }
    }
    
    /**
     * 获取本地推送服务配置
     * 
     * @return 推送服务配置，如果不存在则返回null
     */
    suspend fun getLocalConfig(): NotificationConfig? {
        return mutex.withLock {
            try {
                if (cachedConfig != null) {
                    Log.d(TAG, "从缓存返回配置")
                    return@withLock cachedConfig
                }
                
                val config = configManager.getNotificationConfig()
                if (config != null) {
                    cachedConfig = config
                }
                config
            } catch (e: Exception) {
                Log.e(TAG, "获取本地推送服务配置失败", e)
                null
            }
        }
    }
    
    /**
     * 强制重新加载配置（清除缓存）
     */
    suspend fun reloadConfig(): NotificationConfig? {
        return mutex.withLock {
            try {
                Log.d(TAG, "强制重新加载配置")
                cachedConfig = null
                val config = configManager.getNotificationConfig()
                if (config != null) {
                    cachedConfig = config
                }
                config
            } catch (e: Exception) {
                Log.e(TAG, "重新加载配置失败", e)
                null
            }
        }
    }
    
    /**
     * 删除本地推送服务配置
     * 
     * @return 删除是否成功
     */
    suspend fun deleteLocalConfig(): Boolean {
        return mutex.withLock {
            try {
                Log.i(TAG, "删除本地推送服务配置")
                configManager.deleteNotificationConfig()
            } catch (e: Exception) {
                Log.e(TAG, "删除本地推送服务配置失败", e)
                false
            }
        }
    }
    
    /**
     * 保存联系人的推送服务配置
     * 
     * @param contactAci 联系人ACI
     * @param config 联系人推送服务配置
     * @return 保存是否成功
     */
    suspend fun saveContactConfig(contactAci: String, config: ContactNotificationConfig): Boolean {
        return mutex.withLock {
            try {
                Log.i(TAG, "保存联系人推送配置: contactAci=$contactAci")
                
                if (!config.validate()) {
                    Log.w(TAG, "联系人推送配置验证失败")
                    return@withLock false
                }
                
                val saved = configManager.saveContactNotificationConfig(contactAci, config)
                
                if (saved) {
                    uploadContactConfigToCOS(contactAci, config)
                }
                
                saved
            } catch (e: Exception) {
                Log.e(TAG, "保存联系人推送配置失败: contactAci=$contactAci", e)
                false
            }
        }
    }
    
    private suspend fun uploadContactConfigToCOS(contactAci: String, config: ContactNotificationConfig) {
        try {
            val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
            val provider = transportManager.getProvider("cos")
            
            if (provider == null) {
                Log.w(TAG, "COS Provider未找到，无法上传联系人配置")
                return
            }
            
            val contactHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(contactAci.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            
            val configJson = org.json.JSONObject().apply {
                put("webhookUrl", config.webhookUrl)
                put("notifySecret", config.notifySecret)
                put("userId", config.userId)
                put("platform", config.platform)
                put("lastUpdated", config.lastUpdated)
                put("version", "2.0")
            }.toString()
            
            val uploadPath = "tap-state/contacts/${contactHash}.json"
            
            val configManagerInstance = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            val cosConfig = configManagerInstance.getProviderConfig("cos")
            if (cosConfig == null) {
                Log.w(TAG, "COS配置未找到，无法上传联系人配置")
                return
            }
            
            // 创建一个简化的CosTransportMetadata用于内部配置上传
            // 由于这只是内部配置上传，使用最小化的metadata
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
            val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
            
            // 从cosConfig提取必要信息
            val bucketName = cosConfig["bucketName"] as? String ?: return
            val region = cosConfig["region"] as? String ?: return
            val myAddress = when (cosConfig["provider"] as? String) {
                "aws" -> "https://${bucketName}.s3.${region}.amazonaws.com"
                "tencent" -> "https://${bucketName}.cos.${region}.myqcloud.com"
                else -> return
            }
            
            // 创建最小化的CosTransportMetadata
            val metadata = org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
                recipientId = contactAci,
                providerType = "cos",
                myAddress = myAddress,
                myToken = null, // 内部上传使用provider自身的凭证
                myRegion = region,
                myBucketName = bucketName,
                mySendPath = "tap-state/contacts/",
                peerAddress = myAddress,
                peerToken = null,
                peerRegion = region,
                peerBucketName = bucketName,
                peerReceivePath = "tap-state/contacts/",
                myHashedId = myHashedId,
                peerHashedId = contactHash
            )
            
            val uploadResult = provider.uploadFile(
                data = configJson.toByteArray(),
                path = uploadPath,
                metadata = metadata
            )
            
            if (uploadResult is org.thoughtcrime.securesms.tap.TransportResult.Success) {
                Log.i(TAG, "联系人配置已上传到COS: $uploadPath")
            } else {
                Log.w(TAG, "联系人配置上传失败: $uploadResult")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "上传联系人配置到COS失败: contactAci=$contactAci", e)
        }
    }
    
    /**
     * 从WebhookConfigData创建并保存联系人配置
     * 
     * @param contactAci 联系人ACI
     * @param webhookData Webhook配置数据
     * @param platform 平台类型
     * @return 保存是否成功
     */
    suspend fun saveContactConfigFromWebhook(
        contactAci: String,
        webhookData: WebhookConfigData,
        platform: String
    ): Boolean {
        return mutex.withLock {
            try {
                if (!webhookData.validate()) {
                    Log.w(TAG, "Webhook配置数据验证失败")
                    return@withLock false
                }
                
                val config = ContactNotificationConfig(
                    contactId = contactAci,
                    platform = platform,
                    webhookUrl = webhookData.webhookUrl,
                    notifySecret = webhookData.notifySecret,
                    userId = webhookData.userId,
                    lastUpdated = System.currentTimeMillis(),
                    verified = false
                )
                
                saveContactConfig(contactAci, config)
            } catch (e: Exception) {
                Log.e(TAG, "从Webhook数据保存联系人配置失败", e)
                false
            }
        }
    }
    
    /**
     * 获取联系人的推送服务配置
     * 
     * @param contactAci 联系人ACI
     * @return 联系人推送服务配置，如果不存在则返回null
     */
    suspend fun getContactConfig(contactAci: String): ContactNotificationConfig? {
        return mutex.withLock {
            try {
                configManager.getContactNotificationConfig(contactAci)
            } catch (e: Exception) {
                Log.e(TAG, "获取联系人推送配置失败: contactAci=$contactAci", e)
                null
            }
        }
    }
    
    /**
     * 删除联系人的推送服务配置
     * 
     * @param contactAci 联系人ACI
     * @return 删除是否成功
     */
    suspend fun deleteContactConfig(contactAci: String): Boolean {
        return mutex.withLock {
            try {
                Log.i(TAG, "删除联系人推送配置: contactAci=$contactAci")
                configManager.deleteContactNotificationConfig(contactAci)
            } catch (e: Exception) {
                Log.e(TAG, "删除联系人推送配置失败: contactAci=$contactAci", e)
                false
            }
        }
    }
    
    /**
     * 获取所有联系人的推送服务配置
     * 
     * @return 联系人推送服务配置列表
     */
    suspend fun getAllContactConfigs(): List<ContactNotificationConfig> {
        return mutex.withLock {
            try {
                configManager.getContactNotificationConfigList()
            } catch (e: Exception) {
                Log.e(TAG, "获取所有联系人推送配置失败", e)
                emptyList()
            }
        }
    }
    
    /**
     * 批量保存联系人推送配置
     * 
     * @param configs 联系人配置映射(contactAci -> ContactNotificationConfig)
     * @return 成功保存的数量
     */
    suspend fun batchSaveContactConfigs(configs: Map<String, ContactNotificationConfig>): Int {
        return mutex.withLock {
            var savedCount = 0
            for ((contactAci, config) in configs) {
                try {
                    if (saveContactConfig(contactAci, config)) {
                        savedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "批量保存时出错: contactAci=$contactAci", e)
                }
            }
            Log.i(TAG, "批量保存联系人配置: 成功=$savedCount/${configs.size}")
            savedCount
        }
    }
    
    /**
     * 验证联系人的推送配置
     * 
     * @param contactAci 联系人ACI
     * @return 验证结果
     */
    suspend fun verifyContactConfig(contactAci: String): ContactConfigVerificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val config = getContactConfig(contactAci)
                if (config == null) {
                    return@withContext ContactConfigVerificationResult.NotFound
                }
                
                if (!config.validate()) {
                    return@withContext ContactConfigVerificationResult.Invalid("配置验证失败")
                }
                
                // TODO: 可以添加实际的webhook连通性测试
                // 当前只做基本验证
                
                ContactConfigVerificationResult.Valid(config)
            } catch (e: Exception) {
                Log.e(TAG, "验证联系人配置失败: contactAci=$contactAci", e)
                ContactConfigVerificationResult.Error(e.message ?: "验证异常")
            }
        }
    }
    
    /**
     * 标记联系人配置为已验证
     * 
     * @param contactAci 联系人ACI
     * @return 操作是否成功
     */
    suspend fun markContactConfigVerified(contactAci: String): Boolean {
        return mutex.withLock {
            try {
                val config = getContactConfig(contactAci)
                if (config == null) {
                    Log.w(TAG, "联系人配置不存在，无法标记验证: contactAci=$contactAci")
                    return@withLock false
                }
                
                val updatedConfig = config.copy(
                    verified = true,
                    lastUpdated = System.currentTimeMillis()
                )
                
                saveContactConfig(contactAci, updatedConfig)
            } catch (e: Exception) {
                Log.e(TAG, "标记联系人配置验证失败: contactAci=$contactAci", e)
                false
            }
        }
    }
    
    /**
     * 清空所有联系人的推送配置
     * 
     * @return 清空是否成功
     */
    suspend fun clearAllContactConfigs(): Boolean {
        return mutex.withLock {
            try {
                Log.w(TAG, "清空所有联系人推送配置")
                configManager.clearAllContactNotificationConfigs()
            } catch (e: Exception) {
                Log.e(TAG, "清空联系人推送配置失败", e)
                false
            }
        }
    }
    
    /**
     * 检查是否已配置本地推送服务
     * 
     * @return 是否已配置
     */
    suspend fun hasLocalConfig(): Boolean {
        return mutex.withLock {
            configManager.hasNotificationConfig()
        }
    }
    
    /**
     * 检查联系人是否已配置推送服务
     * 
     * @param contactAci 联系人ACI
     * @return 是否已配置
     */
    suspend fun hasContactConfig(contactAci: String): Boolean {
        return mutex.withLock {
            configManager.hasContactNotificationConfig(contactAci)
        }
    }
    
    /**
     * 获取配置统计信息
     * 
     * @return 配置统计
     */
    suspend fun getConfigStats(): NotificationConfigStats {
        return mutex.withLock {
            try {
                val hasLocal = hasLocalConfig()
                val allContacts = getAllContactConfigs()
                val verifiedContacts = allContacts.count { it.verified }
                
                NotificationConfigStats(
                    hasLocalConfig = hasLocal,
                    totalContacts = allContacts.size,
                    verifiedContacts = verifiedContacts,
                    unverifiedContacts = allContacts.size - verifiedContacts
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取配置统计失败", e)
                NotificationConfigStats(
                    hasLocalConfig = false,
                    totalContacts = 0,
                    verifiedContacts = 0,
                    unverifiedContacts = 0
                )
            }
        }
    }
}

/**
 * 联系人配置验证结果
 */
sealed class ContactConfigVerificationResult {
    data class Valid(val config: ContactNotificationConfig) : ContactConfigVerificationResult()
    data class Invalid(val reason: String) : ContactConfigVerificationResult()
    object NotFound : ContactConfigVerificationResult()
    data class Error(val message: String) : ContactConfigVerificationResult()
    
    fun isValid(): Boolean = this is Valid
}

/**
 * 推送配置统计信息
 */
data class NotificationConfigStats(
    val hasLocalConfig: Boolean,
    val totalContacts: Int,
    val verifiedContacts: Int,
    val unverifiedContacts: Int
) {
    val verificationRate: Float
        get() = if (totalContacts > 0) {
            verifiedContacts.toFloat() / totalContacts
        } else {
            0f
        }
}

