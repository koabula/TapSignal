package org.thoughtcrime.securesms.tap.provider.cos.coscomm.data

/**
 * COS通信系统常量定义
 */
object CosConstants {
    // 文件大小限制
    const val MAX_MESSAGE_SIZE = 64 * 1024 * 1024      // 64MB
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024   // 100MB
    
    // 轮询配置
    const val ACTIVE_POLLING_INTERVAL = 5000L           // 5秒 - 活跃对话
    const val INACTIVE_POLLING_INTERVAL = 30000L        // 30秒 - 非活跃对话
    const val BACKGROUND_POLLING_INTERVAL = 60000L      // 60秒 - 后台模式
    
    // 重试配置
    const val MAX_RETRY_COUNT = 3
    const val RETRY_BACKOFF_BASE = 1000L                // 1秒基础退避
    
    // 清理配置
    const val MESSAGE_RETENTION_DAYS = 30               // 消息保留30天
    const val TEMP_FILE_CLEANUP_HOURS = 24              // 临时文件24小时清理
    
    // 安全配置
    const val MIN_TOKEN_VALIDITY_HOURS = 1              // 最小令牌有效期1小时
    const val MAX_TOKEN_VALIDITY_DAYS = 90              // 最大令牌有效期90天
    
    // 轮询策略配置
    const val ACTIVITY_THRESHOLD_MINUTES = 5            // 活跃阈值5分钟
    const val INACTIVE_THRESHOLD_HOURS = 1              // 非活跃阈值1小时
    const val BACKGROUND_THRESHOLD_HOURS = 24           // 后台阈值24小时
}

/**
 * COS文件路径模板
 */
object CosPathTemplates {
    // 自己的COS存储桶路径
    const val OUTBOX_ROOT = "outbox"
    const val MESSAGE_TEMPLATE = "outbox/messages"
    const val ATTACHMENT_TEMPLATE = "outbox/attachments"
    const val METADATA_TEMPLATE = "outbox/metadata"
    
    // 临时文件路径
    const val TEMP_ROOT = "temp"
    const val TEMP_UPLOADS = "temp/uploads"
    const val TEMP_PROCESSING = "temp/processing"
    
    // 系统文件路径
    const val SYSTEM_ROOT = "system"
    const val SYSTEM_CONFIG = "system/config"
    const val SYSTEM_LOGS = "system/logs"
    
    // 文件名模板
    const val MESSAGE_FILE_TEMPLATE = "%010d_%05d_%03d_%s.json"  // sequence_messageNumber_chainNumber_random.json
    const val ATTACHMENT_FILE_TEMPLATE = "%s_%s.bin"         // attachmentId_random.bin
    const val INDEX_FILE_NAME = "message_index.json"
    
    // 子账户Pool相关
    const val SUB_ACCOUNT_POOL_STATE_FILE = "sub_account_pool_state.json"
    const val SUB_ACCOUNT_POOL_BACKUP_FILE = "sub_account_pool_backup.json"
}

/**
 * 子账户Pool管理常量
 */
object SubAccountConstants {
    // 子账户Pool管理
    const val MAX_POOL_SIZE = 100                    // 最大子账户数量
    const val CLEANUP_INTERVAL_HOURS = 6            // 清理间隔6小时
    const val TOKEN_REFRESH_THRESHOLD_HOURS = 24    // 24小时内过期的令牌需要刷新（子账户永久有效，保留用于兼容）

    // 轮询配置
    const val MAX_POLLING_ERRORS = 5                // 最大连续轮询错误次数
    const val POLLING_ERROR_BACKOFF_BASE = 2000L    // 轮询错误退避基础时间2秒
    const val BATCH_POLLING_SIZE = 10               // 批量轮询大小

    // 子账户配置
    const val SUB_USER_PERMISSION = "READ_ONLY"     // 子账户权限类型
    const val PERMANENT_EXPIRE_TIME = 9223372036854775807L  // 永久有效时间戳
}

/**
 * @deprecated 使用SubAccountConstants替代
 */
typealias CamPoolConstants = SubAccountConstants

/**
 * COS操作错误码枚举
 */
enum class CosErrorCode(val code: Int, val message: String) {
    // 网络错误 (1000-1099)
    NETWORK_TIMEOUT(1001, "网络超时"),
    NETWORK_UNREACHABLE(1002, "网络不可达"),
    NETWORK_ERROR(1003, "网络错误"),
    
    // 认证错误 (1100-1199)  
    INVALID_CREDENTIALS(1101, "无效的访问凭证"),
    TOKEN_EXPIRED(1102, "访问令牌已过期"),
    PERMISSION_DENIED(1103, "权限不足"),
    AUTHENTICATION_FAILED(1104, "认证失败"),
    
    // 存储错误 (1200-1299)
    BUCKET_NOT_FOUND(1201, "存储桶不存在"),
    FILE_NOT_FOUND(1202, "文件不存在"),
    STORAGE_QUOTA_EXCEEDED(1203, "存储配额超限"),
    UPLOAD_FAILED(1204, "上传失败"),
    DOWNLOAD_FAILED(1205, "下载失败"),
    
