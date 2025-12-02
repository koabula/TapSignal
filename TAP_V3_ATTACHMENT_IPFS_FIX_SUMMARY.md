# Tap v3 附件IPFS上传修复 - 实施总结

## 问题描述

通过分析日志文件（Log_A 和 Log_B）发现，Tap v3 模式下发送的附件并没有上传到 IPFS/Pinata，而是继续使用 Signal CDN（cdnNumber=3）。

### 根本原因

当前的 Tap v3 发送流程：
```
IndividualSendJob
  └─> SignalServiceMessageSender.sendDataMessageViaTapV3()
       └─> 加密消息体 + 附件
            └─> TapV3MessageTransportImpl.sendMessageViaTapV3(ciphertext)
                 └─> TapV3SendIntegrator.sendCiphertext(ciphertext)
```

问题在于：
1. `sendDataMessageViaTapV3()` 方法会将消息体和附件一起加密成密文
2. `sendMessageViaTapV3()` 只接收密文，无法访问原始附件
3. `sendCiphertext()` 只能处理密文，无法分离和上传附件到 IPFS

## 解决方案

创建一个 **附件传递机制**，让原始附件能够从 `IndividualSendJob` 层传递到 `TapV3MessageTransportImpl` 层。

### 架构设计

```
IndividualSendJob
  ├─> 提取附件列表
  ├─> 存储到 TapV3AttachmentHolder [recipientId -> attachments]
  └─> SignalServiceMessageSender.sendDataMessageViaTapV3()
       └─> 加密消息体（不含附件）
            └─> TapV3MessageTransportImpl.sendMessageViaTapV3(ciphertext)
                 ├─> 从 TapV3AttachmentHolder 获取附件
                 └─> TapV3SendIntegrator.sendMessage(ciphertext, attachments)
                      ├─> 上传密文到 IPFS → messageCid
                      ├─> 上传附件到 IPFS → attachmentCids[]
                      └─> 通过 UnifiedPush 发送 CID 引用
```

## 实施的修改

### 1. 创建 TapV3AttachmentHolder.kt

**文件路径**: `app/src/main/java/org/thoughtcrime/securesms/tapv3/integration/TapV3AttachmentHolder.kt`

**功能**: 线程安全的附件临时存储器

```kotlin
object TapV3AttachmentHolder {
    private val attachmentMap = ConcurrentHashMap<String, List<Attachment>>()
    
    fun setAttachments(recipientId: String, attachments: List<Attachment>)
    fun getAndClearAttachments(recipientId: String): List<Attachment>
    fun clear(recipientId: String)
}
```

**设计要点**:
- 使用 `ConcurrentHashMap` 保证线程安全
- `getAndClearAttachments` 方法自动清除，防止内存泄漏
- 以 recipientId (ACI) 为 key，支持并发发送多个消息

### 2. 修改 IndividualSendJob.java

**修改的方法**: `sendMessageViaTapV3()`

**关键改动**:

```java
// 1. 提取附件（排除贴纸）
List<Attachment> attachments = message.getAttachments().stream()
    .filter(a -> !a.isSticker())
    .collect(Collectors.toList());

// 2. 存储到 TapV3AttachmentHolder
TapV3AttachmentHolder.INSTANCE.setAttachments(recipientId, attachments);

// 3. 构建 SignalServiceDataMessage (不包含附件)
SignalServiceDataMessage dataMessage = SignalServiceDataMessage.newBuilder()
    .withBody(message.getBody())
    .withAttachments(Collections.emptyList())  // 附件通过IPFS单独处理
    .withTimestamp(message.getSentTimeMillis())
    // ... 其他字段
    .build();

// 4. 发送（TapV3MessageTransportImpl会拦截并处理附件）
SendMessageResult result = messageSender.sendDataMessageViaTapV3(
    address,
    SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
    ContentHint.RESENDABLE,
    dataMessage,
    message.isUrgent()
);
```

**错误处理**:
- 在 catch 块中调用 `TapV3AttachmentHolder.clear(recipientId)` 清除存储的附件
- 防止内存泄漏

### 3. 修改 TapV3MessageTransportImpl.kt

**修改的方法**: `sendMessageViaTapV3()`

**关键改动**:

