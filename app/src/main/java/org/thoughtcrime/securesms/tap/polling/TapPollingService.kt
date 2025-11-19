package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.database.TransportPollingStateTable
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.TransportChannel

/**
 * Tap轮询服务 - 简化版本 (已废弃)
 * 
 * 此服务已被废弃，V2模式下完全依赖推送通知 (Push Notification)。
 * 保留此类是为了兼容性，但所有操作均为无操作 (No-op)。
 */
class TapPollingService(private val context: Context) {
    
    companion object {
        private const val TAG = "TapPollingService"
        private const val ENABLE_POLLING = false
        
        @Volatile
        private var INSTANCE: TapPollingService? = null
        
        @JvmStatic
        fun getInstance(context: Context): TapPollingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapPollingService(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isPollingEnabled(): Boolean = ENABLE_POLLING
    }
    
    fun startPolling(): Boolean {
        Log.i(TAG, "轮询服务已废弃，startPolling 为无操作")
        return false
    }
    
    fun stopPolling() {
        Log.i(TAG, "轮询服务已废弃，stopPolling 为无操作")
    }
    
    fun addGroupPollingTargets(
        groupId: String,
        memberMetadatas: Map<String, TransportMetadata>
    ): Int {
        return memberMetadatas.size
    }
    
    fun removeGroupPollingTargets(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Int {
        return memberAcis.size
    }
    
    fun addPollingTarget(recipientId: String, metadata: TransportMetadata, channel: TransportChannel? = null): Boolean {
        return true
    }
    
    fun removePollingTarget(recipientId: String, providerType: String): Boolean {
        return true
    }
    
    fun getPollingState(recipientId: String, providerType: String): TransportPollingStateTable.PollingState? {
        return null
    }
    
    fun getPollingStatus(): TapPollingStatus {
        return TapPollingStatus(
            isRunning = false,
            activePollingTargets = 0,
            totalPollingTargets = 0,
            averagePollingInterval = 0,
            lastPollingTime = 0,
            pollingStatistics = createEmptyStatistics(),
            resourceUsage = PollingResourceUsage(0, 0, 0),
            systemStartTime = System.currentTimeMillis()
        )
    }
    
    fun getCurrentStatistics(): TapPollingStatistics {
        return createEmptyStatistics()
    }
    
    fun adjustPollingInterval(recipientId: String, changeType: IntervalChangeType): Boolean {
        return false
    }
    
    fun adjustGlobalPolling(changeType: IntervalChangeType) {
        // No-op
    }

    private fun createEmptyStatistics(): TapPollingStatistics {
        return TapPollingStatistics(
            totalPolls = 0,
            successfulPolls = 0,
            failedPolls = 0,
            messagesFound = 0,
            averageResponseTime = 0,
            providerStatistics = emptyMap(),
            activityLevelStats = emptyMap(),
            lastHourStats = RecentPollingStats(3600000L, 0, 0, 0, 0, 0.0, 0.0),
            last24HourStats = RecentPollingStats(86400000L, 0, 0, 0, 0, 0.0, 0.0)
        )
    }
}

/**
 * 间隔变化类型
 */
enum class IntervalChangeType {
    INCREASE,       // 增加间隔（降低频率）
    DECREASE,       // 减少间隔（提高频率）
    RESET,          // 重置到默认间隔
    ERROR_BACKOFF,  // 错误退避
    LOW_POWER,      // 低电量模式
    REEVALUATE      // 重新评估
}
