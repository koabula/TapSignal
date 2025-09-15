# COS通信模块 - 阶段一实现

## 概述

本阶段实现了Signal COS混合通信架构的核心数据结构和消息格式设计。该模块提供了完整的数据结构定义、消息序列化/反序列化、路径管理和验证功能。

## 目录结构

```
coscomm/
├── data/                           # 数据结构定义
│   ├── CosMessage.kt              # COS消息数据结构
│   ├── CosRequest.kt              # COS请求和响应数据结构
│   ├── CosChannel.kt              # COS通道和子账户Pool数据结构
│   ├── CosConstants.kt            # 常量和错误定义
│   └── CosSignalMessage.kt        # Signal消息扩展
├── utils/                          # 工具类
│   ├── CosMessageSerializer.kt    # 消息序列化工具
│   ├── CosPathManager.kt          # 文件路径管理工具
│   ├── CosMessageValidator.kt     # 消息验证工具
│   └── CosMessageUtils.kt         # 综合工具类
└── README_1.md                      # 本文档
```

## 核心数据结构

### 1. COS消息 (CosMessage)

用于在COS存储中传输的消息格式，包含Double Ratchet所需的所有元数据：

```kotlin
data class CosMessage(
    val version: String = "1.0",
    val messageId: String,
    val timestamp: Long,
    val senderId: String,
    val recipientId: String,
    val messageType: MessageType,
    val ratchetInfo: RatchetInfo,
    val encryptedContent: String, // Base64编码
    val contentMetadata: ContentMetadata,
    val attachmentInfo: AttachmentInfo? = null
)
```

### 2. Ratchet信息 (RatchetInfo)

包含Double Ratchet密钥轮换所需的信息：

```kotlin
data class RatchetInfo(
    val messageNumber: Int,        // 消息序号
    val chainNumber: Int,          // 链序号
    val ratchetPublicKey: String,  // Base64编码的公钥
    val previousChainLength: Int   // 前一个链长度
)
```

### 3. COS请求 (CosRequest)

用户发送COS通信请求的数据结构：

```kotlin
data class CosRequest(
    val requestId: String,
    val timestamp: Long,
    val durationType: CosDuration,
    val accessInfo: CosAccessInfo,
    val message: String? = null
)
```

### 4. COS访问信息 (CosAccessInfo)

包含访问COS存储所需的临时凭证：

```kotlin
data class CosAccessInfo(
    val provider: String,          // "AWS" | "TENCENT" | "ALIYUN"
    val region: String,
    val bucketName: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expireTime: Long,
    val sharedDirectory: String = "/outbox/"
)
```

### 5. COS通道 (CosChannel)

管理每个联系人的COS通信通道状态：

```kotlin
data class CosChannel(
    val channelId: String,
    val recipientId: String,
    val requestId: String,
    val status: ChannelStatus,
    val establishedTime: Long?,
    val lastActivity: Long,
    val myAccessInfo: CosAccessInfo?,
    val theirAccessInfo: CosAccessInfo?,
    val statistics: ChannelStatistics,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 6. CAM Pool条目 (CamPoolEntry)

管理所有有效的CAM凭证：

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

## 核心功能

### 1. 消息序列化 (CosMessageSerializer)

提供COS消息与JSON之间的转换功能：

```kotlin
// 序列化消息
val result = CosMessageSerializer.serializeMessage(message)

// 反序列化消息
val result = CosMessageSerializer.deserializeMessage(jsonString)

// Base64编码/解码
val encoded = CosMessageSerializer.encodeBase64(data)
val decoded = CosMessageSerializer.decodeBase64(base64String)
```

### 2. 路径管理 (CosPathManager)

管理COS中的文件存储路径：

```kotlin
// 生成消息文件路径
val path = CosPathManager.generateMessageFilePath(timestamp, messageNumber, chainNumber)

// 生成附件文件路径
val path = CosPathManager.generateAttachmentFilePath(attachmentId)

// 解析文件名信息
val info = CosPathManager.parseMessageFileName(fileName)
```

### 3. 消息验证 (CosMessageValidator)

验证各种COS消息的格式和安全性：

```kotlin
// 验证COS消息
val result = CosMessageValidator.validateCosMessage(message)

// 验证COS请求
val result = CosMessageValidator.validateCosRequest(request)

// 验证COS响应
val result = CosMessageValidator.validateCosResponse(response)
```

### 4. 工具方法 (CosMessageUtils)

提供各种实用工具方法：

```kotlin
// 创建COS消息
val message = CosMessageUtils.createCosMessage(...)

// 检查消息是否过期
val expired = CosMessageUtils.isMessageExpired(message)

// 计算轮询间隔
val interval = CosMessageUtils.getPollingInterval(lastActivity)

// 格式化文件大小
val sizeStr = CosMessageUtils.formatFileSize(bytes)
```

## 文件命名规范

### 消息文件

```
格式: {timestamp}_{message_number:05d}_{chain_number:03d}_{random}.json
示例: 1640995200000_00042_003_a1b2c3d4.json
路径: outbox/messages/1640995200000_00042_003_a1b2c3d4.json
```

### 附件文件

```
格式: {attachment_id}_{random}.bin
示例: 12345678-1234-1234-1234-123456789abc_f7e8d9c0.bin
路径: outbox/attachments/12345678-1234-1234-1234-123456789abc_f7e8d9c0.bin
```

## 错误处理

### 错误码定义

```kotlin
enum class CosErrorCode(val code: Int, val message: String) {
    // 网络错误 (1000-1099)
    NETWORK_TIMEOUT(1001, "网络超时"),
    NETWORK_UNREACHABLE(1002, "网络不可达"),
    
