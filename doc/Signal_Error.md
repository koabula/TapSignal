Signal 消息类型和错误分析

## 消息类型

Signal 使用 64 位整数存储消息的类型和状态信息，通过位掩码（bitmask）技术在单个64位字段中存储多个维度的信息。

### 位掩码结构

```
|特殊类型| 加密信息 |安全消息|群组| 密钥交换 |消息属性|  基础类型  |
|  4位  |   8位   | 3位  | 4位|   8位   |  3位  |    5位    |
```

### 1. 基础类型 (BASE_TYPE_MASK = 0x1F, 5位)

**特殊事件类型:**
- `INCOMING_AUDIO_CALL_TYPE (1)`: 来电（语音）
- `OUTGOING_AUDIO_CALL_TYPE (2)`: 去电（语音）  
- `MISSED_AUDIO_CALL_TYPE (3)`: 未接语音来电
- `INCOMING_VIDEO_CALL_TYPE (10)`: 来电（视频）
- `OUTGOING_VIDEO_CALL_TYPE (11)`: 去电（视频）
- `MISSED_VIDEO_CALL_TYPE (8)`: 未接视频来电
- `GROUP_CALL_TYPE (12)`: 群组通话
- `JOINED_TYPE (4)`: 联系人加入Signal
- `UNSUPPORTED_MESSAGE_TYPE (5)`: 不支持的消息类型（协议版本过高）
- `INVALID_MESSAGE_TYPE (6)`: 无效消息
- `PROFILE_CHANGE_TYPE (7)`: 用户资料变更
- `GV1_MIGRATION_TYPE (9)`: 群组V1迁移到V2
- `BAD_DECRYPT_TYPE (13)`: **解密失败（可能导致重发）**
- `CHANGE_NUMBER_TYPE (14)`: 用户更换号码
- `RELEASE_CHANNEL_DONATION_REQUEST_TYPE (15)`: 发布频道捐赠请求
- `THREAD_MERGE_TYPE (16)`: 会话合并
- `SMS_EXPORT_TYPE (17)`: SMS导出
- `SESSION_SWITCHOVER_TYPE (18)`: 会话切换

**消息状态类型:**
- `BASE_INBOX_TYPE (21)`: 收件箱（已接收）
- `BASE_OUTBOX_TYPE (22)`: 发件箱（待发送）
- `BASE_SENDING_TYPE (23)`: 发送中
- `BASE_SENT_TYPE (24)`: 已发送
- `BASE_SENT_FAILED_TYPE (25)`: **发送失败（触发重发）**
- `BASE_PENDING_SECURE_SMS_FALLBACK (26)`: 等待加密SMS回退
- `BASE_PENDING_INSECURE_SMS_FALLBACK (27)`: 等待非加密SMS回退
- `BASE_DRAFT_TYPE (28)`: 草稿
- `BASE_SENDING_SKIPPED_TYPE (29)`: 跳过发送

### 2. 消息属性 (MESSAGE_ATTRIBUTE_MASK = 0xE0)

- `MESSAGE_RATE_LIMITED_BIT (0x80)`: 消息被限速
- `MESSAGE_FORCE_SMS_BIT (0x40)`: 强制使用SMS发送

### 3. 密钥交换信息 (KEY_EXCHANGE_MASK = 0xFF00)

- `KEY_EXCHANGE_BIT (0x8000)`: 密钥交换消息
- `KEY_EXCHANGE_IDENTITY_VERIFIED_BIT (0x4000)`: 身份已验证
- `KEY_EXCHANGE_IDENTITY_DEFAULT_BIT (0x2000)`: 身份重置为默认
- `KEY_EXCHANGE_IDENTITY_UPDATE_BIT (0x200)`: **身份更新（可能触发重发）**
- `KEY_EXCHANGE_INVALID_VERSION_BIT (0x800)`: 无效版本
- `KEY_EXCHANGE_BUNDLE_BIT (0x400)`: Bundle密钥交换

### 4. 安全消息信息

- `SECURE_MESSAGE_BIT (0x800000)`: 加密消息
- `END_SESSION_BIT (0x400000)`: 结束会话
- `PUSH_MESSAGE_BIT (0x200000)`: Push消息

