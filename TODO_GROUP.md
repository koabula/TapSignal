# 群组 V2 Mode 实现任务清单

## Phase 1: 数据结构和基础组件 ✅

### 1.1 数据库设计
- [x] 创建 `group_v2_status` 表定义
- [x] 在 `SignalDatabase.kt` 中添加群组 v2 表访问器
- [x] 创建 `GroupV2StatusTable.kt` 实现 CRUD 操作
- [x] 为 `transport_channels` 表添加 `group_id` 字段（通过 config_json 实现）
- [x] 为 `transport_tokens` 表添加 `group_id` 字段（通过 tokenData 实现）
- [x] 添加必要的索引

### 1.2 数据模型
- [x] 创建 `GroupV2Status` 枚举类
- [x] 创建 `GroupV2State` 数据类（包含状态、成员列表等）
- [x] 创建 `GroupSendResult` 密封类（Success/PartialSuccess/Failed）
- [x] 扩展 `TapTokenExchangeMessage` 添加群组相关的 REQUEST_TYPE

### 1.3 核心管理器框架
- [x] 创建 `GroupTransportManager` 类框架
- [x] 实现 `getGroupStatus()` 和 `updateGroupStatus()` 方法
- [x] 在 `TransportChannelManager` 中添加 `getGroupChannels()` 方法
- [x] 在 `TransportTokenPool` 中添加 `getGroupMemberTokens()` 方法
- [ ] 创建单元测试模板（待后续 Phase 8 完善）

## Phase 2: Token 管理和通道建立 ✅

### 2.1 群组 Token 生成
- [x] 实现 `GroupTransportManager.generateGroupTokens()` 
  - 为群组所有成员生成目录
  - 生成对应的只读 token
  - 批量保存到 `TransportTokenPool`
- [x] 实现 Token 的批量存储逻辑
- [x] 实现 Token 的批量查询接口
- [x] 添加 Token 验证和过期检查

### 2.2 群组通道管理
- [x] 实现 `GroupTransportManager.establishGroupChannels()`
  - 批量创建与群组成员的 channels
  - 支持并发创建
  - 错误处理和部分失败重试
- [x] 扩展 `TransportChannelManager` 支持群组通道查询
- [x] 实现群组通道状态批量更新
- [x] 添加通道健康检查

### 2.3 消息去重
- [x] 创建 `GroupMessageDeduplicator` 类
- [x] 实现基于消息 ID 的去重逻辑
- [x] 使用 LRU 缓存优化性能
- [x] 添加去重统计和日志

## Phase 3: 提议和激活流程 ✅

### 3.1 发起提议
- [x] 实现 `GroupTransportManager.proposeV2Mode()`
  - 检查 provider 配置
  - 生成群组 tokens
  - 构建 GROUP_OFFER 消息
  - 通过 Signal Server 广播
  - 更新本地状态为 PROPOSING
- [x] 实现 `GroupTransportManager.proposeV2ModeComplete()` - 完整流程
- [x] 创建 `GroupTokenExchangeHelper` 辅助类
- [ ] 在群组菜单中添加"Use v2 mode"选项（UI 待实现，参考 UI_INTEGRATION_GUIDE.md）
- [ ] 实现 UI 交互逻辑（UI 待实现）

### 3.2 接受提议
- [x] 实现 `TapMessageProcessor` 处理 GROUP_OFFER 消息
- [x] 显示提议通知 UI（类似 safety number 通知）
- [x] 实现 `GroupTransportManager.acceptV2Proposal()`
  - 保存发起人 token
  - 生成自己的 tokens
  - 构建 GROUP_ACCEPT 消息
  - 广播给所有成员
- [x] 实现接受/拒绝的 BroadcastReceiver (`GroupTokenExchangeReceiver`)

### 3.3 激活检查
- [x] 实现 `GroupTransportManager.checkAndActivateV2Mode()`
  - 检查 agreedMembers 是否等于 totalMembers
  - 批量建立 channels
  - 启动群组轮询
  - 更新状态为 FULL_V2_ACTIVE
