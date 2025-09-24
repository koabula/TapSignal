package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportProviderManager
import org.thoughtcrime.securesms.tap.TransportProviderConfigManager
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.polling.TapPollingService
import org.thoughtcrime.securesms.tap.factory.DefaultTransportProviderFactory
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import kotlinx.coroutines.*

/**
 * TaP模块初始化器
 * 
 * 负责在系统启动时初始化TaP模块的各个组件，包括：
 * 1. 初始化核心管理器
 * 2. 注册Provider
 * 3. 启动轮询服务
 */
class TapModuleInitializer private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapModuleInitializer::class.java)
        
        @Volatile
        private var INSTANCE: TapModuleInitializer? = null
        
        @JvmStatic
        fun getInstance(context: Context): TapModuleInitializer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapModuleInitializer(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // 已移除SharedPreferences常量，改为使用SignalStore.tap
    }
    
    private val initScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private val tapValues by lazy { SignalStore.tap }
    
    /**
     * 初始化TaP模块
     * 
     * @param forceReinit 是否强制重新初始化
     */
    fun initialize(forceReinit: Boolean = false) {
        if (isInitialized && !forceReinit) {
            Log.d(TAG, "TaP模块已初始化，跳过")
            return
        }
        
        Log.i(TAG, "开始初始化TaP模块...")
        
        initScope.launch {
            try {
                // 1. 检查是否需要执行初始化
                if (!tapValues.shouldPerformInitialization() && !forceReinit) {
                    Log.d(TAG, "TaP模块已完成初始化")
                    isInitialized = true
                    return@launch
                }
                
                // 2. 初始化核心组件
                initializeCoreComponents()
                
                // 3. 注册传输Provider
                registerTransportProviders()
                
                // 4. 启动轮询服务（移除数据迁移步骤）
                startPollingService()
                
                // 5. 标记初始化完成
                tapValues.markInitializationComplete()
                
                isInitialized = true
                Log.i(TAG, "TaP模块初始化完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "TaP模块初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
                // 初始化失败不应该影响应用启动
            }
        }
    }
    
    // shouldPerformInitialization方法已移到TapValues中
    
    /**
     * 初始化核心组件
     */
    private suspend fun initializeCoreComponents() {
        Log.d(TAG, "初始化核心组件...")
        
        try {
            // 1. 初始化配置管理器
            val configManager = TransportProviderConfigManager.getInstance(context)
            Log.d(TAG, "配置管理器初始化完成")
            
            // 2. 初始化Token池
            val tokenPool = TransportTokenPool.getInstance(context)
            Log.d(TAG, "Token池初始化完成")
            
            // 3. 初始化传输管理器
            val transportManager = TransportManager.getInstance(context)
            Log.d(TAG, "传输管理器初始化完成")
            
            // 4. 初始化通道管理器
            val channelManager = TransportChannelManager.getInstance(context)
            Log.d(TAG, "通道管理器初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "核心组件初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
            throw e
        }
    }
    
    /**
     * 注册传输Provider
     */
    private suspend fun registerTransportProviders() {
        Log.d(TAG, "注册传输Provider...")
        
        try {
            val transportManager = TransportManager.getInstance(context)
            val providerManager = TransportProviderManager.getInstance(context)
            val factory = DefaultTransportProviderFactory(context)
            
            // 注册工厂
            providerManager.registerProviderFactory("default", factory)
            
            // 注册所有可用的Provider类型（不创建实际实例，因为需要用户配置）
            val availableProviders = factory.supportedProviderTypes
            for (providerType in availableProviders) {
                try {
                    // 获取Provider配置描述器以验证Provider可用性
                    val configDescriptor = factory.getProviderConfigDescriptor(providerType)
                    if (configDescriptor != null) {
                        Log.d(TAG, "Provider类型可用: $providerType - ${configDescriptor.displayName}")
                        // 注意：实际的Provider实例将在用户配置后通过TransportProviderManager创建
                    } else {
                        Log.w(TAG, "Provider类型无配置描述器: $providerType")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "验证Provider类型失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Provider注册失败: ${LogSanitizer.sanitizeThrowable(e)}")
            throw e
        }
    }
    
    /**
     * 启动轮询服务
     */
    private suspend fun startPollingService() {
        Log.d(TAG, "启动轮询服务...")
        
        try {
            val pollingService = TapPollingService.getInstance(context)
            
            // 检查是否有需要轮询的联系人
            val tokenPool = TransportTokenPool.getInstance(context)
            val activeRecipients = getActiveRecipientsFromTokenPool(tokenPool)
            
            if (activeRecipients.isNotEmpty()) {
                pollingService.startPolling()
                Log.i(TAG, "轮询服务启动成功，活跃联系人数量: ${activeRecipients.size}")
            } else {
                Log.d(TAG, "无活跃联系人，轮询服务未启动")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "启动轮询服务失败: ${LogSanitizer.sanitizeThrowable(e)}")
            throw e
        }
    }
    
    /**
     * 从TokenPool获取活跃的接收者列表
     */
    private fun getActiveRecipientsFromTokenPool(tokenPool: TransportTokenPool): Set<String> {
        return try {
            Log.d(TAG, "获取活跃接收者列表")
            
            // 使用公开的方法获取有效的Token
            val activeRecipients = mutableSetOf<String>()
            
            // 获取所有有效的接收Token
            val validReceivedTokens = tokenPool.getAllValidReceivedTokens()
            validReceivedTokens.forEach { (recipientId, _) ->
                activeRecipients.add(recipientId)
            }
            
            // 获取所有有效的共享Token
            val validSharedTokens = tokenPool.getAllValidSharedTokens()
            validSharedTokens.forEach { (recipientId, _) ->
                activeRecipients.add(recipientId)
            }
            
            Log.d(TAG, "找到活跃接收者数量: ${activeRecipients.size}")
            activeRecipients
            
        } catch (e: Exception) {
            Log.w(TAG, "获取活跃接收者失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptySet()
        }
    }
    
    // markInitializationComplete方法已移到TapValues中
    
    /**
     * 获取初始化状态
     */
    fun getInitializationStatus(): InitializationStatus {
        return InitializationStatus(
            isInitialized = tapValues.isTapInitialized(),
            migrationCompleted = tapValues.isLegacyMigrationCompleted(),
            initVersion = tapValues.getInitVersion(),
            initTimestamp = tapValues.getInitTimestamp(),
            currentVersion = tapValues.getCurrentInitVersion()
        )
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        initScope.cancel()
        isInitialized = false
    }
}

/**
 * 初始化状态
 */
data class InitializationStatus(
    val isInitialized: Boolean,
    val migrationCompleted: Boolean,
    val initVersion: Int,
    val initTimestamp: Long,
    val currentVersion: Int
) {
    val needsUpgrade: Boolean
        get() = initVersion < currentVersion
        
    val isFullyInitialized: Boolean
        get() = isInitialized && initVersion >= currentVersion
} 