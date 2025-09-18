package org.thoughtcrime.securesms.tap

/**
 * 传输配置数据结构
 * 
 * 定义传输层的全局配置选项，包括Provider启用状态、路由策略、轮询配置等。
 */
data class TransportConfig(
    /** 启用的传输提供者类型集合 */
    val enabledProviders: Set<String> = emptySet(),
    
    /** 默认传输提供者类型 */
    val defaultProvider: String? = null,
    
    /** 路由策略 */
    val routingPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT,
    
    /** 轮询配置 */
    val pollingConfig: TransportPollingConfig = TransportPollingConfig(),
    
    /** 通道管理配置 */
    val channelConfig: TransportChannelConfig = TransportChannelConfig(),
    
    /** Token管理配置 */
    val tokenConfig: TransportTokenConfig = TransportTokenConfig(),
    
    /** 重试配置 */
    val retryConfig: TransportRetryConfig = TransportRetryConfig(),
    
    /** 调试模式 */
    val debugMode: Boolean = false,
    
    /** 额外配置参数 */
    val extraConfig: Map<String, Any> = emptyMap()
) {
    
    /**
     * 检查指定Provider是否已启用
     */
    fun isProviderEnabled(providerType: String): Boolean {
        return enabledProviders.contains(providerType)
    }
    
    /**
     * 获取有效的默认Provider
     */
    fun getValidDefaultProvider(): String? {
        return defaultProvider?.takeIf { isProviderEnabled(it) }
    }
    
    /**
     * 复制配置并启用指定Provider
     */
    fun withEnabledProvider(providerType: String): TransportConfig {
        return copy(enabledProviders = enabledProviders + providerType)
    }
    
    /**
     * 复制配置并禁用指定Provider
     */
    fun withDisabledProvider(providerType: String): TransportConfig {
        return copy(enabledProviders = enabledProviders - providerType)
    }
    
    /**
     * 复制配置并设置默认Provider
     */
    fun withDefaultProvider(providerType: String): TransportConfig {
        return copy(
            defaultProvider = providerType,
            enabledProviders = if (enabledProviders.contains(providerType)) {
                enabledProviders
            } else {
                enabledProviders + providerType
            }
        )
    }
    
    /**
     * 验证配置的有效性
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        // 验证Provider类型名称格式
        enabledProviders.forEach { providerType ->
            if (providerType.isBlank()) {
                errors.add("Provider类型不能为空")
            } else if (!providerType.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]*$"))) {
                errors.add("Provider类型格式无效: '$providerType' (只能包含字母、数字、下划线和连字符，且必须以字母开头)")
            } else if (providerType.length > 50) {
                errors.add("Provider类型名称过长: '$providerType' (最大50字符)")
            }
        }
        
        // 检查默认Provider是否在启用列表中
        defaultProvider?.let { default ->
            if (default.isBlank()) {
                errors.add("默认传输提供者不能为空字符串")
            } else if (!enabledProviders.contains(default)) {
                errors.add("默认传输提供者 '$default' 未在启用列表中")
            }
        }
        
        // 验证启用Provider数量限制
        if (enabledProviders.size > 10) {
            errors.add("启用的Provider数量过多: ${enabledProviders.size} (最大支持10个)")
        }
        
        // 验证路由策略兼容性
        if (routingPolicy == TransportRoutingPolicy.TRANSPORT_ONLY && enabledProviders.isEmpty()) {
            errors.add("选择'仅传输服务'路由策略时必须启用至少一个Provider")
        }
        
        // 验证轮询配置
        val pollingValidation = pollingConfig.validate()
        if (!pollingValidation.isValid && pollingValidation is TransportConfigValidationResult.Invalid) {
            errors.addAll(pollingValidation.errors.map { "轮询配置: $it" })
        }
        
        // 验证通道配置
        val channelValidation = channelConfig.validate()
        if (!channelValidation.isValid && channelValidation is TransportConfigValidationResult.Invalid) {
            errors.addAll(channelValidation.errors.map { "通道配置: $it" })
        }
        
        // 验证Token配置
        val tokenValidation = tokenConfig.validate()
        if (!tokenValidation.isValid && tokenValidation is TransportConfigValidationResult.Invalid) {
            errors.addAll(tokenValidation.errors.map { "Token配置: $it" })
        }
        
        // 验证重试配置
        val retryValidation = retryConfig.validate()
        if (!retryValidation.isValid && retryValidation is TransportConfigValidationResult.Invalid) {
            errors.addAll(retryValidation.errors.map { "重试配置: $it" })
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors)
        }
    }
}

/**
 * 传输路由策略枚举
 */
enum class TransportRoutingPolicy(
    val displayName: String,
    val description: String
) {
    /** 优先使用传输服务 */
    TRANSPORT_FIRST(
        displayName = "传输优先",
        description = "优先使用第三方传输服务，失败时回退到Signal Server"
    ),
    
    /** 优先使用Signal Server */
    SIGNAL_FIRST(
        displayName = "Signal优先", 
        description = "优先使用Signal Server，特定情况下使用传输服务"
    ),
    
    /** 智能路由 */
    INTELLIGENT(
        displayName = "智能路由",
        description = "根据网络状况、消息类型、接收者状态等智能选择最佳路由"
    ),
    
    /** 仅使用传输服务 */
    TRANSPORT_ONLY(
        displayName = "仅传输服务",
        description = "仅使用第三方传输服务，不使用Signal Server"
    ),
    
    /** 仅使用Signal Server */
    SIGNAL_ONLY(
        displayName = "仅Signal",
        description = "仅使用Signal Server，不使用传输服务"
    );
    
    /**
     * 检查是否允许使用传输服务
     */
    fun allowsTransport(): Boolean = this != SIGNAL_ONLY
    
    /**
     * 检查是否允许使用Signal Server
     */
    fun allowsSignal(): Boolean = this != TRANSPORT_ONLY
}

/**
 * 传输轮询配置
 */
data class TransportPollingConfig(
    /** 是否启用轮询 */
    val enabled: Boolean = true,
    
    /** 基础轮询间隔（毫秒） */
    val baseInterval: Long = 5000L,
    
    /** 最大并发轮询数 */
    val maxConcurrent: Int = 8,
    
    /** 轮询超时时间（毫秒） */
    val timeoutMs: Long = 30000L,
    
    /** 智能轮询配置 */
    val intelligentPolling: IntelligentPollingConfig = IntelligentPollingConfig(),
    
    /** 批处理配置 */
    val batchConfig: PollingBatchConfig = PollingBatchConfig()
) {
    
    /**
     * 验证轮询配置
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        if (baseInterval < 1000L) {
            errors.add("轮询间隔不能小于1秒")
        }
        
        if (maxConcurrent < 1 || maxConcurrent > 50) {
            errors.add("最大并发数必须在1-50之间")
        }
        
        if (timeoutMs < 5000L || timeoutMs > 300000L) {
            errors.add("超时时间必须在5秒-5分钟之间")
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors)
        }
    }
}

/**
 * 智能轮询配置
 */
data class IntelligentPollingConfig(
    /** 启用智能轮询 */
    val enabled: Boolean = true,
    
    /** 活跃模式轮询间隔（毫秒） */
    val activeInterval: Long = 3000L,
    
    /** 非活跃模式轮询间隔（毫秒） */
    val inactiveInterval: Long = 30000L,
    
    /** 后台模式轮询间隔（毫秒） */
    val backgroundInterval: Long = 60000L,
    
    /** 活跃度判断阈值（分钟） */
    val activityThresholdMinutes: Long = 5L,
    
    /** 错误退避配置 */
    val errorBackoff: ErrorBackoffConfig = ErrorBackoffConfig()
)

/**
 * 错误退避配置
 */
data class ErrorBackoffConfig(
    /** 启用错误退避 */
    val enabled: Boolean = true,
    
    /** 基础退避时间（毫秒） */
    val baseBackoffMs: Long = 2000L,
    
    /** 最大退避时间（毫秒） */
    val maxBackoffMs: Long = 180000L,
    
    /** 退避倍数 */
    val backoffMultiplier: Double = 2.0,
    
    /** 最大重试次数 */
    val maxRetries: Int = 10
)

/**
 * 轮询批处理配置
 */
data class PollingBatchConfig(
    /** 启用批处理 */
    val enabled: Boolean = true,
    
    /** 批处理大小 */
    val batchSize: Int = 5,
    
    /** 批处理延迟（毫秒） */
    val batchDelayMs: Long = 1000L
)

/**
 * 传输通道配置
 */
data class TransportChannelConfig(
    /** 最大通道数 */
    val maxChannels: Int = 100,
    
    /** 通道超时时间（毫秒） */
    val channelTimeoutMs: Long = 300000L, // 5分钟
    
    /** 清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 60000L, // 1分钟
    
    /** 最大失败次数 */
    val maxFailureCount: Int = 10,
    
    /** 通道优先级范围 */
    val priorityRange: IntRange = 1..10
) {
    
    /**
     * 验证通道配置
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        if (maxChannels < 1 || maxChannels > 1000) {
            errors.add("最大通道数必须在1-1000之间")
        }
        
        if (channelTimeoutMs < 60000L) { // 不能小于1分钟
            errors.add("通道超时时间不能小于1分钟")
        }
        
        if (cleanupIntervalMs < 10000L) { // 不能小于10秒
            errors.add("清理间隔不能小于10秒")
        }
        
        if (maxFailureCount < 1 || maxFailureCount > 100) {
            errors.add("最大失败次数必须在1-100之间")
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors)
        }
    }
}

/**
 * 传输Token配置
 */
data class TransportTokenConfig(
    /** Token缓存大小 */
    val cacheSize: Int = 1000,
    
    /** Token清理间隔（毫秒） */
    val cleanupIntervalMs: Long = 300000L, // 5分钟
    
    /** Token即将过期阈值（毫秒） */
    val nearExpiryThresholdMs: Long = 300000L, // 5分钟
    
    /** 自动刷新Token */
    val autoRefresh: Boolean = true,
    
    /** 刷新提前时间（毫秒） */
    val refreshAdvanceMs: Long = 600000L // 10分钟
) {
    
    /**
     * 验证Token配置
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        if (cacheSize < 10 || cacheSize > 10000) {
            errors.add("Token缓存大小必须在10-10000之间")
        }
        
        if (cleanupIntervalMs < 60000L) { // 不能小于1分钟
            errors.add("Token清理间隔不能小于1分钟")
        }
        
        if (nearExpiryThresholdMs < 60000L) { // 不能小于1分钟
            errors.add("Token即将过期阈值不能小于1分钟")
        }
        
        if (refreshAdvanceMs < nearExpiryThresholdMs) {
            errors.add("刷新提前时间必须大于即将过期阈值")
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors)
        }
    }
}

/**
 * 传输重试配置
 */
data class TransportRetryConfig(
    /** 启用重试 */
    val enabled: Boolean = true,
    
    /** 最大重试次数 */
    val maxRetries: Int = 3,
    
    /** 基础重试间隔（毫秒） */
    val baseRetryInterval: Long = 2000L,
    
    /** 重试间隔倍数 */
    val retryMultiplier: Double = 2.0,
    
    /** 最大重试间隔（毫秒） */
    val maxRetryInterval: Long = 60000L, // 1分钟
    
    /** 可重试的错误类型 */
    val retryableErrors: Set<TransportError> = setOf(
        TransportError.NETWORK_ERROR,
        TransportError.PROVIDER_UNAVAILABLE,
        TransportError.TIMEOUT_ERROR
    )
) {
    
    /**
     * 验证重试配置
     */
    fun validate(): TransportConfigValidationResult {
        val errors = mutableListOf<String>()
        
        if (maxRetries < 0) {
            errors.add("最大重试次数不能为负数")
        } else if (maxRetries > 10) {
            errors.add("最大重试次数不能超过10次")
        }
        
        if (baseRetryInterval < 100L) {
            errors.add("基础重试间隔不能小于100毫秒")
        } else if (baseRetryInterval > 300000L) { // 5分钟
            errors.add("基础重试间隔不能超过5分钟")
        }
        
        if (retryMultiplier < 1.0) {
            errors.add("重试间隔倍数不能小于1.0")
        } else if (retryMultiplier > 10.0) {
            errors.add("重试间隔倍数不能超过10.0")
        }
        
        if (maxRetryInterval < baseRetryInterval) {
            errors.add("最大重试间隔不能小于基础重试间隔")
        } else if (maxRetryInterval > 3600000L) { // 1小时
            errors.add("最大重试间隔不能超过1小时")
        }
        
        if (retryableErrors.isEmpty()) {
            errors.add("至少需要设置一种可重试的错误类型")
        }
        
        return if (errors.isEmpty()) {
            TransportConfigValidationResult.Valid
        } else {
            TransportConfigValidationResult.Invalid(errors)
        }
    }
}

/**
 * 传输配置验证结果
 */
sealed class TransportConfigValidationResult {
    object Valid : TransportConfigValidationResult()
    data class Invalid(val errors: List<String>) : TransportConfigValidationResult()
    
    val isValid: Boolean get() = this is Valid
}

/**
 * 传输配置构建器
 */
class TransportConfigBuilder {
    private var enabledProviders: Set<String> = emptySet()
    private var defaultProvider: String? = null
    private var routingPolicy: TransportRoutingPolicy = TransportRoutingPolicy.INTELLIGENT
    private var pollingConfig: TransportPollingConfig = TransportPollingConfig()
    private var channelConfig: TransportChannelConfig = TransportChannelConfig()
    private var tokenConfig: TransportTokenConfig = TransportTokenConfig()
    private var retryConfig: TransportRetryConfig = TransportRetryConfig()
    private var debugMode: Boolean = false
    private var extraConfig: Map<String, Any> = emptyMap()
    
    fun enableProviders(vararg providers: String) = apply {
        enabledProviders = providers.toSet()
    }
    
    fun defaultProvider(provider: String) = apply {
        defaultProvider = provider
    }
    
    fun routingPolicy(policy: TransportRoutingPolicy) = apply {
        routingPolicy = policy
    }
    
    fun pollingConfig(config: TransportPollingConfig) = apply {
        pollingConfig = config
    }
    
    fun channelConfig(config: TransportChannelConfig) = apply {
        channelConfig = config
    }
    
    fun tokenConfig(config: TransportTokenConfig) = apply {
        tokenConfig = config
    }
    
    fun retryConfig(config: TransportRetryConfig) = apply {
        retryConfig = config
    }
    
    fun debugMode(enabled: Boolean) = apply {
        debugMode = enabled
    }
    
    fun extraConfig(config: Map<String, Any>) = apply {
        extraConfig = config
    }
    
    fun build(): TransportConfig {
        return TransportConfig(
            enabledProviders = enabledProviders,
            defaultProvider = defaultProvider,
            routingPolicy = routingPolicy,
            pollingConfig = pollingConfig,
            channelConfig = channelConfig,
            tokenConfig = tokenConfig,
            retryConfig = retryConfig,
            debugMode = debugMode,
            extraConfig = extraConfig
        )
    }
} 