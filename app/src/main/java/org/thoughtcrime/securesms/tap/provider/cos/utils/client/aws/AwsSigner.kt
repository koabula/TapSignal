package org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws

import okio.ByteString
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4签名工具
 * 
 * 轻量级实现，专门用于AWS IAM API签名
 * 核心S3操作已使用AWS SDK for Kotlin，不需要手动签名
 */
object AwsSigner {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_ALGORITHM = "SHA-256"

    fun hash(value: String): String = hash(value.toByteArray(Charsets.UTF_8))

    fun hash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance(HASH_ALGORITHM)
        val digest = md.digest(bytes)
        return ByteString.of(*digest).hex()
    }

    private fun hmacSHA256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun getSignatureKey(secretKey: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kDate = hmacSHA256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSHA256(kDate, regionName)
        val kService = hmacSHA256(kRegion, serviceName)
        return hmacSHA256(kService, "aws4_request")
    }

    fun buildAuthorizationHeader(
        accessKeyId: String,
        secretKey: String,
        region: String,
        service: String,
        canonicalRequest: String,
        requestDateTime: String,
        dateStamp: String,
        signedHeaders: String
    ): String {
        val algorithm = "AWS4-HMAC-SHA256"
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val canonicalRequestHash = hash(canonicalRequest)
        val stringToSign = "$algorithm\n$requestDateTime\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(secretKey, dateStamp, region, service)
        val signature = ByteString.of(*hmacSHA256(signingKey, stringToSign)).hex()

        return "$algorithm Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /**
     * 计算SigV4签名
     */
    fun signString(
        secretKey: String,
        dateStamp: String,
        regionName: String,
        serviceName: String,
        stringToSign: String
    ): String {
        val signingKey = getSignatureKey(secretKey, dateStamp, regionName, serviceName)
        val signatureBytes = hmacSHA256(signingKey, stringToSign)
        return ByteString.of(*signatureBytes).hex()
    }
}

