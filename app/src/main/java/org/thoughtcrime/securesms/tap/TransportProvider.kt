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
 * 用于向系统注册传输提供者。
 */
interface TransportProviderRegistrar {
    
    /**
     * 注册传输提供者
     * 
     * @param provider 提供者实例
     */
    fun registerProvider(provider: TransportProvider)
    
    /**
     * 注销传输提供者
     * 
     * @param providerType 提供者类型
     */
    fun unregisterProvider(providerType: String)
    
    /**
     * 获取已注册的提供者
     * 
     * @param providerType 提供者类型
     * @return 提供者实例，如果未注册则返回null
     */
    fun getProvider(providerType: String): TransportProvider?
    
    /**
     * 获取所有已注册的提供者
     * 
     * @return 提供者类型到实例的映射
     */
    fun getAllProviders(): Map<String, TransportProvider>
    
    /**
     * 检查提供者是否已注册
     */
    fun isProviderRegistered(providerType: String): Boolean
}

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
            val token = metadata.token
            if (token != null && !validateToken(token)) {
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