package org.thoughtcrime.securesms.tap.notification.integration

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig
import org.thoughtcrime.securesms.tap.notification.NotificationDeployer

/**
 * 推送服务状态同步器
 * 
 * 支持本地缓存和远程同步
 */
class NotificationStateSync(
    private val context: Context,
    private val provider: String
) {
    
    companion object {
        private val TAG = Log.tag(NotificationStateSync::class.java)
    }
    
    private val mutex = Mutex()
    private var localConfig: NotificationConfig? = null
    private val contactConfigs = mutableMapOf<String, ContactNotificationConfig>()
    private val cache = ConfigurationCache(context, provider)
    
    suspend fun saveLocalConfig(config: NotificationConfig): Boolean {
        return mutex.withLock {
            try {
                if (!config.validate()) {
                    Log.w(TAG, "配置验证失败")
                    return@withLock false
                }
                
                // Save to memory
                localConfig = config
                
                // Save to local cache
                cache.saveLocal(config)
                
                Log.i(TAG, "本地推送配置已保存到内存和缓存")
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存本地推送配置失败", e)
                false
            }
        }
    }
    
    suspend fun loadLocalConfig(): NotificationConfig? {
        return mutex.withLock {
            // If in memory, return it
            if (localConfig != null) {
                return@withLock localConfig
            }
            
            // Try to load from cache
            val cached = cache.loadLocal()
            if (cached != null) {
                localConfig = cached
                Log.d(TAG, "从本地缓存加载配置成功")
            }
            
            localConfig
        }
    }
    
    /**
     * 同步配置
     * 
     * 检查远程配置是否更新，如果是则同步到本地
     */
    suspend fun syncWithRemote(deployer: NotificationDeployer): Boolean {
        return mutex.withLock {
            try {
                // Check if sync is needed
                if (!cache.needsSync()) {
                    Log.d(TAG, "配置同步间隔未到，跳过同步")
                    return@withLock true
                }
                
                Log.i(TAG, "开始同步远程配置")
                
                // Load remote config
                val remoteConfig = deployer.loadConfiguration()
                if (remoteConfig == null) {
                    Log.w(TAG, "无法加载远程配置")
                    return@withLock false
                }
                
                // Compare versions
                val comparison = cache.compareVersion(remoteConfig.version)
                
                when {
                    comparison < 0 -> {
                        // Remote is newer, update local
                        Log.i(TAG, "远程配置更新，同步到本地")
                        localConfig = remoteConfig
                        cache.saveLocal(remoteConfig)
                        true
                    }
                    comparison > 0 -> {
                        // Local is newer, update remote
                        Log.i(TAG, "本地配置更新，同步到远程")
                        if (localConfig != null) {
                            deployer.saveConfiguration(localConfig!!)
                        }
                        true
                    }
                    else -> {
                        // Versions match, no sync needed
                        Log.d(TAG, "配置版本一致，无需同步")
                        cache.saveLocal(remoteConfig) // Update sync time
                        true
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "配置同步失败", e)
                false
            }
        }
    }
    
    suspend fun saveContactConfig(config: ContactNotificationConfig): Boolean {
        return mutex.withLock {
            try {
                if (!config.validate()) {
                    Log.w(TAG, "联系人推送配置验证失败")
                    return@withLock false
                }
                
                contactConfigs[config.contactId] = config
                Log.i(TAG, "联系人推送配置已保存: ${config.contactId}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存联系人推送配置失败", e)
                false
            }
        }
    }
    
    suspend fun loadContactConfig(contactId: String): ContactNotificationConfig? {
        return mutex.withLock {
            contactConfigs[contactId]
        }
    }
    
    suspend fun removeContactConfig(contactId: String): Boolean {
        return mutex.withLock {
            try {
                contactConfigs.remove(contactId)
                Log.i(TAG, "联系人推送配置已移除: $contactId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "移除联系人推送配置失败", e)
                false
            }
        }
    }
    
    suspend fun getAllContactConfigs(): List<ContactNotificationConfig> {
        return mutex.withLock {
            contactConfigs.values.toList()
        }
    }
    
    suspend fun clear() {
        mutex.withLock {
            localConfig = null
            contactConfigs.clear()
            cache.clearCache()
            Log.i(TAG, "推送服务状态已清空")
        }
    }
}

