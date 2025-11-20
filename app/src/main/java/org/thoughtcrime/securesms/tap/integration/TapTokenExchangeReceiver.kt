package org.thoughtcrime.securesms.tap.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportProvider
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.util.JsonUtils

/**
 * 处理Token交换确认/拒绝的广播接收器
 */
class TapTokenExchangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "TapTokenExchangeReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "收到Token交换操作: action=${intent.action}")
        
        val senderId = intent.getStringExtra("senderId") ?: return
        
        when (intent.action) {
            "ACCEPT_TOKEN_EXCHANGE" -> {
                val tokenExchangeMessageJson = intent.getStringExtra("tokenExchangeMessage")
                if (tokenExchangeMessageJson != null) {
                    handleTokenAcceptance(context, senderId, tokenExchangeMessageJson)
                }
            }
            "REJECT_TOKEN_EXCHANGE" -> {
                handleTokenRejection(context, senderId)
            }
        }
    }
    
    /**
     * 处理用户接受Token交换请求
     */
    private fun handleTokenAcceptance(context: Context, senderId: String, tokenExchangeMessageJson: String) {
        Log.i(TAG, "用户接受Token交换请求: senderId=$senderId")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 解析Token交换消息
                val tokenExchangeMessage = JsonUtils.fromJson(tokenExchangeMessageJson, TapTokenExchangeMessage::class.java)
                if (tokenExchangeMessage == null) {
                    Log.e(TAG, "无法解析Token交换消息")
                    return@launch
                }
                
                val channelVersion = resolveChannelVersion(tokenExchangeMessage)
                val gatewayOnly = tokenExchangeMessage.isGatewayOnlyChannel()

                // 2. 获取Token池实例并确保已初始化
                val tokenPool = TransportTokenPool.getInstance(context)
                
                // 确保TokenPool已初始化
                val initialized = tokenPool.initialize(org.thoughtcrime.securesms.tap.TransportTokenConfig())
                if (!initialized) {
                    Log.e(TAG, "TokenPool初始化失败")
                    return@launch
                }
                
                var senderAci = tokenExchangeMessage.senderAci
                if (senderAci.isBlank()) {
                    Log.e(TAG, "Token交换消息中缺少senderAci")
                    return@launch
                }

                // 3. 解析并保存接收到的Token（A发给B的Token）
                // 即使是Gateway-only通道，如果有Token数据也应该保存
                if (!gatewayOnly || tokenExchangeMessage.tokenData.isNotEmpty()) {
                    val receivedTokenData = tokenExchangeMessage.tokenData
                    if (receivedTokenData.isNotEmpty()) {
                        val receivedToken = org.thoughtcrime.securesms.tap.TransportTokenFactory.fromMap(receivedTokenData)
                        if (receivedToken == null) {
                            Log.e(TAG, "无法解析接收到的Token数据")
                            return@launch
                        }
                        
                        val saved = tokenPool.addReceivedToken(senderAci, receivedToken)
                        if (!saved) {
                            Log.e(TAG, "保存接收Token失败: senderId=$senderId, senderAci=$senderAci")
                            return@launch
                        }
                        
                        Log.i(TAG, "[Token交换] B端接收Token保存成功: senderId=$senderId, senderAci=$senderAci, tokenId=${receivedToken.tokenId}")
                    }
                } else {
                    Log.d(TAG, "Gateway-only握手且无Token数据，跳过接收Token保存: senderAci=$senderAci")
                }
                
                // 4. 执行对称操作 - 生成B的Token并发送给A
                generateAndSendResponseToken(context, senderId, tokenExchangeMessage, channelVersion)
                
                // 5. 关闭通知
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(senderId.hashCode())
                
            } catch (e: Exception) {
                Log.e(TAG, "处理Token接受失败", e)
            }
        }
    }
    
    /**
     * 处理用户拒绝Token交换请求
     */
    private fun handleTokenRejection(context: Context, senderId: String) {
        Log.i(TAG, "用户拒绝Token交换请求: senderId=$senderId")
        
        // 关闭通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(senderId.hashCode())
        
        // TODO: 可以考虑发送拒绝消息给对方
    }
    
    /**
     * 生成B的Token并发送响应给A
     */
    private suspend fun handleGatewayOnlyAcceptance(
        context: Context,
        senderId: String,
        originalMessage: TapTokenExchangeMessage,
        channelVersion: Int,
        provider: TransportProvider,
        providerConfig: Map<String, Any>
    ) {
        try {
            // 确保 senderId 是 ACI 格式
            val senderAci = if (senderId.contains("-") && senderId.length >= 32) {
                senderId
            } else {
                // 尝试转换为 ACI
                try {
                    val recipientId = when {
                        senderId.startsWith("RecipientId::") -> {
                            val id = senderId.removePrefix("RecipientId::").toLong()
                            org.thoughtcrime.securesms.recipients.RecipientId.from(id)
                        }
                        senderId.all { it.isDigit() } -> {
                            org.thoughtcrime.securesms.recipients.RecipientId.from(senderId.toLong())
                        }
                        else -> {
                            Log.e(TAG, "无法解析senderId格式: $senderId")
                            return
                        }
                    }
                    val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientId)
                    recipient.requireAci().toString()
                } catch (e: Exception) {
                    Log.e(TAG, "无法转换senderId为ACI: $senderId", e)
                    return
                }
            }
            
            Log.d(TAG, "Gateway-only握手: 原始senderId=$senderId, ACI=$senderAci")
            
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val channel = channelManager.getOrCreateChannel(
                recipientId = senderAci,
                providerType = originalMessage.providerType,
                provider = provider,
                options = org.thoughtcrime.securesms.tap.TransportChannelManager.ChannelOptions(
                    channelVersion = channelVersion,
                    gatewayOnly = true
                )
            )

            if (channel == null) {
                Log.e(TAG, "Gateway-only通道创建失败: recipientId=$senderAci")
                return
            }

            channelManager.upgradeChannelToFullActive(
                recipientId = senderAci,
                providerType = originalMessage.providerType,
                options = org.thoughtcrime.securesms.tap.TransportChannelManager.ChannelUpgradeOptions(
                    channelVersion = channelVersion,
                    gatewayOnly = true
                )
            )

            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val notificationConfigManager = org.thoughtcrime.securesms.tap.notification.NotificationConfigManager.getInstance(context)
            val localNotificationConfig = notificationConfigManager.getLocalConfig()
            val responseMessage = buildAcceptResponse(
                myAci = myAci,
                originalMessage = originalMessage,
                tokenPayload = emptyMap(),
                providerConfig = providerConfig,
                localNotificationConfig = localNotificationConfig,
                channelVersion = channelVersion
            )

            sendTokenExchangeResponse(context, senderId, responseMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Gateway-only握手处理失败", e)
        }
    }

    private suspend fun generateAndSendResponseToken(context: Context, senderId: String, originalMessage: TapTokenExchangeMessage, channelVersion: Int) {
        try {
            Log.i(TAG, "开始生成响应Token: senderId=$senderId")
            
            // 1. 确保TaP模块完全初始化
            try {
                val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
                tapInitializer.initializeSync(false)
                Log.d(TAG, "TaP模块同步初始化确认完成")
            } catch (e: Exception) {
                Log.e(TAG, "TaP模块初始化失败", e)
                return
            }
            
            // 2. 获取传输管理器和Provider
            val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
            var provider = transportManager.getProvider(originalMessage.providerType)
            
            // 3. 如果provider不存在，尝试按需创建
            if (provider == null) {
                Log.w(TAG, "Provider不存在，尝试按需创建: ${originalMessage.providerType}")
                
                val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                val providerConfig = configManager.getProviderConfig(originalMessage.providerType)
                
                if (providerConfig != null) {
                    Log.d(TAG, "找到Provider配置，尝试通过ProviderRegistry创建实例: ${originalMessage.providerType}")
                    
                    // 直接使用ProviderRegistry创建Provider，绕过工厂机制
                    val providerRegistry = org.thoughtcrime.securesms.tap.ProviderRegistry.getInstance(context)
                    providerRegistry.initialize() // 确保已初始化
                    
                    val registrar = providerRegistry.getProviderRegistrar(originalMessage.providerType)
                    if (registrar != null) {
                        Log.d(TAG, "找到Provider注册器: ${originalMessage.providerType}")
                        val createdProvider = registrar.createProvider(providerConfig, context)
                        
                        if (createdProvider != null) {
                            Log.i(TAG, "通过ProviderRegistry创建Provider成功: ${originalMessage.providerType}")
                            // 将创建的provider注册到管理器中
                            val providerManager = org.thoughtcrime.securesms.tap.TransportProviderManager.getInstance(context)
                            providerManager.registerProvider(createdProvider)
                            provider = createdProvider
                        } else {
                            Log.e(TAG, "ProviderRegistrar创建Provider失败: ${originalMessage.providerType}")
                        }
                    } else {
                        Log.e(TAG, "未找到Provider注册器: ${originalMessage.providerType}")
                        
                        // 作为后备方案，尝试通过TransportProviderManager创建
                        Log.d(TAG, "尝试后备方案：通过TransportProviderManager创建")
                        val providerManager = org.thoughtcrime.securesms.tap.TransportProviderManager.getInstance(context)
                        val createdProvider = providerManager.createProvider(originalMessage.providerType, providerConfig)
                        
                        if (createdProvider != null) {
                            Log.i(TAG, "通过后备方案创建Provider成功: ${originalMessage.providerType}")
                            provider = createdProvider
                        } else {
                            Log.e(TAG, "后备方案也创建Provider失败: ${originalMessage.providerType}")
                        }
                    }
                } else {
                    Log.e(TAG, "Provider配置不存在: ${originalMessage.providerType}")
                }
            }
            
            // 4. 最终验证provider是否可用
            if (provider == null) {
                Log.e(TAG, "无法获取Provider: ${originalMessage.providerType}")
                return
            }
            
            // 5. 获取配置（这里重新获取确保最新）
            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            val providerConfig = configManager.getProviderConfig(originalMessage.providerType)
            if (providerConfig == null) {
                Log.e(TAG, "无法获取Provider配置: ${originalMessage.providerType}")
                return
            }
            val providerConfigMap = providerConfig

            
            // 6. 清理旧的v2 mode状态（确保使用全新的Token和目录）
            val senderAci = originalMessage.senderAci
            Log.i(TAG, "B端清理旧的v2 mode状态: senderAci=$senderAci")
            
            // 6.1 清理旧sharedToken（receivedToken刚保存，保留它）
            try {
                val oldSharedRemoved = TransportTokenPool.getInstance(context).removeSharedToken(senderAci, originalMessage.providerType)
                if (oldSharedRemoved) {
                    Log.i(TAG, "B端已清理旧的sharedToken")
                }
            } catch (e: Exception) {
                Log.w(TAG, "B端清理旧sharedToken失败", e)
            }
            
            // 6.2 清理旧Channel
            try {
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                val oldChannels = channelManager.getActiveChannels(senderAci)
                if (oldChannels.isNotEmpty()) {
                    Log.i(TAG, "B端发现${oldChannels.size}个旧通道，准备清理")
                    oldChannels.forEach { oldChannel ->
                        channelManager.closeChannel(oldChannel.channelId)
                        Log.d(TAG, "B端已关闭旧通道: ${oldChannel.channelId}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "B端清理旧Channel失败", e)
            }
            
            if (originalMessage.isGatewayOnlyChannel() && originalMessage.tokenData.isEmpty()) {
                handleGatewayOnlyAcceptance(context, senderId, originalMessage, channelVersion, provider, providerConfigMap)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(senderId.hashCode())
                return
            }

            // 7. 为A生成专用Token
            val tokenRequest = org.thoughtcrime.securesms.tap.TransportTokenRequest(
                recipientId = originalMessage.senderAci,
                providerType = originalMessage.providerType,
                requestedPermissions = setOf(
                    org.thoughtcrime.securesms.tap.TransportPermission.READ,
                    org.thoughtcrime.securesms.tap.TransportPermission.LIST
                ),
                validityDurationMs = 0L, // 长期有效
                providerConfig = providerConfigMap,
                purpose = "v2_mode_response_token"
            )
            
            val generatedToken = provider.generateToken(tokenRequest)
            if (generatedToken == null) {
                Log.e(TAG, "生成响应Token失败")
                return
            }
            
            Log.i(TAG, "B端响应Token生成成功: tokenId=${generatedToken.tokenId}")
            
            // 8. 保存生成的Token到共享Token池
            val tokenPool = TransportTokenPool.getInstance(context)
            
            // 确保TokenPool已初始化
            val initialized = tokenPool.initialize(org.thoughtcrime.securesms.tap.TransportTokenConfig())
            if (!initialized) {
                Log.e(TAG, "TokenPool初始化失败")
                return
            }
            
            // 使用对方的ACI保存sharedToken（与receivedToken格式一致）
            val tokenSaved = tokenPool.addSharedToken(senderAci, generatedToken)
            if (!tokenSaved) {
                Log.e(TAG, "保存共享Token失败: senderAci=$senderAci")
                return
            }
            
            Log.i(TAG, "[Token交换] B端新sharedToken保存成功: senderAci=$senderAci, tokenId=${generatedToken.tokenId}")
            
            // 9. 建立传输通道 (B端为接收方建立通道，使用ACI格式)
            try {
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                
                // 确保通道管理器已初始化
                val initialized = channelManager.initialize(org.thoughtcrime.securesms.tap.TransportChannelConfig())
                if (!initialized) {
                    Log.w(TAG, "通道管理器初始化失败，跳过通道建立")
                } else {
                    // 使用senderAci（已经是ACI格式）创建通道
                    Log.d(TAG, "B端建立通道: 使用senderAci=$senderAci")
                    
                    val channel = channelManager.getOrCreateChannel(
                        recipientId = senderAci,
                        providerType = originalMessage.providerType,
                        provider = provider
                    )
                    
                    if (channel != null) {
                        Log.i(TAG, "B端成功建立传输通道: channelId=${channel.channelId}, recipientId=$senderId, senderAci=${originalMessage.senderAci}")
                        
                        // B端通道建立成功后立即启动轮询（使用RecipientId格式）
                        try {
                            Log.d(TAG, "B端通道建立后启动轮询: recipientId=$senderId")
                            val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                            
                            if (channel.metadata != null) {
                                val pollingStarted = pollingService.startPolling()
                                if (pollingStarted) {
                                    val targetAdded = pollingService.addPollingTarget(senderId, channel.metadata!!, channel)
                                    if (targetAdded) {
                                        Log.i(TAG, "B端通道建立后轮询启动成功: recipientId=$senderId")
                                    } else {
                                        Log.w(TAG, "B端通道建立后轮询目标添加失败: recipientId=$senderId")
                                    }
                                } else {
                                    Log.w(TAG, "B端通道建立后轮询服务启动失败: recipientId=$senderId")
                                }
                            } else {
                                Log.w(TAG, "B端通道建立时metadata为null，暂不启动轮询: recipientId=$senderId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "B端通道建立后启动轮询异常: recipientId=$senderId", e)
                        }
                    } else {
                        Log.w(TAG, "B端建立传输通道失败: recipientId=${originalMessage.senderAci}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "建立传输通道异常", e)
                // 不返回，继续发送响应消息
            }
            
            // 9. 创建响应消息
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val notificationConfigManager = org.thoughtcrime.securesms.tap.notification.NotificationConfigManager.getInstance(context)
            val localNotificationConfig = notificationConfigManager.getLocalConfig()
            val responseMessage = buildAcceptResponse(
                myAci = myAci,
                originalMessage = originalMessage,
                tokenPayload = generatedToken.toMap(),
                providerConfig = providerConfigMap,
                localNotificationConfig = localNotificationConfig,
                channelVersion = channelVersion
            )
            
            // 10. 发送响应消息
            sendTokenExchangeResponse(context, senderId, responseMessage)
            
            // 11. 验证B端通道状态和Token状态（使用RecipientId格式）
            delay(1000L) // 等待响应处理完成
            try {
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                val channels = channelManager.getActiveChannels(senderId)
                val fullActiveChannels = channels.filter { it.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FULL_ACTIVE }
                
                if (fullActiveChannels.isEmpty()) {
                    Log.w(TAG, "B端响应发送后未发现FULL_ACTIVE通道，状态可能异常: senderId=$senderId")
                    channels.forEach { channel ->
                        Log.d(TAG, "B端通道状态: channelId=${channel.channelId}, status=${channel.status}, providerType=${channel.providerType}")
                    }
                } else {
                    Log.i(TAG, "B端响应发送后确认通道状态正常: senderId=$senderId, fullActiveChannels=${fullActiveChannels.size}")
                }
                
                // 验证Token状态（使用ACI格式查询，与Token保存格式一致）
                val tokenPool = TransportTokenPool.getInstance(context)
                val senderAci = originalMessage.senderAci
                val receivedToken = tokenPool.getValidReceivedToken(senderAci, originalMessage.providerType)
                val sharedToken = tokenPool.getValidSharedToken(senderAci, originalMessage.providerType)
                
                Log.d(TAG, "[Token交换] B端Token状态验证: senderId=$senderId, senderAci=$senderAci, hasReceivedToken=${receivedToken != null}, hasSharedToken=${sharedToken != null}")
                
            } catch (verifyException: Exception) {
                Log.w(TAG, "B端状态验证失败，但不影响主流程: senderId=$senderId", verifyException)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "生成和发送响应Token失败", e)
        }
    }
    
    /**
     * 发送Token交换响应消息
     */
    private suspend fun sendTokenExchangeResponse(context: Context, senderId: String, responseMessage: TapTokenExchangeMessage) {
        try {
            Log.i(TAG, "开始发送Token交换响应: senderId=$senderId")
            
            // 1. 从responseMessage的metadata中获取原始发送者的ACI（A端的ACI）
            val targetAci = responseMessage.metadata["recipientAci"] as? String
            if (targetAci.isNullOrBlank()) {
                Log.e(TAG, "响应消息metadata中缺少recipientAci")
                return
            }
            
            val serviceId = org.whispersystems.signalservice.api.push.ServiceId.ACI.parseOrThrow(targetAci)
            val recipient = org.thoughtcrime.securesms.recipients.Recipient.externalPush(serviceId)
            
            Log.d(TAG, "发送响应消息目标: targetAci=$targetAci, recipient=${recipient.id}")
            
            // 2. 编码响应消息
            val encodedMessage = TapTokenExchangeMessage.encode(responseMessage)
            
            // 3. 创建OutgoingMessage
            val outgoingMessage = org.thoughtcrime.securesms.mms.OutgoingMessage.tapTokenExchangeMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                expiresIn = 0,
                tokenExchangeData = encodedMessage
            )
            
            // 4. 获取线程ID并发送消息
            val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            
            // 5. 发送消息
            org.thoughtcrime.securesms.sms.MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                org.thoughtcrime.securesms.sms.MessageSender.SendType.SIGNAL,
                null,
                null
            )
            
            Log.i(TAG, "Token交换响应已发送: senderId=$senderId")
            
        } catch (e: Exception) {
            Log.e(TAG, "发送Token交换响应失败", e)
        }
    }

    private fun buildAcceptResponse(
        myAci: String,
        originalMessage: TapTokenExchangeMessage,
        tokenPayload: Map<String, Any>,
        providerConfig: Map<String, Any>,
        localNotificationConfig: NotificationConfig?,
        channelVersion: Int
    ): TapTokenExchangeMessage {
        val metadata = mapOf(
            "recipientAci" to originalMessage.senderAci,
            "responseToTokenId" to (originalMessage.tokenData["tokenId"] ?: "")
        )

        return if (localNotificationConfig != null && localNotificationConfig.validate()) {
            val userId = localNotificationConfig.pushServiceInfo.metadata["userId"] as? String
                ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            Log.d(TAG, "[notifySecret调试] 发送ACCEPT: notifySecret=${localNotificationConfig.notifySecret.take(4)}...${localNotificationConfig.notifySecret.takeLast(4)}, webhookUrl=${localNotificationConfig.webhookUrl}, userId=$userId")
            val gatewayConfig = org.thoughtcrime.securesms.tap.utils.TapGatewayConfigBuilder.build(localNotificationConfig)
            TapTokenExchangeMessage.createWithWebhook(
                senderAci = myAci,
                providerType = originalMessage.providerType,
                tokenData = tokenPayload,
                metadata = metadata,
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_ACCEPT,
                webhookUrl = localNotificationConfig.webhookUrl,
                notifySecret = localNotificationConfig.notifySecret,
                userId = userId,
                gatewayConfig = gatewayConfig,
                channelVersion = channelVersion
            )
        } else {
            TapTokenExchangeMessage(
                senderAci = myAci,
                providerType = originalMessage.providerType,
                tokenData = tokenPayload,
                metadata = metadata,
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_ACCEPT,
                channelVersion = channelVersion
            )
        }
    }

    private fun resolveChannelVersion(message: TapTokenExchangeMessage): Int {
        return if (message.channelVersion <= 0) 2 else message.channelVersion
    }
}
