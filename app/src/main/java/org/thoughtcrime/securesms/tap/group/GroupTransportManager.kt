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
                        val success = tokenPool.addSharedToken(memberAci, token)
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
                val groupState = getGroupState(groupId)
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

