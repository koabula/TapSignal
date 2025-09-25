package org.thoughtcrime.securesms.tap

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.factory.DefaultTransportProviderFactory
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * 传输Provider管理器
 * 
 * 负责Provider的注册、创建、生命周期管理和状态监控
 * 从TransportManager中分离出来，专注于Provider管理职责
 */
class TransportProviderManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportProviderManager::class.java)
        
        @Volatile
        private var INSTANCE: TransportProviderManager? = null
        
        fun getInstance(context: Context): TransportProviderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportProviderManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
        
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE?.let { instance ->
                    runBlocking {
                        instance.cleanup()
                    }
                }
                INSTANCE = null
            }
        }
    }
    
    // Provider实例管理
    private val providers = ConcurrentHashMap<String, TransportProvider>()
    private val providerFactories = ConcurrentHashMap<String, TransportProviderFactory>()
    private val providerLock = ReentrantReadWriteLock()
    
    // Provider状态跟踪
    private val providerStatus = ConcurrentHashMap<String, ProviderStatusInfo>()
    
    // 协程作用域
    private val managerScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("ProviderManager")
    )
    
    private var isInitialized = false
    
    /**
     * 初始化Provider管理器
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized) {
                    Log.d(TAG, "Provider管理器已初始化")
                    return@withContext true
                }
                
                // 注册默认工厂
                registerProviderFactory("default", DefaultTransportProviderFactory(context))
                
                // 加载已配置的Providers
                loadConfiguredProviders()
                
                isInitialized = true
                Log.i(TAG, "Provider管理器初始化完成，已注册Provider: ${providers.size}")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Provider管理器初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
                false
            }
        }
    }
    
    /**
     * 注册Provider工厂
     */
    fun registerProviderFactory(name: String, factory: TransportProviderFactory) {
        providerLock.write {
            providerFactories[name] = factory
            Log.d(TAG, "注册Provider工厂: $name，支持类型: ${factory.supportedProviderTypes}")
        }
    }
    
    /**
     * 注册Provider实例
     */
    fun registerProvider(provider: TransportProvider) {
        val normalizedProviderType = provider.providerType.lowercase()
        providerLock.write {
            providers[normalizedProviderType] = provider
            providerStatus[normalizedProviderType] = ProviderStatusInfo(
                providerType = normalizedProviderType,
                isActive = true,
                lastCheckTime = System.currentTimeMillis(),
                errorCount = 0
            )
            Log.i(TAG, "注册Provider: ${provider.providerType}")
        }
    }
    
    /**
     * 获取Provider实例
     */
    fun getProvider(providerType: String): TransportProvider? {
        val normalizedProviderType = providerType.lowercase()
        return providerLock.read {
            providers[normalizedProviderType]
        }
    }
    
    /**
     * 获取所有活跃的Provider
     */
    fun getActiveProviders(): List<TransportProvider> {
        return providerLock.read {
            providers.values.filter { provider ->
                val status = providerStatus[provider.providerType]
                status?.isActive == true
            }
        }
    }
    
    /**
     * 获取可用的Provider类型
     */
    fun getAvailableProviderTypes(): Set<String> {
        return providerLock.read {
            providerFactories.values.flatMap { it.supportedProviderTypes }.toSet()
        }
    }
    
    /**
     * 检查指定Provider是否已注册
     */
    fun isProviderRegistered(providerType: String): Boolean {
        return providerLock.read {
            providers.containsKey(providerType)
        }
    }
    
    /**
     * 检查指定Provider是否可用（已注册且活跃）
     */
    fun isProviderAvailable(providerType: String): Boolean {
        return providerLock.read {
            val provider = providers[providerType]
            val status = providerStatus[providerType]
            provider != null && status?.isActive == true
        }
    }
    
    /**
     * 创建Provider实例
     */
    suspend fun createProvider(providerType: String, config: Map<String, Any>): TransportProvider? {
        val normalizedProviderType = providerType.lowercase()
        return withContext(Dispatchers.IO) {
            try {
                val factory = findSuitableFactory(normalizedProviderType)
                if (factory == null) {
                    Log.w(TAG, "未找到支持Provider类型的工厂: $normalizedProviderType")
                    return@withContext null
                }
                
                val provider = factory.createProvider(normalizedProviderType, config)
                if (provider != null) {
                    providerLock.write {
                        providers[normalizedProviderType] = provider
                        providerStatus[normalizedProviderType] = ProviderStatusInfo(
                            providerType = normalizedProviderType,
                            isActive = true,
                            lastCheckTime = System.currentTimeMillis()
                        )
                    }
                    Log.i(TAG, "成功创建Provider: $normalizedProviderType")
                }
                
                provider
            } catch (e: Exception) {
                Log.e(TAG, "创建Provider失败: $normalizedProviderType - ${LogSanitizer.sanitizeThrowable(e)}")
                null
            }
        }
    }
    
    /**
     * 从配置创建Provider实例
     */
    fun createProviderFromConfig(providerType: String, config: Map<String, Any>): TransportProvider? {
        return providerLock.read {
            val factory = providerFactories["default"] // 使用默认工厂
            factory?.createProvider(providerType, config)
        }
    }
    
    /**
     * 移除Provider实例
     */
    suspend fun removeProvider(providerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val provider = providerLock.write {
                    val removed = providers.remove(providerType)
                    providerStatus.remove(providerType)
                    removed
                }
                
                if (provider != null) {
                    // 清理Provider资源
                    try {
                        provider.cleanup()
                    } catch (e: Exception) {
                        Log.w(TAG, "清理Provider资源失败: $providerType", e)
                    }
                    
                    Log.i(TAG, "移除Provider: $providerType")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "移除Provider失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                false
            }
        }
    }
    
    /**
     * 检查Provider健康状态
     */
    fun checkProviderHealth(providerType: String): Boolean {
        return providerLock.read {
            val provider = providers[providerType]
            val status = providerStatus[providerType]
            
            if (provider == null) {
                Log.w(TAG, "Provider不存在: $providerType")
                return@read false
            }
            
            if (status?.isHealthy != true) {
                Log.w(TAG, "Provider健康状态异常: $providerType")
                return@read false
            }
            
            true
        }
    }
    
    /**
     * 获取Provider统计信息
     */
    fun getProviderStatistics(): ProviderStatistics {
        return providerLock.read {
            val totalProviders = providers.size
            val activeProviders = providerStatus.values.count { it.isActive }
            val healthyProviders = providerStatus.values.count { it.isActive && it.errorCount == 0 }
            
            ProviderStatistics(
                totalProviders = totalProviders,
                activeProviders = activeProviders,
                healthyProviders = healthyProviders,
                providerTypes = providers.keys.toSet(),
                statusMap = providerStatus.toMap()
            )
        }
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始清理Provider管理器资源")
                
                // 清理所有Providers
                providerLock.write {
                    providers.values.forEach { provider ->
                        try {
                            runBlocking { provider.cleanup() }
                        } catch (e: Exception) {
                            Log.w(TAG, "清理Provider失败: ${provider.providerType}", e)
                        }
                    }
                    providers.clear()
                    providerFactories.clear()
                    providerStatus.clear()
                }
                
                // 取消协程作用域
                managerScope.cancel()
                isInitialized = false
                
                Log.i(TAG, "Provider管理器资源清理完成")
            } catch (e: Exception) {
                Log.e(TAG, "清理Provider管理器资源失败", e)
            }
        }
    }
    
    /**
     * 加载已配置的Providers
     */
    private suspend fun loadConfiguredProviders() {
        try {
            val configManager = TransportProviderConfigManager.getInstance(context)
            val enabledProviders = configManager.getEnabledProviders()
            
            Log.d(TAG, "开始加载已配置的Provider，启用的Provider: $enabledProviders")
            
            var successCount = 0
            for (providerType in enabledProviders) {
                val config = configManager.getProviderConfig(providerType)
                if (config != null) {
                    Log.d(TAG, "为Provider加载配置: $providerType")
                    val provider = createProvider(providerType, config)
                    if (provider != null) {
                        successCount++
                        Log.i(TAG, "Provider加载成功: $providerType")
                    } else {
                        Log.w(TAG, "Provider创建失败: $providerType")
                    }
                } else {
                    Log.w(TAG, "Provider配置不存在: $providerType")
                }
            }
            
            // 输出最终加载结果
            val loadedProviders = providers.keys.toList()
            Log.i(TAG, "Provider加载完成，成功加载: $successCount/${enabledProviders.size}，已加载Provider: $loadedProviders")
            
        } catch (e: Exception) {
            Log.e(TAG, "加载已配置Provider失败: ${LogSanitizer.sanitizeThrowable(e)}")
        }
    }
    
    /**
     * 查找支持指定Provider类型的工厂
     */
    private fun findSuitableFactory(providerType: String): TransportProviderFactory? {
        return providerLock.read {
            // 首先尝试查找现有工厂
            var factory = providerFactories.values.find { factory ->
                factory.supportedProviderTypes.contains(providerType)
            }
            
            // 如果找不到工厂，尝试重新注册默认工厂
            if (factory == null) {
                Log.w(TAG, "未找到支持Provider类型的工厂: $providerType，尝试重新初始化默认工厂")
                
                // 在写锁中重新注册默认工厂
                providerLock.write {
                    val defaultFactory = DefaultTransportProviderFactory(context)
                    providerFactories["default"] = defaultFactory
                    
                    Log.d(TAG, "重新注册默认工厂，支持的Provider类型: ${defaultFactory.supportedProviderTypes}")
                    
                    // 再次查找
                    factory = providerFactories.values.find { f ->
                        f.supportedProviderTypes.contains(providerType)
                    }
                    
                    if (factory != null) {
                        Log.i(TAG, "重新注册默认工厂后找到支持的工厂: $providerType")
                    } else {
                        Log.e(TAG, "重新注册默认工厂后仍未找到支持的工厂: $providerType")
                        Log.d(TAG, "当前所有工厂支持的类型: ${providerFactories.values.flatMap { it.supportedProviderTypes }}")
                    }
                }
            }
            
            factory
        }
    }
}

/**
 * Provider状态信息
 */
data class ProviderStatusInfo(
    val providerType: String,
    val isActive: Boolean,
    val lastCheckTime: Long,
    val errorCount: Int = 0
) {
    val isHealthy: Boolean
        get() = isActive && errorCount < 5 && (System.currentTimeMillis() - lastCheckTime) < 300000L // 5分钟内检查过且错误次数少于5次
}

/**
 * Provider统计信息
 */
data class ProviderStatistics(
    val totalProviders: Int,
    val activeProviders: Int,
    val healthyProviders: Int,
    val providerTypes: Set<String>,
    val statusMap: Map<String, ProviderStatusInfo>
) 