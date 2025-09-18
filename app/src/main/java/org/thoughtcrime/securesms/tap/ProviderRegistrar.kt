package org.thoughtcrime.securesms.tap

import android.content.Context

/**
 * Provider注册接口
 * 
 * 每个TransportProvider都需要实现对应的注册器，用于：
 * 1. 提供Provider的配置描述器
 * 2. 创建Provider实例
 * 3. 注册到传输管理器
 * 4. 处理Provider的生命周期
 */
interface ProviderRegistrar {
    
    /**
     * Provider类型标识符
     */
    val providerType: String
    
    /**
     * 获取Provider配置描述器
     * 
     * @return 配置描述器实例
     */
    fun getConfigDescriptor(): ProviderConfigDescriptor
    
    /**
     * 创建Provider实例
     * 
     * @param config 配置数据
     * @param context Android上下文
     * @return Provider实例，如果配置无效则返回null
     */
    fun createProvider(config: Map<String, Any>, context: Context): TransportProvider?
    
    /**
     * 验证Provider是否可以在当前环境中运行
     * 
     * @param context Android上下文
     * @return 验证结果
     */
    fun validateEnvironment(context: Context): ProviderEnvironmentValidation {
        return ProviderEnvironmentValidation.Valid
    }
    
    /**
     * 获取Provider依赖信息
     * 
     * @return 依赖信息列表
     */
    fun getDependencies(): List<ProviderDependency> = emptyList()
    
    /**
     * 获取Provider支持的特性
     * 
     * @return 特性集合
     */
    fun getSupportedFeatures(): Set<ProviderFeature> = emptySet()
    
    /**
     * Provider初始化回调
     * 
     * 在Provider被注册到管理器时调用
     * 
     * @param context Android上下文
     */
    fun onProviderRegistered(context: Context) {
        // 默认空实现
    }
    
    /**
     * Provider销毁回调
     * 
     * 在Provider从管理器中移除时调用
     * 
     * @param context Android上下文
     */
    fun onProviderUnregistered(context: Context) {
        // 默认空实现
    }
    
    /**
     * 获取Provider元数据
     * 
     * @return Provider元数据
     */
    fun getProviderMetadata(): ProviderMetadata {
        val descriptor = getConfigDescriptor()
        return ProviderMetadata(
            providerType = providerType,
            displayName = descriptor.displayName,
            description = descriptor.description,
            version = descriptor.getVersion(),
            author = descriptor.getAuthor(),
            supportedFeatures = getSupportedFeatures(),
            dependencies = getDependencies()
        )
    }
}

/**
 * Provider环境验证结果
 */
sealed class ProviderEnvironmentValidation {
    /**
     * 环境验证通过
     */
    object Valid : ProviderEnvironmentValidation()
    
    /**
     * 环境验证失败
     * 
     * @param reason 失败原因
     * @param requirements 未满足的要求
     */
    data class Invalid(
        val reason: String,
        val requirements: List<String> = emptyList()
    ) : ProviderEnvironmentValidation()
    
    /**
     * 环境验证警告
     * 
     * Provider可以运行但存在潜在问题
     * 
     * @param warning 警告信息
     * @param recommendations 建议措施
     */
    data class Warning(
        val warning: String,
        val recommendations: List<String> = emptyList()
    ) : ProviderEnvironmentValidation()
}

/**
 * Provider依赖信息
 */
data class ProviderDependency(
    /**
     * 依赖名称
     */
    val name: String,
    
    /**
     * 依赖版本要求
     */
    val version: String? = null,
    
    /**
     * 依赖类型
     */
    val type: DependencyType,
    
    /**
     * 是否为必需依赖
     */
    val required: Boolean = true,
    
    /**
     * 依赖描述
     */
    val description: String? = null
) {
    enum class DependencyType {
        LIBRARY,        // 第三方库
        PERMISSION,     // Android权限
        SERVICE,        // 系统服务
        NETWORK,        // 网络连接
        HARDWARE        // 硬件要求
    }
}

/**
 * Provider支持的特性
 */
enum class ProviderFeature {
    /**
     * 支持权限管理
     */
    AUTH_MANAGEMENT,
    
    /**
     * 支持群组传输
     */
    GROUP_TRANSPORT,
    
    /**
     * 支持配置测试
     */
    CONFIG_TEST,
    
    /**
     * 支持断点续传
     */
    RESUME_TRANSFER,
    
    /**
     * 支持大文件传输
     */
    LARGE_FILE_SUPPORT,
    
    /**
     * 支持压缩传输
     */
    COMPRESSION,
    
    /**
     * 支持加密传输
     */
    ENCRYPTION,
    
    /**
     * 支持离线传输
     */
    OFFLINE_SUPPORT,
    
    /**
     * 支持进度回调
     */
    PROGRESS_CALLBACK,
    
    /**
     * 支持自定义元数据
     */
    CUSTOM_METADATA
}

/**
 * Provider元数据
 */
