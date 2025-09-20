package org.thoughtcrime.securesms.tap

import android.content.Context
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
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