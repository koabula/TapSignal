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
    
    // 重新初始化计数器，避免无限循环
    @Volatile
    private var reinitializationAttempts = 0
    private val maxReinitializationAttempts = 3
    
    // 轮询启动重试计数器
    @Volatile
    private var pollingRetryAttempts = 0
    private val maxPollingRetryAttempts = 3

    /**
     * 初始化TaP模块（异步版本）
     * 
     * @param forceReinit 是否强制重新初始化
     */
    fun initialize(forceReinit: Boolean = false) {
        Log.i(TAG, "开始初始化TaP模块（异步）...")
        
        initScope.launch {
            try {
                // 始终执行核心组件初始化以恢复数据
                // 与同步版本保持一致的行为
                initializeCoreComponents()
                
                // 启动轮询服务
                startPollingService()
                
                // 启动推送服务（Phase 6新增）
                startNotificationService()
                
                // 标记初始化完成
                try {
                    tapValues.markInitializationComplete()
                } catch (e: Exception) {
                    Log.w(TAG, "标记初始化完成失败（SignalStore可能未就绪）", e)
                }
                
                isInitialized = true
                Log.i(TAG, "TaP模块初始化完成（异步）")
                
            } catch (e: Exception) {
                Log.e(TAG, "TaP模块初始化失败（异步）: ${LogSanitizer.sanitizeThrowable(e)}")
                // 初始化失败不应该影响应用启动
            }
        }
    }
    
    /**
     * 同步初始化方法（阻塞直到初始化完成）
     */
    fun initializeSync(forceReinit: Boolean = false) {
        // 防御性措施：重置标志确保数据恢复执行
        if (!forceReinit && isInitialized) {
            Log.d(TAG, "TaP模块已初始化，重置标志以确保数据恢复")
        }
        isInitialized = false
        
        Log.i(TAG, "开始同步初始化TaP模块...")
        
        runBlocking {
            try {
                // 始终执行核心组件初始化以恢复数据
                initializeCoreComponents()
                
                // 验证初始化结果
                val transportManager = TransportManager.getInstance(context)
                val initSuccess = transportManager.isInitialized()
                
                if (!initSuccess) {
                    Log.e(TAG, "TaP模块同步初始化失败：TransportManager未能正确初始化")
                    throw RuntimeException("TransportManager初始化失败")
                }
                
                val availableProviders = transportManager.getAvailableProviders()
                Log.i(TAG, "TaP模块同步初始化成功，可用Provider数量: ${availableProviders.size}")
                
                // 启动轮询服务
                startPollingService()
                
                // 启动推送服务（Phase 6新增）
                startNotificationService()
                
                // 标记初始化完成
                try {
                    val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                    tapValues.markInitializationComplete()
                } catch (e: Exception) {
                    Log.w(TAG, "标记初始化完成失败（SignalStore可能未就绪）", e)
                }
                
                isInitialized = true
                reinitializationAttempts = 0
                Log.i(TAG, "TaP模块初始化完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "TaP模块同步初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
                isInitialized = false
                throw e
            }
        }
    }
    
    // shouldPerformInitialization方法已移到TapValues中
    
    /**
     * 初始化TAP独立数据库
     */
    private suspend fun initializeTapDatabase() {
        try {
            val databaseManager = org.thoughtcrime.securesms.tap.database.TapDatabaseManager.getInstance(context)
            
            // 优先确保数据库连接能正常建立（触发onConfigure执行）
            try {
                val writeDb = databaseManager.getWritableDatabase()
                databaseManager.releaseConnection()
                Log.v(TAG, "TAP数据库写连接验证成功")
            } catch (e: Exception) {
                Log.w(TAG, "TAP数据库写连接建立失败", e)
                // 继续尝试，可能是配置问题但数据库本身可用
            }
            
            // 执行健康检查（如果首次失败则重试一次）
            var healthCheck = databaseManager.performHealthCheck()
            if (!healthCheck) {
                Log.v(TAG, "首次健康检查失败，重试一次")
                kotlinx.coroutines.delay(200) // 短暂等待
                healthCheck = databaseManager.performHealthCheck()
            }
            
            if (healthCheck) {
                Log.d(TAG, "TAP独立数据库初始化成功")
            } else {
                Log.w(TAG, "TAP独立数据库健康检查失败")
            }
            
            // 初始化独立数据库表
            val independentTable = org.thoughtcrime.securesms.tap.database.TapTransportChannelTable.getInstance(context)
            Log.d(TAG, "TAP独立数据库表初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "TAP独立数据库初始化异常", e)
            // 数据库初始化失败不应阻止模块启动，后续会回退到主数据库
        }
    }
    
    /**
     * 初始化核心组件
     */
    private suspend fun initializeCoreComponents() {
        Log.i(TAG, "开始初始化核心组件...")
        
        try {
            // 0. 初始化独立数据库和监控器（优先初始化以监控后续操作）
            initializeTapDatabase()
            TapDatabaseContext.initializeMonitor(context)
            Log.i(TAG, "数据库和监控器初始化完成")
            
            // 1. 初始化配置管理器
            val configManager = TransportProviderConfigManager.getInstance(context)
            Log.i(TAG, "配置管理器就绪")
            
            // 2. 初始化Token池（数据恢复）
            val tokenPool = TransportTokenPool.getInstance(context)
            val tokenPoolInitialized = tokenPool.initialize(TransportTokenConfig())
            if (!tokenPoolInitialized) {
                Log.e(TAG, "Token池初始化失败")
                throw RuntimeException("TokenPool初始化失败")
            }
            Log.i(TAG, "Token池初始化完成")
            
            // 3. 注册传输Provider（仅在首次或Provider缺失时）
            registerTransportProviders()
            
            // 4. 初始化传输管理器
            val transportManager = TransportManager.getInstance(context)
            val transportInitialized = transportManager.initialize()
            if (!transportInitialized) {
                Log.e(TAG, "传输管理器初始化失败")
                throw RuntimeException("TransportManager初始化失败")
            }
            
            // 验证Provider是否正确注册
            val availableProviders = transportManager.getAvailableProviders()
            Log.i(TAG, "传输管理器初始化完成，可用Provider数量: ${availableProviders.size}")
            availableProviders.forEach { provider ->
                Log.i(TAG, "已注册Provider: ${provider.providerType} - ${provider.displayName}")
            }
            
            // 5. 初始化通道管理器（数据恢复）
            val channelManager = TransportChannelManager.getInstance(context)
            val channelManagerInitialized = channelManager.initialize(TransportChannelConfig())
            if (!channelManagerInitialized) {
                Log.e(TAG, "通道管理器初始化失败")
                throw RuntimeException("ChannelManager初始化失败")
            }
            Log.i(TAG, "通道管理器初始化完成")
            
            // 6. 检查恢复的数据状态
            val totalTokens = tokenPool.getTotalReceivedTokensCount() + tokenPool.getTotalSharedTokensCount()
            val activeChannels = channelManager.getAllActiveChannels()
            
            Log.i(TAG, "数据恢复状态检查:")
            Log.i(TAG, "  - Token总数: $totalTokens (接收=${tokenPool.getTotalReceivedTokensCount()}, 共享=${tokenPool.getTotalSharedTokensCount()})")
            Log.i(TAG, "  - 活跃通道数: ${activeChannels.size}")
            
            if (totalTokens == 0 && activeChannels.isEmpty()) {
                Log.w(TAG, "未恢复任何v2 mode数据，可能是首次启动或数据已清空")
            } else if (totalTokens == 0) {
                Log.w(TAG, "Token为空但有活跃通道，可能SignalStore延迟初始化，将触发Token重试")
            } else {
                Log.i(TAG, "成功恢复v2 mode数据")
            }
            
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
            val configManager = TransportProviderConfigManager.getInstance(context)
            
            // 确保ProviderManager已初始化
            val providerManagerInitialized = providerManager.initialize()
            if (!providerManagerInitialized) {
                Log.e(TAG, "Provider管理器初始化失败")
                throw RuntimeException("ProviderManager初始化失败")
            }
            Log.d(TAG, "Provider管理器初始化完成")
            
            val factory = DefaultTransportProviderFactory(context)
            
            // 注册工厂
            providerManager.registerProviderFactory("default", factory)
            
            // 主动创建已配置的Provider实例
            val configuredProviders = configManager.getConfiguredProviders()
            Log.d(TAG, "发现已配置Provider: ${configuredProviders.joinToString(", ")}")
            
            for (providerType in configuredProviders) {
                try {
                    val config = configManager.getProviderConfig(providerType)
                    if (config != null) {
                        Log.d(TAG, "创建Provider实例: $providerType")
                        val provider = factory.createProvider(providerType, config)
                        if (provider != null) {
                            providerManager.registerProvider(provider)
                            Log.i(TAG, "Provider创建并注册成功: ${provider.providerType} - ${provider.displayName}")
                        } else {
                            Log.w(TAG, "Provider创建失败: $providerType")
                        }
                    } else {
                        Log.w(TAG, "Provider配置为空: $providerType")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "创建Provider实例失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                }
            }
            
            // 验证所有支持的Provider类型（仅记录，不创建实例）
            val availableProviders = factory.supportedProviderTypes
            for (providerType in availableProviders) {
                if (!configuredProviders.contains(providerType)) {
                    try {
                        val configDescriptor = factory.getProviderConfigDescriptor(providerType)
                        if (configDescriptor != null) {
                            Log.d(TAG, "Provider类型可用但未配置: $providerType - ${configDescriptor.displayName}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "验证Provider类型失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Provider注册失败: ${LogSanitizer.sanitizeThrowable(e)}")
            throw e
        }
    }
    
    /**
     * 启动推送服务
     */
    private suspend fun startNotificationService() {
        Log.d(TAG, "启动推送服务...")
        
        try {
            val notificationService = org.thoughtcrime.securesms.tap.notification.lifecycle.TapNotificationService.getInstance(context)
            
            // 初始化推送服务
            val initResult = notificationService.initialize()
            
            if (initResult) {
                Log.i(TAG, "推送服务初始化成功，启动服务")
                
                // 启动推送服务
                notificationService.start { notification ->
                    handlePushNotification(notification)
                }
                
                Log.i(TAG, "推送服务启动成功")
                
                // P1修复：推送服务初始化成功后，检查并启用COS Provider的推送通知
                try {
                    val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                    val cosProvider = transportManager.getProvider("cos")
                    if (cosProvider != null && cosProvider is org.thoughtcrime.securesms.tap.provider.cos.CosTransportProvider) {
                        // 通过反射调用启用通知的方法，或者添加一个公共方法
                        // 这里我们先记录日志，实际的启用逻辑在部署时已经完成
                        Log.d(TAG, "推送服务已启动，COS Provider推送通知状态将在下次部署时更新")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "检查COS Provider推送状态失败", e)
                }
            } else {
                Log.w(TAG, "推送服务初始化失败，可能未配置推送服务")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "启动推送服务失败: ${LogSanitizer.sanitizeThrowable(e)}")
            // 推送服务启动失败不应影响应用运行
        }
    }
    
    /**
     * 处理推送通知
     */
    private fun handlePushNotification(notification: org.thoughtcrime.securesms.tap.notification.NotificationMessage) {
        try {
            Log.d(TAG, "收到推送通知: type=${notification.type}, senderId=${notification.senderId}")
            
            when (notification.type) {
                org.thoughtcrime.securesms.tap.notification.NotificationMessage.TYPE_NEW_MESSAGE -> {
                    // 推送通知会触发离线消息处理器自动下载消息
                    Log.i(TAG, "收到新消息推送通知: senderId=${notification.senderId}")
                    // 离线消息处理器会在前台模式下自动处理
                }
                org.thoughtcrime.securesms.tap.notification.NotificationMessage.TYPE_HEARTBEAT -> {
                    Log.v(TAG, "收到心跳推送")
                }
                else -> {
                    Log.w(TAG, "收到未知类型的推送通知: ${notification.type}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理推送通知失败", e)
        }
    }
    
    /**
     * 启动轮询服务 (已废弃)
     * 替代方案：启动群组状态同步器
     */
    private suspend fun startPollingService() {
        Log.i(TAG, "轮询服务已废弃，跳过启动")
        
        // 启动群组状态同步器 (原由TapPollingService启动)
        try {
            val stateSynchronizer = org.thoughtcrime.securesms.tap.group.GroupV2StateSynchronizer.getInstance(context)
            stateSynchronizer.start()
            Log.i(TAG, "群组 V2 状态同步器已启动")
        } catch (e: Exception) {
            Log.w(TAG, "启动群组状态同步器失败", e)
        }
    }
    
    /**
     * 从TokenPool获取活跃的接收者列表 (已废弃)
     */
    private fun getActiveRecipientsFromTokenPool(tokenPool: TransportTokenPool): Set<String> {
        return emptySet()
    }
    
    /**
     * 延迟重试启动轮询服务 (已废弃)
     */
    private fun schedulePollingRetry() {
        // No-op
    }
    
    /**
     * 重试启动轮询服务 (已废弃)
     */
    private suspend fun retryStartPollingService() {
        // No-op
    }
    
    // markInitializationComplete方法已移到TapValues中
    
    /**
     * 检查初始化是否完成
     */
    fun isInitializationComplete(): Boolean {
        return isInitialized
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
     * 在Token加载完成后重新检查并启动轮询服务 (已废弃)
     * 由TransportTokenPool在延迟加载Token成功后调用
     */
    suspend fun retryPollingServiceIfNeeded() {
        Log.d(TAG, "retryPollingServiceIfNeeded: 轮询服务已废弃，忽略")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        initScope.cancel()
        isInitialized = false
    }
    
    /**
     * 判断recipientId是否为群组ID
     * 群组ID特征：Base64格式，不包含'-'（UUID包含'-'）
     */
    private fun isGroupId(recipientId: String): Boolean {
        return !recipientId.contains("-") && recipientId.length > 20
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