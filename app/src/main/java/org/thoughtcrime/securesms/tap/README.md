# Signal Android - Tap传输层模块

## 概述

Tap (Transport-as-a-Plugin) 是Signal Android客户端的可插拔传输层，允许用户通过第三方存储服务（如云对象存储、NAS等）进行端到端加密消息传输，而非通过Signal官方服务器。

**核心设计原则**：
- 加密与传输分离：Signal负责端到端加密，Tap负责传输路由
- 插件化架构：支持多种存储服务Provider，易于扩展
- 抽象层设计：主模块提供接口和基础实现，具体Provider在子目录实现

## 核心组件

### 主要接口和管理器

| 文件 | 功能 | 说明 |
|------|------|------|
| `TransportProvider.kt` | 传输提供者核心接口 | 定义所有Provider必须实现的方法 |
| `TransportManager.kt` | 传输管理器 | 统一的传输服务访问点，管理Provider注册和消息路由 |
| `TransportChannelManager.kt` | 通道管理器 | 管理传输通道的生命周期 |
| `TransportTokenPool.kt` | Token池管理器 | 管理权限凭证和Token生命周期 |
| `ProviderRegistry.kt` | Provider注册中心 | 动态发现和注册可用的传输Provider |

### 数据结构

| 文件 | 功能 |
|------|------|
| `TransportMessage.kt` | 传输消息数据结构，兼容Signal加密格式 |
| `TransportMetadata.kt` | 传输元数据，包含路径、Token等信息 |
| `TransportToken.kt` | 传输Token接口和实现 |
| `TransportResult.kt` | 传输操作结果封装 |
| `TransportError.kt` | 错误类型定义和异常处理 |

### 配置管理

| 文件 | 功能 |
|------|------|
| `ProviderRegistrar.kt` | Provider注册器接口 |
| `ProviderConfigDescriptor.kt` | Provider配置描述器 |
| `ConfigField.kt` | 配置字段定义 |
| `TransportConfig.kt` | 传输配置管理 |

## 子目录说明

### `provider/`
具体的传输Provider实现
- `cos/` - 云对象存储Provider（AWS S3、腾讯云COS）
- 未来可扩展其他Provider（Email、Git、NAS等）

### `integration/`
Signal集成层，实现Tap与Signal核心的对接
- 消息发送/接收集成
- Signal加密/解密适配
- 附件处理

### `polling/`
轮询系统，负责定期从Provider拉取新消息
- 智能轮询策略
- 任务调度管理
- 错误处理和重试

### `database/`
数据库层，持久化存储传输相关数据
- 通道状态管理
- Token存储
- 轮询状态跟踪

### `utils/`
工具类集合
- 日志脱敏
- ID哈希
- 消息去重
- 元数据工厂

### `ui/`
用户界面，提供Tap配置和状态显示
- 动态配置界面生成
- Provider选择
- v2模式指示器

### `factory/`
工厂模式实现，负责Provider实例创建和管理

## 开发指南

### 添加新Provider

1. 在`provider/`下创建新目录（如`email/`）
2. 实现`TransportProvider`接口
3. 创建`ProviderRegistrar`和`ProviderConfigDescriptor`
4. 添加`provider.json`配置文件
5. Provider会被自动发现和注册

### 关键设计模式

- **抽象工厂模式**：ProviderRegistry + TransportProviderFactory
- **策略模式**：不同Provider的实现策略
- **观察者模式**：轮询状态和配置变更通知
- **单例模式**：各管理器采用线程安全单例

### 并发控制

- 使用`ReentrantReadWriteLock`保证线程安全
- 协程用于异步操作，避免阻塞UI
- 单例实例采用双重检查锁定

### 安全注意事项

- 所有敏感信息必须通过`LogSanitizer`脱敏
- Token和密钥信息不得记录到日志
- 输入数据必须进行边界检查和验证

## 使用流程

### v2模式建立
1. 用户配置Provider（如COS凭证）
2. 双方交换TAP_REQ/TAP_RESP消息建立通道
3. 交换Token和元数据信息
4. 进入v2模式，消息通过Tap传输

### 消息传输
1. **发送**：Signal加密 → TransportMessage → Provider上传
2. **接收**：轮询下载 → 解析TransportMessage → Signal解密

## 配置说明

Provider配置通过`TransportProviderConfigManager`管理，支持：
- 动态配置验证
- 实时配置测试
- 配置热更新

## 故障排除

1. **Provider不可用**：检查ProviderRegistry注册状态
2. **Token失效**：查看TransportTokenPool状态
3. **通道异常**：检查TransportChannelManager日志
4. **轮询问题**：查看polling/目录下的状态信息

## 扩展建议

- 新Provider应优先考虑安全性和可靠性
- 大文件传输考虑分片上传/下载
- 网络不稳定环境增强重试机制
- 定期监控Token过期和通道健康状态

---

更多详细信息请参考各子目录的README文件。 