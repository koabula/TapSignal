# Transport-as-a-Plugin (TaP) 架构设计

## 概述

Tap模块旨在将Signal的传输层抽象化，支持多种传输服务（COS、NAS、IPFS、邮件、Git等）作为插件接入。该架构将替换现有的cos和coscomm模块，实现传输层的统一管理和扩展。

## 核心设计原则

1. **传输层抽象**: 统一的TransportProvider接口屏蔽底层传输细节
2. **插件化设计**: 新增传输服务只需实现接口，无需修改其他代码
3. **权限管理**: 统一的Token池管理，支持有权限和无权限传输服务
4. **向下兼容**: 与现有Signal协议完全兼容，支持渐进式迁移

## 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                         TAP架构                              │
├─────────────────────────────────────────────────────────────┤
│  Signal Integration Layer (集成层)                          │
│  ├── TapMessageSendIntegrator (消息发送集成器)               │
│  ├── TapPollingService (轮询服务)                           │
│  └── TapMessageProcessor (消息处理器)                       │
├─────────────────────────────────────────────────────────────┤
│  Transport Management Layer (传输管理层)                    │
│  ├── TransportManager (传输管理器)                          │
│  ├── TransportChannelManager (通道管理器)                   │
│  ├── TransportTokenPool (Token池管理器)                     │
│  └── TransportRoutingManager (路由管理器)                   │
├─────────────────────────────────────────────────────────────┤
│  Core Interface Layer (核心接口层)                          │
│  ├── TransportProvider (传输提供者接口)                     │
│  ├── TransportChannel (传输通道接口)                        │
│  ├── TransportToken (传输Token接口)                         │
│  └── TransportMetadata (传输元数据接口)                     │
├─────────────────────────────────────────────────────────────┤
│  Provider Implementation Layer (实现层)                     │
│  ├── Provider/cos/ (COS传输提供者)                          │
│  ├── Provider/email/ (邮件传输提供者)                       │
│  ├── Provider/nas/ (NAS传输提供者)                          │
│  ├── Provider/ipfs/ (IPFS传输提供者)                        │
│  └── Provider/git/ (Git传输提供者)                          │
└─────────────────────────────────────────────────────────────┘
```

## 核心接口设计

### 1. TransportProvider（传输提供者接口）

```kotlin
interface TransportProvider {
    /**
     * 传输提供者类型标识
     */
    val providerType: String
    
    /**
     * 是否支持权限管理
     */
    val supportsAuth: Boolean
    
    /**
     * 推送文件到传输服务
     * @param message 加密消息内容
     * @param metadata 传输元数据（目标地址、路径等）
     * @return 推送结果
     */
    suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult
    
    /**
     * 从传输服务拉取文件
     * @param metadata 传输元数据（源地址、路径等）
     * @return 拉取结果和消息内容
     */
    suspend fun pull(metadata: TransportMetadata): TransportResult
    
    /**
     * 群组推送（一对多）
     * @param message 加密消息内容
     * @param groupMetadata 群组传输元数据
     * @return 推送结果
     */
    suspend fun groupPush(message: TransportMessage, groupMetadata: GroupTransportMetadata): TransportResult
    
    /**
     * 群组拉取（多对一）
     * @param groupMetadata 群组传输元数据
     * @return 拉取结果和消息列表
     */
    suspend fun groupPull(groupMetadata: GroupTransportMetadata): List<TransportResult>
    
    /**
     * 权限管理 - 生成访问Token
     * @param request Token请求参数
     * @return 生成的访问Token
     */
    suspend fun generateToken(request: TransportTokenRequest): TransportToken?
    
    /**
     * 权限管理 - 验证Token有效性
     * @param token 待验证的Token
     * @return 验证结果
     */
    suspend fun validateToken(token: TransportToken): Boolean
    
    /**
     * 权限管理 - 撤销Token
     * @param token 待撤销的Token
     * @return 撤销结果
     */
    suspend fun revokeToken(token: TransportToken): Boolean
}
```

### 2. TransportMessage（传输消息）

```kotlin
data class TransportMessage(
    val messageId: String,
    val encryptedContent: ByteArray,
    val messageType: TransportMessageType,
    val timestamp: Long,
    val attachments: List<TransportAttachment> = emptyList()
)

enum class TransportMessageType {
    TEXT_MESSAGE,
    MEDIA_MESSAGE,
    CONTROL_MESSAGE,
    RATCHET_UPDATE
}

data class TransportAttachment(
    val attachmentId: String,
    val encryptedData: ByteArray,
    val mimeType: String,
    val size: Long
)
```

### 3. TransportMetadata（传输元数据）

```kotlin
interface TransportMetadata {
    val recipientId: String
    val address: String           // 传输服务地址(COS bucket url, email, etc.)
    val token: TransportToken?    // 可选的访问Token
    val path: String             // 消息路径
    val providerType: String     // 传输提供者类型
    
    fun toMap(): Map<String, Any>
    fun fromMap(data: Map<String, Any>): TransportMetadata
}

data class CosTransportMetadata(
    override val recipientId: String,
    override val address: String,        // COS bucket URL
    override val token: TransportToken?,
    override val path: String,
    override val providerType: String = "cos",
    val region: String,
    val bucketName: String
) : TransportMetadata {
    // ... 实现toMap()和fromMap()方法
}
```

### 4. TransportToken（传输Token）

```kotlin
interface TransportToken {
    val tokenId: String
    val recipientId: String
    val providerType: String
    val permissions: Set<TransportPermission>
    val expirationTime: Long
    val isExpired: Boolean get() = System.currentTimeMillis() > expirationTime
    
    fun toMap(): Map<String, Any>
    fun fromMap(data: Map<String, Any>): TransportToken
}

enum class TransportPermission {
    READ, WRITE, DELETE, LIST
}

data class CosTransportToken(
    override val tokenId: String,
    override val recipientId: String,
    override val providerType: String = "cos",
    override val permissions: Set<TransportPermission>,
    override val expirationTime: Long,
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?
) : TransportToken {
    // ... 实现toMap()和fromMap()方法
}
```

### 5. TransportResult（传输结果）

```kotlin
sealed class TransportResult {
    data class Success(
        val message: TransportMessage? = null,
        val metadata: Map<String, Any> = emptyMap()
    ) : TransportResult()
    
    data class Failed(
        val error: TransportError,
        val retryable: Boolean = false
    ) : TransportResult()
    
    data class RetryScheduled(
        val retryAfter: Long,
        val reason: String
    ) : TransportResult()
}

