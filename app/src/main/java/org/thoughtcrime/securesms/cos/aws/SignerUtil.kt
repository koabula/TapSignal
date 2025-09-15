package org.thoughtcrime.securesms.cos.aws

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignerUtil {
    fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
} 