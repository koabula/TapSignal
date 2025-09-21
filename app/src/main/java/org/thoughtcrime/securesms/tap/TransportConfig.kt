package org.thoughtcrime.securesms.tap

/**
 * 传输配置
 */
data class TransportConfig(
    /** 启用的Provider列表 */
    val enabledProviders: Set<String> = emptySet(),
    
    /** 路由策略 */
    val routingPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT,
    
    /** 通道配置 */
    val channelConfig: TransportChannelConfig = TransportChannelConfig(),
    
    /** Token配置 */
    val tokenConfig: TransportTokenConfig = TransportTokenConfig(),
    
    /** 超时设置（毫秒） */
    val timeoutMs: Long = 30000L,
    
    /** 重试次数 */
    val maxRetries: Int = 3,
    
    /** 是否启用调试模式 */
    val debugEnabled: Boolean = false
) {
    
    /**
     * 检查Provider是否已启用
     */
    fun isProviderEnabled(providerType: String): Boolean {
        return enabledProviders.contains(providerType)
    }
    
    /**
     * 验证配置有效性
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        if (timeoutMs <= 0) {
            errors.add("超时时间必须大于0")
        }
        
        if (maxRetries < 0) {
            errors.add("重试次数不能为负数")
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors.associateWith { it })
        }
    }
}

/**
 * 传输通道配置
 */
data class TransportChannelConfig(
    /** 最大通道数量 */
    val maxChannels: Int = 1000,
    
    /** 通道超时时间（毫秒） */
    val channelTimeoutMs: Long = 300000L, // 5分钟
    
    /** 心跳间隔（毫秒） */
    val heartbeatIntervalMs: Long = 60000L, // 1分钟
    
    /** 自动清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 3600000L, // 1小时
    
    /** 最大失败次数 */
    val maxFailureCount: Int = 10,
    
    /** 通道优先级范围 */
    val priorityRange: IntRange = 1..10
)

/**
 * 传输Token配置
 */
data class TransportTokenConfig(
    /** 最大Token数量 */
    val maxTokens: Int = 10000,
    
    /** Token清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 3600000L, // 1小时
    
    /** 自动刷新Token */
    val autoRefresh: Boolean = true,
    
    /** Token有效期（毫秒） */
    val defaultValidityMs: Long = 86400000L // 24小时
)

/**
 * 传输路由策略
 */
enum class TransportRoutingPolicy(val displayName: String) {
    /** 传输优先 - 优先使用传输服务 */
    TRANSPORT_FIRST("传输优先"),
    
    /** Signal优先 - 优先使用Signal服务器 */
    SIGNAL_FIRST("Signal优先"),
    
    /** 智能路由 - 根据情况自动选择 */
    INTELLIGENT("智能路由"),
    
    /** 仅传输 - 只使用传输服务 */
    TRANSPORT_ONLY("仅传输"),
    
    /** 仅Signal - 只使用Signal服务器 */
    SIGNAL_ONLY("仅Signal")
}

/**
 * 配置验证结果
 */
sealed class TransportConfigValidationResult {
    abstract val isValid: Boolean
    
    object Valid : TransportConfigValidationResult() {
        override val isValid: Boolean = true
    }
    
    data class Invalid(val errors: Map<String, String>) : TransportConfigValidationResult() {
        override val isValid: Boolean = false
        
        /**
         * 将错误信息转换为可读字符串
         */
        fun joinToString(separator: String = ", "): String {
            return errors.values.joinToString(separator)
        }
    }
} 