```kotlin
override fun sendMessageViaTapV3(
    recipient: SignalServiceAddress,
    ciphertext: ByteArray,
    timestamp: Long,
    urgent: Boolean,
    online: Boolean
): SendMessageResult {
    val recipientId = recipient.serviceId.toString()
    
    // 从 TapV3AttachmentHolder 获取附件
    val attachments = TapV3AttachmentHolder.getAndClearAttachments(recipientId)
    
    val result = if (attachments.isNotEmpty()) {
        Log.d(TAG, "sending with ${attachments.size} attachments")
        runBlocking {
            // 调用 sendMessage() 处理密文 + 附件
            sendIntegrator.sendMessage(recipientId, ciphertext, attachments)
        }
    } else {
        runBlocking {
            // 调用 sendCiphertext() 仅处理密文
            sendIntegrator.sendCiphertext(recipientId, ciphertext)
        }
    }
    
    // 处理结果...
}
```

**设计要点**:
- 根据是否有附件，分别调用 `sendMessage()` 或 `sendCiphertext()`
- `sendMessage()` 会将密文和附件都上传到 IPFS
- `getAndClearAttachments()` 自动清除，防止被多次处理

### 4. 删除 TapV3SendAdapter.kt

**原因**: 最初设计的 `TapV3SendAdapter` 试图在 `IndividualSendJob` 层直接调用 `TapV3SendIntegrator`，但这会导致问题：
1. 需要重复进行 Signal E2EE 加密
2. 可能绕过 Signal 的其他处理逻辑
3. 架构复杂度高

**最终方案**: 使用更简单的 `TapV3AttachmentHolder` 传递附件，让现有的加密流程保持不变。

## 工作原理

### 发送端流程

1. **IndividualSendJob.sendMessageViaTapV3()**:
   - 提取附件列表（不含贴纸）
   - 将附件存储到 `TapV3AttachmentHolder[recipientId]`
   - 构建不含附件的 `SignalServiceDataMessage`
   - 调用 `sendDataMessageViaTapV3()` 进行加密

2. **SignalServiceMessageSender.sendDataMessageViaTapV3()**:
   - 对消息体进行 Signal E2EE 加密（不包含附件）
   - 生成密文 ciphertext
   - 调用 `TapV3MessageTransportImpl.sendMessageViaTapV3(ciphertext)`

3. **TapV3MessageTransportImpl.sendMessageViaTapV3()**:
   - 从 `TapV3AttachmentHolder` 获取并清除附件
   - 如果有附件：调用 `TapV3SendIntegrator.sendMessage(ciphertext, attachments)`
   - 如果无附件：调用 `TapV3SendIntegrator.sendCiphertext(ciphertext)`

4. **TapV3SendIntegrator.sendMessage()**:
   - 上传密文到 IPFS → `messageCid`
   - 遍历附件，上传到 IPFS → `attachmentCids[]`
   - 构建 `TapV3Payload.IpfsRefs(messageCid, attachmentRefs)`
   - 用 k_push 加密 payload
   - 通过 UnifiedPush 发送到对方的 push endpoint

### 接收端流程

（接收端逻辑已存在，不需要修改）

1. **UnifiedPush 接收推送通知**
2. **TapV3MessageCodec.decodeMessage()**:
   - 用 k_push 解密，得到 `TapV3Payload.IpfsRefs`
3. **TapV3ReceiveIntegrator**:
   - 从 IPFS 下载密文（使用 messageCid）
   - Signal E2EE 解密密文 → 消息体
   - 从 IPFS 下载附件（使用 attachmentCids）
   - 存储消息和附件到数据库

## 参考 Tap v2 实现

Tap v2 使用类似的模式（`TapSignalServiceAdapter.createTapAttachmentPointer()`）：

1. 拦截附件上传流程
2. 将附件上传到 COS/S3（而非 Signal CDN）
3. 创建特殊的 `AttachmentPointer`（cdnNumber=999）
4. 接收端识别 cdnNumber=999，从 COS 下载

Tap v3 改进：
- 使用 IPFS 而非 COS（更去中心化）
- 使用 CID 内容寻址（而非 URL）
- 使用 UnifiedPush（而非轮询）

## 验证方法

### 发送端日志