### 5. 群组消息信息 (GROUP_MASK = 0xF0000)

- `GROUP_UPDATE_BIT (0x10000)`: 群组更新
- `GROUP_LEAVE_BIT (0x20000)`: 成员离开
- `EXPIRATION_TIMER_UPDATE_BIT (0x40000)`: 过期定时器更新
- `GROUP_V2_BIT (0x80000)`: GV2群组

### 6. 加密存储信息 (ENCRYPTION_MASK = 0xFF000000)

- `ENCRYPTION_REMOTE_BIT (0x20000000)`: 远程加密
- `ENCRYPTION_REMOTE_FAILED_BIT (0x10000000)`: **远程解密失败（触发会话刷新和重发）**
- `ENCRYPTION_REMOTE_NO_SESSION_BIT (0x08000000)`: **无会话（触发重发）**
- `ENCRYPTION_REMOTE_DUPLICATE_BIT (0x04000000)`: 重复消息
- `ENCRYPTION_REMOTE_LEGACY_BIT (0x02000000)`: 旧版消息

### 7. 特殊类型 (SPECIAL_TYPES_MASK = 0xF00000000L)

- `SPECIAL_TYPE_STORY_REACTION (0x100000000L)`: 故事回应
- `SPECIAL_TYPE_GIFT_BADGE (0x200000000L)`: 礼物徽章
- `SPECIAL_TYPE_PAYMENTS_NOTIFICATION (0x300000000L)`: 支付通知
- `SPECIAL_TYPE_PAYMENTS_ACTIVATE_REQUEST (0x400000000L)`: 激活支付请求
- `SPECIAL_TYPE_REPORTED_SPAM (0x500000000L)`: 举报垃圾信息
- `SPECIAL_TYPE_MESSAGE_REQUEST_ACCEPTED (0x600000000L)`: 消息请求已接受
- `SPECIAL_TYPE_PAYMENTS_ACTIVATED (0x800000000L)`: 支付已激活
- `SPECIAL_TYPE_PAYMENTS_TOMBSTONE (0x900000000L)`: 支付墓碑
- `SPECIAL_TYPE_BLOCKED (0xA00000000L)`: 已屏蔽
- `SPECIAL_TYPE_UNBLOCKED (0xB00000000L)`: 已解除屏蔽

---

## 所有错误消息类型

Signal会在会话中插入各种错误/警告消息，这些消息对用户可见，用于提示安全问题或通信故障。

### 1. 解密失败类错误

#### BAD_DECRYPT_TYPE (13)
- **含义**: 解密失败，无法读取消息内容
- **触发条件**: 
  - 收到的消息无法解密（密钥不匹配、会话损坏等）
  - ContentHint为DEFAULT或超过最大重试次数时
- **插入位置**: `MessageTable.insertBadDecryptMessage()`
- **用户可见**: 显示"无法解密此消息"
- **后续处理**: 
  - 如果启用重试收据且未超限，发送重试请求
  - 否则触发会话重置

#### 会话刷新消息 (ENCRYPTION_REMOTE_FAILED_BIT)
- **含义**: 聊天会话已刷新
- **触发条件**: 
  - `AutomaticSessionResetJob` 执行时
  - 表示旧会话已归档，建立了新会话
- **插入位置**: `MessageTable.insertChatSessionRefreshedMessage()`
- **类型标志**: `SECURE_MESSAGE_BIT | PUSH_MESSAGE_BIT | ENCRYPTION_REMOTE_FAILED_BIT`
- **用户可见**: 显示"聊天会话已刷新"
- **后续处理**: 发送空消息建立新会话

### 2. 协议版本不兼容类错误

#### UNSUPPORTED_MESSAGE_TYPE (5)
- **含义**: 不支持的消息类型（对方使用了更新的协议版本）
- **触发条件**: 
  - 消息的 `requiredProtocolVersion` 高于当前应用支持的版本
  - 对应 `MessageDecryptor.Result.UnsupportedDataMessage`