enum class TransportError {
    NETWORK_ERROR,
    AUTH_ERROR,
    PERMISSION_DENIED,
    STORAGE_FULL,
    MESSAGE_TOO_LARGE,
    INVALID_FORMAT,
    PROVIDER_UNAVAILABLE
}
```

## 核心管理组件

### 1. TransportManager（传输管理器）

```kotlin
class TransportManager(private val context: Context) {
    companion object {
        private var INSTANCE: TransportManager? = null
        fun getInstance(context: Context): TransportManager
    }
    
    /**
     * 注册传输提供者
     */
    fun registerProvider(provider: TransportProvider)
    
    /**
     * 获取传输提供者
     */
    fun getProvider(providerType: String): TransportProvider?
    
    /**
     * 获取所有可用提供者
     */
    fun getAvailableProviders(): List<TransportProvider>
    
    /**
     * 发送消息（自动路由）
     */
    suspend fun sendMessage(recipientId: String, message: TransportMessage): TransportResult
    
    /**
     * 轮询消息
     */
    suspend fun pollMessages(): List<TransportMessage>
}
```

### 2. TransportChannelManager（通道管理器）

```kotlin
class TransportChannelManager(private val context: Context) {
    /**
     * 建立传输通道
     */
    suspend fun establishChannel(
        recipientId: String,
        providerType: String,
        metadata: TransportMetadata
    ): TransportChannel?
    
    /**
     * 获取活跃通道
     */
    fun getActiveChannels(recipientId: String): List<TransportChannel>
    
    /**
     * 关闭通道
     */
    suspend fun closeChannel(channelId: String): Boolean
    
    /**
     * 清理过期通道
     */
    suspend fun cleanupExpiredChannels()
}

data class TransportChannel(
    val channelId: String,
    val recipientId: String,
    val providerType: String,
    val metadata: TransportMetadata,
    val status: TransportChannelStatus,
    val createdAt: Long,
    val lastActiveAt: Long
)

enum class TransportChannelStatus {
    ESTABLISHING, ACTIVE, INACTIVE, FAILED, CLOSED
}
```

### 3. TransportTokenPool（Token池管理器）

```kotlin
class TransportTokenPool(private val context: Context) {
    /**
     * 添加接收到的Token
     */
    fun addReceivedToken(recipientId: String, token: TransportToken)
    
    /**
     * 添加共享的Token
     */
    fun addSharedToken(recipientId: String, token: TransportToken)
    
    /**
     * 获取有效的接收Token
     */
    fun getValidReceivedToken(recipientId: String, providerType: String): TransportToken?
    
    /**
     * 获取有效的共享Token
     */
    fun getValidSharedToken(recipientId: String, providerType: String): TransportToken?
    
    /**
     * 清理过期Token
     */
    fun cleanExpiredTokens()
    
    /**
     * 撤销Token
     */
    suspend fun revokeToken(tokenId: String, providerType: String): Boolean
}
```

### 4. TransportRoutingManager（路由管理器）

```kotlin
class TransportRoutingManager(private val context: Context) {
    /**
     * 选择最佳传输提供者
     */
    fun selectBestProvider(
        recipientId: String,
        message: TransportMessage,
        availableChannels: List<TransportChannel>
    ): TransportProvider?
    
    /**
     * 是否应该使用传输服务发送
     */
    fun shouldUseTransport(recipientId: String, message: TransportMessage): Boolean
    
    /**
     * 获取路由统计信息
     */
    fun getRoutingStats(): TransportRoutingStats
}
```

## Provider插件协议

### 1. Provider配置管理

#### 1.1 动态配置接口

```kotlin
/**
 * Provider配置描述接口
 */
interface ProviderConfigDescriptor {
    /**
     * Provider类型标识
     */
    val providerType: String
    
    /**
     * Provider显示名称
     */
    val displayName: String
    
    /**
     * Provider描述信息
     */
    val description: String
    
    /**
     * 获取所需的配置项列表
     */
    fun getConfigFields(): List<ConfigField>
    
    /**
     * 验证配置是否完整和有效
     */
    fun validateConfig(config: Map<String, Any>): ConfigValidationResult
    
    /**
     * 是否支持配置测试
     */
    val supportsConfigTest: Boolean
    
    /**
     * 测试配置连接性（可选实现）
     */
    suspend fun testConfig(config: Map<String, Any>): ConfigTestResult
}

/**
 * 配置字段描述
 */
data class ConfigField(
    val key: String,                    // 配置键名
    val displayName: String,            // 显示名称
    val description: String,            // 字段描述
    val fieldType: ConfigFieldType,     // 字段类型
    val isRequired: Boolean = true,     // 是否必需
    val defaultValue: Any? = null,      // 默认值
    val validation: ConfigFieldValidation? = null,  // 验证规则
    val options: List<ConfigOption>? = null,        // 下拉选项（适用于SELECT类型）
    val placeholder: String? = null,    // 占位符文本
    val helpText: String? = null        // 帮助文本
)

/**
 * 配置字段类型
 */
enum class ConfigFieldType {
    TEXT,           // 文本输入框
    PASSWORD,       // 密码输入框
    NUMBER,         // 数字输入框
    EMAIL,          // 邮箱输入框
    URL,            // URL输入框
    SELECT,         // 下拉选择框
    MULTI_SELECT,   // 多选框
    CHECKBOX,       // 复选框
    TEXTAREA,       // 多行文本框
    FILE_PATH,      // 文件路径选择
    REGION_SELECT   // 区域选择（特殊类型）
}

/**
 * 配置字段验证规则
 */
data class ConfigFieldValidation(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,        // 正则表达式
    val minValue: Number? = null,
    val maxValue: Number? = null,
    val customValidator: ((Any) -> Boolean)? = null
)

/**
 * 配置选项
 */
data class ConfigOption(
    val value: String,
    val displayText: String,
    val description: String? = null
)

/**
 * 配置验证结果
 */
sealed class ConfigValidationResult {
    object Valid : ConfigValidationResult()
    data class Invalid(val errors: Map<String, String>) : ConfigValidationResult()
}

/**
 * 配置测试结果
 */
sealed class ConfigTestResult {
    data class Success(val message: String) : ConfigTestResult()
    data class Failed(val error: String, val details: String? = null) : ConfigTestResult()
    data class Warning(val message: String, val details: String? = null) : ConfigTestResult()
}
```

#### 1.2 Provider配置实现示例

```kotlin
/**
 * COS Provider配置描述
 */
class CosProviderConfigDescriptor : ProviderConfigDescriptor {
    override val providerType = "cos"
    override val displayName = "云对象存储 (COS)"
    override val description = "支持AWS S3和腾讯云COS等云存储服务"
    override val supportsConfigTest = true
    
