# 群组 V2 Mode 问题修复总结

## 修复日期
2025-10-07

## 问题描述

### 核心问题
在两人群聊中测试群组 v2 mode 时出现以下问题：
1. **P0 严重问题**: A 发起提议后，B 同意时没有任何反应，整个流程卡住
2. **P1 功能缺失**: A 无法在 PROPOSING 阶段主动取消提议

### 根本原因分析

#### 问题 1: B 同意后流程中断
**位置**: `GroupTokenExchangeReceiver.handleGroupTokenAcceptance()` 第124-136行

**错误逻辑**:
```kotlin
// 错误的代码
if (isProposer) {
    val initialized = groupManager.proposeV2Mode(
        groupId = groupId,
        proposerAci = proposerAci,
        memberAcis = memberAcis,
        providerType = originalMessage.providerType
    )
    if (!initialized) {
        Log.e(TAG, "初始化群组状态失败")
        return@launch  // 这里导致流程终止
    }
}
```

**问题分析**:
1. A 发起提议时已经调用 `proposeV2ModeComplete()`，创建了群组状态（status=PROPOSING, agreedMembers={A}）
2. B 收到提议点击同意时，`isProposer=true`（表示这是提议者发来的消息）
3. B 错误地尝试调用 `proposeV2Mode()`，但该方法会检查群组状态是否已存在
4. 由于状态已存在，返回 false，导致 B 的整个流程被 `return@launch` 终止
5. B 没有生成 tokens，没有发送 GROUP_ACCEPT 消息，流程完全卡住

#### 问题 2: 无法取消提议
**影响**: 提议者在 PROPOSING 阶段无法主动取消提议，只能等待或重启应用

## 修复方案

### 修复 1: 纠正 B 端接受逻辑（P0 优先级）

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeReceiver.kt`

**修改内容**:
- 移除错误的 `proposeV2Mode()` 调用
- B 端直接调用 `acceptV2Proposal()` 标记自己为已同意
- 状态已由提议者创建，不需要重新初始化

**修改后的逻辑**:
```kotlin
// 7. 标记自己为已同意
// 注意: 群组状态已经由提议者创建，这里不需要调用 proposeV2Mode()
// 直接调用 acceptV2Proposal 将自己加入 agreedMembers
Log.d(TAG, "标记自己为已同意: groupId=$groupId, myAci=$myAci")
val accepted = groupManager.acceptV2Proposal(groupId, myAci)
if (!accepted) {
    Log.e(TAG, "标记自己为已同意失败: groupId=$groupId")
    return@launch
}
```

### 修复 2: 添加取消提议功能（P1 优先级）

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`

**新增方法**: `cancelV2Proposal(groupId: String): GroupOperationResult<Unit>`

**功能**:
1. 检查当前状态是否为 PROPOSING
2. 验证调用者是否为提议者
3. 清理资源（tokens, channels, polling）
4. 重置状态为 NATIVE
5. 插入系统消息
6. 发送取消通知给其他成员

**代码片段**:
```kotlin
suspend fun cancelV2Proposal(groupId: String): GroupOperationResult<Unit> {
    // 1. 获取并验证群组状态
    // 2. 检查是否为 PROPOSING 状态
    // 3. 验证是否为提议者
    // 4. 清理资源
    // 5. 重置为 NATIVE
    // 6. 通知成员
}
```

### 修复 3: UI 层支持取消提议（P1 优先级）

**文件**: `app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt`

**修改内容**:

1. **增强 `disableGroupV2Mode()` 方法**:
   - 根据状态调用不同的处理方法
   - PROPOSING 状态调用 `cancelV2Proposal()`
   - FULL_V2_ACTIVE 状态调用 `disableV2ModeComplete()`

2. **新增 `showGroupV2ModeCancelProposalDialog()` 方法**:
   - 为提议者显示取消确认对话框
   - 显示当前同意进度

3. **修改菜单处理逻辑**:
   - PROPOSING 状态下，提议者可以取消提议
   - 非提议者只能查看进度

**代码片段**:
```kotlin
when (groupStatus) {
    GroupV2Status.PROPOSING -> {
        if (groupState?.proposerAci == myAci) {
            // 提议者可以取消
            showGroupV2ModeCancelProposalDialog(recipient, groupIdString, groupState)
        } else {
            // 非提议者只能查看进度
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

### 修复 4: 增强日志输出（P2 优先级）

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`

**修改位置**:
- `acceptV2Proposal()` 方法
- `checkAndActivateV2Mode()` 方法

**增强内容**:
- 输出当前群组状态（status, agreedMembers, totalMembers）
- 输出成员列表（已脱敏）
- 输出全员同意检查结果
- 输出操作前后的状态对比

## 修复效果

