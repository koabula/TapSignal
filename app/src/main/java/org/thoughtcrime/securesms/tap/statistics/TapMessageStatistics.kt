package org.thoughtcrime.securesms.tap.statistics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * TAP消息统计管理器
 * 记录和管理TAP传输层的各种统计信息
 */
class TapMessageStatistics private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageStatistics::class.java)
        
        @Volatile
        private var INSTANCE: TapMessageStatistics? = null
        
        fun getInstance(context: Context): TapMessageStatistics {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageStatistics(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 内存统计缓存（用于快速访问热点数据）
    private val messageCountByType = ConcurrentHashMap<TransportMessageType, AtomicLong>()
    private val messageCountBySender = ConcurrentHashMap<String, AtomicLong>()
    private val totalMessagesReceived = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val successCount = AtomicLong(0)
    
    // 统计数据持久化
    private val statisticsTable = TapMessageStatisticsTable.getInstance(context)
    
    init {
        // 初始化时从数据库加载统计数据
        loadStatisticsFromDatabase()
    }
    
    /**
     * 记录消息接收统计
     */
    fun recordMessageReceived(message: TransportMessage) {
        try {
            // 更新内存统计
            updateMemoryStatistics(message)
            
            // 异步更新数据库统计
            updateDatabaseStatistics(message)
            
            Log.d(TAG, "统计记录成功: messageId=${message.messageId}, " +
                    "type=${message.messageType}, " +
                    "sender=${message.senderId}, " +
                    "size=${message.signalCiphertext.length}")
                    
        } catch (e: Exception) {
            Log.e(TAG, "记录消息统计失败: messageId=${message.messageId}", e)
            errorCount.incrementAndGet()
        }
    }
    
    /**
     * 记录消息处理成功
     */
    fun recordMessageProcessingSuccess(messageId: String) {
        successCount.incrementAndGet()
        statisticsTable.recordProcessingResult(messageId, true, null)
        Log.d(TAG, "记录处理成功: messageId=$messageId")
    }
    
    /**
     * 记录消息处理失败
     */
    fun recordMessageProcessingError(messageId: String, error: String) {
        errorCount.incrementAndGet()
        statisticsTable.recordProcessingResult(messageId, false, error)
        Log.d(TAG, "记录处理失败: messageId=$messageId, error=$error")
    }
    
    /**
     * 更新内存统计
     */
    private fun updateMemoryStatistics(message: TransportMessage) {
        // 按消息类型统计
        messageCountByType.computeIfAbsent(message.messageType) { AtomicLong(0) }
            .incrementAndGet()
        
        // 按发送者统计
        messageCountBySender.computeIfAbsent(message.senderId) { AtomicLong(0) }
            .incrementAndGet()
        
        // 总数统计
        totalMessagesReceived.incrementAndGet()
        totalBytesReceived.addAndGet(message.signalCiphertext.length.toLong())
    }
    
    /**
     * 异步更新数据库统计
     */
    private fun updateDatabaseStatistics(message: TransportMessage) {
        // 创建统计记录
        val record = MessageStatisticsRecord(
            messageId = message.messageId,
            timestamp = message.timestamp,
            senderId = message.senderId,
            recipientId = message.recipientId,
            messageType = message.messageType.name,
            messageSizeBytes = message.signalCiphertext.length,
            attachmentCount = message.attachments.size,
            totalAttachmentSizeBytes = message.attachments.sumOf { it.size },
            processingStatus = "received", // 初始状态为已接收
            errorMessage = null,
            createdAt = System.currentTimeMillis()
        )
        
        // 异步保存到数据库
        statisticsTable.insertStatisticsRecord(record)
    }
    
    /**
     * 从数据库加载统计数据到内存
     */
    private fun loadStatisticsFromDatabase() {
        try {
            val recentStats = statisticsTable.getRecentStatistics(24 * 60 * 60 * 1000L) // 最近24小时
            
            var totalMessages = 0L
            var totalBytes = 0L
            var errors = 0L
            var successes = 0L
            
            val typeMap = mutableMapOf<TransportMessageType, Long>()
            val senderMap = mutableMapOf<String, Long>()
            
            for (record in recentStats) {
                totalMessages++
                totalBytes += record.messageSizeBytes
                
                when (record.processingStatus) {
                    "success" -> successes++
                    "failed" -> errors++
                }
                
                // 按类型统计
                try {
                    val messageType = TransportMessageType.valueOf(record.messageType)
                    typeMap[messageType] = typeMap.getOrDefault(messageType, 0) + 1
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "未知的消息类型: ${record.messageType}")
                }
                
                // 按发送者统计
                senderMap[record.senderId] = senderMap.getOrDefault(record.senderId, 0) + 1
            }
            
            // 更新内存缓存
            totalMessagesReceived.set(totalMessages)
            totalBytesReceived.set(totalBytes)
            errorCount.set(errors)
            successCount.set(successes)
            
            // 更新类型统计
            messageCountByType.clear()
            typeMap.forEach { (type, count) ->
                messageCountByType[type] = AtomicLong(count)
            }
            
            // 更新发送者统计
            messageCountBySender.clear()
            senderMap.forEach { (sender, count) ->
                messageCountBySender[sender] = AtomicLong(count)
            }
            
            Log.i(TAG, "统计数据加载完成: messages=$totalMessages, bytes=$totalBytes, " +
                    "errors=$errors, successes=$successes")
                    
        } catch (e: Exception) {
            Log.e(TAG, "加载统计数据失败", e)
        }
    }
    
    /**
     * 获取统计摘要
     */
    fun getStatisticsSummary(): StatisticsSummary {
        return StatisticsSummary(
            totalMessagesReceived = totalMessagesReceived.get(),
            totalBytesReceived = totalBytesReceived.get(),
            successCount = successCount.get(),
            errorCount = errorCount.get(),
            messageCountByType = messageCountByType.mapValues { it.value.get() },
            messageCountBySender = messageCountBySender.mapValues { it.value.get() },
            uptime = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * 获取详细统计报告
     */
    fun getDetailedStatistics(timeRangeMs: Long = 24 * 60 * 60 * 1000L): DetailedStatistics {
        val recentRecords = statisticsTable.getRecentStatistics(timeRangeMs)
        
        return DetailedStatistics(
            summary = getStatisticsSummary(),
            recentRecords = recentRecords,
            timeRangeMs = timeRangeMs,
            generatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 清理过期统计数据
     */
    fun cleanupExpiredStatistics(retentionDays: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        val deletedCount = statisticsTable.deleteOldStatistics(cutoffTime)
        Log.i(TAG, "清理过期统计数据: 删除 $deletedCount 条记录")
    }
    
    private val startTime = System.currentTimeMillis()
}

/**
 * 消息统计记录
 */
data class MessageStatisticsRecord(
    val messageId: String,
    val timestamp: Long,
    val senderId: String,
    val recipientId: String,
    val messageType: String,
    val messageSizeBytes: Int,
    val attachmentCount: Int,
    val totalAttachmentSizeBytes: Long,
    val processingStatus: String, // "received", "processing", "success", "failed"
    val errorMessage: String?,
    val createdAt: Long
)

/**
 * 统计摘要
 */
data class StatisticsSummary(
    val totalMessagesReceived: Long,
    val totalBytesReceived: Long,
    val successCount: Long,
    val errorCount: Long,
    val messageCountByType: Map<TransportMessageType, Long>,
    val messageCountBySender: Map<String, Long>,
    val uptime: Long
) {
    val successRate: Double
        get() = if (totalMessagesReceived > 0) successCount.toDouble() / totalMessagesReceived else 0.0
        
    val errorRate: Double
        get() = if (totalMessagesReceived > 0) errorCount.toDouble() / totalMessagesReceived else 0.0
        
    val averageMessageSize: Double
        get() = if (totalMessagesReceived > 0) totalBytesReceived.toDouble() / totalMessagesReceived else 0.0
}

/**
 * 详细统计信息
 */
data class DetailedStatistics(
    val summary: StatisticsSummary,
    val recentRecords: List<MessageStatisticsRecord>,
    val timeRangeMs: Long,
    val generatedAt: Long
) 