data class ProviderMetadata(
    val providerType: String,
    val displayName: String,
    val description: String,
    val version: String,
    val author: String,
    val supportedFeatures: Set<ProviderFeature>,
    val dependencies: List<ProviderDependency>,
    val icon: String? = null,
    val website: String? = null,
    val supportEmail: String? = null,
    val licenseType: String? = null
) {
    /**
     * 检查是否支持特定功能
     */
    fun hasFeature(feature: ProviderFeature): Boolean {
        return supportedFeatures.contains(feature)
    }
    
    /**
     * 获取必需依赖列表
     */
    fun getRequiredDependencies(): List<ProviderDependency> {
        return dependencies.filter { it.required }
    }
    
    /**
     * 获取可选依赖列表
     */
    fun getOptionalDependencies(): List<ProviderDependency> {
        return dependencies.filter { !it.required }
    }
}

/**
 * Provider注册状态
 */
data class ProviderRegistrationStatus(
    val providerType: String,
    val isRegistered: Boolean,
    val registeredAt: Long? = null,
    val environmentValidation: ProviderEnvironmentValidation? = null,
    val lastError: String? = null
)

/**
 * Provider注册管理器
 * 
 * 用于管理所有Provider的注册状态和生命周期
 */
class ProviderRegistrationManager private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: ProviderRegistrationManager? = null
        
        fun getInstance(): ProviderRegistrationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProviderRegistrationManager().also { INSTANCE = it }
            }
        }
    }
    
    private val registrars = java.util.concurrent.ConcurrentHashMap<String, ProviderRegistrar>()
    private val registrationStatus = java.util.concurrent.ConcurrentHashMap<String, ProviderRegistrationStatus>()
    
    /**
     * 注册Provider
     * 
     * @param registrar Provider注册器
     * @param context Android上下文
     * @return 注册是否成功
     */
    fun registerProvider(registrar: ProviderRegistrar, context: Context): Boolean {
        return try {
            val providerType = registrar.providerType
            
            // 检查是否已注册
            if (registrars.containsKey(providerType)) {
                return false
            }
            
            // 验证环境
            val envValidation = registrar.validateEnvironment(context)
            if (envValidation is ProviderEnvironmentValidation.Invalid) {
                registrationStatus[providerType] = ProviderRegistrationStatus(
                    providerType = providerType,
                    isRegistered = false,
                    environmentValidation = envValidation,
                    lastError = envValidation.reason
                )
                return false
            }
            
            // 注册Provider
            registrars[providerType] = registrar
            registrationStatus[providerType] = ProviderRegistrationStatus(
                providerType = providerType,
                isRegistered = true,
                registeredAt = System.currentTimeMillis(),
                environmentValidation = envValidation
            )
            
            // 调用注册回调
            registrar.onProviderRegistered(context)
            
            true
        } catch (e: Exception) {
            registrationStatus[registrar.providerType] = ProviderRegistrationStatus(
                providerType = registrar.providerType,
                isRegistered = false,
                lastError = e.message
            )
            false
        }
    }
    
    /**
     * 注销Provider
     * 
     * @param providerType Provider类型
     * @param context Android上下文
     * @return 注销是否成功
     */
    fun unregisterProvider(providerType: String, context: Context): Boolean {
        return try {
            val registrar = registrars.remove(providerType)
            if (registrar != null) {
                registrar.onProviderUnregistered(context)
                registrationStatus.remove(providerType)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取Provider注册器
     * 
     * @param providerType Provider类型
     * @return 注册器实例，如果未注册则返回null
     */
    fun getRegistrar(providerType: String): ProviderRegistrar? {
        return registrars[providerType]
    }
    
    /**
     * 获取所有已注册的Provider类型
     * 
     * @return Provider类型列表
     */
    fun getRegisteredProviders(): List<String> {
        return registrars.keys.toList()
    }
    
    /**
     * 获取Provider注册状态
     * 
     * @param providerType Provider类型
     * @return 注册状态，如果Provider从未注册过则返回null
     */
    fun getRegistrationStatus(providerType: String): ProviderRegistrationStatus? {
        return registrationStatus[providerType]
    }
    
    /**
     * 获取所有Provider的注册状态
     * 
     * @return 注册状态映射
     */
    fun getAllRegistrationStatus(): Map<String, ProviderRegistrationStatus> {
        return registrationStatus.toMap()
    }
    
    /**
     * 获取Provider元数据
     * 
     * @param providerType Provider类型
     * @return Provider元数据，如果未注册则返回null
     */
    fun getProviderMetadata(providerType: String): ProviderMetadata? {
        return registrars[providerType]?.getProviderMetadata()
    }
    
    /**
     * 获取所有Provider的元数据
     * 
     * @return Provider元数据映射
     */
    fun getAllProviderMetadata(): Map<String, ProviderMetadata> {
        return registrars.mapValues { it.value.getProviderMetadata() }
    }
    
    /**
     * 检查Provider是否已注册
     * 
     * @param providerType Provider类型
     * @return 是否已注册
     */
    fun isProviderRegistered(providerType: String): Boolean {
        return registrars.containsKey(providerType)
    }
    
    /**
     * 清空所有注册的Provider
     * 
     * @param context Android上下文
     */
    fun clearAllProviders(context: Context) {
        val providerTypes = registrars.keys.toList()
        for (providerType in providerTypes) {
            unregisterProvider(providerType, context)
        }
    }
} 