# 群组 V2 Mode 架构重构

## 问题根源

### 原设计的问题

在之前的实现中，我们在 `PushGroupSendJob` 中手动调用 `TapSignalServiceAdapter.encryptGroupMessage()` 来加密群组消息，这导致了以下问题：

1. **过早介入加密过程**：TAP 层不应该决定使用什么加密方式（Sender Key vs Legacy）
2. **绕过 Signal 原生逻辑**：绕过了 `GroupSendUtil` 的群组发送逻辑
3. **手动处理 Sender Key**：需要手动初始化和管理 Sender Key 会话
4. **无法处理边缘情况**：2人群组、Sender Key 未初始化等情况处理不当
5. **违反设计原则**：TAP 应该只负责传输，不应该参与加密决策

### 与私聊的对比

| 维度 | 私聊 V2 Mode（正确） | 群组 V2 Mode（旧实现） |
|------|---------------------|---------------------|
| 加密决策 | ✅ Signal | ❌ TAP |
| 加密执行 | ✅ Signal | ❌ TAP |
| 传输路径 | ✅ TAP | ✅ TAP |
| TAP 职责 | ✅ 只传输 | ❌ 加密+传输 |

## 新架构设计

### 核心原则

**TAP 是传输层修改，不参与任何加密决策。Signal 使用什么加密方式，都与 TAP 无关！**

### 架构图

```
原生 Signal 群组发送流程：
OutgoingMessage
  → PushGroupSendJob
  → GroupSendUtil.sendMessage()
      → 判断群组大小、成员状态
      → 决定使用 Sender Key 还是 Legacy
      → SignalServiceMessageSender.sendGroupMessage()
          → SignalServiceCipher.encryptForGroup()  ← Signal 完成加密
          → messageApi.sendGroupMessage()          ← 发送到 Signal Server

新架构（TAP 拦截）：
OutgoingMessage
  → PushGroupSendJob
  → GroupSendUtil.sendMessage()
      → 判断群组大小、成员状态
      → 决定使用 Sender Key 还是 Legacy
      → SignalServiceMessageSender.sendGroupMessage()
          → SignalServiceCipher.encryptForGroup()  ← Signal 完成加密
          → [TAP 拦截点]                           ← 检查是否为 v2 mode
          ├─ 是 v2 mode → TapMessageTransport.sendGroupMessageViaTap()
          │                 → GroupTransportManager.sendGroupMessage()
          │                 → 上传到 COS
          └─ 不是 v2 mode → messageApi.sendGroupMessage()
                              → 发送到 Signal Server
```

### 拦截点选择

**在 `SignalServiceMessageSender.sendGroupMessage()` 的加密完成后、网络发送前拦截**

- ✅ Signal 已完成所有加密决策和加密操作
- ✅ 我们只拿到加密后的密文
- ✅ 改变传输路径：从 Signal Server 改为 TAP
- ✅ 与私聊的拦截方式一致

## 实现细节

### 1. 创建 TAP 传输接口（libsignal-service 层）

**文件**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/TapMessageTransport.java`

```java
public interface TapMessageTransport {
  boolean shouldUseTapForGroup(Optional<byte[]> groupId);
  boolean shouldUseTapForRecipient(SignalServiceAddress recipient);
  
  List<SendMessageResult> sendGroupMessageViaTap(
      Optional<byte[]> groupId,
      List<SignalServiceAddress> recipients,
      byte[] ciphertext,  // Signal 已加密的密文
      long timestamp,
      boolean urgent,
      boolean online
  ) throws IOException;
  
  SendMessageResult sendMessageViaTap(
      SignalServiceAddress recipient,
      byte[] ciphertext,  // Signal 已加密的密文
      long timestamp,
      boolean urgent,
      boolean online
  ) throws IOException;
}
```

**关键点**：
- 接口只接收 `byte[] ciphertext`，不关心加密细节
- 职责单一：判断是否使用 TAP，以及传输密文

### 2. 修改 SignalServiceMessageSender（拦截点）

**文件**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**修改点 1**：添加 `TapMessageTransport` 字段

```java
private final TapMessageTransport tapTransport;

