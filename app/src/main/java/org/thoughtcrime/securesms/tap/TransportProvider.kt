package org.thoughtcrime.securesms.tap

/**
 * 传输提供者核心接口
 * 
 * 所有传输服务的统一接口，定义了传输层的核心功能。
 * 支持单点传输、群组传输和权限管理等功能。
 */
interface TransportProvider {
    
    /** 传输提供者类型标识 */
    val providerType: String
    
    /** 是否支持权限管理 */
    val supportsAuth: Boolean
    
    /** 是否支持群组传输 */
    val supportsGroup: Boolean get() = false
    
    /** 提供者显示名称 */
    val displayName: String
    
    /** 提供者描述信息 */
    val description: String
    
    /** 支持的最大消息大小（字节），-1表示无限制 */
    val maxMessageSize: Long get() = -1L
    
    /** 支持的操作权限 */
    val supportedPermissions: Set<TransportPermission>
    
    /**
     * 推送文件到传输服务
     * 
     * @param message 加密消息内容
     * @param metadata 传输元数据（目标地址、路径等）
     * @return 推送结果
     */
    suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult
    
    /**
     * 从传输服务拉取文件
     * 
     * @param metadata 传输元数据（源地址、路径等）
     * @return 拉取结果和消息内容
     * @deprecated 使用 listFiles + downloadFile 替代
     */
    @Deprecated("使用 listFiles + downloadFile 替代，将在后续版本中移除")
    suspend fun pull(metadata: TransportMetadata): TransportResult
    
    /**
     * 群组推送（一对多）
     * 
     * @param message 加密消息内容
     * @param groupMetadata 群组传输元数据
     * @return 推送结果
     */
    suspend fun groupPush(message: TransportMessage, groupMetadata: GroupTransportMetadata): TransportResult {
        if (!supportsGroup) {
            return TransportResult.failure(
                TransportError.INVALID_FORMAT,
                false,
                "该传输服务不支持群组操作"
            )
        }
        
        // 默认实现：顺序发送到每个成员
        val results = mutableListOf<TransportResult>()
        for (memberMetadata in groupMetadata.memberMetadata) {
            val result = push(message, memberMetadata)
            results.add(result)
        }
        
        return TransportResult.merge(results)
    }
    
    /**
     * 群组拉取（多对一）
     * 
     * @param groupMetadata 群组传输元数据
     * @return 拉取结果和消息列表
     * @deprecated 使用 listFiles + downloadFile 替代
     */
    @Deprecated("使用 listFiles + downloadFile 替代，将在后续版本中移除")
    suspend fun groupPull(groupMetadata: GroupTransportMetadata): List<TransportResult> {
        if (!supportsGroup) {
            return listOf(
                TransportResult.failure(
                    TransportError.INVALID_FORMAT,
                    false,
                    "该传输服务不支持群组操作"
                )
            )
        }
        
        // 默认实现：从每个成员拉取
        val results = mutableListOf<TransportResult>()
        for (memberMetadata in groupMetadata.memberMetadata) {
            val result = pull(memberMetadata)
            results.add(result)
        }
        
        return results
    }
    
    /**
     * 列出指定路径下的文件
     * 
     * @param path 文件路径
     * @param metadata 传输元数据
     * @return 文件列表结果
     */
    suspend fun listFiles(path: String, metadata: TransportMetadata): TransportResult {
        // 默认实现：通过pull方法来保持向下兼容
        return pull(metadata)
    }
    
    /**
     * 下载指定文件
     * 
     * @param fileInfo 文件信息
     * @param metadata 传输元数据
     * @return 下载结果和文件数据
     */
    suspend fun downloadFile(fileInfo: FileInfo, metadata: TransportMetadata): TransportResult {
        // 默认实现：通过pull方法来保持向下兼容
        return pull(metadata)
    }
    
