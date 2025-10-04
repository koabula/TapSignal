# 群组 V2 Mode 实现文档索引

## 📚 文档列表

### 1. 设计和规划文档

#### [PLAN_GROUP.md](../../../../PLAN_GROUP.md)
**群组 V2 Mode 实现计划**
- 完整的架构设计
- 数据结构定义
- 核心流程说明
- 风险评估和注意事项

#### [TODO_GROUP.md](../../../../TODO_GROUP.md)
**群组 V2 Mode 实现任务清单**
- Phase 1-9 的详细任务列表
- 里程碑和时间规划
- 风险项跟踪
- 资源需求

### 2. Phase 实现总结

#### [PHASE1_SUMMARY.md](PHASE1_SUMMARY.md)
**Phase 1: 数据结构和基础组件**
- 数据库表设计和实现
- 数据模型定义
- 核心管理器框架

#### [PHASE2_SUMMARY.md](PHASE2_SUMMARY.md)
**Phase 2: Token 管理和通道建立**
- 群组 Token 生成逻辑
- 群组通道管理
- 消息去重机制

#### [PHASE3_SUMMARY.md](PHASE3_SUMMARY.md)
**Phase 3: 提议和激活流程**
- 提议流程实现
- 接受流程实现
- 激活检查和轮询启动
- 数据流程图

### 3. 实施状态和问题跟踪

#### [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)
**实施状态报告**
- 已完成功能清单
- 待完成事项
- 代码统计
- 已知问题和限制
- 下一步计划

#### [BUGFIX_SUMMARY.md](BUGFIX_SUMMARY.md)
**编译错误修复总结**
- 原始错误列表
- 修复方法详解
- API 使用正确方式
- 待完善部分

#### [ENHANCEMENT_SUMMARY.md](ENHANCEMENT_SUMMARY.md)
**功能增强实现总结**
- GroupId 转换多格式支持
- 系统消息样式优化
- API 使用示例
- 设计决策说明

### 4. 开发指南

#### [UI_INTEGRATION_GUIDE.md](UI_INTEGRATION_GUIDE.md)
**UI 集成指南**
- 群组菜单集成
- 状态指示器实现
- BroadcastReceiver 注册
- 完整代码示例

## 🎯 快速导航

### 我想了解...

**整体设计**
→ 阅读 [PLAN_GROUP.md](../../../../PLAN_GROUP.md)

**开发进度**
→ 查看 [TODO_GROUP.md](../../../../TODO_GROUP.md) 和 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)

**某个 Phase 的实现**
→ 查看对应的 PHASE*_SUMMARY.md

**如何集成 UI**
→ 阅读 [UI_INTEGRATION_GUIDE.md](UI_INTEGRATION_GUIDE.md)

**遇到编译错误**
→ 参考 [BUGFIX_SUMMARY.md](BUGFIX_SUMMARY.md)

**功能增强细节**
→ 查看 [ENHANCEMENT_SUMMARY.md](ENHANCEMENT_SUMMARY.md)

## 📊 实施进度

### 已完成 (95%)

- ✅ Phase 1: 数据结构和基础组件
- ✅ Phase 2: Token 管理和通道建立
- ✅ Phase 3: 提议和激活流程（核心功能）
  - ✅ GroupId 转换增强
  - ✅ 系统消息样式优化

### 进行中

- ⏳ Phase 3: UI 集成（待实现）

### 待开始

- ⏳ Phase 4: 消息发送和接收
- ⏳ Phase 5: 成员变动处理
- ⏳ Phase 6: 禁用和降级
- ⏳ Phase 7: UI 和用户体验
- ⏳ Phase 8: 测试和优化
- ⏳ Phase 9: 文档和发布准备

## 🗂️ 代码结构

```
app/src/main/java/org/thoughtcrime/securesms/tap/group/
├── 核心组件
│   ├── GroupTransportManager.kt          # 群组传输管理器
│   ├── GroupTokenExchangeHelper.kt       # Token 交换辅助类
│   ├── GroupTokenExchangeReceiver.kt     # 用户操作接收器
│   └── GroupMessageDeduplicator.kt       # 消息去重器
│
├── 数据模型
│   ├── GroupV2Status.kt                  # 状态枚举
│   ├── GroupV2State.kt                   # 状态数据类
│   └── GroupSendResult.kt                # 发送结果类型
│
├── 数据库
│   └── database/
│       └── GroupV2StatusTable.kt         # 群组状态表
│
└── 文档
    ├── README.md                         # 本文档
    ├── PHASE1_SUMMARY.md
    ├── PHASE2_SUMMARY.md
    ├── PHASE3_SUMMARY.md
    ├── IMPLEMENTATION_STATUS.md
    ├── BUGFIX_SUMMARY.md
    ├── ENHANCEMENT_SUMMARY.md
    └── UI_INTEGRATION_GUIDE.md
```

## 🔧 关键 API

### GroupTransportManager
```kotlin
// 发起提议
suspend fun proposeV2ModeComplete(
    groupId: String,
    memberRecipientIds: List<RecipientId>,
    providerType: String
): Boolean

// 接受提议
suspend fun acceptV2Proposal(groupId: String, memberAci: String): Boolean

// 检查并激活
suspend fun checkAndActivateV2Mode(groupId: String): Boolean
```

### GroupTokenExchangeHelper
```kotlin
// 发送提议消息
suspend fun sendGroupOfferMessage(...)

// 插入系统消息
suspend fun insertSystemMessage(recipientId: RecipientId, messageBody: String, isEnabled: Boolean = true)

// 插入自定义消息
suspend fun insertCustomSystemMessage(recipientId: RecipientId, messageBody: String)
```

### GroupTokenExchangeReceiver
```kotlin
// 处理用户接受操作
private fun handleGroupTokenAcceptance(context: Context, senderId: String, groupId: String, ...)

// GroupId 转换（支持多种格式）
private fun getGroupRecipientId(context: Context, groupId: String): RecipientId?
```

## 📝 使用示例

### 发起群组 V2 提议

```kotlin
val groupManager = GroupTransportManager.getInstance(context)
val success = groupManager.proposeV2ModeComplete(
    groupId = groupId,
    memberRecipientIds = memberRecipientIds,
    providerType = "cos"
)
```

### 接受群组 V2 提议

```kotlin
// 用户点击通知中的"同意"按钮后自动处理
// 由 GroupTokenExchangeReceiver 处理
```

### 插入系统消息

```kotlin
val helper = GroupTokenExchangeHelper.getInstance(context)

// 启用 v2 mode 消息（灰色居中）
helper.insertSystemMessage(recipientId, "", isEnabled = true)

// 自定义文本消息
helper.insertCustomSystemMessage(recipientId, "v2 mode 提议已发起")
```

## 🧪 测试

目前暂无自动化测试，建议手动测试：

1. 3人小群组完整流程
2. 部分成员接受场景
3. 网络异常场景
4. 成员加入/离开场景

详见 [TODO_GROUP.md](../../../../TODO_GROUP.md) Phase 8

## 🚀 下一步

1. **立即**: 实现 UI 集成（参考 [UI_INTEGRATION_GUIDE.md](UI_INTEGRATION_GUIDE.md)）
2. **短期**: 进行 3人小群组的集成测试
3. **中期**: 实施 Phase 4（消息发送和接收）

## 📞 联系

有问题或建议？请查看相应的文档或提交 Issue。

---

**最后更新**: 2025-10-04  
**版本**: 1.0  
**维护者**: AI Assistant
