package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.polling.TapPollingService

/**
 * 群组 V2 状态同步器
 * 
 * 定期检查群组 V2 mode 状态，自动修复不一致的状态：
 * 1. 检查处于 PROPOSING 状态但所有成员都已同意的群组 → 自动激活
 * 2. 检查处于 FULL_V2_ACTIVE 但未启动轮询的群组 → 重新启动轮询
 * 3. 检查处于 FULL_V2_ACTIVE 但通道未建立的群组 → 重新建立通道
 */
class GroupV2StateSynchronizer private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupV2StateSynchronizer::class.java)
        
        // 检查间隔：5分钟
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
        
        // 启动延迟：30秒（避免启动时立即执行）
        private const val INITIAL_DELAY_MS = 30 * 1000L
        
        @Volatile
        private var INSTANCE: GroupV2StateSynchronizer? = null
        
        @JvmStatic
        fun getInstance(context: Context): GroupV2StateSynchronizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupV2StateSynchronizer(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    
    /**
     * 启动状态同步器
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "状态同步器已在运行")
            return
        }
        
        isRunning = true
        Log.i(TAG, "启动群组 V2 状态同步器")
        
        scope.launch {
            // 初始延迟
            delay(INITIAL_DELAY_MS)
            
            while (isActive && isRunning) {
                try {
                    performStateCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "状态检查异常", e)
                }
                
                // 等待下次检查
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止状态同步器
     */
    fun stop() {
        isRunning = false
        Log.i(TAG, "停止群组 V2 状态同步器")
    }
    
    /**
     * 执行状态检查
     */
    private suspend fun performStateCheck() {
        try {
            Log.d(TAG, "[状态同步] 开始状态同步检查")
            val startTime = System.currentTimeMillis()
            
            val groupManager = GroupTransportManager.getInstance(context)
            
            // 1. 检查 PROPOSING 状态的群组
            checkProposingGroups(groupManager)
            
            // 2. 检查 FULL_V2_ACTIVE 状态的群组
            checkActiveGroups(groupManager)
            
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "[状态同步] 状态同步检查完成，耗时: ${duration}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 状态检查失败", e)
        }
    }
    
    /**
     * 检查 PROPOSING 状态的群组
     * 
     * 如果所有成员都已同意但状态仍为 PROPOSING，自动激活
     */
    private suspend fun checkProposingGroups(groupManager: GroupTransportManager) {
        try {
            val proposingGroups = groupManager.getGroupsByStatus(GroupV2Status.PROPOSING)
            
            if (proposingGroups.isEmpty()) {
                Log.d(TAG, "[状态同步] 没有处于 PROPOSING 状态的群组")
                return
            }
            
            Log.d(TAG, "[状态同步] 检查 PROPOSING 状态群组: ${proposingGroups.size}个")
            
            var fixedCount = 0
            for (groupState in proposingGroups) {
                try {
                    if (groupState.isFullyAgreed()) {
                        Log.w(TAG, "[状态同步] 发现不一致状态：群组全员同意但仍为 PROPOSING: groupId=${groupState.groupId}")
                        
                        // 尝试激活
                        val activated = groupManager.checkAndActivateV2Mode(groupState.groupId)
                        if (activated) {
                            Log.i(TAG, "[状态同步] ✅ 自动激活成功: groupId=${groupState.groupId}")
                            fixedCount++
                            
                            // 触发激活后处理
                            handleGroupActivation(groupState)
                            
                            // 发送通知给用户
                            sendStateFixedNotification(groupState, "群组 v2 mode 已自动激活")
                        } else {
                            Log.w(TAG, "[状态同步] 自动激活失败: groupId=${groupState.groupId}")
                            
                            // 发送通知提示用户手动处理
                            sendInconsistencyNotification(groupState, "群组 v2 mode 状态异常，需要手动处理")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[状态同步] 处理群组失败: groupId=${groupState.groupId}", e)
                }
            }
            
            if (fixedCount > 0) {
                Log.i(TAG, "[状态同步] 修复了 $fixedCount 个 PROPOSING 群组")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 检查 PROPOSING 群组失败", e)
        }
    }
    
    /**
     * 检查 FULL_V2_ACTIVE 状态的群组
     * 
     * 检查通道和资源是否正常，发现异常则自动降级到Native
     */
    private suspend fun checkActiveGroups(groupManager: GroupTransportManager) {
        try {
            val activeGroups = groupManager.getGroupsByStatus(GroupV2Status.FULL_V2_ACTIVE)
            
            if (activeGroups.isEmpty()) {
                Log.d(TAG, "[状态同步] 没有处于 FULL_V2_ACTIVE 状态的群组")
                return
            }
            
            Log.d(TAG, "[状态同步] 检查 FULL_V2_ACTIVE 状态群组: ${activeGroups.size}个")
            
            val channelManager = TransportChannelManager.getInstance(context)
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            val pollingService = TapPollingService.getInstance(context)
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            
            var degradedCount = 0
            for (groupState in activeGroups) {
                try {
                    val otherMembers = groupState.totalMembers.filter { it != myAci }
                    
                    // 检查每个成员的资源状态
                    val issues = mutableListOf<String>()
                    for (memberAci in otherMembers) {
                        // 检查通道
                        val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
                        if (channel == null) {
                            issues.add("成员通道缺失: ${sanitizeMemberAci(memberAci)}")
                            Log.w(TAG, "[状态同步] 群组成员缺少通道: groupId=${groupState.groupId}, member=${sanitizeMemberAci(memberAci)}")
                            continue
                        }
                        
                        // 检查Token
                        val token = tokenPool.getValidReceivedToken(memberAci, groupState.providerType)
                        if (token == null) {
                            issues.add("成员Token缺失: ${sanitizeMemberAci(memberAci)}")
                            Log.w(TAG, "[状态同步] 群组成员缺少Token: groupId=${groupState.groupId}, member=${sanitizeMemberAci(memberAci)}")
                            continue
                        }
                        
                        // 检查轮询连续失败情况
                        val pollingState = pollingService.getPollingState(memberAci, groupState.providerType)
                        if (pollingState != null && pollingState.consecutiveErrors > 10) {
                            issues.add("轮询连续失败(${pollingState.consecutiveErrors}次): ${sanitizeMemberAci(memberAci)}")
                            Log.w(TAG, "[状态同步] 群组成员轮询连续失败: groupId=${groupState.groupId}, member=${sanitizeMemberAci(memberAci)}, failures=${pollingState.consecutiveErrors}")
                            continue
                        }
                    }
                    
                    if (issues.isNotEmpty()) {
                        Log.w(TAG, "[状态同步] ⚠️ 检测到群组异常，自动降级到Native: groupId=${groupState.groupId}, issues=${issues.joinToString("; ")}")
                        
                        // ✅ 降级到Native（而不是尝试修复）
                        val result = groupManager.degradeV2ModeOnError(
                            groupId = groupState.groupId,
                            reason = issues.first() // 使用第一个问题作为主要原因
                        )
                        
                        when (result) {
                            is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Success -> {
                                Log.i(TAG, "[状态同步] ✅ 群组已降级到Native: groupId=${groupState.groupId}")
                                degradedCount++
                                
                                // 通知用户
                                notifyUserAboutDegradation(groupState, issues.first())
                            }
                            is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Failed -> {
                                Log.e(TAG, "[状态同步] ❌ 群组降级失败: groupId=${groupState.groupId}, error=${result.message}")
                            }
                            else -> {
                                Log.w(TAG, "[状态同步] 群组降级结果未知: groupId=${groupState.groupId}")
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "[状态同步] 处理活跃群组失败: groupId=${groupState.groupId}", e)
                }
            }
            
            if (degradedCount > 0) {
                Log.i(TAG, "[状态同步] 降级了 $degradedCount 个 FULL_V2_ACTIVE 群组到Native")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 检查 FULL_V2_ACTIVE 群组失败", e)
        }
    }
    
    /**
     * 脱敏成员ACI用于日志
     */
    private fun sanitizeMemberAci(memberAci: String): String {
        return if (memberAci.length > 8) {
            "${memberAci.substring(0, 4)}...${memberAci.takeLast(4)}"
        } else {
            "****"
        }
    }
    
    /**
     * 处理群组激活
     */
    private suspend fun handleGroupActivation(groupState: GroupV2State) {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val otherMembers = groupState.totalMembers.filter { it != myAci }
            
            // 建立通道
            val groupManager = GroupTransportManager.getInstance(context)
            val (successCount, failedMembers) = groupManager.establishGroupChannels(
                groupState.groupId,
                otherMembers.toSet(),
                groupState.providerType
            )
            
            if (successCount > 0) {
                Log.i(TAG, "[状态同步] 群组通道建立成功: groupId=${groupState.groupId}, 成功=$successCount")
                
                // 启动轮询
                try {
                    val channelManager = TransportChannelManager.getInstance(context)
                    val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                    
                    for (memberAci in otherMembers) {
                        val channels = channelManager.getActiveChannels(memberAci)
                        for (channel in channels) {
                            if (channel.metadata != null && channel.providerType == groupState.providerType) {
                                pollingService.addPollingTarget(memberAci, channel.metadata!!, channel)
                                Log.d(TAG, "[状态同步] 添加轮询目标: groupId=${groupState.groupId}, member=$memberAci")
                            }
                        }
                    }
                    
                    pollingService.startPolling()
                    Log.i(TAG, "[状态同步] 群组轮询已启动: groupId=${groupState.groupId}, members=${otherMembers.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "[状态同步] 启动群组轮询失败: groupId=${groupState.groupId}", e)
                }
            }
            
            if (failedMembers.isNotEmpty()) {
                Log.w(TAG, "[状态同步] 部分成员通道建立失败: groupId=${groupState.groupId}, failed=${failedMembers.size}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 处理群组激活失败: groupId=${groupState.groupId}", e)
        }
    }
    
    
    /**
     * 手动触发状态检查
     * 
     * 用于调试或用户主动触发
     */
    suspend fun performManualCheck() {
        Log.i(TAG, "[状态同步] 手动触发状态检查")
        performStateCheck()
    }
    
    /**
     * 通知用户群组v2 mode已自动降级
     */
    private fun notifyUserAboutDegradation(groupState: GroupV2State, reason: String) {
        try {
            val groupRecipientId = getGroupRecipientId(groupState.groupId) ?: return
            val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(groupRecipientId)
            val groupName = groupRecipient.getDisplayName(context)
            
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            
            // 创建通知渠道
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "tap_group_degradation",
                    "Group V2 Degradation",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "群组 V2 模式降级通知"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // 创建点击通知后跳转到群组的 Intent
            val conversationIntent = android.content.Intent(context, org.thoughtcrime.securesms.conversation.v2.ConversationActivity::class.java).apply {
                putExtra("recipient_id", groupRecipientId.serialize())
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val conversationPendingIntent = android.app.PendingIntent.getActivity(
                context,
                groupState.groupId.hashCode(),
                conversationIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建通知
            val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_group_degradation")
                .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
                .setContentTitle("群组已切换回常规模式")
                .setContentText("$groupName: $reason")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("$groupName\n\nv2 mode 因异常已自动关闭: $reason\n\n已切换回 Signal Server 模式。如需重新启用，请在群组菜单中选择 \"Use v2 mode\""))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(conversationPendingIntent)
                .build()
            
            notificationManager.notify(groupState.groupId.hashCode(), notification)
            Log.d(TAG, "[通知] 发送降级通知: groupId=${groupState.groupId}, reason=$reason")
            
        } catch (e: Exception) {
            Log.e(TAG, "[通知] 发送降级通知失败", e)
        }
    }
    
    /**
     * 发送状态修复通知（用于PROPOSING自动激活）
     */
    private fun sendStateFixedNotification(groupState: GroupV2State, message: String) {
        try {
            val groupRecipientId = getGroupRecipientId(groupState.groupId) ?: return
            val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(groupRecipientId)
            val groupName = groupRecipient.getDisplayName(context)
            
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            
            // 创建通知渠道
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "tap_group_state_sync",
                    "Group V2 State Sync",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "群组 V2 状态同步通知"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // 创建通知
            val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_group_state_sync")
                .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
                .setContentTitle("群组 v2 mode 已激活")
                .setContentText("$groupName: $message")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(groupState.groupId.hashCode(), notification)
            Log.d(TAG, "[通知] 发送激活通知: groupId=${groupState.groupId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "[通知] 发送激活通知失败", e)
        }
    }
    
    /**
     * 发送状态不一致通知（用于无法自动处理的异常）
     */
    private fun sendInconsistencyNotification(groupState: GroupV2State, message: String) {
        try {
            val groupRecipientId = getGroupRecipientId(groupState.groupId) ?: return
            val groupRecipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(groupRecipientId)
            val groupName = groupRecipient.getDisplayName(context)
            
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            
            // 创建通知渠道
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "tap_group_state_sync",
                    "Group V2 State Sync",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "群组 V2 状态同步通知"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // 创建点击通知后跳转到群组的 Intent
            val conversationIntent = android.content.Intent(context, org.thoughtcrime.securesms.conversation.v2.ConversationActivity::class.java).apply {
                putExtra("recipient_id", groupRecipientId.serialize())
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val conversationPendingIntent = android.app.PendingIntent.getActivity(
                context,
                groupState.groupId.hashCode(),
                conversationIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建通知
            val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_group_state_sync")
                .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
                .setContentTitle("群组状态异常")
                .setContentText("$groupName: $message")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$groupName: $message\n\n点击查看详情"))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(conversationPendingIntent)
                .build()
            
            notificationManager.notify(groupState.groupId.hashCode(), notification)
            Log.d(TAG, "[通知] 发送状态异常通知: groupId=${groupState.groupId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "[通知] 发送状态异常通知失败", e)
        }
    }
    
    /**
     * 获取群组 RecipientId
     */
    private fun getGroupRecipientId(groupId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            val result = org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.convert(groupId, context)
            when (result) {
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Success -> result.recipientId
                is org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter.ConversionResult.Failed -> {
                    Log.w(TAG, "转换 groupId 失败: $groupId")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取群组 RecipientId 异常: groupId=$groupId", e)
            null
        }
    }
}


