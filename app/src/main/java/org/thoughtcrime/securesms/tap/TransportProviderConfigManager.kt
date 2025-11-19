package org.thoughtcrime.securesms.tap

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Transport Provider配置管理器
 * 
 * 负责Provider配置的持久化存储、读取、验证和管理。
 * 使用Signal的安全KeyValueStore进行配置存储，确保敏感配置数据的安全性。
 */
class TransportProviderConfigManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportProviderConfigManager::class.java)
        
        // 安全存储相关常量
        private const val KEY_PROVIDER_CONFIGS = "tap.provider_configs"
        private const val KEY_ENABLED_PROVIDERS = "tap.enabled_providers"
        private const val KEY_CONFIG_VERSION = "tap.config_version"
        private const val KEY_NOTIFICATION_CONFIG = "tap.notification_config"
        private const val KEY_CONTACT_NOTIFICATION_CONFIGS = "tap.contact_notification_configs"
        private const val CURRENT_CONFIG_VERSION = 1
        
        // 单例实例
        @Volatile
        private var INSTANCE: TransportProviderConfigManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): TransportProviderConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportProviderConfigManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }
    
    private val tapValues by lazy { SignalStore.tap }
    private val objectMapper = ObjectMapper()
    private val mapType = object : TypeReference<Map<String, Any>>() {}
    private val configsMapType = object : TypeReference<Map<String, Map<String, Any>>>() {}
    
    // 内存缓存，提高读取性能
    private val configCache = ConcurrentHashMap<String, Map<String, Any>>()
    private var cacheVersion = -1
    
    /**
     * 保存Provider配置
     * 
     * @param providerType Provider类型标识
     * @param config 配置数据
     * @return 保存是否成功
     */
    fun saveProviderConfig(providerType: String, config: Map<String, Any>): Boolean {
        return try {
            Log.i(TAG, "保存Provider配置: $providerType")
            
            // 获取所有现有配置并更新
            val allConfigs = tapValues.getProviderConfigs().toMutableMap()
            allConfigs[providerType] = config
            
            // 保存到安全存储
            tapValues.setProviderConfigs(allConfigs)
            
            // 更新缓存
            updateCache(allConfigs)
            cacheVersion = CURRENT_CONFIG_VERSION
            
            Log.i(TAG, "Provider配置保存成功: $providerType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存Provider配置失败: $providerType", e)
            false
        }
    }
    
    /**
     * 获取Provider配置
     * 
     * @param providerType Provider类型标识
     * @return 配置数据，如果不存在则返回null
     */
    fun getProviderConfig(providerType: String): Map<String, Any>? {
        return try {
            getAllConfigs()[providerType]
        } catch (e: Exception) {
            Log.e(TAG, "获取Provider配置失败: $providerType", e)
            null
        }
    }
    
    /**
     * 获取所有Provider配置
     * 
     * @return 所有Provider的配置数据映射
     */
    fun getAllConfigs(): Map<String, Map<String, Any>> {
        return try {
            val currentVersion = tapValues.getConfigVersion()
            
            // 检查缓存是否有效
            if (cacheVersion == currentVersion && configCache.isNotEmpty()) {
                return configCache.mapValues { it.value.toMap() }
            }
            
            // 从安全存储读取
            val configs = tapValues.getProviderConfigs()
            
            // 更新缓存
            updateCache(configs)
            cacheVersion = currentVersion
            
            configs
        } catch (e: Exception) {
            Log.e(TAG, "获取所有Provider配置失败", e)
            emptyMap()
        }
    }
    
    /**
     * 删除Provider配置
     * 
     * @param providerType Provider类型标识
     * @return 删除是否成功
     */
    fun deleteProviderConfig(providerType: String): Boolean {
        return try {
            Log.i(TAG, "删除Provider配置: $providerType")
            
            val allConfigs = getAllConfigs().toMutableMap()
            val removed = allConfigs.remove(providerType)
            
            if (removed != null) {
                tapValues.setProviderConfigs(allConfigs)
                
                // 更新缓存
                updateCache(allConfigs)
                cacheVersion = CURRENT_CONFIG_VERSION
                
                // 同时禁用该Provider
                disableProvider(providerType)
                
                Log.i(TAG, "Provider配置删除成功: $providerType")
                true
            } else {
                Log.w(TAG, "要删除的Provider配置不存在: $providerType")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除Provider配置失败: $providerType", e)
            false
        }
    }
    
    /**
     * 检查Provider配置是否存在
     * 
     * @param providerType Provider类型标识
     * @return 配置是否存在
     */
    fun hasProviderConfig(providerType: String): Boolean {
        return getProviderConfig(providerType) != null
    }
    
    /**
     * 启用Provider
     * 
     * @param providerType Provider类型标识
     * @return 启用是否成功
     */
    fun enableProvider(providerType: String): Boolean {
        return try {
            // 检查配置是否存在
            if (!hasProviderConfig(providerType)) {
                Log.w(TAG, "尝试启用不存在配置的Provider: $providerType")
                return false
            }
            
            val enabledProviders = getEnabledProviders().toMutableSet()
            val wasAdded = enabledProviders.add(providerType)
            
            if (wasAdded) {
                saveEnabledProviders(enabledProviders)
                Log.i(TAG, "Provider已启用: $providerType")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "启用Provider失败: $providerType", e)
            false
        }
    }
    
    /**
     * 禁用Provider
     * 
     * @param providerType Provider类型标识
     * @return 禁用是否成功
     */
    fun disableProvider(providerType: String): Boolean {
        return try {
            val enabledProviders = getEnabledProviders().toMutableSet()
            val wasRemoved = enabledProviders.remove(providerType)
            
            if (wasRemoved) {
                saveEnabledProviders(enabledProviders)
                Log.i(TAG, "Provider已禁用: $providerType")
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "禁用Provider失败: $providerType", e)
            false
        }
    }
    
    /**
     * 获取已启用的Provider列表
     * 
     * @return 已启用的Provider类型集合
     */
    fun getEnabledProviders(): Set<String> {
        return try {
            tapValues.getEnabledProviders()
        } catch (e: Exception) {
            Log.e(TAG, "获取已启用Provider列表失败", e)
            emptySet()
        }
    }
    
    /**
     * 检查Provider是否已启用
     * 
     * @param providerType Provider类型标识
     * @return 是否已启用
     */
    fun isProviderEnabled(providerType: String): Boolean {
        return getEnabledProviders().contains(providerType)
    }
    
    /**
     * 获取已配置的Provider列表
     * 
     * @return 已配置的Provider类型列表
     */
    fun getConfiguredProviders(): List<String> {
        return getAllConfigs().keys.toList()
    }
    
    /**
     * 获取已配置且已启用的Provider列表
     * 
     * @return 已配置且已启用的Provider类型列表
     */
    fun getActiveProviders(): List<String> {
        val configured = getConfiguredProviders().toSet()
        val enabled = getEnabledProviders()
        return configured.intersect(enabled).toList()
    }
    
    /**
     * 批量验证所有Provider配置
     * 
     * @param descriptors Provider配置描述器映射
     * @return 批量验证结果
     */
    fun validateAllConfigs(descriptors: Map<String, ProviderConfigDescriptor>): BatchConfigValidationResult {
        val results = mutableMapOf<String, ConfigValidationResult>()
        
        getAllConfigs().forEach { (providerType, config) ->
            val descriptor = descriptors[providerType]
            if (descriptor != null) {
                results[providerType] = descriptor.validateConfig(config)
            } else {
                results[providerType] = ConfigValidationResult.failure(
                    "provider", 
                    "找不到Provider类型 $providerType 的配置描述器"
                )
            }
        }
        
        return BatchConfigValidationResult(results)
    }
    
    /**
     * 清空所有配置
     * 
     * 谨慎使用，会删除所有Provider配置
     */
    fun clearAllConfigs(): Boolean {
        return try {
            Log.w(TAG, "清空所有Provider配置")
            
            tapValues.clearProviderConfigs()
            
            // 清空缓存
            configCache.clear()
            cacheVersion = CURRENT_CONFIG_VERSION
            
            Log.i(TAG, "所有Provider配置已清空")
            true
        } catch (e: Exception) {
            Log.e(TAG, "清空配置失败", e)
            false
        }
    }
    
    /**
     * 导出配置数据（用于备份）
     * 
     * @return JSON格式的配置数据
     */
    fun exportConfigs(): String? {
        return try {
            val exportData = mapOf<String, Any>(
                "version" to CURRENT_CONFIG_VERSION,
                "configs" to getAllConfigs(),
                "enabled" to getEnabledProviders(),
                "timestamp" to System.currentTimeMillis()
            )
            objectMapper.writeValueAsString(exportData)
        } catch (e: Exception) {
            Log.e(TAG, "导出配置失败", e)
            null
        }
    }
    
    /**
     * 导入配置数据（用于恢复）
     * 
     * @param configJson JSON格式的配置数据
     * @return 导入是否成功
     */
    fun importConfigs(configJson: String): Boolean {
        return try {
            Log.i(TAG, "导入Provider配置")
            
            val importData = objectMapper.readValue(configJson, object : TypeReference<Map<String, Any>>() {})
            
            // 检查版本兼容性
            val version = importData["version"] as? Int ?: 0
            if (version > CURRENT_CONFIG_VERSION) {
                Log.w(TAG, "导入的配置版本 $version 高于当前支持的版本 $CURRENT_CONFIG_VERSION")
                return false
            }
            
            // 导入配置
            val configs = importData["configs"] as? Map<String, Any> ?: emptyMap()
            val enabled = (importData["enabled"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            
            // 保存配置
            tapValues.setProviderConfigs(configs as Map<String, Map<String, Any>>)
            tapValues.setEnabledProviders(enabled)
            
            // 更新缓存
            @Suppress("UNCHECKED_CAST")
            updateCache(configs as Map<String, Map<String, Any>>)
            cacheVersion = CURRENT_CONFIG_VERSION
            
            Log.i(TAG, "Provider配置导入成功，共导入 ${configs.size} 个配置，启用 ${enabled.size} 个Provider")
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入配置失败", e)
            false
        }
    }
    
    /**
     * 获取配置统计信息
     */
    fun getConfigStats(): ConfigStats {
        val allConfigs = getAllConfigs()
        val enabledProviders = getEnabledProviders()
        
        return ConfigStats(
            totalConfigured = allConfigs.size,
            totalEnabled = enabledProviders.size,
            configuredProviders = allConfigs.keys.toList(),
            enabledProviders = enabledProviders.toList(),
            configVersion = tapValues.getConfigVersion()
        )
    }
    
    /**
     * 保存已启用Provider列表
     */
    private fun saveEnabledProviders(providers: Set<String>) {
        try {
            tapValues.setEnabledProviders(providers)
        } catch (e: Exception) {
            Log.e(TAG, "保存已启用Provider列表失败", e)
        }
    }
    
    /**
     * 更新内存缓存
     */
    private fun updateCache(configs: Map<String, Map<String, Any>>) {
        configCache.clear()
        configCache.putAll(configs)
    }
    
    // ====================================
    // 推送服务配置管理
    // ====================================
    
    /**
     * 保存推送服务配置
     * 
     * @param config 推送服务配置
     * @return 保存是否成功
     */
    fun saveNotificationConfig(config: NotificationConfig): Boolean {
        return try {
            Log.i(TAG, "保存推送服务配置: provider=${config.provider}")
            
            if (!config.validate()) {
                Log.w(TAG, "推送服务配置验证失败")
                return false
            }
            
            // 将NotificationConfig转换为Map存储
            val configMap = mutableMapOf(
                "provider" to config.provider,
                "webhookUrl" to config.webhookUrl,
                "notifySecret" to config.notifySecret,
                "pushServiceInfo" to mapOf(
                    "endpoint" to config.pushServiceInfo.endpoint,
                    "region" to config.pushServiceInfo.region,
                    "credentials" to config.pushServiceInfo.credentials,
                    "metadata" to config.pushServiceInfo.metadata
                ),
                "deployedAt" to config.deployedAt,
                "version" to config.version
            )
            
            // 添加websocketManagementEndpoint（如果存在）
            config.websocketManagementEndpoint?.let {
                configMap["websocketManagementEndpoint"] = it
            }
            
            tapValues.setNotificationConfig(configMap)
            
            Log.i(TAG, "推送服务配置保存成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存推送服务配置失败", e)
            false
        }
    }
    
    /**
     * 获取推送服务配置
     * 
     * @return 推送服务配置，如果不存在则返回null
     */
    fun getNotificationConfig(): NotificationConfig? {
        return try {
            val configMap = tapValues.getNotificationConfig() ?: return null
            
            // 从Map转换为NotificationConfig
            val provider = configMap["provider"] as? String ?: return null
            val webhookUrl = configMap["webhookUrl"] as? String ?: return null
            val notifySecret = configMap["notifySecret"] as? String ?: return null
            val deployedAt = (configMap["deployedAt"] as? Number)?.toLong() ?: return null
            val version = configMap["version"] as? String ?: "1.0"
            
            @Suppress("UNCHECKED_CAST")
            val pushServiceInfoMap = configMap["pushServiceInfo"] as? Map<String, Any> ?: return null
            val endpoint = pushServiceInfoMap["endpoint"] as? String ?: return null
            val region = pushServiceInfoMap["region"] as? String ?: return null
            val credentials = pushServiceInfoMap["credentials"] as? Map<String, String> ?: emptyMap()
            val metadata = pushServiceInfoMap["metadata"] as? Map<String, Any> ?: emptyMap()
            
            val pushServiceInfo = org.thoughtcrime.securesms.tap.notification.PushServiceInfo(
                endpoint = endpoint,
                region = region,
                credentials = credentials,
                metadata = metadata
            )
            
            // 读取websocketManagementEndpoint（向后兼容）
            val websocketEndpoint = configMap["websocketManagementEndpoint"] as? String
            
            NotificationConfig(
                provider = provider,
                webhookUrl = webhookUrl,
                notifySecret = notifySecret,
                pushServiceInfo = pushServiceInfo,
                deployedAt = deployedAt,
                version = version,
                websocketManagementEndpoint = websocketEndpoint
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取推送服务配置失败", e)
            null
        }
    }
    
    /**
     * 删除推送服务配置
     * 
     * @return 删除是否成功
     */
    fun deleteNotificationConfig(): Boolean {
        return try {
            Log.i(TAG, "删除推送服务配置")
            tapValues.clearNotificationConfig()
            Log.i(TAG, "推送服务配置删除成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除推送服务配置失败", e)
            false
        }
    }
    
    /**
     * 检查是否启用推送通知模式
     * 
     * @return 如果有有效的推送服务配置则返回true，否则返回false
     */
    fun isNotificationEnabled(): Boolean {
        return try {
            val config = getNotificationConfig()
            val enabled = config != null && config.validate()
            
            if (enabled) {
                Log.d(TAG, "推送通知模式已启用: provider=${config?.provider}")
            } else {
                Log.v(TAG, "推送通知模式未启用")
            }
            
            enabled
        } catch (e: Exception) {
            Log.w(TAG, "检查推送通知模式时出错", e)
            false
        }
    }
    
    /**
     * 保存联系人的推送服务配置
     * 
     * @param contactId 联系人ID (ACI)
     * @param config 联系人推送服务配置
     * @return 保存是否成功
     */
    fun saveContactNotificationConfig(contactId: String, config: ContactNotificationConfig): Boolean {
        return try {
            Log.i(TAG, "保存联系人推送配置: contactId=$contactId")
            
            if (!config.validate()) {
                Log.w(TAG, "联系人推送配置验证失败")
                return false
            }
            
            // 获取所有联系人配置并更新
            val allConfigs = getAllContactNotificationConfigs().toMutableMap()
            
            // 将ContactNotificationConfig转换为Map存储
            val configMap = mapOf(
                "contactId" to config.contactId,
                "platform" to config.platform,
                "webhookUrl" to config.webhookUrl,
                "notifySecret" to config.notifySecret,
                "userId" to config.userId,
                "lastUpdated" to config.lastUpdated,
                "verified" to config.verified,
                "websocketManagementEndpoint" to (config.websocketManagementEndpoint ?: ""),
                "gatewayRegion" to (config.gatewayRegion ?: ""),
                "gatewayProvider" to (config.gatewayProvider ?: ""),
                "offlineBucket" to (config.offlineBucket ?: ""),
                "presignDelegation" to config.presignDelegation,
                "gatewayMetadata" to config.gatewayMetadata
            )
            
            allConfigs[contactId] = configMap
            tapValues.setContactNotificationConfigs(allConfigs)
            
            Log.i(TAG, "联系人推送配置保存成功: contactId=$contactId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存联系人推送配置失败: contactId=$contactId", e)
            false
        }
    }
    
    /**
     * 获取联系人的推送服务配置
     * 
     * @param contactId 联系人ID (ACI)
     * @return 联系人推送服务配置，如果不存在则返回null
     */
    fun getContactNotificationConfig(contactId: String): ContactNotificationConfig? {
        return try {
            val allConfigs = getAllContactNotificationConfigs()
            val configMap = allConfigs[contactId] ?: return null
            
            // 从Map转换为ContactNotificationConfig
            val contactIdValue = configMap["contactId"] as? String ?: return null
            val platform = configMap["platform"] as? String ?: return null
            val webhookUrl = configMap["webhookUrl"] as? String ?: return null
            val notifySecret = configMap["notifySecret"] as? String ?: return null
            val userId = configMap["userId"] as? String ?: return null
            val lastUpdated = (configMap["lastUpdated"] as? Number)?.toLong() ?: return null
            val verified = configMap["verified"] as? Boolean ?: false
            // 优先读取新字段名，向后兼容旧字段名
            val websocketEndpoint = (configMap["websocketManagementEndpoint"] as? String).takeUnless { it.isNullOrEmpty() }
                ?: (configMap["gatewayEndpoint"] as? String).takeUnless { it.isNullOrEmpty() }
            val gatewayRegion = (configMap["gatewayRegion"] as? String).takeUnless { it.isNullOrEmpty() }
            val gatewayProvider = (configMap["gatewayProvider"] as? String).takeUnless { it.isNullOrEmpty() }
            val offlineBucket = (configMap["offlineBucket"] as? String).takeUnless { it.isNullOrEmpty() }
            val presignDelegation = configMap["presignDelegation"] as? Boolean ?: false
            val gatewayMetadataAny = configMap["gatewayMetadata"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val gatewayMetadata = gatewayMetadataAny.entries.associate { (k, v) -> k.toString() to v.toString() }
            
            ContactNotificationConfig(
                contactId = contactIdValue,
                platform = platform,
                webhookUrl = webhookUrl,
                notifySecret = notifySecret,
                userId = userId,
                lastUpdated = lastUpdated,
                verified = verified,
                websocketManagementEndpoint = websocketEndpoint,
                gatewayRegion = gatewayRegion,
                gatewayProvider = gatewayProvider,
                offlineBucket = offlineBucket,
                presignDelegation = presignDelegation,
                gatewayMetadata = gatewayMetadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取联系人推送配置失败: contactId=$contactId", e)
            null
        }
    }
    
    /**
     * 获取所有联系人的推送服务配置
     * 
     * @return 联系人ID到配置Map的映射
     */
    private fun getAllContactNotificationConfigs(): Map<String, Map<String, Any>> {
        return try {
            tapValues.getContactNotificationConfigs()
        } catch (e: Exception) {
            Log.e(TAG, "获取所有联系人推送配置失败", e)
            emptyMap()
        }
    }
    
    /**
     * 获取所有联系人的推送服务配置列表
     * 
     * @return 联系人推送服务配置列表
     */
    fun getContactNotificationConfigList(): List<ContactNotificationConfig> {
        return try {
            val allConfigs = getAllContactNotificationConfigs()
            allConfigs.mapNotNull { (contactId, _) ->
                getContactNotificationConfig(contactId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取联系人推送配置列表失败", e)
            emptyList()
        }
    }
    
    /**
     * 删除联系人的推送服务配置
     * 
     * @param contactId 联系人ID (ACI)
     * @return 删除是否成功
     */
    fun deleteContactNotificationConfig(contactId: String): Boolean {
        return try {
            Log.i(TAG, "删除联系人推送配置: contactId=$contactId")
            
            val allConfigs = getAllContactNotificationConfigs().toMutableMap()
            val removed = allConfigs.remove(contactId)
            
            if (removed != null) {
                tapValues.setContactNotificationConfigs(allConfigs)
                Log.i(TAG, "联系人推送配置删除成功: contactId=$contactId")
                true
            } else {
                Log.w(TAG, "要删除的联系人推送配置不存在: contactId=$contactId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除联系人推送配置失败: contactId=$contactId", e)
            false
        }
    }
    
    /**
     * 清空所有联系人的推送服务配置
     * 
     * @return 清空是否成功
     */
    fun clearAllContactNotificationConfigs(): Boolean {
        return try {
            Log.w(TAG, "清空所有联系人推送配置")
            tapValues.clearContactNotificationConfigs()
            Log.i(TAG, "所有联系人推送配置已清空")
            true
        } catch (e: Exception) {
            Log.e(TAG, "清空联系人推送配置失败", e)
            false
        }
    }
    
    /**
     * 检查是否已配置推送服务
     * 
     * @return 是否已配置推送服务
     */
    fun hasNotificationConfig(): Boolean {
        return getNotificationConfig() != null
    }
    
    /**
     * 检查联系人是否已配置推送服务
     * 
     * @param contactId 联系人ID (ACI)
     * @return 联系人是否已配置推送服务
     */
    fun hasContactNotificationConfig(contactId: String): Boolean {
        return getContactNotificationConfig(contactId) != null
    }
}

/**
 * 配置统计信息
 */
data class ConfigStats(
    val totalConfigured: Int,
    val totalEnabled: Int,
    val configuredProviders: List<String>,
    val enabledProviders: List<String>,
    val configVersion: Int
) {
    /**
     * 获取已配置但未启用的Provider
     */
    val disabledProviders: List<String>
        get() = configuredProviders - enabledProviders.toSet()
    
    /**
     * 获取配置完整率（已启用/已配置）
     */
    val enabledRatio: Float
        get() = if (totalConfigured > 0) totalEnabled.toFloat() / totalConfigured else 0f
} 
