package org.thoughtcrime.securesms.tapv3.crypto

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result

class KPushManager private constructor(
    private val context: Context
) {
    
    fun generateKey(): ByteArray {
        val key = TapV3Crypto.generateKPushKey()
        Log.d(TAG, "Generated new k_push key")
        return key
    }
    
    fun saveKey(recipientId: String, key: ByteArray, keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL) {
        val encoded = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val keyString = getKeyForRecipient(recipientId, keyVersion)
        
        SignalStore.tapV3.putStringValue(keyString, encoded)
        SignalStore.tapV3.putIntegerValue(getActiveVersionKey(recipientId), keyVersion)
        
        Log.d(TAG, "Saved k_push key for recipient: ${recipientId.take(8)}..., version: $keyVersion")
    }
    
    fun getKey(recipientId: String, keyVersion: Int? = null): TapV3Result<ByteArray> {
        val version = keyVersion ?: getActiveKeyVersion(recipientId)
        if (version == null) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "No active key version for recipient: ${recipientId.take(8)}..."
            )
        }
        
        val keyString = getKeyForRecipient(recipientId, version)
        val encoded = SignalStore.tapV3.getStringValue(keyString, null)
        
        if (encoded == null) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "Key not found for recipient: ${recipientId.take(8)}..., version: $version"
            )
        }
        
        return try {
            val key = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            TapV3Result.Success(key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode key", e)
            TapV3Result.Failure(TapV3Error.KEY_NOT_FOUND, "Failed to decode key: ${e.message}", e)
        }
    }
    
    fun removeKey(recipientId: String, keyVersion: Int? = null) {
        val version = keyVersion ?: getActiveKeyVersion(recipientId)
        if (version != null) {
            val keyString = getKeyForRecipient(recipientId, version)
            SignalStore.tapV3.removeValue(keyString)
            
            if (keyVersion == null) {
                SignalStore.tapV3.removeValue(getActiveVersionKey(recipientId))
            }
            
            Log.d(TAG, "Removed k_push key for recipient: ${recipientId.take(8)}..., version: $version")
        }
    }
    
    fun hasKey(recipientId: String): Boolean {
        val version = getActiveKeyVersion(recipientId) ?: return false
        val keyString = getKeyForRecipient(recipientId, version)
        return SignalStore.tapV3.getStringValue(keyString, null) != null
    }
    
    fun getActiveKeyVersion(recipientId: String): Int? {
        val version = SignalStore.tapV3.getIntegerValue(getActiveVersionKey(recipientId), -1)
        return if (version > 0) version else null
    }
    
    private fun getKeyForRecipient(recipientId: String, keyVersion: Int): String {
        return "tapv3_kpush_${recipientId}_v$keyVersion"
    }
    
    private fun getActiveVersionKey(recipientId: String): String {
        return "tapv3_kpush_active_version_$recipientId"
    }
    
    companion object {
        private val TAG = Log.tag(KPushManager::class.java)
        
        @Volatile
        private var INSTANCE: KPushManager? = null
        
        fun getInstance(context: Context): KPushManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KPushManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
