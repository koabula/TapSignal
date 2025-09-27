package org.thoughtcrime.securesms.tap.database

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * TAP数据库操作缓冲器
 * 将数据库操作缓存并在合适时机批量执行，减少数据库连接竞争
 */
class TapDatabaseOperationBuffer private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapDatabaseOperationBuffer::class.java)
        
        @Volatile
        private var INSTANCE: TapDatabaseOperationBuffer? = null
        
        fun getInstance(context: Context): TapDatabaseOperationBuffer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapDatabaseOperationBuffer(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // 缓冲配置
        private const val MAX_BUFFER_SIZE = 50
        private const val FLUSH_INTERVAL_MS = 2000L // 2秒
        private const val MAX_FLUSH_WAIT_MS = 3000L // 最长等待3秒（从5秒优化）
        private const val EMERGENCY_BUFFER_SIZE = 100 // 紧急刷新阈值
    }
    
    private val bufferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val flushMutex = Mutex()
    private val bufferLock = ReentrantReadWriteLock()
    
    // 操作缓冲队列
    private val operationBuffer = ConcurrentLinkedQueue<BufferedOperation>()
    private val operationCounter = AtomicLong(0)
    private val lastFlushTime = AtomicLong(System.currentTimeMillis())
    private val isFlushingFlag = AtomicBoolean(false)
    private val lastSkipLogTime = AtomicLong(0) // 控制"刷新操作已在进行中"日志频率
    
    // 系统监控器
    private val systemMonitor = TapDatabaseSystemMonitor.getInstance(context)
    
    init {
        startPeriodicFlush()
    }
    
    /**
     * 缓冲数据库操作
     */
    suspend fun bufferOperation(
        operationType: String,
        channel: TransportChannel,
        operation: suspend () -> Unit
    ): Boolean {
        return try {
            val bufferedOp = BufferedOperation(
                id = operationCounter.incrementAndGet(),
                type = operationType,
                channelId = channel.channelId,
                channel = channel.copy(), // 保存通道的快照
                operation = operation,
                timestamp = System.currentTimeMillis()
            )
            
            bufferLock.read {
                operationBuffer.offer(bufferedOp)
            }
            
            Log.v(TAG, "操作已缓冲: $operationType #${bufferedOp.id} (缓冲区大小: ${operationBuffer.size})")
            
            // 检查是否需要紧急刷新
            if (operationBuffer.size >= EMERGENCY_BUFFER_SIZE) {
                Log.w(TAG, "缓冲区达到紧急阈值，触发立即刷新")
                flushWhenSafe()
            } else if (operationBuffer.size >= MAX_BUFFER_SIZE) {
                // 达到常规刷新阈值
                flushWhenSafe()
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "缓冲操作失败: $operationType", e)
            false
        }
    }
    
    /**
     * 在系统空闲时刷新缓冲区
     */
    suspend fun flushWhenSafe() {
        if (isFlushingFlag.get()) {
            // 控制日志频率，每2秒最多输出一次
            val currentTime = System.currentTimeMillis()
            val lastLogTime = lastSkipLogTime.get()
            if (currentTime - lastLogTime > 2000L) {
                lastSkipLogTime.set(currentTime)
                Log.v(TAG, "刷新操作已在进行中，跳过")
            }
            return
        }
        
        bufferScope.launch {
            flushMutex.withLock {
                if (isFlushingFlag.compareAndSet(false, true)) {
                    try {
                        performFlush()
                    } finally {
                        isFlushingFlag.set(false)
                    }
                }
            }
        }
    }
    
    /**
     * 执行刷新操作
     */
    private suspend fun performFlush() {
        if (operationBuffer.isEmpty()) return
        
        Log.d(TAG, "开始刷新缓冲区，待处理操作数: ${operationBuffer.size}")
        
        // 等待系统相对空闲
        waitForSystemQuiet()
        
        val operations = mutableListOf<BufferedOperation>()
        
        // 收集操作
        bufferLock.write {
            val batchSize = operationBuffer.size.coerceAtMost(MAX_BUFFER_SIZE)
            repeat(batchSize) {
                operationBuffer.poll()?.let { operations.add(it) }
            }
        }
        
        if (operations.isEmpty()) return
        
        Log.d(TAG, "批量执行 ${operations.size} 个数据库操作")
        
        // 按通道ID分组批量执行
        val groupedOperations = operations.groupBy { it.channelId }
        var successCount = 0
        var failureCount = 0
        
        groupedOperations.forEach { (channelId, channelOps) ->
            try {
                executeChannelOperationsBatch(channelId, channelOps)
                successCount += channelOps.size
            } catch (e: Exception) {
                Log.e(TAG, "批量执行通道操作失败: $channelId", e)
                failureCount += channelOps.size
                
                // 失败的操作重新入队（但有限制）
                requeueFailedOperations(channelOps)
            }
        }
        
        lastFlushTime.set(System.currentTimeMillis())
        Log.i(TAG, "缓冲区刷新完成: 成功=${successCount}, 失败=${failureCount}")
    }
    
    /**
     * 批量执行同一通道的操作
     */
    private suspend fun executeChannelOperationsBatch(
        channelId: String,
        operations: List<BufferedOperation>
    ) {
        Log.v(TAG, "批量执行通道操作: $channelId (${operations.size}个)")
        
        for (operation in operations) {
            try {
                operation.operation()
                Log.v(TAG, "缓冲操作执行成功: ${operation.type} #${operation.id}")
            } catch (e: Exception) {
                Log.e(TAG, "缓冲操作执行失败: ${operation.type} #${operation.id}", e)
                throw e // 重新抛出异常，让上层处理重新入队
            }
        }
    }
    
    /**
     * 重新入队失败的操作（有限制）
     */
    private fun requeueFailedOperations(operations: List<BufferedOperation>) {
        operations.forEach { operation ->
            if (operation.retryCount < 2) { // 最多重试2次
                val retryOperation = BufferedOperation(
                    id = operation.id,
                    type = operation.type,
                    channelId = operation.channelId,
                    channel = operation.channel,
                    operation = operation.operation,
                    timestamp = System.currentTimeMillis(),
                    retryCount = operation.retryCount + 1
                )
                operationBuffer.offer(retryOperation)
                Log.v(TAG, "操作重新入队: ${operation.type} #${operation.id} (重试: ${retryOperation.retryCount})")
            } else {
                Log.w(TAG, "操作重试次数超限，丢弃: ${operation.type} #${operation.id}")
            }
        }
    }
    
    /**
     * 等待系统相对空闲（优化版：递增间隔，更快响应）
     */
    private suspend fun waitForSystemQuiet() {
        var waitTime = 0L
        val maxWait = MAX_FLUSH_WAIT_MS
        var currentInterval = 150L // 起始间隔150ms（从300ms优化）
        
        while (systemMonitor.isSystemDatabaseBusy() && waitTime < maxWait) {
            delay(currentInterval)
            waitTime += currentInterval
            
            // 递增检查间隔，最大600ms
            currentInterval = (currentInterval * 1.3).toLong().coerceAtMost(600L)
        }
        
        if (waitTime > 0) {
            Log.d(TAG, "等待系统空闲: ${waitTime}ms")
        }
    }
    
    /**
     * 启动定期刷新
     */
    private fun startPeriodicFlush() {
        bufferScope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                
                try {
                    val timeSinceLastFlush = System.currentTimeMillis() - lastFlushTime.get()
                    val bufferSize = operationBuffer.size
                    
                    if (bufferSize > 0 && (timeSinceLastFlush >= FLUSH_INTERVAL_MS || bufferSize >= MAX_BUFFER_SIZE)) {
                        flushWhenSafe()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "定期刷新异常", e)
                }
            }
        }
    }
    
    /**
     * 获取缓冲区状态
     */
    fun getBufferStatus(): BufferStatus {
        return BufferStatus(
            pendingOperations = operationBuffer.size,
            isFlushInProgress = isFlushingFlag.get(),
            lastFlushTime = lastFlushTime.get(),
            totalOperationsBuffered = operationCounter.get()
        )
    }
    
    /**
     * 缓冲操作数据类
     */
    private data class BufferedOperation(
        val id: Long,
        val type: String,
        val channelId: String,
        val channel: TransportChannel,
        val operation: suspend () -> Unit,
        val timestamp: Long,
        val retryCount: Int = 0
    )
    
    /**
     * 缓冲区状态数据类
     */
    data class BufferStatus(
        val pendingOperations: Int,
        val isFlushInProgress: Boolean,
        val lastFlushTime: Long,
        val totalOperationsBuffered: Long
    )
} 