    // 认证错误 (1100-1199)
    INVALID_CREDENTIALS(1101, "无效的访问凭证"),
    TOKEN_EXPIRED(1102, "访问令牌已过期"),
    
    // 存储错误 (1200-1299)
    BUCKET_NOT_FOUND(1201, "存储桶不存在"),
    FILE_NOT_FOUND(1202, "文件不存在"),
    
    // 消息错误 (1300-1399)
    INVALID_MESSAGE_FORMAT(1301, "无效的消息格式"),
    MESSAGE_TOO_LARGE(1302, "消息过大"),
    
    // 加密错误 (1400-1499)
    DECRYPTION_FAILED(1401, "解密失败"),
    INVALID_RATCHET_STATE(1402, "无效的Ratchet状态")
}
```

### 结果封装

```kotlin
sealed class CosResult<out T> {
    data class Success<T>(val data: T) : CosResult<T>()
    data class Error(val exception: CosException) : CosResult<Nothing>()
}
```

## 常量配置

```kotlin
object CosConstants {
    // 文件大小限制
    const val MAX_MESSAGE_SIZE = 64 * 1024 * 1024      // 64MB
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024   // 100MB
    
    // 轮询配置
    const val ACTIVE_POLLING_INTERVAL = 5000L           // 5秒
    const val INACTIVE_POLLING_INTERVAL = 30000L        // 30秒
    const val BACKGROUND_POLLING_INTERVAL = 60000L      // 60秒
    
    // 清理配置
    const val MESSAGE_RETENTION_DAYS = 30               // 消息保留30天
    const val TEMP_FILE_CLEANUP_HOURS = 24              // 临时文件24小时清理
}
```

## 使用示例

### 创建和序列化COS消息

```kotlin
// 创建Ratchet信息
val ratchetInfo = RatchetInfo(
    messageNumber = 42,
    chainNumber = 3,
    ratchetPublicKey = "base64-encoded-key",
    previousChainLength = 15
)

// 创建COS消息
val message = CosMessageUtils.createCosMessage(
    senderId = "sender-id",
    recipientId = "recipient-id",
    messageType = MessageType.TEXT,
    encryptedContent = encryptedData,
    ratchetInfo = ratchetInfo
)

// 序列化为JSON
val jsonResult = CosMessageSerializer.serializeMessage(message)
if (jsonResult.isSuccess()) {
    val jsonString = jsonResult.getOrThrow()
    // 上传到COS
}
```

### 创建COS请求

```kotlin
val request = CosMessageUtils.createCosRequest(
    provider = "AWS",
    region = "us-east-1",
    bucketName = "my-bucket",
    accessKeyId = "AKIA...",
    secretAccessKey = "secret...",
    sessionToken = "token...",
    durationType = CosDuration.ONE_WEEK,
    message = "请求建立COS通信通道"
)

// 验证请求
val validationResult = CosMessageValidator.validateCosRequest(request)
if (validationResult.isSuccess()) {
    // 发送请求
}
```

### 管理文件路径

```kotlin
// 生成消息文件路径
val filePath = CosPathManager.generateMessageFilePath(message)

// 检查路径安全性
if (CosPathManager.isPathSafe(filePath)) {
    // 上传文件
}

// 解析文件名信息
val fileName = CosPathManager.getFileName(filePath)
val fileInfo = CosPathManager.parseMessageFileName(fileName)
```

## 安全考虑

1. **数据加密**: 所有敏感数据使用Base64编码传输
2. **路径安全**: 防止路径遍历攻击
3. **消息验证**: 严格的消息格式验证
4. **凭证管理**: 临时凭证自动过期检查
5. **错误处理**: 详细的错误分类和处理

## 下一步计划

阶段一完成后，下一步将实现：

1. **阶段二**: COS通道管理系统
2. **阶段三**: 消息轮询和接收系统
3. **阶段四**: COS请求发送和处理
4. **阶段五**: 消息发送流程改造

## 测试

已创建完整的单元测试文件：`app\src\test\java\org\thoughtcrime\securesms\coscomm\CosMessageTest.kt`

测试覆盖以下功能：

1. **消息序列化/反序列化** - 验证JSON转换的正确性
2. **路径生成和解析** - 测试文件路径管理功能
3. **消息验证逻辑** - 验证各种验证规则
4. **错误处理机制** - 测试错误分类和处理
5. **工具方法功能** - 验证各种实用工具方法
6. **Base64编码** - 测试二进制数据编码
7. **数据结构创建** - 验证各种数据结构的创建和操作

## 编译状态

✅ **所有COS模块文件编译通过**
- 已修复Log类导入问题
- 无语法错误
- 无依赖问题

## 下一步计划

阶段一已完成，建议按以下顺序进行后续开发：

1. **阶段二**: COS通道管理系统
   - 通道生命周期管理
   - CAM Pool维护
   - 状态同步机制

2. **阶段三**: 消息轮询和接收系统
   - 后台轮询服务
   - 消息解密和验证
   - 数据库集成

3. **阶段四**: COS请求发送和处理
   - UI集成
   - 用户交互流程
   - 权限管理

4. **阶段五**: 消息发送流程改造
   - Hook集成
   - 路由决策
   - 性能优化
