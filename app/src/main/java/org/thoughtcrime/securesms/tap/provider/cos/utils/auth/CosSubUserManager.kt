package org.thoughtcrime.securesms.tap.provider.cos.utils.auth

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig

/**
 * COS子用户管理器
 * 负责创建、管理和清理COS子用户账号
 * 
 * 已废弃: V2架构使用WebSocket推送和主账户凭证,不再需要子账户管理。
 * 
 * V2架构变更:
 * - 消息通过Lambda + WebSocket推送传输,无需子账户共享
 * - 使用主账户凭证 + 临时访问密钥(STS)
 * - 附件使用预签名URL,无需子账户权限
 * 
 * @deprecated V2架构不再使用子账户,此接口保留仅用于兼容旧代码
 */
@Deprecated(
    message = "V2架构使用WebSocket推送,不需要子账户管理",
    replaceWith = ReplaceWith("使用主账户凭证 + 预签名URL"),
    level = DeprecationLevel.WARNING
)
interface CosSubUserManager {
    
    /**
     * 创建子用户并分配权限
     * @param userName 子用户名称
     * @param directoryPath 允许访问的目录路径
     * @param permissions 权限类型（只读、读写等）
     * @return 子用户凭证信息
     */
    fun createSubUser(
        userName: String, 
        directoryPath: String, 
        permissions: CosPermission = CosPermission.READ_ONLY
    ): CosSubUserCredential
    
    /**
     * 删除子用户
     * @param userName 子用户名称
     * @return 是否删除成功
     */
    fun deleteSubUser(userName: String): Boolean
    
    /**
     * 为子用户创建访问密钥（内部方法，不同云提供商实现方式不同）
     * AWS使用userName，腾讯云使用UIN，因此标记为内部方法
     * @param userIdentifier 用户标识符（AWS使用userName，腾讯云使用UIN）
     * @return 访问密钥信息
     */
    fun createAccessKeyInternal(userIdentifier: Any): CosAccessKey
    
    /**
     * 删除子用户的访问密钥
     * @param userName 子用户名称
     * @param accessKeyId 访问密钥ID
     * @return 是否删除成功
     */
    fun deleteAccessKey(userName: String, accessKeyId: String): Boolean
    
    /**
     * 列出所有子用户
     * @return 子用户列表
     */
    fun listSubUsers(): List<CosSubUserInfo>
    
    /**
     * 清理过期或无用的子用户
     * @param maxAgeHours 最大存在时间（小时）
     * @return 清理的用户数量
     */
    fun cleanupExpiredUsers(maxAgeHours: Int = 24 * 7): Int
}

/**
 * 子用户凭证信息
 */
data class CosSubUserCredential(
    val userName: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val allowedDirectory: String,
    val permissions: CosPermission,
    val createdTime: Long,
    val isPermanent: Boolean = true
) {
    /**
     * 转换为CosAccessInfo (tap模块版本)
     */
    fun toTapAccessInfo(provider: String, region: String, bucketName: String): TapCosAccessInfo {
        return TapCosAccessInfo(
            provider = provider,
            region = region,
            bucketName = bucketName,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = null, // 永久凭证不需要sessionToken
            expireTime = Long.MAX_VALUE, // 永久有效
            sharedDirectory = allowedDirectory
        )
    }
}

/**
 * Tap模块的CosAccessInfo（替代coscomm模块的CosAccessInfo）
 */
data class TapCosAccessInfo(
    val provider: String,
    val region: String,
    val bucketName: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expireTime: Long,
    val sharedDirectory: String
)

/**
 * 访问密钥信息
 */
data class CosAccessKey(
    val accessKeyId: String,
    val secretAccessKey: String,
    val status: String = "Active",
    val createDate: Long = System.currentTimeMillis()
)

/**
 * 子用户信息
 */
data class CosSubUserInfo(
    val userName: String,
    val userId: String?,
    val createDate: Long,
    val lastActivity: Long?,
    val accessKeys: List<String> = emptyList()
)

/**
 * COS权限类型
 */
enum class CosPermission(val actions: List<String>) {
    READ_ONLY(listOf(
        "GetObject",      // 下载对象
        "HeadObject",     // 获取对象元数据
        "GetBucket",      // 列举对象（腾讯云的ListObjects API）
        "GetBucketObjectVersions", // 获取对象版本列表
        "GetBucketIntelligentTiering", // 获取智能分层配置
        "HeadBucket",     // 获取存储桶信息
        "ListMultipartUploads", // 列举分片上传
        "ListParts",      // 列举分片
        "OptionsObject"   // 预检请求
    )),
    READ_WRITE(listOf(
        "GetObject",      // 下载对象
        "PutObject",      // 上传对象
        "DeleteObject",   // 删除对象
        "HeadObject",     // 获取对象元数据
        "GetBucket",      // 列举对象（腾讯云的ListObjects API）
        "GetBucketObjectVersions", // 获取对象版本列表
        "GetBucketIntelligentTiering", // 获取智能分层配置
        "HeadBucket",     // 获取存储桶信息
        "ListMultipartUploads",  // 列举分片上传
        "ListParts",      // 列举分片
        "OptionsObject"   // 预检请求
    )),
    FULL_ACCESS(listOf("*"))
}

/**
 * 子用户管理器工厂
 * 
 * @deprecated V2架构不再使用子账户管理,此工厂类保留仅用于兼容旧代码
 */
@Deprecated(
    message = "V2架构不再使用子账户管理",
    level = DeprecationLevel.WARNING
)
object CosSubUserManagerFactory {
    private val TAG = Log.tag(CosSubUserManagerFactory::class.java)

    /**
     * 创建子用户管理器
     */
    fun createManager(config: CosConfig, context: android.content.Context): CosSubUserManager {
        return when (config.provider) {
            CosConfig.Provider.AWS -> AwsSubUserManager(config)
            CosConfig.Provider.TENCENT -> TencentSubUserManager(config, context)
        }
    }
}

/**
 * 子用户管理异常
 */
class CosSubUserException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String? = null
) : Exception(message, cause)

/**
 * 子用户管理结果
 */
sealed class CosSubUserResult<T> {
    data class Success<T>(val data: T) : CosSubUserResult<T>()
    data class Failure<T>(val error: String, val errorCode: String? = null) : CosSubUserResult<T>()
    
    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw CosSubUserException(error, null, errorCode)
    }
} 