- **标记方法**: `MessageTable.markAsUnsupportedProtocolVersion()`
- **用户可见**: 提示"此消息需要更新Signal才能查看"
- **安全影响**: 提示用户更新应用

#### INVALID_MESSAGE_TYPE (6)
- **含义**: 无效的消息格式
- **触发条件**: 
  - 消息结构不符合协议规范
  - 消息验证失败
- **标记方法**: `MessageTable.markAsInvalidMessage()`
- **用户可见**: 显示错误消息
- **安全影响**: 可能是攻击尝试或网络传输错误

#### 旧版消息 (ENCRYPTION_REMOTE_LEGACY_BIT)
- **含义**: 使用已弃用的旧版加密格式
- **触发条件**: 
  - 收到2015年前的旧版Signal消息
  - 对应 `ProtocolLegacyMessageException`
- **标记方法**: `MessageTable.markAsLegacyVersion()`
- **用户可见**: 可能显示兼容性警告
- **安全影响**: 旧版加密强度较弱

### 3. 密钥交换相关错误

#### 无效密钥交换版本 (KEY_EXCHANGE_INVALID_VERSION_BIT)
- **含义**: 密钥交换协议版本不兼容
- **触发条件**: 
  - 收到的密钥交换消息使用不支持的版本
  - 对应 `ProtocolInvalidVersionException`
- **标记方法**: `MessageTable.markAsInvalidVersionKeyExchange()`
- **用户可见**: 显示密钥交换失败
- **安全影响**: 无法建立安全会话

### 4. 身份安全相关消息

这些不是"错误"，而是重要的安全通知消息。

#### 身份密钥更新 (KEY_EXCHANGE_IDENTITY_UPDATE_BIT)
- **含义**: 对方的安全号码（身份密钥）已变更
- **触发条件**: 
  - 对方重新安装Signal
  - 对方更换设备
  - 通过 `IdentityUtil.markIdentityUpdate()` 插入
- **插入方法**: `IncomingMessage.identityUpdate()`
- **用户可见**: 显示"[用户名]的安全号码已变更"
- **安全影响**: 
  - **关键安全警告**，可能是中间人攻击
  - 用户应验证新的安全号码
  - 删除该联系人的所有消息发送日志

#### 身份已验证 (KEY_EXCHANGE_IDENTITY_VERIFIED_BIT)
- **含义**: 用户已验证对方的安全号码
- **触发条件**: 
  - 用户扫描对方的安全号码二维码并确认匹配
  - 或接收到同步的验证状态
  - 通过 `IdentityUtil.markIdentityVerified(verified=true)` 插入
- **插入方法**: `IncomingMessage.identityVerified()` 或 `OutgoingMessage.identityVerifiedMessage()`
- **用户可见**: 显示"你已将[用户名]标记为已验证"
- **安全影响**: 表示已确认身份，通信更安全

#### 身份重置为默认 (KEY_EXCHANGE_IDENTITY_DEFAULT_BIT)
- **含义**: 将已验证的身份重置为未验证状态
- **触发条件**: 
  - 用户主动取消验证
  - 接收到同步的未验证状态
  - 通过 `IdentityUtil.markIdentityVerified(verified=false)` 插入
- **插入方法**: `IncomingMessage.identityDefault()` 或 `OutgoingMessage.identityDefaultMessage()`
- **用户可见**: 显示"你不再将[用户名]标记为已验证"
- **安全影响**: 降低信任级别

### 5. 其他系统通知类消息

虽然不是错误，但也会作为系统消息插入：

#### SESSION_SWITCHOVER_TYPE (18)
- **含义**: 会话切换事件
- **触发条件**: 特定的会话迁移场景
- **插入位置**: `MessageTable.insertSessionSwitchoverEvent()`

#### THREAD_MERGE_TYPE (16)
- **含义**: 线程合并事件
- **触发条件**: 多个会话合并为一个（例如通过电话号码和UUID识别为同一联系人）
- **插入位置**: `MessageTable.insertThreadMergeEvent()`

### 6. 错误消息的安全影响

从密码学安全分析角度：

