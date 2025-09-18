package org.thoughtcrime.securesms.tap

/**
 * 传输错误枚举
 * 
 * 定义了传输层可能出现的各种错误类型，用于统一的错误处理和分类。
 * 每种错误类型包含显示名称、错误代码和默认重试策略。
 */
enum class TransportError(
    val displayName: String,
    val errorCode: String,
    val isRetryable: Boolean = false,
    val description: String = ""
) {
    /** 网络连接错误 */
    NETWORK_ERROR(
        displayName = "网络连接错误",
        errorCode = "NETWORK_ERROR",
        isRetryable = true,
        description = "网络连接超时、断开或不可达"
    ),
    
    /** 认证错误 */
    AUTH_ERROR(
        displayName = "认证失败",
        errorCode = "AUTH_ERROR",
        isRetryable = false,
        description = "身份认证失败，凭证无效或已过期"
    ),
    
    /** 权限被拒绝 */
    PERMISSION_DENIED(
        displayName = "权限被拒绝",
        errorCode = "PERMISSION_DENIED",
        isRetryable = false,
        description = "没有执行该操作的权限"
    ),
    
    /** 存储空间已满 */
    STORAGE_FULL(
        displayName = "存储空间已满",
        errorCode = "STORAGE_FULL",
        isRetryable = false,
        description = "目标存储空间已满，无法写入更多数据"
    ),
    
    /** 消息过大 */
    MESSAGE_TOO_LARGE(
        displayName = "消息过大",
        errorCode = "MESSAGE_TOO_LARGE",
        isRetryable = false,
        description = "消息大小超过了传输服务的限制"
    ),
    
    /** 格式无效 */
    INVALID_FORMAT(
        displayName = "格式无效",
        errorCode = "INVALID_FORMAT",
        isRetryable = false,
        description = "消息格式不正确或不被支持"
    ),
    
    /** 传输服务不可用 */
    PROVIDER_UNAVAILABLE(
        displayName = "传输服务不可用",
        errorCode = "PROVIDER_UNAVAILABLE",
        isRetryable = true,
        description = "传输服务暂时不可用或维护中"
    ),
    
    /** 速率限制 */
    RATE_LIMITED(
        displayName = "请求过于频繁",
        errorCode = "RATE_LIMITED",
        isRetryable = true,
        description = "请求频率超过了服务限制"
    ),
    
    /** Token过期 */
    TOKEN_EXPIRED(
        displayName = "访问凭证已过期",
        errorCode = "TOKEN_EXPIRED",
        isRetryable = true,
        description = "访问凭证已过期，需要刷新"
    ),
    
    /** 配置错误 */
    CONFIG_ERROR(
        displayName = "配置错误",
        errorCode = "CONFIG_ERROR",
        isRetryable = false,
        description = "传输服务配置不正确"
    ),
    
    /** 文件不存在 */
    FILE_NOT_FOUND(
        displayName = "文件不存在",
        errorCode = "FILE_NOT_FOUND",
        isRetryable = false,
        description = "请求的文件不存在"
    ),
    
    /** 服务器内部错误 */
    SERVER_ERROR(
        displayName = "服务器内部错误",
        errorCode = "SERVER_ERROR",
        isRetryable = true,
        description = "传输服务内部发生错误"
    ),
    
    /** 协议版本不兼容 */
    PROTOCOL_VERSION_MISMATCH(
        displayName = "协议版本不兼容",
        errorCode = "PROTOCOL_VERSION_MISMATCH",
        isRetryable = false,
        description = "传输协议版本不兼容"
    ),
    
    /** 加密错误 */
    ENCRYPTION_ERROR(
        displayName = "加密错误",
        errorCode = "ENCRYPTION_ERROR",
        isRetryable = false,
        description = "消息加密或解密失败"
    ),
    
    /** 未知错误 */
    UNKNOWN_ERROR(
        displayName = "未知错误",
        errorCode = "UNKNOWN_ERROR",
        isRetryable = false,
        description = "发生了未知的错误"
    );
    
    companion object {
        /**
         * 根据错误代码获取错误类型
         */
        fun fromErrorCode(errorCode: String): TransportError? {
            return values().find { it.errorCode == errorCode }
        }
        
        /**
         * 获取所有可重试的错误类型
         */
        fun getRetryableErrors(): List<TransportError> {
            return values().filter { it.isRetryable }
        }
        
        /**
         * 获取所有不可重试的错误类型
         */
        fun getNonRetryableErrors(): List<TransportError> {
            return values().filter { !it.isRetryable }
        }
        
        /**
         * 根据异常类型推断错误类型
         */
        fun fromException(exception: Throwable): TransportError {
            return when (exception) {
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException,
                is java.net.ConnectException -> NETWORK_ERROR
                
                is java.io.IOException -> {
                    val message = exception.message?.lowercase() ?: ""
                    when {
                        message.contains("permission") -> PERMISSION_DENIED
                        message.contains("not found") -> FILE_NOT_FOUND
                        message.contains("space") && message.contains("full") -> STORAGE_FULL
                        else -> NETWORK_ERROR
                    }
                }
                
                is SecurityException -> PERMISSION_DENIED
                is IllegalArgumentException -> INVALID_FORMAT
                is javax.crypto.BadPaddingException,
                is java.security.GeneralSecurityException -> ENCRYPTION_ERROR
                
                else -> UNKNOWN_ERROR
            }
        }
    }
}

