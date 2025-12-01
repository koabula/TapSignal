# Tap v3 模块

## 概述
Tap v3 是 Signal Android 的去中心化传输层实现，使用 IPFS 和 UnifiedPush 替代云厂商服务。

## Phase 1 完成状态

### 已完成
- ✅ 核心数据结构定义
  - TapV3Constants: 常量定义
  - TapV3Payload: 消息负载（Inline/IpfsRefs）
  - TapV3HandshakeInfo: 握手信息
  - TapV3Result: 统一结果类型
  
- ✅ IPFS 集成
  - IpfsGateway 接口
  - PinataGateway 实现
  - Web3StorageGateway 实现
  - IpfsGatewayManager: 多网关管理和故障切换
  
- ✅ UnifiedPush 集成
  - UnifiedPushProvider: 推送提供者
  - PushEndpointManager: 端点管理
  - PushMessageHandler: 消息接收处理
  
- ✅ 加密层
  - TapV3Crypto: AES-256-GCM 加密/解密
  - KPushManager: k_push 密钥管理
  
- ✅ 数据库层
  - TapV3ChannelTable: v3 通道表
  - IpfsContentTable: IPFS 内容跟踪表
  
- ✅ 管理器
  - TapV3Manager: v3 总管理器
  - TapV3TransportManager: 传输管理器
  
- ✅ 工具类
  - TapV3Logger: 日志工具
  - TapV3Validator: 数据验证

## 架构

```
tapv3/
├── TapV3Constants.kt           # 常量定义
├── TapV3Payload.kt             # 消息负载
├── TapV3HandshakeInfo.kt       # 握手信息
├── TapV3Result.kt              # 结果类型
├── TapV3Manager.kt             # 总管理器
├── TapV3TransportManager.kt    # 传输管理器
│
├── ipfs/                       # IPFS 子模块
│   ├── IpfsGateway.kt          # Gateway 接口
│   ├── PinataGateway.kt        # Pinata 实现
│   ├── Web3StorageGateway.kt   # Web3.Storage 实现
│   └── IpfsGatewayManager.kt   # Gateway 管理器
│
├── push/                       # UnifiedPush 子模块
│   ├── UnifiedPushProvider.kt  # 推送提供者
│   ├── PushEndpointManager.kt  # 端点管理
│   └── PushMessageHandler.kt   # 消息处理
│
├── crypto/                     # 加密子模块
│   ├── TapV3Crypto.kt          # 加密工具
│   └── KPushManager.kt         # k_push 管理
│
├── database/                   # 数据库子模块
│   ├── TapV3ChannelTable.kt    # 通道表
│   └── IpfsContentTable.kt     # 内容表
│
└── utils/                      # 工具子模块
    ├── TapV3Logger.kt          # 日志工具
    └── TapV3Validator.kt       # 验证工具
```

## 核心功能

### 消息发送
```kotlin
val manager = TapV3TransportManager.getInstance(context)
val result = manager.sendMessage(
    recipientId = "...",
    signalEncrypted = encryptedData,
    attachments = listOf(...)
)
```

### 消息接收
```kotlin
val result = manager.receiveMessage(
    encryptedData = pushData,
    senderId = "..."
)
```

## 下一步

### Phase 2: 协议层实现
- [ ] TapV3HandshakeManager: 握手管理
- [ ] TapV3MessageCodec: 消息编解码
- [ ] TapV3ControlMessage: 控制消息

### Phase 3: 端到端消息传输
- [ ] 集成到 IndividualSendJob
- [ ] 集成到消息接收流程
- [ ] 附件处理

### Phase 4: UI 和用户体验
- [ ] TapV3ConfigActivity: 配置界面
- [ ] TapV3HandshakeDialog: 握手对话框
- [ ] TapV3StatusIndicator: 状态指示器

### Phase 5: 测试和优化
- [ ] 单元测试
- [ ] 集成测试
- [ ] 性能优化

## 注意事项

1. **数据库集成**: 需要在 SignalDatabase 中注册 TapV3ChannelTable 和 IpfsContentTable
2. **依赖项**: 需要添加 UnifiedPush Android SDK 依赖
3. **权限**: 需要网络权限和 UnifiedPush 相关权限
4. **SignalStore**: 需要在 SignalStore 中添加 tapV3 存储
