package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.TransportManager
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
 * 4. 从coscomm模块迁移配置和数据（如需要）
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
                
                // 4. 执行数据迁移（如需要）
                if (shouldPerformLegacyMigration()) {
                    migrateFromLegacyModules()
                }
                
                // 5. 启动轮询服务
                startPollingService()
                
                // 6. 标记初始化完成
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
            val factory = DefaultTransportProviderFactory(context)
            
            // 注册工厂
            transportManager.registerProviderFactory(factory)
            
            // 注册所有可用的Provider
            val availableProviders = factory.supportedProviderTypes
            for (providerType in availableProviders) {
                try {
                    // 获取默认配置
                    val defaultConfig = factory.getDefaultConfig(providerType)
                    if (defaultConfig.isNotEmpty()) {
                        val provider = factory.createProvider(providerType, defaultConfig)
                        if (provider != null) {
                            transportManager.registerProvider(provider)
                            Log.d(TAG, "Provider注册成功: $providerType")
                        } else {
                            Log.w(TAG, "Provider创建失败: $providerType")
                        }
                    } else {
                        Log.d(TAG, "Provider无默认配置，跳过: $providerType")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Provider注册失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Provider注册失败: ${LogSanitizer.sanitizeThrowable(e)}")
            throw e
        }
    }
    
    /**
     * 检查是否需要执行遗留模块迁移
     */
    private fun shouldPerformLegacyMigration(): Boolean {
        val migrationCompleted = tapValues.isLegacyMigrationCompleted()
        
        // 检查是否存在coscomm配置需要迁移
        val hasCosCommConfig = try {
            // 检查是否存在旧的coscomm配置文件
            context.getSharedPreferences("cos_settings", Context.MODE_PRIVATE)
                .contains("cos_enabled")
        } catch (e: Exception) {
            false
        }
        
        return !migrationCompleted && hasCosCommConfig
    }
    
    /**
     * 从coscomm模块迁移配置和数据
     */
    private suspend fun migrateFromLegacyModules() {
        Log.i(TAG, "开始从coscomm模块迁移数据...")
        
        try {
            // 1. 迁移COS配置
            migrateCosConfiguration()
            
            // 2. 迁移SubAccount Pool到Transport Token Pool
            migrateSubAccountPool()
            
            // 3. 迁移轮询状态
            migratePollingStates()
            
            // 4. 标记迁移完成
            markLegacyMigrationComplete()
            
            Log.i(TAG, "coscomm模块数据迁移完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "数据迁移失败: ${LogSanitizer.sanitizeThrowable(e)}")
            // 迁移失败不应该阻止初始化
        }
    }
    
    /**
     * 迁移COS配置
     */
    private fun migrateCosConfiguration() {
        try {
            // 检查是否有COS配置需要迁移
            val cosPrefs = context.getSharedPreferences("cos_settings", Context.MODE_PRIVATE)
            val cosEnabled = cosPrefs.getBoolean("cos_enabled", false)
            
            if (!cosEnabled) {
                Log.d(TAG, "无COS配置需要迁移")
                return
            }
            
            // 获取COS配置
            val cosConfig = mapOf(
                "provider" to cosPrefs.getString("cos_provider", "TENCENT"),
                "secretId" to cosPrefs.getString("cos_secret_id", ""),
                "secretKey" to cosPrefs.getString("cos_secret_key", ""),
                "region" to cosPrefs.getString("cos_region", ""),
                "bucketName" to cosPrefs.getString("cos_bucket_name", "")
            ).filter { it.value != null && it.value.toString().isNotEmpty() }
            
            if (cosConfig.isNotEmpty()) {
                // 转换为TaP Provider配置
                val configManager = TransportProviderConfigManager.getInstance(context)
                val tapConfig = convertCosConfigToTapConfig(cosConfig)
                
                if (tapConfig != null) {
                    configManager.saveProviderConfig("cos", tapConfig)
                    Log.d(TAG, "COS配置迁移成功")
                } else {
                    Log.w(TAG, "COS配置转换失败")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "迁移COS配置失败: ${LogSanitizer.sanitizeThrowable(e)}")
        }
    }
    
    /**
     * 转换COS配置为TaP配置
     */
    private fun convertCosConfigToTapConfig(cosConfig: Map<String, Any?>): Map<String, Any>? {
        return try {
            // 这里需要根据实际的COS配置结构进行转换
            // 示例转换逻辑
            mapOf(
                "provider_type" to "cos",
                "enabled" to true,
                "provider" to (cosConfig["provider"] ?: "TENCENT"),
                "secretId" to (cosConfig["secretId"] ?: ""),
                "secretKey" to (cosConfig["secretKey"] ?: ""),
                "region" to (cosConfig["region"] ?: ""),
                "bucketName" to (cosConfig["bucketName"] ?: ""),
                "migrated_from_legacy" to true,
                "migration_timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "转换COS配置失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 从SubAccountPool迁移数据到TransportTokenPool
     */
    private fun migrateSubAccountPool() {
        Log.i(TAG, "开始从SubAccountPool迁移数据到TransportTokenPool")
        
        try {
            // 获取原始的SubAccountPoolManager实例
            val subAccountPoolManager = org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager.getInstance(context)
            val transportTokenPool = TransportTokenPool.getInstance(context)
            
            var migratedReceived = 0
            var migratedShared = 0
            
            // 1. 迁移接收到的子账户（对方分享给我的）
            try {
                val receivedSubAccounts = subAccountPoolManager.getAllValidReceivedSubAccounts()
                Log.d(TAG, "发现接收子账户数量: ${receivedSubAccounts.size}")
                
                receivedSubAccounts.forEach { subAccountEntry ->
                    try {
                        val recipientId = subAccountEntry.recipientId
                        val accessInfo = subAccountEntry.accessInfo
                        
                        // 转换为CosTransportToken
                        val transportToken = convertCosAccessInfoToTransportToken(
                            recipientId = recipientId,
                            accessInfo = accessInfo,
                            tokenType = "received"
                        )
                        
                        // 添加到TransportTokenPool
                        runBlocking {
                            val success = transportTokenPool.addReceivedToken(recipientId, transportToken)
                            if (success) {
                                migratedReceived++
                                Log.d(TAG, "迁移接收Token成功: recipientId=$recipientId")
                            } else {
                                Log.w(TAG, "迁移接收Token失败: recipientId=$recipientId")
                            }
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "迁移单个接收子账户失败: ${subAccountEntry.recipientId}", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "迁移接收子账户失败", e)
            }
            
            // 2. 迁移分享的子账户（我分享给对方的）
            try {
                // 注意：原始设计中可能没有直接获取所有分享子账户的方法
                // 这里需要通过其他方式获取，或者从持久化存储中读取
                val statistics = subAccountPoolManager.getStatistics()
                Log.d(TAG, "SubAccount统计信息: $statistics")
                
                // 由于SubAccountPoolManager可能没有直接获取所有分享子账户的方法
                // 我们可以通过遍历已知的recipientId来获取分享的子账户
                // 这里暂时跳过，因为原始实现可能不完整
                Log.w(TAG, "分享子账户迁移暂时跳过，原始API可能不支持批量获取")
                
            } catch (e: Exception) {
                Log.e(TAG, "迁移分享子账户失败", e)
            }
            
            Log.i(TAG, "SubAccountPool数据迁移完成: 接收=$migratedReceived, 分享=$migratedShared")
            
            // 3. 标记迁移完成（可选：清理原始数据）
            if (migratedReceived > 0 || migratedShared > 0) {
                // 可以选择清理原始数据，但为了安全起见，暂时保留
                Log.i(TAG, "迁移成功，原始SubAccountPool数据保留以备回滚")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "SubAccountPool数据迁移失败", e)
        }
    }
    
    /**
     * 将CosAccessInfo转换为CosTransportToken
     */
    private fun convertCosAccessInfoToTransportToken(
        recipientId: String,
        accessInfo: org.thoughtcrime.securesms.coscomm.data.CosAccessInfo,
        tokenType: String
    ): CosTransportToken {
        
        // 生成tokenId
        val timestamp = System.currentTimeMillis()
        val tokenId = "migrated-cos-$tokenType-$recipientId-$timestamp"
        
        // 映射权限
        val permissions = setOf(TransportPermission.READ) // 原始设计中通常是只读权限
        
        return CosTransportToken(
            tokenId = tokenId,
            recipientId = recipientId,
            permissions = permissions,
            expirationTime = accessInfo.expireTime,
            accessKeyId = accessInfo.accessKeyId,
            secretAccessKey = accessInfo.secretAccessKey,
            sessionToken = accessInfo.sessionToken,
            region = accessInfo.region,
            bucketName = accessInfo.bucketName
        )
    }
    
    /**
     * 迁移轮询状态
     */
    private fun migratePollingStates() {
        try {
            // 迁移coscomm的轮询状态到TaP轮询状态
            Log.d(TAG, "轮询状态迁移 - 占位符实现")
            
        } catch (e: Exception) {
            Log.e(TAG, "迁移轮询状态失败: ${LogSanitizer.sanitizeThrowable(e)}")
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
            // 通过反射访问私有字段来获取接收者列表
            val receivedTokensField = tokenPool.javaClass.getDeclaredField("receivedTokens")
            receivedTokensField.isAccessible = true
            val receivedTokens = receivedTokensField.get(tokenPool) as? java.util.concurrent.ConcurrentHashMap<String, *>
            
            val sharedTokensField = tokenPool.javaClass.getDeclaredField("sharedTokens")
            sharedTokensField.isAccessible = true
            val sharedTokens = sharedTokensField.get(tokenPool) as? java.util.concurrent.ConcurrentHashMap<String, *>
            
            val recipients = mutableSetOf<String>()
            receivedTokens?.keys?.let { recipients.addAll(it) }
            sharedTokens?.keys?.let { recipients.addAll(it) }
            
            recipients
        } catch (e: Exception) {
            Log.w(TAG, "获取活跃接收者失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptySet()
        }
    }
    
    // markInitializationComplete方法已移到TapValues中
    
    /**
     * 标记遗留模块迁移完成
     */
    private fun markLegacyMigrationComplete() {
        tapValues.setLegacyMigrationCompleted(true)
    }
    
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