package org.thoughtcrime.securesms.tap.provider.cos.coscomm.cache

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosMessage
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/**
 * COS早期消息缓存（简化版）
 * 
 * 不再基于序列号进行缓存，依赖Signal原生的Double Ratchet处理消息乱序
 * 保留接口兼容性，但功能简化为空实现
 */
class CosEarlyMessageCache {

    companion object {
        private val TAG = Log.tag(CosEarlyMessageCache::class.java)
    }

    private val lock = ReentrantReadWriteLock()
    
    /**
     * 缓存统计信息
     */
    data class CacheStats(
        val totalCachedMessages: Int = 0,
        val cacheHitRate: Double = 0.0,
        val oldestCachedMessage: Long = 0L
    )
    
    /**
     * 存储消息到缓存（空实现）
     * 不再进行缓存，依赖Signal原生处理
     */
    fun store(recipientId: String, expectedSequence: Long, message: CosMessage, reason: String) {
        Log.v(TAG, "跳过消息缓存（已简化）: messageId=${message.messageId}")
        // 空实现 - 不再缓存消息
    }
    
    /**
     * 检索缓存消息（空实现）
     * 总是返回空列表
     */
    fun retrieve(recipientId: String, processedSequence: Long): List<CosMessage> {
        return emptyList()
    }
    
    /**
     * 尝试处理消息（简化版）
     * 不再进行缓存处理，直接返回消息供Signal原生系统处理
     */
    fun tryProcessMessage(recipientId: String, message: CosMessage, processedSequence: Long): List<CosMessage> {
        Log.v(TAG, "直接处理消息（无缓存）: recipient=$recipientId, messageId=${message.messageId}")
        return listOf(message)
    }
    
    /**
     * 检查是否有可以处理的缓存消息（空实现）
     * 总是返回空列表
     */
    fun checkPendingMessages(recipientId: String): List<CosMessage> {
        return emptyList()
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats()
    }
    
    /**
     * 清理缓存（空实现）
     */
    fun cleanup() {
        Log.v(TAG, "缓存清理（已简化，无操作）")
        // 空实现 - 无需清理
    }
    
    /**
     * 清理指定接收者的缓存（空实现）
     */
    fun cleanup(recipientId: String) {
        Log.v(TAG, "清理接收者缓存（已简化，无操作）: recipientId=$recipientId")
        // 空实现 - 无需清理
    }
    
    /**
     * 获取缓存大小
     */
    fun size(): Int {
        return 0
    }
    
    /**
     * 检查缓存是否为空
     */
    fun isEmpty(): Boolean {
        return true
    }
}