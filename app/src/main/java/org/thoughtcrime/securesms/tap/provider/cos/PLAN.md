# COS Provider迁移实施计划

## 概述

将现有的cos和coscomm模块迁移到TAP架构中作为一个Provider插件，实现传输层的抽象化和标准化。

## ⚡ 重要简化 

### 🎯 无需数据兼容性
**原因：** 之前的系统没有发布使用，无需考虑数据迁移和向下兼容。
**影响：** 可以直接使用新的数据结构，大幅简化实现工作。

### 🎯 无需重新实现轮询机制  
**原因：** TAP层已经实现了完整的、先进的轮询系统。
**TAP轮询工作流程：** `TapPollingService` → `provider.pull(metadata)` → 智能调度和优化
**影响：** 我们只需实现`TransportProvider.pull()`接口，TAP自动处理轮询调度、间隔优化、错误重试等。

## 一、现有模块分析

### cos模块核心组件
- `CosConfig` - 用户配置（provider, secretId, secretKey, region, bucketName）
- `CosClient` - 统一接口（uploadFile, downloadFile, listFiles, generateTemporaryAccessToken）
- `CosAccessToken` - 临时访问令牌
- `CosClientFactory` - 支持AWS和TENCENT的客户端工厂

### coscomm模块核心组件
- `SubAccountPoolManager` - **子账户池管理器（永久凭证，无过期时间）**
- `CosPollingManager` - 智能轮询管理器
- `SignalMessageSendIntegrator` - Signal消息发送集成器
- `CosAccessInfo` - 完整访问信息（sharedDirectory="/outbox/"）

## 二、核心迁移任务

### 2.1 CosTransportProvider.kt - 主Provider实现
**功能映射（🎯 重点是pull方法）：**
```kotlin
// 基础传输方法
push() -> CosClient.uploadFile()
pull() -> CosClient.downloadFile() + listFiles() 
    ↑ 🔥 TAP轮询直接调用此方法！

// 新增群组方法
groupPush() -> 上传到自己COS的 /group/{groupId}/outbox/
groupPull() -> 轮询所有群友的 /group/{groupId}/outbox/

// 权限管理方法（适配现有SubAccountPoolManager）
generateToken() -> 适配SubAccountPoolManager的凭证生成
validateToken() -> 适配现有凭证验证逻辑
revokeToken() -> 适配现有凭证撤销逻辑
```

**TAP轮询调用流程：**
`TapPollingService` → `provider.pull(metadata)` → 返回`TransportResult` → TAP处理调度优化

### 2.2 CosTransportMetadata.kt - 元数据适配
将`CosAccessInfo`适配为`TransportMetadata`：
```kotlin
recipientId: String           // 对方ID
address: String               // COS bucket URL
token: TransportToken?        // 适配SubAccountEntry.accessInfo
path: String                  // 对应sharedDirectory "/outbox/"
providerType: String = "cos"  // 固定值
region: String                // 来自CosAccessInfo.region
bucketName: String            // 来自CosAccessInfo.bucketName
```

### 2.3 CosTransportToken.kt - Token适配
将`CosAccessInfo`凭证信息适配为`TransportToken`：
```kotlin
// ⚠️ 关键：coscomm中是永久凭证，不设过期时间
expirationTime: Long = Long.MAX_VALUE  // 永久有效

accessKeyId: String           // 来自CosAccessInfo
secretAccessKey: String       // 来自CosAccessInfo
sessionToken: String?         // 来自CosAccessInfo
```

### 2.4 CosProviderConfigDescriptor.kt - 配置字段定义
**⚠️ 重要：配置字段在代码中定义，不在provider.json中**
```kotlin
override fun getConfigFields(): List<ConfigField> {
    return listOf(
        // provider选择（AWS/TENCENT）
        ConfigField("provider", "云服务提供商", SELECT),
        // 永久凭证字段（不是临时凭证的camDuration）
        ConfigField("secretId", "访问密钥ID", TEXT),
        ConfigField("secretKey", "访问密钥", PASSWORD),
        ConfigField("region", "地域", REGION_SELECT),
        ConfigField("bucketName", "存储桶名称", TEXT),
        // ⚠️ 注意：不包含过期时间配置，因为生成的是永久凭证
    )
}
```

### 2.5 CosProviderRegistrar.kt - 注册器实现
负责Provider的注册和实例创建。

## 三、新增功能实现

