package org.thoughtcrime.securesms.tap.notification.integration

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log
import org.json.JSONObject
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.notification.PushServiceInfo

/**
 * 配置缓存管理器
 * 
 * 提供本地配置缓存和同步机制
 */
class ConfigurationCache(
    private val context: Context,
    private val provider: String
) {
    
    companion object {
        private val TAG = Log.tag(ConfigurationCache::class.java)
        private const val PREF_NAME = "tap_notification_config_cache"
        private const val KEY_CONFIG = "cached_config"
        private const val KEY_VERSION = "config_version"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_NOTIFY_SECRET = "notify_secret"
        private const val KEY_DEPLOYED_AT = "deployed_at"
        
        private const val SYNC_INTERVAL_MS = 3600000L // 1 hour
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "${PREF_NAME}_$provider",
        Context.MODE_PRIVATE
    )
    
    /**
     * 保存配置到本地缓存
     */
    fun saveLocal(config: NotificationConfig) {
        try {
            val configJson = JSONObject().apply {
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
            
            prefs.edit().apply {
                putString(KEY_CONFIG, configJson)
                putString(KEY_VERSION, config.version)
                putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                putString(KEY_WEBHOOK_URL, config.webhookUrl)
                putString(KEY_NOTIFY_SECRET, config.notifySecret)
                putLong(KEY_DEPLOYED_AT, config.deployedAt)
                apply()
            }
            
            Log.i(TAG, "配置已保存到本地缓存: version=${config.version}")
            
        } catch (e: Exception) {
            Log.e(TAG, "保存配置到本地缓存失败", e)
        }
    }
    
    /**
     * 从本地缓存加载配置
     */
    fun loadLocal(): NotificationConfig? {
        return try {
            val configJson = prefs.getString(KEY_CONFIG, null) ?: return null
            
            val json = JSONObject(configJson)
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
            Log.e(TAG, "从本地缓存加载配置失败", e)
            null
        }
    }
    
    /**
     * 获取缓存的版本号
     */
    fun getCachedVersion(): String? {
        return prefs.getString(KEY_VERSION, null)
    }
    
    /**
     * 检查是否需要同步
     */
    fun needsSync(): Boolean {
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0)
        val now = System.currentTimeMillis()
        return (now - lastSync) > SYNC_INTERVAL_MS
    }
    
    /**
     * 比较版本
     */
    fun compareVersion(remoteVersion: String): Int {
        val localVersion = getCachedVersion() ?: return -1
        
        return try {
            val localParts = localVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(localParts.size, remoteParts.size)
            
            for (i in 0 until maxLength) {
                val local = localParts.getOrElse(i) { 0 }
                val remote = remoteParts.getOrElse(i) { 0 }
                
                when {
                    local < remote -> return -1
                    local > remote -> return 1
                }
            }
            
            0
        } catch (e: Exception) {
            Log.w(TAG, "版本比较失败", e)
            0
        }
    }
    
    /**
     * 清除本地缓存
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        Log.i(TAG, "本地配置缓存已清除")
    }
    
    /**
     * 获取快速访问的webhook URL
     */
    fun getWebhookUrl(): String? {
        return prefs.getString(KEY_WEBHOOK_URL, null)
    }
    
    /**
     * 获取部署时间
     */
    fun getDeployedAt(): Long {
        return prefs.getLong(KEY_DEPLOYED_AT, 0)
    }
}

