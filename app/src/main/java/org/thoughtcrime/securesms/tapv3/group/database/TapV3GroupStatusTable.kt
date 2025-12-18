package org.thoughtcrime.securesms.tapv3.group.database

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

class TapV3GroupStatusTable(context: Context, databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {

    companion object {
        private val TAG = Log.tag(TapV3GroupStatusTable::class.java)

        const val TABLE_NAME = "tap_v3_group_status"
        const val ID = "_id"
        const val GROUP_ID = "group_id"
        const val STATUS = "status"
        const val PROPOSER_ACI = "proposer_aci"
        const val MY_K_PUSH = "my_k_push"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"

        val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $GROUP_ID TEXT NOT NULL UNIQUE,
                $STATUS INTEGER NOT NULL,
                $PROPOSER_ACI TEXT,
                $MY_K_PUSH BLOB,
                $CREATED_AT INTEGER NOT NULL,
                $UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val CREATE_INDEX = arrayOf(
            """
            CREATE INDEX IF NOT EXISTS tap_v3_group_status_group_id_index 
            ON $TABLE_NAME ($GROUP_ID)
            """.trimIndent()
        )
    }

    private fun ensureTableExists() {
        try {
            readableDatabase.query(TABLE_NAME, arrayOf(ID), null, null, null, null, null, "0").use { }
        } catch (e: Exception) {
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

    enum class GroupStatus(val code: Int) {
        NATIVE(0),
        PROPOSING(1),
        ACTIVE(2);

        companion object {
            fun fromCode(code: Int): GroupStatus {
                return values().find { it.code == code } ?: NATIVE
            }
        }
    }

    data class GroupRecord(
        val id: Long,
        val groupId: String,
        val status: GroupStatus,
        val proposerAci: String?,
        val myKPush: ByteArray?,
        val createdAt: Long,
        val updatedAt: Long
    )

    fun insertOrUpdate(
        groupId: String,
        status: GroupStatus,
        proposerAci: String?,
        myKPush: ByteArray?
    ): Long {
        ensureTableExists()

        val now = System.currentTimeMillis()
        val values = contentValuesOf(
            GROUP_ID to groupId,
            STATUS to status.code,
            PROPOSER_ACI to proposerAci,
            MY_K_PUSH to myKPush,
            UPDATED_AT to now
        )

        val existing = getGroup(groupId)

        return if (existing != null) {
            writableDatabase.update(TABLE_NAME, values, "$GROUP_ID = ?", arrayOf(groupId))
            Log.d(TAG, "Updated group status for: ${groupId.take(8)}...")
            existing.id
        } else {
            values.put(CREATED_AT, now)
            val id = writableDatabase.insert(TABLE_NAME, null, values)
            Log.d(TAG, "Inserted group status for: ${groupId.take(8)}..., id: $id")
            id
        }
    }

    fun getGroup(groupId: String): GroupRecord? {
        ensureTableExists()

        readableDatabase.query(
            TABLE_NAME,
            null,
            "$GROUP_ID = ?",
            arrayOf(groupId),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                readGroup(cursor)
            } else {
                null
            }
        }
    }

    private fun readGroup(cursor: Cursor): GroupRecord {
        return GroupRecord(
            id = cursor.requireLong(ID),
            groupId = cursor.requireNonNullString(GROUP_ID),
            status = GroupStatus.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow(STATUS))),
            proposerAci = cursor.requireString(PROPOSER_ACI),
            myKPush = cursor.getBlob(cursor.getColumnIndexOrThrow(MY_K_PUSH)),
            createdAt = cursor.requireLong(CREATED_AT),
            updatedAt = cursor.requireLong(UPDATED_AT)
        )
    }
}
