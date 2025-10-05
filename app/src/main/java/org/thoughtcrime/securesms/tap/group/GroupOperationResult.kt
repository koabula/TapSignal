package org.thoughtcrime.securesms.tap.group

/**
 * 群组操作结果
 * 
 * 用于替代直接返回布尔值或空值，提供更详细的错误信息
 */
sealed class GroupOperationResult<out T> {
    /**
     * 操作成功
     */
    data class Success<T>(val data: T) : GroupOperationResult<T>()
    
    /**
     * 操作失败
     */
    data class Failed(
        val error: GroupOperationError,
        val message: String,
        val cause: Throwable? = null
    ) : GroupOperationResult<Nothing>()
    
    /**
     * 是否成功
     */
    fun isSuccess(): Boolean = this is Success
    
    /**
     * 是否失败
     */
    fun isFailed(): Boolean = this is Failed
    
    /**
     * 获取数据（如果成功）
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failed -> null
    }
    
    /**
     * 获取数据或抛出异常
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failed -> throw GroupOperationException(error, message, cause)
    }
    
    /**
     * 转换成功值
     */
    inline fun <R> map(transform: (T) -> R): GroupOperationResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failed -> this
    }
    
    /**
     * 转换为其他 Result
     */
    inline fun <R> flatMap(transform: (T) -> GroupOperationResult<R>): GroupOperationResult<R> = when (this) {
        is Success -> transform(data)
        is Failed -> this
    }
}

/**
 * 群组操作错误类型
 */
enum class GroupOperationError {
    GROUP_NOT_FOUND,
    INVALID_GROUP_ID,
    INVALID_STATE,
    INVALID_MEMBER,
    DATABASE_ERROR,
    OPTIMISTIC_LOCK_CONFLICT,
    PERMISSION_DENIED,
    PROVIDER_ERROR,
    NETWORK_ERROR,
    OPERATION_FAILED,
    UNKNOWN_ERROR
}

/**
 * 群组操作异常
 */
class GroupOperationException(
    val error: GroupOperationError,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
