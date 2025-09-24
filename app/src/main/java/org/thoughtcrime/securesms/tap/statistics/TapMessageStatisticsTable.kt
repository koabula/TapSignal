package org.thoughtcrime.securesms.tap.statistics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * TAP消息统计数据库表
 * 持久化存储TAP传输层的消息统计信息
 */
class TapMessageStatisticsTable private constructor(
    context: Context, 
    databaseHelper: SignalDatabase
) : DatabaseTable(context, databaseHelper) {
    
    companion object {
        private val TAG = Log.tag(TapMessageStatisticsTable::class.java)
        
        @Volatile
        private var INSTANCE: TapMessageStatisticsTable? = null
        
        fun getInstance(context: Context): TapMessageStatisticsTable {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageStatisticsTable(
                    context.applicationContext, 
                    SignalDatabase.instance ?: throw IllegalStateException("SignalDatabase未初始化")
                ).also { INSTANCE = it }
            }
        }
        
        // 表名和字段定义
        const val TABLE_NAME = "tap_message_statistics"
        
        private const val ID = "_id"
        private const val MESSAGE_ID = "message_id"
        private const val TIMESTAMP = "timestamp"
        private const val SENDER_ID = "sender_id"
        private const val RECIPIENT_ID = "recipient_id"
        private const val MESSAGE_TYPE = "message_type"
        private const val MESSAGE_SIZE_BYTES = "message_size_bytes"
        private const val ATTACHMENT_COUNT = "attachment_count"
        private const val TOTAL_ATTACHMENT_SIZE_BYTES = "total_attachment_size_bytes"
        private const val PROCESSING_STATUS = "processing_status"
        private const val ERROR_MESSAGE = "error_message"
        private const val CREATED_AT = "created_at"
        private const val UPDATED_AT = "updated_at"
        
        // 创建表的SQL
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $MESSAGE_ID TEXT NOT NULL UNIQUE,
                $TIMESTAMP INTEGER NOT NULL,
                $SENDER_ID TEXT NOT NULL,
                $RECIPIENT_ID TEXT NOT NULL,
                $MESSAGE_TYPE TEXT NOT NULL,
                $MESSAGE_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $ATTACHMENT_COUNT INTEGER NOT NULL DEFAULT 0,
                $TOTAL_ATTACHMENT_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $PROCESSING_STATUS TEXT NOT NULL DEFAULT 'received',
                $ERROR_MESSAGE TEXT DEFAULT NULL,
                $CREATED_AT INTEGER NOT NULL,
                $UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """
        
        // 创建索引的SQL
        val CREATE_INDEXES = arrayOf(
            "CREATE INDEX IF NOT EXISTS tap_statistics_message_id_idx ON $TABLE_NAME ($MESSAGE_ID)",
            "CREATE INDEX IF NOT EXISTS tap_statistics_timestamp_idx ON $TABLE_NAME ($TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS tap_statistics_sender_idx ON $TABLE_NAME ($SENDER_ID)",
            "CREATE INDEX IF NOT EXISTS tap_statistics_type_idx ON $TABLE_NAME ($MESSAGE_TYPE)",
            "CREATE INDEX IF NOT EXISTS tap_statistics_status_idx ON $TABLE_NAME ($PROCESSING_STATUS)",
            "CREATE INDEX IF NOT EXISTS tap_statistics_created_at_idx ON $TABLE_NAME ($CREATED_AT)"
        )
    }
    
    /**
     * 插入统计记录
     */
    @WorkerThread
    fun insertStatisticsRecord(record: MessageStatisticsRecord) {
        try {
            val values = ContentValues().apply {
                put(MESSAGE_ID, record.messageId)
                put(TIMESTAMP, record.timestamp)
                put(SENDER_ID, record.senderId)
                put(RECIPIENT_ID, record.recipientId)
                put(MESSAGE_TYPE, record.messageType)
                put(MESSAGE_SIZE_BYTES, record.messageSizeBytes)
                put(ATTACHMENT_COUNT, record.attachmentCount)
                put(TOTAL_ATTACHMENT_SIZE_BYTES, record.totalAttachmentSizeBytes)
                put(PROCESSING_STATUS, record.processingStatus)
                put(ERROR_MESSAGE, record.errorMessage)
                put(CREATED_AT, record.createdAt)
                put(UPDATED_AT, System.currentTimeMillis())
            }
            
            // 使用 INSERT OR REPLACE 避免重复插入
            val insertId = writableDatabase.insertWithOnConflict(
                TABLE_NAME, 
                null, 
                values, 
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            
            if (insertId >= 0) {
                Log.d(TAG, "统计记录插入成功: messageId=${record.messageId}, id=$insertId")
            } else {
                Log.w(TAG, "统计记录插入失败: messageId=${record.messageId}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "插入统计记录异常: messageId=${record.messageId}", e)
        }
    }
    
    /**
     * 记录处理结果
     */
    @WorkerThread
    fun recordProcessingResult(messageId: String, success: Boolean, errorMessage: String?) {
        try {
            val status = if (success) "success" else "failed"
            val values = ContentValues().apply {
                put(PROCESSING_STATUS, status)
                put(ERROR_MESSAGE, errorMessage)
                put(UPDATED_AT, System.currentTimeMillis())
            }
            
            val updatedRows = writableDatabase.update(
                TABLE_NAME,
                values,
                "$MESSAGE_ID = ?",
                arrayOf(messageId)
            )
            
            if (updatedRows > 0) {
                Log.d(TAG, "处理结果记录成功: messageId=$messageId, status=$status")
            } else {
                Log.w(TAG, "处理结果记录失败，记录不存在: messageId=$messageId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "记录处理结果异常: messageId=$messageId", e)
        }
    }
    
    /**
     * 获取最近的统计记录
     */
    @WorkerThread
    @NonNull
    fun getRecentStatistics(timeRangeMs: Long): List<MessageStatisticsRecord> {
        val records = mutableListOf<MessageStatisticsRecord>()
        val cutoffTime = System.currentTimeMillis() - timeRangeMs
        
        try {
            readableDatabase.query(
                TABLE_NAME,
                null,
                "$CREATED_AT >= ?",
                arrayOf(cutoffTime.toString()),
                null,
                null,
                "$CREATED_AT DESC",
                "1000" // 限制最多1000条记录
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = readStatisticsRecord(cursor)
                    if (record != null) {
                        records.add(record)
                    }
                }
            }
            
            Log.d(TAG, "获取最近统计记录: ${records.size}条, timeRange=${timeRangeMs}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "获取最近统计记录失败", e)
        }
        
        return records
    }
    
    /**
     * 获取按类型分组的统计
     */
    @WorkerThread
    @NonNull
    fun getStatisticsByType(timeRangeMs: Long): Map<String, TypeStatistics> {
        val statistics = mutableMapOf<String, TypeStatistics>()
        val cutoffTime = System.currentTimeMillis() - timeRangeMs
        
        try {
            val sql = """
                SELECT 
                    $MESSAGE_TYPE,
                    COUNT(*) as message_count,
                    SUM($MESSAGE_SIZE_BYTES) as total_bytes,
                    AVG($MESSAGE_SIZE_BYTES) as avg_bytes,
                    SUM(CASE WHEN $PROCESSING_STATUS = 'success' THEN 1 ELSE 0 END) as success_count,
                    SUM(CASE WHEN $PROCESSING_STATUS = 'failed' THEN 1 ELSE 0 END) as error_count
                FROM $TABLE_NAME 
                WHERE $CREATED_AT >= ? 
                GROUP BY $MESSAGE_TYPE
            """
            
            readableDatabase.rawQuery(sql, arrayOf(cutoffTime.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val messageType = cursor.getString(0)
                    val messageCount = cursor.getLong(1)
                    val totalBytes = cursor.getLong(2)
                    val avgBytes = cursor.getDouble(3)
                    val successCount = cursor.getLong(4)
                    val errorCount = cursor.getLong(5)
                    
                    statistics[messageType] = TypeStatistics(
                        messageType = messageType,
                        messageCount = messageCount,
                        totalBytes = totalBytes,
                        averageBytes = avgBytes,
                        successCount = successCount,
                        errorCount = errorCount
                    )
                }
            }
            
            Log.d(TAG, "获取类型统计: ${statistics.size}种类型")
            
        } catch (e: Exception) {
            Log.e(TAG, "获取类型统计失败", e)
        }
        
        return statistics
    }
    
    /**
     * 获取按发送者分组的统计
     */
    @WorkerThread
    @NonNull
    fun getStatisticsBySender(timeRangeMs: Long): Map<String, SenderStatistics> {
        val statistics = mutableMapOf<String, SenderStatistics>()
        val cutoffTime = System.currentTimeMillis() - timeRangeMs
        
        try {
            val sql = """
                SELECT 
                    $SENDER_ID,
                    COUNT(*) as message_count,
                    SUM($MESSAGE_SIZE_BYTES) as total_bytes,
                    MIN($TIMESTAMP) as first_message_time,
                    MAX($TIMESTAMP) as last_message_time
                FROM $TABLE_NAME 
                WHERE $CREATED_AT >= ? 
                GROUP BY $SENDER_ID
                ORDER BY message_count DESC
                LIMIT 100
            """
            
            readableDatabase.rawQuery(sql, arrayOf(cutoffTime.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val senderId = cursor.getString(0)
                    val messageCount = cursor.getLong(1)
                    val totalBytes = cursor.getLong(2)
                    val firstMessageTime = cursor.getLong(3)
                    val lastMessageTime = cursor.getLong(4)
                    
                    statistics[senderId] = SenderStatistics(
                        senderId = senderId,
                        messageCount = messageCount,
                        totalBytes = totalBytes,
                        firstMessageTime = firstMessageTime,
                        lastMessageTime = lastMessageTime
                    )
                }
            }
            
            Log.d(TAG, "获取发送者统计: ${statistics.size}个发送者")
            
        } catch (e: Exception) {
            Log.e(TAG, "获取发送者统计失败", e)
        }
        
        return statistics
    }
    
    /**
     * 删除过期统计数据
     */
    @WorkerThread
    fun deleteOldStatistics(cutoffTime: Long): Int {
        return try {
            val deletedCount = writableDatabase.delete(
                TABLE_NAME,
                "$CREATED_AT < ?",
                arrayOf(cutoffTime.toString())
            )
            
            Log.i(TAG, "删除过期统计数据: $deletedCount 条记录")
            deletedCount
            
        } catch (e: Exception) {
            Log.e(TAG, "删除过期统计数据失败", e)
            0
        }
    }
    
    /**
     * 获取统计记录总数
     */
    @WorkerThread
    fun getTotalRecordCount(): Long {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    0L
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取总记录数失败", e)
            0L
        }
    }
    
    /**
     * 从Cursor读取统计记录
     */
    @Nullable
    private fun readStatisticsRecord(cursor: Cursor): MessageStatisticsRecord? {
        return try {
            MessageStatisticsRecord(
                messageId = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_ID)),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(TIMESTAMP)),
                senderId = cursor.getString(cursor.getColumnIndexOrThrow(SENDER_ID)),
                recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID)),
                messageType = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_TYPE)),
                messageSizeBytes = cursor.getInt(cursor.getColumnIndexOrThrow(MESSAGE_SIZE_BYTES)),
                attachmentCount = cursor.getInt(cursor.getColumnIndexOrThrow(ATTACHMENT_COUNT)),
                totalAttachmentSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(TOTAL_ATTACHMENT_SIZE_BYTES)),
                processingStatus = cursor.getString(cursor.getColumnIndexOrThrow(PROCESSING_STATUS)),
                errorMessage = cursor.getString(cursor.getColumnIndexOrThrow(ERROR_MESSAGE)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CREATED_AT))
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取统计记录失败", e)
            null
        }
    }
}

/**
 * 按类型分组的统计信息
 */
data class TypeStatistics(
    val messageType: String,
    val messageCount: Long,
    val totalBytes: Long,
    val averageBytes: Double,
    val successCount: Long,
    val errorCount: Long
) {
    val successRate: Double
        get() = if (messageCount > 0) successCount.toDouble() / messageCount else 0.0
}

/**
 * 按发送者分组的统计信息
 */
data class SenderStatistics(
    val senderId: String,
    val messageCount: Long,
    val totalBytes: Long,
    val firstMessageTime: Long,
    val lastMessageTime: Long
) {
    val averageBytes: Double
        get() = if (messageCount > 0) totalBytes.toDouble() / messageCount else 0.0
        
    val activityDuration: Long
        get() = lastMessageTime - firstMessageTime
} 