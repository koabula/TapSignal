package org.thoughtcrime.securesms.tap.group.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.DatabaseTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.group.GroupV2State
import org.thoughtcrime.securesms.tap.group.GroupV2Status
import org.thoughtcrime.securesms.util.JsonUtils
import java.io.IOException

/**
 * 群组 V2 状态持久化表
 * 
 * 存储群组的 V2 模式状态信息
 */
class GroupV2StatusTable(@NonNull context: Context, @NonNull databaseHelper: SignalDatabase) :
    DatabaseTable(context, databaseHelper) {

    companion object {
        private val TAG = Log.tag(GroupV2StatusTable::class.java)

        const val TABLE_NAME = "group_v2_status"

        private const val ID = "_id"
        private const val GROUP_ID = "group_id"
        private const val STATUS = "status"
        private const val PROPOSER_ACI = "proposer_aci"
        private const val AGREED_MEMBERS = "agreed_members"
        private const val TOTAL_MEMBERS = "total_members"
        private const val PROVIDER_TYPE = "provider_type"
        private const val CREATED_AT = "created_at"
        private const val UPDATED_AT = "updated_at"

        const val CREATE_TABLE = 
            "CREATE TABLE $TABLE_NAME(" +
                "$ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$GROUP_ID TEXT NOT NULL UNIQUE, " +
                "$STATUS TEXT NOT NULL, " +
                "$PROPOSER_ACI TEXT, " +
                "$AGREED_MEMBERS TEXT NOT NULL, " +
                "$TOTAL_MEMBERS TEXT NOT NULL, " +
                "$PROVIDER_TYPE TEXT NOT NULL, " +
                "$CREATED_AT INTEGER NOT NULL, " +
                "$UPDATED_AT INTEGER NOT NULL" +
            ")"

        val CREATE_INDEXES = arrayOf(
            "CREATE INDEX IF NOT EXISTS group_v2_status_group_id_idx ON $TABLE_NAME ($GROUP_ID)",
            "CREATE INDEX IF NOT EXISTS group_v2_status_status_idx ON $TABLE_NAME ($STATUS)",
            "CREATE INDEX IF NOT EXISTS group_v2_status_proposer_idx ON $TABLE_NAME ($PROPOSER_ACI)"
        )
    }

    /**
     * 插入或更新群组状态
     */
    @WorkerThread
    fun insertOrUpdateGroupState(@NonNull state: GroupV2State) {
        try {
            writableDatabase.beginTransaction()
            try {
                val values = buildContentValues(state)
                
                val existingState = getGroupState(state.groupId)
                if (existingState != null) {
                    val updated = writableDatabase.update(
                        TABLE_NAME,
                        values,
                        "$GROUP_ID = ?",
                        arrayOf(state.groupId)
                    )
                    Log.d(TAG, "更新群组状态: ${state.groupId}, updated=$updated")
                } else {
                    val id = writableDatabase.insert(TABLE_NAME, null, values)
                    Log.d(TAG, "插入群组状态: ${state.groupId}, id=$id")
                }
                
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "插入或更新群组状态失败: ${state.groupId}", e)
        }
    }

    /**
     * 获取群组状态
     */
    @WorkerThread
    @Nullable
    fun getGroupState(@NonNull groupId: String): GroupV2State? {
        return try {
            readableDatabase.query(
                TABLE_NAME,
                null,
                "$GROUP_ID = ?",
                arrayOf(groupId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    readGroupState(cursor)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询群组状态失败: $groupId", e)
            null
        }
    }

    /**
     * 获取所有处于特定状态的群组
     */
    @WorkerThread
    @NonNull
    fun getGroupsByStatus(@NonNull status: GroupV2Status): List<GroupV2State> {
        val states = mutableListOf<GroupV2State>()
        
        try {
            readableDatabase.query(
                TABLE_NAME,
                null,
                "$STATUS = ?",
                arrayOf(status.name),
                null,
                null,
                "$UPDATED_AT DESC"
            ).use { cursor ->
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val state = readGroupState(cursor)
                        if (state != null) {
                            states.add(state)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询特定状态的群组失败: $status", e)
        }
        
        return states
    }

    /**
     * 获取所有活跃的 V2 群组
     */
    @WorkerThread
    @NonNull
    fun getAllActiveV2Groups(): List<GroupV2State> {
        return getGroupsByStatus(GroupV2Status.FULL_V2_ACTIVE)
    }

    /**
     * 删除群组状态
     */
    @WorkerThread
    fun deleteGroupState(@NonNull groupId: String): Boolean {
        return try {
            val deleted = writableDatabase.delete(
                TABLE_NAME,
                "$GROUP_ID = ?",
                arrayOf(groupId)
            )
            Log.d(TAG, "删除群组状态: $groupId, deleted=$deleted")
            deleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "删除群组状态失败: $groupId", e)
            false
        }
    }

    /**
     * 更新群组状态枚举
     */
    @WorkerThread
    fun updateGroupStatus(@NonNull groupId: String, @NonNull newStatus: GroupV2Status): Boolean {
        return try {
            val values = ContentValues().apply {
                put(STATUS, newStatus.name)
                put(UPDATED_AT, System.currentTimeMillis())
            }
            
            val updated = writableDatabase.update(
                TABLE_NAME,
                values,
                "$GROUP_ID = ?",
                arrayOf(groupId)
            )
            
            Log.d(TAG, "更新群组状态: $groupId -> $newStatus, updated=$updated")
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "更新群组状态失败: $groupId", e)
            false
        }
    }

    /**
     * 添加同意成员
     */
    @WorkerThread
    fun addAgreedMember(@NonNull groupId: String, @NonNull memberAci: String): Boolean {
        return try {
            val state = getGroupState(groupId)
            if (state != null) {
                val newState = state.withAgreedMember(memberAci)
                insertOrUpdateGroupState(newState)
                true
            } else {
                Log.w(TAG, "群组状态不存在: $groupId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加同意成员失败: $groupId, $memberAci", e)
            false
        }
    }

    /**
     * 构建 ContentValues
     */
    private fun buildContentValues(@NonNull state: GroupV2State): ContentValues {
        return ContentValues().apply {
            put(GROUP_ID, state.groupId)
            put(STATUS, state.status.name)
            put(PROPOSER_ACI, state.proposerAci)
            put(AGREED_MEMBERS, serializeStringSet(state.agreedMembers))
            put(TOTAL_MEMBERS, serializeStringSet(state.totalMembers))
            put(PROVIDER_TYPE, state.providerType)
            put(CREATED_AT, state.createdAt)
            put(UPDATED_AT, state.updatedAt)
        }
    }

    /**
     * 从 Cursor 读取群组状态
     */
    @Nullable
    private fun readGroupState(@NonNull cursor: Cursor): GroupV2State? {
        return try {
            val groupId = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))
            val statusString = cursor.getString(cursor.getColumnIndexOrThrow(STATUS))
            val proposerAci = cursor.getString(cursor.getColumnIndexOrThrow(PROPOSER_ACI))
            val agreedMembersJson = cursor.getString(cursor.getColumnIndexOrThrow(AGREED_MEMBERS))
            val totalMembersJson = cursor.getString(cursor.getColumnIndexOrThrow(TOTAL_MEMBERS))
            val providerType = cursor.getString(cursor.getColumnIndexOrThrow(PROVIDER_TYPE))
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CREATED_AT))
            val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(UPDATED_AT))

            val status = try {
                GroupV2Status.valueOf(statusString)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "无效的状态值: $statusString", e)
                GroupV2Status.NATIVE
            }

            GroupV2State(
                groupId = groupId,
                status = status,
                proposerAci = proposerAci,
                agreedMembers = deserializeStringSet(agreedMembersJson),
                totalMembers = deserializeStringSet(totalMembersJson),
                providerType = providerType,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取群组状态失败", e)
            null
        }
    }

    /**
     * 序列化字符串集合为 JSON
     */
    private fun serializeStringSet(set: Set<String>): String {
        return try {
            JsonUtils.toJson(set.toList())
        } catch (e: IOException) {
            Log.e(TAG, "序列化字符串集合失败", e)
            "[]"
        }
    }

    /**
     * 从 JSON 反序列化字符串集合
     */
    private fun deserializeStringSet(json: String): Set<String> {
        return try {
            val list = JsonUtils.fromJsonArray(json, String::class.java)
            list.toSet()
        } catch (e: IOException) {
            Log.w(TAG, "反序列化字符串集合失败", e)
            emptySet()
        }
    }
}

