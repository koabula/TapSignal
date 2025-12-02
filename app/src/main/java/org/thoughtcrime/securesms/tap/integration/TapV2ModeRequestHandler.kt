package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.tap.ProviderRegistry
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportChannelStatus
import org.thoughtcrime.securesms.tap.TransportProviderConfigManager
import org.thoughtcrime.securesms.tap.TransportProviderManager
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportTokenConfig
import org.thoughtcrime.securesms.tap.TransportTokenFactory
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportTokenRequest
import org.thoughtcrime.securesms.tap.TransportPermission
import org.thoughtcrime.securesms.tap.notification.ContactNotificationConfig
import org.thoughtcrime.securesms.tap.notification.NotificationConfigManager
import org.thoughtcrime.securesms.tap.utils.TapGatewayConfigBuilder
import org.thoughtcrime.securesms.util.JsonUtils
import org.whispersystems.signalservice.api.push.ServiceId

/**
 * 处理 Tap v2 mode 请求的接受和拒绝逻辑
 */
object TapV2ModeRequestHandler {

    private const val TAG = "TapV2ModeRequestHandler"

    /**
     * 处理用户接受 Tap v2 mode 请求
     */
    suspend fun handleAccept(context: Context, senderId: String, tokenExchangeMessageJson: String) {
        Log.i(TAG, "Processing accept: senderId=$senderId")

        try {
            // 1. 关闭通知
            cancelNotification(context, senderId)

            // 2. 解析 Token 交换消息
            val tokenExchangeMessage = JsonUtils.fromJson(tokenExchangeMessageJson, TapTokenExchangeMessage::class.java)
            if (tokenExchangeMessage == null) {
                Log.e(TAG, "Failed to parse token exchange message")
                return
            }

            val channelVersion = resolveChannelVersion(tokenExchangeMessage)
            val gatewayOnly = tokenExchangeMessage.isGatewayOnlyChannel()

            // 3. 获取 Token 池并初始化
            val tokenPool = TransportTokenPool.getInstance(context)
            val initialized = tokenPool.initialize(TransportTokenConfig())
            if (!initialized) {
                Log.e(TAG, "TokenPool initialization failed")
                return
            }

            val senderAci = tokenExchangeMessage.senderAci
            if (senderAci.isBlank()) {
                Log.e(TAG, "Missing senderAci in token exchange message")
                return
            }

            // 4. 保存接收到的 Token
            if (!gatewayOnly || tokenExchangeMessage.tokenData.isNotEmpty()) {
                val receivedTokenData = tokenExchangeMessage.tokenData
                if (receivedTokenData.isNotEmpty()) {
                    val receivedToken = TransportTokenFactory.fromMap(receivedTokenData)
                    if (receivedToken == null) {
                        Log.e(TAG, "Failed to parse received token data")
                        return
                    }

                    val saved = tokenPool.addReceivedToken(senderAci, receivedToken)
                    if (!saved) {
                        Log.e(TAG, "Failed to save received token: senderId=$senderId, senderAci=$senderAci")
                        return
                    }

                    Log.i(TAG, "Received token saved: senderId=$senderId, senderAci=$senderAci, tokenId=${receivedToken.tokenId}")
                }
            }

            // 5. 生成响应 Token 并发送
            generateAndSendResponseToken(context, senderId, tokenExchangeMessage, channelVersion)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle accept", e)
        }
    }

    /**
     * 处理用户拒绝 Tap v2 mode 请求
     */
    suspend fun handleReject(context: Context, senderId: String, tokenExchangeMessageJson: String) {
        Log.i(TAG, "Processing reject: senderId=$senderId")

        try {
            // 1. 关闭通知
            cancelNotification(context, senderId)

            // 2. 解析 Token 交换消息
            val tokenExchangeMessage = JsonUtils.fromJson(tokenExchangeMessageJson, TapTokenExchangeMessage::class.java)
            if (tokenExchangeMessage == null) {
                Log.e(TAG, "Failed to parse token exchange message")
                return
            }

            // 3. 发送 REJECT 消息给对方
            sendRejectMessage(context, tokenExchangeMessage)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reject", e)
        }
    }

