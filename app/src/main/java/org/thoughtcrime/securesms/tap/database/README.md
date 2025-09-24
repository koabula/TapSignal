# Tap模块数据库层

## 概述

Tap模块数据库层负责传输层相关数据的持久化存储，支持多Provider架构下的通道管理、轮询状态跟踪和权限Token管理。

## 核心表结构

### 1. TransportChannelTable
**作用:** 传输通道生命周期管理
```sql
transport_channels
├── channel_id (TEXT, UNIQUE)      # 通道唯一标识
├── recipient_id (TEXT)            # 接收者ID
├── provider_type (TEXT)           # Provider类型 (cos, email等)
├── metadata_json (TEXT)           # Provider特定元数据
├── status (INTEGER)               # 通道状态枚举
├── config_json (TEXT)             # 通道配置信息
└── 统计字段 (success_count, failure_count等)
```

### 2. TransportPollingStateTable
**作用:** 轮询状态追踪和消息去重
```sql
transport_polling_state
├── recipient_id + provider_type   # 复合主键
├── processed_files_json (TEXT)    # 已处理文件列表
├── last_polling_cursor (TEXT)     # 轮询游标位置
└── 错误统计和轮询指标

transport_processed_messages       # 消息去重辅助表
├── duplication_key (TEXT, UNIQUE) # 消息去重键
└── processed_timestamp (INTEGER)  # 处理时间戳
```

### 3. TransportTokenTable
**作用:** 权限Token生命周期管理
```sql
transport_tokens
├── token_id (TEXT, UNIQUE)        # Token唯一标识
├── token_type (INTEGER)           # RECEIVED=0, SHARED=1
├── permissions_json (TEXT)        # 权限集合
├── expiration_time (INTEGER)      # 过期时间
└── 使用统计和状态字段
```

## 技术特点

### JSON序列化
- 使用Jackson通过`JsonUtils`进行安全序列化
- 支持向后兼容的数据格式迁移
- 防止JSON注入攻击

### 错误处理
- 优雅降级：JSON解析失败时使用默认值
- 枚举兼容性：忽略未知枚举值而非崩溃
- 完整异常日志记录

### 性能优化
- 完善索引策略覆盖常用查询场景
- 乐观锁版本控制防止并发冲突
- 批量操作支持和事务安全

## 开发指南

### 添加新字段
1. 修改对应Table的`CREATE_TABLE`常量
2. 更新数据类(PollingState, TokenRecord等)
3. 修改序列化/反序列化逻辑
4. 在`SignalDatabase.kt`中处理schema变更

### 扩展Provider支持
1. 新Provider的metadata序列化在`TransportMetadataFactory`中注册
2. Channel配置通过`config_json`字段存储Provider特定配置
3. Token权限枚举在`TransportPermission`中扩展

### 数据迁移原则
- 保持向后兼容性，新字段使用DEFAULT值
- JSON格式变更需支持旧格式解析
- 枚举变更使用安全的`valueOf()`包装

### 性能考虑
- 大量数据操作使用事务包装
- 定期调用清理方法(`cleanupExpired*`)
- 避免在UI线程调用数据库操作(@WorkerThread)

## 数据库初始化

表创建和索引在`SignalDatabase.kt`的`onCreateTablesIndexesAndTriggers()`方法中完成：
```kotlin
// 表创建
db.execSQL(TransportChannelTable.CREATE_TABLE)
db.execSQL(TransportPollingStateTable.CREATE_TABLE)
db.execSQL(TransportPollingStateTable.CREATE_PROCESSED_MESSAGES_TABLE)
db.execSQL(TransportTokenTable.CREATE_TABLE)

// 索引创建
executeStatements(db, TransportChannelTable.CREATE_INDEXES)
executeStatements(db, TransportPollingStateTable.CREATE_INDEXES)
executeStatements(db, TransportTokenTable.CREATE_INDEXES)
```

## 常用操作示例

### 通道管理
```java
// 创建通道
TransportChannel channel = new TransportChannel(...);
transportChannelTable.insertOrUpdateChannel(channel);

// 查询活跃通道
List<TransportChannel> channels = transportChannelTable
    .getActiveChannelsForRecipient(recipientId);
```

### 轮询状态管理
```java
// 记录成功轮询
transportPollingStateTable.recordSuccessfulPoll(
    recipientId, providerType, processedFiles, messageCount);

// 消息去重检查
if (!transportPollingStateTable.isMessageProcessed(duplicationKey)) {
    // 处理新消息
    transportPollingStateTable.markMessageAsProcessed(duplicationKey, timestamp);
}
```

### Token管理
```java
// 添加接收Token
transportTokenTable.addReceivedToken(
    recipientId, tokenId, providerType, permissions, expirationTime, tokenData);

// 获取有效Token
TokenRecord token = transportTokenTable
    .getValidReceivedToken(recipientId, providerType);
```

## 维护任务

定期执行以下清理操作以保持数据库性能：
- `cleanupExpiredStates()` - 清理过期轮询状态
- `cleanupExpiredMessages()` - 清理过期去重记录  
- `cleanupExpiredTokens()` - 清理过期Token记录
- `deleteExpiredChannels()` - 清理过期通道记录 