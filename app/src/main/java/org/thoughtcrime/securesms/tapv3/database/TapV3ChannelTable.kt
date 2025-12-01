package org.thoughtcrime.securesms.tapv3.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.content.contentValuesOf
import org.signal.core.util.logging.Log
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase

class TapV3ChannelTable(context: Context, databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {
    
    companion object {
        private val TAG = Log.tag(TapV3ChannelTable::class.java)
        
        const val TABLE_NAME = "tap_v3_channels"
        const val ID = "_id"
        const val RECIPIENT_ID = "recipient_id"
        const val STATUS = "status"
        const val PUSH_ENDPOINT = "push_endpoint"
        const val K_PUSH = "k_push"
        const val KEY_VERSION = "key_version"
        const val IPFS_GATEWAYS = "ipfs_gateways"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
        
        val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $RECIPIENT_ID TEXT NOT NULL UNIQUE,
                $STATUS TEXT NOT NULL,
                $PUSH_ENDPOINT TEXT NOT NULL,
                $K_PUSH BLOB NOT NULL,
                $KEY_VERSION INTEGER DEFAULT 1,
                $IPFS_GATEWAYS TEXT,
                $CREATED_AT INTEGER NOT NULL,
                $UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        
        val CREATE_INDEX = arrayOf(
            """
            CREATE INDEX IF NOT EXISTS tap_v3_channels_recipient_id_index 
            ON $TABLE_NAME ($RECIPIENT_ID)
            """.trimIndent()
        )
    }
    
    /**
     * 确保表存在,如果不存在则创建
     * 这是一个防御性措施,防止迁移未执行导致的表不存在错误
     */
    private fun ensureTableExists() {
        try {
            // 尝试查询表是否存在
            readableDatabase.query(
                TABLE_NAME,
                arrayOf(ID),
                null, null, null, null, null, "0"
            ).use { /* 表存在 */ }
        } catch (e: Exception) {
            // 表不存在,创建它
            Log.w(TAG, "Table $TABLE_NAME does not exist, creating it now...", e)
            try {
                writableDatabase.execSQL(CREATE_TABLE)
                for (index in CREATE_INDEX) {
                    writableDatabase.execSQL(index)
                }
                Log.i(TAG, "Table $TABLE_NAME created successfully")
            } catch (createError: Exception) {
                Log.e(TAG, "Failed to create table $TABLE_NAME", createError)
                throw createError
            }
        }
    }
    
    enum class ChannelStatus {
        PENDING,
        ACTIVE,
        FAILED
    }
    
    data class ChannelRecord(
        val id: Long,
        val recipientId: String,
        val status: ChannelStatus,
        val pushEndpoint: String,
        val kPush: ByteArray,
        val keyVersion: Int,
        val ipfsGateways: List<String>,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ChannelRecord
            if (id != other.id) return false
            if (recipientId != other.recipientId) return false
            if (status != other.status) return false
            if (pushEndpoint != other.pushEndpoint) return false
            if (!kPush.contentEquals(other.kPush)) return false
            if (keyVersion != other.keyVersion) return false
            if (ipfsGateways != other.ipfsGateways) return false
            if (createdAt != other.createdAt) return false
            if (updatedAt != other.updatedAt) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + recipientId.hashCode()
            result = 31 * result + status.hashCode()
            result = 31 * result + pushEndpoint.hashCode()
            result = 31 * result + kPush.contentHashCode()
            result = 31 * result + keyVersion
            result = 31 * result + ipfsGateways.hashCode()
            result = 31 * result + createdAt.hashCode()
            result = 31 * result + updatedAt.hashCode()
            return result
        }
    }
    
    fun insertOrUpdate(
        recipientId: String,
        status: ChannelStatus,
        pushEndpoint: String,
        kPush: ByteArray,
        keyVersion: Int,
        ipfsGateways: List<String>
    ): Long {
        ensureTableExists()
        
        val now = System.currentTimeMillis()
        val gatewaysJson = ipfsGateways.joinToString(",")
        
        val values = contentValuesOf(
            RECIPIENT_ID to recipientId,
            STATUS to status.name,
            PUSH_ENDPOINT to pushEndpoint,
            K_PUSH to kPush,
            KEY_VERSION to keyVersion,
            IPFS_GATEWAYS to gatewaysJson,
            UPDATED_AT to now
        )
        
        val existing = getChannel(recipientId)
        
        return if (existing != null) {
            writableDatabase.update(TABLE_NAME, values, "$RECIPIENT_ID = ?", arrayOf(recipientId))
            Log.d(TAG, "Updated channel for recipient: ${recipientId.take(8)}...")
            existing.id
        } else {
            values.put(CREATED_AT, now)
            val id = writableDatabase.insert(TABLE_NAME, null, values)
            Log.d(TAG, "Inserted channel for recipient: ${recipientId.take(8)}..., id: $id")
            id
        }
    }
    
    fun getChannel(recipientId: String): ChannelRecord? {
        ensureTableExists()
        
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$RECIPIENT_ID = ?",
            arrayOf(recipientId),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                readChannel(cursor)
            } else {
                null
            }
        }
    }
    
    fun updateStatus(recipientId: String, status: ChannelStatus) {
        ensureTableExists()
        
        val values = contentValuesOf(
            STATUS to status.name,
            UPDATED_AT to System.currentTimeMillis()
        )
        
        writableDatabase.update(TABLE_NAME, values, "$RECIPIENT_ID = ?", arrayOf(recipientId))
        Log.d(TAG, "Updated status for recipient: ${recipientId.take(8)}... to $status")
    }
    
    fun deleteChannel(recipientId: String) {
        ensureTableExists()
        
        writableDatabase.delete(TABLE_NAME, "$RECIPIENT_ID = ?", arrayOf(recipientId))
        Log.d(TAG, "Deleted channel for recipient: ${recipientId.take(8)}...")
    }
    
    fun getAllActiveChannels(): List<ChannelRecord> {
        ensureTableExists()
        
        val channels = mutableListOf<ChannelRecord>()
        
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$STATUS = ?",
            arrayOf(ChannelStatus.ACTIVE.name),
            null,
            null,
            "$CREATED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                channels.add(readChannel(cursor))
            }
        }
        
        return channels
    }
    
    private fun readChannel(cursor: Cursor): ChannelRecord {
        val gatewaysString = cursor.requireString(IPFS_GATEWAYS) ?: ""
        val ipfsGateways = if (gatewaysString.isNotEmpty()) {
            gatewaysString.split(",")
        } else {
            emptyList()
        }
        
        return ChannelRecord(
            id = cursor.requireLong(ID),
            recipientId = cursor.requireNonNullString(RECIPIENT_ID),
            status = ChannelStatus.valueOf(cursor.requireNonNullString(STATUS)),
            pushEndpoint = cursor.requireNonNullString(PUSH_ENDPOINT),
            kPush = cursor.getBlob(cursor.getColumnIndexOrThrow(K_PUSH)),
            keyVersion = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_VERSION)),
            ipfsGateways = ipfsGateways,
            createdAt = cursor.requireLong(CREATED_AT),
            updatedAt = cursor.requireLong(UPDATED_AT)
        )
    }
}
