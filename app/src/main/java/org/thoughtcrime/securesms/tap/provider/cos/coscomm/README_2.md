# Signal COS 混合通信架构 - 阶段二实现报告

## 概述

阶段二成功实现了Signal COS混合通信架构的通道管理系统，为后续的消息轮询、请求处理和消息发送功能奠定了坚实的基础。本阶段重点关注通道状态管理、增强的CAM Pool管理、文件路径管理和消息上传下载服务。

## 实现内容

### 1. COS通道状态管理系统

#### 1.1 CosChannelManager
**位置**: `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/CosChannelManager.kt`

**核心功能**:
- 创建和管理COS通信通道
- 跟踪通道状态（PENDING、ACTIVE、EXPIRED、REVOKED等）
- 管理双向访问信息（我的CAM和对方的CAM）
- 通道活动时间和统计信息更新
- 自动清理过期和无效通道

**主要方法**:
```kotlin
// 创建新通道
fun createChannel(recipientId: String, requestId: String, status: ChannelStatus): CosChannel

// 更新通道状态
fun updateChannelStatus(recipientId: String, newStatus: ChannelStatus): Boolean

// 建立通道（双方都有访问信息时）
fun establishChannel(recipientId: String): Boolean

// 更新通道统计信息
fun updateChannelStatistics(recipientId: String, messagesSent: Int, messagesReceived: Int, ...): Boolean

// 获取活跃通道
fun getActiveChannels(): List<CosChannel>
```

#### 1.2 CosChannelStorage
**位置**: `app/src/main/java/org/thoughtcrime/securesms/coscomm/storage/CosChannelStorage.kt`

**核心功能**:
- 通道数据的持久化存储
- 基于SharedPreferences的JSON序列化存储
- 支持备份和恢复功能
- 按状态查询通道

### 2. 增强的CAM Pool管理系统

#### 2.1 CamPoolManager (增强版)
**位置**: `app/src/main/java/org/thoughtcrime/securesms/cos/CamPoolManager.kt`

**相比原有版本的增强功能**:
- **智能轮询策略**: 根据活动频率动态调整轮询间隔
- **错误处理机制**: 轮询错误计数和指数退避重试，使用CosResult错误处理
- **凭证验证**: 自动检查CAM凭证有效性
- **批量轮询支持**: 支持批量处理多个CAM条目
- **统计信息**: 提供详细的CAM Pool统计数据
- **COS错误处理**: 充分利用COS模块的错误处理机制

**智能轮询算法**:
```kotlin
fun calculatePollingInterval(camEntry: CamPoolEntry): Long {
    // 错误退避策略
    if (camEntry.pollingErrors > 0) {
        val backoffMultiplier = Math.pow(2.0, camEntry.pollingErrors.toDouble()).toLong()
        return CamPoolConstants.POLLING_ERROR_BACKOFF_BASE * backoffMultiplier
    }
    
    // 基于活动时间的动态间隔
    val timeSinceLastActivity = System.currentTimeMillis() - camEntry.lastPollingTime
    return when {
        timeSinceLastActivity < TimeUnit.MINUTES.toMillis(5) -> 5000L      // 5秒
        timeSinceLastActivity < TimeUnit.HOURS.toMillis(1) -> 30000L       // 30秒
        else -> 60000L                                                     // 1分钟
    }
}
```

#### 2.2 CamPoolStorage
**位置**: `app/src/main/java/org/thoughtcrime/securesms/coscomm/storage/CamPoolStorage.kt`

**核心功能**:
- CAM Pool条目的持久化存储
- 自动清理最旧条目以控制Pool大小
- 支持按有效性和活跃状态查询
- 备份和恢复功能

### 3. COS消息上传下载服务

#### 3.1 CosMessageService
**位置**: `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/CosMessageService.kt`

**核心功能**:
- **消息上传**: 序列化COS消息并上传到自己的COS存储桶
- **消息下载**: 从对方的COS存储桶下载并反序列化消息
- **附件处理**: 支持消息附件的上传和下载
- **重试机制**: 指数退避重试策略
- **临时客户端**: 使用CAM凭证创建临时COS客户端

**异步操作支持**:
```kotlin
// 上传消息（返回CompletableFuture）
fun uploadMessage(recipientId: String, message: CosMessage, attachmentFile: File?): CompletableFuture<CosUploadResult>

// 下载消息
fun downloadMessage(recipientId: String, messagePath: String): CompletableFuture<CosDownloadResult>

// 列举消息文件
fun listMessages(recipientId: String): CompletableFuture<CosListResult>
```

### 4. 文件路径管理系统

#### 4.1 现有CosPathManager增强
**位置**: `app/src/main/java/org/thoughtcrime/securesms/coscomm/utils/CosPathManager.kt`

