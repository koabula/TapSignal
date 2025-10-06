# 群组成员变动处理集成报告

## 概述

**完成日期**: 2025-10-06  
**优先级**: P0 (关键)  
**状态**: ✅ 已完成

本文档记录了将 tap 群组成员变动处理集成到 Signal 群组管理流程的实施情况。

## 问题背景

在代码审计中发现，虽然 `GroupTransportManager` 已经实现了 `handleMemberJoin()` 和 `handleMemberLeave()` 方法，但这些方法没有被 Signal 的群组管理系统调用。这导致：

### 严重影响
1. 🔴 成员加入时，tap 模块无法感知，无法自动处理新成员的 v2 mode 状态
2. 🔴 成员离开时，tap 模块继续轮询已离开成员的目录，浪费资源
3. 🔴 PROPOSING 阶段成员变动不会自动回退到 NATIVE 状态
4. 🔴 群组 v2 mode 状态与实际群组成员不同步

## 解决方案

### 集成点

**文件**: `app/src/main/java/org/thoughtcrime/securesms/groups/GroupManagerV2.java`

#### 1. 成员添加集成 (第 286-305 行)

**方法**: `GroupEditor.addMembers()`

**修改内容**:
```java
// 原代码
return commitChangeWithConflictResolution(...);

// 修改后
GroupManager.GroupActionResult result = commitChangeWithConflictResolution(...);
handleTapGroupMemberAddition(newMembers);  // 新增：集成 tap 处理
return result;
```

#### 2. 成员移除集成 (第 437-452 行)

**方法**: `GroupEditor.ejectMember()`

**修改内容**:
```java
// 原代码
return commitChangeWithConflictResolution(...);

// 修改后
GroupManager.GroupActionResult result = commitChangeWithConflictResolution(...);
handleTapGroupMemberRemoval(aci);  // 新增：集成 tap 处理
return result;
```

### 实现的辅助方法

#### 1. handleTapGroupMemberAddition() (第 805-858 行)

**功能**:
- 提取群组 ID 和新成员 ACI
- 获取群组当前所有成员列表
- 在后台线程异步调用 `GroupTransportManager.handleMemberJoin()`
- 处理多个新成员的批量添加

**关键特性**:
- ✅ 异步执行，不阻塞 Signal 主流程
- ✅ 使用 try-catch 确保异常不影响 Signal 核心功能
- ✅ 完整的错误日志记录
- ✅ 支持批量成员添加

**实现细节**:
```java
// 1. 转换群组 ID 为 Base64 字符串
String groupIdString = Base64.encodeToString(groupId.getDecodedId(), Base64.NO_WRAP);

// 2. 获取所有成员 ACI
DecryptedGroup decryptedGroup = v2GroupProperties.getDecryptedGroup();
Set<String> allMemberAcis = ...;

// 3. 异步调用 tap 处理
SignalExecutors.BOUNDED.execute(() -> {
    GroupTransportManager groupTransportManager = GroupTransportManager.getInstance(context);
    runBlocking(Dispatchers.IO, 
        (scope, continuation) -> groupTransportManager.handleMemberJoin(...)
    );
});
```

#### 2. handleTapGroupMemberRemoval() (第 864-888 行)

**功能**:
- 提取群组 ID 和离开成员的 ACI
- 在后台线程异步调用 `GroupTransportManager.handleMemberLeave()`

**关键特性**:
- ✅ 异步执行，不阻塞 Signal 主流程
- ✅ 使用 try-catch 确保异常不影响 Signal 核心功能
- ✅ 完整的错误日志记录
- ✅ 简洁高效的实现

**实现细节**:
```java
// 1. 转换群组 ID 和成员 ACI
String groupIdString = Base64.encodeToString(groupId.getDecodedId(), Base64.NO_WRAP);
String memberAci = aci.toString();

// 2. 异步调用 tap 处理
SignalExecutors.BOUNDED.execute(() -> {
    GroupTransportManager groupTransportManager = GroupTransportManager.getInstance(context);
    runBlocking(Dispatchers.IO,
        (scope, continuation) -> groupTransportManager.handleMemberLeave(...)
    );
});
```

## 设计原则遵循

### 1. 不影响原有功能 (rule1)
- ✅ 使用异步执行，不阻塞 Signal 主流程
- ✅ 所有异常都被 try-catch 捕获
- ✅ tap 处理失败不会影响 Signal 群组操作成功
- ✅ 在 Signal 操作成功后才调用 tap 处理

### 2. 代码简约优雅 (nosimplify)
- ✅ 没有硬编码或模拟实现
- ✅ 真实调用 GroupTransportManager 的方法
- ✅ 清晰的方法命名和注释
- ✅ 适当的错误处理和日志记录

### 3. 抽象层实现 (tap)
- ✅ 集成点在 Signal 群组管理层（抽象层）
- ✅ 不涉及具体的 provider 实现
- ✅ 通过 GroupTransportManager 统一调度

## 执行流程

### 成员加入流程

```
Signal UI
    ↓
GroupManagerV2.GroupEditor.addMembers()
    ↓
commitChangeWithConflictResolution()  [Signal 原生处理]
    ↓ (成功)
handleTapGroupMemberAddition()  [新增]
    ↓ (异步)
GroupTransportManager.handleMemberJoin()
    ↓
- 检查群组状态
- PROPOSING → 回退 NATIVE
- FULL_V2_ACTIVE → 更新成员列表
- 清理/更新资源
```

