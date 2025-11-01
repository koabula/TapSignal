package org.thoughtcrime.securesms.tap.notification.provider.tencent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.thoughtcrime.securesms.tap.notification.NotificationMessage
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 腾讯云IoT Hub MQTT客户端
 * 负责管理MQTT连接、订阅主题和接收消息
 */
class TencentIoTHubClient(
    private val context: Context,
    private val endpoint: String,
    private val productId: String,
    private val deviceName: String,
    private val deviceSecret: String,
    private val certificatePem: String? = null,
    private val privateKeyPem: String? = null
) {
    companion object {
        private const val TAG = "TencentIoTHubClient"
        private const val QOS = 1
        private const val KEEP_ALIVE_INTERVAL = 240
        private const val CONNECTION_TIMEOUT = 10
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val BASE_RECONNECT_DELAY_MS = 1000L
    }

    private var mqttClient: MqttAsyncClient? = null
    private val isConnected = AtomicBoolean(false)
    private val messageChannel = Channel<NotificationMessage>(Channel.BUFFERED)
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    suspend fun connect(): Result<String> = suspendCoroutine { continuation ->
        try {
            _connectionState.value = ConnectionState.Connecting
            
            val clientId = "$productId$deviceName"
            val mqttBrokerUrl = if (certificatePem != null) {
                "ssl://$endpoint:8883"
            } else {
                "tcp://$endpoint:1883"
            }
            
            val client = MqttAsyncClient(mqttBrokerUrl, clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                keepAliveInterval = KEEP_ALIVE_INTERVAL
                connectionTimeout = CONNECTION_TIMEOUT
                isAutomaticReconnect = false
                
                if (certificatePem != null && privateKeyPem != null) {
                    socketFactory = createSslSocketFactory(certificatePem, privateKeyPem)
                } else {
                    val (username, pwd) = generateCredentials()
                    userName = username
                    password = pwd.toCharArray()
                }
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "Connection complete. Reconnect=$reconnect, URI=$serverURI")
                    isConnected.set(true)
                    _connectionState.value = ConnectionState.Connected
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost", cause)
                    isConnected.set(false)
                    _connectionState.value = ConnectionState.Error(cause?.message ?: "Connection lost")
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    Log.d(TAG, "Message arrived on topic: $topic")
                    if (message != null) {
                        handleIncomingMessage(topic, message)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.v(TAG, "Delivery complete")
                }
            })

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Connected to Tencent IoT Hub: $endpoint")
                    isConnected.set(true)
                    _connectionState.value = ConnectionState.Connected
                    continuation.resume(Result.success(clientId))
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to connect to Tencent IoT Hub", exception)
                    isConnected.set(false)
                    _connectionState.value = ConnectionState.Error(exception?.message ?: "Connection failed")
                    continuation.resume(Result.failure(exception ?: Exception("Connection failed")))
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error during connection setup", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Setup failed")
            continuation.resumeWithException(e)
        }
    }

    suspend fun disconnect() {
        try {
            reconnectJob?.cancel()
            reconnectJob = null
            
            mqttClient?.let { client ->
                if (client.isConnected) {
                    suspendCoroutine { continuation ->
                        client.disconnect(null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                Log.i(TAG, "Disconnected from Tencent IoT Hub")
                                isConnected.set(false)
                                _connectionState.value = ConnectionState.Disconnected
                                continuation.resume(Unit)
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                Log.w(TAG, "Error during disconnect", exception)
                                isConnected.set(false)
                                _connectionState.value = ConnectionState.Disconnected
                                continuation.resume(Unit)
                            }
                        })
                    }
                }
                client.close()
            }
            mqttClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    suspend fun subscribe(topic: String): Result<Unit> = suspendCoroutine { continuation ->
        val client = mqttClient
        if (client == null || !client.isConnected) {
            continuation.resume(Result.failure(Exception("Not connected")))
            return@suspendCoroutine
        }

        try {
            client.subscribe(topic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to topic: $topic")
                    continuation.resume(Result.success(Unit))
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to topic: $topic", exception)
                    continuation.resume(Result.failure(exception ?: Exception("Subscribe failed")))
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to topic: $topic", e)
            continuation.resumeWithException(e)
        }
    }

    suspend fun publish(topic: String, payload: ByteArray): Result<Unit> = suspendCoroutine { continuation ->
        val client = mqttClient
        if (client == null || !client.isConnected) {
            continuation.resume(Result.failure(Exception("Not connected")))
            return@suspendCoroutine
        }

        try {
            val message = MqttMessage(payload).apply {
                qos = QOS
                isRetained = false
            }

            client.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Published message to topic: $topic")
                    continuation.resume(Result.success(Unit))
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish to topic: $topic", exception)
                    continuation.resume(Result.failure(exception ?: Exception("Publish failed")))
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing to topic: $topic", e)
            continuation.resumeWithException(e)
        }
    }

    fun getMessageChannel(): Channel<NotificationMessage> = messageChannel

    private fun handleIncomingMessage(topic: String?, message: MqttMessage) {
        try {
            val payload = String(message.payload)
            Log.d(TAG, "Received message on topic $topic: $payload")
            
            val notificationMessage = parseNotificationMessage(payload)
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

        reconnectJob = scope.launch {
            var delay = BASE_RECONNECT_DELAY_MS
            var attempts = 0

            while (isActive && !isConnected.get()) {
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
        }
    }

    private fun generateCredentials(): Pair<String, String> {
        val timestamp = System.currentTimeMillis()
        val username = "$productId$deviceName;21010406;$timestamp;$timestamp"
        val content = "deviceName=$deviceName&nonce=$username&productId=$productId&timestamp=$timestamp"
        
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(deviceSecret.toByteArray(), "HmacSHA256")
        hmac.init(secretKeySpec)
        val hash = hmac.doFinal(content.toByteArray())
        val password = Base64.getEncoder().encodeToString(hash)
        
        return Pair(username, password)
    }

    private fun createSslSocketFactory(certificatePem: String, privateKeyPem: String): SSLSocketFactory {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        
        val certInputStream = certificatePem.byteInputStream()
        val certificate = certificateFactory.generateCertificate(certInputStream) as X509Certificate
        certInputStream.close()

        val privateKey = parsePrivateKey(privateKeyPem)

        val password = "temp".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "tencent-iot-key",
            privateKey,
            password,
            arrayOf(certificate)
        )

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, password)

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)

        return sslContext.socketFactory
    }

    private fun parsePrivateKey(privateKeyPem: String): PrivateKey {
        var pem = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()

        val encoded = Base64.getDecoder().decode(pem)
        val keySpec = PKCS8EncodedKeySpec(encoded)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(keySpec)
    }

    fun cleanup() {
        scope.cancel()
        messageChannel.close()
    }
}

