package org.thoughtcrime.securesms.tap.polling

import android.util.Log
import org.thoughtcrime.securesms.tap.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 轮询错误分类器
 * 
 * 根据错误类型对错误进行分类，并提供相应的处理策略。
 * 不同类型的错误采用不同的重试策略、退避时间和暂停条件。
 */
class PollingErrorClassifier {
    
    companion object {
        private const val TAG = "PollingErrorClassifier"
    }
    
    /**
     * 分类错误类型
     */
    fun classifyError(error: Throwable): PollingErrorType {
        return when {
            // 网络相关错误
            error is UnknownHostException -> PollingErrorType.NETWORK_UNREACHABLE
            error is SocketTimeoutException -> PollingErrorType.NETWORK_TIMEOUT
            error is TimeoutException || error is TimeoutCancellationException -> PollingErrorType.NETWORK_TIMEOUT
            error is IOException && error.message?.contains("network", true) == true -> PollingErrorType.NETWORK_ERROR
            error is SSLException -> PollingErrorType.NETWORK_SSL_ERROR
            
            // Provider服务错误
            error is IllegalStateException && error.message?.contains("provider", true) == true -> PollingErrorType.PROVIDER_ERROR
            error is IllegalArgumentException && error.message?.contains("credential", true) == true -> PollingErrorType.PROVIDER_AUTH_ERROR
            
            // 权限相关错误
            error is IllegalAccessException -> PollingErrorType.PERMISSION_ERROR
            error.message?.contains("permission", true) == true -> PollingErrorType.PERMISSION_ERROR
            
            // 数据解析错误
            error.message?.contains("parse", true) == true -> PollingErrorType.PARSING_ERROR
            error.message?.contains("json", true) == true -> PollingErrorType.PARSING_ERROR
            error.message?.contains("xml", true) == true -> PollingErrorType.PARSING_ERROR
            
            // 文件操作错误
            error is IOException && error.message?.contains("file", true) == true -> PollingErrorType.FILE_ERROR
            
            // 系统资源错误
            error is OutOfMemoryError -> PollingErrorType.SYSTEM_RESOURCE_ERROR
            error.message?.contains("memory", true) == true -> PollingErrorType.SYSTEM_RESOURCE_ERROR
            
            // 配置错误
            error.message?.contains("config", true) == true -> PollingErrorType.CONFIGURATION_ERROR
            
            // 其他未知错误
            else -> PollingErrorType.UNKNOWN_ERROR
        }
    }
    
    /**
     * 获取错误处理策略
     */
    fun getErrorStrategy(errorType: PollingErrorType): PollingErrorStrategy {
        return when (errorType) {
            PollingErrorType.NETWORK_UNREACHABLE -> NetworkUnreachableStrategy()
            PollingErrorType.NETWORK_TIMEOUT -> NetworkTimeoutStrategy()
            PollingErrorType.NETWORK_ERROR -> NetworkErrorStrategy()
            PollingErrorType.NETWORK_SSL_ERROR -> SslErrorStrategy()
            PollingErrorType.PROVIDER_ERROR -> ProviderErrorStrategy()
            PollingErrorType.PROVIDER_AUTH_ERROR -> AuthErrorStrategy()
            PollingErrorType.PERMISSION_ERROR -> PermissionErrorStrategy()
            PollingErrorType.PARSING_ERROR -> ParsingErrorStrategy()
            PollingErrorType.FILE_ERROR -> FileErrorStrategy()
            PollingErrorType.SYSTEM_RESOURCE_ERROR -> SystemResourceErrorStrategy()
            PollingErrorType.CONFIGURATION_ERROR -> ConfigurationErrorStrategy()
            PollingErrorType.UNKNOWN_ERROR -> UnknownErrorStrategy()
        }
    }
    
    /**
     * 创建错误处理决策
     */
    fun createErrorDecision(
        error: Throwable,
        attemptCount: Int,
        lastAttemptTime: Long,
        taskInfo: PollingTaskInfo
    ): PollingErrorDecision {
        val errorType = classifyError(error)
        val strategy = getErrorStrategy(errorType)
        
        val shouldRetry = strategy.shouldRetry(errorType, attemptCount)
        val backoffTime = strategy.calculateBackoffTime(errorType, attemptCount)
        val shouldSuspend = strategy.shouldSuspendPolling(errorType, attemptCount)
        val recoverable = strategy.isRecoverable(errorType)
        
        return PollingErrorDecision(
            errorType = errorType,
            shouldRetry = shouldRetry,
            backoffTimeMs = backoffTime,
            shouldSuspendPolling = shouldSuspend,
            isRecoverable = recoverable,
            recommendedAction = strategy.getRecommendedAction(errorType, attemptCount),
            errorMessage = strategy.getErrorMessage(errorType, error)
        )
    }
    
    /**
     * 记录错误统计
     */
    fun recordErrorStatistics(errorType: PollingErrorType, taskInfo: PollingTaskInfo) {
        try {
            // 这里可以记录到统计系统
            Log.d(TAG, "记录错误统计: type=$errorType, recipient=${taskInfo.recipientId}")
        } catch (e: Exception) {
            // 静默处理统计错误
        }
    }
}

/**
 * 轮询错误类型
 */
