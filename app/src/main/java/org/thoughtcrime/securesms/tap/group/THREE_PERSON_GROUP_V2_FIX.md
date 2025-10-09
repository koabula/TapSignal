# 3人群组 V2 Mode 修复报告

## 修复日期
2025-10-09

## 问题概述

在实现3+人群组TAP v2 mode时，发现以下问题：
1. **解密失败**：接收端报错 `ciphertext version was too old <2>`
2. **轮询路径错误**：设备A轮询私聊路径而非群组路径
3. **Token数量异常**：生成了5个token而非预期的3个

## 根本原因分析

### 问题1：Envelope类型错误 ❌

**根本原因**：对 `cipher.encryptForGroup()` 返回值的理解错误

**密文层次结构**：
```
原始消息
  ↓ GroupCipher.encrypt()
SenderKey 密文 (type=7, 第一个字节=7)
  ↓ 包装成 UnidentifiedSenderMessageContent
  ↓ multiRecipientEncrypt()
Sealed Sender 密文 (第一个字节=版本号2)
```

**错误实现**：
```java
// SignalServiceCipher.encryptForGroup() 实际返回 Sealed Sender 密文
byte[] ciphertext = cipher.encryptForGroup(...);

// 错误：设置 type=SENDERKEY_MESSAGE
envelopeBuilder.type(Envelope.Type.SENDERKEY_MESSAGE)  // ❌
```

**解密失败机制**：
1. 发送端设置 `Envelope.type = SENDERKEY_MESSAGE (7)`
2. 接收端根据type直接用 GroupCipher 解密 envelope.content
3. 但 content 是 Sealed Sender 格式，第一个字节是版本号 `2`
4. GroupCipher 期望第一个字节是 `7` (SENDERKEY_TYPE)
5. 报错：`ciphertext version was too old <2>`

### 问题2：轮询任务冲突 ❌

**根本原因**：轮询任务使用 `recipientId` 作为唯一key，导致多群组冲突

**问题场景**：
- 设备A有两个群组，都包含成员1973和72ea
  - 群组1: `jK756PEaVhwCEMDGO2C7cR1R6LN...` (已激活)
  - 群组2: `0cbeIJCp9OuN72EQz+fc...` (新激活)

**冲突机制**：
```kotlin
// pollingTasks 使用 recipientId 作为key
pollingTasks[recipientId] = task  // ← 群组2会覆盖群组1的任务！

// 当群组2激活时
val existingTask = pollingTasks[recipientId]  // 找到群组1的任务
if (existingTask != null && currentStatus == RUNNING) {
    return true  // ← 直接返回，没有更新metadata！
}
```

**结果**：设备A继续用群组1的旧路径 `/v2-channels/...` 轮询，而不是群组2的路径 `/group/{groupId}/`

### 问题3：Token数量

**实际生成的Token**：
- 3个群组Token（每人1个CAM子用户）✅
- 2个额外的Channel Token（状态同步时重建通道产生）

**结论**：不是问题，通道管理机制会产生临时token

## 修复方案

### 修复1：Envelope类型 (高优先级) ✅

**修改文件**：`libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**修改内容**：
```java
// Line 2739: 修改 Envelope 类型
.type(Envelope.Type.UNIDENTIFIED_SENDER)  // ✅ 正确：Sealed Sender
// 之前是：.type(Envelope.Type.SENDERKEY_MESSAGE)  // ❌ 错误

// Line 2735-2736: 更新注释
// 构造 Sealed Sender 类型的 Envelope
// encryptForGroup() 返回的是 Sealed Sender 包装的密文，而不是纯 SenderKey 密文
```

**原理**：
- `encryptForGroup()` 返回 Sealed Sender 密文
- Envelope.type 应该是 `UNIDENTIFIED_SENDER (6)`
- 接收端检测到该类型，自动使用 Sealed Sender 解密流程
- 解开后得到 SenderKey 密文，继续解密

### 修复2：轮询任务复合key (中优先级) ✅

**修改文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/polling/TapPollingService.kt`

**核心修改**：

1. **添加复合key生成方法** (Line 252-258)：
```kotlin
private fun getPollingTaskKey(recipientId: String, groupId: String?): String {
    return if (groupId.isNullOrEmpty()) {
        recipientId  // 私聊：只用 recipientId
    } else {
        "$groupId:$recipientId"  // 群聊：groupId:recipientId
    }
}
```

2. **添加groupId提取方法** (Line 266-286)：
```kotlin
/**
 * 从 metadata 的接收路径中提取 groupId
 * 
 * 群组消息路径格式：/group/{groupId}/messages/
 * 私聊消息路径格式：/v2-channels/{hashedId}/outbox/messages/
 */
private fun extractGroupId(metadata: TransportMetadata): String? {
    return try {
        val receivePath = metadata.getReceiveMetadata().path
        
        // 检查是否为群组路径
        if (receivePath.startsWith("/group/")) {
            // 提取 groupId：/group/{groupId}/... → groupId
            val parts = receivePath.split("/")
            if (parts.size >= 3 && parts[1] == "group") {
                parts[2].takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } else {
            null  // 私聊路径，返回 null
        }
    } catch (e: Exception) {
        Log.e(TAG, "从metadata中提取groupId失败", e)
        null
    }
}
```

