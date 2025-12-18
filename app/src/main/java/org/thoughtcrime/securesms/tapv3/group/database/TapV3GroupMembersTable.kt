package org.thoughtcrime.securesms.tapv3.group.database

import android.content.Context
import android.database.Cursor
import androidx.core.content.contentValuesOf
import org.signal.core.util.logging.Log
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase

class TapV3GroupMembersTable(context: Context, databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {

    companion object {
        private val TAG = Log.tag(TapV3GroupMembersTable::class.java)

        const val TABLE_NAME = "tap_v3_group_members"
        const val ID = "_id"
        const val GROUP_ID = "group_id"
        const val MEMBER_ACI = "member_aci"
        const val ENDPOINT = "endpoint"
        const val K_PUSH = "k_push"
        const val KEY_VERSION = "key_version"
        const val IPFS_GATEWAYS = "ipfs_gateways"
        const val STATUS = "status"

        val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $GROUP_ID TEXT NOT NULL,
                $MEMBER_ACI TEXT NOT NULL,
                $ENDPOINT TEXT,
                $K_PUSH BLOB,
                $KEY_VERSION INTEGER DEFAULT 1,
                $IPFS_GATEWAYS TEXT,
                $STATUS INTEGER NOT NULL,
                UNIQUE($GROUP_ID, $MEMBER_ACI)
            )
        """.trimIndent()

        val CREATE_INDEX = arrayOf(
            """
            CREATE INDEX IF NOT EXISTS tap_v3_group_members_group_id_index 
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

    enum class MemberStatus(val code: Int) {
        PENDING(0),
        ACCEPTED(1)
    }

    data class MemberRecord(
        val id: Long,
        val groupId: String,
        val memberAci: String,
        val endpoint: String?,
        val kPush: ByteArray?,
        val keyVersion: Int,
        val ipfsGateways: List<String>,
        val status: MemberStatus
    )

    fun insertOrUpdate(
        groupId: String,
        memberAci: String,
        endpoint: String?,
        kPush: ByteArray?,
        keyVersion: Int,
        ipfsGateways: List<String>,
        status: MemberStatus
    ): Long {
        ensureTableExists()

        val gatewaysJson = ipfsGateways.joinToString(",")
        val values = contentValuesOf(
            GROUP_ID to groupId,
            MEMBER_ACI to memberAci,
            ENDPOINT to endpoint,
            K_PUSH to kPush,
            KEY_VERSION to keyVersion,
            IPFS_GATEWAYS to gatewaysJson,
            STATUS to status.code
        )

        val existing = getMember(groupId, memberAci)

        return if (existing != null) {
            writableDatabase.update(TABLE_NAME, values, "$GROUP_ID = ? AND $MEMBER_ACI = ?", arrayOf(groupId, memberAci))
            Log.d(TAG, "Updated member for group: ${groupId.take(8)}..., member: ${memberAci.take(8)}...")
            existing.id
        } else {
            val id = writableDatabase.insert(TABLE_NAME, null, values)
            Log.d(TAG, "Inserted member for group: ${groupId.take(8)}..., member: ${memberAci.take(8)}..., id: $id")
            id
        }
    }

    fun getMember(groupId: String, memberAci: String): MemberRecord? {
        ensureTableExists()

        readableDatabase.query(
            TABLE_NAME,
            null,
            "$GROUP_ID = ? AND $MEMBER_ACI = ?",
            arrayOf(groupId, memberAci),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                readMember(cursor)
            } else {
                null
            }
        }
    }

    fun getMembers(groupId: String): List<MemberRecord> {
        ensureTableExists()

        val members = mutableListOf<MemberRecord>()
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$GROUP_ID = ?",
            arrayOf(groupId),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                members.add(readMember(cursor))
            }
        }
        return members
    }

    private fun readMember(cursor: Cursor): MemberRecord {
        val gatewaysString = cursor.requireString(IPFS_GATEWAYS) ?: ""
        val ipfsGateways = if (gatewaysString.isNotEmpty()) {
            gatewaysString.split(",")
        } else {
            emptyList()
        }

        return MemberRecord(
            id = cursor.requireLong(ID),
            groupId = cursor.requireNonNullString(GROUP_ID),
            memberAci = cursor.requireNonNullString(MEMBER_ACI),
            endpoint = cursor.requireString(ENDPOINT),
            kPush = cursor.getBlob(cursor.getColumnIndexOrThrow(K_PUSH)),
            keyVersion = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_VERSION)),
            ipfsGateways = ipfsGateways,
            status = MemberStatus.values().find { it.code == cursor.getInt(cursor.getColumnIndexOrThrow(STATUS)) } ?: MemberStatus.PENDING
        )
    }
}
