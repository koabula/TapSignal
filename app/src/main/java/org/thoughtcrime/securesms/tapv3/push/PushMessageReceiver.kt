package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.integration.TapV3ReceiveIntegrator
import org.unifiedpush.android.connector.MessagingReceiver
import org.json.JSONObject

class PushMessageReceiver : MessagingReceiver() {
    
    companion object {
        private val TAG = Log.tag(PushMessageReceiver::class.java)
    }
    
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Log.d(TAG, "Received UnifiedPush message: size=${message.size}, instance=$instance")
        
        try {
            val messageString = String(message, Charsets.UTF_8)
            Log.d(TAG, "Message content: ${messageString.take(100)}...")
            
            val json = JSONObject(messageString)
            val base64Data = json.getString("data")
            val senderId = json.getString("senderId")
            
            CoroutineScope(Dispatchers.IO).launch {
                val receiveIntegrator = TapV3ReceiveIntegrator.getInstance(context)
                val result = receiveIntegrator.receiveMessage(base64Data, senderId)
                
                if (result.success) {
                    Log.d(TAG, "Message processed successfully")
                } else {
                    Log.e(TAG, "Message processing failed: ${result.error}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle UnifiedPush message", e)
        }
    }
    
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "Received new Push endpoint: endpoint=$endpoint, instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.updateEndpoint(endpoint)
            
            Log.i(TAG, "Push endpoint updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Push endpoint", e)
        }
    }
    
    override fun onRegistrationFailed(context: Context, instance: String) {
        Log.e(TAG, "UnifiedPush registration failed: instance=$instance")
    }
    
    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "UnifiedPush已注销: instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.unregister()
            
            Log.i(TAG, "Push服务已注销")
        } catch (e: Exception) {
            Log.e(TAG, "处理注销事件失败", e)
        }
    }
}
