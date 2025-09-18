package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 动态轮询调度器
 * 
 * 负责动态调整轮询调度，根据系统状态、用户行为、网络条件等因素
 * 实时优化轮询策略，提供智能化的调度管理。
 * 
 * 主要功能：
 * 1. 动态调整轮询间隔
 * 2. 批量优化轮询调度
 * 3. 根据系统资源调整并发度
 * 4. 智能暂停和恢复轮询
 * 5. 性能监控和统计
 */
class DynamicPollingScheduler(private val context: Context) {
    
    companion object {
        private const val TAG = "DynamicPollingScheduler"
        
        // 调度配置常量
        private const val DEFAULT_CORE_POOL_SIZE = 2
        private const val DEFAULT_MAX_POOL_SIZE = 8
        private const val SCHEDULER_CHECK_INTERVAL_MS = 30000L // 30秒检查一次
        
        // 系统负载阈值
        private const val HIGH_CPU_THRESHOLD = 0.8      // CPU使用率80%
        private const val HIGH_MEMORY_THRESHOLD = 0.9   // 内存使用率90%
        private const val LOW_BATTERY_THRESHOLD = 15    // 电量15%
        
        // 调整触发阈值
        private const val MIN_ADJUSTMENT_INTERVAL_MS = 5000L  // 最小调整间隔5秒
        private const val MAX_CONSECUTIVE_EMPTY_POLLS = 10    // 最大连续空轮询次数
    }
    
    // 核心组件
    private val pollingStrategy = TapIntelligentPollingStrategy(context)
    
    // 线程池和调度器
    private var schedulerExecutor: ScheduledExecutorService? = null
    private var monitoringTask: ScheduledFuture<*>? = null
    
    // 调度状态管理
    private val schedulingLock = ReentrantReadWriteLock()
    private var isRunning = false
    private var currentPoolSize = DEFAULT_CORE_POOL_SIZE
    
    // 调度统计
    private val adjustmentCount = AtomicInteger(0)
    private val lastAdjustmentTime = ConcurrentHashMap<String, Long>()
    private val consecutiveEmptyPolls = ConcurrentHashMap<String, Int>()
    
    // 系统状态缓存
    private var lastSystemLoad: SystemLoadInfo? = null
    private var lastNetworkQuality: NetworkQuality = NetworkQuality.UNKNOWN
    
