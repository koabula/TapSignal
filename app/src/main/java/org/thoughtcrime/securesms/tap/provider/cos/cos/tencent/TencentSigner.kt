package org.thoughtcrime.securesms.tap.provider.cos.cos.tencent

import org.thoughtcrime.securesms.tap.provider.cos.cos.aws.AwsSigner
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okio.ByteString

/**
 * 腾讯云TC3-HMAC-SHA256签名算法实现
 */
object TencentSigner {
    
    /**
     * 构建腾讯云TC3-HMAC-SHA256授权头
     */
    fun buildTC3AuthorizationHeader(
        secretId: String,
        secretKey: String,
        service: String,
        region: String,
        action: String,
        timestamp: Long,
        payload: String,
        host: String
    ): String {
        val date = Date(timestamp * 1000)
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(date)
        
        // 1. 拼接规范请求串
        val httpRequestMethod = "POST"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val canonicalHeaders = "content-type:application/json; charset=utf-8\n" +
                "host:$host\n"
        val signedHeaders = "content-type;host"
        val hashedRequestPayload = AwsSigner.hash(payload)
        
        val canonicalRequest = "$httpRequestMethod\n" +
                "$canonicalUri\n" +
                "$canonicalQueryString\n" +
                "$canonicalHeaders\n" +
                "$signedHeaders\n" +
                "$hashedRequestPayload"
        
        // 2. 拼接待签名字符串
        val algorithm = "TC3-HMAC-SHA256"
        val requestTimestamp = timestamp.toString()
        val credentialScope = "$dateString/$service/tc3_request"
        val hashedCanonicalRequest = AwsSigner.hash(canonicalRequest)
        
        val stringToSign = "$algorithm\n" +
                "$requestTimestamp\n" +
                "$credentialScope\n" +
                "$hashedCanonicalRequest"
        
        // 3. 计算签名
        val secretDate = hmacSha256("TC3$secretKey".toByteArray(), dateString)
        val secretService = hmacSha256(secretDate, service)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256(secretSigning, stringToSign)
        val signatureHex = bytesToHex(signature)
        
        // 4. 拼接 Authorization
        return "$algorithm Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signatureHex"
    }

    /**
     * HMAC-SHA256计算
     */
    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * 字节数组转十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        return ByteString.of(*bytes).hex()
    }
}