### 3.1 配置测试功能
```kotlin
override suspend fun testConfig(config: Map<String, Any>): ConfigTestResult {
    // 1. 创建临时COS客户端
    // 2. 测试上传一个小文件
    // 3. 测试下载该文件
    // 4. 验证内容一致性
    // 5. 清理测试文件
    return ConfigTestResult.Success("COS连接测试成功")
}
```

### 3.2 群组支持实现
**GroupPush实现：**
- 上传到自己COS的群聊专用目录：`/group/{groupId}/outbox/`

**GroupPull实现：**
- 依次pull所有群友的群聊目录：`/group/{groupId}/outbox/`
- 聚合所有结果返回

## 四、关键注意事项

### 4.1 ⚠️ 永久凭证处理
- **coscomm中的子账户是永久凭证，不设过期时间**
- `TransportToken.expirationTime`应设为`Long.MAX_VALUE`
- 不要在配置UI中添加过期时间字段

### 4.2 ⚠️ 配置架构理解
- **配置字段定义在代码中**（`getConfigFields()`），不在provider.json中
- **provider.json只包含元数据**（Provider信息、类路径）
- TAP工作流程：发现provider.json → 加载类 → 调用getConfigFields() → 生成UI

### 4.3 ✅ 数据迁移（无需处理）
**重要简化：** 之前的系统没有发布使用，无需考虑数据兼容性
- ❌ 不需要迁移`SubAccountPoolManager`数据
- ❌ 不需要迁移`CosConfigStorage`配置
- ✅ 直接使用新的TAP数据结构

### 4.4 ✅ 轮询机制（无需重新实现）
**重要简化：** TAP已实现完整的先进轮询系统
- ❌ 不需要迁移`CosPollingManager`
- ❌ 不需要适配`IntelligentPollingStrategy`
- ✅ 只需实现`provider.pull(metadata)`接口
- 🚀 TAP自动处理：智能调度、间隔优化、错误重试、批处理等

## 五、实施步骤

### Phase 1: 核心接口实现（优先级：P0）
1. **CosTransportProvider.kt** - 实现TransportProvider接口
2. **CosTransportMetadata.kt** - 适配元数据结构
3. **CosTransportToken.kt** - 适配Token结构

### Phase 2: 配置系统实现（优先级：P1）
1. **CosProviderConfigDescriptor.kt** - 定义配置字段
2. **CosProviderRegistrar.kt** - 实现注册器
3. **provider.json** - 创建元数据文件

### Phase 3: 新增功能实现（优先级：P2）
1. **配置测试功能** - testConfig()方法
2. **群组功能支持** - groupPush()和groupPull()方法

### Phase 4: 集成和测试（优先级：P3）
1. **集成测试** - 确保功能完整性
2. **性能测试** - 验证TAP轮询优化效果

## 六、验收标准

### 6.1 功能完整性
- [ ] 支持AWS和TENCENT两种Provider
- [ ] 支持永久凭证管理（不过期）
- [ ] 支持一对一消息传输
- [ ] 支持群组消息传输
- [ ] 支持配置测试功能

### 6.2 性能要求
- [ ] 利用TAP智能轮询，性能优于原coscomm模块
- [ ] 消息发送成功率不低于原系统
- [ ] 配置UI响应时间 < 1秒
- [ ] TAP轮询调度延迟 < 100ms

## 七、风险点和缓解措施

### 7.1 ✅ 原数据丢失风险（已消除）
**原风险：** 迁移过程中数据丢失
**现状：** 无需迁移数据，直接使用新结构

### 7.2 ✅ 轮询性能风险（已消除）
**原风险：** 新架构影响轮询性能  
**现状：** TAP轮询比原系统更先进，性能提升

### 7.3 接口适配风险
**风险：** CosClient接口与TransportProvider不匹配
**缓解：** 仔细设计适配层，充分测试边界情况

## 八、后续优化方向

1. **COS传输优化** - 断点续传、压缩传输等COS特性
2. **多Provider支持** - 添加阿里云OSS、华为云OBS等
3. **故障恢复增强** - COS特有的错误处理和重试策略
4. **安全增强** - 服务端加密、访问控制精细化管理

## 🎯 工作量评估

**大幅简化后的工作量：**
- ✅ **核心工作减少70%** - 无需重写轮询和数据迁移
- ✅ **开发周期缩短** - 预计3-5个工作日完成核心功能
- ✅ **测试复杂度降低** - 专注于Provider接口测试
- ✅ **风险显著降低** - TAP轮询已经过验证，稳定可靠
