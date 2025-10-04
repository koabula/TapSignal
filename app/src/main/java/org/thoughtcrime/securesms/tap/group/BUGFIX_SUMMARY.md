# 编译错误修复总结

## 修复日期
2025-10-04

## 原始错误

### 1. GroupTokenExchangeHelper.kt

**错误 1**: `Unresolved reference 'OutgoingMessage'` (行 12, 306)
- **原因**: 导入了错误的包 `org.thoughtcrime.securesms.sms.OutgoingMessage`
- **修复**: 改为 `org.thoughtcrime.securesms.mms.OutgoingMessage`

**错误 2**: `Argument type mismatch` in IndividualSendJob (行 334-335)
- **原因**: 使用了错误的 IndividualSendJob 构造函数
- **修复**: 
  - 改用 `IndividualSendJob.create()` 静态方法
  - 参数从 `(messageId, recipientId)` 改为 `(messageId, recipient, hasMedia, isScheduledSend)`

**错误 3**: OutgoingMessage 构造函数参数错误
- **原因**: 使用了旧版本的 OutgoingMessage 构造函数参数
- **修复**: 
  - `recipient` → `threadRecipient`
  - `timestamp` → `sentTimeMillis`
  - 移除了 `subscriptionId`, `distributionType`, `storyType`, `parentStoryId`, `isStoryReaction`, `giftBadge`, `bodyRanges`, `scheduledDate` 等不必要的参数

**错误 4**: `Unresolved reference 'insertInfoMessage'` (行 364)
- **原因**: MessageTable 中没有 `insertInfoMessage` 方法
- **修复**: 使用 `insertMessageOutbox()` 创建系统消息

### 2. GroupTokenExchangeReceiver.kt

**错误 5**: `Unresolved reference 'GroupsV2'` (行 317)
- **原因**: 尝试引用不存在或不正确的 protobuf 类
- **修复**: 简化了 `getGroupRecipientId()` 的实现，先尝试解析为 RecipientId 数字，失败则返回 null（待后续完善）

### 3. TapMessageProcessor.kt

**错误 6**: `Unresolved reference 'aciToRecipientId'` (行 1322)
- **原因**: 方法未定义
- **修复**: 添加了 `aciToRecipientId()` 方法实现
  ```kotlin
  private fun aciToRecipientId(aci: String): RecipientId? {
      val serviceId = ServiceId.ACI.parseOrThrow(aci)
      val recipient = Recipient.externalPush(serviceId)
      return recipient.id
  }
  ```

**错误 7**: `Argument type mismatch` when calling Recipient.resolved (行 1324)
- **原因**: proposerRecipientId 可能为 null，但 Recipient.resolved 期望非空
- **修复**: 添加了 try-catch 错误处理

## 修复后的关键变更

### GroupTokenExchangeHelper.kt

#### 导入变更
```kotlin
- import org.thoughtcrime.securesms.sms.OutgoingMessage
+ import org.thoughtcrime.securesms.mms.OutgoingMessage
- import java.util.Optional  // 不再需要
```

#### sendDataMessageToRecipient() 方法
```kotlin
// 修复前
val outgoingMessage = OutgoingMessage(
    recipient = recipient,
    body = messageBody,
    timestamp = System.currentTimeMillis(),
    ...
)

val sendJob = IndividualSendJob(messageId, recipient.id)

// 修复后
val outgoingMessage = OutgoingMessage(
    threadRecipient = recipient,
    sentTimeMillis = System.currentTimeMillis(),
    body = messageBody,
    expiresIn = recipient.expiresInSeconds.toLong() * 1000,
    isSecure = true
)

val sendJob = IndividualSendJob.create(
    messageId = messageId,
    recipient = recipient,
    hasMedia = false,
    isScheduledSend = false
)
```

#### insertSystemMessage() 方法
```kotlin
// 修复前
val insertedMessageId = SignalDatabase.messages.insertInfoMessage(
    threadId = threadId,
    body = messageBody,
    timestamp = System.currentTimeMillis()
)

// 修复后
val systemMessage = OutgoingMessage(
    threadRecipient = recipient,
    sentTimeMillis = System.currentTimeMillis(),
    body = messageBody,
    isSecure = true
)

val insertedMessageId = SignalDatabase.messages.insertMessageOutbox(
    message = systemMessage,
    threadId = threadId,
    forceSms = false,
    insertListener = null
)
```

### GroupTokenExchangeReceiver.kt

#### getGroupRecipientId() 方法
```kotlin
// 修复前
val groupIdBytes = android.util.Base64.decode(groupId, android.util.Base64.DEFAULT)
val signalGroupId = org.signal.storageservice.protos.groups.GroupsV2.GROUP_CONTEXT_FIELD_NUMBER

// 修复后
try {
    return RecipientId.from(groupId.toLong())
} catch (e: NumberFormatException) {
    // 不是数字，继续尝试其他方法
}
// 暂时返回 null，需要后续完善
```

### TapMessageProcessor.kt

#### 新增方法
```kotlin
/**
 * 从 ACI 字符串获取 RecipientId
 */
private fun aciToRecipientId(aci: String): RecipientId? {
    return try {
        val serviceId = ServiceId.ACI.parseOrThrow(aci)
        val recipient = Recipient.externalPush(serviceId)
        recipient.id
    } catch (e: Exception) {
        Log.e(TAG, "无法从ACI获取RecipientId: $aci", e)
        null
    }
}
```

#### showGroupTokenExchangeNotification() 方法
```kotlin
// 修复前
val proposerName = if (proposerRecipientId != null) {
    Recipient.resolved(proposerRecipientId).getDisplayName(context)
} else {
    "未知成员"
}

// 修复后
val proposerName = if (proposerRecipientId != null) {
    try {
        Recipient.resolved(proposerRecipientId).getDisplayName(context)
    } catch (e: Exception) {
        "未知成员"
    }
} else {
    "未知成员"
}
```

## 待完善的部分

### 1. GroupId 转换 (优先级：高)
`getGroupRecipientId()` 方法目前只能处理简单的数字 RecipientId，需要实现：
- 从 Base64 编码的 GroupId 字节数组转换
- 使用 `SignalDatabase.groups.getByGroupId()` 查询
- 支持 GroupId.v2() 格式

### 2. 系统消息样式 (优先级：中)
当前使用 `insertMessageOutbox()` 创建系统消息，可能需要：
- 使用特殊的消息类型标记
- 不显示发送状态
- 使用灰色或居中的显示样式

### 3. 错误处理增强 (优先级：中)
- 添加消息发送失败的重试机制
- 添加网络异常的用户提示
- 记录详细的错误日志用于调试

## 编译验证

修复后的代码已通过 linter 检查，无编译错误。

建议下一步：
1. 运行单元测试验证修复的正确性
2. 在真实设备上测试 token 交换流程
3. 完善 GroupId 转换逻辑

---

**修复人**: AI Assistant  
**审阅**: 待审阅  
**版本**: 1.0  
**最后更新**: 2025-10-04

