package org.thoughtcrime.securesms.coscomm.utils

import org.thoughtcrime.securesms.coscomm.data.*
import org.signal.core.util.logging.Log
import java.util.concurrent.TimeUnit

/**
 * COS消息验证工具类
 * 提供各种COS消息的验证功能，确保消息格式正确和安全性
 */
object CosMessageValidator {
    private val TAG = "CosMessageValidator"
    
    /**
     * 验证COS消息
     */
    fun validateCosMessage(message: CosMessage): CosResult<Unit> {
        return try {
            // 基本字段验证
            validateBasicFields(message)?.let { return it }
            
            // 序列号验证已移除，依赖Signal原生处理
            
            // 内容验证
            validateContent(message)?.let { return it }
            
            // 附件验证（如果存在）
            message.attachmentInfo?.let { attachment ->
                validateAttachmentInfo(attachment)?.let { return it }
            }
            
            Log.d(TAG, "COS消息验证通过: messageId=${message.messageId}")
            CosResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "COS消息验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, e))
        }
    }
    
    /**
     * 验证COS请求
     */
    fun validateCosRequest(request: CosRequest): CosResult<Unit> {
        return try {
            // 基本字段验证
            if (request.requestId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "请求ID不能为空"))
            }
            
            if (request.timestamp <= 0) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的时间戳"))
            }
            
            // 检查请求是否过期（24小时）
            val maxAge = TimeUnit.HOURS.toMillis(24)
            if (System.currentTimeMillis() - request.timestamp > maxAge) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "请求已过期"))
            }
            
            // 验证访问信息
            validateAccessInfo(request.accessInfo)?.let { return it }
            
            // 验证时长类型
            validateDuration(request.durationType)?.let { return it }
            
            Log.d(TAG, "COS请求验证通过: requestId=${request.requestId}")
            CosResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "COS请求验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, e))
        }
    }
    
    /**
     * 验证COS响应
     */
    fun validateCosResponse(response: CosResponse): CosResult<Unit> {
        return try {
            // 基本字段验证
            if (response.requestId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "请求ID不能为空"))
            }
            
            if (response.timestamp <= 0) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的时间戳"))
            }
            
            // 如果接受请求，必须提供访问信息
            if (response.accepted) {
                if (response.accessInfo == null) {
                    return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "接受请求时必须提供访问信息"))
                }
                
                validateAccessInfo(response.accessInfo)?.let { return it }
                
                if (response.agreedDuration == null) {
                    return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "接受请求时必须提供同意的时长"))
                }
                
                validateDuration(response.agreedDuration)?.let { return it }
            } else {
                // 如果拒绝请求，应该提供拒绝原因
                if (response.rejectionReason.isNullOrBlank()) {
                    Log.w(TAG, "拒绝请求时建议提供拒绝原因")
                }
            }
            
            Log.d(TAG, "COS响应验证通过: requestId=${response.requestId}")
            CosResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "COS响应验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, e))
        }
    }
    
    /**
     * 验证COS撤销消息
     */
    fun validateCosRevocation(revocation: CosRevocation): CosResult<Unit> {
        return try {
            if (revocation.requestId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "请求ID不能为空"))
            }
            
            if (revocation.revocationTime <= 0) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的撤销时间"))
            }
            
            if (revocation.revocationReason.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "撤销原因不能为空"))
            }
            
            Log.d(TAG, "COS撤销消息验证通过: requestId=${revocation.requestId}")
            CosResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "COS撤销消息验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, e))
        }
    }
    
    /**
     * 验证COS通道
     */
    fun validateCosChannel(channel: CosChannel): CosResult<Unit> {
        return try {
            if (channel.channelId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "通道ID不能为空"))
            }
            
            if (channel.recipientId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "接收者ID不能为空"))
            }
            
            if (channel.requestId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "请求ID不能为空"))
            }
            
            // 验证访问信息（如果存在）
            channel.myAccessInfo?.let { accessInfo ->
                validateAccessInfo(accessInfo)?.let { return it }
            }
            
            channel.theirAccessInfo?.let { accessInfo ->
                validateAccessInfo(accessInfo)?.let { return it }
            }
            
            Log.d(TAG, "COS通道验证通过: channelId=${channel.channelId}")
            CosResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "COS通道验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, e))
        }
    }
    
    /**
     * 验证基本字段
     */
    private fun validateBasicFields(message: CosMessage): CosResult<Unit>? {
        if (message.version != CosMessage.CURRENT_VERSION) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "不支持的消息版本: ${message.version}"))
        }
        
        if (message.messageId.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "消息ID不能为空"))
        }
        
        if (message.senderId.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "发送者ID不能为空"))
        }
        
        if (message.recipientId.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "接收者ID不能为空"))
        }
        
        if (message.timestamp <= 0) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的时间戳"))
        }
        
        if (message.signalCiphertext.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "Signal密文不能为空"))
        }
        
        return null
    }
    

    
    /**
     * 验证内容
     */
    private fun validateContent(message: CosMessage): CosResult<Unit>? {
        if (message.contentMetadata.originalSize <= 0) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的内容大小"))
        }
        
        if (message.contentMetadata.originalSize > CosConstants.MAX_MESSAGE_SIZE) {
            return CosResult.Error(CosException(CosErrorCode.MESSAGE_TOO_LARGE, "消息大小超过限制"))
        }
        
        return null
    }
    
    /**
     * 验证附件信息
     */
    private fun validateAttachmentInfo(attachmentInfo: AttachmentInfo): CosResult<Unit>? {
        if (attachmentInfo.attachmentId.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "附件ID不能为空"))
        }
        
        if (attachmentInfo.size <= 0) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "无效的附件大小"))
        }
        
        if (attachmentInfo.size > CosConstants.MAX_ATTACHMENT_SIZE) {
            return CosResult.Error(CosException(CosErrorCode.MESSAGE_TOO_LARGE, "附件大小超过限制"))
        }
        
        return null
    }
    
    /**
     * 验证访问信息
     */
    private fun validateAccessInfo(accessInfo: CosAccessInfo): CosResult<Unit>? {
        if (accessInfo.provider.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "提供商不能为空"))
        }
        
        if (accessInfo.region.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "区域不能为空"))
        }
        
        if (accessInfo.bucketName.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "存储桶名称不能为空"))
        }
        
        if (accessInfo.accessKeyId.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "访问密钥ID不能为空"))
        }
        
        if (accessInfo.secretAccessKey.isBlank()) {
            return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "访问密钥不能为空"))
        }
        
        if (accessInfo.expireTime <= System.currentTimeMillis()) {
            return CosResult.Error(CosException(CosErrorCode.TOKEN_EXPIRED, "访问令牌已过期"))
        }
        
        return null
    }
    
    /**
     * 验证时长类型
     */
    private fun validateDuration(duration: CosDuration): CosResult<Unit>? {
        // 检查时长是否在合理范围内
        when (duration) {
            CosDuration.ONE_HOUR -> {
                if (duration.hours < CosConstants.MIN_TOKEN_VALIDITY_HOURS) {
                    return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "访问时长过短"))
                }
            }
            CosDuration.PERMANENT -> {
                // 永久访问需要特殊权限，这里可以添加额外检查
                Log.w(TAG, "请求永久访问权限")
            }
            else -> {
                if (duration.hours > CosConstants.MAX_TOKEN_VALIDITY_DAYS * 24) {
                    return CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT, "访问时长过长"))
                }
            }
        }
        
        return null
    }

    /**
     * 验证COS访问信息
     */
    fun validateCosAccessInfo(accessInfo: CosAccessInfo): CosResult<Unit> {
        return try {
            // 检查基本字段
            if (accessInfo.provider.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "提供商不能为空"))
            }

            if (accessInfo.region.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "区域不能为空"))
            }

            if (accessInfo.bucketName.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "存储桶名称不能为空"))
            }

            if (accessInfo.accessKeyId.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "访问密钥ID不能为空"))
            }

            if (accessInfo.secretAccessKey.isBlank()) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "访问密钥不能为空"))
            }

            // 检查过期时间
            if (accessInfo.isExpired()) {
                return CosResult.Error(CosException(CosErrorCode.TOKEN_EXPIRED, "访问凭证已过期"))
            }

            // 检查共享目录
            if (accessInfo.sharedDirectory != "/outbox/") {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "共享目录必须为/outbox/"))
            }

            // 检查提供商是否支持
            val supportedProviders = listOf("AWS", "TENCENT", "ALIYUN")
            if (accessInfo.provider !in supportedProviders) {
                return CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, "不支持的提供商: ${accessInfo.provider}"))
            }

            Log.d(TAG, "COS访问信息验证通过: provider=${accessInfo.provider}, bucket=${accessInfo.bucketName}")
            CosResult.Success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "COS访问信息验证异常", e)
            CosResult.Error(CosException(CosErrorCode.INVALID_CREDENTIALS, e))
        }
    }
}
