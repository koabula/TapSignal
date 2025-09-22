package org.thoughtcrime.securesms.keyvalue

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * TAP模块的配置存储
 */
class TapValues internal constructor(store: KeyValueStore) : SignalStoreValues(store) {
    
    private val objectMapper = ObjectMapper()
    
    override fun onFirstEverAppLaunch() {
        // TAP模块首次启动时的初始化逻辑
        // 清空任何可能存在的配置
        clearProviderConfigs()
        clearTokens()
    }
    
    override fun getKeysToIncludeInBackup(): List<String> {
        // 返回需要包含在备份中的配置键
        // 注意：Token相关的键不包含在备份中，因为它们是临时性的且包含敏感信息
        return listOf(
            KEY_PROVIDER_CONFIGS,
            KEY_ENABLED_PROVIDERS,
            KEY_CONFIG_VERSION
        )
    }
    
    companion object {
        // Provider配置相关键
        private const val KEY_PROVIDER_CONFIGS = "tap.provider_configs"
        private const val KEY_ENABLED_PROVIDERS = "tap.enabled_providers"
        private const val KEY_CONFIG_VERSION = "tap.config_version"
        
        // Token池相关键
        private const val KEY_RECEIVED_TOKENS = "tap.received_tokens"
        private const val KEY_SHARED_TOKENS = "tap.shared_tokens"
        private const val KEY_LAST_CLEANUP_TIME = "tap.last_cleanup_time"
        private const val KEY_LAST_SAVE_TIME = "tap.last_save_time"
        private const val KEY_TOKEN_METADATA = "tap.token_metadata"
        
        // 初始化状态相关键
        private const val KEY_TAP_INITIALIZED = "tap.initialized"
        private const val KEY_INIT_VERSION = "tap.init_version"
        private const val KEY_LEGACY_MIGRATION_COMPLETED = "tap.legacy_migration_completed"
        private const val KEY_INIT_TIMESTAMP = "tap.init_timestamp"
        private const val KEY_MIGRATION_TIMESTAMP = "tap.migration_timestamp"
        
        private const val CURRENT_CONFIG_VERSION = 1
        private const val CURRENT_INIT_VERSION = 1
    }
    
    // Provider配置相关方法
    fun getProviderConfigs(): Map<String, Map<String, Any>> {
        val configJson = store.getString(KEY_PROVIDER_CONFIGS, null)
        return if (configJson.isNullOrEmpty()) {
            emptyMap()
        } else {
            try {
                objectMapper.readValue(configJson, object : TypeReference<Map<String, Map<String, Any>>>() {})
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
    
    fun setProviderConfigs(configs: Map<String, Map<String, Any>>) {
        try {
            val configJson = objectMapper.writeValueAsString(configs)
            store.beginWrite()
                .putString(KEY_PROVIDER_CONFIGS, configJson)
                .putInteger(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)
                .apply()
        } catch (e: Exception) {
            // Log error but don't throw
        }
    }
    
    fun getEnabledProviders(): Set<String> {
        val enabledJson = store.getString(KEY_ENABLED_PROVIDERS, null)
        return if (enabledJson.isNullOrEmpty()) {
            emptySet()
        } else {
            try {
                objectMapper.readValue(enabledJson, object : TypeReference<Set<String>>() {})
            } catch (e: Exception) {
                emptySet()
            }
        }
    }
    
    fun setEnabledProviders(providers: Set<String>) {
        try {
            val enabledJson = objectMapper.writeValueAsString(providers)
            store.beginWrite()
                .putString(KEY_ENABLED_PROVIDERS, enabledJson)
                .apply()
        } catch (e: Exception) {
            // Log error but don't throw
        }
    }
    
    fun getConfigVersion(): Int {
        return store.getInteger(KEY_CONFIG_VERSION, 0)
    }
    
    fun clearProviderConfigs() {
        store.beginWrite()
            .remove(KEY_PROVIDER_CONFIGS)
            .remove(KEY_ENABLED_PROVIDERS)
            .putInteger(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)
            .apply()
    }
    
    // Token池相关方法
    fun getReceivedTokens(): String? {
        return store.getString(KEY_RECEIVED_TOKENS, null)
    }
    
    fun setReceivedTokens(tokensJson: String) {
        store.beginWrite()
            .putString(KEY_RECEIVED_TOKENS, tokensJson)
            .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
            .apply()
    }
    
    fun getSharedTokens(): String? {
        return store.getString(KEY_SHARED_TOKENS, null)
    }
    
    fun setSharedTokens(tokensJson: String) {
        store.beginWrite()
            .putString(KEY_SHARED_TOKENS, tokensJson)
            .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
            .apply()
    }
    
    fun getLastCleanupTime(): Long {
        return store.getLong(KEY_LAST_CLEANUP_TIME, 0)
    }
    
    fun setLastCleanupTime(time: Long) {
        store.beginWrite()
            .putLong(KEY_LAST_CLEANUP_TIME, time)
            .apply()
    }
    
    fun getTokenMetadata(): String? {
        return store.getString(KEY_TOKEN_METADATA, null)
    }
    
    fun setTokenMetadata(metadataJson: String) {
        store.beginWrite()
            .putString(KEY_TOKEN_METADATA, metadataJson)
            .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
            .apply()
    }
    
    fun clearTokens() {
        store.beginWrite()
            .remove(KEY_RECEIVED_TOKENS)
            .remove(KEY_SHARED_TOKENS)
            .remove(KEY_TOKEN_METADATA)
            .putLong(KEY_LAST_CLEANUP_TIME, 0)
            .apply()
    }
    
    // 初始化状态管理方法
    fun isTapInitialized(): Boolean {
        return store.getBoolean(KEY_TAP_INITIALIZED, false)
    }
    
    fun setTapInitialized(initialized: Boolean) {
        store.beginWrite()
            .putBoolean(KEY_TAP_INITIALIZED, initialized)
            .apply()
    }
    
    fun getInitVersion(): Int {
        return store.getInteger(KEY_INIT_VERSION, 0)
    }
    
    fun setInitVersion(version: Int) {
        store.beginWrite()
            .putInteger(KEY_INIT_VERSION, version)
            .apply()
    }
    
    fun isLegacyMigrationCompleted(): Boolean {
        return store.getBoolean(KEY_LEGACY_MIGRATION_COMPLETED, false)
    }
    
    fun setLegacyMigrationCompleted(completed: Boolean) {
        store.beginWrite()
            .putBoolean(KEY_LEGACY_MIGRATION_COMPLETED, completed)
            .putLong(KEY_MIGRATION_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    fun markInitializationComplete() {
        store.beginWrite()
            .putBoolean(KEY_TAP_INITIALIZED, true)
            .putInteger(KEY_INIT_VERSION, CURRENT_INIT_VERSION)
            .putLong(KEY_INIT_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    fun shouldPerformInitialization(): Boolean {
        return !isTapInitialized() || getInitVersion() < CURRENT_INIT_VERSION
    }
    
    fun getInitTimestamp(): Long {
        return store.getLong(KEY_INIT_TIMESTAMP, 0)
    }
    
    fun getMigrationTimestamp(): Long {
        return store.getLong(KEY_MIGRATION_TIMESTAMP, 0)
    }
    
    fun getCurrentInitVersion(): Int {
        return CURRENT_INIT_VERSION
    }
} 