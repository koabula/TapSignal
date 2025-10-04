# Phase 4: 消息发送和接收 - 实现总结

**实施日期**: 2025-10-04  
**状态**: ✅ 核心功能已完成

---

## 实现概述

Phase 4 实现了群组 V2 模式下消息的发送和接收功能，包括并发发送、轮询接收、消息去重等核心能力。

---

## 已实现功能

### 1. 群组消息发送 ✅

#### 1.1 GroupTransportManager.sendGroupMessage()

**位置**: `GroupTransportManager.kt` (行 622-829)

**核心功能**:
- 检查群组状态是否为 `FULL_V2_ACTIVE`
- 获取所有群组成员的通道
- 并发为每个成员上传消息
- 统计成功/失败结果并返回

**关键方法**:
```kotlin
suspend fun sendGroupMessage(
    groupId: String,
    encryptedMessage: ByteArray,
    messageId: String
): GroupSendResult

private suspend fun performConcurrentUpload(...)
private suspend fun uploadMessageToMember(...)
```

**特性**:
- 使用 `supervisorScope` 进行并发上传
- 支持部分失败处理
- 完整的错误日志和统计

#### 1.2 PushGroupSendJob 集成

**位置**: `PushGroupSendJob.java` (行 227-362)

**核心修改**:
1. 在 `onPushSend()` 方法中添加 v2 mode 检查
2. 如果群组状态为 `FULL_V2_ACTIVE`，通过 tap 层发送
3. 发送失败时回退到 Signal Server

**关键代码**:
```java
String groupIdString = groupRecipient.requireGroupId().toString();
GroupTransportManager groupTransportManager = 
    GroupTransportManager.getInstance(context);
GroupV2Status groupV2Status = groupTransportManager.getGroupStatus(groupIdString);

if (groupV2Status == GroupV2Status.FULL_V2_ACTIVE) {
    boolean tapSendSuccess = deliverViaGroupV2Mode(messageId, groupIdString, groupRecipient);
    if (tapSendSuccess) {
        // 标记为已发送
        database.markAsSent(messageId, true);
        return;
    }
}
```

**消息流程**:
1. 获取群组状态
2. 调用 `TapSignalServiceAdapter.encryptGroupMessage()` 进行 Signal 加密
3. 调用 `GroupTransportManager.sendGroupMessage()` 并发上传
4. 处理发送结果（Success/PartialSuccess/Failed）
5. 失败时回退到 Signal Server

---

### 2. 群组消息接收 ✅

#### 2.1 TapPollingService 扩展

**位置**: `TapPollingService.kt` (行 227-319)

**新增方法**:
```kotlin
fun addGroupPollingTargets(
    groupId: String,
    memberMetadatas: Map<String, TransportMetadata>
): Int

fun removeGroupPollingTargets(
    groupId: String,
    memberAcis: Set<String>,
    providerType: String
): Int
```

**功能说明**:
- 批量为群组成员添加轮询目标
- 在 metadata 中标记 `groupId` 和 `isGroupPolling`
- 支持批量移除群组轮询目标

**使用场景**:
- 群组激活 v2 mode 时调用 `addGroupPollingTargets()`
- 群组禁用 v2 mode 时调用 `removeGroupPollingTargets()`

#### 2.2 群组消息去重集成

**位置**: `TapMessageProcessor.kt` (行 136-189)

**核心逻辑**:
```kotlin
// 检查是否为群组消息
val isGroupMessage = transportMessage.metadata["isGroupMessage"] == "true" ||
                     transportMessage.metadata.containsKey("groupId")

if (isGroupMessage) {
    val deduplicator = GroupMessageDeduplicator.getInstance(context)
    val isDuplicate = deduplicator.isDuplicate(
        messageId = transportMessage.messageId,
        senderAci = senderAci,
        groupId = groupId,
        timestamp = timestamp
    )
    
    if (isDuplicate) {
        return TapProcessResult.Duplicate
    }
}
```

**去重策略**:
- 使用 `GroupMessageDeduplicator` 检查重复
- 基于 `groupId:messageId:senderAci:timestamp` 生成去重键
- LRU 缓存 + 数据库持久化
- 消息成功处理后调用 `markAsProcessed()`

---

### 3. Signal 加密支持 ✅

#### 3.1 群组消息加密

**位置**: `TapSignalServiceAdapter.kt` (行 115-225)

**新增方法**:
```kotlin
fun encryptGroupMessage(
    messageId: Long,
    groupRecipient: Recipient,
    outgoingMessage: OutgoingMessage
): ByteArray?

private fun serializeGroupMessage(
    message: SignalServiceDataMessage,
    groupRecipient: Recipient
): ByteArray?
```

**加密流程**:
1. 验证接收者是群组
2. 设置发送上下文
3. 构建 Signal 数据消息
4. 序列化群组消息（包含群组上下文）
5. 返回加密后的字节数组

**序列化内容**:
- 消息文本
- 时间戳
- 过期时间
- 群组上下文 (GroupContextV2)
- 附件指针
- 预览信息

---

## 关键设计决策

### 群组消息标识

