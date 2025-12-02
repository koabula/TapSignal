package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosFileInfo
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import java.io.File

/**
 * COS Provider配置描述器
 * 
 * 定义COS Provider所需的配置字段，支持自动UI生成和配置验证。
 * 根据用户提醒：配置字段在代码中定义，不在provider.json中。
 */
class CosProviderConfigDescriptor : ProviderConfigDescriptor {

    companion object {
        private val TAG = Log.tag(CosProviderConfigDescriptor::class.java)
    }

    override val providerType: String = "cos"
    override val displayName: String = "云对象存储 (COS)"
    override val description: String = "支持AWS S3和腾讯云COS的云存储服务，提供永久凭证管理和群组消息传输功能"
    override val supportsConfigTest: Boolean = true

    override fun getConfigFields(): List<ConfigField> {
        return listOf(
            // 云服务提供商选择
            ConfigField(
                key = "provider",
                displayName = "云服务提供商",
                description = "选择您的云存储提供商",
                fieldType = ConfigFieldType.SELECT,
                isRequired = true,
                options = listOf(
                    ConfigOption("AWS", "Amazon S3", "亚马逊简单存储服务"),
                    ConfigOption("TENCENT", "腾讯云 COS", "腾讯云对象存储服务")
                ),
                defaultValue = "TENCENT",
                helpText = "不同提供商的API和区域设置有所不同"
            ),
            
            // 访问密钥ID
            ConfigField(
                key = "secretId",
                displayName = "访问密钥 ID",
                description = "您的云服务访问密钥标识符",
                fieldType = ConfigFieldType.TEXT,
                isRequired = true,
                validation = ConfigFieldValidation(
                    minLength = 10,
                    maxLength = 128,
                    pattern = "^[A-Za-z0-9]+$"
                ),
                placeholder = "AKID...",
                helpText = "请从您的云服务控制台获取访问密钥ID"
            ),
            
            // 访问密钥Secret
            ConfigField(
                key = "secretKey",
                displayName = "访问密钥",
                description = "您的云服务访问密钥",
                fieldType = ConfigFieldType.PASSWORD,
                isRequired = true,
                isSensitive = true,
                validation = ConfigFieldValidation(
                    minLength = 20,
                    maxLength = 128
                ),
                placeholder = "请输入访问密钥",
                helpText = "请妥善保管您的访问密钥，不要泄露给他人"
            ),
            
            // 区域输入
            ConfigField(
                key = "region",
                displayName = "存储区域",
                description = "存储桶所在的地域标识符",
                fieldType = ConfigFieldType.TEXT,
                isRequired = true,
                validation = ConfigFieldValidation(
                    minLength = 2,
                    maxLength = 50,
                    pattern = "^[a-z0-9-]+$"
                ),
                placeholder = "如: us-east-1, ap-beijing, eu-west-1",
                helpText = "请输入您的云服务商支持的区域标识符，如AWS的us-east-1或腾讯云的ap-beijing"
            ),
            
            // 存储桶名称
            ConfigField(
                key = "bucketName",
                displayName = "存储桶名称",
                description = "用于存储消息的存储桶名称",
                fieldType = ConfigFieldType.TEXT,
                isRequired = true,
                validation = ConfigFieldValidation(
                    minLength = 3,
                    maxLength = 63,
                    pattern = "^[a-z0-9][a-z0-9-]*[a-z0-9]$"
                ),
                placeholder = "my-signal-bucket",
                helpText = "存储桶名称只能包含小写字母、数字和连字符，且必须全局唯一"
            ),
            
            // 启用服务端加密 (可选)
            ConfigField(
                key = "enableEncryption",
                displayName = "启用服务端加密",
                description = "在云端启用额外的加密保护",
                fieldType = ConfigFieldType.CHECKBOX,
                isRequired = false,
                defaultValue = true,
                helpText = "推荐启用以增强安全性，不会影响性能"
            ),
            
            // 自动清理策略 (可选)
            ConfigField(
                key = "autoCleanupDays",
                displayName = "自动清理天数",
                description = "自动删除指定天数前的消息文件",
                fieldType = ConfigFieldType.NUMBER,
                isRequired = false,
                defaultValue = 30,
                validation = ConfigFieldValidation(
                    minValue = 1,
                    maxValue = 365
                ),
                helpText = "设置为0表示不自动清理。建议设置为7-30天"
            )
        )
    }

