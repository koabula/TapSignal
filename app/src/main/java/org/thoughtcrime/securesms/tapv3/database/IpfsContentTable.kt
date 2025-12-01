package org.thoughtcrime.securesms.tapv3.database

import android.content.Context
import androidx.core.content.contentValuesOf
import org.signal.core.util.logging.Log
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase

class IpfsContentTable(context: Context, databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {
    
    companion object {
        private val TAG = Log.tag(IpfsContentTable::class.java)
        
        const val TABLE_NAME = "tap_v3_ipfs_content"
        const val ID = "_id"
        const val CID = "cid"
        const val CONTENT_TYPE = "content_type"
        const val SIZE_BYTES = "size_bytes"
        const val GATEWAY = "gateway"
        const val PINNED_AT = "pinned_at"
        const val EXPIRES_AT = "expires_at"
        const val RECIPIENT_ID = "recipient_id"
        const val MESSAGE_ID = "message_id"
        
        val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $CID TEXT NOT NULL UNIQUE,
                $CONTENT_TYPE TEXT,
                $SIZE_BYTES INTEGER,
                $GATEWAY TEXT,
                $PINNED_AT INTEGER NOT NULL,
                $EXPIRES_AT INTEGER,
                $RECIPIENT_ID TEXT,
                $MESSAGE_ID TEXT
            )
        """.trimIndent()
        
        val CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS tap_v3_ipfs_content_cid_index 
            ON $TABLE_NAME ($CID)
        """.trimIndent()
        
        val CREATE_EXPIRES_INDEX = """
            CREATE INDEX IF NOT EXISTS tap_v3_ipfs_content_expires_index 
            ON $TABLE_NAME ($EXPIRES_AT)
        """.trimIndent()
    }
    
    enum class ContentType {
        MESSAGE,
        ATTACHMENT
    }
    
    data class ContentRecord(
        val id: Long,
        val cid: String,
        val contentType: ContentType?,
        val sizeBytes: Long?,
        val gateway: String?,
        val pinnedAt: Long,
        val expiresAt: Long?,
        val recipientId: String?,
        val messageId: String?
    )
    
    fun insertContent(
        cid: String,
        contentType: ContentType? = null,
        sizeBytes: Long? = null,
        gateway: String? = null,
        expiresAt: Long? = null,
        recipientId: String? = null,
        messageId: String? = null
    ): Long {
        val values = contentValuesOf(
            CID to cid,
            CONTENT_TYPE to contentType?.name,
            SIZE_BYTES to sizeBytes,
            GATEWAY to gateway,
            PINNED_AT to System.currentTimeMillis(),
            EXPIRES_AT to expiresAt,
            RECIPIENT_ID to recipientId,
            MESSAGE_ID to messageId
        )
        
        val id = writableDatabase.insert(TABLE_NAME, null, values)
        Log.d(TAG, "Inserted IPFS content: $cid, id: $id")
        return id
    }
    
    fun getContent(cid: String): ContentRecord? {
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$CID = ?",
            arrayOf(cid),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                val contentTypeStr = cursor.getString(cursor.getColumnIndexOrThrow(CONTENT_TYPE))
                
                ContentRecord(
                    id = cursor.requireLong(ID),
                    cid = cursor.requireNonNullString(CID),
                    contentType = contentTypeStr?.let { ContentType.valueOf(it) },
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(SIZE_BYTES)),
                    gateway = cursor.getString(cursor.getColumnIndexOrThrow(GATEWAY)),
                    pinnedAt = cursor.requireLong(PINNED_AT),
                    expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_AT)),
                    recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID)),
                    messageId = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_ID))
                )
            } else {
                null
            }
        }
    }
    
    fun deleteContent(cid: String) {
        writableDatabase.delete(TABLE_NAME, "$CID = ?", arrayOf(cid))
        Log.d(TAG, "Deleted IPFS content: $cid")
    }
    
    fun getExpiredContent(currentTime: Long): List<ContentRecord> {
        val contents = mutableListOf<ContentRecord>()
        
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$EXPIRES_AT IS NOT NULL AND $EXPIRES_AT <= ?",
            arrayOf(currentTime.toString()),
            null,
            null,
            "$EXPIRES_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val contentTypeStr = cursor.getString(cursor.getColumnIndexOrThrow(CONTENT_TYPE))
                
                contents.add(
                    ContentRecord(
                        id = cursor.requireLong(ID),
                        cid = cursor.requireNonNullString(CID),
                        contentType = contentTypeStr?.let { ContentType.valueOf(it) },
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(SIZE_BYTES)),
                        gateway = cursor.getString(cursor.getColumnIndexOrThrow(GATEWAY)),
                        pinnedAt = cursor.requireLong(PINNED_AT),
                        expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_AT)),
                        recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID)),
                        messageId = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_ID))
                    )
                )
            }
        }
        
        return contents
    }
}
