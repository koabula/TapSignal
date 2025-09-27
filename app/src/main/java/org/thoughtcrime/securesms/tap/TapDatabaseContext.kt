package org.thoughtcrime.securesms.tap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.database.TapDatabaseSystemMonitor
import org.thoughtcrime.securesms.tap.database.TapDatabaseOperationBuffer
import org.thoughtcrime.securesms.tap.TransportChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * TAP模块统一的数据库操作上下文
 * 解决多个组件间的数据库并发竞争问题
 */
object TapDatabaseContext {
    private val TAG = Log.tag(TapDatabaseContext::class.java)
    
    /**
     * 专用数据库操作调度器，确保所有TAP相关数据库操作串行执行
     * 使用limitedParallelism(1)确保同一时间只有一个数据库操作
     */
    val databaseDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    /**
     * 数据库操作互斥锁，提供额外的并发保护
     */
    private val databaseMutex = Mutex()
    
    /**
     * 数据库连接信号量，限制同时使用的连接数
     */
    private val connectionSemaphore = Semaphore(permits = 2)
    
    /**
     * 操作计数器，用于监控和诊断
     */
    private val operationCounter = AtomicLong(0)
    private val pendingOperations = AtomicLong(0)
    
    /**
     * 监控器实例引用
     */
    private val monitorRef = AtomicReference<TapDatabaseMonitor?>(null)
    private val systemMonitorRef = AtomicReference<TapDatabaseSystemMonitor?>(null)
    private val operationBufferRef = AtomicReference<TapDatabaseOperationBuffer?>(null)
    
    /**
     * 初始化监控器
     */
    fun initializeMonitor(context: Context) {
        if (monitorRef.compareAndSet(null, TapDatabaseMonitor.getInstance(context))) {
            Log.i(TAG, "数据库监控器已初始化")
        }
        if (systemMonitorRef.compareAndSet(null, TapDatabaseSystemMonitor.getInstance(context))) {
            Log.i(TAG, "数据库系统监控器已初始化")
        }
        if (operationBufferRef.compareAndSet(null, TapDatabaseOperationBuffer.getInstance(context))) {
            Log.i(TAG, "数据库操作缓冲器已初始化")
        }
    }
    
