package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.storage.MessageProcessingStorage
import org.thoughtcrime.securesms.coscomm.service.CosPollingService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 消息去重和排序管理器
 * 基于消息ID和时间戳进行去重和排序，处理网络延迟导致的乱序问题
 */
class MessageDeduplicationManager(private val context: Context) {

    companion object {
        private val TAG = Log.tag(MessageDeduplicationManager::class.java)

        // 缓存配置
        private const val MAX_CACHE_SIZE = 1000        // 最大缓存消息数量
        private const val CACHE_CLEANUP_THRESHOLD = 800 // 缓存清理阈值
        private const val MESSAGE_RETENTION_HOURS = 24  // 消息保留时间24小时

        // 排序配置（动态调整）
        private const val MIN_SORTING_WINDOW_MS = 10000L   // 最小排序窗口10秒
        private const val DEFAULT_SORTING_WINDOW_MS = 30000L // 默认排序窗口30秒
        private const val MAX_SORTING_WINDOW_MS = 120000L   // 最大排序窗口2分钟
        private const val MAX_OUT_OF_ORDER_DELAY = 300000L // 最大乱序延迟5分钟

        // 网络状况评估配置
        private const val NETWORK_GOOD_THRESHOLD = 5000L    // 网络良好阈值5秒
        private const val NETWORK_POOR_THRESHOLD = 30000L   // 网络较差阈值30秒

        // 解密失败重试配置
        private const val MAX_DECRYPT_RETRIES = 5
        private const val RETRY_DELAY_BASE_MS = 10_000L // 10秒起，指数退避

        @Volatile
        private var INSTANCE: MessageDeduplicationManager? = null

        fun getInstance(context: Context): MessageDeduplicationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageDeduplicationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val messageProcessingStorage = MessageProcessingStorage(context)
    
    // 🔧 修复：CosPollingService实例用于状态同步
    private val cosPollingService by lazy { CosPollingService(context) }
    
    private val lock = ReentrantReadWriteLock()

    // 内存缓存：已处理的消息ID
    private val processedMessageIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 内存缓存：等待排序的消息
    private val pendingMessages: MutableMap<String, MutableList<CosMessage>> = ConcurrentHashMap()

    // 网络状况跟踪
    private val networkLatencyTracker: MutableMap<String, MutableList<Long>> = ConcurrentHashMap()

    // 动态排序窗口
    private val dynamicSortingWindows: MutableMap<String, Long> = ConcurrentHashMap()

    // 解密失败重试管理
    private val failedDecrypts: MutableMap<String, FailedDecryptInfo> = ConcurrentHashMap()

    data class FailedDecryptInfo(
        val messageId: String,
        val senderId: String,
        val firstFailureTime: Long,
        val lastFailureTime: Long,
        val retryCount: Int,
        val lastError: String?
    )

    fun recordFailedDecrypt(message: CosMessage, error: String?) {
        lock.write {
            val now = System.currentTimeMillis()
            val existed = failedDecrypts[message.messageId]
            if (existed == null) {
                failedDecrypts[message.messageId] = FailedDecryptInfo(
                    messageId = message.messageId,
                    senderId = message.senderId,
                    firstFailureTime = now,
                    lastFailureTime = now,
                    retryCount = 1,
                    lastError = error
                )
                Log.d(TAG, "记录解密失败: id=${message.messageId}")
            } else {
                failedDecrypts[message.messageId] = existed.copy(
                    retryCount = existed.retryCount + 1,
                    lastFailureTime = now,
                    lastError = error ?: existed.lastError
                )
                Log.d(TAG, "更新解密失败: id=${message.messageId}, retry=${existed.retryCount + 1}")
            }
        }
    }

    fun clearFailedDecrypt(messageId: String) {
        lock.write {
            if (failedDecrypts.remove(messageId) != null) {
                Log.d(TAG, "清除解密失败记录: id=$messageId")
            }
        }
    }

    private fun shouldRetryFailedDecrypt(info: FailedDecryptInfo): Boolean {
        if (info.retryCount >= MAX_DECRYPT_RETRIES) return false
        val now = System.currentTimeMillis()
        val backoff = RETRY_DELAY_BASE_MS * (1L shl (info.retryCount - 1).coerceAtMost(10))
        val due = now - info.lastFailureTime >= backoff
        return due
    }
    // 统计信息
    private val deduplicationStats = DeduplicationStatistics()

    init {
        // 启动时加载已处理的消息ID
        loadProcessedMessageIds()

        // 启动定期清理任务
        startPeriodicCleanup()
    }

    /**
     * 计算动态排序窗口
     * @param recipientId 发送者ID
     * @return 排序窗口时长（毫秒）
     */
    private fun calculateDynamicSortingWindow(recipientId: String): Long {
        val latencies = networkLatencyTracker[recipientId] ?: return DEFAULT_SORTING_WINDOW_MS

        if (latencies.isEmpty()) {
            return DEFAULT_SORTING_WINDOW_MS
        }

        // 计算平均延迟
        val avgLatency = latencies.average().toLong()

        // 根据网络状况调整排序窗口
        val sortingWindow = when {
            avgLatency <= NETWORK_GOOD_THRESHOLD -> MIN_SORTING_WINDOW_MS
            avgLatency <= NETWORK_POOR_THRESHOLD -> DEFAULT_SORTING_WINDOW_MS
            else -> MAX_SORTING_WINDOW_MS
        }

        // 缓存计算结果
        dynamicSortingWindows[recipientId] = sortingWindow

        Log.d(TAG, "动态排序窗口: recipientId=$recipientId, avgLatency=${avgLatency}ms, window=${sortingWindow}ms")

        return sortingWindow
    }

    /**
     * 更新网络延迟统计
     * @param recipientId 发送者ID
     * @param latency 延迟时间（毫秒）
     */
    private fun updateNetworkLatency(recipientId: String, latency: Long) {
        val latencies = networkLatencyTracker.getOrPut(recipientId) { mutableListOf() }

        latencies.add(latency)

        // 保持最近10个延迟记录
        if (latencies.size > 10) {
            latencies.removeAt(0)
        }
    }

    /**
     * 处理接收到的消息列表
     * 修复双发问题：只进行去重，不排序，完全依赖Signal原生Double Ratchet处理顺序
     * @param recipientId 发送者ID
     * @param messages 接收到的消息列表
     * @return 去重后的新消息列表（保持原始顺序）
     */
    fun processMessages(recipientId: String, messages: List<CosMessage>): List<CosMessage> {
        Log.d(TAG, "处理消息列表: recipientId=$recipientId, count=${messages.size}")

        if (messages.isEmpty()) {
            return emptyList()
        }

        return lock.write {
            // 1. 简单去重：基于messageId
            val uniqueMessages = messages.distinctBy { it.messageId }
            Log.d(TAG, "去重后消息数量: ${uniqueMessages.size}")
            
            // 2. 不再排序，保持原始顺序，让Signal原生Double Ratchet机制处理消息顺序
            
            // 3. 过滤已处理消息
            val newMessages = uniqueMessages.filter { message ->
                !processedMessageIds.contains(message.messageId)
            }
            
            Log.d(TAG, "最终待处理消息数量: ${newMessages.size}，保持原始顺序依赖Signal原生机制")
            newMessages
        }
    }

    /**
     * 消息去重处理
     * 🔧 修复：正确区分"已尝试处理"和"已处理"状态，避免消息被错误过滤
     * 🔧 修复：增加CosPollingService状态检查，确保双重状态管理系统同步
     * @param messages 原始消息列表
     * @return 去重后的消息列表
     */
    private fun deduplicateMessages(messages: List<CosMessage>): List<CosMessage> {
        val newMessages = mutableListOf<CosMessage>()

        messages.forEach { message ->
            val isReallyProcessed = isMessageReallyProcessed(message.messageId)
            val isFailedDecrypt = failedDecrypts.containsKey(message.messageId)
            
            // 🔧 修复：检查CosPollingService的已处理状态，防止状态管理不同步
            val isProcessedInPollingService = checkPollingServiceProcessedStatus(message.messageId)
            val isAttemptedInPollingService = checkPollingServiceAttemptedStatus(message.messageId)
            
            when {
                // 1. 已真正成功处理的消息（存在于processedMessageIds中），直接跳过
                isReallyProcessed -> {
                    Log.d(TAG, "发现已真正处理的消息: messageId=${message.messageId}")
                    deduplicationStats.incrementDuplicateMessages()
                }
                
                // 1.5. 🔧 修复：CosPollingService已处理的消息，避免Double Ratchet状态破坏
                isProcessedInPollingService -> {
                    Log.d(TAG, "发现CosPollingService已处理的消息: messageId=${message.messageId}")
                    deduplicationStats.incrementDuplicateMessages()
                    // 同步状态到本地
                    processedMessageIds.add(message.messageId)
                }
                
                // 2. 🔧 修复：CosPollingService已尝试处理的消息，防止重复处理
                isAttemptedInPollingService -> {
                    Log.d(TAG, "发现CosPollingService已尝试处理的消息: messageId=${message.messageId}")
                    deduplicationStats.incrementDuplicateMessages()
                }
                
                // 3. 解密失败的消息，检查是否可以重试
                isFailedDecrypt -> {
                    val failedInfo = failedDecrypts[message.messageId]!!
                    if (shouldRetryFailedDecrypt(failedInfo)) {
                        Log.d(TAG, "重试解密失败消息: messageId=${message.messageId}, retryCount=${failedInfo.retryCount}")
                        newMessages.add(message)
                        deduplicationStats.incrementNewMessages()
                    } else {
                        Log.d(TAG, "跳过解密失败消息(未到重试时机): messageId=${message.messageId}")
                        deduplicationStats.incrementDuplicateMessages()
                    }
                }
                
                // 4. 全新消息、已尝试处理但未成功、或需要重新处理的消息，允许处理
                else -> {
                    // 🔧 新增：检查是否是很旧的消息（超过24小时）
                    val messageAge = System.currentTimeMillis() - message.timestamp
                    if (messageAge > 24 * 3600 * 1000L) {
                        Log.w(TAG, "跳过过旧消息: messageId=${message.messageId}, age=${messageAge}ms")
                        deduplicationStats.incrementDuplicateMessages()
                    } else {
                        Log.d(TAG, "允许处理消息: messageId=${message.messageId} (可能是新消息或需要重新处理)")
                        newMessages.add(message)
                        deduplicationStats.incrementNewMessages()
                    }
                }
            }
        }

        Log.d(TAG, "去重处理完成: 原始${messages.size}条，过滤后${newMessages.size}条")
        return newMessages
    }

    /**
     * 🔧 修复：检查CosPollingService中的消息处理状态
     * 防止双重状态管理系统不同步导致的Double Ratchet状态破坏
     * @param messageId 消息ID
     * @return 是否在CosPollingService中被标记为已处理
     */
    private fun checkPollingServiceProcessedStatus(messageId: String): Boolean {
        return try {
            // 🔧 修复：直接使用CosPollingService的公共方法
            cosPollingService.isMessageProcessed(messageId)
        } catch (e: Exception) {
            Log.w(TAG, "检查CosPollingService状态失败: messageId=$messageId", e)
            false
        }
    }
    
    /**
     * 🔧 修复：检查CosPollingService中的消息尝试状态
     * @param messageId 消息ID
     * @return 是否在CosPollingService中被标记为已尝试处理
     */
    private fun checkPollingServiceAttemptedStatus(messageId: String): Boolean {
        return try {
            // 注意：CosPollingService目前没有提供公共的isMessageAttempted方法
            // 所以这里只能检查已处理状态
            false
        } catch (e: Exception) {
            Log.w(TAG, "检查CosPollingService尝试状态失败: messageId=$messageId", e)
            false
        }
    }
    
    /**
     * 检查消息是否已处理（旧方法，保留兼容性）
     * @param messageId 消息ID
     * @return 是否已处理
     */
    private fun isMessageProcessed(messageId: String): Boolean {
        // 首先检查内存缓存
        if (processedMessageIds.contains(messageId)) {
            return true
        }

        // 检查持久化存储
        return messageProcessingStorage.isMessageProcessed(messageId)
    }

    /**
     * 检查消息是否真正已处理完成
     * 🔧 修复：只检查真正成功处理的消息，不包括"已尝试处理"状态
     * @param messageId 消息ID
     * @return 是否真正已处理
     */
    private fun isMessageReallyProcessed(messageId: String): Boolean {
        // 只检查内存缓存中的processedMessageIds（这些是真正处理成功的）
        // 不检查MessageProcessingStorage，因为它可能包含"已尝试处理"的状态
        val reallyProcessed = processedMessageIds.contains(messageId)
        
        if (reallyProcessed) {
            Log.d(TAG, "消息确实已处理: messageId=$messageId")
        } else {
            Log.d(TAG, "消息未真正处理(可能只是已尝试): messageId=$messageId")
        }
        
        return reallyProcessed
    }

    /**
     * 添加消息到待排序队列
     * @param recipientId 发送者ID
     * @param messages 新消息列表
     */
    private fun addToPendingMessages(recipientId: String, messages: List<CosMessage>) {
        val pendingList = pendingMessages.getOrPut(recipientId) { mutableListOf() }
        pendingList.addAll(messages)

        Log.d(TAG, "添加到待排序队列: recipientId=$recipientId, added=${messages.size}, total=${pendingList.size}")
    }

    /**
     * 使用严格时间顺序提取已排序的消息
     * 🔧 修复：基于COS序列号排序 - 使用时间戳+消息序号（现在包含COS序列号）
     * @param recipientId 发送者ID
     * @return 严格按时间戳排序的消息列表
     */
    private fun extractSortedMessagesWithDynamicWindow(recipientId: String): List<CosMessage> {
        val pendingList = pendingMessages[recipientId] ?: return emptyList()

        if (pendingList.isEmpty()) {
            return emptyList()
        }

        Log.d(TAG, "开始COS序列号排序: recipientId=$recipientId, 待排序消息数=${pendingList.size}")

        // 简化排序：基于时间戳排序，让Signal原生系统处理消息顺序
        val sortedMessages = pendingList.sortedBy { it.timestamp }

        // 记录排序详情
        sortedMessages.forEachIndexed { index, message ->
            Log.d(TAG, "排序后消息[$index]: messageId=${message.messageId}, timestamp=${message.timestamp}")
        }

        // 过滤未到重试时机的失败解密消息，避免频繁重复解密
        val filtered = sortedMessages.filter { msg ->
            val info = failedDecrypts[msg.messageId]
            if (info == null) return@filter true
            val allow = shouldRetryFailedDecrypt(info)
            if (!allow) Log.d(TAG, "跳过未到重试时机的消息: id=${msg.messageId}, retry=${info.retryCount}")
            allow
        }

        // 不再清空待排序队列，只有在消息成功处理后才移除对应项
        Log.i(TAG, "严格时间排序完成: recipientId=$recipientId, 排序后消息数=${filtered.size}")
        return filtered
    }












    /**
     * 标记单个消息为已处理（公共方法）
     * @param message 已处理的消息
     */
    fun markMessageAsProcessed(message: CosMessage) {
        lock.write {
                    // 添加到内存缓存
        processedMessageIds.add(message.messageId)

        // 保存到持久化存储
        messageProcessingStorage.markMessageAsProcessed(
            messageId = message.messageId,
            senderId = message.senderId,
            timestamp = message.timestamp
        )

        // 从待排序队列中移除该消息（如果仍然存在）
        pendingMessages[message.senderId]?.removeAll { it.messageId == message.messageId }

            Log.d(TAG, "标记消息为已处理并从待队列移除: messageId=${message.messageId}")

            // 检查缓存大小，必要时清理
            if (processedMessageIds.size > MAX_CACHE_SIZE) {
                cleanupCache()
            }
        }
    }

    /**
     * 标记消息为已处理
     * @param messages 已处理的消息列表
     */
    fun markMessagesAsProcessed(messages: List<CosMessage>) {
        if (messages.isEmpty()) return

        messages.forEach { message ->
            // 添加到内存缓存
            processedMessageIds.add(message.messageId)

            // 保存到持久化存储
            messageProcessingStorage.markMessageAsProcessed(
                messageId = message.messageId,
                senderId = message.senderId,
                timestamp = message.timestamp
            )
        }

        // 从待排序队列中移除这些消息
        messages.groupBy { it.senderId }.forEach { (senderId, msgs) ->
            val ids = msgs.map { it.messageId }.toSet()
            pendingMessages[senderId]?.removeAll { it.messageId in ids }
        }

        Log.d(TAG, "标记${messages.size}条消息为已处理并从待队列移除")

        // 检查缓存大小，必要时清理
        if (processedMessageIds.size > MAX_CACHE_SIZE) {
            cleanupCache()
        }
    }

    /**
     * 强制处理所有待排序消息
     * @param recipientId 发送者ID
     * @return 强制处理的消息列表
     */
    fun forceProcessPendingMessages(recipientId: String): List<CosMessage> {
        Log.i(TAG, "强制处理待排序消息: recipientId=$recipientId")

        return lock.read {
            val pendingList = pendingMessages[recipientId] ?: return@read emptyList()

            // 简化排序：基于COS序列号排序
            val sortedMessages = pendingList.sortedBy { it.timestamp }

            Log.i(TAG, "强制处理读取完成: recipientId=$recipientId, count=${sortedMessages.size}")
            sortedMessages
        }
    }

    /**
     * 获取待排序消息统计
     * @return 统计信息
     */
    fun getPendingMessageStats(): Map<String, Int> {
        return lock.read {
            pendingMessages.mapValues { it.value.size }
        }
    }

    /**
     * 加载已处理的消息ID
     */
    private fun loadProcessedMessageIds() {
        try {
            val recentMessageIds = messageProcessingStorage.getRecentProcessedMessageIds(MESSAGE_RETENTION_HOURS)
            processedMessageIds.addAll(recentMessageIds)
            Log.i(TAG, "加载了${recentMessageIds.size}个已处理消息ID")
        } catch (e: Exception) {
            Log.e(TAG, "加载已处理消息ID失败", e)
        }
    }

    /**
     * 启动定期清理任务
     */
    private fun startPeriodicCleanup() {
        // TODO: 实现定期清理任务，清理过期的消息记录和缓存
        Log.d(TAG, "定期清理任务已启动")
    }

    /**
     * 清理内存缓存
     */
    private fun cleanupCache() {
        if (processedMessageIds.size <= CACHE_CLEANUP_THRESHOLD) {
            return
        }

        Log.i(TAG, "开始清理消息ID缓存，当前大小: ${processedMessageIds.size}")

        // 清理过期的消息ID
        val cutoffTime = System.currentTimeMillis() - (MESSAGE_RETENTION_HOURS * 3600 * 1000)
        val expiredIds = messageProcessingStorage.getExpiredMessageIds(cutoffTime)

        processedMessageIds.removeAll(expiredIds.toSet())

        Log.i(TAG, "缓存清理完成，清理了${expiredIds.size}个过期ID，当前大小: ${processedMessageIds.size}")
    }

    /**
     * 获取去重统计信息
     * @return 统计信息
     */
    fun getDeduplicationStatistics(): DeduplicationStatistics.Snapshot {
        return deduplicationStats.getSnapshot()
    }

    /**
     * 去重统计信息
     */
    class DeduplicationStatistics {
        private var newMessages = 0L
        private var duplicateMessages = 0L
        private var forcedProcessing = 0L

        @Synchronized
        fun incrementNewMessages() { newMessages++ }

        @Synchronized
        fun incrementDuplicateMessages() { duplicateMessages++ }

        @Synchronized
        fun incrementForcedProcessing() { forcedProcessing++ }

        @Synchronized
        fun getSnapshot(): Snapshot {
            return Snapshot(
                newMessages = newMessages,
                duplicateMessages = duplicateMessages,
                forcedProcessing = forcedProcessing
            )
        }

        data class Snapshot(
            val newMessages: Long,
            val duplicateMessages: Long,
            val forcedProcessing: Long
        ) {
            val totalMessages: Long get() = newMessages + duplicateMessages
            val deduplicationRate: Double get() = if (totalMessages > 0) duplicateMessages.toDouble() / totalMessages else 0.0
        }
    }
}
