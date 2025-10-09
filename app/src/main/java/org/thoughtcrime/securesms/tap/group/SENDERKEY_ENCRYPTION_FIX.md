# 纯 SenderKey 加密修复报告

## 修复日期
2025-10-09

## 问题概述

3人群组 TAP v2 mode 存在两个关键问题：
1. **解密失败**：`invalid sealed sender message: derived ephemeral key did not match key provided in message`
2. **数据库Schema错误**：`no such column: message_id`

## 问题1：Sealed Sender 多接收者加密兼容性问题

### 根本原因

**错误的加密流程**：
```
cipher.encryptForGroup() 
  ↓ GroupCipher.encrypt() → 纯 SenderKey 密文 (type=7)
  ↓ 包装成 UnidentifiedSenderMessageContent
  ↓ multiRecipientEncrypt() → Sealed Sender 密文（绑定特定接收者）
  ↓ 返回 Sealed Sender 密文
```

**问题**：
- `multiRecipientEncrypt()` 为**特定接收者**创建 Sealed Sender 包装
- Sealed Sender 密文包含**接收者特定的临时密钥**
- TAP 场景下，**同一个密文发给所有成员**
- 只有匹配的接收者能解密，其他人解密失败：`derived ephemeral key did not match`

**Signal Server vs TAP 的差异**：
- **Signal Server**：为每个接收者分别发送独立的 Sealed Sender 消息
- **TAP（错误实现）**：把同一个 Sealed Sender 密文发给所有成员

### 修复方案：使用纯 SenderKey 加密

**核心思路**：TAP 是私有传输通道，不需要 Sealed Sender 的匿名保护，直接使用纯 SenderKey 加密。

#### 修复1：发送端 - 纯 SenderKey 加密

**文件**：`libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**新增导入** (Line 31, 37)：
```java
import org.whispersystems.signalservice.api.crypto.SignalGroupCipher;
import org.whispersystems.signalservice.internal.push.PushTransportDetails;
```

**新增方法1**：`encryptForGroupWithoutSealedSender()` (Line 2735-2760)
```java
private byte[] encryptForGroupWithoutSealedSender(
    DistributionId distributionId,
    byte[] plaintext,
    List<SignalProtocolAddress> destinations
) {
    // 创建 GroupCipher
    SignalGroupCipher groupCipher = new SignalGroupCipher(
        sessionLock, 
        new org.signal.libsignal.protocol.groups.GroupCipher(aciStore, localProtocolAddress)
    );
    
    // 使用 PushTransportDetails 添加 padding
    PushTransportDetails transport = new PushTransportDetails();
    byte[] paddedMessage = transport.getPaddedMessageBody(plaintext);
    
    // 纯 SenderKey 加密，不包装 Sealed Sender
    CiphertextMessage ciphertextMessage = groupCipher.encrypt(distributionId.asUuid(), paddedMessage);
    
    return ciphertextMessage.serialize();
}
```

**新增方法2**：`constructEnvelopeForPureSenderKey()` (Line 2773-2816)
```java
private byte[] constructEnvelopeForPureSenderKey(
    byte[] pureSenderKeyCiphertext,
    List<SignalServiceAddress> recipients,
    long timestamp,
    Optional<byte[]> groupId
) {
    // 构造 SENDERKEY_MESSAGE 类型的 Envelope
    Envelope.Builder envelopeBuilder = 
        new Envelope.Builder()
            .type(Envelope.Type.SENDERKEY_MESSAGE)  // ← 关键：使用 SENDERKEY_MESSAGE
            .timestamp(timestamp)
            .content(ByteString.of(pureSenderKeyCiphertext))
            .sourceServiceId(localAddress.getServiceId().toString())
            .sourceDevice(localDeviceId);
    
    // 设置 groupId 为 serverGuid
    if (groupId.isPresent()) {
        envelopeBuilder.serverGuid(Base64.encodeWithPadding(groupId.get()));
    }
    
    return envelopeBuilder.build().encode();
}
```

**修改TAP拦截逻辑** (Line 2610-2629)：
```java
} else if (tapTransport != null && tapTransport.shouldUseTapForGroup(groupId)) {
    // 为 TAP 传输使用纯 SenderKey 加密（不使用 Sealed Sender 包装）
    // 原因：Sealed Sender 的多接收者加密在 TAP 场景下有兼容性问题
    byte[] pureSenderKeyCiphertext = encryptForGroupWithoutSealedSender(
        distributionId, 
        content.encode(), 
        targetInfo.destinations
    );
    
    // 构造 SENDERKEY_MESSAGE 类型的 Envelope
    byte[] envelopeBytes = constructEnvelopeForPureSenderKey(
        pureSenderKeyCiphertext, 
        recipients, 
        timestamp, 
        groupId
    );
    
    return tapTransport.sendGroupMessageViaTap(...);
}
```

#### 修复2：接收端 - SenderKey 解密

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapEnvelopeAdapter.kt`