**已实现的完善功能**:
- 消息文件路径生成（包含时间戳、消息序号、链序号和随机后缀）
- 附件文件路径生成
- 文件名解析和信息提取
- 路径安全性检查（防止路径遍历攻击）
- 路径规范化和验证

**文件命名规范**:
```
消息文件: /outbox/messages/{timestamp}_{message_number:05d}_{chain_number:03d}_{random}.json
附件文件: /outbox/attachments/{attachment_id}_{random}.bin
```

## 数据结构设计

### 1. 核心数据结构

#### CosChannel - 通道状态管理
```kotlin
data class CosChannel(
    val channelId: String,
    val recipientId: String,
    val requestId: String,
    val status: ChannelStatus,
    val establishedTime: Long?,
    val lastActivity: Long,
    val myAccessInfo: CosAccessInfo?,      // 我分享给对方的访问信息
    val theirAccessInfo: CosAccessInfo?,   // 对方分享给我的访问信息
    val statistics: ChannelStatistics,
    val createdAt: Long,
    val updatedAt: Long
)
```

#### CamPoolEntry - CAM Pool条目
```kotlin
data class CamPoolEntry(
    val recipientId: String,
    val accessInfo: CosAccessInfo,
    val lastPollingTime: Long,
    val pollingErrors: Int,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 2. 结果类型定义

#### 操作结果封装
```kotlin
// 上传结果
sealed class CosUploadResult {
    data class Success(val messagePath: String, val attachmentPath: String?) : CosUploadResult()
    data class Failure(val error: String) : CosUploadResult()
}

// 下载结果
sealed class CosDownloadResult {
    data class Success(val message: CosMessage, val attachmentFile: File?) : CosDownloadResult()
    data class Failure(val error: String) : CosDownloadResult()
}

// 列举结果
sealed class CosListResult {
    data class Success(val files: List<CosFileInfo>) : CosListResult()
    data class Failure(val error: String) : CosListResult()
}
```

## 技术特性

### 1. 线程安全
- 使用ConcurrentHashMap确保并发访问安全
- 单例模式确保管理器实例唯一性
- 异步操作使用线程池执行

### 2. 错误处理
- 指数退避重试机制
- 详细的错误分类和日志记录
- 优雅的降级处理

### 3. 性能优化
- 内存缓存活跃通道和CAM条目
- 智能轮询策略减少不必要的网络请求
- 批量操作支持

### 4. 数据持久化
- 基于SharedPreferences的JSON序列化存储
- 支持数据备份和恢复
- 自动清理过期数据

## 安全考虑

### 1. 访问控制
- CAM凭证有效性验证
- 临时凭证自动过期检查
- 路径安全性验证防止路径遍历攻击

### 2. 数据保护
- 敏感数据加密存储（TODO: 后续实现）
- 临时文件自动清理
- 错误信息不泄露敏感数据

## 集成说明

### 1. 依赖关系
- 依赖现有的COS模块（`org.thoughtcrime.securesms.cos`）
- 使用Signal的日志系统
- 集成Jackson JSON序列化库

### 2. 初始化
```kotlin
// 获取管理器实例
val channelManager = CosChannelManager.getInstance(context)
val camPoolManager = EnhancedCamPoolManager.getInstance(context)
val messageService = CosMessageService.getInstance(context)
```

## 后续计划

### 阶段三：消息轮询和接收系统
1. 实现基于CAM Pool的智能轮询服务
2. 消息去重和排序机制
3. Double Ratchet解密集成

### 阶段四：COS请求发送和处理
1. COS请求生成和发送功能
2. 请求接收和处理机制
3. 通道建立确认系统

### 阶段五：消息发送流程改造
1. 消息发送路由逻辑修改
2. COS消息加密和上传
3. 附件COS传输支持

## 测试建议

### 1. 单元测试
- 通道状态管理测试
- CAM Pool管理测试
- 消息序列化/反序列化测试
- 路径管理功能测试

### 2. 集成测试
- 端到端通道建立测试
- 消息上传下载测试
- 错误处理和重试测试

### 3. 性能测试
- 大量CAM条目的轮询性能
- 并发消息处理能力
- 内存使用和清理效果

## 总结

阶段二成功实现了COS通道管理系统的核心功能，为Signal COS混合通信架构提供了坚实的基础设施。主要成就包括：

1. **完善的通道管理**: 支持完整的通道生命周期管理
2. **智能CAM Pool**: 增强的凭证管理和轮询策略
3. **可靠的消息服务**: 支持重试的异步消息上传下载
4. **安全的路径管理**: 防止安全漏洞的文件路径处理

这些功能为后续阶段的实现奠定了良好的基础，特别是为消息轮询服务和COS请求处理提供了必要的管理组件。
