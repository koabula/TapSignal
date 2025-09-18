package org.thoughtcrime.securesms.tap

/**
 * 传输通道状态枚举
 * 
 * 定义传输通道的各种状态，用于跟踪通道的生命周期。
 */
enum class TransportChannelStatus(
    val displayName: String,
    val description: String,
    val isActive: Boolean = false
) {
    /** 建立中 - 通道正在建立连接 */
    ESTABLISHING(
        displayName = "建立中",
        description = "正在建立传输通道连接",
        isActive = false
    ),
    
    /** 活跃 - 通道正常工作，可以传输消息 */
    ACTIVE(
        displayName = "活跃",
        description = "传输通道正常工作，可以传输消息",
        isActive = true
    ),
    
    /** 非活跃 - 通道暂时不活跃，但可以重新激活 */
    INACTIVE(
        displayName = "非活跃",
        description = "传输通道暂时不活跃，但可以重新激活",
        isActive = false
    ),
    
    /** 失败 - 通道连接失败或出现错误 */
    FAILED(
        displayName = "失败",
        description = "传输通道连接失败或出现错误",
        isActive = false
    ),
    
    /** 已关闭 - 通道已被主动关闭 */
    CLOSED(
        displayName = "已关闭",
        description = "传输通道已被主动关闭",
        isActive = false
    ),
    
    /** 暂停 - 通道因策略原因被暂停 */
    SUSPENDED(
        displayName = "暂停",
        description = "传输通道因策略原因被暂停",
        isActive = false
    ),
    
    /** 维护中 - 通道正在进行维护 */
    MAINTENANCE(
        displayName = "维护中",
        description = "传输通道正在进行维护",
        isActive = false
    );
    
    companion object {
        /**
         * 获取所有活跃状态
         */
        fun getActiveStates(): List<TransportChannelStatus> {
            return values().filter { it.isActive }
        }
        
        /**
         * 获取所有非活跃状态
         */
        fun getInactiveStates(): List<TransportChannelStatus> {
            return values().filter { !it.isActive }
        }
        
        /**
         * 检查状态是否可以转换到目标状态
         */
        fun canTransitionTo(from: TransportChannelStatus, to: TransportChannelStatus): Boolean {
            return when (from) {
                ESTABLISHING -> to in listOf(ACTIVE, FAILED, CLOSED)
                ACTIVE -> to in listOf(INACTIVE, FAILED, CLOSED, SUSPENDED, MAINTENANCE)
                INACTIVE -> to in listOf(ACTIVE, FAILED, CLOSED, SUSPENDED)
                FAILED -> to in listOf(ESTABLISHING, CLOSED)
                CLOSED -> false // 已关闭的通道不能转换到其他状态
                SUSPENDED -> to in listOf(ACTIVE, INACTIVE, CLOSED)
                MAINTENANCE -> to in listOf(ACTIVE, INACTIVE, FAILED, CLOSED)
            }
        }
    }
}

/**
 * 传输通道数据结构
 * 
 * 表示一个传输通道的完整信息，包括通道标识、状态、元数据等。
 * 每个通道代表与特定接收者通过特定传输服务建立的连接。
 */