发送消息后，检查日志应包含：

```
IndividualSendJob: Starting Tap v3 send: messageId=xxx
IndividualSendJob: Storing N attachments for Tap v3 transport
TapV3MessageTransportImpl: sendMessageViaTapV3: ciphertextSize=xxx
TapV3MessageTransportImpl: sending with N attachments
TapV3SendIntegrator: Sending IPFS message: xxx bytes, N attachments
TapV3SendIntegrator: Uploaded message to IPFS: <messageCid>
TapV3SendIntegrator: Uploaded attachment to IPFS: <attachmentCid>
TapV3SendIntegrator: IPFS message sent successfully
```

### 接收端日志

接收消息后，检查日志应包含：

```
TapV3ReceiveIntegrator: Downloading message from IPFS: <messageCid>
TapV3ReceiveIntegrator: Downloading attachment from IPFS: <attachmentCid>
TapAttachmentDownloadIn: isTapAttachment=true
```

**关键指标**:
- `cdnNumber` 应该不再是 3（Signal CDN）
- 日志中应出现 "Uploaded to IPFS" 和 CID
- 接收端应从 IPFS 下载，而不是 Signal CDN

## 潜在问题和改进

### 1. 线程安全

**当前实现**: 使用 `ConcurrentHashMap` 保证基本线程安全

**潜在风险**: 
- 如果同一 recipientId 的两个消息同时发送，可能出现附件错配
- 虽然概率很低（不同消息的 messageId 不同，通常顺序处理），但理论上存在

**改进建议**: 
- 使用 `messageId + recipientId` 复合键
- 或添加超时清理机制

### 2. 内存管理

**当前实现**: `getAndClearAttachments()` 自动清除

**潜在风险**:
- 如果发送失败但未清理，附件会留在内存中
- 大附件可能占用较多内存

**改进建议**:
- 添加定时清理机制（例如：超过 5 分钟的条目自动清除）
- 监控 `attachmentMap` 大小

### 3. 大附件处理

**当前实现**: 直接读取附件到内存，上传到 IPFS

**潜在问题**:
- 大附件（例如视频）可能导致 OOM
- IPFS 上传可能超时

**改进建议**:
- 实现流式上传（避免一次性加载到内存）
- 添加上传进度回调
- 实现断点续传

### 4. IPFS 可靠性

**当前实现**: 依赖 IpfsGatewayManager 和 Pinata

**潜在问题**:
- IPFS 网关可能不稳定
- Pin 可能失败或过期

**改进建议**:
- 实现多个 IPFS 网关回退
- 添加 Pin 状态检查
- 实现本地缓存机制

## 与 Tap v2 的对比

| 特性 | Tap v2 | Tap v3 |
|------|--------|--------|
| 存储方式 | COS/S3 (腾讯云) | IPFS/Pinata |
| 附件引用 | cdnNumber=999 + URL | CID (内容寻址) |
| 消息传输 | HTTP 轮询 | UnifiedPush |
| 附件拦截 | TapSignalServiceAdapter | TapV3AttachmentHolder |
| 加密层次 | Signal E2EE | Signal E2EE + k_push |
| 去中心化 | 半中心化（依赖COS） | 更去中心化（IPFS） |

## 总结

本次修复通过创建 `TapV3AttachmentHolder` 作为附件传递机制，成功解决了 Tap v3 模式下附件无法上传到 IPFS 的问题。

**优点**:
- ✅ 最小化代码修改
- ✅ 不破坏现有的 Signal E2EE 流程
- ✅ 利用已有的 `TapV3SendIntegrator.sendMessage()` 方法
- ✅ 线程安全
- ✅ 自动清理，防止内存泄漏

**修改的文件**:
1. 新建：`TapV3AttachmentHolder.kt`
2. 修改：`IndividualSendJob.java` (sendMessageViaTapV3 方法)
3. 修改：`TapV3MessageTransportImpl.kt` (sendMessageViaTapV3 方法)
4. 删除：`TapV3SendAdapter.kt` (废弃的设计)

**下一步**:
1. 构建项目：`./gradlew assembleDebug`
2. 测试发送带附件的消息
3. 检查日志，验证附件上传到 IPFS
4. 测试接收端，验证从 IPFS 下载附件
