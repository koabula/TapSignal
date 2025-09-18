package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.storage.MessageSendStatusStorage
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 消息发送状态跟踪器
 * 负责跟踪COS消息的发送状态、重试机制和状态更新
 */
class MessageSendStatusTracker private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(MessageSendStatusTracker::class.java)
        
        // 状态检查配置
        private const val STATUS_CHECK_INTERVAL = 30000L // 30秒检查一次
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_BASE = 5000L // 5秒基础延迟
        
        @Volatile
        private var INSTANCE: MessageSendStatusTracker? = null
        
        fun getInstance(context: Context): MessageSendStatusTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageSendStatusTracker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val statusStorage = MessageSendStatusStorage(context)
    private val statusExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    
    // 内存中的发送状态缓存
    private val sendingMessages: ConcurrentHashMap<Long, MessageSendStatus> = ConcurrentHashMap()
    
    init {
        // 启动状态检查任务
        startStatusCheckTask()
        
        // 恢复未完成的发送状态
        restorePendingSendStatus()
    }
    
    /**
     * 记录发送开始
     */
    fun onSendStarted(messageId: Long, method: MessageSendMethod) {
        Log.i(TAG, "记录发送开始: messageId=$messageId, method=$method")
        
        val status = MessageSendStatus(
            messageId = messageId,
            method = method,
            status = SendStatus.SENDING,
            startTime = System.currentTimeMillis(),
            retryCount = 0,
            lastError = null,
            cosPath = null
        )
        
        sendingMessages[messageId] = status
        statusStorage.saveSendStatus(status)
        
        // 更新数据库状态为发送中
        SignalDatabase.messages.markAsSending(messageId)
    }
    
    /**
     * 记录发送成功
     */
    fun onSendSuccess(messageId: Long, method: MessageSendMethod, cosPath: String?) {
        Log.i(TAG, "记录发送成功: messageId=$messageId, method=$method, path=$cosPath")
        
        val status = sendingMessages[messageId]?.copy(
            status = SendStatus.SUCCESS,
            endTime = System.currentTimeMillis(),
            cosPath = cosPath
        ) ?: MessageSendStatus(
            messageId = messageId,
            method = method,
            status = SendStatus.SUCCESS,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            retryCount = 0,
            lastError = null,
            cosPath = cosPath
        )
        
        sendingMessages[messageId] = status
        statusStorage.saveSendStatus(status)
        
        // 更新数据库状态为已发送
        SignalDatabase.messages.markAsSent(messageId, true)
        
        // 清理完成的状态（延迟清理）
        statusExecutor.schedule({
            sendingMessages.remove(messageId)
            statusStorage.removeSendStatus(messageId)
        }, 5, TimeUnit.MINUTES)
    }
    
    /**
     * 记录发送失败
     */
    fun onSendFailure(messageId: Long, method: MessageSendMethod, error: String) {
        Log.w(TAG, "记录发送失败: messageId=$messageId, method=$method, error=$error")
        
        val currentStatus = sendingMessages[messageId]
        val retryCount = (currentStatus?.retryCount ?: 0) + 1
        
        val status = currentStatus?.copy(
            status = if (retryCount >= MAX_RETRY_COUNT) SendStatus.FAILED else SendStatus.RETRY_PENDING,
            retryCount = retryCount,
            lastError = error,
            lastRetryTime = System.currentTimeMillis()
        ) ?: MessageSendStatus(
            messageId = messageId,
            method = method,
            status = if (retryCount >= MAX_RETRY_COUNT) SendStatus.FAILED else SendStatus.RETRY_PENDING,
            startTime = System.currentTimeMillis(),
            retryCount = retryCount,
            lastError = error,
            lastRetryTime = System.currentTimeMillis(),
            cosPath = null
        )
        
        sendingMessages[messageId] = status
        statusStorage.saveSendStatus(status)
        
        if (status.status == SendStatus.FAILED) {
            // 标记为发送失败
            SignalDatabase.messages.markAsSentFailed(messageId)
            
            // 清理失败的状态（延迟清理）
            statusExecutor.schedule({
                sendingMessages.remove(messageId)
                statusStorage.removeSendStatus(messageId)
            }, 1, TimeUnit.HOURS)
        } else {
            // 安排重试
            scheduleRetry(messageId, retryCount)
        }
    }
    
    /**
     * 获取消息发送状态
     */
    fun getSendStatus(messageId: Long): MessageSendStatus? {
        return sendingMessages[messageId] ?: statusStorage.getSendStatus(messageId)
    }
    
    /**
     * 获取所有发送中的消息
     */
    fun getSendingMessages(): List<MessageSendStatus> {
        return sendingMessages.values.filter { it.status == SendStatus.SENDING }
    }
    
    /**
     * 获取需要重试的消息
     */
    fun getRetryPendingMessages(): List<MessageSendStatus> {
        return sendingMessages.values.filter { 
            it.status == SendStatus.RETRY_PENDING &&
            it.retryCount < MAX_RETRY_COUNT &&
            System.currentTimeMillis() - (it.lastRetryTime ?: 0) >= calculateRetryDelay(it.retryCount)
        }
    }
    
    /**
     * 手动重试发送
     */
    fun retryMessage(messageId: Long): Boolean {
        val status = sendingMessages[messageId] ?: return false
        
        if (status.status != SendStatus.RETRY_PENDING && status.status != SendStatus.FAILED) {
            Log.w(TAG, "消息状态不允许重试: messageId=$messageId, status=${status.status}")
            return false
        }
        
        if (status.retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限: messageId=$messageId, retryCount=${status.retryCount}")
            return false
        }
        
        Log.i(TAG, "手动重试消息: messageId=$messageId")
        
        // 重置状态为发送中
        val updatedStatus = status.copy(
            status = SendStatus.SENDING,
            lastRetryTime = System.currentTimeMillis()
        )
        
        sendingMessages[messageId] = updatedStatus
        statusStorage.saveSendStatus(updatedStatus)
        
        return true
    }
    
    /**
     * 取消消息发送
     */
    fun cancelMessage(messageId: Long) {
        Log.i(TAG, "取消消息发送: messageId=$messageId")
        
        val status = sendingMessages[messageId]
        if (status != null) {
            val cancelledStatus = status.copy(
                status = SendStatus.CANCELLED,
                endTime = System.currentTimeMillis()
            )
            
            sendingMessages[messageId] = cancelledStatus
            statusStorage.saveSendStatus(cancelledStatus)
            
            // 清理取消的状态
            statusExecutor.schedule({
                sendingMessages.remove(messageId)
                statusStorage.removeSendStatus(messageId)
            }, 1, TimeUnit.MINUTES)
        }
    }
    
    /**
     * 启动状态检查任务
     */
    private fun startStatusCheckTask() {
        statusExecutor.scheduleWithFixedDelay({
            try {
                checkAndUpdateSendingStatus()
                processRetryPendingMessages()
                cleanupExpiredStatus()
            } catch (e: Exception) {
                Log.e(TAG, "状态检查任务异常", e)
            }
        }, STATUS_CHECK_INTERVAL, STATUS_CHECK_INTERVAL, TimeUnit.MILLISECONDS)
    }
    
    /**
     * 检查和更新发送中的状态
     */
    private fun checkAndUpdateSendingStatus() {
        val sendingMessages = getSendingMessages()
        val currentTime = System.currentTimeMillis()
        
        for (status in sendingMessages) {
            // 检查是否超时（超过5分钟仍在发送中）
            if (currentTime - status.startTime > 5 * 60 * 1000) {
                Log.w(TAG, "发送超时，标记为重试: messageId=${status.messageId}")
                onSendFailure(status.messageId, status.method, "发送超时")
            }
        }
    }
    
    /**
     * 处理需要重试的消息
     */
    private fun processRetryPendingMessages() {
        val retryMessages = getRetryPendingMessages()
        
        for (status in retryMessages) {
            Log.i(TAG, "自动重试消息: messageId=${status.messageId}, retryCount=${status.retryCount}")
            
            // 这里应该触发实际的重试逻辑
            // 由于重试需要调用发送管理器，这里只是标记为可重试状态
            // 实际重试由外部调用者处理
        }
    }
    
    /**
     * 清理过期状态
     */
    private fun cleanupExpiredStatus() {
        val currentTime = System.currentTimeMillis()
        val expiredMessages = sendingMessages.values.filter { status ->
            when (status.status) {
                SendStatus.SUCCESS -> (status.endTime ?: currentTime) < currentTime - TimeUnit.HOURS.toMillis(1)
                SendStatus.FAILED, SendStatus.CANCELLED -> (status.endTime ?: currentTime) < currentTime - TimeUnit.HOURS.toMillis(24)
                else -> false
            }
        }
        
        for (status in expiredMessages) {
            sendingMessages.remove(status.messageId)
            statusStorage.removeSendStatus(status.messageId)
        }
        
        if (expiredMessages.isNotEmpty()) {
            Log.d(TAG, "清理过期状态: count=${expiredMessages.size}")
        }
    }
    
    /**
     * 恢复未完成的发送状态
     */
    private fun restorePendingSendStatus() {
        try {
            val pendingStatuses = statusStorage.getAllPendingSendStatus()
            for (status in pendingStatuses) {
                sendingMessages[status.messageId] = status
            }
            Log.i(TAG, "恢复未完成发送状态: count=${pendingStatuses.size}")
        } catch (e: Exception) {
            Log.e(TAG, "恢复发送状态失败", e)
        }
    }
    
    /**
     * 安排重试
     */
    private fun scheduleRetry(messageId: Long, retryCount: Int) {
        val delay = calculateRetryDelay(retryCount)
        Log.i(TAG, "安排重试: messageId=$messageId, retryCount=$retryCount, delay=${delay}ms")
        
        statusExecutor.schedule({
            val status = sendingMessages[messageId]
            if (status?.status == SendStatus.RETRY_PENDING) {
                Log.i(TAG, "重试时间到达: messageId=$messageId")
                // 这里可以触发重试回调或事件
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
    
    /**
     * 计算重试延迟
     */
    private fun calculateRetryDelay(retryCount: Int): Long {
        return RETRY_DELAY_BASE * (1L shl (retryCount - 1)) // 指数退避
    }
}

/**
 * 消息发送状态
 */
data class MessageSendStatus(
    val messageId: Long,
    val method: MessageSendMethod,
    val status: SendStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val retryCount: Int,
    val lastError: String?,
    val lastRetryTime: Long? = null,
    val cosPath: String?
)

/**
 * 发送状态枚举
 */
enum class SendStatus {
    SENDING,        // 发送中
    SUCCESS,        // 发送成功
    FAILED,         // 发送失败
    RETRY_PENDING,  // 等待重试
    CANCELLED       // 已取消
}
