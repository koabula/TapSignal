package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.unifiedpush.android.connector.UnifiedPush as UP

class UnifiedPushProvider private constructor(
    private val context: Context
) {
    
    private var currentEndpoint: String? = null
    
    fun isRegistered(): Boolean {
        return UP.getDistributor(context).isNotEmpty()
    }
    
    fun getCurrentEndpoint(): String? {
        return currentEndpoint
    }
    
    fun register(onNewEndpoint: (String) -> Unit, onError: (String) -> Unit) {
        val distributors = UP.getDistributors(context)
        
        if (distributors.isEmpty()) {
            Log.e(TAG, "No UnifiedPush distributor available")
            onError("No UnifiedPush distributor installed")
            return
        }
        
        if (!UP.getDistributor(context).isNotEmpty()) {
            UP.saveDistributor(context, distributors.first())
            Log.d(TAG, "Set UnifiedPush distributor: ${distributors.first()}")
        }
        
        UP.registerAppWithDialog(context)
        Log.d(TAG, "Registering with UnifiedPush")
    }
    
    fun updateEndpoint(endpoint: String) {
        currentEndpoint = endpoint
        Log.d(TAG, "UnifiedPush endpoint updated: $endpoint")
    }
    
    fun unregister() {
        UP.unregisterApp(context)
        currentEndpoint = null
        Log.d(TAG, "Unregistered from UnifiedPush")
    }
    
    suspend fun send(endpoint: String, payload: ByteArray, ttlSeconds: Int = DEFAULT_TTL_SECONDS): TapV3Result<Unit> {
        return try {
            Log.d(TAG, "Sending push message to endpoint: ${endpoint.take(50)}...")
            Log.d(TAG, "Payload size: ${payload.size} bytes, TTL: $ttlSeconds seconds")
            
            val url = java.net.URL(endpoint)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("TTL", ttlSeconds.toString())
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            connection.outputStream.use { outputStream ->
                outputStream.write(payload)
                outputStream.flush()
            }
            
            val responseCode = connection.responseCode
            
            if (responseCode in 200..299) {
                Log.d(TAG, "Successfully sent push message: HTTP $responseCode")
                connection.disconnect()
                TapV3Result.Success(Unit)
            } else {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                connection.disconnect()
                
                Log.e(TAG, "Push send failed: HTTP $responseCode, endpoint: ${endpoint.take(50)}..., error: $errorBody")
                TapV3Result.Failure(
                    TapV3Error.PUSH_ERROR,
                    "HTTP $responseCode" + if (errorBody.isNotEmpty()) ": $errorBody" else ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send push message to ${endpoint.take(50)}...", e)
            TapV3Result.Failure(TapV3Error.PUSH_ERROR, "Push error: ${e.message}", e)
        }
    }
    
    companion object {
        private val TAG = Log.tag(UnifiedPushProvider::class.java)
        private const val DEFAULT_TTL_SECONDS = 86400  // 24 hours
        
        @Volatile
        private var INSTANCE: UnifiedPushProvider? = null
        
        fun getInstance(context: Context): UnifiedPushProvider {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnifiedPushProvider(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