public SignalServiceMessageSender(..., TapMessageTransport tapTransport) {
    // ...
    this.tapTransport = tapTransport;
}
```

**修改点 2**：在 `sendGroupMessage()` 中添加拦截逻辑

```java
private List<SendMessageResult> sendGroupMessage(...) {
    // ... Signal 原生的 Sender Key 分发和加密逻辑 ...
    
    // Signal 完成加密
    byte[] ciphertext = cipher.encryptForGroup(...);
    
    sendEvents.onMessageEncrypted();
    
    // TAP 拦截：如果群组处于 v2 mode，通过 TAP 传输而不是 Signal Server
    if (tapTransport != null && tapTransport.shouldUseTapForGroup(groupId)) {
        Log.i(TAG, "Group is in v2 mode, sending via TAP transport.");
        return tapTransport.sendGroupMessageViaTap(groupId, recipients, ciphertext, timestamp, urgent, online);
    }
    
    // 否则，通过 Signal Server 发送（原有逻辑）
    SendGroupMessageResponse response = messageApi.sendGroupMessage(ciphertext, ...);
    // ...
}
```

**关键点**：
- 在 `cipher.encryptForGroup()` 之后拦截
- 在 `messageApi.sendGroupMessage()` 之前拦截
- Signal 的所有加密逻辑（Sender Key 分发、会话管理、加密）都已完成

### 3. 实现 TapMessageTransport（app 层）

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageTransportImpl.kt`

```kotlin
class TapMessageTransportImpl(private val context: Context) : TapMessageTransport {
    
    override fun shouldUseTapForGroup(groupId: Optional<ByteArray>): Boolean {
        if (!groupId.isPresent) return false
        
        val groupIdString = Base64.encodeToString(groupId.get(), Base64.NO_WRAP)
        val groupTransportManager = GroupTransportManager.getInstance(context)
        val status = groupTransportManager.getGroupStatusSync(groupIdString)
        
        return status == GroupV2Status.FULL_V2_ACTIVE
    }
    
    override fun sendGroupMessageViaTap(
        groupId: Optional<ByteArray>,
        recipients: List<SignalServiceAddress>,
        ciphertext: ByteArray,  // Signal 已加密的密文
        timestamp: Long,
        urgent: Boolean,
        online: Boolean
    ): List<SendMessageResult> {
        val groupIdString = Base64.encodeToString(groupId.get(), Base64.NO_WRAP)
        
        // 构建 TransportMessage（只包含密文）
        val transportMessage = TransportMessage(
            messageId = System.currentTimeMillis().toString(),
            senderId = "",
            recipientId = groupIdString,
            timestamp = timestamp,
            content = ciphertext,  // 直接传递 Signal 的密文
            messageType = "group_message",
            urgent = urgent
        )
        
        // 通过 GroupTransportManager 上传到 COS
        val result = groupTransportManager.sendGroupMessage(groupIdString, transportMessage)
        
        // 返回发送结果
        return when (result) {
            is TransportResult.Success -> recipients.map { 
                SendMessageResult.success(it, emptyList(), true, false, timestamp, Optional.empty())
            }
            is TransportResult.Failed -> throw IOException("TAP transport failed: ${result.errorMessage}")
            // ...
        }
    }
}
```

**关键点**：
- 只接收 Signal 已加密的密文
- 不关心加密方式（Sender Key、Legacy、Session Cipher）
- 只负责传输

### 4. 注入 TapMessageTransport

**文件**: `app/src/main/java/org/thoughtcrime/securesms/dependencies/ApplicationDependencyProvider.java`

```java
@Override
public @NonNull SignalServiceMessageSender provideSignalServiceMessageSender(...) {
    return new SignalServiceMessageSender(
        pushServiceSocket,
        protocolStore,
        ReentrantSessionLock.INSTANCE,
        attachmentApi,
        messageApi,
        keysApi,
        Optional.of(new SecurityEventListener(context)),
        SignalExecutors.newCachedBoundedExecutor(...),
        ByteUnit.KILOBYTES.toBytes(256),
        RemoteConfig::useMessageSendRestFallback,
        RemoteConfig.usePqRatchet(),
        new TapMessageTransportImpl(context)  // ← 注入 TAP 传输实现
    );
}
```

### 5. 清理旧代码

**删除的文件/方法**：

1. `TapSignalServiceAdapter.encryptGroupMessage()` - 不再需要手动加密
2. `TapSignalServiceAdapter.encryptWithSenderKey()` - Signal 自己处理
3. `TapSignalServiceAdapter.serializeGroupMessage()` - Signal 自己处理
4. `PushGroupSendJob.deliverViaGroupV2Mode()` - 不再需要手动调用
5. `PushGroupSendJob` 中的 v2 mode 判断逻辑 - 移到 `SignalServiceMessageSender`

