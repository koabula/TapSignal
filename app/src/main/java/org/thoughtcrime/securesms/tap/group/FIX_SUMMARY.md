# 群组 V2 Mode 问题修复总结

## 修复日期
2025-10-07

## 问题描述

### 问题 1: 群组控制消息显示在错误的会话中
- **现象**: A 和 B 在群组中使用 v2 mode，但控制消息显示在 A 和 B 的私聊界面
- **影响**: B 显示与 A 的私聊和群聊都进入 v2 mode

### 问题 2: 数据库死锁
- **现象**: 应用在处理群组 token 交换时出现死锁，导致闪退
- **日志特征**: 多个线程在 `SQLiteConnectionPool.waitForConnection` 处 TIMED_WAITING

## 根本原因分析

### 原因 1: 消息路由错误

**问题**:
- Signal 中群组消息的 `senderId` 参数是发送者**个人**的 RecipientId，而非群组 RecipientId
- 在处理群组控制消息时，错误地将 `senderId` 当作目标会话 ID 使用
- 导致通知和系统消息都显示在私聊界面

**关键代码位置**:
- `TapMessageProcessor.processGroupTokenOffer()` (1126-1164行)
- `TapMessageProcessor.showGroupTokenExchangeNotification()` (1399-1517行)

### 原因 2: 嵌套数据库连接请求

**问题**:
- `GroupV2StatusTable.addAgreedMember()` 方法分两步执行:
  1. 在事务外调用 `getGroupState()` - 需要一个数据库连接
  2. 调用 `insertOrUpdateGroupState()` 开启新事务 - 需要另一个连接
- 在高并发情况下，多个线程同时请求连接导致连接池耗尽
- SQLite 连接池默认大小有限，容易发生死锁

**关键代码位置**:
- `GroupV2StatusTable.addAgreedMember()` (247-284行 修复前)

## 修复方案

### 修复 1: 正确处理群组消息路由

**改动文件**: `TapMessageProcessor.kt`

**主要修改**:

1. **在 `processGroupTokenOffer()` 中**:
```kotlin
// 修复前: 使用 senderId (个人ID)
showGroupTokenExchangeNotification(senderId, groupId, ...)

// 修复后: 从 groupId 获取群组 RecipientId
val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
showGroupTokenExchangeNotification(groupRecipientId, groupId, ...)
```

2. **修改通知显示方法签名**:
```kotlin
// 修复前:
private fun showGroupTokenExchangeNotification(
    senderId: RecipientId,  // 个人ID - 错误
    groupId: String, ...
)

// 修复后:
private fun showGroupTokenExchangeNotification(
    groupRecipientId: RecipientId,  // 群组ID - 正确
    groupId: String, ...
)
```

3. **添加辅助方法**:
```kotlin
private fun getGroupRecipientIdFromGroupId(groupIdString: String): RecipientId? {
    val result = GroupIdConverter.convert(groupIdString, context)
    return when (result) {
        is ConversionResult.Success -> result.recipientId
        is ConversionResult.Failed -> null
    }
}
```

4. **更新通知 Intent**:
   - 修改 Extra 参数从 `senderId` 改为 `groupRecipientId`
   - 添加点击通知后跳转到群组会话的 Intent

### 修复 2: 合并数据库事务避免死锁

**改动文件**: `GroupV2StatusTable.kt`

**主要修改**:

1. **重写 `addAgreedMember()` 方法**:
```kotlin
fun addAgreedMember(groupId: String, memberAci: String, maxRetries: Int = 3): Boolean {
    var attempt = 0
    while (attempt < maxRetries) {
        try {
            // 整个操作在一个事务内完成
            writableDatabase.beginTransaction()
            try {
                // 在事务内查询（使用 writableDatabase）
                val state = getGroupStateInTransaction(groupId)
                
                // 在事务内更新（使用乐观锁）
                val newVersion = state.version + 1
                val updated = writableDatabase.update(...)
                
                if (updated == 0) {
                    throw OptimisticLockException(...)
                }
                
                writableDatabase.setTransactionSuccessful()
                return true
                
            } finally {
                writableDatabase.endTransaction()
            }
            
        } catch (e: OptimisticLockException) {
            // 重试逻辑
            attempt++
            Thread.sleep(50L * attempt)  // 指数退避
        }
    }
    return false
}
```

