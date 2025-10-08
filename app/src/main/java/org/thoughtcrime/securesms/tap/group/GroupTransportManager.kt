package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportTokenPool
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
    suspend fun getGroupStatus(groupId: String): GroupOperationResult<GroupV2Status> {
        return withContext(Dispatchers.IO) {
            stateLock.read {
                try {
                    val state = groupV2StatusTable.getGroupState(groupId)
                    if (state != null) {
                        GroupOperationResult.Success(state.status)
                    } else {
                        GroupOperationResult.Success(GroupV2Status.NATIVE)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "获取群组状态失败: $groupId", e)
                    GroupOperationResult.Failed(
                        error = GroupOperationError.DATABASE_ERROR,
                        message = "获取群组状态失败: $groupId",
                        cause = e
                    )
                }
            }
        }
    }
    
    /**
     * 获取群组状态（同步版本，用于向后兼容）
     * @deprecated 使用 suspend 版本的 getGroupStatus
     */
    @Deprecated("使用 suspend 版本", ReplaceWith("getGroupStatus(groupId)"))
    fun getGroupStatusSync(groupId: String): GroupV2Status {
        return try {
            Log.d(TAG, "getGroupStatusSync: 查询群组状态, groupId=${groupId.substring(0, Math.min(20, groupId.length))}...")
            val state = groupV2StatusTable.getGroupState(groupId)
            val status = state?.status ?: GroupV2Status.NATIVE
            
            if (state != null) {
                Log.d(TAG, "getGroupStatusSync: 找到群组状态, groupId=$groupId, status=$status, version=${state.version}, memberCount=${state.totalMembers.size}")
            } else {
                Log.d(TAG, "getGroupStatusSync: 未找到群组状态, groupId=$groupId, 返回 NATIVE")
            }
            
            status
        } catch (e: Exception) {
            Log.e(TAG, "getGroupStatusSync: 获取群组状态失败, groupId=$groupId", e)
            GroupV2Status.NATIVE
        }
    }
    
    /**
     * 获取群组完整状态
     */
    suspend fun getGroupState(groupId: String): GroupOperationResult<GroupV2State?> {
        return withContext(Dispatchers.IO) {
            stateLock.read {
                try {
                    val state = groupV2StatusTable.getGroupState(groupId)
                    GroupOperationResult.Success(state)
                } catch (e: Exception) {
                    Log.e(TAG, "获取群组完整状态失败: $groupId", e)
                    GroupOperationResult.Failed(
                        error = GroupOperationError.DATABASE_ERROR,
                        message = "获取群组完整状态失败: $groupId",
                        cause = e
                    )
                }
            }
        }
    }
    
    /**
     * 获取群组完整状态（同步版本，用于向后兼容）
     * @deprecated 使用 suspend 版本的 getGroupState
     */
    @Deprecated("使用 suspend 版本", ReplaceWith("getGroupState(groupId)"))
    fun getGroupStateSync(groupId: String): GroupV2State? {
        return try {
            groupV2StatusTable.getGroupState(groupId)
        } catch (e: Exception) {
            Log.e(TAG, "获取群组完整状态失败: $groupId", e)
            null
        }
    }
    
    /**
     * 更新群组状态
     */
    suspend fun updateGroupStatus(groupId: String, status: GroupV2Status): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
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
    
    /**
     * 更新群组完整状态
     * 
     * @throws GroupOperationException 当状态无效或更新失败时
     * @throws GroupV2StatusTable.OptimisticLockException 当发生乐观锁冲突时
     */
    suspend fun updateGroupState(state: GroupV2State): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                if (!state.validate()) {
                    Log.w(TAG, "群组状态无效，拒绝更新: ${state.groupId}")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.INVALID_STATE,
                        message = "群组状态无效: ${state.groupId}"
                    )
                }
                
                // 传入状态对象中的版本号作为期望版本，让数据库层做乐观锁检查
                groupV2StatusTable.insertOrUpdateGroupState(state, expectedVersion = state.version)
                Log.i(TAG, "群组状态更新成功: ${state.groupId}, status=${state.status}")
                GroupOperationResult.Success(Unit)
                
            } catch (e: org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable.OptimisticLockException) {
                Log.w(TAG, "更新群组状态乐观锁冲突: ${state.groupId}", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.OPTIMISTIC_LOCK_CONFLICT,
                    message = "并发更新冲突: ${state.groupId}",
                    cause = e
                )
            } catch (e: Exception) {
                Log.e(TAG, "更新群组状态异常: ${state.groupId}", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.DATABASE_ERROR,
                    message = "更新群组状态失败: ${state.groupId}",
                    cause = e
                )
            }
        }
    }
    
    /**
     * 创建或更新群组状态（用于接收者初始化）
     * 
     * 当接收者收到 GROUP_OFFER 消息时，需要在本地数据库创建群组状态记录。
     * 如果状态已存在，会判断是否需要更新：
     * - NATIVE -> PROPOSING: 允许更新（接收到提议消息）
     * - 其他情况: 跳过更新（避免覆盖已有的提议或激活状态）
     * 
     * Note: Caller should ensure this is called from Dispatchers.IO context
     * 
     * @param groupId 群组 ID
     * @param initialState 初始状态
     * @return 是否成功创建或更新
     */
    suspend fun createOrUpdateGroupState(groupId: String, initialState: GroupV2State): Boolean {
        return try {
            val existing = groupV2StatusTable.getGroupState(groupId)
            if (existing == null) {
                // 创建新状态
                groupV2StatusTable.insertOrUpdateGroupState(initialState, expectedVersion = null)
                Log.i(TAG, "接收者创建群组状态: groupId=$groupId, status=${initialState.status}, " +
                    "proposer=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(initialState.proposerAci ?: "null")}, " +
                    "totalMembers=${initialState.totalMembers.size}, agreedMembers=${initialState.agreedMembers.size}")
                true
            } else {
                // 状态已存在，判断是否需要更新
                if (existing.status == GroupV2Status.NATIVE && initialState.status == GroupV2Status.PROPOSING) {
                    // 允许从 NATIVE 状态升级到 PROPOSING 状态（接收到提议消息）
                    Log.i(TAG, "更新群组状态: groupId=$groupId, NATIVE -> PROPOSING, " +
                        "proposer=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(initialState.proposerAci ?: "null")}")
                    groupV2StatusTable.insertOrUpdateGroupState(
                        initialState.copy(version = existing.version),
                        expectedVersion = existing.version
                    )
                    true
                } else {
                    // 其他情况跳过更新（避免覆盖更高级的状态或重复消息）
                    Log.d(TAG, "群组状态已存在，跳过更新: groupId=$groupId, existingStatus=${existing.status}, requestedStatus=${initialState.status}")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建或更新群组状态失败: groupId=$groupId", e)
            false
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
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
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
                
                // 如果当前状态存在（NATIVE），传递版本号；否则传 null（新建）
                val expectedVersion = currentState?.version
                groupV2StatusTable.insertOrUpdateGroupState(newState, expectedVersion = expectedVersion)
                Log.i(TAG, "群组 V2 模式提议成功: $groupId, proposer=$proposerAci, members=${memberAcis.size}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "发起 V2 模式提议失败: $groupId", e)
                false
            }
        }
    }
    
    /**
     * 完整的群组 V2 提议流程
     * 
     * 包括生成 tokens、发送消息、更新状态等所有步骤
     * 
     * @param groupId 群组 ID
     * @param memberRecipientIds 所有成员的 RecipientId 列表（包括自己）
     * @param providerType Provider 类型
     * @return 是否成功发起提议
     */
    suspend fun proposeV2ModeComplete(
        groupId: String,
        memberRecipientIds: List<org.thoughtcrime.securesms.recipients.RecipientId>,
        providerType: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始完整的群组 V2 提议流程: groupId=$groupId, members=${memberRecipientIds.size}")
                
                // 1. 检查 provider 配置
                val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                val providerConfig = configManager.getProviderConfig(providerType)
                if (providerConfig == null) {
                    Log.e(TAG, "Provider 配置不存在: $providerType")
                    return@withContext false
                }
                
                // 2. 获取所有成员的 ACI
                val memberAcis = memberRecipientIds.mapNotNull { recipientId ->
                    try {
                        org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId).requireAci().toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "无法获取成员 ACI: recipientId=$recipientId", e)
                        null
                    }
                }.toSet()
                
                if (memberAcis.size != memberRecipientIds.size) {
                    Log.w(TAG, "部分成员 ACI 获取失败: ${memberRecipientIds.size - memberAcis.size} 个成员")
                }
                
                // 3. 获取我的 ACI
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                
                // 4. 生成我的群组token（只生成一个）
                Log.i(TAG, "为群组生成我的token: groupId=$groupId")
                val myGroupToken = generateMyGroupToken(
                    groupId = groupId,
                    providerType = providerType
                )
                
                if (myGroupToken == null) {
                    Log.e(TAG, "生成群组token失败")
                    return@withContext false
                }
                
                // 5. 保存我的sharedToken（使用groupId作为key）
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                val saved = tokenPool.addSharedToken(groupId, myGroupToken, groupId)
                if (!saved) {
                    Log.e(TAG, "保存群组token失败")
                    return@withContext false
                }
                
                Log.i(TAG, "群组token已保存: groupId=$groupId, tokenId=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(myGroupToken.tokenId)}")
                
                // 6. 更新群组状态为 PROPOSING
                val stateUpdated = proposeV2Mode(
                    groupId = groupId,
                    proposerAci = myAci,
                    memberAcis = memberAcis,
                    providerType = providerType
                )
                
                if (!stateUpdated) {
                    Log.e(TAG, "更新群组状态失败")
                    return@withContext false
                }
                
                // 7. 发送提议消息给所有成员（包含我的token）
                Log.i(TAG, "发送群组提议消息: groupId=$groupId")
                val helper = org.thoughtcrime.securesms.tap.group.GroupTokenExchangeHelper.getInstance(context)
                val sent = helper.sendGroupOfferMessage(
                    groupId = groupId,
                    proposerAci = myAci,
                    memberRecipientIds = memberRecipientIds,
                    myToken = myGroupToken, // 只发送我的token
                    providerType = providerType
                )
                
                if (sent) {
                    Log.i(TAG, "群组 V2 提议完成: groupId=$groupId")
                    
                    // 8. 插入系统消息
                    val groupRecipientId = memberRecipientIds.firstOrNull { recipientId ->
                        org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId).isGroup
                    }
                    
                    if (groupRecipientId != null) {
                        // 插入自定义文本的系统消息
                        helper.insertCustomSystemMessage(
                            recipientId = groupRecipientId,
                            messageBody = "v2 mode 提议已发起"
                        )
                    }
                    
                    true
                } else {
                    Log.e(TAG, "发送群组提议消息失败")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "完整群组 V2 提议流程失败: groupId=$groupId", e)
                false
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
            try {
                Log.d(TAG, "开始接受 V2 提议: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                
                // 直接调用数据库操作，不持有应用层锁
                // addAgreedMember 已优化：在单个事务内完成查询和更新，避免死锁
                // 内部实现了乐观锁机制和重试，可以安全处理并发
                
                // 预先进行状态检查（可选，用于早期失败）
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext false
                }
                
                Log.d(TAG, "当前群组状态: groupId=$groupId, status=${currentState.status}, " +
                        "agreedMembers=${currentState.agreedMembers.size}/${currentState.totalMembers.size}, " +
                        "proposer=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(currentState.proposerAci ?: "null")}")
                
                if (currentState.status != GroupV2Status.PROPOSING) {
                    Log.w(TAG, "群组不在提议状态: $groupId, status=${currentState.status}")
                    return@withContext false
                }
                
                if (memberAci in currentState.agreedMembers) {
                    Log.d(TAG, "成员已经同意: $groupId, member=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                    return@withContext true
                }
                
                Log.d(TAG, "添加前 agreedMembers: ${currentState.agreedMembers.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
                
                // 执行原子更新操作（单一事务，避免死锁）
                val success = groupV2StatusTable.addAgreedMember(groupId, memberAci)
                if (success) {
                    Log.i(TAG, "成员接受 V2 提议成功: $groupId, member=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                    
                    // 重新读取状态验证
                    val updatedState = groupV2StatusTable.getGroupState(groupId)
                    if (updatedState != null) {
                        Log.d(TAG, "更新后 agreedMembers: ${updatedState.agreedMembers.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
                        Log.d(TAG, "是否全员同意: ${updatedState.isFullyAgreed()}")
                    }
                } else {
                    Log.w(TAG, "成员接受 V2 提议失败（可能由于并发冲突）: $groupId, member=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "接受 V2 提议失败: $groupId, ${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                false
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
            try {
                Log.d(TAG, "[状态检查] 检查是否可激活 V2 模式: groupId=$groupId")
                
                // 直接调用数据库操作，不持有应用层锁
                // 避免"应用层锁 → 数据库事务"的嵌套导致死锁
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "[状态检查] 群组状态不存在: $groupId")
                    return@withContext false
                }
                
                Log.d(TAG, "[状态检查] 群组当前状态: groupId=$groupId, status=${currentState.status}, " +
                        "agreed=${currentState.agreedMembers.size}/${currentState.totalMembers.size}, " +
                        "proposer=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(currentState.proposerAci ?: "unknown")}")
                Log.d(TAG, "[状态检查] 同意成员: ${currentState.agreedMembers.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
                Log.d(TAG, "[状态检查] 全部成员: ${currentState.totalMembers.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
                
                if (currentState.status != GroupV2Status.PROPOSING) {
                    Log.d(TAG, "[状态检查] 群组不在提议状态，无法激活: $groupId, status=${currentState.status}")
                    return@withContext false
                }
                
                val isFullyAgreed = currentState.isFullyAgreed()
                Log.d(TAG, "[状态检查] 全员同意检查: isFullyAgreed=$isFullyAgreed " +
                    "(agreed=${currentState.agreedMembers.size}, total=${currentState.totalMembers.size})")
                
                if (!isFullyAgreed) {
                    val notAgreedMembers = currentState.totalMembers - currentState.agreedMembers
                    Log.d(TAG, "[状态检查] 群组尚未全员同意: $groupId, " +
                        "未同意成员(${notAgreedMembers.size}): ${notAgreedMembers.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
                    return@withContext false
                }
                
                Log.i(TAG, "[状态激活] ✅ 所有成员已同意，开始激活 V2 模式: $groupId")
                
                // 升级为 FULL_V2_ACTIVE（数据库内部有事务保证原子性）
                val success = groupV2StatusTable.updateGroupStatus(groupId, GroupV2Status.FULL_V2_ACTIVE)
                if (success) {
                    Log.i(TAG, "[状态激活] ✅ 群组 V2 模式激活成功: $groupId, PROPOSING → FULL_V2_ACTIVE")
                    
                    // 验证激活后的状态
                    val activatedState = groupV2StatusTable.getGroupState(groupId)
                    if (activatedState != null) {
                        Log.d(TAG, "[状态激活] 激活后验证: status=${activatedState.status}, version=${activatedState.version}")
                    }
                } else {
                    Log.e(TAG, "[状态激活] ❌ 群组 V2 模式激活失败（数据库更新失败）: $groupId")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "[状态激活] ❌ 激活 V2 模式异常: $groupId", e)
                false
            }
        }
    }
    
    /**
     * 为群组生成我的Token
     * 
     * 每个成员为群组创建一个目录和一个只读token，供其他成员轮询使用。
     * 目录结构：/group/{groupId}/outbox/messages/ 和 /group/{groupId}/outbox/attachments/
     * 
     * @param groupId 群组 ID
     * @param providerType Provider 类型
     * @return 生成的群组 Token，失败返回null
     */
    suspend fun generateMyGroupToken(
        groupId: String,
        providerType: String
    ): org.thoughtcrime.securesms.tap.TransportToken? {
        return withContext(Dispatchers.IO) {
            stateLock.read {
                try {
                    Log.i(TAG, "开始为群组生成我的Token: groupId=$groupId, provider=$providerType")
                    
                    // 获取 TransportProvider
                    val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                    val provider = transportManager.getProvider(providerType)
                    if (provider == null) {
                        Log.e(TAG, "Provider 不存在: $providerType")
                        return@withContext null
                    }
                    
                    if (!provider.supportsAuth) {
                        Log.e(TAG, "Provider 不支持权限管理: $providerType")
                        return@withContext null
                    }
                    
                    // 获取 Provider 配置
                    val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                    val providerConfig = configManager.getProviderConfig(providerType)
                    if (providerConfig == null) {
                        Log.e(TAG, "Provider 配置不存在: $providerType")
                        return@withContext null
                    }
                    
                    // 构建群组token请求（只读权限，供其他成员轮询）
                    val tokenRequest = org.thoughtcrime.securesms.tap.TransportTokenRequest(
                        recipientId = groupId, // 使用groupId而不是memberAci
                        providerType = providerType,
                        requestedPermissions = setOf(
                            org.thoughtcrime.securesms.tap.TransportPermission.READ,
                            org.thoughtcrime.securesms.tap.TransportPermission.LIST
                        ),
                        validityDurationMs = 0L, // 使用默认有效期
                        providerConfig = providerConfig,
                        purpose = "group_shared:$groupId" // 标识为群组共享token
                    )
                    
                    // 使用provider的generateGroupToken方法生成群组token
                    val token = provider.generateGroupToken(groupId, tokenRequest)
                    
                    if (token != null) {
                        Log.i(TAG, "群组Token生成成功: groupId=$groupId, tokenId=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(token.tokenId)}")
                    } else {
                        Log.e(TAG, "群组Token生成失败: groupId=$groupId")
                    }
                    
                    token
                    
                } catch (e: Exception) {
                    Log.e(TAG, "生成群组Token失败: groupId=$groupId", e)
                    null
                }
            }
        }
    }
    
    /**
     * 建立群组通道
     * 
     * 为群组的所有成员建立传输通道，支持并发创建
     * 
     * @param groupId 群组 ID
     * @param memberAcis 所有成员 ACI 集合（不包括自己）
     * @param providerType Provider 类型
     * @return 建立结果 (成功数, 失败成员列表)
     */
    suspend fun establishGroupChannels(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Pair<Int, List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始建立群组通道: groupId=$groupId, members=${memberAcis.size}, provider=$providerType")
                
                // 获取通道管理器
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                val provider = transportManager.getProvider(providerType)
                
                if (provider == null) {
                    Log.e(TAG, "Provider 不存在: $providerType")
                    return@withContext Pair(0, memberAcis.toList())
                }
                
                var successCount = 0
                val failedMembers = mutableListOf<String>()

                // 使用稳定的成员列表，保证索引与结果一一对应
                val memberList = memberAcis.toList()

                // 并发为每个成员建立通道
                supervisorScope {
                    val jobs: List<Deferred<Boolean>> = memberList.map { memberAci ->
                        async(Dispatchers.IO) {
                            try {
                                val channel = channelManager.getOrCreateChannel(
                                    recipientId = memberAci,
                                    providerType = providerType,
                                    provider = provider
                                )
                                
                                if (channel != null) {
                                    // 在通道配置中标记群组 ID
                                    val groupConfig = channel.config.toMutableMap()
                                    groupConfig["groupId"] = groupId
                                    
                                    // 更新通道配置
                                    val updatedChannel = channel.copy(config = groupConfig)
                                    
                                    // 保存更新后的通道
                                    // (TransportChannelManager 会自动保存)
                                    
                                    Log.d(TAG, "群组成员通道建立成功: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}, channelId=${updatedChannel.channelId}")
                                    true
                                } else {
                                    Log.w(TAG, "群组成员通道建立失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                                    false
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "建立群组成员通道时异常: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                                false
                            }
                        }
                    }
                    // 等待所有任务完成并统计结果
                    jobs.forEachIndexed { index, deferred ->
                        val memberAci = memberList[index]
                        val success = deferred.await()
                        if (success) {
                            successCount++
                        } else {
                            failedMembers.add(memberAci)
                        }
                    }
                }
                
                Log.i(TAG, "群组通道建立完成: groupId=$groupId, 成功=$successCount/${memberAcis.size}, 失败=${failedMembers.size}")
                Pair(successCount, failedMembers)
                
            } catch (e: Exception) {
                Log.e(TAG, "建立群组通道失败: groupId=$groupId", e)
                Pair(0, memberAcis.toList())
            }
        }
    }
    
    /**
     * 保存群组 Token 到 Token 池
     * 
     * 将生成的群组 token 批量保存到 TransportTokenPool，标记为共享 token
     * 
     * @param groupId 群组 ID
     * @param tokens Token 映射 (memberAci -> TransportToken)
     * @return 保存成功的数量
     */
    suspend fun saveGroupTokensToPool(
        groupId: String,
        tokens: Map<String, org.thoughtcrime.securesms.tap.TransportToken>
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                var savedCount = 0
                
                for ((memberAci, token) in tokens) {
                    try {
                        // 保存为共享 token (我们生成的，供成员轮询)
                        // 添加 groupId 参数以标记这是群组 Token
                        val success = tokenPool.addSharedToken(memberAci, token, groupId)
                        if (success) {
                            savedCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "保存群组 Token 失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                    }
                }
                
                Log.i(TAG, "群组 Token 保存完成: groupId=$groupId, 成功=$savedCount/${tokens.size}")
                savedCount
                
            } catch (e: Exception) {
                Log.e(TAG, "保存群组 Tokens 到池失败: groupId=$groupId", e)
                0
            }
        }
    }
    
    /**
     * 发送群组消息（V2 模式下）
     * 
     * 为群组的所有成员并发上传消息，支持部分失败处理
     * 
     * @param groupId 群组 ID
     * @param encryptedMessage 加密后的消息内容（Signal 协议已加密）
     * @param messageId 消息 ID
     * @return 发送结果
     */
    suspend fun sendGroupMessage(
        groupId: String,
        encryptedMessage: ByteArray,
        messageId: String
    ): GroupSendResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "sendGroupMessage: 开始发送, groupId=${groupId.substring(0, Math.min(20, groupId.length))}..., messageId=$messageId, ciphertextSize=${encryptedMessage.size}")
                
                // 1. 检查群组状态
                Log.d(TAG, "sendGroupMessage: 检查群组状态, groupId=$groupId")
                val groupState = getGroupStateSync(groupId)
                
                if (groupState == null) {
                    Log.e(TAG, "sendGroupMessage: ❌ 群组状态不存在, groupId=$groupId")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "群组状态不存在",
                        memberCount = 0
                    )
                }
                
                Log.d(TAG, "sendGroupMessage: 群组状态查询成功, status=${groupState.status}, members=${groupState.totalMembers.size}")
                
                if (groupState.status != GroupV2Status.FULL_V2_ACTIVE) {
                    Log.e(TAG, "sendGroupMessage: ❌ 群组未激活, groupId=$groupId, status=${groupState.status} (需要 FULL_V2_ACTIVE)")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "群组未激活 V2 模式: ${groupState.status}",
                        memberCount = groupState.totalMembers.size
                    )
                }
                
                // 2. 获取我的 ACI
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                
                // 3. 获取群组其他成员数量（用于返回结果）
                val otherMembersCount = groupState.totalMembers.filter { it != myAci }.size
                
                // 4. 获取 Provider
                val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                val provider = transportManager.getProvider(groupState.providerType)
                if (provider == null) {
                    Log.e(TAG, "Provider 不存在: ${groupState.providerType}")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "Provider 不可用",
                        memberCount = otherMembersCount
                    )
                }
                
                // 5. 获取我的群组sharedToken
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                val myGroupToken = tokenPool.getGroupSharedToken(groupId, groupState.providerType)
                if (myGroupToken == null) {
                    Log.e(TAG, "无法获取群组SharedToken: groupId=$groupId")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "群组Token不可用",
                        memberCount = otherMembersCount
                    )
                }
                
                // 6. 构建群组消息metadata（指向我的群组目录）
                val metadata = buildGroupSendMetadata(groupId, myGroupToken, groupState.providerType)
                
                // 7. 构建TransportMessage (保持原始messageId，简洁清晰)
                val transportMessage = org.thoughtcrime.securesms.tap.TransportMessage(
                    messageId = messageId,
                    timestamp = System.currentTimeMillis(),
                    senderId = myAci,
                    recipientId = groupId, // 使用groupId作为recipientId
                    messageType = org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE,
                    signalCiphertext = org.signal.core.util.Base64.encodeWithPadding(encryptedMessage),
                    contentMetadata = org.thoughtcrime.securesms.tap.TransportContentMetadata(
                        originalSize = encryptedMessage.size.toLong()
                    )
                )
                
                Log.d(TAG, "上传群组消息到我的目录: groupId=$groupId, messageId=$messageId")
                
                // 8. 上传消息到我的群组目录（只上传一次）
                val result = provider.push(transportMessage, metadata)
                
                // 9. 返回结果
                when (result) {
                    is org.thoughtcrime.securesms.tap.TransportResult.Success -> {
                        Log.i(TAG, "群组消息上传成功: groupId=$groupId, messageId=$messageId")
                        GroupSendResult.Success(
                            groupId = groupId,
                            messageId = messageId,
                            memberCount = otherMembersCount
                        )
                    }
                    is org.thoughtcrime.securesms.tap.TransportResult.Failed -> {
                        Log.e(TAG, "群组消息上传失败: groupId=$groupId, messageId=$messageId, error=${result.errorMessage}")
                        GroupSendResult.Failed(
                            groupId = groupId,
                            messageId = messageId,
                            reason = result.errorMessage ?: "上传失败",
                            memberCount = otherMembersCount
                        )
                    }
                    else -> {
                        GroupSendResult.Failed(
                            groupId = groupId,
                            messageId = messageId,
                            reason = "未知错误",
                            memberCount = otherMembersCount
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "群组消息发送异常: groupId=$groupId", e)
                GroupSendResult.Failed(
                    groupId = groupId,
                    messageId = messageId,
                    reason = "发送异常: ${e.message}",
                    memberCount = 0
                )
            }
        }
    }
    
    /**
     * 构建群组消息发送的metadata
     * 
     * 构建指向我的群组目录的metadata，用于上传消息
     * 注意：myToken设置为null以使用主账户凭证（有写权限），而非只读的sharedToken
     */
    private fun buildGroupSendMetadata(
        groupId: String,
        myGroupToken: org.thoughtcrime.securesms.tap.TransportToken,
        providerType: String
    ): org.thoughtcrime.securesms.tap.TransportMetadata {
        // 构建COS群组metadata
        if (providerType == "cos" && myGroupToken is org.thoughtcrime.securesms.tap.CosTransportToken) {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
            val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
            
            // 群组目录路径：/group/{groupId}/ (简化路径，不需要outbox层级)
            val groupPath = "/group/${groupId}/"
            
            return org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
                recipientId = groupId,
                providerType = providerType,
                myAddress = "${myGroupToken.bucketName}.cos.${myGroupToken.region}.myqcloud.com",
                myToken = null, // ✅ 使用null让CosTransportProvider使用主账户凭证（有写权限）
                myRegion = myGroupToken.region,
                myBucketName = myGroupToken.bucketName,
                mySendPath = groupPath, // 发送到我的群组目录
                peerAddress = "${myGroupToken.bucketName}.cos.${myGroupToken.region}.myqcloud.com",
                peerToken = myGroupToken, // 对端token保留（虽然发送时不使用）
                peerRegion = myGroupToken.region,
                peerBucketName = myGroupToken.bucketName,
                peerReceivePath = groupPath,
                myHashedId = myHashedId,
                peerHashedId = myHashedId
            )
        }
        
        // 默认实现（其他provider）
        throw UnsupportedOperationException("不支持的providerType: $providerType")
    }
    
    /**
     * 并发上传消息到所有成员（已废弃 - 群组消息只上传一次）
     */
    @Deprecated("群组消息只上传一次到自己的目录，不再需要并发上传")
    private suspend fun performConcurrentUpload(
        groupId: String,
        messageId: String,
        encryptedMessage: ByteArray,
        memberChannels: Map<String, org.thoughtcrime.securesms.tap.TransportChannel>,
        provider: org.thoughtcrime.securesms.tap.TransportProvider
    ): Map<String, org.thoughtcrime.securesms.tap.TransportResult> {
        return supervisorScope {
            // 为每个成员创建上传任务
            val uploadTasks: List<Pair<String, Deferred<org.thoughtcrime.securesms.tap.TransportResult>>> = 
                memberChannels.map { (memberAci, channel) ->
                    val uploadTask = async(Dispatchers.IO) {
                        uploadMessageToMember(
                            memberAci = memberAci,
                            groupId = groupId,
                            messageId = messageId,
                            encryptedMessage = encryptedMessage,
                            channel = channel,
                            provider = provider
                        )
                    }
                    memberAci to uploadTask
                }
            
            // 等待所有上传完成
            uploadTasks.associate { (memberAci, task) ->
                memberAci to task.await()
            }
        }
    }
    
    /**
     * 上传消息到单个成员
     */
    private suspend fun uploadMessageToMember(
        memberAci: String,
        groupId: String,
        messageId: String,
        encryptedMessage: ByteArray,
        channel: org.thoughtcrime.securesms.tap.TransportChannel,
        provider: org.thoughtcrime.securesms.tap.TransportProvider
    ): org.thoughtcrime.securesms.tap.TransportResult {
        return try {
            // 构建 TransportMessage
            // 使用特殊的 messageId 格式来标识群组消息：group_{groupId}_{originalMessageId}
            val groupMessageId = "group_${groupId}_${messageId}"
            
            val transportMessage = org.thoughtcrime.securesms.tap.TransportMessage(
                messageId = groupMessageId,
                timestamp = System.currentTimeMillis(),
                senderId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString(),
                recipientId = memberAci,
                messageType = org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE,  // 群组消息使用 TEXT_MESSAGE 类型，进入 messages 目录
                signalCiphertext = org.signal.core.util.Base64.encodeWithPadding(encryptedMessage),
                contentMetadata = org.thoughtcrime.securesms.tap.TransportContentMetadata(
                    originalSize = encryptedMessage.size.toLong()
                )
            )
            
            Log.d(TAG, "上传消息到成员: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
            
            // 上传消息
            val result = provider.push(transportMessage, channel.metadata)
            
            if (result is org.thoughtcrime.securesms.tap.TransportResult.Success) {
                Log.i(TAG, "成员消息上传成功: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
            } else {
                Log.w(TAG, "成员消息上传失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}, result=$result")
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "上传消息到成员时异常: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
            org.thoughtcrime.securesms.tap.TransportResult.Failed(
                error = org.thoughtcrime.securesms.tap.TransportError.NETWORK_ERROR,
                errorMessage = "上传异常: ${e.message}",
                retryable = true
            )
        }
    }
    
    /**
     * 处理新成员加入
     * 
     * 根据群组当前状态采取不同策略：
     * - PROPOSING: 回退到 NATIVE 状态
     * - FULL_V2_ACTIVE: 保持激活但不为新成员建立 token（新成员需主动加入）
     * 
     * @param groupId 群组 ID
     * @param newMemberAci 新成员 ACI
     * @param allCurrentMemberAcis 更新后的所有成员 ACI 列表（包括新成员）
     * @return 是否成功处理
     */
    suspend fun handleMemberJoin(
        groupId: String, 
        newMemberAci: String,
        allCurrentMemberAcis: Set<String>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                Log.i(TAG, "处理新成员加入: groupId=$groupId, newMember=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(newMemberAci)}, totalMembers=${allCurrentMemberAcis.size}")
                
                // 1. 获取群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.d(TAG, "群组状态不存在，无需处理: $groupId")
                    return@withContext true
                }
                
                // 2. 如果群组在原生状态，无需处理
                if (currentState.status == GroupV2Status.NATIVE) {
                    Log.d(TAG, "群组在原生状态，无需处理: $groupId")
                    return@withContext true
                }
                
                // 3. 根据当前状态采取不同策略
                when (currentState.status) {
                    GroupV2Status.PROPOSING -> {
                        // PROPOSING 阶段：回退到 NATIVE
                        Log.i(TAG, "群组在提议阶段，回退到原生状态: $groupId")
                        handleMemberJoinDuringProposing(groupId, currentState, allCurrentMemberAcis)
                    }
                    
                    GroupV2Status.FULL_V2_ACTIVE -> {
                        // FULL_V2_ACTIVE 阶段：更新成员列表，但不自动为新成员建立 token
                        // 新成员需要主动请求加入 v2 mode
                        Log.i(TAG, "群组在激活状态，更新成员列表: $groupId")
                        handleMemberJoinDuringActive(groupId, currentState, newMemberAci, allCurrentMemberAcis)
                    }
                    
                    else -> {
                        Log.w(TAG, "未知的群组状态: $groupId, status=${currentState.status}")
                        false
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "处理新成员加入失败: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(newMemberAci)}", e)
                false
            }
        }
    }
    
    /**
     * 处理 PROPOSING 阶段的新成员加入
     * 
     * 策略：回退到 NATIVE 状态，清理所有 v2 mode 资源
     */
    private suspend fun handleMemberJoinDuringProposing(
        groupId: String,
        currentState: GroupV2State,
        allCurrentMemberAcis: Set<String>
    ): Boolean {
        return try {
            Log.i(TAG, "回退群组到原生状态: $groupId")
            
            // 1. 清理所有成员的资源
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val otherMembers = currentState.totalMembers.filter { it != myAci }
            
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            
            for (memberAci in otherMembers) {
                try {
                    // 移除轮询目标
                    pollingService.removePollingTarget(memberAci, currentState.providerType)
                    
                    // 关闭通道
                    val channel = channelManager.getActiveChannel(memberAci, currentState.providerType)
                    if (channel != null) {
                        channelManager.closeChannel(channel.channelId)
                    }
                    
                    // 移除 token
                    tokenPool.removeToken(memberAci, currentState.providerType)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "清理成员资源失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            // 2. 重置群组状态为 NATIVE
            val resetState = currentState.reset().copy(
                totalMembers = allCurrentMemberAcis,
                updatedAt = System.currentTimeMillis()
            )
            // 传递当前版本号进行乐观锁更新
            groupV2StatusTable.insertOrUpdateGroupState(resetState, expectedVersion = currentState.version)
            
            Log.i(TAG, "群组已回退到原生状态: $groupId")
            
            // 3. 插入系统消息通知用户
            val groupRecipientId = getGroupRecipientId(groupId)
            if (groupRecipientId != null) {
                val helper = GroupTokenExchangeHelper.getInstance(context)
                helper.insertCustomSystemMessage(
                    recipientId = groupRecipientId,
                    messageBody = "群组成员变动，v2 mode 提议已取消"
                )
            } else {
                Log.w(TAG, "无法获取群组 RecipientId，跳过系统消息插入: $groupId")
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "处理 PROPOSING 阶段成员加入失败: $groupId", e)
            false
        }
    }
    
    /**
     * 处理 FULL_V2_ACTIVE 阶段的新成员加入
     * 
     * 策略：更新成员列表，但不自动为新成员建立 token
     * 新成员需要通过 UI 主动请求加入 v2 mode
     */
    private suspend fun handleMemberJoinDuringActive(
        groupId: String,
        currentState: GroupV2State,
        newMemberAci: String,
        allCurrentMemberAcis: Set<String>
    ): Boolean {
        return try {
            Log.i(TAG, "更新群组成员列表: $groupId, 新成员=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(newMemberAci)}")
            
            // 更新群组状态：添加新成员到 totalMembers，但不添加到 agreedMembers
            // 新成员需要主动同意并发送 token 才会被添加到 agreedMembers
            val updatedState = currentState.copy(
                totalMembers = allCurrentMemberAcis,
                updatedAt = System.currentTimeMillis()
            )
            
            // 传递当前版本号进行乐观锁更新
            groupV2StatusTable.insertOrUpdateGroupState(updatedState, expectedVersion = currentState.version)
            
            Log.i(TAG, "群组成员列表已更新: $groupId, totalMembers=${allCurrentMemberAcis.size}")
            
            // 注意：不插入系统消息，因为新成员加入时 Signal 会自动插入系统消息
            // v2 mode 的提示将在新成员端通过 UI 显示
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "处理 FULL_V2_ACTIVE 阶段成员加入失败: $groupId", e)
            false
        }
    }
    
    /**
     * 处理成员离开
     * 
     * 清理离开成员的 token、关闭相关 channel、移除轮询目标
     * 
     * @param groupId 群组 ID
     * @param leftMemberAci 离开成员 ACI
     * @return 是否成功处理
     */
    suspend fun handleMemberLeave(groupId: String, leftMemberAci: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                Log.i(TAG, "处理成员离开: groupId=$groupId, member=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                
                // 1. 获取群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext false
                }
                
                // 2. 如果群组不在 v2 mode，不需要处理
                if (currentState.status == GroupV2Status.NATIVE) {
                    Log.d(TAG, "群组不在 v2 mode，无需清理: $groupId")
                    return@withContext true
                }
                
                // 3. 移除轮询目标
                try {
                    val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                    val removed = pollingService.removePollingTarget(leftMemberAci, currentState.providerType)
                    if (removed) {
                        Log.i(TAG, "移除成员轮询目标成功: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                    } else {
                        Log.d(TAG, "成员轮询目标可能不存在: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移除轮询目标失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}", e)
                }
                
                // 4. 关闭与该成员的通道
                try {
                    val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                    val channel = channelManager.getActiveChannel(leftMemberAci, currentState.providerType)
                    if (channel != null) {
                        val closed = channelManager.closeChannel(channel.channelId)
                        if (closed) {
                            Log.i(TAG, "关闭成员通道成功: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}, channelId=${channel.channelId}")
                        } else {
                            Log.w(TAG, "关闭成员通道失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                        }
                    } else {
                        Log.d(TAG, "成员通道不存在: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "关闭成员通道失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}", e)
                }
                
                // 5. 移除成员的 token（receivedToken 和 sharedToken）
                try {
                    val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                    val removed = tokenPool.removeToken(leftMemberAci, currentState.providerType)
                    if (removed) {
                        Log.i(TAG, "移除成员 token 成功: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                    } else {
                        Log.d(TAG, "成员 token 可能不存在: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移除成员 token 失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}", e)
                }
                
                // 6. 更新群组状态：从成员列表中移除离开的成员
                val updatedMembers = currentState.totalMembers - leftMemberAci
                val updatedAgreedMembers = currentState.agreedMembers - leftMemberAci
                
                val updatedState = currentState.copy(
                    totalMembers = updatedMembers,
                    agreedMembers = updatedAgreedMembers,
                    updatedAt = System.currentTimeMillis()
                )
                
                // 传递当前版本号进行乐观锁更新
                groupV2StatusTable.insertOrUpdateGroupState(updatedState, expectedVersion = currentState.version)
                
                Log.i(TAG, "成员离开处理完成: groupId=$groupId, 剩余成员=${updatedMembers.size}")
                
                // 7. 检查是否还有足够的成员维持 v2 mode
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                val otherMembersCount = updatedMembers.filter { it != myAci }.size
                
                if (otherMembersCount < 1) {
                    Log.w(TAG, "群组成员不足，禁用 v2 mode: groupId=$groupId")
                    disableV2Mode(groupId)
                }
                
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "处理成员离开失败: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(leftMemberAci)}", e)
                false
            }
        }
    }
    
    /**
     * 新成员请求加入 V2 Mode
     * 
     * 当新成员加入一个已经处于 FULL_V2_ACTIVE 状态的群组时，
     * 可以调用此方法主动加入 v2 mode
     * 
     * @param groupId 群组 ID
     * @param memberRecipientIds 所有成员的 RecipientId 列表
     * @return 是否成功发起加入请求
     */
    suspend fun requestJoinV2Mode(
        groupId: String,
        memberRecipientIds: List<org.thoughtcrime.securesms.recipients.RecipientId>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                Log.i(TAG, "新成员请求加入 v2 mode: groupId=$groupId")
                
                // 1. 检查群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext false
                }
                
                if (currentState.status != GroupV2Status.FULL_V2_ACTIVE) {
                    Log.w(TAG, "群组未处于 FULL_V2_ACTIVE 状态: $groupId, status=${currentState.status}")
                    return@withContext false
                }
                
                // 2. 检查我是否已经是同意成员
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                if (myAci in currentState.agreedMembers) {
                    Log.d(TAG, "当前用户已经加入 v2 mode: $groupId")
                    return@withContext true
                }
                
                // 3. 检查我是否在成员列表中
                if (myAci !in currentState.totalMembers) {
                    Log.w(TAG, "当前用户不在群组成员列表中: $groupId")
                    return@withContext false
                }
                
                // 4. 生成我的群组token（只生成一个）
                Log.i(TAG, "生成我的群组token: groupId=$groupId")
                val myGroupToken = generateMyGroupToken(
                    groupId = groupId,
                    providerType = currentState.providerType
                )
                
                if (myGroupToken == null) {
                    Log.e(TAG, "生成群组token失败")
                    return@withContext false
                }
                
                // 5. 保存我的sharedToken（使用groupId作为key）
                val tokenPool = TransportTokenPool.getInstance(context)
                val saved = tokenPool.addSharedToken(groupId, myGroupToken, groupId)
                if (!saved) {
                    Log.e(TAG, "保存群组token失败")
                    return@withContext false
                }
                
                Log.i(TAG, "群组token已保存: groupId=$groupId, tokenId=${myGroupToken.tokenId}")
                
                // 6. 发送 GROUP_ACCEPT 消息给所有成员（使用新成员加入的方式，包含我的token）
                val helper = GroupTokenExchangeHelper.getInstance(context)
                val sent = helper.sendNewMemberJoinMessage(
                    groupId = groupId,
                    newMemberAci = myAci,
                    memberRecipientIds = memberRecipientIds,
                    myToken = myGroupToken,
                    providerType = currentState.providerType
                )
                
                if (!sent) {
                    Log.e(TAG, "发送新成员加入消息失败")
                    return@withContext false
                }
                
                // 8. 更新本地状态：将自己添加到 agreedMembers
                val success = acceptV2Proposal(groupId, myAci)
                if (!success) {
                    Log.e(TAG, "更新本地状态失败")
                    return@withContext false
                }
                
                Log.i(TAG, "新成员加入 v2 mode 请求已发送: groupId=$groupId")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "新成员请求加入 v2 mode 失败: groupId=$groupId", e)
                false
            }
        }
    }
    
    /**
     * 处理其他成员对新成员加入的响应
     * 
     * 当收到新成员的加入请求后，老成员需要为新成员生成 token 并回复
     * 
     * @param groupId 群组 ID
     * @param newMemberAci 新成员 ACI
     * @param newMemberRecipientId 新成员的 RecipientId
     * @return 是否成功发送响应
     */
    suspend fun respondToNewMemberJoin(
        groupId: String,
        newMemberAci: String,
        newMemberRecipientId: org.thoughtcrime.securesms.recipients.RecipientId
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "响应新成员加入: groupId=$groupId, newMember=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(newMemberAci)}")
                
                // 1. 检查群组状态
                val currentState = getGroupStateSync(groupId)
                if (currentState == null || currentState.status != GroupV2Status.FULL_V2_ACTIVE) {
                    Log.w(TAG, "群组未处于 FULL_V2_ACTIVE 状态: $groupId")
                    return@withContext false
                }
                
                // 2. 获取我的群组sharedToken
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                val myGroupToken = tokenPool.getGroupSharedToken(groupId, currentState.providerType)
                
                if (myGroupToken == null) {
                    Log.e(TAG, "未找到我的群组token: groupId=$groupId")
                    return@withContext false
                }
                
                // 3. 发送我的群组token给新成员
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                val helper = GroupTokenExchangeHelper.getInstance(context)
                
                val sent = helper.sendNewMemberResponseMessage(
                    groupId = groupId,
                    senderAci = myAci,
                    newMemberRecipientId = newMemberRecipientId,
                    myToken = myGroupToken,
                    providerType = currentState.providerType
                )
                
                if (sent) {
                    Log.i(TAG, "新成员响应已发送: groupId=$groupId")
                    
                    // 5. 建立与新成员的通道
                    val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                    val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                    val provider = transportManager.getProvider(currentState.providerType)
                    
                    if (provider != null) {
                        val channel = channelManager.getOrCreateChannel(
                            recipientId = newMemberAci,
                            providerType = currentState.providerType,
                            provider = provider
                        )
                        
                        if (channel != null) {
                            Log.i(TAG, "与新成员的通道已建立: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(newMemberAci)}")
                        }
                    }
                    
                    true
                } else {
                    Log.e(TAG, "发送新成员响应失败")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "响应新成员加入失败: groupId=$groupId", e)
                false
            }
        }
    }
    
    /**
     * 取消 V2 模式提议
     * 
     * 允许提议者在 PROPOSING 阶段主动取消提议
     * 
     * @param groupId 群组 ID
     * @return 操作结果
     */
    suspend fun cancelV2Proposal(groupId: String): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "尝试取消 V2 模式提议: groupId=$groupId")
                
                // 1. 获取群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.GROUP_NOT_FOUND,
                        message = "群组状态不存在"
                    )
                }
                
                // 2. 检查当前状态是否为 PROPOSING
                if (currentState.status != GroupV2Status.PROPOSING) {
                    Log.w(TAG, "群组不在提议状态: $groupId, status=${currentState.status}")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.INVALID_STATE,
                        message = "只能在提议阶段取消"
                    )
                }
                
                // 3. 验证调用者是否为提议者
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                if (currentState.proposerAci != myAci) {
                    Log.w(TAG, "非提议者无法取消提议: groupId=$groupId, proposer=${currentState.proposerAci}, myAci=$myAci")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.PERMISSION_DENIED,
                        message = "只有提议者可以取消提议"
                    )
                }
                
                Log.i(TAG, "提议者取消 V2 模式提议: groupId=$groupId")
                
                // 4. 清理资源
                cleanupGroupResources(groupId, currentState)
                
                // 5. 重置状态为 NATIVE
                val resetState = currentState.reset()
                groupV2StatusTable.insertOrUpdateGroupState(resetState, expectedVersion = currentState.version)
                
                // 6. 插入系统消息
                try {
                    val groupRecipientId = getGroupRecipientId(groupId)
                    if (groupRecipientId != null) {
                        val helper = GroupTokenExchangeHelper.getInstance(context)
                        helper.insertCustomSystemMessage(
                            recipientId = groupRecipientId,
                            messageBody = "v2 mode 提议已取消"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "插入取消提议系统消息失败: groupId=$groupId", e)
                }
                
                // 7. 发送取消通知给其他成员（可选）
                try {
                    val otherMembers = currentState.totalMembers.filter { it != myAci }
                    if (otherMembers.isNotEmpty()) {
                        val memberRecipientIds = getMemberRecipientIds(groupId)
                        if (memberRecipientIds.isNotEmpty()) {
                            val helper = GroupTokenExchangeHelper.getInstance(context)
                            helper.sendGroupDisableMessage(
                                groupId = groupId,
                                senderAci = myAci,
                                memberRecipientIds = memberRecipientIds,
                                providerType = currentState.providerType
                            )
                            Log.d(TAG, "取消提议通知已发送: groupId=$groupId")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "发送取消提议通知失败: groupId=$groupId", e)
                    // 即使通知发送失败也继续，本地状态已经重置
                }
                
                Log.i(TAG, "V2 模式提议取消成功: groupId=$groupId")
                GroupOperationResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "取消 V2 模式提议失败: $groupId", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.OPERATION_FAILED,
                    message = "取消失败: ${e.message}",
                    cause = e
                )
            }
        }
    }
    
    /**
     * 禁用 V2 模式（简单版，仅重置状态）
     * 
     * 用于内部调用或被动禁用场景（如收到禁用消息）
     * 
     * @param groupId 群组 ID
     * @return 是否成功禁用
     */
    suspend fun disableV2Mode(groupId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext false
                }
                
                // 重置为原生状态
                val resetState = currentState.reset()
                // 传递当前版本号进行乐观锁更新
                groupV2StatusTable.insertOrUpdateGroupState(resetState, expectedVersion = currentState.version)
                
                Log.i(TAG, "群组 V2 模式已禁用: $groupId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "禁用 V2 模式失败: $groupId", e)
                false
            }
        }
    }
    
    /**
     * 完整禁用 V2 模式流程
     * 
     * 主动禁用场景，包括：
     * 1. 清理所有通道和 tokens
     * 2. 停止轮询
     * 3. 发送禁用消息给所有成员
     * 4. 插入系统消息
     * 5. 重置群组状态
     * 
     * @param groupId 群组 ID
     * @param sendControlMessage 是否发送禁用控制消息（默认 true）
     * @param insertSystemMessage 是否插入系统消息（默认 true）
     * @param reason 禁用原因（可选，用于日志和调试）
     * @return 是否成功禁用
     */
    suspend fun disableV2ModeComplete(
        groupId: String,
        sendControlMessage: Boolean = true,
        insertSystemMessage: Boolean = true,
        reason: String? = null
    ): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                Log.i(TAG, "开始完整禁用群组 V2 模式: groupId=$groupId, reason=${reason ?: "用户主动禁用"}")
                
                // 1. 获取群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.GROUP_NOT_FOUND,
                        message = "群组状态不存在"
                    )
                }
                
                if (currentState.status == GroupV2Status.NATIVE) {
                    Log.d(TAG, "群组已经是原生状态，无需禁用: $groupId")
                    return@withContext GroupOperationResult.Success(Unit)
                }
                
                // 2. 获取我的 ACI 和其他成员
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                val otherMembers = currentState.totalMembers.filter { it != myAci }
                
                // 3. 清理所有资源
                cleanupGroupResources(groupId, currentState)
                
                // 4. 发送禁用消息（如果需要）
                if (sendControlMessage && otherMembers.isNotEmpty()) {
                    try {
                        val memberRecipientIds = getMemberRecipientIds(groupId)
                        if (memberRecipientIds.isNotEmpty()) {
                            val helper = GroupTokenExchangeHelper.getInstance(context)
                            val sent = helper.sendGroupDisableMessage(
                                groupId = groupId,
                                senderAci = myAci,
                                memberRecipientIds = memberRecipientIds,
                                providerType = currentState.providerType
                            )
                            
                            if (sent) {
                                Log.i(TAG, "群组禁用消息已发送: groupId=$groupId")
                            } else {
                                Log.w(TAG, "群组禁用消息发送失败，但继续禁用流程: groupId=$groupId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "发送群组禁用消息异常: groupId=$groupId", e)
                        // 即使发送失败也继续禁用流程
                    }
                }
                
                // 5. 重置群组状态
                val resetState = currentState.reset()
                // 传递当前版本号进行乐观锁更新
                groupV2StatusTable.insertOrUpdateGroupState(resetState, expectedVersion = currentState.version)
                
                // 6. 插入系统消息（如果需要）
                if (insertSystemMessage) {
                    try {
                        val groupRecipientId = getGroupRecipientId(groupId)
                        if (groupRecipientId != null) {
                            val helper = GroupTokenExchangeHelper.getInstance(context)
                            helper.insertSystemMessage(
                                recipientId = groupRecipientId,
                                messageBody = "v2 mode 已禁用",
                                isEnabled = false
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "插入系统消息失败: groupId=$groupId", e)
                    }
                }
                
                Log.i(TAG, "群组 V2 模式完整禁用成功: groupId=$groupId")
                GroupOperationResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "完整禁用 V2 模式失败: $groupId", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.OPERATION_FAILED,
                    message = "禁用失败: ${e.message}",
                    cause = e
                )
            }
        }
    }
    
    /**
     * 清理群组资源
     * 
     * 包括关闭通道、停止轮询、清理 tokens
     * 
     * @param groupId 群组 ID
     * @param groupState 群组状态
     */
    private suspend fun cleanupGroupResources(groupId: String, groupState: GroupV2State) {
        try {
            Log.i(TAG, "清理群组资源: groupId=$groupId")
            
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val otherMembers = groupState.totalMembers.filter { it != myAci }
            
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            
            var pollingRemoved = 0
            var channelsClosed = 0
            var tokensRemoved = 0
            
            for (memberAci in otherMembers) {
                try {
                    // 移除轮询目标
                    val removed = pollingService.removePollingTarget(memberAci, groupState.providerType)
                    if (removed) {
                        pollingRemoved++
                    }
                    
                    // 关闭通道
                    val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
                    if (channel != null) {
                        val closed = channelManager.closeChannel(channel.channelId)
                        if (closed) {
                            channelsClosed++
                        }
                    }
                    
                    // 移除 token（包括 receivedToken 和 sharedToken）
                    val tokenRemoved = tokenPool.removeToken(memberAci, groupState.providerType)
                    if (tokenRemoved) {
                        tokensRemoved++
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "清理成员资源失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            Log.i(TAG, "群组资源清理完成: groupId=$groupId, " +
                "轮询移除=$pollingRemoved/${otherMembers.size}, " +
                "通道关闭=$channelsClosed/${otherMembers.size}, " +
                "Token移除=$tokensRemoved/${otherMembers.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理群组资源异常: groupId=$groupId", e)
            // 不抛出异常，尽可能完成清理
        }
    }
    
    /**
     * 处理接收到的禁用消息
     * 
     * 当收到其他成员发送的禁用消息时调用
     * 
     * @param groupId 群组 ID
     * @param senderAci 发送者 ACI
     * @return 是否成功处理
     */
    suspend fun handleDisableV2ModeRequest(
        groupId: String,
        senderAci: String
    ): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 直接调用数据库操作，不持有应用层锁，避免死锁
                Log.i(TAG, "处理群组禁用请求: groupId=$groupId, sender=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(senderAci)}")
                
                // 1. 获取群组状态
                val currentState = groupV2StatusTable.getGroupState(groupId)
                if (currentState == null) {
                    Log.w(TAG, "群组状态不存在: $groupId")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.GROUP_NOT_FOUND,
                        message = "群组状态不存在"
                    )
                }
                
                if (currentState.status == GroupV2Status.NATIVE) {
                    Log.d(TAG, "群组已经是原生状态: $groupId")
                    return@withContext GroupOperationResult.Success(Unit)
                }
                
                // 2. 验证发送者是否为群组成员
                if (senderAci !in currentState.totalMembers) {
                    Log.w(TAG, "禁用请求来自非群组成员: sender=$senderAci, groupId=$groupId")
                    return@withContext GroupOperationResult.Failed(
                        error = GroupOperationError.INVALID_MEMBER,
                        message = "发送者不是群组成员"
                    )
                }
                
                // 3. 清理资源（不发送控制消息，避免回声）
                cleanupGroupResources(groupId, currentState)
                
                // 4. 重置状态
                val resetState = currentState.reset()
                // 传递当前版本号进行乐观锁更新
                groupV2StatusTable.insertOrUpdateGroupState(resetState, expectedVersion = currentState.version)
                
                // 5. 插入系统消息
                try {
                    val groupRecipientId = getGroupRecipientId(groupId)
                    if (groupRecipientId != null) {
                        val helper = GroupTokenExchangeHelper.getInstance(context)
                        helper.insertSystemMessage(
                            recipientId = groupRecipientId,
                            messageBody = "v2 mode 已禁用",
                            isEnabled = false
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "插入系统消息失败: groupId=$groupId", e)
                }
                
                Log.i(TAG, "群组禁用请求处理完成: groupId=$groupId")
                GroupOperationResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "处理禁用请求失败: groupId=$groupId", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.OPERATION_FAILED,
                    message = "处理失败: ${e.message}",
                    cause = e
                )
            }
        }
    }
    
    /**
     * 异常降级处理
     * 
     * 当检测到异常情况时（如轮询连续失败、token 过期等），自动降级到原生模式
     * 
     * @param groupId 群组 ID
     * @param reason 降级原因
     * @return 是否成功降级
     */
    suspend fun degradeV2ModeOnError(
        groupId: String,
        reason: String
    ): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.w(TAG, "群组 V2 模式异常降级: groupId=$groupId, reason=$reason")
                
                // 不发送控制消息（避免在网络异常时堆积失败的消息）
                // 但插入系统消息通知用户
                val result = disableV2ModeComplete(
                    groupId = groupId,
                    sendControlMessage = false,
                    insertSystemMessage = true,
                    reason = "异常降级: $reason"
                )
                
                when (result) {
                    is GroupOperationResult.Success -> {
                        Log.i(TAG, "群组异常降级成功: groupId=$groupId")
                        
                        // 插入额外的提示消息说明降级原因
                        try {
                            val groupRecipientId = getGroupRecipientId(groupId)
                            if (groupRecipientId != null) {
                                val helper = GroupTokenExchangeHelper.getInstance(context)
                                helper.insertCustomSystemMessage(
                                    recipientId = groupRecipientId,
                                    messageBody = "v2 mode 因异常自动禁用: $reason"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "插入降级原因消息失败: groupId=$groupId", e)
                        }
                        
                        GroupOperationResult.Success(Unit)
                    }
                    is GroupOperationResult.Failed -> {
                        Log.e(TAG, "群组异常降级失败: groupId=$groupId, error=${result.message}")
                        result
                    }
                    else -> {
                        GroupOperationResult.Failed(
                            error = GroupOperationError.OPERATION_FAILED,
                            message = "降级失败"
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "异常降级处理失败: groupId=$groupId", e)
                GroupOperationResult.Failed(
                    error = GroupOperationError.OPERATION_FAILED,
                    message = "降级失败: ${e.message}",
                    cause = e
                )
            }
        }
    }
    
    /**
     * 获取群组成员的 RecipientId 列表
     */
    private fun getMemberRecipientIds(groupId: String): List<org.thoughtcrime.securesms.recipients.RecipientId> {
        return try {
            // 使用 GroupIdConverter 支持多种格式（Base64, encoded GroupId 等）
            val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupId, context)
            when (result) {
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> {
                    val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(result.recipientId)
                    if (groupRecipient.isGroup) {
                        groupRecipient.participantIds
                    } else {
                        emptyList()
                    }
                }
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Failed -> {
                    Log.e(TAG, "转换 groupId 失败: ${result.reason}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组成员 RecipientId 列表失败: groupId=$groupId", e)
            emptyList()
        }
    }
    
    /**
     * 获取群组的 RecipientId
     */
    private fun getGroupRecipientId(groupId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupId, context)
            when (result) {
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> {
                    result.recipientId
                }
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Failed -> {
                    Log.w(TAG, "转换 groupId 失败: $groupId, 原因: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组 RecipientId 异常: groupId=$groupId", e)
            null
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
    
    /**
     * 检测群组状态不一致
     * 
     * 返回处于不一致状态的群组列表：
     * - PROPOSING 但全员同意
     * - FULL_V2_ACTIVE 但缺少通道或轮询
     * 
     * @return 不一致群组状态列表
     */
    suspend fun detectInconsistentStates(): List<GroupStateInconsistency> = withContext(Dispatchers.IO) {
        val inconsistencies = mutableListOf<GroupStateInconsistency>()
        
        try {
            // 检查 PROPOSING 状态的群组
            val proposingGroups = getGroupsByStatus(GroupV2Status.PROPOSING)
            for (groupState in proposingGroups) {
                if (groupState.isFullyAgreed()) {
                    inconsistencies.add(
                        GroupStateInconsistency(
                            groupId = groupState.groupId,
                            type = InconsistencyType.PROPOSING_BUT_FULLY_AGREED,
                            description = "群组全员同意但状态仍为 PROPOSING",
                            currentStatus = groupState.status,
                            expectedStatus = GroupV2Status.FULL_V2_ACTIVE
                        )
                    )
                }
            }
            
            // 检查 FULL_V2_ACTIVE 状态的群组
            val activeGroups = getGroupsByStatus(GroupV2Status.FULL_V2_ACTIVE)
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            
            for (groupState in activeGroups) {
                val otherMembers = groupState.totalMembers.filter { it != myAci }
                var missingChannels = 0
                
                for (memberAci in otherMembers) {
                    val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
                    if (channel == null) {
                        missingChannels++
                    }
                }
                
                if (missingChannels > 0) {
                    inconsistencies.add(
                        GroupStateInconsistency(
                            groupId = groupState.groupId,
                            type = InconsistencyType.ACTIVE_BUT_MISSING_CHANNELS,
                            description = "群组已激活但缺少 $missingChannels 个通道",
                            currentStatus = groupState.status,
                            expectedStatus = GroupV2Status.FULL_V2_ACTIVE
                        )
                    )
                }
            }
            
            if (inconsistencies.isNotEmpty()) {
                Log.w(TAG, "[状态检测] 发现 ${inconsistencies.size} 个不一致状态")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态检测] 检测状态不一致失败", e)
        }
        
        inconsistencies
    }
    
    /**
     * 修复群组状态不一致
     * 
     * @param groupId 群组 ID
     * @return 是否成功修复
     */
    suspend fun fixInconsistentState(groupId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "[状态修复] 尝试修复群组状态: groupId=$groupId")
            
            val groupState = groupV2StatusTable.getGroupState(groupId)
            if (groupState == null) {
                Log.w(TAG, "[状态修复] 群组状态不存在: $groupId")
                return@withContext false
            }
            
            when (groupState.status) {
                GroupV2Status.PROPOSING -> {
                    if (groupState.isFullyAgreed()) {
                        Log.i(TAG, "[状态修复] 修复 PROPOSING 状态：激活群组: $groupId")
                        return@withContext checkAndActivateV2Mode(groupId)
                    }
                }
                GroupV2Status.FULL_V2_ACTIVE -> {
                    Log.i(TAG, "[状态修复] 修复 FULL_V2_ACTIVE 状态：重建通道: $groupId")
                    val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                    val otherMembers = groupState.totalMembers.filter { it != myAci }.toSet()
                    
                    val (successCount, _) = establishGroupChannels(
                        groupId,
                        otherMembers,
                        groupState.providerType
                    )
                    
                    return@withContext successCount > 0
                }
                else -> {
                    Log.d(TAG, "[状态修复] 无需修复: $groupId, status=${groupState.status}")
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "[状态修复] 修复状态失败: $groupId", e)
            false
        }
    }
}

/**
 * 群组状态不一致类型
 */
enum class InconsistencyType {
    PROPOSING_BUT_FULLY_AGREED,   // PROPOSING 但全员同意
    ACTIVE_BUT_MISSING_CHANNELS,  // ACTIVE 但缺少通道
    ACTIVE_BUT_NO_POLLING         // ACTIVE 但未启动轮询
}

/**
 * 群组状态不一致信息
 */
data class GroupStateInconsistency(
    val groupId: String,
    val type: InconsistencyType,
    val description: String,
    val currentStatus: GroupV2Status,
    val expectedStatus: GroupV2Status
)

