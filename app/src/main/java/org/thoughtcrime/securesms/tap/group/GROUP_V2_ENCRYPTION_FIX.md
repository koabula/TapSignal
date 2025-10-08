# 群组 V2 Mode 加密解密修复方案

## 问题描述

在群组 v2 mode 中，消息发送和接收出现解密失败的问题：

**错误信息**：
```
org.signal.libsignal.protocol.LegacyMessageException: ciphertext version was too old <0>
```

## 根本原因

### 原实现问题

1. **发送端**：`TapSignalServiceAdapter.encryptGroupMessage()` 只是将消息序列化为 protobuf 格式，**没有使用 Signal 的加密机制**
   - 上传的是未加密的 protobuf 数据
   - 不是有效的 Signal 密文

2. **接收端**：尝试将 protobuf 数据当作 Signal 密文解密
   - 第一个字节不是有效的密文版本号（应该是 2, 3, 或 7）
   - 导致 `LegacyMessageException`

### Signal 群组消息加密机制

Signal 的群组消息使用 **Sender Key** 机制：

- **一对一消息**：使用 Session Cipher（X3DH + Double Ratchet）
- **群组消息**：使用 Sender Key（一次加密，所有成员收到相同密文）

```
发送方：
  明文 → GroupCipher.encrypt(distributionId, plaintext) → Sender Key 密文 (类型 7)
  
接收方：
  Sender Key 密文 → GroupCipher.decrypt(ciphertext) → 明文
```

## 修复方案

### 1. 发送端修复（TapSignalServiceAdapter）

**关键修改**：在 `encryptGroupMessage()` 中添加 Sender Key 加密步骤

```kotlin
fun encryptGroupMessage(...): ByteArray? {
    // 1. 构建 Signal 数据消息
    val signalDataMessage = buildSignalDataMessage(outgoingMessage)
    
    // 2. 序列化为 protobuf（明文）
    val plaintextBytes = serializeGroupMessage(signalDataMessage, groupRecipient)
    
    // 3. 使用 Sender Key 加密 ✅ 关键修复
    val ciphertextBytes = encryptWithSenderKey(groupRecipient, plaintextBytes)
    
    return ciphertextBytes
}

private fun encryptWithSenderKey(groupRecipient: Recipient, plaintextBytes: ByteArray): ByteArray? {
    val groupId = groupRecipient.requireGroupId() as GroupId.V2
    
    // 获取 Distribution ID
    val distributionId = SignalDatabase.groups.getOrCreateDistributionId(groupId)
    
    // 构建 SignalProtocolAddress（发送者地址）
    val localAci = SignalStore.account.requireAci()
    val localDeviceId = SignalStore.account.deviceId
    val localProtocolAddress = SignalProtocolAddress(localAci.toString(), localDeviceId)
    
    // 创建 GroupCipher
    val groupCipher = GroupCipher(protocolStore, localProtocolAddress)
    val signalGroupCipher = SignalGroupCipher(sessionLock, groupCipher)
    
    // 使用 Sender Key 加密
    val ciphertextMessage = signalGroupCipher.encrypt(distributionId.asUuid(), plaintextBytes)
    
    return ciphertextMessage.serialize() // 返回 Sender Key 密文
}
```

### 2. 接收端修复（TapEnvelopeAdapter）

**修改 1**：在 `adaptTransportMessageToEnvelope()` 中检测消息类型

```kotlin
private fun adaptTransportMessageToEnvelope(transportMessage: TransportMessage): Envelope? {
    // 解码密文
    val ciphertextBytes = Base64.decode(transportMessage.signalCiphertext)
    
    // 检测消息类型：群组 (Sender Key) 还是一对一 (Session Cipher)
    val envelopeType = detectEnvelopeType(ciphertextBytes, transportMessage.recipientId)
    
    // 构建 Envelope
    val envelopeBuilder = Envelope.Builder()
        .type(envelopeType) // 设置正确的类型
        .content(ciphertextBytes.toByteString())
        ...
}

private fun detectEnvelopeType(ciphertextBytes: ByteArray, recipientId: String): Envelope.Type {
    // 通过 recipientId 判断：groupId 还是个人 ACI
    val isGroupMessage = isGroupId(recipientId)
    
    return if (isGroupMessage) {
        Envelope.Type.SENDERKEY_MESSAGE // 群组消息
    } else {
        Envelope.Type.CIPHERTEXT // 一对一消息
    }
}

private fun isGroupId(recipientId: String): Boolean {
    // UUID 格式：个人 ACI
    // 长 base64 字符串：群组 ID
    val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val isUuid = uuidPattern.matches(recipientId)
    val isLongId = recipientId.length > 40
    
    return !isUuid && isLongId
}
```

**修改 2**：添加 Sender Key 消息的专用解密方法

