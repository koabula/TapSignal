package org.thoughtcrime.securesms.tap.utils

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 传输消息去重器
 * 
 * 负责检测和防止重复处理相同的传输层消息
 * 使用内存缓存 + 数据库持久化的双重策略确保去重效果
 */
class TransportMessageDeduplicator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportMessageDeduplicator::class.java)
        
        // 内存缓存相关常量
        private const val MAX_CACHE_SIZE = 10000
        private const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L // 1小时
        private const val RETENTION_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L // 7天
        
        @Volatile
        private var INSTANCE: TransportMessageDeduplicator? = null
        
        fun getInstance(context: Context): TransportMessageDeduplicator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportMessageDeduplicator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 内存缓存：快速去重检查
    private val messageCache = ConcurrentHashMap<String, MessageRecord>()
    private val cacheLock = ReentrantReadWriteLock()
    private var lastCleanupTime = System.currentTimeMillis()
    
    /**
     * 检查消息是否为重复消息
     * 
     * @param messageId 消息ID
     * @param senderId 发送者ID
     * @param timestamp 消息时间戳
     * @return true如果是重复消息，false如果是新消息
     */
    fun isDuplicate(messageId: String, senderId: String, timestamp: Long): Boolean {
        val duplicationKey = generateDuplicationKey(messageId, senderId, timestamp)
        
        return cacheLock.read {
            try {
                // 1. 首先检查内存缓存
                if (messageCache.containsKey(duplicationKey)) {
                    Log.d(TAG, "内存缓存命中，消息重复: $duplicationKey")
                    return@read true
                }
                
                // 2. 检查数据库
                val existsInDb = checkDatabaseForDuplicate(duplicationKey, messageId, senderId, timestamp)
                if (existsInDb) {
                    // 添加到内存缓存以加速后续检查
                    addToCache(duplicationKey, messageId, senderId, timestamp)
                    Log.d(TAG, "数据库检测到重复消息: $duplicationKey")
                    return@read true
                }
                
                Log.d(TAG, "新消息，无重复: $duplicationKey")
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
     * @param messageId 消息ID
     * @param senderId 发送者ID
     * @param timestamp 消息时间戳
     */
    fun markAsProcessed(messageId: String, senderId: String, timestamp: Long) {
        val duplicationKey = generateDuplicationKey(messageId, senderId, timestamp)
        
        cacheLock.write {
            try {
                // 1. 添加到内存缓存
                addToCache(duplicationKey, messageId, senderId, timestamp)
                
                // 2. 持久化到数据库
                saveToDatabase(duplicationKey, messageId, senderId, timestamp)
                
                // 3. 定期清理
                performPeriodicCleanup()
                
                Log.d(TAG, "消息标记为已处理: $duplicationKey")
                
            } catch (e: Exception) {
                Log.e(TAG, "标记消息已处理失败: $duplicationKey", e)
            }
        }
    }
    
    /**
     * 生成去重键
     */
    private fun generateDuplicationKey(messageId: String, senderId: String, timestamp: Long): String {
        return "$messageId:$senderId:$timestamp"
    }
    
    /**
     * 添加到内存缓存
     */
    private fun addToCache(duplicationKey: String, messageId: String, senderId: String, timestamp: Long) {
        // 检查缓存大小，必要时清理
        if (messageCache.size >= MAX_CACHE_SIZE) {
            cleanupOldCacheEntries()
        }
        
        val record = MessageRecord(
            messageId = messageId,
            senderId = senderId,
            timestamp = timestamp,
            processedAt = System.currentTimeMillis()
        )
        
        messageCache[duplicationKey] = record
    }
    
    /**
     * 检查数据库中是否存在重复消息
     */
    private fun checkDatabaseForDuplicate(
        duplicationKey: String,
        messageId: String,
        senderId: String,
        timestamp: Long
    ): Boolean {
        return try {
            val database = SignalDatabase.rawDatabase
            database.rawQuery(
                """
                SELECT COUNT(*) FROM transport_processed_messages 
                WHERE duplication_key = ? OR (message_id = ? AND recipient_id = ? AND timestamp = ?)
                """,
                arrayOf(duplicationKey, messageId, senderId, timestamp.toString())
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
    private fun saveToDatabase(duplicationKey: String, messageId: String, senderId: String, timestamp: Long) {
        try {
            val database = SignalDatabase.rawDatabase
            val currentTime = System.currentTimeMillis()
            
            database.execSQL(
                """
                INSERT OR REPLACE INTO transport_processed_messages 
                (duplication_key, message_id, recipient_id, timestamp, processed_at, created_at) 
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                arrayOf(duplicationKey, messageId, senderId, timestamp, currentTime, currentTime)
            )
            
            Log.d(TAG, "消息去重记录已保存到数据库: $duplicationKey")
            
        } catch (e: Exception) {
            Log.e(TAG, "保存去重记录到数据库失败: $duplicationKey", e)
        }
    }
    
    /**
     * 清理过期的缓存条目
     */
    private fun cleanupOldCacheEntries() {
        try {
            val currentTime = System.currentTimeMillis()
            val expiredKeys = messageCache.entries.filter { (_, record) ->
                currentTime - record.processedAt > RETENTION_PERIOD_MS
            }.map { it.key }
            
            expiredKeys.forEach { key ->
                messageCache.remove(key)
            }
            
            Log.d(TAG, "清理过期缓存条目: ${expiredKeys.size}个")
            
            // 如果清理后还是太大，按时间清理最老的条目
            if (messageCache.size > MAX_CACHE_SIZE * 0.8) {
                val sortedEntries = messageCache.entries.sortedBy { it.value.processedAt }
                val toRemove = sortedEntries.take(messageCache.size - (MAX_CACHE_SIZE / 2))
                
                toRemove.forEach { (key, _) ->
                    messageCache.remove(key)
                }
                
                Log.d(TAG, "额外清理最老缓存条目: ${toRemove.size}个")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "清理缓存条目失败", e)
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
                
                val deletedRows = database.execSQL(
                    "DELETE FROM transport_processed_messages WHERE processed_at < ?",
                    arrayOf(expiredTime.toString())
                )
                
                lastCleanupTime = currentTime
                
                Log.d(TAG, "定期清理数据库过期记录完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "定期清理数据库失败", e)
            }
        }
    }
    
    /**
     * 获取去重统计信息
     */
    fun getStatistics(): DuplicationStatistics {
        return cacheLock.read {
            try {
                val cacheSize = messageCache.size
                val database = SignalDatabase.rawDatabase
                
                val dbCount = database.rawQuery(
                    "SELECT COUNT(*) FROM transport_processed_messages",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                
                DuplicationStatistics(
                    cacheSize = cacheSize,
                    databaseSize = dbCount,
                    lastCleanupTime = lastCleanupTime
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "获取去重统计失败", e)
                DuplicationStatistics(0, 0, lastCleanupTime)
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
                database.execSQL("DELETE FROM transport_processed_messages")
                
                Log.i(TAG, "已清空所有去重记录")
                
            } catch (e: Exception) {
                Log.e(TAG, "清空去重记录失败", e)
            }
        }
    }
    
    /**
     * 消息记录数据类
     */
    private data class MessageRecord(
        val messageId: String,
        val senderId: String,
        val timestamp: Long,
        val processedAt: Long
    )
    
    /**
     * 去重统计信息
     */
    data class DuplicationStatistics(
        val cacheSize: Int,
        val databaseSize: Int,
        val lastCleanupTime: Long
    )
} 