package org.thoughtcrime.securesms.tap.factory

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.provider.cos.CosTransportProvider
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 默认的传输提供者工厂实现
 * 
 * 负责根据配置创建不同类型的TransportProvider实例
 * 支持动态注册Provider类型和配置缓存
 */
class DefaultTransportProviderFactory(
    private val context: Context
) : TransportProviderFactory {
    
    companion object {
        private val TAG = Log.tag(DefaultTransportProviderFactory::class.java)
    }
    
    // Provider创建器映射
    private val providerCreators = ConcurrentHashMap<String, ProviderCreator>()
    private val creatorsLock = ReentrantReadWriteLock()
    
    // 配置缓存
    private val configCache = ConcurrentHashMap<String, Map<String, Any>>()
    private val validationCache = ConcurrentHashMap<String, ConfigValidationResult>()
    
    init {
        // 注册内置Provider类型
        registerBuiltinProviders()
    }
    
    override val supportedProviderTypes: Set<String>
        get() = creatorsLock.read { 
            providerCreators.keys.toSet() 
        }
    
    override fun createProvider(providerType: String, config: Map<String, Any>): TransportProvider? {
        return try {
            Log.d(TAG, "创建Provider实例: $providerType")
            
            val creator = creatorsLock.read { 
                providerCreators[providerType.lowercase()] 
            }
            
            if (creator == null) {
                Log.w(TAG, "不支持的Provider类型: $providerType")
                return null
            }
            
            // 验证配置
            val validationResult = validateConfig(providerType, config)
            if (validationResult is ConfigValidationResult.Invalid) {
                Log.w(TAG, "Provider配置验证失败: $providerType - ${validationResult.errors}")
                return null
            }
            
            // 创建Provider实例
            creator.create(context, config)
        } catch (e: Exception) {
            Log.e(TAG, "创建Provider失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    override fun validateConfig(providerType: String, config: Map<String, Any>): ConfigValidationResult {
        return try {
            val cacheKey = "${providerType}:${config.hashCode()}"
            
            // 尝试从缓存获取验证结果
            validationCache[cacheKey]?.let { cached ->
                return cached
            }
            
            val creator = creatorsLock.read { 
                providerCreators[providerType.lowercase()] 
            }
            
            val result = if (creator != null) {
                creator.validateConfig(config)
            } else {
                ConfigValidationResult.Invalid(
                    mapOf("providerType" to "不支持的Provider类型: $providerType")
                )
            }
            
            // 缓存验证结果
            validationCache[cacheKey] = result
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "验证配置失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            ConfigValidationResult.Invalid(
                mapOf("error" to "配置验证过程中发生错误: ${e.message}")
            )
        }
    }
    
    override fun getDefaultConfig(providerType: String): Map<String, Any> {
        return configCache.computeIfAbsent(providerType) { type ->
            creatorsLock.read { 
                providerCreators[type.lowercase()]?.getDefaultConfig() ?: emptyMap() 
            }
        }
    }
    
    /**
     * 注册Provider创建器
     * 
     * @param providerType Provider类型
     * @param creator Provider创建器
     */
    fun registerProviderCreator(providerType: String, creator: ProviderCreator) {
        creatorsLock.write {
            providerCreators[providerType.lowercase()] = creator
            // 清除相关缓存
            configCache.remove(providerType.lowercase())
            validationCache.entries.removeIf { it.key.startsWith("$providerType:") }
            
            Log.d(TAG, "注册Provider创建器: $providerType")
        }
    }
    
    /**
     * 清除配置缓存
     */
    fun clearCache() {
        configCache.clear()
        validationCache.clear()
        Log.d(TAG, "清除工厂配置缓存")
    }
    
    /**
     * 注册内置Provider类型
     */
    private fun registerBuiltinProviders() {
        // 注册COS Provider
        registerProviderCreator("cos", object : ProviderCreator {
            override fun create(context: Context, config: Map<String, Any>): TransportProvider? {
                return try {
                    CosTransportProvider(context, config)
                } catch (e: Exception) {
                    Log.e(TAG, "创建COS Provider失败: ${LogSanitizer.sanitizeThrowable(e)}")
                    null
                }
            }
            
            override fun validateConfig(config: Map<String, Any>): ConfigValidationResult {
                return validateCosConfig(config)
            }
            
            override fun getDefaultConfig(): Map<String, Any> {
                return getCosDefaultConfig()
            }
        })
        
        // 未来可以在这里注册更多Provider类型
        // registerProviderCreator("email", EmailProviderCreator())
        // registerProviderCreator("ipfs", IpfsProviderCreator())
        Log.i(TAG, "注册了 ${providerCreators.size} 个内置Provider类型")
    }
    
    /**
     * 创建COS Provider实例
     */
    private fun createCosProvider(config: Map<String, Any>): CosTransportProvider? {
        return try {
            // 验证配置
            val validationResult = validateCosConfig(config)
            if (validationResult is ConfigValidationResult.Invalid) {
                Log.w(TAG, "COS配置验证失败: ${validationResult.errors}")
                return null
            }
            
            // 创建COS Provider实例
            CosTransportProvider(context, config)
        } catch (e: Exception) {
            Log.e(TAG, "创建COS Provider失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 验证COS Provider配置
     */
    private fun validateCosConfig(config: Map<String, Any>): ConfigValidationResult {
        val errors = mutableMapOf<String, String>()
        
        // 检查必需字段
        val requiredFields = mapOf(
            "provider" to "云服务提供商",
            "secretId" to "访问密钥ID",
            "secretKey" to "访问密钥",
            "region" to "地域",
            "bucketName" to "存储桶名称"
        )
        
        for ((field, displayName) in requiredFields) {
            val value = config[field]?.toString()
            if (value.isNullOrBlank()) {
                errors[field] = "$displayName 不能为空"
            }
        }
        
        // 验证provider值
        val provider = config["provider"]?.toString()
        if (provider != null) {
            val validProviders = setOf("AWS", "TENCENT", "ALIYUN")
            if (!validProviders.contains(provider.uppercase())) {
                errors["provider"] = "无效的云服务提供商: $provider"
            }
        }
        
        // 验证存储桶名称格式
        val bucketName = config["bucketName"]?.toString()
        if (bucketName != null) {
            if (!bucketName.matches(Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"))) {
                errors["bucketName"] = "存储桶名称格式不正确，只能包含小写字母、数字和连字符"
            }
        }
        
        // 验证访问密钥长度
        val secretId = config["secretId"]?.toString()
        if (secretId != null && secretId.length < 10) {
            errors["secretId"] = "访问密钥ID长度不能少于10位"
        }
        
        val secretKey = config["secretKey"]?.toString()
        if (secretKey != null && secretKey.length < 20) {
            errors["secretKey"] = "访问密钥长度不能少于20位"
        }
        
        // 验证CAM持续时间
        val camDuration = config["camDuration"] as? Number
        if (camDuration != null) {
            val minutes = camDuration.toInt()
            if (minutes < 5 || minutes > 1440) {
                errors["camDuration"] = "临时凭证有效期必须在5-1440分钟之间"
            }
        }
        
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid
        } else {
            ConfigValidationResult.Invalid(errors)
        }
    }
    
    /**
     * 获取COS Provider的默认配置
     */
    private fun getCosDefaultConfig(): Map<String, Any> {
        return mapOf(
            "provider" to "TENCENT",
            "camDuration" to 15,
            "enableEncryption" to true,
            "maxRetries" to 3,
            "timeoutSeconds" to 30
        )
    }
    
    /**
     * 创建其他类型Provider的方法可以在这里添加
     */
    
    /*
    private fun createEmailProvider(config: Map<String, Any>): EmailTransportProvider? {
        return try {
            val validationResult = validateEmailConfig(config)
            if (validationResult is ConfigValidationResult.Invalid) {
                Log.w(TAG, "Email配置验证失败: ${validationResult.errors}")
                return null
            }
            
            EmailTransportProvider(config)
        } catch (e: Exception) {
            Log.e(TAG, "创建Email Provider失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    private fun validateEmailConfig(config: Map<String, Any>): ConfigValidationResult {
        val errors = mutableMapOf<String, String>()
        
        // Email Provider的配置验证逻辑
        val requiredFields = mapOf(
            "smtpServer" to "SMTP服务器",
            "smtpPort" to "SMTP端口",
            "imapServer" to "IMAP服务器", 
            "imapPort" to "IMAP端口",
            "username" to "邮箱用户名",
            "password" to "邮箱密码"
        )
        
        for ((field, displayName) in requiredFields) {
            val value = config[field]?.toString()
            if (value.isNullOrBlank()) {
                errors[field] = "$displayName 不能为空"
            }
        }
        
        // 验证邮箱格式
        val username = config["username"]?.toString()
        if (username != null && !username.contains("@")) {
            errors["username"] = "请输入有效的邮箱地址"
        }
        
        // 验证端口范围
        val smtpPort = config["smtpPort"] as? Number
        if (smtpPort != null) {
            val port = smtpPort.toInt()
            if (port < 1 || port > 65535) {
                errors["smtpPort"] = "SMTP端口必须在1-65535之间"
            }
        }
        
        val imapPort = config["imapPort"] as? Number
        if (imapPort != null) {
            val port = imapPort.toInt()
            if (port < 1 || port > 65535) {
                errors["imapPort"] = "IMAP端口必须在1-65535之间"
            }
        }
        
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid
        } else {
            ConfigValidationResult.Invalid(errors)
        }
    }
    */
} 

/**
 * Provider创建器接口
 * 
 * 定义了创建和配置Provider实例的方法
 */
interface ProviderCreator {
    /**
     * 创建Provider实例
     * 
     * @param context 应用上下文
     * @param config 配置参数
     * @return Provider实例，创建失败返回null
     */
    fun create(context: Context, config: Map<String, Any>): TransportProvider?
    
    /**
     * 验证Provider配置
     * 
     * @param config 配置参数
     * @return 验证结果
     */
    fun validateConfig(config: Map<String, Any>): ConfigValidationResult
    
    /**
     * 获取默认配置
     * 
     * @return 默认配置映射
     */
    fun getDefaultConfig(): Map<String, Any>
} 