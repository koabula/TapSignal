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

    /**
     * 下载文件直接到内存（优化版本）
     */
    override suspend fun downloadFileToMemory(remotePath: String): ByteArray? {
        return try {
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
                    Log.w(TAG, "AWS S3直接下载失败: code=${resp.code}, message=${resp.message}, path=$remotePath, error=$errorBody")
                    return null
                }
                
                val data = resp.body?.bytes()
                if (data != null && data.isNotEmpty()) {
                    Log.d(TAG, "AWS S3直接下载成功: $remotePath, 大小=${data.size} bytes")
                    data
                } else {
                    Log.w(TAG, "AWS S3直接下载失败: 数据为空 - $remotePath")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3直接下载异常: $remotePath", e)
            null
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
    
    override suspend fun listFilesWithMarker(
        directoryPath: String,
        marker: String?,
        maxKeys: Int
    ): org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult {
        return try {
            val prefix = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
            
            // AWS S3 使用 continuation-token 而不是 marker
            // 构建查询参数
            val queryParams = mutableListOf(
                "list-type=2",
                "prefix=${prefix.encodeUtf8().utf8()}",
                "delimiter=/",
                "max-keys=$maxKeys"
            )
            
            // 如果有marker（continuation-token），添加到查询参数
            if (!marker.isNullOrEmpty()) {
                queryParams.add("continuation-token=${marker.encodeUtf8().utf8()}")
                Log.d(TAG, "使用continuation-token进行增量查询: marker=$marker")
            }
            
            val query = queryParams.joinToString("&")
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
                    Log.w(TAG, "增量列举文件失败: ${resp.code}")
                    return org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult(
                        files = emptyList(),
                        nextMarker = null,
                        isTruncated = false
                    )
                }
                
                val xml = resp.body?.string() ?: return org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult(
                    files = emptyList(),
                    nextMarker = null,
                    isTruncated = false
                )
                
                // 解析XML响应
                val fileList = S3XmlParser.parseListObjects(xml)
                
                // 解析分页信息
                val isTruncated = xml.contains("<IsTruncated>true</IsTruncated>")
                val nextContinuationToken = if (isTruncated) {
                    // 从XML中提取NextContinuationToken
                    val tokenRegex = Regex("<NextContinuationToken>([^<]+)</NextContinuationToken>")
                    tokenRegex.find(xml)?.groupValues?.get(1)
                } else {
                    null
                }
                
                Log.d(TAG, "增量列举成功: 找到 ${fileList.size} 个文件, isTruncated=$isTruncated")
                if (marker != null && fileList.isNotEmpty()) {
                    Log.i(TAG, "增量查询返回 ${fileList.size} 个新文件（Marker优化生效）")
                }
                
                org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult(
                    files = fileList,
                    nextMarker = nextContinuationToken,
                    isTruncated = isTruncated
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "增量列举文件异常: directoryPath=$directoryPath", e)
            org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult(
                files = emptyList(),
                nextMarker = null,
                isTruncated = false
            )
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