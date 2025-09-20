package org.thoughtcrime.securesms.tap.utils

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.database.TransportPollingStateTable
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.database.SignalDatabase
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*

/**
 * 传输消息去重器
 * 
 * 基于coscomm模块的MessageDeduplicationManager设计，提供持久化的去重机制，
 * 支持幂等性和防重放攻击。使用数据库进行持久化存储，确保重启后去重状态不丢失。
 */
class TransportMessageDeduplicator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportMessageDeduplicator::class.java)
        
        // 缓存配置
        private const val MAX_CACHE_SIZE = 1000
        private const val CACHE_CLEANUP_THRESHOLD = 800
        private const val MESSAGE_RETENTION_HOURS = 24 // 消息保留时间24小时
        
        // 去重键配置
        private const val DUPLICATION_KEY_SEPARATOR = ":"
        
        @Volatile
        private var INSTANCE: TransportMessageDeduplicator? = null
        
        /**
         * 获取TransportMessageDeduplicator单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TransportMessageDeduplicator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportMessageDeduplicator(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "创建TransportMessageDeduplicator实例: ${it.hashCode()}")
                }
            }
        }
        
        /**
         * 重置单例实例（仅用于测试）
         */
        @JvmStatic
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE?.let { instance ->
                    instance.cleanup()
                }
                INSTANCE = null
                Log.d(TAG, "重置TransportMessageDeduplicator实例")
            }
        }
    }
    
    // 数据库访问
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
    // JSON序列化
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    }
    
    // 线程安全
    private val lock = ReentrantReadWriteLock()
    
    // 内存缓存：已处理的消息去重键 - 基于coscomm的设计
    private val processedMessageKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    
    // 统计信息
    private var totalProcessedMessages = 0L
    private var duplicateMessagesFiltered = 0L
    private var lastCleanupTime = 0L
    
    init {
        // 启动时加载已处理的消息键
        loadProcessedMessageKeys()
        
        // 启动定期清理任务
        startPeriodicCleanup()
    }
    
    /**
     * 处理接收到的消息列表，进行去重和排序
     * 
     * @param recipientId 发送者ID
     * @param messages 接收到的消息列表
     * @return 去重后的新消息列表
     */
    fun processMessages(recipientId: String, messages: List<TransportMessage>): List<TransportMessage> {
        Log.d(TAG, "处理消息列表: recipientId=${LogSanitizer.sanitize(recipientId)}, count=${messages.size}")
        
        if (messages.isEmpty()) {
            return emptyList()
        }
        
        return lock.write {
            try {
                // 1. 基于messageId去重
                val uniqueMessages = messages.distinctBy { it.messageId }
                Log.d(TAG, "去重后消息数量: ${uniqueMessages.size}")
                
                // 2. 过滤已处理消息 - 使用coscomm的去重键策略
                val newMessages = uniqueMessages.filter { message ->
                    val duplicationKey = createDuplicationKey(message, recipientId)
                    val isNew = !isMessageProcessed(duplicationKey)
                    if (!isNew) {
                        Log.d(TAG, "过滤重复消息: messageId=${message.messageId}")
                        duplicateMessagesFiltered++
                    }
                    isNew
                }
                
                Log.d(TAG, "过滤已处理消息后数量: ${newMessages.size}")
                
                // 3. 按时间戳排序，保持消息顺序
                val sortedMessages = newMessages.sortedBy { it.timestamp }
                
                // 4. 更新统计信息
                totalProcessedMessages += sortedMessages.size
                
                Log.d(TAG, "去重处理完成: 新消息=${sortedMessages.size}, 总处理=${totalProcessedMessages}, 重复过滤=${duplicateMessagesFiltered}")
                
                sortedMessages
                
            } catch (e: Exception) {
                Log.e(TAG, "处理消息列表失败: recipientId=${LogSanitizer.sanitize(recipientId)}", e)
                // 发生异常时返回原始消息列表，确保不丢失消息
                messages
            }
        }
    }
    
    /**
     * 标记消息列表为已处理
     * 
     * @param messages 已处理的消息列表
     * @param recipientId 发送者ID
     */
    fun markMessagesAsProcessed(messages: List<TransportMessage>, recipientId: String) {
        lock.write {
            try {
                val processedKeys = mutableListOf<String>()
                val currentTime = System.currentTimeMillis()
                
                for (message in messages) {
                    val duplicationKey = createDuplicationKey(message, recipientId)
                    
                    // 添加到内存缓存
                    processedMessageKeys.add(duplicationKey)
                    processedKeys.add(duplicationKey)
                    
                    // 持久化到数据库
                    saveProcessedMessageKey(duplicationKey, message.timestamp)
                }
                
                Log.d(TAG, "标记消息已处理: recipientId=${LogSanitizer.sanitize(recipientId)}, count=${processedKeys.size}")
                
                // 检查是否需要清理缓存
                if (processedMessageKeys.size > MAX_CACHE_SIZE) {
                    cleanupMemoryCache()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "标记消息已处理失败: recipientId=${LogSanitizer.sanitize(recipientId)}", e)
            }
        }
    }
    
    /**
     * 创建消息去重键
     * 
     * 基于coscomm的去重键策略：messageId + senderId + timestamp
     * 这样可以确保即使messageId相同，但来自不同发送者或时间的消息也不会被误判为重复
     */
    private fun createDuplicationKey(message: TransportMessage, recipientId: String): String {
        return "${message.messageId}${DUPLICATION_KEY_SEPARATOR}${message.senderId}${DUPLICATION_KEY_SEPARATOR}${message.timestamp}"
    }
    
    /**
     * 检查消息是否已处理
     */
    private fun isMessageProcessed(duplicationKey: String): Boolean {
        // 首先检查内存缓存
        if (processedMessageKeys.contains(duplicationKey)) {
            return true
        }
        
        // 检查数据库
        return try {
            pollingStateTable.isMessageProcessed(duplicationKey)
        } catch (e: Exception) {
            Log.e(TAG, "检查消息处理状态失败: key=${LogSanitizer.sanitize(duplicationKey)}", e)
            false
        }
    }
    
    /**
     * 保存已处理消息键到数据库
     */
    private fun saveProcessedMessageKey(duplicationKey: String, timestamp: Long) {
        try {
            pollingStateTable.markMessageAsProcessed(duplicationKey, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "保存已处理消息键失败: key=${LogSanitizer.sanitize(duplicationKey)}", e)
        }
    }
    
    /**
     * 从数据库加载已处理的消息键
     */
    private fun loadProcessedMessageKeys() {
        try {
            val cutoffTime = System.currentTimeMillis() - (MESSAGE_RETENTION_HOURS * 3600 * 1000)
            val keys = pollingStateTable.getRecentProcessedMessageKeys(cutoffTime)
            
            processedMessageKeys.clear()
            processedMessageKeys.addAll(keys)
            
            Log.d(TAG, "从数据库加载已处理消息键: ${keys.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "加载已处理消息键失败", e)
        }
    }
    
    /**
     * 清理内存缓存
     */
    private fun cleanupMemoryCache() {
        try {
            if (processedMessageKeys.size <= CACHE_CLEANUP_THRESHOLD) {
                return
            }
            
            // 清理超过阈值的缓存项
            val keysToRemove = processedMessageKeys.size - CACHE_CLEANUP_THRESHOLD
            val iterator = processedMessageKeys.iterator()
            var removed = 0
            
            while (iterator.hasNext() && removed < keysToRemove) {
                iterator.next()
                iterator.remove()
                removed++
            }
            
            Log.d(TAG, "清理内存缓存: 移除=${removed}, 剩余=${processedMessageKeys.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理内存缓存失败", e)
        }
    }
    
    /**
     * 启动定期清理任务
     */
    private fun startPeriodicCleanup() {
        try {
            // 使用协程定期清理过期数据
            GlobalScope.launch(Dispatchers.IO) {
                while (true) {
                    try {
                        delay(5 * 60 * 1000L) // 5分钟清理一次
                        cleanupExpiredMessages()
                    } catch (e: Exception) {
                        Log.e(TAG, "定期清理任务异常", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动定期清理任务失败", e)
        }
    }
    
    /**
     * 清理过期的消息记录
     */
    private fun cleanupExpiredMessages() {
        lock.write {
            try {
                val cutoffTime = System.currentTimeMillis() - (MESSAGE_RETENTION_HOURS * 3600 * 1000)
                val cleanedCount = pollingStateTable.cleanupExpiredMessages(cutoffTime)
                
                if (cleanedCount > 0) {
                    Log.d(TAG, "清理过期消息记录: ${cleanedCount}条")
                    // 重新加载内存缓存
                    loadProcessedMessageKeys()
                }
                
                lastCleanupTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
                Log.e(TAG, "清理过期消息记录失败", e)
            }
        }
    }
    
    /**
     * 获取去重统计信息
     */
    fun getDeduplicationStatistics(): DeduplicationStatistics {
        return lock.read {
            DeduplicationStatistics(
                totalProcessedMessages = totalProcessedMessages,
                duplicateMessagesFiltered = duplicateMessagesFiltered,
                memoryCacheSize = processedMessageKeys.size,
                lastCleanupTime = lastCleanupTime
            )
        }
    }
    
    /**
     * 强制处理待处理消息（用于恢复场景）
     */
    fun forceProcessPendingMessages(recipientId: String): List<TransportMessage> {
        Log.d(TAG, "强制处理待处理消息: recipientId=${LogSanitizer.sanitize(recipientId)}")
        // 对于tap模块，这个方法暂时返回空列表
        // 在实际实现中，可以从数据库或缓存中获取待处理的消息
        return emptyList()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        lock.write {
            try {
                processedMessageKeys.clear()
                Log.d(TAG, "清理TransportMessageDeduplicator资源")
            } catch (e: Exception) {
                Log.e(TAG, "清理资源失败", e)
            }
        }
    }
    
    /**
     * 去重统计信息数据类
     */
    data class DeduplicationStatistics(
        val totalProcessedMessages: Long,
        val duplicateMessagesFiltered: Long,
        val memoryCacheSize: Int,
        val lastCleanupTime: Long
    ) {
        val duplicateFilterRate: Double
            get() = if (totalProcessedMessages > 0) {
                duplicateMessagesFiltered.toDouble() / totalProcessedMessages.toDouble()
            } else {
                0.0
            }
    }
} 