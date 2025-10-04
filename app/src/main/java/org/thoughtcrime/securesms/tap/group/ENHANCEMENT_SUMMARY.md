# GroupId 转换和系统消息增强实现总结

## 实施日期
2025-10-04

## 概述

完善了 Phase 3 中两个待实现的功能：
1. **GroupId 转换**：实现了从多种格式的 groupId 字符串转换为 RecipientId
2. **系统消息样式**：使用 Signal 原生的系统消息类型，显示为灰色居中的提示

## 功能 1: GroupId 转换增强

### 文件
`app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeReceiver.kt`

### 方法
`getGroupRecipientId(context: Context, groupId: String): RecipientId?`

### 实现

支持三种 groupId 格式的自动识别和转换：

#### 1. RecipientId 序列化数字
```kotlin
// 格式: "123456789"
try {
    val recipientId = RecipientId.from(groupId.toLong())
    return recipientId
} catch (e: NumberFormatException) {
    // 继续尝试其他方法
}
```

#### 2. GroupId 编码字符串
```kotlin
// 格式: "__signal_group__v2__!<hex>" 或 "__textsecure_group__!<hex>"
try {
    val parsedGroupId = GroupId.parse(groupId)
    val recipientIdOptional = SignalDatabase.recipients.getByGroupId(parsedGroupId)
    if (recipientIdOptional.isPresent) {
        return recipientIdOptional.get()
    }
} catch (e: BadGroupIdException) {
    // 继续尝试其他方法
}
```

#### 3. Base64 编码的群组 ID 字节数组
```kotlin
// 格式: Base64 编码的字节数组
try {
    val groupIdBytes = Base64.decode(groupId, Base64.DEFAULT)
    val pushGroupId = GroupId.push(groupIdBytes)
    val recipientIdOptional = SignalDatabase.recipients.getByGroupId(pushGroupId)
    if (recipientIdOptional.isPresent) {
        return recipientIdOptional.get()
    }
} catch (e: Exception) {
    // 所有方法都失败
}
```

### 使用的 API

1. **GroupId.parse(String)**
   - 位置: `org.thoughtcrime.securesms.groups.GroupId`
   - 功能: 从编码字符串解析 GroupId 对象
   - 支持格式:
     - V2 群组: `__signal_group__v2__!<hex>`
     - V1 群组: `__textsecure_group__!<hex>`
     - MMS 群组: `__signal_mms_group__!<hex>`

2. **GroupId.push(byte[])**
   - 功能: 从字节数组创建 Push GroupId (V1 或 V2)
   - 根据字节长度自动判断版本

3. **SignalDatabase.recipients.getByGroupId(GroupId)**
   - 位置: `org.thoughtcrime.securesms.database.RecipientTable`
   - 功能: 通过 GroupId 查找对应的 RecipientId
   - 返回: `Optional<RecipientId>`

### 优势

- **自动识别**: 无需事先知道 groupId 的格式
- **向后兼容**: 支持多种历史格式
- **容错性强**: 一种方法失败自动尝试下一种
- **详细日志**: 每种尝试都有日志记录，便于调试

### 示例

```kotlin
// 例子 1: 数字 RecipientId
val recipientId = getGroupRecipientId(context, "123456789")

// 例子 2: GroupId 编码
val recipientId = getGroupRecipientId(context, "__signal_group__v2__!a1b2c3d4...")

// 例子 3: Base64 编码
val recipientId = getGroupRecipientId(context, "YWJjZGVmZ2hpams...")
```

## 功能 2: 系统消息样式增强

### 文件
`app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeHelper.kt`

### 方法

#### 1. insertSystemMessage() - 标准系统消息

```kotlin
suspend fun insertSystemMessage(
    recipientId: RecipientId,
    messageBody: String,
    isEnabled: Boolean = true
)
```

**特点**:
- 使用 Signal 原生的系统消息类型
- 显示为**灰色居中**的提示
- 消息类型由 `isEnabled` 参数决定
- 不可自定义文本内容

**实现**:
```kotlin
val insertResult = if (isEnabled) {
    SignalDatabase.messages.insertTapV2ModeEnabledMessage(recipientId)
} else {
    SignalDatabase.messages.insertTapV2ModeDisabledMessage(recipientId)
}
```

**使用场景**:
- 群组 V2 mode 激活: `insertSystemMessage(recipientId, "", isEnabled = true)`
- 群组 V2 mode 禁用: `insertSystemMessage(recipientId, "", isEnabled = false)`

#### 2. insertCustomSystemMessage() - 自定义系统消息

```kotlin
suspend fun insertCustomSystemMessage(
    recipientId: RecipientId,
    messageBody: String
)
```

**特点**:
- 可以自定义消息文本
- 显示为普通消息样式（可能不是居中灰色）
- 适用于需要自定义提示文本的场景

**实现**:
```kotlin
val systemMessage = OutgoingMessage(
    threadRecipient = recipient,
    sentTimeMillis = System.currentTimeMillis(),
    body = messageBody,
    isSecure = true
)
SignalDatabase.messages.insertMessageOutbox(...)
```

**使用场景**:
- 提议发起提示: `insertCustomSystemMessage(recipientId, "v2 mode 提议已发起")`
- 成员同意提示: `insertCustomSystemMessage(recipientId, "你已同意使用 v2 mode")`
- 其他自定义提示

