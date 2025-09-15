package org.thoughtcrime.securesms.coscomm.data

import com.fasterxml.jackson.annotation.JsonProperty
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.util.*

/**
 * COS Signal消息扩展
 * 用于在Signal Server中传输COS相关的请求、响应和撤销消息
 */
sealed class CosSignalMessage {
    abstract val messageId: String
    abstract val timestamp: Long
    
    /**
     * COS请求Signal消息
     */
    data class Request(
        @JsonProperty("messageId")
        override val messageId: String,
        
        @JsonProperty("timestamp")
        override val timestamp: Long,
        
        @JsonProperty("cosRequest")
        val cosRequest: CosRequest
    ) : CosSignalMessage() {
        
        companion object {
            /**
             * 创建COS请求Signal消息
             */
            fun create(cosRequest: CosRequest): Request {
                return Request(
                    messageId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    cosRequest = cosRequest
                )
            }
        }
    }
    
    /**
     * COS响应Signal消息
     */
    data class Response(
        @JsonProperty("messageId")
        override val messageId: String,
        
        @JsonProperty("timestamp")
        override val timestamp: Long,
        
        @JsonProperty("cosResponse")
        val cosResponse: CosResponse
    ) : CosSignalMessage() {
        
        companion object {
            /**
             * 创建COS响应Signal消息
             */
            fun create(cosResponse: CosResponse): Response {
                return Response(
                    messageId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    cosResponse = cosResponse
                )
            }
        }
    }
    
    /**
     * COS撤销Signal消息
     */
    data class Revocation(
        @JsonProperty("messageId")
        override val messageId: String,

        @JsonProperty("timestamp")
        override val timestamp: Long,

        @JsonProperty("cosRevocation")
        val cosRevocation: CosRevocation
    ) : CosSignalMessage() {

        companion object {
            /**
             * 创建COS撤销Signal消息
             */
            fun create(cosRevocation: CosRevocation): Revocation {
                return Revocation(
                    messageId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    cosRevocation = cosRevocation
                )
            }
        }
    }

    /**
     * COS断开连接Signal消息
     */
    data class Disconnection(
        @JsonProperty("messageId")
        override val messageId: String,

        @JsonProperty("timestamp")
        override val timestamp: Long,

        @JsonProperty("cosDisconnection")
        val cosDisconnection: CosDisconnection
    ) : CosSignalMessage() {

        companion object {
            /**
             * 创建COS断开连接Signal消息
             */
            fun create(cosDisconnection: CosDisconnection): Disconnection {
                return Disconnection(
                    messageId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    cosDisconnection = cosDisconnection
                )
            }
        }
    }
}

/**
 * COS消息类型标识
 * 用于在Signal消息中标识COS相关消息
 */
object CosMessageType {
    const val COS_REQUEST = "cos_request"
    const val COS_RESPONSE = "cos_response"
    const val COS_REVOCATION = "cos_revocation"
    const val COS_DISCONNECTION = "cos_disconnection"

    /**
     * 检查是否为COS消息类型
     */
    fun isCosMessageType(type: String): Boolean {
        return type in listOf(COS_REQUEST, COS_RESPONSE, COS_REVOCATION, COS_DISCONNECTION)
    }
}

/**
 * COS消息状态
 * 用于跟踪COS请求和响应的处理状态
 */
enum class CosMessageStatus {
    @JsonProperty("pending")
    PENDING, // 等待处理
    
    @JsonProperty("processing")
    PROCESSING, // 处理中
    
    @JsonProperty("completed")
    COMPLETED, // 已完成
    
    @JsonProperty("failed")
    FAILED, // 处理失败
    
    @JsonProperty("expired")
    EXPIRED // 已过期
}

/**
 * COS消息处理结果
 */
