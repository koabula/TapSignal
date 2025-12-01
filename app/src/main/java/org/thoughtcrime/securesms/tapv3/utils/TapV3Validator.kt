package org.thoughtcrime.securesms.tapv3.utils

import org.thoughtcrime.securesms.tapv3.TapV3Constants

object TapV3Validator {
    
    fun isValidCid(cid: String): Boolean {
        if (cid.length !in TapV3Constants.CID_LENGTH_MIN..TapV3Constants.CID_LENGTH_MAX) {
            return false
        }
        
        return cid.startsWith("Qm") || cid.startsWith("bafy")
    }
    
    fun isValidEndpoint(endpoint: String): Boolean {
        return try {
            val url = java.net.URL(endpoint)
            url.protocol in listOf("http", "https")
        } catch (e: Exception) {
            false
        }
    }
    
    fun isValidKeySize(key: ByteArray): Boolean {
        return key.size == TapV3Constants.KPUSH_KEY_SIZE_BYTES
    }
    
    fun isValidMessageSize(size: Int): Boolean {
        return size > 0 && size <= TapV3Constants.MAX_ATTACHMENT_SIZE
    }
}
