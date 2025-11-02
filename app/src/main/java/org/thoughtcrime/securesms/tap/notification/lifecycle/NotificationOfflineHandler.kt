package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportProvider
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult

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
        
        // 离线消息处理最大超时时间
        private const val OFFLINE_PROCESS_TIMEOUT_MS = 30_000L
        
        // 单次批量处理最大消息数
        private const val MAX_BATCH_SIZE = 50
    }
    
    private val mutex = Mutex()
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
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
            
            val activeChannels = withContext(Dispatchers.IO) {
                channelManager.getAllActiveChannels()
            }
            
            if (activeChannels.isEmpty()) {
                Log.d(TAG, "没有活跃的传输通道")
                return
            }
            
            Log.i(TAG, "找到 ${activeChannels.size} 个活跃通道，开始检查离线消息")
            
            var totalMessagesProcessed = 0
            
            for (channel in activeChannels) {
                try {
                    val messagesProcessed = processChannelOfflineMessages(channel)
                    totalMessagesProcessed += messagesProcessed
                    
                    if (messagesProcessed > 0) {
                        Log.i(TAG, "通道 ${channel.recipientId} 处理了 $messagesProcessed 条离线消息")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理通道 ${channel.recipientId} 离线消息失败", e)
                }
            }
            
            if (totalMessagesProcessed > 0) {
                Log.i(TAG, "离线消息处理完成，共处理 $totalMessagesProcessed 条消息")
            } else {
                Log.d(TAG, "没有发现离线消息")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理离线消息异常", e)
        } finally {
            mutex.withLock {
                isProcessing = false
            }
        }
    }
    
    /**
     * 处理单个通道的离线消息
     */
    private suspend fun processChannelOfflineMessages(
        channel: org.thoughtcrime.securesms.tap.TransportChannel
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val provider = transportManager.getProvider(channel.providerType)
                if (provider == null) {
                    Log.w(TAG, "Provider不可用: ${channel.providerType}")
                    return@withContext 0
                }
                
                val metadata = channel.metadata
                
                // 获取轮询状态
                val pollingState = pollingStateTable.getPollingState(
                    channel.recipientId,
                    channel.providerType
                )
                
                // 列出文件
                val basePath = metadata.getReceiveMetadata().path
                val pollingPaths = listOf("${basePath}messages/", "${basePath}attachments/")
                
                val allFiles = mutableListOf<org.thoughtcrime.securesms.tap.FileInfo>()
                for (path in pollingPaths) {
                    val listResult = provider.listFiles(path, metadata)
                    
                    if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                        allFiles.addAll(listResult.files)
                    }
                }
                
                if (allFiles.isEmpty()) {
                    return@withContext 0
                }
                
                // 过滤出新文件
                val processedFiles = pollingState?.processedFiles ?: emptySet()
                val lastProcessedTime = pollingState?.lastProcessedTime ?: 0L
                
                val newFiles = allFiles.filter { file ->
                    !processedFiles.contains(file.name) && 
                    file.lastModified > lastProcessedTime
                }.sortedBy { it.lastModified }
                
                if (newFiles.isEmpty()) {
                    return@withContext 0
                }
                
                Log.d(TAG, "通道 ${channel.recipientId} 发现 ${newFiles.size} 个新文件")
                
                // 处理新文件（限制批量大小）
                val filesToProcess = newFiles.take(MAX_BATCH_SIZE)
                var messagesProcessed = 0
                val newProcessedFiles = mutableSetOf<String>()
                
                for (file in filesToProcess) {
                    try {
                        val processed = processOfflineFile(provider, file, metadata)
                        if (processed) {
                            messagesProcessed++
                            newProcessedFiles.add(file.name)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理离线文件失败: ${file.name}", e)
                    }
                }
                
                // 更新轮询状态
                if (messagesProcessed > 0) {
                    pollingStateTable.recordSuccessfulPoll(
                        channel.recipientId,
                        channel.providerType,
                        newProcessedFiles,
                        messagesProcessed
                    )
                }
                
                messagesProcessed
                
            } catch (e: Exception) {
                Log.e(TAG, "处理通道离线消息异常", e)
                0
            }
        }
    }
    
    /**
     * 处理单个离线文件
     */
    private suspend fun processOfflineFile(
        provider: TransportProvider,
        file: org.thoughtcrime.securesms.tap.FileInfo,
        metadata: org.thoughtcrime.securesms.tap.TransportMetadata
    ): Boolean {
        return try {
            // 下载文件
            val downloadResult = provider.downloadFile(file, metadata)
            
            if (downloadResult !is TransportResult.Success || downloadResult.data == null) {
                Log.w(TAG, "下载离线文件失败: ${file.name}")
                return false
            }
            
            // 解析消息
            val message = provider.parseTransportMessage(downloadResult.data, file, metadata)
            if (message == null) {
                Log.d(TAG, "文件不是消息文件，跳过: ${file.name}")
                return false
            }
            
            // 处理消息
            val processResult = messageProcessor.processTapTransportMessage(message)
            when (processResult) {
                is TapProcessResult.Success -> {
                    Log.d(TAG, "离线消息处理成功: ${file.name}")
                    true
                }
                is TapProcessResult.Failed -> {
                    Log.w(TAG, "离线消息处理失败: ${file.name}, error=${processResult.error}")
                    false
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理离线文件异常: ${file.name}", e)
            false
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

