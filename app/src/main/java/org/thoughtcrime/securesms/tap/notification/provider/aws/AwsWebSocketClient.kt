package org.thoughtcrime.securesms.tap.notification.provider.aws

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
 * AWS API Gateway WebSocket客户端
 * 负责管理WebSocket连接和接收推送消息
 */
class AwsWebSocketClient(
    private val endpoint: String,
    private val userId: String
) {
    companion object {
        private const val TAG = "AwsWebSocketClient"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val WRITE_TIMEOUT_SEC = 10L
        private const val PING_INTERVAL_SEC = 30L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val BASE_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_ATTEMPTS = 15
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
    private val sessionId: String = java.util.UUID.randomUUID().toString()
    
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
        return "$endpoint?userId=$userId"
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
                Log.e(TAG, "WebSocket failure", t)
                isConnected.set(false)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                
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
            return
        }

        val currentSession = sessionId
        reconnectJob = scope.launch {
            var delay = BASE_RECONNECT_DELAY_MS
            var attempts = 0

            while (isActive && !isConnected.get() && attempts < MAX_RECONNECT_ATTEMPTS) {
                // 若会话已被替换（上层断开并创建了新实例），立即停止重连
                if (currentSession != sessionId) {
                    Log.i(TAG, "Reconnect aborted due to session change")
                    break
                }
                attempts++
                Log.i(TAG, "Reconnect attempt #$attempts in ${delay}ms")
                
                delay(delay)
                
                try {
                    connect().getOrThrow()
                    Log.i(TAG, "Reconnected successfully")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt #$attempts failed", e)
                    delay = (delay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
            
            if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached. Giving up.")
                _connectionState.value = ConnectionState.Error("Max reconnect attempts reached")
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        messageChannel.close()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}

