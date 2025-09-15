package org.thoughtcrime.securesms.cos

import okhttp3.*
import okio.ByteString.Companion.encodeUtf8
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.aws.AwsSigner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.thoughtcrime.securesms.cos.aws.StsXmlParser

class AwsS3Client(private val config: CosConfig) : CosClient {
    private val TAG = Log.tag(AwsS3Client::class.java)
    private val okHttpClient: OkHttpClient = OkHttpClient()
    private val service = "s3"

    private fun endpointHost(): String = "${config.bucketName}.s3.${config.region}.amazonaws.com"

    override fun createDirectory(directoryPath: String): Boolean {
        // S3 目录本质上是对象, 上传一个 0 字节对象即可
        val normalized = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val tmpFile = File.createTempFile("empty", null)
        tmpFile.writeBytes(ByteArray(0))
        return uploadFile(tmpFile, normalized)
    }

    override fun uploadFile(localFile: File, remotePath: String): Boolean {
        val requestBody = localFile.readBytes()
        val canonicalUri = "/$remotePath"
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)

        val payloadHash = AwsSigner.hash(requestBody)

        val canonicalHeaders = "host:${endpointHost()}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "PUT\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, config.region, service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )

        val request = Request.Builder()
            .url("https://${endpointHost()}$canonicalUri")
            .put(RequestBody.create(null, requestBody))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Upload failed: ${'$'}{resp.code}")
            }
            return resp.isSuccessful
        }
    }

    override fun downloadFile(remotePath: String, localFile: File): Boolean {
        val canonicalUri = "/$remotePath"
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)

        val payloadHash = AwsSigner.hash("")
        val canonicalHeaders = "host:${endpointHost()}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, config.region, service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )

        val request = Request.Builder()
            .url("https://${endpointHost()}$canonicalUri")
            .get()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Download failed: ${'$'}{resp.code}")
                return false
            }
            localFile.outputStream().use { out ->
                resp.body?.byteStream()?.copyTo(out)
            }
            return true
        }
    }

    override fun listFiles(directoryPath: String): List<CosFileInfo> {
        val prefix = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val query = "list-type=2&prefix=${prefix.encodeUtf8().utf8()}&delimiter=/"
        val canonicalUri = "/"
        val canonicalQuery = query
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        val payloadHash = AwsSigner.hash("")
        val canonicalHeaders = "host:${endpointHost()}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, config.region, service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )
        val request = Request.Builder()
            .url("https://${endpointHost()}?$query")
            .get()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "List files failed: ${'$'}{resp.code}")
                return emptyList()
            }
            val xml = resp.body?.string() ?: return emptyList()
            return S3XmlParser.parseListObjects(xml)
        }
    }

    override fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int): CosAccessToken {
        // 使用 AWS STS GetSessionToken API
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        val action = "Action=GetSessionToken&Version=2011-06-15&DurationSeconds=${durationMinutes * 60}"
        val host = "sts.amazonaws.com"
        val canonicalUri = "/"
        val canonicalQueryString = action
        val payloadHash = AwsSigner.hash("")
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "GET\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, "us-east-1", "sts", canonicalRequest, amzDate, dateStamp, signedHeaders
        )

        val request = Request.Builder()
            .url("https://$host/?$action")
            .get()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()

        Log.d(TAG, "Requesting STS session token with duration: ${durationMinutes} minutes")

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                Log.e(TAG, "STS API failed with code: ${resp.code}, body: $errorBody")
                throw RuntimeException("Failed to get session token: ${resp.code} - $errorBody")
            }
            val xml = resp.body?.string() ?: throw RuntimeException("Empty STS response")
            Log.d(TAG, "STS API response: $xml")
            return StsXmlParser.parseSessionToken(xml)
        }
    }

    private fun iso8601(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    private fun dateStamp(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
} 