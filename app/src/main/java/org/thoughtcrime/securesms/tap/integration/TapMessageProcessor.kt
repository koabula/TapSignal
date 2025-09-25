package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import org.thoughtcrime.securesms.recipients.Recipient
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

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
     * 处理传输层接收到的加密消息
     * 将消息交给TapEnvelopeAdapter处理Signal解密和入库
     * 
     * @param transportMessage 传输消息
     * @return 处理结果
     */
    suspend fun processTapTransportMessage(transportMessage: TransportMessage): TapProcessResult {
        Log.i(TAG, "处理Tap传输消息: messageId=${transportMessage.messageId}, type=${transportMessage.messageType}")
        
        return try {
            // 验证消息完整性
            if (!validateTransportMessage(transportMessage)) {
                Log.w(TAG, "传输消息验证失败: messageId=${transportMessage.messageId}")
                return TapProcessResult.Failed("消息验证失败")
            }
            
            // 将加密消息交给TapEnvelopeAdapter处理Signal相关逻辑
            val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
            val adapterResult = envelopeAdapter.processEncryptedMessage(transportMessage)
            
            when (adapterResult) {
                is TapEnvelopeProcessResult.Success -> {
                    Log.i(TAG, "传输消息处理成功: messageId=${transportMessage.messageId}")
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
                        // 存储对方提供的Token
                        val addResult = tokenPool.addReceivedToken(senderId.toString(), responseInfo.token)
                        if (!addResult) {
                            Log.w(TAG, "添加接收Token失败: senderId=$senderId")
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
            
            for (provider in availableProviders) {
                val providerType = provider.providerType
                val token = tokenPool.getValidReceivedToken(senderId.toString(), providerType)
                if (token != null && tokenPool.removeToken(senderId.toString(), providerType)) {
                    removed++
                }
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
                    // 处理Token交换确认（A发送给B）
                    processTokenConfirm(senderId, tokenExchangeMessage)
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
        Log.i(TAG, "处理Token接受回应: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
        
        return try {
            // 将RecipientId转换为ACI作为统一键
            val senderAci = recipientIdToAci(senderId)
            if (senderAci == null) {
                Log.w(TAG, "无法获取发送者ACI: senderId=$senderId")
                return TapProcessResult.Failed("无法获取发送者ACI")
            }
            
            // 将对方的Token保存到TokenPool，使用ACI作为键
            val peerToken = org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(tokenExchangeMessage.tokenData)
            if (peerToken == null) {
                Log.w(TAG, "无法解析对方Token: senderId=$senderId")
                return TapProcessResult.Failed("无法解析对方Token")
            }
            
            val saved = tokenPool.addReceivedToken(senderAci, peerToken)
            if (!saved) {
                Log.w(TAG, "保存对方Token失败: senderId=$senderId, senderAci=$senderAci")
                return TapProcessResult.Failed("保存对方Token失败")
            }
            
            Log.i(TAG, "已保存对方Token: senderId=$senderId, senderAci=$senderAci, tokenId=${peerToken.tokenId}")
            
            // 更新通道状态为FULL_ACTIVE，使用ACI作为键
            val upgraded = channelManager.upgradeChannelToFullActive(senderAci, tokenExchangeMessage.providerType)
            if (upgraded) {
                Log.i(TAG, "通道成功升级为FULL_ACTIVE: senderId=$senderId, senderAci=$senderAci, providerType=${tokenExchangeMessage.providerType}")
                
                // 发送确认消息给B端，通知其也升级通道
                try {
                    sendTapConfirmationMessage(senderAci, tokenExchangeMessage.providerType)
                    Log.i(TAG, "已发送Tap确认消息: senderAci=$senderAci")
                } catch (e: Exception) {
                    Log.e(TAG, "发送Tap确认消息失败: senderAci=$senderAci", e)
                }
                
                // 插入v2 mode启用提示消息
                insertV2ModeEnabledMessage(senderId)
                
            } else {
                Log.w(TAG, "通道升级失败: senderId=$senderId, senderAci=$senderAci, providerType=${tokenExchangeMessage.providerType}")
            }
            
            TapProcessResult.Success("Token交换完成，通道已升级")
        } catch (e: Exception) {
            Log.e(TAG, "处理Token接受回应异常: senderId=$senderId", e)
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
     * 解析通道请求数据
     */
    private fun parseChannelRequest(requestData: String): ChannelRequestInfo? {
        return try {
            val requestInfo = objectMapper.readValue<TapChannelRequestMessage>(requestData)
            Log.d(TAG, "解析通道请求: recipientId=${requestInfo.recipientId}, providerType=${requestInfo.providerType}")
            
            // 构建TransportChannelConfig (使用默认值，因为TransportChannelConfig没有这些参数)
            val config = TransportChannelConfig(
                maxChannels = 1000,
                channelTimeoutMs = requestInfo.timeout ?: 300000L,
                heartbeatIntervalMs = 60000L,
                cleanupIntervalMs = 3600000L,
                maxFailureCount = requestInfo.maxRetries ?: 10
            )
            
            // 解析Token
            val token = requestInfo.token?.let { tokenData ->
                when (requestInfo.providerType) {
                    "cos" -> CosTransportToken.fromMap(tokenData)
                    else -> null
                }
            }
            
            // 构建扩展信息
            val extendedInfo = ChannelRequestExtendedInfo(
                address = requestInfo.address,
                sendPath = requestInfo.sendPath,
                receivePath = requestInfo.receivePath,
                hashedId = requestInfo.hashedId,
                capabilities = requestInfo.capabilities ?: emptyList(),
                version = requestInfo.version ?: "1.0"
            )
            
            ChannelRequestInfo(config, token, extendedInfo)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道请求数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道请求（建议升级到JSON格式）")
                // 旧格式解析逻辑
                val config = TransportChannelConfig(
                    maxChannels = 1000,
                    channelTimeoutMs = 30000L,
                    heartbeatIntervalMs = 60000L,
                    cleanupIntervalMs = 3600000L,
                    maxFailureCount = 3
                )
                ChannelRequestInfo(config, null, null)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道请求解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 解析通道响应数据
     */
    private fun parseChannelResponse(responseData: String): ChannelResponseInfo? {
        return try {
            val responseInfo = objectMapper.readValue<TapChannelResponseMessage>(responseData)
            Log.d(TAG, "解析通道响应: status=${responseInfo.status}, providerType=${responseInfo.providerType}")
            
            // 解析Token
            val token = responseInfo.token?.let { tokenData ->
                when (responseInfo.providerType) {
                    "cos" -> CosTransportToken.fromMap(tokenData)
                    else -> null
                }
            }
            
            // 构建扩展信息
            val extendedInfo = ChannelResponseExtendedInfo(
                address = responseInfo.address,
                sendPath = responseInfo.sendPath,
                receivePath = responseInfo.receivePath,
                hashedId = responseInfo.hashedId,
                capabilities = responseInfo.capabilities ?: emptyList(),
                version = responseInfo.version ?: "1.0"
            )
            
            ChannelResponseInfo(
                status = responseInfo.status,
                providerType = responseInfo.providerType,
                token = token,
                reason = responseInfo.reason,
                extendedInfo = extendedInfo
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道响应数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道响应（建议升级到JSON格式）")
                ChannelResponseInfo(
                    status = if (responseData.contains("accepted")) "accepted" else "rejected",
                    providerType = "cos",
                    token = null,
                    reason = null,
                    extendedInfo = null
                )
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道响应解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 解析控制消息数据
     */
    private fun parseControlMessage(controlData: String): ControlMessageInfo? {
        return try {
            val controlInfo = objectMapper.readValue<TapControlMessage>(controlData)
            Log.d(TAG, "解析控制消息: type=${controlInfo.type}")
            ControlMessageInfo(controlInfo.type)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON控制消息数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析控制消息（建议升级到JSON格式）")
                ControlMessageInfo(controlData)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式控制消息解析也失败", legacyException)
                null
            }
        }
    }

    /**
     * 解析通道撤销数据
     */
    private fun parseChannelRevoke(revokeData: String): ChannelRevokeInfo? {
        return try {
            val revokeInfo = objectMapper.readValue<TapChannelRevokeMessage>(revokeData)
            Log.d(TAG, "解析通道撤销: recipientId=${revokeInfo.recipientId}")
            ChannelRevokeInfo(revokeInfo.recipientId)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道撤销数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道撤销（建议升级到JSON格式）")
                ChannelRevokeInfo(revokeData)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道撤销解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 检查消息是否重复
     */
    fun isDuplicateMessage(messageId: String, recipientId: String): Boolean {
        return try {
            Log.d(TAG, "检查消息重复性: messageId=$messageId, recipientId=$recipientId")
            
            // 使用TapEnvelopeAdapter中的TransportMessageDeduplicator进行去重检查
            val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
            val messageDeduplicator = org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator.getInstance(context)
            
            // 检查消息是否已被处理过
            val currentTimestamp = System.currentTimeMillis()
            val isDuplicate = messageDeduplicator.isDuplicate(messageId, recipientId, currentTimestamp)
            
            Log.d(TAG, "消息重复性检查结果: messageId=$messageId, recipientId=$recipientId, isDuplicate=$isDuplicate")
            isDuplicate
            
        } catch (e: Exception) {
            Log.w(TAG, "检查消息重复性失败: messageId=$messageId, recipientId=$recipientId", e)
            // 出错时保守处理，返回false避免丢失消息
            false
        }
    }
    
    /**
     * 标记消息为已处理
     */
    private fun markMessageAsProcessed(messageId: String, recipientId: String, timestamp: Long) {
        try {
            Log.d(TAG, "标记消息为已处理: messageId=$messageId, recipientId=$recipientId")
            
            // 通过TransportMessageDeduplicator标记消息为已处理
            val messageDeduplicator = org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator.getInstance(context)
            messageDeduplicator.markAsProcessed(messageId, recipientId, timestamp)
            
            Log.d(TAG, "消息标记完成: messageId=$messageId, recipientId=$recipientId")
        } catch (e: Exception) {
            Log.w(TAG, "标记消息已处理失败: messageId=$messageId, recipientId=$recipientId", e)
        }
    }
    
    /**
     * 清理过期的去重记录
     */
    private fun cleanupOldDuplicationRecords() {
        try {
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            val database = org.thoughtcrime.securesms.database.SignalDatabase.rawDatabase
            
            val deletedCount = database.delete(
                "transport_processed_messages",
                "processed_at < ?",
                arrayOf(thirtyDaysAgo.toString())
            )
            
            if (deletedCount > 0) {
                Log.d(TAG, "清理了 $deletedCount 条过期的去重记录")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "清理过期去重记录失败", e)
        }
    }
    
    /**
     * 调度后处理任务
     */
    private fun schedulePostProcessingJobs(
        insertResult: org.thoughtcrime.securesms.database.MessageTable.InsertResult,
        senderRecipient: Recipient
    ) {
        try {
            Log.d(TAG, "调度后处理任务: messageId=${insertResult.messageId}")
            
            // 触发附件下载
            val attachments = insertResult.insertedAttachments
            if (attachments != null && attachments.isNotEmpty()) {
                Log.d(TAG, "启动附件下载任务: messageId=${insertResult.messageId}, attachments=${attachments.size}")
                
                // 使用TapAttachmentDownloadInterceptor处理Tap附件下载
                val downloadInterceptor = TapAttachmentDownloadInterceptor.getInstance(context)
                attachments.forEach { (attachment, attachmentId) ->
                    if (attachment is org.thoughtcrime.securesms.attachments.DatabaseAttachment && downloadInterceptor.isTapAttachment(attachment)) {
                        Log.d(TAG, "检测到Tap附件，启动下载: attachmentId=${attachmentId}")
                        try {
                            downloadInterceptor.interceptAndDownload(insertResult.messageId, attachment)
                        } catch (e: Exception) {
                            Log.w(TAG, "Tap附件下载启动失败: attachmentId=${attachmentId}", e)
                        }
                    }
                }
            }
            
            // 其他后处理任务
            Log.d(TAG, "后处理任务调度完成: messageId=${insertResult.messageId}")
            
        } catch (e: Exception) {
            Log.w(TAG, "调度后处理任务失败: messageId=${insertResult.messageId}", e)
        }
    }

/**
 * 将RecipientId转换为ACI字符串
 */
private fun recipientIdToAci(recipientId: org.thoughtcrime.securesms.recipients.RecipientId): String? {
    return try {
        val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId)
        recipient.requireAci().toString()
    } catch (e: Exception) {
        Log.e(TAG, "无法从RecipientId获取ACI: $recipientId", e)
        null
    }
}

/**
 * 发送Tap确认消息
 */
private suspend fun sendTapConfirmationMessage(recipientAci: String, providerType: String) {
    withContext(Dispatchers.IO) {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val serviceId = org.whispersystems.signalservice.api.push.ServiceId.ACI.parseOrThrow(recipientAci)
            val recipient = org.thoughtcrime.securesms.recipients.Recipient.externalPush(serviceId)
            
            val confirmMessage = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage(
                senderAci = myAci,
                providerType = providerType,
                tokenData = emptyMap(), // 确认消息不需要token数据
                metadata = mapOf(
                    "confirmationType" to "channel_upgrade",
                    "timestamp" to System.currentTimeMillis()
                ),
                requestType = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.REQUEST_TYPE_CONFIRM
            )
            
            val encodedMessage = org.thoughtcrime.securesms.tap.TapTokenExchangeMessage.encode(confirmMessage)
            val outgoingMessage = org.thoughtcrime.securesms.mms.OutgoingMessage.tapTokenExchangeMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                expiresIn = 0,
                tokenExchangeData = encodedMessage
            )
            
            // 通过Signal Server发送确认消息
            val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            val messageId = org.thoughtcrime.securesms.database.SignalDatabase.messages.insertMessageOutbox(outgoingMessage, threadId, false, null)
            if (messageId > 0) {
                org.thoughtcrime.securesms.jobs.IndividualSendJob.enqueue(context, org.thoughtcrime.securesms.dependencies.AppDependencies.jobManager, messageId, recipient, false)
                Log.i(TAG, "Tap确认消息已加入发送队列: messageId=$messageId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "发送Tap确认消息异常", e)
            throw e
        }
    }
}

/**
 * 处理Token交换确认（A发送给B的确认）
 */
private suspend fun processTokenConfirm(senderId: org.thoughtcrime.securesms.recipients.RecipientId, tokenExchangeMessage: org.thoughtcrime.securesms.tap.TapTokenExchangeMessage): TapProcessResult {
    Log.i(TAG, "处理Token交换确认: senderId=$senderId, providerType=${tokenExchangeMessage.providerType}")
    
    return try {
        val senderAci = recipientIdToAci(senderId)
        if (senderAci == null) {
            Log.w(TAG, "无法获取发送者ACI: senderId=$senderId")
            return TapProcessResult.Failed("无法获取发送者ACI")
        }
        
        // B端收到A的确认消息，将自己的通道升级为FULL_ACTIVE
        val upgraded = channelManager.upgradeChannelToFullActive(senderAci, tokenExchangeMessage.providerType)
        if (upgraded) {
            Log.i(TAG, "收到确认消息，通道升级为FULL_ACTIVE: senderId=$senderId, senderAci=$senderAci, providerType=${tokenExchangeMessage.providerType}")
            
            // 插入v2 mode启用提示消息
            insertV2ModeEnabledMessage(senderId)
            
        } else {
            Log.w(TAG, "收到确认消息但通道升级失败: senderId=$senderId, senderAci=$senderAci, providerType=${tokenExchangeMessage.providerType}")
        }
        
        TapProcessResult.Success("Token交换确认处理完成")
    } catch (e: Exception) {
        Log.e(TAG, "处理Token交换确认异常: senderId=$senderId", e)
        TapProcessResult.Failed("处理异常: ${e.message}")
    }
}

/**
 * 插入v2 mode启用提示消息
 */
private suspend fun insertV2ModeEnabledMessage(recipientId: org.thoughtcrime.securesms.recipients.RecipientId) {
    withContext(Dispatchers.IO) {
        try {
            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId)
            val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            
            // 创建系统提示消息 - 使用Profile名称变更消息的方式
            val messageBody = "🔒 Tap v2 mode enabled"
            
            // 使用insertChatSessionRefreshedMessage作为模板，插入系统消息
            val insertResult = org.thoughtcrime.securesms.database.SignalDatabase.messages.insertChatSessionRefreshedMessage(
                recipientId,
                0, // senderDeviceId
                System.currentTimeMillis() // sentTimestamp
            )
            
            Log.i(TAG, "已插入v2 mode启用提示消息: messageId=${insertResult.messageId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "插入v2 mode启用提示消息失败，跳过此步骤", e)
            // 不抛出异常，避免影响主流程
        }
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

/**
 * 通道请求信息
 */
private data class ChannelRequestInfo(
    val config: TransportChannelConfig,
    val token: TransportToken?,
    val extendedInfo: ChannelRequestExtendedInfo? = null
)

/**
 * 通道请求扩展信息
 */
private data class ChannelRequestExtendedInfo(
    val address: String?,
    val sendPath: String?,
    val receivePath: String?,
    val hashedId: String?,
    val capabilities: List<String>,
    val version: String
)

/**
 * 通道响应信息
 */
private data class ChannelResponseInfo(
    val status: String,
    val providerType: String,
    val token: TransportToken?,
    val reason: String?,
    val extendedInfo: ChannelResponseExtendedInfo? = null
)

/**
 * 通道响应扩展信息
 */
private data class ChannelResponseExtendedInfo(
    val address: String?,
    val sendPath: String?,
    val receivePath: String?,
    val hashedId: String?,
    val capabilities: List<String>,
    val version: String
)

/**
 * 控制消息信息
 */
private data class ControlMessageInfo(
    val type: String
)

/**
 * 通道撤销信息
 */
private data class ChannelRevokeInfo(
    val recipientId: String
)

/**
 * JSON消息格式定义
 */
private data class TapChannelRequestMessage(
    @JsonProperty("recipientId") val recipientId: String,
    @JsonProperty("providerType") val providerType: String,
    @JsonProperty("priority") val priority: Int?,
    @JsonProperty("maxRetries") val maxRetries: Int?,
    @JsonProperty("timeout") val timeout: Long?,
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
    @JsonProperty("reason") val reason: String?,
    @JsonProperty("address") val address: String?,
    @JsonProperty("sendPath") val sendPath: String?,
    @JsonProperty("receivePath") val receivePath: String?,
    @JsonProperty("hashedId") val hashedId: String?,
    @JsonProperty("capabilities") val capabilities: List<String>?,
    @JsonProperty("version") val version: String?
)

private data class TapChannelRevokeMessage(
    @JsonProperty("recipientId") val recipientId: String
)

private data class TapControlMessage(
    @JsonProperty("type") val type: String
) 