data class TransportChannel(
    /** 通道唯一标识符 */
    val channelId: String,
    
    /** 接收者ID */
    val recipientId: String,
    
    /** 传输提供者类型 */
    val providerType: String,
    
    /** 传输元数据 */
    val metadata: TransportMetadata,
    
    /** 通道当前状态 */
    val status: TransportChannelStatus,
    
    /** 通道创建时间戳 */
    val createdAt: Long,
    
    /** 最后活跃时间戳 */
    val lastActiveAt: Long,
    
    /** 失败次数统计 */
    val failureCount: Int = 0,
    
    /** 成功次数统计 */
    val successCount: Int = 0,
    
    /** 最后错误信息 */
    val lastError: TransportError? = null,
    
    /** 通道配置参数 */
    val config: Map<String, Any> = emptyMap(),
    
    /** 通道优先级（1-10，数字越大优先级越高） */
    val priority: Int = 5
) {
    
    /**
     * 检查通道是否活跃
     */
    fun isActive(): Boolean = status.isActive
    
    /**
     * 检查通道是否已关闭
     */
    fun isClosed(): Boolean = status == TransportChannelStatus.CLOSED
    
    /**
     * 检查通道是否失败
     */
    fun isFailed(): Boolean = status == TransportChannelStatus.FAILED
    
    /**
     * 检查通道是否可用（活跃或非活跃但不是失败/关闭状态）
     */
    fun isAvailable(): Boolean = status in listOf(
        TransportChannelStatus.ACTIVE,
        TransportChannelStatus.INACTIVE,
        TransportChannelStatus.SUSPENDED
    )
    
    /**
     * 计算通道成功率
     */
    fun getSuccessRate(): Double {
        val total = successCount + failureCount
        return if (total > 0) successCount.toDouble() / total else 0.0
    }
    
    /**
     * 计算通道年龄（毫秒）
     */
    fun getAge(): Long = System.currentTimeMillis() - createdAt
    
    /**
     * 计算自上次活跃以来的时间（毫秒）
     */
    fun getTimeSinceLastActive(): Long = System.currentTimeMillis() - lastActiveAt
    
    /**
     * 检查通道是否过期（超过指定时间未活跃）
     */
    fun isExpired(timeoutMs: Long): Boolean = getTimeSinceLastActive() > timeoutMs
    
    /**
     * 更新通道状态
     */
    fun updateStatus(newStatus: TransportChannelStatus): TransportChannel {
        return if (TransportChannelStatus.canTransitionTo(status, newStatus)) {
            copy(
                status = newStatus,
                lastActiveAt = if (newStatus.isActive) System.currentTimeMillis() else lastActiveAt
            )
        } else {
            this // 不允许的状态转换，返回原状态
        }
    }
    
    /**
     * 记录成功操作
     */
    fun recordSuccess(): TransportChannel {
        return copy(
            successCount = successCount + 1,
            lastActiveAt = System.currentTimeMillis(),
            lastError = null
        )
    }
    
    /**
     * 记录失败操作
     */
    fun recordFailure(error: TransportError): TransportChannel {
        return copy(
            failureCount = failureCount + 1,
            lastError = error,
            status = if (failureCount + 1 >= getMaxFailureThreshold()) {
                TransportChannelStatus.FAILED
            } else {
                status
            }
        )
    }
    
    /**
     * 获取最大失败阈值
     */
    private fun getMaxFailureThreshold(): Int {
        return config["maxFailureThreshold"] as? Int ?: 10
    }
    
    /**
     * 转换为Map格式用于序列化
     */
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "channelId" to channelId,
            "recipientId" to recipientId,
            "providerType" to providerType,
            "metadata" to metadata.toMap(),
            "status" to status.name,
            "createdAt" to createdAt,
            "lastActiveAt" to lastActiveAt,
            "failureCount" to failureCount,
            "successCount" to successCount,
            "config" to config,
            "priority" to priority
        )
        
        lastError?.let { map["lastError"] = it.errorCode }
        
        return map
    }
    
    companion object {
        /**
         * 创建新的传输通道
         */
        fun create(
            channelId: String,
            recipientId: String,
            providerType: String,
            metadata: TransportMetadata,
            priority: Int = 5,
            config: Map<String, Any> = emptyMap()
        ): TransportChannel {
            val now = System.currentTimeMillis()
            return TransportChannel(
                channelId = channelId,
                recipientId = recipientId,
                providerType = providerType,
                metadata = metadata,
                status = TransportChannelStatus.ESTABLISHING,
                createdAt = now,
                lastActiveAt = now,
                priority = priority,
                config = config
            )
        }
        
        /**
         * 从Map数据创建传输通道
         */
        fun fromMap(data: Map<String, Any>, metadata: TransportMetadata): TransportChannel? {
            return try {
                val channelId = data["channelId"] as? String ?: return null
                val recipientId = data["recipientId"] as? String ?: return null
                val providerType = data["providerType"] as? String ?: return null
                val statusName = data["status"] as? String ?: return null
                val status = TransportChannelStatus.valueOf(statusName)
                val createdAt = (data["createdAt"] as? Number)?.toLong() ?: return null
                val lastActiveAt = (data["lastActiveAt"] as? Number)?.toLong() ?: return null
                val failureCount = (data["failureCount"] as? Number)?.toInt() ?: 0
                val successCount = (data["successCount"] as? Number)?.toInt() ?: 0
                val priority = (data["priority"] as? Number)?.toInt() ?: 5
                val config = data["config"] as? Map<String, Any> ?: emptyMap()
                
                val lastError = (data["lastError"] as? String)?.let { errorCode ->
                    TransportError.fromErrorCode(errorCode)
                }
                
                TransportChannel(
                    channelId = channelId,
                    recipientId = recipientId,
                    providerType = providerType,
                    metadata = metadata,
                    status = status,
                    createdAt = createdAt,
                    lastActiveAt = lastActiveAt,
                    failureCount = failureCount,
                    successCount = successCount,
                    lastError = lastError,
                    config = config,
                    priority = priority
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * 生成通道ID
         */
        fun generateChannelId(recipientId: String, providerType: String): String {
            val timestamp = System.currentTimeMillis()
            val hash = "${recipientId}_${providerType}_${timestamp}".hashCode()
            return "${providerType}_${recipientId}_${Math.abs(hash)}"
        }
    }
}

/**
 * 传输通道统计信息
 * 
 * 用于收集和展示通道的统计数据。
 */
data class TransportChannelStats(
    /** 总通道数 */
    val totalChannels: Int,
    
    /** 活跃通道数 */
    val activeChannels: Int,
    
    /** 失败通道数 */
    val failedChannels: Int,
    
    /** 平均成功率 */
    val averageSuccessRate: Double,
    
    /** 按提供者类型分组的统计 */
    val providerStats: Map<String, ProviderChannelStats>
) {
    
    /**
     * 计算活跃率
     */
    fun getActiveRate(): Double {
        return if (totalChannels > 0) activeChannels.toDouble() / totalChannels else 0.0
    }
    
    /**
     * 计算失败率
     */
    fun getFailureRate(): Double {
        return if (totalChannels > 0) failedChannels.toDouble() / totalChannels else 0.0
    }
}

/**
 * 提供者通道统计信息
 */
data class ProviderChannelStats(
    /** 提供者类型 */
    val providerType: String,
    
    /** 通道数量 */
    val channelCount: Int,
    
    /** 活跃通道数 */
    val activeCount: Int,
    
    /** 成功率 */
    val successRate: Double,
    
    /** 平均响应时间 */
    val averageResponseTime: Long
) 