1. **信息泄露**
   - 错误消息类型本身泄露了解密状态、协议版本、身份变更等信息
   - 攻击者可以通过观察错误消息类型推断系统状态

2. **重放攻击检测**
   - `ENCRYPTION_REMOTE_DUPLICATE_BIT` 可以被攻击者用来确认重放
   - 但同时也保护用户免受重放攻击

3. **身份更新消息**
   - `IDENTITY_UPDATE` 消息是关键的安全机制
   - 防止静默的中间人攻击
   - 但攻击者可能通过触发密钥更新来进行拒绝服务攻击

4. **协议降级检测**
   - `UNSUPPORTED_MESSAGE_TYPE` 和 `LEGACY_MESSAGE` 防止协议降级攻击
   - 确保始终使用最新、最安全的协议版本


---

## 导致消息重发的错误

Signal中有多种机制会触发消息的重发，这些机制从安全性角度提供了"加密预言机"的能力。

### 1. 解密错误触发的自动重发机制

**涉及异常类型:**
- `ProtocolInvalidKeyIdException`: 无效的密钥ID
- `ProtocolInvalidKeyException`: 无效的密钥
- `ProtocolUntrustedIdentityException`: 不受信任的身份
- `ProtocolNoSessionException`: 无会话存在
- `ProtocolInvalidMessageException`: 无效消息

**处理流程 (MessageDecryptor.kt):**

1. **重试收据机制 (Retry Receipt)**
   - 当收到解密失败的消息时，如果启用了重试收据功能（`RemoteConfig.retryReceipts`）
   - 根据ContentHint决定处理方式:
     - `ContentHint.DEFAULT`: 立即插入错误消息到会话中
     - `ContentHint.RESENDABLE`: 发送重试收据给发送方，请求重发
       - 将待重试收据插入 `PendingRetryReceiptCache`
       - 调度 `SendRetryReceiptJob` 发送 `DecryptionErrorMessage`
     - `ContentHint.IMPLICIT`: 静默忽略，不插入错误消息

2. **错误计数限制**
   - 每个发送者维护一个解密错误计数器
   - 如果错误数量超过 `RemoteConfig.retryReceiptMaxCount`，停止发送重试收据
   - 如果距离上次错误超过 `RemoteConfig.retryReceiptMaxCountResetAge`，重置计数器

3. **Prekey消息特殊处理**
   - 如果解密错误发生在prekey消息上，会强制轮换prekey后再请求重试
   - 调度 `PreKeysSyncJob(forceRotation=true)` 然后发送重试收据

**相关Job:**
- `SendRetryReceiptJob`: 向发送方发送解密错误消息，请求重发
- `ResendMessageJob`: 发送方收到重试收据后，用新的会话密钥重新发送消息

### 2. 会话重置机制

**触发条件:**
- 解密失败且重试收据机制被禁用
- `SendRetryReceiptJob` 失败（onFailure回调）
- 接收到自己发送的消息解密失败

**处理流程 (AutomaticSessionResetJob):**

1. **归档旧会话**
   - 调用 `ProtocolStore.sessions().archiveSessions(recipientId, deviceId)`
   - 删除该接收者的所有发送者密钥共享记录

2. **插入本地消息**
   - 在会话中插入"聊天会话已刷新"的系统消息
   - 消息类型: BAD_DECRYPT_TYPE 配合 ENCRYPTION_REMOTE_FAILED_BIT

3. **发送空消息 (Null Message)**
   - 如果启用了自动会话重置（`RemoteConfig.automaticSessionReset()`）
   - 检查距离上次重置的时间间隔（防止频繁重置）
   - 发送一个空消息以触发建立新会话
   - 记录本次重置时间

### 3. 发送失败后的自动重试

**重试Job发现机制 (RetryPendingSendsJob):**

- **触发时机**: 应用启动时
- **检测逻辑**: 
  - 扫描数据库中所有处于 `BASE_SENDING_TYPE` 或 `BASE_OUTBOX_TYPE` 状态的消息
  - 为每条待发送消息调度 `RetryPendingSendSecondCheckJob`

**二次检查机制 (RetryPendingSendSecondCheckJob):**

