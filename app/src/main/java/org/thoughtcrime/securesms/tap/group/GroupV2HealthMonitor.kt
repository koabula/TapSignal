package org.thoughtcrime.securesms.tap.group

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 群组 V2 模式健康监控器
 * 
 * 监控群组 V2 模式的健康状态，包括：
 * - 轮询失败次数
 * - Token 过期检测
 * - 通道连接状态
 * 
 * 当检测到异常情况时自动触发降级
 */
class GroupV2HealthMonitor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(GroupV2HealthMonitor::class.java)
        
        @Volatile
        private var INSTANCE: GroupV2HealthMonitor? = null
        
        @JvmStatic
        fun getInstance(context: Context): GroupV2HealthMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupV2HealthMonitor(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
        
        // 健康检查阈值
        private const val MAX_CONSECUTIVE_POLLING_FAILURES = 5  // 最大连续轮询失败次数
        private const val MAX_CHANNEL_FAILURES = 3              // 最大通道失败次数
        private const val TOKEN_EXPIRY_WARNING_DAYS = 7         // Token 过期警告提前天数
        
        // 自动降级开关（可配置）
        private const val ENABLE_AUTO_DEGRADATION = true
    }
    
    // 群组轮询失败计数器
    private val pollingFailureCount = ConcurrentHashMap<String, AtomicInteger>()
    
    // 群组通道失败计数器
    private val channelFailureCount = ConcurrentHashMap<String, AtomicInteger>()
    
    // 最后一次成功轮询时间
    private val lastSuccessfulPolling = ConcurrentHashMap<String, Long>()
    
    // 协程作用域
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 记录轮询失败
     * 
     * @param groupId 群组 ID
     * @param memberAci 成员 ACI
     * @param reason 失败原因
     */
    fun recordPollingFailure(groupId: String, memberAci: String, reason: String) {
        try {
            val key = "$groupId:$memberAci"
            val count = pollingFailureCount.computeIfAbsent(key) { AtomicInteger(0) }
            val currentCount = count.incrementAndGet()
            
            Log.w(TAG, "轮询失败记录: groupId=$groupId, member=$memberAci, count=$currentCount, reason=$reason")
            
            // 检查是否超过阈值
            if (currentCount >= MAX_CONSECUTIVE_POLLING_FAILURES) {
                Log.e(TAG, "轮询连续失败超过阈值: groupId=$groupId, count=$currentCount")
                
                if (ENABLE_AUTO_DEGRADATION) {
                    triggerDegradation(groupId, "连续轮询失败 $currentCount 次")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录轮询失败时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 记录轮询成功
     * 
     * @param groupId 群组 ID
     * @param memberAci 成员 ACI
     */
    fun recordPollingSuccess(groupId: String, memberAci: String) {
        try {
            val key = "$groupId:$memberAci"
            
            // 重置失败计数
            pollingFailureCount[key]?.set(0)
            
            // 更新最后成功时间
            lastSuccessfulPolling[key] = System.currentTimeMillis()
            
            Log.d(TAG, "轮询成功记录: groupId=$groupId, member=$memberAci")
        } catch (e: Exception) {
            Log.e(TAG, "记录轮询成功时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 记录通道失败
     * 
     * @param groupId 群组 ID
     * @param reason 失败原因
     */
    fun recordChannelFailure(groupId: String, reason: String) {
        try {
            val count = channelFailureCount.computeIfAbsent(groupId) { AtomicInteger(0) }
            val currentCount = count.incrementAndGet()
            
            Log.w(TAG, "通道失败记录: groupId=$groupId, count=$currentCount, reason=$reason")
            
            // 检查是否超过阈值
            if (currentCount >= MAX_CHANNEL_FAILURES) {
                Log.e(TAG, "通道失败超过阈值: groupId=$groupId, count=$currentCount")
                
                if (ENABLE_AUTO_DEGRADATION) {
                    triggerDegradation(groupId, "通道连续失败 $currentCount 次")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录通道失败时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 记录通道成功
     * 
     * @param groupId 群组 ID
     */
    fun recordChannelSuccess(groupId: String) {
        try {
            // 重置失败计数
            channelFailureCount[groupId]?.set(0)
            
            Log.d(TAG, "通道成功记录: groupId=$groupId")
        } catch (e: Exception) {
            Log.e(TAG, "记录通道成功时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 检查 Token 是否即将过期
     * 
     * @param groupId 群组 ID
     * @param expiryTimestamp Token 过期时间戳
     */
    fun checkTokenExpiry(groupId: String, expiryTimestamp: Long) {
        try {
            val currentTime = System.currentTimeMillis()
            val daysUntilExpiry = (expiryTimestamp - currentTime) / (24 * 60 * 60 * 1000)
            
            if (daysUntilExpiry <= 0) {
                // Token 已过期
                Log.e(TAG, "Token 已过期: groupId=$groupId")
                
                if (ENABLE_AUTO_DEGRADATION) {
                    triggerDegradation(groupId, "Token 已过期")
                }
            } else if (daysUntilExpiry <= TOKEN_EXPIRY_WARNING_DAYS) {
                // Token 即将过期
                Log.w(TAG, "Token 即将过期: groupId=$groupId, 剩余天数=$daysUntilExpiry")
                
                // TODO: 可以在这里触发 Token 刷新逻辑
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查 Token 过期时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 触发异常降级
     * 
     * @param groupId 群组 ID
     * @param reason 降级原因
     */
    private fun triggerDegradation(groupId: String, reason: String) {
        monitorScope.launch {
            try {
                Log.w(TAG, "触发群组异常降级: groupId=$groupId, reason=$reason")
                
                val groupManager = GroupTransportManager.getInstance(context)
                val result = groupManager.degradeV2ModeOnError(groupId, reason)
                
                when (result) {
                    is GroupOperationResult.Success -> {
                        Log.i(TAG, "异常降级成功: groupId=$groupId")
                        
                        // 清理监控数据
                        cleanup(groupId)
                    }
                    is GroupOperationResult.Failed -> {
                        Log.e(TAG, "异常降级失败: groupId=$groupId, error=${result.message}")
                    }
                    else -> {
                        Log.w(TAG, "异常降级结果未知: groupId=$groupId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "触发异常降级时异常: groupId=$groupId", e)
            }
        }
    }
    
    /**
     * 清理指定群组的监控数据
     * 
     * @param groupId 群组 ID
     */
    fun cleanup(groupId: String) {
        try {
            // 清理轮询失败计数
            pollingFailureCount.keys.removeAll { it.startsWith("$groupId:") }
            
            // 清理通道失败计数
            channelFailureCount.remove(groupId)
            
            // 清理最后成功时间
            lastSuccessfulPolling.keys.removeAll { it.startsWith("$groupId:") }
            
            Log.d(TAG, "已清理群组监控数据: groupId=$groupId")
        } catch (e: Exception) {
            Log.e(TAG, "清理监控数据时异常: groupId=$groupId", e)
        }
    }
    
    /**
     * 获取群组健康状态
     * 
     * @param groupId 群组 ID
     * @return 健康状态报告
     */
    fun getHealthStatus(groupId: String): GroupHealthStatus {
        return try {
            val channelFailures = channelFailureCount[groupId]?.get() ?: 0
            
            // 统计轮询失败
            val pollingFailures = pollingFailureCount.entries
                .filter { it.key.startsWith("$groupId:") }
                .sumOf { it.value.get() }
            
            // 计算最后成功时间
            val lastSuccess = lastSuccessfulPolling.entries
                .filter { it.key.startsWith("$groupId:") }
                .maxOfOrNull { it.value }
            
            val isHealthy = channelFailures < MAX_CHANNEL_FAILURES && 
                           pollingFailures < MAX_CONSECUTIVE_POLLING_FAILURES
            
            GroupHealthStatus(
                groupId = groupId,
                isHealthy = isHealthy,
                channelFailureCount = channelFailures,
                pollingFailureCount = pollingFailures,
                lastSuccessfulPollingTime = lastSuccess
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取健康状态时异常: groupId=$groupId", e)
            GroupHealthStatus(
                groupId = groupId,
                isHealthy = false,
                channelFailureCount = 0,
                pollingFailureCount = 0,
                lastSuccessfulPollingTime = null
            )
        }
    }
}

/**
 * 群组健康状态数据类
 */
data class GroupHealthStatus(
    val groupId: String,
    val isHealthy: Boolean,
    val channelFailureCount: Int,
    val pollingFailureCount: Int,
    val lastSuccessfulPollingTime: Long?
)

