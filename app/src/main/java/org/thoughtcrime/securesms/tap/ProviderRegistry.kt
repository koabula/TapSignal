package org.thoughtcrime.securesms.tap

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Provider注册中心
 * 
 * 负责动态发现、注册和管理所有可用的TransportProvider类型。
 * 通过扫描provider目录下的provider.json文件来自动发现可用的providers。
 */
class ProviderRegistry private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(ProviderRegistry::class.java)
        
        @Volatile
        private var INSTANCE: ProviderRegistry? = null
        
        fun getInstance(context: Context): ProviderRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderRegistry(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
        
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
    
    // Provider注册器映射表
    private val registrars = ConcurrentHashMap<String, ProviderRegistrar>()
    private val registrarsLock = ReentrantReadWriteLock()
    
    // Provider元数据映射表
    private val providerMetadata = ConcurrentHashMap<String, ProviderMetadata>()
    
    // 初始化状态
    private var isInitialized = false
    
    /**
     * 初始化Provider注册中心
     */
    fun initialize(): Boolean {
        return try {
            if (isInitialized) {
                Log.d(TAG, "Provider注册中心已初始化")
                return true
            }
            
            Log.i(TAG, "开始初始化Provider注册中心")
            
            // 发现并注册所有可用的providers
            val discoveredProviders = discoverProviders()
            Log.i(TAG, "发现了 ${discoveredProviders.size} 个provider")
            
            var successCount = 0
            discoveredProviders.forEach { providerInfo ->
                if (registerProviderFromInfo(providerInfo)) {
                    successCount++
                } else {
                    Log.w(TAG, "注册provider失败: ${providerInfo.type}")
                }
            }
            
            isInitialized = true
            Log.i(TAG, "Provider注册中心初始化完成，成功注册了 $successCount 个provider")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Provider注册中心初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 获取所有已注册的Provider类型
     */
    fun getAvailableProviderTypes(): Set<String> {
        return registrarsLock.read { 
            registrars.keys.toSet() 
        }
    }
    
    /**
     * 获取Provider注册器
     */
    fun getProviderRegistrar(providerType: String): ProviderRegistrar? {
        return registrarsLock.read { 
            registrars[providerType.lowercase()] 
        }
    }
    
    /**
     * 获取Provider配置描述器
     */
    fun getProviderConfigDescriptor(providerType: String): ProviderConfigDescriptor? {
        return getProviderRegistrar(providerType)?.getConfigDescriptor()
    }
    
    /**
     * 获取Provider元数据
     */
    fun getProviderMetadata(providerType: String): ProviderMetadata? {
        return providerMetadata[providerType.lowercase()]
    }
    
    /**
     * 获取所有Provider元数据
     */
    fun getAllProviderMetadata(): List<ProviderMetadata> {
        return providerMetadata.values.toList()
    }
    
    /**
     * 手动注册Provider注册器
     */
    fun registerProvider(registrar: ProviderRegistrar): Boolean {
        return try {
            registrarsLock.write {
                val providerType = registrar.providerType.lowercase()
                registrars[providerType] = registrar
                providerMetadata[providerType] = registrar.getProviderMetadata()
                
                Log.i(TAG, "手动注册Provider: ${registrar.providerType}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "手动注册Provider失败: ${registrar.providerType} - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 注销Provider
     */
    fun unregisterProvider(providerType: String): Boolean {
        return try {
            registrarsLock.write {
                val removed = registrars.remove(providerType.lowercase())
                providerMetadata.remove(providerType.lowercase())
                
                if (removed != null) {
                    Log.i(TAG, "注销Provider: $providerType")
                    true
                } else {
                    Log.w(TAG, "尝试注销不存在的Provider: $providerType")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "注销Provider失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 发现所有可用的providers
     */
    private fun discoverProviders(): List<ProviderInfo> {
        val providers = mutableListOf<ProviderInfo>()
        
        try {
            // 扫描assets目录下的provider配置文件
            val assetManager = context.assets
            
            // 尝试列出provider目录
            try {
                val providerDirs = assetManager.list("providers") ?: emptyArray()
                providerDirs.forEach { providerDir ->
                    try {
                        val configStream = assetManager.open("providers/$providerDir/provider.json")
                        val providerInfo = parseProviderConfig(configStream, providerDir)
                        if (providerInfo != null) {
                            providers.add(providerInfo)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "无法读取provider配置: $providerDir - ${LogSanitizer.sanitizeThrowable(e)}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "assets/providers目录不存在，尝试从代码路径发现providers")
            }
            
            // 如果assets中没有找到，回退到硬编码的已知providers
            if (providers.isEmpty()) {
                providers.addAll(getKnownProviders())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "发现providers时出错: ${LogSanitizer.sanitizeThrowable(e)}")
            // 回退到硬编码的已知providers
            providers.addAll(getKnownProviders())
        }
        
        return providers
    }
    
    /**
     * 解析provider配置文件
     */
    private fun parseProviderConfig(configStream: InputStream, providerDir: String): ProviderInfo? {
        return try {
            val configText = configStream.bufferedReader().use { it.readText() }
            val jsonConfig = JSONObject(configText)
            val providerConfig = jsonConfig.getJSONObject("provider")
            
            ProviderInfo(
                type = providerConfig.getString("type"),
                name = providerConfig.getString("name"),
                description = providerConfig.getString("description"),
                version = providerConfig.optString("version", "1.0.0"),
                registrarClass = providerConfig.getString("registrarClass"),
                configDescriptorClass = providerConfig.getString("configDescriptorClass"),
                supportsAuth = providerConfig.optBoolean("supportsAuth", false),
                supportsGroup = providerConfig.optBoolean("supportsGroup", false),
                supportsConfigTest = providerConfig.optBoolean("supportsConfigTest", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析provider配置失败: $providerDir - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 获取已知的providers（回退方案）
     */
    private fun getKnownProviders(): List<ProviderInfo> {
        return listOf(
            ProviderInfo(
                type = "cos",
                name = "云对象存储 (COS)",
                description = "支持AWS S3和腾讯云COS的云存储服务，提供永久凭证管理和群组消息传输功能",
                version = "1.0.0",
                registrarClass = "org.thoughtcrime.securesms.tap.provider.cos.CosProviderRegistrar",
                configDescriptorClass = "org.thoughtcrime.securesms.tap.provider.cos.CosProviderConfigDescriptor",
                supportsAuth = true,
                supportsGroup = true,
                supportsConfigTest = true
            )
            // 未来可以在这里添加其他已知的providers
        )
    }
    
    /**
     * 从ProviderInfo注册Provider
     */
    private fun registerProviderFromInfo(providerInfo: ProviderInfo): Boolean {
        return try {
            Log.d(TAG, "尝试注册provider: ${providerInfo.type}")
            
            // 通过反射创建注册器实例
            val registrarClass = Class.forName(providerInfo.registrarClass)
            val registrar = registrarClass.getDeclaredConstructor().newInstance() as ProviderRegistrar
            
            // 验证provider类型匹配
            if (registrar.providerType.lowercase() != providerInfo.type.lowercase()) {
                Log.w(TAG, "Provider类型不匹配: 期望=${providerInfo.type}, 实际=${registrar.providerType}")
                return false
            }
            
            // 注册provider
            registrarsLock.write {
                val key = providerInfo.type.lowercase()
                registrars[key] = registrar
                
                // 创建元数据
                val metadata = ProviderMetadata(
                    providerType = providerInfo.type,
                    displayName = providerInfo.name,
                    description = providerInfo.description,
                    version = providerInfo.version,
                    author = "Signal Tap Team",
                    supportedFeatures = buildSet {
                        if (providerInfo.supportsAuth) add(ProviderFeature.AUTH_MANAGEMENT)
                        if (providerInfo.supportsGroup) add(ProviderFeature.GROUP_TRANSPORT)
                        if (providerInfo.supportsConfigTest) add(ProviderFeature.CONFIG_TEST)
                    },
                    dependencies = registrar.getDependencies()
                )
                providerMetadata[key] = metadata
            }
            
            Log.i(TAG, "成功注册provider: ${providerInfo.type}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "注册provider失败: ${providerInfo.type} - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
}

/**
 * Provider信息数据类
 */
private data class ProviderInfo(
    val type: String,
    val name: String,
    val description: String,
    val version: String,
    val registrarClass: String,
    val configDescriptorClass: String,
    val supportsAuth: Boolean,
    val supportsGroup: Boolean,
    val supportsConfigTest: Boolean
) 