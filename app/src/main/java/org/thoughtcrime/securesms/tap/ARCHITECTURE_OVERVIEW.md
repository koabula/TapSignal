# Tap模块架构概览

## 架构图
```
┌─────────────────────────────────────────────────────────────┐
│                    Signal Android                           │
├─────────────────────────────────────────────────────────────┤
│  消息发送: IndividualSendJob                                 │
│  ├── TAP控制消息 ──> Signal Server                           │
│  └── 普通消息 ──> TapMessageSendIntegrator ──> Provider      │
│                                                             │
│  消息接收: DataMessageProcessor                              │
│  ├── v2模式检测 ──> 跳过Signal Server Job                     │
│  └── 标准处理 ──> MessageContentProcessor                     │
├─────────────────────────────────────────────────────────────┤
│                     Tap模块                                 │
│  ┌─────────────────┬──────────────────┬──────────────────┐   │
│  │ 管理层           │ 集成层            │ 传输层            │   │
│  │ TransportManager│ TapEnvelopeAdapter│ CosProvider     │   │
│  │ ChannelManager  │ MessageProcessor  │ (其他Provider)   │   │
│  │ TokenPool       │ ModuleInitializer │                 │   │
│  │ PollingService  │                   │                 │   │
│  └─────────────────┴──────────────────┴──────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 关键类说明

### Signal集成点
| 类名 | 文件位置 | 作用 |
|-----|----------|------|
| `IndividualSendJob` | jobs/IndividualSendJob.java | 消息发送路由 |
| `DataMessageProcessor` | messages/DataMessageProcessor.kt | v2模式检测 |
| `TapModuleInitializer` | tap/integration/TapModuleInitializer.kt | 模块初始化 |

### 核心管理器
| 类名 | 功能 | 单例 |
|-----|------|------|
| `TransportManager` | 传输统一管理 | ✓ |
| `TransportChannelManager` | 通道生命周期 | ✓ |
| `TransportTokenPool` | 权限凭证池 | ✓ |
| `TapPollingService` | 轮询调度 | ✓ |

### 集成适配器
| 类名 | 功能 | 备注 |
|-----|------|------|
| `TapMessageSendIntegrator` | 发送集成 | 统一发送接口 |
| `TapEnvelopeAdapter` | 接收适配 | TransportMessage→Envelope |
| `TapMessageProcessor` | 消息路由 | 控制消息和数据消息 |

## 消息流程图

### 发送流程
```
用户发送消息
    ↓
IndividualSendJob.onPushSend()
    ↓
检查消息类型
    ├── TAP控制消息 → Signal Server
    └── 普通消息 → canUseTapForSending()?
                  ├── 是 → TapMessageSendIntegrator
                  │        ↓
                  │     TapSignalServiceAdapter
                  │        ↓  
                  │     TransportManager.send()
                  │        ↓
                  │     Provider.push()
                  └── 否 → Signal Server
```

### 接收流程
```
TapPollingService 轮询
    ↓
发现新文件
    ↓
Provider.downloadFile()
    ↓
Provider.parseTransportMessage()
    ↓
TapMessageProcessor.processTapTransportMessage()
    ↓
TapEnvelopeAdapter.processEncryptedMessage()
    ↓
Signal标准解密
    ↓
MessageContentProcessor.process()
    ↓
消息存储到数据库
```

## 配置和数据

### 数据库表
- `transport_channels` - 通道状态和配置
- `transport_tokens` - Token和权限信息  
- `transport_polling_states` - 轮询状态跟踪

### 关键配置
- `SignalStore.tap` - Tap模块配置存储
- Provider配置通过`TransportProviderConfigManager`管理

## Provider开发模板

### 最小实现
```kotlin
class MyTransportProvider(context: Context, config: Map<String, Any>) : TransportProvider {
    override val providerType = "my_provider"
    override val supportsAuth = true
    override val displayName = "我的传输服务"
    override val description = "自定义传输服务描述"
    
    override suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult {
        // 实现上传逻辑
    }
    
    override suspend fun listFiles(metadata: TransportMetadata): TransportResult {
        // 实现文件列表逻辑
    }
    
    override suspend fun downloadFile(file: FileInfo, metadata: TransportMetadata): TransportResult {
        // 实现下载逻辑  
    }
}
```

## 调试检查点

### 发送调试
1. `IndividualSendJob:191` - Tap路由决策
2. `TapMessageSendIntegrator:189` - 发送执行
3. Provider的`push()`方法 - 实际传输

### 接收调试
1. `TapPollingService:541` - 消息处理入口
2. `TapEnvelopeAdapter:88` - Signal适配
3. `MessageContentProcessor.process()` - 标准处理

### 通道调试
1. `TransportChannelManager.establishChannel()` - 通道建立
2. TAP_REQ/TAP_RESP消息处理 - 握手协议
3. Token有效性检查

---

**快速定位**: 
- 发送问题 → `IndividualSendJob` 
- 接收问题 → `TapPollingService`
- 配置问题 → `tap/provider/*/`
- 集成问题 → `tap/integration/` 