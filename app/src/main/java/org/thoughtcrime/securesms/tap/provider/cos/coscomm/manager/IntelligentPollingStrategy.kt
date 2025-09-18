package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
// CamPoolManager已删除，使用SubAccountPoolManager
import java.util.concurrent.TimeUnit

/**
 * 智能轮询策略管理器
 * 根据通道活跃度动态调整轮询频率，优化性能和用户体验
 */
class IntelligentPollingStrategy(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(IntelligentPollingStrategy::class.java)
        
        // 轮询间隔常量
        private const val ACTIVE_POLLING_INTERVAL = 5000L      // 活跃对话: 5秒
        private const val INACTIVE_POLLING_INTERVAL = 30000L   // 非活跃对话: 30秒  
        private const val BACKGROUND_POLLING_INTERVAL = 60000L // 后台模式: 60秒
        private const val SUSPENDED_POLLING_INTERVAL = 300000L // 暂停模式: 5分钟
        
        // 活跃度判断阈值
        private const val ACTIVE_THRESHOLD_MINUTES = 5L        // 5分钟内有活动视为活跃
        private const val INACTIVE_THRESHOLD_HOURS = 1L        // 1小时内有活动视为非活跃
        
        // 错误处理常量 - 放宽限制，提高容错性
        private const val MAX_CONSECUTIVE_ERRORS = 10          // 最大连续错误次数（从5增加到10）
        private const val ERROR_BACKOFF_BASE = 2000L          // 错误退避基础时间: 2秒
        private const val MAX_ERROR_BACKOFF = 180000L         // 最大错误退避时间: 3分钟（从5分钟减少到3分钟）
    }
    
    private val cosChannelManager = CosChannelManager.getInstance(context)
    
    /**
     * 计算指定接收者的轮询间隔
     * @param recipientId 接收者ID
     * @param camEntry CAM Pool条目
     * @return 轮询间隔(毫秒)
     */
    fun calculatePollingInterval(recipientId: String, camEntry: CamPoolEntry): Long {
        Log.d(TAG, "计算轮询间隔: recipientId=$recipientId")
        
        // 检查是否有连续错误，应用错误退避策略
        if (camEntry.pollingErrors > 0) {
            val backoffInterval = calculateErrorBackoff(camEntry.pollingErrors)
            Log.w(TAG, "应用错误退避策略: recipientId=$recipientId, errors=${camEntry.pollingErrors}, interval=${backoffInterval}ms")
            return backoffInterval
        }
        
        // 检查CAM凭证是否即将过期
        if (isTokenNearExpiry(camEntry)) {
            Log.w(TAG, "CAM凭证即将过期，降低轮询频率: recipientId=$recipientId")
            return BACKGROUND_POLLING_INTERVAL
        }
        
        // 获取通道信息判断活跃度
        val channel = cosChannelManager.getChannel(recipientId)
        if (channel == null) {
            Log.w(TAG, "未找到通道信息，使用默认轮询间隔: recipientId=$recipientId")
            return INACTIVE_POLLING_INTERVAL
        }
        
        // 根据通道活跃度计算轮询间隔
        val activityLevel = calculateActivityLevel(channel)
        val interval = when (activityLevel) {
            ActivityLevel.ACTIVE -> ACTIVE_POLLING_INTERVAL
            ActivityLevel.INACTIVE -> INACTIVE_POLLING_INTERVAL
            ActivityLevel.BACKGROUND -> BACKGROUND_POLLING_INTERVAL
            ActivityLevel.SUSPENDED -> SUSPENDED_POLLING_INTERVAL
        }
        
        Log.d(TAG, "轮询间隔计算完成: recipientId=$recipientId, level=$activityLevel, interval=${interval}ms")
        return interval
    }
    
    /**
     * 判断是否应该跳过轮询
     * @param recipientId 接收者ID
     * @param camEntry CAM Pool条目
     * @return 是否跳过轮询
     */
    fun shouldSkipPolling(recipientId: String, camEntry: CamPoolEntry): Boolean {
        // 检查CAM条目是否活跃
        if (!camEntry.isActive) {
            Log.d(TAG, "CAM条目未激活，跳过轮询: recipientId=$recipientId")
            return true
        }
        
        // 检查CAM凭证是否已过期
        if (camEntry.accessInfo.isExpired()) {
            Log.w(TAG, "CAM凭证已过期，跳过轮询: recipientId=$recipientId")
            return true
        }
        
        // 检查连续错误次数是否超过阈值 - 改进逻辑，不完全暂停
        if (camEntry.pollingErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.w(TAG, "连续错误次数较多，降低轮询频率: recipientId=$recipientId, errors=${camEntry.pollingErrors}")
            // 不再完全暂停轮询，而是通过返回false让系统降低轮询频率
            // 这样可以在网络恢复时自动恢复轮询
        }
        
        // 检查通道状态
        val channel = cosChannelManager.getChannel(recipientId)
        if (channel?.status != ChannelStatus.ACTIVE) {
            Log.d(TAG, "通道状态非活跃，跳过轮询: recipientId=$recipientId, status=${channel?.status}")
            return true
        }
        
        return false
    }
    
    /**
     * 计算错误退避时间
     * @param errorCount 连续错误次数
     * @return 退避时间(毫秒)
     */
    private fun calculateErrorBackoff(errorCount: Int): Long {
        // 指数退避算法: base * 2^errorCount
        val backoffMultiplier = Math.pow(2.0, errorCount.toDouble()).toLong()
        val backoffTime = ERROR_BACKOFF_BASE * backoffMultiplier
        
        // 限制最大退避时间
        return Math.min(backoffTime, MAX_ERROR_BACKOFF)
    }
    
    /**
     * 检查CAM凭证是否即将过期
     * @param camEntry CAM Pool条目
     * @return 是否即将过期
     */
    private fun isTokenNearExpiry(camEntry: CamPoolEntry): Boolean {
        val currentTime = System.currentTimeMillis()
        val expiryTime = camEntry.accessInfo.expireTime
        val timeToExpiry = expiryTime - currentTime
        
        // 如果24小时内过期，视为即将过期
        return timeToExpiry < TimeUnit.HOURS.toMillis(24)
    }
    
    /**
     * 计算通道活跃度级别
     * @param channel COS通道
     * @return 活跃度级别
     */
    private fun calculateActivityLevel(channel: CosChannel): ActivityLevel {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastActivity = currentTime - channel.lastActivity
        
        return when {
            // 5分钟内有活动 - 活跃
            timeSinceLastActivity < TimeUnit.MINUTES.toMillis(ACTIVE_THRESHOLD_MINUTES) -> {
                ActivityLevel.ACTIVE
            }
            // 1小时内有活动 - 非活跃
            timeSinceLastActivity < TimeUnit.HOURS.toMillis(INACTIVE_THRESHOLD_HOURS) -> {
                ActivityLevel.INACTIVE
            }
            // 24小时内有活动 - 后台
            timeSinceLastActivity < TimeUnit.HOURS.toMillis(24) -> {
                ActivityLevel.BACKGROUND
            }
            // 超过24小时无活动 - 暂停
            else -> {
                ActivityLevel.SUSPENDED
            }
        }
    }
    
    /**
     * 活跃度级别枚举
     */
    enum class ActivityLevel {
        ACTIVE,      // 活跃 - 5秒轮询
        INACTIVE,    // 非活跃 - 30秒轮询
        BACKGROUND,  // 后台 - 60秒轮询
        SUSPENDED    // 暂停 - 5分钟轮询
    }
    
    /**
     * 获取轮询策略统计信息
     * @return 策略统计信息
     */
    fun getPollingStatistics(): PollingStatistics {
        val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
        val allEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()

        var activeCount = 0
        var inactiveCount = 0
        var backgroundCount = 0
        var suspendedCount = 0
        var errorCount = 0

        allEntries.forEach { entry ->
            if (entry.pollingErrors > 0) {
                errorCount++
            } else {
                val channel = cosChannelManager.getChannel(entry.recipientId)
                if (channel != null) {
                    when (calculateActivityLevel(channel)) {
                        ActivityLevel.ACTIVE -> activeCount++
                        ActivityLevel.INACTIVE -> inactiveCount++
                        ActivityLevel.BACKGROUND -> backgroundCount++
                        ActivityLevel.SUSPENDED -> suspendedCount++
                    }
                }
            }
        }

        return PollingStatistics(
            totalEntries = allEntries.size,
            activePolling = activeCount,
            inactivePolling = inactiveCount,
            backgroundPolling = backgroundCount,
            suspendedPolling = suspendedCount,
            errorPolling = errorCount
        )
    }
    
    /**
     * 轮询统计信息数据类
     */
    data class PollingStatistics(
        val totalEntries: Int,
        val activePolling: Int,
        val inactivePolling: Int,
        val backgroundPolling: Int,
        val suspendedPolling: Int,
        val errorPolling: Int
    )
}
