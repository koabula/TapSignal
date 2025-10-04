package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 群组传输管理器
 * 
 * 负责管理群组 V2 模式的生命周期，包括状态管理、提议、激活和消息发送
 */
class GroupTransportManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupTransportManager::class.java)
        
        @Volatile
        private var INSTANCE: GroupTransportManager? = null
        
        /**
         * 获取 GroupTransportManager 单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): GroupTransportManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupTransportManager(context.applicationContext).also {
                    INSTANCE = it
                    Log.d(TAG, "创建 GroupTransportManager 实例")
                }
            }
        }
        
        /**
         * 重置单例实例（仅用于测试）
         */
        @JvmStatic
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
                Log.d(TAG, "重置 GroupTransportManager 实例")
            }
        }
    }
    
    // 数据库访问
    private val groupV2StatusTable: GroupV2StatusTable by lazy {
        SignalDatabase.instance?.let {
            GroupV2StatusTable(context, it)
        } ?: throw IllegalStateException("SignalDatabase not initialized")
    }
    
    // 线程安全
    private val stateLock = ReentrantReadWriteLock()
    
    /**
     * 获取群组状态
     */
    fun getGroupStatus(groupId: String): GroupV2Status {
        return stateLock.read {
            try {
                val state = groupV2StatusTable.getGroupState(groupId)
                state?.status ?: GroupV2Status.NATIVE
            } catch (e: Exception) {
                Log.e(TAG, "获取群组状态失败: $groupId", e)
                GroupV2Status.NATIVE
            }
        }
    }
    
    /**
     * 获取群组完整状态
     */
    fun getGroupState(groupId: String): GroupV2State? {
        return stateLock.read {
            try {
                groupV2StatusTable.getGroupState(groupId)
            } catch (e: Exception) {
                Log.e(TAG, "获取群组完整状态失败: $groupId", e)
                null
            }
        }
    }
    
    /**
     * 更新群组状态
     */
    suspend fun updateGroupStatus(groupId: String, status: GroupV2Status): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    val success = groupV2StatusTable.updateGroupStatus(groupId, status)
                    if (success) {
                        Log.i(TAG, "群组状态更新成功: $groupId -> $status")
                    } else {
                        Log.w(TAG, "群组状态更新失败: $groupId -> $status")
                    }
                    success
                } catch (e: Exception) {
                    Log.e(TAG, "更新群组状态异常: $groupId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 更新群组完整状态
     */
    suspend fun updateGroupState(state: GroupV2State): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    if (!state.validate()) {
                        Log.w(TAG, "群组状态无效，拒绝更新: ${state.groupId}")
                        return@withContext false
                    }
                    
                    groupV2StatusTable.insertOrUpdateGroupState(state)
                    Log.i(TAG, "群组状态更新成功: ${state.groupId}, status=${state.status}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "更新群组状态异常: ${state.groupId}", e)
                    false
                }
            }
        }
    }
    
    /**
     * 提议群组使用 V2 模式
     * 
     * @param groupId 群组 ID
     * @param proposerAci 发起人 ACI
     * @param memberAcis 所有成员 ACI 集合
     * @param providerType Provider 类型
     * @return 是否成功发起提议
     */
    suspend fun proposeV2Mode(
        groupId: String,
        proposerAci: String,
        memberAcis: Set<String>,
        providerType: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    // 检查当前状态
                    val currentState = groupV2StatusTable.getGroupState(groupId)
                    if (currentState != null && currentState.status != GroupV2Status.NATIVE) {
                        Log.w(TAG, "群组已处于非原生状态，无法发起提议: $groupId, status=${currentState.status}")
                        return@withContext false
                    }
                    
                    // 创建提议状态
                    val newState = GroupV2State(
                        groupId = groupId,
                        status = GroupV2Status.PROPOSING,
                        proposerAci = proposerAci,
                        agreedMembers = setOf(proposerAci), // 发起人自动同意
                        totalMembers = memberAcis,
                        providerType = providerType,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    
                    groupV2StatusTable.insertOrUpdateGroupState(newState)
                    Log.i(TAG, "群组 V2 模式提议成功: $groupId, proposer=$proposerAci, members=${memberAcis.size}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "发起 V2 模式提议失败: $groupId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 接受 V2 模式提议
     * 
     * @param groupId 群组 ID
     * @param memberAci 接受者 ACI
     * @return 是否成功接受
     */
    suspend fun acceptV2Proposal(groupId: String, memberAci: String): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    val currentState = groupV2StatusTable.getGroupState(groupId)
                    if (currentState == null) {
                        Log.w(TAG, "群组状态不存在: $groupId")
                        return@withContext false
                    }
                    
                    if (currentState.status != GroupV2Status.PROPOSING) {
                        Log.w(TAG, "群组不在提议状态: $groupId, status=${currentState.status}")
                        return@withContext false
                    }
                    
                    if (memberAci in currentState.agreedMembers) {
                        Log.d(TAG, "成员已经同意: $groupId, member=$memberAci")
                        return@withContext true
                    }
                    
                    // 添加同意成员
                    val success = groupV2StatusTable.addAgreedMember(groupId, memberAci)
                    if (success) {
                        Log.i(TAG, "成员接受 V2 提议: $groupId, member=$memberAci")
                    }
                    success
                } catch (e: Exception) {
                    Log.e(TAG, "接受 V2 提议失败: $groupId, $memberAci", e)
                    false
                }
            }
        }
    }
    
    /**
     * 检查并激活 V2 模式
     * 
     * 当所有成员都同意后，将状态升级为 FULL_V2_ACTIVE
     * 
     * @param groupId 群组 ID
     * @return 是否成功激活
     */
    suspend fun checkAndActivateV2Mode(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    val currentState = groupV2StatusTable.getGroupState(groupId)
                    if (currentState == null) {
                        Log.w(TAG, "群组状态不存在: $groupId")
                        return@withContext false
                    }
                    
                    if (currentState.status != GroupV2Status.PROPOSING) {
                        Log.d(TAG, "群组不在提议状态: $groupId, status=${currentState.status}")
                        return@withContext false
                    }
                    
                    if (!currentState.isFullyAgreed()) {
                        Log.d(TAG, "群组尚未全员同意: $groupId, agreed=${currentState.agreedMembers.size}, total=${currentState.totalMembers.size}")
                        return@withContext false
                    }
                    
                    // 升级为 FULL_V2_ACTIVE
                    val success = groupV2StatusTable.updateGroupStatus(groupId, GroupV2Status.FULL_V2_ACTIVE)
                    if (success) {
                        Log.i(TAG, "群组 V2 模式激活成功: $groupId")
                    }
                    success
                } catch (e: Exception) {
                    Log.e(TAG, "激活 V2 模式失败: $groupId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 发送群组消息（V2 模式下）
     * 
     * TODO: 在 Phase 4 中实现完整功能
     * 
     * @param groupId 群组 ID
     * @param message 消息内容
     * @return 发送结果
     */
    suspend fun sendGroupMessage(groupId: String, message: ByteArray): GroupSendResult {
        // 占位实现，Phase 4 中完善
        return GroupSendResult.Failed(
            groupId = groupId,
            messageId = "",
            reason = "Not implemented yet",
            memberCount = 0
        )
    }
    
    /**
     * 处理新成员加入
     * 
     * TODO: 在 Phase 5 中实现
     * 
     * @param groupId 群组 ID
     * @param newMemberAci 新成员 ACI
     */
    suspend fun handleMemberJoin(groupId: String, newMemberAci: String) {
        Log.d(TAG, "处理新成员加入: $groupId, member=$newMemberAci (暂未实现)")
    }
    
    /**
     * 处理成员离开
     * 
     * TODO: 在 Phase 5 中实现
     * 
     * @param groupId 群组 ID
     * @param leftMemberAci 离开成员 ACI
     */
    suspend fun handleMemberLeave(groupId: String, leftMemberAci: String) {
        Log.d(TAG, "处理成员离开: $groupId, member=$leftMemberAci (暂未实现)")
    }
    
    /**
     * 禁用 V2 模式
     * 
     * @param groupId 群组 ID
     * @return 是否成功禁用
     */
    suspend fun disableV2Mode(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    val currentState = groupV2StatusTable.getGroupState(groupId)
                    if (currentState == null) {
                        Log.w(TAG, "群组状态不存在: $groupId")
                        return@withContext false
                    }
                    
                    // 重置为原生状态
                    val resetState = currentState.reset()
                    groupV2StatusTable.insertOrUpdateGroupState(resetState)
                    
                    Log.i(TAG, "群组 V2 模式已禁用: $groupId")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "禁用 V2 模式失败: $groupId", e)
                    false
                }
            }
        }
    }
    
    /**
     * 获取所有活跃的 V2 群组
     */
    fun getAllActiveV2Groups(): List<GroupV2State> {
        return stateLock.read {
            try {
                groupV2StatusTable.getAllActiveV2Groups()
            } catch (e: Exception) {
                Log.e(TAG, "获取活跃 V2 群组失败", e)
                emptyList()
            }
        }
    }
    
    /**
     * 获取处于特定状态的群组
     */
    fun getGroupsByStatus(status: GroupV2Status): List<GroupV2State> {
        return stateLock.read {
            try {
                groupV2StatusTable.getGroupsByStatus(status)
            } catch (e: Exception) {
                Log.e(TAG, "获取特定状态群组失败: $status", e)
                emptyList()
            }
        }
    }
}

