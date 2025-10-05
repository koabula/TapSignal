# Phase 5: 成员变动处理 - 集成指南

## 概述
本文档说明如何将 Phase 5 实现的成员变动处理功能集成到 Signal 的现有群组管理流程中。

## 1. 集成到群组更新流程

### 1.1 在 GroupV2UpdateJob 中添加同步

**位置**: `app/src/main/java/org/thoughtcrime/securesms/jobs/GroupV2UpdateJob.java`

**建议添加位置**: 在群组更新完成后

```kotlin
// 在 GroupV2UpdateJob 的 onRun() 方法末尾添加
private suspend fun syncV2ModeAfterGroupUpdate(groupId: String) {
    try {
        val synchronizer = GroupMembershipSynchronizer.getInstance(context)
        val success = synchronizer.syncGroupMembership(groupId)
        if (success) {
            Log.i(TAG, "V2 mode 成员同步成功: $groupId")
        }
    } catch (e: Exception) {
        Log.e(TAG, "V2 mode 成员同步失败: $groupId", e)
        // 不抛出异常，避免影响群组更新流程
    }
}
```

### 1.2 监听群组成员变更事件

**方案 A: 在数据库监听器中添加**

```kotlin
// 在 GroupTable 的成员变更方法中
fun onGroupMembersChanged(groupId: GroupId) {
    // 现有逻辑...
    
    // 触发 v2 mode 同步
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val base64GroupId = Base64.encodeWithPadding(groupId.decodedId)
            GroupMembershipSynchronizer.getInstance(context)
                .syncGroupMembership(base64GroupId)
        } catch (e: Exception) {
            Log.e(TAG, "V2 mode 同步失败", e)
        }
    }
}
```

**方案 B: 创建定期同步任务**

```kotlin
class GroupV2ModeSyncJob : Job {
    override fun run(): Result {
        val synchronizer = GroupMembershipSynchronizer.getInstance(context)
        val syncedCount = runBlocking {
            synchronizer.syncAllActiveGroups()
        }
        Log.i(TAG, "批量同步完成: $syncedCount 个群组")
        return Result.success()
    }
}

// 在 AppDependencies 中定期调度
JobManager.schedulePeriodic(
    GroupV2ModeSyncJob(),
    TimeUnit.HOURS.toMillis(1)  // 每小时同步一次
)
```

## 2. UI 集成

### 2.1 新成员加入提示

**位置**: `ConversationFragment` 或群组详情页

**场景**: 用户加入一个已经启用 v2 mode 的群组

**实现示例**:

```kotlin
class ConversationFragment : Fragment() {
    
    private fun checkAndShowV2ModeJoinPrompt() {
        val recipient = viewModel.recipient.value ?: return
        if (!recipient.isGroup) return
        
        val groupId = recipient.requireGroupId().toString()
        
        lifecycleScope.launch {
            val groupTransportManager = GroupTransportManager.getInstance(requireContext())
            val groupState = groupTransportManager.getGroupState(groupId)
            
            if (groupState?.status == GroupV2Status.FULL_V2_ACTIVE) {
                val myAci = SignalStore.account.requireAci().toString()
                
                // 检查我是否已经加入
                if (myAci !in groupState.agreedMembers && myAci in groupState.totalMembers) {
                    // 显示提示对话框
                    showV2ModeJoinDialog(groupId)
                }
            }
        }
    }
    
    private fun showV2ModeJoinDialog(groupId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("该群组使用 v2 mode")
            .setMessage("该群组通过 Tap 层传输消息。是否加入 v2 mode？")
            .setPositiveButton("加入") { _, _ ->
                joinV2Mode(groupId)
            }
            .setNegativeButton("暂不加入", null)
            .show()
    }
    
    private fun joinV2Mode(groupId: String) {
        lifecycleScope.launch {
            try {
                // 显示加载提示
                val progressDialog = showLoadingDialog("正在加入 v2 mode...")
                
                val groupTransportManager = GroupTransportManager.getInstance(requireContext())
                val recipient = viewModel.recipient.value ?: return@launch
                val memberRecipientIds = getMemberRecipientIds(recipient)
                
                val success = groupTransportManager.requestJoinV2Mode(
                    groupId = groupId,
                    memberRecipientIds = memberRecipientIds
                )
                
                progressDialog.dismiss()
                
                if (success) {
                    Toast.makeText(context, "成功加入 v2 mode", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "加入 v2 mode 失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加入 v2 mode 失败", e)
                Toast.makeText(context, "加入 v2 mode 失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun getMemberRecipientIds(recipient: Recipient): List<RecipientId> {
        val groupId = recipient.requireGroupId()
        return SignalDatabase.groups.getGroupMembers(
            groupId,
            GroupTable.MemberSet.FULL_MEMBERS_INCLUDING_SELF
        )
    }
}
```

