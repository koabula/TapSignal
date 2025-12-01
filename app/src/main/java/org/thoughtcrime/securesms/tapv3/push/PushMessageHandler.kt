package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
import org.unifiedpush.android.connector.MessagingReceiver

class PushMessageHandler : MessagingReceiver() {
    
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.d(TAG, "New UnifiedPush endpoint received: $endpoint")
        
        val provider = UnifiedPushProvider.getInstance(context)
        provider.updateEndpoint(endpoint)
        
        val endpointManager = PushEndpointManager.getInstance(context)
        endpointManager.saveMyEndpoint(endpoint)
        
        broadcastEndpointUpdate(context, endpoint)
    }
    
    override fun onRegistrationFailed(context: Context, instance: String) {
        Log.e(TAG, "UnifiedPush registration failed")
        broadcastRegistrationFailed(context)
    }
    
    override fun onUnregistered(context: Context, instance: String) {
        Log.d(TAG, "UnifiedPush unregistered")
        
        val endpointManager = PushEndpointManager.getInstance(context)
        endpointManager.clearMyEndpoint()
        
        broadcastUnregistered(context)
    }
    
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Log.d(TAG, "Received UnifiedPush message: ${message.size} bytes")
        
        try {
            val decoded = android.util.Base64.decode(message, android.util.Base64.NO_WRAP)
            
            broadcastMessageReceived(context, decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process push message", e)
        }
    }
    
    private fun broadcastEndpointUpdate(context: Context, endpoint: String) {
        val intent = Intent(ACTION_ENDPOINT_UPDATE).apply {
            putExtra(EXTRA_ENDPOINT, endpoint)
        }
        context.sendBroadcast(intent)
    }
    
    private fun broadcastRegistrationFailed(context: Context) {
        val intent = Intent(ACTION_REGISTRATION_FAILED)
        context.sendBroadcast(intent)
    }
    
    private fun broadcastUnregistered(context: Context) {
        val intent = Intent(ACTION_UNREGISTERED)
        context.sendBroadcast(intent)
    }
    
    private fun broadcastMessageReceived(context: Context, message: ByteArray) {
        val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(intent)
    }
    
    companion object {
        private val TAG = Log.tag(PushMessageHandler::class.java)
        
        const val ACTION_ENDPOINT_UPDATE = "org.thoughtcrime.securesms.tapv3.ENDPOINT_UPDATE"
        const val ACTION_REGISTRATION_FAILED = "org.thoughtcrime.securesms.tapv3.REGISTRATION_FAILED"
        const val ACTION_UNREGISTERED = "org.thoughtcrime.securesms.tapv3.UNREGISTERED"
        const val ACTION_MESSAGE_RECEIVED = "org.thoughtcrime.securesms.tapv3.MESSAGE_RECEIVED"
        
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_MESSAGE = "message"
    }
}
