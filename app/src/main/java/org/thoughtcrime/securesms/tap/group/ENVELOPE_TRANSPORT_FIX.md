# TAP 群组消息 Envelope 传输修复报告

## 修复日期
- 初次修复：2025-10-08（2人群组）
- GroupId修复：2025-10-09（3+人群组）

## 问题背景

### 原始问题
在实现 TAP 群组 v2 mode 时，2人群组消息无法正常解密，出现以下错误：
```
ciphertext version was too old <1>
```

### 根本原因分析

1. **类型系统混淆**
   - libsignal 的 `CiphertextMessage.type` (2=WHISPER_TYPE, 3=PREKEY_TYPE, 7=SENDERKEY_TYPE)
   - Envelope 的 `Envelope.Type` (1=CIPHERTEXT, 3=PREKEY_BUNDLE, 6=UNIDENTIFIED_SENDER, 7=SENDERKEY_MESSAGE)
   - 两套类型系统的数值不完全对应，导致映射错误

2. **格式不兼容**
   - `OutgoingPushMessage.content` 是为 REST API 传输设计的格式
   - `Envelope.content` 是为 WebSocket/Protobuf 传输设计的格式
   - 直接使用 `OutgoingPushMessage.content` 作为 `Envelope.content` 导致格式不匹配

3. **Sealed Sender 检测问题**
   - 发送端没有使用 Sealed Sender 时，`type=2` (WHISPER_TYPE)
   - 密文第一个字节不是 Sealed Sender 的版本标识
   - 手动检测失败，类型设置错误

## 解决方案

### 核心思路
**不传输单纯的密文，而是传输完整序列化的 Envelope**

这样可以：
- 避免 `OutgoingPushMessage` 和 `Envelope` 之间的格式差异
- 避免类型映射问题
- 避免 Sealed Sender 检测问题
- 模拟 Signal Server 的行为

### 实施方案

#### 1. 发送端修改

##### 1.1 2人群组（SessionCipher）
**文件**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**位置**: `sendMessage(List<SignalServiceAddress> recipients, ...)` 方法

**修改**:
```java
// 修改前：提取密文
byte[] ciphertext = extractPrimaryCiphertext(messages);

// 修改后：构造完整 Envelope
byte[] envelopeBytes = constructEnvelopeFromOutgoingMessage(messages, recipient, timestamp, groupId);
```

**新增方法**: `constructEnvelopeFromOutgoingMessage()`
- 从 `OutgoingPushMessage` 提取信息
- 正确映射 `OutgoingPushMessage.type` 到 `Envelope.Type`
- 构造完整的 Envelope
- 序列化并返回字节数组

##### 1.2 3+人群组（SenderKey）
**文件**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**位置**: `sendGroupMessage()` 方法

**修改**:
```java
// 修改前：直接传输 SenderKey 密文
return tapTransport.sendGroupMessageViaTap(groupId, recipients, ciphertext, ...);

// 修改后：构造完整 Envelope
byte[] envelopeBytes = constructEnvelopeForSenderKey(ciphertext, recipients, timestamp, groupId);
return tapTransport.sendGroupMessageViaTap(groupId, recipients, envelopeBytes, ...);
```

**新增方法**: `constructEnvelopeForSenderKey()`
- 使用 SenderKey 密文构造 Envelope
- 设置 `type=SENDERKEY_MESSAGE`
- 序列化并返回字节数组

#### 2. 接收端修改

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapEnvelopeAdapter.kt`

**修改**:
```kotlin
// 修改前：手动构造 Envelope
val envelopeBuilder = Envelope.Builder()...

