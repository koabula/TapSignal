# COS Provider - 云对象存储传输提供商

## 概述

COS Provider是Signal Android TAP (Transport-as-a-Plugin) 系统的云对象存储实现，支持通过AWS S3和腾讯云COS进行消息传输。该模块实现了完整的文件上传、下载、权限管理和用户认证功能。

## 功能特性

- ✅ 支持AWS S3和腾讯云COS两种云存储服务
- ✅ 完整的文件CRUD操作（创建、读取、更新、删除）
- ✅ 子用户权限管理（IAM/CAM集成）
- ✅ 临时凭证和永久凭证支持
- ✅ 路径级权限控制
- ✅ 配置验证和连接测试
- ✅ 群组消息支持

## 目录结构

```
cos/
├── CosTransportProvider.kt        # 主Provider实现，TAP接口适配
├── CosTransportMetadata.kt        # COS专用元数据结构
├── CosProviderConfigDescriptor.kt # 配置字段定义和验证
├── CosProviderRegistrar.kt        # Provider注册和生命周期管理
├── provider.json                  # Provider元信息配置
├── utils/
│   ├── auth/                      # 权限管理模块
│   │   ├── CosSubUserManager.kt   # 子用户管理接口
│   │   ├── AwsSubUserManager.kt   # AWS IAM子用户管理
│   │   └── TencentSubUserManager.kt # 腾讯云CAM子用户管理
│   ├── client/                    # 客户端实现
│   │   ├── CosClient.kt           # 统一客户端接口
│   │   ├── CosClientFactory.kt    # 客户端工厂
│   │   ├── aws/                   # AWS S3客户端实现
│   │   └── tencent/               # 腾讯云COS客户端实现
│   └── common/                    # 通用组件
│       ├── CosConfig.kt           # 配置数据结构
│       ├── CosException.kt        # 异常定义
│       └── ...                    # 其他通用类型
└── docs/                          # 文档和计划文件
```

## 核心组件

### 1. CosTransportProvider
主要的Provider实现类，实现TAP的`TransportProvider`接口：
- `push()` - 消息发送
- `listFiles()` / `downloadFile()` - 消息接收
- `uploadFile()` - 文件上传
- `validateToken()` - 凭证验证

### 2. 客户端层 (utils/client/)
- **CosClient接口** - 统一的云存储操作接口
- **AwsS3Client** - AWS S3 API实现，使用Signature V4签名
- **TencentCosClient** - 腾讯云COS SDK实现

### 3. 权限管理 (utils/auth/)
- **CosSubUserManager接口** - 子用户管理抽象
- **AwsSubUserManager** - AWS IAM用户和策略管理
- **TencentSubUserManager** - 腾讯云CAM用户和策略管理

## 开发指导

### 添加新的云服务提供商

1. **扩展Provider枚举**
   ```kotlin
   // CosConfig.kt
   enum class Provider {
       AWS,
       TENCENT,
       NEW_PROVIDER  // 添加新的提供商
   }
   ```

2. **实现客户端接口**
   ```kotlin
   // utils/client/newprovider/NewProviderClient.kt
   class NewProviderClient(private val config: CosConfig) : CosClient {
       override fun uploadFile(localFile: File, remotePath: String): Boolean { ... }
       override fun downloadFile(remotePath: String, localFile: File): Boolean { ... }
       // 实现其他接口方法
   }
   ```

3. **实现权限管理**
   ```kotlin
   // utils/auth/NewProviderSubUserManager.kt
   class NewProviderSubUserManager(private val config: CosConfig) : CosSubUserManager {
       // 实现子用户管理接口
   }
   ```

4. **更新工厂类**
   ```kotlin
   // CosClientFactory.kt
   fun createClient(config: CosConfig, context: Context? = null): CosClient {
       return when (config.provider) {
           CosConfig.Provider.AWS -> AwsS3Client(config)
           CosConfig.Provider.TENCENT -> TencentCosClient(config, context)
           CosConfig.Provider.NEW_PROVIDER -> NewProviderClient(config)
       }
   }
   ```

### 修改配置字段

在`CosProviderConfigDescriptor.kt`中修改`getConfigFields()`方法：

```kotlin
override fun getConfigFields(): List<ConfigField> {
    return listOf(
        // 现有字段...
        ConfigField(
            key = "newField",
            displayName = "新配置项",
            fieldType = ConfigFieldType.TEXT,
            isRequired = true
        )
    )
}
```

### 自定义权限策略

修改`CosSubUserManager`实现中的权限定义：

```kotlin
enum class CosPermission(val actions: List<String>) {
    READ_ONLY(listOf("GetObject", "HeadObject")),
    READ_WRITE(listOf("GetObject", "PutObject", "DeleteObject")),
    CUSTOM_PERMISSION(listOf("自定义权限列表"))
}
```

## 配置示例

### AWS S3配置
```kotlin
val config = mapOf(
    "provider" to "AWS",
    "secretId" to "AKIAIOSFODNN7EXAMPLE",
    "secretKey" to "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    "region" to "us-east-1",
    "bucketName" to "my-signal-bucket"
)
```

### 腾讯云COS配置
```kotlin
val config = mapOf(
    "provider" to "TENCENT",
    "secretId" to "AKIDQjz3ltompVjBni1XjVpS6v1TEDKgqvH5W",
    "secretKey" to "BQYIM75p8x0iWVFSWi4pBPLEr7FT1SJ8gw",
    "region" to "ap-beijing",
    "bucketName" to "my-signal-bucket-1250000000"
)
```

## 测试和调试

### 启用详细日志
```kotlin
// 在代码中添加调试日志
Log.d(TAG, "COS操作详情: ${LogSanitizer.sanitize(details)}")
```

### 配置测试
使用`CosProviderConfigDescriptor.testConfig()`进行配置验证：
```kotlin
val result = configDescriptor.testConfig(config)
when (result) {
    is ConfigTestResult.Success -> println("配置有效")
    is ConfigTestResult.Failed -> println("配置失败: ${result.error}")
}
```

### 权限测试
通过`validateToken()`方法测试凭证权限：
```kotlin
val validationResult = provider.validateToken(cosToken)
```

## 注意事项

1. **安全性**
   - 所有敏感信息都通过`LogSanitizer`进行脱敏处理
   - 使用临时凭证而非永久密钥（推荐）
   - 实施最小权限原则

2. **错误处理**
   - 网络错误应该标记为可重试(`retryable = true`)
   - 权限错误应该标记为不可重试(`retryable = false`)
   - 临时文件必须在finally块中清理

3. **性能优化**
   - 大文件上传考虑使用分片上传
   - 合理设置连接超时和读取超时
   - 复用HTTP客户端连接

4. **依赖管理**
   - 腾讯云SDK版本需要与项目其他依赖兼容
   - 注意OkHttp版本兼容性
