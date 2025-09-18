package org.thoughtcrime.securesms.tap.provider.cos.coscomm.storage

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.MessageSendStatus
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.SendStatus
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.MessageSendMethod

/**
 * 消息发送状态存储
 * 负责持久化消息发送状态，支持应用重启后的状态恢复
 */
class MessageSendStatusStorage(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(MessageSendStatusStorage::class.java)
        private const val PREFS_NAME = "cos_message_send_status"
        private const val KEY_STATUS_PREFIX = "status_"
        private const val KEY_STATUS_LIST = "status_list"
        private const val MAX_STORED_STATUS = 1000 // 最大存储状态数量
    }
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
    }
    
    /**
     * 保存发送状态
     */
    fun saveSendStatus(status: MessageSendStatus) {
        try {
            val json = objectMapper.writeValueAsString(status)
            val key = KEY_STATUS_PREFIX + status.messageId
            
            sharedPreferences.edit()
                .putString(key, json)
                .apply()
            
            // 更新状态列表
            updateStatusList(status.messageId, true)
            
            Log.d(TAG, "保存发送状态: messageId=${status.messageId}, status=${status.status}")
        } catch (e: Exception) {
            Log.e(TAG, "保存发送状态失败: messageId=${status.messageId}", e)
        }
    }
    
    /**
     * 获取发送状态
     */
    fun getSendStatus(messageId: Long): MessageSendStatus? {
        return try {
            val key = KEY_STATUS_PREFIX + messageId
            val json = sharedPreferences.getString(key, null) ?: return null
            
            objectMapper.readValue<MessageSendStatus>(json)
        } catch (e: Exception) {
            Log.e(TAG, "获取发送状态失败: messageId=$messageId", e)
            null
        }
    }
    
    /**
     * 删除发送状态
     */
    fun removeSendStatus(messageId: Long) {
        try {
            val key = KEY_STATUS_PREFIX + messageId
            
            sharedPreferences.edit()
                .remove(key)
                .apply()
            
            // 更新状态列表
            updateStatusList(messageId, false)
            
            Log.d(TAG, "删除发送状态: messageId=$messageId")
        } catch (e: Exception) {
            Log.e(TAG, "删除发送状态失败: messageId=$messageId", e)
        }
    }
    
    /**
     * 获取所有未完成的发送状态
     */
    fun getAllPendingSendStatus(): List<MessageSendStatus> {
        return try {
            val messageIds = getStoredMessageIds()
            val pendingStatuses = mutableListOf<MessageSendStatus>()
            
            for (messageId in messageIds) {
                val status = getSendStatus(messageId)
                if (status != null && isPendingStatus(status.status)) {
                    pendingStatuses.add(status)
                }
            }
            
            Log.d(TAG, "获取未完成发送状态: count=${pendingStatuses.size}")
            pendingStatuses
        } catch (e: Exception) {
            Log.e(TAG, "获取未完成发送状态失败", e)
            emptyList()
        }
    }
    
    /**
     * 获取所有发送状态
     */
    fun getAllSendStatus(): List<MessageSendStatus> {
        return try {
            val messageIds = getStoredMessageIds()
            val allStatuses = mutableListOf<MessageSendStatus>()
            
            for (messageId in messageIds) {
                val status = getSendStatus(messageId)
                if (status != null) {
                    allStatuses.add(status)
                }
            }
            
            Log.d(TAG, "获取所有发送状态: count=${allStatuses.size}")
            allStatuses
        } catch (e: Exception) {
            Log.e(TAG, "获取所有发送状态失败", e)
            emptyList()
        }
    }
    
    /**
     * 清理过期状态
     */
    fun cleanupExpiredStatus(maxAgeMillis: Long) {
        try {
            val currentTime = System.currentTimeMillis()
            val messageIds = getStoredMessageIds()
            var cleanedCount = 0
            
            for (messageId in messageIds) {
                val status = getSendStatus(messageId)
                if (status != null && isExpiredStatus(status, currentTime, maxAgeMillis)) {
                    removeSendStatus(messageId)
                    cleanedCount++
                }
            }
            
            Log.i(TAG, "清理过期状态完成: cleanedCount=$cleanedCount")
        } catch (e: Exception) {
            Log.e(TAG, "清理过期状态失败", e)
        }
    }
    
    /**
     * 获取存储统计信息
     */
    fun getStorageStatistics(): SendStatusStorageStatistics {
        return try {
            val messageIds = getStoredMessageIds()
            val statusCounts = mutableMapOf<SendStatus, Int>()
            var totalSize = 0L
            
            for (messageId in messageIds) {
                val status = getSendStatus(messageId)
                if (status != null) {
                    statusCounts[status.status] = statusCounts.getOrDefault(status.status, 0) + 1
                    totalSize += estimateStatusSize(status)
                }
            }
            
            SendStatusStorageStatistics(
                totalCount = messageIds.size,
                statusCounts = statusCounts,
                estimatedSizeBytes = totalSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取存储统计信息失败", e)
            SendStatusStorageStatistics(0, emptyMap(), 0L)
        }
    }
    
    /**
     * 清理所有状态
     */
    fun clearAllStatus() {
        try {
            sharedPreferences.edit().clear().apply()
            Log.i(TAG, "清理所有发送状态完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理所有发送状态失败", e)
        }
    }
    
    /**
     * 更新状态列表
     */
    private fun updateStatusList(messageId: Long, add: Boolean) {
        try {
            val messageIds = getStoredMessageIds().toMutableSet()
            
            if (add) {
                messageIds.add(messageId)
                
                // 限制存储数量
                if (messageIds.size > MAX_STORED_STATUS) {
                    val sortedIds = messageIds.sorted()
                    val toRemove = sortedIds.take(messageIds.size - MAX_STORED_STATUS)
                    
                    for (id in toRemove) {
                        messageIds.remove(id)
                        sharedPreferences.edit().remove(KEY_STATUS_PREFIX + id).apply()
                    }
                    
                    Log.w(TAG, "存储状态数量超限，清理旧状态: removedCount=${toRemove.size}")
                }
            } else {
                messageIds.remove(messageId)
            }
            
            val json = objectMapper.writeValueAsString(messageIds.toList())
            sharedPreferences.edit()
                .putString(KEY_STATUS_LIST, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "更新状态列表失败", e)
        }
    }
    
    /**
     * 获取存储的消息ID列表
     */
    private fun getStoredMessageIds(): List<Long> {
        return try {
            val json = sharedPreferences.getString(KEY_STATUS_LIST, null) ?: return emptyList()
            objectMapper.readValue<List<Long>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "获取存储消息ID列表失败", e)
            emptyList()
        }
    }
    
    /**
     * 检查是否为未完成状态
     */
    private fun isPendingStatus(status: SendStatus): Boolean {
        return when (status) {
            SendStatus.SENDING, SendStatus.RETRY_PENDING -> true
            SendStatus.SUCCESS, SendStatus.FAILED, SendStatus.CANCELLED -> false
        }
    }
    
    /**
     * 检查状态是否过期
     */
    private fun isExpiredStatus(status: MessageSendStatus, currentTime: Long, maxAgeMillis: Long): Boolean {
        val statusTime = when (status.status) {
            SendStatus.SUCCESS, SendStatus.FAILED, SendStatus.CANCELLED -> status.endTime ?: status.startTime
            SendStatus.SENDING, SendStatus.RETRY_PENDING -> status.lastRetryTime ?: status.startTime
        }
        
        return currentTime - statusTime > maxAgeMillis
    }
    
    /**
     * 估算状态大小
     */
    private fun estimateStatusSize(status: MessageSendStatus): Long {
        var size = 0L
        
        // 基础字段大小
        size += 8 * 4 // Long字段
        size += 4 * 2 // Int和enum字段
        
        // 字符串字段大小
        size += (status.lastError?.length ?: 0) * 2
        size += (status.cosPath?.length ?: 0) * 2
        
        // JSON序列化开销
        size += 100
        
        return size
    }
}

/**
 * 发送状态存储统计信息
 */
data class SendStatusStorageStatistics(
    val totalCount: Int,
    val statusCounts: Map<SendStatus, Int>,
    val estimatedSizeBytes: Long
) {
    fun getSendingCount(): Int = statusCounts[SendStatus.SENDING] ?: 0
    fun getSuccessCount(): Int = statusCounts[SendStatus.SUCCESS] ?: 0
    fun getFailedCount(): Int = statusCounts[SendStatus.FAILED] ?: 0
    fun getRetryPendingCount(): Int = statusCounts[SendStatus.RETRY_PENDING] ?: 0
    fun getCancelledCount(): Int = statusCounts[SendStatus.CANCELLED] ?: 0
}
