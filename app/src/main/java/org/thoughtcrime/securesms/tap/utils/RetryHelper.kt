package org.thoughtcrime.securesms.tap.utils

import kotlinx.coroutines.delay
import org.signal.core.util.logging.Log

/**
 * 重试帮助工具
 * 
 * 提供通用的重试包装方法，支持suspend函数和普通函数
 */
object RetryHelper {
    
    private val TAG = Log.tag(RetryHelper::class.java)
    
    /**
     * 带重试的执行suspend函数
     * 
     * @param policy 重试策略
     * @param operationName 操作名称（用于日志）
     * @param block 要执行的操作
     * @return 操作结果
     * @throws Exception 如果所有重试都失败，抛出最后一次的异常
     */
    suspend fun <T> executeWithRetry(
        policy: RetryPolicy = RetryPolicy.default(),
        operationName: String = "操作",
        block: suspend () -> T
    ): T {
        var lastException: Throwable? = null
        val totalAttempts = policy.maxRetries + 1
        
        for (attempt in 1..totalAttempts) {
            try {
                if (attempt > 1) {
                    Log.i(TAG, "$operationName: 第 $attempt/$totalAttempts 次尝试")
                } else {
                    Log.d(TAG, "$operationName: 首次尝试")
                }
                
                val result = block()
                
                if (attempt > 1) {
                    Log.i(TAG, "$operationName: 第 $attempt 次尝试成功")
                }
                
                return result
                
            } catch (e: Throwable) {
                lastException = e
                
                // 判断是否应该重试
                if (attempt >= totalAttempts) {
                    Log.e(TAG, "$operationName: 所有重试均失败 (${attempt}次尝试)", e)
                    throw e
                }
                
                if (!policy.isRetryable(e)) {
                    Log.w(TAG, "$operationName: 遇到不可重试的错误，停止重试", e)
                    throw e
                }
                
                // 计算延迟时间
                val delayMs = policy.calculateDelay(attempt)
                Log.w(TAG, "$operationName: 第 $attempt 次尝试失败，${delayMs}ms后重试 (${e.javaClass.simpleName}: ${e.message})")
                
                // 等待后重试
                delay(delayMs)
            }
        }
        
        // 理论上不会到达这里，但为了类型安全
        throw lastException ?: IllegalStateException("重试逻辑异常")
    }
    
    /**
     * 带重试的执行普通函数（阻塞式）
     * 
     * @param policy 重试策略
     * @param operationName 操作名称（用于日志）
     * @param block 要执行的操作
     * @return 操作结果
     * @throws Exception 如果所有重试都失败，抛出最后一次的异常
     */
    fun <T> executeWithRetryBlocking(
        policy: RetryPolicy = RetryPolicy.default(),
        operationName: String = "操作",
        block: () -> T
    ): T {
        var lastException: Throwable? = null
        val totalAttempts = policy.maxRetries + 1
        
        for (attempt in 1..totalAttempts) {
            try {
                if (attempt > 1) {
                    Log.i(TAG, "$operationName: 第 $attempt/$totalAttempts 次尝试")
                } else {
                    Log.d(TAG, "$operationName: 首次尝试")
                }
                
                val result = block()
                
                if (attempt > 1) {
                    Log.i(TAG, "$operationName: 第 $attempt 次尝试成功")
                }
                
                return result
                
            } catch (e: Throwable) {
                lastException = e
                
                // 判断是否应该重试
                if (attempt >= totalAttempts) {
                    Log.e(TAG, "$operationName: 所有重试均失败 (${attempt}次尝试)", e)
                    throw e
                }
                
                if (!policy.isRetryable(e)) {
                    Log.w(TAG, "$operationName: 遇到不可重试的错误，停止重试", e)
                    throw e
                }
                
                // 计算延迟时间
                val delayMs = policy.calculateDelay(attempt)
                Log.w(TAG, "$operationName: 第 $attempt 次尝试失败，${delayMs}ms后重试 (${e.javaClass.simpleName}: ${e.message})")
                
                // 等待后重试
                Thread.sleep(delayMs)
            }
        }
        
        // 理论上不会到达这里，但为了类型安全
        throw lastException ?: IllegalStateException("重试逻辑异常")
    }
    
    /**
     * 创建自定义重试策略构建器
     */
    fun customPolicy(): RetryPolicyBuilder = RetryPolicyBuilder()
    
    /**
     * 重试策略构建器
     */
    class RetryPolicyBuilder {
        private var maxRetries: Int = 3
        private var initialDelayMs: Long = 1000L
        private var backoffStrategy: RetryPolicy.BackoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL
        private var maxDelayMs: Long = 10000L
        private var retryableChecker: (Throwable) -> Boolean = NetworkErrorClassifier::isRetryable
        
        fun maxRetries(value: Int) = apply { this.maxRetries = value }
        fun initialDelay(ms: Long) = apply { this.initialDelayMs = ms }
        fun backoffStrategy(strategy: RetryPolicy.BackoffStrategy) = apply { this.backoffStrategy = strategy }
        fun maxDelay(ms: Long) = apply { this.maxDelayMs = ms }
        fun retryableChecker(checker: (Throwable) -> Boolean) = apply { this.retryableChecker = checker }
        
        fun build(): RetryPolicy {
            return object : RetryPolicy {
                override val maxRetries: Int = this@RetryPolicyBuilder.maxRetries
                override val initialDelayMs: Long = this@RetryPolicyBuilder.initialDelayMs
                override val backoffStrategy: RetryPolicy.BackoffStrategy = this@RetryPolicyBuilder.backoffStrategy
                override val maxDelayMs: Long = this@RetryPolicyBuilder.maxDelayMs
                
                override fun isRetryable(throwable: Throwable): Boolean {
                    return this@RetryPolicyBuilder.retryableChecker(throwable)
                }
            }
        }
    }
}

