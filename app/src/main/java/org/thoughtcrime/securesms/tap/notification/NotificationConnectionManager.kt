package org.thoughtcrime.securesms.tap.notification

import kotlinx.coroutines.*
import org.signal.core.util.logging.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推送连接管理器
 */
class NotificationConnectionManager {
    
    companion object {
        private val TAG = Log.tag(NotificationConnectionManager::class.java)
        
        private const val BACKGROUND_DISCONNECT_DELAY_MS = 5 * 60 * 1000L
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 60 * 1000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }
    
    private val notificationManager = NotificationManager.getInstance()
    
    private val isInForeground = AtomicBoolean(false)
    private val shouldMaintainConnection = AtomicBoolean(false)
    
    private var backgroundDisconnectJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    suspend fun onEnterForeground(userId: String, onNotification: (NotificationMessage) -> Unit) {
        try {
            Log.i(TAG, "进入前台模式")
            
            isInForeground.set(true)
            shouldMaintainConnection.set(true)
            
            backgroundDisconnectJob?.cancel()
            backgroundDisconnectJob = null
            
            if (!notificationManager.isConnected()) {
                connectWithRetry(userId, onNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "进入前台模式失败", e)
        }
    }
    
    suspend fun onEnterBackground() {
        try {
            Log.i(TAG, "进入后台模式")
            
            isInForeground.set(false)
            
            backgroundDisconnectJob?.cancel()
            backgroundDisconnectJob = scope.launch {
                delay(BACKGROUND_DISCONNECT_DELAY_MS)
                
                if (!isInForeground.get()) {
                    Log.i(TAG, "后台延迟断开连接")
                    shouldMaintainConnection.set(false)
                    notificationManager.disconnect()
                    reconnectJob?.cancel()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "进入后台模式失败", e)
        }
    }
    
    suspend fun onNetworkAvailable(userId: String, onNotification: (NotificationMessage) -> Unit) {
        try {
            Log.i(TAG, "网络可用")
            
            if (shouldMaintainConnection.get() && !notificationManager.isConnected()) {
                connectWithRetry(userId, onNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "网络可用处理失败", e)
        }
    }
    
    suspend fun onNetworkLost() {
        try {
            Log.i(TAG, "网络丢失")
            
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
        } catch (e: Exception) {
            Log.e(TAG, "网络丢失处理失败", e)
        }
    }
    
    suspend fun forceDisconnect() {
        try {
            Log.i(TAG, "强制断开连接")
            
            shouldMaintainConnection.set(false)
            backgroundDisconnectJob?.cancel()
            reconnectJob?.cancel()
            
            notificationManager.disconnect()
            reconnectAttempts = 0
        } catch (e: Exception) {
            Log.e(TAG, "强制断开连接失败", e)
        }
    }
    
    private suspend fun connectWithRetry(userId: String, onNotification: (NotificationMessage) -> Unit) {
        reconnectJob?.cancel()
        reconnectAttempts = 0
        
        reconnectJob = scope.launch {
            while (shouldMaintainConnection.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                try {
                    Log.i(TAG, "尝试连接推送服务: attempt=${reconnectAttempts + 1}")
                    
                    val result = notificationManager.connect(userId, onNotification)
                    
                    if (result.success) {
                        Log.i(TAG, "推送服务连接成功")
                        reconnectAttempts = 0
                        return@launch
                    } else {
                        Log.w(TAG, "推送服务连接失败: ${result.errorMessage}")
                        reconnectAttempts++
                        
                        if (shouldMaintainConnection.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                            val delayMs = calculateBackoffDelay(reconnectAttempts)
                            Log.d(TAG, "等待 ${delayMs}ms 后重试")
                            delay(delayMs)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "连接推送服务异常", e)
                    reconnectAttempts++
                    
                    if (shouldMaintainConnection.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        val delayMs = calculateBackoffDelay(reconnectAttempts)
                        delay(delayMs)
                    }
                }
            }
            
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "推送服务连接失败，已达到最大重试次数")
            }
        }
    }
    
    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = RECONNECT_BASE_DELAY_MS * (1 shl (attempt - 1))
        return minOf(delay, RECONNECT_MAX_DELAY_MS)
    }
    
    fun cleanup() {
        try {
            shouldMaintainConnection.set(false)
            backgroundDisconnectJob?.cancel()
            reconnectJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败", e)
        }
    }
}

