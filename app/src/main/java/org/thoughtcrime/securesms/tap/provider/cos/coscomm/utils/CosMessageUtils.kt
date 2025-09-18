package org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils

import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.signal.core.util.logging.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * COS消息工具类
 * 提供COS消息处理的各种实用工具方法
 */
object CosMessageUtils {
    private val TAG = "CosMessageUtils"
    private val secureRandom = SecureRandom()
    
    /**
     * 创建COS消息
     */
    fun createCosMessage(
        senderId: String,
        recipientId: String,
        messageType: MessageType,
        signalCiphertext: ByteArray,
        signalCiphertextType: Int = 2, // 默认为WHISPER_TYPE
        attachmentInfo: AttachmentInfo? = null,
        timestamp: Long = System.currentTimeMillis() // 允许指定时间戳，默认使用当前时间
    ): CosMessage {
        val contentMetadata = ContentMetadata(
            originalSize = signalCiphertext.size.toLong(),
            compressionType = CompressionType.NONE,
            encryptionAlgorithm = "Signal-Protocol"
        )
        
        return CosMessage(
            messageId = CosMessage.generateMessageId(),
            timestamp = timestamp, // 使用传入的时间戳
            senderId = senderId,
            recipientId = recipientId,
            messageType = messageType,
            signalCiphertext = CosMessageSerializer.encodeBase64(signalCiphertext),
            signalCiphertextType = signalCiphertextType,
            contentMetadata = contentMetadata,
            attachmentInfo = attachmentInfo
        )
    }
    
    /**
     * 创建COS请求
     */
    fun createCosRequest(
        provider: String,
        region: String,
        bucketName: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        durationType: CosDuration,
        message: String? = null
    ): CosRequest {
        val accessInfo = CosAccessInfo(
            provider = provider,
            region = region,
            bucketName = bucketName,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            expireTime = durationType.getExpireTime()
        )
        
        return CosRequest.create(durationType, accessInfo, message)
    }
    
    /**
     * 检查消息是否过期
     */
    fun isMessageExpired(message: CosMessage, maxAgeHours: Int = 24): Boolean {
        val maxAge = TimeUnit.HOURS.toMillis(maxAgeHours.toLong())
        return System.currentTimeMillis() - message.timestamp > maxAge
    }
    
    /**
     * 检查请求是否过期
     */
    fun isRequestExpired(request: CosRequest, maxAgeHours: Int = 24): Boolean {
        val maxAge = TimeUnit.HOURS.toMillis(maxAgeHours.toLong())
        return System.currentTimeMillis() - request.timestamp > maxAge
    }
    
    /**
     * 检查访问信息是否即将过期
     */
    fun isAccessInfoExpiringSoon(accessInfo: CosAccessInfo, thresholdHours: Int = 24): Boolean {
        val threshold = TimeUnit.HOURS.toMillis(thresholdHours.toLong())
        return accessInfo.getRemainingTime() < threshold
    }
    
    /**
     * 计算消息哈希值（用于去重）
     */
    fun calculateMessageHash(message: CosMessage): String {
        val content = "${message.senderId}:${message.recipientId}:${message.timestamp}:${message.messageId}"
        return calculateSHA256(content)
    }
    
