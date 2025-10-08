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
    
    /** 轮询配置 */
    val pollingConfig: TapPollingConfig = TapPollingConfig(),
    
    /** Provider特定配置 */
    val providerConfigs: Map<String, ProviderSpecificConfig> = emptyMap(),
    
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
    val maxTokens: Int = 1000,
    
    /** 默认Token有效期（毫秒） */
    val defaultValidityMs: Long = 24 * 60 * 60 * 1000L, // 24小时
    
    /** 是否自动刷新即将过期的Token */
    val autoRefresh: Boolean = true,
    
    /** 清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 60 * 60 * 1000L, // 1小时
    
    /** Token刷新提前时间（毫秒） */
    val refreshAdvanceMs: Long = 60 * 60 * 1000L, // 1小时
    
    /** 是否启用Token统计 */
    val enableStatistics: Boolean = true
) {
    
    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        return maxTokens > 0 && 
               defaultValidityMs > 0 && 
               cleanupIntervalMs > 0 && 
               refreshAdvanceMs >= 0
    }
}

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

/**
 * Tap轮询配置
 */
data class TapPollingConfig(
    /** 核心线程池大小 */
    val corePoolSize: Int = 2,
    
    /** 最大线程池大小 */
    val maxPoolSize: Int = 8,
    
    /** 线程保活时间（秒） */
    val keepAliveTimeSeconds: Long = 60L,
    
    /** 活跃对话轮询间隔（毫秒） */
    val activePollingIntervalMs: Long = 5000L,
    
    /** 非活跃对话轮询间隔（毫秒） */
    val inactivePollingIntervalMs: Long = 30000L,
    
    /** 后台模式轮询间隔（毫秒） */
    val backgroundPollingIntervalMs: Long = 60000L,
    
    /** 暂停模式轮询间隔（毫秒） */
    val suspendedPollingIntervalMs: Long = 300000L,
    
    /** 轮询超时时间（毫秒） */
    val pollingTimeoutMs: Long = 30000L,
    
    /** 最大重试次数 */
    val maxRetryAttempts: Int = 3,
    
    /** 清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 300000L
) {
    
    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        return corePoolSize > 0 &&
               maxPoolSize >= corePoolSize &&
               keepAliveTimeSeconds > 0 &&
               activePollingIntervalMs > 0 &&
               inactivePollingIntervalMs > 0 &&
               backgroundPollingIntervalMs > 0 &&
               suspendedPollingIntervalMs > 0 &&
               pollingTimeoutMs > 0 &&
               maxRetryAttempts >= 0 &&
               cleanupIntervalMs > 0
    }
}

/**
 * Provider特定配置基类
 */
abstract class ProviderSpecificConfig

/**
 * COS Provider特定配置
 */
data class CosProviderConfig(
    /** 最大文件大小（字节） */
    val maxFileSize: Long = 100 * 1024 * 1024L, // 100MB
    
    /** 发件箱路径 */
    val outboxPath: String = "/outbox/",
    
    /** 群组路径前缀 */
    val groupPathPrefix: String = "/group/",
    
    /** 群组发件箱后缀（群组使用简化路径，不需要outbox层级） */
    val groupOutboxSuffix: String = "/",
    
    /** 连接超时时间（毫秒） */
    val connectionTimeoutMs: Long = 30000L,
    
    /** 读取超时时间（毫秒） */
    val readTimeoutMs: Long = 60000L,
    
    /** 写入超时时间（毫秒） */
    val writeTimeoutMs: Long = 60000L
) : ProviderSpecificConfig() {
    
    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        return maxFileSize > 0 &&
               outboxPath.isNotBlank() &&
               groupPathPrefix.isNotBlank() &&
               groupOutboxSuffix.isNotBlank() &&
               connectionTimeoutMs > 0 &&
               readTimeoutMs > 0 &&
               writeTimeoutMs > 0
    }
} 