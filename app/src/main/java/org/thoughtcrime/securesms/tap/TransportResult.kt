package org.thoughtcrime.securesms.tap

/**
 * 传输结果密封类
 * 
 * 封装传输操作的各种可能结果，包括成功、失败和重试调度等状态。
 * 提供统一的结果处理接口，支持链式操作和错误处理。
 */
sealed class TransportResult {
    
    /**
     * 操作成功结果
     * 
     * @param message 成功时返回的消息（Pull操作时使用）
     * @param metadata 操作相关的元数据信息
     */
    data class Success(
        val message: TransportMessage? = null,
        val metadata: Map<String, Any> = emptyMap()
    ) : TransportResult() {
        
        /**
         * 检查是否包含消息数据
         */
        fun hasMessage(): Boolean = message != null
        
        /**
         * 获取特定的元数据值
         */
        fun getMetadata(key: String): Any? = metadata[key]
        
        /**
         * 检查是否包含特定元数据
         */
        fun hasMetadata(key: String): Boolean = metadata.containsKey(key)
    }
    
    /**
     * 操作失败结果
     * 
     * @param error 错误类型
     * @param retryable 是否可以重试
     * @param errorMessage 详细错误信息
     * @param cause 原始异常（可选）
     */
    data class Failed(
        val error: TransportError,
        val retryable: Boolean = false,
        val errorMessage: String = "",
        val cause: Throwable? = null
    ) : TransportResult() {
        
        /**
         * 检查是否为网络相关错误
         */
        fun isNetworkError(): Boolean = error == TransportError.NETWORK_ERROR
        
        /**
         * 检查是否为权限相关错误
         */
        fun isPermissionError(): Boolean = 
            error == TransportError.AUTH_ERROR || error == TransportError.PERMISSION_DENIED
        
        /**
         * 检查是否为暂时性错误（通常可重试）
         */
        fun isTemporaryError(): Boolean = when (error) {
            TransportError.NETWORK_ERROR,
            TransportError.PROVIDER_UNAVAILABLE,
            TransportError.RATE_LIMITED -> true
            else -> false
        }
        
        /**
         * 获取完整的错误描述
         */
        fun getFullErrorMessage(): String {
            return if (errorMessage.isNotBlank()) {
                "${error.displayName}: $errorMessage"
            } else {
                error.displayName
            }
        }
    }
    
    /**
     * 重试调度结果
     * 
     * @param retryAfter 重试间隔时间（毫秒）
     * @param reason 重试原因
     * @param maxRetries 最大重试次数
     * @param currentRetry 当前重试次数
     */
    data class RetryScheduled(
        val retryAfter: Long,
        val reason: String,
        val maxRetries: Int = 3,
        val currentRetry: Int = 1
    ) : TransportResult() {
        
        /**
         * 检查是否还有剩余重试次数
         */
        fun hasRemainingRetries(): Boolean = currentRetry < maxRetries
        
        /**
         * 获取下一次重试时间戳
         */
        fun getNextRetryTime(): Long = System.currentTimeMillis() + retryAfter
        
        /**
         * 创建下一次重试的调度结果
         */
        fun nextRetry(newRetryAfter: Long = retryAfter * 2): RetryScheduled {
            return copy(
                retryAfter = newRetryAfter,
                currentRetry = currentRetry + 1
            )
        }
    }
    
    /**
     * 部分成功结果（用于批量操作）
     * 
     * @param successCount 成功数量
     * @param failureCount 失败数量
     * @param results 详细结果列表
     */
    data class PartialSuccess(
        val successCount: Int,
        val failureCount: Int,
        val results: List<TransportResult>,
        val metadata: Map<String, Any> = emptyMap()
    ) : TransportResult() {
        
        /**
         * 计算成功率
         */
        fun getSuccessRate(): Double {
            val total = successCount + failureCount
            return if (total > 0) successCount.toDouble() / total else 0.0
        }
        
        /**
         * 检查是否完全成功
         */
        fun isCompleteSuccess(): Boolean = failureCount == 0
        
        /**
         * 检查是否完全失败
         */
        fun isCompleteFailure(): Boolean = successCount == 0
        
        /**
         * 获取失败的结果
         */
        fun getFailures(): List<Failed> {
            return results.filterIsInstance<Failed>()
        }
        
        /**
         * 获取成功的结果
         */
        fun getSuccesses(): List<Success> {
            return results.filterIsInstance<Success>()
        }
    }
    
