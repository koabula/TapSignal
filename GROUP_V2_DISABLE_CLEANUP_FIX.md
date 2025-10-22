# 群组V2模式禁用后重启初始化问题修复

## 修复日期
2025-10-22

## 问题描述

### 症状
建立群聊v2 mode后一段时间，disable群聊v2 mode。之后成员A重启Signal程序，可能会在A的Signal显示和某群友B的私聊成为了v2 mode，但是B却没有显示。同时A会开启一个轮询。

### 根本原因

#### 1. Disable时清理不完整
群聊v2 mode采用特殊架构：
- Token层面：使用 `groupId` 作为key存储在 `sharedTokens[groupId]`
- 通道层面：为每个群友建立独立通道，使用 `memberAci` 作为recipientId

**问题**：`cleanupGroupResources` 只清理了成员的Token（memberAci），没有清理群组的sharedToken（groupId）

```kotlin
// 清理前：只清理成员Token
for (memberAci in otherMembers) {
    tokenPool.removeToken(memberAci, providerType)  // ✅
}
// 缺失：tokenPool.removeToken(groupId, providerType)  // ❌
```

#### 2. 初始化时数据不一致
`getActiveRecipientsFromTokenPool` 方法将所有Token的recipientId合并，包括：
- 私聊Token：recipientId = UUID格式（包含'-'）
- 群组Token：recipientId = groupId（Base64格式，不包含'-'）

**问题**：没有验证群组Token的有效性，残留的groupId被当作活跃联系人

#### 3. 恢复通道时缺少验证
初始化时恢复通道，没有检查群组通道的群组状态，导致已禁用群组的成员通道被当作私聊通道恢复

## 修复方案

### 1. 完善 cleanupGroupResources 清理逻辑

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`

**位置**: 行1773-1783

**修改内容**:
```kotlin
// 清理群组的sharedToken（使用groupId作为key）
val groupTokenRemoved = tokenPool.removeToken(groupId, groupState.providerType)
if (groupTokenRemoved) {
    Log.d(TAG, "群组SharedToken已清理: groupId=$groupId")
}

Log.i(TAG, "群组资源清理完成: groupId=$groupId, " +
    "轮询移除=$pollingRemoved/${otherMembers.size}, " +
    "通道关闭=$channelsClosed/${otherMembers.size}, " +
    "成员Token移除=$tokensRemoved/${otherMembers.size}, " +
    "群组Token移除=$groupTokenRemoved")
```

### 2. 过滤无效的群组Token

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapModuleInitializer.kt`

**位置**: 行453-494

**修改内容**:
```kotlin
private fun getActiveRecipientsFromTokenPool(tokenPool: TransportTokenPool): Set<String> {
    val activeRecipients = mutableSetOf<String>()
    val groupV2StatusTable = org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable(context)
    
    // 获取所有有效的接收Token
    val validReceivedTokens = tokenPool.getAllValidReceivedTokens()
    validReceivedTokens.forEach { (recipientId, token) ->
        activeRecipients.add(recipientId)
    }
    
    // 获取所有有效的共享Token，并验证群组Token
    val validSharedTokens = tokenPool.getAllValidSharedTokens()
    validSharedTokens.forEach { (recipientId, token) ->
        if (isGroupId(recipientId)) {
            // 验证群组状态
            val groupState = groupV2StatusTable.getGroupState(recipientId)
            if (groupState != null && groupState.status == org.thoughtcrime.securesms.tap.group.GroupV2Status.FULL_V2_ACTIVE) {
                activeRecipients.add(recipientId)
            } else {
                Log.w(TAG, "清理无效的群组SharedToken: groupId=${LogSanitizer.sanitize(recipientId)}")
                tokenPool.removeToken(recipientId, token.providerType)
            }
        } else {
            // 私聊Token
            activeRecipients.add(recipientId)
        }
    }
    
    return activeRecipients
}
```

### 3. 添加 isGroupId 工具方法

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapModuleInitializer.kt`

**位置**: 行653-659

**修改内容**:
```kotlin
/**
 * 判断recipientId是否为群组ID
 * 群组ID特征：Base64格式，不包含'-'（UUID包含'-'）
 */
private fun isGroupId(recipientId: String): Boolean {
    return !recipientId.contains("-") && recipientId.length > 20
}
```

### 4. 验证通道的群组上下文

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapModuleInitializer.kt`

**位置1**: 行413-434（startPollingService方法）
**位置2**: 行645-671（retryStartPollingServiceOnTokenLoad方法）

**修改内容**:
```kotlin
// 为每个活跃联系人添加轮询目标
val groupV2StatusTable = org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable(context)
allActiveRecipients.forEach { recipientId ->
    try {
        val recipientChannels = channelManager.getActiveChannels(recipientId)
        if (recipientChannels.isNotEmpty()) {
            val channel = recipientChannels.first()
            
            // 验证群组通道的有效性
            val groupId = channel.config["groupId"] as? String
            if (groupId != null) {
                // 这是群组通道，验证群组状态
                val groupState = groupV2StatusTable.getGroupState(groupId)
                if (groupState == null || groupState.status != org.thoughtcrime.securesms.tap.group.GroupV2Status.FULL_V2_ACTIVE) {
                    Log.w(TAG, "清理无效的群组通道: recipientId=${LogSanitizer.sanitize(recipientId)}, groupId=${LogSanitizer.sanitize(groupId)}")
                    channelManager.closeChannel(channel.channelId)
                    return@forEach
                }
            }
            
            val addResult = pollingService.addPollingTarget(recipientId, channel.metadata, channel)
            Log.d(TAG, "添加轮询目标: recipientId=$recipientId, 结果=$addResult")
        }
    } catch (e: Exception) {
        Log.e(TAG, "添加轮询目标失败: recipientId=$recipientId", e)
    }
}
```

## 修复效果

### 修复前
1. Disable群聊v2 mode时，群组的sharedToken残留
2. 重启后，残留的groupId被当作活跃联系人
3. 群友的通道被错误地当作私聊通道恢复
4. A显示和B建立了私聊v2 mode，但B没有显示

### 修复后
1. Disable时完整清理所有Token（包括群组sharedToken）
2. 初始化时验证群组Token有效性，清理残留数据
3. 恢复通道时检查群组上下文，清理无效通道
4. 数据一致性得到保证，不会出现误判

## 技术细节

### 群聊v2 mode的架构特点
- **Token索引**：使用groupId
- **通道索引**：使用memberAci
- **通道标识**：config["groupId"]区分群组通道和私聊通道

### groupId与UUID的区别
- **groupId**：Base64编码，不包含'-'，长度>20
- **UUID**：标准格式，包含'-'，如 `c716a84d-2fbd-4598-8bc3-b12cbba41973`

### 数据一致性保证
1. **Disable时**：清理成员Token + 群组Token
2. **初始化时**：验证Token有效性 + 验证通道群组上下文
3. **恢复时**：检查群组状态，拒绝无效通道

## 相关文档
- `PRIVATE_GROUP_CHANNEL_CONFUSION_FIX.md`: 私聊和群组通道混淆问题修复
- `Summary.md`: 项目架构总览

## 总结

本次修复通过三个层面的改进彻底解决了群组v2 mode禁用后的数据残留问题：
1. 完善清理逻辑，确保所有Token被删除
2. 增强初始化验证，过滤无效数据并自动清理
3. 添加通道验证，防止群组通道被误判为私聊通道

修复保持代码简约优雅，没有改变现有架构，具有良好的防御性和可维护性。

