# Tap模块Signal集成工程说明

## 概述

Tap (Transport-as-a-Plugin) 模块已完整集成到Signal Android中，提供可插拔的第三方传输层。本文档说明集成架构和修改指南。

## 核心集成点

### 1. 消息发送集成
**位置**: `app/src/main/java/org/thoughtcrime/securesms/jobs/IndividualSendJob.java`

**集成逻辑** (第170-200行):
```java
// 检查TAP控制消息 -> 强制使用Signal Server
if (isTapControlMessage) {
    unidentified = deliver(message, originalEditedMessage);
} else {
    // 检查Tap通道 -> 智能路由
    TapMessageSendIntegrator integrator = TapMessageSendIntegrator.getInstance(context);
    if (integrator.canUseTapForSending(recipient.getId())) {
        unidentified = sendMessageViaTapIntegration(...);
    } else {
        unidentified = deliver(message, originalEditedMessage);
    }
}
```

**关键特性**:
- TAP_REQ/TAP_RESP等控制消息强制通过Signal Server
- 普通消息根据通道状态智能路由
- Tap失败时不回退(v2模式设计)

### 2. 消息接收集成
**处理链条**:
```
轮询发现 -> 文件下载 -> 消息解析 -> Signal适配 -> 标准处理
TapPollingService -> Provider -> TapEnvelopeAdapter -> MessageContentProcessor
```

**关键文件**:
- `tap/polling/TapPollingService.kt` - 轮询调度
- `tap/integration/TapEnvelopeAdapter.kt` - Signal适配
- `tap/integration/TapMessageProcessor.kt` - 消息路由

### 3. v2模式检测
**位置**: `messages/DataMessageProcessor.kt` (第199-206行)
```java
val isV2Mode = tapValidator.isTapV2ModeMessage(envelope, senderRecipient);
// 跳过Signal Server相关Job
if (!isV2Mode) {
    jobManager.add(SendDeliveryReceiptJob(...));
}
```

### 4. 模块初始化
**位置**: `providers/AvatarProvider.kt` (第90行)
```java
TapModuleInitializer.getInstance(application).initialize(false);
```

## 架构设计原则

### 1. 职责分离
- **Signal负责**: 端到端加密/解密
- **Tap负责**: 传输路由和存储

### 2. 智能路由
- 有Tap通道时优先使用Tap传输
- TAP控制消息强制Signal Server
- 异常情况处理和重试机制

### 3. 向后兼容
- 不影响原有Signal Server功能
- 渐进式启用Tap功能

## 开发修改指南

### 添加新Provider
1. **创建Provider实现**
```kotlin
// tap/provider/[name]/[Name]TransportProvider.kt
class NewTransportProvider : TransportProvider {
    override val providerType = "new_provider"
    override suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult
    // ... 其他实现
}
```

2. **配置描述器**
```kotlin
// tap/provider/[name]/[Name]ProviderConfigDescriptor.kt
class NewProviderConfigDescriptor : ProviderConfigDescriptor {
    override val configFields: List<ConfigField>
    override suspend fun testConfig(config: Map<String, Any>): ConfigTestResult
}
```

3. **注册Provider**
```kotlin
// tap/provider/[name]/[Name]ProviderRegistrar.kt
class NewProviderRegistrar : TransportProviderRegistrar {
    override fun register(registry: ProviderRegistry)
}
```

### 修改消息处理流程
**发送端修改**: 在`IndividualSendJob.sendMessageViaTapIntegration()`方法中
**接收端修改**: 在`TapEnvelopeAdapter.processEncryptedMessage()`方法中

### 调试和日志
**日志标签**: 搜索`TAG = Log.tag(...)`
**关键日志点**:
- `IndividualSendJob`: 发送路由决策
- `TapPollingService`: 轮询状态
- `TapEnvelopeAdapter`: 消息适配

## 关键配置

### 数据库表
- `transport_channels` - 通道状态
- `transport_tokens` - 权限凭证
- `transport_polling_states` - 轮询状态

### 核心管理器
- `TransportManager` - 传输管理
- `TransportChannelManager` - 通道管理  
- `TransportTokenPool` - Token管理
- `TapPollingService` - 轮询服务

## 故障排除

### 发送问题
1. 检查`IndividualSendJob`日志中的路由决策
2. 验证`canUseTapForSending()`返回值
3. 查看Provider连接状态

### 接收问题  
1. 检查`TapPollingService`轮询状态
2. 验证文件解析和消息适配
3. 查看`MessageContentProcessor`处理结果

### 通道问题
1. 检查`TransportChannelManager`通道状态
2. 验证TAP_REQ/TAP_RESP消息交换
3. 查看Token有效性

## 性能考虑

### 轮询优化
- 智能间隔调整: 活跃时快速，空闲时减缓
- 设备性能适配: 根据设备能力调整线程池
- 错误退避: 连续失败时增加间隔

### 内存管理
- 文件流式处理，避免大文件内存占用
- 及时清理临时文件和缓存
- 合理的连接池和线程池大小

## 安全注意事项

1. **日志脱敏**: 所有敏感信息通过`LogSanitizer`处理
2. **Token保护**: 不在日志中记录Token和密钥
3. **消息验证**: 完整的消息完整性和时间戳验证
4. **权限控制**: 严格的Provider权限和访问控制

## 测试策略

### 单元测试
- Provider功能测试
- 消息格式转换测试
- 路由逻辑测试

### 集成测试  
- 端到端消息传输
- 多设备同步
- 异常恢复测试

### 性能测试
- 轮询效率测试
- 大文件传输测试
- 并发处理能力测试

---

**最后更新**: 2024年
**维护者**: Tap模块开发团队 