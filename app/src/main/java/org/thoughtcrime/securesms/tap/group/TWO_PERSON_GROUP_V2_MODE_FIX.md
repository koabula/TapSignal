# 2人群组 V2 Mode 修复

## 问题描述

**现象**：2人群组启用 v2 mode 后，消息仍然通过 Signal Server 发送，而不是通过 TAP 传输。

**根本原因**：Signal 对于 2人群组使用 **Legacy（1对1）发送路径**，而不是 Sender Key 路径。

---

## 问题分析

### Signal 原生群组发送逻辑

在 `GroupSendUtil.java` 中，Signal 强制要求**至少 2 个 Sender Key 目标**才使用 Sender Key：

```java
// GroupSendUtil.java 第 339-348 行
// Enforce minimum number of sender key destinations
if (SignalStore.internal().getRemoveSenderKeyMinimum()) {
    Log.i(TAG, "Sender key minimum removed. Using for " + senderKeyTargets.size() + " recipients.");
} else if (senderKeyTargets.size() < 2 && !isStorySend) {
    Log.i(TAG, "Too few sender-key-capable users (" + senderKeyTargets.size() + ") for non-story send. Doing all legacy sends.");
    legacyTargets.addAll(senderKeyTargets);  // ← 2人群组被移到 legacy！
    senderKeyTargets.clear();
} else {
    Log.i(TAG, "Can use sender key for " + senderKeyTargets.size() + "/" + allTargets.size() + " recipients.");
}
```

**为什么 2人群组只有 1 个目标？**
- 群组成员：我 + 对方
- 发送目标：只有对方（不包括自己）
- 所以 `senderKeyTargets.size() = 1`
- 触发条件：`< 2` → 移到 `legacyTargets`

### 发送路径对比

| 群组大小 | 发送路径 | 加密方式 | 方法调用 | TAP 拦截 |
|---------|---------|---------|---------|---------|
| **3人以上** | Sender Key | GroupCipher | `sendGroupMessage()` | ✅ 有（在 `sendGroupMessage()` 中） |
| **2人** | Legacy | SessionCipher | `sendDataMessage()` → `sendMessage()` | ❌ **无** |

### 2人群组的完整路径

```
PushGroupSendJob
  → GroupSendUtil.sendMessage()
      → 判断：senderKeyTargets.size() < 2  ← 只有1个目标（对方）
      → 移到 legacyTargets
      → sendOperation.sendLegacy()
          → messageSender.sendDataMessage()  ← 1对1发送！
              → sendContent()
                  → sendMessage(SignalServiceAddress, ...)  ← 单收件人
                      → getEncryptedMessages()
                      → messageApi.sendMessage()  ← 发送到 Signal Server ❌
```

**关键问题**：
- `sendGroupMessage()` 有 TAP 拦截 ✅
- `sendMessage()` **没有 TAP 拦截** ❌
- 2人群组走的是 `sendMessage()` ❌

---

## 修复方案

### 方案：在 `sendMessage()` 中添加 TAP 拦截

在 `SignalServiceMessageSender.sendMessage()` 中添加类似的 TAP 拦截逻辑。

---

## 实现细节

### 1. 在 `sendMessage()` 中添加拦截

**文件**：`libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

```java
private SendMessageResult sendMessage(SignalServiceAddress recipient, ...) {
    // ...加密完成...
    OutgoingPushMessageList messages = getEncryptedMessages(recipient, ...);
    
    sendEvents.onMessageEncrypted();
    
    // ✅ TAP 拦截：如果收件人处于 v2 mode（包括私聊和2人群组），通过 TAP 传输
    if (tapTransport != null && tapTransport.shouldUseTapForRecipient(recipient)) {
        Log.i(TAG, "Recipient is in v2 mode, sending via TAP transport.");
        try {
            // 从 messages 中提取主设备的密文
            byte[] ciphertext = extractPrimaryCiphertext(messages);
            if (ciphertext != null) {
                return tapTransport.sendMessageViaTap(recipient, ciphertext, timestamp, urgent, online);
            }
        } catch (IOException e) {
            Log.w(TAG, "TAP transport failed, falling back to Signal Server.");
        }
    }
    
    // 否则，通过 Signal Server 发送（原有逻辑）
    SendMessageResponse response = messageApi.sendMessage(messages, ...);
    // ...
}
```

### 2. 提取密文的辅助方法

```java
/**
 * 从 OutgoingPushMessageList 中提取主设备的密文（用于 TAP 传输）
 */
