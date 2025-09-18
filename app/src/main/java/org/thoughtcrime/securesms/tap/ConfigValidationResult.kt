package org.thoughtcrime.securesms.tap

/**
 * 配置验证结果封装
 * 
 * 用于封装Provider配置验证的结果，包含成功状态或错误信息
 */
sealed class ConfigValidationResult {
    /**
     * 验证成功
     */
    object Valid : ConfigValidationResult()
    
    /**
     * 验证失败
     * 
     * @param errors 字段错误映射，key为字段键名，value为错误信息
     */
    data class Invalid(val errors: Map<String, String>) : ConfigValidationResult() {
        
        /**
         * 创建单个字段错误的验证结果
         */
        constructor(fieldKey: String, errorMessage: String) : this(mapOf(fieldKey to errorMessage))
        
        /**
         * 获取所有错误信息的列表
         */
        fun getAllErrors(): List<String> = errors.values.toList()
        
        /**
         * 获取格式化的错误信息字符串
         */
        fun getFormattedErrors(): String = errors.values.joinToString("; ")
        
        /**
         * 检查特定字段是否有错误
         */
        fun hasFieldError(fieldKey: String): Boolean = errors.containsKey(fieldKey)
        
        /**
         * 获取特定字段的错误信息
         */
        fun getFieldError(fieldKey: String): String? = errors[fieldKey]
        
        /**
         * 合并其他验证结果的错误
         */
        fun merge(other: ConfigValidationResult): ConfigValidationResult {
            return when (other) {
                is Valid -> this
                is Invalid -> Invalid(this.errors + other.errors)
            }
        }
    }
    
    /**
     * 是否验证通过
     */
    val isValid: Boolean
        get() = this is Valid
    
    /**
     * 是否验证失败
     */
    val isInvalid: Boolean
        get() = this is Invalid
    
    companion object {
        /**
         * 创建成功的验证结果
         */
        fun success(): ConfigValidationResult = Valid
        
        /**
         * 创建失败的验证结果
         */
        fun failure(errors: Map<String, String>): ConfigValidationResult = Invalid(errors)
        
        /**
         * 创建单个字段错误的验证结果
         */
        fun failure(fieldKey: String, errorMessage: String): ConfigValidationResult = 
            Invalid(fieldKey, errorMessage)
        
        /**
         * 从字段验证结果列表创建验证结果
         */
        fun fromFieldValidations(fieldValidations: List<Pair<String, String?>>): ConfigValidationResult {
            val errors = fieldValidations
                .filter { it.second != null }
                .associate { pair -> pair.first to pair.second!! }
            
            return if (errors.isEmpty()) {
                Valid
            } else {
                Invalid(errors)
            }
        }
        
        /**
         * 合并多个验证结果
         */
        fun merge(vararg results: ConfigValidationResult): ConfigValidationResult {
            val allErrors = mutableMapOf<String, String>()
            
            for (result in results) {
                if (result is Invalid) {
                    allErrors.putAll(result.errors)
                }
            }
            
            return if (allErrors.isEmpty()) {
                Valid
            } else {
                Invalid(allErrors)
            }
        }
    }
}

/**
 * 批量配置验证结果
 * 
 * 用于封装多个Provider配置的批量验证结果
 */
data class BatchConfigValidationResult(
    /**
     * 各个Provider的验证结果
     */
    val providerResults: Map<String, ConfigValidationResult>
) {
    /**
     * 是否所有Provider配置都验证通过
     */
    val isAllValid: Boolean
        get() = providerResults.values.all { it.isValid }
    
    /**
     * 是否有任何Provider配置验证失败
     */
    val hasAnyInvalid: Boolean
        get() = providerResults.values.any { it.isInvalid }
    
    /**
     * 获取所有验证失败的Provider类型
     */
    fun getInvalidProviders(): List<String> {
        return providerResults.filter { it.value.isInvalid }.keys.toList()
    }
    
    /**
     * 获取所有验证成功的Provider类型
     */
    fun getValidProviders(): List<String> {
        return providerResults.filter { it.value.isValid }.keys.toList()
    }
    
    /**
     * 获取特定Provider的验证结果
     */
    fun getProviderResult(providerType: String): ConfigValidationResult? {
        return providerResults[providerType]
    }
    
    /**
     * 获取所有错误信息的汇总
     */
    fun getAllErrors(): Map<String, List<String>> {
        return providerResults.mapValues { (_, result) ->
            when (result) {
                is ConfigValidationResult.Valid -> emptyList()
                is ConfigValidationResult.Invalid -> result.getAllErrors()
            }
        }.filterValues { it.isNotEmpty() }
    }
    
    companion object {
        /**
         * 创建空的批量验证结果
         */
        fun empty(): BatchConfigValidationResult = BatchConfigValidationResult(emptyMap())
        
        /**
         * 从单个验证结果创建批量结果
         */
        fun single(providerType: String, result: ConfigValidationResult): BatchConfigValidationResult {
            return BatchConfigValidationResult(mapOf(providerType to result))
        }
    }
} 