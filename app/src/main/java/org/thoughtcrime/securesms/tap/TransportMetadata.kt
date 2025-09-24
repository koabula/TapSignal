package org.thoughtcrime.securesms.tap

import org.json.JSONObject
import org.json.JSONException
import android.util.Log

/**
 * 传输元数据抽象接口
 * 
 * 定义传输层元数据的基本契约，不包含任何具体provider的实现细节。
 * 具体的provider实现应该在各自的包中提供具体的元数据结构。
 */
interface TransportMetadata {
    /** 接收者ID */
    val recipientId: String
    
    /** 传输提供者类型 */
    val providerType: String
    
    /**
     * 获取发送元数据
     */
    fun getSendMetadata(): SendMetadata
    
    /**
     * 获取接收元数据  
     */
    fun getReceiveMetadata(): ReceiveMetadata
    
    /**
     * 将元数据转换为Map格式，用于序列化存储
     */
    fun toMap(): Map<String, Any>
    
    /**
     * 将元数据转换为JSON字符串，用于数据库存储
     */
    fun toJson(): String {
        return try {
            val jsonObject = JSONObject(toMap())
            jsonObject.toString()
        } catch (e: JSONException) {
            Log.e("TransportMetadata", "JSON序列化失败", e)
            "{}"
        }
    }
    
    /**
     * 验证元数据的基本有效性
     */
    fun validate(): Boolean
}

/**
 * 发送元数据
 * 
 * 包含发送消息所需的基本信息，不包含provider特有的验证逻辑。
 */
data class SendMetadata(
    /** 目标服务地址 */
    val address: String,
    
    /** 访问凭证 */
    val token: TransportToken?,
    
    /** 传输路径（由具体provider定义格式） */
    val path: String,
    
    /** 接收者ID */
    val recipientId: String
) {
    /**
     * 基础验证，不包含provider特有的路径格式检查
     */
    fun validate(): Boolean {
        return address.isNotBlank() && 
               path.isNotBlank() && 
               recipientId.isNotBlank()
    }
}

/**
 * 接收元数据
 * 
 * 包含接收消息所需的基本信息，不包含provider特有的验证逻辑。
 */
data class ReceiveMetadata(
    /** 源服务地址 */
    val address: String,
    
    /** 访问凭证 */
    val token: TransportToken?,
    
    /** 传输路径（由具体provider定义格式） */
    val path: String,
    
    /** 本端ID */
    val myId: String
) {
    /**
     * 基础验证，不包含provider特有的路径格式检查
     */
    fun validate(): Boolean {
        return address.isNotBlank() && 
               path.isNotBlank() && 
               myId.isNotBlank()
    }
}

/**
 * 通用传输元数据实现
 * 
 * 提供基础的元数据结构，具体Provider可以继承或组合使用。
 */
data class GenericTransportMetadata(
    override val recipientId: String,
    override val providerType: String,
    private val sendMetadata: SendMetadata,
    private val receiveMetadata: ReceiveMetadata,
    private val additionalData: Map<String, Any> = emptyMap()
) : TransportMetadata {
    
    override fun getSendMetadata(): SendMetadata = sendMetadata
    
    override fun getReceiveMetadata(): ReceiveMetadata = receiveMetadata
    
    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "recipientId" to recipientId,
            "providerType" to providerType,
            "sendMetadata" to mapOf(
                "address" to sendMetadata.address,
                "path" to sendMetadata.path,
                "recipientId" to sendMetadata.recipientId
            ),
            "receiveMetadata" to mapOf(
                "address" to receiveMetadata.address,
                "path" to receiveMetadata.path,
                "myId" to receiveMetadata.myId
            )
        )
        
        sendMetadata.token?.let { 
            (map["sendMetadata"] as MutableMap<String, Any>)["token"] = it.toMap() 
        }
        receiveMetadata.token?.let { 
            (map["receiveMetadata"] as MutableMap<String, Any>)["token"] = it.toMap() 
        }
        
        map.putAll(additionalData)
        return map
    }
    
    override fun validate(): Boolean {
        return recipientId.isNotBlank() &&
               providerType.isNotBlank() &&
               sendMetadata.validate() &&
               receiveMetadata.validate()
    }
} 