### 2.2 群组信息页显示成员 v2 mode 状态

**位置**: `GroupMembersFragment` 或群组详情页

```kotlin
class GroupMemberItem(
    val recipient: Recipient,
    val isV2ModeActive: Boolean  // 新增字段
)

class GroupMembersAdapter {
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        // 现有绑定逻辑...
        
        // 显示 v2 mode 状态
        if (item.isV2ModeActive) {
            holder.v2ModeIndicator.visibility = View.VISIBLE
            holder.v2ModeIndicator.setImageResource(R.drawable.ic_v2_mode_active)
        } else {
            holder.v2ModeIndicator.visibility = View.GONE
        }
    }
}

// 加载成员列表时
private suspend fun loadGroupMembers(groupId: String): List<GroupMemberItem> {
    val groupState = GroupTransportManager.getInstance(context).getGroupState(groupId)
    val agreedMembers = groupState?.agreedMembers ?: emptySet()
    
    return members.map { recipient ->
        val memberAci = recipient.aci.orElse(null)?.toString()
        GroupMemberItem(
            recipient = recipient,
            isV2ModeActive = memberAci in agreedMembers
        )
    }
}
```

## 3. TapMessageProcessor 集成

### 3.1 处理新成员加入消息

**位置**: `TapMessageProcessor.kt`

**添加到现有的消息处理逻辑中**:

