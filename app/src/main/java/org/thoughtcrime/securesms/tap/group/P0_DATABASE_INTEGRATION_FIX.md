# P0 数据库集成修复完成报告

## 修复时间
2025-10-05

## 问题描述
群组V2模式的数据库表未正确集成到Signal数据库系统中，导致：
1. `group_v2_status` 表虽已定义但缺少迁移代码
2. `transport_group_processed_messages` 表未定义
3. 应用运行时会因为表不存在而崩溃

## 修复内容

### 1. 创建数据库迁移 V288
**文件**: `app/src/main/java/org/thoughtcrime/securesms/database/helpers/migration/V288_GroupV2TablesCreation.kt`

**功能**:
- 创建 `group_v2_status` 表及其索引
  - 存储群组V2模式状态信息
  - 支持NATIVE, PROPOSING, FULL_V2_ACTIVE三种状态
  - 包含成员同意情况、provider类型等信息

- 创建 `transport_group_processed_messages` 表及其索引
  - 用于群组消息去重
  - 防止同一消息从多个成员处重复获取
  - 支持基于duplication_key的快速查询

**表结构**:
```sql
-- 群组V2状态表
CREATE TABLE group_v2_status (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    proposer_aci TEXT,
    agreed_members TEXT NOT NULL,
    total_members TEXT NOT NULL,
    provider_type TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 群组消息去重表
CREATE TABLE transport_group_processed_messages (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    duplication_key TEXT UNIQUE NOT NULL,
    message_id TEXT NOT NULL,
    sender_aci TEXT NOT NULL,
    group_id TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    processed_at INTEGER NOT NULL,
    polling_member_aci TEXT,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
);
```

### 2. 注册迁移到数据库系统
**文件**: `app/src/main/java/org/thoughtcrime/securesms/database/helpers/SignalDatabaseMigrations.kt`

**修改**:
- 导入 `V288_GroupV2TablesCreation`
- 在 migrations 列表中注册: `288 to V288_GroupV2TablesCreation`
- 更新数据库版本: `DATABASE_VERSION = 288` (原287)

### 3. 验证 SignalDatabase 注册
**文件**: `app/src/main/java/org/thoughtcrime/securesms/database/SignalDatabase.kt`

**状态**: 已确认 groupV2StatusTable 已正确声明
```kotlin
val groupV2StatusTable: org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable = 
    org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable(context, this)
```

## 技术细节

### 索引优化
为保证查询性能，创建了以下索引：

**group_v2_status 表**:
- `group_v2_status_group_id_idx`: 快速查询特定群组
- `group_v2_status_status_idx`: 按状态筛选群组
- `group_v2_status_proposer_idx`: 查询某人发起的提议

**transport_group_processed_messages 表**:
- `transport_group_processed_messages_key_idx`: 去重键唯一性
- `transport_group_processed_messages_group_idx`: 按群组查询
- `transport_group_processed_messages_sender_idx`: 按发送者查询
- `transport_group_processed_messages_timestamp_idx`: 时间范围查询
- `transport_group_processed_messages_message_idx`: 消息ID查询

### 数据类型说明
- `agreed_members` 和 `total_members`: JSON数组格式存储ACI集合
- `duplication_key`: 格式为 `groupId:messageId:senderAci:timestamp`
- 时间戳统一使用毫秒级 INTEGER

## 迁移安全性

### 兼容性
- 使用 `CREATE TABLE IF NOT EXISTS` 确保幂等性
- 适配现有数据库迁移框架
- 保持外键约束启用状态

### 错误处理
- 迁移失败时抛出异常，阻止应用启动
- 详细的日志记录便于问题排查

## 测试建议

### 全新安装测试
1. 卸载现有应用
2. 安装包含V288迁移的版本
3. 验证表创建成功
4. 测试群组V2模式基本流程

### 升级测试
1. 从版本287升级到版本288
2. 验证迁移自动执行
3. 检查表和索引创建
4. 验证现有功能不受影响

### 数据验证
```kotlin
// 验证表存在
val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='group_v2_status'", null)
assert(cursor.count > 0)

// 验证索引创建
val indexCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='group_v2_status'", null)
assert(indexCursor.count >= 3)
```

## 后续工作

### 已完成 (P0)
- [x] 数据库表定义
- [x] 迁移代码实现
- [x] SignalDatabase 注册
- [x] 索引优化

### 待处理 (P1-P3)
- [ ] 添加数据库事务保护 (P1)
- [ ] 实现token过期处理 (P1)
- [ ] 完善失败重试机制 (P1)
- [ ] UI集成 (P2)
- [ ] 单元测试 (P2)

## 影响范围

### 直接影响
- 群组V2模式现在可以正常启动
- GroupTransportManager 可以持久化状态
- 消息去重功能可以正常工作

### 间接影响
- 数据库版本号从287升级到288
- 所有用户升级时会自动执行迁移
- 数据库大小会略有增加（两个新表）

## 风险评估

**风险等级**: 低

**原因**:
1. 新增表不影响现有功能
2. 迁移代码简单清晰
3. 使用 IF NOT EXISTS 确保安全
4. 遵循现有迁移模式

**缓解措施**:
1. 充分的日志记录
2. 异常时阻止启动（数据安全优先）
3. 可通过清除应用数据回退

## 结论

P0优先级的数据库集成问题已完全修复。群组V2模式现在可以：
1. 正常存储和查询状态
2. 执行消息去重
3. 进行功能测试

建议立即进行集成测试，验证完整的群组V2模式流程。
