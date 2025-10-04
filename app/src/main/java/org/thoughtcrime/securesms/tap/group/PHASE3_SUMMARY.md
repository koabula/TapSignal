# Phase 3 实现总结：提议和激活流程

## 实施日期
2025-10-04

## 实施内容

### 3.1 发起提议功能

#### 核心实现

**GroupTransportManager.kt**
- ✅ 实现了 `proposeV2ModeComplete()` 方法，完整的提议流程包括：
  1. 检查 provider 配置
  2. 获取所有成员 ACI
  3. 为其他成员生成 tokens
  4. 保存 tokens 到池
  5. 更新群组状态为 PROPOSING
  6. 发送提议消息给所有成员
  7. 插入系统消息

**GroupTokenExchangeHelper.kt** (新建)
- ✅ 创建了群组 Token 交换的帮助类
- ✅ 实现 `sendGroupOfferMessage()` - 发送群组提议消息
- ✅ 实现 `sendGroupAcceptMessage()` - 发送群组接受消息
- ✅ 实现 `sendGroupActivateMessage()` - 发送群组激活消息（可选）
- ✅ 实现 `sendGroupDisableMessage()` - 发送群组禁用消息
- ✅ 实现 `insertSystemMessage()` - 插入系统消息
- ✅ 实现 `extractTokenForMember()` - 从 token 映射中提取指定成员的 token

### 3.2 接受提议功能

#### TapMessageProcessor.kt 扩展
- ✅ 添加了群组消息类型的处理分支：
  - `processGroupTokenOffer()` - 处理群组提议消息
  - `processGroupTokenAccept()` - 处理群组接受消息
  - `processGroupActivate()` - 处理群组激活通知
  - `processGroupDisable()` - 处理群组禁用消息

- ✅ 实现了辅助方法：
  - `checkAndActivateGroupV2Mode()` - 检查并激活群组 V2 模式
  - `activateGroupChannelsAndPolling()` - 激活群组通道并启动轮询
  - `startGroupPolling()` - 启动群组轮询
  - `showGroupTokenExchangeNotification()` - 显示群组 Token 交换通知

#### GroupTokenExchangeReceiver.kt (新建)
- ✅ 创建了群组 Token 交换请求接收器
- ✅ 实现 `handleGroupTokenAcceptance()` - 处理用户接受操作
- ✅ 实现 `handleGroupTokenRejection()` - 处理用户拒绝操作
- ✅ 实现 `saveProposerTokens()` - 保存提议者的 tokens
- ✅ 实现 `checkAndActivateIfReady()` - 检查并激活（如果所有成员都同意）
- ✅ 实现 `startGroupPolling()` - 启动群组轮询

### 3.3 激活检查功能

#### 自动激活机制
- ✅ 在收到 GROUP_ACCEPT 消息时自动检查是否全员同意
- ✅ 全员同意后自动执行：
  1. 建立群组通道
  2. 启动轮询
  3. 插入系统消息"群组已启用 v2 mode"

## 数据流程

### 提议流程（发起人 A）

```
用户点击"Use v2 mode"（UI 未实现）
    ↓
proposeV2ModeComplete()
    ↓
生成 tokens → 保存到池 → 更新状态 → 发送 GROUP_OFFER 消息
    ↓
插入系统消息："v2 mode 提议已发起"
```

### 接受流程（接收者 B）

```
收到 GROUP_OFFER 消息
    ↓
processGroupTokenOffer() → 显示通知
    ↓
用户点击"同意"
    ↓
GroupTokenExchangeReceiver.handleGroupTokenAcceptance()
    ↓
保存提议者 token → 生成自己的 tokens → 发送 GROUP_ACCEPT 消息
    ↓
检查是否全员同意 → checkAndActivateIfReady()
```

### 激活流程（所有成员）

```
收到 GROUP_ACCEPT 消息
    ↓
processGroupTokenAccept()
    ↓
保存发送者 token → 更新已同意成员列表
    ↓
checkAndActivateGroupV2Mode()
    ↓
全员同意？
    ↓ 是
建立通道 → 启动轮询 → 插入系统消息
    ↓
状态更新为 FULL_V2_ACTIVE
```

## 消息格式

### GROUP_OFFER 消息

```json
{
  "senderAci": "发起人ACI",
  "providerType": "cos",
  "tokenData": {},
  "metadata": {
    "groupId": "群组ID",
    "proposerAci": "发起人ACI",
    "tokens": {
      "成员ACI1": { "tokenId": "...", ... },
      "成员ACI2": { "tokenId": "...", ... }
    },
    "timestamp": 1234567890
  },
  "requestType": "GROUP_OFFER",
  "version": 1
}
```

### GROUP_ACCEPT 消息

```json
{
  "senderAci": "接受者ACI",
  "providerType": "cos",
  "tokenData": {},
  "metadata": {
    "groupId": "群组ID",
    "accepterAci": "接受者ACI",
    "proposerAci": "发起人ACI",
    "tokens": {
      "成员ACI1": { "tokenId": "...", ... },
      "成员ACI2": { "tokenId": "...", ... }
    },
    "timestamp": 1234567890
  },
  "requestType": "GROUP_ACCEPT",
  "version": 1
}
```

