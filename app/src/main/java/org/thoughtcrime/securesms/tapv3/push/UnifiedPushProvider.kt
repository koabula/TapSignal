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
    
    suspend fun send(endpoint: String, payload: ByteArray): TapV3Result<Unit> {
        return try {
            val url = java.net.URL(endpoint)
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            connection.outputStream.use { outputStream ->
                outputStream.write(payload)
                outputStream.flush()
            }
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode in 200..299) {
                Log.d(TAG, "Successfully sent push message: $responseCode")
                TapV3Result.Success(Unit)
            } else {
                Log.e(TAG, "Push send failed: HTTP $responseCode")
                TapV3Result.Failure(
                    TapV3Error.PUSH_ERROR,
                    "Push failed: HTTP $responseCode"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send push message", e)
            TapV3Result.Failure(TapV3Error.PUSH_ERROR, "Push error: ${e.message}", e)
        }
    }
    
    companion object {
        private val TAG = Log.tag(UnifiedPushProvider::class.java)
        
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
