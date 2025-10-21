package org.thoughtcrime.securesms.tap

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.type.TypeReference
import org.thoughtcrime.securesms.tap.utils.LogSanitizer

/**
 * 传输Token池管理器
 * 
 * 负责传输服务访问Token的管理，包括接收Token、共享Token的存储、获取、
 * 验证、刷新和清理。支持多种Token类型和自动管理功能。
 */
class TransportTokenPool private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportTokenPool"
        
        @Volatile
        private var INSTANCE: TransportTokenPool? = null
        
        /**
         * 获取TransportTokenPool单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportTokenPool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportTokenPool(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建TransportTokenPool实例: ${it.hashCode()}")
                }
            }
        }
        
        /**
         * 重置单例实例（仅用于测试）
         */
        @JvmStatic
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE?.let { instance ->
                    runBlocking {
                        instance.cleanup()
                    }
                }
                INSTANCE = null
                Log.d(TAG, "重置TransportTokenPool实例")
            }
        }
        

        
        // 缓存相关常量
        private const val DEFAULT_CACHE_SIZE = 1000
        private const val DEFAULT_CLEANUP_INTERVAL_MS = 300000L // 5分钟
        
        // 清理间隔：24小时
        private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
    
    // Token存储
    private val receivedTokens = ConcurrentHashMap<String, MutableMap<String, TransportToken>>()
    private val sharedTokens = ConcurrentHashMap<String, MutableMap<String, TransportToken>>()
    private val tokenMetadata = ConcurrentHashMap<String, TokenMetadata>()
    
    // 线程安全
    private val tokenLock = ReentrantReadWriteLock()
    private val configLock = ReentrantReadWriteLock()
    
    // 安全持久化存储（迁移到SignalStore.tap）
    // 使用getter方法而非lazy初始化，避免启动时序问题
    private fun getTapValues(): org.thoughtcrime.securesms.keyvalue.TapValues? {
        return try {
            org.thoughtcrime.securesms.keyvalue.SignalStore.tap
        } catch (e: Exception) {
            Log.w(TAG, "SignalStore.tap尚未初始化: ${e.message}")
            null
        }
    }
    

    
    private val objectMapper = ObjectMapper().apply {
        // 忽略未知属性，确保向后兼容性
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    
    // 配置和状态
    private var config: TransportTokenConfig = TransportTokenConfig()
    private var isInitialized: Boolean = false
    
    // 定时清理
    private var cleanupExecutor: ScheduledExecutorService? = null
    private var cleanupTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var refreshTask: java.util.concurrent.ScheduledFuture<*>? = null
    
    // 协程作用域
    private val poolScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("TokenPool")
    )
    
    /**
     * 初始化Token池管理器
     */
    suspend fun initialize(tokenConfig: TransportTokenConfig): Boolean {
        return withContext(Dispatchers.IO) {
            configLock.write {
                try {
                    if (isInitialized) {
                        Log.w(TAG, "Token池管理器已经初始化")
                        return@withContext true
                    }
                    
                    Log.i(TAG, "初始化Token池管理器...")
                    
                    config = tokenConfig
                    
                    // 从持久化存储加载Token
                    loadTokensFromStorage()
                    
                    // 如果加载失败（SignalStore未初始化），安排重试
                    if (getTotalReceivedTokens() == 0 && getTotalSharedTokens() == 0) {
                        Log.w(TAG, "Token加载结果为空，可能SignalStore未就绪，安排延迟重试")
                        scheduleTokenLoadRetry()
                    }
                    
                    // 启动定时任务
                    startCleanupTask()
                    if (config.autoRefresh) {
                        startRefreshTask()
                    }
                    
                    isInitialized = true
                    Log.i(TAG, "Token池管理器初始化完成，接收Token: ${getTotalReceivedTokens()}, 共享Token: ${getTotalSharedTokens()}")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "初始化Token池管理器失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 安排Token加载重试
     */
    private fun scheduleTokenLoadRetry() {
        poolScope.launch {
            var retryCount = 0
            val maxRetries = 3
            
            while (retryCount < maxRetries) {
                retryCount++
                val delayMs = 1000L * retryCount // 1秒、2秒、3秒递增延迟
                
                Log.d(TAG, "计划${delayMs}ms后重试加载Token (第${retryCount}次)")
                kotlinx.coroutines.delay(delayMs)
                
                try {
                    tokenLock.write {
                        Log.d(TAG, "执行Token加载重试")
                        loadTokensFromStorage()
                        
                        val totalTokens = getTotalReceivedTokens() + getTotalSharedTokens()
                        if (totalTokens > 0) {
                            Log.i(TAG, "Token重试加载成功: 接收=${getTotalReceivedTokens()}, 共享=${getTotalSharedTokens()}")
                            
                            // 通知TapModuleInitializer重新检查并启动轮询
                            notifyTokensLoaded()
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Token加载重试失败 (第${retryCount}次)", e)
                }
            }
            
            Log.w(TAG, "Token加载重试已达上限，可能需要重新建立v2 mode")
        }
    }
    
    /**
     * 通知Token已加载，触发轮询服务检查
     */
    private fun notifyTokensLoaded() {
        try {
            Log.d(TAG, "准备通知TapModuleInitializer: Token已加载")
            
            // 通知TapModuleInitializer重新检查轮询服务
            val initializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
            
            // 确保在初始化完成后才通知
            poolScope.launch {
                // 等待TapModuleInitializer初始化完成
                var waitCount = 0
                while (!initializer.isInitializationComplete() && waitCount < 10) {
                    waitCount++
                    Log.d(TAG, "等待TapModuleInitializer初始化完成 (${waitCount}/10)")
                    kotlinx.coroutines.delay(500)
                }
                
                if (initializer.isInitializationComplete()) {
                    Log.i(TAG, "TapModuleInitializer已就绪，触发轮询服务重试")
                    initializer.retryPollingServiceIfNeeded()
                } else {
                    Log.w(TAG, "TapModuleInitializer初始化超时，无法触发轮询重试")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知Token加载完成失败", e)
        }
    }
    
    /**
     * 添加接收到的Token
     * 
     * @param recipientId 接收者ID
     * @param token 传输Token
     * @param groupId 可选的群组 ID，用于标记群组 Token
     * @return 是否成功添加
     */
    suspend fun addReceivedToken(
        recipientId: String, 
        token: TransportToken,
        groupId: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "Token池管理器未初始化，recipientId=${LogSanitizer.sanitize(recipientId)}")
                        return@withContext false
                    }
                    
                    Log.d(TAG, "开始添加接收Token: tokenId=${LogSanitizer.sanitize(token.tokenId)}, recipientId=${LogSanitizer.sanitize(recipientId)}, providerType=${token.providerType}")
                    
                    // 验证Token有效性，并提供详细的验证失败信息
                    if (!token.validate()) {
                        Log.w(TAG, "接收到无效Token，详细验证失败信息:")
                        Log.w(TAG, "  tokenId: ${if (token.tokenId.isBlank()) "空白" else "有效"}")
                        Log.w(TAG, "  recipientId: ${if (token.recipientId.isBlank()) "空白" else "有效"}")
                        Log.w(TAG, "  providerType: ${token.providerType}")
                        Log.w(TAG, "  permissions: ${token.permissions.size}个权限")
                        
                        if (token is CosTransportToken) {
                            Log.w(TAG, "  COS Token详情:")
                            Log.w(TAG, "    accessKeyId: ${if (token.accessKeyId.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    secretAccessKey: ${if (token.secretAccessKey.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    region: ${if (token.region.isBlank()) "空白" else token.region}")
                            Log.w(TAG, "    bucketName: ${if (token.bucketName.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    cloudProvider: ${if (token.cloudProvider.isBlank()) "空白" else token.cloudProvider}")
                            Log.w(TAG, "    providerType匹配: ${token.providerType == "cos"}")
                            Log.w(TAG, "    cloudProvider有效: ${token.cloudProvider == "AWS" || token.cloudProvider == "TENCENT"}")
                        }
                        return@withContext false
                    }
                    
                    // 检查是否超过缓存大小限制
                    val currentReceivedTokens = getTotalReceivedTokens()
                    if (currentReceivedTokens >= config.maxTokens) {
                        Log.d(TAG, "接收Token缓存接近限制($currentReceivedTokens/${config.maxTokens})，清理过期Token")
                        cleanExpiredReceivedTokens()
                        val afterCleanup = getTotalReceivedTokens()
                        if (afterCleanup >= config.maxTokens) {
                            Log.w(TAG, "接收Token缓存已满，清理后仍有${afterCleanup}个Token，无法添加新Token")
                            return@withContext false
                        } else {
                            Log.d(TAG, "清理过期Token成功，从${currentReceivedTokens}减少到${afterCleanup}")
                        }
                    }
                    
                    // 检查是否已存在相同Token
                    val providerTokens = receivedTokens.computeIfAbsent(recipientId) { mutableMapOf() }
                    val existingToken = providerTokens[token.providerType]
                    if (existingToken != null) {
                        Log.d(TAG, "替换已存在的接收Token: 旧tokenId=${LogSanitizer.sanitize(existingToken.tokenId)}, 新tokenId=${LogSanitizer.sanitize(token.tokenId)}")
                    }
                    
                    // 添加到缓存
                    providerTokens[token.providerType] = token
                    
                    // 记录元数据
                    val metadata = TokenMetadata(
                        tokenId = token.tokenId,
                        recipientId = recipientId,
                        providerType = token.providerType,
                        tokenType = TokenType.RECEIVED,
                        addedAt = System.currentTimeMillis(),
                        lastAccessedAt = System.currentTimeMillis(),
                        groupId = groupId  // 记录群组 ID
                    )
                    tokenMetadata[token.tokenId] = metadata
                    
                    // 持久化存储
                    try {
                        saveTokensToStorage()
                        Log.d(TAG, "Token持久化存储成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "Token持久化存储失败，但内存缓存已更新", e)
                        // 持久化失败不影响内存操作的成功
                    }
                    
                    val groupInfo = if (groupId != null) ", groupId=${LogSanitizer.sanitize(groupId)}" else ""
                    Log.i(TAG, "添加接收Token成功: tokenId=${LogSanitizer.sanitize(token.tokenId)}, recipientId=${LogSanitizer.sanitize(recipientId)}, providerType=${token.providerType}$groupInfo")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "添加接收Token异常: recipientId=${LogSanitizer.sanitize(recipientId)}, tokenId=${LogSanitizer.sanitize(token.tokenId)}", e)
                    false
                }
            }
        }
    }
    
    /**
     * 添加共享的Token
     * 
     * @param recipientId 接收者ID
     * @param token 传输Token
     * @param groupId 可选的群组 ID，用于标记群组 Token
     * @return 是否成功添加
     */
    suspend fun addSharedToken(
        recipientId: String, 
        token: TransportToken,
        groupId: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "Token池管理器未初始化，recipientId=${LogSanitizer.sanitize(recipientId)}")
                        return@withContext false
                    }
                    
                    Log.d(TAG, "开始添加共享Token: tokenId=${LogSanitizer.sanitize(token.tokenId)}, recipientId=${LogSanitizer.sanitize(recipientId)}, providerType=${token.providerType}")
                    
                    // 验证Token有效性，并提供详细的验证失败信息
                    if (!token.validate()) {
                        Log.w(TAG, "共享Token无效，详细验证失败信息:")
                        Log.w(TAG, "  tokenId: ${if (token.tokenId.isBlank()) "空白" else "有效"}")
                        Log.w(TAG, "  recipientId: ${if (token.recipientId.isBlank()) "空白" else "有效"}")
                        Log.w(TAG, "  providerType: ${token.providerType}")
                        Log.w(TAG, "  permissions: ${token.permissions.size}个权限")
                        
                        if (token is CosTransportToken) {
                            Log.w(TAG, "  COS Token详情:")
                            Log.w(TAG, "    accessKeyId: ${if (token.accessKeyId.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    secretAccessKey: ${if (token.secretAccessKey.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    region: ${if (token.region.isBlank()) "空白" else token.region}")
                            Log.w(TAG, "    bucketName: ${if (token.bucketName.isBlank()) "空白" else "有效"}")
                            Log.w(TAG, "    cloudProvider: ${if (token.cloudProvider.isBlank()) "空白" else token.cloudProvider}")
                            Log.w(TAG, "    providerType匹配: ${token.providerType == "cos"}")
                            Log.w(TAG, "    cloudProvider有效: ${token.cloudProvider == "AWS" || token.cloudProvider == "TENCENT"}")
                        }
                        return@withContext false
                    }
                    
                    // 检查是否超过缓存大小限制
                    val currentSharedTokens = getTotalSharedTokens()
                    if (currentSharedTokens >= config.maxTokens) {
                        Log.d(TAG, "共享Token缓存接近限制($currentSharedTokens/${config.maxTokens})，清理过期Token")
                        cleanExpiredSharedTokens()
                        val afterCleanup = getTotalSharedTokens()
                        if (afterCleanup >= config.maxTokens) {
                            Log.w(TAG, "共享Token缓存已满，清理后仍有${afterCleanup}个Token，无法添加新Token")
                            return@withContext false
                        } else {
                            Log.d(TAG, "清理过期Token成功，从${currentSharedTokens}减少到${afterCleanup}")
                        }
                    }
                    
                    // 检查是否已存在相同Token
                    val providerTokens = sharedTokens.computeIfAbsent(recipientId) { mutableMapOf() }
                    val existingToken = providerTokens[token.providerType]
                    if (existingToken != null) {
                        Log.d(TAG, "替换已存在的共享Token: 旧tokenId=${LogSanitizer.sanitize(existingToken.tokenId)}, 新tokenId=${LogSanitizer.sanitize(token.tokenId)}")
                    }
                    
                    // 添加到缓存
                    providerTokens[token.providerType] = token
                    
                    // 记录元数据
                    val metadata = TokenMetadata(
                        tokenId = token.tokenId,
                        recipientId = recipientId,
                        providerType = token.providerType,
                        tokenType = TokenType.SHARED,
                        addedAt = System.currentTimeMillis(),
                        lastAccessedAt = System.currentTimeMillis(),
                        groupId = groupId  // 记录群组 ID
                    )
                    tokenMetadata[token.tokenId] = metadata
                    
                    // 持久化存储
                    try {
                        saveTokensToStorage()
                        Log.d(TAG, "Token持久化存储成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "Token持久化存储失败，但内存缓存已更新", e)
                        // 持久化失败不影响内存操作的成功
                    }
                    
                    val groupInfo = if (groupId != null) ", groupId=${LogSanitizer.sanitize(groupId)}" else ""
                    Log.i(TAG, "添加共享Token成功: tokenId=${LogSanitizer.sanitize(token.tokenId)}, recipientId=${LogSanitizer.sanitize(recipientId)}, providerType=${token.providerType}$groupInfo")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "添加共享Token异常: recipientId=${LogSanitizer.sanitize(recipientId)}, tokenId=${LogSanitizer.sanitize(token.tokenId)}", e)
                    false
                }
            }
        }
    }
    
    /**
     * 获取群组的所有 Token
     * 
     * @param groupId 群组 ID
     * @return 群组相关的 Token 列表（包括接收和共享的）
     */
    fun getGroupTokens(groupId: String): List<Pair<String, TransportToken>> {
        return tokenLock.read {
            val result = mutableListOf<Pair<String, TransportToken>>()
            
            // 查找所有与该群组相关的 Token
            tokenMetadata.values
                .filter { it.groupId == groupId }
                .forEach { metadata ->
                    // 根据类型从对应的存储中获取 Token
                    val token = when (metadata.tokenType) {
                        TokenType.RECEIVED -> receivedTokens[metadata.recipientId]?.get(metadata.providerType)
                        TokenType.SHARED -> sharedTokens[metadata.recipientId]?.get(metadata.providerType)
                    }
                    
                    if (token != null) {
                        result.add(metadata.recipientId to token)
                        Log.v(TAG, "找到群组Token: groupId=$groupId, recipientId=${LogSanitizer.sanitize(metadata.recipientId)}, type=${metadata.tokenType}")
                    }
                }
            
            Log.d(TAG, "查询群组Token: groupId=$groupId, 找到${result.size}个")
            result
        }
    }
    
    /**
     * 获取有效的接收Token
     */
    fun getValidReceivedToken(recipientId: String, providerType: String): TransportToken? {
        tokenLock.read {
            val token = receivedTokens[recipientId]?.get(providerType)
            if (token != null && !token.isExpired && token.validate()) {
                // 更新访问时间
                updateTokenAccessTime(token.tokenId)
                Log.d(TAG, "获取有效接收Token: ${token.tokenId}")
                return token
            } else if (token != null) {
                Log.d(TAG, "接收Token已过期或无效: ${token.tokenId}")
            }
            return null
        }
    }
    
    /**
     * 获取有效的共享Token
     */
    fun getValidSharedToken(recipientId: String, providerType: String): TransportToken? {
        tokenLock.read {
            val token = sharedTokens[recipientId]?.get(providerType)
            if (token != null && !token.isExpired && token.validate()) {
                // 更新访问时间
                updateTokenAccessTime(token.tokenId)
                Log.d(TAG, "获取有效共享Token: ${token.tokenId}")
                return token
            } else if (token != null) {
                Log.d(TAG, "共享Token已过期或无效: ${token.tokenId}")
            }
            return null
        }
    }
    
    /**
     * 获取对端Token信息
     * 
     * 对端Token是指我们接收到的、用于访问对端存储的Token
     */
    fun getPeerTokenInfo(recipientId: String, providerType: String): PeerTokenInfo? {
        tokenLock.read {
            val receivedToken = receivedTokens[recipientId]?.get(providerType)
            if (receivedToken != null && !receivedToken.isExpired && receivedToken.validate()) {
                // 从Token中提取对端信息
                return extractPeerInfo(receivedToken)
            }
            return null
        }
    }
    
    /**
     * 从Token中提取对端信息
     */
    private fun extractPeerInfo(token: TransportToken): PeerTokenInfo {
        return when (token) {
            is CosTransportToken -> {
                PeerTokenInfo(
                    address = constructCosAddress(token.region, token.bucketName),
                    token = token,
                    region = token.region,
                    bucketName = token.bucketName,
                    endpoint = null
                )
            }
            else -> {
                // 对于其他类型的Token，提供基础信息
                PeerTokenInfo(
                    address = "unknown://${token.providerType}",
                    token = token,
                    region = null,
                    bucketName = null,
                    endpoint = null
                )
            }
        }
    }
    
    /**
     * 构建COS地址
     */
    private fun constructCosAddress(region: String, bucketName: String): String {
        // 根据region和bucketName构建标准的COS地址
        // 这里假设是腾讯云COS，具体可能需要根据Provider配置调整
        return "https://$bucketName.cos.$region.myqcloud.com"
    }
    
    /**
     * 获取本端Token信息
     * 
     * 本端Token是指我们共享给对端的、用于对方访问我们存储的Token
     */
    fun getMyTokenInfo(recipientId: String, providerType: String): MyTokenInfo? {
        tokenLock.read {
            val sharedToken = sharedTokens[recipientId]?.get(providerType)
            if (sharedToken != null && !sharedToken.isExpired && sharedToken.validate()) {
                return extractMyInfo(sharedToken)
            }
            return null
        }
    }
    
    /**
     * 从共享Token中提取本端信息
     */
    private fun extractMyInfo(token: TransportToken): MyTokenInfo {
        return when (token) {
            is CosTransportToken -> {
                MyTokenInfo(
                    address = constructCosAddress(token.region, token.bucketName),
                    token = token,
                    region = token.region,
                    bucketName = token.bucketName,
                    endpoint = null
                )
            }
            else -> {
                MyTokenInfo(
                    address = "unknown://${token.providerType}",
                    token = token,
                    region = null,
                    bucketName = null,
                    endpoint = null
                )
            }
        }
    }
    
    /**
     * 获取即将过期的Token列表
     */
    fun getNearExpiryTokens(): List<TransportToken> {
        tokenLock.read {
            val nearExpiryTokens = mutableListOf<TransportToken>()
            
            // 检查接收Token
            receivedTokens.values.forEach { providerTokens ->
                providerTokens.values.forEach { token ->
                    if (token.isNearExpiry && !token.isExpired) {
                        nearExpiryTokens.add(token)
                    }
                }
            }
            
            // 检查共享Token
            sharedTokens.values.forEach { providerTokens ->
                providerTokens.values.forEach { token ->
                    if (token.isNearExpiry && !token.isExpired) {
                        nearExpiryTokens.add(token)
                    }
                }
            }
            
            return nearExpiryTokens.sortedBy { it.expirationTime }
        }
    }
    
    /**
     * 清理过期Token
     */
    suspend fun cleanExpiredTokens(): Int {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                var cleanedCount = 0
                
                // 清理过期的接收Token
                cleanedCount += cleanExpiredReceivedTokens()
                
                // 清理过期的共享Token
                cleanedCount += cleanExpiredSharedTokens()
                
                // 检查是否超过内存限制，如果是则强制清理
                val totalCount = getTotalReceivedTokens() + getTotalSharedTokens()
                if (totalCount > config.maxTokens) {
                    val excessCount = totalCount - config.maxTokens
                    cleanedCount += forceClearExcessTokens(excessCount)
                }
                
                // 持久化存储
                if (cleanedCount > 0) {
                    saveTokensToStorage()
                    Log.i(TAG, "清理Token数量: $cleanedCount (过期 + 多余)")
                }
                
                cleanedCount
            }
        }
    }
    
    /**
     * 强制清理多余Token（当达到缓存限制时）
     */
    private fun forceClearExcessTokens(excessCount: Int): Int {
        if (excessCount <= 0) return 0
        
        Log.i(TAG, "内存限制清理，需清理Token数量: $excessCount")
        
        // 按优先级清理Token：最后访问时间早的、添加时间早的
        val allTokenMetadata = tokenMetadata.values
            .sortedWith(compareBy<TokenMetadata> { it.lastAccessedAt }
                .thenBy { it.addedAt }) // 然后按添加时间排序
        
        val tokensToRemove = allTokenMetadata.take(excessCount)
        var removedCount = 0
        
        tokensToRemove.forEach { metadata ->
            when (metadata.tokenType) {
                TokenType.RECEIVED -> {
                    if (receivedTokens[metadata.recipientId]?.remove(metadata.providerType) != null) {
                        removedCount++
                    }
                }
                TokenType.SHARED -> {
                    if (sharedTokens[metadata.recipientId]?.remove(metadata.providerType) != null) {
                        removedCount++
                    }
                }
            }
            tokenMetadata.remove(metadata.tokenId)
            Log.d(TAG, "内存限制移除Token: ${metadata.tokenId} (最后访问: ${metadata.lastAccessedAt})")
        }
        
        // 清理空的映射
        cleanupEmptyMaps()
        
        return removedCount
    }
    
    /**
     * 撤销Token
     */
    suspend fun revokeToken(tokenId: String, providerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    val metadata = tokenMetadata[tokenId]
                    if (metadata == null) {
                        Log.w(TAG, "Token元数据不存在: $tokenId")
                        return@withContext false
                    }
                    
                    val recipientId = metadata.recipientId
                    var revoked = false
                    
                    // 从接收Token中移除
                    receivedTokens[recipientId]?.remove(providerType)?.let {
                        revoked = true
                        Log.d(TAG, "撤销接收Token: $tokenId")
                    }
                    
                    // 从共享Token中移除
                    sharedTokens[recipientId]?.remove(providerType)?.let {
                        revoked = true
                        Log.d(TAG, "撤销共享Token: $tokenId")
                    }
                    
                    // 移除元数据
                    if (revoked) {
                        tokenMetadata.remove(tokenId)
                        
                        // 清理空的映射
                        cleanupEmptyMaps()
                        
                        // 持久化存储
                        saveTokensToStorage()
                    }
                    
                    revoked
                    
                } catch (e: Exception) {
                    Log.e(TAG, "撤销Token失败: $tokenId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 移除指定的Token
     */
    suspend fun removeToken(recipientId: String, providerType: String): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    var removed = false
                    
                    // 从接收Token中移除
                    receivedTokens[recipientId]?.remove(providerType)?.let {
                        tokenMetadata.remove(it.tokenId)
                        removed = true
                        Log.d(TAG, "移除接收Token: ${it.tokenId}")
                    }
                    
                    // 从共享Token中移除
                    sharedTokens[recipientId]?.remove(providerType)?.let {
                        tokenMetadata.remove(it.tokenId)
                        removed = true
                        Log.d(TAG, "移除共享Token: ${it.tokenId}")
                    }
                    
                    if (removed) {
                        // 清理空的映射
                        cleanupEmptyMaps()
                        
                        // 持久化存储
                        saveTokensToStorage()
                    }
                    
                    removed
                } catch (e: Exception) {
                    Log.e(TAG, "移除Token失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 移除指定联系人的所有Token
     */
    suspend fun removeAllTokensForRecipient(recipientId: String): Int {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    var removedCount = 0
                    val keysToRemove = mutableSetOf<String>()
                    
                    // ✅ 收集所有可能的ID格式
                    // 1. 添加原始格式
                    keysToRemove.add(recipientId)
                    
                    // 2. 如果是RecipientId::格式，转换为ACI格式
                    if (recipientId.startsWith("RecipientId::")) {
                        try {
                            val idNum = recipientId.removePrefix("RecipientId::").toLong()
                            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(
                                org.thoughtcrime.securesms.recipients.RecipientId.from(idNum)
                            )
                            keysToRemove.add(recipient.requireAci().toString())
                            Log.d(TAG, "RecipientId格式转换: $recipientId -> ${recipient.requireAci()}")
                        } catch (e: Exception) {
                            Log.w(TAG, "无法转换RecipientId格式: $recipientId", e)
                        }
                    }
                    
                    // 3. 如果是ACI/UUID格式，查找对应的RecipientId::格式
                    if (recipientId.contains("-") && recipientId.length >= 32) {
                        try {
                            val aci = org.whispersystems.signalservice.api.push.ServiceId.parseOrNull(recipientId)
                            if (aci != null) {
                                val recipient = org.thoughtcrime.securesms.recipients.Recipient.externalPush(aci)
                                keysToRemove.add("RecipientId::${recipient.id.toLong()}")
                                Log.d(TAG, "ACI格式转换: $recipientId -> RecipientId::${recipient.id.toLong()}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "无法查找RecipientId格式: $recipientId", e)
                        }
                    }
                    
                    // 4. 如果是纯数字格式，也尝试转换
                    if (recipientId.all { it.isDigit() }) {
                        try {
                            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(
                                org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                            )
                            keysToRemove.add(recipient.requireAci().toString())
                            keysToRemove.add("RecipientId::$recipientId")
                            Log.d(TAG, "数字格式转换: $recipientId -> ${recipient.requireAci()}")
                        } catch (e: Exception) {
                            Log.w(TAG, "无法转换数字格式: $recipientId", e)
                        }
                    }
                    
                    Log.d(TAG, "Token清理目标格式: ${keysToRemove.joinToString(", ")}")
                    
                    // ✅ 遍历所有格式，删除匹配的Token
                    for (key in keysToRemove) {
                        // 移除接收Token
                        receivedTokens[key]?.values?.forEach { token ->
                            tokenMetadata.remove(token.tokenId)
                            removedCount++
                            Log.d(TAG, "移除接收Token [key=$key]: ${token.tokenId}")
                        }
                        receivedTokens.remove(key)
                        
                        // 移除共享Token
                        sharedTokens[key]?.values?.forEach { token ->
                            tokenMetadata.remove(token.tokenId)
                            removedCount++
                            Log.d(TAG, "移除共享Token [key=$key]: ${token.tokenId}")
                        }
                        sharedTokens.remove(key)
                    }
                    
                    if (removedCount > 0) {
                        // 清理空的映射
                        cleanupEmptyMaps()
                        
                        // 持久化存储
                        saveTokensToStorage()
                        
                        Log.i(TAG, "移除联系人所有Token: recipientId=$recipientId, 清理了${keysToRemove.size}种格式, count=$removedCount")
                    } else {
                        Log.d(TAG, "没有找到需要移除的Token: recipientId=$recipientId")
                    }
                    
                    removedCount
                } catch (e: Exception) {
                    Log.e(TAG, "移除联系人所有Token失败", e)
                    0
                }
            }
        }
    }
    
    /**
     * 刷新Token（如果支持）
     */
    suspend fun refreshToken(tokenId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val metadata = tokenMetadata[tokenId]
                if (metadata == null) {
                    Log.w(TAG, "Token元数据不存在，无法刷新: $tokenId")
                    return@withContext false
                }
                
                // 获取Provider进行Token刷新
                val transportManager = TransportManager.getInstance(context)
                val provider = transportManager.getProvider(metadata.providerType)
                
                if (provider == null) {
                    Log.w(TAG, "Provider不存在，无法刷新Token: ${metadata.providerType}")
                    return@withContext false
                }
                
                if (!provider.supportsAuth) {
                    Log.d(TAG, "Provider不支持权限管理，无需刷新Token: ${metadata.providerType}")
                    return@withContext true
                }
                
                // 获取当前Token
                val currentToken = when (metadata.tokenType) {
                    TokenType.RECEIVED -> receivedTokens[metadata.recipientId]?.get(metadata.providerType)
                    TokenType.SHARED -> sharedTokens[metadata.recipientId]?.get(metadata.providerType)
                }
                
                if (currentToken == null) {
                    Log.w(TAG, "当前Token不存在，无法刷新: $tokenId")
                    return@withContext false
                }
                
                // 创建刷新请求
                val refreshRequest = TransportTokenRequest(
                    recipientId = metadata.recipientId,
                    providerType = metadata.providerType,
                    requestedPermissions = currentToken.permissions,
                    validityDurationMs = 24 * 60 * 60 * 1000L // 24小时有效期
                )
                
                // 执行Token刷新
                val newToken = provider.generateToken(refreshRequest)
                if (newToken != null) {
                    // 替换旧Token
                    when (metadata.tokenType) {
                        TokenType.RECEIVED -> addReceivedToken(metadata.recipientId, newToken)
                        TokenType.SHARED -> addSharedToken(metadata.recipientId, newToken)
                    }
                    
                    Log.i(TAG, "Token刷新成功: ${LogSanitizer.sanitize(tokenId)} -> ${LogSanitizer.sanitize(newToken.tokenId)}")
                    true
                } else {
                    Log.w(TAG, "Token刷新失败: ${LogSanitizer.sanitize(tokenId)}")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Token刷新异常: $tokenId", e)
                false
            }
        }
    }
    
    /**
     * 获取Token统计信息
     */
    fun getTokenStatistics(): TransportTokenStatistics {
        tokenLock.read {
            val validReceivedTokens = receivedTokens.values.sumOf { providerTokens ->
                providerTokens.values.count { !it.isExpired && it.validate() }
            }
            
            val validSharedTokens = sharedTokens.values.sumOf { providerTokens ->
                providerTokens.values.count { !it.isExpired && it.validate() }
            }
            
            val totalReceivedTokens = getTotalReceivedTokens()
            val totalSharedTokens = getTotalSharedTokens()
            
            val providerStats = mutableMapOf<String, TokenProviderStatistics>()
            
            // 统计各Provider的Token数量
            (receivedTokens.values + sharedTokens.values).forEach { providerTokens ->
                providerTokens.forEach { (providerType, token) ->
                    val stats = providerStats.computeIfAbsent(providerType) {
                        TokenProviderStatistics(providerType)
                    }
                    
                    if (receivedTokens.values.any { it.containsKey(providerType) }) {
                        stats.receivedCount++
                        if (!token.isExpired && token.validate()) {
                            stats.validReceivedCount++
                        }
                    }
                    
                    if (sharedTokens.values.any { it.containsKey(providerType) }) {
                        stats.sharedCount++
                        if (!token.isExpired && token.validate()) {
                            stats.validSharedCount++
                        }
                    }
                }
            }
            
            return TransportTokenStatistics(
                totalTokens = totalReceivedTokens + totalSharedTokens,
                validTokens = validReceivedTokens + validSharedTokens,
                receivedTokens = totalReceivedTokens,
                sharedTokens = totalSharedTokens,
                validReceivedTokens = validReceivedTokens,
                validSharedTokens = validSharedTokens,
                providerStatistics = providerStats
            )
        }
    }
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(newConfig: TransportTokenConfig) {
        withContext(Dispatchers.IO) {
            configLock.write {
                val oldConfig = config
                config = newConfig
                
                Log.i(TAG, "更新Token配置")
                
                // 如果清理间隔改变，重启清理任务
                if (oldConfig.cleanupIntervalMs != newConfig.cleanupIntervalMs) {
                    restartCleanupTask()
                }
                
                // 如果自动刷新配置改变，重启刷新任务
                if (oldConfig.autoRefresh != newConfig.autoRefresh || 
                    oldConfig.defaultValidityMs != newConfig.defaultValidityMs) {
                    restartRefreshTask()
                }
                
                // 如果缓存大小减少，清理多余Token
                if (newConfig.maxTokens < oldConfig.maxTokens) {
                    poolScope.launch {
                        cleanupExcessTokens(newConfig.maxTokens)
                    }
                }
            }
        }
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始清理Token池管理器资源")
                
                // 停止定时任务
                stopCleanupTask()
                stopRefreshTask()
                
                // 清理所有Token
                tokenLock.write {
                    receivedTokens.clear()
                    sharedTokens.clear()
                    tokenMetadata.clear()
                }
                
                // 取消协程作用域
                poolScope.cancel()
                
                configLock.write {
                    isInitialized = false
                }
                
                Log.i(TAG, "Token池管理器资源清理完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "清理Token池管理器资源失败", e)
            }
        }
    }
    
    // 私有辅助方法
    
    /**
     * 获取接收Token总数
     */
    private fun getTotalReceivedTokens(): Int {
        return receivedTokens.values.sumOf { it.size }
    }
    
    /**
     * 获取共享Token总数
     */
    private fun getTotalSharedTokens(): Int {
        return sharedTokens.values.sumOf { it.size }
    }
    
    /**
     * 获取接收Token总数（公开方法）
     */
    fun getTotalReceivedTokensCount(): Int {
        return getTotalReceivedTokens()
    }
    
    /**
     * 获取共享Token总数（公开方法）
     */
    fun getTotalSharedTokensCount(): Int {
        return getTotalSharedTokens()
    }
    
    /**
     * 清理过期的接收Token
     */
    private fun cleanExpiredReceivedTokens(): Int {
        var cleanedCount = 0
        val expiredTokens = mutableListOf<Pair<String, String>>()
        
        receivedTokens.forEach { (recipientId, providerTokens) ->
            val iterator = providerTokens.iterator()
            while (iterator.hasNext()) {
                val (providerType, token) = iterator.next()
                if (token.isExpired || !token.validate()) {
                    expiredTokens.add(recipientId to providerType)
                    iterator.remove()
                    cleanedCount++
                    Log.d(TAG, "清理过期接收Token: ${token.tokenId}")
                }
            }
        }
        
        // 清理元数据
        expiredTokens.forEach { (recipientId, providerType) ->
            tokenMetadata.values.removeAll { metadata ->
                metadata.recipientId == recipientId && 
                metadata.providerType == providerType && 
                metadata.tokenType == TokenType.RECEIVED
            }
        }
        
        return cleanedCount
    }
    
    /**
     * 清理过期的共享Token
     */
    private fun cleanExpiredSharedTokens(): Int {
        var cleanedCount = 0
        val expiredTokens = mutableListOf<Pair<String, String>>()
        
        sharedTokens.forEach { (recipientId, providerTokens) ->
            val iterator = providerTokens.iterator()
            while (iterator.hasNext()) {
                val (providerType, token) = iterator.next()
                if (token.isExpired || !token.validate()) {
                    expiredTokens.add(recipientId to providerType)
                    iterator.remove()
                    cleanedCount++
                    Log.d(TAG, "清理过期共享Token: ${token.tokenId}")
                }
            }
        }
        
        // 清理元数据
        expiredTokens.forEach { (recipientId, providerType) ->
            tokenMetadata.values.removeAll { metadata ->
                metadata.recipientId == recipientId && 
                metadata.providerType == providerType && 
                metadata.tokenType == TokenType.SHARED
            }
        }
        
        return cleanedCount
    }
    
    /**
     * 清理空的映射
     */
    private fun cleanupEmptyMaps() {
        receivedTokens.entries.removeAll { it.value.isEmpty() }
        sharedTokens.entries.removeAll { it.value.isEmpty() }
    }
    
    /**
     * 更新Token访问时间
     */
    private fun updateTokenAccessTime(tokenId: String) {
        tokenMetadata[tokenId]?.let { metadata ->
            tokenMetadata[tokenId] = metadata.copy(lastAccessedAt = System.currentTimeMillis())
        }
    }
    
    /**
     * 从持久化存储加载Token
     */
    private fun loadTokensFromStorage() {
        try {
            Log.d(TAG, "开始加载持久化的Token数据")
            
            // 安全获取tapValues，如果SignalStore未初始化则跳过
            val tapValues = getTapValues()
            if (tapValues == null) {
                Log.w(TAG, "SignalStore尚未初始化，跳过Token加载，将在后续重试")
                return
            }
            
            // 尝试从SignalStore.tap加载
            val receivedTokensJson = tapValues.getReceivedTokens()
            if (!receivedTokensJson.isNullOrEmpty()) {
                val type = object : TypeReference<Map<String, Map<String, Map<String, Any>>>>() {}
                val receivedTokensData = objectMapper.readValue(receivedTokensJson, type)
                
                receivedTokens.clear()
                receivedTokensData.forEach { (recipientId, providerTokens) ->
                    val tokenMap = mutableMapOf<String, TransportToken>()
                    providerTokens.forEach { (providerType, tokenData) ->
                        val token = createTokenFromData(tokenData)
                        if (token != null && !token.isExpired) {
                            tokenMap[providerType] = token
                        } else if (token?.isExpired == true) {
                            Log.d(TAG, "跳过过期Token: ${token.tokenId}")
                        }
                    }
                    if (tokenMap.isNotEmpty()) {
                        receivedTokens[recipientId] = tokenMap
                    }
                }
                Log.d(TAG, "加载接收Token数据: ${receivedTokens.size}个条目")
            }
            
            // 尝试从SignalStore.tap加载
            val sharedTokensJson = tapValues.getSharedTokens()
            if (!sharedTokensJson.isNullOrEmpty()) {
                val type = object : TypeReference<Map<String, Map<String, Map<String, Any>>>>() {}
                val sharedTokensData = objectMapper.readValue(sharedTokensJson, type)
                
                sharedTokens.clear()
                sharedTokensData.forEach { (recipientId, providerTokens) ->
                    val tokenMap = mutableMapOf<String, TransportToken>()
                    providerTokens.forEach { (providerType, tokenData) ->
                        val token = createTokenFromData(tokenData)
                        if (token != null && !token.isExpired) {
                            tokenMap[providerType] = token
                        } else if (token?.isExpired == true) {
                            Log.d(TAG, "跳过过期Token: ${token.tokenId}")
                        }
                    }
                    if (tokenMap.isNotEmpty()) {
                        sharedTokens[recipientId] = tokenMap
                    }
                }
                Log.d(TAG, "加载共享Token数据: ${sharedTokens.size}个条目")
            }
            
            // 从SignalStore.tap加载Token元数据
            val tokenMetadataJson = tapValues.getTokenMetadata()
            if (!tokenMetadataJson.isNullOrEmpty()) {
                val type = object : TypeReference<Map<String, Map<String, Any>>>() {}
                val tokenMetadataData = objectMapper.readValue(tokenMetadataJson, type)
                
                tokenMetadata.clear()
                tokenMetadataData.forEach { (tokenId, metadataMap) ->
                    try {
                        val metadata = TokenMetadata.fromMap(metadataMap)
                        tokenMetadata[tokenId] = metadata
                    } catch (e: Exception) {
                        Log.w(TAG, "无法加载Token元数据: $tokenId", e)
                    }
                }
                Log.d(TAG, "加载Token元数据: ${tokenMetadata.size}个条目")
            }
            

            
            // 重建Token元数据（如果元数据丢失）
            if (tokenMetadata.isEmpty() && (receivedTokens.isNotEmpty() || sharedTokens.isNotEmpty())) {
                rebuildTokenMetadata()
            }
            
            Log.i(TAG, "持久化Token数据加载完成: 接收=${receivedTokens.size}, 共享=${sharedTokens.size}, 元数据=${tokenMetadata.size}")
            
            // 执行清理检查
            performCleanupIfNeeded()
            
        } catch (e: Exception) {
            Log.e(TAG, "加载持久化Token数据失败", e)
            // 清空可能损坏的数据
            receivedTokens.clear()
            sharedTokens.clear()
            tokenMetadata.clear()
        }
    }
    

    
    /**
     * 持久化Token到存储
     */
    private fun saveTokensToStorage() {
        try {
            Log.d(TAG, "开始持久化Token数据")
            
            // 安全获取tapValues
            val tapValues = getTapValues()
            if (tapValues == null) {
                Log.w(TAG, "SignalStore尚未初始化，无法保存Token")
                return
            }
            
            // 保存接收Token
            val receivedTokensData = receivedTokens.mapValues { (_, providerTokens) ->
                providerTokens.mapValues { (_, token) -> token.toMap() }
            }
            val receivedTokensJson = objectMapper.writeValueAsString(receivedTokensData)
            tapValues.setReceivedTokens(receivedTokensJson)
            
            // 保存共享Token
            val sharedTokensData = sharedTokens.mapValues { (_, providerTokens) ->
                providerTokens.mapValues { (_, token) -> token.toMap() }
            }
            val sharedTokensJson = objectMapper.writeValueAsString(sharedTokensData)
            tapValues.setSharedTokens(sharedTokensJson)
            
            // 保存Token元数据
            val tokenMetadataData = tokenMetadata.mapValues { it.value.toMap() }
            val tokenMetadataJson = objectMapper.writeValueAsString(tokenMetadataData)
            tapValues.setTokenMetadata(tokenMetadataJson)
            
            Log.d(TAG, "Token数据持久化完成: 接收=${receivedTokens.size}, 共享=${sharedTokens.size}, 元数据=${tokenMetadata.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "持久化Token到存储失败", e)
        }
    }
    
    /**
     * 从数据创建Token对象
     */
    private fun createTokenFromData(tokenData: Map<String, Any>): TransportToken? {
        return try {
            val providerType = tokenData["providerType"] as? String ?: return null
            
            when (providerType) {
                "cos" -> CosTransportToken.fromMap(tokenData)
                // 其他类型的Token可以在这里添加
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建Token对象失败", e)
            null
        }
    }
    
    /**
     * 重建Token元数据
     */
    private fun rebuildTokenMetadata() {
        tokenMetadata.clear()
        val currentTime = System.currentTimeMillis()
        
        // 重建接收Token元数据
        receivedTokens.forEach { (recipientId, providerTokens) ->
            providerTokens.forEach { (providerType, token) ->
                val metadata = TokenMetadata(
                    tokenId = token.tokenId,
                    recipientId = recipientId,
                    providerType = providerType,
                    tokenType = TokenType.RECEIVED,
                    addedAt = currentTime,
                    lastAccessedAt = currentTime
                )
                tokenMetadata[token.tokenId] = metadata
            }
        }
        
        // 重建共享Token元数据
        sharedTokens.forEach { (recipientId, providerTokens) ->
            providerTokens.forEach { (providerType, token) ->
                val metadata = TokenMetadata(
                    tokenId = token.tokenId,
                    recipientId = recipientId,
                    providerType = providerType,
                    tokenType = TokenType.SHARED,
                    addedAt = currentTime,
                    lastAccessedAt = currentTime
                )
                tokenMetadata[token.tokenId] = metadata
            }
        }
    }
    
    /**
     * 清理多余Token
     */
    private suspend fun cleanupExcessTokens(maxTokens: Int) {
        withContext(Dispatchers.IO) {
            tokenLock.write {
                val totalTokens = getTotalReceivedTokens() + getTotalSharedTokens()
                if (totalTokens <= maxTokens) {
                    return@withContext
                }
                
                val excessCount = totalTokens - maxTokens
                Log.i(TAG, "清理多余Token，数量: $excessCount")
                
                // 按最后访问时间排序，清理最少使用的Token
                val allTokenMetadata = tokenMetadata.values.sortedBy { it.lastAccessedAt }
                val tokensToRemove = allTokenMetadata.take(excessCount)
                
                tokensToRemove.forEach { metadata ->
                    when (metadata.tokenType) {
                        TokenType.RECEIVED -> {
                            receivedTokens[metadata.recipientId]?.remove(metadata.providerType)
                        }
                        TokenType.SHARED -> {
                            sharedTokens[metadata.recipientId]?.remove(metadata.providerType)
                        }
                    }
                    tokenMetadata.remove(metadata.tokenId)
                    Log.d(TAG, "移除Token: ${metadata.tokenId}")
                }
                
                // 清理空的映射
                cleanupEmptyMaps()
                
                // 持久化存储
                saveTokensToStorage()
            }
        }
    }
    
    // 定时任务相关方法
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        stopCleanupTask()
        
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TokenCleanupTask").apply {
                isDaemon = true
            }
        }
        
        cleanupTask = cleanupExecutor?.scheduleWithFixedDelay(
            {
                try {
                    runBlocking {
                        cleanExpiredTokens()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "定时清理任务异常", e)
                }
            },
            config.cleanupIntervalMs,
            config.cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        
        Log.d(TAG, "启动Token清理任务，间隔: ${config.cleanupIntervalMs}ms")
    }
    
    /**
     * 停止清理任务
     */
    private fun stopCleanupTask() {
        cleanupTask?.cancel(true)
        cleanupTask = null
        
        cleanupExecutor?.shutdown()
        cleanupExecutor = null
        
        Log.d(TAG, "停止Token清理任务")
    }
    
    /**
     * 启动刷新任务
     */
    private fun startRefreshTask() {
        if (!config.autoRefresh) {
            return
        }
        
        stopRefreshTask()
        
        refreshTask = cleanupExecutor?.scheduleWithFixedDelay(
            {
                try {
                    runBlocking {
                        refreshNearExpiryTokens()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "定时刷新任务异常", e)
                }
            },
            config.defaultValidityMs / 20, // 提前一半时间开始检查 (有效期的1/20)
            config.defaultValidityMs / 40, // 每1/4刷新间隔检查一次 (有效期的1/40)
            TimeUnit.MILLISECONDS
        )
        
        Log.d(TAG, "启动Token刷新任务")
    }
    
    /**
     * 停止刷新任务
     */
    private fun stopRefreshTask() {
        refreshTask?.cancel(true)
        refreshTask = null
        
        Log.d(TAG, "停止Token刷新任务")
    }
    
    /**
     * 重启清理任务
     */
    private fun restartCleanupTask() {
        Log.d(TAG, "重启Token清理任务")
        startCleanupTask()
    }
    
    /**
     * 重启刷新任务
     */
    private fun restartRefreshTask() {
        Log.d(TAG, "重启Token刷新任务")
        startRefreshTask()
    }
    
    /**
     * 刷新即将过期的Token
     */
    private suspend fun refreshNearExpiryTokens() {
        val nearExpiryTokens = getNearExpiryTokens()
        if (nearExpiryTokens.isNotEmpty()) {
            Log.i(TAG, "发现即将过期的Token数量: ${nearExpiryTokens.size}")
            
            nearExpiryTokens.forEach { token ->
                val refreshThreshold = config.defaultValidityMs / 10
                val currentTimeWithBuffer = System.currentTimeMillis() + refreshThreshold
                if (currentTimeWithBuffer >= token.expirationTime) {
                    try {
                        refreshToken(token.tokenId)
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新Token异常: ${token.tokenId}", e)
                    }
                }
            }
        }
    }
    
    /**
     * 如果需要则执行清理
     */
    private fun performCleanupIfNeeded() {
        val tapValues = getTapValues() ?: return
        
        val lastCleanupTime = tapValues.getLastCleanupTime()
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            Log.d(TAG, "执行定期清理检查")
            poolScope.launch {
                cleanExpiredTokens()
                // 更新最后清理时间
                getTapValues()?.setLastCleanupTime(currentTime)
            }
        }
    }

    /**
     * 获取对端Token（简化接口，直接返回Token）
     * 
     * 这个方法提供与原始SubAccountPool类似的直接访问方式
     */
    fun getPeerToken(recipientId: String, providerType: String): TransportToken? {
        return getValidReceivedToken(recipientId, providerType)
    }
    
    /**
     * 获取本端Token（简化接口，直接返回Token）
     * 
     * 这个方法提供与原始SubAccountPool类似的直接访问方式
     */
    fun getMyToken(recipientId: String, providerType: String): TransportToken? {
        return getValidSharedToken(recipientId, providerType)
    }
    
    /**
     * 获取所有有效的接收Token列表（用于轮询）
     */
    fun getAllValidReceivedTokens(): List<Pair<String, TransportToken>> {
        tokenLock.read {
            val validTokens = mutableListOf<Pair<String, TransportToken>>()
            
            receivedTokens.forEach { (recipientId, providerTokens) ->
                providerTokens.forEach { (providerType, token) ->
                    if (!token.isExpired && token.validate()) {
                        // 更新访问时间
                        updateTokenAccessTime(token.tokenId)
                        validTokens.add(recipientId to token)
                    }
                }
            }
            
            Log.d(TAG, "获取所有有效接收Token: ${validTokens.size}个")
            return validTokens
        }
    }
    
    /**
     * 获取所有有效的共享Token列表
     */
    fun getAllValidSharedTokens(): List<Pair<String, TransportToken>> {
        tokenLock.read {
            val validTokens = mutableListOf<Pair<String, TransportToken>>()
            
            sharedTokens.forEach { (recipientId, providerTokens) ->
                providerTokens.forEach { (providerType, token) ->
                    if (!token.isExpired && token.validate()) {
                        // 更新访问时间
                        updateTokenAccessTime(token.tokenId)
                        validTokens.add(recipientId to token)
                    }
                }
            }
            
            Log.d(TAG, "获取所有有效共享Token: ${validTokens.size}个")
            return validTokens
        }
    }
    
    /**
     * 获取群组成员的 Token 映射
     * 
     * @param groupId 群组 ID
     * @return 成员 ACI -> Token 的映射
     */
    fun getGroupMemberTokens(groupId: String): Map<String, TransportToken> {
        tokenLock.read {
            val groupTokens = mutableMapOf<String, TransportToken>()
            
            // 遍历所有接收 Token，找出属于该群组的
            receivedTokens.forEach { (recipientId, providerTokens) ->
                providerTokens.values.forEach { token ->
                    // 通过 token 的元数据判断是否属于该群组
                    // 群组 token 会在 tokenData 中包含 groupId 字段
                    val tokenGroupId = (token.toMap()["groupId"] as? String)
                    if (tokenGroupId == groupId && !token.isExpired && token.validate()) {
                        groupTokens[recipientId] = token
                        updateTokenAccessTime(token.tokenId)
                    }
                }
            }
            
            Log.d(TAG, "获取群组成员Token: groupId=$groupId, count=${groupTokens.size}")
            return groupTokens
        }
    }
    
    /**
     * 批量添加群组接收 Token
     * 
     * @param groupId 群组 ID
     * @param tokens 成员 ACI -> Token 的映射
     * @return 成功添加的数量
     */
    suspend fun addGroupReceivedTokens(
        groupId: String,
        tokens: Map<String, TransportToken>
    ): Int {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    var successCount = 0
                    
                    for ((memberAci, token) in tokens) {
                        try {
                            // 验证 Token
                            if (!token.validate()) {
                                Log.w(TAG, "群组接收Token无效: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}")
                                continue
                            }
                            
                            // 添加到接收 Token
                            val providerTokens = receivedTokens.computeIfAbsent(memberAci) { mutableMapOf() }
                            providerTokens[token.providerType] = token
                            
                            // 记录元数据
                            val metadata = TokenMetadata(
                                tokenId = token.tokenId,
                                recipientId = memberAci,
                                providerType = token.providerType,
                                tokenType = TokenType.RECEIVED,
                                addedAt = System.currentTimeMillis(),
                                lastAccessedAt = System.currentTimeMillis()
                            )
                            tokenMetadata[token.tokenId] = metadata
                            
                            successCount++
                            Log.d(TAG, "群组接收Token添加成功: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "添加群组接收Token失败: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                        }
                    }
                    
                    // 持久化存储
                    if (successCount > 0) {
                        try {
                            saveTokensToStorage()
                            Log.d(TAG, "群组接收Token持久化成功: groupId=$groupId")
                        } catch (e: Exception) {
                            Log.e(TAG, "群组接收Token持久化失败: groupId=$groupId", e)
                        }
                    }
                    
                    Log.i(TAG, "批量添加群组接收Token: groupId=$groupId, 成功=$successCount/${tokens.size}")
                    successCount
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量添加群组接收Token异常: groupId=$groupId", e)
                    0
                }
            }
        }
    }
    
    /**
     * 批量添加群组共享 Token
     * 
     * @param groupId 群组 ID
     * @param tokens 成员 ACI -> Token 的映射
     * @return 成功添加的数量
     */
    suspend fun addGroupSharedTokens(
        groupId: String,
        tokens: Map<String, TransportToken>
    ): Int {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    var successCount = 0
                    
                    for ((memberAci, token) in tokens) {
                        try {
                            // 验证 Token
                            if (!token.validate()) {
                                Log.w(TAG, "群组共享Token无效: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}")
                                continue
                            }
                            
                            // 添加到共享 Token
                            val providerTokens = sharedTokens.computeIfAbsent(memberAci) { mutableMapOf() }
                            providerTokens[token.providerType] = token
                            
                            // 记录元数据
                            val metadata = TokenMetadata(
                                tokenId = token.tokenId,
                                recipientId = memberAci,
                                providerType = token.providerType,
                                tokenType = TokenType.SHARED,
                                addedAt = System.currentTimeMillis(),
                                lastAccessedAt = System.currentTimeMillis()
                            )
                            tokenMetadata[token.tokenId] = metadata
                            
                            successCount++
                            Log.d(TAG, "群组共享Token添加成功: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "添加群组共享Token失败: groupId=$groupId, memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                        }
                    }
                    
                    // 持久化存储
                    if (successCount > 0) {
                        try {
                            saveTokensToStorage()
                            Log.d(TAG, "群组共享Token持久化成功: groupId=$groupId")
                        } catch (e: Exception) {
                            Log.e(TAG, "群组共享Token持久化失败: groupId=$groupId", e)
                        }
                    }
                    
                    Log.i(TAG, "批量添加群组共享Token: groupId=$groupId, 成功=$successCount/${tokens.size}")
                    successCount
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量添加群组共享Token异常: groupId=$groupId", e)
                    0
                }
            }
        }
    }
    
    /**
     * 获取群组的共享Token（我自己为群组生成的token）
     * 
     * @param groupId 群组 ID
     * @param providerType Provider 类型
     * @return 我的群组shared token，未找到返回null
     */
    fun getGroupSharedToken(groupId: String, providerType: String): TransportToken? {
        tokenLock.read {
            // 群组的sharedToken使用groupId作为key
            val token = sharedTokens[groupId]?.get(providerType)
            if (token != null && !token.isExpired && token.validate()) {
                updateTokenAccessTime(token.tokenId)
                Log.d(TAG, "获取群组SharedToken: groupId=${LogSanitizer.sanitize(groupId)}, providerType=$providerType")
                return token
            }
            Log.w(TAG, "群组SharedToken不存在或已失效: groupId=${LogSanitizer.sanitize(groupId)}, providerType=$providerType")
            return null
        }
    }
    
    /**
     * 获取群组的所有接收Token（其他成员为群组生成的tokens）
     * 
     * @param groupId 群组 ID
     * @param providerType Provider 类型
     * @return 成员ACI -> Token的映射
     */
    fun getGroupReceivedTokens(groupId: String, providerType: String): Map<String, TransportToken> {
        tokenLock.read {
            val groupReceivedTokens = mutableMapOf<String, TransportToken>()
            
            // 遍历所有receivedTokens，通过metadata中的groupId标记识别
            receivedTokens.forEach { (memberAci, providerTokens) ->
                val token = providerTokens[providerType]
                if (token != null && !token.isExpired && token.validate()) {
                    // 检查token的metadata，确认是否属于该群组
                    val tokenMetadata = tokenMetadata[token.tokenId]
                    if (tokenMetadata?.recipientId == memberAci) {
                        // 通过tokenId格式判断是否为群组token
                        if (token.tokenId.contains("group") && token.tokenId.contains(groupId.take(8))) {
                            groupReceivedTokens[memberAci] = token
                            updateTokenAccessTime(token.tokenId)
                        }
                    }
                }
            }
            
            Log.d(TAG, "获取群组ReceivedTokens: groupId=${LogSanitizer.sanitize(groupId)}, count=${groupReceivedTokens.size}")
            return groupReceivedTokens
        }
    }
    
    /**
     * 移除群组的所有 Token
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 移除的数量
     */
    suspend fun removeGroupTokens(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Int {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    var removedCount = 0
                    
                    for (memberAci in memberAcis) {
                        // 移除接收 Token
                        receivedTokens[memberAci]?.get(providerType)?.let { token ->
                            receivedTokens[memberAci]?.remove(providerType)
                            tokenMetadata.remove(token.tokenId)
                            removedCount++
                        }
                        
                        // 移除共享 Token
                        sharedTokens[memberAci]?.get(providerType)?.let { token ->
                            sharedTokens[memberAci]?.remove(providerType)
                            tokenMetadata.remove(token.tokenId)
                            removedCount++
                        }
                    }
                    
                    // 清理空的映射
                    cleanupEmptyMaps()
                    
                    // 持久化存储
                    if (removedCount > 0) {
                        saveTokensToStorage()
                    }
                    
                    Log.i(TAG, "移除群组Token: groupId=$groupId, 移除=$removedCount")
                    removedCount
                    
                } catch (e: Exception) {
                    Log.e(TAG, "移除群组Token失败: groupId=$groupId", e)
                    0
                }
            }
        }
    }
    
    /**
     * 验证群组 Token 有效性
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 有效的成员 ACI 集合
     */
    fun validateGroupTokens(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Set<String> {
        return tokenLock.read {
            val validMembers = mutableSetOf<String>()
            
            for (memberAci in memberAcis) {
                // 检查接收 Token
                val receivedToken = receivedTokens[memberAci]?.get(providerType)
                if (receivedToken != null && !receivedToken.isExpired && receivedToken.validate()) {
                    validMembers.add(memberAci)
                }
            }
            
            Log.d(TAG, "验证群组Token: groupId=$groupId, 有效=${validMembers.size}/${memberAcis.size}")
            validMembers
        }
    }
    
    /**
     * 刷新群组 Token
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 集合
     * @param providerType Provider 类型
     * @return 刷新成功的数量
     */
    suspend fun refreshGroupTokens(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Int {
        return withContext(Dispatchers.IO) {
            var refreshedCount = 0
            
            for (memberAci in memberAcis) {
                try {
                    // 获取现有 Token
                    val existingToken = tokenLock.read {
                        receivedTokens[memberAci]?.get(providerType)
                            ?: sharedTokens[memberAci]?.get(providerType)
                    }
                    
                    if (existingToken != null && existingToken.isNearExpiry) {
                        val success = refreshToken(existingToken.tokenId)
                        if (success) {
                            refreshedCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "刷新群组Token失败: memberAci=${LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            Log.i(TAG, "刷新群组Token: groupId=$groupId, 成功=$refreshedCount/${memberAcis.size}")
            refreshedCount
        }
    }
}

/**
 * Token类型枚举
 */
enum class TokenType {
    RECEIVED,  // 接收到的Token
    SHARED     // 共享的Token
}

/**
 * Token元数据
 */
data class TokenMetadata(
    val tokenId: String,
    val recipientId: String,
    val providerType: String,
    val tokenType: TokenType,
    val addedAt: Long,
    val lastAccessedAt: Long,
    val groupId: String? = null  // 群组 ID，用于区分群组和一对一 Token
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf(
            "tokenId" to tokenId,
            "recipientId" to recipientId,
            "providerType" to providerType,
            "tokenType" to tokenType.name,
            "addedAt" to addedAt,
            "lastAccessedAt" to lastAccessedAt
        )
        groupId?.let { map["groupId"] = it }
        return map
    }

    companion object {
        fun fromMap(map: Map<String, Any>): TokenMetadata {
            return TokenMetadata(
                tokenId = map["tokenId"] as String,
                recipientId = map["recipientId"] as String,
                providerType = map["providerType"] as String,
                tokenType = TokenType.valueOf(map["tokenType"] as String),
                addedAt = map["addedAt"] as Long,
                lastAccessedAt = map["lastAccessedAt"] as Long,
                groupId = map["groupId"] as? String  // 兼容旧数据，groupId 可能不存在
            )
        }
    }
}

/**
 * Token统计信息
 */
data class TransportTokenStatistics(
    /** 总Token数 */
    val totalTokens: Int = 0,
    
    /** 有效Token数 */
    val validTokens: Int = 0,
    
    /** 接收Token数 */
    val receivedTokens: Int = 0,
    
    /** 共享Token数 */
    val sharedTokens: Int = 0,
    
    /** 有效接收Token数 */
    val validReceivedTokens: Int = 0,
    
    /** 有效共享Token数 */
    val validSharedTokens: Int = 0,
    
    /** 各Provider的Token统计 */
    val providerStatistics: Map<String, TokenProviderStatistics> = emptyMap()
) {
    
    /**
     * 计算Token有效率
     */
    fun getValidRate(): Double {
        return if (totalTokens > 0) validTokens.toDouble() / totalTokens else 0.0
    }
    
    /**
     * 计算接收Token有效率
     */
    fun getReceivedValidRate(): Double {
        return if (receivedTokens > 0) validReceivedTokens.toDouble() / receivedTokens else 0.0
    }
    
    /**
     * 计算共享Token有效率
     */
    fun getSharedValidRate(): Double {
        return if (sharedTokens > 0) validSharedTokens.toDouble() / sharedTokens else 0.0
    }
}

/**
 * 单个Provider的Token统计
 */
data class TokenProviderStatistics(
    val providerType: String,
    var receivedCount: Int = 0,
    var sharedCount: Int = 0,
    var validReceivedCount: Int = 0,
    var validSharedCount: Int = 0
) {
    
    val totalCount: Int get() = receivedCount + sharedCount
    val validCount: Int get() = validReceivedCount + validSharedCount
    
    fun getValidRate(): Double {
        return if (totalCount > 0) validCount.toDouble() / totalCount else 0.0
    }
} 

/**
 * 对端Token信息
 */
data class PeerTokenInfo(
    /** 对端地址 */
    val address: String,
    
    /** 对端Token */
    val token: TransportToken,
    
    /** 地域信息（COS等云服务） */
    val region: String?,
    
    /** 存储桶名称（COS等云服务） */
    val bucketName: String?,
    
    /** 服务端点（自定义服务） */
    val endpoint: String?
)

/**
 * 本端Token信息
 */
data class MyTokenInfo(
    /** 本端地址 */
    val address: String,
    
    /** 本端Token */
    val token: TransportToken,
    
    /** 地域信息（COS等云服务） */
    val region: String?,
    
    /** 存储桶名称（COS等云服务） */
    val bucketName: String?,
    
    /** 服务端点（自定义服务） */
    val endpoint: String?
) 