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
     * 启动轮询服务
     */
    private suspend fun startPollingService() {
        Log.d(TAG, "启动轮询服务...")
        
        try {
            // 检查缓冲区状态，如果有待写操作则短暂延迟以改善体验
            try {
                val operationBuffer = org.thoughtcrime.securesms.tap.database.TapDatabaseOperationBuffer.getInstance(context)
                val bufferStatus = operationBuffer.getBufferStatus()
                if (bufferStatus.pendingOperations > 0) {
                    Log.d(TAG, "检测到${bufferStatus.pendingOperations}个待写操作，延迟500ms启动轮询以改善体验")
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: Exception) {
                Log.v(TAG, "缓冲区状态检查异常，继续启动", e)
            }
            
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
                                val addResult = pollingService.addPollingTarget(recipientId, channel.metadata, channel)
                                Log.d(TAG, "添加轮询目标: recipientId=$recipientId, 结果=$addResult")
                            } else {
                                // ✅ 有Token但没有活跃通道：可能是正在建立通道，或等待用户确认
                                // 不自动清理，Token会在明确的disable/降级/拒绝时被清理
                                Log.w(TAG, "有Token但无活跃通道，跳过轮询添加: recipientId=$recipientId")
                                Log.d(TAG, "  可能原因: 1)通道正在建立中 2)等待用户确认 3)历史残留Token")
                                Log.d(TAG, "  Token会在用户disable v2 mode或自动降级时被清理")
                            }
                        } catch (addTargetEx: Exception) {
                            Log.e(TAG, "添加轮询目标失败: recipientId=$recipientId", addTargetEx)
                        }
                    }
                    
                    // 轮询启动成功，重置重试计数
                    pollingRetryAttempts = 0
                } else {
                    Log.w(TAG, "轮询服务启动失败")
                }
            } else {
                Log.w(TAG, "无活跃联系人，轮询服务未启动")
                // 延迟重试启动轮询服务
                schedulePollingRetry()
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
            validReceivedTokens.forEach { (recipientId, token) ->
                activeRecipients.add(recipientId)
                // 诊断日志：记录recipientId格式
                Log.d(TAG, "接收Token recipientId格式: $recipientId (包含'-': ${recipientId.contains("-")}, 数字格式: ${recipientId.all { it.isDigit() || it == ':' }})")
            }
            
            // 获取所有有效的共享Token
            val validSharedTokens = tokenPool.getAllValidSharedTokens()
            validSharedTokens.forEach { (recipientId, token) ->
                activeRecipients.add(recipientId)
                // 诊断日志：记录recipientId格式
                Log.d(TAG, "共享Token recipientId格式: $recipientId (包含'-': ${recipientId.contains("-")}, 数字格式: ${recipientId.all { it.isDigit() || it == ':' }})")
            }
            
            Log.d(TAG, "找到活跃接收者数量: ${activeRecipients.size}")
            activeRecipients
            
        } catch (e: Exception) {
            Log.w(TAG, "获取活跃接收者失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptySet()
        }
    }
    
    /**
     * 延迟重试启动轮询服务
     */
    private fun schedulePollingRetry() {
        if (pollingRetryAttempts >= maxPollingRetryAttempts) {
            Log.w(TAG, "轮询服务启动重试已达上限(${maxPollingRetryAttempts}次)，停止重试")
            return
        }
        
        pollingRetryAttempts++
        val delayMs = 3000L * pollingRetryAttempts // 3秒、6秒、9秒递增延迟
        
        Log.i(TAG, "计划${delayMs}ms后重试启动轮询服务 (第${pollingRetryAttempts}次)")
        
        initScope.launch {
            try {
                kotlinx.coroutines.delay(delayMs)
                
                Log.d(TAG, "执行轮询服务启动重试")
                retryStartPollingService()
                
            } catch (e: Exception) {
                Log.e(TAG, "轮询服务重试启动失败: ${LogSanitizer.sanitizeThrowable(e)}")
            }
        }
    }
    
    /**
     * 重试启动轮询服务
     */
    private suspend fun retryStartPollingService() {
        try {
            val pollingService = TapPollingService.getInstance(context)
            val tokenPool = TransportTokenPool.getInstance(context)
            val channelManager = TransportChannelManager.getInstance(context)
            
            // 重新检查活跃联系人
            val activeRecipientsFromTokens = getActiveRecipientsFromTokenPool(tokenPool)
            val allActiveChannels = channelManager.getAllActiveChannels()
            val activeRecipientsFromChannels = allActiveChannels.map { it.recipientId }.toSet()
            val allActiveRecipients = activeRecipientsFromTokens + activeRecipientsFromChannels
            
            if (allActiveRecipients.isEmpty()) {
                Log.d(TAG, "重试时仍无活跃联系人")
                schedulePollingRetry() // 继续重试
                return
            }
            
            Log.i(TAG, "重试启动轮询服务，发现${allActiveRecipients.size}个活跃联系人")
            
            val startResult = pollingService.startPolling()
            if (startResult) {
                // 为每个活跃联系人添加轮询目标
                allActiveRecipients.forEach { recipientId ->
                    try {
                        val recipientChannels = channelManager.getActiveChannels(recipientId)
                        if (recipientChannels.isNotEmpty()) {
                            val channel = recipientChannels.first()
                            pollingService.addPollingTarget(recipientId, channel.metadata, channel)
                            Log.d(TAG, "重试添加轮询目标成功: recipientId=$recipientId")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "重试添加轮询目标失败: recipientId=$recipientId", e)
                    }
                }
                
                Log.i(TAG, "轮询服务重试启动成功")
                pollingRetryAttempts = 0 // 成功后重置计数
            } else {
                Log.w(TAG, "轮询服务重试启动失败")
                schedulePollingRetry() // 继续重试
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "重试启动轮询服务异常: ${LogSanitizer.sanitizeThrowable(e)}")
            schedulePollingRetry() // 继续重试
        }
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
     * 在Token加载完成后重新检查并启动轮询服务
     * 由TransportTokenPool在延迟加载Token成功后调用
     */
    suspend fun retryPollingServiceIfNeeded() {
        if (!isInitialized) {
            Log.w(TAG, "TaP模块未初始化，无法重试轮询服务")
            return
        }
        
        Log.i(TAG, "Token加载完成，重新检查轮询服务状态")
        
        try {
            val pollingService = TapPollingService.getInstance(context)
            val tokenPool = TransportTokenPool.getInstance(context)
            val channelManager = TransportChannelManager.getInstance(context)
            
            // 检查活跃联系人
            val activeRecipientsFromTokens = getActiveRecipientsFromTokenPool(tokenPool)
            val allActiveChannels = channelManager.getAllActiveChannels()
            val activeRecipientsFromChannels = allActiveChannels.map { it.recipientId }.toSet()
            val allActiveRecipients = activeRecipientsFromTokens + activeRecipientsFromChannels
            
            if (allActiveRecipients.isEmpty()) {
                Log.d(TAG, "Token加载后仍无活跃联系人")
                return
            }
            
            Log.i(TAG, "Token加载后发现${allActiveRecipients.size}个活跃联系人，启动轮询服务")
            
            // 启动轮询服务
            val startResult = pollingService.startPolling()
            if (startResult) {
                // 为每个活跃联系人添加轮询目标
                allActiveRecipients.forEach { recipientId ->
                    try {
                        val recipientChannels = channelManager.getActiveChannels(recipientId)
                        if (recipientChannels.isNotEmpty()) {
                            val channel = recipientChannels.first()
                            val addResult = pollingService.addPollingTarget(recipientId, channel.metadata, channel)
                            if (addResult) {
                                Log.d(TAG, "Token加载后成功添加轮询目标: recipientId=$recipientId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Token加载后添加轮询目标失败: recipientId=$recipientId", e)
                    }
                }
                
                Log.i(TAG, "Token加载后轮询服务启动成功")
            } else {
                Log.w(TAG, "Token加载后轮询服务启动失败")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Token加载后重试轮询服务异常: ${LogSanitizer.sanitizeThrowable(e)}")
        }
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