package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.tap.utils.TransportIdHasher
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor

/**
 * 推送服务管理器
 */
class NotificationManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(NotificationManager::class.java)
        
        @Volatile
        private var instance: NotificationManager? = null
        
        fun getInstance(context: Context): NotificationManager {
            return instance ?: synchronized(this) {
                instance ?: NotificationManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val mutex = Mutex()
    private var currentProvider: NotificationProvider? = null
    private var currentConfig: NotificationConfig? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var onNotificationCallback: ((NotificationMessage) -> Unit)? = null
    
    // 下载执行器
    private val downloadExecutor = NotificationDownloadExecutor.getInstance(context)
    
    // 协程作用域
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val offlineSyncRunning = AtomicBoolean(false)
    @Volatile
    private var lastOfflineSyncTime: Long = 0L
    
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
                    triggerOfflineSync("initial_connect")
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
    
    fun triggerOfflineSync(reason: String = "manual") {
        val myAci = try {
            SignalStore.account.requireAci()
        } catch (e: Exception) {
            Log.w(TAG, "无法获取本端ACI，跳过离线同步", e)
            return
        }
        val myHash = try {
            TransportIdHasher.hashAci(myAci)
        } catch (e: Exception) {
            Log.w(TAG, "计算本端哈希失败，跳过离线同步", e)
            return
        }
        if (!offlineSyncRunning.compareAndSet(false, true)) {
            Log.d(TAG, "离线同步已在执行中，跳过: reason=$reason")
            return
        }
        notificationScope.launch {
            try {
                Log.i(TAG, "开始离线消息同步: reason=$reason")
                val result = downloadExecutor.syncOfflineMessages(myHash)
                Log.i(TAG, "离线消息同步完成: processed=${result.processed}, failed=${result.failed}, scanned=${result.scanned}, skipped=${result.skippedReason}")
            } finally {
                lastOfflineSyncTime = System.currentTimeMillis()
                offlineSyncRunning.set(false)
            }
        }
    }

    fun isOfflineSyncRunning(): Boolean = offlineSyncRunning.get()

    fun getLastOfflineSyncTime(): Long = lastOfflineSyncTime
    
    private fun handleNotification(notification: NotificationMessage) {
        try {
            // P0修复：在入口处添加详细日志
            Log.i(TAG, "handleNotification called: type=${notification.type}, senderId=${notification.senderId}, timestamp=${notification.timestamp}")
            
            if (!notification.validate()) {
                Log.w(TAG, "收到无效通知消息")
                return
            }
            
            Log.d(TAG, "收到推送通知: type=${notification.type}, senderId=${notification.senderId}")
            
            // 先触发回调（如果有）
            onNotificationCallback?.invoke(notification)
            
            // 处理不同类型的通知
            when (notification.type) {
                NotificationMessage.TYPE_NEW_MESSAGE -> {
                    handleNewMessageNotification(notification)
                }
                NotificationMessage.TYPE_HEARTBEAT -> {
                    handleHeartbeatNotification(notification)
                }
                else -> {
                    Log.w(TAG, "未知的通知类型: ${notification.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理推送通知异常", e)
        }
    }
    
    /**
     * 处理新消息通知
     * 收到推送后触发下载或直接处理Payload
     */
    private fun handleNewMessageNotification(notification: NotificationMessage) {
        try {
            val senderId = notification.senderId
            val fileKey = notification.metadata["key"] as? String
            val payload = notification.payload
            
            Log.i(TAG, "处理新消息通知: senderId=$senderId, key=$fileKey, hasPayload=${payload != null}")
            
            if (payload != null) {
                // V2 Mode: 直接处理推送的消息Payload
                notificationScope.launch {
                    try {
                        TapMessageProcessor.getInstance(context).processPushMessage(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "处理推送Payload异常: senderId=$senderId", e)
                    }
                }
            } else {
                // V1 Mode: 触发下载
                Log.i(TAG, "无Payload，触发下载: senderId=$senderId")
                notificationScope.launch {
                    try {
                        // 使用新的直接下载方案
                        val result = downloadExecutor.executeDirectDownload(notification)
                        
                        if (result.isSuccess) {
                            Log.i(TAG, "推送触发下载成功: senderId=$senderId, " +
                                "messagesProcessed=${result.messagesProcessed}, " +
                                "filesProcessed=${result.filesProcessed}, " +
                                "responseTime=${result.responseTime}ms")
                        } else {
                            Log.w(TAG, "推送触发下载失败: senderId=$senderId, " +
                                "error=${result.error}, " +
                                "responseTime=${result.responseTime}ms")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "推送触发下载异常: senderId=$senderId", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理新消息通知失败: senderId=${notification.senderId}", e)
        }
    }
    
    /**
     * 处理心跳通知
     */
    private fun handleHeartbeatNotification(notification: NotificationMessage) {
        Log.d(TAG, "收到心跳通知: senderId=${notification.senderId}")
    }
}

enum class ConnectionState {
    DISCONNECTED,
    INITIALIZED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
