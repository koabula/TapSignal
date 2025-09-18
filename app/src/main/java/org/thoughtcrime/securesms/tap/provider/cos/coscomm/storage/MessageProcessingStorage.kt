package org.thoughtcrime.securesms.tap.provider.cos.coscomm.storage

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.signal.core.util.logging.Log

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 消息处理存储管理器
 * 负责持久化存储已处理消息的记录和序列号信息
 */
class MessageProcessingStorage(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(MessageProcessingStorage::class.java)
        
        // SharedPreferences文件名
        private const val PREFS_NAME = "cos_message_processing"
        
        // 存储键名
        private const val KEY_PROCESSED_MESSAGES = "processed_messages"
        private const val KEY_LAST_SEQUENCE_NUMBER = "last_sequence_number"
        private const val KEY_MESSAGE_TIMESTAMPS = "message_timestamps"
        
        // 清理配置
        private const val MAX_STORED_MESSAGES = 10000
        private const val CLEANUP_BATCH_SIZE = 1000
    }
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val lock = ReentrantReadWriteLock()
    
    /**
     * 标记消息为已处理
     * @param messageId 消息ID
     * @param senderId 发送者ID
     * @param timestamp 消息时间戳
     */
    fun markMessageAsProcessed(
        messageId: String,
        senderId: String,
        timestamp: Long
    ) {
        lock.write {
            try {
                // 保存已处理消息ID
                saveProcessedMessageId(messageId, timestamp)
                
                Log.d(TAG, "标记消息已处理: messageId=$messageId, senderId=$senderId")
            } catch (e: Exception) {
                Log.e(TAG, "标记消息已处理失败: messageId=$messageId", e)
            }
        }
    }
    
    /**
     * 检查消息是否已处理
     * @param messageId 消息ID
     * @return 是否已处理
     */
    fun isMessageProcessed(messageId: String): Boolean {
        return lock.read {
            try {
                val processedMessages = getProcessedMessages()
                processedMessages.containsKey(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "检查消息处理状态失败: messageId=$messageId", e)
                false
            }
        }
    }
    
    /**
     * 获取最近处理的消息ID列表
     * @param retentionHours 保留时间（小时）
     * @return 消息ID列表
     */
    fun getRecentProcessedMessageIds(retentionHours: Int): List<String> {
        return lock.read {
            try {
                val cutoffTime = System.currentTimeMillis() - (retentionHours * 3600 * 1000)
                val processedMessages = getProcessedMessages()
                
                processedMessages.filterValues { timestamp ->
                    timestamp >= cutoffTime
                }.keys.toList()
            } catch (e: Exception) {
                Log.e(TAG, "获取最近处理消息ID失败", e)
                emptyList()
            }
        }
    }
    
    /**
     * 获取过期的消息ID列表
     * @param cutoffTime 截止时间
     * @return 过期的消息ID列表
     */
    fun getExpiredMessageIds(cutoffTime: Long): List<String> {
        return lock.read {
            try {
                val processedMessages = getProcessedMessages()
                
                processedMessages.filterValues { timestamp ->
                    timestamp < cutoffTime
                }.keys.toList()
            } catch (e: Exception) {
                Log.e(TAG, "获取过期消息ID失败", e)
                emptyList()
            }
        }
    }
    
    /**
     * 获取最后处理的序列号
     * @param senderId 发送者ID
     * @return 序列号，如果不存在返回null
     */
    fun getLastProcessedSequenceNumber(senderId: String): Long? {
        return lock.read {
            try {
                val lastSequenceMap = getLastSequenceMap()
                val sequenceNumber = lastSequenceMap[senderId]
                
                if (sequenceNumber != null) {
                    sequenceNumber.toLong()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取最后序列号失败: senderId=$senderId", e)
                null
            }
        }
    }
    
    /**
     * 清理过期的消息记录
     * @param retentionHours 保留时间（小时）
     * @return 清理的记录数量
     */
    fun cleanupExpiredMessages(retentionHours: Int): Int {
        return lock.write {
            try {
                val cutoffTime = System.currentTimeMillis() - (retentionHours * 3600 * 1000)
                val processedMessages = getProcessedMessages().toMutableMap()
                
                val expiredIds = processedMessages.filterValues { timestamp ->
                    timestamp < cutoffTime
                }.keys
                
                if (expiredIds.isNotEmpty()) {
                    expiredIds.forEach { messageId ->
                        processedMessages.remove(messageId)
                    }
                    
                    saveProcessedMessages(processedMessages)
                    Log.i(TAG, "清理了${expiredIds.size}条过期消息记录")
                }
                
                expiredIds.size
            } catch (e: Exception) {
                Log.e(TAG, "清理过期消息失败", e)
                0
            }
        }
    }
    
    /**
     * 重置指定发送者的序列号状态
     * 用于处理严重的序列号异常情况
     * @param senderId 发送者ID
     */
    fun resetSequenceState(senderId: String) {
        lock.write {
            try {
                val lastSequenceMap = getLastSequenceMap().toMutableMap()
                lastSequenceMap.remove(senderId)
                saveLastSequenceMap(lastSequenceMap)
                
                Log.w(TAG, "重置发送者的序列号状态: senderId=$senderId")
            } catch (e: Exception) {
                Log.e(TAG, "重置序列号状态失败: senderId=$senderId", e)
            }
        }
    }

    /**
     * 获取存储统计信息
     * @return 统计信息
     */
    fun getStorageStatistics(): StorageStatistics {
        return lock.read {
            try {
                val processedMessages = getProcessedMessages()
                val lastSequenceMap = getLastSequenceMap()
                
                StorageStatistics(
                    processedMessageCount = processedMessages.size,
                    sequenceInfoCount = lastSequenceMap.size,
                    oldestMessageTime = processedMessages.values.minOrNull() ?: 0L,
                    newestMessageTime = processedMessages.values.maxOrNull() ?: 0L
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取存储统计信息失败", e)
                StorageStatistics(0, 0, 0L, 0L)
            }
        }
    }
    
    /**
     * 保存已处理消息ID
     */
    private fun saveProcessedMessageId(messageId: String, timestamp: Long) {
        val processedMessages = getProcessedMessages().toMutableMap()
        processedMessages[messageId] = timestamp
        
        // 检查是否需要清理旧记录
        if (processedMessages.size > MAX_STORED_MESSAGES) {
            cleanupOldMessages(processedMessages)
        }
        
        saveProcessedMessages(processedMessages)
    }
    
    /**
     * 清理最旧的消息记录
     */
    private fun cleanupOldMessages(processedMessages: MutableMap<String, Long>) {
        if (processedMessages.size <= MAX_STORED_MESSAGES) {
            return
        }
        
        // 按时间戳排序，移除最旧的记录
        val sortedEntries = processedMessages.entries.sortedBy { it.value }
        val toRemove = sortedEntries.take(CLEANUP_BATCH_SIZE)
        
        toRemove.forEach { entry ->
            processedMessages.remove(entry.key)
        }
        
        Log.i(TAG, "清理了${toRemove.size}条最旧的消息记录")
    }
    
    /**
     * 更新最后处理的Ratchet信息
     */
    private fun updateLastSequenceNumber(senderId: String, sequenceNumber: Long) {
        val lastSequenceMap = getLastSequenceMap().toMutableMap()
        lastSequenceMap[senderId] = sequenceNumber.toString()
        
        saveLastSequenceMap(lastSequenceMap)
    }
    
    /**
     * 获取已处理消息映射
     */
    private fun getProcessedMessages(): Map<String, Long> {
        return try {
            val json = sharedPreferences.getString(KEY_PROCESSED_MESSAGES, "{}")
            if (json.isNullOrEmpty() || json == "{}") {
                emptyMap()
            } else {
                objectMapper.readValue<Map<String, Long>>(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取已处理消息失败", e)
            emptyMap()
        }
    }
    
    /**
     * 保存已处理消息映射
     */
    private fun saveProcessedMessages(processedMessages: Map<String, Long>) {
        try {
            val json = objectMapper.writeValueAsString(processedMessages)
            sharedPreferences.edit()
                .putString(KEY_PROCESSED_MESSAGES, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存已处理消息失败", e)
        }
    }
    
    /**
     * 获取最后序列号映射
     */
    private fun getLastSequenceMap(): Map<String, String> {
        return try {
            val json = sharedPreferences.getString(KEY_LAST_SEQUENCE_NUMBER, "{}")
            if (json.isNullOrEmpty() || json == "{}") {
                emptyMap()
            } else {
                objectMapper.readValue<Map<String, String>>(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取最后序列号信息失败", e)
            emptyMap()
        }
    }
    
    /**
     * 保存最后序列号映射
     */
    private fun saveLastSequenceMap(lastSequenceMap: Map<String, String>) {
        try {
            val json = objectMapper.writeValueAsString(lastSequenceMap)
            sharedPreferences.edit()
                .putString(KEY_LAST_SEQUENCE_NUMBER, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存最后序列号信息失败", e)
        }
    }
    
    /**
     * 存储统计信息数据类
     */
    data class StorageStatistics(
        val processedMessageCount: Int,
        val sequenceInfoCount: Int,
        val oldestMessageTime: Long,
        val newestMessageTime: Long
    ) {
        val storageSpanHours: Long
            get() = if (newestMessageTime > oldestMessageTime) {
                (newestMessageTime - oldestMessageTime) / (1000 * 3600)
            } else {
                0L
            }
    }
}