**重要实现细节**：
- `TransportMetadata` 是不可变接口，不能直接添加 `groupId` 字段
- 通过解析 `peerReceivePath` 路径来识别群组消息和提取 groupId
- 群组路径格式：`/group/{groupId}/messages/` 或 `/group/{groupId}/attachments/`
- 私聊路径格式：`/v2-channels/{hashedId}/outbox/messages/`

3. **修改 addPollingTarget** (Line 374-447)：
```kotlin
// 从 metadata 中提取 groupId
val groupId = extractGroupId(metadata)
val taskKey = getPollingTaskKey(recipientId, groupId)

// 使用复合key查找任务
val existingTask = pollingTasks[taskKey]

// 添加时使用复合key
pollingTasks[taskKey] = taskInfo
```

4. **修改 addGroupPollingTargets** (Line 319-320)：
```kotlin
// metadata 的 peerReceivePath 应该已经包含 groupId 信息 (/group/{groupId}/)
val added = addPollingTarget(memberAci, metadata, null)
```
注：不需要手动设置 groupId，因为 `buildGroupPollingMetadata()` 已经将群组路径设置为 `/group/{groupId}/`

5. **修改 removePollingTarget** (Line 469-474)：
```kotlin
// 查找所有匹配的任务（支持私聊和群聊）
val tasksToRemove = pollingTasks.filter { (key, taskInfo) ->
    val isMatch = (key == recipientId || key.endsWith(":$recipientId")) && 
                  taskInfo.metadata.providerType == providerType
    isMatch
}
```

6. **修改 removeGroupPollingTargets** (Line 349-357)：
```kotlin
// 生成群组复合key
val taskKey = getPollingTaskKey(memberAci, groupId)
val taskInfo = pollingTasks[taskKey]

if (taskInfo != null && taskInfo.metadata.providerType == providerType) {
    taskInfo.cleanup()
    pollingTasks.remove(taskKey)
    successCount++
}
```

## 修复效果验证

### 发送端日志验证
修复后应看到：
```
constructEnvelopeForSenderKey: Created Sealed Sender Envelope (type=UNIDENTIFIED_SENDER), size=...
Using SenderKey encryption (Sealed Sender wrapped), isSessionCipher=false
```

### 接收端日志验证
修复后应看到：
```
使用 Signal 解密 Envelope: type=UNIDENTIFIED_SENDER
Signal解密成功
```

不再出现：`ciphertext version was too old <2>`

### 轮询日志验证
修复后应看到：
```
添加轮询目标: taskKey=0cbeIJCp9OuN72EQz+fc+ZZ6TZrYQ1iktjX5dhL3xrY=:c716..., groupId=0cbeIJCp...
轮询路径: /group/0cbeIJCp9OuN72EQz+fc+ZZ6TZrYQ1iktjX5dhL3xrY=/messages/
```

而不是：
```
轮询路径: /v2-channels/053abb43f297ee1d/outbox/messages/
```

## 技术要点

### Sealed Sender vs SenderKey

**Sealed Sender**：
- 隐藏发送者身份的加密层
- 包含发送者证书
- 第一个字节是版本号（通常是2）

**SenderKey**：
- 群组消息加密机制
- 直接的密文格式
- 第一个字节是类型标识（7）

**关系**：
```
Sealed Sender (外层)
  ↓ 解开后得到
SenderKey (内层)
  ↓ 解密后得到
原始消息
```

### 复合Key设计

**设计原则**：
- 私聊和群聊使用不同的key格式
- 同一recipientId在不同群组中独立轮询
- 向后兼容已有的私聊轮询

**Key格式**：
- 私聊：`recipientId`
- 群聊：`groupId:recipientId`

**优势**：
- 避免多群组任务冲突
- 支持同一成员在多个群组
- 易于识别任务类型

## 相关文档

- `ENVELOPE_TRANSPORT_FIX.md`: 2人群组Envelope修复（类似问题）
- `GROUP_V2_ARCHITECTURE_REFACTOR.md`: 群组V2架构重构
- `PLAN_GROUP.md`: 群组V2实现计划
- `TODO_GROUP.md`: 群组V2任务清单

## 总结

**核心修复**：
1. ✅ Envelope类型从 `SENDERKEY_MESSAGE` 改为 `UNIDENTIFIED_SENDER`
2. ✅ 轮询任务使用 `groupId:recipientId` 复合key，避免冲突
3. ✅ 通过解析路径提取 groupId，避免修改不可变的 `TransportMetadata`

**关键洞察**：
- TAP只是传输层，不应干涉加密层
- 应该传输与Signal Server相同格式的数据
- `encryptForGroup()` 返回的是Sealed Sender密文，不是纯SenderKey密文
- `TransportMetadata` 是不可变接口，通过路径识别群组消息

**影响范围**：
- 仅影响3+人群组的TAP v2 mode
- 不影响私聊和2人群组
- 不影响Signal Server传输路径

## 编译问题修复

在实现过程中遇到的编译错误：
1. ❌ `metadata.configJson` 不存在 → ✅ 通过 `metadata.getReceiveMetadata().path` 提取 groupId
2. ❌ `metadata` 是 val 不能重新赋值 → ✅ 移除赋值语句，依赖复合key匹配

**最终实现**：从接收路径中解析 groupId
- 群组路径：`/group/{groupId}/messages/` → 提取 `groupId`
- 私聊路径：`/v2-channels/{hashedId}/outbox/messages/` → 返回 `null`

