package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConfigManager
import org.thoughtcrime.securesms.tap.notification.NotificationManager
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import org.thoughtcrime.securesms.tap.notification.NotificationProviderFactory

/**
 * Tap推送通知服务
 * 
 * 集成所有推送相关的组件，提供统一的服务接口
 */
class TapNotificationService private constructor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(TapNotificationService::class.java)
        
        @Volatile
        private var instance: TapNotificationService? = null
        
        fun getInstance(context: Context): TapNotificationService {
            return instance ?: synchronized(this) {
                instance ?: TapNotificationService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    
    // 核心组件
    private val notificationManager = NotificationManager.getInstance(context)
    private val configManager = NotificationConfigManager.getInstance(context)
    private val lifecycleIntegrator = TapLifecycleIntegrator.getInstance(context)
    private val offlineHandler = NotificationOfflineHandler.getInstance(context)
    private val dozeHandler = TapDozeHandler.getInstance(context)
    
    private var isInitialized = false
    private var isStarted = false
    
    private var messageCallback: ((NotificationMessage) -> Unit)? = null
    
    /**
     * 初始化推送服务
     * 
     * 从配置加载推送服务提供商并初始化
     */
    suspend fun initialize(): Boolean {
        return mutex.withLock {
            if (isInitialized) {
                Log.w(TAG, "推送服务已初始化")
                return@withLock true
            }
            
            try {
                Log.i(TAG, "初始化推送服务")
                
                // 检查是否有本地配置
                if (!configManager.hasLocalConfig()) {
                    Log.w(TAG, "未找到推送服务配置，跳过初始化")
                    return@withLock false
                }
                
                // 加载配置
                val config = configManager.getLocalConfig()
                if (config == null) {
                    Log.e(TAG, "加载推送服务配置失败")
                    return@withLock false
                }
                
                // 从pushServiceInfo提取credentials
                val credentials = config.pushServiceInfo.credentials.mapValues { it.value.toString() }.toMutableMap()
                credentials["region"] = config.pushServiceInfo.region
                
                // 创建Provider
                val provider = NotificationProviderFactory.getInstance().createProvider(
                    context,
                    config.provider,
                    credentials
                )
                
                if (provider == null) {
                    Log.e(TAG, "创建推送服务提供商失败: ${config.provider}")
                    return@withLock false
                }
                
                // 初始化NotificationManager
                val initResult = notificationManager.initialize(provider, config)
                
                if (!initResult) {
                    Log.e(TAG, "NotificationManager初始化失败")
                    return@withLock false
                }
                
                isInitialized = true
                Log.i(TAG, "推送服务初始化成功: provider=${config.provider}")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "初始化推送服务异常", e)
                false
            }
        }
    }
    
    /**
     * 启动推送服务
     * 
     * 启动生命周期集成和所有相关组件
     * 
     * @param onMessage 收到推送消息的回调
     */
    fun start(onMessage: (NotificationMessage) -> Unit) {
        scope.launch {
            mutex.withLock {
                if (isStarted) {
                    Log.w(TAG, "推送服务已启动")
                    return@launch
                }
                
                if (!isInitialized) {
                    Log.w(TAG, "推送服务未初始化，尝试初始化")
                    if (!initialize()) {
                        Log.e(TAG, "推送服务初始化失败，无法启动")
                        return@launch
                    }
                }
                
                try {
                    Log.i(TAG, "启动推送服务")
                    
                    messageCallback = onMessage
                    
                    // 获取userId（从配置中）
                    val config = notificationManager.getWebhookConfig()
                    if (config == null) {
                        Log.e(TAG, "获取Webhook配置失败")
                        return@launch
                    }
                    
                    val userId = config.userId
                    
                    // 启动生命周期集成
                    lifecycleIntegrator.start(userId, onMessage)
                    
                    // 启动Doze处理器
                    dozeHandler.start(
                        onStateChange = { inDoze ->
                            handleDozeStateChange(inDoze)
                        },
                        onCheck = {
                            handleDozeCheck()
                        }
                    )
                    
                    isStarted = true
                    Log.i(TAG, "推送服务启动成功")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "启动推送服务异常", e)
                }
            }
        }
    }
    
    /**
     * 停止推送服务
     */
    fun stop() {
        scope.launch {
            mutex.withLock {
                if (!isStarted) {
                    return@launch
                }
                
                try {
                    Log.i(TAG, "停止推送服务")
                    
                    // 停止所有组件
                    lifecycleIntegrator.stop()
                    dozeHandler.stop()
                    
                    isStarted = false
                    messageCallback = null
                    
                    Log.i(TAG, "推送服务已停止")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "停止推送服务异常", e)
                }
            }
        }
    }
    
    /**
     * 重启推送服务
     */
    fun restart(onMessage: (NotificationMessage) -> Unit) {
        scope.launch {
            stop()
            // 等待停止完成
            kotlinx.coroutines.delay(500)
            start(onMessage)
        }
    }
    
    /**
     * 处理Doze状态变化
     */
    private fun handleDozeStateChange(inDoze: Boolean) {
        Log.i(TAG, "Doze状态变化: inDoze=$inDoze")
        
        if (inDoze) {
            // 进入Doze模式，不需要特别处理，连接会被保持或断开
            Log.d(TAG, "进入Doze模式，定期检查已激活")
        } else {
            // 退出Doze模式，尝试恢复正常连接
            Log.d(TAG, "退出Doze模式，尝试恢复连接")
            scope.launch {
                offlineHandler.processOfflineMessages()
            }
        }
    }
    
    /**
     * 处理Doze模式下的定期检查
     */
    private fun handleDozeCheck() {
        Log.d(TAG, "执行Doze模式定期检查")
        
        scope.launch {
            try {
                // 在Doze模式下定期检查离线消息
                offlineHandler.processOfflineMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Doze检查异常", e)
            }
        }
    }
    
    /**
     * 手动触发离线消息处理
     */
    fun processOfflineMessages() {
        scope.launch {
            try {
                offlineHandler.processOfflineMessages()
            } catch (e: Exception) {
                Log.e(TAG, "处理离线消息失败", e)
            }
        }
    }
    
    /**
     * 手动触发重连
     */
    fun reconnect() {
        lifecycleIntegrator.reconnect()
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return lifecycleIntegrator.isConnected()
    }
    
    /**
     * 检查是否已启动
     */
    fun isStarted(): Boolean {
        return isStarted
    }
    
    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
    
    /**
     * 获取服务状态
     */
    fun getServiceStatus(): TapNotificationServiceStatus {
        return TapNotificationServiceStatus(
            initialized = isInitialized,
            started = isStarted,
            connected = isConnected(),
            inDozeMode = dozeHandler.isInDozeMode(),
            processingOffline = offlineHandler.isProcessing(),
            lastOfflineProcessTime = offlineHandler.getLastProcessTime()
        )
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        scope.launch {
            mutex.withLock {
                try {
                    Log.i(TAG, "清理推送服务资源")
                    
                    stop()
                    lifecycleIntegrator.cleanup()
                    
                    isInitialized = false
                    
                } catch (e: Exception) {
                    Log.e(TAG, "清理资源失败", e)
                }
            }
        }
    }
}

/**
 * Tap推送服务状态
 */
data class TapNotificationServiceStatus(
    val initialized: Boolean,
    val started: Boolean,
    val connected: Boolean,
    val inDozeMode: Boolean,
    val processingOffline: Boolean,
    val lastOfflineProcessTime: Long
) {
    fun isHealthy(): Boolean {
        return initialized && started && (connected || processingOffline)
    }
}

