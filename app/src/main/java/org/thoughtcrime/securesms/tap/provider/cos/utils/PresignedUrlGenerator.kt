package org.thoughtcrime.securesms.tap.provider.cos.utils

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner

interface PresignedUrlGenerator {
    fun generate(objectKey: String, expiresInSeconds: Int = DEFAULT_EXPIRATION_SECONDS): PresignedUrlResult?

    companion object {
        const val DEFAULT_EXPIRATION_SECONDS = 14 * 24 * 60 * 60 // 14天
    }
}

data class PresignedUrlResult(
    val url: String,
    val expiresAtEpochMillis: Long,
    val expiresInSeconds: Int
)

class S3CompatiblePresignedUrlGenerator(
    private val cosConfig: CosConfig
) : PresignedUrlGenerator {

    private val tag = Log.tag(S3CompatiblePresignedUrlGenerator::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    override fun generate(objectKey: String, expiresInSeconds: Int): PresignedUrlResult? {
        return try {
            val sanitizedKey = objectKey.removePrefix("/")
            if (sanitizedKey.isBlank()) {
                Log.w(tag, "generatePresignedUrl: object key is blank")
                return null
            }

            val providerMax = when (cosConfig.provider) {
                CosConfig.Provider.AWS -> AWS_MAX_EXPIRATION_SECONDS
                CosConfig.Provider.TENCENT -> TENCENT_MAX_EXPIRATION_SECONDS
            }
            val expires = min(expiresInSeconds, providerMax).coerceAtLeast(1)
            if (expiresInSeconds > providerMax) {
                Log.w(
                    tag,
                    "Requested presign expiry ${expiresInSeconds}s exceeds ${cosConfig.provider} limit " +
                        "$providerMax s. Clamping to $expires s."
                )
            }
            val now = Instant.now()
            val amzDate = dateTimeFormatter.format(now)
            val dateStamp = dateFormatter.format(now)

            val host = buildHost()
            val canonicalUri = "/" + sanitizedKey.split("/").joinToString("/") { encodePathSegment(it) }
            val queryParams = sortedMapOf(
                "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
                "X-Amz-Credential" to encodeQueryComponent("${cosConfig.secretId.trim()}/$dateStamp/${cosConfig.region}/s3/aws4_request"),
                "X-Amz-Date" to amzDate,
                "X-Amz-Expires" to expires.toString(),
                "X-Amz-SignedHeaders" to "host"
            )

            cosConfig.sessionToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                queryParams["X-Amz-Security-Token"] = encodeQueryComponent(token)
            }

            val canonicalQueryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            val canonicalHeaders = "host:$host\n"
            val signedHeaders = "host"
            val payloadHash = "UNSIGNED-PAYLOAD"

            val canonicalRequest = buildString {
                append("GET\n")
                append(canonicalUri).append('\n')
                append(canonicalQueryString).append('\n')
                append(canonicalHeaders).append('\n')
                append(signedHeaders).append('\n')
                append(payloadHash)
            }

            val credentialScope = "$dateStamp/${cosConfig.region}/s3/aws4_request"
            val canonicalRequestHash = AwsSigner.hash(canonicalRequest)
            val stringToSign = buildString {
                append("AWS4-HMAC-SHA256\n")
                append(amzDate).append('\n')
                append(credentialScope).append('\n')
                append(canonicalRequestHash)
            }

            val signature = AwsSigner.signString(
                secretKey = cosConfig.secretKey.trim(),
                dateStamp = dateStamp,
                regionName = cosConfig.region,
                serviceName = "s3",
                stringToSign = stringToSign
            )

            val finalUrl = buildString {
                append("https://").append(host).append(canonicalUri)
                append('?').append(canonicalQueryString)
                append("&X-Amz-Signature=").append(signature)
            }

            PresignedUrlResult(
                url = finalUrl,
                expiresAtEpochMillis = now.toEpochMilli() + expires * 1000L,
                expiresInSeconds = expires
            )
        } catch (e: Exception) {
            Log.e(tag, "生成预签名URL失败: ${e.message}", e)
            null
        }
    }

    private fun buildHost(): String {
        val bucket = cosConfig.bucketName
        return when (cosConfig.provider) {
            CosConfig.Provider.AWS -> "$bucket.s3.${cosConfig.region}.amazonaws.com"
            CosConfig.Provider.TENCENT -> "$bucket.cos.${cosConfig.region}.myqcloud.com"
        }
    }

    private fun encodePathSegment(segment: String): String {
        return URLEncoder.encode(segment, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun encodeQueryComponent(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    companion object {
        private const val AWS_MAX_EXPIRATION_SECONDS = 7 * 24 * 60 * 60 // AWS: 7天上限
        private const val TENCENT_MAX_EXPIRATION_SECONDS = 14 * 24 * 60 * 60 // COS默认14天
    }
}
