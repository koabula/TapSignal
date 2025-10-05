# Phase 6: 禁用和降级功能实现报告

## 实现概览

Phase 6 完成了群组 V2 模式的禁用和异常降级功能，确保在各种情况下系统能够安全地回退到原生模式。

## 核心功能

### 1. 主动禁用 V2 模式

#### 1.1 完整禁用流程 (`disableV2ModeComplete()`)

**文件**: `GroupTransportManager.kt` (第 1412-1520 行)

**功能说明**:
- 主动禁用场景的完整处理流程
- 包括资源清理、消息发送、状态重置

**流程步骤**:
1. 获取群组当前状态
2. 获取我的 ACI 和其他成员列表
3. 调用 `cleanupGroupResources()` 清理所有资源
4. 发送 GROUP_DISABLE 消息给所有成员（可选）
5. 重置群组状态为 NATIVE
6. 插入系统消息通知用户（可选）

**参数**:
- `groupId`: 群组 ID
- `sendControlMessage`: 是否发送禁用控制消息（默认 true）
- `insertSystemMessage`: 是否插入系统消息（默认 true）
- `reason`: 禁用原因（可选，用于日志）

**返回值**: `GroupOperationResult<Unit>`

**使用示例**:
```kotlin
val groupManager = GroupTransportManager.getInstance(context)
val result = groupManager.disableV2ModeComplete(
    groupId = groupId,
    sendControlMessage = true,
    insertSystemMessage = true,
    reason = "用户主动禁用"
)

when (result) {
    is GroupOperationResult.Success -> {
        // 禁用成功
    }
    is GroupOperationResult.Failed -> {
        // 禁用失败
        Log.e(TAG, "禁用失败: ${result.message}")
    }
}
```

#### 1.2 资源清理 (`cleanupGroupResources()`)

**文件**: `GroupTransportManager.kt` (第 1522-1583 行)

**功能说明**:
- 清理群组 V2 模式相关的所有资源
- 包括轮询、通道、tokens

**清理内容**:
1. **轮询目标**: 移除所有成员的轮询任务
2. **通道**: 关闭与所有成员的传输通道
3. **Tokens**: 删除所有成员的 receivedToken 和 sharedToken

**统计信息**:
- 记录每类资源的清理成功数量
- 输出详细的清理日志

#### 1.3 处理禁用请求 (`handleDisableV2ModeRequest()`)

**文件**: `GroupTransportManager.kt` (第 1585-1662 行)

**功能说明**:
- 处理接收到其他成员发送的禁用消息
- 验证发送者身份
- 执行本地禁用（不发送控制消息，避免回声）

**安全检查**:
1. 验证群组状态存在
2. 验证发送者是群组成员
3. 检查群组是否已经是 NATIVE 状态

**流程**:
1. 验证禁用请求的合法性
2. 清理本地资源
3. 重置群组状态
4. 插入系统消息

#### 1.4 消息处理集成

**文件**: `TapMessageProcessor.kt` (第 1246-1283 行)

**更新内容**:
- 更新 `processGroupDisable()` 方法
- 调用 `handleDisableV2ModeRequest()` 处理禁用消息
- 使用 `GroupOperationResult` 统一结果处理

### 2. 异常降级

#### 2.1 异常降级处理 (`degradeV2ModeOnError()`)

**文件**: `GroupTransportManager.kt` (第 1664-1731 行)

**功能说明**:
- 当检测到异常情况时自动降级到原生模式
- 不发送控制消息（避免在网络异常时堆积失败消息）
- 插入系统消息说明降级原因

**适用场景**:
- 轮询连续失败
- 通道连接失败
- Token 过期
- 其他网络或系统异常

**使用示例**:
```kotlin
val groupManager = GroupTransportManager.getInstance(context)
val result = groupManager.degradeV2ModeOnError(
    groupId = groupId,
    reason = "连续轮询失败 5 次"
)
```

#### 2.2 健康监控器 (`GroupV2HealthMonitor`)

**文件**: `GroupV2HealthMonitor.kt`

**功能说明**:
全面监控群组 V2 模式的健康状态，自动触发异常降级。

**监控维度**:

1. **轮询失败监控**
   - 记录每个成员的轮询失败次数
   - 连续失败超过阈值（默认 5 次）触发降级
   - 成功轮询时重置计数

2. **通道失败监控**
   - 记录群组通道的失败次数
   - 失败超过阈值（默认 3 次）触发降级
   - 成功连接时重置计数

3. **Token 过期检测**
   - 检查 Token 是否已过期
   - Token 过期时立即触发降级
   - Token 即将过期（7 天内）时发出警告

**关键方法**:

```kotlin
// 记录轮询失败
fun recordPollingFailure(groupId: String, memberAci: String, reason: String)

// 记录轮询成功
fun recordPollingSuccess(groupId: String, memberAci: String)

// 记录通道失败
fun recordChannelFailure(groupId: String, reason: String)

// 记录通道成功
fun recordChannelSuccess(groupId: String)

// 检查 Token 过期
fun checkTokenExpiry(groupId: String, expiryTimestamp: Long)

// 获取健康状态
fun getHealthStatus(groupId: String): GroupHealthStatus

// 清理监控数据
fun cleanup(groupId: String)
```

**配置项**:
- `MAX_CONSECUTIVE_POLLING_FAILURES = 5`: 最大连续轮询失败次数
- `MAX_CHANNEL_FAILURES = 3`: 最大通道失败次数
- `TOKEN_EXPIRY_WARNING_DAYS = 7`: Token 过期警告提前天数
- `ENABLE_AUTO_DEGRADATION = true`: 自动降级开关

**使用示例**:

```kotlin
val healthMonitor = GroupV2HealthMonitor.getInstance(context)

// 在轮询失败时记录
healthMonitor.recordPollingFailure(groupId, memberAci, "网络超时")

// 在轮询成功时记录
healthMonitor.recordPollingSuccess(groupId, memberAci)

// 检查 Token 过期
healthMonitor.checkTokenExpiry(groupId, token.expiryTime)

// 获取健康状态
val status = healthMonitor.getHealthStatus(groupId)
if (!status.isHealthy) {
    Log.w(TAG, "群组不健康: channelFailures=${status.channelFailureCount}")
}

// 在禁用后清理监控数据
healthMonitor.cleanup(groupId)
```

**健康状态数据类**:
```kotlin
data class GroupHealthStatus(
    val groupId: String,
    val isHealthy: Boolean,
    val channelFailureCount: Int,
    val pollingFailureCount: Int,
    val lastSuccessfulPollingTime: Long?
)
```

## 辅助方法

### 获取群组成员 RecipientId 列表

**方法**: `getMemberRecipientIds(groupId: String)`
**文件**: `GroupTransportManager.kt` (第 1733-1751 行)

从 groupId 解析出群组的所有成员 RecipientId。

### 获取群组 RecipientId

**方法**: `getGroupRecipientId(groupId: String)`
**文件**: `GroupTransportManager.kt` (第 1753-1772 行)

从 groupId 转换为群组的 RecipientId，用于插入系统消息。

## 系统消息

### 禁用消息

禁用 V2 模式时插入的系统消息：
- 主动禁用: "v2 mode 已禁用"
- 异常降级: "v2 mode 已禁用" + "v2 mode 因异常自动禁用: [原因]"

消息类型使用 Signal 原生的 TAP V2 Mode 系统消息类型，显示为灰色居中提示。

## 错误处理

### 1. 禁用失败

**场景**: 禁用流程中的任何步骤失败

**处理**:
- 记录详细错误日志
- 返回 `GroupOperationResult.Failed`
- 尽可能完成部分清理（不抛出异常）

### 2. 消息发送失败

**场景**: 发送禁用消息失败

**处理**:
- 记录警告日志
- 继续执行禁用流程（本地状态仍然重置）
- 不影响本地禁用结果

### 3. 资源清理异常

**场景**: 清理某个成员的资源时异常

**处理**:
- 记录错误日志
- 继续清理其他成员的资源
- 不中断整个清理流程

## 集成要点

### 1. 在轮询服务中集成健康监控

```kotlin
// TapPollingService 中
val healthMonitor = GroupV2HealthMonitor.getInstance(context)

// 轮询失败时
healthMonitor.recordPollingFailure(groupId, memberAci, "超时")

// 轮询成功时
healthMonitor.recordPollingSuccess(groupId, memberAci)
```

### 2. 在通道管理器中集成健康监控

```kotlin
// TransportChannelManager 中
val healthMonitor = GroupV2HealthMonitor.getInstance(context)

// 通道失败时
healthMonitor.recordChannelFailure(groupId, "连接失败")

// 通道成功时
healthMonitor.recordChannelSuccess(groupId)
```

### 3. 在 Token 刷新逻辑中集成过期检测

```kotlin
// Token 刷新逻辑中
val healthMonitor = GroupV2HealthMonitor.getInstance(context)
healthMonitor.checkTokenExpiry(groupId, token.expiryTime)
```

## UI 集成（待 Phase 7 实现）

### 群组菜单中添加禁用选项

**位置**: 群组对话右上角菜单

**条件**: 仅在 `FULL_V2_ACTIVE` 或 `PROPOSING` 状态显示