```kotlin
private suspend fun handleGroupAcceptMessage(
    exchangeMessage: TapTokenExchangeMessage,
    senderRecipientId: RecipientId
) {
    val metadata = exchangeMessage.metadata
    val groupId = metadata["groupId"] as? String ?: return
    val isNewMember = metadata["isNewMember"] as? Boolean ?: false
    val isNewMemberResponse = metadata["isNewMemberResponse"] as? Boolean ?: false
    
    when {
        isNewMember -> {
            // 老成员收到新成员加入请求
            handleNewMemberJoinRequest(groupId, exchangeMessage, senderRecipientId)
        }
        
        isNewMemberResponse -> {
            // 新成员收到老成员响应
            handleNewMemberJoinResponse(groupId, exchangeMessage, senderRecipientId)
        }
        
        else -> {
            // 现有的 GROUP_ACCEPT 处理逻辑
            handleRegularGroupAccept(groupId, exchangeMessage, senderRecipientId)
        }
    }
}

private suspend fun handleNewMemberJoinRequest(
    groupId: String,
    exchangeMessage: TapTokenExchangeMessage,
    newMemberRecipientId: RecipientId
) {
    val newMemberAci = exchangeMessage.senderAci
    Log.i(TAG, "收到新成员加入请求: groupId=$groupId, newMember=$newMemberAci")
    
    // 保存新成员发送的 tokens
    val tokensData = exchangeMessage.metadata["tokens"] as? Map<String, Any> ?: return
    val myAci = SignalStore.account.requireAci().toString()
    val myToken = extractTokenForMember(tokensData, myAci)
    
    if (myToken != null) {
        val tokenPool = TransportTokenPool.getInstance(context)
        tokenPool.addReceivedToken(newMemberAci, myToken)
        Log.i(TAG, "保存新成员的 token 成功")
    }
    
    // 更新群组状态
    val groupTransportManager = GroupTransportManager.getInstance(context)
    groupTransportManager.acceptV2Proposal(groupId, newMemberAci)
    
    // 响应新成员
    val success = groupTransportManager.respondToNewMemberJoin(
        groupId = groupId,
        newMemberAci = newMemberAci,
        newMemberRecipientId = newMemberRecipientId
    )
    
    if (success) {
        Log.i(TAG, "响应新成员加入成功")
    }
}

private suspend fun handleNewMemberJoinResponse(
    groupId: String,
    exchangeMessage: TapTokenExchangeMessage,
    oldMemberRecipientId: RecipientId
) {
    val oldMemberAci = exchangeMessage.senderAci
    Log.i(TAG, "收到老成员响应: groupId=$groupId, oldMember=$oldMemberAci")
    
    // 保存老成员发送的 token
    val token = TransportTokenFactory.fromMap(exchangeMessage.tokenData)
    if (token != null) {
        val tokenPool = TransportTokenPool.getInstance(context)
        tokenPool.addReceivedToken(oldMemberAci, token)
        Log.i(TAG, "保存老成员的 token 成功")
        
        // 建立通道
        val channelManager = TransportChannelManager.getInstance(context)
        val providerType = exchangeMessage.providerType
        val transportManager = TransportManager.getInstance(context)
        val provider = transportManager.getProvider(providerType)
        
        if (provider != null) {
            val channel = channelManager.getOrCreateChannel(
                recipientId = oldMemberAci,
                providerType = providerType,
                provider = provider
            )
            
            if (channel != null) {
                Log.i(TAG, "与老成员的通道已建立")
                
                // 启动轮询
                val pollingService = TapPollingService.getInstance(context)
                val metadata = channel.metadata
                pollingService.addPollingTarget(oldMemberAci, metadata)
            }
        }
    }
    
    // 检查是否收到所有老成员的响应
    checkIfAllResponsesReceived(groupId)
}

private suspend fun checkIfAllResponsesReceived(groupId: String) {
    val groupTransportManager = GroupTransportManager.getInstance(context)
    val groupState = groupTransportManager.getGroupState(groupId) ?: return
    
    val myAci = SignalStore.account.requireAci().toString()
    val otherMembers = groupState.totalMembers - myAci
    
    val tokenPool = TransportTokenPool.getInstance(context)
    val receivedTokensCount = otherMembers.count { memberAci ->
        tokenPool.getReceivedToken(memberAci, groupState.providerType) != null
    }
    
    if (receivedTokensCount == otherMembers.size) {
        Log.i(TAG, "已收到所有老成员的响应，完全加入 v2 mode")
        
        // 可以显示通知或更新 UI
        showNotification("已完全加入群组 v2 mode")
    } else {
        Log.d(TAG, "等待更多响应: $receivedTokensCount/${otherMembers.size}")
    }
}
```

## 4. 后台同步任务

### 4.1 创建定期同步 Job

```kotlin
class GroupV2ModeSyncJob private constructor(parameters: Parameters) : BaseJob(parameters) {
    
    companion object {
        const val KEY = "GroupV2ModeSyncJob"
        
        @JvmStatic
        fun enqueue() {
            val job = GroupV2ModeSyncJob(
                Parameters.Builder()
                    .setQueue(KEY)
                    .setMaxAttempts(3)
                    .setLifespan(TimeUnit.HOURS.toMillis(1))
                    .build()
            )
            
            AppDependencies.jobManager.add(job)
        }
    }
    
    override fun serialize(): ByteArray = ByteArray(0)
    
    override fun getFactoryKey(): String = KEY
    
    override fun onRun() {
        val synchronizer = GroupMembershipSynchronizer.getInstance(context)
        
        val syncedCount = runBlocking {
            synchronizer.syncAllActiveGroups()
        }
        
        Log.i(TAG, "V2 mode 批量同步完成: $syncedCount 个群组")
    }
    
    override fun onShouldRetry(e: Exception): Boolean {
        return e is IOException || e is TimeoutException
    }
    
    override fun onFailure() {
        Log.w(TAG, "V2 mode 批量同步失败")
    }
    
    class Factory : Job.Factory<GroupV2ModeSyncJob> {
        override fun create(parameters: Parameters, data: ByteArray): GroupV2ModeSyncJob {
            return GroupV2ModeSyncJob(parameters)
        }
    }
}

// 在 JobManagerFactories 中注册
put(GroupV2ModeSyncJob.KEY, GroupV2ModeSyncJob.Factory())

// 在应用启动时调度
// 在 ApplicationContext 或 AppInitialization 中
fun scheduleV2ModeSync() {
    JobManager.schedule(
        GroupV2ModeSyncJob.KEY,
        JobSchedule.periodic(TimeUnit.HOURS.toMillis(1))  // 每小时同步一次
    )
}
```

