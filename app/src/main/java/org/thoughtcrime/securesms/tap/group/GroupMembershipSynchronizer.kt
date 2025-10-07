package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tap.utils.LogSanitizer

/**
 * 群组成员同步器
 * 
 * 负责检测群组成员变动并触发相应的 v2 mode 处理逻辑
 * 包括：
 * - 检测新成员加入
 * - 检测成员离开
 * - 同步群组状态
 * - 处理成员列表不一致
 */
class GroupMembershipSynchronizer private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupMembershipSynchronizer::class.java)
        
        @Volatile
        private var INSTANCE: GroupMembershipSynchronizer? = null
        
        @JvmStatic
        fun getInstance(context: Context): GroupMembershipSynchronizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupMembershipSynchronizer(context.applicationContext).also {
                    INSTANCE = it
                    Log.d(TAG, "创建 GroupMembershipSynchronizer 实例")
                }
            }
        }
    }
    
    private val groupTransportManager by lazy {
        GroupTransportManager.getInstance(context)
    }
    
    /**
     * 检查并同步群组成员状态
     * 
     * 对比 Signal 数据库中的群组成员和 v2 状态中记录的成员，
     * 检测变化并触发相应的处理
     * 
     * @param groupId 群组 ID（Base64 编码）
     * @return 是否检测到变化并成功处理
     */
    suspend fun syncGroupMembership(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始同步群组成员: groupId=$groupId")
                
                // 1. 获取群组 v2 状态
                val groupState = groupTransportManager.getGroupStateSync(groupId)
                if (groupState == null || groupState.status == GroupV2Status.NATIVE) {
                    Log.d(TAG, "群组未启用 v2 mode，无需同步: $groupId")
                    return@withContext true
                }
                
                // 2. 从 Signal 数据库获取当前成员列表
                val currentMembers = getCurrentGroupMembers(groupId)
                if (currentMembers == null) {
                    Log.w(TAG, "无法获取群组成员列表: $groupId")
                    return@withContext false
                }
                
                Log.d(TAG, "群组当前成员数: ${currentMembers.size}, v2 记录成员数: ${groupState.totalMembers.size}")
                
                // 3. 检测变化
                val addedMembers = currentMembers - groupState.totalMembers
                val removedMembers = groupState.totalMembers - currentMembers
                
                if (addedMembers.isEmpty() && removedMembers.isEmpty()) {
                    Log.d(TAG, "群组成员无变化: $groupId")
                    return@withContext true
                }
                
                Log.i(TAG, "检测到群组成员变化: 新增=${addedMembers.size}, 移除=${removedMembers.size}")
                
                // 4. 处理移除的成员
                for (removedMember in removedMembers) {
                    try {
                        val success = groupTransportManager.handleMemberLeave(groupId, removedMember)
                        if (success) {
                            Log.i(TAG, "成员离开处理成功: memberAci=${LogSanitizer.sanitize(removedMember)}")
                        } else {
                            Log.w(TAG, "成员离开处理失败: memberAci=${LogSanitizer.sanitize(removedMember)}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理成员离开异常: memberAci=${LogSanitizer.sanitize(removedMember)}", e)
                    }
                }
                
                // 5. 处理新增的成员
                for (addedMember in addedMembers) {
                    try {
                        val success = groupTransportManager.handleMemberJoin(
                            groupId = groupId,
                            newMemberAci = addedMember,
                            allCurrentMemberAcis = currentMembers
                        )
                        if (success) {
                            Log.i(TAG, "成员加入处理成功: memberAci=${LogSanitizer.sanitize(addedMember)}")
                        } else {
                            Log.w(TAG, "成员加入处理失败: memberAci=${LogSanitizer.sanitize(addedMember)}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理成员加入异常: memberAci=${LogSanitizer.sanitize(addedMember)}", e)
                    }
                }
                
                Log.i(TAG, "群组成员同步完成: groupId=$groupId")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "同步群组成员失败: groupId=$groupId", e)
                false
            }
        }
    }
    
    /**
     * 检测群组状态不一致
     * 
     * 检查 v2 状态中的成员列表与实际群组成员是否一致
     * 
     * @param groupId 群组 ID
     * @return 是否存在不一致
     */
    suspend fun detectInconsistency(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val groupState = groupTransportManager.getGroupStateSync(groupId)
                if (groupState == null || groupState.status == GroupV2Status.NATIVE) {
                    return@withContext false
                }
                
                val currentMembers = getCurrentGroupMembers(groupId)
                if (currentMembers == null) {
                    return@withContext false
                }
                
                val inconsistent = groupState.totalMembers != currentMembers
                
                if (inconsistent) {
                    Log.w(TAG, "检测到群组成员不一致: groupId=$groupId, " +
                            "v2记录=${groupState.totalMembers.size}个, " +
                            "实际=${currentMembers.size}个")
                }
                
                inconsistent
                
            } catch (e: Exception) {
                Log.e(TAG, "检测群组状态不一致失败: groupId=$groupId", e)
                false
            }
        }
    }
    
    /**
     * 强制重新同步群组状态
     * 
     * 忽略当前 v2 状态，完全基于 Signal 数据库重建状态
     * 
     * @param groupId 群组 ID
     * @return 是否成功重新同步
     */
    suspend fun forceResync(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "强制重新同步群组: groupId=$groupId")
                
                // 1. 获取当前 v2 状态
                val groupState = groupTransportManager.getGroupStateSync(groupId)
                if (groupState == null) {
                    Log.w(TAG, "群组状态不存在，无法重新同步: $groupId")
                    return@withContext false
                }
                
                // 2. 获取当前实际成员列表
                val currentMembers = getCurrentGroupMembers(groupId)
                if (currentMembers == null) {
                    Log.e(TAG, "无法获取群组成员列表: $groupId")
                    return@withContext false
                }
                
                // 3. 更新状态中的成员列表
                val updatedState = groupState.copy(
                    totalMembers = currentMembers,
                    // 保留已同意的成员（如果他们还在群组中）
                    agreedMembers = groupState.agreedMembers.intersect(currentMembers),
                    updatedAt = System.currentTimeMillis()
                )
                
                val result = groupTransportManager.updateGroupState(updatedState)
                
                if (result.isSuccess()) {
                    Log.i(TAG, "群组状态强制同步成功: groupId=$groupId, 成员=${currentMembers.size}")
                    true
                } else {
                    Log.w(TAG, "群组状态强制同步失败: groupId=$groupId")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "强制重新同步群组失败: groupId=$groupId", e)
                false
            }
        }
    }
    
    /**
     * 从 Signal 数据库获取群组当前成员列表
     * 
     * @param groupId 群组 ID（Base64 编码）
     * @return 成员 ACI 集合，失败返回 null
     */
    private suspend fun getCurrentGroupMembers(groupId: String): Set<String>? {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 GroupIdConverter 解析群组 ID（支持多种格式）
                val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupId, context)
                val (decodedGroupId, groupRecipient) = when (result) {
                    is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> {
                        result.groupId to Recipient.resolved(result.recipientId)
                    }
                    is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Failed -> {
                        Log.e(TAG, "转换群组 ID 失败: $groupId, 原因: ${result.reason}")
                        return@withContext null
                    }
                }
                
                if (!groupRecipient.isGroup) {
                    Log.w(TAG, "不是群组 Recipient: $groupId")
                    return@withContext null
                }
                
                // 获取群组成员
                val members = SignalDatabase.groups.getGroupMembers(decodedGroupId, GroupTable.MemberSet.FULL_MEMBERS_INCLUDING_SELF)
                
                // 转换为 ACI 集合
                val memberAcis = members.mapNotNull { recipient ->
                    try {
                        recipient.aci.orElse(null)?.toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "无法获取成员 ACI: recipientId=${recipient.id}", e)
                        null
                    }
                }.toSet()
                
                Log.d(TAG, "获取群组成员成功: groupId=$groupId, count=${memberAcis.size}")
                memberAcis
                
            } catch (e: Exception) {
                Log.e(TAG, "获取群组成员失败: groupId=$groupId", e)
                null
            }
        }
    }
    
    /**
     * 批量同步所有活跃 v2 群组
     * 
     * @return 成功同步的群组数量
     */
    suspend fun syncAllActiveGroups(): Int {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始批量同步所有活跃 v2 群组")
                
                val activeGroups = groupTransportManager.getAllActiveV2Groups()
                var successCount = 0
                
                for (groupState in activeGroups) {
                    try {
                        val success = syncGroupMembership(groupState.groupId)
                        if (success) {
                            successCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "同步群组失败: groupId=${groupState.groupId}", e)
                    }
                }
                
                Log.i(TAG, "批量同步完成: 成功=$successCount/${activeGroups.size}")
                successCount
                
            } catch (e: Exception) {
                Log.e(TAG, "批量同步失败", e)
                0
            }
        }
    }
}