接收端已有完整的 `decryptSenderKeyMessage()` 方法 (Line 465-559)，能够正确处理：
1. 提取 `envelope.sourceServiceId` 和 `envelope.serverGuid`（包含 groupId）
2. 创建 `GroupCipher`
3. 解密纯 SenderKey 密文
4. 解析 `Content` protobuf
5. 构建 `SignalServiceCipherResult`

**关键代码**：
```kotlin
// 创建 GroupCipher
val groupCipher = org.signal.libsignal.protocol.groups.GroupCipher(
    protocolStore,
    senderProtocolAddress
)

val signalGroupCipher = SignalGroupCipher(sessionLock, groupCipher)

// 解密纯 SenderKey 密文
val ciphertextBytes = envelope.content?.toByteArray()
val plaintextBytes = signalGroupCipher.decrypt(ciphertextBytes)

// 解析 Content
val content = Content.ADAPTER.decode(plaintextBytes)
```

### 对比：Sealed Sender vs 纯 SenderKey

| 特性 | Sealed Sender | 纯 SenderKey |
|------|--------------|-------------|
| **匿名性** | 隐藏发送者身份 | 包含发送者信息 |
| **多接收者** | 为每个接收者独立加密 | 所有接收者共享密文 |
| **TAP适用性** | ❌ 多接收者兼容性问题 | ✅ 完美支持 |
| **密文大小** | 较大（包含证书） | 较小 |
| **加密类型** | `UNIDENTIFIED_SENDER (6)` | `SENDERKEY_MESSAGE (7)` |

## 问题2：数据库Schema不一致

### 根本原因

**两个表定义冲突**：

1. **旧定义**（`TransportPollingStateTable.java`，Line 74-80）：
   ```java
   CREATE TABLE transport_processed_messages (
       _id INTEGER PRIMARY KEY,
       duplication_key TEXT UNIQUE,
       processed_timestamp INTEGER,
       created_at INTEGER
   )
   ```
   ❌ **缺少 `message_id`, `recipient_id`, `timestamp`, `processed_at` 列**

2. **新定义**（`V287_TransportTablesCreation.kt`，Line 60-69）：
   ```kotlin
   CREATE TABLE transport_processed_messages (
       _id INTEGER PRIMARY KEY,
       duplication_key TEXT UNIQUE,
       message_id TEXT NOT NULL,           // ✅ 有这列
       recipient_id TEXT NOT NULL,         // ✅ 有这列
       timestamp INTEGER NOT NULL,         // ✅ 有这列
       processed_at INTEGER NOT NULL,      // ✅ 有这列
       created_at INTEGER NOT NULL
   )
   ```

**问题**：
- `SignalDatabase.kt` 的 `onCreate()` 使用旧定义创建表
- `TransportMessageDeduplicator.kt` 使用新列名查询数据库
- 导致 `no such column: message_id` 错误

### 修复方案：更新旧定义

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/database/TransportPollingStateTable.java`

**修改前** (Line 48-80)：
```java
// 消息去重表常量
public static final String PROCESSED_MESSAGES_TABLE = "transport_processed_messages";
private static final String PM_ID                    = "_id";
private static final String PM_DUPLICATION_KEY      = "duplication_key";
private static final String PM_PROCESSED_TIMESTAMP  = "processed_timestamp";  // ❌ 旧列名
private static final String PM_CREATED_AT           = "created_at";