由于 `TransportMessage` 和 `TransportContentMetadata` 的结构限制，我们采用了**消息ID编码**的方式来标识群组消息：

**格式**: `group_{groupId}_{originalMessageId}`

**示例**: `group_abcd1234_567890`

**优点**:
- 不需要修改现有的数据结构
- 简单高效的识别方式
- 包含所有必要的群组信息

**使用场景**:
- 发送时：`GroupTransportManager.uploadMessageToMember()` 构建消息ID
- 接收时：`TapMessageProcessor.processTapTransportMessage()` 解析消息ID
- 去重时：使用完整的 messageId 作为去重键的一部分

---

## 核心数据流

### 发送流程

```
用户发送群组消息
    ↓
PushGroupSendJob.onPushSend()
    ↓
检查群组是否 FULL_V2_ACTIVE
    ↓ (是)
TapSignalServiceAdapter.encryptGroupMessage()
    ↓
Signal 协议加密 + 序列化
    ↓
GroupTransportManager.sendGroupMessage()
    ↓
为每个成员构建 TransportMessage
  - messageId: "group_{groupId}_{originalMessageId}"
  - messageType: MEDIA_MESSAGE
    ↓
并发上传到所有成员目录
    ↓
汇总结果 (Success/PartialSuccess/Failed)
    ↓
标记消息已发送
```

### 接收流程

```
TapPollingService 轮询群组成员目录
    ↓
发现新文件
    ↓
下载并解析 TransportMessage
    ↓
TapMessageProcessor.processTapTransportMessage()
    ↓
检查 messageId 前缀 (startsWith("group_"))
    ↓
从 messageId 提取 groupId (split("_")[1])
    ↓
GroupMessageDeduplicator 去重检查
    ↓ (不重复)
TapEnvelopeAdapter.processEncryptedMessage()
    ↓
Signal 协议解密
    ↓
消息入库
    ↓
GroupMessageDeduplicator.markAsProcessed()
```

---

## 性能优化

### 1. 并发上传
- 使用 Kotlin 协程的 `supervisorScope` 和 `async`
- 所有成员的上传任务并行执行
- 单个成员失败不影响其他成员

### 2. 消息去重
- LRU 缓存 (最多 5000 条)
- 缓存命中避免数据库查询
- 定期清理过期记录 (7天)

### 3. 轮询优化
- 复用现有的单联系人轮询机制
- 在 metadata 中标记群组信息
- 支持批量添加/移除

---

## 错误处理

### 1. 发送失败处理

**部分成功**:
- 记录失败成员列表
- 返回 `GroupSendResult.PartialSuccess`
- TODO: 在后续版本中实现重试逻辑

**完全失败**:
- 返回 `GroupSendResult.Failed`
- 回退到 Signal Server 发送

### 2. 轮询失败处理

- 单个成员轮询失败不影响其他成员
- 继承 `TapPollingService` 的错误处理机制
- 支持自动退避和重试

---

## 数据库支持

### 群组去重表

已在 Phase 2 创建的表：
```sql
CREATE TABLE transport_group_processed_messages (
    duplication_key TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    sender_aci TEXT NOT NULL,
    group_id TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    processed_at INTEGER NOT NULL,
    polling_member_aci TEXT,
    created_at INTEGER NOT NULL
);
```

---

## 测试建议

### 1. 单元测试
- [ ] `GroupTransportManager.sendGroupMessage()` 各种结果场景
- [ ] 并发上传的成功/失败处理
- [ ] 群组消息去重逻辑
- [ ] Signal 加密序列化

### 2. 集成测试
- [ ] 3人群组发送接收
- [ ] 5人群组并发发送
- [ ] 消息去重验证（同一消息从多个成员获取）
- [ ] 部分成员失败处理

### 3. 性能测试
- [ ] 10人群组发送延迟
- [ ] 并发上传稳定性
- [ ] 去重缓存命中率
- [ ] 轮询资源消耗

---

## 已知限制

### 1. 重试机制
- 当前版本对失败成员的重试逻辑未实现
- 部分成功时暂时认为整体成功
- 需要在后续版本中完善重试逻辑

### 2. 附件支持
- 群组消息附件的序列化已实现
- 但附件的实际上传和下载需要进一步测试
- 大附件的处理可能需要优化

### 3. 消息顺序
- 多成员轮询可能导致消息乱序
- 依赖 Signal 的时间戳排序
- 需要在 UI 层确保正确显示顺序

---

## 下一步工作 (Phase 5)

### 1. 成员变动处理
- 新成员加入时的处理逻辑
- 成员离开时的清理逻辑
- Token 交换和通道建立

### 2. 状态同步
- 成员状态不一致检测
- 强制重新同步功能

### 3. 禁用和降级
- 主动禁用 v2 mode
- 异常情况下的自动降级
- Token 过期处理

---

## 相关文件

### 核心实现
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/polling/TapPollingService.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapSignalServiceAdapter.kt`
- `app/src/main/java/org/thoughtcrime/securesms/jobs/PushGroupSendJob.java`

### 数据模型
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupSendResult.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupMessageDeduplicator.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupV2Status.kt`

---

**实施完成**: 2025-10-04  
**版本**: Phase 4 MVP

