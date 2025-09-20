# TaP模块替代cos+coscomm实现计划

## 当前状态评估

✅ **已完成**：核心架构（阶段1-5）
- 核心接口层：TransportProvider、TransportMessage、TransportMetadata等
- 管理组件：TransportManager、TransportChannelManager、TransportTokenPool
- 轮询系统：TapPollingService、智能调度策略
- COS Provider：基础实现完成
- 配置管理：ProviderConfigManager、安全存储

🔴 **阻断问题**：需立即修复
- 客户端CAM/IAM操作安全漏洞
- Token权限验证简化实现
- 消息序列化格式不一致
- 缺少Signal主流程集成器

## 实施阶段

### 阶段1：修复阻断性问题（1-2周）

#### 1.1 安全漏洞修复（高优先级）
- **移除客户端CAM管理**
  ```kotlin
  // 删除 CosTransportProvider.revokeTencentToken()
  // 删除 CosTransportProvider.revokeAwsToken()
  override suspend fun revokeToken(token: TransportToken): Boolean {
      // 改为调用后端API或标记为撤销状态
      return markTokenAsRevoked(token)
  }
  ```

- **实现真实Token验证**
  ```kotlin
  private suspend fun validatePathPermissions(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
      // 测试只读权限：能列举允许路径，不能访问未授权路径
      // 测试路径范围限制
      // 返回真实验证结果
  }
  ```

#### 1.2 格式一致性修复
- **统一消息序列化**
  - 修改 `TransportMessage.serialize()` 使用二进制格式
  - 确保与 `CosTransportProvider.parseMessageFromFile()` 一致
  - 添加版本兼容性检查

#### 1.3 Provider实现完善
- **完善COS客户端创建**
  ```kotlin
  private fun createCosClient(cosToken: CosTransportToken): CosClient? {
      // 返回真实的CosClient实例，而非Map
      return CosClientFactory.createClientWithToken(...)
  }
  ```

### 阶段2：Signal集成层实现（2-3周）

#### 2.1 消息发送集成器
```kotlin
// 新建：TapMessageSendIntegrator.kt
class TapMessageSendIntegrator {
    /**
     * 替换 SignalMessageSendIntegrator 的tap相关功能
     */
    suspend fun sendTapMessage(message: OutgoingMessage, recipient: Recipient): SendResult
    
    /**
     * 判断是否使用tap发送
     */
    fun shouldUseTapTransport(recipient: Recipient): Boolean
    
    /**
     * 将Signal消息转换为TransportMessage
     */
    private fun convertToTransportMessage(message: OutgoingMessage): TransportMessage
}
```

#### 2.2 消息接收处理器
```kotlin
// 新建：TapMessageProcessor.kt  
class TapMessageProcessor {
    /**
     * 替换 CosMessageProcessor 功能
     */
    suspend fun processIncomingTapMessages(messages: List<TransportMessage>)
    
    /**
     * 将TransportMessage转换为Signal消息并入库
     */
    private suspend fun deliverToSignal(message: TransportMessage)
    
    /**
     * 去重和幂等处理
     */
    private fun deduplicateMessage(message: TransportMessage): Boolean
}
```

#### 2.3 轮询集成
```kotlin
// 修改：TapPollingService.kt
class TapPollingService {
    /**
     * 实现消息到Signal的投递
     */
    private suspend fun deliverMessageToSignal(message: TransportMessage) {
        messageProcessor.processIncomingTapMessages(listOf(message))
    }
}
```

#### 2.4 模块初始化器
```kotlin
// 新建：TapModuleInitializer.kt
class TapModuleInitializer {
    /**
     * 系统启动时初始化tap模块
     */
    fun initialize(context: Context) {
        // 初始化TransportManager
        // 注册Provider
        // 启动轮询服务
        // 迁移现有配置
    }
    
    /**
     * 从cos+coscomm迁移配置和数据
     */
    private fun migrateFromLegacyModules()
}
```

