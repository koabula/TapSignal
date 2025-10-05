# API 变更迁移说明

## 变更时间
2025-10-05

## 变更内容

### `GroupTransportManager` API 变更

#### 1. `getGroupState()` 方法签名变更

**旧版本**:
```kotlin
fun getGroupState(groupId: String): GroupV2State?
```

**新版本**:
```kotlin
suspend fun getGroupState(groupId: String): GroupOperationResult<GroupV2State?>
```

#### 2. `getGroupStatus()` 方法签名变更

**旧版本**:
```kotlin
fun getGroupStatus(groupId: String): GroupV2Status
```

**新版本**:
```kotlin
suspend fun getGroupStatus(groupId: String): GroupOperationResult<GroupV2Status>
```

#### 3. `updateGroupState()` 方法返回值变更

**旧版本**:
```kotlin
suspend fun updateGroupState(state: GroupV2State): Boolean
```

**新版本**:
```kotlin
suspend fun updateGroupState(state: GroupV2State): GroupOperationResult<Unit>
```

## 迁移方案

### 临时方案（向后兼容）

使用 deprecated 的同步版本：

```kotlin
// 在协程或后台线程中使用
val groupState = groupManager.getGroupStateSync(groupId)
val status = groupManager.getGroupStatusSync(groupId)
```

**适用场景**:
- 已在 `withContext(Dispatchers.IO)` 中
- 不需要详细的错误处理
- 快速迁移

### 推荐方案（新 API）

使用 suspend + Result 版本：

```kotlin
// 协程中使用
val result = groupManager.getGroupState(groupId)
when (result) {
    is GroupOperationResult.Success -> {
        val groupState = result.data
        if (groupState != null) {
            // 使用 groupState
        }
    }
    is GroupOperationResult.Failed -> {
        Log.e(TAG, "获取失败: ${result.message}", result.cause)
        // 根据 result.error 采取不同措施
    }
}
```

**优点**:
- 明确的错误处理
- 不会阻塞线程
- 更好的类型安全

## 已迁移的文件

以下文件已使用临时方案迁移（使用 `*Sync()` 版本）：

1. `GroupTokenExchangeReceiver.kt`
   - `checkAndActivateIfReady()` 方法

2. `GroupTransportManager.kt`
   - `sendGroupMessage()` 方法
   - `respondToNewMemberJoin()` 方法

3. `GroupMembershipSynchronizer.kt`
   - `syncGroupMembership()` 方法
   - `detectInconsistency()` 方法
   - `forceResync()` 方法（同时使用了 `Result.isSuccess()`）

4. `TapMessageProcessor.kt`
   - `checkAndActivateGroupIfReady()` 方法

5. **`PushGroupSendJob.java`** (Java 文件)
   - 群组消息发送前的状态检查
   - **注意**: Java 文件必须使用同步版本

## Deprecated API 时间表

- `getGroupStateSync()` - 计划在 v2.0 移除
- `getGroupStatusSync()` - 计划在 v2.0 移除

## 建议

1. **新代码**: 使用新的 suspend + Result API
2. **现有代码**: 可以继续使用 deprecated 版本，但建议逐步迁移
3. **UI 层**: 必须使用 suspend 版本，在 ViewModel/协程中调用
4. **后台任务**: 可以使用 sync 版本（在 IO 线程中）
5. **Java 代码**: 必须使用 `*Sync()` 版本，因为 Java 不支持 Kotlin 协程

## Java 互操作性

Kotlin suspend 函数不能直接从 Java 调用。对于 Java 代码：

```java
// ✅ 正确：使用同步版本
GroupTransportManager manager = GroupTransportManager.getInstance(context);
GroupV2Status status = manager.getGroupStatusSync(groupId);

// ❌ 错误：不能直接调用 suspend 函数
GroupV2Status status = manager.getGroupStatus(groupId); // 编译错误！
```

如果必须在 Java 中使用新 API，需要使用 Kotlin 协程桥接代码。

## 相关文档

- [CRITICAL_FIXES_REPORT.md](CRITICAL_FIXES_REPORT.md) - 详细修复报告
- [GroupOperationResult.kt](GroupOperationResult.kt) - Result 类型定义
