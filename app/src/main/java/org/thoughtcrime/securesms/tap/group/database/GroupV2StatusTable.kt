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
        private const val VERSION = "version"

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
                "$UPDATED_AT INTEGER NOT NULL, " +
                "$VERSION INTEGER NOT NULL DEFAULT 0" +
            ")"

        val CREATE_INDEXES = arrayOf(
            "CREATE INDEX IF NOT EXISTS group_v2_status_group_id_idx ON $TABLE_NAME ($GROUP_ID)",
            "CREATE INDEX IF NOT EXISTS group_v2_status_status_idx ON $TABLE_NAME ($STATUS)",
            "CREATE INDEX IF NOT EXISTS group_v2_status_proposer_idx ON $TABLE_NAME ($PROPOSER_ACI)"
        )
    }

    /**
     * 插入或更新群组状态
     * 
     * @param state 群组状态
     * @param expectedVersion 期望的版本号（用于乐观锁）。如果为 null，将尝试 INSERT OR REPLACE
     * @throws OptimisticLockException 当版本冲突时抛出
     */
    @WorkerThread
    fun insertOrUpdateGroupState(@NonNull state: GroupV2State, expectedVersion: Long? = null) {
        try {
            writableDatabase.beginTransaction()
            try {
                if (expectedVersion != null) {
                    // 有期望版本号：使用乐观锁更新（不在事务内查询，避免连接池死锁）
                    val newVersion = expectedVersion + 1
                    val values = buildContentValues(state.copy(version = newVersion))
                    
                    val updated = writableDatabase.update(
                        TABLE_NAME,
                        values,
                        "$GROUP_ID = ? AND $VERSION = ?",
                        arrayOf(state.groupId, expectedVersion.toString())
                    )
                    
                    if (updated == 0) {
                        throw OptimisticLockException(
                            "并发更新冲突: groupId=${state.groupId}, expectedVersion=$expectedVersion"
                        )
                    }
                    
                    Log.d(TAG, "更新群组状态: ${state.groupId}, version: $expectedVersion -> $newVersion")
                } else {
                    // 没有期望版本号：尝试插入，如果已存在则替换（用于新建或强制更新）
                    val values = buildContentValues(state.copy(version = 0L))
                    writableDatabase.insertWithOnConflict(
                        TABLE_NAME,
                        null,
                        values,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                    )
                    Log.d(TAG, "插入或替换群组状态: ${state.groupId}")
                }
                
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } catch (e: OptimisticLockException) {
            Log.w(TAG, "乐观锁冲突: ${state.groupId}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "插入或更新群组状态失败: ${state.groupId}", e)
            throw e
        }
    }
    
    /**
     * 乐观锁异常
     */
    class OptimisticLockException(message: String) : Exception(message)

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
     * 添加同意成员（带重试的乐观锁）
     * 
     * 使用单一事务完成查询和更新，避免嵌套连接请求导致的死锁
     * 
     * @param maxRetries 最大重试次数
     */
    @WorkerThread
    fun addAgreedMember(
        @NonNull groupId: String, 
        @NonNull memberAci: String,
        maxRetries: Int = 3
    ): Boolean {
        var attempt = 0
        while (attempt < maxRetries) {
            try {
                // 整个操作在一个事务内完成，使用同一个数据库连接
                writableDatabase.beginTransaction()
                try {
                    // 在事务内查询（使用 writableDatabase 而不是 readableDatabase）
                    val state = getGroupStateInTransaction(groupId)
                    if (state == null) {
                        Log.w(TAG, "群组状态不存在: $groupId")
                        writableDatabase.setTransactionSuccessful()
                        return false
                    }
                    
                    if (memberAci in state.agreedMembers) {
                        Log.d(TAG, "成员已在同意列表中: $groupId, $memberAci")
                        writableDatabase.setTransactionSuccessful()
                        return true
                    }
                    
                    // 在事务内更新（使用乐观锁）
                    val newState = state.withAgreedMember(memberAci)
                    val newVersion = state.version + 1
                    val values = buildContentValues(newState.copy(version = newVersion))
                    
                    val updated = writableDatabase.update(
                        TABLE_NAME,
                        values,
                        "$GROUP_ID = ? AND $VERSION = ?",
                        arrayOf(state.groupId, state.version.toString())
                    )
                    
                    if (updated == 0) {
                        throw OptimisticLockException(
                            "并发更新冲突: groupId=$groupId, expectedVersion=${state.version}"
                        )
                    }
                    
                    Log.d(TAG, "成功添加同意成员: $groupId, $memberAci, version: ${state.version} -> $newVersion")
                    writableDatabase.setTransactionSuccessful()
                    return true
                    
                } finally {
                    writableDatabase.endTransaction()
                }
                
            } catch (e: OptimisticLockException) {
                attempt++
                Log.w(TAG, "添加同意成员乐观锁冲突，重试 $attempt/$maxRetries: $groupId, $memberAci")
                if (attempt >= maxRetries) {
                    Log.e(TAG, "添加同意成员失败，超过最大重试次数: $groupId, $memberAci")
                    return false
                }
                // 指数退避
                Thread.sleep(50L * attempt)
            } catch (e: Exception) {
                Log.e(TAG, "添加同意成员失败: $groupId, $memberAci", e)
                return false
            }
        }
        return false
    }
    
    /**
     * 在现有事务内查询群组状态
     * 
     * 注意：此方法必须在事务内调用，使用 writableDatabase 确保使用同一连接
     */
    @WorkerThread
    @Nullable
    private fun getGroupStateInTransaction(@NonNull groupId: String): GroupV2State? {
        return try {
            writableDatabase.query(
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
            Log.e(TAG, "在事务内查询群组状态失败: $groupId", e)
            null
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
            put(VERSION, state.version)
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
            val version = cursor.getLong(cursor.getColumnIndexOrThrow(VERSION))

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
                updatedAt = updatedAt,
                version = version
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取群组状态失败", e)
            null
        }
    }

    /**
     * 序列化字符串集合为 JSON
     * 
     * @throws IOException 当序列化失败时
     */
    private fun serializeStringSet(set: Set<String>): String {
        return try {
            JsonUtils.toJson(set.toList())
        } catch (e: IOException) {
            Log.e(TAG, "序列化字符串集合失败: size=${set.size}", e)
            throw IOException("序列化字符串集合失败: ${e.message}", e)
        }
    }
    
    /**
     * 从 JSON 反序列化字符串集合
     * 
     * 对于反序列化失败的情况，返回空集合是安全的选择
     * 因为读取操作不应该因为数据格式问题而完全失败
     */
    private fun deserializeStringSet(json: String): Set<String> {
        return try {
            if (json.isBlank() || json == "null") {
                emptySet()
            } else {
                val list = JsonUtils.fromJsonArray(json, String::class.java)
                list.toSet()
            }
        } catch (e: Exception) {
            Log.w(TAG, "反序列化字符串集合失败，返回空集合: json=$json", e)
            emptySet()
        }
    }
}

