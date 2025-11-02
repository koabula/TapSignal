package org.thoughtcrime.securesms.tap.provider.cos.utils.notification

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.io.File

/**
 * 联系人Webhook配置管理器
 * 
 * 负责在COS中保存和读取联系人的webhook配置信息
 * 路径: tap-state/contacts/{contactHash}.json
 */
class ContactWebhookManager(
    private val context: Context,
    private val cosClient: CosClient
) {
    
    companion object {
        private val TAG = Log.tag(ContactWebhookManager::class.java)
        private const val CONTACTS_PREFIX = "tap-state/contacts/"
    }
    
    /**
     * 保存联系人webhook配置
     */
    suspend fun saveContactConfig(config: ContactNotificationConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "保存联系人webhook配置: contactId=${LogSanitizer.sanitize(config.contactId)}")
                
                if (!config.validate()) {
                    Log.w(TAG, "配置验证失败")
                    return@withContext false
                }
                
                val jsonConfig = JSONObject().apply {
                    put("contactId", config.contactId)
                    put("platform", config.platform)
                    put("webhookUrl", config.webhookUrl)
                    put("notifySecret", config.notifySecret)
                    put("userId", config.userId)
                    put("lastUpdated", config.lastUpdated)
                    put("verified", config.verified)
                }.toString()
                
                val contactHash = hashContactId(config.contactId)
                val key = "${CONTACTS_PREFIX}${contactHash}.json"
                
                val tempFile = File.createTempFile("contact_config", ".json", context.cacheDir)
                try {
                    tempFile.writeText(jsonConfig)
                    
                    val uploadSuccess = cosClient.uploadFile(tempFile, key)
                    
                    if (uploadSuccess) {
                        Log.i(TAG, "联系人webhook配置保存成功: $key")
                        true
                    } else {
                        Log.e(TAG, "联系人webhook配置保存失败: $key")
                        false
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "保存联系人webhook配置失败", e)
                false
            }
        }
    }
    
    /**
     * 读取联系人webhook配置
     */
    suspend fun loadContactConfig(contactId: String): ContactNotificationConfig? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "读取联系人webhook配置: contactId=${LogSanitizer.sanitize(contactId)}")
                
                val contactHash = hashContactId(contactId)
                val key = "${CONTACTS_PREFIX}${contactHash}.json"
                
                val tempFile = File.createTempFile("contact_config", ".json", context.cacheDir)
                try {
                    val downloadSuccess = cosClient.downloadFile(key, tempFile)
                    
                    if (!downloadSuccess || !tempFile.exists()) {
                        Log.d(TAG, "联系人webhook配置不存在: $key")
                        return@withContext null
                    }
                    
                    val jsonConfig = JSONObject(tempFile.readText())
                    
                    val config = ContactNotificationConfig(
                        contactId = jsonConfig.getString("contactId"),
                        platform = jsonConfig.getString("platform"),
                        webhookUrl = jsonConfig.getString("webhookUrl"),
                        notifySecret = jsonConfig.getString("notifySecret"),
                        userId = jsonConfig.getString("userId"),
                        lastUpdated = jsonConfig.getLong("lastUpdated"),
                        verified = jsonConfig.optBoolean("verified", false)
                    )
                    
                    Log.d(TAG, "联系人webhook配置加载成功")
                    config
                    
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "读取联系人webhook配置失败: contactId=${LogSanitizer.sanitize(contactId)}", e)
                null
            }
        }
    }
    
    /**
     * 列举所有联系人webhook配置
     */
    suspend fun listAllContactConfigs(): List<ContactNotificationConfig> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "列举所有联系人webhook配置")
                
                val files = cosClient.listFiles(CONTACTS_PREFIX)
                
                val configs = mutableListOf<ContactNotificationConfig>()
                
                for (fileInfo in files) {
                    if (!fileInfo.name.endsWith(".json")) {
                        continue
                    }
                    
                    try {
                        val tempFile = File.createTempFile("contact_config", ".json", context.cacheDir)
                        try {
                            val downloadSuccess = cosClient.downloadFile(fileInfo.name, tempFile)
                            
                            if (downloadSuccess && tempFile.exists()) {
                                val jsonConfig = JSONObject(tempFile.readText())
                                
                                val config = ContactNotificationConfig(
                                    contactId = jsonConfig.getString("contactId"),
                                    platform = jsonConfig.getString("platform"),
                                    webhookUrl = jsonConfig.getString("webhookUrl"),
                                    notifySecret = jsonConfig.getString("notifySecret"),
                                    userId = jsonConfig.getString("userId"),
                                    lastUpdated = jsonConfig.getLong("lastUpdated"),
                                    verified = jsonConfig.optBoolean("verified", false)
                                )
                                
                                if (config.validate()) {
                                    configs.add(config)
                                }
                            }
                        } finally {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "加载联系人配置失败: ${fileInfo.name}", e)
                    }
                }
                
                Log.i(TAG, "列举联系人webhook配置完成: 共${configs.size}个")
                configs
                
            } catch (e: Exception) {
                Log.e(TAG, "列举联系人webhook配置失败", e)
                emptyList()
            }
        }
    }
    
    /**
     * 删除联系人webhook配置
     */
    suspend fun deleteContactConfig(contactId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "删除联系人webhook配置: contactId=${LogSanitizer.sanitize(contactId)}")
                
                val contactHash = hashContactId(contactId)
                val key = "${CONTACTS_PREFIX}${contactHash}.json"
                
                val deleteSuccess = cosClient.deleteFile(key)
                
                if (deleteSuccess) {
                    Log.i(TAG, "联系人webhook配置删除成功: $key")
                    true
                } else {
                    Log.e(TAG, "联系人webhook配置删除失败: $key")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "删除联系人webhook配置失败", e)
                false
            }
        }
    }
    
    /**
     * 更新联系人webhook配置
     */
    suspend fun updateContactConfig(
        contactId: String,
        updates: (ContactNotificationConfig) -> ContactNotificationConfig
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "更新联系人webhook配置: contactId=${LogSanitizer.sanitize(contactId)}")
                
                val existingConfig = loadContactConfig(contactId)
                if (existingConfig == null) {
                    Log.w(TAG, "联系人webhook配置不存在，无法更新")
                    return@withContext false
                }
                
                val updatedConfig = updates(existingConfig).copy(
                    lastUpdated = System.currentTimeMillis()
                )
                
                saveContactConfig(updatedConfig)
                
            } catch (e: Exception) {
                Log.e(TAG, "更新联系人webhook配置失败", e)
                false
            }
        }
    }
    
    /**
     * 批量保存联系人webhook配置
     */
    suspend fun batchSaveContactConfigs(configs: List<ContactNotificationConfig>): Int {
        return withContext(Dispatchers.IO) {
            var successCount = 0
            
            for (config in configs) {
                if (saveContactConfig(config)) {
                    successCount++
                }
            }
            
            Log.i(TAG, "批量保存联系人webhook配置完成: ${successCount}/${configs.size}")
            successCount
        }
    }
    
    /**
     * 对联系人ID进行哈希处理，生成文件名
     */
    private fun hashContactId(contactId: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(contactId.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }.substring(0, 16)
        } catch (e: Exception) {
            Log.w(TAG, "哈希联系人ID失败，使用原始ID", e)
            contactId.replace("[^a-zA-Z0-9]".toRegex(), "").take(16)
        }
    }
}

