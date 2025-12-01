package org.thoughtcrime.securesms.tapv3.utils

import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result

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
    
    fun validateIpfsRefs(payload: TapV3Payload.IpfsRefs): TapV3Result<Unit> {
        if (payload.messageCid != null && !isValidCid(payload.messageCid)) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Invalid message CID: ${payload.messageCid}"
            )
        }
        
        if (payload.attachments.isEmpty() && payload.messageCid == null) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "IpfsRefs must have either messageCid or attachments"
            )
        }
        
        for (attachment in payload.attachments) {
            if (!isValidCid(attachment.cid)) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Invalid attachment CID: ${attachment.cid}"
                )
            }
            
            if (attachment.size <= 0 || attachment.size > TapV3Constants.MAX_ATTACHMENT_SIZE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Invalid attachment size: ${attachment.size}"
                )
            }
        }
        
        return TapV3Result.Success(Unit)
    }
}
