package org.thoughtcrime.securesms.tap.provider.cos.coscomm.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.*

/**
 * COS通道状态数据结构
 * 管理每个联系人的COS通信通道状态
 */
data class CosChannel(
    @JsonProperty("channelId")
    val channelId: String,
    
    @JsonProperty("recipientId")
    val recipientId: String, // 对方的Service ID
    
    @JsonProperty("requestId")
    val requestId: String, // 原始请求ID
    
    @JsonProperty("status")
    val status: ChannelStatus,
    
    @JsonProperty("establishedTime")
    val establishedTime: Long? = null,
    
    @JsonProperty("lastActivity")
    val lastActivity: Long,
    
    @JsonProperty("myAccessInfo")
    val myAccessInfo: CosAccessInfo? = null, // 我分享给对方的访问信息
    
    @JsonProperty("theirAccessInfo")
    val theirAccessInfo: CosAccessInfo? = null, // 对方分享给我的访问信息

    @JsonProperty("channelDirectory")
    val channelDirectory: String? = null, // v2通道目录名（如：signal-v2-1640995200000-1234）

    @JsonProperty("statistics")
    val statistics: ChannelStatistics,
    
    @JsonProperty("createdAt")
    val createdAt: Long,
    
    @JsonProperty("updatedAt")
    val updatedAt: Long
) {
    companion object {
        /**
         * 生成新的通道ID
         */
        fun generateChannelId(): String = UUID.randomUUID().toString()
        
        /**
         * 创建新的COS通道
         */
        fun create(
            recipientId: String,
            requestId: String,
            status: ChannelStatus = ChannelStatus.PENDING
        ): CosChannel {
            val now = System.currentTimeMillis()
            return CosChannel(
                channelId = generateChannelId(),
                recipientId = recipientId,
                requestId = requestId,
                status = status,
                lastActivity = now,
                statistics = ChannelStatistics(),
                createdAt = now,
                updatedAt = now
            )
        }
    }
    
    /**
     * 检查通道是否活跃
     */
    @JsonIgnore
    fun isActive(): Boolean {
        return status == ChannelStatus.ACTIVE &&
               theirAccessInfo?.isExpired() == false
    }
    
    /**
     * 检查是否可以发送消息
     */
    @JsonIgnore
    fun canSendMessages(): Boolean {
        return isActive() && myAccessInfo != null
    }

    /**
     * 检查是否可以接收消息
     */
    @JsonIgnore
    fun canReceiveMessages(): Boolean {
        return isActive() && theirAccessInfo != null
    }

    /**
     * 获取我的通道目录名（从我的访问信息中提取）
     */
    @JsonIgnore
    fun getMyChannelDirectory(): String? {
        return channelDirectory ?: extractChannelDirectoryFromPath(myAccessInfo?.sharedDirectory)
    }

    /**
     * 获取对方的通道目录名（从对方的访问信息中提取）
     */
    @JsonIgnore
    fun getTheirChannelDirectory(): String? {
        val result = extractChannelDirectoryFromPath(theirAccessInfo?.sharedDirectory)
        org.signal.core.util.logging.Log.d("CosChannel", "提取对方通道目录: sharedDirectory=${theirAccessInfo?.sharedDirectory}, 提取结果=$result")
        return result
    }

    /**
     * 从共享目录路径中提取通道目录名
     * 例如："/v2-channels/signal-v2-1640995200000-1234/outbox/" -> "signal-v2-1640995200000-1234"
     */
    @JsonIgnore
    private fun extractChannelDirectoryFromPath(sharedDirectory: String?): String? {
        if (sharedDirectory.isNullOrEmpty()) return null

        val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
        val matchResult = regex.find(sharedDirectory)
        val result = matchResult?.groupValues?.get(1)
        org.signal.core.util.logging.Log.d("CosChannel", "正则匹配: 输入=$sharedDirectory, 匹配结果=$result")
        return result
    }

    /**
     * 更新最后活动时间
     */
    @JsonIgnore
    fun updateActivity(): CosChannel {
        return copy(
            lastActivity = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 更新通道状态
     */
    @JsonIgnore
    fun updateStatus(newStatus: ChannelStatus): CosChannel {
        return copy(
            status = newStatus,
            updatedAt = System.currentTimeMillis()
        )
    }
}

/**
 * 通道统计信息
 */
data class ChannelStatistics(
    @JsonProperty("messagesSent")
    val messagesSent: Int = 0,
    
    @JsonProperty("messagesReceived")
    val messagesReceived: Int = 0,
    
    @JsonProperty("lastSyncTime")
    val lastSyncTime: Long = 0,
    
    @JsonProperty("totalDataSent")
    val totalDataSent: Long = 0, // 字节数
    
    @JsonProperty("totalDataReceived")
    val totalDataReceived: Long = 0, // 字节数
    
    @JsonProperty("errorCount")
    val errorCount: Int = 0
) {
    /**
     * 增加发送消息计数
     */
    fun incrementSent(dataSize: Long = 0): ChannelStatistics {
        return copy(
            messagesSent = messagesSent + 1,
            totalDataSent = totalDataSent + dataSize
        )
    }
    
    /**
     * 增加接收消息计数
     */
    fun incrementReceived(dataSize: Long = 0): ChannelStatistics {
        return copy(
            messagesReceived = messagesReceived + 1,
            totalDataReceived = totalDataReceived + dataSize,
            lastSyncTime = System.currentTimeMillis()
        )
    }
    
    /**
     * 增加错误计数
     */
    fun incrementError(): ChannelStatistics {
        return copy(errorCount = errorCount + 1)
    }
}

/**
 * 通道状态枚举
 */
enum class ChannelStatus {
    @JsonProperty("pending")
    PENDING, // 等待对方响应
    
    @JsonProperty("active")
    ACTIVE, // 通道活跃
    
    @JsonProperty("expired")
    EXPIRED, // 令牌过期
    
    @JsonProperty("revoked")
    REVOKED, // 已撤销
    
    @JsonProperty("error")
    ERROR, // 错误状态
    
    @JsonProperty("suspended")
    SUSPENDED // 暂停（临时错误）
}

/**
 * 轮询兼容性条目数据结构
 * 为了兼容现有的轮询策略而保留，实际使用SubAccountEntry
 * @deprecated 使用SubAccountEntry替代，此类仅用于轮询策略兼容性
 */
data class CamPoolEntry(
    @JsonProperty("recipientId")
    val recipientId: String, // 对方的Service ID

    @JsonProperty("accessInfo")
    val accessInfo: CosAccessInfo, // 对方分享给我的子账户凭证

    @JsonProperty("channelDirectory")
    val channelDirectory: String? = null, // 对方的v2通道目录名（从accessInfo中提取）

    @JsonProperty("lastPollingTime")
    val lastPollingTime: Long = 0, // 最后轮询时间
    
    @JsonProperty("pollingErrors")
    val pollingErrors: Int = 0, // 连续轮询错误次数
    
    @JsonProperty("isActive")
    val isActive: Boolean = true, // 是否活跃
    
    @JsonProperty("createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    
    @JsonProperty("updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 检查凭证是否有效
     */
    fun isValid(): Boolean {
        return isActive && !accessInfo.isExpired() && pollingErrors < 5 // MAX_POLLING_ERRORS
    }
    
    /**
     * 更新轮询时间
     */
    fun updatePollingTime(): CamPoolEntry {
        return copy(
            lastPollingTime = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 增加轮询错误
     */
    fun incrementPollingError(): CamPoolEntry {
        return copy(
            pollingErrors = pollingErrors + 1,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 重置轮询错误
     */
    fun resetPollingErrors(): CamPoolEntry {
        return copy(
            pollingErrors = 0,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 设置活跃状态
     */
    fun setActive(active: Boolean): CamPoolEntry {
        return copy(
            isActive = active,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 检查凭证是否过期
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return accessInfo.isExpired()
    }

    /**
     * 转换为CosAccessToken
     */
    fun toCosAccessToken(): org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken {
        return org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken(
            accessKeyId = accessInfo.accessKeyId,
            secretAccessKey = accessInfo.secretAccessKey,
            sessionToken = accessInfo.sessionToken,
            expireTime = accessInfo.expireTime
        )
    }

    /**
     * 获取通道目录名（从accessInfo中提取或使用已存储的值）
     */
    fun resolveChannelDirectory(): String? {
        return channelDirectory ?: extractChannelDirectoryFromAccessInfo()
    }

    /**
     * 从accessInfo的sharedDirectory中提取通道目录名
     */
    private fun extractChannelDirectoryFromAccessInfo(): String? {
        val sharedDirectory = accessInfo.sharedDirectory
        if (sharedDirectory.isNullOrEmpty()) return null

        val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
        val matchResult = regex.find(sharedDirectory)
        return matchResult?.groupValues?.get(1)
    }

    companion object {
        /**
         * 从CosAccessToken创建CamPoolEntry
         * 使用当前用户的COS配置信息
         */
        fun fromCosAccessToken(
            context: android.content.Context,
            recipientId: String,
            token: org.thoughtcrime.securesms.tap.provider.cos.cos.CosAccessToken,
            type: Type
        ): CamPoolEntry {
            // 从COS配置存储获取当前配置
            val cosConfig = org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfigStorage.getConfig(context)

            val accessInfo = if (cosConfig != null) {
                // 使用真实的配置信息
                CosAccessInfo(
                    provider = cosConfig.provider.name,
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName,
                    accessKeyId = token.accessKeyId,
                    secretAccessKey = token.secretAccessKey,
                    sessionToken = token.sessionToken,
                    expireTime = token.expireTime,
                    sharedDirectory = "/outbox/"
                )
            } else {
                // 如果没有配置，使用默认值并记录警告
                org.signal.core.util.logging.Log.w("CamPoolEntry", "COS配置未找到，使用默认值")
                CosAccessInfo(
                    provider = "AWS", // 默认值
                    region = "us-east-1", // 默认值
                    bucketName = "default-bucket", // 默认值
                    accessKeyId = token.accessKeyId,
                    secretAccessKey = token.secretAccessKey,
                    sessionToken = token.sessionToken,
                    expireTime = token.expireTime,
                    sharedDirectory = "/outbox/"
                )
            }

            // 从accessInfo中提取通道目录名
            val channelDirectory = extractChannelDirectoryFromSharedDirectory(accessInfo.sharedDirectory)

            return CamPoolEntry(
                recipientId = recipientId,
                accessInfo = accessInfo,
                channelDirectory = channelDirectory
            )
        }

        /**
         * 从共享目录路径中提取通道目录名
         */
        private fun extractChannelDirectoryFromSharedDirectory(sharedDirectory: String?): String? {
            if (sharedDirectory.isNullOrEmpty()) return null

            val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
            val matchResult = regex.find(sharedDirectory)
            return matchResult?.groupValues?.get(1)
        }

        /**
         * 创建新的CamPoolEntry
         */
        fun create(
            recipientId: String,
            accessInfo: CosAccessInfo
        ): CamPoolEntry {
            val channelDirectory = extractChannelDirectoryFromSharedDirectory(accessInfo.sharedDirectory)

            return CamPoolEntry(
                recipientId = recipientId,
                accessInfo = accessInfo,
                channelDirectory = channelDirectory
            )
        }
    }

    /**
     * CAM条目类型
     */
    enum class Type {
        SHARED,   // 我分享给对方的
        RECEIVED  // 对方分享给我的
    }
}

/**
 * 轮询统计信息
 * @deprecated 使用SubAccountPoolManager的统计功能替代
 */
data class CamPoolStatistics(
    val totalSharedCams: Int = 0,
    val totalReceivedCams: Int = 0,
    val activeCams: Int = 0,
    val expiredCams: Int = 0,
    val errorCams: Int = 0
)
