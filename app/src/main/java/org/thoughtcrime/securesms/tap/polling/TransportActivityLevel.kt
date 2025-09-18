package org.thoughtcrime.securesms.tap.polling

/**
 * 传输活跃度级别
 * 
 * 用于智能轮询策略，根据通信频率和用户行为确定轮询间隔。
 * 活跃度越高，轮询频率越高，反之亦然。
 */
enum class TransportActivityLevel(
    val levelName: String,
    val description: String,
    val baseIntervalMs: Long,
    val priority: Int
) {
    /**
     * 活跃级别 - 最近有频繁通信活动
     * 适用于：5分钟内有消息收发的对话
     */
    ACTIVE(
        levelName = "活跃",
        description = "最近有频繁通信活动，快速轮询",
        baseIntervalMs = 5000L,  // 5秒
        priority = 1
    ),
    
    /**
     * 非活跃级别 - 有定期通信但不频繁
     * 适用于：5分钟-1小时内有消息收发的对话
     */
    INACTIVE(
        levelName = "非活跃", 
        description = "有定期通信但不频繁，标准轮询",
        baseIntervalMs = 30000L, // 30秒
        priority = 2
    ),
    
    /**
     * 后台级别 - 偶尔有通信
     * 适用于：1小时-24小时内有消息收发的对话
     */
    BACKGROUND(
        levelName = "后台",
        description = "偶尔有通信，慢速轮询", 
        baseIntervalMs = 60000L, // 1分钟
        priority = 3
    ),
    
    /**
     * 暂停级别 - 很少通信
     * 适用于：24小时-7天内有消息收发的对话
     */
    SUSPENDED(
        levelName = "暂停",
        description = "很少通信，超慢轮询",
        baseIntervalMs = 300000L, // 5分钟
        priority = 4
    ),
    
    /**
     * 休眠级别 - 长期无通信
     * 适用于：7天以上未有消息收发的对话
     */
    DORMANT(
        levelName = "休眠",
        description = "长期无通信，极慢轮询或停止",
        baseIntervalMs = 600000L, // 10分钟
        priority = 5
    );
    
    companion object {
        /**
         * 根据最后活动时间计算活跃度级别
         * @param lastActivityTimeMs 最后活动时间戳（毫秒）
         * @param currentTimeMs 当前时间戳（毫秒）
         * @return 对应的活跃度级别
         */
        fun calculateFromLastActivity(
            lastActivityTimeMs: Long, 
            currentTimeMs: Long = System.currentTimeMillis()
        ): TransportActivityLevel {
            val timeDiffMs = currentTimeMs - lastActivityTimeMs
            
            return when {
                timeDiffMs <= 5 * 60 * 1000L -> ACTIVE        // 5分钟内
                timeDiffMs <= 60 * 60 * 1000L -> INACTIVE      // 1小时内
                timeDiffMs <= 24 * 60 * 60 * 1000L -> BACKGROUND // 24小时内
                timeDiffMs <= 7 * 24 * 60 * 60 * 1000L -> SUSPENDED // 7天内
                else -> DORMANT                                 // 7天以上
            }
        }
        
        /**
         * 根据消息频率计算活跃度级别
         * @param messageCountInLast24Hours 最近24小时内的消息数量
         * @param messageCountInLastWeek 最近一周内的消息数量
         * @return 对应的活跃度级别
         */
        fun calculateFromMessageFrequency(
            messageCountInLast24Hours: Int,
            messageCountInLastWeek: Int
        ): TransportActivityLevel {
            return when {
                messageCountInLast24Hours >= 10 -> ACTIVE
                messageCountInLast24Hours >= 3 -> INACTIVE
                messageCountInLast24Hours >= 1 -> BACKGROUND
                messageCountInLastWeek >= 3 -> SUSPENDED
                else -> DORMANT
            }
        }
        
        /**
         * 综合计算活跃度级别
         * 结合时间和频率两个维度
         */
        fun calculateComprehensive(
            lastActivityTimeMs: Long,
            messageCountInLast24Hours: Int,
            messageCountInLastWeek: Int,
            currentTimeMs: Long = System.currentTimeMillis()
        ): TransportActivityLevel {
            val timeBasedLevel = calculateFromLastActivity(lastActivityTimeMs, currentTimeMs)
            val frequencyBasedLevel = calculateFromMessageFrequency(
                messageCountInLast24Hours, 
                messageCountInLastWeek
            )
            
            // 取较高的活跃度级别（优先级较低的数字）
            return if (timeBasedLevel.priority <= frequencyBasedLevel.priority) {
                timeBasedLevel
            } else {
                frequencyBasedLevel
            }
        }
    }
    
    /**
     * 检查是否比指定级别更活跃
     */
    fun isMoreActiveThan(other: TransportActivityLevel): Boolean {
        return this.priority < other.priority
    }
    
    /**
     * 检查是否比指定级别更不活跃
     */
    fun isLessActiveThan(other: TransportActivityLevel): Boolean {
        return this.priority > other.priority
    }
    
    /**
     * 获取下一个更不活跃的级别
     */
    fun getNextLessActiveLevel(): TransportActivityLevel? {
        return values().find { it.priority == this.priority + 1 }
    }
    
    /**
     * 获取下一个更活跃的级别
     */
    fun getNextMoreActiveLevel(): TransportActivityLevel? {
        return values().find { it.priority == this.priority - 1 }
    }
} 