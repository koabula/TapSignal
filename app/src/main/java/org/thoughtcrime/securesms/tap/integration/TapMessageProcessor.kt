package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.TransportError
import org.thoughtcrime.securesms.tap.TransportChannelConfig
import org.thoughtcrime.securesms.tap.TransportToken
import org.thoughtcrime.securesms.tap.CosTransportToken
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TapDatabaseContext
import org.thoughtcrime.securesms.tap.WebhookConfigData
import org.thoughtcrime.securesms.tap.GatewayConfigData
import org.thoughtcrime.securesms.recipients.Recipient
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Tap消息处理器
 * 负责处理通过Tap传输层接收的控制消息和管理传输通道
 * 专注于传输层功能，不涉及Signal的加密解密处理
 */
class TapMessageProcessor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageProcessor::class.java)
        
        // Jackson ObjectMapper配置
        private val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        
        @Volatile
        private var INSTANCE: TapMessageProcessor? = null
        
        fun getInstance(context: Context): TapMessageProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val channelManager = TransportChannelManager.getInstance(context)
    private val tokenPool = TransportTokenPool.getInstance(context)
    
    // 专用协程作用域用于异步数据库操作
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 检查是否为Tap传输层控制消息
     * 
     * @param messageBody 消息体
     * @return 是否为Tap控制消息
     */
    fun isTapMessage(messageBody: String): Boolean {
        return messageBody.startsWith("TAP_MSG:") || 
               messageBody.startsWith("TAP_REQ:") || 
               messageBody.startsWith("TAP_RESP:") ||
               messageBody.startsWith("TAP_REVOKE:") ||
               org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.isTapTokenExchangeMessage(messageBody)
    }
    
    /**
     * 处理Tap传输层控制消息
     * 
     * @param senderId 发送者ID
     * @param messageBody 消息体
     * @return 处理结果
     */
    suspend fun processTapMessage(senderId: org.thoughtcrime.securesms.recipients.RecipientId, messageBody: String): TapProcessResult {
        Log.i(TAG, "处理Tap传输层控制消息: senderId=$senderId, bodyLength=${messageBody.length}")
        
        return try {
            when {
                messageBody.startsWith("TAP_REQ:") -> {
                    processChannelRequest(senderId, messageBody.substring(8))
                }
                messageBody.startsWith("TAP_RESP:") -> {
                    processChannelResponse(senderId, messageBody.substring(9))
                }
                messageBody.startsWith("TAP_REVOKE:") -> {
                    processChannelRevoke(senderId, messageBody.substring(11))
                }
                messageBody.startsWith("TAP_MSG:") -> {
                    processControlMessage(senderId, messageBody.substring(8))
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.isTapTokenExchangeMessage(messageBody) -> {
                    processTokenExchangeMessage(senderId, messageBody)
                }
                else -> {
                    Log.w(TAG, "未知的Tap控制消息类型: senderId=$senderId")
                    TapProcessResult.Failed("未知的Tap控制消息类型")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Tap控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }

    /**
     * 处理推送过来的TransportMessage Payload
     * 
     * @param payload JSON格式的TransportMessage
     * @return 处理结果
     */
    suspend fun processPushMessage(payload: String): TapProcessResult {
        Log.i(TAG, "处理推送消息Payload: length=${payload.length}")
        
        return try {
            val transportMessage = TransportMessage.objectMapper.readValue(payload, TransportMessage::class.java)
            processTapTransportMessage(transportMessage)
        } catch (e: Exception) {
            Log.e(TAG, "解析推送消息Payload失败", e)
            TapProcessResult.Failed("解析Payload失败: ${e.message}")
        }
    }
    
    /**
     * 处理传输层接收到的加密消息
     * 将消息交给TapEnvelopeAdapter处理Signal解密和入库
     * 
     * @param transportMessage 传输消息
     * @return 处理结果
     */
    data class PushContext(
        val groupId: String? = null,
        val recipientGateway: RecipientGatewaySnapshot? = null
    )

    data class RecipientGatewaySnapshot(
        val webhookUrl: String,
        val notifySecret: String,
        val userId: String,
        val provider: String? = null,
        val region: String? = null,
        val endpoint: String? = null,
        val offlineBucket: String? = null,
        val presignDelegation: Boolean = false,
        val metadata: Map<String, String> = emptyMap()
    )

    suspend fun processTapTransportMessage(
        transportMessage: TransportMessage,
        pushContext: PushContext? = null
    ): TapProcessResult {
        Log.i(TAG, "处理Tap传输消息: messageId=${transportMessage.messageId}, type=${transportMessage.messageType}")

        return try {
            // 验证消息完整性
            if (!validateTransportMessage(transportMessage)) {
                Log.w(TAG, "传输消息验证失败: messageId=${transportMessage.messageId}")
                return TapProcessResult.Failed("消息验证失败")
            }
            
            val resolvedGroupId = resolveGroupId(transportMessage, pushContext)
            val isGroupMessage = resolvedGroupId != null
            
            if (isGroupMessage) {
                val senderAci = transportMessage.senderId
                val timestamp = transportMessage.timestamp
                val deduplicator = org.thoughtcrime.securesms.tap.group.GroupMessageDeduplicator.getInstance(context)
                val duplicate = deduplicator.isDuplicate(
                    messageId = transportMessage.messageId,
                    senderAci = senderAci,
                    rawGroupId = resolvedGroupId,
                    timestamp = timestamp
                )

                if (duplicate) {
                    Log.d(TAG, "群组消息重复，跳过处理: messageId=${transportMessage.messageId}, groupId=$resolvedGroupId")
                    return TapProcessResult.Success("群组消息重复，已忽略")
                }
            }

            if (resolvedGroupId != null) {
                updateRecipientGatewayIfNeeded(resolvedGroupId, pushContext)
            }
            
            // 将加密消息交给TapEnvelopeAdapter处理Signal相关逻辑
            val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
            val adapterResult = envelopeAdapter.processEncryptedMessage(transportMessage)
            
            when (adapterResult) {
                is TapEnvelopeProcessResult.Success -> {
                    Log.i(TAG, "传输消息处理成功: messageId=${transportMessage.messageId}")
                    
                    // 如果是群组消息，标记为已处理
                    if (isGroupMessage && resolvedGroupId != null) {
                        val senderAci = transportMessage.senderId
                        val deduplicator = org.thoughtcrime.securesms.tap.group.GroupMessageDeduplicator.getInstance(context)
                        deduplicator.markAsProcessed(
                            messageId = transportMessage.messageId,
                            senderAci = senderAci,
                            rawGroupId = resolvedGroupId,
                            timestamp = transportMessage.timestamp,
                            pollingMemberAci = transportMessage.recipientId
                        )
                    }
                    
                    TapProcessResult.Success("消息处理成功")
                }
                is TapEnvelopeProcessResult.Failed -> {
                    Log.w(TAG, "传输消息处理失败: messageId=${transportMessage.messageId}, error=${adapterResult.error}")
                    TapProcessResult.Failed(adapterResult.error)
                }
                is TapEnvelopeProcessResult.Duplicate -> {
                    Log.d(TAG, "传输消息重复: messageId=${transportMessage.messageId}")
                    TapProcessResult.Success("消息重复，已忽略")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理传输消息异常: messageId=${transportMessage.messageId}", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }

    private suspend fun updateRecipientGatewayIfNeeded(groupId: String?, pushContext: PushContext?) {
        if (groupId.isNullOrBlank()) return
        val gateway = pushContext?.recipientGateway ?: return
        val webhookConfig = WebhookConfigData(
            webhookUrl = gateway.webhookUrl,
            notifySecret = gateway.notifySecret,
            userId = gateway.userId,
            version = "2.0"
        )
        val gatewayConfig = GatewayConfigData(
            endpoint = gateway.endpoint,
            region = gateway.region,
            provider = gateway.provider,
            offlineBucket = gateway.offlineBucket,
            presignDelegation = gateway.presignDelegation,
            metadata = gateway.metadata
        )

        try {
            val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            groupManager.updateMemberGatewayInfo(groupId, myAci, webhookConfig, gatewayConfig)
        } catch (e: Exception) {
            Log.w(TAG, "更新群成员Gateway信息失败: groupId=$groupId", e)
        }
    }

    private fun resolveGroupId(message: TransportMessage, pushContext: PushContext?): String? {
        pushContext?.groupId?.let { return it }
        val hints = message.contentMetadata.gatewayFailoverHints
        val hintGroupIdValue = hints?.get("groupId")
        if (hintGroupIdValue != null) {
            val hintGroupId = hintGroupIdValue.toString()
            if (hintGroupId.isNotBlank()) {
                return hintGroupId
            }
        }

        if (message.messageId.startsWith("group_")) {
            val parts = message.messageId.split("_")
            if (parts.size >= 3) {
                return parts[1]
            }
        }
        return null
    }
    
    /**
     * 验证传输消息完整性
     */
    private fun validateTransportMessage(message: TransportMessage): Boolean {
        return try {
            // 基本字段验证
            if (message.messageId.isBlank()) {
                Log.w(TAG, "消息ID为空")
                return false
            }
            
            if (message.senderId.isBlank()) {
                Log.w(TAG, "发送者ID为空")
                return false
            }
            
            if (message.recipientId.isBlank()) {
                Log.w(TAG, "接收者ID为空")
                return false
            }
            
            // 验证Signal密文存在
            if (message.signalCiphertext.isEmpty()) {
                Log.w(TAG, "Signal密文为空")
                return false
            }
            
            // 验证时间戳合理性
            val currentTime = System.currentTimeMillis()
            val messageTime = message.timestamp
            val timeDiff = Math.abs(currentTime - messageTime)
            
            // 允许1小时的时间偏差，防止重放攻击
            val MAX_TIME_DRIFT_MS = 60 * 60 * 1000L // 1小时
            if (timeDiff > MAX_TIME_DRIFT_MS) {
                Log.w(TAG, "消息时间戳偏差过大: messageTime=$messageTime, currentTime=$currentTime, diff=${timeDiff}ms")
                return false
            }
            
            // 检查时间戳是否为未来时间（允许5分钟时钟偏差）
            val MAX_FUTURE_DRIFT_MS = 5 * 60 * 1000L // 5分钟
            if (messageTime > currentTime + MAX_FUTURE_DRIFT_MS) {
                Log.w(TAG, "消息时间戳为未来时间: messageTime=$messageTime, currentTime=$currentTime")
                return false
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "验证传输消息失败", e)
            false
        }
    }


    /**
     * 处理传输通道请求
     */
    private suspend fun processChannelRequest(senderId: org.thoughtcrime.securesms.recipients.RecipientId, requestData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道请求: senderId=$senderId")
        
        return try {
            // 解析请求数据
            val requestInfo = parseChannelRequest(requestData)
            if (requestInfo == null) {
                Log.w(TAG, "无法解析通道请求数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析请求数据")
            }
            
            // 检查是否已有活跃通道
            if (channelManager.hasActiveChannel(senderId.toString())) {
                Log.i(TAG, "已存在活跃通道，更新配置: senderId=$senderId")
                val updated = channelManager.updateChannelConfig(senderId.toString(), requestInfo.config)
                if (updated) {
                    TapProcessResult.Success("通道配置已更新")
                } else {
                    TapProcessResult.Failed("通道配置更新失败")
                }
            } else {
                // 创建新的传输通道
                Log.i(TAG, "创建新的传输通道: senderId=$senderId")
                val tokenString = requestInfo.token?.toMap()?.let { 
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it)
                } ?: ""
                val channelResult = channelManager.createChannel(
                    recipientId = senderId.toString(),
                    config = requestInfo.config,
                    token = tokenString
                )
                
                if (channelResult != null) {
                    TapProcessResult.Success("传输通道创建成功")
                } else {
                    TapProcessResult.Failed("传输通道创建失败")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道请求异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道响应
     */
    private suspend fun processChannelResponse(senderId: org.thoughtcrime.securesms.recipients.RecipientId, responseData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道响应: senderId=$senderId")
        
        return try {
            val responseInfo = parseChannelResponse(responseData)
            if (responseInfo == null) {
                Log.w(TAG, "无法解析通道响应数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析响应数据")
            }
            
            when (responseInfo.status) {
                "accepted" -> {
                    // 对方接受了通道请求
                    Log.i(TAG, "通道请求被接受: senderId=$senderId")
                    
                    if (responseInfo.token != null) {
                        // 获取对方的ACI（用于Token保存）
                        val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(senderId)
                        val peerAci = recipient.requireAci().toString()
                        
                        // 存储对方提供的Token（使用ACI格式）
                        val addResult = tokenPool.addReceivedToken(peerAci, responseInfo.token)
                        if (!addResult) {
                            Log.w(TAG, "添加接收Token失败: senderId=$senderId, peerAci=$peerAci")
                        } else {
                            Log.i(TAG, "[Token交换] 通道响应Token保存成功: peerAci=$peerAci")
                        }
                    }
                    
                    // 由于activateChannel是私有方法，使用公开的updateChannelConfig激活通道
                    val updated = channelManager.updateChannelConfig(senderId.toString(), TransportChannelConfig())
                    if (updated) {
                        TapProcessResult.Success("传输通道已激活")
                    } else {
                        TapProcessResult.Failed("通道激活失败")
                    }
                }
                
                "rejected" -> {
                    // 对方拒绝了通道请求
                    Log.i(TAG, "通道请求被拒绝: senderId=$senderId, reason=${responseInfo.reason}")
                    TapProcessResult.Failed("通道请求被拒绝: ${responseInfo.reason}")
                }
                
                else -> {
                    Log.w(TAG, "未知的响应状态: ${responseInfo.status}")
                    TapProcessResult.Failed("未知的响应状态")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道响应异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道撤销
     */
    private suspend fun processChannelRevoke(senderId: org.thoughtcrime.securesms.recipients.RecipientId, revokeData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道撤销: senderId=$senderId")
        
        return try {
            val revokeInfo = parseChannelRevoke(revokeData)
            if (revokeInfo == null) {
                Log.w(TAG, "无法解析通道撤销数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析撤销数据")
            }
            
            // 关闭相关通道
            val senderAci = recipientIdToAci(senderId)
            if (senderAci != null) {
                val channels = channelManager.getActiveChannels(senderAci)
                var closed = 0
                for (channel in channels) {
                    if (channelManager.closeChannel(channel.channelId)) {
                        closed++
                    }
                }
                if (closed > 0) {
                    Log.i(TAG, "已关闭通道数量: $closed, senderId=$senderId")
                }
            }
            
            // 移除相关Token（遍历所有可用的Provider类型）
            val transportManager = TransportManager.getInstance(context)
            val availableProviders = transportManager.getAvailableProviders()
            var removed = 0
            
            // 修复：使用ACI字符串作为键，与通道管理保持一致
            if (senderAci != null) {
                for (provider in availableProviders) {
                    val providerType = provider.providerType
                    val token = tokenPool.getValidReceivedToken(senderAci, providerType)
                    if (token != null && tokenPool.removeToken(senderAci, providerType)) {
                        removed++
                    }
                }
            } else {
                Log.w(TAG, "无法获取senderAci，跳过Token清理: senderId=$senderId")
            }
            
            if (removed > 0) {
                Log.i(TAG, "已移除Token数量: $removed, senderId=$senderId")
            }
            
            TapProcessResult.Success("传输通道已撤销")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道撤销异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理控制消息
     */
    private suspend fun processControlMessage(senderId: org.thoughtcrime.securesms.recipients.RecipientId, controlData: String): TapProcessResult {
        Log.d(TAG, "处理控制消息: senderId=$senderId")
        
        return try {
            val controlInfo = parseControlMessage(controlData)
            if (controlInfo == null) {
                Log.w(TAG, "无法解析控制消息数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析控制消息")
            }
            
            when (controlInfo.type) {
                "heartbeat" -> {
                    // 心跳消息
                    Log.d(TAG, "收到心跳消息: senderId=$senderId")
                    // 通过更新通道配置来刷新最后访问时间
                    val senderAci = recipientIdToAci(senderId)
                    if (senderAci != null) {
                        val channels = channelManager.getActiveChannels(senderAci)
                        for (channel in channels) {
                            // 使用默认配置来刷新通道
                            val defaultConfig = TransportChannelConfig()
                            channelManager.updateChannelConfig(senderAci, defaultConfig)
                        }
                    }
                    TapProcessResult.Success("心跳处理完成")
                }
                
                "status_update" -> {
                    // 状态更新消息
                    Log.d(TAG, "收到状态更新: senderId=$senderId")
                    TapProcessResult.Success("状态更新处理完成")
                }
                
                else -> {
                    Log.w(TAG, "未知的控制消息类型: ${controlInfo.type}")
                    TapProcessResult.Failed("未知的控制消息类型")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理Token交换消息
     */
    private suspend fun processTokenExchangeMessage(senderId: org.thoughtcrime.securesms.recipients.RecipientId, messageBody: String): TapProcessResult {
        Log.i(TAG, "处理Token交换消息: senderId=$senderId")
        
        return try {
            val tokenExchangeMessage = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.decode(messageBody)
            if (tokenExchangeMessage == null) {
                Log.w(TAG, "无法解析Token交换消息: senderId=$senderId")
                return TapProcessResult.Failed("无法解析Token交换消息")
            }
            
            when (tokenExchangeMessage.requestType) {
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_OFFER -> {
                    // 处理Token交换请求（A发送给B）
                    processTokenOffer(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_ACCEPT -> {
                    // 处理Token交换接受（B发送给A）
                    processTokenAccept(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_CONFIRM -> {
                    // 处理Token交换确认（A发送给A）
                    processTokenConfirm(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_DISABLE -> {
                    // 处理v2模式禁用请求
                    processV2ModeDisable(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_GROUP_OFFER -> {
                    // 处理群组V2提议
                    processGroupTokenOffer(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACCEPT -> {
                    // 处理群组V2接受
                    processGroupTokenAccept(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_GROUP_ACTIVATE -> {
                    // 处理群组V2激活通知
                    processGroupActivate(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_GROUP_DISABLE -> {
                    // 处理群组V2禁用
                    processGroupDisable(senderId, tokenExchangeMessage)
                }
                org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_WEBHOOK_UPDATE -> {
                    // 处理Webhook配置更新
                    processWebhookUpdate(senderId, tokenExchangeMessage)
                }
                else -> {
                    Log.w(TAG, "未知的Token交换类型: ${tokenExchangeMessage.requestType}")
                    TapProcessResult.Failed("未知的Token交换类型")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Token交换消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理Token提供请求（A发送给B的请求）
     */
    private suspend fun processTokenOffer(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): TapProcessResult {
        Log.i(TAG, "处理Token提供请求: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
        
        return withContext(Dispatchers.Main) {
            try {
                // 如果包含Webhook配置,先保存
                if (tokenExchangeMessage.hasWebhookConfig()) {
                    val webhookConfigData = tokenExchangeMessage.extractWebhookConfig()
                    val gatewayConfigData = tokenExchangeMessage.extractGatewayConfig()
                    if (webhookConfigData != null) {
                        // 使用消息中的senderAci，避免本地Recipient解析不一致
                        val senderAci = tokenExchangeMessage.senderAci
                        saveContactWebhookConfig(
                            senderAci,
                            webhookConfigData,
                            tokenExchangeMessage.providerType,
                            gatewayConfigData
                        )
                        Log.i(TAG, "已保存Token Offer中的Webhook配置: senderAci=$senderAci")
                    }
                }
                
                // 显示确认对话框给用户
                showTokenExchangeConfirmDialog(senderId, tokenExchangeMessage)
                TapProcessResult.Success("Token交换确认对话框已显示")
            } catch (e: Exception) {
                Log.e(TAG, "显示Token交换确认对话框失败", e)
                TapProcessResult.Failed("显示确认对话框失败: ${e.message}")
            }
        }
    }
    
    /**
     * 处理Token接受回应（B发送给A的回应）
     */
    private suspend fun processTokenAccept(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): TapProcessResult {
        Log.i(TAG, "[Token交换] A端处理ACCEPT: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
        
        return try {
            // 获取对方的ACI（用于Token保存）
            // 使用消息中的senderAci作为peerAci，避免本地Recipient解析不一致
            val peerAci = tokenExchangeMessage.senderAci
            Log.d(TAG, "[Token交换] A端: senderId=$senderId, peerAci=$peerAci")
            val channelVersion = resolveChannelVersion(tokenExchangeMessage)
            
            // 如果包含Webhook配置,先保存
            if (tokenExchangeMessage.hasWebhookConfig()) {
                val webhookConfigData = tokenExchangeMessage.extractWebhookConfig()
                val gatewayConfigData = tokenExchangeMessage.extractGatewayConfig()
                if (webhookConfigData != null) {
                    Log.d(TAG, "[notifySecret调试] A端接收ACCEPT: notifySecret=${webhookConfigData.notifySecret.take(4)}...${webhookConfigData.notifySecret.takeLast(4)}, webhookUrl=${webhookConfigData.webhookUrl}, userId=${webhookConfigData.userId}")
                    
                    // 1. 保存到本地
                    saveContactWebhookConfig(
                        peerAci,
                        webhookConfigData,
                        tokenExchangeMessage.providerType,
                        gatewayConfigData
                    )
                    
                    // 2. 直接上传到 S3（确保 Lambda 能查找到 B 的配置）
                    val uploadSuccess = uploadContactConfigToS3(
                        peerAci,
                        webhookConfigData,
                        gatewayConfigData
                    )
                    if (uploadSuccess) {
                        Log.i(TAG, "已保存并上传Token Accept中的Webhook配置到S3: peerAci=$peerAci")
                    } else {
                        Log.w(TAG, "已保存Token Accept中的Webhook配置到本地，但上传S3失败: peerAci=$peerAci")
                    }
                }
            }

            if (tokenExchangeMessage.isGatewayOnlyChannel() && tokenExchangeMessage.tokenData.isEmpty()) {
                val senderAci = recipientIdToAci(senderId)
                if (senderAci == null) {
                    Log.w(TAG, "无法获取发送者ACI,跳过通道升级: senderId=$senderId")
                    return TapProcessResult.Failed("无法获取发送者ACI")
                }
                
                val upgraded = channelManager.upgradeChannelToFullActive(
                    senderAci,
                    tokenExchangeMessage.providerType,
                    org.thoughtcrime.securesms.tap.TransportChannelManager.ChannelUpgradeOptions(
                        channelVersion = channelVersion,
                        gatewayOnly = true
                    )
                )
                if (upgraded) {
                    Log.i(TAG, "[Token交换] Gateway-only通道激活成功: senderId=$senderId")
                    
                    // 异步处理后续操作（发送确认消息和插入系统消息）
                    processorScope.launch {
                        try {
                            handlePostUpgradeOperations(senderId, tokenExchangeMessage.providerType)
                        } catch (e: Exception) {
                            Log.e(TAG, "[Token交换] A端后续操作处理异常: senderId=$senderId", e)
                        }
                    }
                    
                    return TapProcessResult.Success("Gateway-only通道已激活")
                } else {
                    Log.w(TAG, "[Token交换] Gateway-only通道升级失败: senderId=$senderId")
                    return TapProcessResult.Failed("通道升级失败")
                }
            }
            
            // 将对方的Token保存到TokenPool，使用ACI格式（与metadata构建时保持一致）
            val peerToken = org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(tokenExchangeMessage.tokenData)
            if (peerToken == null) {
                Log.w(TAG, "[Token交换] 无法解析对方Token: senderId=$senderId")
                return TapProcessResult.Failed("无法解析对方Token")
            }
            
            Log.d(TAG, "[Token交换] 解析到Token: senderId=$senderId, tokenId=${peerToken.tokenId}, tokenType=${peerToken.javaClass.simpleName}")
            
            // 使用ACI格式保存Token（关键修复）
            val saved = tokenPool.addReceivedToken(peerAci, peerToken)
            if (!saved) {
                Log.w(TAG, "[Token交换] 保存对方Token失败: peerAci=$peerAci")
                return TapProcessResult.Failed("保存对方Token失败")
            }
            
            Log.i(TAG, "[Token交换] 已保存对方receivedToken: peerAci=$peerAci, tokenId=${peerToken.tokenId}")
            
            // 验证Token是否保存成功（使用ACI格式验证）
            val verifyToken = tokenPool.getValidReceivedToken(peerAci, tokenExchangeMessage.providerType)
            if (verifyToken == null) {
                Log.e(TAG, "[Token交换] Token验证失败：保存后无法读取: peerAci=$peerAci, providerType=${tokenExchangeMessage.providerType}")
                return TapProcessResult.Failed("Token验证失败")
            }
            Log.i(TAG, "[Token交换] Token验证通过: peerAci=$peerAci")
            
            // 验证是否有sharedToken（自己的token）
            val sharedToken = tokenPool.getValidSharedToken(peerAci, tokenExchangeMessage.providerType)
            if (sharedToken == null) {
                Log.w(TAG, "[Token交换] 警告：没有找到sharedToken: peerAci=$peerAci, providerType=${tokenExchangeMessage.providerType}")
            } else {
                Log.i(TAG, "[Token交换] sharedToken存在: peerAci=$peerAci, tokenId=${sharedToken.tokenId}")
            }
            
            // 更新通道状态为FULL_ACTIVE，使用RecipientId格式
            val upgraded = channelManager.upgradeChannelToFullActive(
                senderId.toString(),
                tokenExchangeMessage.providerType,
                org.thoughtcrime.securesms.tap.TransportChannelManager.ChannelUpgradeOptions(
                    channelVersion = channelVersion,
                    gatewayOnly = tokenExchangeMessage.isGatewayOnlyChannel()
                )
            )
            if (upgraded) {
                Log.i(TAG, "[Token交换] A端通道成功升级为FULL_ACTIVE: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
                
                // 验证通道的metadata是否包含正确的Token信息
                val channel = channelManager.getActiveChannel(senderId.toString(), tokenExchangeMessage.providerType)
                if (channel?.metadata != null) {
                    val receiveMetadata = channel.metadata!!.getReceiveMetadata()
                    Log.i(TAG, "[Token交换] 通道metadata验证: receivePath=${receiveMetadata.path}, hasToken=${receiveMetadata.token != null}")
                    
                    if (org.thoughtcrime.securesms.tap.polling.TapPollingService.isPollingEnabled()) {
                        try {
                            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                            val pollingStarted = pollingService.startPolling()
                            if (pollingStarted) {
                                val targetAdded = pollingService.addPollingTarget(senderId.toString(), channel.metadata!!, channel)
                                if (targetAdded) {
                                    Log.i(TAG, "[Token交换] A端轮询启动成功: senderId=$senderId")
                                } else {
                                    Log.w(TAG, "[Token交换] A端轮询目标添加失败: senderId=$senderId")
                                }
                            } else {
                                Log.w(TAG, "[Token交换] A端轮询服务启动失败: senderId=$senderId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[Token交换] A端启动轮询异常: senderId=$senderId", e)
                        }
                    } else {
                        Log.d(TAG, "[Token交换] 轮询已禁用，跳过A端轮询启动: senderId=$senderId")
                    }
                } else {
                    Log.w(TAG, "[Token交换] 通道metadata为null，无法启动轮询: senderId=$senderId")
                }
                
                // 异步处理后续操作（发送确认消息和插入系统消息）
                processorScope.launch {
                    try {
                        handlePostUpgradeOperations(senderId, tokenExchangeMessage.providerType)
                    } catch (e: Exception) {
                        Log.e(TAG, "[Token交换] A端后续操作处理异常: senderId=$senderId", e)
                    }
                }
                
            } else {
                Log.w(TAG, "[Token交换] A端通道升级失败，检查通道状态: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
                // 添加调试信息，检查当前通道状态
                val channels = channelManager.getActiveChannels(senderId.toString())
                channels.forEach { channel ->
                    Log.d(TAG, "[Token交换] A端通道状态: channelId=${channel.channelId}, status=${channel.status}, providerType=${channel.providerType}")
                }
                if (channels.isEmpty()) {
                    Log.w(TAG, "[Token交换] A端未找到任何活跃通道: senderId=$senderId")
                }
            }
            
            TapProcessResult.Success("Token交换完成，通道已升级")
        } catch (e: Exception) {
            Log.e(TAG, "[Token交换] 处理Token接受回应异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理Token交换确认（A发送给A）
     */
    private suspend fun processTokenConfirm(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): TapProcessResult {
        Log.i(TAG, "处理Token交换确认: senderId=$senderId")
        // 确认消息通常用于通知发送方Token交换已完成，可以开始使用v2通道
        // 这里可以进行一些状态检查或日志记录
        return TapProcessResult.Success("Token交换确认已处理")
    }

    /**
     * 处理v2模式禁用请求
     */
    private suspend fun processV2ModeDisable(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): TapProcessResult {
        Log.i(TAG, "处理v2模式禁用请求: senderId=$senderId")
        return try {
            val success = channelManager.disableV2Mode(senderId.toString())
            if (success) {
                TapProcessResult.Success("v2模式已禁用")
            } else {
                TapProcessResult.Failed("禁用v2模式失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理v2模式禁用请求异常", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 显示Token交换确认对话框
     * 使用通知方式显示，用户可以确认或拒绝Token交换请求
     */
    private fun showTokenExchangeConfirmDialog(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage) {
        // 获取发送者信息
        val senderRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(senderId)
        val senderName = senderRecipient.getDisplayName(context)
        
        Log.i(TAG, "收到Token交换请求: senderId=$senderId, senderName=$senderName, providerType=${tokenExchangeMessage.providerType}")
        Log.i(TAG, "Token数据: ${org.thoughtcrime.securesms.util.JsonUtils.toJson(tokenExchangeMessage.tokenData)}")
        
        // 显示确认通知
        showTokenExchangeNotification(senderId, senderName, tokenExchangeMessage)
    }
    
    /**
     * 显示Token交换确认通知
     */
    private fun showTokenExchangeNotification(senderId: org.thoughtcrime.securesms.recipients.RecipientId, senderName: String, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // 创建通知渠道（如果不存在）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "tap_token_exchange",
                "Tap Token Exchange",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Token交换请求通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建确认按钮的Intent
        val acceptIntent = android.content.Intent(context, TapTokenExchangeReceiver::class.java).apply {
            action = "ACCEPT_TOKEN_EXCHANGE"
            putExtra("senderId", senderId.toString())
            putExtra("tokenExchangeMessage", org.thoughtcrime.securesms.util.JsonUtils.toJson(tokenExchangeMessage))
        }
        val acceptPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            (senderId.toString() + "accept").hashCode(),
            acceptIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        // 创建拒绝按钮的Intent
        val rejectIntent = android.content.Intent(context, TapTokenExchangeReceiver::class.java).apply {
            action = "REJECT_TOKEN_EXCHANGE"
            putExtra("senderId", senderId.toString())
        }
        val rejectPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            (senderId.toString() + "reject").hashCode(),
            rejectIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_token_exchange")
            .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
            .setContentTitle("Tap v2模式请求")
            .setContentText("$senderName 想要与您建立v2模式传输通道")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                .bigText("$senderName 想要与您建立v2模式传输通道。这将允许消息通过${tokenExchangeMessage.providerType}传输层发送，而不是通过Signal服务器。"))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(org.thoughtcrime.securesms.R.drawable.v2_media_check, "接受", acceptPendingIntent)
            .addAction(org.thoughtcrime.securesms.R.drawable.symbol_x_white_24, "拒绝", rejectPendingIntent)
            .build()
        
        // 显示通知
        notificationManager.notify(senderId.toString().hashCode(), notification)
        
        Log.i(TAG, "Token交换确认通知已显示: senderId=$senderId, senderName=$senderName")
    }


    /**
     * 处理群组V2提议
     */
    private suspend fun processGroupTokenOffer(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
    ): TapProcessResult {
        Log.i(TAG, "处理群组 V2 提议: senderId=$senderId")
        
        return try {
            val groupId = tokenExchangeMessage.metadata["groupId"] as? String
            if (groupId == null) {
                Log.w(TAG, "群组提议消息缺少 groupId")
                return TapProcessResult.Failed("缺少 groupId")
            }
            
            // 异步处理通知显示，不阻塞消息处理流程
            processorScope.launch(Dispatchers.IO) {
                try {
                    withTimeout(3000L) {
                        // 在 IO 线程获取 RecipientId（数据库操作）
                        val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
                        
                        if (groupRecipientId != null) {
                            // 切换到主线程显示通知（Android 要求）
                            withContext(Dispatchers.Main) {
                                showGroupTokenExchangeNotification(groupRecipientId, groupId, tokenExchangeMessage, true)
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "获取群组信息超时，跳过通知显示")
                } catch (e: Exception) {
                    Log.e(TAG, "显示群组通知失败", e)
                }
            }
            
            // 立即返回成功，不等待通知显示完成
            TapProcessResult.Success("群组 V2 提议处理完成，通知将异步显示")
        } catch (e: Exception) {
            Log.e(TAG, "处理群组 V2 提议失败", e)
            TapProcessResult.Failed("处理失败: ${e.message}")
        }
    }

    /**
     * 处理群组V2接受
     */
    private suspend fun processGroupTokenAccept(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
    ): TapProcessResult {
        Log.i(TAG, "处理群组 V2 接受: senderId=$senderId")
        
        return try {
            val groupId = tokenExchangeMessage.metadata["groupId"] as? String
            if (groupId == null) {
                Log.w(TAG, "群组接受消息缺少 groupId")
                return TapProcessResult.Failed("缺少 groupId")
            }
            
            val accepterAci = tokenExchangeMessage.senderAci
            
            // 同步更新群组状态
            val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
            
            // 标记成员已同意
            try {
                val marked = groupManager.acceptV2Proposal(groupId, accepterAci)
                if (marked) {
                    Log.i(TAG, "[状态转换] 标记群组成员同意: accepter=$accepterAci, groupId=$groupId")
                    
                    // 检查是否所有成员都已同意，如果是则激活 V2 mode
                    val activated = groupManager.checkAndActivateV2Mode(groupId)
                    
                    if (activated) {
                        Log.i(TAG, "[状态转换] ✅ A端群组 V2 mode 已激活: groupId=$groupId")
                        
                        // 异步处理后续操作（建立通道、启动轮询、插入系统消息）
                        processorScope.launch {
                            try {
                                handleGroupActivationComplete(groupId, accepterAci)
                            } catch (e: Exception) {
                                Log.e(TAG, "处理群组激活后续操作失败: groupId=$groupId", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "[状态转换] 群组尚未全员同意，等待其他成员: groupId=$groupId")
                    }
                } else {
                    Log.w(TAG, "[状态转换] ❌ 标记群组成员同意失败: accepter=$accepterAci, groupId=$groupId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[状态转换] 同步更新群组状态异常: groupId=$groupId, accepter=$accepterAci", e)
            }
            
            TapProcessResult.Success("群组 V2 接受处理完成")
        } catch (e: Exception) {
            Log.e(TAG, "处理群组 V2 接受失败: senderId=$senderId", e)
            TapProcessResult.Failed("处理失败: ${e.message}")
        }
    }
    
    /**
     * 处理群组 V2 激活通知
     */
    private suspend fun processGroupActivate(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
    ): TapProcessResult {
        Log.i(TAG, "处理群组 V2 激活通知: senderId=$senderId")
        
        return try {
            val groupId = tokenExchangeMessage.metadata["groupId"] as? String
            if (groupId == null) {
                Log.w(TAG, "群组激活消息缺少 groupId")
                return TapProcessResult.Failed("缺少 groupId")
            }
            
            Log.i(TAG, "群组 V2 模式已激活: groupId=$groupId")
            TapProcessResult.Success("群组 V2 激活处理完成")
        } catch (e: Exception) {
            Log.e(TAG, "处理群组 V2 激活失败", e)
            TapProcessResult.Failed("处理失败: ${e.message}")
        }
    }
    
    /**
     * 处理群组 V2 禁用消息
     */
    private suspend fun processGroupDisable(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
    ): TapProcessResult {
        Log.i(TAG, "处理群组 V2 禁用: senderId=$senderId")
        
        return try {
            val groupId = tokenExchangeMessage.metadata["groupId"] as? String
            if (groupId == null) {
                Log.w(TAG, "群组禁用消息缺少 groupId")
                return TapProcessResult.Failed("缺少 groupId")
            }
            
            val senderAci = tokenExchangeMessage.senderAci
            
            Log.d(TAG, "同步处理群组禁用: groupId=$groupId, sender=$senderAci")
            
            val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
            val result = groupManager.handleDisableV2ModeRequest(groupId, senderAci)
            
            when (result) {
                is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Success -> {
                    Log.i(TAG, "✅ 群组 V2 模式已禁用: groupId=$groupId")
                    TapProcessResult.Success("群组 V2 模式已禁用")
                }
                is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Failed -> {
                    Log.w(TAG, "❌ 群组 V2 模式禁用失败: groupId=$groupId, error=${result.message}")
                    TapProcessResult.Failed("禁用失败: ${result.message}")
                }
                else -> {
                    Log.w(TAG, "群组 V2 模式禁用结果未知: groupId=$groupId")
                    TapProcessResult.Failed("禁用结果未知")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理群组 V2 禁用失败", e)
            TapProcessResult.Failed("处理失败: ${e.message}")
        }
    }

    /**
     * 处理Webhook配置更新
     */
    private suspend fun processWebhookUpdate(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
    ): TapProcessResult {
        Log.i(TAG, "处理Webhook配置更新: senderId=$senderId")
        
        return try {
            val webhookConfigData = tokenExchangeMessage.extractWebhookConfig()
            if (webhookConfigData == null) {
                Log.w(TAG, "Webhook更新消息缺少有效配置")
                return TapProcessResult.Failed("缺少有效的Webhook配置")
            }

            if (!webhookConfigData.validate()) {
                Log.w(TAG, "Webhook配置验证失败")
                return TapProcessResult.Failed("Webhook配置验证失败")
            }
            
            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(senderId)
            val senderAci = recipient.requireAci().toString()
            
            val gatewayConfigData = tokenExchangeMessage.extractGatewayConfig()

            val saved = saveContactWebhookConfig(
                senderAci = senderAci,
                webhookConfigData = webhookConfigData,
                providerType = tokenExchangeMessage.providerType,
                gatewayConfigData = gatewayConfigData
            )
            return if (saved) {
                Log.i(TAG, "Webhook配置更新成功: senderAci=$senderAci")
                TapProcessResult.Success("Webhook配置已更新")
            } else {
                Log.w(TAG, "Webhook配置更新失败: senderAci=$senderAci")
                TapProcessResult.Failed("Webhook配置保存失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Webhook更新异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }

    /**
     * 保存联系人的Webhook配置
     * 
     * 使用 NotificationConfigManager 保存，会同时：
     * 1. 保存配置到本地
     * 2. 上传配置到 S3 (tap-state/contacts/{contactHash}.json)
     * 这样 Lambda 函数才能读取到接收方的 webhook 配置
     */
    private suspend fun saveContactWebhookConfig(
        senderAci: String,
        webhookConfigData: org.thoughtcrime.securesms.tap.WebhookConfigData,
        providerType: String,
        gatewayConfigData: org.thoughtcrime.securesms.tap.GatewayConfigData? = null
    ): Boolean {
        return try {
            val contactConfig = org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig(
                contactId = senderAci,
                platform = providerType,
                webhookUrl = webhookConfigData.webhookUrl,
                notifySecret = webhookConfigData.notifySecret,
                userId = webhookConfigData.userId,
                lastUpdated = System.currentTimeMillis(),
                verified = false,
                websocketManagementEndpoint = gatewayConfigData?.endpoint,
                gatewayRegion = gatewayConfigData?.region,
                gatewayProvider = gatewayConfigData?.provider,
                offlineBucket = gatewayConfigData?.offlineBucket,
                presignDelegation = gatewayConfigData?.presignDelegation ?: false,
                gatewayMetadata = gatewayConfigData?.metadata ?: emptyMap()
            )
            
            // 使用 NotificationConfigManager 保存，会同时上传到 S3
            val notificationConfigManager = org.thoughtcrime.securesms.tap.notification.NotificationConfigManager.getInstance(context)
            notificationConfigManager.saveContactConfig(senderAci, contactConfig)
        } catch (e: Exception) {
            Log.e(TAG, "保存联系人Webhook配置异常: senderAci=$senderAci", e)
            false
        }
    }

    /**
     * 直接上传联系人配置到 S3
     * 确保 A 发送给 B 消息时，Lambda_A 能在 S3_A 找到 B 的配置
     */
    private suspend fun uploadContactConfigToS3(
        contactAci: String,
        webhookConfig: org.thoughtcrime.securesms.tap.WebhookConfigData,
        gatewayConfig: org.thoughtcrime.securesms.tap.GatewayConfigData?
    ): Boolean {
        return try {
            val notificationConfigManager = org.thoughtcrime.securesms.tap.notification.NotificationConfigManager.getInstance(context)
            notificationConfigManager.forceUploadContactConfig(contactAci, webhookConfig, gatewayConfig)
        } catch (e: Exception) {
            Log.e(TAG, "[S3上传] 联系人配置上传失败: contactAci=$contactAci", e)
            false
        }
    }

    private fun resolveChannelVersion(message: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): Int {
        return if (message.channelVersion <= 0) 2 else message.channelVersion
    }

    private suspend fun handleGroupActivationComplete(groupId: String, triggerMemberAci: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "[激活后处理] 开始处理群组激活后续操作: groupId=$groupId, trigger=$triggerMemberAci")
                
                val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
                val groupState = groupManager.getGroupStateSync(groupId)
                
                if (groupState != null && groupState.status == org.thoughtcrime.securesms.tap.group.GroupV2Status.FULL_V2_ACTIVE) {
                    activateGroupChannelsAndPolling(groupState)
                    
                    try {
                        val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
                        if (groupRecipientId != null) {
                            val helper = org.thoughtcrime.securesms.tap.group.GroupTokenExchangeHelper.getInstance(context)
                            helper.insertSystemMessage(
                                recipientId = groupRecipientId,
                                messageBody = "群组已启用 v2 mode",
                                isEnabled = true
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[激活后处理] 插入系统消息失败: groupId=$groupId", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[激活后处理] 处理群组激活后续操作异常: groupId=$groupId", e)
            }
        }
    }

    private suspend fun activateGroupChannelsAndPolling(groupState: org.thoughtcrime.securesms.tap.group.GroupV2State) {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val otherMembers = groupState.totalMembers.filter { it != myAci }
            
            val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
            val (successCount, _) = groupManager.establishGroupChannels(
                groupState.groupId,
                otherMembers.toSet(),
                groupState.providerType
            )
            
            if (successCount > 0) {
                startGroupPolling(groupState.groupId, otherMembers)
            }
        } catch (e: Exception) {
            Log.e(TAG, "激活群组通道失败: groupId=${groupState.groupId}", e)
        }
    }

    private suspend fun startGroupPolling(groupId: String, memberAcis: List<String>) {
        if (!org.thoughtcrime.securesms.tap.polling.TapPollingService.isPollingEnabled()) return
        try {
            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
            val groupState = groupManager.getGroupStateSync(groupId) ?: return
            
            val providerType = groupState.providerType
            val memberTokens = tokenPool.getGroupReceivedTokens(groupId, providerType)
            
            val memberMetadatas = mutableMapOf<String, org.thoughtcrime.securesms.tap.TransportMetadata>()
            for ((memberAci, token) in memberTokens) {
                val metadata = buildGroupPollingMetadata(groupId, memberAci, token, providerType)
                if (metadata != null) {
                    memberMetadatas[memberAci] = metadata
                }
            }
            
            if (memberMetadatas.isNotEmpty()) {
                pollingService.startPolling()
                pollingService.addGroupPollingTargets(groupId, memberMetadatas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动群组轮询失败: groupId=$groupId", e)
        }
    }

    private fun buildGroupPollingMetadata(
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
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showGroupTokenExchangeNotification(
        groupRecipientId: org.thoughtcrime.securesms.recipients.RecipientId,
        groupId: String,
        tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage,
        isProposer: Boolean
    ) {
        try {
            val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(groupRecipientId)
            val groupName = groupRecipient.getDisplayName(context)
            
            val senderAci = tokenExchangeMessage.senderAci
            val senderRecipientId = aciToRecipientId(senderAci)
            val senderName = if (senderRecipientId != null) {
                try {
                    org.thoughtcrime.securesms.recipients.Recipient.resolved(senderRecipientId).getDisplayName(context)
                } catch (e: Exception) {
                    "未知成员"
                }
            } else {
                "未知成员"
            }
            
            val proposerAci = tokenExchangeMessage.metadata["proposerAci"] as? String ?: tokenExchangeMessage.senderAci
            val proposerRecipientId = aciToRecipientId(proposerAci)
            val proposerName = if (proposerRecipientId != null) {
                try {
                    org.thoughtcrime.securesms.recipients.Recipient.resolved(proposerRecipientId).getDisplayName(context)
                } catch (e: Exception) {
                    "未知成员"
                }
            } else {
                "未知成员"
            }
            
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "tap_group_token_exchange",
                    "Group Tap Token Exchange",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "群组Token交换请求通知"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val conversationIntent = android.content.Intent(context, org.thoughtcrime.securesms.conversation.v2.ConversationActivity::class.java).apply {
                putExtra("recipient_id", groupRecipientId.serialize())
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val conversationPendingIntent = android.app.PendingIntent.getActivity(
                context,
                groupId.hashCode(),
                conversationIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val acceptIntent = android.content.Intent(context, org.thoughtcrime.securesms.tap.group.GroupTokenExchangeReceiver::class.java).apply {
                action = "ACCEPT_GROUP_TOKEN_EXCHANGE"
                putExtra("senderId", tokenExchangeMessage.senderAci)
                putExtra("groupRecipientId", groupRecipientId.toString())
                putExtra("groupId", groupId)
                putExtra("tokenExchangeMessage", org.thoughtcrime.securesms.util.JsonUtils.toJson(tokenExchangeMessage))
                putExtra("isProposer", isProposer)
            }
            val acceptPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                (groupId + "accept").hashCode(),
                acceptIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val rejectIntent = android.content.Intent(context, org.thoughtcrime.securesms.tap.group.GroupTokenExchangeReceiver::class.java).apply {
                action = "REJECT_GROUP_TOKEN_EXCHANGE"
                putExtra("senderId", tokenExchangeMessage.senderAci)
                putExtra("groupRecipientId", groupRecipientId.toString())
                putExtra("groupId", groupId)
            }
            val rejectPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                (groupId + "reject").hashCode(),
                rejectIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val notificationText = if (isProposer) {
                "$proposerName 提议将群组 \"$groupName\" 升级到 v2 mode"
            } else {
                "$senderName 同意群组 \"$groupName\" 使用 v2 mode"
            }
            
            val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_group_token_exchange")
                .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
                .setContentTitle("群组 V2 Mode 提议")
                .setContentText(notificationText)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(notificationText))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(conversationPendingIntent)
                .addAction(org.thoughtcrime.securesms.R.drawable.v2_media_check, "同意", acceptPendingIntent)
                .addAction(org.thoughtcrime.securesms.R.drawable.symbol_x_white_24, "拒绝", rejectPendingIntent)
                .build()
            
            notificationManager.notify(groupId.hashCode(), notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "显示群组 Token 交换通知失败", e)
        }
    }

    private fun diagnosisPollingTargetFailure(
        pollingService: org.thoughtcrime.securesms.tap.polling.TapPollingService,
        recipientAci: String,
        metadata: org.thoughtcrime.securesms.tap.TransportMetadata
    ) {
        // Implementation
    }

    private fun diagnosisChannelMetadataIssue(
        channel: org.thoughtcrime.securesms.tap.TransportChannel?,
        recipientAci: String,
        providerType: String
    ) {
        // Implementation
    }

    private suspend fun handlePostUpgradeOperations(
        senderId: org.thoughtcrime.securesms.recipients.RecipientId,
        providerType: String
    ) {
        try {
            sendTapConfirmationMessage(senderId, providerType)
            insertV2ModeEnabledMessage(senderId)
        } catch (e: Exception) {
            Log.e(TAG, "处理通道升级后续操作失败: senderId=$senderId", e)
        }
    }

    private suspend fun insertV2ModeEnabledMessage(recipientId: org.thoughtcrime.securesms.recipients.RecipientId) {
        try {
            org.thoughtcrime.securesms.database.SignalDatabase.messages.insertTapV2ModeEnabledMessage(recipientId)
        } catch (e: Exception) {
            Log.e(TAG, "插入v2 mode启用提示消息失败: recipientId=$recipientId", e)
        }
    }
    
    private fun sendTapConfirmationMessage(senderId: org.thoughtcrime.securesms.recipients.RecipientId, providerType: String) {
        Log.i(TAG, "发送Tap确认消息: senderId=$senderId, providerType=$providerType")
        
        processorScope.launch {
            try {
                val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(senderId)
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
                
                // 创建CONFIRM消息
                val confirmMessage = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage(
                    senderAci = myAci,
                    providerType = providerType,
                    tokenData = emptyMap(),
                    metadata = mapOf("confirmationType" to "channel_established"),
                    requestType = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_CONFIRM
                )
                
                val encodedMessage = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.encode(confirmMessage)
                
                val outgoingMessage = org.thoughtcrime.securesms.mms.OutgoingMessage.tapTokenExchangeMessage(
                    threadRecipient = recipient,
                    sentTimeMillis = System.currentTimeMillis(),
                    expiresIn = 0,
                    tokenExchangeData = encodedMessage
                )
                
                val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
                
                org.thoughtcrime.securesms.sms.MessageSender.send(
                    context,
                    outgoingMessage,
                    threadId,
                    org.thoughtcrime.securesms.sms.MessageSender.SendType.SIGNAL,
                    null,
                    null
                )
                
                Log.i(TAG, "Tap确认消息已发送: senderId=$senderId, providerType=$providerType")
            } catch (e: Exception) {
                Log.e(TAG, "发送Tap确认消息失败: senderId=$senderId", e)
            }
        }
    }

    private fun parseChannelRequest(data: String): ChannelRequestInfo? {
        return try {
            val msg = objectMapper.readValue(data, TapChannelRequestMessage::class.java)
            // TransportChannelConfig is a global config, so we use default values here.
            // Connection details from msg (address, paths) should be handled via Token or Metadata if needed.
            val config = TransportChannelConfig()
            val token = if (msg.token != null) org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(msg.token) else null
            ChannelRequestInfo(config, token)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseChannelResponse(data: String): ChannelResponseInfo? {
        return try {
            val msg = objectMapper.readValue(data, TapChannelResponseMessage::class.java)
            val token = if (msg.token != null) org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(msg.token) else null
            ChannelResponseInfo(msg.status, msg.providerType, token, msg.reason)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseChannelRevoke(data: String): ChannelRevokeInfo? {
        return try {
            val msg = objectMapper.readValue(data, TapChannelRevokeMessage::class.java)
            ChannelRevokeInfo(msg.recipientId)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseControlMessage(data: String): ControlMessageInfo? {
        return try {
            val msg = objectMapper.readValue(data, TapControlMessage::class.java)
            ControlMessageInfo(msg.type)
        } catch (e: Exception) {
            null
        }
    }

    private fun recipientIdToAci(recipientId: org.thoughtcrime.securesms.recipients.RecipientId): String? {
        return try {
            org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId).requireAci().toString()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun aciToRecipientId(aci: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            org.thoughtcrime.securesms.recipients.RecipientId.from(org.whispersystems.signalservice.api.push.ServiceId.ACI.parseOrThrow(aci))
        } catch (e: Exception) {
            null
        }
    }

    private fun getGroupRecipientIdFromGroupId(groupId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            val groupIdObj = if (org.thoughtcrime.securesms.groups.GroupId.isEncodedGroup(groupId)) {
                org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)
            } else {
                val bytes = android.util.Base64.decode(groupId, android.util.Base64.NO_WRAP)
                org.thoughtcrime.securesms.groups.GroupId.push(bytes)
            }
            org.thoughtcrime.securesms.recipients.Recipient.externalGroupExact(groupIdObj).id
        } catch (e: Exception) {
            Log.w(TAG, "解析群组ID失败: $groupId", e)
            null
        } catch (e: AssertionError) {
            Log.w(TAG, "解析群组ID断言错误: $groupId", e)
            null
        }
    }
}

/**
 * Tap处理结果
 */
sealed class TapProcessResult {
    data class Success(val message: String) : TapProcessResult()
    data class Failed(val error: String) : TapProcessResult()
}

/**
 * Tap Envelope处理结果
 */
sealed class TapEnvelopeProcessResult {
    data class Success(val messageId: String) : TapEnvelopeProcessResult()
    data class Failed(val error: String) : TapEnvelopeProcessResult()
    data class Duplicate(val messageId: String) : TapEnvelopeProcessResult()
}

private data class ChannelRequestInfo(
    val config: TransportChannelConfig,
    val token: TransportToken?
)

private data class ChannelResponseInfo(
    val status: String,
    val providerType: String,
    val token: TransportToken?,
    val reason: String?
)

private data class ChannelRevokeInfo(
    val recipientId: String
)

private data class ControlMessageInfo(
    val type: String
)

private data class TapChannelRequestMessage(
    @JsonProperty("recipientId") val recipientId: String,
    @JsonProperty("providerType") val providerType: String,
    @JsonProperty("token") val token: Map<String, Any>?,
    @JsonProperty("address") val address: String?,
    @JsonProperty("sendPath") val sendPath: String?,
    @JsonProperty("receivePath") val receivePath: String?,
    @JsonProperty("hashedId") val hashedId: String?,
    @JsonProperty("capabilities") val capabilities: List<String>?,
    @JsonProperty("version") val version: String?
)

private data class TapChannelResponseMessage(
    @JsonProperty("status") val status: String,
    @JsonProperty("providerType") val providerType: String,
    @JsonProperty("token") val token: Map<String, Any>?,
    @JsonProperty("reason") val reason: String?
)

private data class TapChannelRevokeMessage(
    @JsonProperty("recipientId") val recipientId: String
)

private data class TapControlMessage(
    @JsonProperty("type") val type: String
)
