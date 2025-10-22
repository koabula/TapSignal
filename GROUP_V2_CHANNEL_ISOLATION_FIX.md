# 群组和私聊V2 Mode通道隔离修复

## 修复日期
2025-10-22

## 问题描述

### 症状
1. 重启后群组v2 mode禁用，但仍然开启了私聊的v2 mode
2. 群聊进入v2 mode后，和群友的私聊也显示v2 mode
3. 虽然消息会回退到Signal Server，但UI显示混淆

### 根本原因

#### 1. 通道清理不彻底
- `cleanupGroupResources` 使用 `getActiveChannel` 只获取一个通道
- 实际上群组成员可能有多个通道
- 通道关闭后异步删除数据库，可能在进程终止前未完成

#### 2. 通道索引混淆
- 群组通道使用 `recipientId = memberAci`
- 私聊通道也使用 `recipientId = memberAci`
- 两者共享同一索引空间，仅通过 `config["groupId"]` 区分

#### 3. 数据库恢复时缺少验证
- 重启时从数据库恢复通道，没有验证群组状态
- 群组已禁用的通道被当作私聊通道恢复
- 导致错误的轮询和UI显示

#### 4. 初始化时群组通道误判
- 群组通道被添加到个人轮询中
- 应该由群组管理器统一管理，而不是个人轮询

## 修复方案

### P0修复（必须）

#### 1. 增强通道清理逻辑

**文件**: `GroupTransportManager.kt` 行1745-1766

**修改前**:
```kotlin
for (memberAci in otherMembers) {
    val channel = channelManager.getActiveChannel(memberAci, groupState.providerType)
    if (channel != null) {
        channelManager.closeChannel(channel.channelId)
        channelsClosed++
    }
    tokenPool.removeToken(memberAci, groupState.providerType)
}
```

**修改后**:
```kotlin
// 批量清理群组通道
channelsClosed = channelManager.closeGroupChannels(groupId)

for (memberAci in otherMembers) {
    // 移除轮询和Token
    pollingService.removePollingTarget(memberAci, groupState.providerType)
    tokenPool.removeToken(memberAci, groupState.providerType)
}
```

#### 2. 添加批量清理方法

**文件**: `TransportChannelManager.kt` 行677-713

**新增方法**:
```kotlin
suspend fun closeGroupChannels(groupId: String): Int {
    return withContext(Dispatchers.IO) {
        channelLock.write {
            // 找出所有属于该群组的通道
            val groupChannels = channels.values.filter { channel ->
                channel.config["groupId"] == groupId
            }
            
            var closedCount = 0
            groupChannels.forEach { channel ->
                channels.remove(channel.channelId)
                deleteChannelFromDatabase(channel.channelId)
                closedCount++
            }
            
            closedCount
        }
    }
}
```

#### 3. 初始化时防止群组通道误判

**文件**: `TapModuleInitializer.kt` 行423-445, 670-692

**修改逻辑**:
```kotlin
val groupId = channel.config["groupId"] as? String
if (groupId != null) {
    // 这是群组通道
    if (groupV2StatusTable != null) {
        val groupState = groupV2StatusTable.getGroupState(groupId)
        if (groupState == null) {
            Log.w(TAG, "清理孤立的群组通道: groupId不存在")
            channelManager.closeChannel(channel.channelId)
        } else if (groupState.status != FULL_V2_ACTIVE) {
            Log.w(TAG, "清理无效的群组通道")
            channelManager.closeChannel(channel.channelId)
        }
    }
    // 群组通道不应该添加到个人轮询，由群组管理器统一管理
    return@forEach
}

// 只为私聊通道添加轮询
addPollingTarget(...)
```

### P1修复（重要）

#### 4. 改进closeChannel数据库删除

**文件**: `TransportChannelManager.kt` 行654-675

**修改前**:
```kotlin
suspend fun closeChannel(channelId: String): Boolean {
    val closedChannel = channel.updateStatus(CLOSED)
    channels[channelId] = closedChannel
    
    // 异步清理（可能未完成）
    managerScope.launch {
        cleanupChannel(channelId)
        deleteChannelFromDatabase(channelId)
    }
}
```

**修改后**:
```kotlin
suspend fun closeChannel(channelId: String): Boolean {
    // 从内存中移除
    channels.remove(channelId)
    
    // 同步删除数据库记录
    deleteChannelFromDatabase(channelId)
    
    Log.i(TAG, "通道已关闭并删除: $channelId")
}
```

#### 5. 加载通道时验证群组关联

**文件**: `TransportChannelManager.kt` 行2028-2094

**修改前**:
```kotlin
private suspend fun restoreChannelsFromDatabase() {
    val activeChannels = independentChannelTable.getActiveChannels()
    
    for (channel in activeChannels) {
        channels[channel.channelId] = channel
        // 重建索引...
    }
}
```

