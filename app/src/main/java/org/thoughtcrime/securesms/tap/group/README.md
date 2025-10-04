# 群组 V2 Mode 模块

## 概述

本模块实现了 Signal tap 层的群组 V2 模式功能，支持群组成员之间通过 tap 层传输消息密文。

## 架构设计

### 核心组件

#### 1. 数据模型 (`GroupV2Status.kt`, `GroupV2State.kt`, `GroupSendResult.kt`)

- **GroupV2Status**: 群组状态枚举
  - `NATIVE`: 原生群聊，消息通过 Signal Server
  - `PROPOSING`: 提议阶段，部分成员同意
  - `FULL_V2_ACTIVE`: 全员激活，消息通过 tap 层

- **GroupV2State**: 群组状态数据类
  - 包含群组 ID、状态、发起人、成员列表等信息
  - 提供状态验证和转换方法

- **GroupSendResult**: 消息发送结果密封类
  - `Success`: 全部成功
  - `PartialSuccess`: 部分成功
  - `Failed`: 完全失败

#### 2. 数据库层 (`database/GroupV2StatusTable.kt`)

- 持久化存储群组 V2 状态
- 支持 CRUD 操作和状态查询
- 自动索引优化查询性能

#### 3. 管理器层 (`GroupTransportManager.kt`)

核心功能：
- `proposeV2Mode()`: 发起 V2 模式提议
- `acceptV2Proposal()`: 接受提议
- `checkAndActivateV2Mode()`: 检查并激活
- `sendGroupMessage()`: 发送群组消息（待实现）
- `disableV2Mode()`: 禁用 V2 模式

### 消息类型扩展

在 `TapTokenExchangeMessage` 中添加了群组相关的消息类型：

- `REQUEST_TYPE_GROUP_OFFER`: 群组提议
- `REQUEST_TYPE_GROUP_ACCEPT`: 接受提议
- `REQUEST_TYPE_GROUP_ACTIVATE`: 全员激活通知
- `REQUEST_TYPE_GROUP_DISABLE`: 禁用 v2 mode

## 状态流转

```
NATIVE ----[propose]----> PROPOSING ----[all accept]----> FULL_V2_ACTIVE
  ^                           |                                  |
  |                      [member change]                   [disable]
  +<--------------------------+-----------------------------------+
```

## 使用示例

```kotlin
val groupManager = GroupTransportManager.getInstance(context)

// 发起提议
groupManager.proposeV2Mode(
    groupId = "group123",
    proposerAci = "aci:alice",
    memberAcis = setOf("aci:alice", "aci:bob", "aci:charlie"),
    providerType = "cos"
)

// 接受提议
groupManager.acceptV2Proposal(
    groupId = "group123",
    memberAci = "aci:bob"
)

// 检查并激活
if (groupManager.checkAndActivateV2Mode("group123")) {
    // 群组已激活 V2 模式
}

// 获取状态
val status = groupManager.getGroupStatus("group123")
```

## 数据库结构

### group_v2_status 表

| 字段 | 类型 | 说明 |
|------|------|------|
| _id | INTEGER | 主键 |
| group_id | TEXT | 群组 ID（唯一） |
| status | TEXT | 当前状态 |
| proposer_aci | TEXT | 发起人 ACI |
| agreed_members | TEXT | 已同意成员（JSON） |
| total_members | TEXT | 全部成员（JSON） |
| provider_type | TEXT | Provider 类型 |
| created_at | INTEGER | 创建时间 |
| updated_at | INTEGER | 更新时间 |

### 索引

- `group_id`: 主要查询索引
- `status`: 按状态查询
- `proposer_aci`: 按发起人查询

## 扩展的功能

### TransportChannelManager

- `getGroupChannels(groupId)`: 获取群组所有通道

### TransportTokenPool

- `getGroupMemberTokens(groupId)`: 获取群组成员 Token 映射

## Phase 1 完成情况

✅ 数据库设计和表创建
✅ 数据模型定义
✅ 消息类型扩展
✅ 核心管理器框架
✅ 基础 CRUD 操作
✅ 状态管理和转换逻辑

## 后续 Phases

- **Phase 2**: Token 管理和通道建立
- **Phase 3**: 提议和激活流程
- **Phase 4**: 消息发送和接收
- **Phase 5**: 成员变动处理
- **Phase 6**: 禁用和降级
- **Phase 7**: UI 和用户体验
- **Phase 8**: 测试和优化
- **Phase 9**: 文档和发布准备

## 注意事项

1. 所有状态转换都需要验证合法性
2. 数据库操作使用事务保证一致性
3. 群组状态变更需要同步所有成员
4. Token 管理遵循安全最佳实践
5. 遵循 tap 模块的抽象层设计原则

## 相关文档

- [PLAN_GROUP.md](../../../../PLAN_GROUP.md): 完整实现计划
- [TODO_GROUP.md](../../../../TODO_GROUP.md): 任务清单
- [../ARCHITECTURE_OVERVIEW.md](../ARCHITECTURE_OVERVIEW.md): Tap 模块架构概述