**操作**:
```kotlin
// 在群组菜单的点击事件中
lifecycleScope.launch {
    val groupManager = GroupTransportManager.getInstance(context)
    val result = groupManager.disableV2ModeComplete(
        groupId = groupId,
        sendControlMessage = true,
        insertSystemMessage = true
    )
    
    when (result) {
        is GroupOperationResult.Success -> {
            Toast.makeText(context, "v2 mode 已禁用", Toast.LENGTH_SHORT).show()
        }
        is GroupOperationResult.Failed -> {
            Toast.makeText(context, "禁用失败: ${result.message}", Toast.LENGTH_LONG).show()
        }
    }
}
```

## 测试要点

### 1. 主动禁用测试

- [ ] 在 PROPOSING 状态禁用
- [ ] 在 FULL_V2_ACTIVE 状态禁用
- [ ] 禁用消息是否发送给所有成员
- [ ] 资源是否完全清理
- [ ] 系统消息是否正确插入

### 2. 接收禁用消息测试

- [ ] 正常接收禁用消息
- [ ] 非成员发送禁用消息（应拒绝）
- [ ] 已经是 NATIVE 状态接收禁用消息（应忽略）
- [ ] 资源清理是否完整

### 3. 异常降级测试

- [ ] 轮询连续失败触发降级
- [ ] 通道连续失败触发降级
- [ ] Token 过期触发降级
- [ ] 降级不发送控制消息（避免回声）
- [ ] 降级原因是否记录在系统消息中

### 4. 健康监控测试

- [ ] 轮询失败计数是否正确
- [ ] 轮询成功重置计数
- [ ] 通道失败计数是否正确
- [ ] Token 过期检测是否准确
- [ ] 达到阈值时是否触发降级
- [ ] 健康状态查询是否准确

### 5. 边界情况测试

- [ ] 群组状态不存在时的处理
- [ ] 同时收到多个禁用消息
- [ ] 禁用过程中新成员加入
- [ ] 禁用过程中成员离开
- [ ] 网络异常时的降级行为

## 性能考虑

### 1. 资源清理性能

- 使用循环清理，避免并发冲突
- 每个成员的清理失败不影响其他成员
- 统计清理结果便于调试

### 2. 监控数据管理

- 使用 `ConcurrentHashMap` 保证线程安全
- 使用 `AtomicInteger` 保证计数原子性
- 禁用后及时清理监控数据，避免内存泄漏

### 3. 异步处理

- 降级处理在独立协程中执行，不阻塞主流程
- 使用 `SupervisorJob` 确保异常不影响其他协程

## 日志和调试

### 关键日志点

1. **禁用开始**: `开始完整禁用群组 V2 模式`
2. **资源清理**: `清理群组资源`, `群组资源清理完成`
3. **消息发送**: `群组禁用消息已发送`
4. **状态更新**: `群组 V2 模式完整禁用成功`
5. **异常降级**: `群组 V2 模式异常降级`, `触发群组异常降级`
6. **健康监控**: `轮询失败记录`, `通道失败记录`, `轮询连续失败超过阈值`

### 日志级别

- **INFO**: 正常流程日志（开始、完成、成功）
- **WARN**: 警告日志（发送失败、部分清理失败、即将过期）
- **ERROR**: 错误日志（禁用失败、超过阈值、已过期）
- **DEBUG**: 调试日志（详细的状态信息）

## 后续优化建议

### 1. Token 自动刷新

在 Token 即将过期时，自动触发刷新流程，避免降级。

### 2. 智能降级策略

根据失败类型和频率，采用不同的降级策略：
- 临时性网络问题：延迟降级，给予恢复时间
- 持续性问题：立即降级
- Token 问题：先尝试刷新，再降级

### 3. 降级后的恢复机制

允许用户在问题解决后，手动重新启用 v2 mode，而不需要重新发起完整的提议流程。

### 4. 监控数据持久化

将监控数据持久化到数据库，重启应用后仍能保留历史状态。

### 5. 健康报告

提供更详细的健康报告，包括失败原因分析、建议修复措施等。

## 总结

Phase 6 实现了完整的禁用和异常降级功能，主要亮点：

1. **完整的禁用流程**: 资源清理、消息通知、状态重置一体化
2. **智能健康监控**: 多维度监控，自动触发降级
3. **安全的异常处理**: 尽可能完成清理，避免资源泄漏
4. **灵活的配置**: 支持配置阈值和开关
5. **清晰的日志**: 便于调试和问题定位

这为群组 V2 模式提供了可靠的退出机制和异常保护，确保系统的稳定性和用户体验。

---

*实现日期: 2025-10-05*
*Phase: 6 - 禁用和降级*

