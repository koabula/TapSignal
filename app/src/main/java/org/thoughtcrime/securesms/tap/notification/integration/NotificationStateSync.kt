package org.thoughtcrime.securesms.tap.notification.integration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig

/**
 * 推送服务状态同步器
 */
class NotificationStateSync {
    
    companion object {
        private val TAG = Log.tag(NotificationStateSync::class.java)
    }
    
    private val mutex = Mutex()
    private var localConfig: NotificationConfig? = null
    private val contactConfigs = mutableMapOf<String, ContactNotificationConfig>()
    
    suspend fun saveLocalConfig(config: NotificationConfig): Boolean {
        return mutex.withLock {
            try {
                if (!config.validate()) {
                    Log.w(TAG, "配置验证失败")
                    return@withLock false
                }
                
                localConfig = config
                Log.i(TAG, "本地推送配置已保存")
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存本地推送配置失败", e)
                false
            }
        }
    }
    
    suspend fun loadLocalConfig(): NotificationConfig? {
        return mutex.withLock {
            localConfig
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
            Log.i(TAG, "推送服务状态已清空")
        }
    }
}