    /**
     * 发送 REJECT 消息给请求方
     */
    private suspend fun sendRejectMessage(context: Context, originalMessage: TapTokenExchangeMessage) {
        try {
            val myAci = SignalStore.account.requireAci().toString()
            val targetAci = originalMessage.senderAci

            Log.i(TAG, "Sending reject message: targetAci=$targetAci")

            val rejectMessage = TapTokenExchangeMessage(
                senderAci = myAci,
                providerType = originalMessage.providerType,
                tokenData = emptyMap(),
                metadata = mapOf(
                    "recipientAci" to targetAci,
                    "reason" to "user_rejected"
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_REJECT,
                channelVersion = originalMessage.channelVersion
            )

            val serviceId = ServiceId.ACI.parseOrThrow(targetAci)
            val recipient = Recipient.externalPush(serviceId)

            val encodedMessage = TapTokenExchangeMessage.encode(rejectMessage)

            val outgoingMessage = OutgoingMessage.tapTokenExchangeMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                expiresIn = 0,
                tokenExchangeData = encodedMessage
            )

            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

            MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null,
                null
            )

            Log.i(TAG, "Reject message sent: targetAci=$targetAci")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reject message", e)
        }
    }

    /**
     * 生成响应 Token 并发送给请求方
     */
    private suspend fun generateAndSendResponseToken(
        context: Context,
        senderId: String,
        originalMessage: TapTokenExchangeMessage,
        channelVersion: Int
    ) {
        try {
            Log.i(TAG, "Generating response token: senderId=$senderId")

            // 1. 初始化 TaP 模块
            try {
                val tapInitializer = TapModuleInitializer.getInstance(context)
                tapInitializer.initializeSync(false)
            } catch (e: Exception) {
                Log.e(TAG, "TaP module initialization failed", e)
                return
            }

            // 2. 获取 Provider
            val transportManager = TransportManager.getInstance(context)
            var provider = transportManager.getProvider(originalMessage.providerType)

            if (provider == null) {
                provider = createProvider(context, originalMessage.providerType)
                if (provider == null) {
                    Log.e(TAG, "Failed to get or create provider: ${originalMessage.providerType}")
                    return
                }
            }

            // 3. 获取配置
            val configManager = TransportProviderConfigManager.getInstance(context)
            val providerConfig = configManager.getProviderConfig(originalMessage.providerType)
            if (providerConfig == null) {
                Log.e(TAG, "Provider config not found: ${originalMessage.providerType}")
                return
            }

            // 4. 处理 Gateway-only 握手
            if (originalMessage.isGatewayOnlyChannel() && originalMessage.tokenData.isEmpty()) {
                handleGatewayOnlyAcceptance(context, senderId, originalMessage, channelVersion, provider, providerConfig)
                return
            }

            // 5. 清理旧状态
            val senderAci = originalMessage.senderAci
            cleanupOldState(context, senderAci, originalMessage.providerType)

            // 6. 生成响应 Token
            val tokenRequest = TransportTokenRequest(
                recipientId = senderAci,
                providerType = originalMessage.providerType,
                requestedPermissions = setOf(TransportPermission.READ, TransportPermission.LIST),
                validityDurationMs = 0L,
                providerConfig = providerConfig,
                purpose = "v2_mode_response_token"
            )

            val generatedToken = provider.generateToken(tokenRequest)
            if (generatedToken == null) {
                Log.e(TAG, "Failed to generate response token")
                return
            }

            Log.i(TAG, "Response token generated: tokenId=${generatedToken.tokenId}")

            // 7. 保存生成的 Token
            val tokenPool = TransportTokenPool.getInstance(context)
            val tokenSaved = tokenPool.addSharedToken(senderAci, generatedToken)
            if (!tokenSaved) {
                Log.e(TAG, "Failed to save shared token: senderAci=$senderAci")
                return
            }

            // 8. 建立传输通道
            establishChannel(context, senderId, senderAci, originalMessage, provider, channelVersion)

            // 9. 上传 contact config 到 S3
            uploadContactConfig(context, originalMessage.providerType)

            // 10. 构建并发送响应消息
            val responseMessage = buildAcceptResponse(context, originalMessage, generatedToken.toMap(), providerConfig, channelVersion)
            sendTokenExchangeResponse(context, senderId, responseMessage)

            // 11. 验证状态
            delay(1000L)
            verifyState(context, senderId, senderAci, originalMessage.providerType)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate and send response token", e)
        }
    }

    private suspend fun createProvider(context: Context, providerType: String): org.thoughtcrime.securesms.tap.TransportProvider? {
        val configManager = TransportProviderConfigManager.getInstance(context)
        val providerConfig = configManager.getProviderConfig(providerType) ?: return null

        val providerRegistry = ProviderRegistry.getInstance(context)
        providerRegistry.initialize()

        val registrar = providerRegistry.getProviderRegistrar(providerType)
        if (registrar != null) {
            val provider = registrar.createProvider(providerConfig, context)
            if (provider != null) {
                val providerManager = TransportProviderManager.getInstance(context)
                providerManager.registerProvider(provider)
                return provider
            }
        }

        val providerManager = TransportProviderManager.getInstance(context)
        return providerManager.createProvider(providerType, providerConfig)
    }

    private suspend fun handleGatewayOnlyAcceptance(
        context: Context,
        senderId: String,
        originalMessage: TapTokenExchangeMessage,
        channelVersion: Int,
        provider: org.thoughtcrime.securesms.tap.TransportProvider,
        providerConfig: Map<String, Any>
    ) {
        val senderAci = resolveSenderAci(senderId) ?: return

        val channelManager = TransportChannelManager.getInstance(context)
        val channel = channelManager.getOrCreateChannel(
            recipientId = senderAci,
            providerType = originalMessage.providerType,
            provider = provider,
            options = TransportChannelManager.ChannelOptions(
                channelVersion = channelVersion,
                gatewayOnly = true
            )
        )

        if (channel == null) {
            Log.e(TAG, "Gateway-only channel creation failed: recipientId=$senderAci")
            return
        }

        channelManager.upgradeChannelToFullActive(
            recipientId = senderAci,
            providerType = originalMessage.providerType,
            options = TransportChannelManager.ChannelUpgradeOptions(
                channelVersion = channelVersion,
                gatewayOnly = true
            )
        )

        val responseMessage = buildAcceptResponse(context, originalMessage, emptyMap(), providerConfig, channelVersion)
        sendTokenExchangeResponse(context, senderId, responseMessage)

        cancelNotification(context, senderId)
    }

    private fun resolveSenderAci(senderId: String): String? {
        return if (senderId.contains("-") && senderId.length >= 32) {
            senderId
        } else {
            try {
                val recipientId = when {
                    senderId.startsWith("RecipientId::") -> {
                        org.thoughtcrime.securesms.recipients.RecipientId.from(senderId.removePrefix("RecipientId::").toLong())
                    }
                    senderId.all { it.isDigit() } -> {
                        org.thoughtcrime.securesms.recipients.RecipientId.from(senderId.toLong())
                    }
                    else -> {
                        Log.e(TAG, "Invalid senderId format: $senderId")
                        return null
                    }
                }
                Recipient.resolved(recipientId).requireAci().toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve senderId to ACI: $senderId", e)
                null
            }
        }
    }

    private suspend fun cleanupOldState(context: Context, senderAci: String, providerType: String) {
        Log.i(TAG, "Cleaning up old state: senderAci=$senderAci")

        try {
            val tokenPool = TransportTokenPool.getInstance(context)
            tokenPool.removeSharedToken(senderAci, providerType)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove old shared token", e)
        }

        try {
            val channelManager = TransportChannelManager.getInstance(context)
            val oldChannels = channelManager.getActiveChannels(senderAci)
            oldChannels.forEach { channelManager.closeChannel(it.channelId) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close old channels", e)
        }
    }

    private suspend fun establishChannel(
        context: Context,
        senderId: String,
        senderAci: String,
        originalMessage: TapTokenExchangeMessage,
        provider: org.thoughtcrime.securesms.tap.TransportProvider,
        channelVersion: Int
    ) {
        try {
            val channelManager = TransportChannelManager.getInstance(context)
            val initialized = channelManager.initialize(org.thoughtcrime.securesms.tap.TransportChannelConfig())
            if (!initialized) {
                Log.w(TAG, "Channel manager initialization failed")
                return
            }

            val channel = channelManager.getOrCreateChannel(
                recipientId = senderAci,
                providerType = originalMessage.providerType,
                provider = provider
            )

            if (channel != null) {
                Log.i(TAG, "Channel established: channelId=${channel.channelId}")

                if (org.thoughtcrime.securesms.tap.polling.TapPollingService.isPollingEnabled() && channel.metadata != null) {
                    try {
                        val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                        if (pollingService.startPolling()) {
                            pollingService.addPollingTarget(senderId, channel.metadata!!, channel)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start polling", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish channel", e)
        }
    }

    private suspend fun uploadContactConfig(context: Context, providerType: String) {
        try {
            val myAci = SignalStore.account.requireAci().toString()
            val notificationConfigManager = NotificationConfigManager.getInstance(context)
            val localConfig = notificationConfigManager.getLocalConfig() ?: return

            if (localConfig.webhookUrl.isNullOrEmpty() || localConfig.notifySecret.isNullOrEmpty()) {
                return
            }

            val userId = localConfig.pushServiceInfo.metadata["userId"] as? String
                ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)

            val contactConfig = ContactNotificationConfig(
                contactId = myAci,
                platform = providerType,
                webhookUrl = localConfig.webhookUrl,
                notifySecret = localConfig.notifySecret,
                userId = userId,
                lastUpdated = System.currentTimeMillis(),
                verified = false,
                websocketManagementEndpoint = localConfig.pushServiceInfo.metadata["endpoint"] as? String,
                gatewayRegion = localConfig.pushServiceInfo.metadata["region"] as? String,
                gatewayProvider = localConfig.pushServiceInfo.metadata["provider"] as? String,
                offlineBucket = localConfig.pushServiceInfo.metadata["offlineBucket"] as? String,
                presignDelegation = localConfig.pushServiceInfo.metadata["presignDelegation"] as? Boolean ?: false,
                gatewayMetadata = emptyMap()
            )

            notificationConfigManager.saveContactConfig(myAci, contactConfig)
            Log.i(TAG, "Contact config uploaded: myAci=$myAci")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload contact config", e)
        }
    }

    private suspend fun buildAcceptResponse(
        context: Context,
        originalMessage: TapTokenExchangeMessage,
        tokenPayload: Map<String, Any>,
        providerConfig: Map<String, Any>,
        channelVersion: Int
    ): TapTokenExchangeMessage {
        val myAci = SignalStore.account.requireAci().toString()
        val notificationConfigManager = NotificationConfigManager.getInstance(context)
        val localConfig = notificationConfigManager.getLocalConfig()

        val metadata = mapOf(
            "recipientAci" to originalMessage.senderAci,
            "responseToTokenId" to (originalMessage.tokenData["tokenId"] ?: "")
        )

        val hasValidConfig = localConfig != null &&
            !localConfig.webhookUrl.isNullOrEmpty() &&
            !localConfig.notifySecret.isNullOrEmpty()

        return if (hasValidConfig) {
            val userId = localConfig!!.pushServiceInfo.metadata["userId"] as? String
                ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val gatewayConfig = TapGatewayConfigBuilder.build(localConfig)

            TapTokenExchangeMessage.createWithWebhook(
                senderAci = myAci,
                providerType = originalMessage.providerType,
                tokenData = tokenPayload,
                metadata = metadata,
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_ACCEPT,
                webhookUrl = localConfig.webhookUrl,
                notifySecret = localConfig.notifySecret,
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

    private suspend fun sendTokenExchangeResponse(context: Context, senderId: String, responseMessage: TapTokenExchangeMessage) {
        try {
            val targetAci = responseMessage.metadata["recipientAci"] as? String
            if (targetAci.isNullOrBlank()) {
                Log.e(TAG, "Missing recipientAci in response message metadata")
                return
            }

            val serviceId = ServiceId.ACI.parseOrThrow(targetAci)
            val recipient = Recipient.externalPush(serviceId)

            val encodedMessage = TapTokenExchangeMessage.encode(responseMessage)

            val outgoingMessage = OutgoingMessage.tapTokenExchangeMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                expiresIn = 0,
                tokenExchangeData = encodedMessage
            )

            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

            MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null,
                null
            )

            Log.i(TAG, "Token exchange response sent: senderId=$senderId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send token exchange response", e)
        }
    }

    private suspend fun verifyState(context: Context, senderId: String, senderAci: String, providerType: String) {
        try {
            val channelManager = TransportChannelManager.getInstance(context)
            val channels = channelManager.getActiveChannels(senderId)
            val fullActiveChannels = channels.filter { it.status == TransportChannelStatus.FULL_ACTIVE }

            if (fullActiveChannels.isEmpty()) {
                Log.w(TAG, "No FULL_ACTIVE channels after response: senderId=$senderId")
            } else {
                Log.i(TAG, "Channel state verified: senderId=$senderId, activeChannels=${fullActiveChannels.size}")
            }

            val tokenPool = TransportTokenPool.getInstance(context)
            val receivedToken = tokenPool.getValidReceivedToken(senderAci, providerType)
            val sharedToken = tokenPool.getValidSharedToken(senderAci, providerType)

            Log.d(TAG, "Token state: senderAci=$senderAci, hasReceivedToken=${receivedToken != null}, hasSharedToken=${sharedToken != null}")
        } catch (e: Exception) {
            Log.w(TAG, "State verification failed", e)
        }
    }

    private fun cancelNotification(context: Context, senderId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(senderId.hashCode())
    }

    private fun resolveChannelVersion(message: TapTokenExchangeMessage): Int {
        return if (message.channelVersion <= 0) 2 else message.channelVersion
    }
}
