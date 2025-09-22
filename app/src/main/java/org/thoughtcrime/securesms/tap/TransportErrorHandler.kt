package org.thoughtcrime.securesms.tap

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/**
 * 传输错误处理器
 * 
 * 提供统一的错误处理、重试策略和错误监控机制
 */
class TransportErrorHandler private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportErrorHandler::class.java)
        
        @Volatile
        private var INSTANCE: TransportErrorHandler? = null
        
        fun getInstance(context: Context): TransportErrorHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportErrorHandler(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
        
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
        
        // 默认重试配置
        internal const val DEFAULT_MAX_RETRIES = 3
        internal const val DEFAULT_BASE_DELAY_MS = 1000L
        internal const val DEFAULT_MAX_DELAY_MS = 30000L
        internal const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
    }
    
    // 错误统计
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()
    private val retryAttempts = ConcurrentHashMap<String, AtomicLong>()
    private val lastErrorTimes = ConcurrentHashMap<String, Long>()
    
    // 重试配置
    private var retryConfig = RetryConfig()
    
    /**
     * 处理传输错误
     * 
     * @param error 错误类型
     * @param context 错误上下文
     * @param exception 原始异常（可选）
     * @return 处理结果，包含重试建议
     */
    fun handleError(
        error: TransportError, 
        context: ErrorContext, 
        exception: Throwable? = null
    ): ErrorHandlingResult {
        try {
            Log.w(TAG, "处理传输错误: error=$error, context=$context, exception=${exception?.message}")
            
            // 记录错误统计
            recordError(error, context)
            
            // 判断重试策略
            val retryDecision = determineRetryStrategy(error, context, exception)
            
            // 记录处理结果
            val result = ErrorHandlingResult(
                error = error,
                context = context,
                shouldRetry = retryDecision.shouldRetry,
                retryDelayMs = retryDecision.retryDelayMs,
                maxRetries = retryDecision.maxRetries,
                errorMessage = buildErrorMessage(error, context, exception)
            )
            
            Log.d(TAG, "错误处理结果: shouldRetry=${result.shouldRetry}, " +
                      "retryDelayMs=${result.retryDelayMs}, maxRetries=${result.maxRetries}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "错误处理器异常: ${LogSanitizer.sanitizeThrowable(e)}")
            
            // 返回默认处理结果
            return ErrorHandlingResult(
                error = error,
                context = context,
                shouldRetry = error.isRetryable,
                retryDelayMs = DEFAULT_BASE_DELAY_MS,
                maxRetries = DEFAULT_MAX_RETRIES,
                errorMessage = "错误处理器异常: ${e.message}"
            )
        }
    }
    
    /**
     * 执行带重试的操作
     * 
     * @param operation 要执行的操作
     * @param context 错误上下文
     * @return 操作结果
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> T,
        context: ErrorContext
    ): T {
        var lastException: Exception? = null
        var retryCount = 0
        
        repeat(retryConfig.maxRetries + 1) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                retryCount = attempt
                
                val transportError = mapExceptionToTransportError(e)
                val handlingResult = handleError(transportError, context, e)
                
                if (!handlingResult.shouldRetry || attempt >= retryConfig.maxRetries) {
                    Log.w(TAG, "停止重试: attempt=$attempt, maxRetries=${retryConfig.maxRetries}")
                    throw e
                }
                
                Log.i(TAG, "准备重试: attempt=${attempt + 1}, delay=${handlingResult.retryDelayMs}ms")
                delay(handlingResult.retryDelayMs)
            }
        }
        
        // 不应该到达这里，但作为后备
        throw lastException ?: RuntimeException("操作失败且无异常信息")
    }
    
    /**
     * 获取错误统计信息
     */
    fun getErrorStatistics(): ErrorStatistics {
        val totalErrors = errorCounts.values.sumOf { it.get() }
        val totalRetries = retryAttempts.values.sumOf { it.get() }
        
        val errorDistribution = errorCounts.mapValues { (_, count) ->
            count.get()
        }
        
        return ErrorStatistics(
            totalErrors = totalErrors,
            totalRetries = totalRetries,
            errorDistribution = errorDistribution,
            lastErrorTime = lastErrorTimes.values.maxOrNull() ?: 0L
        )
    }
    
    /**
     * 更新重试配置
     */
    fun updateRetryConfig(newConfig: RetryConfig) {
        retryConfig = newConfig
        Log.i(TAG, "更新重试配置: maxRetries=${newConfig.maxRetries}, " +
                  "baseDelayMs=${newConfig.baseDelayMs}")
    }
    
    /**
     * 清除错误统计
     */
    fun clearStatistics() {
        errorCounts.clear()
        retryAttempts.clear()
        lastErrorTimes.clear()
        Log.i(TAG, "清除错误统计")
    }
    
    /**
     * 记录错误统计
     */
    private fun recordError(error: TransportError, context: ErrorContext) {
        val errorKey = "${error.errorCode}:${context.providerType}"
        val currentTime = System.currentTimeMillis()
        
        errorCounts.computeIfAbsent(errorKey) { AtomicLong(0) }.incrementAndGet()
        lastErrorTimes[errorKey] = currentTime
        
        // 记录提供者级别的统计
        val providerErrorKey = "provider:${context.providerType}"
        errorCounts.computeIfAbsent(providerErrorKey) { AtomicLong(0) }.incrementAndGet()
        
        Log.d(TAG, "记录错误统计: error=$error, provider=${context.providerType}")
    }
    
    /**
     * 确定重试策略
     */
    private fun determineRetryStrategy(
        error: TransportError, 
        context: ErrorContext, 
        exception: Throwable?
    ): RetryDecision {
        // 基于错误类型的初始判断
        if (!error.isRetryable) {
            return RetryDecision(
                shouldRetry = false,
                retryDelayMs = 0L,
                maxRetries = 0
            )
        }
        
        // 获取当前重试次数
        val retryKey = "${context.operationType}:${context.providerType}:${context.targetId}"
        val currentRetries = retryAttempts.computeIfAbsent(retryKey) { AtomicLong(0) }.get()
        
        if (currentRetries >= retryConfig.maxRetries) {
            Log.w(TAG, "达到最大重试次数: $currentRetries")
            return RetryDecision(
                shouldRetry = false,
                retryDelayMs = 0L,
                maxRetries = retryConfig.maxRetries
            )
        }
        
        // 计算退避延迟
        val retryDelayMs = calculateBackoffDelay(currentRetries.toInt(), error)
        
        // 增加重试计数
        retryAttempts[retryKey]?.incrementAndGet()
        
        return RetryDecision(
            shouldRetry = true,
            retryDelayMs = retryDelayMs,
            maxRetries = retryConfig.maxRetries
        )
    }
    
    /**
     * 计算指数退避延迟
     */
    private fun calculateBackoffDelay(retryCount: Int, error: TransportError): Long {
        val baseDelay = when (error) {
            TransportError.NETWORK_ERROR -> retryConfig.baseDelayMs
            TransportError.PROVIDER_UNAVAILABLE -> retryConfig.baseDelayMs * 2
            TransportError.RATE_LIMITED -> retryConfig.baseDelayMs * 3
            TransportError.TOKEN_EXPIRED -> 500L // Token过期快速重试
            else -> retryConfig.baseDelayMs
        }
        
        val exponentialDelay = (baseDelay * Math.pow(retryConfig.backoffMultiplier, retryCount.toDouble())).toLong()
        
        return exponentialDelay.coerceAtMost(retryConfig.maxDelayMs)
    }
    
    /**
     * 将异常映射到TransportError
     */
    private fun mapExceptionToTransportError(exception: Throwable): TransportError {
        return when (exception) {
            is SecurityException -> TransportError.AUTH_ERROR
            is java.net.UnknownHostException -> TransportError.NETWORK_ERROR
            is java.net.SocketTimeoutException -> TransportError.NETWORK_ERROR
            is java.net.ConnectException -> TransportError.NETWORK_ERROR
            is java.io.IOException -> TransportError.NETWORK_ERROR
            is IllegalArgumentException -> TransportError.INVALID_FORMAT
            is IllegalStateException -> TransportError.CONFIG_ERROR
            else -> {
                val message = exception.message?.lowercase() ?: ""
                when {
                    message.contains("permission") -> TransportError.PERMISSION_DENIED
                    message.contains("unauthorized") -> TransportError.AUTH_ERROR
                    message.contains("forbidden") -> TransportError.PERMISSION_DENIED
                    message.contains("timeout") -> TransportError.NETWORK_ERROR
                    message.contains("storage") && message.contains("full") -> TransportError.STORAGE_FULL
                    message.contains("too large") || message.contains("size") -> TransportError.MESSAGE_TOO_LARGE
                    message.contains("rate") && message.contains("limit") -> TransportError.RATE_LIMITED
                    else -> TransportError.UNKNOWN_ERROR
                }
            }
        }
    }
    
    /**
     * 构建错误消息
     */
    private fun buildErrorMessage(
        error: TransportError, 
        context: ErrorContext, 
        exception: Throwable?
    ): String {
        return buildString {
            append(error.displayName)
            
            if (context.providerType.isNotEmpty()) {
                append(" (Provider: ${context.providerType})")
            }
            
            if (context.operationType.isNotEmpty()) {
                append(" [操作: ${context.operationType}]")
            }
            
            exception?.message?.let { message ->
                if (message.isNotBlank()) {
                    append(" - ")
                    append(LogSanitizer.sanitizeGeneric(message))
                }
            }
        }
    }
}

/**
 * 错误上下文
 */
data class ErrorContext(
    val providerType: String = "",
    val operationType: String = "",
    val targetId: String = "",
    val channelId: String = "",
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 错误处理结果
 */
data class ErrorHandlingResult(
    val error: TransportError,
    val context: ErrorContext,
    val shouldRetry: Boolean,
    val retryDelayMs: Long,
    val maxRetries: Int,
    val errorMessage: String
)

/**
 * 重试决策
 */
private data class RetryDecision(
    val shouldRetry: Boolean,
    val retryDelayMs: Long,
    val maxRetries: Int
)

/**
 * 重试配置
 */
data class RetryConfig(
    val maxRetries: Int = TransportErrorHandler.DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = TransportErrorHandler.DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = TransportErrorHandler.DEFAULT_MAX_DELAY_MS,
    val backoffMultiplier: Double = TransportErrorHandler.DEFAULT_BACKOFF_MULTIPLIER
)

/**
 * 错误统计信息
 */
data class ErrorStatistics(
    val totalErrors: Long,
    val totalRetries: Long,
    val errorDistribution: Map<String, Long>,
    val lastErrorTime: Long
) 