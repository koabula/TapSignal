package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.notification.NotificationDownloadExecutor
import org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata

/**
 * 推送服务离线消息处理器
 * 
 * 负责处理离线期间未接收的消息，通过轮询机制补齐
 */
class NotificationOfflineHandler private constructor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(NotificationOfflineHandler::class.java)
        
        @Volatile
        private var instance: NotificationOfflineHandler? = null
        
        fun getInstance(context: Context): NotificationOfflineHandler {
            return instance ?: synchronized(this) {
                instance ?: NotificationOfflineHandler(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val mutex = Mutex()
    private val channelManager = TransportChannelManager.getInstance(context)
    private val downloadExecutor = NotificationDownloadExecutor.getInstance(context)
    
    private var isProcessing = false
    private var lastProcessTime = 0L
    
    /**
     * 处理离线消息
     * 
     * 从所有活跃的传输通道中拉取可能遗漏的消息
     */
    suspend fun processOfflineMessages() {
        mutex.withLock {
            if (isProcessing) {
                Log.d(TAG, "离线消息处理正在进行中，跳过")
                return
            }
            
            // 避免频繁处理（至少间隔1分钟）
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < 60_000L) {
                Log.d(TAG, "离线消息处理间隔过短，跳过")
                return
            }
            
            isProcessing = true
            lastProcessTime = currentTime
        }
        
        try {
            Log.i(TAG, "开始处理离线消息")

            val recipientHashes = collectRecipientHashes()

            if (recipientHashes.isEmpty()) {
                Log.d(TAG, "离线同步：无可用 recipient hash，跳过")
                return
            }

            Log.i(TAG, "离线同步：准备处理 ${recipientHashes.size} 个 hash")

            var totalMessagesProcessed = 0
            var totalFailed = 0

            for (hash in recipientHashes) {
                try {
                    val result = downloadExecutor.syncOfflineMessages(hash)
                    totalMessagesProcessed += result.processed
                    totalFailed += result.failed

                    Log.i(TAG, "离线同步完成: hash=$hash processed=${result.processed} failed=${result.failed} scanned=${result.scanned}")
                } catch (e: Exception) {
                    Log.e(TAG, "离线同步失败: hash=$hash", e)
                    totalFailed++
                }
            }

            if (totalMessagesProcessed > 0) {
                Log.i(TAG, "离线消息处理完成，共处理 $totalMessagesProcessed 条消息，失败 $totalFailed")
            } else {
                Log.d(TAG, "离线同步完成，无新增消息，失败 $totalFailed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理离线消息异常", e)
        } finally {
            mutex.withLock {
                isProcessing = false
            }
        }
    }
    
    private suspend fun collectRecipientHashes(): Set<String> {
        return withContext(Dispatchers.IO) {
            val activeChannels = channelManager.getAllActiveChannels()
            if (activeChannels.isEmpty()) {
                emptySet()
            } else {
                activeChannels.mapNotNull { channel ->
                    val metadata = channel.metadata
                    val cosMetadata = metadata as? CosTransportMetadata
                    cosMetadata?.peerHashedId
                }.toSet()
            }
        }
    }
    
    /**
     * 检查是否正在处理离线消息
     */
    fun isProcessing(): Boolean {
        return isProcessing
    }
    
    /**
     * 获取上次处理时间
     */
    fun getLastProcessTime(): Long {
        return lastProcessTime
    }
}