    // 便利方法
    
    /**
     * 检查结果是否为成功状态
     */
    fun isSuccess(): Boolean = this is Success || (this is PartialSuccess && isCompleteSuccess())
    
    /**
     * 检查结果是否为失败状态
     */
    fun isFailure(): Boolean = this is Failed || (this is PartialSuccess && isCompleteFailure())
    
    /**
     * 检查结果是否需要重试
     */
    fun needsRetry(): Boolean = this is RetryScheduled || (this is Failed && retryable)
    
    /**
     * 获取结果中的消息（如果有）- 避免与Success.message属性冲突
     */
    fun getResultMessage(): TransportMessage? = when (this) {
        is Success -> message
        else -> null
    }
    
    /**
     * 获取错误信息（如果是失败状态）- 避免与Failed.error属性冲突
     */
    fun getResultError(): TransportError? = when (this) {
        is Failed -> error
        else -> null
    }
    
    /**
     * 获取错误消息（如果是失败状态）- 避免与Failed.errorMessage属性冲突
     */
    fun getResultErrorMessage(): String? = when (this) {
        is Failed -> if (errorMessage.isNotBlank()) errorMessage else error.displayName
        else -> null
    }
    
    companion object {
        /**
         * 创建成功结果
         */
        fun success(message: TransportMessage? = null, metadata: Map<String, Any> = emptyMap()): Success {
            return Success(message, metadata)
        }
        
        /**
         * 创建失败结果
         */
        fun failure(
            error: TransportError, 
            retryable: Boolean = false, 
            errorMessage: String = "",
            cause: Throwable? = null
        ): Failed {
            return Failed(error, retryable, errorMessage, cause)
        }
        
        /**
         * 创建重试调度结果
         */
        fun retryScheduled(
            retryAfter: Long, 
            reason: String, 
            maxRetries: Int = 3,
            currentRetry: Int = 1
        ): RetryScheduled {
            return RetryScheduled(retryAfter, reason, maxRetries, currentRetry)
        }
        
        /**
         * 创建部分成功结果
         */
        fun partialSuccess(
            successCount: Int,
            failureCount: Int,
            results: List<TransportResult>,
            metadata: Map<String, Any> = emptyMap()
        ): PartialSuccess {
            return PartialSuccess(successCount, failureCount, results, metadata)
        }
        
        /**
         * 从异常创建失败结果
         */
        fun fromException(exception: Throwable, retryable: Boolean = false): Failed {
            val error = when (exception) {
                is SecurityException -> TransportError.PERMISSION_DENIED
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException,
                is java.io.IOException -> TransportError.NETWORK_ERROR
                is IllegalArgumentException -> TransportError.INVALID_FORMAT
                else -> TransportError.UNKNOWN_ERROR
            }
            
            return Failed(
                error = error,
                retryable = retryable,
                errorMessage = exception.message ?: "",
                cause = exception
            )
        }
        
        /**
         * 合并多个传输结果
         */
        fun merge(results: List<TransportResult>): TransportResult {
            if (results.isEmpty()) {
                return failure(TransportError.INVALID_FORMAT, false, "没有结果需要合并")
            }
            
            if (results.size == 1) {
                return results[0]
            }
            
            val successes = results.filterIsInstance<Success>()
            val failures = results.filterIsInstance<Failed>()
            val retries = results.filterIsInstance<RetryScheduled>()
            
            return when {
                failures.isEmpty() && retries.isEmpty() -> 
                    success(metadata = mapOf("mergedResults" to results.size))
                
                successes.isEmpty() && retries.isEmpty() -> 
                    failures.first()
                
                retries.isNotEmpty() -> 
                    retries.first()
                
                else -> 
                    partialSuccess(
                        successCount = successes.size,
                        failureCount = failures.size,
                        results = results
                    )
            }
        }
    }
} 