    /**
     * 计算SHA256哈希值
     */
    fun calculateSHA256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 生成安全的随机字符串
     */
    fun generateSecureRandomString(length: Int = 16): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return "%.1f %s".format(size, units[unitIndex])
    }
    
    /**
     * 格式化时间间隔
     */
    fun formatTimeInterval(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}天"
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            else -> "${seconds}秒"
        }
    }
    
    /**
     * 检查消息序号是否连续
     */
    fun isMessageNumberSequential(
        previousMessageNumber: Int,
        currentMessageNumber: Int
    ): Boolean {
        return currentMessageNumber == previousMessageNumber + 1
    }
    
    /**
     * 检查链序号是否有效
     */
    fun isChainNumberValid(
        previousChainNumber: Int,
        currentChainNumber: Int
    ): Boolean {
        // 链序号应该相同或递增1
        return currentChainNumber == previousChainNumber || 
               currentChainNumber == previousChainNumber + 1
    }
    
    /**
     * 创建错误响应
     */
    fun createErrorResponse(
        requestId: String,
        errorCode: CosErrorCode,
        customMessage: String? = null
    ): CosResponse {
        val rejectionReason = customMessage ?: errorCode.message
        return CosResponse.createRejected(requestId, rejectionReason)
    }
    
    /**
     * 检查提供商是否支持
     */
    fun isSupportedProvider(provider: String): Boolean {
        return provider.uppercase() in listOf("AWS", "TENCENT", "ALIYUN")
    }
    
    /**
     * 获取轮询间隔
     */
    fun getPollingInterval(lastActivity: Long): Long {
        val timeSinceLastActivity = System.currentTimeMillis() - lastActivity
        
        return when {
            timeSinceLastActivity < TimeUnit.MINUTES.toMillis(CosConstants.ACTIVITY_THRESHOLD_MINUTES.toLong()) -> 
                CosConstants.ACTIVE_POLLING_INTERVAL
            timeSinceLastActivity < TimeUnit.HOURS.toMillis(CosConstants.INACTIVE_THRESHOLD_HOURS.toLong()) -> 
                CosConstants.INACTIVE_POLLING_INTERVAL
            else -> 
                CosConstants.BACKGROUND_POLLING_INTERVAL
        }
    }
    
    /**
     * 检查是否应该跳过轮询
     * @deprecated 使用IntelligentPollingStrategy.shouldSkipPolling替代
     */
    fun shouldSkipPolling(camPoolEntry: CamPoolEntry): Boolean {
        return !camPoolEntry.isValid() ||
               camPoolEntry.pollingErrors >= 5 // MAX_POLLING_ERRORS
    }
    
    /**
     * 计算重试延迟（指数退避）
     */
    fun calculateRetryDelay(attemptCount: Int, baseDelayMs: Long = CosConstants.RETRY_BACKOFF_BASE): Long {
        val maxDelay = TimeUnit.MINUTES.toMillis(5) // 最大5分钟
        val delay = baseDelayMs * (1L shl minOf(attemptCount, 10)) // 2^attemptCount，最大2^10
        return minOf(delay, maxDelay)
    }
    
    /**
     * 检查消息是否需要清理
     */
    fun shouldCleanupMessage(messageTimestamp: Long): Boolean {
        val retentionTime = TimeUnit.DAYS.toMillis(CosConstants.MESSAGE_RETENTION_DAYS.toLong())
        return System.currentTimeMillis() - messageTimestamp > retentionTime
    }
    
    /**
     * 检查临时文件是否需要清理
     */
    fun shouldCleanupTempFile(fileTimestamp: Long): Boolean {
        val cleanupTime = TimeUnit.HOURS.toMillis(CosConstants.TEMP_FILE_CLEANUP_HOURS.toLong())
        return System.currentTimeMillis() - fileTimestamp > cleanupTime
    }
    
    /**
     * 生成消息摘要（用于日志）
     */
    fun generateMessageDigest(message: CosMessage): String {
        return "CosMessage(id=${message.messageId.take(8)}..., " +
               "type=${message.messageType}, " +
               "sender=${message.senderId.take(8)}..., " +
               "recipient=${message.recipientId.take(8)}..., " +
               "timestamp=${message.timestamp}, " +
               "size=${formatFileSize(message.contentMetadata.originalSize)})"
    }
    
    /**
     * 生成请求摘要（用于日志）
     */
    fun generateRequestDigest(request: CosRequest): String {
        return "CosRequest(id=${request.requestId.take(8)}..., " +
               "duration=${request.durationType}, " +
               "provider=${request.accessInfo.provider}, " +
               "bucket=${request.accessInfo.bucketName})"
    }
    
    /**
     * 生成通道摘要（用于日志）
     */
    fun generateChannelDigest(channel: CosChannel): String {
        return "CosChannel(id=${channel.channelId.take(8)}..., " +
               "recipient=${channel.recipientId.take(8)}..., " +
               "status=${channel.status}, " +
               "canSend=${channel.canSendMessages()}, " +
               "canReceive=${channel.canReceiveMessages()})"
    }
}
