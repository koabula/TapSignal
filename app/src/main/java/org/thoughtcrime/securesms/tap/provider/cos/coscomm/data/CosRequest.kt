package org.thoughtcrime.securesms.tap.provider.cos.coscomm.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.*

/**
 * COS通信请求数据结构
 * 用户向联系人发送COS通信请求时使用
 */
data class CosRequest(
    @JsonProperty("requestId")
    val requestId: String,
    
    @JsonProperty("timestamp")
    val timestamp: Long,
    
    @JsonProperty("durationType")
    val durationType: CosDuration,
    
    @JsonProperty("accessInfo")
    val accessInfo: CosAccessInfo,
    
    @JsonProperty("message")
    val message: String? = null // 可选的请求说明
) {
    companion object {
        /**
         * 生成新的请求ID
         */
        fun generateRequestId(): String = UUID.randomUUID().toString()
        
        /**
         * 创建新的COS请求
         */
        fun create(
            durationType: CosDuration,
            accessInfo: CosAccessInfo,
            message: String? = null
        ): CosRequest {
            return CosRequest(
                requestId = generateRequestId(),
                timestamp = System.currentTimeMillis(),
                durationType = durationType,
                accessInfo = accessInfo,
                message = message
            )
        }
    }
}

/**
 * COS通信响应数据结构
 * 用户响应COS通信请求时使用
 */
data class CosResponse(
    @JsonProperty("requestId")
    val requestId: String, // 对应的请求ID
    
    @JsonProperty("accepted")
    val accepted: Boolean, // 是否接受请求
    
    @JsonProperty("timestamp")
    val timestamp: Long,
    
    @JsonProperty("accessInfo")
    val accessInfo: CosAccessInfo? = null, // 响应方的COS访问信息（如果接受）
    
    @JsonProperty("rejectionReason")
    val rejectionReason: String? = null, // 拒绝原因（如果拒绝）
    
    @JsonProperty("agreedDuration")
    val agreedDuration: CosDuration? = null // 同意的访问时长（可能与请求不同）
) {
    companion object {
        /**
         * 创建接受响应
         */
        fun createAccepted(
            requestId: String,
            accessInfo: CosAccessInfo,
            agreedDuration: CosDuration
        ): CosResponse {
            return CosResponse(
                requestId = requestId,
                accepted = true,
                timestamp = System.currentTimeMillis(),
                accessInfo = accessInfo,
                agreedDuration = agreedDuration
            )
        }
        
        /**
         * 创建拒绝响应
         */
        fun createRejected(
            requestId: String,
            rejectionReason: String
        ): CosResponse {
            return CosResponse(
                requestId = requestId,
                accepted = false,
                timestamp = System.currentTimeMillis(),
                rejectionReason = rejectionReason
            )
        }
    }
}

/**
 * COS撤销消息数据结构
 * 用于撤销已授权的COS访问权限
 */
data class CosRevocation(
    @JsonProperty("requestId")
    val requestId: String, // 原请求ID
    
    @JsonProperty("revocationTime")
    val revocationTime: Long,
    
    @JsonProperty("revocationReason")
    val revocationReason: String,
    
    @JsonProperty("type")
    val type: RevocationType
) {
    companion object {
        /**
         * 创建撤销消息
         */
        fun create(
            requestId: String,
            revocationReason: String,
            type: RevocationType
        ): CosRevocation {
            return CosRevocation(
                requestId = requestId,
                revocationTime = System.currentTimeMillis(),
                revocationReason = revocationReason,
                type = type
            )
        }
    }
}

/**
 * COS访问信息
 * 包含访问COS存储所需的所有凭证信息
 */
