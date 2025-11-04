package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 腾讯云函数URL WebSocket客户端
 * 负责管理WebSocket连接和接收推送消息
 * 连接到启用WebSocket支持的函数URL（WSS地址）
 */
class TencentWebSocketClient(
    private val endpoint: String,
    private val userId: String
) {
    companion object {
        private const val TAG = "TencentWebSocketClient"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val WRITE_TIMEOUT_SEC = 10L
        private const val PING_INTERVAL_SEC = 30L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val BASE_RECONNECT_DELAY_MS = 1000L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val messageChannel = Channel<NotificationMessage>(Channel.BUFFERED)
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private var connectionContinuation: kotlin.coroutines.Continuation<Result<String>>? = null

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    suspend fun connect(): Result<String> = suspendCoroutine { continuation ->
        try {
            _connectionState.value = ConnectionState.Connecting
            connectionContinuation = continuation
            
            val wsUrl = buildWebSocketUrl()
            Log.i(TAG, "Connecting to WebSocket: $wsUrl")
            
            val request = Request.Builder()
                .url(wsUrl)
                .build()
            
            webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection setup", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Setup failed")
            continuation.resume(Result.failure(e))
        }
    }

    suspend fun disconnect() {
        try {
            Log.i(TAG, "Disconnecting from WebSocket")
            
            reconnectJob?.cancel()
            reconnectJob = null
            
            webSocket?.close(1000, "Client disconnect")
            webSocket = null
            
            isConnected.set(false)
            _connectionState.value = ConnectionState.Disconnected
            
            Log.i(TAG, "Disconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    suspend fun sendMessage(message: String): Result<Unit> {
        val ws = webSocket
        if (ws == null || !isConnected.get()) {
            return Result.failure(Exception("Not connected"))
        }
        
        return try {
            val success = ws.send(message)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to send message"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            Result.failure(e)
        }
    }

    fun getMessageChannel(): Channel<NotificationMessage> = messageChannel

    private fun buildWebSocketUrl(): String {
        val url = "$endpoint?userId=$userId"
        // P2修复：记录构建的URL（脱敏处理）
        val maskedUrl = url.replace(Regex("userId=[^&]+"), "userId=***")
        Log.d(TAG, "Building WebSocket URL: $maskedUrl")
        return url
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connection opened")
                isConnected.set(true)
                _connectionState.value = ConnectionState.Connected
                
                connectionContinuation?.resume(Result.success(userId))
                connectionContinuation = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received WebSocket message: $text")
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: code=$code, reason=$reason")
                isConnected.set(false)
                _connectionState.value = ConnectionState.Disconnected
                
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // P2修复：详细记录错误信息和HTTP响应
                val errorDetails = buildString {
                    append("WebSocket failure: ${t.message}")
                    if (response != null) {
                        append("\nHTTP Status: ${response.code}")
                        append("\nHTTP Message: ${response.message}")
                        try {
                            val responseBody = response.peekBody(1024).string()
                            append("\nResponse Body: $responseBody")
                        } catch (e: Exception) {
                            append("\nResponse Body: (unable to read)")
                        }
                        // OkHttp Headers 不是 Map 的 forEach(K,V)，用索引遍历
                        for (i in 0 until response.headers.size) {
                            val name = response.headers.name(i)
                            val value = response.headers.value(i)
                            append("\nHeader $name: $value")
                        }
                    }
                }
                
                Log.e(TAG, errorDetails, t)
                
                isConnected.set(false)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                
                // P2修复：记录失败原因，帮助诊断问题
                val failureReason = when {
                    response?.code == 400 -> "Bad Request - 可能是WebSocket未启用或URL格式错误"
                    response?.code == 401 -> "Unauthorized - 认证失败"
                    response?.code == 403 -> "Forbidden - 权限不足"
                    response?.code == 404 -> "Not Found - URL不存在"
                    response?.code == 500 -> "Internal Server Error - 服务器错误"
                    t is javax.net.ssl.SSLHandshakeException -> "SSL握手失败 - 可能是证书或协议问题"
                    t is java.net.ProtocolException -> "协议错误 - 可能不支持WebSocket协议升级"
                    else -> "未知错误: ${t.javaClass.simpleName}"
                }
                
                Log.w(TAG, "Connection failure reason: $failureReason")
                
                connectionContinuation?.resume(Result.failure(t))
                connectionContinuation = null
                
                scheduleReconnect()
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val notificationMessage = parseNotificationMessage(text)
            if (notificationMessage != null) {
                scope.launch {
                    messageChannel.send(notificationMessage)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message", e)
        }
    }

    private fun parseNotificationMessage(payload: String): NotificationMessage? {
        return try {
            val json = org.json.JSONObject(payload)
            NotificationMessage(
                type = json.optString("type", NotificationMessage.TYPE_NEW_MESSAGE),
                senderId = json.getString("senderId"),
                timestamp = json.getLong("timestamp"),
                metadata = json.optJSONObject("metadata")?.let { metaJson ->
                    metaJson.keys().asSequence().associateWith { key ->
                        metaJson.get(key)
                    }
                } ?: emptyMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification message", e)
            null
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "Reconnect already scheduled, skipping")
            return
        }

        reconnectJob = scope.launch {
            var delay = BASE_RECONNECT_DELAY_MS
            var attempts = 0
            val maxAttempts = 15  // P0修复：增加最大重试次数到15次

            while (isActive && !isConnected.get() && attempts < maxAttempts) {
                attempts++
                Log.i(TAG, "Reconnect attempt #$attempts in ${delay}ms (max: $maxAttempts)")
                
                delay(delay)
                
                try {
                    val result = connect()
                    if (result.isSuccess) {
                        Log.i(TAG, "Reconnected successfully after $attempts attempts")
                        break
                    } else {
                        val error = result.exceptionOrNull()
                        Log.w(TAG, "Reconnect attempt #$attempts failed", error)
                        // P2修复：记录失败原因
                        error?.let {
                            Log.d(TAG, "Reconnect error type: ${it.javaClass.simpleName}, message: ${it.message}")
                        }
                        delay = (delay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt #$attempts failed with exception", e)
                    delay = (delay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
            
            // P0修复：到达最大重连次数后设置错误状态
            if (attempts >= maxAttempts && !isConnected.get()) {
                Log.e(TAG, "Max reconnect attempts ($maxAttempts) reached. Giving up.")
                _connectionState.value = ConnectionState.Error("Failed to reconnect after $maxAttempts attempts")
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        messageChannel.close()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}