**保留的代码**：
- `TapSignalServiceAdapter.sendWithSignalEncryption()` - 私聊仍需要
- `TapEnvelopeAdapter` - 接收端解密逻辑
- `GroupTransportManager` - 群组传输管理

## 优势对比

### 旧架构的问题

```kotlin
// PushGroupSendJob.java (旧)
if (groupV2Status == FULL_V2_ACTIVE) {
    // ❌ 手动调用加密
    byte[] encryptedMessage = adapter.encryptGroupMessage(messageId, groupRecipient, message);
    // ❌ 绕过了 GroupSendUtil
    // ❌ 需要手动处理 Sender Key
    groupTransportManager.sendGroupMessage(groupId, encryptedMessage);
    return;
}

// 正常流程
GroupSendUtil.sendResendableDataMessage(...);
```

### 新架构的优势

```java
// PushGroupSendJob.java (新)
// 所有消息都走统一流程
List<SendMessageResult> results = deliver(message, originalEditedMessage, groupRecipient, target);

// SignalServiceMessageSender.java (新)
private List<SendMessageResult> sendGroupMessage(...) {
    // ✅ Signal 完成所有加密逻辑
    byte[] ciphertext = cipher.encryptForGroup(...);
    
    // ✅ TAP 只在传输层拦截
    if (tapTransport != null && tapTransport.shouldUseTapForGroup(groupId)) {
        return tapTransport.sendGroupMessageViaTap(..., ciphertext, ...);
    }
    
    // ✅ 否则走 Signal Server
    return messageApi.sendGroupMessage(ciphertext, ...);
}
```

**优势**：
1. ✅ **职责清晰**：Signal 负责加密，TAP 负责传输
2. ✅ **完全复用 Signal 逻辑**：Sender Key 分发、会话管理、2人群组处理等
3. ✅ **统一的代码路径**：v2 mode 和原生模式走相同的加密流程
4. ✅ **易于维护**：TAP 代码不需要跟随 Signal 协议更新
5. ✅ **与私聊一致**：相同的拦截模式
6. ✅ **最小侵入性**：只在传输层拦截，不影响其他功能

## 接收端（无需修改）

接收端的 `TapEnvelopeAdapter` 已经正确实现：

```kotlin
// TapEnvelopeAdapter.kt
fun adaptTransportMessageToEnvelope(transportMessage: TransportMessage): Envelope {
    // 1. 将 TransportMessage 转换为 Envelope
    val envelope = Envelope.Builder()
        .type(detectEnvelopeType(...))  // 自动检测类型
        .content(transportMessage.content.toByteString())  // Signal 密文
        // ...
    
    // 2. Signal 的 SignalServiceCipher 会自动解密
    //    - 如果是 Sender Key 消息，使用 GroupCipher 解密
    //    - 如果是 Session Cipher 消息，使用 SessionCipher 解密
}
```

**关键点**：
- TAP 只负责下载密文并转换为 `Envelope`
- Signal 的 `SignalServiceCipher` 自动识别类型并解密
- 无需手动判断加密方式

## 测试验证

### 测试场景

1. **3人以上群组**：
   - Signal 自动使用 Sender Key
   - 所有成员收到相同密文
   - 通过 TAP 传输

2. **2人群组**：
   - Signal 自动使用 Legacy (Session Cipher)
   - 每个成员收到不同密文
   - 通过 TAP 传输

3. **Sender Key 未初始化**：
   - Signal 自动分发 Sender Key Distribution Message
   - 然后使用 Sender Key 加密
   - 通过 TAP 传输

4. **Token 交换控制消息**：
   - 仍然通过 Signal Server 发送（在 `shouldUseTapForGroup` 中判断）

### 预期结果

- ✅ 所有加密/解密由 Signal 原生逻辑处理
- ✅ TAP 只负责传输密文
- ✅ 不再出现 `NoSessionException`
- ✅ 2人群组和多人群组都能正常工作
- ✅ 与私聊 v2 mode 行为一致

## 总结

这次重构的核心是**回归 TAP 的本质**：

> TAP 是传输层修改，不是加密层修改。

通过将拦截点从 `PushGroupSendJob`（应用层）移到 `SignalServiceMessageSender`（传输层），我们实现了：

1. **正确的抽象层次**：加密在 Signal 层，传输在 TAP 层
2. **最小的代码侵入**：只修改传输路径，不修改加密逻辑
3. **完全的协议兼容**：100% 复用 Signal 的加密实现
4. **统一的架构模式**：私聊和群聊使用相同的拦截方式

这才是 TAP 模块应有的设计！
