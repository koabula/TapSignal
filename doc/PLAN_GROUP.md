# 群组 V2 Mode 实现计划

## 1. 概述

将 tap 模块的 v2 mode 扩展到群组场景，支持群组成员之间通过 tap 层传输消息密文。

### 核心设计原则
- **全员共识**：只有全员同意后才启用 v2 mode，否则使用 Signal Server
- **对称结构**：每个成员为群组创建独立目录和 token
- **渐进升级**：从原生群聊逐步升级到 v2 mode
- **优雅降级**：成员变动或异常时可回退到 Signal Server

### 状态定义
```
NATIVE          → 原生群聊，通过 Signal Server
PROPOSING       → 有人提议升级，仍通过 Signal Server（包括部分同意）
FULL_V2_ACTIVE  → 全员同意，通过 tap 层传输
```

## 2. 架构设计

### 2.1 数据结构

**群组 V2 状态**
- groupId: String - 群组标识
- status: GroupV2Status - 当前状态
- proposerAci: String? - 发起人 ACI
- agreedMembers: Set<String> - 已同意成员 ACI 集合
- totalMembers: Set<String> - 全部成员 ACI 集合
- providerType: String - 使用的 provider 类型
- createdAt: Long
- updatedAt: Long

**群组成员 Token 映射**
- groupId + memberAci + providerType → TransportToken
- 每个成员维护 N-1 个 receivedTokens（用于轮询其他成员）
- 每个成员维护 1 个 sharedToken（供其他成员访问）

### 2.2 核心组件扩展

**GroupTransportManager** (新增)
```kotlin
class GroupTransportManager {
    // 群组状态管理
    fun getGroupStatus(groupId: String): GroupV2Status
    fun updateGroupStatus(groupId: String, status: GroupV2Status)
    
    // 提议和确认
    suspend fun proposeV2Mode(groupId: String): Boolean
    suspend fun acceptV2Proposal(groupId: String): Boolean
    suspend fun checkAndActivateV2Mode(groupId: String): Boolean
    
    // 消息发送（v2 mode下）
    suspend fun sendGroupMessage(groupId: String, message: TransportMessage): GroupSendResult
    
    // 成员管理
    suspend fun handleMemberJoin(groupId: String, newMemberAci: String)
    suspend fun handleMemberLeave(groupId: String, leftMemberAci: String)
}
```

**TransportChannelManager** (扩展)
- 支持批量创建群组成员通道
- 支持查询群组所有成员通道状态
- 新增方法：`getGroupChannels(groupId: String): List<TransportChannel>`

**TransportTokenPool** (扩展)
- 支持群组 token 的批量存储和查询
- 新增方法：`getGroupMemberTokens(groupId: String): Map<String, TransportToken>`

**TapPollingService** (扩展)
- 支持同时轮询群组多个成员的目录
- 群组消息去重（同一消息可能被多次获取）
- 新增方法：`addGroupPollingTargets(groupId: String, memberMetadatas: List<TransportMetadata>)`

### 2.3 消息类型扩展

**TapTokenExchangeMessage** 新增类型
```kotlin
companion object {
    const val REQUEST_TYPE_GROUP_OFFER = "GROUP_OFFER"      // 群组提议
    const val REQUEST_TYPE_GROUP_ACCEPT = "GROUP_ACCEPT"    // 接受提议
    const val REQUEST_TYPE_GROUP_ACTIVATE = "GROUP_ACTIVATE" // 全员激活通知
    const val REQUEST_TYPE_GROUP_DISABLE = "GROUP_DISABLE"  // 禁用v2 mode
}

// metadata 中包含
{
    "groupId": "...",
    "proposerAci": "...",
    "totalMembers": [...],
    "tokenData": {...}  // 发送者为该群组生成的 token
}
```

## 3. 核心流程

### 3.1 提议和建立 V2 Mode

