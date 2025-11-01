package org.thoughtcrime.securesms.tap.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.core.util.logging.Log

/**
 * 推送服务管理器
 */
class NotificationManager {
    
    companion object {
        private val TAG = Log.tag(NotificationManager::class.java)
        
        @Volatile
        private var instance: NotificationManager? = null
        
        fun getInstance(): NotificationManager {
            return instance ?: synchronized(this) {
                instance ?: NotificationManager().also { instance = it }
            }
        }
    }
    
    private val mutex = Mutex()
    private var currentProvider: NotificationProvider? = null
    private var currentConfig: NotificationConfig? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var onNotificationCallback: ((NotificationMessage) -> Unit)? = null
    
    suspend fun initialize(provider: NotificationProvider, config: NotificationConfig): Boolean {
        return mutex.withLock {
            try {
                if (!config.validate()) {
                    Log.w(TAG, "配置验证失败")
                    return@withLock false
                }
                
                currentProvider = provider
                currentConfig = config
                connectionState = ConnectionState.INITIALIZED
                
                Log.i(TAG, "推送服务管理器初始化成功: ${provider.providerType}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "推送服务管理器初始化失败", e)
                false
            }
        }
    }
    
    suspend fun connect(userId: String, onNotification: (NotificationMessage) -> Unit): ConnectionResult {
        return mutex.withLock {
            try {
                val provider = currentProvider
                    ?: return@withLock ConnectionResult.failure("推送服务未初始化")
                
                if (connectionState == ConnectionState.CONNECTED) {
                    Log.w(TAG, "推送服务已连接")
                    return@withLock ConnectionResult.success("already_connected")
                }
                
                onNotificationCallback = onNotification
                connectionState = ConnectionState.CONNECTING
                
                val result = provider.connect(userId) { notification ->
                    handleNotification(notification)
                }
                
                if (result.success) {
                    connectionState = ConnectionState.CONNECTED
                    Log.i(TAG, "推送服务连接成功")
                } else {
                    connectionState = ConnectionState.DISCONNECTED
                    Log.e(TAG, "推送服务连接失败: ${result.errorMessage}")
                }
                
                result
            } catch (e: Exception) {
                connectionState = ConnectionState.DISCONNECTED
                Log.e(TAG, "推送服务连接异常", e)
                ConnectionResult.failure(e.message ?: "连接异常")
            }
        }
    }
    
    suspend fun disconnect() {
        mutex.withLock {
            try {
                currentProvider?.disconnect()
                connectionState = ConnectionState.DISCONNECTED
                onNotificationCallback = null
                Log.i(TAG, "推送服务已断开")
            } catch (e: Exception) {
                Log.e(TAG, "推送服务断开异常", e)
            }
        }
    }
    
    suspend fun healthCheck(): HealthStatus {
        return try {
            val provider = currentProvider
                ?: return HealthStatus.unhealthy("推送服务未初始化")
            
            provider.healthCheck()
        } catch (e: Exception) {
            Log.e(TAG, "健康检查失败", e)
            HealthStatus.unhealthy(e.message ?: "健康检查异常")
        }
    }
    
    fun getWebhookConfig(): WebhookConfig? {
        return currentProvider?.getWebhookConfig()
    }
    
    fun getConnectionState(): ConnectionState {
        return connectionState
    }
    
    fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED
    }
    
    fun getCurrentConfig(): NotificationConfig? {
        return currentConfig
    }
    
    private fun handleNotification(notification: NotificationMessage) {
        try {
            if (!notification.validate()) {
                Log.w(TAG, "收到无效通知消息")
                return
            }
            
            Log.d(TAG, "收到推送通知: type=${notification.type}, senderId=${notification.senderId}")
            
            onNotificationCallback?.invoke(notification)
        } catch (e: Exception) {
            Log.e(TAG, "处理推送通知异常", e)
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    INITIALIZED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

