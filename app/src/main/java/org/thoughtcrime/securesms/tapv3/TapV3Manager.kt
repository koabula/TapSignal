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
    
    private val signalStore = org.thoughtcrime.securesms.keyvalue.SignalStore.tapV3
    
    init {
        restoreConfiguration()
    }
    
    fun isConfigured(): Boolean {
        val hasPinata = signalStore.getStringValue("tapv3.pinata.api_key", null) != null
        val hasWeb3 = signalStore.getStringValue("tapv3.web3storage.token", null) != null
        return (hasPinata || hasWeb3) && pushProvider.isRegistered()
    }
    
    fun configurePinata(apiKey: String, apiSecret: String) {
        ipfsGatewayManager.configurePinata(apiKey, apiSecret)
        signalStore.putStringValue("tapv3.pinata.api_key", apiKey)
        signalStore.putStringValue("tapv3.pinata.api_secret", apiSecret)
        Log.d(TAG, "Configured Pinata")
    }
    
    fun configureWeb3Storage(token: String) {
        ipfsGatewayManager.configureWeb3Storage(token)
        signalStore.putStringValue("tapv3.web3storage.token", token)
        Log.d(TAG, "Configured Web3.Storage")
    }
    
    private fun restoreConfiguration() {
        val pinataKey = signalStore.getStringValue("tapv3.pinata.api_key", null)
        val pinataSecret = signalStore.getStringValue("tapv3.pinata.api_secret", null)
        if (pinataKey != null && pinataSecret != null) {
            ipfsGatewayManager.configurePinata(pinataKey, pinataSecret)
        }
        
        val web3Token = signalStore.getStringValue("tapv3.web3storage.token", null)
        if (web3Token != null) {
            ipfsGatewayManager.configureWeb3Storage(web3Token)
        }
        
        val endpoint = signalStore.getStringValue("tapv3.my_push_endpoint", null)
        if (endpoint != null) {
            pushEndpointManager.saveMyEndpoint(endpoint)
        }
    }
    
    fun registerPush(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        pushProvider.register(
            onNewEndpoint = { endpoint ->
                Log.d(TAG, "Push registration successful")
                pushEndpointManager.saveMyEndpoint(endpoint)
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
