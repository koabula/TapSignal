package org.thoughtcrime.securesms.tap.provider.cos.cos.aws

import java.nio.charset.Charset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import okio.ByteString

/**
 * AWS Signature Version 4 实现 (适用于 S3 / STS 等服务)。
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

    /**
     * 生成签名密钥。
     */
    private fun getSignatureKey(secretKey: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kDate = hmacSHA256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSHA256(kDate, regionName)
        val kService = hmacSHA256(kRegion, serviceName)
        return hmacSHA256(kService, "aws4_request")
    }

    /**
     * 生成 Authorization header。
     * @param canonicalRequest 已计算的 canonical request (字符串)
     */
    fun buildAuthorizationHeader(
        accessKeyId: String,
        secretKey: String,
        region: String,
        service: String,
        canonicalRequest: String,
        requestDateTime: String, // yyyyMMdd'T'HHmmss'Z'
        dateStamp: String, // yyyyMMdd
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
} 