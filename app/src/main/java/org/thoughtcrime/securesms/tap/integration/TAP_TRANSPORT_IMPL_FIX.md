# TapMessageTransportImpl 编译错误修复

## 问题

初始实现中使用了错误的 API 和参数：

1. `TransportMessage` 构造函数参数错误
2. `GroupTransportManager.sendGroupMessage()` 参数错误
3. 缺少必要的 import
4. 未处理 suspend 函数调用

## 修复

### 1. 修正 Import

```kotlin
import kotlinx.coroutines.runBlocking
import org.thoughtcrime.securesms.tap.TransportContentMetadata
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.group.GroupSendResult
```

### 2. 修正群组消息发送

**之前（错误）**：
```kotlin
val transportMessage = TransportMessage(
    messageId = ...,
    senderId = "",
    recipientId = groupIdString,
    timestamp = timestamp,
    content = ciphertext,           // ❌ 错误参数
    messageType = "group_message",  // ❌ 错误类型
    urgent = urgent                 // ❌ 不存在的参数
)

val result = groupTransportManager.sendGroupMessage(
    groupId = groupIdString,
    transportMessage = transportMessage  // ❌ 错误参数
)
```

**现在（正确）**：
```kotlin
// 直接调用 GroupTransportManager.sendGroupMessage()
// 它会在内部构建 TransportMessage
val result = runBlocking {
    groupTransportManager.sendGroupMessage(
        groupId = groupIdString,
        encryptedMessage = ciphertext,  // ✅ Signal 加密的密文
        messageId = messageId
    )
}
```

### 3. 修正结果处理

**之前（错误）**：
```kotlin
when (result) {
    is TransportResult.Success -> ...  // ❌ 错误类型
    is TransportResult.Failed -> ...
    // ...
}
```

**现在（正确）**：
```kotlin
when (result) {
    is GroupSendResult.Success -> {
        Log.i(TAG, "TAP 群组消息发送成功")
        return recipients.map { recipient ->
            SendMessageResult.success(recipient, ...)
        }
    }
    is GroupSendResult.Failed -> {
        Log.e(TAG, "TAP 群组消息发送失败: reason=${result.reason}")
        throw IOException("TAP transport failed: ${result.reason}")
    }
    is GroupSendResult.PartialSuccess -> {
        Log.w(TAG, "TAP 群组消息部分成功: success=${result.successCount}, failed=${result.failureCount}")
        return recipients.map { recipient ->
            SendMessageResult.success(recipient, ...)
        }
    }
}
```

### 4. 私聊消息处理

由于私聊目前仍使用 `TapSignalServiceAdapter.sendWithSignalEncryption()`，暂不通过 `TapMessageTransport` 路径：

```kotlin
override fun sendMessageViaTap(...): SendMessageResult {
    // 私聊 v2 mode 暂未实现通过此路径
    throw IOException("Private message TAP transport not implemented via this path. Use TapSignalServiceAdapter instead.")
}
```

## 关键点

1. **`GroupTransportManager.sendGroupMessage()` 是 suspend 函数**
   - 需要使用 `runBlocking` 调用（因为 `TapMessageTransport` 接口不是 suspend）

2. **不需要手动构建 `TransportMessage`**
   - `GroupTransportManager.sendGroupMessage()` 内部会构建
   - 我们只需要传递 `encryptedMessage`（Signal 加密的密文）

3. **返回类型是 `GroupSendResult`，不是 `TransportResult`**
   - `GroupSendResult.Success`
   - `GroupSendResult.Failed`
   - `GroupSendResult.PartialSuccess`

4. **职责清晰**
   - `TapMessageTransportImpl`: 只负责调用 `GroupTransportManager`
   - `GroupTransportManager`: 负责构建 `TransportMessage` 并上传到 COS
   - Signal: 负责加密

## 验证

编译成功，无错误：
```
✅ No linter errors found.
```
