package org.thoughtcrime.securesms.tapv3.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo

class TapV3GroupStateTable(context: Context, databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {

    companion object {
        private val TAG = Log.tag(TapV3GroupStateTable::class.java)

        const val TABLE_NAME = "tap_v3_group_state"
        const val GROUP_ID = "group_id"
        const val STATE = "state"
        const val INITIATOR_ID = "initiator_id"
        const val AGREED_MEMBERS = "agreed_members"
        const val HANDSHAKE_INFO = "handshake_info"
        const val UPDATED_AT = "updated_at"

        val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $GROUP_ID TEXT PRIMARY KEY,
                $STATE INTEGER NOT NULL,
                $INITIATOR_ID TEXT,
                $AGREED_MEMBERS TEXT,
                $HANDSHAKE_INFO BLOB,
                $UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
    }

    private fun ensureTableExists() {
        try {
            readableDatabase.query(
                TABLE_NAME,
                arrayOf(GROUP_ID),
                null, null, null, null, null, "0"
            ).use { /* Table exists */ }
        } catch (e: Exception) {
            Log.w(TAG, "Table $TABLE_NAME does not exist, creating it now...", e)
            try {
                writableDatabase.execSQL(CREATE_TABLE)
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

    data class GroupStateRecord(
        val groupId: String,
        val status: GroupStatus,
        val initiatorId: String?,
        val agreedMembers: List<String>,
        val handshakeInfo: TapV3HandshakeInfo?,
        val updatedAt: Long
    )

    fun getGroupState(groupId: String): GroupStateRecord? {
        ensureTableExists()
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            "$GROUP_ID = ?",
            arrayOf(groupId),
            null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return readRow(it)
            }
        }
        return null
    }

    fun setGroupState(record: GroupStateRecord) {
        ensureTableExists()
        val values = ContentValues().apply {
            put(GROUP_ID, record.groupId)
            put(STATE, record.status.code)
            put(INITIATOR_ID, record.initiatorId)
            put(AGREED_MEMBERS, record.agreedMembers.joinToString(","))
            put(HANDSHAKE_INFO, record.handshakeInfo?.let { TapV3HandshakeInfo.serialize(it) })
            put(UPDATED_AT, System.currentTimeMillis())
        }

        writableDatabase.replace(TABLE_NAME, null, values)
        notifyConversationListeners(record.groupId)
    }

    fun updateAgreedMembers(groupId: String, agreedMembers: List<String>) {
        ensureTableExists()
        val values = ContentValues().apply {
            put(AGREED_MEMBERS, agreedMembers.joinToString(","))
            put(UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_NAME, values, "$GROUP_ID = ?", arrayOf(groupId))
        notifyConversationListeners(groupId)
    }

    fun updateStatus(groupId: String, status: GroupStatus) {
        ensureTableExists()
        val values = ContentValues().apply {
            put(STATE, status.code)
            put(UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_NAME, values, "$GROUP_ID = ?", arrayOf(groupId))
        notifyConversationListeners(groupId)
    }

    private fun notifyConversationListeners(groupId: String) {
        try {
            val gid = GroupId.parse(groupId)
            val recipient = Recipient.externalGroupExact(gid)
            val threadId = SignalDatabase.threads.getThreadIdFor(recipient.id)
            if (threadId != -1L) {
                AppDependencies.databaseObserver.notifyConversationListeners(setOf(threadId))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify listeners for group $groupId", e)
        }
    }

    private fun readRow(cursor: Cursor): GroupStateRecord {
        val groupId = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))
        val status = GroupStatus.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow(STATE)))
        val initiatorId = cursor.getString(cursor.getColumnIndexOrThrow(INITIATOR_ID))
        val agreedMembersStr = cursor.getString(cursor.getColumnIndexOrThrow(AGREED_MEMBERS))
        val agreedMembers = if (agreedMembersStr.isNullOrEmpty()) emptyList() else agreedMembersStr.split(",")
        val handshakeInfoBlob = cursor.getBlob(cursor.getColumnIndexOrThrow(HANDSHAKE_INFO))
        val handshakeInfo = handshakeInfoBlob?.let { TapV3HandshakeInfo.deserialize(it) }
        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(UPDATED_AT))

        return GroupStateRecord(
            groupId = groupId,
            status = status,
            initiatorId = initiatorId,
            agreedMembers = agreedMembers,
            handshakeInfo = handshakeInfo,
            updatedAt = updatedAt
        )
    }
}
