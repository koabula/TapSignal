package org.thoughtcrime.securesms.coscomm.utils

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.thoughtcrime.securesms.coscomm.data.*
import org.signal.core.util.logging.Log
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * COS消息序列化和反序列化工具类
 * 提供COS消息与JSON之间的转换功能，处理二进制数据的Base64编码
 */
object CosMessageSerializer {
    private val TAG = "CosMessageSerializer"
    
    // Jackson ObjectMapper配置
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        // 配置忽略未知字段，避免反序列化时出错
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    
    /**
     * 将COS消息序列化为JSON字符串
     */
    fun serializeMessage(message: CosMessage): CosResult<String> {
        return try {
            val jsonString = objectMapper.writeValueAsString(message)
            Log.d(TAG, "消息序列化成功: messageId=${message.messageId}")
            CosResult.Success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "消息序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将JSON字符串反序列化为COS消息
     */
    fun deserializeMessage(jsonString: String): CosResult<CosMessage> {
        return try {
            val message = objectMapper.readValue<CosMessage>(jsonString)
            
            // 验证消息格式
            validateMessage(message)?.let { error ->
                return CosResult.Error(error)
            }
            
            Log.d(TAG, "消息反序列化成功: messageId=${message.messageId}")
            CosResult.Success(message)
        } catch (e: Exception) {
            Log.e(TAG, "消息反序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将COS请求序列化为JSON字符串
     */
    fun serializeRequest(request: CosRequest): CosResult<String> {
        return try {
            val jsonString = objectMapper.writeValueAsString(request)
            Log.d(TAG, "请求序列化成功: requestId=${request.requestId}")
            CosResult.Success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "请求序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将JSON字符串反序列化为COS请求
     */
    fun deserializeRequest(jsonString: String): CosResult<CosRequest> {
        return try {
            val request = objectMapper.readValue<CosRequest>(jsonString)
            Log.d(TAG, "请求反序列化成功: requestId=${request.requestId}")
            CosResult.Success(request)
        } catch (e: Exception) {
            Log.e(TAG, "请求反序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将COS响应序列化为JSON字符串
     */
    fun serializeResponse(response: CosResponse): CosResult<String> {
        return try {
            val jsonString = objectMapper.writeValueAsString(response)
            Log.d(TAG, "响应序列化成功: requestId=${response.requestId}")
            CosResult.Success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "响应序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将JSON字符串反序列化为COS响应
     */
    fun deserializeResponse(jsonString: String): CosResult<CosResponse> {
        return try {
            val response = objectMapper.readValue<CosResponse>(jsonString)
            Log.d(TAG, "响应反序列化成功: requestId=${response.requestId}")
            CosResult.Success(response)
        } catch (e: Exception) {
            Log.e(TAG, "响应反序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将COS撤销消息序列化为JSON字符串
     */
    fun serializeRevocation(revocation: CosRevocation): CosResult<String> {
        return try {
            val jsonString = objectMapper.writeValueAsString(revocation)
            Log.d(TAG, "撤销消息序列化成功: requestId=${revocation.requestId}")
            CosResult.Success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "撤销消息序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将JSON字符串反序列化为COS撤销消息
     */
    fun deserializeRevocation(jsonString: String): CosResult<CosRevocation> {
        return try {
            val revocation = objectMapper.readValue<CosRevocation>(jsonString)
            Log.d(TAG, "撤销消息反序列化成功: requestId=${revocation.requestId}")
            CosResult.Success(revocation)
        } catch (e: Exception) {
            Log.e(TAG, "撤销消息反序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将字节数组编码为Base64字符串
     */
    fun encodeBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    
    /**
     * 将COS Signal消息序列化为JSON字符串
     */
    fun serializeSignalMessage(signalMessage: CosSignalMessage): CosResult<String> {
        return try {
            // 创建包含messageType的Map
            val messageMap = mutableMapOf<String, Any>()

            // 添加通用字段
            messageMap["messageId"] = signalMessage.messageId
            messageMap["timestamp"] = signalMessage.timestamp

            // 根据消息类型添加特定字段
            when (signalMessage) {
                is CosSignalMessage.Request -> {
                    messageMap["messageType"] = "COS_REQUEST"
                    messageMap["cosRequest"] = signalMessage.cosRequest
                }
                is CosSignalMessage.Response -> {
                    messageMap["messageType"] = "COS_RESPONSE"
                    messageMap["cosResponse"] = signalMessage.cosResponse
                }
                is CosSignalMessage.Revocation -> {
                    messageMap["messageType"] = "COS_REVOCATION"
                    messageMap["cosRevocation"] = signalMessage.cosRevocation
                }
                is CosSignalMessage.Disconnection -> {
                    messageMap["messageType"] = "COS_DISCONNECTION"
                    messageMap["cosDisconnection"] = signalMessage.cosDisconnection
                }
            }

            val jsonString = objectMapper.writeValueAsString(messageMap)
            Log.d(TAG, "Signal消息序列化成功: messageId=${signalMessage.messageId}")
            CosResult.Success(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Signal消息序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }

    /**
     * 将JSON字符串反序列化为COS Signal消息
     */
    fun deserializeSignalMessage(jsonString: String): CosResult<CosSignalMessage> {
        return try {
            // 首先解析为通用的Map来检查消息类型
            val messageMap = objectMapper.readValue<Map<String, Any>>(jsonString)
            val messageType = messageMap["messageType"] as? String

            val signalMessage = when (messageType) {
                "COS_REQUEST" -> {
                    objectMapper.readValue<CosSignalMessage.Request>(jsonString)
                }
                "COS_RESPONSE" -> {
                    objectMapper.readValue<CosSignalMessage.Response>(jsonString)
                }
                "COS_REVOCATION" -> {
                    objectMapper.readValue<CosSignalMessage.Revocation>(jsonString)
                }
                "COS_DISCONNECTION" -> {
                    objectMapper.readValue<CosSignalMessage.Disconnection>(jsonString)
                }
                else -> {
                    throw IllegalArgumentException("未知的COS Signal消息类型: $messageType")
                }
            }

            Log.d(TAG, "Signal消息反序列化成功: messageId=${signalMessage.messageId}, type=$messageType")
            CosResult.Success(signalMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Signal消息反序列化失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }

    /**
     * 将Base64字符串解码为字节数组
     */
    fun decodeBase64(base64String: String): CosResult<ByteArray> {
        return try {
            val data = Base64.decode(base64String, Base64.NO_WRAP)
            CosResult.Success(data)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Base64解码失败", e)
            CosResult.Error(CosException(CosErrorCode.MESSAGE_PARSING_FAILED, e))
        }
    }
    
    /**
     * 将字符串编码为Base64
     */
    fun encodeStringToBase64(text: String): String {
        return encodeBase64(text.toByteArray(StandardCharsets.UTF_8))
    }
    
    /**
     * 将Base64字符串解码为字符串
     */
    fun decodeBase64ToString(base64String: String): CosResult<String> {
        return decodeBase64(base64String).map { bytes ->
            String(bytes, StandardCharsets.UTF_8)
        }
    }
    
    /**
     * 验证消息格式
     */
    private fun validateMessage(message: CosMessage): CosException? {
        // 检查版本
        if (message.version != CosMessage.CURRENT_VERSION) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "不支持的消息版本: ${message.version}")
        }
        
        // 检查必需字段
        if (message.messageId.isBlank()) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "消息ID不能为空")
        }
        
        if (message.senderId.isBlank()) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "发送者ID不能为空")
        }
        
        if (message.recipientId.isBlank()) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "接收者ID不能为空")
        }
        
        if (message.signalCiphertext.isBlank()) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "Signal密文不能为空")
        }
        
        // 检查时间戳
        if (message.timestamp <= 0) {
            return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的时间戳")
        }
        
        // 序列号检查已移除，依赖Signal原生处理
        
        // 检查内容大小
        if (message.contentMetadata.originalSize > CosConstants.MAX_MESSAGE_SIZE) {
            return CosException(CosErrorCode.MESSAGE_TOO_LARGE, "消息大小超过限制")
        }
        
        // 检查附件信息（如果存在）
        message.attachmentInfo?.let { attachment ->
            if (attachment.attachmentId.isBlank()) {
                return CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "附件ID不能为空")
            }
            
            if (attachment.size > CosConstants.MAX_ATTACHMENT_SIZE) {
                return CosException(CosErrorCode.MESSAGE_TOO_LARGE, "附件大小超过限制")
            }
        }
        
        return null
    }
}
