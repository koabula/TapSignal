package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.service.CosPollingService
import org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * COS轮询管理器
 * 统一管理轮询服务的启动、停止、状态监控和配置更新
 */
class CosPollingManager(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosPollingManager::class.java)
        
        // 监控配置
        private const val STATUS_CHECK_INTERVAL = 60000L    // 状态检查间隔1分钟
        private const val HEALTH_CHECK_INTERVAL = 300000L   // 健康检查间隔5分钟
        private const val AUTO_RESTART_THRESHOLD = 3        // 自动重启阈值
        
        @Volatile
        private var INSTANCE: CosPollingManager? = null
        
        fun getInstance(context: Context): CosPollingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosPollingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val cosPollingService = CosPollingService(context)
    private val cosMessageProcessor = CosMessageProcessor.getInstance(context)
    private val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
    private val pollingStrategy = IntelligentPollingStrategy(context)
    
    // 状态管理（加强同步）
    private val isInitialized = AtomicBoolean(false)
    private val currentState = AtomicReference(PollingManagerState.STOPPED)
    private val consecutiveFailures = AtomicReference(0)
    private val stateLock = ReentrantReadWriteLock()
    private val lastStateChangeTime = AtomicLong(System.currentTimeMillis())
    private val stateChangeListeners = ConcurrentHashMap<String, (PollingManagerState, PollingManagerState) -> Unit>()

    // 监控和调度
    private val monitoringExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val managerStatistics = ManagerStatistics()

    // 状态同步标志
    private val isStateSyncing = AtomicBoolean(false)
    
    /**
     * 初始化轮询管理器
     */
    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            Log.i(TAG, "初始化COS轮询管理器")
            
            // 启动状态监控
            startStatusMonitoring()
            
            // 启动健康检查
            startHealthCheck()
            
            Log.i(TAG, "COS轮询管理器初始化完成")
        } else {
            Log.w(TAG, "轮询管理器已初始化")
        }
    }
    
    /**
     * 启动轮询服务（改进状态检查和错误处理）
     */
    fun startPolling(): Boolean {
        Log.i(TAG, "启动COS轮询服务")

        if (!isInitialized.get()) {
            Log.w(TAG, "管理器未初始化，先进行初始化")
            initialize()
        }

        return try {
            // 检查是否有活跃的子账户条目
            val allReceivedEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()
            val activeSubAccountEntries = allReceivedEntries.filter { it.isActive && it.isValid() }

            Log.d(TAG, "轮询服务启动检查: 总接收子账户条目=${allReceivedEntries.size}, 活跃且有效条目=${activeSubAccountEntries.size}")
            allReceivedEntries.forEachIndexed { index, entry ->
                Log.d(TAG, "子账户条目[$index]: recipientId=${entry.recipientId}, isActive=${entry.isActive}, isValid=${entry.isValid()}, isExpired=${entry.isExpired()}")
                if (!entry.isValid()) {
                    Log.w(TAG, "  无效原因: isActive=${entry.isActive}, isExpired=${entry.isExpired()}, pollingErrors=${entry.pollingErrors}")
                }
            }

            if (activeSubAccountEntries.isEmpty()) {
                Log.w(TAG, "没有活跃且有效的子账户条目，跳过启动轮询服务")
                changeState(PollingManagerState.IDLE, "无有效子账户")
                return false
            }

            // 启动轮询服务
            cosPollingService.startPolling()
            changeState(PollingManagerState.RUNNING, "轮询服务启动成功")
            consecutiveFailures.set(0)
            managerStatistics.incrementStartCount()

            Log.i(TAG, "COS轮询服务启动成功，监控${activeSubAccountEntries.size}个有效子账户")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动轮询服务失败", e)
            changeState(PollingManagerState.ERROR, "启动失败: ${e.message}")
            managerStatistics.incrementFailureCount()
            false
        }
    }
    
    /**
     * 停止轮询服务
     */
    fun stopPolling(): Boolean {
        Log.i(TAG, "停止COS轮询服务")
        
        return try {
            cosPollingService.stopPolling()
            currentState.set(PollingManagerState.STOPPED)
            consecutiveFailures.set(0)
            managerStatistics.incrementStopCount()
            
            Log.i(TAG, "COS轮询服务停止成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止轮询服务失败", e)
            currentState.set(PollingManagerState.ERROR)
            false
        }
    }
    
    /**
     * 重启轮询服务
     */
    fun restartPolling(): Boolean {
        Log.i(TAG, "重启COS轮询服务")
        
        return try {
            cosPollingService.restartPolling()
            currentState.set(PollingManagerState.RUNNING)
            consecutiveFailures.set(0)
            managerStatistics.incrementRestartCount()
            
            Log.i(TAG, "COS轮询服务重启成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "重启轮询服务失败", e)
            currentState.set(PollingManagerState.ERROR)
            managerStatistics.incrementFailureCount()
            false
        }
    }
    
    /**
     * 暂停轮询服务
     */
    fun pausePolling(): Boolean {
        Log.i(TAG, "暂停COS轮询服务")
        
        return try {
            cosPollingService.stopPolling()
            currentState.set(PollingManagerState.PAUSED)
            
            Log.i(TAG, "COS轮询服务暂停成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "暂停轮询服务失败", e)
            false
        }
    }
    
    /**
     * 恢复轮询服务
     */
    fun resumePolling(): Boolean {
        Log.i(TAG, "恢复COS轮询服务")
        
        if (currentState.get() != PollingManagerState.PAUSED) {
            Log.w(TAG, "轮询服务未处于暂停状态，无法恢复")
            return false
        }
        
        return startPolling()
    }
    
    /**
     * 检查轮询是否活跃
     */
    fun isPollingActive(): Boolean {
        return currentState.get() == PollingManagerState.RUNNING
    }

    /**
     * 获取轮询管理器状态
     */
    fun getManagerStatus(): PollingManagerStatus {
        val pollingStatus = cosPollingService.getPollingStatus()
        val processingStats = cosMessageProcessor.getProcessingStatistics()
        val pollingStrategyStats = pollingStrategy.getPollingStatistics()
        
        return PollingManagerStatus(
            managerState = currentState.get(),
            isInitialized = isInitialized.get(),
            consecutiveFailures = consecutiveFailures.get(),
            pollingServiceStatus = pollingStatus,
            processingStatistics = processingStats,
            pollingStrategyStatistics = pollingStrategyStats,
            managerStatistics = managerStatistics.getSnapshot()
        )
    }
    
    /**
     * 更新轮询配置
     */
    fun updatePollingConfiguration(config: PollingConfiguration): Boolean {
        Log.i(TAG, "更新轮询配置")
        
        return try {
            // TODO: 实现配置更新逻辑
            // 这里可以根据配置调整轮询策略、间隔等参数
            
            Log.i(TAG, "轮询配置更新成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新轮询配置失败", e)
            false
        }
    }
    
    /**
     * 强制处理所有待排序消息
     */
    fun forceProcessAllPendingMessages(): Map<String, CosMessageProcessor.ProcessingResult> {
        Log.i(TAG, "强制处理所有待排序消息")
        
        val results = mutableMapOf<String, CosMessageProcessor.ProcessingResult>()
        val activeSubAccountEntries = subAccountPoolManager.getAllValidReceivedSubAccounts().filter { it.isActive }

        activeSubAccountEntries.forEach { subAccountEntry ->
            try {
                val result = cosMessageProcessor.forceProcessPendingMessages(subAccountEntry.recipientId).get()
                results[subAccountEntry.recipientId] = result
                Log.d(TAG, "强制处理完成: recipientId=${subAccountEntry.recipientId}, result=$result")
            } catch (e: Exception) {
                Log.e(TAG, "强制处理失败: recipientId=${subAccountEntry.recipientId}", e)
                results[subAccountEntry.recipientId] = CosMessageProcessor.ProcessingResult.Error(e.message ?: "未知错误")
            }
        }
        
        return results
    }
    
    /**
     * 启动状态监控
     */
    private fun startStatusMonitoring() {
        monitoringExecutor.scheduleWithFixedDelay({
            try {
                performStatusCheck()
            } catch (e: Exception) {
                Log.e(TAG, "状态检查异常", e)
            }
        }, STATUS_CHECK_INTERVAL, STATUS_CHECK_INTERVAL, TimeUnit.MILLISECONDS)
        
        Log.d(TAG, "状态监控已启动")
    }
    
    /**
     * 启动健康检查
     */
    private fun startHealthCheck() {
        monitoringExecutor.scheduleWithFixedDelay({
            try {
                performHealthCheck()
            } catch (e: Exception) {
                Log.e(TAG, "健康检查异常", e)
            }
        }, HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.MILLISECONDS)
        
        Log.d(TAG, "健康检查已启动")
    }
    
    /**
     * 执行状态检查
     */
    private fun performStatusCheck() {
        val currentManagerState = currentState.get()
        val pollingStatus = cosPollingService.getPollingStatus()
        
        Log.d(TAG, "状态检查: managerState=$currentManagerState, pollingRunning=${pollingStatus.isRunning}")
        
        // 检查状态一致性
        when (currentManagerState) {
            PollingManagerState.RUNNING -> {
                if (!pollingStatus.isRunning) {
                    Log.w(TAG, "状态不一致：管理器状态为RUNNING但轮询服务未运行")
                    handleInconsistentState()
                }
            }
            PollingManagerState.STOPPED, PollingManagerState.PAUSED -> {
                if (pollingStatus.isRunning) {
                    Log.w(TAG, "状态不一致：管理器状态为${currentManagerState}但轮询服务正在运行")
                    handleInconsistentState()
                }
            }
            else -> {
                // 其他状态暂不处理
            }
        }
    }
    
    /**
     * 执行健康检查
     */
    private fun performHealthCheck() {
        Log.d(TAG, "执行健康检查")
        
        val pollingStatus = cosPollingService.getPollingStatus()
        val activeSubAccountCount = subAccountPoolManager.getAllValidReceivedSubAccounts().filter { it.isActive }.size
        
        // 检查轮询服务健康状况
        if (currentState.get() == PollingManagerState.RUNNING) {
            val successRate = pollingStatus.statistics.successRate
            
            if (successRate < 0.5 && pollingStatus.statistics.pollingAttempts > 10) {
                Log.w(TAG, "轮询成功率过低: $successRate")
                handleLowSuccessRate()
            }
        }
        
        // 检查子账户Pool状态
        if (activeSubAccountCount == 0 && currentState.get() == PollingManagerState.RUNNING) {
            Log.w(TAG, "没有活跃的子账户条目但轮询服务正在运行")
            pausePolling()
        } else if (activeSubAccountCount > 0 && currentState.get() == PollingManagerState.IDLE) {
            Log.i(TAG, "发现活跃的子账户条目，启动轮询服务")
            startPolling()
        }
    }
    
    /**
     * 处理状态不一致
     */
    private fun handleInconsistentState() {
        val failures = consecutiveFailures.get() + 1
        consecutiveFailures.set(failures)
        Log.w(TAG, "处理状态不一致，连续失败次数: $failures")

        if (failures >= AUTO_RESTART_THRESHOLD) {
            Log.w(TAG, "连续失败次数达到阈值，尝试自动重启")
            if (restartPolling()) {
                Log.i(TAG, "自动重启成功")
            } else {
                Log.e(TAG, "自动重启失败")
                currentState.set(PollingManagerState.ERROR)
            }
        }
    }
    
    /**
     * 处理低成功率
     */
    private fun handleLowSuccessRate() {
        Log.w(TAG, "轮询成功率过低，尝试重启服务")
        restartPolling()
    }
    
    /**
     * 关闭管理器
     */
    fun shutdown() {
        Log.i(TAG, "关闭COS轮询管理器")
        
        try {
            stopPolling()
            monitoringExecutor.shutdown()
            
            if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow()
            }
            
            isInitialized.set(false)
            Log.i(TAG, "COS轮询管理器关闭完成")
        } catch (e: Exception) {
            Log.e(TAG, "关闭管理器异常", e)
        }
    }
    
    /**
     * 轮询管理器状态枚举
     */
    enum class PollingManagerState {
        STOPPED,    // 已停止
        RUNNING,    // 运行中
        PAUSED,     // 已暂停
        IDLE,       // 空闲（无CAM条目）
        ERROR       // 错误状态
    }
    
    /**
     * 轮询配置数据类
     */
    data class PollingConfiguration(
        val enableAutoRestart: Boolean = true,
        val autoRestartThreshold: Int = AUTO_RESTART_THRESHOLD,
        val statusCheckInterval: Long = STATUS_CHECK_INTERVAL,
        val healthCheckInterval: Long = HEALTH_CHECK_INTERVAL
    )
    
    /**
     * 轮询管理器状态数据类
     */
    data class PollingManagerStatus(
        val managerState: PollingManagerState,
        val isInitialized: Boolean,
        val consecutiveFailures: Int,
        val pollingServiceStatus: CosPollingService.PollingStatus,
        val processingStatistics: CosMessageProcessor.ProcessingStatistics,
        val pollingStrategyStatistics: IntelligentPollingStrategy.PollingStatistics,
        val managerStatistics: ManagerStatistics.Snapshot
    )
    
    /**
     * 管理器统计信息
     */
    class ManagerStatistics {
        private var startCount = 0
        private var stopCount = 0
        private var restartCount = 0
        private var failureCount = 0
        
        @Synchronized
        fun incrementStartCount() { startCount++ }
        
        @Synchronized
        fun incrementStopCount() { stopCount++ }
        
        @Synchronized
        fun incrementRestartCount() { restartCount++ }
        
        @Synchronized
        fun incrementFailureCount() { failureCount++ }
        
        @Synchronized
        fun getSnapshot(): Snapshot {
            return Snapshot(startCount, stopCount, restartCount, failureCount)
        }
        
        data class Snapshot(
            val startCount: Int,
            val stopCount: Int,
            val restartCount: Int,
            val failureCount: Int
        )
    }

    /**
     * 改变状态（线程安全）
     */
    private fun changeState(newState: PollingManagerState, reason: String = "") {
        stateLock.write {
            val oldState = currentState.get()
            if (oldState != newState) {
                currentState.set(newState)
                lastStateChangeTime.set(System.currentTimeMillis())

                Log.i(TAG, "状态变更: $oldState -> $newState${if (reason.isNotEmpty()) " ($reason)" else ""}")

                // 通知状态变更监听器
                stateChangeListeners.values.forEach { listener ->
                    try {
                        listener(oldState, newState)
                    } catch (e: Exception) {
                        Log.e(TAG, "状态变更监听器异常", e)
                    }
                }
            }
        }
    }

    /**
     * 添加状态变更监听器
     */
    fun addStateChangeListener(id: String, listener: (PollingManagerState, PollingManagerState) -> Unit) {
        stateChangeListeners[id] = listener
        Log.d(TAG, "添加状态变更监听器: $id")
    }

    /**
     * 移除状态变更监听器
     */
    fun removeStateChangeListener(id: String) {
        stateChangeListeners.remove(id)
        Log.d(TAG, "移除状态变更监听器: $id")
    }

    /**
     * 同步轮询服务状态
     */
    private fun syncPollingServiceState() {
        if (isStateSyncing.compareAndSet(false, true)) {
            try {
                val serviceStatus = cosPollingService.getPollingStatus()
                val currentManagerState = currentState.get()

                // 检查状态一致性
                val expectedManagerState = when {
                    !serviceStatus.isRunning && currentManagerState == PollingManagerState.RUNNING -> {
                        Log.w(TAG, "检测到状态不一致: 管理器运行中但服务已停止")
                        PollingManagerState.ERROR
                    }
                    serviceStatus.isRunning && currentManagerState == PollingManagerState.STOPPED -> {
                        Log.w(TAG, "检测到状态不一致: 管理器已停止但服务运行中")
                        PollingManagerState.RUNNING
                    }
                    serviceStatus.activeTaskCount == 0 && currentManagerState == PollingManagerState.RUNNING -> {
                        Log.d(TAG, "检测到空闲状态: 无活跃轮询任务")
                        PollingManagerState.IDLE
                    }
                    else -> currentManagerState
                }

                if (expectedManagerState != currentManagerState) {
                    changeState(expectedManagerState, "状态同步")
                }

            } catch (e: Exception) {
                Log.e(TAG, "同步轮询服务状态异常", e)
            } finally {
                isStateSyncing.set(false)
            }
        }
    }

    /**
     * 获取详细状态信息
     */
    fun getDetailedStatus(): DetailedPollingStatus {
        return stateLock.read {
            val serviceStatus = cosPollingService.getPollingStatus()
            val deduplicationStats = cosPollingService.getDeduplicationStatistics()
            val subAccountStats = subAccountPoolManager.getStatistics()

            DetailedPollingStatus(
                managerState = currentState.get(),
                isInitialized = isInitialized.get(),
                consecutiveFailures = consecutiveFailures.get(),
                lastStateChangeTime = lastStateChangeTime.get(),
                serviceStatus = serviceStatus,
                deduplicationStatistics = deduplicationStats,
                subAccountStatistics = subAccountStats,
                managerStatistics = managerStatistics.getSnapshot()
            )
        }
    }

    /**
     * 详细轮询状态数据类
     */
    data class DetailedPollingStatus(
        val managerState: PollingManagerState,
        val isInitialized: Boolean,
        val consecutiveFailures: Int,
        val lastStateChangeTime: Long,
        val serviceStatus: CosPollingService.PollingStatus,
        val deduplicationStatistics: CosPollingService.DeduplicationStatistics,
        val subAccountStatistics: SubAccountPoolStatistics,
        val managerStatistics: ManagerStatistics.Snapshot
    )
}
