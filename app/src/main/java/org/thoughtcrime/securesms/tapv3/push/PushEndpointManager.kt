package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore

class PushEndpointManager private constructor(
    private val context: Context
) {
    
    fun saveEndpoint(recipientId: String, endpoint: String) {
        val key = getKeyForRecipient(recipientId)
        SignalStore.tapV3.putStringValue(key, endpoint)
        Log.d(TAG, "Saved push endpoint for recipient: ${recipientId.take(8)}...")
    }
    
    fun getEndpoint(recipientId: String): String? {
        val key = getKeyForRecipient(recipientId)
        return SignalStore.tapV3.getStringValue(key, null)
    }
    
    fun removeEndpoint(recipientId: String) {
        val key = getKeyForRecipient(recipientId)
        SignalStore.tapV3.removeValue(key)
        Log.d(TAG, "Removed push endpoint for recipient: ${recipientId.take(8)}...")
    }
    
    fun saveMyEndpoint(endpoint: String) {
        SignalStore.tapV3.putStringValue(KEY_MY_ENDPOINT, endpoint)
        Log.d(TAG, "Saved my push endpoint: $endpoint")
    }
    
    fun getMyEndpoint(): String? {
        return SignalStore.tapV3.getStringValue(KEY_MY_ENDPOINT, null)
    }
    
    fun clearMyEndpoint() {
        SignalStore.tapV3.removeValue(KEY_MY_ENDPOINT)
        Log.d(TAG, "Cleared my push endpoint")
    }
    
    private fun getKeyForRecipient(recipientId: String): String {
        return "tapv3_push_endpoint_$recipientId"
    }
    
    companion object {
        private val TAG = Log.tag(PushEndpointManager::class.java)
        private const val KEY_MY_ENDPOINT = "tapv3_my_push_endpoint"
        
        @Volatile
        private var INSTANCE: PushEndpointManager? = null
        
        fun getInstance(context: Context): PushEndpointManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PushEndpointManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