- 该Job被加入到对应线程的消息发送队列末尾
- 当队列排空后，如果消息仍处于pending状态，说明发送Job丢失
- 调用 `MessageSender.resend()` 重新发送消息

### 4. 用户手动重发

**触发方式:**
- 用户点击失败消息的重试按钮
- 通过 `ResendClickListener` 处理

**处理流程 (MessageSender.resend):**

1. **确定发送类型**
   - 如果是强制SMS: 根据是否有附件选择SMS或MMS
   - 否则: 使用Signal加密发送

2. **重新发送**
   - 从数据库读取原消息的所有内容（文本、附件等）
   - 创建新的发送Job（IndividualSendJob、PushGroupSendJob等）
   - 使用**新的密钥**重新加密和发送

**群组消息重发:**
- `MessageSender.resendGroupMessage()`: 可以选择性地只向特定成员重发
- 用于消息详情页面中针对特定失败接收者的重试

### 5. 重试收据响应的重发 (ResendMessageJob)

**触发条件:**
- 接收到对方发来的 `DecryptionErrorMessage` (重试收据)

**处理流程:**

1. **验证接收者状态**
   - 检查接收者是否仍在群组/分发列表中
   - 检查接收者是否已注销

2. **重新生成密钥材料**
   - 如果是群组消息，生成新的 `SenderKeyDistributionMessage`
   - 附加到消息内容中

3. **重新发送**
   - 使用原消息的 `sentTimestamp` 作为标识
   - 用新的会话密钥重新加密
   - 如果发生 `IllegalStateException`，归档所有会话并重试

### 6. 网络错误重试

**普通发送Job的重试:**
- 所有发送类Job（IndividualSendJob、PushGroupSendJob等）都继承自BaseJob
- 网络错误（`PushNetworkException`）会触发Job系统的自动重试
- 重试次数: `Parameters.UNLIMITED`（无限重试）
- 退避策略: 指数退避

**关键区别:**
- 网络错误重试**不会**重新生成密钥，只是重发相同的密文
- 解密错误/会话错误的重发**会**使用新密钥重新加密

### 7. 安全性影响总结

从密码学分析的角度，这些重发机制提供了以下"预言机"能力：

1. **解密预言机**
   - 攻击者可以通过观察是否收到重试收据，判断消息是否解密成功
   - 可以通过发送伪造/修改的密文，观察接收方的反应

2. **会话状态预言机**
   - 通过观察会话重置消息，可以推断会话状态
   - 可以判断某个设备是否有有效的会话密钥

3. **重放检测预言机**
   - 通过 `ENCRYPTION_REMOTE_DUPLICATE_BIT`，可以判断消息是否是重放

4. **密钥更新预言机**
   - 重发的消息使用新密钥加密
   - 攻击者可以触发多次重发，获得同一明文的多个密文（不同密钥）

5. **时序侧信道**
   - 重试的延迟可以泄露信息
   - 会话重置的频率可能泄露网络/设备状态

**注意:** Signal的安全性设计已经考虑了这些因素，但在形式化安全证明时需要将这些机制纳入模型中



---

ContentHint.DEFAULT (默认)
解密失败时：立即在会话中插入"无法解密"的错误消息
同时发送重试收据请求重发
用于：普通文本消息等重要内容
ContentHint.RESENDABLE (可重发)
解密失败时：不立即显示错误，先发送重试收据请求重发
只有重发也失败才显示错误
用于：群组消息等可以重新获取的内容
ContentHint.IMPLICIT (隐式)
解密失败时：静默忽略，不显示任何错误消息
不发送重试收据
用于：输入状态指示器、已读回执等非关键内容

---

解密错误计数器
目的：防止恶意攻击者通过不断发送无法解密的消息来消耗资源或进行拒绝服务攻击。
工作机制：
为每个发送者单独维护一个错误计数器
每次解密失败，计数器 +1
如果错误数超过阈值（RemoteConfig.retryReceiptMaxCount，通常是5次），停止发送重试收据
如果距离上次错误时间超过重置周期（RemoteConfig.retryReceiptMaxCountResetAge，通常是24小时），计数器归零