### 成员离开流程

```
Signal UI
    ↓
GroupManagerV2.GroupEditor.ejectMember()
    ↓
commitChangeWithConflictResolution()  [Signal 原生处理]
    ↓ (成功)
handleTapGroupMemberRemoval()  [新增]
    ↓ (异步)
GroupTransportManager.handleMemberLeave()
    ↓
- 移除轮询目标
- 关闭通道
- 删除 token
- 更新成员列表
- 检查是否需要禁用 v2 mode
```

## 关键技术点

### 1. Java 调用 Kotlin 协程
使用 `kotlinx.coroutines.BuildersKt.runBlocking()`:
```java
kotlinx.coroutines.BuildersKt.runBlocking(
    kotlinx.coroutines.Dispatchers.getIO(),
    (scope, continuation) -> groupTransportManager.handleMemberJoin(...)
);
```

### 2. 异步执行
使用 Signal 的线程池:
```java
org.thoughtcrime.securesms.util.concurrent.SignalExecutors.BOUNDED.execute(() -> {
    // 异步处理逻辑
});
```

### 3. 群组 ID 转换
```java
// GroupId.V2 → Base64 字符串
String groupIdString = android.util.Base64.encodeToString(
    groupId.getDecodedId(), 
    android.util.Base64.NO_WRAP
);
```

### 4. ACI 提取
```java
// 从 RecipientId 获取 ACI
Recipient member = Recipient.resolved(recipientId);
String aci = member.requireServiceId().toString();

// 从 DecryptedMember 解析 ACI
ACI memberAci = ACI.parseFromBinary(ByteString.of(member.aciBytes.toByteArray()));
String aciString = memberAci.toString();
```

## 测试建议

### 单元测试场景
1. 成员添加后，tap 处理被正确调用
2. 成员移除后，tap 处理被正确调用
3. tap 处理异常不影响 Signal 操作
4. 批量添加成员的处理

### 集成测试场景
1. **NATIVE → 添加成员**: 状态保持 NATIVE
2. **PROPOSING → 添加成员**: 回退到 NATIVE
3. **FULL_V2_ACTIVE → 添加成员**: 更新成员列表，保持 ACTIVE
4. **FULL_V2_ACTIVE → 移除成员**: 清理资源，保持 ACTIVE
5. **FULL_V2_ACTIVE → 移除大部分成员**: 检查是否禁用 v2 mode

### 边界情况测试
1. 添加多个成员（批量）
2. 快速连续添加/移除成员
3. 网络异常时的处理
4. tap 模块未初始化时的处理
5. 群组 v2 mode 未启用时的处理

## 验证清单

- [x] 代码编译通过，无 lint 错误
- [x] 遵循 nosimplify 原则（无模拟实现）
- [x] 遵循 rule1 原则（不影响原有功能）
- [x] 遵循 tap 原则（在抽象层实现）
- [x] 异步执行，不阻塞主流程
- [x] 完整的异常处理
- [x] 清晰的日志记录
- [x] 更新 TODO_GROUP.md
- [x] 创建集成文档

## 影响范围

### 修改的文件
1. `app/src/main/java/org/thoughtcrime/securesms/groups/GroupManagerV2.java`
   - 新增代码：~100 行
   - 修改方法：2 个
   - 新增方法：2 个

2. `TODO_GROUP.md`
   - 更新任务状态

### 不需要修改的文件
- ✅ GroupTransportManager.kt (已有完整实现)
- ✅ TapMessageProcessor.kt (已有完整实现)
- ✅ TapPollingService.kt (已有完整实现)
- ✅ 其他 tap 模块文件

## 后续工作

### 立即执行
- [ ] 进行集成测试验证
- [ ] 测试成员变动的各种场景
- [ ] 验证异步执行的性能影响

### 短期计划
- [ ] 添加单元测试
- [ ] 添加性能监控埋点
- [ ] 测试大群组场景

### 长期优化
- [ ] 考虑批量处理优化
- [ ] 考虑添加重试机制
- [ ] 监控异常率和性能指标

## 风险评估

### 🟢 低风险
- ✅ 异步执行，不影响主流程
- ✅ 异常处理完善
- ✅ 遵循 Signal 现有模式
- ✅ 代码简洁清晰

### 🟡 中风险
- ⚠️ 需要测试验证实际效果
- ⚠️ 大群组场景未测试
- ⚠️ 性能影响未量化

### 🔴 已解决的高风险
- ✅ 成员变动不同步问题（本次修复）
- ✅ 资源浪费问题（本次修复）

## 结论

**集成状态**: ✅ 完成

成员变动处理已成功集成到 Signal 群组管理流程。实现遵循了所有设计原则，确保：
1. 不影响 Signal 原有功能
2. 异步执行，性能影响最小
3. 异常处理完善，系统稳定性高
4. 代码简约优雅，易于维护

这是群组 v2 mode 功能的关键 P0 缺失项，现已修复。建议尽快进行集成测试验证。

---

**实现者**: AI Assistant  
**审阅者**: 待审阅  
**版本**: 1.0  
**最后更新**: 2025-10-06