**发起人 (A) 流程：**
1. 用户点击"Use v2 mode"菜单
2. 检查本地是否已配置 provider
3. 为群组的每个其他成员生成目录和只读 token
4. 构建 GROUP_OFFER 消息（包含 token 元数据）
5. 通过 Signal Server 广播给所有成员
6. 本地标记状态为 PROPOSING，记录自己为 agreedMembers

**接收者 (B,C,D...) 流程：**
1. 收到 GROUP_OFFER 消息
2. 显示提示："xxx 提议将群组升级到 v2 mode"
3. 如果同意：
   - 保存 A 的 token 到 receivedTokens
   - 为群组的每个其他成员生成目录和 token
   - 构建 GROUP_ACCEPT 消息（包含自己的 token）
   - 通过 Signal Server 广播给所有成员
   - 本地标记状态为 PROPOSING，记录自己为 agreedMembers

**激活检查（所有成员）：**
1. 每次收到 GROUP_OFFER 或 GROUP_ACCEPT 消息时：
   - 更新 agreedMembers 集合
   - 检查是否 agreedMembers == totalMembers
2. 如果全员同意：
   - 建立与所有其他成员的 TransportChannel
   - 升级通道状态为 FULL_ACTIVE
   - 启动群组轮询
   - 发送 GROUP_ACTIVATE 消息（可选，用于确认）
   - 本地状态更新为 FULL_V2_ACTIVE
   - 显示系统消息："群组已启用 v2 mode"

### 3.2 V2 Mode 下的消息发送

**发送流程（PushGroupSendJob 修改）：**
```kotlin
// 检查群组是否为 v2 mode
val groupV2Status = GroupTransportManager.getInstance(context).getGroupStatus(groupId)
if (groupV2Status == GroupV2Status.FULL_V2_ACTIVE) {
    // 使用 tap 发送
    val result = GroupTransportManager.getInstance(context)
        .sendGroupMessage(groupId, encryptedMessage)
    
    // 处理部分失败：记录失败成员，后续重试
    if (result is GroupSendResult.PartialSuccess) {
        scheduleRetryForFailedMembers(result.failedMembers)
    }
} else {
    // 使用 Signal Server（原有逻辑）
}
```

**GroupTransportManager.sendGroupMessage 实现：**
1. 获取群组所有成员的 channel
2. 为每个成员单独上传消息到其目录
3. 支持并发上传（使用 coroutineScope）
4. 收集结果，返回成功/失败统计

### 3.3 V2 Mode 下的消息接收

**轮询和处理：**
1. TapPollingService 为群组创建轮询任务
2. 轮询所有其他成员的目录（N-1个目录）
3. 下载新消息，通过 TapMessageProcessor 处理
4. **消息去重**：使用 TransportMessageDeduplicator
   - 基于消息的唯一标识（发送者ACI + 时间戳 + 序列号）
   - 避免从多个成员处获取同一条消息

### 3.4 新成员加入

**场景 1：PROPOSING 阶段**
- 新成员加入 → 群组回退到 NATIVE 状态
- 清理所有 agreedMembers 记录
- 显示系统消息："群组成员变动，v2 mode 提议已取消"

**场景 2：FULL_V2_ACTIVE 阶段**
1. Signal 通过 Server 完成 Sender Key 交换（标准流程）
2. 新成员收到系统提示："该群组使用 v2 mode，是否加入？"
3. 如果同意：
   - 新成员为群组所有老成员生成目录和 token
   - 发送 GROUP_ACCEPT 消息给所有老成员
   - 老成员收到后，为新成员生成 token 并回复
4. 在 token 交换完成前，新成员的消息通过 Signal Server 收发
5. token 交换完成后：
   - 新成员建立 channels 并启动轮询
   - 状态保持 FULL_V2_ACTIVE

### 3.5 成员离开

1. Signal 通过 Server 完成 Sender Key 协调（标准流程）
2. 其他成员执行清理：
   - 删除离开成员的 receivedToken
   - 关闭与该成员的 channel
   - 移除轮询目标
3. 状态保持 FULL_V2_ACTIVE（不影响其他成员）

### 3.6 禁用 V2 Mode