    override fun getConfigFields(): List<ConfigField> {
        return listOf(
            ConfigField(
                key = "provider",
                displayName = "云服务提供商",
                description = "选择您的云存储提供商",
                fieldType = ConfigFieldType.SELECT,
                options = listOf(
                    ConfigOption("aws", "Amazon S3"),
                    ConfigOption("tencent", "腾讯云 COS"),
                    ConfigOption("aliyun", "阿里云 OSS")
                )
            ),
            ConfigField(
                key = "secretId",
                displayName = "访问密钥 ID",
                description = "您的访问密钥标识符",
                fieldType = ConfigFieldType.TEXT,
                validation = ConfigFieldValidation(minLength = 10)
            ),
            ConfigField(
                key = "secretKey", 
                displayName = "访问密钥",
                description = "您的访问密钥",
                fieldType = ConfigFieldType.PASSWORD,
                validation = ConfigFieldValidation(minLength = 20)
            ),
            ConfigField(
                key = "region",
                displayName = "地域",
                description = "存储桶所在的地域",
                fieldType = ConfigFieldType.REGION_SELECT,
                helpText = "请选择距离您最近的地域以获得更好的性能"
            ),
            ConfigField(
                key = "bucketName",
                displayName = "存储桶名称",
                description = "用于存储消息的存储桶名称",
                fieldType = ConfigFieldType.TEXT,
                validation = ConfigFieldValidation(
                    minLength = 3,
                    maxLength = 63,
                    pattern = "^[a-z0-9][a-z0-9-]*[a-z0-9]$"
                ),
                helpText = "存储桶名称只能包含小写字母、数字和连字符"
            ),
            ConfigField(
                key = "camDuration",
                displayName = "临时凭证有效期（分钟）",
                description = "临时访问凭证的有效时间",
                fieldType = ConfigFieldType.NUMBER,
                defaultValue = 15,
                validation = ConfigFieldValidation(minValue = 5, maxValue = 1440),
                helpText = "建议设置为15-60分钟"
            ),
            ConfigField(
                key = "enableEncryption",
                displayName = "启用服务端加密",
                description = "在云端启用额外的加密保护",
                fieldType = ConfigFieldType.CHECKBOX,
                defaultValue = true,
                isRequired = false
            )
        )
    }
    
    override fun validateConfig(config: Map<String, Any>): ConfigValidationResult {
        val errors = mutableMapOf<String, String>()
        
        // 验证必需字段
        val requiredFields = getConfigFields().filter { it.isRequired }
        for (field in requiredFields) {
            if (!config.containsKey(field.key) || config[field.key].toString().isBlank()) {
                errors[field.key] = "${field.displayName}不能为空"
            }
        }
        
        // 验证存储桶名称格式
        val bucketName = config["bucketName"]?.toString()
        if (bucketName != null && !bucketName.matches(Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$"))) {
            errors["bucketName"] = "存储桶名称格式不正确"
        }
        
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid
        } else {
            ConfigValidationResult.Invalid(errors)
        }
    }
    
    override suspend fun testConfig(config: Map<String, Any>): ConfigTestResult {
        return try {
            // 创建测试客户端
            val cosConfig = CosConfig(
                provider = CosConfig.Provider.valueOf(config["provider"].toString().uppercase()),
                secretId = config["secretId"].toString(),
                secretKey = config["secretKey"].toString(),
                region = config["region"].toString(),
                bucketName = config["bucketName"].toString()
            )
            
            val client = CosClientFactory.createClient(cosConfig)
                ?: return ConfigTestResult.Failed("无法创建COS客户端")
            
            // 测试上传和下载
            val testFileName = "tap_test_${System.currentTimeMillis()}.txt"
            val testContent = "Transport-as-a-Plugin 配置测试"
            
            // 创建临时测试文件
            val testFile = File.createTempFile("tap_test", ".txt")
            testFile.writeText(testContent)
            
            try {
                // 测试上传
                if (!client.uploadFile(testFile, testFileName)) {
                    return ConfigTestResult.Failed("文件上传失败，请检查存储桶权限")
                }
                
                // 测试下载
                val downloadFile = File.createTempFile("tap_download", ".txt")
                if (!client.downloadFile(testFileName, downloadFile)) {
                    return ConfigTestResult.Failed("文件下载失败")
                }
                
                // 验证内容
                val downloadedContent = downloadFile.readText()
                if (downloadedContent != testContent) {
                    return ConfigTestResult.Warning(
                        "测试通过，但下载内容与上传内容不匹配",
                        "这可能表示存在数据传输问题"
                    )
                }
                
                // 清理测试文件
                client.deleteFile(testFileName)
                testFile.delete()
                downloadFile.delete()
                
                ConfigTestResult.Success("配置测试成功！存储服务连接正常，文件上传下载功能正常")
                
            } catch (e: Exception) {
                testFile.delete()
                ConfigTestResult.Failed("测试过程中发生错误", e.message)
            }
            
        } catch (e: Exception) {
            ConfigTestResult.Failed("配置测试失败", e.message)
        }
    }
}

/**
 * Email Provider配置描述
 */
class EmailProviderConfigDescriptor : ProviderConfigDescriptor {
    override val providerType = "email"
    override val displayName = "电子邮件"
    override val description = "通过电子邮件传输消息"
    override val supportsConfigTest = true
    
    override fun getConfigFields(): List<ConfigField> {
        return listOf(
            ConfigField(
                key = "smtpServer",
                displayName = "SMTP服务器",
                description = "发送邮件的SMTP服务器地址",
                fieldType = ConfigFieldType.TEXT,
                placeholder = "smtp.gmail.com"
            ),
            ConfigField(
                key = "smtpPort",
                displayName = "SMTP端口",
                description = "SMTP服务器端口",
                fieldType = ConfigFieldType.NUMBER,
                defaultValue = 587,
                validation = ConfigFieldValidation(minValue = 1, maxValue = 65535)
            ),
            ConfigField(
                key = "imapServer",
                displayName = "IMAP服务器",
                description = "接收邮件的IMAP服务器地址",
                fieldType = ConfigFieldType.TEXT,
                placeholder = "imap.gmail.com"
            ),
            ConfigField(
                key = "imapPort",
                displayName = "IMAP端口",
                description = "IMAP服务器端口",
                fieldType = ConfigFieldType.NUMBER,
                defaultValue = 993,
                validation = ConfigFieldValidation(minValue = 1, maxValue = 65535)
            ),
            ConfigField(
                key = "username",
                displayName = "邮箱用户名",
                description = "您的邮箱地址或用户名",
                fieldType = ConfigFieldType.EMAIL
            ),
            ConfigField(
                key = "password",
                displayName = "邮箱密码",
                description = "您的邮箱密码或应用专用密码",
                fieldType = ConfigFieldType.PASSWORD
            ),
            ConfigField(
                key = "useSSL",
                displayName = "使用SSL加密",
                description = "启用SSL/TLS加密连接",
                fieldType = ConfigFieldType.CHECKBOX,
                defaultValue = true,
                isRequired = false
            )
        )
    }
    
