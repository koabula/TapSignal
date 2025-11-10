# Tap WebSocket 推送改造方案

## 1. 背景与目标
- 现状：Tap 传输层仍依赖“上传至 S3/COS → 触发云函数 → WebSocket 通知 → 客户端再去 S3 拉取”的链路，离线补偿依赖轮询。
- 目标：使消息密文在上传后直接经云函数推送至接收方 Gateway_B，再由 Gateway_B 写入 WebSocket；仅在接收方离线时将密文缓存在 S3_B，附件通过预签名 URL 拉取。
- 范围：仅实现 COS provider（AWS S3/Tencent COS）路径，其它 provider 暂不改造。

## 2. 功能性需求
1. **实时通道**：消息密文生成后，发送端客户端直接触发云函数将密文和元数据同步转发给接收方的 Gateway，全程无需把文本密文写入 S3_A。
2. **离线兜底**：若 Gateway_B 无法把消息写入 WebSocket，会将密文写入接收方 S3_B，并记录 offset，待客户端上线后统一下发。
3. **附件传输**：附件仍上传到发送方 S3_A，由发送端生成单次有效的预签名 URL 并写入密文附件元数据；接收方直接使用 URL 下载（必要时向 Gateway_B 请求重签），不再依赖路径推导或 Gateway 代为缓存附件（文本密文始终不落盘到 S3_A）。
4. **群聊支持**：发送端对每个成员执行一次云函数转发，Gateway_B 负责 fan-out；消息在 S3_B 离线区按“senderId/messageId”去重。
5. **安全与审计**：客户端调用云函数必须使用短期签名；Gateway_B 记录每条消息的推送状态、离线存储状态以及下载确认。
6. **回退能力**：保留旧轮询模块作 emergency fallback，可在 server flag 下重新启用。

## 3. 非功能性约束
- **可靠性**：端到端投递成功率 ≥99.9%，离线缓存至少保留 7 天或 10 万条消息。
- **延迟**：前台实时消息从上传到 Client_B 收到 ≤ 2 秒，后台场景 ≤ 5 秒。
- **成本**：单条消息云函数和 Gateway 调用费用需控制在现有方案的 1.5 倍以内，通过批量 ACK 降低写放大。
- **安全**：任何访问密钥不得持久化在磁盘明文；所有接口使用 HMAC-SHA256 基于 ACI hash 的签名链路。

## 4. 目标架构
```
Client_A ──(密文POST+元数据)──► Lambda_A
      │                          │
      │                          ├─► Gateway_B(Webhook 入站)
      │                          │       │
      │                          │       ├─► WebSocket -> Client_B (在线)
      │                          │       └─► S3_B 离线区 (若写入失败)
      │                          │
      │                          └─► Metrics / Logs
      │
      └─(附件上传)──► S3_A ──预签名URL──► Client_B (必要时 Gateway_B 重新签名)
```

## 5. 关键组件改造
### 5.1 客户端（Sender）
- **Transport Provider**：`CosTransportProvider.push` 在构造密文 payload 后，先异步确保附件（如有）上传至 S3_A，并为每个附件生成预签名 URL/元数据，然后通过 `LambdaDispatchExecutor` 将密文、附件元数据以及 `recipientGatewayInfo` 直接 POST 到云函数，不再把文本密文写入 S3_A。
- **Credential 管理**：TransportProviderConfigManager 需新增 `lambdaEndpoint`, `lambdaApiKey`, `gatewayPublicKey` 等字段，采用 12 小时有效的 STS 凭证。
- **重试策略**：Lambda 调用失败时，消息保留在客户端 Outbox 队列并按指数退避重试；必要时可受控切换至 legacy 轮询模式，但默认不向 S3_A 写入文本密文。

### 5.2 云函数（Lambda_A）
- **入口校验**：校验 HMAC 签名、timestamp、防重放 nonce。
- **主逻辑**：根据 `recipientGatewayInfo` 把密文 POST 到 Gateway_B；收到 200/ACK 视为成功。
- **离线写入**：若 Gateway 返回 `WS_OFFLINE`，将密文上传到 S3_B 离线 bucket（使用接收方提供的写入预签名 URL 或跨账号角色）。
- **幂等性**：以 `messageId + senderId` 为幂等键，重复请求直接返回缓存结果。