**主动禁用：**
1. 任意成员点击"Disable v2 mode"
2. 发送 GROUP_DISABLE 消息（通过 Signal Server）
3. 所有成员收到后：
   - 关闭所有群组相关 channels
   - 清理 tokens
   - 状态回退到 NATIVE
   - 显示系统消息："v2 mode 已禁用"

## 4. 数据库设计

**新增表：group_v2_status**
```sql
CREATE TABLE group_v2_status (
    group_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    proposer_aci TEXT,
    agreed_members TEXT NOT NULL,     -- JSON array
    total_members TEXT NOT NULL,      -- JSON array
    provider_type TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

**扩展表：transport_channels**
- 新增字段：`group_id TEXT` (可为空，群组通道时使用)
- 新增索引：`CREATE INDEX idx_channels_group ON transport_channels(group_id)`

**扩展表：transport_tokens**
- 新增字段：`group_id TEXT` (可为空)
- token 查询时需考虑 groupId 维度

## 5. UI 变更

**群组对话界面**
- 右上角菜单新增："Use v2 mode"（仅在 NATIVE 状态显示）
- 右上角菜单新增："Disable v2 mode"（仅在 FULL_V2_ACTIVE 状态显示）
- 顶部显示 v2 mode 指示器（与私聊类似）

**提议通知**
- 显示方式：类似 safety number 变更通知
- 内容："[发起人] 提议将群组升级到 v2 mode，点击查看详情"
- 操作按钮："同意" / "拒绝"

**系统消息**
- "v2 mode 提议已发起"
- "群组已启用 v2 mode"
- "v2 mode 已禁用"
- "成员变动，v2 mode 提议已取消"

## 6. 性能优化

**并发上传**
```kotlin
// GroupTransportManager 中使用协程并发
coroutineScope {
    channels.map { channel ->
        async {
            provider.push(message, channel.metadata)
        }
    }.awaitAll()
}
```

**批量轮询**
- 将群组成员的轮询任务合并到一个调度周期
- 使用线程池避免同时创建过多轮询任务

**消息去重优化**
- 使用 LRU 缓存记录最近处理的消息 ID
- 避免重复解密和处理

## 7. 错误处理和边界情况

**部分发送失败**
- 记录失败成员列表
- 安排重试任务（IndividualSendJob）
- 超过重试次数后提示用户

**轮询失败**
- 单个成员轮询失败不影响其他成员
- 记录失败次数，超过阈值后暂停该成员轮询
- 用户可手动重新同步

**Token 过期**
- 群组 token 过期前自动刷新
- 刷新失败时提示用户重新建立 v2 mode

**网络分区**
- 部分成员网络异常时，其他成员继续使用 v2 mode
- 恢复后自动同步消息

## 8. 测试计划

**单元测试**
- GroupTransportManager 各方法测试
- 状态转换逻辑测试
- Token 管理和查询测试

**集成测试**
- 3人小群组完整流程测试
- 成员加入/离开测试
- 提议取消和禁用测试

**性能测试**
- 10人群组消息发送延迟测试
- 轮询性能和资源消耗测试
- 并发发送稳定性测试

## 9. 风险和注意事项

**复杂度风险**
- 群组状态管理比一对一复杂得多
- 需要完善的日志和调试工具

**一致性风险**
- 成员可能对群组状态有不同理解
- 需要通过 Signal Server 的控制消息保证最终一致性

**性能风险**
- 大群组（>20人）可能有性能问题
- 建议初期限制群组规模（如≤10人）

**用户体验风险**
- 提议流程可能繁琐
- 部分成员不同意会影响体验
- 需要清晰的状态提示

## 10. 迭代计划

**Phase 1: 基础功能（MVP）**
- 3-5人小群组支持
- 基本的提议-同意-激活流程
- 简单的发送和接收

**Phase 2: 完善功能**
- 成员变动处理
- 错误处理和重试
- 性能优化

**Phase 3: 增强体验**
- 更大群组支持（10-20人）
- 批量操作优化
- 更好的状态提示

---

*文档版本：1.0*
*最后更新：2025-10-04*