    override fun validateConfig(config: Map<String, Any>): ConfigValidationResult {
        val errors = mutableMapOf<String, String>()
        
        try {
            // 验证必需字段
            val requiredFields = getConfigFields().filter { it.isRequired }
            for (field in requiredFields) {
                val value = config[field.key]
                if (value == null || value.toString().isBlank()) {
                    errors[field.key] = "${field.displayName}不能为空"
                    continue
                }
                
                // 使用字段自己的验证方法
                val fieldError = field.validateValue(value)
                if (fieldError != null) {
                    errors[field.key] = fieldError
                }
            }
            
            // COS特定验证
            val provider = config["provider"]?.toString()
            val region = config["region"]?.toString()
            val bucketName = config["bucketName"]?.toString()
            
            // 验证provider是否支持
            if (provider != null) {
                when (provider.uppercase()) {
                    "AWS", "TENCENT" -> {
                        // 支持的提供商，无需额外验证
                    }
                    else -> {
                        errors["provider"] = "不支持的云服务提供商: $provider"
                    }
                }
            }
            
            // 验证region格式（基本格式验证，不限制具体区域）
            if (region != null && !region.matches(Regex("^[a-z0-9-]+$"))) {
                errors["region"] = "区域标识符格式不正确，只能包含小写字母、数字和连字符"
            }
            
            // 验证存储桶名称格式
            if (bucketName != null) {
                if (!bucketName.matches(Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$"))) {
                    errors["bucketName"] = "存储桶名称格式不正确，只能包含小写字母、数字和连字符"
                }
                
                if (bucketName.contains("--") || bucketName.startsWith("-") || bucketName.endsWith("-")) {
                    errors["bucketName"] = "存储桶名称不能包含连续的连字符，不能以连字符开始或结束"
                }
            }
            
            // 验证自动清理天数
            val autoCleanupDays = config["autoCleanupDays"]
            if (autoCleanupDays != null) {
                try {
                    val days = when (autoCleanupDays) {
                        is Number -> autoCleanupDays.toInt()
                        is String -> autoCleanupDays.toInt()
                        else -> throw NumberFormatException()
                    }
                    if (days < 0 || days > 365) {
                        errors["autoCleanupDays"] = "自动清理天数必须在0-365之间"
                    }
                } catch (e: NumberFormatException) {
                    errors["autoCleanupDays"] = "自动清理天数必须是有效的数字"
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "配置验证时发生异常", e)
            errors["general"] = "配置验证失败: ${e.message}"
        }
        
        return if (errors.isEmpty()) {
            ConfigValidationResult.Valid
        } else {
            ConfigValidationResult.Invalid(errors)
        }
    }

    override suspend fun testConfig(config: Map<String, Any>, context: android.content.Context): ConfigTestResult {
        return try {
            Log.i(TAG, "开始测试COS配置连接性")
            
            // 首先验证配置
            val validationResult = validateConfig(config)
            if (validationResult is ConfigValidationResult.Invalid) {
                return ConfigTestResult.Failed(
                    "配置验证失败",
                    validationResult.errors.values.joinToString("; ")
                )
            }
            
            // 创建COS配置（使用tap模块的CosConfig）
            val provider = when (config["provider"]?.toString()?.uppercase()) {
                "AWS" -> CosConfig.Provider.AWS
                "TENCENT" -> CosConfig.Provider.TENCENT
                else -> return ConfigTestResult.Failed("不支持的提供商")
            }
            
            // 对字符串值进行trim处理，清理用户输入时可能不小心添加的首尾空格
            val cosConfig = CosConfig(
                provider = provider,
                secretId = config["secretId"]?.toString()?.trim() ?: return ConfigTestResult.Failed("缺少访问密钥ID"),
                secretKey = config["secretKey"]?.toString()?.trim() ?: return ConfigTestResult.Failed("缺少访问密钥"),
                region = config["region"]?.toString()?.trim() ?: return ConfigTestResult.Failed("缺少区域"),
                bucketName = config["bucketName"]?.toString()?.trim() ?: return ConfigTestResult.Failed("缺少存储桶名称")
            )
            
            // 创建测试客户端（使用tap模块的CosClientFactory，传入有效的Context）
            val cosClient = try {
                CosClientFactory.createClient(cosConfig, context)
            } catch (e: Exception) {
                return ConfigTestResult.Failed("无法创建COS客户端", LogSanitizer.sanitizeGeneric(e.message ?: "未知错误"))
            }
            
            // 执行连接测试
            performConnectionTest(cosClient, cosConfig)
            
        } catch (e: Exception) {
            Log.e(TAG, "测试配置时发生异常: ${LogSanitizer.sanitizeThrowable(e)}")
            ConfigTestResult.Failed("配置测试失败", LogSanitizer.sanitizeGeneric(e.message ?: "未知错误"))
        }
    }
    
    /**
     * 执行实际的连接测试
     */
    private suspend fun performConnectionTest(cosClient: org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient, config: CosConfig): ConfigTestResult {
        return try {
            val testFileName = "tap_connection_test_${System.currentTimeMillis()}.txt"
            val testContent = "Transport-as-a-Plugin COS连接测试"
            val testPath = "/test/"
            
            // 创建临时测试文件
            val testFile = File.createTempFile("tap_test", ".txt")
            
            try {
                testFile.writeText(testContent)
                
                // 1. 测试创建目录
                val createDirSuccess = cosClient.createDirectory(testPath)
                if (!createDirSuccess) {
                    return ConfigTestResult.Warning(
                        "目录创建测试未完全成功",
                        "可能是目录已存在或权限限制，但不影响基本功能"
                    )
                }
                
                // 2. 测试文件上传
                val uploadPath = "$testPath$testFileName"
                val uploadSuccess = cosClient.uploadFile(testFile, uploadPath)
                if (!uploadSuccess) {
                    return ConfigTestResult.Failed(
                        "文件上传失败",
                        "请检查存储桶权限和网络连接"
                    )
                }
                
                Log.i(TAG, "文件上传成功: ${LogSanitizer.sanitize(uploadPath, "path")}")
                
                // 3. 测试目录列举
                val files = cosClient.listFiles(testPath)
                val uploadedFile = files.find { it.name.contains(testFileName) }
                if (uploadedFile == null) {
                    return ConfigTestResult.Warning(
                        "文件列举测试异常",
                        "文件上传成功但无法在目录中找到，可能存在同步延迟"
                    )
                }
                
                Log.i(TAG, "文件列举成功，找到测试文件: ${LogSanitizer.sanitize(uploadedFile.name, "filename")}")
                
                // 4. 测试文件下载
                val downloadFile = File.createTempFile("tap_download", ".txt")
                try {
                    val downloadSuccess = cosClient.downloadFile(uploadPath, downloadFile)
                    if (!downloadSuccess) {
                        return ConfigTestResult.Warning(
                            "文件下载测试失败",
                            "上传成功但下载失败，请检查读取权限"
                        )
                    }
                    
                    // 5. 验证文件内容
                    val downloadedContent = downloadFile.readText()
                    if (downloadedContent != testContent) {
                        return ConfigTestResult.Warning(
                            "内容完整性检查失败",
                            "下载的内容与上传的内容不匹配"
                        )
                    }
                    
                    Log.i(TAG, "文件下载和内容验证成功")
                    
                } finally {
                    // 清理下载的临时文件
                    if (downloadFile.exists()) {
                        downloadFile.delete()
                    }
                }
                
                // 6. 清理远端测试文件
                val deleteSuccess = try {
                    cosClient.deleteFile(uploadPath)
                } catch (e: Exception) {
                    Log.w(TAG, "删除测试文件时出现异常（非致命）: ${LogSanitizer.sanitizeThrowable(e)}")
                    false
                }
                
                if (!deleteSuccess) {
                    Log.w(TAG, "无法删除远端测试文件: ${LogSanitizer.sanitize(uploadPath, "path")} - 请手动清理")
                }
                
                ConfigTestResult.Success(
                    "COS配置测试成功！存储服务连接正常，文件上传下载功能正常。" +
                    "提供商: ${config.provider.name}，区域: ${LogSanitizer.sanitize(config.region, "region")}，" +
                    "存储桶: ${LogSanitizer.sanitize(config.bucketName, "bucket")}"
                )
                
            } finally {
                // 清理本地临时文件
                if (testFile.exists()) {
                    testFile.delete()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "连接测试过程中发生异常: ${LogSanitizer.sanitizeThrowable(e)}")
            ConfigTestResult.Failed("连接测试失败", LogSanitizer.sanitizeGeneric(e.message ?: "未知错误"))
        }
    }

} 