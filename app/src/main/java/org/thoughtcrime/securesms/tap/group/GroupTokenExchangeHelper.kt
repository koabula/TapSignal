package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.IndividualSendJob
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportToken

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
    
    /**
     * 发送群组 V2 mode 提议消息
     * 
     * @param groupId 群组 ID
     * @param proposerAci 发起人 ACI
     * @param memberRecipientIds 所有成员的 RecipientId 列表（包括发起人）
     * @param tokens 为每个成员生成的 token 映射 (memberAci -> TransportToken)
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupOfferMessage(
        groupId: String,
        proposerAci: String,
        memberRecipientIds: List<RecipientId>,
        tokens: Map<String, TransportToken>,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 提议消息: groupId=$groupId, members=${memberRecipientIds.size}")
            
            // 构建群组提议消息
            val offerMessage = TapTokenExchangeMessage(
                senderAci = proposerAci,
                providerType = providerType,
                tokenData = emptyMap(), // 群组消息中不包含单个 token，而是在 metadata 中
                metadata = buildGroupOfferMetadata(groupId, proposerAci, tokens),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_OFFER,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(offerMessage)
            
            // 向群组所有成员发送（除了自己）
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val otherMembers = memberRecipientIds.filter { recipientId ->
                val recipient = Recipient.resolved(recipientId)
                recipient.requireAci().toString() != myAci
            }
            
            var successCount = 0
            for (recipientId in otherMembers) {
                val sent = sendDataMessageToRecipient(recipientId, messageBody, groupId)
                if (sent) {
                    successCount++
                }
            }
            
            Log.i(TAG, "群组提议消息发送完成: 成功=$successCount/${otherMembers.size}")
            successCount == otherMembers.size
            
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
     * @param tokens 为每个成员生成的 token 映射
     * @param providerType Provider 类型
     * @return 发送是否成功
     */
    suspend fun sendGroupAcceptMessage(
        groupId: String,
        accepterAci: String,
        proposerAci: String,
        memberRecipientIds: List<RecipientId>,
        tokens: Map<String, TransportToken>,
        providerType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "发送群组 V2 接受消息: groupId=$groupId, accepter=$accepterAci")
            
            val acceptMessage = TapTokenExchangeMessage(
                senderAci = accepterAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = buildGroupAcceptMetadata(groupId, accepterAci, proposerAci, tokens),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACCEPT,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(acceptMessage)
            
            // 向群组所有成员发送（除了自己）
            val otherMembers = memberRecipientIds.filter { recipientId ->
                val recipient = Recipient.resolved(recipientId)
                recipient.requireAci().toString() != accepterAci
            }
            
            var successCount = 0
            for (recipientId in otherMembers) {
                val sent = sendDataMessageToRecipient(recipientId, messageBody, groupId)
                if (sent) {
                    successCount++
                }
            }
            
            Log.i(TAG, "群组接受消息发送完成: 成功=$successCount/${otherMembers.size}")
            successCount == otherMembers.size
            
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
            
            val activateMessage = TapTokenExchangeMessage(
                senderAci = senderAci,
                providerType = providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "groupId" to groupId,
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACTIVATE,
                version = 1
            )
            
            val messageBody = TapTokenExchangeMessage.encode(activateMessage)
            
            // 向群组所有成员发送（除了自己）
            val otherMembers = memberRecipientIds.filter { recipientId ->
                val recipient = Recipient.resolved(recipientId)
                recipient.requireAci().toString() != senderAci
            }
            
            for (recipientId in otherMembers) {
                sendDataMessageToRecipient(recipientId, messageBody, groupId)
            }
            
            Log.i(TAG, "群组激活消息已发送")
            true
            
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
            
            // 向群组所有成员发送（除了自己）
            val otherMembers = memberRecipientIds.filter { recipientId ->
                val recipient = Recipient.resolved(recipientId)
                recipient.requireAci().toString() != senderAci
            }
            
            for (recipientId in otherMembers) {
                sendDataMessageToRecipient(recipientId, messageBody, groupId)
            }
            
            Log.i(TAG, "群组禁用消息已发送")
            true
            
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
        tokens: Map<String, TransportToken>
    ): Map<String, Any> {
        val tokensData = tokens.mapValues { (_, token) -> token.toMap() }
        
        return mapOf(
            "groupId" to groupId,
            "proposerAci" to proposerAci,
            "tokens" to tokensData,
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
        tokens: Map<String, TransportToken>
    ): Map<String, Any> {
        val tokensData = tokens.mapValues { (_, token) -> token.toMap() }
        
        return mapOf(
            "groupId" to groupId,
            "accepterAci" to accepterAci,
            "proposerAci" to proposerAci,
            "tokens" to tokensData,
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * 发送数据消息给指定接收者
     * 
     * 使用 Signal 的消息发送机制
     */
    private suspend fun sendDataMessageToRecipient(
        recipientId: RecipientId,
        messageBody: String,
        groupId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val recipient = Recipient.resolved(recipientId)
            
            // 创建外发消息
            val outgoingMessage = OutgoingMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                body = messageBody,
                expiresIn = recipient.expiresInSeconds.toLong() * 1000,
                isSecure = true
            )
            
            // 将消息插入数据库
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            val messageId = SignalDatabase.messages.insertMessageOutbox(
                message = outgoingMessage,
                threadId = threadId,
                forceSms = false,
                insertListener = null
            )
            
            // 创建发送任务
            val sendJob = IndividualSendJob.create(
                messageId,
                recipient,
                false,  // hasMedia
                false   // isScheduledSend
            )
            
            // 提交任务
            AppDependencies.jobManager.add(sendJob)
            
            Log.d(TAG, "数据消息已加入发送队列: recipientId=$recipientId, messageId=$messageId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "发送数据消息失败: recipientId=$recipientId", e)
            false
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
}

