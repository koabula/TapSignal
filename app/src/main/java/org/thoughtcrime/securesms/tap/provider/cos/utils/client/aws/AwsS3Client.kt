package org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString.Companion.encodeUtf8
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AwsS3Client(private val config: CosConfig) : CosClient {
    private val TAG = Log.tag(AwsS3Client::class.java)
    private val okHttpClient: OkHttpClient = OkHttpClient()
    private val service = "s3"

    private fun endpointHost(): String = "${config.bucketName}.s3.${config.region}.amazonaws.com"

    override suspend fun createDirectory(directoryPath: String): Boolean {
        // S3 目录本质上是对象, 上传一个 0 字节对象即可
        val normalized = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val tmpFile = File.createTempFile("empty", null)
        tmpFile.writeBytes(ByteArray(0))
        return uploadFile(tmpFile, normalized)
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Boolean {
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

        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "无响应内容"
                Log.w(TAG, "AWS S3上传失败: code=${resp.code}, message=${resp.message}, path=$remotePath, error=$errorBody")
            }
            return resp.isSuccessful
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Boolean {
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

        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "无响应内容"
                Log.w(TAG, "AWS S3下载失败: code=${resp.code}, message=${resp.message}, path=$remotePath, error=$errorBody")
                return false
            }
            localFile.outputStream().use { out ->
                resp.body?.byteStream()?.copyTo(out)
            }
            return true
        }
    }

    override suspend fun listFiles(directoryPath: String): List<CosFileInfo> {
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
        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "List files failed: ${resp.code}")
                return emptyList()
            }
            val xml = resp.body?.string() ?: return emptyList()
            return S3XmlParser.parseListObjects(xml)
        }
    }

    override suspend fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int): CosAccessToken {
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

        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                Log.e(TAG, "STS API failed with code: ${resp.code}, body: $errorBody")
                throw CosNetworkException("Failed to get session token: ${resp.code} - $errorBody")
            }
            val xml = resp.body?.string() ?: throw CosNetworkException("Empty STS response")
            Log.d(TAG, "STS API response: $xml")
            return StsXmlParser.parseSessionToken(xml)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean {
        val canonicalUri = "/$remotePath"
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)

        val payloadHash = AwsSigner.hash("")
        val canonicalHeaders = "host:${endpointHost()}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "DELETE\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, config.region, service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )

        val request = Request.Builder()
            .url("https://${endpointHost()}$canonicalUri")
            .delete()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()

        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Delete failed: ${resp.code}")
                return false
            }
            return true
        }
    }

    override suspend fun fileExists(remotePath: String): Boolean {
        val canonicalUri = "/$remotePath"
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)

        val payloadHash = AwsSigner.hash("")
        val canonicalHeaders = "host:${endpointHost()}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "HEAD\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, config.region, service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )

        val request = Request.Builder()
            .url("https://${endpointHost()}$canonicalUri")
            .head()
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()

        val resp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        resp.use { resp ->
            return resp.isSuccessful
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