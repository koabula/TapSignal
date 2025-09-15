package org.thoughtcrime.securesms.coscomm.concurrent

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.CosClientFactory
import org.thoughtcrime.securesms.cos.TencentCosClient
import org.thoughtcrime.securesms.coscomm.data.CosAccessInfo
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * COS客户端池管理器
 * 
 * 解决腾讯云COS客户端初始化死锁问题，提供线程安全的客户端访问
 * 使用对象池模式减少客户端创建开销，避免DNS解析阻塞
 */
class CosClientPoolManager private constructor(private val context: Context) {

    companion object {
        private val TAG = Log.tag(CosClientPoolManager::class.java)
        
        // 池配置
        private const val MAX_POOL_SIZE = 10
        private const val MIN_POOL_SIZE = 2
        private const val IDLE_TIMEOUT_MS = 300_000L // 5分钟
        private const val CREATE_TIMEOUT_MS = 30_000L // 30秒创建超时
        private const val MAX_WAIT_TIME_MS = 10_000L // 10秒等待超时
        
        @Volatile
        private var INSTANCE: CosClientPoolManager? = null
        
        fun getInstance(context: Context): CosClientPoolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosClientPoolManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val lock = ReentrantLock()
    
    // 客户端池：访问信息 -> 客户端池
    private val clientPools = ConcurrentHashMap<String, ClientPool>()
    
    // 创建中的客户端，避免重复创建
    private val creatingClients = ConcurrentHashMap<String, CompletableFuture<TencentCosClient>>()
    
    // 统计信息
    private val poolStats = PoolStatistics()
    
    /**
     * 客户端池
     */
    private inner class ClientPool(val accessInfo: CosAccessInfo) {
        private val availableClients = LinkedBlockingQueue<PooledClient>(MAX_POOL_SIZE)
        private val activeClients = ConcurrentHashMap<org.thoughtcrime.securesms.cos.CosClient, PooledClient>()
        private val clientCount = AtomicInteger(0)
        var lastAccessTime = System.currentTimeMillis() // 改为public
        
        /**
         * 获取客户端
         */
        fun borrowClient(): org.thoughtcrime.securesms.cos.CosClient? {
            lastAccessTime = System.currentTimeMillis()
            
            // 先尝试从池中获取
            var pooledClient = availableClients.poll()
            
            // 检查客户端是否仍然有效
            if (pooledClient != null && !isClientValid(pooledClient)) {
                closeClient(pooledClient.client)
                clientCount.decrementAndGet()
                pooledClient = null
            }
            
            // 如果没有可用客户端且未达到最大数量，创建新的
            if (pooledClient == null && clientCount.get() < MAX_POOL_SIZE) {
                pooledClient = createNewClient()
            }
            
            // 如果仍然没有，等待一个短时间
            if (pooledClient == null) {
                try {
                    pooledClient = availableClients.poll(MAX_WAIT_TIME_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            
            if (pooledClient != null) {
                activeClients[pooledClient.client] = pooledClient
                poolStats.incrementBorrowCount()
                Log.d(TAG, "借用COS客户端: pool=${getPoolKey(accessInfo)}, active=${activeClients.size}, available=${availableClients.size}")
                return pooledClient.client
            }
            
            return null
        }
        
        /**
         * 归还客户端
         */
        fun returnClient(client: org.thoughtcrime.securesms.cos.CosClient) {
            val pooledClient = activeClients.remove(client)
            if (pooledClient != null) {
                pooledClient.lastUsed = System.currentTimeMillis()
                
                if (isClientValid(pooledClient) && availableClients.size < MAX_POOL_SIZE) {
                    availableClients.offer(pooledClient)
                    Log.d(TAG, "归还COS客户端: pool=${getPoolKey(accessInfo)}, active=${activeClients.size}, available=${availableClients.size}")
                } else {
                    closeClient(client)
                    clientCount.decrementAndGet()
                }
                poolStats.incrementReturnCount()
            }
        }
        
        /**
         * 创建新客户端
         */
        private fun createNewClient(): PooledClient? {
            return try {
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "🔨 创建新COS客户端: ${getPoolKey(accessInfo)}")
                
                // 使用超时机制创建客户端
                val future = CompletableFuture.supplyAsync {
                    // 将CosAccessInfo转换为CosConfig
                    val config = org.thoughtcrime.securesms.cos.CosConfig(
                        provider = org.thoughtcrime.securesms.cos.CosConfig.Provider.valueOf(accessInfo.provider.toString()),
                        secretId = accessInfo.accessKeyId,
                        secretKey = accessInfo.secretAccessKey,
                        region = accessInfo.region,
                        bucketName = accessInfo.bucketName,
                        sessionToken = accessInfo.sessionToken
                    )
                    CosClientFactory.createClient(config, context)
                }
                
                val client = future.get(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                clientCount.incrementAndGet()
                
                val createTime = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ COS客户端创建成功: ${getPoolKey(accessInfo)}, 耗时=${createTime}ms")
                
                PooledClient(client, System.currentTimeMillis())
                
            } catch (e: TimeoutException) {
                Log.e(TAG, "❌ COS客户端创建超时: ${getPoolKey(accessInfo)}", e)
                
                // 🔧 修复：增强超时错误日志记录
                Log.e(TAG, "COS客户端创建超时详细信息:")
                Log.e(TAG, "  - 池键: ${getPoolKey(accessInfo)}")
                Log.e(TAG, "  - 超时时间: ${CREATE_TIMEOUT_MS}ms")
                Log.e(TAG, "  - 存储桶: ${accessInfo.bucketName}")
                Log.e(TAG, "  - 区域: ${accessInfo.region}")
                Log.e(TAG, "  - 提供商: ${accessInfo.provider}")
                Log.e(TAG, "  - AccessKeyId: ${accessInfo.accessKeyId.take(8)}...")
                Log.e(TAG, "  - 凭证类型: ${if (accessInfo.sessionToken.isNullOrEmpty()) "永久凭证" else "临时凭证"}")
                Log.e(TAG, "  - 建议: 检查网络连接或DNS解析问题")
                
                poolStats.incrementCreateTimeoutCount()
                null
            } catch (e: Exception) {
                Log.e(TAG, "❌ COS客户端创建失败: ${getPoolKey(accessInfo)}", e)
                
                // 🔧 修复：增强异常错误日志记录，帮助调试
                Log.e(TAG, "COS客户端创建失败详细信息:")
                Log.e(TAG, "  - 池键: ${getPoolKey(accessInfo)}")
                Log.e(TAG, "  - 存储桶: ${accessInfo.bucketName}")
                Log.e(TAG, "  - 区域: ${accessInfo.region}")
                Log.e(TAG, "  - 提供商: ${accessInfo.provider}")
                Log.e(TAG, "  - AccessKeyId: ${accessInfo.accessKeyId.take(8)}...")
                Log.e(TAG, "  - 凭证类型: ${if (accessInfo.sessionToken.isNullOrEmpty()) "永久凭证" else "临时凭证"}")
                Log.e(TAG, "  - 凭证是否过期: ${accessInfo.isExpired()}")
                Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - 异常消息: ${e.message}")
                
                // 根据异常类型提供针对性建议
                when (e.javaClass.simpleName) {
                    "UnknownHostException", "ConnectException" -> {
                        Log.e(TAG, "  - 建议: 检查网络连接和DNS设置")
                    }
                    "SecurityException", "AccessDeniedException" -> {
                        Log.e(TAG, "  - 建议: 检查凭证和权限配置")
                    }
                    "IllegalArgumentException" -> {
                        Log.e(TAG, "  - 建议: 检查存储桶名称和区域配置")
                    }
                    else -> {
                        Log.e(TAG, "  - 建议: 检查服务器状态和网络环境")
                    }
                }
                
                poolStats.incrementCreateErrorCount()
                null
            }
        }
        
        /**
         * 检查客户端是否有效
         */
        private fun isClientValid(pooledClient: PooledClient): Boolean {
            val age = System.currentTimeMillis() - pooledClient.lastUsed
            return age <= IDLE_TIMEOUT_MS
        }
        
        /**
         * 清理过期客户端
         */
        fun cleanup() {
            val now = System.currentTimeMillis()
            
            // 清理可用客户端中的过期项
            val iterator = availableClients.iterator()
            while (iterator.hasNext()) {
                val pooledClient = iterator.next()
                if (now - pooledClient.lastUsed > IDLE_TIMEOUT_MS) {
                    iterator.remove()
                    closeClient(pooledClient.client)
                    clientCount.decrementAndGet()
                }
            }
            
            // 检查是否需要保持最小数量
            while (clientCount.get() < MIN_POOL_SIZE && clientCount.get() < MAX_POOL_SIZE) {
                val newClient = createNewClient()
                if (newClient != null) {
                    availableClients.offer(newClient)
                } else {
                    break // 创建失败，停止尝试
                }
            }
        }
        
        /**
         * 关闭池
         */
        fun close() {
            // 关闭所有可用客户端
            availableClients.forEach { closeClient(it.client) }
            availableClients.clear()
            
            // 关闭所有活跃客户端
            activeClients.keys.forEach { closeClient(it) }
            activeClients.clear()
            
            clientCount.set(0)
        }
        
        fun getStats(): PoolStats {
            return PoolStats(
                poolKey = getPoolKey(accessInfo),
                totalClients = clientCount.get(),
                activeClients = activeClients.size,
                availableClients = availableClients.size,
                lastAccessTime = this.lastAccessTime
            )
        }
    }
    
    /**
     * 池化的客户端
     */
    private data class PooledClient(
        val client: org.thoughtcrime.securesms.cos.CosClient,
        var lastUsed: Long
    )
    
    /**
     * 获取或创建COS客户端（兼容方法）
     */
    fun getOrCreateClient(accessInfo: CosAccessInfo): org.thoughtcrime.securesms.cos.CosClient? {
        return getCosClient(accessInfo)
    }
    
    /**
     * 获取COS客户端（增强版：添加健康检查和自动恢复）
     */
    fun getCosClient(accessInfo: CosAccessInfo): org.thoughtcrime.securesms.cos.CosClient? {
        val poolKey = getPoolKey(accessInfo)
        
        return lock.withLock {
            val pool = clientPools.getOrPut(poolKey) {
                Log.i(TAG, "创建新的客户端池: $poolKey")
                ClientPool(accessInfo)
            }
            
            val client = pool.borrowClient()
            
            // 🔧 修复：添加健康检查和自动恢复机制
            if (client == null) {
                Log.w(TAG, "⚠️ 池化客户端获取失败: $poolKey")
                
                // 检查池的健康状态
                if (!isPoolHealthy(pool)) {
                    Log.w(TAG, "🌡️ 检测到不健康的客户端池，尝试重置: $poolKey")
                    
                    // 关闭并移除不健康的池
                    pool.close()
                    clientPools.remove(poolKey)
                    
                    // 创建新的池并尝试再次获取客户端
                    val newPool = ClientPool(accessInfo)
                    clientPools[poolKey] = newPool
                    
                    val recoveredClient = newPool.borrowClient()
                    if (recoveredClient != null) {
                        Log.i(TAG, "✅ 池重置成功，获取到新客户端: $poolKey")
                        return recoveredClient
                    } else {
                        Log.e(TAG, "❌ 池重置后仍无法获取客户端: $poolKey")
                    }
                } else {
                    Log.d(TAG, "📊 池健康状态正常，但暂无可用客户端: $poolKey")
                }
            }
            
            client
        }
    }
    
    /**
     * 检查客户端池是否健康（新增）
     */
    private fun isPoolHealthy(pool: ClientPool): Boolean {
        val stats = pool.getStats()
        
        // 检查错误率
        val totalOperations = poolStats.borrowCount.get()
        val errorRate = if (totalOperations > 0) {
            (poolStats.createErrorCount.get() + poolStats.createTimeoutCount.get()).toDouble() / totalOperations
        } else {
            0.0
        }
        
        val isHealthy = when {
            // 如果错误率超过50%，认为不健康
            errorRate > 0.5 -> {
                Log.w(TAG, "⚠️ 池不健康: 错误率过高 ($errorRate)")
                false
            }
            // 如果池中没有任何客户端且创建错误超过10次
            stats.totalClients == 0 && poolStats.createErrorCount.get() > 10 -> {
                Log.w(TAG, "⚠️ 池不健康: 无可用客户端且创建错误过多")
                false
            }
            // 如果池长时间未被访问（超过1小时）且没有客户端
            System.currentTimeMillis() - stats.lastAccessTime > 3600_000L && stats.totalClients == 0 -> {
                Log.w(TAG, "⚠️ 池不健康: 长时间未使用且无客户端")
                false
            }
            else -> {
                Log.d(TAG, "📊 池健康状态正常: errorRate=$errorRate, clients=${stats.totalClients}")
                true
            }
        }
        
        return isHealthy
    }
    
    /**
     * 重置客户端池
     */
    fun resetPool() {
        lock.withLock {
            Log.w(TAG, "🔄 重置客户端池: pools=${clientPools.size}")
            
            // 关闭所有池
            clientPools.values.forEach { it.close() }
            clientPools.clear()
            
            // 重置统计
            poolStats.createErrorCount.set(0)
            poolStats.createTimeoutCount.set(0)
            
            Log.i(TAG, "✅ 客户端池重置完成")
        }
    }
    
    /**
     * 等待客户端池恢复健康
     */
    fun waitForHealthy(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isHealthy()) {
                return true
            }
            
            try {
                Thread.sleep(100) // 等待100ms
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        
        return false
    }
    
    /**
     * 归还COS客户端
     */
    fun returnClient(accessInfo: CosAccessInfo, client: org.thoughtcrime.securesms.cos.CosClient) {
        val poolKey = getPoolKey(accessInfo)
        
        lock.withLock {
            val pool = clientPools[poolKey]
            if (pool != null) {
                pool.returnClient(client)
            } else {
                Log.w(TAG, "找不到对应的客户端池: $poolKey")
                closeClient(client)
            }
        }
    }
    
    /**
     * 生成池键
     */
    private fun getPoolKey(accessInfo: CosAccessInfo): String {
        return "${accessInfo.provider}-${accessInfo.region}-${accessInfo.bucketName}"
    }
    
    /**
     * 安全关闭客户端
     */
    private fun closeClient(client: org.thoughtcrime.securesms.cos.CosClient) {
        try {
            // COS客户端通常不需要显式关闭，但可以在这里添加清理逻辑
            Log.d(TAG, "关闭COS客户端")
        } catch (e: Exception) {
            Log.w(TAG, "关闭COS客户端时出错", e)
        }
    }
    
    /**
     * 定期清理过期客户端
     */
    fun performMaintenance() {
        lock.withLock {
            Log.d(TAG, "🧹 执行客户端池维护")
            
            val iterator = clientPools.iterator()
            while (iterator.hasNext()) {
                val (poolKey, pool) = iterator.next()
                
                // 清理池内过期客户端
                pool.cleanup()
                
                // 如果池长时间未使用且为空，移除池
                val now = System.currentTimeMillis()
                if (now - pool.lastAccessTime > IDLE_TIMEOUT_MS * 2 && 
                    pool.getStats().totalClients == 0) {
                    pool.close()
                    iterator.remove()
                    Log.d(TAG, "移除未使用的客户端池: $poolKey")
                }
            }
        }
    }
    
    /**
     * 获取所有池的统计信息
     */
    fun getAllPoolStats(): List<PoolStats> {
        return lock.withLock {
            clientPools.values.map { it.getStats() }
        }
    }
    
    /**
     * 获取全局统计信息
     */
    fun getGlobalStats(): GlobalStats {
        return GlobalStats(
            totalPools = clientPools.size,
            borrowCount = poolStats.borrowCount.get().toLong(),
            returnCount = poolStats.returnCount.get().toLong(),
            createTimeoutCount = poolStats.createTimeoutCount.get().toLong(),
            createErrorCount = poolStats.createErrorCount.get().toLong()
        )
    }
    
    /**
     * 针对特定凭证的健康检查和恢复（新增）
     */
    fun performHealthCheckAndRecover(accessInfo: CosAccessInfo): Boolean {
        val poolKey = getPoolKey(accessInfo)
        
        return lock.withLock {
            val pool = clientPools[poolKey]
            if (pool == null) {
                Log.d(TAG, "🔍 未找到池，创建新池: $poolKey")
                val newPool = ClientPool(accessInfo)
                clientPools[poolKey] = newPool
                return true
            }
            
            if (!isPoolHealthy(pool)) {
                Log.w(TAG, "🔧 检测到不健康的池，执行恢复: $poolKey")
                
                // 关闭不健康的池
                pool.close()
                clientPools.remove(poolKey)
                
                // 创建新池
                val newPool = ClientPool(accessInfo)
                clientPools[poolKey] = newPool
                
                // 重置部分统计信息
                poolStats.createErrorCount.set(0)
                poolStats.createTimeoutCount.set(0)
                
                Log.i(TAG, "✅ 池恢复完成: $poolKey")
                return true
            } else {
                Log.d(TAG, "📊 池健康状态正常: $poolKey")
                return true
            }
        }
    }
    
    /**
     * 检查所有客户端池是否健康（公共接口）
     */
    fun isHealthy(): Boolean {
        return lock.withLock {
            // 检查是否有可用的客户端池
            if (clientPools.isEmpty()) {
                return true // 空池也算健康
            }
            
            // 检查是否有过多创建错误
            val errorRate = if (poolStats.borrowCount.get() > 0) {
                poolStats.createErrorCount.get().toDouble() / poolStats.borrowCount.get()
            } else {
                0.0
            }
            
            errorRate < 0.5 // 错误率低于50%认为健康
        }
    }
    
    /**
     * 池统计信息
     */
    data class PoolStats(
        val poolKey: String,
        val totalClients: Int,
        val activeClients: Int,
        val availableClients: Int,
        val lastAccessTime: Long
    )
    
    /**
     * 全局统计信息
     */
    data class GlobalStats(
        val totalPools: Int,
        val borrowCount: Long,
        val returnCount: Long,
        val createTimeoutCount: Long,
        val createErrorCount: Long
    )
    
    /**
     * 池统计信息收集器
     */
    private class PoolStatistics {
        val borrowCount = AtomicInteger(0)
        val returnCount = AtomicInteger(0)
        val createTimeoutCount = AtomicInteger(0)
        val createErrorCount = AtomicInteger(0)
        
        fun incrementBorrowCount(): Long = borrowCount.incrementAndGet().toLong()
        fun incrementReturnCount(): Long = returnCount.incrementAndGet().toLong()
        fun incrementCreateTimeoutCount(): Long = createTimeoutCount.incrementAndGet().toLong()
        fun incrementCreateErrorCount(): Long = createErrorCount.incrementAndGet().toLong()
    }
}