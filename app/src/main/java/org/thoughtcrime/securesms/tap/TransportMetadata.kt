package org.thoughtcrime.securesms.tap

import android.util.Log

/**
 * 传输元数据接口
 * 
 * 定义了传输层需要的元数据信息，包括目标地址、Token、路径等。
 * 不同的TransportProvider可以通过实现这个接口来提供自己的元数据结构。
 */
interface TransportMetadata {
    /** 接收者ID */
    val recipientId: String
    
    /** 传输服务地址（COS bucket url, email, etc.） */
    val address: String
    
    /** 可选的访问Token */
    val token: TransportToken?
    
    /** 消息存储路径 */
    val path: String
    
    /** 传输提供者类型 */
    val providerType: String
    
    /**
     * 将元数据转换为Map格式，用于序列化存储
     */
    fun toMap(): Map<String, Any>
    
    /**
     * 将元数据转换为JSON字符串，用于数据库存储
     */
    fun toJson(): String {
        val map = toMap()
        return mapToJsonString(map)
    }
    
    /**
     * 从Map数据创建元数据实例，用于反序列化
     * 注意：这是一个接口方法，具体实现类需要提供静态工厂方法
     */
    fun validate(): Boolean
    
    /**
     * 简单的Map到JSON字符串转换
     */
    private fun mapToJsonString(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in map) {
            if (!first) sb.append(",")
            sb.append("\"").append(key).append("\":")
            when (value) {
                is String -> sb.append("\"").append(value.replace("\"", "\\\"")).append("\"")
                is Number -> sb.append(value.toString())
                is Boolean -> sb.append(value.toString())
                null -> sb.append("null")
                else -> sb.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"")
            }
            first = false
        }
        sb.append("}")
        return sb.toString()
    }
}

/**
 * COS传输元数据实现
 * 
 * 用于COS（云对象存储）服务的传输元数据，包含COS特有的region和bucketName信息。
 */