    /**
     * 上传文件数据
     * 
     * @param data 文件数据
     * @param path 目标路径
     * @param metadata 传输元数据
     * @return 上传结果
     */
    suspend fun uploadFile(data: ByteArray, path: String, metadata: TransportMetadata): TransportResult {
        // 需要基于data创建TransportMessage，然后调用push
        // 这里提供一个基本的实现，具体Provider应该重写这个方法
        try {
            // 从路径中提取消息ID和时间戳
            val fileName = path.substringAfterLast('/')
            val messageId = fileName.substringBefore('_')
            val timestamp = fileName.substringAfter('_').substringBefore('.').toLongOrNull() ?: System.currentTimeMillis()
            
            // 将数据转换为Base64编码的signalCiphertext
            val signalCiphertext = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            
            // 创建内容元数据
            val contentMetadata = TransportContentMetadata(
                originalSize = data.size.toLong(),
                compressionType = TransportCompressionType.NONE
            )
            
            val message = TransportMessage(
                messageId = messageId,
                timestamp = timestamp,
                senderId = "", // 上传文件时无法确定发送者，留空
                recipientId = "", // 上传文件时无法确定接收者，留空  
                messageType = TransportMessageType.TEXT_MESSAGE,
                signalCiphertext = signalCiphertext,
                contentMetadata = contentMetadata
            )
            
            return push(message, metadata)
        } catch (e: Exception) {
            return TransportResult.failure(
                TransportError.INVALID_FORMAT,
                false,
                "无法从数据创建消息: ${e.message}"
            )
        }
    }
    
    /**
     * 权限管理 - 生成访问Token
     * 
     * @param request Token请求参数
     * @return 生成的访问Token，如果不支持权限管理则返回null
     */
    suspend fun generateToken(request: TransportTokenRequest): TransportToken? {
        return null // 默认实现返回null，支持权限管理的Provider需要重写此方法
    }
    
    /**
     * 权限管理 - 验证Token有效性
     * 
     * @param token 待验证的Token
     * @return 验证结果，如果不支持权限管理则返回true
     */
    suspend fun validateToken(token: TransportToken): Boolean {
        // 基本验证：Provider类型匹配和Token有效性
        if (token.providerType != providerType) {
            return false
        }
        
        if (token.isExpired || !token.validate()) {
            return false
        }
        
        return if (supportsAuth) {
            // 支持权限管理的Provider可以重写此方法进行更严格的验证
            true
        } else {
            // 不支持权限管理的Provider也需要基本验证
            true
        }
    }
    
    /**
     * 权限管理 - 撤销Token
     * 
     * @param token 待撤销的Token
     * @return 撤销结果，如果不支持权限管理则返回true
     */
    suspend fun revokeToken(token: TransportToken): Boolean {
        return !supportsAuth // 不支持权限管理的Provider返回true，支持的Provider需要重写此方法
    }
    
    /**
     * 测试连接性
     * 
     * @param metadata 测试用的元数据
     * @return 测试结果
     */
    suspend fun testConnection(metadata: TransportMetadata): TransportResult {
        return try {
            // 默认实现：尝试进行一次简单的操作
            pull(metadata)
        } catch (e: Exception) {
            TransportResult.fromException(e, true)
        }
    }
    
    /**
     * 获取提供者状态信息
     * 
     * @return 提供者状态
     */
    suspend fun getProviderStatus(): TransportProviderStatus {
        return TransportProviderStatus(
            providerType = providerType,
            isAvailable = true,
            lastCheckTime = System.currentTimeMillis()
        )
    }
    
    /**
     * 清理资源
     */
    suspend fun cleanup() {
        // 默认空实现，具体Provider可以覆盖
    }
    
    /**
     * 检查消息是否过大
     */
    fun isMessageTooLarge(message: TransportMessage): Boolean {
        return if (maxMessageSize > 0) {
            val totalSize = message.encryptedContent.size + 
                           message.attachments.sumOf { it.size }
            totalSize > maxMessageSize
        } else {
            false
        }
    }
    
    /**
     * 检查是否支持特定权限
     */
    fun supportsPermission(permission: TransportPermission): Boolean {
        return supportedPermissions.contains(permission)
    }
    