### 5.3 Gateway_B
- **WebSocket 会话管理**：维护 userId→sessionId 映射，支持多设备，提供 backpressure（如 session 队列满则触发离线写入）。
- **离线队列**：S3_B 结构 `/tap-offline/{recipientHash}/{messageId}.bin`，Gateway 记录 offset 映射；Client_B 上线时发送 `SYNC_REQUEST`，Gateway 从 S3_B 批量取回并按序推送（仅缓存文本密文，附件始终由客户端依靠预签名 URL 直接获取）。
- **附件重签名服务**：提供 `/attachments/resign` 接口，根据 messageId 返回新的预签名 URL。

### 5.4 Client_B
- **WebSocket Handler**：区分 `realtime` 与 `offline` 消息；实时消息直接入库，离线消息收到后向 Gateway 发送 ACK。
- **S3_B 下载器**：保留 `NotificationDownloadExecutor`，但对象来源改为 Gateway_B 传回的 payload 或离线拉取列表。
- **附件下载器**：解析预签名 URL 直接下载；若 URL 失效则请求 Gateway 重新签名，整个流程不再访问发送方路径或依赖 Gateway 缓存。

## 6. 数据与协议变更
1. **TapTransportMessage 扩展字段**：在 `contentMetadata` 中新增 `deliveryChannel`, `attachmentsPresigned`（包含 `url`, `expiresAt`, `attachmentId`）以及 `gatewayFailoverHints`。
2. **Webhook 协议**：定义 `LambdaDispatchRequest`，含 `payload`, `signature`, `retryCount`, `offlineFallback` 信息，Gateway 必须返回 `200 + deliveryStatus`。
3. **ACK 协议**：Client_B 向 Gateway_B 发送 `DELIVERED | FAILED`，Gateway 记录并在必要时通知 Lambda 重新推送。
4. **配置同步**：`tap-state/contacts/{hash}.json` 扩展 `gatewayEndpoint`, `offlineBucket`, `presignDelegation` 字段。

## 7. TODO List
### phase1 修改发送逻辑
1. 密文不再上传至S3,而是直接调用云函数发送给接收端的Gateway.
2. 附件密文上传到自己的S3,生成预签名URL,将原来附件处理中的附件路径信息改为预签名URL
3. 修改云函数: 发送时直接调用云函数向Gateway_B发送密文

### phase2 修改tap交换信息结构
我们不再需要分享子账户给对方,但是需要分享Gateway的必要信息(似乎已经实现了一部分)

### phase3 修改接收逻辑
1. 修改api gateway: 接收到密文后,通过websocket将密文传送给Client. 如果Client离线,则将密文上传到自己的S3下,可以以时间戳命名,上传到/offline目录(或者你想到的更好的目录设计)
2. 修改client: 收到websocket推送的消息后,直接加入解密流程. 如果发现附件的预签名URL,则进行下载后,解密显示.
3. client每次上线后,需要主动查询/offline目录,获取离线时的消息,并按顺序进行解密显示

### phase4 旧逻辑清理
disable旧逻辑中的轮询,子账户,list objects等不再需要的逻辑

## 8. 风险与缓解
- **密钥泄露风险**：所有客户端调用需通过短期 STS + 端侧密钥加密存储，并在云函数端强制校验设备指纹。
- **幂等/重放**：使用 Redis/Dynamo 记录最近 24h 的 messageId，重复请求直接返回；WebSocket ACK 失败时触发告警。
- **跨云互通**：Tencent/AWS 互通需统一 Webhook 协议，云函数里抽象出 ProviderAdapter；先支持 AWS→AWS、Tencent→Tencent，再扩展跨云场景。
- **附件 URL 过期**：不做处理,超过14天没有获取的附件自动失效.
- **成本不确定**：设置 CloudWatch/CLS 监控 Lambda 调用次数与执行时长，对热点联系人可批量发送或开启压缩。

## 9. 指标与验收
- **投递成功率**：>=99.9%，以 Gateway_B 记录为准。
- **端到端延迟**：平均 < 1.5s，95 分位 < 3s。
- **离线补偿**：Client_B 上线 5 秒内完成离线同步。
- **安全审计**：每条消息拥有可追踪的调用日志（Client_A 请求 ID、Lambda 执行 ID、Gateway ACK）。

该方案提供了端到端的实现指引，可作为后续开发与灰度发布的主文档。
