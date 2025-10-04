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
                
                // 2. 如果是提议消息，保存提议者的 tokens
                if (isProposer) {
                    val tokensData = originalMessage.metadata["tokens"] as? Map<String, Any>
                    if (tokensData != null) {
                        saveProposerTokens(context, proposerAci, tokensData)
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
                val memberAcis = memberRecipientIds.mapNotNull { recipientId ->
                    try {
                        Recipient.resolved(recipientId).requireAci().toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "无法获取成员 ACI: recipientId=$recipientId", e)
                        null
                    }
                }.toSet()
                
                // 4. 获取我的 ACI
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                val otherMemberAcis = memberAcis.filter { it != myAci }.toSet()
                
                // 5. 为其他成员生成 tokens
                Log.i(TAG, "为群组其他成员生成 tokens: groupId=$groupId, members=${otherMemberAcis.size}")
                val groupManager = GroupTransportManager.getInstance(context)
                val generatedTokens = groupManager.generateGroupTokens(
                    groupId = groupId,
                    memberAcis = otherMemberAcis,
                    providerType = originalMessage.providerType
                )
                
                if (generatedTokens.isEmpty()) {
                    Log.e(TAG, "生成群组 tokens 失败")
                    return@launch
                }
                
                // 6. 保存生成的 tokens
                val savedCount = groupManager.saveGroupTokensToPool(groupId, generatedTokens)
                Log.i(TAG, "群组 tokens 已保存: groupId=$groupId, saved=$savedCount/${generatedTokens.size}")
                
                // 7. 更新群组状态 - 标记自己为 PROPOSING 状态并记录同意
                if (isProposer) {
                    // 如果是接受提议者的消息，需要初始化群组状态
                    val initialized = groupManager.proposeV2Mode(
                        groupId = groupId,
                        proposerAci = proposerAci,
                        memberAcis = memberAcis,
                        providerType = originalMessage.providerType
                    )
                    if (!initialized) {
                        Log.e(TAG, "初始化群组状态失败")
                        return@launch
                    }
                }
                
                val accepted = groupManager.acceptV2Proposal(groupId, myAci)
                if (!accepted) {
                    Log.w(TAG, "标记自己为已同意失败")
                }
                
                // 8. 发送接受消息给所有成员
                Log.i(TAG, "发送群组接受消息: groupId=$groupId")
                val helper = GroupTokenExchangeHelper.getInstance(context)
                val sent = helper.sendGroupAcceptMessage(
                    groupId = groupId,
                    accepterAci = myAci,
                    proposerAci = proposerAci,
                    memberRecipientIds = memberRecipientIds,
                    tokens = generatedTokens,
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
                
                val groupState = groupManager.getGroupState(groupId)
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
                val groupState = groupManager.getGroupState(groupId)
                if (groupState != null) {
                    Log.d(TAG, "群组尚未全员同意: groupId=$groupId, agreed=${groupState.agreedMembers.size}/${groupState.totalMembers.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查并激活群组失败: groupId=$groupId", e)
        }
    }
    
    /**
     * 启动群组轮询
     */
    private suspend fun startGroupPolling(context: Context, groupId: String, memberAcis: List<String>) {
        try {
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            
            for (memberAci in memberAcis) {
                val channels = channelManager.getActiveChannels(memberAci)
                for (channel in channels) {
                    if (channel.metadata != null) {
                        val added = pollingService.addPollingTarget(memberAci, channel.metadata!!, channel)
                        if (added) {
                            Log.d(TAG, "群组成员轮询已添加: groupId=$groupId, member=$memberAci")
                        }
                    }
                }
            }
            
            pollingService.startPolling()
            Log.i(TAG, "群组轮询已启动: groupId=$groupId, members=${memberAcis.size}")
        } catch (e: Exception) {
            Log.e(TAG, "启动群组轮询失败: groupId=$groupId", e)
        }
    }
    
    /**
     * 获取群组的 RecipientId
     * 
     * 支持多种 groupId 格式：
     * 1. RecipientId 序列化数字字符串
     * 2. GroupId 编码字符串（如 "__signal_group__v2__!xxxx"）
     * 3. Base64 编码的群组 ID 字节数组
     */
    private fun getGroupRecipientId(context: Context, groupId: String): RecipientId? {
        return try {
            // 方法1：尝试作为 RecipientId 数字解析
            try {
                val recipientId = RecipientId.from(groupId.toLong())
                Log.d(TAG, "成功从数字解析 RecipientId: $groupId")
                return recipientId
            } catch (e: NumberFormatException) {
                // 不是数字，继续尝试其他方法
            }
            
            // 方法2：尝试作为 GroupId 编码字符串解析
            try {
                val parsedGroupId = org.thoughtcrime.securesms.groups.GroupId.parse(groupId)
                val recipientIdOptional = org.thoughtcrime.securesms.database.SignalDatabase.recipients.getByGroupId(parsedGroupId)
                if (recipientIdOptional.isPresent) {
                    Log.d(TAG, "成功从 GroupId 编码解析 RecipientId: $groupId")
                    return recipientIdOptional.get()
                }
            } catch (e: org.thoughtcrime.securesms.groups.BadGroupIdException) {
                // 不是有效的 GroupId 编码，继续尝试其他方法
                Log.d(TAG, "不是有效的 GroupId 编码: $groupId")
            }
            
            // 方法3：尝试作为 Base64 编码的字节数组解析
            try {
                val groupIdBytes = android.util.Base64.decode(groupId, android.util.Base64.DEFAULT)
                val pushGroupId = org.thoughtcrime.securesms.groups.GroupId.push(groupIdBytes)
                val recipientIdOptional = org.thoughtcrime.securesms.database.SignalDatabase.recipients.getByGroupId(pushGroupId)
                if (recipientIdOptional.isPresent) {
                    Log.d(TAG, "成功从 Base64 解析 RecipientId: $groupId")
                    return recipientIdOptional.get()
                }
            } catch (e: Exception) {
                Log.d(TAG, "无法从 Base64 解析 GroupId: $groupId", e)
            }
            
            // 所有方法都失败
            Log.w(TAG, "无法从 groupId 获取 RecipientId: $groupId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取群组 RecipientId 失败: groupId=$groupId", e)
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

