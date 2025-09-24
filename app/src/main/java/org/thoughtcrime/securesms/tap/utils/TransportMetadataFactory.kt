package org.thoughtcrime.securesms.tap.utils

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * 传输元数据工厂类
 * 
 * 负责根据Provider类型从Map数据创建相应的TransportMetadata实例
 * 支持可配置的Provider类型扩展
 */
object TransportMetadataFactory {
    
    private val TAG = Log.tag(TransportMetadataFactory::class.java)
    
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    
    /**
     * 支持的Provider类型及其对应的工厂方法
     */
    private val providerFactories = mutableMapOf<String, (Map<String, Any>) -> TransportMetadata?>(
        "cos" to { data -> CosTransportMetadata.fromMap(data) }
    )
    
    /**
     * 注册新的Provider类型
     * 
     * @param providerType Provider类型标识
     * @param factory 对应的工厂方法
     */
    fun registerProvider(providerType: String, factory: (Map<String, Any>) -> TransportMetadata?) {
        Log.i(TAG, "注册Provider类型: $providerType")
        providerFactories[providerType.lowercase()] = factory
    }
    
    /**
     * 获取所有支持的Provider类型
     * 
     * @return Provider类型列表
     */
    fun getSupportedProviders(): Set<String> {
        return providerFactories.keys
    }
    
    /**
     * 从Map数据创建TransportMetadata实例
     * 
     * @param providerType Provider类型标识
     * @param data Map格式的元数据
     * @return 创建的TransportMetadata实例，失败时返回null
     */
    fun createFromMap(providerType: String, data: Map<String, Any>): TransportMetadata? {
        return try {
            val normalizedType = providerType.lowercase()
            val factory = providerFactories[normalizedType]
            
            if (factory == null) {
                Log.w(TAG, "不支持的Provider类型: $providerType，支持的类型: ${providerFactories.keys}")
                return null
            }
            
            factory(data)
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
            if (json.isBlank()) {
                Log.w(TAG, "JSON字符串为空")
                return null
            }
            
            val data: Map<String, Any> = objectMapper.readValue(json)
            createFromMap(providerType, data)
        } catch (e: Exception) {
            Log.e(TAG, "从JSON创建TransportMetadata失败: $providerType - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 将Map转换为JSON字符串
     * 
     * @param data 要转换的Map数据
     * @return JSON字符串，失败时返回null
     */
    fun mapToJson(data: Map<String, Any>): String? {
        return try {
            objectMapper.writeValueAsString(data)
        } catch (e: Exception) {
            Log.e(TAG, "Map转JSON失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 验证JSON字符串格式
     * 
     * @param json 要验证的JSON字符串
     * @return true如果格式有效，false否则
     */
    fun isValidJson(json: String): Boolean {
        return try {
            objectMapper.readTree(json)
            true
        } catch (e: Exception) {
            false
        }
    }
} 