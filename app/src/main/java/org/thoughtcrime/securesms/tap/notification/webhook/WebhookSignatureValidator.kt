package org.thoughtcrime.securesms.tap.notification.webhook

import org.signal.core.util.logging.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook签名验证器
 */
class WebhookSignatureValidator {
    
    companion object {
        private val TAG = Log.tag(WebhookSignatureValidator::class.java)
        
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MAX_TIMESTAMP_DIFF_MS = 5 * 60 * 1000L
    }
    
    fun generateSignature(body: String, secret: String): String {
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
            mac.init(secretKeySpec)
            val hmacBytes = mac.doFinal(body.toByteArray(Charsets.UTF_8))
            bytesToHex(hmacBytes)
        } catch (e: Exception) {
            Log.e(TAG, "生成签名失败", e)
            ""
        }
    }
    
    fun validateSignature(body: String, signature: String, secret: String): Boolean {
        return try {
            val expectedSignature = generateSignature(body, secret)
            
            if (expectedSignature.isEmpty()) {
                Log.w(TAG, "生成期望签名失败")
                return false
            }
            
            val isValid = secureCompare(expectedSignature, signature)
            
            if (!isValid) {
                Log.w(TAG, "签名验证失败: expected=${expectedSignature.take(16)}..., received=${signature.take(16)}..., bodyLength=${body.length}")
            }
            
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "验证签名异常", e)
            false
        }
    }
    
    fun validateTimestamp(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val diff = Math.abs(currentTime - timestamp)
        
        return if (diff > MAX_TIMESTAMP_DIFF_MS) {
            Log.w(TAG, "时间戳验证失败: diff=${diff}ms, maxAllowed=${MAX_TIMESTAMP_DIFF_MS}ms, timestamp=$timestamp, current=$currentTime")
            false
        } else {
            true
        }
    }
    
    fun validateRequest(body: String, signature: String, secret: String, timestamp: Long): Boolean {
        if (!validateTimestamp(timestamp)) {
            return false
        }
        
        return validateSignature(body, signature, secret)
    }
    
    private fun secureCompare(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        
        return result == 0
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
    
    private val hexArray = "0123456789abcdef".toCharArray()
}

