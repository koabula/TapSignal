package org.thoughtcrime.securesms.tap.group

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportToken
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.util.JsonUtils

/**
 * 群组 Token 交换请求接收器
 * 
 * 处理用户对群组 V2 mode 提议的接受或拒绝操作
 */
class GroupTokenExchangeReceiver : BroadcastReceiver() {
    
    companion object {
        private val TAG = Log.tag(GroupTokenExchangeReceiver::class.java)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "收到群组 Token 交换操作: action=${intent.action}")
        
        val senderId = intent.getStringExtra("senderId") ?: return
        val groupId = intent.getStringExtra("groupId") ?: return
        
        when (intent.action) {
            "ACCEPT_GROUP_TOKEN_EXCHANGE" -> {
                val tokenExchangeMessageJson = intent.getStringExtra("tokenExchangeMessage")
                val isProposer = intent.getBooleanExtra("isProposer", false)
                if (tokenExchangeMessageJson != null) {
                    handleGroupTokenAcceptance(context, senderId, groupId, tokenExchangeMessageJson, isProposer)
                }
            }
            "REJECT_GROUP_TOKEN_EXCHANGE" -> {
                handleGroupTokenRejection(context, senderId, groupId)
            }
        }
    }
    
    /**
     * 处理用户接受群组 Token 交换请求
     */
    private fun handleGroupTokenAcceptance(
        context: Context,
        senderId: String,
        groupId: String,
        tokenExchangeMessageJson: String,
        isProposer: Boolean
    ) {
        Log.i(TAG, "用户接受群组 Token 交换请求: groupId=$groupId, senderId=$senderId, isProposer=$isProposer")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 解析 Token 交换消息
                val originalMessage = JsonUtils.fromJson(tokenExchangeMessageJson, TapTokenExchangeMessage::class.java)
                if (originalMessage == null) {
                    Log.e(TAG, "无法解析群组 Token 交换消息")
                    return@launch
                }
                
                val proposerAci = originalMessage.metadata["proposerAci"] as? String
                if (proposerAci == null) {
                    Log.e(TAG, "群组消息缺少 proposerAci")
                    return@launch
                }
                
                // 2. 如果是提议消息，保存提议者的 token（修复：提取myToken而不是tokens map）
                if (isProposer) {
                    val proposerTokenData = originalMessage.metadata["myToken"] as? Map<*, *>
                    if (proposerTokenData != null) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val tokenMap = proposerTokenData as Map<String, Any>
                            val proposerToken = org.thoughtcrime.securesms.tap.CosTransportToken.fromMap(tokenMap)
                            if (proposerToken != null) {
                                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                                val saved = tokenPool.addReceivedToken(proposerAci, proposerToken, groupId)
                                if (saved) {
                                    Log.i(TAG, "已保存提议者的群组token: proposer=$proposerAci, tokenId=${proposerToken.tokenId}")
                                } else {
                                    Log.w(TAG, "保存提议者token失败")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析提议者token失败", e)
                        }
                    }
                }
                
                // 3. 获取群组成员列表
                val groupRecipientId = getGroupRecipientId(context, groupId)
                if (groupRecipientId == null) {
                    Log.e(TAG, "无法找到群组: groupId=$groupId")
                    return@launch
                }
                
                val groupRecipient = Recipient.resolved(groupRecipientId)
                val memberRecipientIds = getGroupMemberRecipientIds(context, groupRecipient)
                
                // 4. 获取我的 ACI
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                
                // 5. 生成我的群组token（只生成一个）
                Log.i(TAG, "生成我的群组token: groupId=$groupId")
                val groupManager = GroupTransportManager.getInstance(context)
                val myGroupToken = groupManager.generateMyGroupToken(
                    groupId = groupId,
                    providerType = originalMessage.providerType
                )
                
                if (myGroupToken == null) {
                    Log.e(TAG, "生成群组token失败")
                    return@launch
                }
                
                // 6. 保存我的sharedToken（使用groupId作为key）
                val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
                val saved = tokenPool.addSharedToken(groupId, myGroupToken, groupId)
                if (!saved) {
                    Log.e(TAG, "保存群组token失败")
                    return@launch
                }
                
                Log.i(TAG, "群组token已保存: groupId=$groupId, tokenId=${myGroupToken.tokenId}")
                
                // 7. 标记自己为已同意
                // 注意: 群组状态已经由提议者创建，这里不需要调用 proposeV2Mode()
                // 直接调用 acceptV2Proposal 将自己加入 agreedMembers
                Log.d(TAG, "标记自己为已同意: groupId=$groupId, myAci=$myAci")
                val accepted = groupManager.acceptV2Proposal(groupId, myAci)
                if (!accepted) {
                    Log.e(TAG, "标记自己为已同意失败: groupId=$groupId")
                    return@launch
                }
                
                Log.i(TAG, "成功标记为已同意: groupId=$groupId, myAci=$myAci")
                
                // 8. 发送接受消息给所有成员（包含我的token）
                Log.i(TAG, "发送群组接受消息: groupId=$groupId")
                val helper = GroupTokenExchangeHelper.getInstance(context)
                val sent = helper.sendGroupAcceptMessage(
                    groupId = groupId,
                    accepterAci = myAci,
                    proposerAci = proposerAci,
                    memberRecipientIds = memberRecipientIds,
                    myToken = myGroupToken,  // 只发送我的token
                    providerType = originalMessage.providerType
                )
                
                if (sent) {
                    Log.i(TAG, "群组接受消息已发送: groupId=$groupId")
                    
                    // 9. 检查是否可以激活 V2 mode
                    checkAndActivateIfReady(context, groupId)
                    
                    // 10. 插入系统消息（自定义文本）
                    helper.insertCustomSystemMessage(
                        recipientId = groupRecipientId,
                        messageBody = "你已同意使用 v2 mode"
                    )
                } else {
                    Log.w(TAG, "群组接受消息发送失败")
                }
                
                // 11. 取消通知
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(groupId.hashCode())
                
            } catch (e: Exception) {
                Log.e(TAG, "处理群组 Token 接受异常: groupId=$groupId", e)
            }
        }
    }
    
    /**
     * 处理用户拒绝群组 Token 交换请求
     */
    private fun handleGroupTokenRejection(
        context: Context,
        senderId: String,
        groupId: String
    ) {
        Log.i(TAG, "用户拒绝群组 Token 交换请求: groupId=$groupId, senderId=$senderId")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 取消通知
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(groupId.hashCode())
                
                Log.i(TAG, "群组 Token 交换请求已拒绝: groupId=$groupId")
            } catch (e: Exception) {
                Log.e(TAG, "处理群组 Token 拒绝异常: groupId=$groupId", e)
            }
        }
    }
    
    /**
     * 保存提议者的 tokens
     */
    private suspend fun saveProposerTokens(
        context: Context,
        proposerAci: String,
        tokensData: Map<String, Any>
    ) {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val helper = GroupTokenExchangeHelper.getInstance(context)
            val myToken = helper.extractTokenForMember(tokensData, myAci)
            
            if (myToken != null) {
                val tokenPool = TransportTokenPool.getInstance(context)
                val saved = tokenPool.addReceivedToken(proposerAci, myToken)
                if (saved) {
                    Log.i(TAG, "已保存提议者的 token: proposer=$proposerAci")
                } else {
                    Log.w(TAG, "保存提议者 token 失败: proposer=$proposerAci")
                }
            } else {
                Log.w(TAG, "未找到我的 token 在提议者的 tokens 中")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存提议者 tokens 失败", e)
        }
    }
    
    /**
     * 检查并激活群组 V2 mode（如果所有成员都同意）
     */
    private suspend fun checkAndActivateIfReady(context: Context, groupId: String) {
        try {
            val groupManager = GroupTransportManager.getInstance(context)
            val activated = groupManager.checkAndActivateV2Mode(groupId)
            
            if (activated) {
                Log.i(TAG, "群组 V2 mode 已激活: groupId=$groupId")
                
                val groupState = groupManager.getGroupStateSync(groupId)
                if (groupState != null) {
                    // 建立通道和启动轮询
                    val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                    val otherMembers = groupState.totalMembers.filter { it != myAci }
                    
                    // 建立通道
                    val (successCount, failedMembers) = groupManager.establishGroupChannels(
                        groupId = groupId,
                        memberAcis = otherMembers.toSet(),
                        providerType = groupState.providerType
                    )
                    
                    Log.i(TAG, "群组通道建立完成: groupId=$groupId, success=$successCount, failed=${failedMembers.size}")
                    
                    // 启动轮询
                    startGroupPolling(context, groupId, otherMembers)
                    
                    // 插入系统消息
                    val groupRecipientId = getGroupRecipientId(context, groupId)
                    if (groupRecipientId != null) {
                        val helper = GroupTokenExchangeHelper.getInstance(context)
                        // 插入"v2 mode 已启用"系统消息（使用原生消息类型）
                        helper.insertSystemMessage(
                            recipientId = groupRecipientId,
                            messageBody = "群组已启用 v2 mode",
                            isEnabled = true
                        )
                    }
                }
            } else {
                val groupState = groupManager.getGroupStateSync(groupId)
                if (groupState != null) {
                    Log.d(TAG, "群组尚未全员同意: groupId=$groupId, agreed=${groupState.agreedMembers.size}/${groupState.totalMembers.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查并激活群组失败: groupId=$groupId", e)
        }
    }
    
    /**
     * 启动群组轮询（修复版：使用群组receivedTokens构建metadata）
     */
    private suspend fun startGroupPolling(context: Context, groupId: String, memberAcis: List<String>) {
        try {
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            
            // 获取群组状态以确定providerType
            val groupManager = GroupTransportManager.getInstance(context)
            val groupState = groupManager.getGroupStateSync(groupId)
            if (groupState == null) {
                Log.w(TAG, "无法获取群组状态，跳过轮询启动: groupId=$groupId")
                return
            }
            
            val providerType = groupState.providerType
            Log.d(TAG, "启动群组轮询: groupId=$groupId, providerType=$providerType, members=${memberAcis.size}")
            
            // 获取所有其他成员的receivedTokens
            val memberTokens = tokenPool.getGroupReceivedTokens(groupId, providerType)
            
            if (memberTokens.isEmpty()) {
                Log.w(TAG, "没有群组成员token，无法启动轮询: groupId=$groupId")
                return
            }
            
            Log.d(TAG, "获取到群组成员tokens: groupId=$groupId, count=${memberTokens.size}, members=${memberTokens.keys.map { org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(it) }}")
            
            // 为每个成员构建群组轮询metadata
            val memberMetadatas = mutableMapOf<String, org.thoughtcrime.securesms.tap.TransportMetadata>()
            for ((memberAci, token) in memberTokens) {
                val metadata = buildGroupPollingMetadata(context, groupId, memberAci, token, providerType)
                if (metadata != null) {
                    memberMetadatas[memberAci] = metadata
                    Log.d(TAG, "构建群组轮询metadata成功: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                } else {
                    Log.w(TAG, "构建群组轮询metadata失败: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                }
            }
            
            if (memberMetadatas.isEmpty()) {
                Log.w(TAG, "无法构建任何有效的群组轮询metadata: groupId=$groupId, tokenCount=${memberTokens.size}")
                return
            }
            
            Log.d(TAG, "准备添加群组轮询目标: groupId=$groupId, metadataCount=${memberMetadatas.size}")
            
            // 先启动轮询服务（确保isRunning=true）
            pollingService.startPolling()
            
            // 批量添加轮询目标
            val addedCount = pollingService.addGroupPollingTargets(groupId, memberMetadatas)
            
            if (addedCount > 0) {
                Log.i(TAG, "群组轮询已启动: groupId=$groupId, 成功添加=${addedCount}/${memberMetadatas.size}")
            } else {
                Log.w(TAG, "未能添加任何群组轮询目标: groupId=$groupId, metadataCount=${memberMetadatas.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动群组轮询失败: groupId=$groupId", e)
        }
    }
    
    /**
     * 构建群组轮询metadata
     */
    private fun buildGroupPollingMetadata(
        context: Context,
        groupId: String,
        memberAci: String,
        memberToken: org.thoughtcrime.securesms.tap.TransportToken,
        providerType: String
    ): org.thoughtcrime.securesms.tap.TransportMetadata? {
        return try {
            if (providerType == "cos" && memberToken is org.thoughtcrime.securesms.tap.CosTransportToken) {
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
                val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
                val memberGroupPath = "/group/${groupId}/"
                
                org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
                    recipientId = memberAci,
                    providerType = providerType,
                    myAddress = "",
                    myToken = null,
                    myRegion = "",
                    myBucketName = "",
                    mySendPath = "",
                    peerAddress = "${memberToken.bucketName}.cos.${memberToken.region}.myqcloud.com",
                    peerToken = memberToken,
                    peerRegion = memberToken.region,
                    peerBucketName = memberToken.bucketName,
                    peerReceivePath = memberGroupPath,
                    myHashedId = myHashedId,
                    peerHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(
                        org.whispersystems.signalservice.api.push.ServiceId.ACI.parseOrThrow(memberAci)
                    )
                )
            } else {
                Log.w(TAG, "不支持的providerType或token类型: providerType=$providerType")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "构建群组轮询metadata失败: memberAci=$memberAci", e)
            null
        }
    }
    
    /**
     * 获取群组的 RecipientId
     * 
     * 使用 GroupIdConverter 统一处理不同格式的 groupId
     */
    private fun getGroupRecipientId(context: Context, groupId: String): RecipientId? {
        return try {
            val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupId, context)
            when (result) {
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> {
                    Log.d(TAG, "成功转换 groupId: $groupId -> ${result.recipientId}")
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
     * 获取群组所有成员的 RecipientId 列表
     */
    private fun getGroupMemberRecipientIds(context: Context, groupRecipient: Recipient): List<RecipientId> {
        return try {
            // 从 Recipient 中获取群组成员
            if (groupRecipient.isGroup) {
                groupRecipient.participantIds
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组成员列表失败", e)
            emptyList()
        }
    }
}