**修改后**:
```kotlin
private suspend fun restoreChannelsFromDatabase() {
    val activeChannels = independentChannelTable.getActiveChannels()
    val groupV2StatusTable = SignalDatabase.instance?.let {
        GroupV2StatusTable(context, it)
    }
    
    val validChannels = mutableListOf<TransportChannel>()
    val invalidChannels = mutableListOf<String>()
    
    for (channel in activeChannels) {
        val groupId = channel.config["groupId"] as? String
        if (groupId != null && groupV2StatusTable != null) {
            // 验证群组通道
            val groupState = groupV2StatusTable.getGroupState(groupId)
            if (groupState == null || groupState.status != FULL_V2_ACTIVE) {
                invalidChannels.add(channel.channelId)
                continue
            }
        }
        validChannels.add(channel)
    }
    
    // 只加载有效通道
    for (channel in validChannels) {
        channels[channel.channelId] = channel
        // 重建索引...
    }
    
    // 删除无效通道
    invalidChannels.forEach { channelId ->
        deleteChannelFromDatabase(channelId)
    }
}
```

## 修复效果

### 修复前
1. Disable群聊v2 mode → 通道可能残留
2. 重启后 → 群组通道被当作私聊通道恢复
3. UI显示和群友有私聊v2 mode（错误）
4. 尝试轮询 → 403 Access Denied

### 修复后
1. Disable群聊v2 mode → 所有群组通道被批量删除（内存+数据库）
2. 重启后 → 只加载有效通道，无效群组通道被清理
3. UI正确显示私聊和群聊的独立状态
4. 群组通道不会被误判为私聊，不会添加到个人轮询

## 技术要点

### 1. 通道类型识别
```kotlin
val groupId = channel.config["groupId"] as? String
if (groupId != null) {
    // 群组通道
} else {
    // 私聊通道
}
```

### 2. 批量清理优势
- 一次性清理所有群组相关通道
- 避免遗漏（一个成员可能有多个通道）
- 性能更好（批量操作）

### 3. 同步删除数据库
- 避免异步删除未完成的问题
- 确保数据一致性
- 简化代码逻辑

### 4. 多层防御
- **清理时**：批量删除所有群组通道
- **加载时**：验证群组状态，过滤无效通道
- **初始化时**：识别群组通道，防止误判
- **轮询时**：只为私聊通道添加轮询

## 代码变更统计

### 修改文件
1. `GroupTransportManager.kt` - 1处修改（简化清理逻辑）
2. `TransportChannelManager.kt` - 2处修改（新增方法 + 改进逻辑）
3. `TapModuleInitializer.kt` - 2处修改（增强验证）

### 新增代码
- `closeGroupChannels()` 方法：批量清理群组通道
- 通道加载验证逻辑：过滤无效群组通道

### 删除/简化代码
- 移除了 `closeChannel` 中的异步清理逻辑
- 简化了 `cleanupGroupResources` 中的循环逻辑

## 验证方法

### 测试场景1：群组禁用后重启
1. 建立3人群组v2 mode
2. Disable群组v2 mode
3. 重启Signal
4. 检查：不应该显示和群友有私聊v2 mode
5. 检查：不应该启动私聊轮询

### 测试场景2：群聊和私聊独立
1. 建立3人群组v2 mode
2. 打开和群友A的私聊对话
3. 检查：不应该显示私聊v2 mode指示器
4. 在私聊中建立v2 mode
5. 检查：私聊和群聊都应该显示v2 mode，但相互独立

### 预期日志
```
# Disable时
批量清理群组通道完成: groupId=xxx, 清理数量=2
群组SharedToken已清理: groupId=xxx

# 重启加载时
跳过加载无效的群组通道: status=NATIVE, channelId=xxx
通道状态恢复完成，有效通道=0, 清理无效通道=2

# 初始化轮询时
跳过群组通道的个人轮询: recipientId=xxx, groupId=xxx
添加私聊轮询目标: recipientId=yyy, 结果=true
```

## 相关文档
- `GROUP_V2_DISABLE_CLEANUP_FIX.md`: 群组Token清理修复
- `PRIVATE_GROUP_CHANNEL_CONFUSION_FIX.md`: 私聊和群组通道混淆问题

## 总结

本次修复通过五个关键改进，彻底解决了群组和私聊v2 mode的通道隔离问题：

1. **批量清理**：使用 `closeGroupChannels` 一次性清理所有群组通道
2. **同步删除**：改进 `closeChannel` 使用同步数据库删除
3. **加载验证**：在 `restoreChannelsFromDatabase` 中验证群组状态
4. **初始化防御**：在初始化轮询时识别和跳过群组通道
5. **日志完善**：添加详细日志便于问题诊断

修复后，群聊和私聊的v2 mode完全隔离，互不干扰，确保数据一致性和UI正确性。代码保持简约优雅，遵循单一职责原则，增强了系统的健壮性。

