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
                
                // 3. 启动轮询服务
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
    
    /**
     * 同步初始化方法（阻塞直到初始化完成）
     */
    fun initializeSync(forceReinit: Boolean = false) {
        // 即使isInitialized=true，也要验证核心组件是否真正可用
        if (isInitialized && !forceReinit) {
            try {
                // 验证TransportManager是否真正可用
                val transportManager = TransportManager.getInstance(context)
                
                // 首先检查TransportManager本身是否初始化
                if (!transportManager.isInitialized()) {
                    Log.w(TAG, "TaP模块状态异常：TransportManager未初始化，强制重新初始化")
                } else {
                    val availableProviders = transportManager.getAvailableProviders()
                    
                    if (availableProviders.isNotEmpty()) {
                        Log.d(TAG, "TaP模块已正确初始化，Provider数量: ${availableProviders.size}")
                        availableProviders.forEach { provider ->
                            Log.d(TAG, "验证已注册Provider: ${provider.providerType} - ${provider.displayName}")
                        }
                        // 重置重新初始化计数器
                        reinitializationAttempts = 0
                        return
                    } else {
                        // 检查是否有已配置的Provider，如果没有配置则不需要重新初始化
                        val configManager = TransportProviderConfigManager.getInstance(context)
                        val configuredProviders = configManager.getConfiguredProviders()
                        
                        if (configuredProviders.isEmpty()) {
                            Log.d(TAG, "TaP模块无已配置Provider，跳过重新初始化")
                            return
                        } else if (reinitializationAttempts >= maxReinitializationAttempts) {
                            Log.w(TAG, "TaP模块重新初始化次数已达上限(${maxReinitializationAttempts}次)，停止重试")
                            return
                        } else {
                            reinitializationAttempts++
                            Log.w(TAG, "TaP模块状态异常：Provider列表为空，强制重新初始化 (第${reinitializationAttempts}次)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TaP模块状态验证异常，强制重新初始化", e)
            }
        }
        
        Log.i(TAG, "开始同步初始化TaP模块...")
        
        runBlocking {
            try {
                // 重新初始化核心组件
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
                
                // 标记初始化完成
                val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                tapValues.markInitializationComplete()
                
                isInitialized = true
                reinitializationAttempts = 0
                Log.d(TAG, "TaP模块已完成初始化")
                
            } catch (e: Exception) {
                Log.e(TAG, "TaP模块同步初始化失败: ${LogSanitizer.sanitizeThrowable(e)}")
                isInitialized = false
                throw e
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
            val tokenPoolInitialized = tokenPool.initialize(TransportTokenConfig())
            if (!tokenPoolInitialized) {
                Log.e(TAG, "Token池初始化失败")
                throw RuntimeException("TokenPool初始化失败")
            }
            Log.d(TAG, "Token池初始化完成")
            
            // 3. 注册传输Provider（在TransportManager初始化前）
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
            Log.d(TAG, "传输管理器初始化完成，可用Provider数量: ${availableProviders.size}")
            availableProviders.forEach { provider ->
                Log.d(TAG, "已注册Provider: ${provider.providerType} - ${provider.displayName}")
            }
            
            // 5. 初始化通道管理器
            val channelManager = TransportChannelManager.getInstance(context)
            val channelManagerInitialized = channelManager.initialize(TransportChannelConfig())
            if (!channelManagerInitialized) {
                Log.e(TAG, "通道管理器初始化失败")
                throw RuntimeException("ChannelManager初始化失败")
            }
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
     * 启动轮询服务
     */
    private suspend fun startPollingService() {
        Log.d(TAG, "启动轮询服务...")
        
        try {
            val pollingService = TapPollingService.getInstance(context)
            
            // 1. 检查TokenPool中的活跃联系人
            val tokenPool = TransportTokenPool.getInstance(context)
            val activeRecipientsFromTokens = getActiveRecipientsFromTokenPool(tokenPool)
            Log.d(TAG, "从TokenPool获取的活跃联系人数量: ${activeRecipientsFromTokens.size}")
            
            // 2. 检查通道管理器中的活跃通道
            val channelManager = TransportChannelManager.getInstance(context)
            val allActiveChannels = channelManager.getAllActiveChannels()
            val activeRecipientsFromChannels = allActiveChannels.map { it.recipientId }.toSet()
            Log.d(TAG, "从通道管理器获取的活跃联系人数量: ${activeRecipientsFromChannels.size}")
            
            // 3. 合并两个来源的活跃联系人
            val allActiveRecipients = activeRecipientsFromTokens + activeRecipientsFromChannels
            Log.d(TAG, "合并后的活跃联系人总数: ${allActiveRecipients.size}")
            
            // 详细诊断信息
            if (allActiveRecipients.isEmpty()) {
                Log.w(TAG, "轮询启动诊断 - 没有活跃联系人:")
                Log.w(TAG, "  - TokenPool状态:")
                
                // 诊断TokenPool状态
                try {
                    val receivedTokensCount = tokenPool.getAllValidReceivedTokens().size
                    val sharedTokensCount = tokenPool.getAllValidSharedTokens().size
                    Log.w(TAG, "    * 有效接收Token数: $receivedTokensCount")
                    Log.w(TAG, "    * 有效共享Token数: $sharedTokensCount")
                    
                    // 如果TokenPool有数据但getActiveRecipientsFromTokenPool返回空，可能是格式问题
                    if (receivedTokensCount > 0 || sharedTokensCount > 0) {
                        Log.w(TAG, "    * TokenPool有Token但无活跃联系人，可能是recipientId格式问题")
                        
                        // 列出实际的Token
                        tokenPool.getAllValidReceivedTokens().forEach { (recipientId, token) ->
                            Log.w(TAG, "    * 接收Token: recipientId=$recipientId, tokenId=${token.tokenId}")
                        }
                        tokenPool.getAllValidSharedTokens().forEach { (recipientId, token) ->
                            Log.w(TAG, "    * 共享Token: recipientId=$recipientId, tokenId=${token.tokenId}")
                        }
                    }
                } catch (tokenDiagnosisEx: Exception) {
                    Log.e(TAG, "TokenPool诊断异常", tokenDiagnosisEx)
                }
                
                Log.w(TAG, "  - 通道管理器状态:")
                Log.w(TAG, "    * 总活跃通道数: ${allActiveChannels.size}")
                allActiveChannels.forEach { channel ->
                    Log.w(TAG, "    * 通道: ${channel.channelId}, recipientId=${channel.recipientId}, provider=${channel.providerType}, status=${channel.status}")
                }
            }
            
            if (allActiveRecipients.isNotEmpty()) {
                Log.i(TAG, "启动轮询服务，活跃联系人:")
                allActiveRecipients.forEach { recipientId ->
                    Log.i(TAG, "  - $recipientId")
                }
                
                val startResult = pollingService.startPolling()
                if (startResult) {
                    Log.i(TAG, "轮询服务启动成功，活跃联系人数量: ${allActiveRecipients.size}")
                    
                    // 为每个活跃联系人添加轮询目标
                    allActiveRecipients.forEach { recipientId ->
                        try {
                            // 获取该联系人的活跃通道
                            val recipientChannels = channelManager.getActiveChannels(recipientId)
                            if (recipientChannels.isNotEmpty()) {
                                val channel = recipientChannels.first() // 使用第一个活跃通道
                                val addResult = pollingService.addPollingTarget(recipientId, channel.metadata)
                                Log.d(TAG, "添加轮询目标: recipientId=$recipientId, 结果=$addResult")
                            } else {
                                Log.w(TAG, "联系人无活跃通道，跳过轮询: recipientId=$recipientId")
                            }
                        } catch (addTargetEx: Exception) {
                            Log.e(TAG, "添加轮询目标失败: recipientId=$recipientId", addTargetEx)
                        }
                    }
                } else {
                    Log.w(TAG, "轮询服务启动失败")
                }
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