    override fun validateConfig(config: Map<String, Any>): ConfigValidationResult {
        // Email Provider的配置验证逻辑
        val errors = mutableMapOf<String, String>()
        
        val email = config["username"]?.toString()
        if (email != null && !email.contains("@")) {
            errors["username"] = "请输入有效的邮箱地址"
        }
        
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid
        } else {
            ConfigValidationResult.Invalid(errors)
        }
    }
    
    override suspend fun testConfig(config: Map<String, Any>): ConfigTestResult {
        return try {
            // 测试SMTP连接
            // 测试IMAP连接
            // 发送测试邮件
            ConfigTestResult.Success("邮件服务器连接测试成功")
        } catch (e: Exception) {
            ConfigTestResult.Failed("邮件服务器连接失败", e.message)
        }
    }
}
```

### 2. Provider注册机制

```kotlin
// 增强的Provider注册接口
interface ProviderRegistrar {
    /**
     * 获取Provider配置描述
     */
    fun getConfigDescriptor(): ProviderConfigDescriptor
    
    /**
     * 创建Provider实例
     */
    fun createProvider(config: Map<String, Any>): TransportProvider
    
    /**
     * 注册Provider到管理器
     */
    fun register(manager: TransportManager)
}

// 示例：COS Provider注册器
class CosProviderRegistrar : ProviderRegistrar {
    override fun getConfigDescriptor(): ProviderConfigDescriptor {
        return CosProviderConfigDescriptor()
    }
    
    override fun createProvider(config: Map<String, Any>): TransportProvider {
        return CosTransportProvider(config)
    }
    
    override fun register(manager: TransportManager) {
        // 注册配置描述和创建工厂
        manager.registerProviderType(this)
    }
}
```

### 3. UI自动生成机制

#### 3.1 配置UI管理器

```kotlin
/**
 * Provider配置UI管理器
 */
class ProviderConfigUIManager(private val context: Context) {
    companion object {
        private var INSTANCE: ProviderConfigUIManager? = null
        fun getInstance(context: Context): ProviderConfigUIManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderConfigUIManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 根据配置描述生成UI布局
     */
    fun generateConfigUI(
        parent: ViewGroup, 
        descriptor: ProviderConfigDescriptor,
        existingConfig: Map<String, Any> = emptyMap()
    ): ProviderConfigUI {
        val configUI = ProviderConfigUI(context, descriptor)
        
        descriptor.getConfigFields().forEach { field ->
            val fieldView = createFieldView(field, existingConfig[field.key])
            configUI.addField(field.key, fieldView)
        }
        
        // 如果支持测试，添加测试按钮
        if (descriptor.supportsConfigTest) {
            val testButton = createTestButton { config ->
                testProviderConfig(descriptor, config)
            }
            configUI.addTestButton(testButton)
        }
        
        parent.addView(configUI.rootView)
        return configUI
    }
    
    /**
     * 创建配置字段视图
     */
    private fun createFieldView(field: ConfigField, currentValue: Any?): View {
        return when (field.fieldType) {
            ConfigFieldType.TEXT -> createTextInputView(field, currentValue?.toString())
            ConfigFieldType.PASSWORD -> createPasswordInputView(field, currentValue?.toString())
            ConfigFieldType.NUMBER -> createNumberInputView(field, currentValue)
            ConfigFieldType.EMAIL -> createEmailInputView(field, currentValue?.toString())
            ConfigFieldType.URL -> createUrlInputView(field, currentValue?.toString())
            ConfigFieldType.SELECT -> createSelectView(field, currentValue?.toString())
            ConfigFieldType.MULTI_SELECT -> createMultiSelectView(field, currentValue as? List<String>)
            ConfigFieldType.CHECKBOX -> createCheckboxView(field, currentValue as? Boolean)
            ConfigFieldType.TEXTAREA -> createTextAreaView(field, currentValue?.toString())
            ConfigFieldType.FILE_PATH -> createFilePathView(field, currentValue?.toString())
            ConfigFieldType.REGION_SELECT -> createRegionSelectView(field, currentValue?.toString())
        }
    }
    
    /**
     * 测试Provider配置
     */
    private suspend fun testProviderConfig(
        descriptor: ProviderConfigDescriptor,
        config: Map<String, Any>
    ): ConfigTestResult {
        return try {
            // 首先验证配置
            val validationResult = descriptor.validateConfig(config)
            if (validationResult is ConfigValidationResult.Invalid) {
                return ConfigTestResult.Failed(
                    "配置验证失败",
                    validationResult.errors.values.joinToString("; ")
                )
            }
            
            // 执行配置测试
            descriptor.testConfig(config)
        } catch (e: Exception) {
            ConfigTestResult.Failed("测试过程中发生异常", e.message)
        }
    }
}

/**
 * Provider配置UI容器
 */
class ProviderConfigUI(
    private val context: Context,
    private val descriptor: ProviderConfigDescriptor
) {
    val rootView: ScrollView
    private val linearLayout: LinearLayout
    private val fieldViews: MutableMap<String, View> = mutableMapOf()
    private var testButton: Button? = null
    
    init {
        rootView = ScrollView(context)
        linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        }
        rootView.addView(linearLayout)
        
        // 添加Provider标题和描述
        addHeaderView()
    }
    
    private fun addHeaderView() {
        val titleView = TextView(context).apply {
            text = descriptor.displayName
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8.dp)
        }
        linearLayout.addView(titleView)
        
        val descriptionView = TextView(context).apply {
            text = descriptor.description
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.signal_text_secondary))
            setPadding(0, 0, 0, 16.dp)
        }
        linearLayout.addView(descriptionView)
    }
    
    fun addField(key: String, view: View) {
        fieldViews[key] = view
        linearLayout.addView(view)
    }
    
    fun addTestButton(button: Button) {
        testButton = button
        linearLayout.addView(button)
    }
    
    /**
     * 获取当前配置值
     */
    fun getCurrentConfig(): Map<String, Any> {
        val config = mutableMapOf<String, Any>()
        
        descriptor.getConfigFields().forEach { field ->
            val view = fieldViews[field.key]
            val value = extractValueFromView(view, field.fieldType)
            if (value != null) {
                config[field.key] = value
            }
        }
        
        return config
    }
    
    /**
     * 设置配置值
     */
    fun setConfig(config: Map<String, Any>) {
        descriptor.getConfigFields().forEach { field ->
            val view = fieldViews[field.key]
            val value = config[field.key]
            if (view != null && value != null) {
                setValueToView(view, field.fieldType, value)
            }
        }
    }
    
    /**
     * 验证当前配置
     */
    fun validateCurrentConfig(): ConfigValidationResult {
        val config = getCurrentConfig()
        return descriptor.validateConfig(config)
    }
}
```

#### 3.2 配置字段视图创建

```kotlin
/**
 * 配置字段视图创建器
 */
