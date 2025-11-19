package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import java.util.UUID
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.IndividualSendJob
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportToken
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.notification.NotificationConfigManager
import org.thoughtcrime.securesms.tap.utils.TapGatewayConfigBuilder

/**
 * 群组 Token 交换帮助类
 * 
 * 封装群组 V2 mode 提议、接受、激活等流程中的消息发送和通知逻辑
 */
class GroupTokenExchangeHelper(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupTokenExchangeHelper::class.java)
        
        @Volatile
        private var INSTANCE: GroupTokenExchangeHelper? = null
        
        @JvmStatic
        fun getInstance(context: Context): GroupTokenExchangeHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupTokenExchangeHelper(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val notificationConfigManager: NotificationConfigManager by lazy {
        NotificationConfigManager.getInstance(context)
    }
    
    /**
     * 发送群组 V2 mode 提议消息
     * 
     * @param groupId 群组 ID
     * @param proposerAci 发起人 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表（包括发起人）
     * @param myToken 我的群组token（单个token）
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupOfferMessage(
        groupId: String,
        proposerAci: String,
        memberRecipientIds: List<RecipientId>,
        myToken: TransportToken,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 提议消息: groupId=$groupId, members=${memberRecipientIds.size}")
            
            // 提取所有成员的 ACI
            val totalMembers = memberRecipientIds.mapNotNull { recipientId ->
                try {
                    Recipient.resolved(recipientId).requireAci().toString()
                } catch (e: Exception) {
                    Log.w(TAG, "无法获取成员 ACI: recipientId=$recipientId", e)
                    null
                }
            }.toSet()
            
            Log.d(TAG, "群组成员 ACIs: totalMembers=${totalMembers.size}")
            
            // 构建群组提议消息
            val offerMessage = buildGroupTapMessage(
                senderAci = proposerAci,
                providerType = providerType,
                metadata = buildGroupOfferMetadata(groupId, proposerAci, myToken, totalMembers),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_OFFER
            )
            
            val messageBody = TapTokenExchangeMessage.encode(offerMessage)
            
            // 获取群组的 RecipientId
            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                return@withContext false
            }
            
            // 发送一条群组消息（Signal 会自动广播给所有成员）
            val sent = sendDataMessageToRecipient(
                recipientId = groupRecipientId,
                messageBody = messageBody,
                groupId = groupId
            )
            
            Log.i(TAG, "群组提议消息发送完成: success=$sent")
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送群组提议消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 发送群组 V2 mode 接受消息
     * 
     * @param groupId 群组 ID
     * @param accepterAci 接受者 ACI
     * @param proposerAci 发起人 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表
     * @param myToken 我的群组token（单个token）
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupAcceptMessage(
        groupId: String,
        accepterAci: String,
        proposerAci: String,
        memberRecipientIds: List<RecipientId>,
        myToken: TransportToken,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 接受消息: groupId=$groupId, accepter=$accepterAci")
            
            val acceptMessage = buildGroupTapMessage(
                senderAci = accepterAci,
                providerType = providerType,
                metadata = buildGroupAcceptMetadata(groupId, accepterAci, proposerAci, myToken),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACCEPT
            )
            
            val messageBody = TapTokenExchangeMessage.encode(acceptMessage)
            
            // 获取群组的 RecipientId
            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                return@withContext false
            }
            
            // 发送一条群组消息（Signal 会自动广播给所有成员）
            val sent = sendDataMessageToRecipient(
                recipientId = groupRecipientId,
                messageBody = messageBody,
                groupId = groupId
            )
            
            Log.i(TAG, "群组接受消息发送完成: success=$sent")
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送群组接受消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 发送群组 V2 mode 激活消息（可选）
     * 
     * @param groupId 群组 ID
     * @param senderAci 发送者 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupActivateMessage(
        groupId: String,
        senderAci: String,
        memberRecipientIds: List<RecipientId>,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 激活消息: groupId=$groupId")
            
            val activateMessage = buildGroupTapMessage(
                senderAci = senderAci,
                providerType = providerType,
                metadata = mapOf(
                    "groupId" to groupId,
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACTIVATE
            )
            
            val messageBody = TapTokenExchangeMessage.encode(activateMessage)
            
            // 获取群组的 RecipientId
            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                return@withContext false
            }
            
            // 发送一条群组消息
            val sent = sendDataMessageToRecipient(
                recipientId = groupRecipientId,
                messageBody = messageBody,
                groupId = groupId
            )
            
            Log.i(TAG, "群组激活消息已发送: success=$sent")
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送群组激活消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 发送群组 V2 mode 禁用消息
     * 
     * @param groupId 群组 ID
     * @param senderAci 发送者 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupDisableMessage(
        groupId: String,
        senderAci: String,
        memberRecipientIds: List<RecipientId>,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 禁用消息: groupId=$groupId")
            
            val disableMessage = TapTokenExchangeMessage(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "groupId" to groupId,
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_DISABLE,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(disableMessage)
            
            // 获取群组的 RecipientId
            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                return@withContext false
            }
            
            // 发送一条群组消息
            val sent = sendDataMessageToRecipient(
                recipientId = groupRecipientId,
                messageBody = messageBody,
                groupId = groupId
            )
            
            Log.i(TAG, "群组禁用消息已发送: success=$sent")
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送群组禁用消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 构建群组提议消息的 metadata
     */
    private fun buildGroupOfferMetadata(
        groupId: String,
        proposerAci: String,
        myToken: TransportToken,
        totalMembers: Set<String>
    ): Map<String, Any> {
        return mapOf(
            "groupId" to groupId,
            "proposerAci" to proposerAci,
            "myToken" to myToken.toMap(), // 只包含我的token
            "totalMembers" to totalMembers.toList(),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * 构建群组接受消息的 metadata
     */
    private fun buildGroupAcceptMetadata(
        groupId: String,
        accepterAci: String,
        proposerAci: String,
        myToken: TransportToken
    ): Map<String, Any> {
        return mapOf(
            "groupId" to groupId,
            "accepterAci" to accepterAci,
            "proposerAci" to proposerAci,
            "myToken" to myToken.toMap(), // 只包含我的token
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * 发送数据消息给指定接收者
     * 
     * 使用 Signal 的消息发送机制
     * 
     * @param recipientId 接收者 ID（可以是个人或群组）
     * @param messageBody 消息内容
     * @param groupId 群组 ID（如果是群组消息则提供）
     * @return 发送是否成功
     */
    private suspend fun sendDataMessageToRecipient(
        recipientId: RecipientId,
        messageBody: String,
        groupId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 确定目标 recipient 和 threadId
            val (targetRecipient, threadId) = if (groupId != null) {
                // 群组消息：使用群组的 recipient
                val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
                if (groupRecipientId == null) {
                    Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                    return@withContext false
                }
                
                val groupRecipient = Recipient.resolved(groupRecipientId)
                val groupThreadId = SignalDatabase.threads.getOrCreateThreadIdFor(groupRecipient)
                
                Log.d(TAG, "发送群组消息: groupId=$groupId, groupRecipientId=$groupRecipientId, threadId=$groupThreadId")
                Pair(groupRecipient, groupThreadId)
            } else {
                // 私聊消息：使用个人 recipient
                val recipient = Recipient.resolved(recipientId)
                val recipientThreadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
                
                Log.d(TAG, "发送私聊消息: recipientId=$recipientId, threadId=$recipientThreadId")
                Pair(recipient, recipientThreadId)
            }
            
            // 创建外发消息
            val outgoingMessage = OutgoingMessage(
                threadRecipient = targetRecipient,
                sentTimeMillis = System.currentTimeMillis(),
                body = messageBody,
                expiresIn = targetRecipient.expiresInSeconds.toLong() * 1000,
                isSecure = true
            )
            
            // 将消息插入数据库
            val messageId = SignalDatabase.messages.insertMessageOutbox(
                message = outgoingMessage,
                threadId = threadId,
                forceSms = false,
                insertListener = null
            )
            
            // 根据是否是群组消息选择发送Job
            if (groupId != null) {
                // 群组消息：使用 PushGroupSendJob
                Log.d(TAG, "使用 PushGroupSendJob 发送群组消息: messageId=$messageId")
                org.thoughtcrime.securesms.jobs.PushGroupSendJob.enqueue(
                    context,
                    AppDependencies.jobManager,
                    messageId,
                    targetRecipient.id,
                    emptySet(),  // filterRecipients：不过滤，发送给所有成员
                    false  // isScheduledSend
                )
            } else {
                // 私聊消息：使用 IndividualSendJob
                Log.d(TAG, "使用 IndividualSendJob 发送私聊消息: messageId=$messageId")
                val sendJob = IndividualSendJob.create(
                    messageId,
                    targetRecipient,
                    false,  // hasMedia
                    false   // isScheduledSend
                )
                AppDependencies.jobManager.add(sendJob)
            }
            
            Log.d(TAG, "数据消息已加入发送队列: recipientId=$recipientId, messageId=$messageId, groupId=$groupId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "发送数据消息失败: recipientId=$recipientId, groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 从 groupId 字符串获取群组的 RecipientId
     * 
     * @param groupIdString 群组 ID 字符串（base64 编码）
     * @return 群组的 RecipientId，失败返回 null
     */
    private fun getGroupRecipientIdFromGroupId(groupIdString: String): RecipientId? {
        return try {
            val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupIdString, context)
            when (result) {
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> {
                    Log.d(TAG, "成功转换 groupId 到 RecipientId: $groupIdString -> ${result.recipientId}")
                    result.recipientId
                }
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Failed -> {
                    Log.w(TAG, "转换 groupId 失败: $groupIdString, 原因: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组 RecipientId 异常: groupId=$groupIdString", e)
            null
        }
    }
    
    /**
     * 在对话中插入系统消息
     * 
     * 使用 Signal 原生的 Tap V2 Mode 系统消息类型，显示为灰色居中的提示
     * 
     * @param recipientId 接收者 ID（群组或个人）
     * @param messageBody 消息内容（当前参数保留但未使用，消息内容由消息类型决定）
     * @param isEnabled true=启用 v2 mode, false=禁用 v2 mode
     */
    suspend fun insertSystemMessage(
        recipientId: RecipientId,
        messageBody: String,
        isEnabled: Boolean = true
    ) = withContext(Dispatchers.IO) {
        try {
            val insertResult = if (isEnabled) {
                // 插入"v2 mode 已启用"系统消息
                SignalDatabase.messages.insertTapV2ModeEnabledMessage(recipientId)
            } else {
                // 插入"v2 mode 已禁用"系统消息
                SignalDatabase.messages.insertTapV2ModeDisabledMessage(recipientId)
            }
            
            Log.i(TAG, "系统消息已插入: recipientId=$recipientId, messageId=${insertResult.messageId}, enabled=$isEnabled")
            
        } catch (e: Exception) {
            Log.e(TAG, "插入系统消息异常: recipientId=$recipientId, enabled=$isEnabled", e)
        }
    }
    
    /**
     * 插入自定义文本的系统消息
     * 
     * 用于插入提议发起等自定义提示消息
     * 
     * @param recipientId 接收者 ID（群组或个人）
     * @param messageBody 消息内容
     */
    suspend fun insertCustomSystemMessage(
        recipientId: RecipientId,
        messageBody: String
    ) = withContext(Dispatchers.IO) {
        try {
            val recipient = Recipient.resolved(recipientId)
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            
            // 创建一个简单的 outgoing message 作为系统提示
            val systemMessage = OutgoingMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                body = messageBody,
                isSecure = true
            )
            
            val insertedMessageId = SignalDatabase.messages.insertMessageOutbox(
                message = systemMessage,
                threadId = threadId,
                forceSms = false,
                insertListener = null
            )
            
            if (insertedMessageId >= 0) {
                Log.i(TAG, "自定义系统消息已插入: recipientId=$recipientId, messageId=$insertedMessageId")
                SignalDatabase.threads.update(threadId, true)
            } else {
                Log.w(TAG, "自定义系统消息插入失败: recipientId=$recipientId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "插入自定义系统消息异常: recipientId=$recipientId", e)
        }
    }
    
    /**
     * 发送新成员加入消息
     * 
     * 当新成员主动请求加入 v2 mode 时发送此消息
     * 
     * @param groupId 群组 ID
     * @param newMemberAci 新成员 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表
     * @param tokens 为每个成员生成的 token 映射
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendNewMemberJoinMessage(
        groupId: String,
        newMemberAci: String,
        memberRecipientIds: List<RecipientId>,
        myToken: TransportToken,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送新成员加入消息: groupId=$groupId, newMember=$newMemberAci")
            
            // 使用 GROUP_ACCEPT 类型，但在 metadata 中标记为新成员加入
            val joinMessage = TapTokenExchangeMessage(
                senderAci = newMemberAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "groupId" to groupId,
                    "accepterAci" to newMemberAci,
                    "myToken" to myToken.toMap(),
                    "isNewMember" to true,  // 标记为新成员加入
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACCEPT,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(joinMessage)
            
            // 获取群组的 RecipientId
            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                Log.w(TAG, "无法获取群组 RecipientId: groupId=$groupId")
                return@withContext false
            }
            
            // 发送一条群组消息
            val sent = sendDataMessageToRecipient(
                recipientId = groupRecipientId,
                messageBody = messageBody,
                groupId = groupId
            )
            
            Log.i(TAG, "新成员加入消息发送完成: success=$sent")
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送新成员加入消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 发送新成员响应消息
     * 
     * 老成员响应新成员加入请求，向新成员发送自己的 token
     * 注意：这是点对点消息，使用私聊发送，不是群组消息
     * 
     * @param groupId 群组 ID（仅用于日志）
     * @param senderAci 发送者 ACI（老成员）
     * @param newMemberRecipientId 新成员的 RecipientId
     * @param token 为新成员生成的 token
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendNewMemberResponseMessage(
        groupId: String,
        senderAci: String,
        newMemberRecipientId: RecipientId,
        myToken: TransportToken,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送新成员响应消息（点对点）: groupId=$groupId, sender=$senderAci, newMember=$newMemberRecipientId")
            
            val responseMessage = TapTokenExchangeMessage(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "groupId" to groupId,
                    "myToken" to myToken.toMap(),
                    "isNewMemberResponse" to true,  // 标记为新成员响应
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACCEPT,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(responseMessage)
            
            // 使用私聊发送（不带 groupId 参数），这样消息会显示在私聊界面
            val sent = sendDataMessageToRecipient(
                recipientId = newMemberRecipientId,
                messageBody = messageBody,
                groupId = null  // 明确指定为 null，使用私聊发送
            )
            
            if (sent) {
                Log.i(TAG, "新成员响应消息已发送（点对点）: groupId=$groupId")
            } else {
                Log.w(TAG, "新成员响应消息发送失败: groupId=$groupId")
            }
            
            sent
            
        } catch (e: Exception) {
            Log.e(TAG, "发送新成员响应消息失败: groupId=$groupId", e)
            false
        }
    }
    
    /**
     * 从 token 映射中提取指定成员的 token
     */
    fun extractTokenForMember(
        tokensData: Map<String, Any>,
        memberAci: String
    ): TransportToken? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val tokenMap = tokensData[memberAci] as? Map<String, Any>
            if (tokenMap != null) {
                org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(tokenMap)
            } else {
                Log.w(TAG, "未找到成员的 token: memberAci=$memberAci")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取成员 token 失败: memberAci=$memberAci", e)
            null
        }
    }

    private suspend fun buildGroupTapMessage(
        senderAci: String,
        providerType: String,
        metadata: Map<String, Any>,
        requestType: String,
        tokenData: Map<String, Any> = emptyMap()
    ): TapTokenExchangeMessage {
        val localConfig = notificationConfigManager.getLocalConfig()
        if (localConfig != null && localConfig.validate()) {
            val userId = extractUserId(localConfig)
            val gatewayConfig = TapGatewayConfigBuilder.build(localConfig)
            return TapTokenExchangeMessage.createWithWebhook(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = tokenData,
                metadata = metadata,
                requestType = requestType,
                webhookUrl = localConfig.webhookUrl,
                notifySecret = localConfig.notifySecret,
                userId = userId,
                gatewayConfig = gatewayConfig,
                channelVersion = TapTokenExchangeMessage.CURRENT_CHANNEL_VERSION
            )
        }
        return TapTokenExchangeMessage(
            senderAci = senderAci,
            providerType = providerType,
            tokenData = tokenData,
            metadata = metadata,
            requestType = requestType,
            channelVersion = TapTokenExchangeMessage.CURRENT_CHANNEL_VERSION,
            version = 1
        )
    }

    private fun extractUserId(config: NotificationConfig): String {
        val metadataUserId = config.pushServiceInfo.metadata["userId"] as? String
        return metadataUserId ?: UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

