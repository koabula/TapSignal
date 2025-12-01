package org.thoughtcrime.securesms.tapv3

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager
import org.thoughtcrime.securesms.tapv3.push.UnifiedPushProvider

class TapV3Manager private constructor(
    private val context: Context
) {
    
    val ipfsGatewayManager: IpfsGatewayManager = IpfsGatewayManager.getInstance(context)
    val pushProvider: UnifiedPushProvider = UnifiedPushProvider.getInstance(context)
    val pushEndpointManager: PushEndpointManager = PushEndpointManager.getInstance(context)
    val kPushManager: KPushManager = KPushManager.getInstance(context)
    
    fun isConfigured(): Boolean {
        return pushProvider.isRegistered()
    }
    
    fun configurePinata(apiKey: String, apiSecret: String) {
        ipfsGatewayManager.configurePinata(apiKey, apiSecret)
        Log.d(TAG, "Configured Pinata")
    }
    
    fun configureWeb3Storage(token: String) {
        ipfsGatewayManager.configureWeb3Storage(token)
        Log.d(TAG, "Configured Web3.Storage")
    }
    
    fun registerPush(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        pushProvider.register(
            onNewEndpoint = { endpoint ->
                Log.d(TAG, "Push registration successful")
                onSuccess(endpoint)
            },
            onError = { error ->
                Log.e(TAG, "Push registration failed: $error")
                onError(error)
            }
        )
    }
    
    fun unregisterPush() {
        pushProvider.unregister()
        Log.d(TAG, "Unregistered push")
    }
    
    fun clearConfiguration() {
        ipfsGatewayManager.clearConfiguration()
        unregisterPush()
        Log.d(TAG, "Cleared configuration")
    }
    
    companion object {
        private val TAG = Log.tag(TapV3Manager::class.java)
        
        @Volatile
        private var INSTANCE: TapV3Manager? = null
        
        fun getInstance(context: Context): TapV3Manager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3Manager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