- [x] 在收到 OFFER/ACCEPT 消息时触发激活检查
- [x] 插入"群组已启用 v2 mode"系统消息
- [ ] 更新 UI 显示 v2 mode 指示器（UI 待实现，参考 UI_INTEGRATION_GUIDE.md）

## Phase 4: 消息发送和接收 ✅

### 4.1 群组消息发送 ✅
- [x] 修改 `PushGroupSendJob.java` 添加 v2 mode 检查
- [x] 实现 `GroupTransportManager.sendGroupMessage()`
  - 获取所有成员 channels
  - 使用协程并发上传
  - 收集上传结果
  - 处理部分失败
- [ ] 实现失败成员的重试逻辑 (待 Phase 4.1 完善)
- [x] 添加发送进度和状态反馈

### 4.2 群组消息接收 ✅
- [x] 扩展 `TapPollingService` 支持群组轮询
- [x] 实现 `addGroupPollingTargets()` 方法
  - 为每个成员创建轮询任务
  - 合并到统一调度
- [x] 集成 `GroupMessageDeduplicator` 去重
- [x] 实现消息处理和存储逻辑
- [x] 添加轮询性能监控（复用现有机制）

### 4.3 消息路由优化 (部分完成)
- [x] 实现群组消息的批量下载（通过并发轮询）
- [x] 优化轮询调度算法（复用现有机制）
- [ ] 添加网络状态感知（WiFi 下更频繁轮询）(可选优化)
- [ ] 实现智能退避策略（复用现有机制）

## Phase 5: 成员变动处理 ✅

### 5.1 新成员加入 ✅
- [x] 在 PROPOSING 阶段加入 → 回退到 NATIVE
  - 实现回退逻辑
  - 清理 agreedMembers
  - 插入系统消息
- [x] 在 FULL_V2_ACTIVE 阶段加入
  - 检测新成员并显示提示
  - 实现新成员的 token 生成和交换
  - 老成员为新成员生成 token
  - 新成员建立 channels 和启动轮询
- [x] 实现 `requestJoinV2Mode()` - 新成员主动加入
- [x] 实现 `respondToNewMemberJoin()` - 老成员响应

### 5.2 成员离开 ✅
- [x] 实现 `GroupTransportManager.handleMemberLeave()`
  - 删除离开成员的 token
  - 关闭相关 channel
  - 移除轮询目标
- [ ] 集成到 Signal 群组成员变更流程 (待 Phase 7 UI 集成时完成)
- [x] 添加清理确认日志

### 5.3 状态同步 ✅
- [x] 实现成员状态不一致检测
- [x] 添加状态同步机制（定期检查）
- [x] 实现强制重新同步功能
- [x] 创建 `GroupMembershipSynchronizer` 类

## Phase 6: 禁用和降级 ✅

### 6.1 主动禁用 ✅
- [ ] 在群组菜单中添加"Disable v2 mode"选项（UI 待实现，参考 UI_INTEGRATION_GUIDE.md）
- [x] 实现 `GroupTransportManager.disableV2ModeComplete()`
  - 发送 GROUP_DISABLE 消息
  - 关闭所有 channels
  - 清理 tokens
  - 状态回退到 NATIVE
- [x] 实现 `GroupTransportManager.handleDisableV2ModeRequest()` - 处理接收到的禁用消息
- [x] 更新 `TapMessageProcessor.processGroupDisable()` - 使用完整的禁用处理流程
- [x] 实现资源清理方法 `cleanupGroupResources()`
- [x] 插入禁用系统消息

### 6.2 异常降级 ✅
- [x] 实现 `GroupTransportManager.degradeV2ModeOnError()` - 异常降级处理
- [x] 创建 `GroupV2HealthMonitor` - 健康监控器
- [x] 实现轮询连续失败监控和自动降级
- [x] 实现通道失败监控和自动降级
- [x] 实现 Token 过期检测
- [x] 插入降级原因的系统消息