### 使用的 API

#### MessageTable.insertTapV2ModeEnabledMessage()
- 位置: `org.thoughtcrime.securesms.database.MessageTable`
- 功能: 插入"v2 mode 已启用"系统消息
- 消息类型: `MessageTypes.TAP_V2_MODE_ENABLED_TYPE`
- 显示效果: 灰色居中提示

#### MessageTable.insertTapV2ModeDisabledMessage()
- 位置: `org.thoughtcrime.securesms.database.MessageTable`
- 功能: 插入"v2 mode 已禁用"系统消息
- 消息类型: `MessageTypes.TAP_V2_MODE_DISABLED_TYPE`
- 显示效果: 灰色居中提示

### 代码示例

#### 使用原生系统消息类型（推荐）

```kotlin
// 激活 V2 mode 时
val helper = GroupTokenExchangeHelper.getInstance(context)
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "群组已启用 v2 mode",  // 该参数当前未使用
    isEnabled = true
)
// 显示: [灰色居中] "Tap v2 mode enabled"
```

```kotlin
// 禁用 V2 mode 时
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "",
    isEnabled = false
)
// 显示: [灰色居中] "Tap v2 mode disabled"
```

#### 使用自定义文本消息

```kotlin
// 提议发起时
helper.insertCustomSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "v2 mode 提议已发起"
)
// 显示: [普通消息样式] "v2 mode 提议已发起"
```

## 更新的调用位置

### GroupTokenExchangeReceiver.kt

**位置 1** (行 162-165):
```kotlin
// 修改前
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "你已同意使用 v2 mode"
)

// 修改后
helper.insertCustomSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "你已同意使用 v2 mode"
)
```

**位置 2** (行 266-270):
```kotlin
// 修改前
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "群组已启用 v2 mode"
)

// 修改后
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "群组已启用 v2 mode",
    isEnabled = true
)
```

### GroupTransportManager.kt

**位置** (行 287-290):
```kotlin
// 修改前
helper.insertSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "v2 mode 提议已发起"
)

// 修改后
helper.insertCustomSystemMessage(
    recipientId = groupRecipientId,
    messageBody = "v2 mode 提议已发起"
)
```

## 设计决策

### 为什么需要两个方法？

1. **insertSystemMessage()** - 使用原生消息类型
   - ✅ 统一的显示风格（灰色居中）
   - ✅ 与 Signal 其他系统消息一致
   - ✅ 可能有特殊的 UI 处理（如不占用气泡）
   - ❌ 不能自定义文本

2. **insertCustomSystemMessage()** - 自定义文本
   - ✅ 灵活的文本内容
   - ✅ 支持各种提示场景
   - ❌ 可能显示为普通消息样式
   - ❌ 需要国际化支持

### 使用建议

- **标准操作**（启用/禁用）→ 使用 `insertSystemMessage()`
- **自定义提示**（提议、同意等）→ 使用 `insertCustomSystemMessage()`

## 测试建议

### GroupId 转换测试

```kotlin
@Test
fun testGetGroupRecipientId_withNumericId() {
    val recipientId = getGroupRecipientId(context, "123456")
    assertNotNull(recipientId)
}

@Test
fun testGetGroupRecipientId_withEncodedGroupId() {
    val groupId = "__signal_group__v2__!<hex>"
    val recipientId = getGroupRecipientId(context, groupId)
    assertNotNull(recipientId)
}

@Test
fun testGetGroupRecipientId_withBase64() {
    val base64Id = "YWJjZGVmZ2hpams..."
    val recipientId = getGroupRecipientId(context, base64Id)
    // 可能返回 null（取决于数据库中是否有该群组）
}
```

### 系统消息测试

```kotlin
@Test
fun testInsertSystemMessage_enabled() {
    helper.insertSystemMessage(recipientId, "", isEnabled = true)
    // 验证消息已插入
    val messages = getMessagesInThread(threadId)
    assertTrue(messages.any { it.isV2ModeEnabled })
}

@Test
fun testInsertCustomSystemMessage() {
    helper.insertCustomSystemMessage(recipientId, "Test message")
    // 验证自定义消息已插入
    val messages = getMessagesInThread(threadId)
    assertTrue(messages.any { it.body == "Test message" })
}
```

## 后续优化建议

### GroupId 转换

1. **缓存机制**: 对常用的 groupId → RecipientId 映射进行缓存
2. **性能优化**: Base64 解码可能较慢，考虑提前验证格式
3. **错误提示**: 在 UI 层提供更友好的错误提示

### 系统消息

1. **国际化**: 为 insertCustomSystemMessage 添加字符串资源支持
2. **统一样式**: 可能需要自定义消息类型以获得统一的显示效果
3. **富文本**: 支持在系统消息中显示链接或格式化文本

## 兼容性

- ✅ 向后兼容：支持旧版本的 groupId 格式
- ✅ 数据库兼容：使用现有的数据库 API
- ✅ UI 兼容：使用 Signal 原生的消息类型

## 性能影响

- GroupId 转换：最多 3 次数据库查询（实际通常只需 1 次）
- 系统消息插入：1 次数据库写入 + 1 次线程更新
- 总体影响：**可忽略**

---

**实施人**: AI Assistant  
**审阅**: 待审阅  
**版本**: 1.0  
**最后更新**: 2025-10-04

