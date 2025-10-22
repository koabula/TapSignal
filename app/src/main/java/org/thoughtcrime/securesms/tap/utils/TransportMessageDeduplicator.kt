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
                INSTANCE ?: TransportMessageDeduplicator(context.applicationContext).also { 
                    INSTANCE = it
                    it.ensureTableExists()
                }
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
     * 确保数据库表存在并且结构正确
     * 在实例初始化时调用，提供自愈能力
     */
    private fun ensureTableExists() {
        try {
            val database = SignalDatabase.rawDatabase
            
            // 检查表是否存在
            val cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='transport_processed_messages'",
                null
            )
            
            val tableExists = cursor.use { it.moveToFirst() }
            
            if (!tableExists) {
                Log.w(TAG, "检测到transport_processed_messages表不存在，正在创建...")
                createTable(database)
                Log.i(TAG, "transport_processed_messages表创建成功")
            } else {
                // 表存在，验证列是否完整
                Log.d(TAG, "transport_processed_messages表已存在，验证结构...")
                verifyTableSchema(database)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "检查/创建去重表失败，去重功能可能受影响", e)
        }
    }
    
    /**
     * 创建去重表及其索引
     */
    private fun createTable(database: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        // 创建主表
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS transport_processed_messages (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "duplication_key TEXT UNIQUE NOT NULL, " +
            "message_id TEXT NOT NULL, " +
            "recipient_id TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL, " +
            "processed_at INTEGER NOT NULL, " +
            "created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)" +
            ")"
        )
        
        // 创建索引
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS transport_processed_messages_key_idx " +
            "ON transport_processed_messages (duplication_key)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS transport_processed_messages_timestamp_idx " +
            "ON transport_processed_messages (processed_at)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS transport_processed_messages_recipient_idx " +
            "ON transport_processed_messages (recipient_id)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS transport_processed_messages_message_idx " +
            "ON transport_processed_messages (message_id)"
        )
        
        Log.i(TAG, "transport_processed_messages表及索引创建完成")
    }
    
    /**
     * 验证表结构是否完整
     * 如果表结构不完整，会重建表
     */
    private fun verifyTableSchema(database: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        try {
            // 验证必需列是否存在
            val requiredColumns = listOf(
                "duplication_key", 
                "message_id", 
                "recipient_id", 
                "timestamp", 
                "processed_at", 
                "created_at"
            )
            
            val cursor = database.rawQuery(
                "PRAGMA table_info(transport_processed_messages)", 
                null
            )
            
            val existingColumns = mutableListOf<String>()
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                if (nameIndex >= 0) {
                    while (it.moveToNext()) {
                        existingColumns.add(it.getString(nameIndex))
                    }
                }
            }
            
            val missingColumns = requiredColumns.filter { it !in existingColumns }
            
            if (missingColumns.isNotEmpty()) {
                Log.w(TAG, "transport_processed_messages表结构不完整，缺少列: $missingColumns")
                Log.i(TAG, "正在重建表以修复结构...")
                
                // 备份现有数据
                val hasData = database.rawQuery(
                    "SELECT COUNT(*) FROM transport_processed_messages", 
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) > 0 else false
                }
                
                if (hasData) {
                    Log.w(TAG, "表中存在数据，尝试迁移...")
                    // 创建临时表用于数据迁移
                    database.execSQL("ALTER TABLE transport_processed_messages RENAME TO transport_processed_messages_backup")
                    
                    // 创建新表
                    createTable(database)
                    
                    // 尝试迁移数据（只迁移存在的列）
                    val columnsToMigrate = requiredColumns.filter { it in existingColumns }.joinToString(", ")
                    if (columnsToMigrate.isNotEmpty()) {
                        try {
                            database.execSQL(
                                "INSERT INTO transport_processed_messages ($columnsToMigrate) " +
                                "SELECT $columnsToMigrate FROM transport_processed_messages_backup"
                            )
                            Log.i(TAG, "数据迁移成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "数据迁移失败，旧数据将丢失", e)
                        }
                    }
                    
                    // 删除备份表
                    database.execSQL("DROP TABLE IF EXISTS transport_processed_messages_backup")
                } else {
                    // 没有数据，直接删除重建
                    Log.i(TAG, "表为空，直接重建...")
                    database.execSQL("DROP TABLE IF EXISTS transport_processed_messages")
                    createTable(database)
                }
                
                Log.i(TAG, "表结构修复完成")
            } else {
                Log.d(TAG, "表结构验证通过，所有必需列都存在")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "验证表结构失败", e)
            // 如果验证失败，尝试完全重建
            try {
                Log.w(TAG, "尝试完全重建表...")
                database.execSQL("DROP TABLE IF EXISTS transport_processed_messages")
                database.execSQL("DROP TABLE IF EXISTS transport_processed_messages_backup")
                createTable(database)
                Log.i(TAG, "表重建成功")
            } catch (rebuildException: Exception) {
                Log.e(TAG, "表重建也失败，去重功能将受影响", rebuildException)
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