public static final String CREATE_PROCESSED_MESSAGES_TABLE = 
    "CREATE TABLE " + PROCESSED_MESSAGES_TABLE + "(" +
        PM_ID                    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        PM_DUPLICATION_KEY       + " TEXT UNIQUE NOT NULL, " +
        PM_PROCESSED_TIMESTAMP   + " INTEGER NOT NULL, " +
        PM_CREATED_AT           + " INTEGER NOT NULL" +
    ")";
```

**修改后** (Line 48-87)：
```java
// 消息去重表常量
public static final String PROCESSED_MESSAGES_TABLE = "transport_processed_messages";
private static final String PM_ID                    = "_id";
private static final String PM_DUPLICATION_KEY      = "duplication_key";
private static final String PM_MESSAGE_ID           = "message_id";          // ✅ 新增
private static final String PM_RECIPIENT_ID         = "recipient_id";        // ✅ 新增
private static final String PM_TIMESTAMP            = "timestamp";            // ✅ 新增
private static final String PM_PROCESSED_AT         = "processed_at";        // ✅ 新增
private static final String PM_CREATED_AT           = "created_at";

// 消息去重表定义（与 V287_TransportTablesCreation 保持一致）
public static final String CREATE_PROCESSED_MESSAGES_TABLE = 
    "CREATE TABLE IF NOT EXISTS " + PROCESSED_MESSAGES_TABLE + " (" +
        PM_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
        PM_DUPLICATION_KEY + " TEXT UNIQUE NOT NULL, " +
        PM_MESSAGE_ID + " TEXT NOT NULL, " +                    // ✅ 新增
        PM_RECIPIENT_ID + " TEXT NOT NULL, " +                  // ✅ 新增
        PM_TIMESTAMP + " INTEGER NOT NULL, " +                  // ✅ 新增
        PM_PROCESSED_AT + " INTEGER NOT NULL, " +              // ✅ 新增
        PM_CREATED_AT + " INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)" +
    ")";
```

**额外修复**：将所有使用旧列名的地方同步更新
- `markMessageAsProcessed()`: `PM_PROCESSED_TIMESTAMP` → `PM_PROCESSED_AT` (Line 473)
- `getRecentProcessedMessageKeys()`: `PM_PROCESSED_TIMESTAMP` → `PM_PROCESSED_AT` (Line 526, 530)
- `cleanupExpiredMessages()`: `PM_PROCESSED_TIMESTAMP` → `PM_PROCESSED_AT` (Line 551)

## 修复 3: Padding处理遗漏（Protobuf解析失败）

### 问题现象

**错误日志**：
```
Line 338: Sender Key 解密成功: plaintextSize=159  ✅
Line 340: ProtocolException: Unexpected tag 0     ❌
  at ProtoReader.nextTag(ProtoReader.kt:143)
  at Content$Companion$ADAPTER$1.decode(Content.kt:405)
```

### 根本原因

**数据流程不完整**：

| 步骤 | 发送端 | 接收端 | 状态 |
|------|--------|--------|------|
| 1. 原始消息 | Content protobuf | - | ✅ |
| 2. 添加Padding | `PushTransportDetails.getPaddedMessageBody()` | - | ✅ 已实现 |
| 3. SenderKey加密 | `GroupCipher.encrypt()` | - | ✅ 已实现 |
| 4. TAP传输 | 上传 | 下载 | ✅ 正常 |
| 5. SenderKey解密 | - | `GroupCipher.decrypt()` | ✅ 成功 |
| 6. **去除Padding** | - | **❌ 遗漏！** | **❌ 问题** |
| 7. 解析Protobuf | - | `Content.ADAPTER.decode()` | ❌ 失败 |

**问题**：
- 解密后得到的是**含padding的数据**（159字节）
- Padding字节（通常是`0x00`）被Protobuf解析器误认为是tag 0
- Protobuf协议中tag 0是非法的，导致`Unexpected tag 0`错误

### 修复方案

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapEnvelopeAdapter.kt`

**修改位置**: `decryptSenderKeyMessage()` 方法，Line 524-535

**修改前**:
```kotlin
val plaintextBytes = signalGroupCipher.decrypt(ciphertextBytes)
Log.d(TAG, "Sender Key 解密成功: plaintextSize=${plaintextBytes.size}")

// 7. 解析 Content
val content = Content.ADAPTER.decode(plaintextBytes)  // ❌ 直接解析含padding数据
```