```kotlin
private suspend fun decryptEnvelopeWithSignal(envelope: Envelope): SignalServiceCipherResult? {
    // 对于 Sender Key 消息，使用专用解密方法
    if (envelope.type == Envelope.Type.SENDERKEY_MESSAGE) {
        return decryptSenderKeyMessage(envelope)
    }
    
    // 其他类型使用标准解密
    return signalServiceCipher.decrypt(envelope, ...)
}

private suspend fun decryptSenderKeyMessage(envelope: Envelope): SignalServiceCipherResult? {
    // 1. 获取发送者信息
    val sourceServiceIdString = envelope.sourceServiceId ?: return null
    val sourceServiceId = ServiceId.parseOrThrow(sourceServiceIdString)
    
    // 2. 构建 SignalProtocolAddress（发送者地址）
    val groupIdString = envelope.serverGuid ?: return null
    val senderProtocolAddress = SignalProtocolAddress(
        sourceServiceIdString,
        envelope.sourceDevice ?: 1
    )
    
    // 3. 创建 GroupCipher
    val groupCipher = GroupCipher(protocolStore, senderProtocolAddress)
    val signalGroupCipher = SignalGroupCipher(sessionLock, groupCipher)
    
    // 4. 解密
    val ciphertextBytes = envelope.content?.toByteArray()
    val plaintextBytes = signalGroupCipher.decrypt(ciphertextBytes)
    
    // 5. 解析 Content 并返回
    val content = Content.ADAPTER.decode(plaintextBytes)
    val metadata = EnvelopeMetadata(
        sourceServiceId, null, envelope.sourceDevice ?: 1,
        false, groupIdString.toByteArray(), localServiceId
    )
    return SignalServiceCipherResult(content, metadata)
}
```

## 修复要点

### 关键设计决策

1. **使用 Signal 原生 Sender Key 机制**
   - 不重新实现加密算法
   - 复用 Signal 的 GroupCipher 和 SignalGroupCipher
   - 确保与 Signal 协议完全兼容

2. **所有成员收到相同密文**
   - Sender Key 特性：一次加密，所有人收到同样的密文
   - 与一对一加密不同：每个接收者的密文都不同

3. **通过 recipientId 区分消息类型**
   - recipientId = UUID → 一对一消息 (Session Cipher)
   - recipientId = 长 base64 字符串 → 群组消息 (Sender Key)

### 数据流

**发送流程**：
```
OutgoingMessage
  → buildSignalDataMessage()          // 构建 Signal 数据消息
  → serializeGroupMessage()           // 序列化为 protobuf (明文)
  → encryptWithSenderKey()            // Sender Key 加密 ✅
  → Base64.encode()                   // 编码
  → TransportMessage                  // 包装为传输消息
  → TAP 上传                          // 上传到我的群组目录
```

**接收流程**：
```
TAP 下载
  → TransportMessage                  // 获取传输消息
  → Base64.decode()                   // 解码
  → detectEnvelopeType()              // 检测类型 ✅
  → Envelope (SENDERKEY_MESSAGE)      // 构建 Envelope
  → decryptSenderKeyMessage()         // Sender Key 解密 ✅
  → Content                           // 解析内容
  → MessageContentProcessor           // 标准消息处理
```

## Sender Key 分发

### 重要前提

Sender Key 解密需要发送者先分发 `SenderKeyDistributionMessage`，这通过以下方式完成：

1. **首次群组消息发送时**
   - Signal 自动通过 Server 分发 Sender Key Distribution Message
   - 所有成员收到并存储发送者的 Sender Key

2. **新成员加入时**
   - Signal 重新分发 Sender Key Distribution Message
   - 确保新成员能解密后续消息

3. **定期轮换**
   - Signal 会定期轮换 Sender Key（配置周期）
   - 通过 `SenderKeyUtil.rotateOurKey(distributionId)` 完成

### TAP 集成考虑

- **依赖 Signal 原生流程**：TAP 不需要单独处理 Sender Key 分发
- **状态同步**：确保群组成员在 Signal Server 和 TAP 都处于相同状态
- **错误处理**：如果收到 `NoSessionException`，说明缺少 Sender Key，需要等待 Signal 重新分发

## 测试验证

### 验证点

1. **加密正确性**
   - 发送方输出的密文第一个字节应为 `0x37` (Sender Key 消息类型 7)
   - 密文长度应大于明文（包含加密开销）

2. **解密正确性**
   - 接收方能成功解密
   - 解密后的内容与原始消息一致

3. **消息类型识别**
   - 群组消息正确识别为 `SENDERKEY_MESSAGE`
   - 一对一消息正确识别为 `CIPHERTEXT`

### 日志检查

**发送端**：
```
GroupTransportManager: 开始群组消息加密
TapSignalServiceAdapter: 序列化消息: plaintextSize=XXX
TapSignalServiceAdapter: Sender Key 加密完成: type=7, ciphertextSize=YYY
GroupTransportManager: 群组消息 Sender Key 加密成功
```

**接收端**：
```
TapEnvelopeAdapter: 检测到消息类型: SENDERKEY_MESSAGE
TapEnvelopeAdapter: 开始解密 Sender Key 消息
TapEnvelopeAdapter: Sender Key 解密成功: plaintextSize=XXX
TapMessageProcessor: 传输消息处理成功
```

## 兼容性

### 与一对一 v2 mode 兼容

- 一对一消息继续使用 `encryptWithSignal()` (Session Cipher)
- 不影响现有的一对一加密流程
- 通过 `recipientId` 自动区分

### 与原生 Signal 群组兼容

- 使用相同的 Sender Key 机制
- 使用相同的 Distribution ID
- 可以无缝切换 TAP 传输和 Signal Server 传输

## 后续优化

1. **性能优化**
   - 缓存 GroupCipher 实例
   - 批量加密优化

2. **错误处理增强**
   - 更好的 Sender Key 缺失提示
   - 自动重试机制

3. **监控和日志**
   - 加密/解密性能监控
   - 成功率统计

---

**修复完成时间**: 2025-10-08
**相关文件**: 
- `TapSignalServiceAdapter.kt`
- `TapEnvelopeAdapter.kt`
- `GroupTransportManager.kt`