data class CosTransportMetadata(
    override val recipientId: String,
    override val address: String,        // COS bucket URL
    override val token: TransportToken?,
    override val path: String,
    override val providerType: String = "cos",
    val region: String,
    val bucketName: String
) : TransportMetadata {
    
    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "recipientId" to recipientId,
            "address" to address,
            "path" to path,
            "providerType" to providerType,
            "region" to region,
            "bucketName" to bucketName
        )
        
        token?.let { map["token"] = it.toMap() }
        
        return map
    }
    
    override fun validate(): Boolean {
        return recipientId.isNotBlank() &&
               address.isNotBlank() &&
               path.isNotBlank() &&
               region.isNotBlank() &&
               bucketName.isNotBlank() &&
               providerType == "cos"
    }
    
    companion object {
        /**
         * 从Map数据创建CosTransportMetadata实例
         */
        fun fromMap(data: Map<String, Any>): CosTransportMetadata? {
            return try {
                // 安全的类型检查和转换
                val recipientId = data["recipientId"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val address = data["address"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val path = data["path"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val providerType = data["providerType"]?.let {
                    if (it is String && it.isNotBlank()) it else "cos"
                } ?: "cos"
                
                val region = data["region"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val bucketName = data["bucketName"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                // 验证Provider类型
                if (providerType != "cos") {
                    return null
                }
                
                // 处理Token数据
                val token: TransportToken? = data["token"]?.let { tokenData ->
                    if (tokenData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val tokenMap = tokenData as Map<String, Any>
                        CosTransportToken.fromMap(tokenMap)
                    } else null
                }
                
                CosTransportMetadata(
                    recipientId = recipientId,
                    address = address,
                    token = token,
                    path = path,
                    providerType = providerType,
                    region = region,
                    bucketName = bucketName
                )
            } catch (e: ClassCastException) {
                Log.e("CosTransportMetadata", "类型转换错误", e)
                null
            } catch (e: Exception) {
                Log.e("CosTransportMetadata", "反序列化失败", e)
                null
            }
        }
    }
}

/**
 * 群组传输元数据接口
 * 
 * 用于群组消息传输的元数据，支持一对多的传输场景。
 */
interface GroupTransportMetadata {
    /** 群组ID */
    val groupId: String
    
    /** 群组成员的传输元数据列表 */
    val memberMetadata: List<TransportMetadata>
    
    /** 群组传输配置 */
    val groupConfig: GroupTransportConfig
    
    /**
     * 获取指定成员的传输元数据
     */
    fun getMemberMetadata(recipientId: String): TransportMetadata?
    
    /**
     * 验证群组元数据的有效性
     */
    fun validate(): Boolean
}

/**
 * 群组传输配置
 * 
 * 定义群组传输的策略和参数。
 */
data class GroupTransportConfig(
    /** 是否启用并发传输 */
    val enableConcurrentTransport: Boolean = true,
    
    /** 最大并发传输数量 */
    val maxConcurrentTransports: Int = 5,
    
    /** 传输超时时间（毫秒） */
    val transportTimeoutMs: Long = 30000L,
    
    /** 失败重试次数 */
    val maxRetryCount: Int = 3
)

/**
 * 基础群组传输元数据实现
 */
data class BasicGroupTransportMetadata(
    override val groupId: String,
    override val memberMetadata: List<TransportMetadata>,
    override val groupConfig: GroupTransportConfig = GroupTransportConfig()
) : GroupTransportMetadata {
    
    override fun getMemberMetadata(recipientId: String): TransportMetadata? {
        return memberMetadata.find { it.recipientId == recipientId }
    }
    
    override fun validate(): Boolean {
        return groupId.isNotBlank() &&
               memberMetadata.isNotEmpty() &&
               memberMetadata.all { it.validate() }
    }
}

/**
 * Email传输元数据实现示例
 * 
 * 展示如何为其他传输服务实现TransportMetadata接口。
 */
data class EmailTransportMetadata(
    override val recipientId: String,
    override val address: String,        // Email地址
    override val token: TransportToken?, // 邮箱认证Token（可选）
    override val path: String,           // 邮件主题或文件夹路径
    override val providerType: String = "email",
    val smtpServer: String,
    val imapServer: String,
    val port: Int,
    val useSSL: Boolean = true
) : TransportMetadata {
    
    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "recipientId" to recipientId,
            "address" to address,
            "path" to path,
            "providerType" to providerType,
            "smtpServer" to smtpServer,
            "imapServer" to imapServer,
            "port" to port,
            "useSSL" to useSSL
        )
        
        token?.let { map["token"] = it.toMap() }
        
        return map
    }
    
    override fun validate(): Boolean {
        return recipientId.isNotBlank() &&
               address.contains("@") &&
               path.isNotBlank() &&
               smtpServer.isNotBlank() &&
               imapServer.isNotBlank() &&
               port in 1..65535 &&
               providerType == "email"
    }
    
    companion object {
        fun fromMap(data: Map<String, Any>): EmailTransportMetadata? {
            return try {
                // 安全的类型检查和转换
                val recipientId = data["recipientId"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val address = data["address"]?.let { 
                    if (it is String && it.contains("@")) it else return null 
                } ?: return null
                
                val path = data["path"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val providerType = data["providerType"]?.let {
                    if (it is String && it.isNotBlank()) it else "email"
                } ?: "email"
                
                val smtpServer = data["smtpServer"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val imapServer = data["imapServer"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val port = data["port"]?.let { portValue ->
                    when (portValue) {
                        is Number -> {
                            val intPort = portValue.toInt()
                            if (intPort in 1..65535) intPort else return null
                        }
                        is String -> {
                            try {
                                val intPort = portValue.toInt()
                                if (intPort in 1..65535) intPort else return null
                            } catch (e: NumberFormatException) {
                                return null
                            }
                        }
                        else -> return null
                    }
                } ?: return null
                
                val useSSL = data["useSSL"]?.let {
                    when (it) {
                        is Boolean -> it
                        is String -> it.toBoolean()
                        else -> true
                    }
                } ?: true
                
                // 验证Provider类型
                if (providerType != "email") {
                    return null
                }
                
                // 处理Token数据
                val token: TransportToken? = data["token"]?.let { tokenData ->
                    if (tokenData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val tokenMap = tokenData as Map<String, Any>
                        EmailTransportToken.fromMap(tokenMap)
                    } else null
                }
                
                EmailTransportMetadata(
                    recipientId = recipientId,
                    address = address,
                    token = token,
                    path = path,
                    providerType = providerType,
                    smtpServer = smtpServer,
                    imapServer = imapServer,
                    port = port,
                    useSSL = useSSL
                )
            } catch (e: ClassCastException) {
                Log.e("EmailTransportMetadata", "类型转换错误", e)
                null
            } catch (e: Exception) {
                Log.e("EmailTransportMetadata", "反序列化失败", e)
                null
            }
        }
    }
} 