    // 消息错误 (1300-1399)
    INVALID_MESSAGE_FORMAT(1301, "无效的消息格式"),
    MESSAGE_TOO_LARGE(1302, "消息过大"),
    DUPLICATE_MESSAGE(1303, "重复消息"),
    MESSAGE_PARSING_FAILED(1304, "消息解析失败"),
    
    // 加密错误 (1400-1499)
    DECRYPTION_FAILED(1401, "解密失败"),
    INVALID_RATCHET_STATE(1402, "无效的Ratchet状态"),
    KEY_DERIVATION_FAILED(1403, "密钥派生失败"),
    ENCRYPTION_FAILED(1404, "加密失败"),
    
    // 通道错误 (1500-1599)
    CHANNEL_NOT_FOUND(1501, "通道不存在"),
    CHANNEL_EXPIRED(1502, "通道已过期"),
    CHANNEL_REVOKED(1503, "通道已撤销"),
    CHANNEL_ERROR(1504, "通道错误"),
    
    // 系统错误 (1600-1699)
    UNKNOWN_ERROR(1601, "未知错误"),
    SYSTEM_ERROR(1602, "系统错误"),
    CONFIGURATION_ERROR(1603, "配置错误"),
    INITIALIZATION_FAILED(1604, "初始化失败")
}

/**
 * COS操作异常类
 */
class CosException(
    val errorCode: CosErrorCode,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: errorCode.message, cause) {
    
    constructor(errorCode: CosErrorCode, cause: Throwable) : this(errorCode, null, cause)
    
    /**
     * 获取错误代码
     */
    fun getCode(): Int = errorCode.code
    
    /**
     * 获取错误消息
     */
    fun getErrorMessage(): String = errorCode.message
    
    /**
     * 是否为网络错误
     */
    fun isNetworkError(): Boolean = errorCode.code in 1000..1099
    
    /**
     * 是否为认证错误
     */
    fun isAuthError(): Boolean = errorCode.code in 1100..1199
    
    /**
     * 是否为存储错误
     */
    fun isStorageError(): Boolean = errorCode.code in 1200..1299
    
    /**
     * 是否为可重试错误
     */
    fun isRetryable(): Boolean {
        return when (errorCode) {
            CosErrorCode.NETWORK_TIMEOUT,
            CosErrorCode.NETWORK_UNREACHABLE,
            CosErrorCode.NETWORK_ERROR,
            CosErrorCode.UPLOAD_FAILED,
            CosErrorCode.DOWNLOAD_FAILED -> true
            else -> false
        }
    }
}

/**
 * 操作结果封装类
 */
sealed class CosResult<out T> {
    data class Success<T>(val data: T) : CosResult<T>()
    data class Error(val exception: CosException) : CosResult<Nothing>()
    
    /**
     * 检查是否成功
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * 检查是否失败
     */
    fun isError(): Boolean = this is Error
    
    /**
     * 获取数据（如果成功）
     */
    fun getOrNull(): T? = if (this is Success) data else null
    
    /**
     * 获取异常（如果失败）
     */
    fun getErrorOrNull(): CosException? = if (this is Error) exception else null
    
    /**
     * 获取数据或抛出异常
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
    }
    
    /**
     * 映射成功结果
     */
    inline fun <R> map(transform: (T) -> R): CosResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
    
    /**
     * 处理错误
     */
    inline fun onError(action: (CosException) -> Unit): CosResult<T> {
        if (this is Error) action(exception)
        return this
    }
    
    /**
     * 处理成功
     */
    inline fun onSuccess(action: (T) -> Unit): CosResult<T> {
        if (this is Success) action(data)
        return this
    }
}

/**
 * 轮询错误类型枚举
 */
enum class PollingErrorType {
    NETWORK_ERROR,      // 网络错误，可重试
    PERMISSION_ERROR,   // 权限错误，需要重新授权
    CREDENTIAL_ERROR,   // 凭证错误，需要刷新
    RATE_LIMIT_ERROR,   // 频率限制错误，需要降低频率
    SYSTEM_ERROR        // 系统错误，需要人工干预
}

/**
 * 消息处理错误类型枚举
 */
enum class MessageProcessingErrorType {
    DECRYPTION_ERROR,   // 解密错误
    RATCHET_ERROR,      // Ratchet状态错误
    VALIDATION_ERROR,   // 消息验证错误
    STORAGE_ERROR,      // 存储错误
    SYSTEM_ERROR        // 系统错误
}

/**
 * 错误严重程度枚举
 */
enum class ErrorSeverity {
    LOW,        // 低严重程度，可以忽略
    MEDIUM,     // 中等严重程度，需要记录
    HIGH,       // 高严重程度，需要处理
    CRITICAL    // 严重错误，需要立即处理
}