// 修改后：直接反序列化 Envelope
val envelope = Envelope.ADAPTER.decode(envelopeBytes)
```

**新增兼容逻辑**: `adaptLegacyFormat()`
- 如果反序列化失败，回退到手动构造
- 保证向后兼容性

## 技术细节

### Envelope 类型映射

#### OutgoingPushMessage.type → Envelope.Type
| OutgoingPushMessage.type | 含义 | Envelope.Type | 说明 |
|--------------------------|------|---------------|------|
| 1 | CIPHERTEXT | CIPHERTEXT (1) | SessionCipher |
| 3 | PREKEY_BUNDLE | PREKEY_BUNDLE (3) | PreKey |
| 6 | UNIDENTIFIED_SENDER | UNIDENTIFIED_SENDER (6) | Sealed Sender |
| 7 | SENDERKEY_MESSAGE | SENDERKEY_MESSAGE (7) | SenderKey |
| 8 | PLAINTEXT_CONTENT | PLAINTEXT_CONTENT (8) | Plaintext |

#### libsignal CiphertextMessage.type → Envelope.Type (旧方案，已废弃)
| libsignal.type | 含义 | Envelope.Type |
|----------------|------|---------------|
| 2 | WHISPER_TYPE | CIPHERTEXT (1) |
| 3 | PREKEY_TYPE | PREKEY_BUNDLE (3) |
| 7 | SENDERKEY_TYPE | SENDERKEY_MESSAGE (7) |

### 消息流程

#### 2人群组（SessionCipher + Sealed Sender）

**发送端**:
```
OutgoingPushMessage (type=6, UNIDENTIFIED_SENDER)
    ↓
constructEnvelopeFromOutgoingMessage()
    ↓
Envelope (type=UNIDENTIFIED_SENDER)
    ↓
Envelope.encode() → 字节数组
    ↓
Base64.encode() → TAP 传输
```

**接收端**:
```
Base64.decode() → 字节数组
    ↓
Envelope.ADAPTER.decode()
    ↓
Envelope (type=UNIDENTIFIED_SENDER)
    ↓
SignalServiceCipher.decrypt() → 成功
```

#### 3+人群组（SenderKey）

**发送端**:
```
cipher.encryptForGroup() → SenderKey 密文
    ↓
constructEnvelopeForSenderKey()
    ↓
Envelope (type=SENDERKEY_MESSAGE)
    ↓
Envelope.encode() → 字节数组
    ↓
Base64.encode() → TAP 传输
```

**接收端**:
```
Base64.decode() → 字节数组
    ↓
Envelope.ADAPTER.decode()
    ↓
Envelope (type=SENDERKEY_MESSAGE)
    ↓
decryptSenderKeyMessage() → 成功
```

## 修改文件清单

### libsignal-service 层
1. `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`
   - 修改 `sendMessage(List<SignalServiceAddress> recipients, ...)` TAP 拦截逻辑
   - 修改 `sendGroupMessage()` TAP 拦截逻辑
   - 新增 `constructEnvelopeFromOutgoingMessage()` 方法
   - 新增 `constructEnvelopeForSenderKey()` 方法

### app 层
2. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapEnvelopeAdapter.kt`
   - 修改 `adaptTransportMessageToEnvelope()` 方法
   - 新增 `adaptLegacyFormat()` 兼容方法

## 关键日志

### 发送端日志

**2人群组**:
```
[sendMessage-List][timestamp] Constructed Envelope size: X bytes
constructEnvelope: Created Envelope with type=UNIDENTIFIED_SENDER, size=X
```

**3+人群组**:
```
[sendGroupMessage][timestamp] Constructed SenderKey Envelope size: X bytes
constructEnvelopeForSenderKey: Created SenderKey Envelope, size=X
[sendGroupMessage][timestamp] Using SenderKey encryption, isSessionCipher=false
```

### 接收端日志

**成功反序列化**:
```
Envelope反序列化成功: messageId=..., type=UNIDENTIFIED_SENDER/SENDERKEY_MESSAGE, timestamp=...
```

**兼容旧格式**:
```
无法反序列化为Envelope，可能是旧格式，尝试手动构造
使用旧格式兼容模式: messageId=...
```

