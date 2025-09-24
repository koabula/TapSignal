# TAP Utils - 工具类模块

tap层传输组件的核心工具类集合，提供哈希、去重、元数据处理和日志脱敏等基础功能。

## 📁 模块结构

```
utils/
├── TransportIdHasher.kt           # ACI哈希工具
├── TransportMetadataFactory.kt    # 传输元数据工厂
├── TransportMessageDeduplicator.kt # 消息去重器
├── LogSanitizer.kt                # 日志脱敏工具
└── README.md                      # 本文档
```

## 🔧 核心组件

### TransportIdHasher.kt
**功能**: 将Signal ACI转换为安全的路径标识符
- **主要方法**: 
  - `hashAci(aci)` - 生成64位确定性哈希
  - `generateChannelHash(myAci, peerAci)` - 生成通信对标识
- **安全特性**: 多层回退机制，确保哈希生成的可靠性
- **用途**: tap层文件路径生成，通信对匹配

### TransportMetadataFactory.kt  
**功能**: 动态创建不同Provider的传输元数据实例
- **设计模式**: 工厂模式 + 注册器模式
- **扩展性**: 支持运行时注册新Provider类型
- **当前支持**: COS Provider
- **用途**: 解析和创建传输配置，支持多存储后端

### TransportMessageDeduplicator.kt
**功能**: 防止重复处理相同的传输层消息
- **存储策略**: 内存缓存 + 数据库持久化
- **性能优化**: 读写锁，分层检查，定期清理
- **数据表**: `transport_processed_messages`
- **用途**: 确保消息传输的幂等性

### LogSanitizer.kt
**功能**: 在日志输出前对敏感信息进行脱敏处理
- **脱敏范围**: 密钥、Token、URL、路径、异常信息
- **安全策略**: 前后缀保留 + 中间掩码
- **平衡性**: 兼顾安全性和可调试性
- **用途**: 保护生产环境日志安全

## 🚀 使用示例

### 生成传输路径
```kotlin
val hasher = TransportIdHasher
val pathId = hasher.hashAci(myAci)
val channelId = hasher.generateChannelHash(myAci, peerAci)
```

### 创建Provider元数据
```kotlin
val factory = TransportMetadataFactory
val metadata = factory.createFromMap("cos", configData)
```

### 消息去重检查
```kotlin
val deduplicator = TransportMessageDeduplicator.getInstance(context)
if (!deduplicator.isDuplicate(messageId, senderId, timestamp)) {
    // 处理新消息
    deduplicator.markAsProcessed(messageId, senderId, timestamp)
}
```

### 日志脱敏
```kotlin
val sanitizer = LogSanitizer
Log.d(TAG, sanitizer.sanitize(sensitiveData, "secretKey"))
```

## 🛠️ 开发指导

### 添加新Provider支持
1. 实现`TransportMetadata`接口
2. 在`TransportMetadataFactory`中注册:
   ```kotlin
   TransportMetadataFactory.registerProvider("newType") { data ->
       NewProviderMetadata.fromMap(data)
   }
   ```

### 自定义脱敏规则
1. 在`LogSanitizer.SENSITIVE_FIELD_NAMES`中添加字段名
2. 或重写`sanitize()`方法实现自定义逻辑

### 性能调优
- `TransportMessageDeduplicator`: 调整`MAX_CACHE_SIZE`和`RETENTION_PERIOD_MS`
- `TransportIdHasher`: 考虑将`HASH_LENGTH_BYTES`从8提升到16

### 安全增强
- 定期监控随机回退的触发频率
- 考虑基于设备的动态盐值生成
- 定期审查脱敏规则的完整性

## ⚠️ 注意事项

1. **哈希一致性**: `TransportIdHasher`的随机回退依赖元数据同步，确保双方能正确交换生成的哈希值
2. **数据库依赖**: 去重功能依赖`V287_TransportTablesCreation`创建的数据表
3. **线程安全**: 所有工具类都设计为线程安全，可在多线程环境使用
4. **内存管理**: 去重器会自动清理过期数据，但高负载时需监控内存使用

## 🔄 维护清单

- [ ] 定期检查哈希碰撞率
- [ ] 监控去重缓存命中率
- [ ] 验证日志脱敏效果
- [ ] 评估新Provider集成需求
- [ ] 检查数据库清理策略效果

---
*本模块为tap层基础设施，修改时请确保向后兼容性和安全性* 