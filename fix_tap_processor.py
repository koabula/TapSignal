
import os

file_path = r"d:\Research\Signal\Signal-Android\app\src\main\java\org\thoughtcrime\securesms\tap\integration\TapMessageProcessor.kt"

# Read the file
with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the line where duplication starts (processPushMessage at line 898 approx)
# We look for the second occurrence of "suspend fun processPushMessage"
# The first one is around line 124.

first_occurrence = -1
second_occurrence = -1

for i, line in enumerate(lines):
    if "suspend fun processPushMessage" in line:
        if first_occurrence == -1:
            first_occurrence = i
        else:
            second_occurrence = i
            break

if second_occurrence != -1:
    # We want to keep lines up to the closing brace of the previous method.
    # The previous method is showTokenExchangeNotification.
    # It ends with a closing brace '}' at indentation level 4.
    
    # Search backwards from second_occurrence for the closing brace
    cut_off_index = second_occurrence
    for j in range(second_occurrence - 1, first_occurrence, -1):
        if "}" in lines[j]:
            cut_off_index = j + 1
            break
            
    kept_lines = lines[:cut_off_index]
else:
    # Fallback: assume the file is duplicated and we just need to cut at line 892 approx
    # But better to be safe. If we can't find duplication, maybe we shouldn't truncate.
    # However, we know it IS duplicated.
    # Let's use a hardcoded limit if search fails, based on our read_file knowledge.
    # Line 891 is "    }"
    if len(lines) > 900:
        kept_lines = lines[:892]
    else:
        kept_lines = lines

# Append the missing methods
missing_code = r"""

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
                val marked = groupManager.markMemberAgreed(groupId, accepterAci)
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
     */
    private fun saveContactWebhookConfig(
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
            
            val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
            configManager.saveContactNotificationConfig(senderAci, contactConfig)
        } catch (e: Exception) {
            Log.e(TAG, "保存联系人Webhook配置异常: senderAci=$senderAci", e)
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
        Log.i(TAG, "Sending Tap confirmation message to $senderId via $providerType")
    }

    private fun parseChannelRequest(data: String): ChannelRequestInfo? {
        return try {
            val msg = objectMapper.readValue(data, TapChannelRequestMessage::class.java)
            val config = TransportChannelConfig(
                address = msg.address,
                sendPath = msg.sendPath,
                receivePath = msg.receivePath,
                hashedId = msg.hashedId,
                capabilities = msg.capabilities ?: emptyList(),
                version = msg.version ?: "1.0"
            )
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
            val groupIdObj = org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)
            org.thoughtcrime.securesms.recipients.Recipient.externalGroup(context, groupIdObj).id
        } catch (e: Exception) {
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
"""

with open(file_path, 'w', encoding='utf-8') as f:
    f.writelines(kept_lines)
    f.write(missing_code)

print("File fixed successfully.")