private byte[] extractPrimaryCiphertext(OutgoingPushMessageList messages) {
    if (messages == null || messages.getMessages() == null || messages.getMessages().isEmpty()) {
        return null;
    }

    // 优先查找主设备（deviceId = 1）的消息
    for (OutgoingPushMessage message : messages.getMessages()) {
        if (message.getDestinationDeviceId() == SignalServiceAddress.DEFAULT_DEVICE_ID) {
            try {
                return Base64.decode(message.content);
            } catch (Exception e) {
                Log.w(TAG, "Failed to decode Base64 content", e);
                return null;
            }
        }
    }

    // 如果没有找到主设备，使用第一个设备的消息（作为回退）
    try {
        return Base64.decode(messages.getMessages().get(0).content);
    } catch (Exception e) {
        return null;
    }
}
```

### 3. 实现 `shouldUseTapForRecipient()`

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageTransportImpl.kt`

```kotlin
override fun shouldUseTapForRecipient(recipient: SignalServiceAddress): Boolean {
    try {
        val recipientId = RecipientId.from(recipient.serviceId)
        val recipientObj = Recipient.resolved(recipientId)
        
        // 1. 检查是否为私聊的 v2 mode
        val channelManager = TransportChannelManager.getInstance(context)
        val hasPrivateChannel = channelManager.hasActiveReceiveChannel(recipient.identifier)
        
        if (hasPrivateChannel) {
            Log.d(TAG, "私聊 v2 mode")
            return true
        }
        
        // 2. 检查是否为 2人群组的 v2 mode
        val groupTransportManager = GroupTransportManager.getInstance(context)
        val groups = SignalDatabase.groups().getGroupsContainingMember(recipientObj.id, false)
        
        for (group in groups) {
            val groupIdString = Base64.encodeToString(group.id.decodedId, Base64.NO_WRAP)
            val groupStatus = groupTransportManager.getGroupStatusSync(groupIdString)
            
            if (groupStatus == GroupV2Status.FULL_V2_ACTIVE) {
                val members = SignalDatabase.groups().getGroupMembers(
                    group.id, 
                    GroupTable.MemberSet.FULL_MEMBERS_INCLUDING_SELF
                )
                if (members.size == 2) {
                    Log.d(TAG, "2人群组 v2 mode, groupId=$groupIdString")
                    return true
                }
            }
        }
        
        return false
        
    } catch (e: Exception) {
        Log.w(TAG, "Error checking TAP status", e)
        return false
    }
}
```

**逻辑**：
1. 检查是否为私聊 v2 mode（通过 `TransportChannelManager`）
2. 检查是否为 2人群组 v2 mode：
   - 遍历收件人参与的所有群组
   - 检查群组是否为 `FULL_V2_ACTIVE`
   - 检查群组成员数是否为 2
3. 如果符合任一条件，返回 `true`

### 4. 实现 `sendMessageViaTap()`