    /**
     * 执行数据库操作的安全包装器
     * 提供统一的错误处理、重试机制、超时控制和降级策略
     */
    suspend fun <T> withDatabaseOperation(
        operationName: String,
        maxRetries: Int = 3,
        timeoutMs: Long = 15000L, // 15秒超时
        allowDegradation: Boolean = true,
        operation: suspend () -> T
    ): T {
        val operationId = operationCounter.incrementAndGet()
        pendingOperations.incrementAndGet()
        
        // 记录操作开始
        val monitor = monitorRef.get()
        monitor?.recordOperationStart(operationName, operationId)
        
        Log.v(TAG, "开始数据库操作 #$operationId: $operationName (待处理: ${pendingOperations.get()})")
        
        val startTime = System.currentTimeMillis()
        var operationSuccess = false
        
        try {
            // 添加超时控制
            val result = withTimeout(timeoutMs) {
                connectionSemaphore.acquire()
                databaseMutex.withLock {
                    var lastException: Exception? = null
                    
                    repeat(maxRetries) { attempt ->
                        try {
                            val attemptStartTime = System.currentTimeMillis()
                            val result = operation()
                            val attemptDuration = System.currentTimeMillis() - attemptStartTime
                            
                            Log.v(TAG, "数据库操作成功 #$operationId: $operationName (耗时: ${attemptDuration}ms, 尝试: ${attempt + 1})")
                            operationSuccess = true
                            return@withLock result
                            
                        } catch (e: Exception) {
                            lastException = e
                            Log.w(TAG, "数据库操作失败 #$operationId: $operationName (尝试: ${attempt + 1}/$maxRetries)", e)
                            
                            if (attempt < maxRetries - 1) {
                                // 使用智能重试机制
                                val systemMonitor = systemMonitorRef.get()
                                if (systemMonitor != null) {
                                    systemMonitor.waitForOptimalRetry(attempt + 1)
                                } else {
                                    // 备用的简单退避算法
                                    val delayMs = (100L * (1 shl attempt)).coerceAtMost(2000L)
                                    kotlinx.coroutines.delay(delayMs)
                                }
                            }
                        }
                    }
                    
                    Log.e(TAG, "数据库操作最终失败 #$operationId: $operationName", lastException)
                    throw lastException ?: RuntimeException("数据库操作失败且未捕获到异常")
                }
            }
            
            return result
            
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "数据库操作超时 #$operationId: $operationName (${timeoutMs}ms)")
            
            if (allowDegradation) {
                // 尝试降级处理
                handleOperationTimeout(operationName, operationId, timeoutMs)
            }
            
            throw DatabaseOperationTimeoutException("数据库操作超时: $operationName", e)
            
        } finally {
            connectionSemaphore.release()
            pendingOperations.decrementAndGet()
            
            val totalDuration = System.currentTimeMillis() - startTime
            
            // 记录操作完成
            monitor?.recordOperationComplete(operationName, operationId, totalDuration, operationSuccess)
            
            Log.v(TAG, "完成数据库操作 #$operationId: $operationName (剩余待处理: ${pendingOperations.get()})")
        }
    }
    
    /**
     * 带缓冲的数据库操作
     * 在系统繁忙时将操作缓冲，在空闲时批量执行
     */
    suspend fun withBufferedOperation(
        operationName: String,
        channel: TransportChannel,
        allowBuffer: Boolean = true,
        operation: suspend () -> Unit
    ): Boolean {
        val systemMonitor = systemMonitorRef.get()
        val operationBuffer = operationBufferRef.get()
        
        // 如果系统繁忙且允许缓冲，则使用缓冲模式
        if (allowBuffer && 
            systemMonitor?.isSystemDatabaseBusy() == true && 
            operationBuffer != null) {
            
            Log.d(TAG, "系统繁忙，使用缓冲模式: $operationName")
            return@withBufferedOperation operationBuffer.bufferOperation(operationName, channel, operation)
        }
        
        // 否则直接执行
        return try {
            withDatabaseOperation(operationName, maxRetries = 3) {
                operation()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "缓冲操作执行失败: $operationName", e)
            false
        }
    }
    
    /**
     * 强制刷新所有缓冲操作
     */
    suspend fun flushBufferedOperations() {
        operationBufferRef.get()?.flushWhenSafe()
    }
    
    /**
     * 处理数据库操作超时
     */
    private suspend fun handleOperationTimeout(operationName: String, operationId: Long, timeoutMs: Long) {
        Log.w(TAG, "执行超时降级处理: $operationName #$operationId")
        
        try {
            // 1. 检测是否由于系统繁忙导致超时
            val systemMonitor = systemMonitorRef.get()
            if (systemMonitor?.isSystemDatabaseBusy() == true) {
                Log.i(TAG, "检测到系统数据库繁忙，可能是超时的原因")
                
                // 2. 记录死锁检测事件
                val monitor = monitorRef.get()
                monitor?.recordDeadlockDetection(operationName, "操作超时，系统繁忙")
            }
            
            // 3. 尝试清理可能的资源
            System.gc() // 建议垃圾回收，释放内存压力
            
        } catch (e: Exception) {
            Log.e(TAG, "超时降级处理异常", e)
        }
    }
    
    /**
     * 获取当前数据库操作统计信息
     */
    fun getOperationStats(): DatabaseOperationStats {
        return DatabaseOperationStats(
            totalOperations = operationCounter.get(),
            pendingOperations = pendingOperations.get(),
            availableConnections = connectionSemaphore.availablePermits
        )
    }
}

/**
 * 数据库操作统计信息
 */
data class DatabaseOperationStats(
    val totalOperations: Long,
    val pendingOperations: Long,
    val availableConnections: Int
)

/**
 * 数据库操作的互斥锁包装器扩展
 */
private suspend inline fun <T> Mutex.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}

/**
 * 数据库操作超时异常
 */
class DatabaseOperationTimeoutException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) 