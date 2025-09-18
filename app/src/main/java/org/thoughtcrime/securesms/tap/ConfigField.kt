package org.thoughtcrime.securesms.tap

/**
 * 配置字段描述
 * 
 * 描述Provider配置中的单个字段，包含字段类型、验证规则、UI提示等信息
 */
data class ConfigField(
    /**
     * 配置键名，用于存储和检索配置值
     */
    val key: String,
    
    /**
     * 字段显示名称，用于UI标签
     */
    val displayName: String,
    
    /**
     * 字段描述信息，用于帮助用户理解该字段的作用
     */
    val description: String,
    
    /**
     * 字段类型，决定UI渲染方式和数据验证
     */
    val fieldType: ConfigFieldType,
    
    /**
     * 是否为必需字段
     */
    val isRequired: Boolean = true,
    
    /**
     * 默认值
     */
    val defaultValue: Any? = null,
    
    /**
     * 验证规则
     */
    val validation: ConfigFieldValidation? = null,
    
    /**
     * 下拉选项列表（适用于SELECT和MULTI_SELECT类型）
     */
    val options: List<ConfigOption>? = null,
    
    /**
     * 占位符文本，用于输入框提示
     */
    val placeholder: String? = null,
    
    /**
     * 帮助文本，提供额外的使用指导
     */
    val helpText: String? = null,
    
    /**
     * 字段是否敏感（如密码），用于确定UI显示方式
     */
    val isSensitive: Boolean = false
) {
    /**
     * 验证字段值
     * 
     * @param value 待验证的值
     * @return 验证错误信息，null表示验证通过
     */
    fun validateValue(value: Any?): String? {
        // 检查必需字段
        if (isRequired) {
            val isEmpty = when {
                value == null -> true
                value is String -> value.isBlank()
                value is Number -> false // 数字类型（包括0）都视为有效值
                value is Boolean -> false // 布尔类型（包括false）都视为有效值
                else -> value.toString().isBlank()
            }
            if (isEmpty) {
                return "${displayName}不能为空"
            }
        }
        
        // 如果值为空且非必需，跳过其他验证
        if (value == null) {
            return null
        }
        
        // 对于字符串类型，如果是空白字符串也跳过验证
        if (value is String && value.isBlank()) {
            return null
        }
        
        val stringValue = value.toString()
        
        // 应用验证规则
        validation?.let { validation ->
            // 长度验证
            validation.minLength?.let { minLength ->
                if (stringValue.length < minLength) {
                    return "${displayName}长度不能少于${minLength}个字符"
                }
            }
            
            validation.maxLength?.let { maxLength ->
                if (stringValue.length > maxLength) {
                    return "${displayName}长度不能超过${maxLength}个字符"
                }
            }
            
            // 正则表达式验证
            validation.pattern?.let { pattern ->
                if (!stringValue.matches(Regex(pattern))) {
                    return "${displayName}格式不正确"
                }
            }
            
            // 数值范围验证
            if (fieldType == ConfigFieldType.NUMBER) {
                try {
                    val numValue = stringValue.toDouble()
                    validation.minValue?.let { minValue ->
                        if (numValue < minValue.toDouble()) {
                            return "${displayName}不能小于${minValue}"
                        }
                    }
                    validation.maxValue?.let { maxValue ->
                        if (numValue > maxValue.toDouble()) {
                            return "${displayName}不能大于${maxValue}"
                        }
                    }
                } catch (e: NumberFormatException) {
                    return "${displayName}必须是有效的数字"
                }
            }
            
            // 自定义验证器
            validation.customValidator?.let { validator ->
                if (!validator(value)) {
                    return "${displayName}验证失败"
                }
            }
        }
        
        // 字段类型特定验证
        return when (fieldType) {
            ConfigFieldType.EMAIL -> validateEmail(stringValue)
            ConfigFieldType.URL -> validateUrl(stringValue)
            else -> null
        }
    }
    
    private fun validateEmail(email: String): String? {
        val emailPattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        return if (!email.matches(Regex(emailPattern))) {
            "${displayName}必须是有效的邮箱地址"
        } else null
    }
    
    private fun validateUrl(url: String): String? {
        return try {
            java.net.URL(url)
            null
        } catch (e: Exception) {
            "${displayName}必须是有效的URL地址"
        }
    }
}

/**
 * 配置字段类型枚举
 * 
 * 决定字段在UI中的渲染方式和数据处理方式
 */
enum class ConfigFieldType {
    /**
     * 文本输入框
     */
    TEXT,
    
    /**
     * 密码输入框（隐藏输入内容）
     */
    PASSWORD,
    
    /**
     * 数字输入框
     */
    NUMBER,
    
    /**
     * 邮箱输入框（带邮箱格式验证）
     */
    EMAIL,
    
    /**
     * URL输入框（带URL格式验证）
     */
    URL,
    
    /**
     * 下拉选择框（单选）
     */
    SELECT,
    
    /**
     * 多选框
     */
    MULTI_SELECT,
    
    /**
     * 复选框（布尔值）
     */
    CHECKBOX,
    
    /**
     * 多行文本框
     */
    TEXTAREA,
    
    /**
     * 文件路径选择
     */
    FILE_PATH,
    
    /**
     * 区域选择（特殊类型，用于云服务区域选择）
     */
    REGION_SELECT,
    
    /**
     * 隐藏字段（不在UI中显示，但参与配置存储）
     */
    HIDDEN
}

/**
 * 配置字段验证规则
 */
data class ConfigFieldValidation(
    /**
     * 最小长度
     */
    val minLength: Int? = null,
    
    /**
     * 最大长度
     */
    val maxLength: Int? = null,
    
    /**
     * 正则表达式模式
     */
    val pattern: String? = null,
    
    /**
     * 最小数值（适用于NUMBER类型）
     */
    val minValue: Number? = null,
    
    /**
     * 最大数值（适用于NUMBER类型）
     */
    val maxValue: Number? = null,
    
    /**
     * 自定义验证函数
     */
    val customValidator: ((Any) -> Boolean)? = null,
    
    /**
     * 验证错误提示信息
     */
    val errorMessage: String? = null
)

/**
 * 配置选项（用于下拉选择）
 */
data class ConfigOption(
    /**
     * 选项值（实际存储的值）
     */
    val value: String,
    
    /**
     * 显示文本（用户看到的文本）
     */
    val displayText: String,
    
    /**
     * 选项描述（可选）
     */
    val description: String? = null,
    
    /**
     * 是否为默认选中项
     */
    val isDefault: Boolean = false
)

/**
 * 配置字段分组
 * 
 * 用于在UI中将相关字段组织在一起
 */
data class ConfigFieldGroup(
    /**
     * 分组标识
     */
    val groupId: String,
    
    /**
     * 分组显示名称
     */
    val displayName: String,
    
    /**
     * 分组描述
     */
    val description: String? = null,
    
    /**
     * 分组中的字段
     */
    val fields: List<ConfigField>,
    
    /**
     * 是否可折叠
     */
    val collapsible: Boolean = false,
    
    /**
     * 是否默认展开
     */
    val expandedByDefault: Boolean = true
) 