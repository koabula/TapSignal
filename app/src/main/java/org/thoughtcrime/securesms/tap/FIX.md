1) 接收链路接入 Signal 主流程
取消“直接插库”与占位正文，改为与 coscomm 一致：将下载到的密文交由现有 Envelope/Job 管道处理（如 PushProcessMessageJob/Message pipeline），由 Signal 完成解密、校验与入库。
在 TapMessageProcessor 中去除简化路径，按 coscomm 的入队与回压机制对齐；支持失败重试与错误上报。
2) 消息解析与协议一致性
TapPollingService.parseTransportMessage() 目前靠文件名取 id/时间，需改为按 TaP 固定二进制格式解码（与 COS 写入一致），或完全复用 cos/coscomm 旧消息封装/解析路径，保证读写一致与前向兼容。
统一 Provider 写入与轮询解析的格式来源，禁止“猜测式解析”。
3) 去重与幂等
实现 isDuplicateMessage()：参考 coscomm 的去重键（如 serverGuid/envelopeId + 发送方 + 时间/哈希），与轮询状态中的 processedFiles 结合，确保幂等、防重放。
4) Token 安全与权限边界（遵从“长期 Token”规划）
保持长期 Token 设计，但需：
最小权限与路径前缀限制（只读/只列举目标前缀）。
validateToken() 做实际可达性与权限范围验证（list/HEAD 指定前缀）。
完整的撤销（revoke）与手动轮换通道（变更后快速失效旧 Token）。
完整脱敏日志（禁止明文 key/签名/URL 全路径）。
如需在客户端生成/分发 Token，机制与 coscomm 的 SubaccountPool 一致，避免“全局高权限凭证”。
5) 健康检查与通道激活
非 COS Provider 不能默认“健康”。为所有 Provider 定义最小健康检查（list/HEAD 某前缀），或在初期仅允许 COS，禁用其他 Provider；通道仅在实测通过后置 ACTIVE。
6) 文件识别策略
将“消息文件识别”从全局正则移至 Provider 策略/回调（与 cos 旧命名一致的匹配/前缀/后缀策略），避免误检/漏检；轮询层不得写死正则。
7) 路由/优先级硬编码清理
移除对 COS 的硬编码加权；路由优先由 TransportRoutingManager 统一决策，权重/阈值可配置（参照 coscomm 的选择逻辑与策略参数）。
8) 持久化与迁移校验
完成并校验 V287_TransportTablesCreation 的迁移注册与回滚；验证 TransportChannelTable/TransportPollingStateTable/TransportTokenTable 的序列化/反序列化与版本字段一致，确保应用升级/回退安全。
9) 轮询到交付闭环
轮询的超时/退避/重试与 coscomm 对齐（指数退避上限、网络切换降频/暂停/恢复）；下载失败不标记处理、成功后标记；批处理策略不影响幂等。