## 5. 手动同步入口

### 5.1 在群组设置中添加手动同步选项

```kotlin
// 在群组设置菜单中添加
class GroupSettingsFragment : Fragment() {
    
    private fun onManualSync() {
        lifecycleScope.launch {
            try {
                val progressDialog = showLoadingDialog("正在同步成员状态...")
                
                val groupId = getGroupId()
                val synchronizer = GroupMembershipSynchronizer.getInstance(requireContext())
                
                val success = synchronizer.forceResync(groupId)
                
                progressDialog.dismiss()
                
                if (success) {
                    Toast.makeText(context, "同步成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "同步失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

## 6. 日志和监控

### 6.1 添加性能监控

```kotlin
class GroupV2ModeMetrics {
    companion object {
        fun trackMemberJoin(groupId: String, duration: Long, success: Boolean) {
            // 发送到分析服务
            Analytics.track("group_v2_member_join", mapOf(
                "group_id_hash" to groupId.hashCode().toString(),
                "duration_ms" to duration,
                "success" to success
            ))
        }
        
        fun trackMemberLeave(groupId: String, duration: Long, success: Boolean) {
            Analytics.track("group_v2_member_leave", mapOf(
                "group_id_hash" to groupId.hashCode().toString(),
                "duration_ms" to duration,
                "success" to success
            ))
        }
        
        fun trackSync(syncedCount: Int, duration: Long) {
            Analytics.track("group_v2_sync", mapOf(
                "synced_count" to syncedCount,
                "duration_ms" to duration
            ))
        }
    }
}
```

## 7. 错误处理建议

### 7.1 网络错误处理

```kotlin
try {
    val success = groupTransportManager.handleMemberJoin(...)
    if (!success) {
        // 记录失败，稍后重试
        scheduleRetry(groupId, "member_join")
    }
} catch (e: IOException) {
    Log.w(TAG, "网络错误，稍后重试", e)
    scheduleRetry(groupId, "member_join")
} catch (e: Exception) {
    Log.e(TAG, "处理成员加入失败", e)
    // 不重试，记录错误
}
```

### 7.2 状态不一致恢复

```kotlin
// 定期检查状态一致性
class GroupV2ModeHealthCheckJob : BaseJob() {
    override fun onRun() {
        val synchronizer = GroupMembershipSynchronizer.getInstance(context)
        val activeGroups = GroupTransportManager.getInstance(context).getAllActiveV2Groups()
        
        for (groupState in activeGroups) {
            val inconsistent = runBlocking {
                synchronizer.detectInconsistency(groupState.groupId)
            }
            
            if (inconsistent) {
                Log.w(TAG, "检测到状态不一致: ${groupState.groupId}")
                // 尝试修复
                runBlocking {
                    synchronizer.forceResync(groupState.groupId)
                }
            }
        }
    }
}
```

## 总结

本文档提供了将 Phase 5 成员变动处理功能集成到 Signal 应用的完整指南。

**核心集成点**:
1. ✅ 群组更新流程 - 自动同步
2. ✅ UI 提示 - 新成员加入提示
3. ✅ 消息处理 - 处理新成员相关消息
4. ✅ 后台任务 - 定期同步
5. ✅ 手动同步 - 用户控制

**建议集成优先级**:
1. **高优先级**: 消息处理（必须，否则无法完成 token 交换）
2. **中优先级**: 群组更新流程集成（自动同步）
3. **中优先级**: UI 提示（用户体验）
4. **低优先级**: 后台任务（优化）
5. **低优先级**: 手动同步（故障恢复）

---

*文档版本：1.0*  
*创建日期：2025-10-05*
