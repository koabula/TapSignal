package org.thoughtcrime.securesms.tap

import android.content.Context
import android.content.SharedPreferences
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
import com.fasterxml.jackson.core.type.TypeReference

/**
 * 传输Token池管理器
 * 
 * 负责传输服务访问Token的管理，包括接收Token、共享Token的存储、获取、
 * 验证、刷新和清理。支持多种Token类型和自动管理功能。
 */
class TransportTokenPool private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TransportTokenPool"
        private var INSTANCE: TransportTokenPool? = null
        
        /**
         * 获取TransportTokenPool单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportTokenPool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportTokenPool(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // SharedPreferences相关常量
        private const val PREF_NAME = "transport_token_pool"
        private const val KEY_RECEIVED_TOKENS = "received_tokens"
        private const val KEY_SHARED_TOKENS = "shared_tokens"
        private const val KEY_TOKEN_METADATA = "token_metadata"
        
        // 缓存相关常量
        private const val DEFAULT_CACHE_SIZE = 1000
        private const val DEFAULT_CLEANUP_INTERVAL_MS = 300000L // 5分钟
    }
    
    // Token存储
    private val receivedTokens = ConcurrentHashMap<String, MutableMap<String, TransportToken>>()
    private val sharedTokens = ConcurrentHashMap<String, MutableMap<String, TransportToken>>()
    private val tokenMetadata = ConcurrentHashMap<String, TokenMetadata>()
    
    // 线程安全
    private val tokenLock = ReentrantReadWriteLock()
    private val configLock = ReentrantReadWriteLock()
    
    // 持久化存储
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper()
    
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
     * 添加接收到的Token
     */
    suspend fun addReceivedToken(recipientId: String, token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "Token池管理器未初始化")
                        return@withContext false
                    }
                    
                    // 验证Token有效性
                    if (!token.validate()) {
                        Log.w(TAG, "接收到无效Token: ${token.tokenId}")
                        return@withContext false
                    }
                    
                    // 检查是否超过缓存大小限制
                    if (getTotalReceivedTokens() >= config.cacheSize) {
                        cleanExpiredReceivedTokens()
                        if (getTotalReceivedTokens() >= config.cacheSize) {
                            Log.w(TAG, "接收Token缓存已满，无法添加新Token")
                            return@withContext false
                        }
                    }
                    
                    // 添加到缓存
                    val providerTokens = receivedTokens.computeIfAbsent(recipientId) { mutableMapOf() }
                    providerTokens[token.providerType] = token
                    
                    // 记录元数据
                    val metadata = TokenMetadata(
                        tokenId = token.tokenId,
                        recipientId = recipientId,
                        providerType = token.providerType,
                        tokenType = TokenType.RECEIVED,
                        addedAt = System.currentTimeMillis(),
                        lastAccessedAt = System.currentTimeMillis()
                    )
                    tokenMetadata[token.tokenId] = metadata
                    
                    // 持久化存储
                    saveTokensToStorage()
                    
                    Log.d(TAG, "添加接收Token: ${token.tokenId}, 接收者: $recipientId, 提供者: ${token.providerType}")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "添加接收Token失败", e)
                    false
                }
            }
        }
    }
    
    /**
     * 添加共享的Token
     */
    suspend fun addSharedToken(recipientId: String, token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            tokenLock.write {
                try {
                    if (!isInitialized) {
                        Log.w(TAG, "Token池管理器未初始化")
                        return@withContext false
                    }
                    
                    // 验证Token有效性
                    if (!token.validate()) {
                        Log.w(TAG, "共享Token无效: ${token.tokenId}")
                        return@withContext false
                    }
                    
                    // 检查是否超过缓存大小限制
                    if (getTotalSharedTokens() >= config.cacheSize) {
                        cleanExpiredSharedTokens()
                        if (getTotalSharedTokens() >= config.cacheSize) {
                            Log.w(TAG, "共享Token缓存已满，无法添加新Token")
                            return@withContext false
                        }
                    }
                    
                    // 添加到缓存
                    val providerTokens = sharedTokens.computeIfAbsent(recipientId) { mutableMapOf() }
                    providerTokens[token.providerType] = token
                    
                    // 记录元数据
                    val metadata = TokenMetadata(
                        tokenId = token.tokenId,
                        recipientId = recipientId,
                        providerType = token.providerType,
                        tokenType = TokenType.SHARED,
                        addedAt = System.currentTimeMillis(),
                        lastAccessedAt = System.currentTimeMillis()
                    )
                    tokenMetadata[token.tokenId] = metadata
                    
                    // 持久化存储
                    saveTokensToStorage()
                    
                    Log.d(TAG, "添加共享Token: ${token.tokenId}, 接收者: $recipientId, 提供者: ${token.providerType}")
                    true
                    
                } catch (e: Exception) {
                    Log.e(TAG, "添加共享Token失败", e)
                    false
                }
            }
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
                if (totalCount > config.cacheSize) {
                    val excessCount = totalCount - config.cacheSize
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
                    
                    Log.i(TAG, "Token刷新成功: $tokenId -> ${newToken.tokenId}")
                    true
                } else {
                    Log.w(TAG, "Token刷新失败: $tokenId")
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
                    oldConfig.refreshAdvanceMs != newConfig.refreshAdvanceMs) {
                    restartRefreshTask()
                }
                
                // 如果缓存大小减少，清理多余Token
                if (newConfig.cacheSize < oldConfig.cacheSize) {
                    poolScope.launch {
                        cleanupExcessTokens(newConfig.cacheSize)
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
     * 从存储加载Token
     */
    private fun loadTokensFromStorage() {
        try {
            // 加载接收Token
            val receivedTokensJson = sharedPreferences.getString(KEY_RECEIVED_TOKENS, null)
            if (receivedTokensJson != null) {
                val type = object : TypeReference<Map<String, Map<String, Map<String, Any>>>>() {}
                val receivedTokensData = objectMapper.readValue(receivedTokensJson, type)
                
                receivedTokensData.forEach { (recipientId, providerTokens) ->
                    val tokenMap = mutableMapOf<String, TransportToken>()
                    providerTokens.forEach { (providerType, tokenData) ->
                        val token = createTokenFromData(tokenData)
                        if (token != null) {
                            tokenMap[providerType] = token
                        }
                    }
                    if (tokenMap.isNotEmpty()) {
                        receivedTokens[recipientId] = tokenMap
                    }
                }
            }
            
            // 加载共享Token
            val sharedTokensJson = sharedPreferences.getString(KEY_SHARED_TOKENS, null)
            if (sharedTokensJson != null) {
                val type = object : TypeReference<Map<String, Map<String, Map<String, Any>>>>() {}
                val sharedTokensData = objectMapper.readValue(sharedTokensJson, type)
                
                sharedTokensData.forEach { (recipientId, providerTokens) ->
                    val tokenMap = mutableMapOf<String, TransportToken>()
                    providerTokens.forEach { (providerType, tokenData) ->
                        val token = createTokenFromData(tokenData)
                        if (token != null) {
                            tokenMap[providerType] = token
                        }
                    }
                    if (tokenMap.isNotEmpty()) {
                        sharedTokens[recipientId] = tokenMap
                    }
                }
            }
            
            // 重建Token元数据
            rebuildTokenMetadata()
            
            Log.i(TAG, "从存储加载Token完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "从存储加载Token失败", e)
        }
    }
    
    /**
     * 保存Token到存储
     */
    private fun saveTokensToStorage() {
        try {
            val editor = sharedPreferences.edit()
            
            // 保存接收Token
            val receivedTokensData = receivedTokens.mapValues { (_, providerTokens) ->
                providerTokens.mapValues { (_, token) -> token.toMap() }
            }
            val receivedTokensJson = objectMapper.writeValueAsString(receivedTokensData)
            editor.putString(KEY_RECEIVED_TOKENS, receivedTokensJson)
            
            // 保存共享Token
            val sharedTokensData = sharedTokens.mapValues { (_, providerTokens) ->
                providerTokens.mapValues { (_, token) -> token.toMap() }
            }
            val sharedTokensJson = objectMapper.writeValueAsString(sharedTokensData)
            editor.putString(KEY_SHARED_TOKENS, sharedTokensJson)
            
            editor.apply()
            
        } catch (e: Exception) {
            Log.e(TAG, "保存Token到存储失败", e)
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
            config.refreshAdvanceMs / 2, // 提前一半时间开始检查
            config.refreshAdvanceMs / 4, // 每1/4刷新间隔检查一次
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
                if (System.currentTimeMillis() + config.refreshAdvanceMs >= token.expirationTime) {
                    try {
                        refreshToken(token.tokenId)
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新Token异常: ${token.tokenId}", e)
                    }
                }
            }
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
    val lastAccessedAt: Long
)

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