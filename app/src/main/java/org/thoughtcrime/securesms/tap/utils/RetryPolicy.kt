package org.thoughtcrime.securesms.tap.utils

/**
 * 重试策略接口
 * 
 * 定义网络请求重试的基本策略参数
 */
interface RetryPolicy {
    /**
     * 最大重试次数（不包括首次尝试）
     */
    val maxRetries: Int
    
    /**
     * 初始延迟时间（毫秒）
     */
    val initialDelayMs: Long
    
    /**
     * 延迟增长策略
     */
    val backoffStrategy: BackoffStrategy
    
    /**
     * 最大延迟时间（毫秒）
     */
    val maxDelayMs: Long
    
    /**
     * 判断异常是否可重试
     */
    fun isRetryable(throwable: Throwable): Boolean
    
    /**
     * 计算下一次重试的延迟时间
     * 
     * @param attemptNumber 当前尝试次数（从1开始）
     * @return 延迟时间（毫秒）
     */
    fun calculateDelay(attemptNumber: Int): Long {
        val delay = when (backoffStrategy) {
            BackoffStrategy.FIXED -> initialDelayMs
            BackoffStrategy.LINEAR -> initialDelayMs * attemptNumber
            BackoffStrategy.EXPONENTIAL -> initialDelayMs * (1L shl (attemptNumber - 1))
        }
        return minOf(delay, maxDelayMs)
    }
    
    /**
     * 延迟增长策略
     */
    enum class BackoffStrategy {
        FIXED,        // 固定延迟
        LINEAR,       // 线性增长
        EXPONENTIAL   // 指数退避
    }
    
    companion object {
        /**
         * 默认重试策略
         * - 最大重试3次
         * - 初始延迟1秒
         * - 指数退避
         * - 最大延迟10秒
         */
        fun default(): RetryPolicy = DefaultRetryPolicy()
        
        /**
         * 为网络操作定制的重试策略
         */
        fun forNetworkOperation(): RetryPolicy = NetworkRetryPolicy()
        
        /**
         * 为CAM/IAM API定制的重试策略
         */
        fun forCloudApiOperation(): RetryPolicy = CloudApiRetryPolicy()
    }
}

/**
 * 默认重试策略实现
 */
internal class DefaultRetryPolicy : RetryPolicy {
    override val maxRetries: Int = 3
    override val initialDelayMs: Long = 1000L
    override val backoffStrategy: RetryPolicy.BackoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL
    override val maxDelayMs: Long = 10000L
    
    override fun isRetryable(throwable: Throwable): Boolean {
        return NetworkErrorClassifier.isRetryable(throwable)
    }
}

/**
 * 网络操作重试策略
 * 较为宽松，允许更多重试
 */
internal class NetworkRetryPolicy : RetryPolicy {
    override val maxRetries: Int = 5
    override val initialDelayMs: Long = 500L
    override val backoffStrategy: RetryPolicy.BackoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL
    override val maxDelayMs: Long = 8000L
    
    override fun isRetryable(throwable: Throwable): Boolean {
        return NetworkErrorClassifier.isRetryable(throwable)
    }
}

/**
 * 云API操作重试策略
 * 考虑API限流，使用较长的延迟
 */
internal class CloudApiRetryPolicy : RetryPolicy {
    override val maxRetries: Int = 3
    override val initialDelayMs: Long = 1000L
    override val backoffStrategy: RetryPolicy.BackoffStrategy = RetryPolicy.BackoffStrategy.EXPONENTIAL
    override val maxDelayMs: Long = 15000L
    
    override fun isRetryable(throwable: Throwable): Boolean {
        // 云API特定的错误判断
        if (NetworkErrorClassifier.isRetryable(throwable)) {
            return true
        }
        
        // 检查是否是频率限制错误（429）
        val errorMessage = throwable.message?.lowercase() ?: ""
        if (errorMessage.contains("429") || 
            errorMessage.contains("rate limit") || 
            errorMessage.contains("too many requests")) {
            return true
        }
        
        return false
    }
}

