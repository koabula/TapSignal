package org.thoughtcrime.securesms.tap.utils

/**
 * 日志脱敏工具类
 * 
 * 用于在日志输出前对敏感信息进行脱敏处理，包括密钥、Token、签名串、
 * 完整路径等敏感信息的掩码和采样。
 */
object LogSanitizer {
    
    /**
     * 敏感字段名列表（小写）
     */
    private val SENSITIVE_FIELD_NAMES = setOf(
        "secretkey", "secret_key", "secret-key",
        "accesskey", "access_key", "access-key", 
        "sessiontoken", "session_token", "session-token",
        "password", "passwd", "pwd",
        "token", "key", "secret", "credential", "auth",
        "signature", "sign", "authorization",
        "bucket", "endpoint", "url", "path",
        "accountid", "account_id", "account-id"
    )
    
    /**
     * 脱敏配置
     */
    private const val MASK_CHAR = '*'
    private const val PRESERVE_PREFIX_LENGTH = 4
    private const val PRESERVE_SUFFIX_LENGTH = 4
    private const val MIN_LENGTH_FOR_MASKING = 8
    
    /**
     * 对字符串进行脱敏处理
     * 
     * @param value 原始值
     * @param fieldName 字段名（用于判断是否为敏感字段）
     * @return 脱敏后的字符串
     */
    fun sanitize(value: String?, fieldName: String? = null): String? {
        if (value.isNullOrEmpty()) return value
        
        // 判断是否为敏感字段
        val isSensitive = fieldName?.let { name ->
            SENSITIVE_FIELD_NAMES.any { sensitiveName ->
                name.lowercase().contains(sensitiveName)
            }
        } ?: false
        
        return when {
            isSensitive -> maskSensitiveValue(value)
            isUrlOrPath(value) -> maskUrlOrPath(value)
            isPossibleToken(value) -> maskSensitiveValue(value)
            else -> value
        }
    }
    
    /**
     * 对Map中的敏感信息进行脱敏
     */
    fun sanitizeMap(map: Map<String, Any?>): Map<String, String> {
        return map.mapValues { (key, value) ->
            when (value) {
                null -> "null"
                is String -> sanitize(value, key) ?: "null"
                is Map<*, *> -> "[Map with ${value.size} entries]"
                is Collection<*> -> "[Collection with ${value.size} items]"
                else -> if (isSensitiveFieldName(key)) {
                    maskSensitiveValue(value.toString())
                } else {
                    value.toString()
                }
            }
        }
    }
    
    /**
     * 对异常信息进行脱敏
     */
    fun sanitizeThrowable(throwable: Throwable): String {
        val message = throwable.message ?: ""
        return sanitizeGeneric(message)
    }
    
    /**
     * 通用脱敏方法，处理任意字符串中可能的敏感信息
     */
    fun sanitizeGeneric(text: String): String {
        return text
            .replace(Regex("(?i)(secret[_-]?key|access[_-]?key|session[_-]?token)\\s*[=:]\\s*[\\w\\-+/=]{8,}")) { match ->
                val parts = match.value.split(Regex("\\s*[=:]\\s*"), 2)
                if (parts.size == 2) {
                    "${parts[0]}=${maskSensitiveValue(parts[1])}"
                } else {
                    match.value
                }
            }
            .replace(Regex("(?i)https?://[\\w.-]+(?:/[^\\s]*)?")) { match ->
                maskUrlOrPath(match.value)
            }
            .replace(Regex("\\b[A-Za-z0-9+/]{40,}={0,2}\\b")) { match ->
                // 可能的Base64编码的Token
                if (match.value.length >= 40) {
                    maskSensitiveValue(match.value)
                } else {
                    match.value
                }
            }
    }
    
    /**
     * 掩码敏感值
     */
    private fun maskSensitiveValue(value: String): String {
        return when {
            value.length < MIN_LENGTH_FOR_MASKING -> value.map { MASK_CHAR }.joinToString("")
            value.length <= PRESERVE_PREFIX_LENGTH + PRESERVE_SUFFIX_LENGTH -> {
                MASK_CHAR.toString().repeat(value.length)
            }
            else -> {
                val prefix = value.take(PRESERVE_PREFIX_LENGTH)
                val suffix = value.takeLast(PRESERVE_SUFFIX_LENGTH)
                val maskLength = value.length - PRESERVE_PREFIX_LENGTH - PRESERVE_SUFFIX_LENGTH
                "$prefix${MASK_CHAR.toString().repeat(maskLength)}$suffix"
            }
        }
    }
    
    /**
     * 掩码URL或路径
     */
    private fun maskUrlOrPath(value: String): String {
        return try {
            when {
                value.startsWith("http://") || value.startsWith("https://") -> {
                    val parts = value.split("/")
                    if (parts.size >= 3) {
                        "${parts[0]}//${maskDomain(parts[2])}/***"
                    } else {
                        value
                    }
                }
                value.startsWith("/") -> {
                    val parts = value.split("/").filter { it.isNotEmpty() }
                    if (parts.size > 1) {
                        "/${parts.first()}/***"
                    } else {
                        value
                    }
                }
                else -> value
            }
        } catch (e: Exception) {
            "[MASKED_PATH]"
        }
    }
    
    /**
     * 掩码域名，保留顶级域名
     */
    private fun maskDomain(domain: String): String {
        val parts = domain.split(".")
        return when {
            parts.size <= 2 -> domain
            parts.size == 3 -> "${parts[0].take(2)}**.${parts[2]}"
            else -> "****.${parts.last()}"
        }
    }
    
    /**
     * 判断是否为敏感字段名
     */
    private fun isSensitiveFieldName(fieldName: String): Boolean {
        val lowerName = fieldName.lowercase()
        return SENSITIVE_FIELD_NAMES.any { lowerName.contains(it) }
    }
    
    /**
     * 判断是否为URL或路径
     */
    private fun isUrlOrPath(value: String): Boolean {
        return value.startsWith("http://") || 
               value.startsWith("https://") || 
               value.startsWith("/") ||
               value.contains("://")
    }
    
    /**
     * 判断是否可能是Token
     */
    private fun isPossibleToken(value: String): Boolean {
        return (value.length >= 20 && 
                value.matches(Regex("[A-Za-z0-9+/=_-]+")) &&
                !value.contains(" "))
    }
    
    /**
     * 创建脱敏后的日志消息
     */
    fun createLogMessage(template: String, vararg args: Any?): String {
        return try {
            val sanitizedArgs = args.map { arg ->
                when (arg) {
                    null -> "null"
                    is String -> sanitizeGeneric(arg)
                    is Map<*, *> -> sanitizeMap(arg as Map<String, Any?>).toString()
                    is Throwable -> sanitizeThrowable(arg)
                    else -> arg.toString()
                }
            }.toTypedArray()
            
            String.format(template, *sanitizedArgs)
        } catch (e: Exception) {
            // 如果格式化失败，至少确保参数被脱敏
            val sanitizedArgs = args.joinToString(", ") { arg ->
                when (arg) {
                    null -> "null" 
                    is String -> sanitizeGeneric(arg)
                    else -> arg.toString()
                }
            }
            "$template [$sanitizedArgs]"
        }
    }
} 