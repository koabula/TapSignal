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
     * 检查轮询和通道是否正常
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
            val pollingService = TapPollingService.getInstance(context)
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            
            var fixedCount = 0
            for (groupState in activeGroups) {
                try {
                    val otherMembers = groupState.totalMembers.filter { it != myAci }
                    
                    // 检查每个成员的通道和轮询状态
                    var hasIssue = false
                    for (memberAci in otherMembers) {
                        // 检查通道
                        val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
                        if (channel == null) {
                            Log.w(TAG, "[状态同步] 群组成员缺少通道: groupId=${groupState.groupId}, member=$memberAci")
                            hasIssue = true
                            break
                        }
                        
                        // 检查轮询状态（可选，因为轮询可能暂停）
                        // TODO: 添加轮询状态检查
                    }
                    
                    if (hasIssue) {
                        Log.w(TAG, "[状态同步] 发现群组通道问题，尝试修复: groupId=${groupState.groupId}")
                        
                        // 重新建立通道和轮询
                        val (successCount, _) = groupManager.establishGroupChannels(
                            groupState.groupId,
                            otherMembers.toSet(),
                            groupState.providerType
                        )
                        
                        if (successCount > 0) {
                            Log.i(TAG, "[状态同步] ✅ 通道修复成功: groupId=${groupState.groupId}, 成功=$successCount")
                            
                            // 重新启动轮询
                            restartGroupPolling(groupState, otherMembers)
                            fixedCount++
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "[状态同步] 处理活跃群组失败: groupId=${groupState.groupId}", e)
                }
            }
            
            if (fixedCount > 0) {
                Log.i(TAG, "[状态同步] 修复了 $fixedCount 个 FULL_V2_ACTIVE 群组")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 检查 FULL_V2_ACTIVE 群组失败", e)
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
                restartGroupPolling(groupState, otherMembers)
            }
            
            if (failedMembers.isNotEmpty()) {
                Log.w(TAG, "[状态同步] 部分成员通道建立失败: groupId=${groupState.groupId}, failed=${failedMembers.size}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 处理群组激活失败: groupId=${groupState.groupId}", e)
        }
    }
    
    /**
     * 重新启动群组轮询
     */
    private suspend fun restartGroupPolling(groupState: GroupV2State, memberAcis: List<String>) {
        try {
            val channelManager = TransportChannelManager.getInstance(context)
            val pollingService = TapPollingService.getInstance(context)
            
            for (memberAci in memberAcis) {
                val channels = channelManager.getActiveChannels(memberAci)
                for (channel in channels) {
                    if (channel.metadata != null && channel.providerType == groupState.providerType) {
                        val added = pollingService.addPollingTarget(memberAci, channel.metadata!!, channel)
                        if (added) {
                            Log.d(TAG, "[状态同步] 重新添加轮询目标: groupId=${groupState.groupId}, member=$memberAci")
                        }
                    }
                }
            }
            
            pollingService.startPolling()
            Log.i(TAG, "[状态同步] 群组轮询已重启: groupId=${groupState.groupId}, members=${memberAcis.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "[状态同步] 重启群组轮询失败: groupId=${groupState.groupId}", e)
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
     * 发送状态修复通知
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
                .setContentTitle("群组状态已修复")
                .setContentText("$groupName: $message")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(groupState.groupId.hashCode(), notification)
            Log.d(TAG, "[通知] 发送状态修复通知: groupId=${groupState.groupId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "[通知] 发送状态修复通知失败", e)
        }
    }
    
    /**
     * 发送状态不一致通知
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

