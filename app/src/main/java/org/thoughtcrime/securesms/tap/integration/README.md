# TAP Integration 模块

## 概述

TAP Integration 模块负责将 TAP (Tap Authenticated Protocol) 传输层与 Signal 消息系统进行深度集成，实现加密消息通过第三方存储服务（如 COS、NAS 等）进行传输，而非通过 Signal 官方服务器。

### 核心设计原则
- **加密与传输分离**: Signal 负责端到端加密，TAP 负责传输路由
- **无回退机制**: v2 模式下仅使用 TAP 传输，不回退到 Signal 服务器
- **动态Provider管理**: 支持多种存储服务提供商，可扩展

## 架构流程

```
发送端: OutgoingMessage → Signal加密 → TAP传输层 → 存储服务
接收端: 存储服务 → TAP轮询 → Signal解密 → MessageContentProcessor
```

## 文件说明

### 核心组件

#### `TapModuleInitializer.kt`
**作用**: TAP 模块启动和初始化管理器
- 初始化核心组件 (TokenPool, ChannelManager, TransportManager)
- 注册传输 Provider 工厂
- 启动轮询服务
- 管理模块生命周期

**关键方法**:
- `initialize()`: 模块初始化入口
- `getActiveRecipientsFromTokenPool()`: 获取活跃联系人列表

#### `TapMessageSendIntegrator.kt`
**作用**: 消息发送集成器，统一的发送接口
- 检查是否可使用 TAP 传输
- 路由消息到 TAP 传输层
- 处理发送结果

**关键方法**:
- `sendMessage()`: 主要发送接口
- `canUseTapForSending()`: 检查 TAP 可用性

#### `TapSignalServiceAdapter.kt`
**作用**: Signal 服务适配器，实现"先加密，后传输"
- 使用 Signal 原生加密方法
- 构建 TransportMessage
- 处理附件转换
- 与 TAP 传输层对接

**关键方法**:
- `sendWithSignalEncryption()`: Signal 加密 + TAP 传输
- `encryptWithSignal()`: Signal 协议加密
- `buildTransportMessage()`: 构建传输消息

### 接收处理

#### `TapMessageProcessor.kt`
**作用**: TAP 消息处理器，处理接收的控制消息和数据消息
- 识别和处理 TAP 控制消息 (TAP_REQ, TAP_RESP, TAP_REVOKE)
- 管理传输通道建立和撤销
- 处理加密消息并交给适配器

**关键方法**:
- `processTapMessage()`: 处理控制消息
- `processTapTransportMessage()`: 处理传输消息
- `isTapMessage()`: 识别 TAP 消息

#### `TapEnvelopeAdapter.kt`
**作用**: 信封适配器，将 TAP 消息适配到 Signal 处理流程
- TransportMessage → Signal Envelope 转换
- 使用 Signal 标准解密流程
- 调用 MessageContentProcessor 处理
- 消息去重管理

**关键方法**:
- `processEncryptedMessage()`: 处理加密传输消息
- `adaptTransportMessageToEnvelope()`: 消息格式转换
- `decryptEnvelopeWithSignal()`: Signal 解密

#### `TapAttachmentDownloadInterceptor.kt`
**作用**: 附件下载拦截器，处理 TAP 传输的附件
- 识别 TAP 附件类型
- 拦截并下载 TAP 附件
- 保存到 Signal 存储系统

**关键方法**:
- `isTapAttachment()`: 识别 TAP 附件
- `interceptAndDownload()`: 拦截下载
- `downloadAttachmentViaTap()`: TAP 下载实现

### 验证和测试

#### `TapSignalIntegrationValidator.kt`
**作用**: 集成验证器，验证 TAP 与 Signal 的集成正确性
- 消息格式兼容性验证
- 解密链路测试
- 去重机制验证
- 错误处理验证

**关键方法**:
- `validateFullIntegration()`: 完整集成验证
- `validateContactTapStatus()`: 联系人 TAP 状态验证
- `isTapV2ModeMessage()`: v2 模式消息检测

## 开发指南

### 扩展新 Provider
1. 在 `TransportProviderFactory` 中注册新类型
2. 更新 `TapSignalIntegrationValidator` 中的 Provider 检查逻辑
3. 确保 `TapAttachmentDownloadInterceptor` 支持新 Provider 的附件格式

### 修改消息处理流程
1. 发送: 修改 `TapSignalServiceAdapter`
2. 接收: 修改 `TapEnvelopeAdapter` 和 `TapMessageProcessor`
3. 控制消息: 在 `TapMessageProcessor` 中添加新的消息类型处理

### 调试和监控
- 所有组件都提供详细日志，TAG 为类名
- 使用 `TapSignalIntegrationValidator` 进行集成测试
- 检查 `TransportTokenPool` 的活跃状态

### 注意事项
- **线程安全**: 所有组件都是线程安全的单例
- **错误处理**: 网络错误支持重试，协议错误直接失败
- **资源清理**: 组件提供 `cleanup()` 方法用于资源释放
- **时间戳验证**: 消息时间戳验证窗口为 1 小时，防止重放攻击

## 依赖关系

```
TapModuleInitializer
├── TransportManager
├── TransportTokenPool  
├── TransportChannelManager
└── TapPollingService

TapMessageSendIntegrator
├── TapSignalServiceAdapter
└── TransportChannelManager

TapMessageProcessor
├── TapEnvelopeAdapter
└── TransportTokenPool

TapEnvelopeAdapter
├── MessageContentProcessor (Signal)
└── TransportMessageDeduplicator
``` 