data class CosAccessInfo(
    @JsonProperty("provider")
    val provider: String, // "AWS" | "TENCENT" | "ALIYUN"
    
    @JsonProperty("region")
    val region: String, // COS区域
    
    @JsonProperty("bucketName")
    val bucketName: String, // 存储桶名称
    
    @JsonProperty("accessKeyId")
    val accessKeyId: String, // 临时访问密钥ID
    
    @JsonProperty("secretAccessKey")
    val secretAccessKey: String, // 临时访问密钥
    
    @JsonProperty("sessionToken")
    val sessionToken: String? = null, // 会话令牌（如果需要）
    
    @JsonProperty("expireTime")
    val expireTime: Long, // 凭证过期时间（毫秒时间戳）
    
    @JsonProperty("sharedDirectory")
    val sharedDirectory: String = "/outbox/" // 共享目录路径（固定为"/outbox/"）
) {
    /**
     * 检查凭证是否已过期
     */
    @JsonIgnore
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expireTime
    }

    /**
     * 获取剩余有效时间（毫秒）
     */
    @JsonIgnore
    fun getRemainingTime(): Long {
        return maxOf(0, expireTime - System.currentTimeMillis())
    }

    /**
     * 转换为CosAccessToken
     */
    @JsonIgnore
    fun toCosAccessToken(): org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken {
        return org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            expireTime = expireTime
        )
    }
}

/**
 * COS访问时长类型
 */
enum class CosDuration(val hours: Int) {
    @JsonProperty("ONE_HOUR")
    ONE_HOUR(1),
    
    @JsonProperty("ONE_DAY")
    ONE_DAY(24),
    
    @JsonProperty("ONE_WEEK")
    ONE_WEEK(24 * 7),
    
    @JsonProperty("ONE_MONTH")
    ONE_MONTH(24 * 30),
    
    @JsonProperty("PERMANENT")
    PERMANENT(-1); // 永久（直到撤销）
    
    /**
     * 获取过期时间戳
     */
    fun getExpireTime(startTime: Long = System.currentTimeMillis()): Long {
        return if (this == PERMANENT) {
            Long.MAX_VALUE
        } else {
            startTime + (hours * 60 * 60 * 1000L)
        }
    }
}

/**
 * 撤销类型
 */
enum class RevocationType {
    @JsonProperty("USER_INITIATED")
    USER_INITIATED, // 用户主动撤销
    
    @JsonProperty("TOKEN_EXPIRED")
    TOKEN_EXPIRED, // 令牌过期
    
    @JsonProperty("SECURITY_BREACH")
    SECURITY_BREACH, // 安全问题
    
    @JsonProperty("SYSTEM_ERROR")
    SYSTEM_ERROR // 系统错误
}

// 扩展方法：CosAccessInfo和CosAccessToken之间的转换
/**
 * 将CosAccessInfo转换为CosAccessToken
 */
fun CosAccessInfo.toCosAccessToken(): org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken {
    return org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken(
        accessKeyId = this.accessKeyId,
        secretAccessKey = this.secretAccessKey,
        sessionToken = this.sessionToken,
        expireTime = this.expireTime
    )
}

/**
 * 将CosAccessToken转换为CosAccessInfo
 */
fun org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken.toCosAccessInfo(
    provider: String,
    region: String,
    bucketName: String,
    sharedDirectory: String = "/outbox/"
): CosAccessInfo {
    return CosAccessInfo(
        provider = provider,
        region = region,
        bucketName = bucketName,
        accessKeyId = this.accessKeyId,
        secretAccessKey = this.secretAccessKey,
        sessionToken = this.sessionToken,
        expireTime = this.expireTime,
        sharedDirectory = sharedDirectory
    )
}

/**
 * COS断开连接消息数据结构
 * 用于断开已建立的COS v2模式连接
 */
data class CosDisconnection(
    @JsonProperty("requestId")
    val requestId: String, // 原请求ID

    @JsonProperty("disconnectionTime")
    val disconnectionTime: Long,

    @JsonProperty("disconnectionReason")
    val disconnectionReason: String,

    @JsonProperty("initiatedBy")
    val initiatedBy: String // 发起断开的用户ID
) {
    companion object {
        /**
         * 创建断开连接消息
         */
        fun create(
            requestId: String,
            disconnectionReason: String,
            initiatedBy: String
        ): CosDisconnection {
            return CosDisconnection(
                requestId = requestId,
                disconnectionTime = System.currentTimeMillis(),
                disconnectionReason = disconnectionReason,
                initiatedBy = initiatedBy
            )
        }
    }
}
