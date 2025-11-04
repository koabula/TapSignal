package org.thoughtcrime.securesms.tap.notification.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON序列化工具
 * 
 * 确保Kotlin和Lambda函数中的JSON序列化顺序一致，以保证签名验证的准确性
 */
object JsonSerializer {
    
    /**
     * 将Map转换为JSON字符串
     * 
     * 保持字段插入顺序，确保与Lambda函数中的JSON.stringify()结果一致
     * 
     * @param map 要序列化的Map
     * @return JSON字符串
     */
    fun toSortedJson(map: Map<String, Any?>): String {
        val orderedMap = LinkedHashMap<String, Any?>()
        
        // 保持原有顺序
        map.keys.forEach { key ->
            orderedMap[key] = convertValue(map[key])
        }
        
        return JSONObject(orderedMap).toString()
    }
    
    /**
     * 将对象转换为JSON兼容的值
     * 
     * 递归处理嵌套的Map和List，保持插入顺序（与JavaScript JSON.stringify一致）
     */
    private fun convertValue(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val orderedMap = LinkedHashMap<String, Any?>()
                // 保持原始插入顺序，与JavaScript的JSON.stringify()行为一致
                // 不进行排序，因为JavaScript不会对对象属性排序
                value.keys
                    .filterIsInstance<String>()
                    .forEach { key ->
                        orderedMap[key] = convertValue(value[key])
                    }
                JSONObject(orderedMap)
            }
            is List<*> -> {
                val array = JSONArray()
                value.forEach { item ->
                    array.put(convertValue(item))
                }
                array
            }
            is String, is Number, is Boolean -> value
            else -> value.toString()
        }
    }
    
    /**
     * 构建用于签名的JSON字符串
     * 
     * 特别处理Webhook请求的签名体，确保与Lambda函数中的序列化逻辑一致
     * Lambda函数顺序: {version, notification}
     * 
     * @param version 版本号
     * @param notification notification对象（Map形式）
     * @return 用于签名的JSON字符串
     */
    fun buildSignatureBody(version: String, notification: Map<String, Any?>): String {
        // 保持与Lambda函数一致的顺序: version, notification
        val body = linkedMapOf(
            "version" to version,
            "notification" to convertValue(notification)
        )
        
        return JSONObject(body).toString()
    }
    
    /**
     * 将NotificationMessage转换为有序Map
     * 
     * 保持与Lambda函数一致的字段顺序: type, senderId, timestamp, metadata
     * 
     * @param type 消息类型
     * @param senderId 发送者ID
     * @param timestamp 时间戳
     * @param metadata 元数据
     * @return 有序Map
     */
    fun buildNotificationMap(
        type: String,
        senderId: String,
        timestamp: Long,
        metadata: Map<String, Any> = emptyMap()
    ): Map<String, Any> {
        // 保持与Lambda函数一致的顺序
        val map = linkedMapOf<String, Any>(
            "type" to type,
            "senderId" to senderId,
            "timestamp" to timestamp
        )
        
        if (metadata.isNotEmpty()) {
            map["metadata"] = metadata
        }
        
        return map
    }
}

