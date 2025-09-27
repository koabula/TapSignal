package org.thoughtcrime.securesms.tap.provider.cos.utils.client.tencent

import android.content.Context
import com.tencent.cos.xml.*
import com.tencent.cos.xml.exception.CosXmlClientException
import com.tencent.cos.xml.exception.CosXmlServiceException
import com.tencent.cos.xml.model.`object`.*
import com.tencent.cos.xml.model.bucket.GetBucketRequest
import com.tencent.qcloud.core.auth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TencentCosClient(private val config: CosConfig, private val context: Context? = null) : CosClient {
    private val TAG = Log.tag(TencentCosClient::class.java)
    private val cosXmlService: CosXmlService

    init {
        // 创建CosXmlServiceConfig对象，根据需要修改默认的配置参数
        val serviceConfig = CosXmlServiceConfig.Builder()
            .setRegion(config.region)
            .isHttps(true)
            .builder()

        // 创建凭证提供者
        val credentialProvider: QCloudCredentialProvider = if (!config.sessionToken.isNullOrEmpty()) {
            // 使用临时凭证
            object : BasicLifecycleCredentialProvider() {
                override fun fetchNewCredentials(): QCloudLifecycleCredentials {
                    val expiredTime = System.currentTimeMillis() / 1000 + 7200 // 2小时后过期
                    return SessionQCloudCredentials(config.secretId, config.secretKey, config.sessionToken, expiredTime)
                }
            }
        } else {
            // 使用永久凭证
            ShortTimeCredentialProvider(config.secretId, config.secretKey, 300)
        }

        // 初始化COS服务
        cosXmlService = CosXmlService(context, serviceConfig, credentialProvider)

        Log.d(TAG, "腾讯云COS客户端初始化完成 - 区域: ${config.region}, 存储桶: ${config.bucketName}")
    }

    override suspend fun createDirectory(directoryPath: String): Boolean {
        return try {
            val normalized = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"

            // 在COS中，目录是通过上传一个0字节的对象来创建的
            val putObjectRequest = PutObjectRequest(config.bucketName, normalized, ByteArray(0))
            val putObjectResult = withContext(Dispatchers.IO) {
                cosXmlService.putObject(putObjectRequest)
            }

            Log.d(TAG, "创建目录成功: $normalized")
            true
        } catch (e: CosXmlClientException) {
            Log.e(TAG, "创建目录失败 - 客户端异常: ${e.message}", e)
            false
        } catch (e: CosXmlServiceException) {
            Log.e(TAG, "创建目录失败 - 服务异常: ${e.errorMessage}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "创建目录失败 - 未知异常: ${e.message}", e)
            false
        }
    }

    override suspend fun uploadFile(localFile: File, remotePath: String): Boolean {
        return try {
            val putObjectRequest = PutObjectRequest(config.bucketName, remotePath, localFile.absolutePath)
            val putObjectResult = withContext(Dispatchers.IO) {
                cosXmlService.putObject(putObjectRequest)
            }

            Log.d(TAG, "上传文件成功: $remotePath")
            true
        } catch (e: CosXmlClientException) {
            Log.e(TAG, "上传文件失败 - 客户端异常: ${e.message}", e)
            false
        } catch (e: CosXmlServiceException) {
            Log.e(TAG, "上传文件失败 - 服务异常: ${e.errorMessage}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "上传文件失败 - 未知异常: ${e.message}", e)
            false
        }
    }

    override suspend fun downloadFile(remotePath: String, localFile: File): Boolean {
        return try {
            Log.d(TAG, "开始下载文件:")
            Log.d(TAG, "  - 远程路径: $remotePath")
            Log.d(TAG, "  - 本地文件: ${localFile.absolutePath}")
            Log.d(TAG, "  - 本地文件存在: ${localFile.exists()}")
            Log.d(TAG, "  - 本地文件是目录: ${localFile.isDirectory()}")

            // 确保父目录存在
            localFile.parentFile?.let { parentDir ->
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                    Log.d(TAG, "创建父目录: ${parentDir.absolutePath}")
                } else {
                    Log.d(TAG, "父目录已存在: ${parentDir.absolutePath}")
                }
            }

            // 如果目标文件已存在，先删除
            if (localFile.exists()) {
                if (localFile.isDirectory()) {
                    // 如果是目录，删除整个目录
                    localFile.deleteRecursively()
                    Log.d(TAG, "删除已存在的目录: ${localFile.absolutePath}")
                } else {
                    localFile.delete()
                    Log.d(TAG, "删除已存在的文件: ${localFile.absolutePath}")
                }
            }

            // 腾讯云COS SDK不接受带前导斜杠的路径
            val normalizedRemotePath = remotePath.removePrefix("/")
            
            Log.d(TAG, "调用腾讯云SDK下载:")
            Log.d(TAG, "  - 存储桶: ${config.bucketName}")
            Log.d(TAG, "  - 原始路径: $remotePath")
            Log.d(TAG, "  - 标准化路径: $normalizedRemotePath")
            Log.d(TAG, "  - 本地路径: ${localFile.absolutePath}")

            // 修复：使用父目录作为下载目录，让SDK自动创建文件
            val downloadDir = localFile.parentFile!!
            val getObjectRequest = GetObjectRequest(config.bucketName, normalizedRemotePath, downloadDir.absolutePath)
            val getObjectResult = withContext(Dispatchers.IO) {
                cosXmlService.getObject(getObjectRequest)
            }

            // SDK会在downloadDir下创建文件，文件名是remotePath的最后一部分
            val fileName = remotePath.substringAfterLast("/")
            val sdkCreatedFile = File(downloadDir, fileName)

            Log.d(TAG, "SDK创建的文件:")
            Log.d(TAG, "  - 预期文件: ${sdkCreatedFile.absolutePath}")
            Log.d(TAG, "  - 文件存在: ${sdkCreatedFile.exists()}")
            Log.d(TAG, "  - 是否为目录: ${sdkCreatedFile.isDirectory()}")
            Log.d(TAG, "  - 文件大小: ${if (sdkCreatedFile.exists()) sdkCreatedFile.length() else "N/A"}")

            // 如果SDK创建的文件与目标文件不同，进行重命名或复制
            if (sdkCreatedFile.absolutePath != localFile.absolutePath) {
                if (sdkCreatedFile.exists() && sdkCreatedFile.isFile()) {
                    if (localFile.exists()) {
                        localFile.delete()
                    }
                    val renamed = sdkCreatedFile.renameTo(localFile)
                    Log.d(TAG, "重命名文件: ${if (renamed) "成功" else "失败"}")
                    if (!renamed) {
                        // 如果重命名失败，尝试复制
                        sdkCreatedFile.copyTo(localFile, overwrite = true)
                        sdkCreatedFile.delete()
                        Log.d(TAG, "复制文件完成")
                    }
                }
            }

            Log.d(TAG, "腾讯云SDK下载和文件处理完成，最终检查:")
            Log.d(TAG, "  - 目标文件存在: ${localFile.exists()}")
            Log.d(TAG, "  - 目标文件大小: ${if (localFile.exists()) localFile.length() else "N/A"}")
            Log.d(TAG, "  - 目标是否为目录: ${localFile.isDirectory()}")
            Log.d(TAG, "  - 目标是否为文件: ${localFile.isFile()}")

            // 详细验证下载结果
            when {
                !localFile.exists() -> {
                    Log.e(TAG, "下载失败: 文件不存在 - ${localFile.absolutePath}")
                    false
                }
                localFile.isDirectory() -> {
                    Log.e(TAG, "下载失败: 目标是目录而不是文件 - ${localFile.absolutePath}")
                    // 删除错误创建的目录
                    localFile.deleteRecursively()
                    false
                }
                localFile.length() == 0L -> {
                    Log.e(TAG, "下载失败: 文件为空 - ${localFile.absolutePath}")
                    localFile.delete()
                    false
                }
                else -> {
                    Log.d(TAG, "下载文件成功: $remotePath -> ${localFile.absolutePath}, 大小: ${localFile.length()} bytes")
                    true
                }
            }

        } catch (e: CosXmlClientException) {
            Log.e(TAG, "下载文件失败 - 客户端异常: ${e.message}", e)
            false
        } catch (e: CosXmlServiceException) {
            Log.e(TAG, "下载文件失败 - 服务异常: ${e.errorMessage}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "下载文件失败 - 未知异常: ${e.message}", e)
            false
        }
    }

    override suspend fun listFiles(directoryPath: String): List<CosFileInfo> {
        return try {
            // 处理路径前缀，移除前导斜杠（如果存在）
            val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
            val prefix = if (normalizedPath.endsWith("/")) normalizedPath else "$normalizedPath/"

            Log.i(TAG, "=== 列举文件调试信息 ===")
            Log.i(TAG, "原始路径: $directoryPath")
            Log.i(TAG, "标准化路径: $normalizedPath")
            Log.i(TAG, "使用前缀: $prefix")
            Log.i(TAG, "目标存储桶: ${config.bucketName}")
            Log.i(TAG, "使用区域: ${config.region}")
            Log.i(TAG, "SecretId: ${config.secretId.take(8)}...")
            Log.i(TAG, "是否有SessionToken: ${!config.sessionToken.isNullOrEmpty()}")
            Log.i(TAG, "=== 调试信息结束 ===")

            Log.d(TAG, "列举文件 - 原始路径: $directoryPath, 前缀: $prefix")

            val getBucketRequest = GetBucketRequest(config.bucketName)
            getBucketRequest.setPrefix(prefix)
            getBucketRequest.setDelimiter("/")
            getBucketRequest.setMaxKeys(1000)

            val getBucketResult = withContext(Dispatchers.IO) {
                cosXmlService.getBucket(getBucketRequest)
            }

            val fileList = mutableListOf<CosFileInfo>()

            // 处理对象列表
            getBucketResult.listBucket?.contentsList?.forEach { content ->
                val fileName = content.key
                val fileSize = try {
                    content.size.toLong()
                } catch (e: Exception) {
                    0L // 如果转换失败，默认为0
                }
                val lastModified = try {
                    content.lastModified.toLong()
                } catch (e: Exception) {
                    System.currentTimeMillis() // 如果转换失败，使用当前时间
                }

                fileList.add(CosFileInfo(
                    name = fileName,
                    size = fileSize,
                    lastModified = lastModified
                ))
            }

            Log.d(TAG, "列举文件成功，找到 ${fileList.size} 个文件")
            fileList
        } catch (e: CosXmlClientException) {
            Log.e(TAG, "列举文件失败 - 客户端异常: ${e.message}", e)
            emptyList()
        } catch (e: CosXmlServiceException) {
            Log.e(TAG, "列举文件失败 - 服务异常: ${e.errorMessage}", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "列举文件失败 - 未知异常: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int): CosAccessToken {
        return try {
            // 使用腾讯云STS API获取联合身份临时访问凭证
            val host = "sts.tencentcloudapi.com"
            val service = "sts"
            val version = "2018-08-13"
            val action = "GetFederationToken"
            val timestamp = System.currentTimeMillis() / 1000

            // 构建权限策略
            val policy = buildAccessPolicy(directoryPath)

            // 构建请求体 - GetFederationToken需要Name和Policy参数
            val requestBody = """
            {
                "Name": "signal-cos-temp-user",
                "Policy": "$policy",
                "DurationSeconds": ${durationMinutes * 60}
            }
            """.trimIndent()

            Log.d(TAG, "请求临时访问凭证，有效期: $durationMinutes 分钟")
            Log.d(TAG, "目录路径: $directoryPath")

            // 生成TC3-HMAC-SHA256签名
            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = okhttp3.Request.Builder()
                .url("https://$host/")
                .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            val okHttpClient = okhttp3.OkHttpClient()
            val resp = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }
            resp.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string() ?: "No error body"
                    Log.e(TAG, "STS API请求失败，状态码: ${resp.code}, 响应: $errorBody")
                    throw CosNetworkException("获取临时访问凭证失败: ${resp.code} - $errorBody")
                }

                val responseBody = resp.body?.string() ?: throw CosNetworkException("STS API响应为空")
                Log.d(TAG, "STS API响应长度: ${responseBody.length}")

                // 敏感信息不要完整记录，只记录截断的片段
                if (responseBody.length > 100) {
                    Log.d(TAG, "STS API响应片段: ${responseBody.substring(0, 100)}...")
                }

                // 腾讯云STS API返回JSON格式
                if (responseBody.trim().startsWith("{")) {
                    TencentStsXmlParser.parseJsonResponse(responseBody)
                } else {
                    // 兼容XML格式
                    TencentStsXmlParser.parseFederationToken(responseBody)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "生成临时访问凭证失败: ${e.message}", e)
            throw CosAuthException("生成临时访问凭证失败: ${e.message}", e)
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean {
        return try {
            val deleteObjectRequest = DeleteObjectRequest(config.bucketName, remotePath)
            val deleteObjectResult = withContext(Dispatchers.IO) {
                cosXmlService.deleteObject(deleteObjectRequest)
            }
            
            Log.d(TAG, "删除文件成功: $remotePath")
            true
        } catch (e: CosXmlClientException) {
            Log.e(TAG, "删除文件失败 - 客户端异常: ${e.message}", e)
            false
        } catch (e: CosXmlServiceException) {
            Log.e(TAG, "删除文件失败 - 服务异常: ${e.errorMessage}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "删除文件失败 - 未知异常: ${e.message}", e)
            false
        }
    }

    override suspend fun fileExists(remotePath: String): Boolean {
        return try {
            val headObjectRequest = HeadObjectRequest(config.bucketName, remotePath)
            val headObjectResult = withContext(Dispatchers.IO) {
                cosXmlService.headObject(headObjectRequest)
            }
            
            Log.d(TAG, "文件存在检查成功: $remotePath")
            true
        } catch (e: CosXmlServiceException) {
            if (e.statusCode == 404) {
                Log.d(TAG, "文件不存在: $remotePath")
                false
            } else {
                Log.e(TAG, "文件存在检查失败 - 服务异常: ${e.errorMessage}", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "文件存在检查失败 - 未知异常: ${e.message}", e)
            false
        }
    }

    /**
     * 构建访问策略，限制只能访问指定目录
     */
    private fun buildAccessPolicy(directoryPath: String): String {
        // 确保路径格式正确
        val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath

        // 提取AppID和存储桶名称
        val appId = extractAppId(config.bucketName)
        val bucketNameWithAppId = config.bucketName // 保留完整的 <bucket>-<appid> 形式

        // 必须使用完整的APPID，不能使用通配符
        if (appId.isEmpty()) {
            Log.w(TAG, "无法从存储桶名称中提取APPID，将使用完整的存储桶名")
        }

        // 使用完整的格式：qcs::cos:<region>:uid/<app_id>:<bucket-name>/<path>
        val bucketResource = "qcs::cos:${config.region}:uid/${appId}:${bucketNameWithAppId}/*"
        val objectResource = "qcs::cos:${config.region}:uid/${appId}:${bucketNameWithAppId}/${normalizedPath}*"

        // 添加更详细的日志，帮助诊断问题
        Log.d(TAG, "CAM策略 - 原始存储桶名称: ${config.bucketName}")
        Log.d(TAG, "CAM策略 - 提取的AppID: $appId")
        Log.d(TAG, "CAM策略 - 使用完整存储桶名称: $bucketNameWithAppId")
        Log.d(TAG, "CAM策略 - 存储桶资源路径: $bucketResource")
        Log.d(TAG, "CAM策略 - 对象资源路径: $objectResource")

        // 简化权限策略，减少可能的错误
        val policy = """
        {
            "version": "2.0",
            "statement": [
                {
                    "effect": "allow",
                    "action": [
                        "name/cos:GetObject",
                        "name/cos:PutObject",
                        "name/cos:DeleteObject",
                        "name/cos:GetBucket",
                        "name/cos:HeadBucket",
                        "name/cos:ListMultipartUploads",
                        "name/cos:ListParts",
                        "name/cos:ListObjects"
                    ],
                    "resource": [
                        "$bucketResource",
                        "$objectResource"
                    ]
                }
            ]
        }
        """.trimIndent().replace("\n", "").replace(" ", "")

        val escapedPolicy = policy.replace("\"", "\\\"")
        Log.d(TAG, "CAM策略 - 完整策略: $policy")
        return escapedPolicy
    }

    /**
     * 从bucket名称中提取AppID
     * 腾讯云COS的bucket命名格式为：<bucketname>-<appid>
     */
    private fun extractAppId(bucketName: String): String {
        val parts = bucketName.split("-")
        if (parts.size > 1) {
            val lastPart = parts.last()
            // AppID应该是纯数字
            if (lastPart.matches(Regex("\\d+"))) {
                return lastPart
            }
        }
        return ""
    }

    // 添加一个辅助方法，用于在日志中安全地显示凭证
    private fun maskCredential(credential: String): String {
        if (credential.length <= 8) return "***"
        return "${credential.take(4)}...${credential.takeLast(4)}"
    }
} 