```kotlin
override fun sendMessageViaTap(
    recipient: SignalServiceAddress,
    ciphertext: ByteArray,
    timestamp: Long,
    urgent: Boolean,
    online: Boolean
): SendMessageResult {
    val recipientId = RecipientId.from(recipient.serviceId)
    val recipientObj = Recipient.resolved(recipientId)
    val messageId = System.currentTimeMillis().toString()
    
    // 1. 判断是私聊还是2人群组
    val channelManager = TransportChannelManager.getInstance(context)
    val hasPrivateChannel = channelManager.hasActiveReceiveChannel(recipient.identifier)
    
    if (hasPrivateChannel) {
        // 私聊 v2 mode - 通过 TransportManager 发送
        Log.i(TAG, "发送私聊消息")
        
        val transportManager = TransportManager.getInstance(context)
        val result = runBlocking {
            transportManager.sendMessage(
                recipientId = recipient.identifier,
                ciphertext = ciphertext,
                messageId = messageId
            )
        }
        
        return when (result) {
            is TransportResult.Success -> SendMessageResult.success(...)
            is TransportResult.Failed -> throw IOException("TAP failed: ${result.errorMessage}")
            else -> throw IOException("Unexpected result")
        }
    }
    
    // 2. 检查是否为 2人群组
    val groupTransportManager = GroupTransportManager.getInstance(context)
    val groups = SignalDatabase.groups().getGroupsContainingMember(recipientObj.id, false)
    
    for (group in groups) {
        val groupIdString = Base64.encodeToString(group.id.decodedId, Base64.NO_WRAP)
        val groupStatus = groupTransportManager.getGroupStatusSync(groupIdString)
        
        if (groupStatus == GroupV2Status.FULL_V2_ACTIVE) {
            val members = SignalDatabase.groups().getGroupMembers(group.id, ...)
            if (members.size == 2) {
                // 2人群组 v2 mode - 通过 GroupTransportManager 发送
                Log.i(TAG, "发送2人群组消息, groupId=$groupIdString")
                
                val result = runBlocking {
                    groupTransportManager.sendGroupMessage(
                        groupId = groupIdString,
                        encryptedMessage = ciphertext,
                        messageId = messageId
                    )
                }
                
                return when (result) {
                    is GroupSendResult.Success -> SendMessageResult.success(...)
                    is GroupSendResult.Failed -> throw IOException("TAP failed: ${result.reason}")
                    is GroupSendResult.PartialSuccess -> SendMessageResult.success(...)
                }
            }
        }
    }
    
    throw IOException("Unable to determine message type for TAP transport")
}
```

---

## 关键点

### 1. OutgoingPushMessageList 结构

```
OutgoingPushMessageList
  ├─ destination (收件人)
  ├─ timestamp
  ├─ messages: List<OutgoingPushMessage>  ← 每个设备一条
  │   ├─ type (密文类型)
  │   ├─ destinationDeviceId
  │   ├─ destinationRegistrationId
  │   └─ content (Base64 编码的密文)
  ├─ online
  └─ urgent
```

**提取策略**：
- 优先提取主设备（`deviceId = 1`）的密文
- 如果没有主设备，使用第一个设备的密文
- Base64 解码后传递给 TAP

### 2. 2人群组识别逻辑

1. 从收件人地址出发
2. 查找该收件人参与的所有群组
3. 检查群组状态是否为 `FULL_V2_ACTIVE`
4. 检查群组成员数是否为 2（包括自己）
5. 如果符合，说明是 2人群组 v2 mode

### 3. 发送路径选择

- **私聊 v2 mode**：通过 `TransportManager.sendMessage()`
- **2人群组 v2 mode**：通过 `GroupTransportManager.sendGroupMessage()`
- 自动判断，无需手动区分

---

## 测试验证

### 测试场景

1. **2人群组 v2 mode**：
   - 创建 2人群组
   - 启用 v2 mode
   - 发送消息
   - 验证：应该通过 TAP 传输（检查日志）

2. **私聊 v2 mode**（已有）：
   - 1对1 聊天
   - 启用 v2 mode
   - 发送消息
   - 验证：应该通过 TAP 传输

3. **3人以上群组 v2 mode**（已有）：
   - 创建 3+ 人群组
   - 启用 v2 mode
   - 发送消息
   - 验证：应该通过 TAP 传输（Sender Key 路径）

### 预期日志

**2人群组发送**：
```
[sendMessage] Recipient is in v2 mode, sending via TAP transport.
TapMessageTransportImpl: shouldUseTapForRecipient: 2人群组 v2 mode, groupId=...
TapMessageTransportImpl: sendMessageViaTap: 发送2人群组消息
GroupTransportManager: sendGroupMessage: ...
TAP 2人群组消息发送成功
```

---

## 总结

### 修复前

- ✅ 3人以上群组：Sender Key → TAP ✅
- ❌ 2人群组：Legacy → Signal Server ❌
- ✅ 私聊：Session Cipher → TAP ✅（已有）

### 修复后

- ✅ 3人以上群组：Sender Key → TAP ✅
- ✅ **2人群组：Legacy → TAP** ✅（新增）
- ✅ 私聊：Session Cipher → TAP ✅

### 架构完整性

现在 TAP 拦截覆盖了所有发送路径：

1. **Sender Key 路径**（`sendGroupMessage()`）：✅ 已拦截
2. **Legacy 路径**（`sendMessage()`）：✅ **新增拦截**

无论 Signal 使用什么加密方式、什么发送路径，只要是 v2 mode，都会被 TAP 拦截！

**这才是真正的传输层拦截！**
