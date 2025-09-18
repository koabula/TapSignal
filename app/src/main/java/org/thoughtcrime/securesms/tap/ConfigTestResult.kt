package org.thoughtcrime.securesms.tap

/**
 * 配置测试结果封装
 * 
 * 用于封装Provider配置测试的结果，包含成功、失败、警告等状态
 */
sealed class ConfigTestResult {
    /**
     * 测试成功
     * 
     * @param message 成功信息
     * @param details 详细信息（可选）
     */
    data class Success(
        val message: String,
        val details: String? = null
    ) : ConfigTestResult()
    
    /**
     * 测试失败
     * 
     * @param error 错误信息
     * @param details 详细错误信息（可选）
     * @param errorCode 错误代码（可选）
     */
    data class Failed(
        val error: String,
        val details: String? = null,
        val errorCode: String? = null
    ) : ConfigTestResult()
    
    /**
     * 测试警告
     * 
     * 测试通过但存在潜在问题
     * 
     * @param message 警告信息
     * @param details 详细警告信息（可选）
     * @param recommendation 建议措施（可选）
     */
    data class Warning(
        val message: String,
        val details: String? = null,
        val recommendation: String? = null
    ) : ConfigTestResult()
    
    /**
     * 测试正在进行中
     * 
     * @param message 进度信息
     * @param progress 进度百分比（0-100）
     */
    data class InProgress(
        val message: String,
        val progress: Int = 0
    ) : ConfigTestResult()
    
    /**
     * 测试被取消
     * 
     * @param reason 取消原因
     */
    data class Cancelled(
        val reason: String = "用户取消"
    ) : ConfigTestResult()
    
    /**
     * 测试超时
     * 
     * @param timeoutMs 超时时间（毫秒）
     */
    data class Timeout(
        val timeoutMs: Long
    ) : ConfigTestResult()
    
    /**
     * 是否测试成功
     */
    val isSuccess: Boolean
        get() = this is Success
    
    /**
     * 是否测试失败
     */
    val isFailed: Boolean
        get() = this is Failed
    
    /**
     * 是否有警告
     */
    val isWarning: Boolean
        get() = this is Warning
    
    /**
     * 是否正在进行中
     */
    val isInProgress: Boolean
        get() = this is InProgress
    
    /**
     * 测试是否已完成（成功、失败、警告、取消、超时）
     */
    val isCompleted: Boolean
        get() = when (this) {
            is Success, is Failed, is Warning, is Cancelled, is Timeout -> true
            is InProgress -> false
        }
    
    /**
     * 获取主要消息
     */
    fun getResultMessage(): String {
        return when (this) {
            is Success -> message
            is Failed -> error
            is Warning -> message
            is InProgress -> message
            is Cancelled -> "测试已取消: $reason"
            is Timeout -> "测试超时: ${timeoutMs}ms"
        }
    }
    
    /**
     * 获取详细信息
     */
    fun getResultDetails(): String? {
        return when (this) {
            is Success -> details
            is Failed -> details
            is Warning -> details
            is InProgress -> null
            is Cancelled -> null
            is Timeout -> null
        }
    }
    
    companion object {
        /**
         * 创建成功结果
         */
        fun success(message: String, details: String? = null): ConfigTestResult {
            return Success(message, details)
        }
        
        /**
         * 创建失败结果
         */
        fun failed(error: String, details: String? = null, errorCode: String? = null): ConfigTestResult {
            return Failed(error, details, errorCode)
        }
        
        /**
         * 创建警告结果
         */
        fun warning(message: String, details: String? = null, recommendation: String? = null): ConfigTestResult {
            return Warning(message, details, recommendation)
        }
        
        /**
         * 创建进行中结果
         */
        fun inProgress(message: String, progress: Int = 0): ConfigTestResult {
            return InProgress(message, progress)
        }
        
        /**
         * 创建取消结果
         */
        fun cancelled(reason: String = "用户取消"): ConfigTestResult {
            return Cancelled(reason)
        }
        
        /**
         * 创建超时结果
         */
        fun timeout(timeoutMs: Long): ConfigTestResult {
            return Timeout(timeoutMs)
        }
        
        /**
         * 从异常创建失败结果
         */
        fun fromException(exception: Exception): ConfigTestResult {
            return Failed(
                error = exception.message ?: "未知错误",
                details = exception.stackTraceToString(),
                errorCode = exception.javaClass.simpleName
            )
        }
    }
}

/**
 * 批量配置测试结果
 * 
 * 用于封装多个Provider配置的批量测试结果
 */
data class BatchConfigTestResult(
    /**
     * 各个Provider的测试结果
     */
    val providerResults: Map<String, ConfigTestResult>
) {
    /**
     * 是否所有Provider配置都测试成功
     */
    val isAllSuccess: Boolean
        get() = providerResults.values.all { it.isSuccess }
    
    /**
     * 是否有任何Provider配置测试失败
     */
    val hasAnyFailed: Boolean
        get() = providerResults.values.any { it.isFailed }
    
    /**
     * 是否有任何Provider配置测试有警告
     */
    val hasAnyWarning: Boolean
        get() = providerResults.values.any { it.isWarning }
    
    /**
     * 是否所有测试都已完成
     */
    val isAllCompleted: Boolean
        get() = providerResults.values.all { it.isCompleted }
    
    /**
     * 获取测试成功的Provider列表
     */
    fun getSuccessProviders(): List<String> {
        return providerResults.filter { it.value.isSuccess }.keys.toList()
    }
    
    /**
     * 获取测试失败的Provider列表
     */
    fun getFailedProviders(): List<String> {
        return providerResults.filter { it.value.isFailed }.keys.toList()
    }
    
    /**
     * 获取有警告的Provider列表
     */
    fun getWarningProviders(): List<String> {
        return providerResults.filter { it.value.isWarning }.keys.toList()
    }
    
    /**
     * 获取正在测试中的Provider列表
     */
    fun getInProgressProviders(): List<String> {
        return providerResults.filter { it.value.isInProgress }.keys.toList()
    }
    
    /**
     * 获取特定Provider的测试结果
     */
    fun getProviderResult(providerType: String): ConfigTestResult? {
        return providerResults[providerType]
    }
    
    /**
     * 获取整体测试进度（0-100）
     */
    fun getOverallProgress(): Int {
        if (providerResults.isEmpty()) return 100
        
        val totalProviders = providerResults.size
        val completedProviders = providerResults.values.count { it.isCompleted }
        
        return (completedProviders * 100) / totalProviders
    }
    
    /**
     * 获取测试摘要信息
     */
    fun getSummary(): String {
        val total = providerResults.size
        val success = getSuccessProviders().size
        val failed = getFailedProviders().size
        val warning = getWarningProviders().size
        val inProgress = getInProgressProviders().size
        
        return buildString {
            append("总计: $total")
            if (success > 0) append(", 成功: $success")
            if (failed > 0) append(", 失败: $failed")
            if (warning > 0) append(", 警告: $warning")
            if (inProgress > 0) append(", 进行中: $inProgress")
        }
    }
    
    companion object {
        /**
         * 创建空的批量测试结果
         */
        fun empty(): BatchConfigTestResult = BatchConfigTestResult(emptyMap())
        
        /**
         * 从单个测试结果创建批量结果
         */
        fun single(providerType: String, result: ConfigTestResult): BatchConfigTestResult {
            return BatchConfigTestResult(mapOf(providerType to result))
        }
    }
} 