2. **添加事务内查询方法**:
```kotlin
private fun getGroupStateInTransaction(groupId: String): GroupV2State? {
    return writableDatabase.query(...)  // 使用 writableDatabase 而非 readableDatabase
}
```

**关键改进**:
- 查询和更新使用同一个数据库连接（writableDatabase）
- 避免嵌套的连接请求
- 保留乐观锁机制处理并发冲突
- 添加指数退避重试策略

### 修复 3: 改进错误处理

**改动文件**: `TapMessageProcessor.kt`, `GroupTransportManager.kt`

**主要改进**:

1. **添加超时保护**:
```kotlin
withTimeout(5000L) {
    val accepted = groupManager.acceptV2Proposal(groupId, accepterAci)
    ...
}
```

2. **增强日志记录**:
   - 添加详细的路由信息日志
   - 记录 groupId, groupRecipientId, senderPersonalId 的区别
   - 记录操作成功/失败的详细原因

3. **改进注释**:
   - 明确说明 senderId vs groupRecipientId 的区别
   - 解释死锁修复的原理

## 测试建议

### 基本功能测试

1. **群组消息路由测试**:
   - 创建一个3人群组
   - A 发起 v2 mode 提议
   - 验证 B 和 C 收到的通知显示在**群组会话**而非私聊
   - 点击通知应该跳转到**群组会话**

2. **群组 v2 mode 激活流程**:
   - 3人群组依次同意
   - 验证所有人的**群组会话**显示 v2 mode 激活
   - 验证**私聊界面不显示** v2 mode

3. **并发测试**:
   - 多人同时接受 v2 mode 提议
   - 验证不会出现死锁
   - 验证状态更新正确

### 压力测试

1. **数据库连接池测试**:
   - 在接收消息的同时进行其他数据库操作
   - 验证不会出现 TIMED_WAITING 死锁

2. **多群组测试**:
   - 同时在多个群组中启用 v2 mode
   - 验证系统稳定性

## 影响范围

### 修改的文件
1. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
2. `app/src/main/java/org/thoughtcrime/securesms/tap/group/database/GroupV2StatusTable.kt`
3. `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`

### 不影响的功能
- 一对一私聊的 v2 mode（完全独立实现）
- 原生群聊功能
- 其他 tap 层功能

### 可能需要更新的部分
- `GroupTokenExchangeReceiver` 需要更新以接收 `groupRecipientId` 而非 `senderId`

## 后续优化建议

1. **性能优化**:
   - 考虑增加数据库连接池大小
   - 优化长时间运行的查询

2. **用户体验**:
   - 添加更友好的错误提示
   - 改进通知的样式和内容

3. **可靠性**:
   - 添加更多的单元测试
   - 增加集成测试覆盖率

## 注意事项

1. **向后兼容性**: 
   - 本次修复不影响已有的数据
   - 旧版本客户端可能仍会有路由问题

2. **数据库迁移**:
   - 不需要数据库迁移
   - 所有修改都在应用层逻辑

3. **部署建议**:
   - 建议先在小范围测试环境验证
   - 监控数据库连接池使用情况

## 验证清单

- [x] 修复消息路由，确保群组控制消息显示在群组会话
- [x] 修复数据库死锁，合并事务避免嵌套连接
- [x] 添加辅助方法和改进错误处理
- [x] 代码编译通过，无 linter 错误
- [ ] 单元测试通过
- [ ] 集成测试验证
- [ ] 性能测试验证
- [ ] 实际环境测试

## 修复人员
AI Assistant (Claude Sonnet 4.5)

## 审核状态
待审核