**修改后**:
```kotlin
val plaintextBytes = signalGroupCipher.decrypt(ciphertextBytes)
Log.d(TAG, "Sender Key 解密成功 (含padding): plaintextSize=${plaintextBytes.size}")

// 6.5. 去除 Padding - Signal 协议标准步骤
val transport = PushTransportDetails()
val strippedMessage = transport.getStrippedPaddingMessageBody(plaintextBytes)
Log.d(TAG, "Padding已去除: originalSize=${plaintextBytes.size}, strippedSize=${strippedMessage.size}")

// 7. 解析 Content (使用去除padding后的数据)
val content = Content.ADAPTER.decode(strippedMessage)  // ✅ 正确
```

## 修复效果

### 发送端日志（修复后）
```
[sendGroupMessage] Using pure SenderKey encryption (no Sealed Sender), isSessionCipher=false
encryptForGroupWithoutSealedSender: Pure SenderKey encryption successful, size=...
constructEnvelopeForPureSenderKey: Created pure SenderKey Envelope (type=SENDERKEY_MESSAGE), size=...
```

### 接收端日志（修复后）
```
开始解密 Sender Key 消息: timestamp=...
Sender Key 解密成功 (含padding): plaintextSize=159
Padding已去除: originalSize=159, strippedSize=145
Sender Key 消息解密完成: timestamp=..., groupId已正确提取
```

### 数据库错误（修复后）
- ✅ 不再出现 `no such column: message_id` 错误
- ✅ 消息去重正常工作
- ✅ 数据库Schema一致

## 技术要点

### SenderKey 加密流程

**正确的纯 SenderKey 流程**：
```
原始消息 (Content protobuf)
  ↓ PushTransportDetails.getPaddedMessageBody() → 添加 padding
填充消息 (padded bytes)
  ↓ GroupCipher.encrypt(distributionId, paddedMessage) → SenderKey 加密
纯 SenderKey 密文 (CiphertextMessage, type=7)
  ↓ ciphertextMessage.serialize() → 序列化
密文字节数组 (byte[])
  ↓ 包装进 Envelope (type=SENDERKEY_MESSAGE)
Envelope (完整消息包)
  ↓ TAP 传输
所有群组成员
  ↓ GroupCipher.decrypt(ciphertextBytes) → 解密
填充消息
  ↓ PushTransportDetails.getStrippedPaddingMessageBody() → 去除 padding
原始消息
```

### Envelope 类型映射

| 密文类型 | libsignal 常量 | Envelope.Type | 值 |
|---------|---------------|---------------|---|
| Session | WHISPER_TYPE | CIPHERTEXT | 1 |
| PreKey | PREKEY_TYPE | PREKEY_BUNDLE | 3 |
| Sealed Sender | - | UNIDENTIFIED_SENDER | 6 |
| **SenderKey** | **SENDERKEY_TYPE** | **SENDERKEY_MESSAGE** | **7** |
| Plaintext | PLAINTEXT_TYPE | PLAINTEXT_CONTENT | 8 |

## 相关文档

- `THREE_PERSON_GROUP_V2_FIX.md`: 轮询路径修复
- `ENVELOPE_TRANSPORT_FIX.md`: 2人群组Envelope修复
- `PLAN_GROUP.md`: 群组V2实现计划

## 总结

**核心修复**：
1. ✅ 发送端：使用纯 SenderKey 加密，构造 `SENDERKEY_MESSAGE` 类型Envelope
2. ✅ 接收端：已有完整的 SenderKey 解密逻辑
3. ✅ 数据库：更新旧表定义，与 V287 迁移保持一致

**关键洞察**：
- TAP 是私有传输通道，不需要 Sealed Sender 的匿名保护
- Sealed Sender 的多接收者加密不适用于广播场景
- 纯 SenderKey 加密完美支持群组消息的一对多传输
- 数据库Schema定义必须在所有地方保持一致

**影响范围**：
- 仅影响3+人群组的TAP v2 mode
- 不影响私聊、2人群组和Signal Server传输
- 解决了解密失败和数据库Schema不一致问题