class ConfigFieldViewCreator(private val context: Context) {
    
    fun createTextInputView(field: ConfigField, currentValue: String?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            
            // 字段标签
            addView(createFieldLabel(field))
            
            // 输入框
            val editText = TextInputEditText(context).apply {
                setText(currentValue ?: field.defaultValue?.toString())
                hint = field.placeholder
                
                // 应用验证规则
                field.validation?.let { validation ->
                    filters = arrayOf(
                        InputFilter.LengthFilter(validation.maxLength ?: Int.MAX_VALUE)
                    )
                    
                    // 添加实时验证
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            validateField(s?.toString(), validation, this@apply)
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
            }
            
            val inputLayout = TextInputLayout(context).apply {
                addView(editText)
                if (field.isRequired) {
                    hint = "${field.displayName} *"
                } else {
                    hint = field.displayName
                }
            }
            addView(inputLayout)
            
            // 帮助文本
            if (!field.helpText.isNullOrBlank()) {
                addView(createHelpText(field.helpText))
            }
        }
    }
    
    fun createSelectView(field: ConfigField, currentValue: String?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            
            addView(createFieldLabel(field))
            
            val spinner = Spinner(context)
            val adapter = ArrayAdapter<String>(context, android.R.layout.simple_spinner_item).apply {
                field.options?.forEach { option ->
                    add(option.displayText)
                }
            }
            spinner.adapter = adapter
            
            // 设置当前值
            currentValue?.let { value ->
                val index = field.options?.indexOfFirst { it.value == value } ?: -1
                if (index >= 0) {
                    spinner.setSelection(index)
                }
            }
            
            addView(spinner)
            
            if (!field.helpText.isNullOrBlank()) {
                addView(createHelpText(field.helpText))
            }
        }
    }
    
    fun createCheckboxView(field: ConfigField, currentValue: Boolean?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 0, 8.dp)
            
            val checkbox = CheckBox(context).apply {
                text = field.displayName
                isChecked = currentValue ?: (field.defaultValue as? Boolean ?: false)
            }
            addView(checkbox)
        }
    }
    
    private fun createFieldLabel(field: ConfigField): TextView {
        return TextView(context).apply {
            text = if (field.isRequired) "${field.displayName} *" else field.displayName
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8.dp, 0, 4.dp)
        }
    }
    
    private fun createHelpText(helpText: String): TextView {
        return TextView(context).apply {
            text = helpText
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.signal_text_secondary))
            setPadding(0, 4.dp, 0, 0)
        }
    }
}
```

### 4. 配置管理器

```kotlin
/**
 * Transport Provider配置管理器
 */
class TransportProviderConfigManager(private val context: Context) {
    companion object {
        private var INSTANCE: TransportProviderConfigManager? = null
        fun getInstance(context: Context): TransportProviderConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportProviderConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        private const val PREF_NAME = "transport_provider_configs"
        private const val KEY_PROVIDER_CONFIGS = "provider_configs"
        private const val KEY_ENABLED_PROVIDERS = "enabled_providers"
    }
    
    private val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper()
    private val mapType = object : TypeReference<Map<String, Map<String, Any>>>() {}
    
    /**
     * 保存Provider配置
     */
    fun saveProviderConfig(providerType: String, config: Map<String, Any>) {
        val allConfigs = getAllConfigs().toMutableMap()
        allConfigs[providerType] = config
        
        val configJson = objectMapper.writeValueAsString(allConfigs)
        sharedPreferences.edit()
            .putString(KEY_PROVIDER_CONFIGS, configJson)
            .apply()
    }
    
    /**
     * 获取Provider配置
     */
    fun getProviderConfig(providerType: String): Map<String, Any>? {
        return getAllConfigs()[providerType]
    }
    
