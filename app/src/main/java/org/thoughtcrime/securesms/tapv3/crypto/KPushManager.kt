package org.thoughtcrime.securesms.tapv3.crypto

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result

/**
 * k_push 密钥管理器
 * 
 * 密钥模型:
 * - myKPush: 我自己的 k_push，所有人给我发消息都使用这个密钥加密，我用它解密收到的消息
 * - peerKPush[recipientId]: 对方的 k_push，我给对方发消息时使用这个密钥加密
 */
class KPushManager private constructor(
    private val context: Context
) {
    
    fun generateKey(): ByteArray {
        val key = TapV3Crypto.generateKPushKey()
        Log.d(TAG, "Generated new k_push key")
        return key
    }
    
    /**
     * 获取或生成我自己的 k_push
     * 如果还没有则自动生成一个
     */
    fun getOrCreateMyKPush(): ByteArray {
        val existing = getMyKPush()
        if (existing != null) {
            return existing
        }
        
        val newKey = generateKey()
        saveMyKPush(newKey)
        return newKey
    }
    
    /**
     * 保存我自己的 k_push
     */
    fun saveMyKPush(key: ByteArray, keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL) {
        val encoded = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        SignalStore.tapV3.putStringValue(KEY_MY_KPUSH, encoded)
        SignalStore.tapV3.putIntegerValue(KEY_MY_KPUSH_VERSION, keyVersion)
        Log.d(TAG, "Saved my k_push, version: $keyVersion")
    }
    
    /**
     * 获取我自己的 k_push，用于解密收到的消息
     */
    fun getMyKPush(): ByteArray? {
        val encoded = SignalStore.tapV3.getStringValue(KEY_MY_KPUSH, null) ?: return null
        return try {
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode my k_push", e)
            null
        }
    }
    
    /**
     * 获取我的 k_push 版本号
     */
    fun getMyKPushVersion(): Int {
        return SignalStore.tapV3.getIntegerValue(KEY_MY_KPUSH_VERSION, TapV3Constants.KPUSH_KEY_VERSION_INITIAL)
    }
    
    /**
     * 保存对方的 k_push，用于给对方发消息时加密
     */
    fun savePeerKPush(recipientId: String, key: ByteArray, keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL) {
        val encoded = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val keyString = getPeerKeyStorageKey(recipientId, keyVersion)
        
        SignalStore.tapV3.putStringValue(keyString, encoded)
        SignalStore.tapV3.putIntegerValue(getPeerActiveVersionKey(recipientId), keyVersion)
        
        Log.d(TAG, "Saved peer k_push for recipient: ${recipientId.take(8)}..., version: $keyVersion")
    }
    
    /**
     * 获取对方的 k_push，用于给对方发消息时加密
     */
    fun getPeerKPush(recipientId: String, keyVersion: Int? = null): TapV3Result<ByteArray> {
        val version = keyVersion ?: getPeerActiveKeyVersion(recipientId)
        if (version == null) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "No active key version for recipient: ${recipientId.take(8)}..."
            )
        }
        
        val keyString = getPeerKeyStorageKey(recipientId, version)
        val encoded = SignalStore.tapV3.getStringValue(keyString, null)
        
        if (encoded == null) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "Peer key not found for recipient: ${recipientId.take(8)}..., version: $version"
            )
        }
        
        return try {
            val key = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            TapV3Result.Success(key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode peer key", e)
            TapV3Result.Failure(TapV3Error.KEY_NOT_FOUND, "Failed to decode peer key: ${e.message}", e)
        }
    }
    
    /**
     * 移除对方的 k_push
     */
    fun removePeerKPush(recipientId: String, keyVersion: Int? = null) {
        val version = keyVersion ?: getPeerActiveKeyVersion(recipientId)
        if (version != null) {
            val keyString = getPeerKeyStorageKey(recipientId, version)
            SignalStore.tapV3.removeValue(keyString)
            
            if (keyVersion == null) {
                SignalStore.tapV3.removeValue(getPeerActiveVersionKey(recipientId))
            }
            
            Log.d(TAG, "Removed peer k_push for recipient: ${recipientId.take(8)}..., version: $version")
        }
    }
    
    /**
     * 检查是否有对方的 k_push
     */
    fun hasPeerKPush(recipientId: String): Boolean {
        val version = getPeerActiveKeyVersion(recipientId) ?: return false
        val keyString = getPeerKeyStorageKey(recipientId, version)
        return SignalStore.tapV3.getStringValue(keyString, null) != null
    }
    
    fun getPeerActiveKeyVersion(recipientId: String): Int? {
        val version = SignalStore.tapV3.getIntegerValue(getPeerActiveVersionKey(recipientId), -1)
        return if (version > 0) version else null
    }
    
    // ===== 兼容旧 API，内部映射到新 API =====
    
    @Deprecated("Use savePeerKPush instead", ReplaceWith("savePeerKPush(recipientId, key, keyVersion)"))
    fun saveKey(recipientId: String, key: ByteArray, keyVersion: Int = TapV3Constants.KPUSH_KEY_VERSION_INITIAL) {
        savePeerKPush(recipientId, key, keyVersion)
    }
    
    @Deprecated("Use getPeerKPush instead", ReplaceWith("getPeerKPush(recipientId, keyVersion)"))
    fun getKey(recipientId: String, keyVersion: Int? = null): TapV3Result<ByteArray> {
        return getPeerKPush(recipientId, keyVersion)
    }
    
    @Deprecated("Use removePeerKPush instead", ReplaceWith("removePeerKPush(recipientId, keyVersion)"))
    fun removeKey(recipientId: String, keyVersion: Int? = null) {
        removePeerKPush(recipientId, keyVersion)
    }
    
    @Deprecated("Use hasPeerKPush instead", ReplaceWith("hasPeerKPush(recipientId)"))
    fun hasKey(recipientId: String): Boolean {
        return hasPeerKPush(recipientId)
    }
    
    @Deprecated("Use getPeerActiveKeyVersion instead", ReplaceWith("getPeerActiveKeyVersion(recipientId)"))
    fun getActiveKeyVersion(recipientId: String): Int? {
        return getPeerActiveKeyVersion(recipientId)
    }
    
    private fun getPeerKeyStorageKey(recipientId: String, keyVersion: Int): String {
        return "tapv3_peer_kpush_${recipientId}_v$keyVersion"
    }
    
    private fun getPeerActiveVersionKey(recipientId: String): String {
        return "tapv3_peer_kpush_active_version_$recipientId"
    }
    
    companion object {
        private val TAG = Log.tag(KPushManager::class.java)
        private const val KEY_MY_KPUSH = "tapv3_my_kpush"
        private const val KEY_MY_KPUSH_VERSION = "tapv3_my_kpush_version"
        
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
