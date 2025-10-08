# 群组 V2 Mode 状态同步问题修复

## 问题描述

在群组 v2 mode 建立过程中，发现 A端（发起人）和 B端（接受者）的状态不一致：
- **B端**：成功激活 v2 mode，显示系统消息，开始轮询
- **A端**：未激活 v2 mode，未显示系统消息，未开始轮询

## 根本原因

### 时序不对称

| 端 | 处理方式 | 激活检查 | 结果 |
|---|---------|---------|-----|
| **B端** (GroupTokenExchangeReceiver) | 同步 | 立即检查并激活 | ✅ 可靠激活 |
| **A端** (TapMessageProcessor) | **异步** | **异步检查并激活** | ❌ 可能失败 |

### 具体场景

**B端流程（同步）：**
```kotlin
// 1. 同步标记自己同意
acceptV2Proposal(groupId, myAci)

// 2. 发送 ACCEPT 消息

// 3. 同步检查并激活
checkAndActivateIfReady()  // agreedMembers={A,B}, 全员同意 → 激活成功 ✅
```

**A端流程（异步）：**
```kotlin
// 1. 收到 ACCEPT 消息
// 2. 异步处理（可能超时、失败或延迟）
processorScope.launch {
    acceptV2Proposal(groupId, accepterAci)  // 可能超时 ❌
    checkAndActivateGroupV2Mode(groupId)    // 可能不执行 ❌
}

// 3. 立即返回 Success（但实际未完成）
```

**问题点：**
- B端同步完成，可靠激活
- A端异步处理，可能因为超时、异常等原因未完成
- 即使失败，也只记录日志，没有重试机制

## 修复方案

### 1. 立即修复：A端改为同步处理 ✅

**修改文件：** `TapMessageProcessor.kt` 的 `processGroupTokenAccept()` 方法

**修改内容：**
```kotlin
// 修改前：异步处理，不等待完成
processorScope.launch(Dispatchers.IO) {
    acceptV2Proposal(groupId, accepterAci)
    checkAndActivateGroupV2Mode(groupId)
}

// 修改后：同步处理，确保完成
withContext(Dispatchers.IO) {
    // 同步标记成员同意
    val accepted = groupManager.acceptV2Proposal(groupId, accepterAci)
    
    if (accepted) {
        // 同步检查并激活
        val activated = groupManager.checkAndActivateV2Mode(groupId)
        
        if (activated) {
            // 异步处理后续操作（不影响状态转换）
            processorScope.launch {
                handleGroupActivationComplete(groupId, accepterAci)
            }
        }
    }
}
```

**优势：**
- ✅ A端和B端处理逻辑一致
- ✅ 确保状态转换可靠完成
- ✅ 后续操作（建立通道、启动轮询）仍然异步，不阻塞消息处理

### 2. 添加详细日志 ✅

**修改文件：**
- `TapMessageProcessor.kt`
- `GroupTransportManager.kt`

**新增日志标签：**
```kotlin
"[状态转换]" - 状态变化相关
"[状态检查]" - 激活前的检查
"[状态激活]" - 激活操作
"[激活后处理]" - 激活后的后续操作
```

**关键日志点：**
1. 开始处理接受消息
2. 当前状态（before）
3. 标记成员同意
4. 更新后状态（after）
5. 全员同意检查
6. 激活操作
7. 激活后验证

**示例：**
```
[状态转换] 开始同步处理群组接受: groupId=xxx, accepter=B
[状态转换] 当前状态: status=PROPOSING, agreed=1/2
[状态转换] 群组成员已标记为同意: accepter=B
[状态转换] 更新后状态: status=PROPOSING, agreed=2/2, isFullyAgreed=true
[状态激活] ✅ 所有成员已同意，开始激活 V2 模式
[状态激活] ✅ 群组 V2 模式激活成功: PROPOSING → FULL_V2_ACTIVE
[激活后处理] ✅ 群组激活后续操作完成
```

### 3. 实现定期状态同步检查器 ✅

**新增文件：** `GroupV2StateSynchronizer.kt`

**功能：**
1. **自动检测不一致状态**
   - PROPOSING 但全员同意 → 自动激活
   - FULL_V2_ACTIVE 但缺少通道 → 重建通道
   - FULL_V2_ACTIVE 但未启动轮询 → 重启轮询

2. **定期检查**
   - 启动延迟：30秒
   - 检查间隔：5分钟
   - 后台运行，不影响主流程

3. **自动修复**
   ```kotlin
   // 检查 PROPOSING 群组
   if (groupState.isFullyAgreed()) {
       val activated = groupManager.checkAndActivateV2Mode(groupId)
       if (activated) {
           // 建立通道、启动轮询
           handleGroupActivation(groupState)
           // 发送成功通知
           sendStateFixedNotification()
       } else {
           // 发送异常通知
           sendInconsistencyNotification()
       }
   }
   ```

4. **集成到 TapPollingService**
   - 随轮询服务一起启动/停止
   - 使用独立的协程作用域