data class CosMessageProcessResult(
    @JsonProperty("messageId")
    val messageId: String,
    
    @JsonProperty("status")
    val status: CosMessageStatus,
    
    @JsonProperty("processedAt")
    val processedAt: Long,
    
    @JsonProperty("errorMessage")
    val errorMessage: String? = null,
    
    @JsonProperty("channelId")
    val channelId: String? = null // 如果成功建立通道，记录通道ID
) {
    companion object {
        /**
         * 创建成功结果
         */
        fun success(messageId: String, channelId: String? = null): CosMessageProcessResult {
            return CosMessageProcessResult(
                messageId = messageId,
                status = CosMessageStatus.COMPLETED,
                processedAt = System.currentTimeMillis(),
                channelId = channelId
            )
        }
        
        /**
         * 创建失败结果
         */
        fun failure(messageId: String, errorMessage: String): CosMessageProcessResult {
            return CosMessageProcessResult(
                messageId = messageId,
                status = CosMessageStatus.FAILED,
                processedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        }
        
        /**
         * 创建过期结果
         */
        fun expired(messageId: String): CosMessageProcessResult {
            return CosMessageProcessResult(
                messageId = messageId,
                status = CosMessageStatus.EXPIRED,
                processedAt = System.currentTimeMillis(),
                errorMessage = "消息已过期"
            )
        }
    }
}

/**
 * COS消息显示信息
 * 用于在UI中显示COS相关消息的信息
 */
data class CosMessageDisplayInfo(
    @JsonProperty("messageType")
    val messageType: String, // COS_REQUEST, COS_RESPONSE, COS_REVOCATION
    
    @JsonProperty("title")
    val title: String, // 显示标题
    
    @JsonProperty("description")
    val description: String, // 描述信息
    
    @JsonProperty("actionRequired")
    val actionRequired: Boolean, // 是否需要用户操作
    
    @JsonProperty("actionText")
    val actionText: String? = null, // 操作按钮文本
    
    @JsonProperty("status")
    val status: CosMessageStatus,
    
    @JsonProperty("expiresAt")
    val expiresAt: Long? = null, // 过期时间
    
    @JsonProperty("metadata")
    val metadata: Map<String, String> = emptyMap() // 额外的元数据
) {
    companion object {
        /**
         * 为COS请求创建显示信息
         */
        fun forRequest(request: CosRequest): CosMessageDisplayInfo {
            return CosMessageDisplayInfo(
                messageType = CosMessageType.COS_REQUEST,
                title = "COS通信请求",
                description = "对方请求建立COS通信通道，访问时长：${request.durationType.name}",
                actionRequired = true,
                actionText = "接受/拒绝",
                status = CosMessageStatus.PENDING,
                expiresAt = request.timestamp + (24 * 60 * 60 * 1000), // 24小时后过期
                metadata = mapOf(
                    "requestId" to request.requestId,
                    "duration" to request.durationType.name,
                    "provider" to request.accessInfo.provider
                )
            )
        }
        
        /**
         * 为COS响应创建显示信息
         */
        fun forResponse(response: CosResponse): CosMessageDisplayInfo {
            val title = if (response.accepted) "COS通信请求已接受" else "COS通信请求已拒绝"
            val description = if (response.accepted) {
                "对方接受了您的COS通信请求，通道已建立"
            } else {
                "对方拒绝了您的COS通信请求：${response.rejectionReason ?: "未提供原因"}"
            }
            
            return CosMessageDisplayInfo(
                messageType = CosMessageType.COS_RESPONSE,
                title = title,
                description = description,
                actionRequired = false,
                status = CosMessageStatus.COMPLETED,
                metadata = mapOf(
                    "requestId" to response.requestId,
                    "accepted" to response.accepted.toString()
                )
            )
        }
        
        /**
         * 为COS撤销创建显示信息
         */
        fun forRevocation(revocation: CosRevocation): CosMessageDisplayInfo {
            return CosMessageDisplayInfo(
                messageType = CosMessageType.COS_REVOCATION,
                title = "COS通信已撤销",
                description = "COS通信通道已被撤销：${revocation.revocationReason}",
                actionRequired = false,
                status = CosMessageStatus.COMPLETED,
                metadata = mapOf(
                    "requestId" to revocation.requestId,
                    "type" to revocation.type.name,
                    "reason" to revocation.revocationReason
                )
            )
        }

        /**
         * 为COS断开连接创建显示信息
         */
        fun forDisconnection(disconnection: CosDisconnection): CosMessageDisplayInfo {
            return CosMessageDisplayInfo(
                messageType = CosMessageType.COS_DISCONNECTION,
                title = "COS v2 mode disconnected",
                description = "COS v2 mode has been disconnected: ${disconnection.disconnectionReason}",
                actionRequired = false,
                status = CosMessageStatus.COMPLETED,
                metadata = mapOf(
                    "requestId" to disconnection.requestId,
                    "reason" to disconnection.disconnectionReason,
                    "initiatedBy" to disconnection.initiatedBy
                )
            )
        }
    }
}