    /**
     * 检查是否可以执行特定操作
     */
    fun canPerformOperation(operation: TransportOperation): Boolean {
        return operation.isPermissionSufficient(supportedPermissions)
    }
    
    /**
     * 判断文件是否为消息文件
     * 
     * 不同Provider可能有不同的文件命名和识别策略
     * 
     * @param fileInfo 文件信息
     * @return 是否为消息文件
     */
    fun isMessageFile(fileInfo: FileInfo): Boolean {
        // 默认实现：基于文件扩展名判断
        val name = fileInfo.name.lowercase()
        return name.endsWith(".dat") || name.endsWith(".msg") || name.endsWith(".enc")
    }
    
    /**
     * 解析文件名获取消息信息
     * 
     * 不同Provider可能有不同的文件命名策略
     * 
     * @param fileName 文件名
     * @return 解析的消息信息，解析失败返回null
     */
    fun parseMessageFileName(fileName: String): MessageFileInfo? {
        return try {
            // 默认实现：支持标准格式 messageId_timestamp.dat
            val baseName = fileName.substringBeforeLast('.')
            val parts = baseName.split('_')
            
            when (parts.size) {
                2 -> {
                    // messageId_timestamp格式
                    MessageFileInfo(
                        messageId = parts[0],
                        timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = "",
                        recipientId = ""
                    )
                }
                3 -> {
                    // senderId_messageId_timestamp格式
                    MessageFileInfo(
                        messageId = parts[1],
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = ""
                    )
                }
                4 -> {
                    // senderId_recipientId_messageId_timestamp格式
                    MessageFileInfo(
                        messageId = parts[2],
                        timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = parts[1]
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 从文件数据解析传输消息
     * 
     * @param fileData 文件二进制数据
     * @param fileInfo 文件信息
     * @param metadata 传输元数据
     * @return 解析的传输消息，解析失败返回null
     */
    suspend fun parseTransportMessage(fileData: ByteArray, fileInfo: FileInfo, metadata: TransportMetadata): TransportMessage? {
        return try {
            // 默认实现：假设文件直接包含序列化的TransportMessage
            // 具体Provider应该重写此方法实现自己的解析逻辑
            
            // 从文件名解析基本信息
            val messageFileInfo = parseMessageFileName(fileInfo.name) ?: return null
            
            // 简单的二进制格式解析（实际应该有版本、长度、校验等）
            val signalCiphertext = android.util.Base64.encodeToString(fileData, android.util.Base64.NO_WRAP)
            
            TransportMessage(
                messageId = messageFileInfo.messageId,
                timestamp = messageFileInfo.timestamp,
                senderId = messageFileInfo.senderId,
                recipientId = messageFileInfo.recipientId,
                messageType = TransportMessageType.TEXT_MESSAGE,
                signalCiphertext = signalCiphertext,
                contentMetadata = TransportContentMetadata(
                    originalSize = fileData.size.toLong(),
                    compressionType = TransportCompressionType.NONE
                )
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取发送路径策略
     * 
     * 不同Provider可能有不同的路径组织策略
     * 
     * @param recipientId 接收者ID
     * @param messageType 消息类型
     * @return 发送路径
     */
    fun getSendPath(recipientId: String, messageType: TransportMessageType = TransportMessageType.TEXT_MESSAGE): String {
        // 默认实现：/outbox/recipientId/
        return "/outbox/$recipientId/"
    }
    
    /**
     * 获取接收路径策略
     * 
     * 不同Provider可能有不同的路径组织策略
     * 
     * @param recipientId 发送者ID（从其路径接收消息）
     * @param messageType 消息类型
     * @return 接收路径
     */
    fun getReceivePath(recipientId: String, messageType: TransportMessageType = TransportMessageType.TEXT_MESSAGE): String {
        // 默认实现：/outbox/recipientId/（从对方的outbox接收）
        return "/outbox/$recipientId/"
    }
    
    /**
     * 获取Provider特定的地址格式
     * 
     * @param config Provider配置
     * @return 格式化的地址
     */
    fun formatAddress(config: Map<String, Any>): String {
        // 默认实现：provider://type
        return "provider://$providerType"
    }
}

/**
 * 传输提供者状态信息
 */
data class TransportProviderStatus(
    /** 提供者类型 */
    val providerType: String,
    
    /** 是否可用 */
    val isAvailable: Boolean,
    
    /** 最后检查时间 */
    val lastCheckTime: Long,
    
    /** 响应时间（毫秒） */
    val responseTimeMs: Long = 0L,
    
    /** 错误信息（如果不可用） */
    val errorMessage: String? = null,
    
    /** 额外状态信息 */
    val additionalInfo: Map<String, Any> = emptyMap()
) {
    
    /**
     * 检查状态是否过期
     */
    fun isStale(maxAgeMs: Long): Boolean {
        return System.currentTimeMillis() - lastCheckTime > maxAgeMs
    }
}

/**
 * 传输提供者工厂接口
 * 
 * 用于创建和管理传输提供者实例。
 */
interface TransportProviderFactory {
    
    /** 支持的提供者类型 */
    val supportedProviderTypes: Set<String>
    
    /**
     * 创建传输提供者实例
     * 
     * @param providerType 提供者类型
     * @param config 配置参数
     * @return 提供者实例，如果不支持该类型则返回null
     */
    fun createProvider(providerType: String, config: Map<String, Any>): TransportProvider?
    
    /**
     * 验证提供者配置
     * 
     * @param providerType 提供者类型
     * @param config 配置参数
     * @return 验证结果
     */
    fun validateConfig(providerType: String, config: Map<String, Any>): ConfigValidationResult
    
    /**
     * 获取提供者的默认配置
     * 
     * @param providerType 提供者类型
     * @return 默认配置
     */
    fun getDefaultConfig(providerType: String): Map<String, Any>
    
    /**
     * 检查是否支持指定的提供者类型
     */
    fun supportsProviderType(providerType: String): Boolean {
        return supportedProviderTypes.contains(providerType)
    }
}



/**
 * 传输提供者注册器接口
 * 
 * 用于注册和管理传输提供者的生命周期。
 */
interface TransportProviderRegistrar {
    
    /** 支持的提供者类型 */
    val supportedProviderTypes: Set<String>
    
    /** 注册器的显示名称 */
    val displayName: String
    
    /** 注册器的优先级（数字越大优先级越高） */
    val priority: Int get() = 0
    
    /**
     * 注册传输提供者
     * 
     * @param factory 提供者工厂
     * @return 注册是否成功
     */
    suspend fun register(factory: TransportProviderFactory): Boolean
    
    /**
     * 注销传输提供者
     * 
     * @param providerType 提供者类型
     * @return 注销是否成功
     */
    suspend fun unregister(providerType: String): Boolean
    
    /**
     * 获取已注册的提供者列表
     * 
     * @return 提供者类型列表
     */
    fun getRegisteredProviders(): Set<String>
    
    /**
     * 检查提供者是否已注册
     * 
     * @param providerType 提供者类型
     * @return 是否已注册
     */
    fun isProviderRegistered(providerType: String): Boolean {
        return getRegisteredProviders().contains(providerType)
    }
    
    /**
     * 获取提供者的详细信息
     * 
     * @param providerType 提供者类型
     * @return 提供者信息，如果未注册则返回null
     */
    fun getProviderInfo(providerType: String): TransportProviderInfo?
    
    /**
     * 清理资源
     */
    suspend fun cleanup()
}

/**
 * 传输提供者信息
 */
data class TransportProviderInfo(
    /** 提供者类型 */
    val providerType: String,
    
    /** 显示名称 */
    val displayName: String,
    
    /** 描述信息 */
    val description: String,
    
    /** 是否支持权限管理 */
    val supportsAuth: Boolean,
    
    /** 是否支持群组传输 */
    val supportsGroup: Boolean,
    
    /** 支持的最大消息大小 */
    val maxMessageSize: Long,
    
    /** 支持的权限列表 */
    val supportedPermissions: Set<TransportPermission>,
    
    /** 注册时间 */
    val registeredAt: Long = System.currentTimeMillis(),
    
    /** 额外信息 */
    val additionalInfo: Map<String, Any> = emptyMap()
)

/**
 * 消息文件信息
 * 
 * 从文件名解析出的消息基本信息
 */
data class MessageFileInfo(
    /** 消息ID */
    val messageId: String,
    
    /** 时间戳 */
    val timestamp: Long,
    
    /** 发送者ID */
    val senderId: String,
    
    /** 接收者ID */
    val recipientId: String
)

/**
 * 抽象传输提供者基类
 * 
 * 提供一些通用的实现，减少具体Provider的工作量。
 */
abstract class AbstractTransportProvider : TransportProvider {
    
    override val supportsGroup: Boolean = false
    override val maxMessageSize: Long = -1L
    override val supportedPermissions: Set<TransportPermission> = TransportPermission.allPermissions()
    
    /**
     * 验证元数据有效性
     */
    protected open fun validateMetadata(metadata: TransportMetadata): Boolean {
        return metadata.validate() && metadata.providerType == providerType
    }
    
    /**
     * 检查操作前置条件
     */
    protected open suspend fun checkPreconditions(
        message: TransportMessage?,
        metadata: TransportMetadata
    ): TransportResult? {
        // 验证元数据
        if (!validateMetadata(metadata)) {
            return TransportResult.failure(
                TransportError.INVALID_FORMAT,
                false,
                "传输元数据无效"
            )
        }
        
        // 检查消息大小
        if (message != null && isMessageTooLarge(message)) {
            return TransportResult.failure(
                TransportError.MESSAGE_TOO_LARGE,
                false,
                "消息大小超过限制"
            )
        }
        
        // 验证Token（如果支持权限管理）
        if (supportsAuth) {
            // 根据操作类型验证相应的token
            val sendToken = metadata.getSendMetadata().token
            val receiveToken = metadata.getReceiveMetadata().token
            
            // 优先验证发送token，如果不存在则验证接收token
            val tokenToValidate = sendToken ?: receiveToken
            if (tokenToValidate != null && !validateToken(tokenToValidate)) {
                return TransportResult.failure(
                    TransportError.AUTH_ERROR,
                    true,
                    "访问凭证无效或已过期"
                )
            }
        }
        
        return null // 前置条件检查通过
    }
    
    /**
     * 包装操作执行，提供统一的错误处理
     */
    protected suspend fun <T> executeOperation(
        operation: suspend () -> T,
        operationName: String
    ): TransportResult {
        return try {
            val result = operation()
            when (result) {
                is TransportResult -> result
                is TransportMessage -> TransportResult.success(result)
                else -> TransportResult.success()
            }
        } catch (e: TransportException) {
            TransportResult.failure(
                e.transportError,
                e.isRetryable(),
                e.message ?: e.transportError.displayName,
                e
            )
        } catch (e: Exception) {
            val transportException = TransportExceptionFactory.fromGenericException(e)
            TransportResult.failure(
                transportException.transportError,
                transportException.isRetryable(),
                e.message ?: "操作失败: $operationName",
                e
            )
        }
    }
    
    override suspend fun getProviderStatus(): TransportProviderStatus {
        return try {
            val startTime = System.currentTimeMillis()
            // 子类可以覆盖这个方法来实现更具体的状态检查
            performStatusCheck()
            val responseTime = System.currentTimeMillis() - startTime
            
            TransportProviderStatus(
                providerType = providerType,
                isAvailable = true,
                lastCheckTime = System.currentTimeMillis(),
                responseTimeMs = responseTime
            )
        } catch (e: Exception) {
            TransportProviderStatus(
                providerType = providerType,
                isAvailable = false,
                lastCheckTime = System.currentTimeMillis(),
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 执行状态检查，子类可以覆盖
     */
    protected open suspend fun performStatusCheck() {
        // 默认空实现
    }
} 