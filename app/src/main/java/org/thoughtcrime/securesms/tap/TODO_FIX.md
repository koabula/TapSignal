### 接入 Signal 传输层前必须修复的问题（阻断项优先）

- 【阻断项】安全与凭证管理
  - 将 Provider 配置与 Token 从明文 SharedPreferences 迁移到受保护存储（EncryptedSharedPreferences 或加密 Room），密钥使用 Android Keystore 管理。
  - 移除在客户端直接执行云厂商 CAM/IAM 账号/密钥管理（如 DeleteUser、DeleteAccessKey）；改为由可信后端执行，客户端仅请求短期受限 STS/CAM 凭证。
  - `CosProviderConfigDescriptor.testConfig()` 在远端创建的测试对象/目录需要可预测命名并保证清理（上传后删除；或使用生命周期策略/前缀批量清理）。
  - 强化 `TransportProvider.validateToken()`：对支持鉴权的 Provider 实现实际可达性与最小权限校验（路径范围、仅READ/LIST等），失败时返回具体可重试/不可重试错误。
  - 日志脱敏：禁止输出 `secretKey/accessKey/sessionToken`、签名串、完整远端路径等敏感信息（必要时做部分掩码与采样）。

- 【阻断项】通道与元数据闭环
  - 完成 `TransportChannelManager.createChannelMetadata()`：
    - 从 `TransportProviderConfigManager` 读取本端配置，从 `TransportTokenPool` 读取对端 Token/地址，生成 `TransportMetadata`（COS/其他 Provider）。
    - 规范并落地默认路径（如 `/outbox/`），支持每会话独立路径前缀。
  - 通道激活改为真实健康检查：替换 `delay(100)` 的模拟，改为针对目标 `metadata` 做一次轻量检查（如 list/HEAD/exists），失败退避，成功才设为 ACTIVE。

- 【阻断项】持久化与恢复
  - 实现通道持久化：`restoreChannelsFromDatabase()`、`saveChannelToDatabase()`、`deleteChannelFromDatabase()` 全量落地；补齐 `TransportChannelTable` 读写与 `metadata` 序列化。
  - 完成对应数据库迁移与回滚（确保与 `V287_TransportTablesCreation` 一致）。

- 【阻断项】Provider 工厂与注册
  - 提供 `TransportProviderFactory` 的实际实现；在 Tap 初始化阶段注册 `CosProviderRegistrar` 并接入 `TransportManager`（或统一通过 `ProviderRegistrationManager` -> `TransportManager`）。
  - `TransportManager.loadConfiguredProviders()` 能够通过工厂正确实例化 Provider，并与启用状态联动。

- 【阻断项】轮询到主流程的集成
  - `TapPollingService.deliverMessageToSignal()` 接入 Signal 消息入库/解密处理链路（通过适配层），确保：去重、幂等、背压与错误上报。
  - 统一使用 `listFiles + downloadFile`，清理所有对废弃 `pull()` 的依赖；在 Provider 侧完成兼容后移除 `pull()`。
  - `parseTransportMessage()` 使用与 Provider 写入完全一致的二进制格式（含版本、长度、校验），禁止临时/猜测式解析。

- 【阻断项】协议/序列化一致性
  - 固化 TaP 层消息封装格式规范（版本号、字段顺序、长度、校验），提供读写工具；各 Provider 不得私自偏移。
  - 文件命名与“消息文件”识别从全局正则改为 Provider 策略/回调，适配不同后端（避免漏检/误检）。

### 高优先级改进（投产前应完成）

- Token 池与刷新
  - `TransportTokenPool.createTokenFromData()` 支持多 Provider（至少覆盖 COS/Email/IPFS），统一使用 `TransportTokenFactory`。
  - 即将过期 Token 的刷新回调与事件通知，失败时的降级与重试策略。

- 路由与策略统一
  - 收敛路由逻辑到 `TransportRoutingManager`，`TransportManager` 仅编排调用，避免评分与策略重复实现。
  - 将 Provider 偏好、权重、阈值与基础间隔改为可配置（避免硬编码对 COS 的偏置）。

- 观测性与统计
  - `TapPollingStatus` 补齐 `activeTargets/totalTargets` 等真实数字（由任务/队列注入）；统计对接路由决策与自适应间隔学习。
  - 错误分布、限流命中、退避层级与重试次数的指标化；敏感字段脱敏后可选上报。

- 错误处理与重试
  - Provider 级错误到 `TransportError` 的精确映射，`RetryScheduled` 贯通至上层调度；指数退避与最大间隔与配置一致。
  - 网络变化/离线模式的策略切换（暂停/降频/恢复）。

- API 清理
  - 标注并在迁移完成后移除 `TransportProvider.pull()`/`groupPull()` 相关废弃接口与兼容路径。

- 文档与测试
  - 安全威胁模型与密钥/凭证管理规范文档；Provider 协议与消息封装格式说明。
  - 单元/集成/端到端测试：
    - 通道持久化与恢复、Token 池边界（过期/刷新/撤销）、
    - 轮询发现/去重/并发下载、
    - 路由策略正确性与回归、
    - 配置测试对象的创建/清理。
  - 兼容与回滚预案：从 cos/coscomm 渐进迁移与失败回退路径验证。