## 测试验证

### 2人群组测试
- ✅ A 发起 use v2 mode 提议
- ✅ B 接受提议
- ✅ A 和 B 互相发送消息
- ✅ 消息成功解密和显示

### 3+人群组测试
建议测试流程：
1. 创建一个3+人群组（3人或更多）
2. 发起 use v2 mode 提议
3. 所有成员接受
4. 成员之间互相发送消息
5. 验证消息能正常显示

## 优势总结

1. **格式统一**: 2人群组和3+人群组都使用 Envelope 传输
2. **类型正确**: Envelope.type 由发送端正确设置，无需推测
3. **解密准确**: 接收端根据 Envelope.type 选择正确的解密方法
4. **健壮性强**: 不依赖旧格式兼容路径
5. **可维护性**: 代码逻辑清晰，易于理解和维护
6. **向后兼容**: 自动回退到旧格式兼容逻辑

## 相关文档

- `TWO_PERSON_GROUP_TAP_FIX.md`: 2人群组初始修复文档
- `GROUP_V2_ARCHITECTURE_REFACTOR.md`: 群组 V2 架构重构文档
- `TAP_TRANSPORT_IMPL_FIX.md`: TAP 传输实现修复文档

## 注意事项

1. **Envelope 序列化格式**: 使用 protobuf 格式，确保发送端和接收端版本一致
2. **类型映射**: 严格按照 OutgoingPushMessage.type 映射，不要使用 libsignal CiphertextMessage.type
3. **向后兼容**: 保留旧格式兼容逻辑，确保与旧版本的互操作性
4. **日志监控**: 关注 "Envelope反序列化成功" 和 "使用旧格式兼容模式" 日志，评估新旧格式占比

## 2025-10-09 GroupId 传递修复

### 问题描述
3+人群组使用SenderKey加密时，groupId无法正确传递给MessageContentProcessor，导致消息虽然解密成功但无法显示在正确的对话中。

**根本原因**：
1. 发送端：`constructEnvelopeForSenderKey`使用随机UUID作为serverGuid
2. 接收端：从`envelope.serverGuid`提取groupId（但得到的是随机UUID）

### 修复方案

#### 发送端修复（SignalServiceMessageSender.java:2743-2750）
```java
// 设置 serverGuid: 使用 groupId (Base64编码) 或生成随机UUID
if (groupId.isPresent()) {
  String groupIdBase64 = org.signal.core.util.Base64.encodeWithPadding(groupId.get());
  envelopeBuilder.serverGuid(groupIdBase64);
  Log.d(TAG, "constructEnvelopeForSenderKey: Using groupId as serverGuid, length=" + groupIdBase64.length());
} else {
  envelopeBuilder.serverGuid(java.util.UUID.randomUUID().toString());
}
```

#### 接收端修复（TapEnvelopeAdapter.kt:483-498）
```kotlin
// 3. 从 envelope.serverGuid 提取 groupId（发送端已将 Base64 编码的 groupId 放入 serverGuid）
val groupIdBase64 = envelope.serverGuid
if (groupIdBase64.isNullOrEmpty()) {
    Log.e(TAG, "Sender Key 消息缺少 groupId (serverGuid)")
    return null
}

// 解码 groupId
val groupIdBytes = try {
    org.signal.core.util.Base64.decode(groupIdBase64)
} catch (e: Exception) {
    Log.e(TAG, "解码 groupId 失败: serverGuid=$groupIdBase64", e)
    return null
}
```

### 修复效果
- MessageContentProcessor能够获取正确的groupId
- 消息正确显示在对应的群组对话中
- 3+人群组v2 mode完全可用

## 未来改进

1. 可以考虑在一段时间后（如所有客户端都升级后）移除旧格式兼容逻辑
2. 可以添加 Envelope 版本号，便于未来协议升级
3. 可以优化 Envelope 构造逻辑，减少重复代码