### 修复前
- A 发起提议 → B 同意 → **B 端流程中断，没有任何反应**
- A 无法取消提议，只能等待或重启

### 修复后
- A 发起提议 → B 同意 → B 生成 tokens → B 发送 GROUP_ACCEPT → 双方检查全员同意 → **自动激活 FULL_V2_ACTIVE**
- A 可以在 PROPOSING 阶段点击菜单取消提议，发送通知给其他成员

## 测试建议

### 基本流程测试
1. **两人群聊正常流程**: 
   - A 创建群组 + B
   - A 点击"Use v2 mode"发起提议
   - B 收到通知并同意
   - **期望**: 双方自动进入 FULL_V2_ACTIVE 状态，显示"v2"指示器

2. **提议者取消**:
   - A 发起提议
   - A 在 B 同意前点击"Use v2 mode"菜单
   - **期望**: 显示取消对话框，确认后回退到 NATIVE 状态

3. **多人群聊部分同意**:
   - A 创建群组 + B + C
   - A 发起提议
   - B 同意，C 不响应
   - **期望**: 保持 PROPOSING 状态，显示"2/3 已同意"

### 边界情况测试
- 并发同意（B 和 C 几乎同时同意）
- 网络异常时的重试
- 提议者离开群组
- 新成员加入（应回退到 NATIVE）

## 影响范围

### 修改文件
1. `GroupTokenExchangeReceiver.kt` - 核心逻辑修复
2. `GroupTransportManager.kt` - 新增取消方法 + 增强日志
3. `ConversationFragment.kt` - UI 层支持取消提议
4. `app/src/main/res/values/strings.xml` - 新增字符串资源

### 修改行数
- 约 150 行代码修改
- 新增约 100 行代码（新方法）
- 新增 7 个字符串资源
- 无数据库变更
- 无新增代码文件

### 新增字符串资源
```xml
<!-- 取消提议相关 -->
<string name="conversation__cancel_group_v2_proposal">Cancel v2 Mode Proposal</string>
<string name="conversation__cancel_group_v2_proposal_message">Do you want to cancel the v2 mode proposal? Currently %1$d of %2$d members have agreed.</string>
<string name="conversation__cancel_proposal">Cancel Proposal</string>
<string name="conversation__keep_waiting">Keep Waiting</string>

<!-- 状态提示 -->
<string name="conversation__group_v2_proposal_cancelled">v2 mode proposal cancelled</string>
<string name="conversation__group_v2_not_active">Group is not in v2 mode</string>
```

### 风险评估
- **风险等级**: 🟢 低风险
- **原因**:
  - 只修复逻辑错误，不改变数据结构
  - 向后兼容，不影响现有功能
  - 新增功能完全独立
  - 无需数据迁移

## 技术细节

### 关键修复点

1. **状态管理**:
   - 群组状态由提议者创建，只创建一次
   - 其他成员直接调用 `acceptV2Proposal()` 加入 agreedMembers
   - 使用乐观锁避免并发冲突

2. **激活检查**:
   - 每次收到 GROUP_ACCEPT 消息后都检查 `isFullyAgreed()`
   - `isFullyAgreed()` = `agreedMembers.size == totalMembers.size && agreedMembers == totalMembers`
   - 全员同意后自动升级为 FULL_V2_ACTIVE

3. **权限控制**:
   - 只有提议者可以取消提议
   - `cancelV2Proposal()` 中验证 `proposerAci == myAci`
   - 防止非提议者误操作

## 相关文档

- `GROUPID_FORMAT_FIX.md` - GroupId 格式兼容性修复
- `DEADLOCK_FIX_V2.md` - 死锁问题修复
- `FIX_SUMMARY.md` - 消息路由修复总结
- `PLAN_GROUP.md` - 群组 V2 实现计划
- `TODO_GROUP.md` - 任务清单

## 验证清单

- [x] 核心逻辑修复（移除错误的 proposeV2Mode 调用）
- [x] 取消提议功能实现
- [x] UI 层支持取消操作
- [x] 增强日志输出
- [x] Lint 检查通过（无错误）
- [ ] 单元测试（待补充）
- [ ] 集成测试（需要至少2台设备）
- [ ] 性能测试（可选）

## 后续优化建议

1. **UI 优化**:
   - 添加进度条显示同意人数
   - 显示哪些成员已同意，哪些未同意
   - 提供手动同步状态功能

2. **错误处理**:
   - 添加网络异常时的自动重试
   - 超时后的自动降级
   - 更友好的错误提示

3. **测试完善**:
   - 添加单元测试覆盖核心逻辑
   - 添加集成测试自动化
   - 性能压测（大群组场景）

---

**修复人员**: AI Assistant  
**审查状态**: 待审查  
**测试状态**: 待测试  
**部署状态**: 待部署