    /**
     * 启动动态调度器
     */
    fun start(): Boolean {
        return schedulingLock.write {
            try {
                if (isRunning) {
                    Log.w(TAG, "动态调度器已经运行")
                    return@write true
                }
                
                Log.i(TAG, "启动动态轮询调度器...")
                
                // 创建调度线程池
                schedulerExecutor = Executors.newScheduledThreadPool(
                    currentPoolSize,
                    { r -> Thread(r, "TapPolling-Scheduler") }
                )
                
                // 启动系统监控任务
                startSystemMonitoring()
                
                isRunning = true
                Log.i(TAG, "动态轮询调度器启动成功")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "启动动态调度器失败", e)
                cleanup()
                false
            }
        }
    }
    
    /**
     * 停止动态调度器
     */
    fun stop() {
        schedulingLock.write {
            try {
                if (!isRunning) {
                    Log.w(TAG, "动态调度器未运行")
                    return@write
                }
                
                Log.i(TAG, "停止动态轮询调度器...")
                
                isRunning = false
                cleanup()
                
                Log.i(TAG, "动态轮询调度器已停止")
                
            } catch (e: Exception) {
                Log.e(TAG, "停止动态调度器时发生错误", e)
            }
        }
    }
    
    /**
     * 动态调整轮询调度
     * 
     * @param recipientId 接收者ID
     * @param trigger 调整触发器
     */
    fun adjustPollingSchedule(recipientId: String, trigger: PollingAdjustTrigger) {
        if (!isRunning) {
            Log.w(TAG, "调度器未运行，跳过调整")
            return
        }
        
        try {
            val currentTime = System.currentTimeMillis()
            val lastAdjustTime = lastAdjustmentTime[recipientId] ?: 0L
            
            // 防止频繁调整
            if (currentTime - lastAdjustTime < MIN_ADJUSTMENT_INTERVAL_MS) {
                Log.d(TAG, "调整间隔太短，跳过调整: recipient=$recipientId")
                return
            }
            
            Log.d(TAG, "动态调整轮询调度: recipient=$recipientId, trigger=$trigger")
            
            when (trigger) {
                PollingAdjustTrigger.MESSAGE_RECEIVED -> handleMessageReceived(recipientId)
                PollingAdjustTrigger.MESSAGE_SENT -> handleMessageSent(recipientId) 
                PollingAdjustTrigger.USER_ACTIVE -> handleUserActive(recipientId)
                PollingAdjustTrigger.CONSECUTIVE_EMPTY -> handleConsecutiveEmpty(recipientId)
                PollingAdjustTrigger.ERROR_OCCURRED -> handleErrorOccurred(recipientId)
                PollingAdjustTrigger.TOKEN_REFRESH -> handleTokenRefresh(recipientId)
                PollingAdjustTrigger.APP_BACKGROUND -> handleAppBackground()
                PollingAdjustTrigger.NETWORK_CHANGE -> handleNetworkChange()
            }
            
            lastAdjustmentTime[recipientId] = currentTime
            adjustmentCount.incrementAndGet()
            
        } catch (e: Exception) {
            Log.e(TAG, "调整轮询调度时发生错误", e)
        }
    }
    
    /**
     * 批量优化轮询调度
     * 
     * 分析所有轮询任务，进行批量优化
     */
    fun optimizePollingSchedules() {
        if (!isRunning) return
        
        try {
            Log.d(TAG, "开始批量优化轮询调度...")
            
            // 获取系统当前状态
            val systemLoad = getCurrentSystemLoad()
            val networkQuality = getCurrentNetworkQuality()
            
            // 缓存系统状态
            lastSystemLoad = systemLoad
            lastNetworkQuality = networkQuality
            
            // 根据系统状态调整全局策略
            adjustGlobalPollingStrategy(systemLoad, networkQuality)
            
            // 清理过期的调整记录
            cleanupOldAdjustmentRecords()
            
            Log.d(TAG, "批量优化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "批量优化轮询调度时发生错误", e)
        }
    }
    
    /**
     * 根据系统资源调整并发度
     * 
     * @param systemLoad 系统负载信息
     */
    fun adjustConcurrency(systemLoad: SystemLoadInfo) {
        if (!isRunning) return
        
        try {
            val newPoolSize = calculateOptimalPoolSize(systemLoad)
            
            if (newPoolSize != currentPoolSize) {
                Log.i(TAG, "调整线程池大小: $currentPoolSize -> $newPoolSize")
                
                // 重新创建线程池（简化实现）
                val oldExecutor = schedulerExecutor
                schedulerExecutor = Executors.newScheduledThreadPool(
                    newPoolSize,
                    { r -> Thread(r, "TapPolling-Scheduler") }
                )
                
                currentPoolSize = newPoolSize
                
                // 优雅关闭旧线程池
                oldExecutor?.let { executor ->
                    executor.shutdown()
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                }
                
                // 重新启动系统监控
                startSystemMonitoring()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "调整并发度时发生错误", e)
        }
    }
    
    /**
     * 获取调度器状态
     */
    fun getSchedulerStatus(): DynamicSchedulerStatus {
        return schedulingLock.read {
            DynamicSchedulerStatus(
                isRunning = isRunning,
                currentPoolSize = currentPoolSize,
                adjustmentCount = adjustmentCount.get(),
                activeAdjustments = lastAdjustmentTime.size,
                lastSystemLoad = lastSystemLoad,
                lastNetworkQuality = lastNetworkQuality
            )
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 启动系统监控任务
     */
    private fun startSystemMonitoring() {
        monitoringTask?.cancel(false)
        
        monitoringTask = schedulerExecutor?.scheduleWithFixedDelay({
            try {
                optimizePollingSchedules()
            } catch (e: Exception) {
                Log.e(TAG, "系统监控任务执行失败", e)
            }
        }, SCHEDULER_CHECK_INTERVAL_MS, SCHEDULER_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        monitoringTask?.cancel(false)
        monitoringTask = null
        
        schedulerExecutor?.shutdown()
        try {
            if (schedulerExecutor?.awaitTermination(5, TimeUnit.SECONDS) == false) {
                schedulerExecutor?.shutdownNow()
            }
        } catch (e: InterruptedException) {
            schedulerExecutor?.shutdownNow()
            Thread.currentThread().interrupt()
        }
        schedulerExecutor = null
        
        lastAdjustmentTime.clear()
        consecutiveEmptyPolls.clear()
    }
    
    /**
     * 处理消息接收事件
     */
    private fun handleMessageReceived(recipientId: String) {
        Log.d(TAG, "处理消息接收事件: recipient=$recipientId")
        
        // 重置连续空轮询计数
        consecutiveEmptyPolls[recipientId] = 0
        
        // 提高轮询频率（通过降低间隔）
        // 实际实现需要与TapPollingService集成
        notifyPollingIntervalChange(recipientId, IntervalChangeType.DECREASE)
    }
    
    /**
     * 处理消息发送事件
     */
    private fun handleMessageSent(recipientId: String) {
        Log.d(TAG, "处理消息发送事件: recipient=$recipientId")
        
        // 发送消息后可能有回复，提高轮询频率
        notifyPollingIntervalChange(recipientId, IntervalChangeType.DECREASE)
    }
    
    /**
     * 处理用户活跃事件
     */
    private fun handleUserActive(recipientId: String) {
        Log.d(TAG, "处理用户活跃事件: recipient=$recipientId")
        
        // 用户活跃时提高轮询频率
        notifyPollingIntervalChange(recipientId, IntervalChangeType.DECREASE)
    }
    
    /**
     * 处理连续空轮询事件
     */
    private fun handleConsecutiveEmpty(recipientId: String) {
        val emptyCount = consecutiveEmptyPolls.getOrDefault(recipientId, 0) + 1
        consecutiveEmptyPolls[recipientId] = emptyCount
        
        Log.d(TAG, "处理连续空轮询事件: recipient=$recipientId, count=$emptyCount")
        
        // 连续空轮询达到阈值时降低频率
        if (emptyCount >= MAX_CONSECUTIVE_EMPTY_POLLS) {
            notifyPollingIntervalChange(recipientId, IntervalChangeType.INCREASE)
            consecutiveEmptyPolls[recipientId] = 0 // 重置计数
        }
    }
    
    /**
     * 处理错误发生事件
     */
    private fun handleErrorOccurred(recipientId: String) {
        Log.d(TAG, "处理错误发生事件: recipient=$recipientId")
        
        // 发生错误时应用退避策略
        notifyPollingIntervalChange(recipientId, IntervalChangeType.ERROR_BACKOFF)
    }
    
    /**
     * 处理Token刷新事件
     */
    private fun handleTokenRefresh(recipientId: String) {
        Log.d(TAG, "处理Token刷新事件: recipient=$recipientId")
        
        // Token刷新后恢复正常轮询
        notifyPollingIntervalChange(recipientId, IntervalChangeType.RESET)
    }
    
    /**
     * 处理应用进入后台事件
     */
    private fun handleAppBackground() {
        Log.d(TAG, "处理应用后台事件")
        
        // 应用进入后台时降低所有轮询频率
        notifyGlobalPollingChange(IntervalChangeType.INCREASE)
    }
    
    /**
     * 处理网络变化事件
     */
    private fun handleNetworkChange() {
        Log.d(TAG, "处理网络变化事件")
        
        // 网络变化时重新评估所有轮询间隔
        notifyGlobalPollingChange(IntervalChangeType.REEVALUATE)
    }
    
    /**
     * 获取当前系统负载
     */
    private fun getCurrentSystemLoad(): SystemLoadInfo {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val memoryUsageRatio = usedMemory.toDouble() / totalMemory.toDouble()
            
            // 简化的CPU使用率估算（实际应使用更精确的方法）
            val cpuUsageRatio = estimateCpuUsage()
            
            // 电池电量（需要权限，简化实现）
            val batteryLevel = getBatteryLevel()
            
            SystemLoadInfo(
                cpuUsageRatio = cpuUsageRatio,
                memoryUsageRatio = memoryUsageRatio,
                availableMemoryKB = freeMemory / 1024,
                batteryLevel = batteryLevel,
                isLowPowerMode = batteryLevel < LOW_BATTERY_THRESHOLD
            )
        } catch (e: Exception) {
            Log.w(TAG, "获取系统负载信息失败", e)
            SystemLoadInfo()
        }
    }
    
    /**
     * 获取当前网络质量
     */
    private fun getCurrentNetworkQuality(): NetworkQuality {
        // 简化实现：返回缓存的网络质量
        // 实际实现应该检测网络连接类型、延迟、带宽等
        return lastNetworkQuality
    }
    
    /**
     * 调整全局轮询策略
     */
    private fun adjustGlobalPollingStrategy(systemLoad: SystemLoadInfo, networkQuality: NetworkQuality) {
        // 根据系统负载调整策略
        when {
            systemLoad.cpuUsageRatio > HIGH_CPU_THRESHOLD -> {
                Log.i(TAG, "CPU使用率过高，降低轮询频率")
                notifyGlobalPollingChange(IntervalChangeType.INCREASE)
            }
            
            systemLoad.memoryUsageRatio > HIGH_MEMORY_THRESHOLD -> {
                Log.i(TAG, "内存使用率过高，降低轮询频率")  
                notifyGlobalPollingChange(IntervalChangeType.INCREASE)
            }
            
            systemLoad.isLowPowerMode -> {
                Log.i(TAG, "进入低电量模式，大幅降低轮询频率")
                notifyGlobalPollingChange(IntervalChangeType.LOW_POWER)
            }
            
            networkQuality == NetworkQuality.POOR -> {
                Log.i(TAG, "网络质量较差，适当降低轮询频率")
                notifyGlobalPollingChange(IntervalChangeType.INCREASE)
            }
        }
        
        // 调整线程池大小
        adjustConcurrency(systemLoad)
    }
    
    /**
     * 计算最优线程池大小
     */
    private fun calculateOptimalPoolSize(systemLoad: SystemLoadInfo): Int {
        return when {
            systemLoad.isLowPowerMode -> 1
            systemLoad.cpuUsageRatio > HIGH_CPU_THRESHOLD -> maxOf(1, currentPoolSize - 1)
            systemLoad.memoryUsageRatio > HIGH_MEMORY_THRESHOLD -> maxOf(1, currentPoolSize - 1)
            systemLoad.cpuUsageRatio < 0.3 && systemLoad.memoryUsageRatio < 0.5 -> minOf(DEFAULT_MAX_POOL_SIZE, currentPoolSize + 1)
            else -> currentPoolSize
        }
    }
    
    /**
     * 清理过期的调整记录
     */
    private fun cleanupOldAdjustmentRecords() {
        val currentTime = System.currentTimeMillis()
        val expireTime = 5 * 60 * 1000L // 5分钟
        
        lastAdjustmentTime.entries.removeIf { (_, time) ->
            currentTime - time > expireTime
        }
        
        consecutiveEmptyPolls.entries.removeIf { (recipientId, _) ->
            !lastAdjustmentTime.containsKey(recipientId)
        }
    }
    
    /**
     * 估算CPU使用率（简化实现）
     */
    private fun estimateCpuUsage(): Double {
        // 实际实现应该使用更精确的方法
        // 这里提供一个简化的估算
        return 0.3 // 假设30%使用率
    }
    
    /**
     * 获取电池电量（简化实现）
     */
    private fun getBatteryLevel(): Int {
        // 实际实现需要BatteryManager
        return 50 // 假设50%电量
    }
    
    /**
     * 通知轮询间隔变化（单个目标）
     */
    private fun notifyPollingIntervalChange(recipientId: String, changeType: IntervalChangeType) {
        // 这里应该与TapPollingService集成
        Log.d(TAG, "通知轮询间隔变化: recipient=$recipientId, changeType=$changeType")
    }
    
    /**
     * 通知全局轮询变化
     */
    private fun notifyGlobalPollingChange(changeType: IntervalChangeType) {
        // 这里应该与TapPollingService集成
        Log.d(TAG, "通知全局轮询变化: changeType=$changeType")
    }
}

/**
 * 轮询调整触发器
 */
enum class PollingAdjustTrigger {
    MESSAGE_RECEIVED,    // 收到消息 -> 提高频率
    MESSAGE_SENT,        // 发送消息 -> 提高频率  
    USER_ACTIVE,         // 用户活跃 -> 提高频率
    CONSECUTIVE_EMPTY,   // 连续空轮询 -> 降低频率
    ERROR_OCCURRED,      // 发生错误 -> 错误退避
    TOKEN_REFRESH,       // Token刷新 -> 恢复轮询
    APP_BACKGROUND,      // 应用后台 -> 降低频率
    NETWORK_CHANGE       // 网络变化 -> 重新评估
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

/**
 * 系统负载信息
 */
data class SystemLoadInfo(
    val cpuUsageRatio: Double = 0.0,      // CPU使用率比例
    val memoryUsageRatio: Double = 0.0,   // 内存使用率比例
    val availableMemoryKB: Long = 0L,     // 可用内存（KB）
    val batteryLevel: Int = 100,          // 电池电量（0-100）
    val isLowPowerMode: Boolean = false   // 是否为低电量模式
)

/**
 * 动态调度器状态
 */
data class DynamicSchedulerStatus(
    val isRunning: Boolean,
    val currentPoolSize: Int,
    val adjustmentCount: Int,
    val activeAdjustments: Int,
    val lastSystemLoad: SystemLoadInfo?,
    val lastNetworkQuality: NetworkQuality
) 