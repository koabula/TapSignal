package org.thoughtcrime.securesms.tap.database

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import kotlinx.coroutines.delay

/**
 * TAP数据库系统监控器
 * 检测系统数据库繁忙状态，为TAP数据库操作提供智能避让策略
 */
class TapDatabaseSystemMonitor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapDatabaseSystemMonitor::class.java)
        
        @Volatile
        private var INSTANCE: TapDatabaseSystemMonitor? = null
        
        fun getInstance(context: Context): TapDatabaseSystemMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapDatabaseSystemMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // 系统繁忙任务的关键字
        private val SYSTEM_BUSY_JOB_PATTERNS = setOf(
            "AnalyzeDatabaseJob",
            "OptimizeMessageSearchIndexJob", 
            "BackupJob",
            "RestoreJob",
            "DirectoryRefreshJob",
            "RefreshAttributesJob",
            "MultiDeviceKeysUpdateJob"
        )
        
        // 重试间隔配置
        private const val NORMAL_RETRY_BASE_MS = 100L
        private const val BUSY_RETRY_BASE_MS = 1000L
        private const val MAX_RETRY_INTERVAL_MS = 10000L
        private const val BUSY_DETECTION_THRESHOLD_MS = 500L
    }
    
    private val lastBusyDetection = AtomicLong(0)
    private val systemBusyFlag = AtomicBoolean(false)
    
    /**
     * 检测系统数据库是否繁忙
     */
    fun isSystemDatabaseBusy(): Boolean {
        return try {
            val currentTime = System.currentTimeMillis()
            
            // 检查最近是否检测到系统繁忙
            val lastBusy = lastBusyDetection.get()
            if (currentTime - lastBusy < BUSY_DETECTION_THRESHOLD_MS) {
                return systemBusyFlag.get()
            }
            
            // 检测活跃的系统任务
            val busy = detectSystemBusyJobs()
            
            // 更新检测结果
            lastBusyDetection.set(currentTime)
            systemBusyFlag.set(busy)
            
            if (busy) {
                Log.d(TAG, "检测到系统数据库繁忙")
            }
            
            busy
        } catch (e: Exception) {
            Log.w(TAG, "系统繁忙检测异常", e)
            false
        }
    }
    
    /**
     * 检测活跃的系统繁忙任务
     */
    private fun detectSystemBusyJobs(): Boolean {
        return try {
            // 简化的检测方法：只通过线程状态检测
            detectDatabaseBusyThreads()
            
        } catch (e: Exception) {
            Log.w(TAG, "检测系统任务异常", e)
            false
        }
    }
    
    /**
     * 检测数据库相关的繁忙线程（简化版）
     */
    private fun detectDatabaseBusyThreads(): Boolean {
        return try {
            // 简化的线程检测，通过当前线程组检测
            val currentThread = Thread.currentThread()
            val threadGroup = currentThread.threadGroup
            
            if (threadGroup != null) {
                val activeThreads = arrayOfNulls<Thread>(threadGroup.activeCount())
                threadGroup.enumerate(activeThreads)
                
                var busyCount = 0
                activeThreads.filterNotNull().forEach { thread ->
                    try {
                        val threadName = thread.name.lowercase()
                        val state = thread.state
                        
                        // 更精确的数据库相关线程检测（必须同时匹配多个条件）
                        val hasDatabaseKeyword = threadName.contains("database") || 
                                                threadName.contains("sqlite")
                        val hasWaitingKeyword = threadName.contains("pool") || 
                                               threadName.contains("connection") ||
                                               threadName.contains("job")
                        
                        // 必须同时满足：数据库关键词 + 等待关键词 + TIMED_WAITING状态
                        if (hasDatabaseKeyword && hasWaitingKeyword && state == Thread.State.TIMED_WAITING) {
                            busyCount++
                        }
                    } catch (e: Exception) {
                        // 忽略单个线程检测异常
                    }
                }
                
                // 如果有2个或更多相关线程在等待，认为系统繁忙
                return busyCount >= 2
            }
            
            false
            
        } catch (e: Exception) {
            Log.w(TAG, "检测数据库线程状态异常", e)
            false
        }
    }
    
    /**
     * 计算智能重试间隔
     */
    fun calculateRetryInterval(attemptCount: Int): Long {
        val baseInterval = if (isSystemDatabaseBusy()) {
            BUSY_RETRY_BASE_MS
        } else {
            NORMAL_RETRY_BASE_MS
        }
        
        // 指数退避算法，但区分系统繁忙状态
        val exponentialInterval = baseInterval * (1L shl (attemptCount - 1))
        return exponentialInterval.coerceAtMost(MAX_RETRY_INTERVAL_MS)
    }
    
    /**
     * 智能等待策略
     */
    suspend fun waitForOptimalRetry(attemptCount: Int) {
        val interval = calculateRetryInterval(attemptCount)
        
        Log.v(TAG, "智能重试等待: ${interval}ms (尝试: $attemptCount, 系统繁忙: ${isSystemDatabaseBusy()})")
        delay(interval)
        
        // 额外等待系统空闲
        if (isSystemDatabaseBusy()) {
            waitForSystemQuiet()
        }
    }
    
    /**
     * 等待系统相对安静（优化版：递增间隔，减少总等待时间）
     */
    private suspend fun waitForSystemQuiet() {
        var quietWaitTime = 0L
        val maxQuietWait = 2000L // 最多等待2秒（从3秒减少）
        var currentInterval = 100L // 起始间隔100ms
        
        while (isSystemDatabaseBusy() && quietWaitTime < maxQuietWait) {
            delay(currentInterval)
            quietWaitTime += currentInterval
            
            // 递增检查间隔，最大500ms
            currentInterval = (currentInterval * 1.2).toLong().coerceAtMost(500L)
        }
        
        if (quietWaitTime > 0) {
            Log.d(TAG, "等待系统安静: ${quietWaitTime}ms")
        }
    }
} 