/**
 * 传输异常基类
 * 
 * 所有传输相关异常的基类，包含错误类型和详细信息。
 */
open class TransportException(
    val transportError: TransportError,
    message: String = transportError.displayName,
    cause: Throwable? = null
) : Exception(message, cause) {
    
    /**
     * 检查是否可以重试
     */
    fun isRetryable(): Boolean = transportError.isRetryable
    
    /**
     * 获取错误代码
     */
    fun getErrorCode(): String = transportError.errorCode
    
    /**
     * 获取完整错误信息
     */
    fun getFullMessage(): String {
        return "${transportError.displayName}: ${message ?: transportError.description}"
    }
}

/**
 * 网络传输异常
 */
class NetworkTransportException(
    message: String = "网络连接失败",
    cause: Throwable? = null
) : TransportException(TransportError.NETWORK_ERROR, message, cause)

/**
 * 权限传输异常
 */
class PermissionTransportException(
    message: String = "权限不足",
    cause: Throwable? = null
) : TransportException(TransportError.PERMISSION_DENIED, message, cause)

/**
 * 认证传输异常
 */
class AuthTransportException(
    message: String = "身份认证失败",
    cause: Throwable? = null
) : TransportException(TransportError.AUTH_ERROR, message, cause)

/**
 * Token过期异常
 */
class TokenExpiredTransportException(
    message: String = "访问凭证已过期",
    cause: Throwable? = null
) : TransportException(TransportError.TOKEN_EXPIRED, message, cause)

/**
 * 存储空间已满异常
 */
class StorageFullTransportException(
    message: String = "存储空间已满",
    cause: Throwable? = null
) : TransportException(TransportError.STORAGE_FULL, message, cause)

/**
 * 消息过大异常
 */
class MessageTooLargeTransportException(
    message: String = "消息大小超过限制",
    cause: Throwable? = null
) : TransportException(TransportError.MESSAGE_TOO_LARGE, message, cause)

/**
 * 格式无效异常
 */
class InvalidFormatTransportException(
    message: String = "数据格式无效",
    cause: Throwable? = null
) : TransportException(TransportError.INVALID_FORMAT, message, cause)

/**
 * 服务不可用异常
 */
class ProviderUnavailableTransportException(
    message: String = "传输服务不可用",
    cause: Throwable? = null
) : TransportException(TransportError.PROVIDER_UNAVAILABLE, message, cause)

/**
 * 速率限制异常
 */
class RateLimitedTransportException(
    message: String = "请求过于频繁",
    cause: Throwable? = null,
    val retryAfterMs: Long = 60000L
) : TransportException(TransportError.RATE_LIMITED, message, cause)

/**
 * 配置错误异常
 */
class ConfigErrorTransportException(
    message: String = "配置错误",
    cause: Throwable? = null
) : TransportException(TransportError.CONFIG_ERROR, message, cause)

/**
 * 文件不存在异常
 */
class FileNotFoundTransportException(
    message: String = "文件不存在",
    cause: Throwable? = null
) : TransportException(TransportError.FILE_NOT_FOUND, message, cause)

/**
 * 加密错误异常
 */
class EncryptionTransportException(
    message: String = "加密操作失败",
    cause: Throwable? = null
) : TransportException(TransportError.ENCRYPTION_ERROR, message, cause)

/**
 * 传输异常工厂
 * 
 * 用于根据错误类型创建相应的异常实例。
 */
object TransportExceptionFactory {
    
    /**
     * 根据传输错误创建异常
     */
    fun createException(
        error: TransportError,
        message: String? = null,
        cause: Throwable? = null
    ): TransportException {
        val errorMessage = message ?: error.displayName
        
        return when (error) {
            TransportError.NETWORK_ERROR -> NetworkTransportException(errorMessage, cause)
            TransportError.AUTH_ERROR -> AuthTransportException(errorMessage, cause)
            TransportError.PERMISSION_DENIED -> PermissionTransportException(errorMessage, cause)
            TransportError.TOKEN_EXPIRED -> TokenExpiredTransportException(errorMessage, cause)
            TransportError.STORAGE_FULL -> StorageFullTransportException(errorMessage, cause)
            TransportError.MESSAGE_TOO_LARGE -> MessageTooLargeTransportException(errorMessage, cause)
            TransportError.INVALID_FORMAT -> InvalidFormatTransportException(errorMessage, cause)
            TransportError.PROVIDER_UNAVAILABLE -> ProviderUnavailableTransportException(errorMessage, cause)
            TransportError.RATE_LIMITED -> RateLimitedTransportException(errorMessage, cause)
            TransportError.CONFIG_ERROR -> ConfigErrorTransportException(errorMessage, cause)
            TransportError.FILE_NOT_FOUND -> FileNotFoundTransportException(errorMessage, cause)
            TransportError.ENCRYPTION_ERROR -> EncryptionTransportException(errorMessage, cause)
            else -> TransportException(error, errorMessage, cause)
        }
    }
    
    /**
     * 从通用异常创建传输异常
     */
    fun fromGenericException(exception: Throwable): TransportException {
        val transportError = TransportError.fromException(exception)
        return createException(transportError, exception.message, exception)
    }
} 