enum class PollingErrorType {
    NETWORK_UNREACHABLE,    // 网络不可达
    NETWORK_TIMEOUT,        // 网络超时
    NETWORK_ERROR,          // 一般网络错误
    NETWORK_SSL_ERROR,      // SSL错误
    PROVIDER_ERROR,         // Provider服务错误
    PROVIDER_AUTH_ERROR,    // Provider认证错误
    PERMISSION_ERROR,       // 权限错误
    PARSING_ERROR,          // 数据解析错误
    FILE_ERROR,             // 文件操作错误
    SYSTEM_RESOURCE_ERROR,  // 系统资源错误
    CONFIGURATION_ERROR,    // 配置错误
    UNKNOWN_ERROR          // 未知错误
}

/**
 * 轮询错误决策
 */
data class PollingErrorDecision(
    val errorType: PollingErrorType,
    val shouldRetry: Boolean,
    val backoffTimeMs: Long,
    val shouldSuspendPolling: Boolean,
    val isRecoverable: Boolean,
    val recommendedAction: ErrorAction,
    val errorMessage: String
)

/**
 * 错误处理建议动作
 */
enum class ErrorAction {
    RETRY_IMMEDIATELY,      // 立即重试
    RETRY_WITH_BACKOFF,     // 延迟重试
    SKIP_CURRENT_TASK,      // 跳过当前任务
    SUSPEND_POLLING,        // 暂停轮询
    RESTART_SERVICE,        // 重启服务
    CHECK_CONFIGURATION,    // 检查配置
    CHECK_PERMISSIONS,      // 检查权限
    NO_ACTION              // 无需动作
}

/**
 * 轮询错误处理策略接口
 */
interface PollingErrorStrategy {
    fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean
    fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long
    fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean
    fun isRecoverable(errorType: PollingErrorType): Boolean
    fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction
    fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String
}

/**
 * 网络不可达错误策略
 */
private class NetworkUnreachableStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 5 // 最多重试5次
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        // 网络不可达时使用指数退避，但上限较高
        return minOf(5000L * (1 shl attemptCount), 300000L) // 最大5分钟
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount >= 3 // 连续3次失败后暂停
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return if (attemptCount < 3) ErrorAction.RETRY_WITH_BACKOFF else ErrorAction.SUSPEND_POLLING
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "网络不可达，请检查网络连接"
    }
}

/**
 * 网络超时错误策略
 */
private class NetworkTimeoutStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 3 // 超时重试次数较少
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 10000L + (attemptCount * 5000L) // 线性增长
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false // 超时不暂停，只是延长间隔
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.RETRY_WITH_BACKOFF
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "网络请求超时，将延长轮询间隔"
    }
}

/**
 * 一般网络错误策略
 */
private class NetworkErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 4
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 2000L * (1 shl attemptCount).coerceAtMost(8) // 指数退避，最大16秒
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount >= 4
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.RETRY_WITH_BACKOFF
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "网络连接异常: ${error.message}"
    }
}

/**
 * SSL错误策略
 */
private class SslErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 2 // SSL错误重试次数很少
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 30000L // 固定30秒
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return true // SSL错误通常需要暂停并检查配置
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = false
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.CHECK_CONFIGURATION
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "SSL连接错误，请检查证书配置"
    }
}

/**
 * Provider服务错误策略
 */
private class ProviderErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 3
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 60000L * attemptCount // 1分钟、2分钟、3分钟
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount >= 2
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.RETRY_WITH_BACKOFF
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "Provider服务异常: ${error.message}"
    }
}

/**
 * 认证错误策略
 */
private class AuthErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false // 认证错误不重试
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 0L
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return true // 立即暂停
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = false
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.CHECK_CONFIGURATION
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "认证失败，请检查凭据配置"
    }
}

/**
 * 权限错误策略
 */
private class PermissionErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 0L
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return true
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = false
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.CHECK_PERMISSIONS
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "权限不足: ${error.message}"
    }
}

/**
 * 解析错误策略
 */
private class ParsingErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 2 // 解析错误重试1次
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 5000L // 固定5秒
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false // 解析错误不暂停轮询
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.SKIP_CURRENT_TASK
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "数据解析失败，跳过当前文件"
    }
}

/**
 * 文件错误策略
 */
private class FileErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 2
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 10000L
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.SKIP_CURRENT_TASK
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "文件操作失败: ${error.message}"
    }
}

/**
 * 系统资源错误策略
 */
private class SystemResourceErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false // 系统资源不足不重试
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 0L
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return true // 立即暂停
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.SUSPEND_POLLING
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "系统资源不足，暂停轮询"
    }
}

/**
 * 配置错误策略
 */
private class ConfigurationErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return false
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 0L
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return true
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = false
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.CHECK_CONFIGURATION
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "配置错误: ${error.message}"
    }
}

/**
 * 未知错误策略
 */
private class UnknownErrorStrategy : PollingErrorStrategy {
    override fun shouldRetry(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount < 2
    }
    
    override fun calculateBackoffTime(errorType: PollingErrorType, attemptCount: Int): Long {
        return 30000L // 未知错误使用较长退避
    }
    
    override fun shouldSuspendPolling(errorType: PollingErrorType, attemptCount: Int): Boolean {
        return attemptCount >= 2
    }
    
    override fun isRecoverable(errorType: PollingErrorType): Boolean = true
    
    override fun getRecommendedAction(errorType: PollingErrorType, attemptCount: Int): ErrorAction {
        return ErrorAction.RETRY_WITH_BACKOFF
    }
    
    override fun getErrorMessage(errorType: PollingErrorType, error: Throwable): String {
        return "未知错误: ${error.javaClass.simpleName} - ${error.message}"
    }
} 