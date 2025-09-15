# COS 管理模块

## 概述

COS (Cloud Object Storage) 管理模块是为 Signal Android 应用添加的云存储功能，支持多种云存储提供商，包括 AWS S3 和腾讯云 COS。该模块提供了统一的接口来管理云存储操作，包括文件上传、下载、目录管理和临时访问凭证生成。

## 主要功能

- **多云支持**: 支持 AWS S3 和腾讯云 COS
- **统一接口**: 通过 `CosClient` 接口提供一致的 API
- **临时凭证**: 支持生成和管理临时访问令牌 (STS/CAM)
- **配置管理**: 安全的配置存储和读取
- **凭证池管理**: 智能的临时凭证缓存和过期管理

## 核心组件

### 1. 接口定义

#### CosClient
统一的云存储客户端接口，定义了所有云存储操作的标准方法：

```kotlin
interface CosClient {
    fun createDirectory(directoryPath: String): Boolean
    fun uploadFile(localFile: File, remotePath: String): Boolean
    fun downloadFile(remotePath: String, localFile: File): Boolean
    fun listFiles(directoryPath: String): List<CosFileInfo>
    fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int = 15): CosAccessToken
}
```

#### CosConfig
配置数据类，包含云存储提供商的认证信息：

```kotlin
data class CosConfig(
    val provider: Provider,      // AWS 或 TENCENT
    val secretId: String,        // 访问密钥 ID
    val secretKey: String,       // 访问密钥
    val region: String,          // 区域
    val bucketName: String       // 存储桶名称
)
```

#### CosFileInfo
文件信息数据类：

```kotlin
data class CosFileInfo(
    val key: String,           // 文件路径/键名
    val size: Long,            // 文件大小（字节）
    val lastModified: Long     // 最后修改时间（毫秒时间戳）
)
```

#### CosAccessToken
临时访问令牌数据类：

```kotlin
data class CosAccessToken(
    val accessKeyId: String,      // 临时访问密钥 ID
    val secretAccessKey: String,  // 临时访问密钥
    val sessionToken: String?,    // 会话令牌
    val expireTime: Long          // 过期时间（毫秒时间戳）
)
```

### 2. 实现类

#### AwsS3Client
AWS S3 的具体实现，支持：
- AWS Signature Version 4 签名算法
- S3 REST API 调用
- STS 临时凭证生成
- 标准 S3 操作（PUT、GET、LIST）

#### TencentCosClient
腾讯云 COS 的具体实现，支持：
- 兼容 S3 的 API 接口
- CAM 临时凭证生成
- 腾讯云特定的端点格式

### 3. 工具类

#### AwsSigner
AWS Signature Version 4 签名工具：
- SHA-256 哈希计算
- HMAC-SHA256 签名
- Authorization header 生成

#### S3XmlParser
S3 XML 响应解析器：
- ListObjects 响应解析
- 文件信息提取

#### StsXmlParser
STS XML 响应解析器：
- GetSessionToken 响应解析
- 临时凭证提取

### 4. 管理组件

#### CosClientFactory
客户端工厂类，根据配置创建对应的客户端实例：

```kotlin
object CosClientFactory {
    fun createClient(context: Context): CosClient?
}
```

#### CosConfigStorage
配置存储管理器，负责配置的持久化：

```kotlin
object CosConfigStorage {
    fun saveConfig(context: Context, config: CosConfig, camDuration: Int): Boolean
    fun getConfig(context: Context): CosConfig?
    fun getCamDuration(context: Context): Int
}
```

#### CamPoolManager
临时凭证池管理器，用于缓存和管理临时访问令牌：

```kotlin
class CamPoolManager {
    fun addSharedCam(recipientId: String, token: CosAccessToken)
    fun addReceivedCam(recipientId: String, token: CosAccessToken)
    fun getValidSharedCam(recipientId: String): CosAccessToken?
    fun getValidReceivedCam(recipientId: String): CosAccessToken?
    fun cleanExpiredCams()
}
```

#### CosModuleInitializer
模块初始化器，在应用启动时验证配置：

```kotlin
object CosModuleInitializer {
    fun initialize(context: Context)
}
```

## 使用示例

### 1. 配置云存储

```kotlin
// 创建配置
val config = CosConfig(
    provider = CosConfig.Provider.AWS,
    secretId = "your-access-key-id",
    secretKey = "your-secret-access-key",
    region = "us-east-1",
    bucketName = "your-bucket-name"
)

// 保存配置
CosConfigStorage.saveConfig(context, config, 15)
```

### 2. 使用客户端

```kotlin
// 创建客户端
val client = CosClientFactory.createClient(context)

// 上传文件
val localFile = File("/path/to/local/file.txt")
val success = client?.uploadFile(localFile, "remote/path/file.txt")

// 下载文件
val downloadFile = File("/path/to/download/file.txt")
val downloaded = client?.downloadFile("remote/path/file.txt", downloadFile)

// 列举文件
val files = client?.listFiles("remote/directory/")

// 生成临时凭证
val token = client?.generateTemporaryAccessToken("shared/directory/", 30)
```

### 3. 管理临时凭证

```kotlin
val camManager = CamPoolManager()

// 添加共享凭证
camManager.addSharedCam("recipient123", token)

// 获取有效凭证
val validToken = camManager.getValidSharedCam("recipient123")

// 清理过期凭证
camManager.cleanExpiredCams()
```

## 安全考虑

1. **配置加密**: 敏感配置信息存储在 SharedPreferences 中，建议后续加密存储
2. **临时凭证**: 使用临时凭证而非永久密钥，降低安全风险
3. **凭证过期**: 自动管理凭证过期，避免使用失效凭证
4. **权限控制**: 临时凭证可以限制访问特定目录和操作

## 扩展支持

模块设计为可扩展架构，添加新的云存储提供商只需：

1. 实现 `CosClient` 接口
2. 在 `CosConfig.Provider` 枚举中添加新提供商
3. 在 `CosClientFactory` 中添加对应的创建逻辑

## 依赖项

- OkHttp3: HTTP 客户端
- XmlPullParser: XML 解析
- Signal Core Utils: 日志和工具类

## 注意事项

1. 所有网络操作都是同步的，建议在后台线程中调用
2. 临时凭证有时效性，需要定期刷新
3. 配置信息包含敏感数据，需要妥善保护
4. 网络异常需要适当的错误处理和重试机制