package org.thoughtcrime.securesms.tap.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportChannel
import org.thoughtcrime.securesms.tap.TransportChannelStatus
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.TransportError
import org.thoughtcrime.securesms.tap.utils.TransportMetadataFactory
import org.thoughtcrime.securesms.util.JsonUtils
import java.util.concurrent.atomic.AtomicReference

/**
 * TAP独立数据库的传输通道表
 * 使用专用数据库文件，避免与主数据库竞争
 */
class TapTransportChannelTable private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapTransportChannelTable::class.java)
        
        @Volatile
        private var INSTANCE: TapTransportChannelTable? = null
        
        fun getInstance(context: Context): TapTransportChannelTable {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapTransportChannelTable(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // 表名和列名
        private const val TABLE_NAME = "transport_channels"
        private const val COLUMN_CHANNEL_ID = "channel_id"
        private const val COLUMN_RECIPIENT_ID = "recipient_id"
        private const val COLUMN_PROVIDER_TYPE = "provider_type"
        private const val COLUMN_METADATA_JSON = "metadata_json"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_PRIORITY = "priority"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_LAST_ACTIVE_AT = "last_active_at"
        private const val COLUMN_SUCCESS_COUNT = "success_count"
        private const val COLUMN_FAILURE_COUNT = "failure_count"
        private const val COLUMN_LAST_ERROR = "last_error"
        private const val COLUMN_CONFIG_JSON = "config_json"
        private const val COLUMN_VERSION = "version"
    }
    
    private val databaseManager = TapDatabaseManager.getInstance(context)
    
    /**
     * 插入或更新传输通道
     * 使用独立数据库事务
     */
    suspend fun insertOrUpdateChannel(channel: TransportChannel) {
        databaseManager.withTransaction { db ->
            
            // 使用 INSERT OR REPLACE 简化逻辑，避免读写分离
            val values = buildChannelValues(channel)
            
            val sql = """
                INSERT OR REPLACE INTO $TABLE_NAME (
                    $COLUMN_CHANNEL_ID, $COLUMN_RECIPIENT_ID, $COLUMN_PROVIDER_TYPE,
                    $COLUMN_METADATA_JSON, $COLUMN_STATUS, $COLUMN_PRIORITY,
                    $COLUMN_CREATED_AT, $COLUMN_LAST_ACTIVE_AT, $COLUMN_SUCCESS_COUNT,
                    $COLUMN_FAILURE_COUNT, $COLUMN_LAST_ERROR, $COLUMN_CONFIG_JSON, $COLUMN_VERSION
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 
                    COALESCE((SELECT $COLUMN_VERSION FROM $TABLE_NAME WHERE $COLUMN_CHANNEL_ID = ?), 0) + 1)
            """.trimIndent()
            
            val args = arrayOf(
                channel.channelId,
                channel.recipientId,
                channel.providerType,
                channel.metadata.toJson(),
                channel.status.ordinal,
                channel.priority,
                channel.createdAt,
                channel.lastActiveAt,
                channel.successCount,
                channel.failureCount,
                channel.lastError?.name,
                JsonUtils.toJson(channel.config),
                channel.channelId // 用于版本查询
            )
            
            db.execSQL(sql, args)
            Log.d(TAG, "通道保存成功 (独立数据库): ${channel.channelId}")
        }
    }
    
    /**
     * 根据通道ID获取通道
     */
    suspend fun getChannel(channelId: String): TransportChannel? {
        return databaseManager.withReadOnly { db ->
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COLUMN_CHANNEL_ID = ?",
                arrayOf(channelId),
                null,
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    readTransportChannel(it)
                } else null
            }
        }
    }
    
    /**
     * 获取指定接收者的所有通道
     */
    suspend fun getChannelsByRecipient(recipientId: String): List<TransportChannel> {
        return databaseManager.withReadOnly { db ->
            val channels = mutableListOf<TransportChannel>()
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COLUMN_RECIPIENT_ID = ?",
                arrayOf(recipientId),
                null,
                null,
                "$COLUMN_LAST_ACTIVE_AT DESC"
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    readTransportChannel(it)?.let { channel ->
                        channels.add(channel)
                    }
                }
            }
            
            channels
        }
    }
    
    /**
     * 获取所有活跃通道（优化的只读查询）
     */
    suspend fun getActiveChannels(): List<TransportChannel> {
        return databaseManager.withReadOnly { db ->
            val channels = mutableListOf<TransportChannel>()
            
            // 使用优化的查询，减少数据传输
            val cursor = db.query(
                TABLE_NAME,
                null,
                "$COLUMN_STATUS IN (?, ?, ?)",
                arrayOf(
                    TransportChannelStatus.ACTIVE.ordinal.toString(),
                    TransportChannelStatus.FULL_ACTIVE.ordinal.toString(),
                    TransportChannelStatus.SEND_READY.ordinal.toString()
                ),
                null,
                null,
                "$COLUMN_LAST_ACTIVE_AT DESC",
                "100" // 限制查询结果数量
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    readTransportChannel(it)?.let { channel ->
                        channels.add(channel)
                    }
                }
            }
            
            channels
        }
    }
    
    /**
     * 获取活跃通道数量（轻量级查询）
     */
    suspend fun getActiveChannelCount(): Int {
        return databaseManager.withReadOnly { db ->
            val cursor = db.rawQuery("""
                SELECT COUNT(*) FROM $TABLE_NAME 
                WHERE $COLUMN_STATUS IN (?, ?, ?)
            """.trimIndent(), arrayOf(
                TransportChannelStatus.ACTIVE.ordinal.toString(),
                TransportChannelStatus.FULL_ACTIVE.ordinal.toString(),
                TransportChannelStatus.SEND_READY.ordinal.toString()
            ))
            
            cursor?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
        }
    }
    
    /**
     * 检查通道是否存在（轻量级查询）
     */
    suspend fun channelExists(channelId: String): Boolean {
        return databaseManager.withReadOnly { db ->
            val cursor = db.rawQuery("""
                SELECT 1 FROM $TABLE_NAME WHERE $COLUMN_CHANNEL_ID = ? LIMIT 1
            """.trimIndent(), arrayOf(channelId))
            
            cursor?.use {
                it.moveToFirst()
            } ?: false
        }
    }
    
    /**
     * 删除通道
     */
    suspend fun deleteChannel(channelId: String) {
        databaseManager.withTransaction { db ->
            val deletedRows = db.delete(TABLE_NAME, "$COLUMN_CHANNEL_ID = ?", arrayOf(channelId))
            Log.d(TAG, "删除通道: $channelId, 影响行数: $deletedRows")
        }
    }
    
    /**
     * 批量更新通道状态
     */
    suspend fun updateChannelStatuses(updates: List<Pair<String, TransportChannelStatus>>) {
        if (updates.isEmpty()) return
        
        databaseManager.withTransaction { db ->
            val updateSql = """
                UPDATE $TABLE_NAME 
                SET $COLUMN_STATUS = ?, $COLUMN_LAST_ACTIVE_AT = ?
                WHERE $COLUMN_CHANNEL_ID = ?
            """.trimIndent()
            
            val stmt = db.compileStatement(updateSql)
            val currentTime = System.currentTimeMillis()
            
            updates.forEach { (channelId, status) ->
                stmt.bindLong(1, status.ordinal.toLong())
                stmt.bindLong(2, currentTime)
                stmt.bindString(3, channelId)
                stmt.executeUpdateDelete()
            }
            
            Log.d(TAG, "批量更新通道状态: ${updates.size}个")
        }
    }
    
    /**
     * 清理过期通道
     */
    suspend fun cleanupExpiredChannels(olderThanMs: Long): Int {
        val cutoffTime = System.currentTimeMillis() - olderThanMs
        
        return databaseManager.withTransaction { db ->
            val deletedRows = db.delete(
                TABLE_NAME,
                "$COLUMN_LAST_ACTIVE_AT < ? AND $COLUMN_STATUS = ?",
                arrayOf(cutoffTime.toString(), TransportChannelStatus.CLOSED.ordinal.toString())
            )
            
            Log.i(TAG, "清理过期通道: ${deletedRows}个")
            deletedRows
        }
    }
    
    /**
     * 构建通道ContentValues
     */
    private fun buildChannelValues(channel: TransportChannel): ContentValues {
        val values = ContentValues()
        values.put(COLUMN_CHANNEL_ID, channel.channelId)
        values.put(COLUMN_RECIPIENT_ID, channel.recipientId)
        values.put(COLUMN_PROVIDER_TYPE, channel.providerType)
        values.put(COLUMN_METADATA_JSON, channel.metadata.toJson())
        values.put(COLUMN_STATUS, channel.status.ordinal)
        values.put(COLUMN_PRIORITY, channel.priority)
        values.put(COLUMN_CREATED_AT, channel.createdAt)
        values.put(COLUMN_LAST_ACTIVE_AT, channel.lastActiveAt)
        values.put(COLUMN_SUCCESS_COUNT, channel.successCount)
        values.put(COLUMN_FAILURE_COUNT, channel.failureCount)
        values.put(COLUMN_LAST_ERROR, channel.lastError?.name)
        values.put(COLUMN_CONFIG_JSON, JsonUtils.toJson(channel.config))
        return values
    }
    
    /**
     * 从Cursor读取TransportChannel
     */
    private fun readTransportChannel(cursor: Cursor): TransportChannel? {
        return try {
            val channelId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CHANNEL_ID))
            val recipientId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_RECIPIENT_ID))
            val providerType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PROVIDER_TYPE))
            val metadataJson = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_METADATA_JSON))
            val statusOrdinal = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
            val priority = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PRIORITY))
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT))
            val lastActiveAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_ACTIVE_AT))
            val successCount = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SUCCESS_COUNT))
            val failureCount = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_FAILURE_COUNT))
            val lastErrorStr = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LAST_ERROR))
            val configJson = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONFIG_JSON))
            
            // 解析各个字段
            val metadata = TransportMetadataFactory.createFromJson(providerType, metadataJson)
                ?: return null // 如果metadata解析失败，返回null
            val status = TransportChannelStatus.values()[statusOrdinal]
            val lastError = lastErrorStr?.let { TransportError.valueOf(it) }
            val config = deserializeChannelConfigAsMap(configJson)
            
            TransportChannel(
                channelId = channelId,
                recipientId = recipientId,
                providerType = providerType,
                metadata = metadata,
                status = status,
                priority = priority,
                createdAt = createdAt,
                lastActiveAt = lastActiveAt,
                successCount = successCount,
                failureCount = failureCount,
                lastError = lastError,
                config = config
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取通道记录异常", e)
            null
        }
    }
    
    /**
     * 反序列化通道配置为Map
     */
    private fun deserializeChannelConfigAsMap(json: String): Map<String, Any> {
        return try {
            if (json.isBlank()) {
                emptyMap()
            } else {
                JsonUtils.fromJson(json, Map::class.java) as? Map<String, Any> ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "反序列化通道配置异常", e)
            emptyMap()
        }
    }
} 