package org.thoughtcrime.securesms.tap.notification.monitoring

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 推送通知指标收集器
 * 
 * 收集推送服务的性能指标和统计数据
 */
class NotificationMetrics private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(NotificationMetrics::class.java)
        private const val PREF_NAME = "notification_metrics"
        
        @Volatile
        private var instance: NotificationMetrics? = null
        
        fun getInstance(context: Context): NotificationMetrics {
            return instance ?: synchronized(this) {
                instance ?: NotificationMetrics(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // 实时指标
    private val connectionCount = AtomicLong(0)
    private val notificationReceivedCount = AtomicLong(0)
    private val notificationSentCount = AtomicLong(0)
    private val webhookSuccessCount = AtomicLong(0)
    private val webhookFailureCount = AtomicLong(0)
    private val reconnectCount = AtomicLong(0)
    
    // 延迟统计
    private val latencyStats = ConcurrentHashMap<String, LatencyStat>()
    
    /**
     * 记录连接事件
     */
    fun recordConnection(success: Boolean) {
        if (success) {
            connectionCount.incrementAndGet()
            Log.d(TAG, "连接成功: total=${connectionCount.get()}")
        } else {
            reconnectCount.incrementAndGet()
            Log.d(TAG, "连接失败/重连: total=${reconnectCount.get()}")
        }
        
        // 持久化计数
        persistMetrics()
    }
    
    /**
     * 记录接收到的推送通知
     */
    fun recordNotificationReceived(senderId: String, latencyMs: Long = 0) {
        notificationReceivedCount.incrementAndGet()
        
        if (latencyMs > 0) {
            recordLatency("notification_received", latencyMs)
        }
        
        Log.d(TAG, "收到推送通知: senderId=$senderId, latency=${latencyMs}ms, total=${notificationReceivedCount.get()}")
    }
    
    /**
     * 记录发送的推送通知
     */
    fun recordNotificationSent(recipientId: String) {
        notificationSentCount.incrementAndGet()
        Log.d(TAG, "发送推送通知: recipientId=$recipientId, total=${notificationSentCount.get()}")
    }
    
    /**
     * 记录Webhook请求结果
     */
    fun recordWebhookRequest(success: Boolean, latencyMs: Long = 0) {
        if (success) {
            webhookSuccessCount.incrementAndGet()
            Log.d(TAG, "Webhook成功: latency=${latencyMs}ms, total=${webhookSuccessCount.get()}")
        } else {
            webhookFailureCount.incrementAndGet()
            Log.w(TAG, "Webhook失败: total=${webhookFailureCount.get()}")
        }
        
        if (latencyMs > 0) {
            recordLatency("webhook", latencyMs)
        }
        
        persistMetrics()
    }
    
    /**
     * 记录延迟统计
     */
    fun recordLatency(type: String, latencyMs: Long) {
        val stat = latencyStats.getOrPut(type) { LatencyStat(type) }
        stat.record(latencyMs)
    }
    
    /**
     * 获取指标摘要
     */
    fun getMetricsSummary(): MetricsSummary {
        val webhookSuccessRate = if (webhookSuccessCount.get() + webhookFailureCount.get() > 0) {
            webhookSuccessCount.get().toFloat() / (webhookSuccessCount.get() + webhookFailureCount.get())
        } else {
            0f
        }
        
        return MetricsSummary(
            connectionCount = connectionCount.get(),
            reconnectCount = reconnectCount.get(),
            notificationReceivedCount = notificationReceivedCount.get(),
            notificationSentCount = notificationSentCount.get(),
            webhookSuccessCount = webhookSuccessCount.get(),
            webhookFailureCount = webhookFailureCount.get(),
            webhookSuccessRate = webhookSuccessRate,
            latencyStats = latencyStats.values.map { it.toSnapshot() }
        )
    }
    
    /**
     * 重置指标
     */
    fun reset() {
        connectionCount.set(0)
        reconnectCount.set(0)
        notificationReceivedCount.set(0)
        notificationSentCount.set(0)
        webhookSuccessCount.set(0)
        webhookFailureCount.set(0)
        latencyStats.clear()
        
        prefs.edit().clear().apply()
        
        Log.i(TAG, "指标已重置")
    }
    
    /**
     * 持久化指标
     */
    private fun persistMetrics() {
        try {
            prefs.edit().apply {
                putLong("connection_count", connectionCount.get())
                putLong("reconnect_count", reconnectCount.get())
                putLong("notification_received_count", notificationReceivedCount.get())
                putLong("notification_sent_count", notificationSentCount.get())
                putLong("webhook_success_count", webhookSuccessCount.get())
                putLong("webhook_failure_count", webhookFailureCount.get())
                putLong("last_update", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "持久化指标失败", e)
        }
    }
    
    /**
     * 从持久化存储加载指标
     */
    fun loadMetrics() {
        try {
            connectionCount.set(prefs.getLong("connection_count", 0))
            reconnectCount.set(prefs.getLong("reconnect_count", 0))
            notificationReceivedCount.set(prefs.getLong("notification_received_count", 0))
            notificationSentCount.set(prefs.getLong("notification_sent_count", 0))
            webhookSuccessCount.set(prefs.getLong("webhook_success_count", 0))
            webhookFailureCount.set(prefs.getLong("webhook_failure_count", 0))
            
            Log.i(TAG, "指标已加载")
        } catch (e: Exception) {
            Log.w(TAG, "加载指标失败", e)
        }
    }
    
    /**
     * 延迟统计
     */
    private class LatencyStat(val type: String) {
        private val samples = mutableListOf<Long>()
        private val maxSamples = 100
        
        @Synchronized
        fun record(latencyMs: Long) {
            samples.add(latencyMs)
            if (samples.size > maxSamples) {
                samples.removeAt(0)
            }
        }
        
        @Synchronized
        fun toSnapshot(): LatencySnapshot {
            if (samples.isEmpty()) {
                return LatencySnapshot(type, 0, 0, 0, 0, 0)
            }
            
            val sorted = samples.sorted()
            val avg = samples.average().toLong()
            val min = sorted.first()
            val max = sorted.last()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
            
            return LatencySnapshot(type, avg, min, max, p50, p95)
        }
    }
}

/**
 * 指标摘要
 */
data class MetricsSummary(
    val connectionCount: Long,
    val reconnectCount: Long,
    val notificationReceivedCount: Long,
    val notificationSentCount: Long,
    val webhookSuccessCount: Long,
    val webhookFailureCount: Long,
    val webhookSuccessRate: Float,
    val latencyStats: List<LatencySnapshot>
) {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 推送通知指标摘要 ===")
        sb.appendLine("连接次数: $connectionCount")
        sb.appendLine("重连次数: $reconnectCount")
        sb.appendLine("接收通知: $notificationReceivedCount")
        sb.appendLine("发送通知: $notificationSentCount")
        sb.appendLine("Webhook成功: $webhookSuccessCount")
        sb.appendLine("Webhook失败: $webhookFailureCount")
        sb.appendLine("Webhook成功率: ${(webhookSuccessRate * 100).toInt()}%")
        
        if (latencyStats.isNotEmpty()) {
            sb.appendLine("\n=== 延迟统计 ===")
            latencyStats.forEach { stat ->
                sb.appendLine("${stat.type}:")
                sb.appendLine("  平均: ${stat.avg}ms")
                sb.appendLine("  最小: ${stat.min}ms")
                sb.appendLine("  最大: ${stat.max}ms")
                sb.appendLine("  P50: ${stat.p50}ms")
                sb.appendLine("  P95: ${stat.p95}ms")
            }
        }
        
        return sb.toString()
    }
}

/**
 * 延迟快照
 */
data class LatencySnapshot(
    val type: String,
    val avg: Long,
    val min: Long,
    val max: Long,
    val p50: Long,
    val p95: Long
)

