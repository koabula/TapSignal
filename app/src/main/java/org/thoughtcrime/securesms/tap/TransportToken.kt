package org.thoughtcrime.securesms.tap

/**
 * 传输Token接口
 * 
 * 定义了传输服务访问凭证的基本结构和功能。
 * 用于权限管理和访问控制，支持不同传输服务的Token实现。
 */
interface TransportToken {
    /** Token唯一标识符 */
    val tokenId: String
    
    /** 接收者ID */
    val recipientId: String
    
    /** 传输提供者类型 */
    val providerType: String
    
    /** Token权限集合 */
    val permissions: Set<TransportPermission>
    
    /** Token过期时间（毫秒时间戳） */
    val expirationTime: Long
    
    /** 检查Token是否已过期 */
    val isExpired: Boolean get() = System.currentTimeMillis() > expirationTime
    
    /** 检查Token是否即将过期（5分钟内） */
    val isNearExpiry: Boolean get() = System.currentTimeMillis() > (expirationTime - 300000L)
    
    /**
     * 将Token转换为Map格式，用于序列化存储
     */
    fun toMap(): Map<String, Any>
    
    /**
     * 验证Token的有效性
     */
    fun validate(): Boolean
    
    /**
     * 检查Token是否具有指定权限
     */
    fun hasPermission(permission: TransportPermission): Boolean {
        return permissions.contains(permission)
    }
    
    /**
     * 检查Token是否可以执行指定操作
     */
    fun canPerformOperation(operation: TransportOperation): Boolean {
        return operation.isPermissionSufficient(permissions)
    }
}

/**
 * COS传输Token实现
 * 
 * 用于COS（云对象存储）服务的访问凭证，包含CAM子账户信息。
 */
data class CosTransportToken(
    override val tokenId: String,
    override val recipientId: String,
    override val providerType: String = "cos",
    override val permissions: Set<TransportPermission>,
    override val expirationTime: Long,
    /** 访问密钥ID（CAM子账户AccessKeyId） */
    val accessKeyId: String,
    /** 访问密钥Secret（CAM子账户SecretAccessKey） */
    val secretAccessKey: String,
    /** 会话Token（可选，用于临时凭证） */
    val sessionToken: String? = null,
    /** COS区域 */
    val region: String,
    /** COS存储桶名称 */
    val bucketName: String,
    /** 云服务提供商类型 (AWS/TENCENT) */
    val cloudProvider: String
) : TransportToken {
    
    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "tokenId" to tokenId,
            "recipientId" to recipientId,
            "providerType" to providerType,
            "permissions" to TransportPermission.toStringList(permissions),
            "expirationTime" to expirationTime,
            "accessKeyId" to accessKeyId,
            "secretAccessKey" to secretAccessKey,
            "region" to region,
            "bucketName" to bucketName,
            "cloudProvider" to cloudProvider
        )
        
        sessionToken?.let { map["sessionToken"] = it }
        
        return map
    }
    
    override fun validate(): Boolean {
        return tokenId.isNotBlank() &&
               recipientId.isNotBlank() &&
               providerType == "cos" &&
               permissions.isNotEmpty() &&
               accessKeyId.isNotBlank() &&
               secretAccessKey.isNotBlank() &&
               region.isNotBlank() &&
               bucketName.isNotBlank() &&
               cloudProvider.isNotBlank() &&
               (cloudProvider == "AWS" || cloudProvider == "TENCENT")
    }
    
    companion object {
        /**
         * 从Map数据创建CosTransportToken实例
         */
        fun fromMap(data: Map<String, Any>): CosTransportToken? {
            return try {
                val tokenId = data["tokenId"] as? String ?: return null
                val recipientId = data["recipientId"] as? String ?: return null
                val providerType = data["providerType"] as? String ?: "cos"
                val permissionsList = data["permissions"] as? List<String> ?: return null
                val permissions = TransportPermission.fromStringList(permissionsList)
                val expirationTime = (data["expirationTime"] as? Number)?.toLong() ?: return null
                val accessKeyId = data["accessKeyId"] as? String ?: return null
                val secretAccessKey = data["secretAccessKey"] as? String ?: return null
                val sessionToken = data["sessionToken"] as? String
                val region = data["region"] as? String ?: return null
                val bucketName = data["bucketName"] as? String ?: return null
                val cloudProvider = data["cloudProvider"] as? String ?: return null
                
                CosTransportToken(
                    tokenId = tokenId,
                    recipientId = recipientId,
                    providerType = providerType,
                    permissions = permissions,
                    expirationTime = expirationTime,
                    accessKeyId = accessKeyId,
                    secretAccessKey = secretAccessKey,
                    sessionToken = sessionToken,
                    region = region,
                    bucketName = bucketName,
                    cloudProvider = cloudProvider
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}



/**
 * 传输Token请求数据结构
 * 
 * 用于向TransportProvider请求生成Token时提供必要的参数。
 */
data class TransportTokenRequest(
    /** 接收者ID */
    val recipientId: String,
    
    /** 传输提供者类型 */
    val providerType: String,
    
    /** 请求的权限集合 */
    val requestedPermissions: Set<TransportPermission>,
    
    /** Token有效期（毫秒），0表示使用默认有效期 */
    val validityDurationMs: Long = 0L,
    
    /** 提供者特定的配置参数 */
    val providerConfig: Map<String, Any> = emptyMap(),
    
    /** 请求Token的目的描述 */
    val purpose: String = "message_transport"
) {
    
    /**
     * 验证请求的有效性
     */
    fun validate(): Boolean {
        return recipientId.isNotBlank() &&
               providerType.isNotBlank() &&
               requestedPermissions.isNotEmpty() &&
               validityDurationMs >= 0L
    }
    
    /**
     * 获取过期时间戳
     */
    fun getExpirationTime(): Long {
        val duration = if (validityDurationMs > 0L) validityDurationMs else getDefaultValidityDuration()
        return System.currentTimeMillis() + duration
    }
    
    /**
     * 获取默认有效期（根据Provider类型）
     */
    private fun getDefaultValidityDuration(): Long {
        return when (providerType) {
            "cos" -> 15 * 60 * 1000L      // COS: 15分钟
            "email" -> 24 * 60 * 60 * 1000L  // Email: 24小时
            "git" -> 7 * 24 * 60 * 60 * 1000L  // Git: 7天
            else -> 60 * 60 * 1000L        // 默认: 1小时
        }
    }
}

/**
 * Token工厂类
 * 
 * 用于创建不同类型的TransportToken实例。
 */
object TransportTokenFactory {
    
    /**
     * 从Map数据创建TransportToken实例
     */
    fun fromMap(data: Map<String, Any>): TransportToken? {
        val providerType = data["providerType"] as? String ?: return null
        
        return when (providerType) {
            "cos" -> CosTransportToken.fromMap(data)
            else -> null
        }
    }
    
    /**
     * 创建COS Token
     */
    fun createCosToken(
        tokenId: String,
        recipientId: String,
        permissions: Set<TransportPermission>,
        expirationTime: Long,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = null,
        region: String,
        bucketName: String,
        cloudProvider: String
    ): CosTransportToken {
        return CosTransportToken(
            tokenId = tokenId,
            recipientId = recipientId,
            permissions = permissions,
            expirationTime = expirationTime,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            region = region,
            bucketName = bucketName,
            cloudProvider = cloudProvider
        )
    }
    

} 