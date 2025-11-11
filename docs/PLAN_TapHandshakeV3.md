# TAP Handshake V3 与去轮询改造草案

## 1. 背景
- V2 握手仍以 COS 子账号互换为核心, 导致跨账号权限复杂、子账号配额紧张、密钥长期可用。  
- 推送/WebSocket 架构上线后, 文本密文直接经 Lambda→Gateway 传递, 子账号只剩附件上传/下载用途。  
- 离线兜底已经迁移到 `tap-offline/`, NotificationOfflineHandler 仍保留旧轮询路径, 造成重复开销。  
- 控制类消息(已读/重试回执、Typing)之前被屏蔽, 现在需要通过 TAP 通道传输, 以保证会话一致性。

## 2. 握手 V3 目标
1. **Channel Version 宣告**: `TapTokenExchangeMessage` 增加 `channelVersion` 字段, 默认发布 `3`.  
2. **GateWay-only 模式**: channelVersion ≥3 表示双方仅需交换 `gatewayConfig`、`webhookConfig`, 不再发放跨账号 Token。  
3. **双向确认**: 接收方保存远端 Gateway 配置后, 调 `TransportChannelManager.markGatewayOnlyChannel()` 将状态标记为 `FULL_ACTIVE`, 并回发 `ACCEPT` 带上本端 `gatewayConfig`。  
4. **回退策略**: 若对端仍为 V2 (channelVersion <3), 继续沿用 Token 互换逻辑, 并在配置中记录 `channelVersion=2`, 方便日后迁移脚本识别。

## 3. 数据迁移计划
| 表/存储 | 动作 |
| --- | --- |
| `transport_channels` | 在 `config` 中新增 `channelVersion`、`gatewayOnly`、`gatewayConfig`。升级通道时若检测到 V3, 直接写入并保存到 DB。 |
| `tap.provider_tokens` | 追加 `deprecatedAt` 字段, V3 通道建立后写入清理计划, 夜间批处理删除闲置 Token。 |
| `NotificationConfigManager` | 去掉联系人配置里的 `offlineBucket/presignDelegation` 等旧字段, 改为引用统一的 Gateway 配置。 |
| Lambda 配置文件 | 新增 `channelVersion` 指标, 以及 `gatewayFailoverHints` 日志, 方便网关识别来源版本。 |

## 4. Cloud Function / Gateway 升级
1. **Lambda_A**: 校验 `channelVersion`, 若 >=3 不再尝试写入对方 S3, 直接 POST Gateway, 失败时将密文写入接收方离线桶(通过对方提供的临时凭证或重签接口)。  
2. **Gateway_B**: 记录 `deliveryChannel`=`tap-websocket` vs `tap-control`, 分开统计。离线回写后返回状态码 `WS_OFFLINE`, 便于 Lambda 打指标。  
3. **WebSocket**: 在 `connectionInfo` 内带上 `channelVersion`, 供客户端判断是否需要 fallback。

## 5. 客户端协商流程
1. `TapTokenExchangeMessage` 增补 `channelVersion`、`capabilities`。发送方默认填 `TapTokenExchangeMessage.CURRENT_CHANNEL_VERSION`。  
2. 接收方在 `TapTokenExchangeReceiver` 中读取 `channelVersion`:  
   - `>=3`: 直接调用 `TransportChannelManager.markGatewayOnlyChannel()`、`NotificationConfigManager.saveContactConfig()` 并触发 `TapMessageProcessor` 生成 ACK。  
   - `<3`: 继续沿用旧逻辑(生成子账号, 启动轮询), 但在 `config.channelVersion` 中写入 2, 方便迁移。  
3. `TapMessageProcessor` 在升级通道成功后, 记录 `deliveryMode = websocket`, 下游解密/拉附件时可据此跳过轮询路径。

## 6. 离线路径合并与 QA
1. **Executor 改造**: `NotificationDownloadExecutor.syncOfflineMessages()` 不论处理成功与否都调用 `deleteOfflineMessage`, 并在失败时打印诊断, 方便 QA 检测 “删除失败是否会导致重复推送”。  
2. **服务入口**: `TapNotificationService` / `TapLifecycleIntegrator` 全部改为调用 `NotificationManager.triggerOfflineSync(reason)`，`NotificationOfflineHandler` 退场。  
3. **QA 用例**:  
   - 关闭网络 → 发送多条消息 → 恢复网络 → 验证 `tap-offline/` 对象被消费并立刻清理(CloudTrail/S3 控制台)。  
   - 人为让 `deleteOfflineMessage` 返回 false(注入 mock) → 观察日志 `离线消息删除失败` 是否出现, 并确认不会触发重复推送。  
4. **监控**: `NotificationManager` 暴露 `isOfflineSyncRunning()/getLastOfflineSyncTime()`，供健康检查/QA 脚本读取。

## 7. 去轮询工作清单
1. **代码路径梳理**  
   - 删除 `TapNotificationService` / `TapLifecycleIntegrator` 对 `NotificationOfflineHandler` 的依赖。  
   - `TapPollingService` 的 `ENABLE_POLLING` 已为 false, 但仍会创建线程池 → 将初始化移动到 legacy flag, 默认完全不启动。  
2. **数据清理**  
   - `transport_polling_states` 表改为仅在 legacy 模式写入; 新客户端上线后, 增加迁移脚本清空旧记录。  
3. **Feature Flag**  
   - 新增远程开关 `tap.polling_fallback_enabled`, QA 可在需要时重启旧逻辑。  
4. **文档同步**  
   - 更新 `PLAN_Websocket.md` 与 README, 明确轮询仅作为灾备, 默认走 WebSocket + Offline bucket。

## 8. 控制消息接入策略
1. `SignalServiceMessageSender.sendReceipt/sendRetryReceipt/sendTyping` 优先尝试经 `tapTransport` 发送, 成功则跳过 Signal Server。  
2. Android 侧删除 “Tap 模式跳过回执/typing” 的特殊分支, Job 仍照常排队, 由底层发送器决定传输通道。  
3. Typing 指示采用逐联系人判定: 能用 TAP 的先加密→送 TAP, 其余继续走 Signal Server, 避免 UX 中断。  
4. QA 覆盖:  
   - 私聊启用 TAP 后, 发送 Typing/阅读 → 另一端应实时收到; 抓包确认 Signal Server 无对应 REST 请求。  
   - 关闭 TAP 或在群聊中, 行为应与旧版一致。

## 9. 后续步骤
1. 完成 iOS/桌面端同样的握手 V3 支持。  
2. OTA 清理：发布脚本自动删除过期子账号、tap-state 旧字段。  
3. 发布阶段在灰度人群开启 `channelVersion=3`, 收集指标后再全量。  
4. 当指标稳定且 legacy flag 长期关闭后, 删除 `NotificationOfflineHandler` 与 `TapPollingService` 源码。
