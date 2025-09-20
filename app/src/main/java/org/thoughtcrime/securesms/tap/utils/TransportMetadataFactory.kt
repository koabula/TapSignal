package org.thoughtcrime.securesms.tap.utils

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.CosTransportMetadata
import org.thoughtcrime.securesms.tap.EmailTransportMetadata

/**
 * 传输元数据工厂类
 * 
 * 负责根据Provider类型从Map数据创建相应的TransportMetadata实例
 */
object TransportMetadataFactory {
    
    private val TAG = Log.tag(TransportMetadataFactory::class.java)
    
    /**
     * 从Map数据创建TransportMetadata实例
     * 
     * @param providerType Provider类型标识
     * @param data Map格式的元数据
     * @return 创建的TransportMetadata实例，失败时返回null
     */
    fun createFromMap(providerType: String, data: Map<String, Any>): TransportMetadata? {
        return try {
            when (providerType.lowercase()) {
                "cos" -> {
                    CosTransportMetadata.fromMap(data)
                }
                "email" -> {
                    EmailTransportMetadata.fromMap(data)
                }
                else -> {
                    Log.w(TAG, "不支持的Provider类型: $providerType")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建TransportMetadata失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 从JSON字符串创建TransportMetadata实例
     * 
     * @param providerType Provider类型标识 
     * @param json JSON格式的元数据字符串
     * @return 创建的TransportMetadata实例，失败时返回null
     */
    fun createFromJson(providerType: String, json: String): TransportMetadata? {
        return try {
            val data = parseJsonToMap(json)
            createFromMap(providerType, data)
        } catch (e: Exception) {
            Log.e(TAG, "从JSON创建TransportMetadata失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 简单的JSON解析（避免依赖外部库）
     */
    private fun parseJsonToMap(json: String): Map<String, Any> {
        // 这是一个简化的JSON解析实现
        // 在实际生产环境中应该使用更强大的JSON库
        val result = mutableMapOf<String, Any>()
        
        try {
            val trimmed = json.trim().removeSurrounding("{", "}")
            if (trimmed.isEmpty()) return result
            
            val pairs = splitJsonPairs(trimmed)
            
            for (pair in pairs) {
                val colonIndex = pair.indexOf(':')
                if (colonIndex > 0) {
                    val key = pair.substring(0, colonIndex).trim().removeSurrounding("\"")
                    val value = pair.substring(colonIndex + 1).trim()
                    
                    result[key] = parseJsonValue(value)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON解析失败: ${LogSanitizer.sanitizeGeneric(e.message ?: "")}")
        }
        
        return result
    }
    
    /**
     * 分割JSON键值对
     */
    private fun splitJsonPairs(content: String): List<String> {
        val pairs = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var braceLevel = 0
        
        for (char in content) {
            when {
                char == '"' && (current.isEmpty() || current.last() != '\\') -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                char == '{' && !inQuotes -> {
                    braceLevel++
                    current.append(char)
                }
                char == '}' && !inQuotes -> {
                    braceLevel--
                    current.append(char)
                }
                char == ',' && !inQuotes && braceLevel == 0 -> {
                    pairs.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        if (current.isNotEmpty()) {
            pairs.add(current.toString().trim())
        }
        
        return pairs
    }
    
    /**
     * 解析JSON值
     */
    private fun parseJsonValue(value: String): Any {
        val trimmed = value.trim()
        
        return when {
            trimmed == "null" -> ""
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                trimmed.removeSurrounding("\"")
            }
            trimmed.startsWith("{") && trimmed.endsWith("}") -> {
                parseJsonToMap(trimmed)
            }
            trimmed.matches(Regex("-?\\d+")) -> {
                trimmed.toLongOrNull() ?: trimmed
            }
            trimmed.matches(Regex("-?\\d+\\.\\d+")) -> {
                trimmed.toDoubleOrNull() ?: trimmed
            }
            else -> trimmed
        }
    }
} 