### 4. 添加状态不一致检测和UI提示 ✅

**修改文件：** `GroupTransportManager.kt`

**新增方法：**

1. **`detectInconsistentStates()`** - 检测不一致状态
   ```kotlin
   suspend fun detectInconsistentStates(): List<GroupStateInconsistency>
   ```
   
   返回：
   - PROPOSING_BUT_FULLY_AGREED：全员同意但未激活
   - ACTIVE_BUT_MISSING_CHANNELS：已激活但缺少通道
   - ACTIVE_BUT_NO_POLLING：已激活但未启动轮询

2. **`fixInconsistentState(groupId)`** - 修复不一致状态
   ```kotlin
   suspend fun fixInconsistentState(groupId: String): Boolean
   ```
   
   自动执行：
   - PROPOSING → 激活群组
   - FULL_V2_ACTIVE → 重建通道

**UI通知：**

1. **自动修复成功**
   - 渠道：`tap_group_state_sync`
   - 优先级：LOW
   - 标题："群组状态已修复"
   - 内容："[群组名]: 群组 v2 mode 已自动激活"

2. **无法自动修复**
   - 渠道：`tap_group_state_sync`
   - 优先级：DEFAULT
   - 标题："群组状态异常"
   - 内容："[群组名]: 群组 v2 mode 状态异常，需要手动处理"
   - 点击：跳转到群组对话

## 修复效果

### Before（修复前）
```
A 发起提议 → B 同意
├─ B端：同步处理 → 立即激活 ✅ → 显示消息 ✅ → 开始轮询 ✅
└─ A端：异步处理 → 可能失败 ❌ → 无消息 ❌ → 无轮询 ❌
```

### After（修复后）
```
A 发起提议 → B 同意
├─ B端：同步处理 → 立即激活 ✅ → 显示消息 ✅ → 开始轮询 ✅
└─ A端：同步处理 → 可靠激活 ✅ → 显示消息 ✅ → 开始轮询 ✅

+ 定期检查（每5分钟）：
  └─ 发现不一致 → 自动修复 → 发送通知
```

## 修改文件清单

### 核心修复
1. ✅ `TapMessageProcessor.kt` - A端同步处理逻辑
2. ✅ `GroupTransportManager.kt` - 详细日志 + 状态检测/修复方法

### 新增功能
3. ✅ `GroupV2StateSynchronizer.kt` - 状态同步检查器（新文件）
4. ✅ `TapPollingService.kt` - 集成同步检查器

### 数据结构
5. ✅ `GroupTransportManager.kt` - 新增：
   - `InconsistencyType` 枚举
   - `GroupStateInconsistency` 数据类

## 测试建议

### 功能测试
1. **正常流程**
   - A 发起提议 → B 同意
   - 验证：A 和 B 都显示 "v2 mode enabled"
   - 验证：A 和 B 都开始轮询
   - 验证：消息通过 tap 传输

2. **异常恢复**
   - 模拟 A 端处理失败（如网络中断）
   - 等待 5 分钟（同步检查周期）
   - 验证：收到自动修复通知
   - 验证：A 端状态恢复正常

3. **多设备场景**
   - 3人群组：A 发起，B、C 依次同意
   - 验证：所有人都显示激活消息
   - 验证：所有人都开始轮询

### 日志验证
启用详细日志，检查：
```
[状态转换] 开始同步处理群组接受
[状态转换] 当前状态: status=PROPOSING, agreed=1/2
[状态转换] 群组成员已标记为同意
[状态转换] 更新后状态: status=PROPOSING, agreed=2/2, isFullyAgreed=true
[状态激活] ✅ 所有成员已同意，开始激活 V2 模式
[状态激活] ✅ 群组 V2 模式激活成功
[激活后处理] ✅ 群组激活后续操作完成
```

## 注意事项

1. **向后兼容**
   - 修改不影响现有私聊 v2 mode
   - 修改不影响原生群聊功能

2. **性能影响**
   - 同步检查器每5分钟运行一次
   - 检查操作轻量，不影响性能

3. **通知频率**
   - 每个群组只在首次检测到问题时通知
   - 自动修复成功后使用低优先级通知

4. **日志级别**
   - `[状态转换]` - INFO/DEBUG
   - `[状态检查]` - DEBUG
   - `[状态激活]` - INFO
   - 异常情况 - WARN/ERROR

## 后续优化建议

1. **重试机制**（可选）
   - 如果同步处理也失败，加入重试队列
   - 使用 WorkManager 实现可靠重试

2. **状态恢复**（可选）
   - 记录状态变化历史
   - 支持手动回滚到历史状态

3. **监控指标**（可选）
   - 统计状态不一致发生频率
   - 统计自动修复成功率
   - 发现潜在的系统性问题

---

**修复日期：** 2025-01-XX  
**修复版本：** v1.0  
**相关文档：** PLAN_GROUP.md, TODO_GROUP.md

