package org.thoughtcrime.securesms.tap

import android.content.Context
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * TAP数据库操作监控工具
 * 提供性能监控、死锁检测和诊断功能
 */
class TapDatabaseMonitor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapDatabaseMonitor::class.java)
        
        @Volatile
        private var INSTANCE: TapDatabaseMonitor? = null
        
        fun getInstance(context: Context): TapDatabaseMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapDatabaseMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val statsLock = ReentrantReadWriteLock()
    
    // 操作统计
    private val operationStats = ConcurrentHashMap<String, OperationMetrics>()
    private val slowOperations = ConcurrentHashMap<String, SlowOperationRecord>()
    
    // 系统指标
    private val totalOperations = AtomicLong(0)
    private val failedOperations = AtomicLong(0)
    private val deadlockDetections = AtomicLong(0)
    
    // 配置
    private val slowOperationThresholdMs = 2000L // 2秒
    private val maxSlowOperationRecords = 50
    
    init {
        startPeriodicReporting()
    }
    
    /**
     * 记录数据库操作开始
     */
    fun recordOperationStart(operationName: String, operationId: Long) {
        totalOperations.incrementAndGet()
        
        val metrics = operationStats.computeIfAbsent(operationName) { 
            OperationMetrics(operationName) 
        }
        
        synchronized(metrics) {
            metrics.activeOperations.incrementAndGet()
            metrics.totalOperations.incrementAndGet()
            metrics.lastStartTime.set(System.currentTimeMillis())
        }
        
        Log.v(TAG, "数据库操作开始: $operationName #$operationId")
    }
    
    /**
     * 记录数据库操作完成
     */
    fun recordOperationComplete(operationName: String, operationId: Long, durationMs: Long, success: Boolean) {
        val metrics = operationStats[operationName]
        if (metrics != null) {
            synchronized(metrics) {
                metrics.activeOperations.decrementAndGet()
                
                if (success) {
                    metrics.successfulOperations.incrementAndGet()
                    metrics.totalDurationMs.addAndGet(durationMs)
                    
                    // 更新最快和最慢操作时间
                    val currentFastest = metrics.fastestOperationMs.get()
                    if (currentFastest == 0L || durationMs < currentFastest) {
                        metrics.fastestOperationMs.set(durationMs)
                    }
                    
                    val currentSlowest = metrics.slowestOperationMs.get()
                    if (durationMs > currentSlowest) {
                        metrics.slowestOperationMs.set(durationMs)
                    }
                } else {
                    metrics.failedOperations.incrementAndGet()
                    failedOperations.incrementAndGet()
                }
                
                metrics.lastCompleteTime.set(System.currentTimeMillis())
            }
        }
        
        // 记录慢操作
        if (durationMs > slowOperationThresholdMs) {
            recordSlowOperation(operationName, operationId, durationMs)
        }
        
        val status = if (success) "成功" else "失败"
        Log.v(TAG, "数据库操作完成: $operationName #$operationId ($status, 耗时: ${durationMs}ms)")
    }
    
    /**
     * 记录死锁检测
     */
    fun recordDeadlockDetection(operationName: String, details: String) {
        deadlockDetections.incrementAndGet()
        Log.w(TAG, "检测到潜在死锁: $operationName - $details")
        
        // 记录为慢操作
        recordSlowOperation(operationName, -1, slowOperationThresholdMs, "潜在死锁: $details")
    }
    
    /**
     * 记录慢操作
     */
    private fun recordSlowOperation(operationName: String, operationId: Long, durationMs: Long, additionalInfo: String = "") {
        val record = SlowOperationRecord(
            operationName = operationName,
            operationId = operationId,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis(),
            additionalInfo = additionalInfo
        )
        
        // 保持最近的慢操作记录
        val key = "${operationName}_${System.currentTimeMillis()}"
        slowOperations[key] = record
        
        // 清理过旧的记录
        if (slowOperations.size > maxSlowOperationRecords) {
            val oldestKeys = slowOperations.keys.sorted().take(slowOperations.size - maxSlowOperationRecords)
            oldestKeys.forEach { slowOperations.remove(it) }
        }
        
        Log.w(TAG, "慢操作记录: $operationName #$operationId (耗时: ${durationMs}ms) $additionalInfo")
    }
    
    /**
     * 获取性能统计报告
     */
    fun getPerformanceReport(): String {
        return statsLock.read {
            val report = StringBuilder()
            report.append("=== TAP数据库性能报告 ===\n")
            
            // 总体统计
            report.append("总操作数: ${totalOperations.get()}\n")
            report.append("失败操作数: ${failedOperations.get()}\n")
            report.append("死锁检测数: ${deadlockDetections.get()}\n")
            
            // 数据库上下文统计
            val contextStats = TapDatabaseContext.getOperationStats()
            report.append("数据库上下文统计:\n")
            report.append("  总操作数: ${contextStats.totalOperations}\n")
            report.append("  待处理操作数: ${contextStats.pendingOperations}\n")
            report.append("  可用连接数: ${contextStats.availableConnections}\n")
            
            // 独立数据库连接池统计
            try {
                val databaseManager = org.thoughtcrime.securesms.tap.database.TapDatabaseManager.getInstance(context)
                val poolStatus = databaseManager.getConnectionPoolStatus()
                report.append("独立数据库连接池统计:\n")
                report.append("  最大连接数: ${poolStatus.maxConnections}\n")
                report.append("  可用连接数: ${poolStatus.availableConnections}\n")
                report.append("  活跃连接数: ${poolStatus.activeConnections}\n")
                report.append("  读连接状态: ${if (poolStatus.hasReadConnection) "已建立" else "未建立"}\n")
                report.append("  写连接状态: ${if (poolStatus.hasWriteConnection) "已建立" else "未建立"}\n")
                
                val dbSize = databaseManager.getDatabaseSize()
                report.append("  数据库文件大小: ${dbSize / 1024}KB\n")
            } catch (e: Exception) {
                report.append("独立数据库统计获取失败: ${e.message}\n")
            }
            
            // 各操作详细统计
            report.append("\n=== 操作详细统计 ===\n")
            operationStats.values.sortedByDescending { it.totalOperations.get() }.forEach { metrics ->
                synchronized(metrics) {
                    val total = metrics.totalOperations.get()
                    val successful = metrics.successfulOperations.get()
                    val failed = metrics.failedOperations.get()
                    val active = metrics.activeOperations.get()
                    val successRate = if (total > 0) (successful * 100.0 / total) else 0.0
                    val avgDuration = if (successful > 0) (metrics.totalDurationMs.get() / successful) else 0L
                    
                    report.append("${metrics.operationName}:\n")
                    report.append("  总数: $total, 成功: $successful, 失败: $failed, 活跃: $active\n")
                    report.append("  成功率: ${"%.2f".format(successRate)}%\n")
                    report.append("  平均耗时: ${avgDuration}ms\n")
                    report.append("  最快: ${metrics.fastestOperationMs.get()}ms, 最慢: ${metrics.slowestOperationMs.get()}ms\n")
                }
            }
            
            // 慢操作列表
            if (slowOperations.isNotEmpty()) {
                report.append("\n=== 最近的慢操作 ===\n")
                slowOperations.values.sortedByDescending { it.timestamp }.take(10).forEach { record ->
                    val timeAgo = System.currentTimeMillis() - record.timestamp
                    report.append("${record.operationName} #${record.operationId}: ${record.durationMs}ms")
                    report.append(" (${timeAgo}ms前)")
                    if (record.additionalInfo.isNotEmpty()) {
                        report.append(" - ${record.additionalInfo}")
                    }
                    report.append("\n")
                }
            }
            
            report.toString()
        }
    }
    
    /**
     * 启动定期报告
     */
    private fun startPeriodicReporting() {
        monitorScope.launch {
            while (true) {
                delay(60000) // 每分钟报告一次
                
                try {
                    val activeOps = operationStats.values.sumOf { it.activeOperations.get() }
                    val pendingOps = TapDatabaseContext.getOperationStats().pendingOperations
                    
                    if (activeOps > 0 || pendingOps > 0) {
                        Log.i(TAG, "数据库操作状态: 活跃=${activeOps}, 待处理=${pendingOps}")
                    }
                    
                    // 检查是否有长时间运行的操作
                    val currentTime = System.currentTimeMillis()
                    operationStats.values.forEach { metrics ->
                        if (metrics.activeOperations.get() > 0) {
                            val runningTime = currentTime - metrics.lastStartTime.get()
                            if (runningTime > slowOperationThresholdMs * 2) {
                                Log.w(TAG, "长时间运行的操作: ${metrics.operationName} (运行时间: ${runningTime}ms)")
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "定期报告异常", e)
                }
            }
        }
    }
    
    /**
     * 操作指标数据类
     */
    private data class OperationMetrics(
        val operationName: String,
        val totalOperations: AtomicLong = AtomicLong(0),
        val successfulOperations: AtomicLong = AtomicLong(0),
        val failedOperations: AtomicLong = AtomicLong(0),
        val activeOperations: AtomicLong = AtomicLong(0),
        val totalDurationMs: AtomicLong = AtomicLong(0),
        val fastestOperationMs: AtomicLong = AtomicLong(0),
        val slowestOperationMs: AtomicLong = AtomicLong(0),
        val lastStartTime: AtomicLong = AtomicLong(0),
        val lastCompleteTime: AtomicLong = AtomicLong(0)
    )
    
    /**
     * 慢操作记录数据类
     */
    private data class SlowOperationRecord(
        val operationName: String,
        val operationId: Long,
        val durationMs: Long,
        val timestamp: Long,
        val additionalInfo: String
    )
} 