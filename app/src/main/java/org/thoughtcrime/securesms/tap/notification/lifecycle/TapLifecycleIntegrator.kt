package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConnectionManager
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import org.thoughtcrime.securesms.util.AppForegroundObserver

/**
 * Tap推送服务生命周期集成器
 * 
 * 集成Android应用生命周期和Tap推送服务，实现智能的前后台切换
 */
class TapLifecycleIntegrator private constructor(
    private val context: Context
) : AppForegroundObserver.Listener {
    
    companion object {
        private val TAG = Log.tag(TapLifecycleIntegrator::class.java)
        
        @Volatile
        private var instance: TapLifecycleIntegrator? = null
        
        fun getInstance(context: Context): TapLifecycleIntegrator {
            return instance ?: synchronized(this) {
                instance ?: TapLifecycleIntegrator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionManager = NotificationConnectionManager()
    private val offlineHandler = NotificationOfflineHandler.getInstance(context)
    private val networkMonitor = NotificationNetworkMonitor.getInstance(context)
    
    private var userId: String? = null
    private var notificationCallback: ((NotificationMessage) -> Unit)? = null
    private var isStarted = false
    
    /**
     * 启动生命周期集成
     * 
     * @param userId 用户ID（用于WebSocket连接标识）
     * @param onNotification 收到推送通知的回调
     */
    fun start(userId: String, onNotification: (NotificationMessage) -> Unit) {
        if (isStarted) {
            Log.w(TAG, "生命周期集成已启动")
            return
        }
        
        Log.i(TAG, "启动Tap推送服务生命周期集成")
        
        this.userId = userId
        this.notificationCallback = onNotification
        this.isStarted = true
        
        // 注册前后台监听器
        AppForegroundObserver.addListener(this)
        
        // 注册网络监听器
        networkMonitor.start { isAvailable ->
            handleNetworkChange(isAvailable)
        }
        
        // 如果当前在前台，立即连接
        if (AppForegroundObserver.isForegrounded()) {
            scope.launch {
                connectionManager.onEnterForeground(userId, onNotification)
            }
        }
    }
    
    /**
     * 停止生命周期集成
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "停止Tap推送服务生命周期集成")
        
        isStarted = false
        
        // 移除监听器
        AppForegroundObserver.removeListener(this)
        networkMonitor.stop()
        
        // 强制断开连接
        scope.launch {
            connectionManager.forceDisconnect()
        }
        
        userId = null
        notificationCallback = null
    }
    
    /**
     * 应用进入前台
     */
    override fun onForeground() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "应用进入前台")
        
        val currentUserId = userId
        val callback = notificationCallback
        
        if (currentUserId != null && callback != null) {
            scope.launch {
                try {
                    // 建立推送连接
                    connectionManager.onEnterForeground(currentUserId, callback)
                    
                    // 处理离线期间的消息
                    offlineHandler.processOfflineMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "进入前台处理失败", e)
                }
            }
        }
    }
    
    /**
     * 应用进入后台
     */
    override fun onBackground() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "应用进入后台")
        
        scope.launch {
            try {
                // 延迟断开连接（5分钟）
                connectionManager.onEnterBackground()
            } catch (e: Exception) {
                Log.e(TAG, "进入后台处理失败", e)
            }
        }
    }
    
    /**
     * 处理网络变化
     */
    private fun handleNetworkChange(isAvailable: Boolean) {
        if (!isStarted) {
            return
        }
        
        val currentUserId = userId
        val callback = notificationCallback
        
        scope.launch {
            try {
                if (isAvailable) {
                    Log.i(TAG, "网络可用，尝试恢复连接")
                    
                    if (currentUserId != null && callback != null) {
                        connectionManager.onNetworkAvailable(currentUserId, callback)
                    }
                } else {
                    Log.i(TAG, "网络丢失")
                    connectionManager.onNetworkLost()
                }
            } catch (e: Exception) {
                Log.e(TAG, "网络变化处理失败", e)
            }
        }
    }
    
    /**
     * 手动触发重连
     */
    fun reconnect() {
        if (!isStarted) {
            Log.w(TAG, "生命周期集成未启动，无法重连")
            return
        }
        
        val currentUserId = userId
        val callback = notificationCallback
        
        if (currentUserId != null && callback != null) {
            scope.launch {
                try {
                    Log.i(TAG, "手动触发重连")
                    connectionManager.onNetworkAvailable(currentUserId, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "手动重连失败", e)
                }
            }
        }
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean {
        return connectionManager.isConnected()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        connectionManager.cleanup()
    }
}