## 通知机制

### 群组提议通知
- 通知标题："群组 V2 Mode 提议"
- 通知内容："[发起人] 提议将群组升级到 v2 mode"
- 操作按钮：
  - "同意" → 触发 ACCEPT_GROUP_TOKEN_EXCHANGE
  - "拒绝" → 触发 REJECT_GROUP_TOKEN_EXCHANGE

### 系统消息
- "v2 mode 提议已发起"（发起人操作后）
- "你已同意使用 v2 mode"（成员同意后）
- "群组已启用 v2 mode"（全员同意后）

## 待完成事项

### Phase 3 剩余任务

1. **UI 集成**（phase3-5）
   - [ ] 在群组菜单中添加"Use v2 mode"选项
   - [ ] 在群组菜单中添加"Disable v2 mode"选项
   - [ ] 实现群组 v2 mode 状态指示器
   - [ ] 显示提议进度（如"3/5 已同意"）

2. **辅助方法完善**
   - [ ] 实现 `getGroupRecipientId()` - 从 groupId 转换为 RecipientId
   - [ ] 实现从 RecipientId 获取 groupId 的方法
   - [ ] 完善系统消息插入逻辑

3. **错误处理**
   - [ ] 添加网络错误处理
   - [ ] 添加 token 生成失败的恢复机制
   - [ ] 添加消息发送失败的重试逻辑

4. **测试**
   - [ ] 单元测试：token 生成和保存
   - [ ] 单元测试：消息处理逻辑
   - [ ] 集成测试：3人小群组完整流程
   - [ ] 集成测试：部分成员接受场景

## 技术要点

### 状态管理
- 使用 `GroupV2StatusTable` 管理群组状态
- 使用 `ReentrantReadWriteLock` 保证线程安全
- 状态转换：NATIVE → PROPOSING → FULL_V2_ACTIVE

### Token 管理
- 每个成员为群组所有其他成员生成独立的 token
- Token 存储在 `TransportTokenPool` 中
- 使用 `sharedTokens`（自己生成的）和 `receivedTokens`（收到的）分别管理

### 消息发送
- 使用 Signal 的 `IndividualSendJob` 发送数据消息
- 消息通过 Signal Server 传递（不经过 tap 层）
- 消息体是 JSON 编码的 `TapTokenExchangeMessage`

### 通道和轮询
- 激活时为每个成员建立独立通道
- 支持并发建立通道（使用 `supervisorScope`）
- 激活后自动启动对所有成员的轮询

## 与 Phase 1-2 的集成

### 使用的 Phase 1 组件
- `GroupV2Status` - 状态枚举
- `GroupV2State` - 状态数据类
- `GroupV2StatusTable` - 数据库表
- `GroupSendResult` - 发送结果类型

### 使用的 Phase 2 组件
- `GroupTransportManager.generateGroupTokens()` - 生成 tokens
- `GroupTransportManager.establishGroupChannels()` - 建立通道
- `GroupTransportManager.saveGroupTokensToPool()` - 保存 tokens
- `GroupMessageDeduplicator` - 消息去重（在轮询时使用）

## 代码结构

```
app/src/main/java/org/thoughtcrime/securesms/tap/group/
├── GroupTransportManager.kt          (扩展)
│   ├── proposeV2Mode()                (已有，基础版本)
│   ├── proposeV2ModeComplete()        (新增，完整流程)
│   ├── acceptV2Proposal()             (已有)
│   └── checkAndActivateV2Mode()       (已有)
├── GroupTokenExchangeHelper.kt        (新建)
│   ├── sendGroupOfferMessage()
│   ├── sendGroupAcceptMessage()
│   ├── sendGroupActivateMessage()
│   ├── sendGroupDisableMessage()
│   ├── insertSystemMessage()
│   └── extractTokenForMember()
└── GroupTokenExchangeReceiver.kt      (新建)
    ├── handleGroupTokenAcceptance()
    ├── handleGroupTokenRejection()
    ├── saveProposerTokens()
    ├── checkAndActivateIfReady()
    └── startGroupPolling()

app/src/main/java/org/thoughtcrime/securesms/tap/integration/
└── TapMessageProcessor.kt             (扩展)
    ├── processGroupTokenOffer()        (新增)
    ├── processGroupTokenAccept()       (新增)
    ├── processGroupActivate()          (新增)
    ├── processGroupDisable()           (新增)
    ├── checkAndActivateGroupV2Mode()   (新增)
    ├── activateGroupChannelsAndPolling() (新增)
    ├── startGroupPolling()             (新增)
    └── showGroupTokenExchangeNotification() (新增)
```

## 下一步：Phase 4

Phase 4 将实现群组消息的发送和接收：
1. 修改 `PushGroupSendJob` 检测 v2 mode
2. 实现 `GroupTransportManager.sendGroupMessage()`
3. 扩展 `TapPollingService` 支持群组轮询
4. 集成 `GroupMessageDeduplicator` 进行消息去重

---

**版本**: 1.0  
**最后更新**: 2025-10-04  
**状态**: Phase 3 核心功能已实现，等待 UI 集成和测试

