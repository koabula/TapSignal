package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 消息序列号管理器
 * 为每个接收者维护递增的消息序列号，确保消息文件命名的有序性
 * 解决时间戳可能重复或不准确的问题
 */
class MessageSequenceManager private constructor(private val context: Context) {

    companion object {
        private val TAG = Log.tag(MessageSequenceManager::class.java)
        private const val PREFS_NAME = "message_sequence"
        private const val KEY_PREFIX = "seq_"
        
        @Volatile
        private var INSTANCE: MessageSequenceManager? = null
        
        fun getInstance(context: Context): MessageSequenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageSequenceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sequenceCache: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
    private val lock = ReentrantReadWriteLock()

    /**
     * 获取下一个消息序列号
     * @param recipientId 接收者ID
     * @return 递增的序列号
     */
    fun getNextSequenceNumber(recipientId: String): Long {
        return lock.write {
            val sequence = sequenceCache.getOrPut(recipientId) {
                val saved = sharedPreferences.getLong(KEY_PREFIX + recipientId, 0L)
                AtomicLong(saved)
            }
            
            val nextSeq = sequence.incrementAndGet()
            
            // 持久化保存
            sharedPreferences.edit()
                .putLong(KEY_PREFIX + recipientId, nextSeq)
                .apply()
            
            Log.d(TAG, "生成消息序列号: recipientId=$recipientId, sequence=$nextSeq")
            nextSeq
        }
    }

    /**
     * 获取当前序列号（不递增）
     * @param recipientId 接收者ID
     * @return 当前序列号
     */
    fun getCurrentSequenceNumber(recipientId: String): Long {
        return lock.read {
            val sequence = sequenceCache[recipientId]
            if (sequence != null) {
                sequence.get()
            } else {
                sharedPreferences.getLong(KEY_PREFIX + recipientId, 0L)
            }
        }
    }

    /**
     * 重置序列号（慎用，仅在通道重建时使用）
     * @param recipientId 接收者ID
     */
    fun resetSequenceNumber(recipientId: String) {
        lock.write {
            sequenceCache.remove(recipientId)
            sharedPreferences.edit()
                .remove(KEY_PREFIX + recipientId)
                .apply()
            
            Log.w(TAG, "重置消息序列号: recipientId=$recipientId")
        }
    }

    /**
     * 批量获取所有序列号状态
     * @return 所有接收者的序列号映射
     */
    fun getAllSequenceNumbers(): Map<String, Long> {
        return lock.read {
            val allPrefs = sharedPreferences.all
            val result = mutableMapOf<String, Long>()
            
            allPrefs.forEach { (key, value) ->
                if (key.startsWith(KEY_PREFIX) && value is Long) {
                    val recipientId = key.removePrefix(KEY_PREFIX)
                    result[recipientId] = value
                }
            }
            
            // 更新缓存中的值
            sequenceCache.forEach { (recipientId, atomicLong) ->
                result[recipientId] = atomicLong.get()
            }
            
            result
        }
    }

    /**
     * 验证序列号的连续性
     * @param recipientId 接收者ID
     * @param expectedSequence 期望的序列号
     * @return 是否连续
     */
    fun validateSequenceContinuity(recipientId: String, expectedSequence: Long): Boolean {
        val currentSequence = getCurrentSequenceNumber(recipientId)
        val isValid = expectedSequence == currentSequence + 1
        
        Log.d(TAG, "验证序列号连续性: recipientId=$recipientId, expected=$expectedSequence, current=$currentSequence, valid=$isValid")
        return isValid
    }

    /**
     * 强制设置序列号（用于错误恢复）
     * @param recipientId 接收者ID
     * @param sequenceNumber 要设置的序列号
     */
    fun forceSetSequenceNumber(recipientId: String, sequenceNumber: Long) {
        lock.write {
            val sequence = sequenceCache.getOrPut(recipientId) {
                AtomicLong(0)
            }
            
            sequence.set(sequenceNumber)
            
            sharedPreferences.edit()
                .putLong(KEY_PREFIX + recipientId, sequenceNumber)
                .apply()
            
            Log.w(TAG, "强制设置序列号: recipientId=$recipientId, sequence=$sequenceNumber")
        }
    }

    /**
     * 清理过期的序列号缓存
     * @param maxAge 最大保留时间（毫秒）
     */
    fun cleanupExpiredSequences(maxAge: Long = 30 * 24 * 60 * 60 * 1000L) { // 默认30天
        // 这里可以根据需要实现清理逻辑
        // 目前序列号需要持续保留以维护顺序，暂不实现自动清理
        Log.d(TAG, "序列号清理检查（当前不执行实际清理）")
    }

    /**
     * 从文件名中解析序列号
     * @param fileName 文件名
     * @return 序列号，如果解析失败返回null
     */
    fun parseSequenceFromFileName(fileName: String): Long? {
        return try {
            // 新格式: {sequence}_{messageNumber}_{chainNumber}_{random}.json
            val nameWithoutExt = fileName.removeSuffix(".json")
            val parts = nameWithoutExt.split("_")
            
            if (parts.size >= 4) {
                parts[0].toLong()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析序列号失败: fileName=$fileName", e)
            null
        }
    }
}