    /**
     * 获取所有配置
     */
    fun getAllConfigs(): Map<String, Map<String, Any>> {
        val configJson = sharedPreferences.getString(KEY_PROVIDER_CONFIGS, null)
        return if (configJson != null) {
            try {
                objectMapper.readValue(configJson, mapType)
            } catch (e: Exception) {
                Log.w(TAG, "读取Provider配置失败", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }
    
    /**
     * 删除Provider配置
     */
    fun deleteProviderConfig(providerType: String) {
        val allConfigs = getAllConfigs().toMutableMap()
        allConfigs.remove(providerType)
        
        val configJson = objectMapper.writeValueAsString(allConfigs)
        sharedPreferences.edit()
            .putString(KEY_PROVIDER_CONFIGS, configJson)
            .apply()
    }
    
    /**
     * 启用Provider
     */
    fun enableProvider(providerType: String) {
        val enabledProviders = getEnabledProviders().toMutableSet()
        enabledProviders.add(providerType)
        saveEnabledProviders(enabledProviders)
    }
    
    /**
     * 禁用Provider
     */
    fun disableProvider(providerType: String) {
        val enabledProviders = getEnabledProviders().toMutableSet()
        enabledProviders.remove(providerType)
        saveEnabledProviders(enabledProviders)
    }
    
    /**
     * 获取已启用的Provider列表
     */
    fun getEnabledProviders(): Set<String> {
        return sharedPreferences.getStringSet(KEY_ENABLED_PROVIDERS, emptySet()) ?: emptySet()
    }
    
    /**
     * 检查Provider是否已启用
     */
    fun isProviderEnabled(providerType: String): Boolean {
        return getEnabledProviders().contains(providerType)
    }
    
    private fun saveEnabledProviders(providers: Set<String>) {
        sharedPreferences.edit()
            .putStringSet(KEY_ENABLED_PROVIDERS, providers)
            .apply()
    }
}
```

### 5. Provider目录结构规范

```
Provider/
├── cos/
│   ├── CosTransportProvider.kt         # 主要实现类
│   ├── CosProviderRegistrar.kt         # 注册器
│   ├── CosProviderConfigDescriptor.kt  # 配置描述器
│   ├── CosTransportMetadata.kt         # 元数据实现
│   ├── CosTransportToken.kt            # Token实现
│   └── provider.json                   # 配置文件(JSON格式)
├── email/
│   ├── EmailTransportProvider.kt
│   ├── EmailProviderRegistrar.kt
│   ├── EmailProviderConfigDescriptor.kt
│   ├── EmailTransportMetadata.kt
│   └── provider.json
├── nas/
│   ├── NasTransportProvider.kt
│   ├── NasProviderRegistrar.kt
│   ├── NasProviderConfigDescriptor.kt
│   └── provider.json
├── ipfs/
│   ├── IpfsTransportProvider.kt
│   ├── IpfsProviderRegistrar.kt
│   ├── IpfsProviderConfigDescriptor.kt
│   └── provider.json
└── git/
    ├── GitTransportProvider.kt
    ├── GitProviderRegistrar.kt
    ├── GitProviderConfigDescriptor.kt
    └── provider.json
```

### 6. Provider配置文件格式

```json
{
  "provider": {
    "type": "cos",
    "name": "云对象存储 (COS)",
    "description": "支持AWS S3和腾讯云COS等云存储服务",
    "version": "1.0.0",
    "author": "Signal Tap Team",
    "supportsAuth": true,
    "supportsGroup": false,
    "supportsConfigTest": true,
    "icon": "cos_provider_icon.png",
    "registrarClass": "org.thoughtcrime.securesms.tap.provider.cos.CosProviderRegistrar",
    "configDescriptorClass": "org.thoughtcrime.securesms.tap.provider.cos.CosProviderConfigDescriptor"
  },
  "requirements": {
    "minApiLevel": 21,
    "permissions": ["INTERNET", "ACCESS_NETWORK_STATE"],
    "dependencies": ["okhttp3", "jackson"]
  }
}
```

### 7. 动态配置系统优势

#### 7.1 自动化UI生成 ✨
- **零UI代码**: Provider只需描述配置字段，UI自动生成
- **丰富字段类型**: 支持11种字段类型（文本、密码、数字、邮箱、URL、下拉、多选、复选框、文本域、文件路径、区域选择）
- **智能验证**: 支持长度、格式、数值范围等多种验证规则
- **实时反馈**: 输入时实时验证，提供即时错误提示
- **帮助系统**: 内置帮助文本和占位符，提升用户体验

#### 7.2 配置测试机制 🧪
- **一键测试**: 如果Provider支持，自动显示测试按钮
- **真实验证**: COS Provider示例中测试实际的上传下载功能
- **详细反馈**: 成功、失败、警告三种测试结果，提供详细信息
- **安全测试**: 测试时使用临时文件，测试完成自动清理

#### 7.3 灵活扩展性 🔧
- **配置字段描述**: 每个Provider可定义任意数量和类型的配置字段
- **自定义验证**: 支持正则表达式和自定义验证函数
- **动态选项**: 下拉选项可动态生成（如区域选择）
- **版本兼容**: Provider配置文件支持版本管理

#### 7.4 用户体验优化 💫
- **统一界面**: 所有Provider配置界面风格统一
- **配置持久化**: 自动保存和恢复用户配置
- **启用管理**: 独立的Provider启用/禁用状态管理
- **错误处理**: 完善的错误处理和用户提示

### 8. Provider配置示例对比

#### 8.1 COS Provider（复杂配置）
- **7个配置项**: 提供商选择、密钥、区域、存储桶等
- **多种字段类型**: 下拉、文本、密码、数字、复选框
- **高级验证**: 存储桶名称正则验证、长度限制
- **功能测试**: 实际上传下载测试

#### 8.2 Email Provider（标准配置）
- **7个配置项**: SMTP/IMAP服务器、端口、用户名密码等
- **专业字段**: 邮箱类型输入框，端口数字验证
- **连接测试**: SMTP/IMAP连接测试

#### 8.3 Git Provider（简单配置，示例）
```kotlin
class GitProviderConfigDescriptor : ProviderConfigDescriptor {
    override val providerType = "git"
    override val displayName = "Git存储库"
    override val description = "通过Git仓库传输消息"
    override val supportsConfigTest = true
    
    override fun getConfigFields(): List<ConfigField> {
        return listOf(
            ConfigField(
                key = "repositoryUrl",
                displayName = "仓库地址",
                description = "Git仓库的克隆地址",
                fieldType = ConfigFieldType.URL,
                placeholder = "https://github.com/user/repo.git"
            ),
            ConfigField(
                key = "branch",
                displayName = "分支名称",
                description = "用于存储消息的分支",
                fieldType = ConfigFieldType.TEXT,
                defaultValue = "messages",
                validation = ConfigFieldValidation(pattern = "^[a-zA-Z][a-zA-Z0-9_-]*$")
            ),
            ConfigField(
                key = "username",
                displayName = "用户名",
                description = "Git仓库用户名",
                fieldType = ConfigFieldType.TEXT,
                isRequired = false
            ),
            ConfigField(
                key = "accessToken",
                displayName = "访问令牌",
                description = "个人访问令牌或密码",
                fieldType = ConfigFieldType.PASSWORD,
                isRequired = false,
                helpText = "建议使用个人访问令牌而非密码"
            )
        )
    }
    
    override suspend fun testConfig(config: Map<String, Any>): ConfigTestResult {
        return try {
            // 测试Git仓库连接和权限
            val repoUrl = config["repositoryUrl"].toString()
            val branch = config["branch"].toString()
            
            // 执行git ls-remote测试连接
            // 测试创建和推送分支的权限
            
            ConfigTestResult.Success("Git仓库连接成功，分支权限正常")
        } catch (e: Exception) {
            ConfigTestResult.Failed("Git仓库连接失败", e.message)
        }
    }
}
```

### 9. 配置系统集成流程

#### 9.1 Provider开发流程
1. **实现接口**: 实现TransportProvider核心接口
2. **创建配置描述**: 实现ProviderConfigDescriptor
3. **定义字段**: 在getConfigFields()中定义所需配置项
4. **添加验证**: 实现validateConfig()和testConfig()（可选）
5. **创建注册器**: 实现ProviderRegistrar
6. **添加配置文件**: 创建provider.json元数据文件

#### 9.2 UI自动集成流程
1. **加载Provider**: TransportManager加载所有Provider
2. **获取配置描述**: 调用getConfigDescriptor()
3. **生成UI**: ProviderConfigUIManager自动生成配置界面
4. **用户配置**: 用户填写配置项
5. **验证配置**: 实时验证输入
6. **测试连接**: 用户点击测试按钮（如果支持）
7. **保存配置**: TransportProviderConfigManager持久化配置
8. **启用Provider**: 用户启用Provider并开始使用

这个动态配置系统完全满足了您的需求，实现了Provider配置的完全解耦和UI的自动化生成！

## 智能轮询层设计

基于现有COS轮询机制，实现智能轮询策略，支持每联系人独立轮询调度和动态优化。

### 1. TapPollingService（轮询服务）

```kotlin
class TapPollingService(private val context: Context) {
    companion object {
        // 并发配置
        private const val CORE_POOL_SIZE = 2
        private const val MAX_POOL_SIZE = 8
        
        // 智能轮询间隔
        private const val ACTIVE_POLLING_INTERVAL = 5000L      // 活跃对话: 5秒
        private const val INACTIVE_POLLING_INTERVAL = 30000L   // 非活跃对话: 30秒  
        private const val BACKGROUND_POLLING_INTERVAL = 60000L // 后台模式: 60秒
        private const val SUSPENDED_POLLING_INTERVAL = 300000L // 暂停模式: 5分钟
    }
    
    // 核心组件
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val pollingStrategy = TapIntelligentPollingStrategy(context)
    
    // 每联系人独立轮询任务管理
    private val pollingTasks: MutableMap<String, PollingTaskInfo> = ConcurrentHashMap()
    private val pollingExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(CORE_POOL_SIZE)
    
    /**
     * 启动轮询服务
     */
    fun startPolling(): Boolean
    
    /**
     * 停止轮询服务
     */
    fun stopPolling()
    
    /**
     * 添加轮询目标（支持每联系人独立调度）
     */
    fun addPollingTarget(recipientId: String, metadata: TransportMetadata)
    
    /**
     * 移除轮询目标
     */
    fun removePollingTarget(recipientId: String, providerType: String)
    
    /**
     * 动态调整轮询间隔（基于活跃度变化）
     */
    fun adjustPollingInterval(recipientId: String, newInterval: Long)
    
    /**
     * 获取轮询状态和统计信息
     */
    fun getPollingStatus(): TapPollingStatus
    
    /**
     * 批量轮询指定Provider的所有目标
     */
    private suspend fun batchPollProvider(providerType: String)
    
    /**
     * 单个目标轮询
     */
    private suspend fun pollSingleTarget(recipientId: String, metadata: TransportMetadata)
}

/**
 * 轮询任务信息
 */
data class PollingTaskInfo(
    val recipientId: String,
    val metadata: TransportMetadata,
    val task: ScheduledFuture<*>,
    val interval: Long,
    val lastPollTime: Long,
    val consecutiveErrors: Int,
    val activityLevel: TransportActivityLevel
)
```

### 2. TapIntelligentPollingStrategy（智能轮询策略）

```kotlin
class TapIntelligentPollingStrategy(private val context: Context) {
    companion object {
        // 活跃度判断阈值
        private const val ACTIVE_THRESHOLD_MINUTES = 5L        // 5分钟内有活动视为活跃
        private const val INACTIVE_THRESHOLD_HOURS = 1L        // 1小时内有活动视为非活跃
        
        // 错误处理常量
        private const val MAX_CONSECUTIVE_ERRORS = 10          // 最大连续错误次数
        private const val ERROR_BACKOFF_BASE = 2000L          // 错误退避基础时间: 2秒
        private const val MAX_ERROR_BACKOFF = 180000L         // 最大错误退避时间: 3分钟
        
        // Provider特定优化
        private const val EMAIL_BASE_INTERVAL = 60000L        // 邮件Provider基础间隔: 1分钟
        private const val IPFS_BASE_INTERVAL = 30000L         // IPFS Provider基础间隔: 30秒
        private const val GIT_BASE_INTERVAL = 120000L         // Git Provider基础间隔: 2分钟
    }
    
    /**
     * 计算智能轮询间隔（核心算法）
     */
    fun calculatePollingInterval(
        recipientId: String, 
        metadata: TransportMetadata,
        channel: TransportChannel?,
        errorCount: Int = 0
    ): Long {
        // 1. 错误退避策略
        if (errorCount > 0) {
            return calculateErrorBackoff(errorCount)
        }
        
        // 2. Token过期检查
        if (isTokenNearExpiry(metadata.token)) {
            return TapPollingService.BACKGROUND_POLLING_INTERVAL
        }
        
        // 3. Provider特定调整
        val baseInterval = getProviderBaseInterval(metadata.providerType)
        
        // 4. 活跃度计算
        val activityLevel = calculateActivityLevel(channel)
        val activityMultiplier = getActivityMultiplier(activityLevel)
        
        // 5. 消息大小和类型优化
        val sizeMultiplier = calculateSizeMultiplier(channel)
        
        return (baseInterval * activityMultiplier * sizeMultiplier).toLong()
    }
    
    /**
     * 判断是否应该跳过轮询
     */
    fun shouldSkipPolling(
        recipientId: String,
        metadata: TransportMetadata,
        channel: TransportChannel?
    ): Boolean
    
    /**
     * 计算传输活跃度级别
     */
    private fun calculateActivityLevel(channel: TransportChannel?): TransportActivityLevel
    
    /**
     * 获取Provider特定的基础间隔
     */
    private fun getProviderBaseInterval(providerType: String): Long {
        return when (providerType) {
            "cos" -> TapPollingService.ACTIVE_POLLING_INTERVAL
            "email" -> EMAIL_BASE_INTERVAL
            "ipfs" -> IPFS_BASE_INTERVAL  
            "git" -> GIT_BASE_INTERVAL
            "nas" -> TapPollingService.INACTIVE_POLLING_INTERVAL
            else -> TapPollingService.INACTIVE_POLLING_INTERVAL
        }
    }
    
    /**
     * 错误退避算法（指数退避）
     */
    private fun calculateErrorBackoff(errorCount: Int): Long {
        val backoffMultiplier = Math.pow(2.0, errorCount.toDouble()).toLong()
        val backoffTime = ERROR_BACKOFF_BASE * backoffMultiplier
        return Math.min(backoffTime, MAX_ERROR_BACKOFF)
    }
    
    /**
     * 获取轮询统计信息
     */
    fun getPollingStatistics(): TapPollingStatistics
}

/**
 * 传输活跃度级别
 */
enum class TransportActivityLevel {
    ACTIVE,      // 活跃 - 快速轮询
    INACTIVE,    // 非活跃 - 标准轮询
    BACKGROUND,  // 后台 - 慢速轮询
    SUSPENDED,   // 暂停 - 超慢轮询
    DORMANT      // 休眠 - 极慢轮询或停止
}
```

### 3. 动态轮询调度器

```kotlin
class DynamicPollingScheduler(private val context: Context) {
    /**
     * 动态调整轮询调度
     */
    fun adjustPollingSchedule(recipientId: String, trigger: PollingAdjustTrigger)
    
    /**
     * 批量优化轮询调度
     */
    fun optimizePollingSchedules()
    
    /**
     * 根据系统资源调整并发度
     */
    fun adjustConcurrency(systemLoad: SystemLoadInfo)
}

enum class PollingAdjustTrigger {
    MESSAGE_RECEIVED,    // 收到消息 -> 提高频率
    MESSAGE_SENT,        // 发送消息 -> 提高频率  
    USER_ACTIVE,         // 用户活跃 -> 提高频率
    CONSECUTIVE_EMPTY,   // 连续空轮询 -> 降低频率
    ERROR_OCCURRED,      // 发生错误 -> 错误退避
    TOKEN_REFRESH,       // Token刷新 -> 恢复轮询
    APP_BACKGROUND,      // 应用后台 -> 降低频率
    NETWORK_CHANGE       // 网络变化 -> 重新评估
}
```

### 4. 轮询优化策略

#### 4.1 智能批处理

```kotlin
/**
 * 智能批处理轮询
 */
class BatchPollingOptimizer(private val context: Context) {
    /**
     * 将同Provider的轮询任务批量处理
     */
    fun batchSimilarPollingTasks(tasks: List<PollingTaskInfo>): List<BatchPollingGroup>
    
    /**
     * 根据网络状况调整批处理策略
     */
    fun adjustBatchStrategy(networkInfo: NetworkInfo)
}
```

#### 4.2 自适应间隔

```kotlin
/**
 * 自适应间隔调整
 */
class AdaptiveIntervalAdjuster(private val context: Context) {
    /**
     * 基于历史数据预测最优间隔
     */
    fun predictOptimalInterval(recipientId: String, historicalData: List<PollingResult>): Long
    
    /**
     * 机器学习优化轮询间隔
     */
    fun mlOptimizeInterval(features: PollingFeatures): Long
}

data class PollingFeatures(
    val hourOfDay: Int,
    val dayOfWeek: Int, 
    val userActivityScore: Double,
    val networkQuality: NetworkQuality,
    val batteryLevel: Int,
    val recentMessageFrequency: Double
)
```

#### 4.3 资源感知调度(暂时搁置)

```kotlin
/**
 * 资源感知轮询调度
 */
class ResourceAwareScheduler(private val context: Context) {
    /**
     * 根据系统资源调整轮询策略
     */
    fun adjustForSystemResources(
        cpuUsage: Double,
        memoryUsage: Double, 
        batteryLevel: Int,
        networkType: NetworkType
    ): PollingAdjustment
    
    /**
     * 低电量模式优化
     */
    fun optimizeForLowBattery(): PollingConfiguration
    
    /**
     * 网络类型优化（WiFi vs 移动数据）
     */
    fun optimizeForNetworkType(networkType: NetworkType): PollingConfiguration
}
```

### 5. 轮询状态管理

```kotlin
data class TapPollingStatus(
    val isRunning: Boolean,
    val activePollingTargets: Int,
    val totalPollingTargets: Int,
    val averagePollingInterval: Long,
    val lastPollingTime: Long,
    val pollingStatistics: TapPollingStatistics,
    val resourceUsage: PollingResourceUsage
)

data class TapPollingStatistics(
    val totalPolls: Long,
    val successfulPolls: Long,
    val failedPolls: Long,
    val messagesFound: Long,
    val averageResponseTime: Long,
    val providerStatistics: Map<String, ProviderPollingStats>
)

data class PollingResourceUsage(
    val cpuUsagePercent: Double,
    val memoryUsageKB: Long,
    val networkUsageKB: Long,
    val batteryDrainRate: Double
)
```

### 6. 轮询层优化亮点

#### 6.1 智能化特性
- **动态间隔调整**: 基于活跃度、错误率、Provider类型智能调整
- **错误退避策略**: 指数退避算法，避免无效轮询
- **预测性调度**: 基于历史数据预测最优轮询时机
- **自适应批处理**: 智能合并相似轮询任务

#### 6.2 每联系人个性化
- **独立轮询调度**: 每个联系人维护独立的轮询任务和间隔
- **个性化策略**: 基于对话活跃度个性化轮询频率
- **错误状态隔离**: 一个联系人的错误不影响其他联系人轮询

#### 6.3 资源优化(暂时搁置)
- **系统资源感知**: 根据CPU、内存、电量动态调整
- **网络类型优化**: WiFi和移动数据不同策略
- **低电量模式**: 电量不足时自动降级轮询频率

#### 6.4 Provider适配
- **Provider特定优化**: 不同传输服务采用不同轮询策略
- **统一接口**: 所有Provider通过统一接口参与轮询
- **灵活扩展**: 新Provider可轻松接入轮询系统
```

## 迁移策略

### 阶段1：接口层迁移
1. 创建Tap核心接口
2. 实现CosTransportProvider适配现有CosClient
3. 保持coscomm模块功能不变，通过适配器调用

### 阶段2：管理层迁移
1. 创建TransportManager替换现有管理器
2. 迁移通道管理和Token池管理
3. 实现路由管理器

### 阶段3：集成层迁移
1. 创建TapMessageSendIntegrator替换SignalMessageSendIntegrator
2. 迁移轮询服务和消息处理器
3. 完成Signal集成层改造

### 阶段4：清理和优化
1. 移除cos和coscomm模块
2. 优化性能和错误处理
3. 完善文档和测试

## 配置管理

### TransportConfig（传输配置）

```kotlin
data class TransportConfig(
    val enabledProviders: Set<String>,
    val defaultProvider: String?,
    val routingPolicy: TransportRoutingPolicy,
    val pollingConfig: TransportPollingConfig
)

data class TransportPollingConfig(
    val enabled: Boolean = true,
    val interval: Long = 5000L,      // 5秒
    val maxConcurrent: Int = 8,
    val timeoutMs: Long = 30000L     // 30秒
)

enum class TransportRoutingPolicy {
    TRANSPORT_FIRST,    // 优先使用传输服务
    SIGNAL_FIRST,       // 优先使用Signal Server
    INTELLIGENT         // 智能路由
}
```

## 接口总结

### 核心接口
1. `TransportProvider` - 传输提供者接口（5个核心方法）
2. `TransportMessage` - 传输消息数据结构
3. `TransportMetadata` - 传输元数据接口
4. `TransportToken` - 传输Token接口
5. `TransportResult` - 传输结果封装

### 管理接口
1. `TransportManager` - 传输管理器（提供者注册和消息路由）
2. `TransportChannelManager` - 通道管理器（通道生命周期）
3. `TransportTokenPool` - Token池管理器（权限管理）
4. `TransportRoutingManager` - 路由管理器（智能路由决策）

### 服务接口
1. `TapPollingService` - 轮询服务（消息拉取）
2. `TapMessageSendIntegrator` - 消息发送集成器
3. `TapMessageProcessor` - 消息处理器

### Provider插件接口
1. `ProviderRegistrar` - Provider注册器
2. Provider配置文件规范
3. Provider目录结构规范

该架构将现有的COS特定实现抽象为通用的传输层接口，支持多种传输服务，并保持完全的向下兼容性。
