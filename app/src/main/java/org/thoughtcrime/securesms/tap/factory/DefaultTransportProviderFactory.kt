package org.thoughtcrime.securesms.tap.factory

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认的传输提供者工厂实现
 * 
 * 基于ProviderRegistrar机制的工厂实现，支持插件化的Provider管理。
 * 通过注册ProviderRegistrar来动态添加支持的Provider类型。
 */
class DefaultTransportProviderFactory(
    private val context: Context
) : TransportProviderFactory {
    
    companion object {
        private val TAG = Log.tag(DefaultTransportProviderFactory::class.java)
    }
    
    // 注：Provider注册器现在由ProviderRegistry统一管理
    
    // 配置验证结果缓存
    private val validationCache = ConcurrentHashMap<String, ConfigValidationResult>()
    private val defaultConfigCache = ConcurrentHashMap<String, Map<String, Any>>()
    
    // Provider注册中心
    private val providerRegistry = ProviderRegistry.getInstance(context)
    
    init {
        // 初始化Provider注册中心，自动发现和注册所有可用的providers
        providerRegistry.initialize()
    }
    
    override val supportedProviderTypes: Set<String>
        get() {
            try {
                // 确保ProviderRegistry已初始化
                if (!providerRegistry.initialize()) {
                    Log.w(TAG, "ProviderRegistry初始化失败")
                }
                
                val types = providerRegistry.getAvailableProviderTypes()
                Log.d(TAG, "获取支持的Provider类型: $types")
                
                if (types.isEmpty()) {
                    Log.w(TAG, "支持的Provider类型为空，可能存在初始化问题")
                }
                
                return types
            } catch (e: Exception) {
                Log.e(TAG, "获取支持的Provider类型失败: ${LogSanitizer.sanitizeThrowable(e)}")
                return emptySet()
            }
        }
    
    override fun createProvider(providerType: String, config: Map<String, Any>): TransportProvider? {
        return try {
            Log.d(TAG, "创建Provider实例: $providerType")
            
            val registrar = providerRegistry.getProviderRegistrar(providerType)
            if (registrar == null) {
                Log.w(TAG, "不支持的Provider类型: $providerType")
                return null
            }
            
            // 委托给ProviderRegistrar创建实例
            registrar.createProvider(config, context)
            
        } catch (e: Exception) {
            Log.e(TAG, "创建Provider失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    override fun validateConfig(providerType: String, config: Map<String, Any>): ConfigValidationResult {
        return try {
            val cacheKey = "${providerType}:${config.toString().hashCode()}"
            
            // 尝试从缓存获取验证结果
            validationCache[cacheKey]?.let { cached ->
                return cached
            }
            
            val registrar = providerRegistry.getProviderRegistrar(providerType)
            
            val result = if (registrar != null) {
                // 委托给ProviderConfigDescriptor进行验证
                registrar.getConfigDescriptor().validateConfig(config)
            } else {
                ConfigValidationResult.Invalid(
                    mapOf("providerType" to "不支持的Provider类型: $providerType")
                )
            }
            
            // 缓存验证结果（仅缓存有效的结果，避免缓存过期配置）
            if (result is ConfigValidationResult.Valid) {
                validationCache[cacheKey] = result
            }
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "验证配置失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            ConfigValidationResult.Invalid(
                mapOf("error" to "配置验证过程中发生错误: ${e.message}")
            )
        }
    }
    
    override fun getDefaultConfig(providerType: String): Map<String, Any> {
        return defaultConfigCache.computeIfAbsent(providerType.lowercase()) { type ->
            val registrar = providerRegistry.getProviderRegistrar(type)
            if (registrar != null) {
                // 从ConfigDescriptor获取默认配置
                try {
                    val configDescriptor = registrar.getConfigDescriptor()
                    val configFields = configDescriptor.getConfigFields()
                    
                    // 构建默认配置映射
                    configFields.associate { field ->
                        field.key to (field.defaultValue ?: when (field.fieldType) {
                            ConfigFieldType.TEXT, ConfigFieldType.PASSWORD, ConfigFieldType.SELECT -> ""
                            ConfigFieldType.NUMBER -> 0
                            ConfigFieldType.CHECKBOX -> false
                            else -> ""
                        })
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "获取默认配置失败: $type - ${LogSanitizer.sanitizeThrowable(e)}")
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        }
    }
    
    override fun registerProviderRegistrar(registrar: ProviderRegistrar): Boolean {
        return try {
            val success = providerRegistry.registerProvider(registrar)
            if (success) {
                // 清除相关缓存
                val providerType = registrar.providerType.lowercase()
                defaultConfigCache.remove(providerType)
                validationCache.entries.removeIf { it.key.startsWith("$providerType:") }
                
                Log.i(TAG, "注册Provider注册器: ${registrar.providerType}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "注册Provider注册器失败: ${registrar.providerType} - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    override fun unregisterProviderRegistrar(providerType: String): Boolean {
        return try {
            val success = providerRegistry.unregisterProvider(providerType)
            if (success) {
                // 清除相关缓存
                defaultConfigCache.remove(providerType.lowercase())
                validationCache.entries.removeIf { it.key.startsWith("$providerType:") }
                
                Log.i(TAG, "注销Provider注册器: $providerType")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "注销Provider注册器失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    override fun getProviderConfigDescriptor(providerType: String): ProviderConfigDescriptor? {
        return providerRegistry.getProviderConfigDescriptor(providerType)
    }
    
    /**
     * 清除配置缓存
     */
    fun clearCache() {
        defaultConfigCache.clear()
        validationCache.clear()
        Log.d(TAG, "清除工厂配置缓存")
    }
    

} 