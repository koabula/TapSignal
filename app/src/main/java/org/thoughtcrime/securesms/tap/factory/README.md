# Factory 模块

## 概述

Factory 模块实现了传输提供者(TransportProvider)的工厂模式，负责动态创建和管理不同类型的传输服务实例。采用基于 `ProviderRegistrar` 的插件化架构，支持运行时注册新的传输服务类型。

## 核心功能

- **插件化架构**: 通过 `ProviderRegistrar` 机制支持动态注册传输服务
- **抽象层分离**: 工厂不包含具体传输服务的业务逻辑
- **配置管理**: 委托给具体 `ProviderConfigDescriptor` 处理配置验证和默认值
- **缓存优化**: 缓存配置验证结果和默认配置以提高性能

## 文件结构

```
factory/
├── DefaultTransportProviderFactory.kt  # 默认工厂实现
└── README.md                           # 本文档
```

## 核心类

### DefaultTransportProviderFactory

**职责**: 
- 实现 `TransportProviderFactory` 接口
- 管理 `ProviderRegistrar` 注册器映射
- 委托 Provider 创建和配置验证给具体注册器

**关键方法**:
- `registerProviderRegistrar()` - 注册新的传输服务类型
- `createProvider()` - 创建传输服务实例
- `validateConfig()` - 验证传输服务配置
- `getProviderConfigDescriptor()` - 获取配置描述器

## 使用方式

### 注册新的传输服务类型

```kotlin
// 创建工厂实例
val factory = DefaultTransportProviderFactory(context)

// 注册新的 Provider 类型
val emailRegistrar = EmailProviderRegistrar()
factory.registerProviderRegistrar(emailRegistrar)
```

### 创建传输服务实例

```kotlin
// 准备配置
val config = mapOf(
    "provider" to "TENCENT",
    "secretId" to "your-secret-id",
    "secretKey" to "your-secret-key"
    // ... 其他配置
)

// 验证配置
val validationResult = factory.validateConfig("cos", config)
if (validationResult is ConfigValidationResult.Valid) {
    // 创建 Provider 实例
    val provider = factory.createProvider("cos", config)
}
```

## 扩展指南

### 添加新的传输服务类型

1. **实现 ProviderRegistrar**:
```kotlin
class MyProviderRegistrar : ProviderRegistrar {
    override val providerType: String = "my-provider"
    
    override fun getConfigDescriptor(): ProviderConfigDescriptor {
        return MyProviderConfigDescriptor()
    }
    
    override fun createProvider(config: Map<String, Any>, context: Context): TransportProvider? {
        return MyTransportProvider(context, config)
    }
}
```

2. **实现 ProviderConfigDescriptor**:
```kotlin
class MyProviderConfigDescriptor : ProviderConfigDescriptor {
    override val providerType: String = "my-provider"
    override val displayName: String = "我的传输服务"
    
    override fun getConfigFields(): List<ConfigField> {
        // 定义配置字段
    }
    
    override fun validateConfig(config: Map<String, Any>): ConfigValidationResult {
        // 实现配置验证逻辑
    }
}
```

3. **注册到工厂**:
```kotlin
val factory = DefaultTransportProviderFactory(context)
factory.registerProviderRegistrar(MyProviderRegistrar())
```

## 架构原则

### 抽象层分离
- **Factory**: 纯抽象层，不包含具体业务逻辑
- **ProviderRegistrar**: 负责具体 Provider 的创建逻辑
- **ProviderConfigDescriptor**: 负责配置字段定义和验证

### 职责单一
- Factory 只负责注册管理和调度
- 配置验证委托给 ConfigDescriptor
- Provider 创建委托给 Registrar

### 开闭原则
- 添加新 Provider 类型无需修改 Factory 代码
- 通过注册机制实现扩展

## 注意事项

1. **线程安全**: 使用 `ReentrantReadWriteLock` 保证并发安全
2. **缓存管理**: 
   - 只缓存有效的配置验证结果
   - 注册/注销时自动清理相关缓存
3. **错误处理**: 所有方法都有完整的异常处理和日志记录
4. **内存管理**: 使用 `ConcurrentHashMap` 和适当的缓存策略

## 相关模块

- `../ProviderRegistrar.kt` - 注册器接口定义
- `../ProviderConfigDescriptor.kt` - 配置描述器接口
- `../provider/cos/` - COS 传输服务实现示例
- `../TransportProviderManager.kt` - Provider 管理器

## 开发建议

1. **添加新 Provider**: 参考 `cos` 包的实现模式
2. **配置验证**: 充分利用 `ConfigField` 的验证能力
3. **测试**: 实现 `ProviderConfigDescriptor.testConfig()` 方法
4. **文档**: 为新 Provider 编写完整的配置说明 