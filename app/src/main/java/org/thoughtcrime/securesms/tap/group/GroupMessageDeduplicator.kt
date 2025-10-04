package org.thoughtcrime.securesms.tap.group

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 群组消息去重器
 * 
 * 专门用于群组 V2 模式的消息去重，处理同一消息可能从多个成员处获取的情况
 * 使用 LRU 缓存 + 数据库持久化策略
 */
class GroupMessageDeduplicator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupMessageDeduplicator::class.java)
        
        // 缓存配置
        private const val MAX_CACHE_SIZE = 5000 // 群组消息可能更频繁
        private const val CLEANUP_INTERVAL_MS = 30 * 60 * 1000L // 30分钟
        private const val RETENTION_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L // 7天
        
        @Volatile
        private var INSTANCE: GroupMessageDeduplicator? = null
        
        fun getInstance(context: Context): GroupMessageDeduplicator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupMessageDeduplicator(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建 GroupMessageDeduplicator 实例")
                }
            }
        }
        
        /**
         * 重置单例实例（仅用于测试）
         */
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
                Log.d(TAG, "重置 GroupMessageDeduplicator 实例")
            }
        }
    }
    
    // LRU 缓存实现：使用 LinkedHashMap 实现 LRU 策略
    private val messageCache = object : LinkedHashMap<String, GroupMessageRecord>(
        MAX_CACHE_SIZE,
        0.75f,
        true // accessOrder = true 实现 LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroupMessageRecord>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    
    private val cacheLock = ReentrantReadWriteLock()
    private var lastCleanupTime = System.currentTimeMillis()
    
    // 统计信息
    private var cacheHitCount = 0L
    private var cacheMissCount = 0L
    private var dbHitCount = 0L
    
    /**
     * 检查群组消息是否为重复消息
     * 
     * 使用消息的唯一标识进行去重：
     * - messageId: 消息唯一标识
     * - senderAci: 发送者 ACI
     * - groupId: 群组 ID
     * - timestamp: 消息时间戳
     * 
     * @param messageId 消息 ID
     * @param senderAci 发送者 ACI
     * @param groupId 群组 ID
     * @param timestamp 消息时间戳
     * @return true 如果是重复消息，false 如果是新消息
     */
    fun isDuplicate(
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long
    ): Boolean {
        val duplicationKey = generateDuplicationKey(messageId, senderAci, groupId, timestamp)
        
        return cacheLock.read {
            try {
                // 1. 检查 LRU 缓存
                if (messageCache.containsKey(duplicationKey)) {
                    cacheHitCount++
                    Log.d(TAG, "LRU 缓存命中: key=$duplicationKey, hitRate=${getCacheHitRate()}")
                    return@read true
                }
                
                cacheMissCount++
                
                // 2. 检查数据库
                val existsInDb = checkDatabaseForDuplicate(
                    duplicationKey, messageId, senderAci, groupId, timestamp
                )
                
                if (existsInDb) {
                    dbHitCount++
                    // 添加到缓存以加速后续检查
                    addToCache(duplicationKey, messageId, senderAci, groupId, timestamp)
                    Log.d(TAG, "数据库检测到重复消息: key=$duplicationKey")
                    return@read true
                }
                
                Log.v(TAG, "新消息: key=$duplicationKey")
                false
                
            } catch (e: Exception) {
                Log.e(TAG, "检查消息重复性失败: $duplicationKey", e)
                // 出错时保守处理，假设不重复以避免丢失消息
                false
            }
        }
    }
    
    /**
     * 标记消息为已处理
     * 
     * @param messageId 消息 ID
     * @param senderAci 发送者 ACI
     * @param groupId 群组 ID
     * @param timestamp 消息时间戳
     * @param pollingMemberAci 轮询获取该消息的成员 ACI（用于追踪）
     */
    fun markAsProcessed(
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long,
        pollingMemberAci: String? = null
    ) {
        val duplicationKey = generateDuplicationKey(messageId, senderAci, groupId, timestamp)
        
        cacheLock.write {
            try {
                // 1. 添加到 LRU 缓存
                addToCache(duplicationKey, messageId, senderAci, groupId, timestamp, pollingMemberAci)
                
                // 2. 持久化到数据库
                saveToDatabase(duplicationKey, messageId, senderAci, groupId, timestamp, pollingMemberAci)
                
                // 3. 定期清理
                performPeriodicCleanup()
                
                Log.d(TAG, "群组消息标记为已处理: key=$duplicationKey")
                
            } catch (e: Exception) {
                Log.e(TAG, "标记群组消息已处理失败: $duplicationKey", e)
            }
        }
    }
    
    /**
     * 生成去重键
     * 
     * 格式: groupId:messageId:senderAci:timestamp
     * 这样可以唯一标识一条群组消息
     */
    private fun generateDuplicationKey(
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long
    ): String {
        return "$groupId:$messageId:$senderAci:$timestamp"
    }
    
    /**
     * 添加到缓存
     */
    private fun addToCache(
        duplicationKey: String,
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long,
        pollingMemberAci: String? = null
    ) {
        val record = GroupMessageRecord(
            messageId = messageId,
            senderAci = senderAci,
            groupId = groupId,
            timestamp = timestamp,
            processedAt = System.currentTimeMillis(),
            pollingMemberAci = pollingMemberAci
        )
        
        messageCache[duplicationKey] = record
    }
    
    /**
     * 检查数据库中是否存在重复消息
     */
    private fun checkDatabaseForDuplicate(
        duplicationKey: String,
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long
    ): Boolean {
        return try {
            val database = SignalDatabase.rawDatabase
            database.rawQuery(
                """
                SELECT COUNT(*) FROM transport_group_processed_messages 
                WHERE duplication_key = ? 
                   OR (message_id = ? AND sender_aci = ? AND group_id = ? AND timestamp = ?)
                """,
                arrayOf(duplicationKey, messageId, senderAci, groupId, timestamp.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(0)
                    count > 0
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查数据库重复消息失败", e)
            false
        }
    }
    
    /**
     * 保存到数据库
     */
    private fun saveToDatabase(
        duplicationKey: String,
        messageId: String,
        senderAci: String,
        groupId: String,
        timestamp: Long,
        pollingMemberAci: String?
    ) {
        try {
            val database = SignalDatabase.rawDatabase
            val currentTime = System.currentTimeMillis()
            
            database.execSQL(
                """
                INSERT OR REPLACE INTO transport_group_processed_messages 
                (duplication_key, message_id, sender_aci, group_id, timestamp, 
                 processed_at, polling_member_aci, created_at) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                arrayOf(
                    duplicationKey, messageId, senderAci, groupId, timestamp,
                    currentTime, pollingMemberAci, currentTime
                )
            )
            
            Log.v(TAG, "群组消息去重记录已保存: key=$duplicationKey")
            
        } catch (e: Exception) {
            Log.e(TAG, "保存群组去重记录失败: $duplicationKey", e)
        }
    }
    
    /**
     * 定期清理数据库中的过期记录
     */
    private fun performPeriodicCleanup() {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            try {
                val database = SignalDatabase.rawDatabase
                val expiredTime = currentTime - RETENTION_PERIOD_MS
                
                database.execSQL(
                    "DELETE FROM transport_group_processed_messages WHERE processed_at < ?",
                    arrayOf(expiredTime.toString())
                )
                
                lastCleanupTime = currentTime
                
                Log.d(TAG, "定期清理群组消息去重记录完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "定期清理数据库失败", e)
            }
        }
    }
    
    /**
     * 获取缓存命中率
     */
    private fun getCacheHitRate(): Double {
        val totalAccess = cacheHitCount + cacheMissCount
        return if (totalAccess > 0) {
            cacheHitCount.toDouble() / totalAccess
        } else {
            0.0
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): GroupDuplicationStatistics {
        return cacheLock.read {
            try {
                val cacheSize = messageCache.size
                val database = SignalDatabase.rawDatabase
                
                val dbCount = database.rawQuery(
                    "SELECT COUNT(*) FROM transport_group_processed_messages",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                
                GroupDuplicationStatistics(
                    cacheSize = cacheSize,
                    databaseSize = dbCount,
                    cacheHitCount = cacheHitCount,
                    cacheMissCount = cacheMissCount,
                    dbHitCount = dbHitCount,
                    cacheHitRate = getCacheHitRate(),
                    lastCleanupTime = lastCleanupTime
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "获取统计信息失败", e)
                GroupDuplicationStatistics(
                    cacheSize = messageCache.size,
                    databaseSize = 0,
                    cacheHitCount = cacheHitCount,
                    cacheMissCount = cacheMissCount,
                    dbHitCount = dbHitCount,
                    cacheHitRate = getCacheHitRate(),
                    lastCleanupTime = lastCleanupTime
                )
            }
        }
    }
    
    /**
     * 清空所有去重记录（仅用于测试）
     */
    fun clearAll() {
        cacheLock.write {
            try {
                messageCache.clear()
                
                val database = SignalDatabase.rawDatabase
                database.execSQL("DELETE FROM transport_group_processed_messages")
                
                // 重置统计
                cacheHitCount = 0
                cacheMissCount = 0
                dbHitCount = 0
                
                Log.i(TAG, "已清空所有群组去重记录")
                
            } catch (e: Exception) {
                Log.e(TAG, "清空群组去重记录失败", e)
            }
        }
    }
    
    /**
     * 群组消息记录数据类
     */
    private data class GroupMessageRecord(
        val messageId: String,
        val senderAci: String,
        val groupId: String,
        val timestamp: Long,
        val processedAt: Long,
        val pollingMemberAci: String? = null
    )
}

/**
 * 群组去重统计信息
 */
data class GroupDuplicationStatistics(
    /** 缓存大小 */
    val cacheSize: Int,
    
    /** 数据库大小 */
    val databaseSize: Int,
    
    /** 缓存命中次数 */
    val cacheHitCount: Long,
    
    /** 缓存未命中次数 */
    val cacheMissCount: Long,
    
    /** 数据库命中次数 */
    val dbHitCount: Long,
    
    /** 缓存命中率 */
    val cacheHitRate: Double,
    
    /** 最后清理时间 */
    val lastCleanupTime: Long
) {
    /**
     * 获取总访问次数
     */
    fun getTotalAccess(): Long = cacheHitCount + cacheMissCount
    
    /**
     * 获取数据库查询率
     */
    fun getDbQueryRate(): Double {
        val totalAccess = getTotalAccess()
        return if (totalAccess > 0) {
            cacheMissCount.toDouble() / totalAccess
        } else {
            0.0
        }
    }
}

