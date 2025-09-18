package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * COS发送统计管理器
 * 负责收集、存储和分析COS消息发送的统计信息
 */
class CosSendStatisticsManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosSendStatisticsManager::class.java)
        private const val PREFS_NAME = "cos_send_statistics"
        private const val KEY_STATISTICS_DATA = "statistics_data"
        private const val KEY_DAILY_STATISTICS = "daily_statistics"
        
        @Volatile
        private var INSTANCE: CosSendStatisticsManager? = null
        
        fun getInstance(context: Context): CosSendStatisticsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosSendStatisticsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    }
    
    // 内存中的统计计数器
    private val cosMessagesSent = AtomicLong(0)
    private val cosMessagesSuccess = AtomicLong(0)
    private val cosMessagesFailed = AtomicLong(0)
    private val signalMessagesSent = AtomicLong(0)
    private val signalMessagesSuccess = AtomicLong(0)
    private val signalMessagesFailed = AtomicLong(0)
    private val fallbackCount = AtomicLong(0)
    private val retryCount = AtomicLong(0)
    
    // 性能统计
    private val sendDurations: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()
    private val attachmentSizes: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()
    
    init {
        loadStatistics()
    }
    
    /**
     * 记录COS消息发送开始
     */
    fun recordCosSendStart(messageId: Long) {
        cosMessagesSent.incrementAndGet()
        sendDurations[messageId] = System.currentTimeMillis()
        Log.d(TAG, "记录COS发送开始: messageId=$messageId")
    }
    
    /**
     * 记录COS消息发送成功
     */
    fun recordCosSendSuccess(messageId: Long, attachmentSize: Long = 0) {
        cosMessagesSuccess.incrementAndGet()
        recordSendDuration(messageId)
        if (attachmentSize > 0) {
            attachmentSizes[messageId] = attachmentSize
        }
        Log.d(TAG, "记录COS发送成功: messageId=$messageId, attachmentSize=$attachmentSize")
        saveStatistics()
    }
    
    /**
     * 记录COS消息发送失败
     */
    fun recordCosSendFailure(messageId: Long, reason: String) {
        cosMessagesFailed.incrementAndGet()
        recordSendDuration(messageId)
        Log.d(TAG, "记录COS发送失败: messageId=$messageId, reason=$reason")
        saveStatistics()
    }
    
    /**
     * 记录Signal消息发送开始
     */
    fun recordSignalSendStart(messageId: Long) {
        signalMessagesSent.incrementAndGet()
        sendDurations[messageId] = System.currentTimeMillis()
        Log.d(TAG, "记录Signal发送开始: messageId=$messageId")
    }
    
    /**
     * 记录Signal消息发送成功
     */
    fun recordSignalSendSuccess(messageId: Long) {
        signalMessagesSuccess.incrementAndGet()
        recordSendDuration(messageId)
        Log.d(TAG, "记录Signal发送成功: messageId=$messageId")
        saveStatistics()
    }
    
    /**
     * 记录Signal消息发送失败
     */
    fun recordSignalSendFailure(messageId: Long, reason: String) {
        signalMessagesFailed.incrementAndGet()
        recordSendDuration(messageId)
        Log.d(TAG, "记录Signal发送失败: messageId=$messageId, reason=$reason")
        saveStatistics()
    }
    
    /**
     * 记录回退到Signal Server
     */
    fun recordFallbackToSignal(messageId: Long, reason: String) {
        fallbackCount.incrementAndGet()
        Log.d(TAG, "记录回退到Signal: messageId=$messageId, reason=$reason")
        saveStatistics()
    }
    
    /**
     * 记录重试
     */
    fun recordRetry(messageId: Long, retryNumber: Int) {
        retryCount.incrementAndGet()
        Log.d(TAG, "记录重试: messageId=$messageId, retryNumber=$retryNumber")
        saveStatistics()
    }
    
    /**
     * 获取总体统计信息
     */
    fun getOverallStatistics(): CosSendOverallStatistics {
        return CosSendOverallStatistics(
            cosMessagesSent = cosMessagesSent.get(),
            cosMessagesSuccess = cosMessagesSuccess.get(),
            cosMessagesFailed = cosMessagesFailed.get(),
            signalMessagesSent = signalMessagesSent.get(),
            signalMessagesSuccess = signalMessagesSuccess.get(),
            signalMessagesFailed = signalMessagesFailed.get(),
            fallbackCount = fallbackCount.get(),
            retryCount = retryCount.get(),
            cosSuccessRate = calculateSuccessRate(cosMessagesSuccess.get(), cosMessagesSent.get()),
            signalSuccessRate = calculateSuccessRate(signalMessagesSuccess.get(), signalMessagesSent.get()),
            fallbackRate = calculateFallbackRate(),
            averageSendDuration = calculateAverageSendDuration(),
            totalDataTransferred = calculateTotalDataTransferred()
        )
    }
    
    /**
     * 获取今日统计信息
     */
    fun getTodayStatistics(): CosSendDailyStatistics {
        val today = getCurrentDateString()
        return getDailyStatistics(today)
    }
    
    /**
     * 获取指定日期的统计信息
     */
    fun getDailyStatistics(date: String): CosSendDailyStatistics {
        return try {
            val dailyStatsJson = sharedPreferences.getString("${KEY_DAILY_STATISTICS}_$date", null)
            if (dailyStatsJson != null) {
                objectMapper.readValue<CosSendDailyStatistics>(dailyStatsJson)
            } else {
                CosSendDailyStatistics.empty(date)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取日统计失败: date=$date", e)
            CosSendDailyStatistics.empty(date)
        }
    }
    
    /**
     * 获取性能统计信息
     */
    fun getPerformanceStatistics(): CosSendPerformanceStatistics {
        val durations = sendDurations.values.toList()
        val sizes = attachmentSizes.values.toList()
        
        return CosSendPerformanceStatistics(
            averageSendDuration = if (durations.isNotEmpty()) durations.average().toLong() else 0L,
            minSendDuration = durations.minOrNull() ?: 0L,
            maxSendDuration = durations.maxOrNull() ?: 0L,
            averageAttachmentSize = if (sizes.isNotEmpty()) sizes.average().toLong() else 0L,
            minAttachmentSize = sizes.minOrNull() ?: 0L,
            maxAttachmentSize = sizes.maxOrNull() ?: 0L,
            totalSamples = durations.size
        )
    }
    
    /**
     * 清理统计数据
     */
    fun clearStatistics() {
        cosMessagesSent.set(0)
        cosMessagesSuccess.set(0)
        cosMessagesFailed.set(0)
        signalMessagesSent.set(0)
        signalMessagesSuccess.set(0)
        signalMessagesFailed.set(0)
        fallbackCount.set(0)
        retryCount.set(0)
        sendDurations.clear()
        attachmentSizes.clear()
        
        sharedPreferences.edit().clear().apply()
        Log.i(TAG, "统计数据已清理")
    }
    
    /**
     * 导出统计数据
     */
    fun exportStatistics(): String {
        val overallStats = getOverallStatistics()
        val performanceStats = getPerformanceStatistics()
        val todayStats = getTodayStatistics()
        
        val exportData = mapOf(
            "overall" to overallStats,
            "performance" to performanceStats,
            "today" to todayStats,
            "exportTime" to System.currentTimeMillis()
        )
        
        return try {
            objectMapper.writeValueAsString(exportData)
        } catch (e: Exception) {
            Log.e(TAG, "导出统计数据失败", e)
            "{\"error\": \"导出失败\"}"
        }
    }
    
    /**
     * 记录发送持续时间
     */
    private fun recordSendDuration(messageId: Long) {
        val startTime = sendDurations.remove(messageId)
        if (startTime != null) {
            val duration = System.currentTimeMillis() - startTime
            // 这里可以记录到持续时间统计中
            Log.d(TAG, "发送持续时间: messageId=$messageId, duration=${duration}ms")
        }
    }
    
    /**
     * 计算成功率
     */
    private fun calculateSuccessRate(successCount: Long, totalCount: Long): Double {
        return if (totalCount > 0) {
            (successCount.toDouble() / totalCount.toDouble()) * 100.0
        } else {
            0.0
        }
    }
    
    /**
     * 计算回退率
     */
    private fun calculateFallbackRate(): Double {
        val totalCosAttempts = cosMessagesSent.get()
        return if (totalCosAttempts > 0) {
            (fallbackCount.get().toDouble() / totalCosAttempts.toDouble()) * 100.0
        } else {
            0.0
        }
    }
    
    /**
     * 计算平均发送时间
     */
    private fun calculateAverageSendDuration(): Long {
        val durations = sendDurations.values
        return if (durations.isNotEmpty()) {
            durations.average().toLong()
        } else {
            0L
        }
    }
    
    /**
     * 计算总传输数据量
     */
    private fun calculateTotalDataTransferred(): Long {
        return attachmentSizes.values.sum()
    }
    
    /**
     * 获取当前日期字符串
     */
    private fun getCurrentDateString(): String {
        val calendar = java.util.Calendar.getInstance()
        return "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.MONTH) + 1}-${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
    }
    
    /**
     * 保存统计数据
     */
    private fun saveStatistics() {
        try {
            val statisticsData = getOverallStatistics()
            val json = objectMapper.writeValueAsString(statisticsData)
            sharedPreferences.edit()
                .putString(KEY_STATISTICS_DATA, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存统计数据失败", e)
        }
    }
    
    /**
     * 加载统计数据
     */
    private fun loadStatistics() {
        try {
            val json = sharedPreferences.getString(KEY_STATISTICS_DATA, null)
            if (json != null) {
                val statistics = objectMapper.readValue<CosSendOverallStatistics>(json)
                cosMessagesSent.set(statistics.cosMessagesSent)
                cosMessagesSuccess.set(statistics.cosMessagesSuccess)
                cosMessagesFailed.set(statistics.cosMessagesFailed)
                signalMessagesSent.set(statistics.signalMessagesSent)
                signalMessagesSuccess.set(statistics.signalMessagesSuccess)
                signalMessagesFailed.set(statistics.signalMessagesFailed)
                fallbackCount.set(statistics.fallbackCount)
                retryCount.set(statistics.retryCount)
                
                Log.i(TAG, "统计数据加载完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载统计数据失败", e)
        }
    }
}

/**
 * COS发送总体统计信息
 */
data class CosSendOverallStatistics(
    val cosMessagesSent: Long,
    val cosMessagesSuccess: Long,
    val cosMessagesFailed: Long,
    val signalMessagesSent: Long,
    val signalMessagesSuccess: Long,
    val signalMessagesFailed: Long,
    val fallbackCount: Long,
    val retryCount: Long,
    val cosSuccessRate: Double,
    val signalSuccessRate: Double,
    val fallbackRate: Double,
    val averageSendDuration: Long,
    val totalDataTransferred: Long
)

/**
 * COS发送日统计信息
 */
data class CosSendDailyStatistics(
    val date: String,
    val cosMessagesSent: Long,
    val cosMessagesSuccess: Long,
    val signalMessagesSent: Long,
    val signalMessagesSuccess: Long,
    val fallbackCount: Long
) {
    companion object {
        fun empty(date: String) = CosSendDailyStatistics(date, 0, 0, 0, 0, 0)
    }
}

/**
 * COS发送性能统计信息
 */
data class CosSendPerformanceStatistics(
    val averageSendDuration: Long,
    val minSendDuration: Long,
    val maxSendDuration: Long,
    val averageAttachmentSize: Long,
    val minAttachmentSize: Long,
    val maxAttachmentSize: Long,
    val totalSamples: Int
)
