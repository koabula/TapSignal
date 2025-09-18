package org.thoughtcrime.securesms.tap

/**
 * Provider配置描述接口
 * 
 * 每个TransportProvider都需要实现对应的配置描述器，用于：
 * 1. 描述Provider所需的配置字段
 * 2. 验证配置的完整性和有效性 
 * 3. 测试配置的连接性（可选）
 * 4. 提供UI生成所需的元数据
 */
interface ProviderConfigDescriptor {
    /**
     * Provider类型标识符
     */
    val providerType: String
    
    /**
     * Provider显示名称
     */
    val displayName: String
    
    /**
     * Provider描述信息
     */
    val description: String
    
    /**
     * 获取所需的配置项列表
     * 
     * @return 配置字段列表，用于UI生成和配置验证
     */
    fun getConfigFields(): List<ConfigField>
    
    /**
     * 验证配置是否完整和有效
     * 
     * @param config 用户填写的配置数据
     * @return 验证结果，包含错误信息（如有）
     */
    fun validateConfig(config: Map<String, Any>): ConfigValidationResult
    
    /**
     * 是否支持配置测试
     * 
     * 如果返回true，UI将显示测试按钮，允许用户测试配置连接性
     */
    val supportsConfigTest: Boolean
    
    /**
     * 测试配置连接性（可选实现）
     * 
     * 只有当supportsConfigTest返回true时才会被调用
     * 
     * @param config 用户填写的配置数据
     * @return 测试结果，包含成功/失败信息
     */
    suspend fun testConfig(config: Map<String, Any>): ConfigTestResult {
        return ConfigTestResult.Failed("此Provider不支持配置测试")
    }
    
    /**
     * 获取Provider版本信息
     */
    fun getVersion(): String = "1.0.0"
    
    /**
     * 获取Provider作者信息
     */
    fun getAuthor(): String = "Signal Tap Team"
    
    /**
     * 检查配置是否已设置所有必需字段
     * 
     * @param config 配置数据
     * @return 是否完整
     */
    fun isConfigComplete(config: Map<String, Any>): Boolean {
        val requiredFields = getConfigFields().filter { it.isRequired }
        return requiredFields.all { field ->
            val value = config[field.key]
            when {
                value == null -> false
                value is String -> value.isNotBlank()
                value is Number -> true // 数字类型（包括0）都视为有效值
                value is Boolean -> true // 布尔类型（包括false）都视为有效值
                else -> value.toString().isNotBlank() // 其他类型转换为字符串判断
            }
        }
    }
} 