## Phase 7: UI 和用户体验 

### 7.1 状态指示
- [ ] 群组对话顶部显示 v2 mode 指示器
- [ ] 显示当前状态（PROPOSING/FULL_V2_ACTIVE）
- [ ] 在 PROPOSING 状态显示同意人数（3/5 已同意）
- [ ] 添加状态图标和颜色

### 7.2 通知和提示
- [ ] 设计提议通知样式
- [ ] 实现通知点击跳转到详情
- [ ] 系统消息的统一样式
- [ ] 错误提示和操作指引

### 7.3 设置和管理
- [ ] 群组信息页面显示 v2 mode 状态
- [ ] 显示成员的同意状态列表
- [ ] 添加手动重新同步选项
- [ ] Provider 配置检查和提示

## Phase 8: 测试和优化 

### 8.1 单元测试
- [ ] `GroupTransportManager` 单元测试（80%+ 覆盖率）
- [ ] `GroupMessageDeduplicator` 测试
- [ ] Token 管理逻辑测试
- [ ] 状态转换测试

### 8.2 集成测试
- [ ] 3人群组完整流程测试
- [ ] 5人群组提议-激活-发送-接收测试
- [ ] 成员加入测试（PROPOSING 和 FULL_V2_ACTIVE）
- [ ] 成员离开测试
- [ ] 禁用流程测试
- [ ] 回退场景测试

### 8.3 性能测试和优化
- [ ] 10人群组消息发送延迟测试（目标<5秒）
- [ ] 轮询资源消耗测试（CPU、内存、网络）
- [ ] 并发上传稳定性测试
- [ ] 识别性能瓶颈并优化
- [ ] 添加性能监控埋点

### 8.4 边界情况测试
- [ ] 网络异常场景测试
- [ ] 部分成员离线测试
- [ ] Token 过期处理测试
- [ ] 状态不一致恢复测试
- [ ] 大量消息积压测试

## Phase 9: 文档和发布准备

### 9.1 代码文档
- [ ] 完善关键类的注释
- [ ] 添加复杂流程的流程图
- [ ] 更新 `ARCHITECTURE_OVERVIEW.md`
- [ ] 编写故障排查指南

### 9.2 用户文档
- [ ] 编写用户使用指南
- [ ] FAQ 文档
- [ ] 限制和注意事项说明

### 9.3 发布检查
- [ ] 代码审查
- [ ] Lint 检查和修复
- [ ] 安全性审查
- [ ] 性能 benchmark 确认
- [ ] 创建发布分支

## 里程碑

**M1: 数据和框架准备 (第1-2周)**
- 完成 Phase 1-2
- 可以生成群组 tokens 和建立 channels

**M2: 核心流程打通 (第3-4周)**
- 完成 Phase 3-4
- 实现提议-激活-发送-接收完整流程
- 3人小群组可用

**M3: 完整功能 (第5-6周)**
- 完成 Phase 5-6
- 支持成员变动
- 支持禁用和降级

**M4: 发布就绪 (第7-8周)**
- 完成 Phase 7-9
- 所有测试通过
- 文档完善

## 风险项

🔴 **高风险**
- [ ] 群组状态一致性保证机制
- [ ] 并发上传的稳定性和性能
- [ ] 复杂场景下的错误处理

🟡 **中风险**
- [ ] Token 批量刷新性能
- [ ] 大群组轮询资源消耗
- [ ] UI 交互的用户体验

🟢 **低风险**
- [ ] 数据库迁移
- [ ] 基础数据结构定义

## 资源需求

- **开发时间**：预计 7-8 周（单人全职）
- **测试环境**：多设备测试（至少3台设备）
- **COS 资源**：测试用 bucket 和账号

---

*任务清单版本：1.0*
*创建日期：2025-10-04*
*预计完成：2025-12-01*

