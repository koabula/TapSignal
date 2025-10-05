package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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
            val state = groupV2StatusTable.getGroupState(groupId)
            state?.status ?: GroupV2Status.NATIVE
        } catch (e: Exception) {
            Log.e(TAG, "获取群组状态失败: $groupId", e)
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
     * 
     * @throws GroupOperationException 当状态无效或更新失败时
     * @throws GroupV2StatusTable.OptimisticLockException 当发生乐观锁冲突时
     */
    suspend fun updateGroupState(state: GroupV2State): GroupOperationResult<Unit> {
        return withContext(Dispatchers.IO) {
            stateLock.write {
                try {
                    if (!state.validate()) {
                        Log.w(TAG, "群组状态无效，拒绝更新: ${state.groupId}")
                        return@withContext GroupOperationResult.Failed(
                            error = GroupOperationError.INVALID_STATE,
                            message = "群组状态无效: ${state.groupId}"
                        )
                    }
                    
                    groupV2StatusTable.insertOrUpdateGroupState(state)
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
                val otherMemberAcis = memberAcis.filter { it != myAci }.toSet()
                
                // 4. 为其他成员生成 tokens
                Log.i(TAG, "为群组其他成员生成 tokens: groupId=$groupId, members=${otherMemberAcis.size}")
                val generatedTokens = generateGroupTokens(
                    groupId = groupId,
                    memberAcis = otherMemberAcis,
                    providerType = providerType
                )
                
                if (generatedTokens.isEmpty()) {
                    Log.e(TAG, "生成群组 tokens 失败")
                    return@withContext false
                }
                
                // 5. 保存生成的 tokens
                val savedCount = saveGroupTokensToPool(groupId, generatedTokens)
                if (savedCount == 0) {
                    Log.e(TAG, "保存群组 tokens 失败")
                    return@withContext false
                }
                
                Log.i(TAG, "群组 tokens 已保存: groupId=$groupId, saved=$savedCount/${generatedTokens.size}")
                
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
                
                // 7. 发送提议消息给所有成员
                Log.i(TAG, "发送群组提议消息: groupId=$groupId")
                val helper = org.thoughtcrime.securesms.tap.group.GroupTokenExchangeHelper.getInstance(context)
                val sent = helper.sendGroupOfferMessage(
                    groupId = groupId,
                    proposerAci = myAci,
                    memberRecipientIds = memberRecipientIds,
                    tokens = generatedTokens,
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
     * 为群组生成 Tokens
     * 
     * 为群组的每个成员创建独立目录和只读 token，用于 v2 mode 通信
     * 
     * @param groupId 群组 ID
     * @param memberAcis 所有成员 ACI 集合（不包括自己）
     * @param providerType Provider 类型
     * @return 生成的 Token 映射 (memberAci -> TransportToken)
     */
    suspend fun generateGroupTokens(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Map<String, org.thoughtcrime.securesms.tap.TransportToken> {
        return withContext(Dispatchers.IO) {
            stateLock.read {
                try {
                    Log.i(TAG, "开始为群组生成 Tokens: groupId=$groupId, members=${memberAcis.size}, provider=$providerType")
                    
                    // 获取 TransportProvider
                    val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                    val provider = transportManager.getProvider(providerType)
                    if (provider == null) {
                        Log.e(TAG, "Provider 不存在: $providerType")
                        return@withContext emptyMap()
                    }
                    
                    if (!provider.supportsAuth) {
                        Log.e(TAG, "Provider 不支持权限管理: $providerType")
                        return@withContext emptyMap()
                    }
                    
                    // 获取 Provider 配置
                    val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                    val providerConfig = configManager.getProviderConfig(providerType)
                    if (providerConfig == null) {
                        Log.e(TAG, "Provider 配置不存在: $providerType")
                        return@withContext emptyMap()
                    }
                    
                    val generatedTokens = mutableMapOf<String, org.thoughtcrime.securesms.tap.TransportToken>()
                    
                    // 为每个成员生成 token
                    for (memberAci in memberAcis) {
                        try {
                            // 构建 token 请求 (只读权限，用于成员轮询)
                            val tokenRequest = org.thoughtcrime.securesms.tap.TransportTokenRequest(
                                recipientId = memberAci,
                                providerType = providerType,
                                requestedPermissions = setOf(
                                    org.thoughtcrime.securesms.tap.TransportPermission.READ,
                                    org.thoughtcrime.securesms.tap.TransportPermission.LIST
                                ),
                                validityDurationMs = 0L, // 使用默认有效期
                                providerConfig = providerConfig,
                                purpose = "group_member_polling:$groupId"
                            )
                            
                            // 生成 token
                            val token = provider.generateToken(tokenRequest)
                            if (token != null) {
                                // 在 token 中添加群组 ID 标记
                                val groupToken = when (token) {
                                    is org.thoughtcrime.securesms.tap.CosTransportToken -> {
                                        // COS token 无法直接修改，我们在元数据中记录 groupId
                                        token
                                    }
                                    else -> token
                                }
                                
                                generatedTokens[memberAci] = groupToken
                                Log.d(TAG, "为成员生成 Token: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}, tokenId=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(token.tokenId)}")
                            } else {
                                Log.w(TAG, "Token 生成失败: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "为成员生成 Token 时异常: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                        }
                    }
                    
                    Log.i(TAG, "群组 Token 生成完成: groupId=$groupId, 成功=${generatedTokens.size}/${memberAcis.size}")
                    generatedTokens
                    
                } catch (e: Exception) {
                    Log.e(TAG, "生成群组 Tokens 失败: groupId=$groupId", e)
                    emptyMap()
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
                Log.i(TAG, "开始群组消息发送: groupId=$groupId, messageId=$messageId, size=${encryptedMessage.size}")
                
                // 1. 检查群组状态
                val groupState = getGroupStateSync(groupId)
                if (groupState == null) {
                    Log.e(TAG, "群组状态不存在: $groupId")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "群组状态不存在",
                        memberCount = 0
                    )
                }
                
                if (groupState.status != GroupV2Status.FULL_V2_ACTIVE) {
                    Log.e(TAG, "群组未处于 FULL_V2_ACTIVE 状态: $groupId, status=${groupState.status}")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "群组未激活 V2 模式",
                        memberCount = groupState.totalMembers.size
                    )
                }
                
                // 2. 获取我的 ACI
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                
                // 3. 获取其他成员（不包括自己）
                val otherMembers = groupState.totalMembers.filter { it != myAci }
                if (otherMembers.isEmpty()) {
                    Log.w(TAG, "群组中没有其他成员: $groupId")
                    return@withContext GroupSendResult.Success(
                        groupId = groupId,
                        messageId = messageId,
                        memberCount = 0
                    )
                }
                
                // 4. 获取所有成员的通道
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                val memberChannels = mutableMapOf<String, org.thoughtcrime.securesms.tap.TransportChannel>()
                
                for (memberAci in otherMembers) {
                    val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
                    if (channel != null) {
                        memberChannels[memberAci] = channel
                    } else {
                        Log.w(TAG, "成员没有活跃通道: memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                    }
                }
                
                if (memberChannels.isEmpty()) {
                    Log.e(TAG, "没有成员有可用的通道: $groupId")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "没有可用的成员通道",
                        memberCount = otherMembers.size
                    )
                }
                
                // 5. 获取 Provider
                val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
                val provider = transportManager.getProvider(groupState.providerType)
                if (provider == null) {
                    Log.e(TAG, "Provider 不存在: ${groupState.providerType}")
                    return@withContext GroupSendResult.Failed(
                        groupId = groupId,
                        messageId = messageId,
                        reason = "Provider 不可用",
                        memberCount = otherMembers.size
                    )
                }
                
                // 6. 并发为每个成员上传消息
                val uploadResults = performConcurrentUpload(
                    groupId = groupId,
                    messageId = messageId,
                    encryptedMessage = encryptedMessage,
                    memberChannels = memberChannels,
                    provider = provider
                )
                
                // 7. 统计结果
                val successMembers = uploadResults.filter { (_, result) -> 
                    result is org.thoughtcrime.securesms.tap.TransportResult.Success 
                }.keys
                val failedMembers = uploadResults.filter { (_, result) -> 
                    result !is org.thoughtcrime.securesms.tap.TransportResult.Success 
                }.mapValues { (_, result) ->
                    when (result) {
                        is org.thoughtcrime.securesms.tap.TransportResult.Failed -> result.errorMessage ?: "未知错误"
                        else -> "未知错误"
                    }
                }
                
                Log.i(TAG, "群组消息发送完成: groupId=$groupId, 成功=${successMembers.size}/${memberChannels.size}")
                
                // 8. 返回结果
                when {
                    successMembers.size == memberChannels.size -> {
                        GroupSendResult.Success(
                            groupId = groupId,
                            messageId = messageId,
                            memberCount = successMembers.size
                        )
                    }
                    successMembers.isNotEmpty() -> {
                        GroupSendResult.PartialSuccess(
                            groupId = groupId,
                            messageId = messageId,
                            successMembers = successMembers,
                            failedMembers = failedMembers
                        )
                    }
                    else -> {
                        GroupSendResult.Failed(
                            groupId = groupId,
                            messageId = messageId,
                            reason = "所有成员发送失败",
                            memberCount = memberChannels.size
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
     * 并发上传消息到所有成员
     */
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
                messageType = org.thoughtcrime.securesms.tap.TransportMessageType.MEDIA_MESSAGE,  // 群组消息作为媒体消息类型
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
            stateLock.write {
                try {
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
            groupV2StatusTable.insertOrUpdateGroupState(resetState)
            
            Log.i(TAG, "群组已回退到原生状态: $groupId")
            
            // 3. 插入系统消息通知用户
            val decodedGroupId = org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)
            val groupRecipientId = org.thoughtcrime.securesms.recipients.Recipient
                .externalGroupExact(decodedGroupId)
                .id
            
            val helper = GroupTokenExchangeHelper.getInstance(context)
            helper.insertCustomSystemMessage(
                recipientId = groupRecipientId,
                messageBody = "群组成员变动，v2 mode 提议已取消"
            )
            
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
            
            groupV2StatusTable.insertOrUpdateGroupState(updatedState)
            
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
            stateLock.write {
                try {
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
                    
                    groupV2StatusTable.insertOrUpdateGroupState(updatedState)
                    
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
            stateLock.write {
                try {
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
                    
                    // 4. 获取其他成员（不包括自己）
                    val otherMemberAcis = currentState.totalMembers.filter { it != myAci }.toSet()
                    
                    // 5. 为其他成员生成 tokens
                    Log.i(TAG, "为群组其他成员生成 tokens: groupId=$groupId, members=${otherMemberAcis.size}")
                    val generatedTokens = generateGroupTokens(
                        groupId = groupId,
                        memberAcis = otherMemberAcis,
                        providerType = currentState.providerType
                    )
                    
                    if (generatedTokens.isEmpty()) {
                        Log.e(TAG, "生成群组 tokens 失败")
                        return@withContext false
                    }
                    
                    // 6. 保存生成的 tokens
                    val savedCount = saveGroupTokensToPool(groupId, generatedTokens)
                    if (savedCount == 0) {
                        Log.e(TAG, "保存群组 tokens 失败")
                        return@withContext false
                    }
                    
                    Log.i(TAG, "群组 tokens 已保存: groupId=$groupId, saved=$savedCount/${generatedTokens.size}")
                    
                    // 7. 发送 GROUP_ACCEPT 消息给所有成员（使用新成员加入的方式）
                    val helper = GroupTokenExchangeHelper.getInstance(context)
                    val sent = helper.sendNewMemberJoinMessage(
                        groupId = groupId,
                        newMemberAci = myAci,
                        memberRecipientIds = memberRecipientIds,
                        tokens = generatedTokens,
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
                
                // 2. 为新成员生成 token
                val token = generateGroupTokens(
                    groupId = groupId,
                    memberAcis = setOf(newMemberAci),
                    providerType = currentState.providerType
                )[newMemberAci]
                
                if (token == null) {
                    Log.e(TAG, "为新成员生成 token 失败")
                    return@withContext false
                }
                
                // 3. 保存 token
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                tokenPool.addSharedToken(newMemberAci, token)
                
                // 4. 发送 token 给新成员
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                val helper = GroupTokenExchangeHelper.getInstance(context)
                
                val sent = helper.sendNewMemberResponseMessage(
                    groupId = groupId,
                    senderAci = myAci,
                    newMemberRecipientId = newMemberRecipientId,
                    token = token,
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
     * 禁用 V2 模式（简单版，仅重置状态）
     * 
     * 用于内部调用或被动禁用场景（如收到禁用消息）
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
            stateLock.write {
                try {
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
                    groupV2StatusTable.insertOrUpdateGroupState(resetState)
                    
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
            stateLock.write {
                try {
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
                    groupV2StatusTable.insertOrUpdateGroupState(resetState)
                    
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
            val decodedGroupId = org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)
            val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient
                .externalGroupExact(decodedGroupId)
            
            if (groupRecipient.isGroup) {
                groupRecipient.participantIds
            } else {
                emptyList()
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
}