### 阶段3：渐进式替换（2-3周）

#### 3.1 数据库迁移
```kotlin
// 新建：V288_TapLegacyMigration.kt
class V288_TapLegacyMigration : SignalDatabaseMigration {
    override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 迁移cos配置到tap配置
        // 迁移subaccount pool到transport token pool
        // 保留原数据以备回滚
    }
}
```

#### 3.2 UI集成点替换
- **设置界面**
  ```kotlin
  // 修改：app/src/main/java/org/thoughtcrime/securesms/preferences/
  // 将COS设置替换为TaP Provider设置
  // 使用ProviderConfigUIManager自动生成UI
  ```

- **对话界面**
  ```kotlin
  // 修改消息发送逻辑调用TapMessageSendIntegrator
  // 修改v2 mode指示器显示逻辑
  ```

#### 3.3 替换关键调用点
```kotlin
// 主要替换点：
// 1. ConversationActivity -> 消息发送
// 2. IndividualSendJob -> 使用TapMessageSendIntegrator
// 3. ApplicationContext -> 启动TapPollingService
// 4. MessageSender -> 路由决策使用TransportManager
```

### 阶段4：兼容性验证（1-2周）

#### 4.1 A/B测试框架
```kotlin
// 新建：TapLegacyCompatibilityManager.kt
class TapLegacyCompatibilityManager {
    /**
     * 并行运行tap和cos逻辑，对比结果
     */
    fun runCompatibilityTest(recipient: Recipient): CompatibilityResult
    
    /**
     * 渐进式切换，支持快速回滚
     */
    fun enableTapForUser(userId: String, enable: Boolean)
}
```

#### 4.2 功能对比验证
- 消息发送成功率对比
- 轮询效率对比
- 配置迁移完整性验证
- Token管理功能验证

### 阶段5：完全替换和清理（1周）

#### 5.1 最终替换
```kotlin
// 确认所有功能正常后：
// 1. 移除cos和coscomm模块依赖
// 2. 删除相关文件和类
// 3. 清理数据库迁移代码
// 4. 更新ProGuard规则
```

#### 5.2 性能优化
- 轮询策略优化
- 内存使用优化
- 网络请求合并

## 关键里程碑

### 里程碑1：安全问题修复完成（2周）
- ✅ 移除所有客户端CAM操作
- ✅ 实现真实Token权限验证
- ✅ 统一消息序列化格式

### 里程碑2：Signal集成完成（5周）
- ✅ TapMessageSendIntegrator实现
- ✅ TapMessageProcessor实现
- ✅ 轮询到Signal入库链路打通

### 里程碑3：替换验证完成（7周）
- ✅ 关键调用点全部替换
- ✅ 功能兼容性验证通过
- ✅ 性能指标达标

### 里程碑4：生产就绪（8周）
- ✅ cos+coscomm模块完全移除
- ✅ 所有测试通过
- ✅ 文档更新完成

## 风险控制

### 技术风险
- **回滚机制**：保留原cos+coscomm模块，支持快速回滚
- **渐进式切换**：按用户/功能逐步切换，降低影响面
- **兼容性监控**：实时监控新旧实现的行为差异

### 质量保证
- **单元测试**：每个新组件100%测试覆盖
- **集成测试**：端到端消息流程测试
- **压力测试**：轮询性能和稳定性测试

### 上线策略
1. **内部测试**（1-2周）：开发人员全面测试
2. **灰度发布**（1周）：少量用户验证
3. **分批上线**（1周）：逐步扩大用户范围
4. **全量上线**（1周）：完成所有用户迁移

## 总结

**总工期**：8周
**团队配置**：2-3名开发人员
**核心原则**：安全第一、渐进替换、可快速回滚
**成功标准**：功能完全兼容、性能不降级、安全问题全面解决

这个计划确保了从当前状态到完全替代cos+coscomm模块的平稳过渡，同时解决了所有已识别的技术债务和安全问题。
