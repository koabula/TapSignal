package org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosListResult
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AWS S3客户端实现（使用官方SDK）
 * 
 * 参考TencentCosClient的实现风格，使用AWS SDK for Kotlin提供
 * 完整的S3对象存储功能，自动处理签名、编码和错误处理。
 */
class AwsS3Client(private val config: CosConfig) : CosClient {
    private val TAG = Log.tag(AwsS3Client::class.java)
    
    private val s3Client: S3Client = S3Client {
        region = config.region
        
        // 使用AWS Smithy Kotlin的正确凭证API - 实现CredentialsProvider接口
        credentialsProvider = object : CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials {
                // 清理密钥中的前后空格和换行符，避免签名错误
                val cleanedAccessKeyId = config.secretId.trim()
                val cleanedSecretKey = config.secretKey.trim()
                val cleanedSessionToken = config.sessionToken?.trim()
                
                Log.d(TAG, "AWS凭证提供者 - 解析凭证")
                Log.d(TAG, "  - AccessKeyId: ${cleanedAccessKeyId.take(8)}***")
                Log.d(TAG, "  - SecretKey长度: ${cleanedSecretKey.length}")
                Log.d(TAG, "  - SessionToken: ${if (cleanedSessionToken.isNullOrEmpty()) "无" else "有 (长度: ${cleanedSessionToken.length})"}")
                
                // 根据是否有sessionToken，使用不同的构造方式
                // 重要：永久凭证不能传递null的sessionToken，会导致签名错误
                return if (!cleanedSessionToken.isNullOrEmpty()) {
                    // 使用临时凭证
                    Credentials(
                        accessKeyId = cleanedAccessKeyId,
                        secretAccessKey = cleanedSecretKey,
                        sessionToken = cleanedSessionToken
                    )
                } else {
                    // 使用永久凭证 - 不传递sessionToken参数
                    Credentials(
                        accessKeyId = cleanedAccessKeyId,
                        secretAccessKey = cleanedSecretKey
                    )
                }
            }
        }
    }

    init {
        Log.d(TAG, "===== AWS S3客户端初始化 =====")
        Log.d(TAG, "区域: ${config.region}")
        Log.d(TAG, "存储桶: ${config.bucketName}")
        Log.d(TAG, "AccessKeyId前缀: ${config.secretId.take(8)}***")
        Log.d(TAG, "使用凭证类型: ${if (config.sessionToken.isNullOrEmpty()) "永久凭证" else "临时凭证"}")
        Log.d(TAG, "===== 初始化完成 =====")
    }

    override suspend fun createDirectory(directoryPath: String): Boolean {
        return try {
            // AWS S3对象键不能以斜杠开头，移除前导斜杠
            val normalizedPath = directoryPath.removePrefix("/")
            val normalized = if (normalizedPath.endsWith("/")) normalizedPath else "$normalizedPath/"
            
            Log.d(TAG, "===== 创建目录操作 =====")
            Log.d(TAG, "原始路径: $directoryPath")
            Log.d(TAG, "标准化路径: $normalized")
            Log.d(TAG, "目标存储桶: ${config.bucketName}")
            Log.d(TAG, "目标区域: ${config.region}")
            
            val request = PutObjectRequest {
                bucket = config.bucketName
                key = normalized
                body = ByteStream.fromBytes(ByteArray(0))
            }
            
            Log.d(TAG, "发送PutObject请求...")
            val response = s3Client.putObject(request)
            
            Log.d(TAG, "✓ 创建目录成功")
            Log.d(TAG, "  - ETag: ${response.eTag ?: "N/A"}")
            Log.d(TAG, "  - VersionId: ${response.versionId ?: "N/A"}")
            Log.d(TAG, "===== 操作完成 =====")
            true
        } catch (e: Exception) {
            Log.e(TAG, "✗ 创建目录失败")
            Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "  - 异常消息: ${e.message}")
            if (e.cause != null) {
                Log.e(TAG, "  - 原因: ${e.cause?.message}")
            }
            Log.e(TAG, "===== 操作失败 =====", e)
            false
        }
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Boolean {
        // AWS S3对象键不能以斜杠开头，移除前导斜杠
        val normalizedRemotePath = remotePath.removePrefix("/")
        
        return try {
            Log.d(TAG, "===== 上传文件操作 =====")
            Log.d(TAG, "本地文件: ${localFile.absolutePath}")
            Log.d(TAG, "文件大小: ${localFile.length()} bytes")
            Log.d(TAG, "原始路径: $remotePath")
            Log.d(TAG, "标准化路径: $normalizedRemotePath")
            Log.d(TAG, "目标存储桶: ${config.bucketName}")
            
            val request = PutObjectRequest {
                bucket = config.bucketName
                key = normalizedRemotePath
                body = ByteStream.fromBytes(localFile.readBytes())
            }
            
            Log.d(TAG, "发送PutObject请求...")
            val response = s3Client.putObject(request)
            
            Log.d(TAG, "✓ 上传文件成功")
            Log.d(TAG, "  - ETag: ${response.eTag ?: "N/A"}")
            Log.d(TAG, "===== 操作完成 =====")
            true
        } catch (e: Exception) {
            Log.e(TAG, "✗ 上传文件失败: $normalizedRemotePath")
            Log.e(TAG, "  - 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    override suspend fun downloadFileToMemory(remotePath: String): ByteArray? {
        return try {
            Log.v(TAG, "下载到内存: $remotePath")
            
            // 移除前导斜杠，与Tencent实现保持一致
            val normalizedRemotePath = remotePath.removePrefix("/")
            
            val response = s3Client.getObject(GetObjectRequest {
                bucket = config.bucketName
                key = normalizedRemotePath
            }) { resp ->
                resp.body?.toByteArray()
            }
            
            if (response != null && response.isNotEmpty()) {
                Log.v(TAG, "下载成功: $remotePath, ${response.size} bytes")
                response
            } else {
                Log.e(TAG, "下载失败: 文件为空 - $remotePath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: $remotePath, ${e.message}", e)
            null
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Boolean {
        return try {
            Log.d(TAG, "开始下载文件:")
            Log.d(TAG, "  - 远程路径: $remotePath")
            Log.d(TAG, "  - 本地文件: ${localFile.absolutePath}")
            
            // 确保父目录存在
            localFile.parentFile?.let { parentDir ->
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                    Log.d(TAG, "创建父目录: ${parentDir.absolutePath}")
                }
            }
            
            // 如果目标文件已存在，先删除
            if (localFile.exists()) {
                localFile.delete()
                Log.d(TAG, "删除已存在的文件: ${localFile.absolutePath}")
            }
            
            // AWS S3不接受带前导斜杠的路径，与Tencent实现保持一致
            val normalizedRemotePath = remotePath.removePrefix("/")
            
            Log.d(TAG, "调用AWS SDK下载:")
            Log.d(TAG, "  - 存储桶: ${config.bucketName}")
            Log.d(TAG, "  - 原始路径: $remotePath")
            Log.d(TAG, "  - 标准化路径: $normalizedRemotePath")
            
            // 下载文件
            val data = s3Client.getObject(GetObjectRequest {
                bucket = config.bucketName
                key = normalizedRemotePath
            }) { resp ->
                resp.body?.toByteArray()
            }
            
            if (data != null && data.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    localFile.writeBytes(data)
                }
                Log.d(TAG, "下载文件成功: $remotePath -> ${localFile.absolutePath}, 大小: ${data.size} bytes")
                true
            } else {
                Log.e(TAG, "下载失败: 数据为空 - $remotePath")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载文件失败: $remotePath, ${e.message}", e)
            false
        }
    }

    override suspend fun listFiles(directoryPath: String): List<CosFileInfo> {
        return try {
            val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
            val prefix = if (normalizedPath.endsWith("/")) normalizedPath else "$normalizedPath/"
            
            Log.d(TAG, "列举文件 - 路径: $directoryPath, 前缀: $prefix")
            
            val request = ListObjectsV2Request {
                bucket = config.bucketName
                this.prefix = prefix
                delimiter = "/"
                maxKeys = 1000
            }
            val response = s3Client.listObjectsV2(request)
            
            val fileList = response.contents?.map { obj ->
                CosFileInfo(
                    name = obj.key ?: "",
                    size = obj.size ?: 0L,
                    lastModified = obj.lastModified?.epochSeconds?.times(1000) ?: System.currentTimeMillis()
                )
            } ?: emptyList()
            
            Log.d(TAG, "列举文件成功: ${fileList.size}个")
            fileList
        } catch (e: Exception) {
            when {
                e.message?.contains("not authorized", ignoreCase = true) == true ||
                e.message?.contains("AccessDenied", ignoreCase = true) == true ||
                e.message?.contains("ListBucket", ignoreCase = true) == true -> {
                    Log.e(TAG, "列举文件失败：权限不足")
                    Log.e(TAG, "  提示：当前凭证缺少s3:ListBucket权限")
                    Log.e(TAG, "  解决：请在AWS IAM中为子用户添加ListBucket权限")
                }
                else -> {
                    Log.e(TAG, "列举文件失败: ${e.message}", e)
                }
            }
            emptyList()
        }
    }

    override suspend fun listFilesWithMarker(
        directoryPath: String,
        marker: String?,
        maxKeys: Int
    ): CosListResult {
        return try {
            val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
            val prefix = if (normalizedPath.endsWith("/")) normalizedPath else "$normalizedPath/"
            
            Log.d(TAG, "=== 增量列举 ===")
            Log.d(TAG, "路径: $prefix, Marker: ${marker ?: "首次"}, MaxKeys: $maxKeys")
            
            val request = ListObjectsV2Request {
                bucket = config.bucketName
                this.prefix = prefix
                this.maxKeys = maxKeys
                if (!marker.isNullOrEmpty()) {
                    val normalizedMarker = if (marker.startsWith("/")) marker.substring(1) else marker
                    startAfter = normalizedMarker
                    Log.d(TAG, "使用startAfter进行增量查询: $normalizedMarker")
                }
            }
            val response = s3Client.listObjectsV2(request)
            
            val fileList = response.contents?.map { obj ->
                CosFileInfo(
                    name = obj.key ?: "",
                    size = obj.size ?: 0L,
                    lastModified = obj.lastModified?.epochSeconds?.times(1000) ?: System.currentTimeMillis()
                )
            } ?: emptyList()
            
            val isTruncated = response.isTruncated ?: false
            
            Log.d(TAG, "增量列举成功: ${fileList.size}个文件, 截断: $isTruncated")
            
            CosListResult(
                files = fileList,
                nextMarker = null,
                isTruncated = isTruncated
            )
        } catch (e: Exception) {
            when {
                e.message?.contains("not authorized", ignoreCase = true) == true ||
                e.message?.contains("AccessDenied", ignoreCase = true) == true ||
                e.message?.contains("ListBucket", ignoreCase = true) == true -> {
                    Log.e(TAG, "增量列举失败：权限不足")
                    Log.e(TAG, "  提示：当前凭证缺少s3:ListBucket权限")
                }
                else -> {
                    Log.e(TAG, "增量列举失败: ${e.message}", e)
                }
            }
            CosListResult(
                files = emptyList(),
                nextMarker = null,
                isTruncated = false
            )
        }
    }

    override suspend fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int): CosAccessToken {
        return try {
            // 使用AWS STS API获取联合身份临时访问凭证
            val stsHost = "sts.amazonaws.com"
            val stsService = "sts"
            val action = "GetFederationToken"
            val federatedUserName = "signal-tap-user-${System.currentTimeMillis()}"
            val durationSeconds = durationMinutes * 60
            
            // 构建权限策略
            val policy = buildAwsAccessPolicy(directoryPath)
            
            Log.d(TAG, "请求AWS STS临时访问凭证，有效期: $durationMinutes 分钟")
            Log.d(TAG, "目录路径: $directoryPath")
            Log.d(TAG, "联合用户名: $federatedUserName")
            
            // 构建查询参数（按字母顺序排列，AWS签名要求）
            val queryParams = sortedMapOf(
                "Action" to action,
                "DurationSeconds" to durationSeconds.toString(),
                "Name" to federatedUserName,
                "Policy" to policy,
                "Version" to "2011-06-15"
            )
            
            val canonicalQuery = queryParams.entries.joinToString("&") { 
                "${uriEncode(it.key)}=${uriEncode(it.value)}" 
            }
            
            // 生成AWS Signature V4签名
            val date = Date()
            val amzDate = iso8601(date)
            val dateStamp = dateStamp(date)
            val payloadHash = AwsSigner.hash("")
            
            val canonicalHeaders = "host:$stsHost\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n/\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                config.secretId, config.secretKey, config.region, stsService,
                canonicalRequest, amzDate, dateStamp, signedHeaders
            )
            
            val request = Request.Builder()
                .url("https://$stsHost/?$canonicalQuery")
                .get()
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()
            
            val okHttpClient = OkHttpClient()
            val resp = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }
            
            resp.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string() ?: "No error body"
                    Log.e(TAG, "AWS STS API请求失败，状态码: ${resp.code}, 响应: $errorBody")
                    throw CosNetworkException("获取临时访问凭证失败: ${resp.code} - $errorBody")
                }
                
                val responseBody = resp.body?.string() ?: throw CosNetworkException("STS API响应为空")
                Log.d(TAG, "STS API响应长度: ${responseBody.length}")
                
                // 敏感信息不要完整记录，只记录截断的片段
                if (responseBody.length > 100) {
                    Log.d(TAG, "STS API响应片段: ${responseBody.substring(0, 100)}...")
                }
                
                // 解析AWS STS XML响应
                parseAwsStsResponse(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "生成AWS临时访问凭证失败: ${e.message}", e)
            throw CosAuthException("生成临时访问凭证失败: ${e.message}", e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean {
        // AWS S3对象键不能以斜杠开头，移除前导斜杠
        val normalizedRemotePath = remotePath.removePrefix("/")
        
        return try {
            Log.d(TAG, "删除文件 - 原始路径: $remotePath, 标准化路径: $normalizedRemotePath")
            
            val request = DeleteObjectRequest {
                bucket = config.bucketName
                key = normalizedRemotePath
            }
            s3Client.deleteObject(request)
            
            Log.d(TAG, "删除文件成功: $normalizedRemotePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除文件失败: $normalizedRemotePath, ${e.message}", e)
            false
        }
    }

    override suspend fun fileExists(remotePath: String): Boolean {
        // AWS S3对象键不能以斜杠开头，移除前导斜杠
        val normalizedRemotePath = remotePath.removePrefix("/")
        
        return try {
            Log.d(TAG, "文件存在检查 - 原始路径: $remotePath, 标准化路径: $normalizedRemotePath")
            
            val request = HeadObjectRequest {
                bucket = config.bucketName
                key = normalizedRemotePath
            }
            s3Client.headObject(request)
            
            Log.d(TAG, "文件存在检查成功: $normalizedRemotePath")
            true
        } catch (e: NoSuchKey) {
            Log.d(TAG, "文件不存在: $normalizedRemotePath")
            false
        } catch (e: Exception) {
            Log.e(TAG, "文件存在检查失败: $normalizedRemotePath, ${e.message}", e)
            false
        }
    }
    
    // ========== 私有辅助方法 ==========
    
    /**
     * 构建AWS IAM权限策略
     * 参考腾讯云的buildAccessPolicy实现
     */
    private fun buildAwsAccessPolicy(directoryPath: String): String {
        // 确保路径格式正确
        val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
        
        // AWS S3资源ARN格式：arn:aws:s3:::bucket-name/path/*
        val bucketArn = "arn:aws:s3:::${config.bucketName}"
        val objectArn = "arn:aws:s3:::${config.bucketName}/${normalizedPath}*"
        
        Log.d(TAG, "IAM策略 - 存储桶: ${config.bucketName}")
        Log.d(TAG, "IAM策略 - 标准化路径: $normalizedPath")
        Log.d(TAG, "IAM策略 - 存储桶ARN: $bucketArn")
        Log.d(TAG, "IAM策略 - 对象ARN: $objectArn")
        
        // 构建完整权限策略（与Tencent实现对齐，包含读写删除权限）
        // 注意：ListBucket作用于bucket级别，其他操作作用于object级别
        val policy = """
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": [
                        "s3:ListBucket"
                    ],
                    "Resource": "$bucketArn"
                },
                {
                    "Effect": "Allow",
                    "Action": [
                        "s3:GetObject",
                        "s3:PutObject",
                        "s3:DeleteObject",
                        "s3:HeadObject"
                    ],
                    "Resource": "$objectArn"
                }
            ]
        }
        """.trimIndent().replace("\n", "").replace("\\s+".toRegex(), " ")
        
        Log.d(TAG, "IAM策略 - 完整策略长度: ${policy.length}")
        return policy
    }
    
    /**
     * 解析AWS STS GetFederationToken响应
     * 参考腾讯云的TencentStsXmlParser实现，使用DOM解析器
     */
    private fun parseAwsStsResponse(xml: String): CosAccessToken {
        try {
            Log.d(TAG, "开始解析AWS STS XML响应")
            
            // 使用DOM解析器，与Tencent实现保持一致
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            
            // AWS STS返回XML格式，示例：
            // <GetFederationTokenResponse>
            //   <GetFederationTokenResult>
            //     <Credentials>
            //       <AccessKeyId>ASIA...</AccessKeyId>
            //       <SecretAccessKey>...</SecretAccessKey>
            //       <SessionToken>...</SessionToken>
            //       <Expiration>2024-01-01T12:00:00Z</Expiration>
            //     </Credentials>
            //   </GetFederationTokenResult>
            // </GetFederationTokenResponse>
            
            // 检查是否有错误
            val errorNode = getElementByTagName(doc, "Error")
            if (errorNode != null) {
                val errorCode = getTextContent(errorNode, "Code")
                val errorMessage = getTextContent(errorNode, "Message")
                Log.e(TAG, "AWS STS返回错误: $errorCode - $errorMessage")
                throw CosAuthException("AWS STS返回错误: $errorCode - $errorMessage")
            }
            
            // 提取临时访问凭证
            val credentials = getElementByTagName(doc, "Credentials")
                ?: throw CosAuthException("无法找到Credentials节点")
            
            val accessKeyId = getTextContent(credentials, "AccessKeyId")
                ?: throw CosAuthException("无法解析AccessKeyId")
            val secretAccessKey = getTextContent(credentials, "SecretAccessKey")
                ?: throw CosAuthException("无法解析SecretAccessKey")
            val sessionToken = getTextContent(credentials, "SessionToken")
                ?: throw CosAuthException("无法解析SessionToken")
            val expirationStr = getTextContent(credentials, "Expiration")
                ?: throw CosAuthException("无法解析Expiration")
            
            // 解析ISO 8601时间格式
            val expiration = parseIso8601Timestamp(expirationStr)
            
            Log.d(TAG, "STS凭证解析成功:")
            Log.d(TAG, "  - AccessKeyId: ${accessKeyId.take(8)}...")
            Log.d(TAG, "  - SessionToken长度: ${sessionToken.length}")
            Log.d(TAG, "  - 过期时间: ${Date(expiration)}")
            
            return CosAccessToken(
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                expiration = expiration
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析STS响应失败", e)
            throw CosAuthException("解析STS响应失败: ${e.message}", e)
        }
    }
    
    /**
     * 辅助方法：根据标签名称获取第一个元素
     */
    private fun getElementByTagName(doc: org.w3c.dom.Document, tagName: String): org.w3c.dom.Element? {
        val elements = doc.getElementsByTagName(tagName)
        if (elements.length > 0) {
            return elements.item(0) as org.w3c.dom.Element
        }
        return null
    }
    
    /**
     * 辅助方法：从元素中获取文本内容
     */
    private fun getTextContent(parent: org.w3c.dom.Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            val element = nodes.item(0) as org.w3c.dom.Element
            return element.textContent.trim()
        }
        return null
    }
    
    /**
     * RFC 3986 URI编码
     * AWS签名v4要求的编码方式
     */
    private fun uriEncode(value: String): String {
        return value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val char = byte.toInt().toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' -> char.toString()
                char in "-_.~" -> char.toString()
                else -> "%${byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')}"
            }
        }
    }
    
    /**
     * 解析ISO 8601时间戳
     */
    private fun parseIso8601Timestamp(dateStr: String): Long {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
    }
    
    /**
     * 生成ISO 8601格式时间戳（用于AWS签名）
     */
    private fun iso8601(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
    
    /**
     * 生成日期戳（用于AWS签名）
     */
    private fun dateStamp(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
}
