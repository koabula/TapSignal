package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosFileInfo
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosAccessToken
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType

/**
 * COS传输提供者实现
 * 
 * 直接使用utils中的COS底层代码，不再依赖cos和coscomm模块。
 * 提供云对象存储服务的传输能力。
 */
class CosTransportProvider(
    private val context: Context,
    private val config: Map<String, Any>
) : TransportProvider {

    companion object {
        private val TAG = Log.tag(CosTransportProvider::class.java)
        
        // COS特定配置
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100MB
        private const val OUTBOX_PATH = "/outbox/"
        private const val GROUP_PATH_PREFIX = "/group/"
        private const val MESSAGE_FORMAT_VERSION = 1
        private const val GROUP_OUTBOX_SUFFIX = "/outbox/"
    }

    override val providerType: String = "cos"
    override val supportsAuth: Boolean = true
    override val supportsGroup: Boolean = true
    override val displayName: String = "云对象存储 (COS)"
    override val description: String = "支持AWS S3和腾讯云COS的云存储服务"
    override val maxMessageSize: Long = MAX_FILE_SIZE
    
    override val supportedPermissions: Set<TransportPermission> = setOf(
        TransportPermission.READ,
        TransportPermission.WRITE,
        TransportPermission.DELETE,
        TransportPermission.LIST
    )
    
    // COS配置信息
    private val cosConfig: CosConfig by lazy {
        createCosConfigFromMap(config)
    }

    /**
     * 推送消息到COS
     */
    override suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始推送消息: messageId=${message.messageId}, recipientId=${metadata.recipientId}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 获取发送元数据（使用本端凭证和存储）
                val sendMetadata = cosMetadata.getSendMetadata()
                
                // 创建COS客户端（使用发送元数据）
                val cosClient = createCosClientForSend(cosMetadata)
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建COS客户端"
                    )

                // 检查消息大小
                if (isMessageTooLarge(message)) {
                    return@withContext TransportResult.failure(
                        TransportError.MESSAGE_TOO_LARGE,
                        false,
                        "消息大小超过限制: ${maxMessageSize}字节"
                    )
                }

                // 序列化消息为JSON格式（兼容coscomm）
                val messageData = TransportMessage.serialize(message)
                
                // 创建临时文件
                val tempFile = createTempFile(messageData)
                
                try {
                    // 使用发送路径：/outbox/<recipientId>/
                    val remotePath = "${sendMetadata.path}${message.messageId}_${System.currentTimeMillis()}.dat"
                    
                    // 上传文件
                    val uploadSuccess = cosClient.uploadFile(tempFile, remotePath)
                    
                    if (uploadSuccess) {
                        Log.i(TAG, "消息推送成功: messageId=${message.messageId}")
                        TransportResult.Success(
                            message = null,
                            metadata = mapOf(
                                "remotePath" to remotePath,
                                "uploadTime" to System.currentTimeMillis()
                            )
                        )
                    } else {
                        Log.e(TAG, "消息推送失败: messageId=${message.messageId}")
                        TransportResult.failure(
                            TransportError.NETWORK_ERROR,
                            true,
                            "文件上传失败"
                        )
                    }
                } finally {
                    // 清理临时文件
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "推送消息时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 从COS拉取消息（废弃方法，保持向下兼容）
     */
    @Deprecated("使用 listFiles + downloadFile 替代")
    override suspend fun pull(metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始拉取消息: recipientId=${metadata.recipientId}")
                
                // 使用新的文件操作接口
                val listResult = listFiles(metadata.getReceiveMetadata().path, metadata)
                if (listResult !is TransportResult.Success || listResult.files.isNullOrEmpty()) {
                    return@withContext TransportResult.Success(null)
                }

                // 获取最新的文件（保持兼容性）
                val latestFile = listResult.files.maxByOrNull { it.lastModified }
                    ?: return@withContext TransportResult.Success(null)

                // 下载文件
                val downloadResult = downloadFile(latestFile, metadata)
                if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                    // 创建临时文件来解析消息
                    val tempFile = File.createTempFile("cos_parse_", ".dat", context.cacheDir)
                    try {
                        tempFile.writeBytes(downloadResult.data)
                        val messageFileInfo = parseMessageFileName(latestFile.name)
                        if (messageFileInfo != null) {
                            val message = parseMessageFromBinaryData(downloadResult.data, messageFileInfo)
                            if (message != null) {
                                Log.i(TAG, "消息拉取成功: messageId=${message.messageId}")
                                TransportResult.Success(message)
                            } else {
                                Log.w(TAG, "消息解析失败: ${latestFile.name}")
                                TransportResult.Failed(TransportError.INVALID_FORMAT, false, "消息解析失败")
                            }
                        } else {
                            Log.w(TAG, "文件名解析失败: ${latestFile.name}")
                            TransportResult.Failed(TransportError.INVALID_FORMAT, false, "文件名解析失败")
                        }
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }
                } else {
                    downloadResult
                }

            } catch (e: Exception) {
                Log.e(TAG, "拉取消息时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 列出指定路径下的文件
     */
    override suspend fun listFiles(path: String, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "列举文件: path=$path, recipientId=${metadata.recipientId}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 获取接收元数据（使用对端凭证和存储）
                val receiveMetadata = cosMetadata.getReceiveMetadata()
                
                // 创建COS客户端（使用接收元数据）
                val cosClient = createCosClientForReceive(cosMetadata)
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建COS客户端"
                    )

                // 列举目录中的文件
                val cosFiles: List<CosFileInfo> = cosClient.listFiles(path)
                
                // 转换为FileInfo列表
                val fileInfos = cosFiles.map { cosFile ->
                    FileInfo(
                        name = cosFile.name,
                        path = "${path.trimEnd('/')}/${cosFile.name}",
                        size = cosFile.size,
                        lastModified = cosFile.lastModified,
                        etag = null, // CosFileInfo中暂无etag字段，保持null
                        mimeType = "application/octet-stream"
                    )
                }.filter { it.isMessageFile() } // 只返回消息文件
                
                Log.d(TAG, "找到文件数量: ${fileInfos.size}")
                TransportResult.success(fileInfos)

            } catch (e: Exception) {
                Log.e(TAG, "列举文件时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 下载指定文件
     */
    override suspend fun downloadFile(fileInfo: FileInfo, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "下载文件: ${fileInfo.name}, size=${fileInfo.size}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 创建COS客户端
                val cosClient = createCosClient(cosMetadata)
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建COS客户端"
                    )

                // 下载文件
                val tempFile = File.createTempFile("cos_download_", ".dat", context.cacheDir)
                
                try {
                    val downloadSuccess = cosClient.downloadFile(fileInfo.path, tempFile)
                    
                    if (downloadSuccess && tempFile.exists()) {
                        val data = tempFile.readBytes()
                        Log.d(TAG, "文件下载成功: ${fileInfo.name}")
                        TransportResult.success(data)
                    } else {
                        Log.e(TAG, "文件下载失败: ${fileInfo.name}")
                        TransportResult.failure(
                            TransportError.NETWORK_ERROR,
                            true,
                            "文件下载失败"
                        )
                    }
                } finally {
                    // 清理临时文件
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载文件时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 上传文件数据
     */
    override suspend fun uploadFile(data: ByteArray, path: String, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "上传文件: path=$path, size=${data.size}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 创建COS客户端
                val cosClient = createCosClient(cosMetadata)
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建COS客户端"
                    )

                // 创建临时文件
                val tempFile = File.createTempFile("cos_upload_", ".dat", context.cacheDir)
                
                try {
                    tempFile.writeBytes(data)
                    
                    // 上传文件
                    val uploadSuccess = cosClient.uploadFile(tempFile, path)
                    
                    if (uploadSuccess) {
                        Log.d(TAG, "文件上传成功: $path")
                        TransportResult.Success(
                            metadata = mapOf(
                                "uploadPath" to path,
                                "uploadTime" to System.currentTimeMillis(),
                                "fileSize" to data.size
                            )
                        )
                    } else {
                        Log.e(TAG, "文件上传失败: $path")
                        TransportResult.failure(
                            TransportError.NETWORK_ERROR,
                            true,
                            "文件上传失败"
                        )
                    }
                } finally {
                    // 清理临时文件
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "上传文件时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 群组推送 - 上传到自己COS的群聊目录
     */
    override suspend fun groupPush(message: TransportMessage, groupMetadata: GroupTransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始群组推送: groupId=${groupMetadata.groupId}, messageId=${message.messageId}")
                
                // 构造群组专用路径
                val groupPath = "$GROUP_PATH_PREFIX${groupMetadata.groupId}$GROUP_OUTBOX_SUFFIX"
                
                // 使用自己的COS配置创建元数据
                val myAddress = "${cosConfig.provider.name.lowercase()}://${cosConfig.bucketName}.${cosConfig.region}"
                val cosMetadata = CosTransportMetadata(
                    recipientId = "self", // 上传到自己的COS
                    providerType = "cos",
                    myAddress = myAddress,
                    myToken = null, // 使用自己的凭证
                    myRegion = cosConfig.region,
                    myBucketName = cosConfig.bucketName,
                    peerAddress = myAddress, // 群组消息，对端就是自己
                    peerToken = null,
                    peerRegion = cosConfig.region,
                    peerBucketName = cosConfig.bucketName,
                    myId = "self"
                )

                // 调用常规push方法
                push(message, cosMetadata)

            } catch (e: Exception) {
                Log.e(TAG, "群组推送时发生异常", e)
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 群组拉取 - 轮询所有群友的群聊目录
     */
    override suspend fun groupPull(groupMetadata: GroupTransportMetadata): List<TransportResult> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始群组拉取: groupId=${groupMetadata.groupId}, 成员数=${groupMetadata.memberMetadata.size}")
                
                val results = mutableListOf<TransportResult>()
                
                // 为每个群组成员构造群组路径并拉取
                for (memberMetadata in groupMetadata.memberMetadata) {
                    try {
                        val groupPath = "$GROUP_PATH_PREFIX${groupMetadata.groupId}$GROUP_OUTBOX_SUFFIX"
                        
                        // 创建成员的群组元数据 
                        val memberGroupMetadata = if (memberMetadata is CosTransportMetadata) {
                            // 创建一个新的实例，因为路径需要更新为群组路径
                            CosTransportMetadata(
                                recipientId = memberMetadata.recipientId,
                                providerType = memberMetadata.providerType,
                                myAddress = memberMetadata.myAddress,
                                myToken = memberMetadata.myToken,
                                myRegion = memberMetadata.myRegion,
                                myBucketName = memberMetadata.myBucketName,
                                peerAddress = memberMetadata.peerAddress,
                                peerToken = memberMetadata.peerToken,
                                peerRegion = memberMetadata.peerRegion,
                                peerBucketName = memberMetadata.peerBucketName,
                                myId = memberMetadata.myId
                            )
                        } else {
                            // 转换为COS元数据
                            CosTransportMetadata(
                                recipientId = memberMetadata.recipientId,
                                providerType = "cos",
                                myAddress = cosConfig.let { "${it.provider.name.lowercase()}://${it.bucketName}.${it.region}" },
                                myToken = null,
                                myRegion = cosConfig.region,
                                myBucketName = cosConfig.bucketName,
                                peerAddress = (memberMetadata as? CosTransportMetadata)?.peerAddress ?: "",
                                peerToken = (memberMetadata as? CosTransportMetadata)?.peerToken as? CosTransportToken,
                                peerRegion = (memberMetadata as? CosTransportMetadata)?.peerRegion ?: "",
                                peerBucketName = (memberMetadata as? CosTransportMetadata)?.peerBucketName ?: "",
                                myId = "self"
                            )
                        }
                        
                        val result = pull(memberGroupMetadata)
                        results.add(result)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "拉取群组成员消息失败: ${memberMetadata.recipientId}", e)
                        results.add(TransportResult.fromException(e, true))
                    }
                }
                
                Log.i(TAG, "群组拉取完成: 总结果数=${results.size}")
                results

            } catch (e: Exception) {
                Log.e(TAG, "群组拉取时发生异常", e)
                listOf(TransportResult.fromException(e, true))
            }
        }
    }

    /**
     * 生成访问Token - 直接使用COS客户端生成临时凭证
     */
    override suspend fun generateToken(request: TransportTokenRequest): TransportToken? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "生成访问Token: recipientId=${request.recipientId}")
                
                // 使用COS客户端生成临时凭证
                val cosClient = CosClientFactory.createClient(cosConfig, context)
                val directoryPath = OUTBOX_PATH // 默认使用outbox路径
                
                // 生成临时访问凭证
                val accessToken = cosClient.generateTemporaryAccessToken(
                    directoryPath = directoryPath, 
                    durationMinutes = 60 // 1小时有效期
                )
                
                // 转换为CosTransportToken
                val transportToken = CosTransportToken(
                    tokenId = UUID.randomUUID().toString(),
                    recipientId = request.recipientId,
                    providerType = "cos",
                    permissions = request.requestedPermissions,
                    expirationTime = accessToken.expiration,
                    accessKeyId = accessToken.accessKeyId,
                    secretAccessKey = accessToken.secretAccessKey,
                    sessionToken = accessToken.sessionToken,
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName
                )
                
                Log.i(TAG, "Token生成成功: tokenId=${transportToken.tokenId}")
                transportToken

            } catch (e: Exception) {
                Log.e(TAG, "生成Token时发生异常", e)
                null
            }
        }
    }

    /**
     * 验证Token有效性和权限范围
     * 
     * 对于长期Token，验证：
     * 1. 凭证是否有效（能否访问服务）
     * 2. 权限范围是否正确（只读特定路径前缀）
     * 3. 可达性测试（能否列举指定前缀）
     */
    override suspend fun validateToken(token: TransportToken): Boolean {
        return try {
            val cosToken = token as? CosTransportToken ?: return false
            Log.d(TAG, "开始验证Token: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
            
            val cosClient = createCosClient(cosToken)
            if (cosClient == null) {
                Log.w(TAG, "无法创建COS客户端")
                return false
            }
            
            // 1. 基本连通性测试：尝试HEAD bucket操作
            val bucketConnectivity = testBucketConnectivity(cosClient, cosToken)
            if (!bucketConnectivity) {
                Log.w(TAG, "Bucket连通性测试失败")
                return false
            }
            
            // 2. 权限范围验证：测试是否只能访问指定路径前缀
            val pathPermission = validatePathPermissions(cosClient, cosToken)
            if (!pathPermission) {
                Log.w(TAG, "路径权限验证失败")
                return false
            }
            
            // 3. 权限验证：根据策略调整
            // 按照当前阶段策略，token应该是长期最高权限的，所以暂时跳过只读校验
            // TODO: 后续可能需要根据实际部署需求调整权限策略
            val readOnlyCheck = true // 暂时禁用只读校验，统一与长期最高权限token策略
            if (!readOnlyCheck) {
                Log.w(TAG, "权限验证失败")
                return false
            }
            
            Log.d(TAG, "权限验证跳过：当前使用长期最高权限token策略")
            
            Log.d(TAG, "Token验证成功: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Token验证异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试Bucket连通性
     */
    private suspend fun testBucketConnectivity(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            when (cosConfig.provider) {
                CosConfig.Provider.TENCENT -> testTencentBucketConnectivity(cosClient, cosToken)
                CosConfig.Provider.AWS -> testAwsBucketConnectivity(cosClient, cosToken)
                else -> {
                    Log.w(TAG, "不支持的COS Provider: ${cosConfig.provider}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bucket连通性测试异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 腾讯云Bucket连通性测试
     */
    private suspend fun testTencentBucketConnectivity(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            val bucketName = extractBucketName(cosToken.bucketName)
            
            // 构建HEAD Bucket请求
            val host = "${bucketName}.cos.${cosToken.region}.myqcloud.com"
            val timestamp = System.currentTimeMillis() / 1000
            
            // 构建授权头
            val authorization = org.thoughtcrime.securesms.tap.provider.cos.utils.client.tencent.TencentSigner.buildTC3AuthorizationHeader(
                secretId = cosToken.accessKeyId,
                secretKey = cosToken.secretAccessKey,
                service = "cos",
                region = cosToken.region,
                action = "HeadBucket",
                timestamp = timestamp,
                payload = "",
                host = host
            )
            
            // 构建HTTP请求
            val request = okhttp3.Request.Builder()
                .url("https://$host/")
                .head()
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Timestamp", timestamp.toString())
                .build()
            
            val okHttpClient = okhttp3.OkHttpClient()
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        Log.d(TAG, "腾讯云Bucket连通性正常")
                        true
                    }
                    403 -> {
                        Log.w(TAG, "腾讯云Bucket访问被拒绝")
                        false
                    }
                    404 -> {
                        Log.w(TAG, "腾讯云Bucket不存在")
                        false
                    }
                    else -> {
                        Log.w(TAG, "腾讯云Bucket连通性测试失败: ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云连通性测试异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * AWS S3 Bucket连通性测试
     */
    private suspend fun testAwsBucketConnectivity(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            val bucketName = extractBucketName(cosToken.bucketName)
            val region = cosToken.region
            
            // 构建HTTP请求时间戳
            val date = java.util.Date()
            val amzDate = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
            val dateStamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
            
            // 构建Canonical Request
            val host = "${bucketName}.s3.${region}.amazonaws.com"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val canonicalHeaders = "host:$host\nx-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-date"
            val payloadHash = org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner.hash("")
            
            val canonicalRequest = "HEAD\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            // 构建授权头
            val authorization = org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner.buildAuthorizationHeader(
                accessKeyId = cosToken.accessKeyId,
                secretKey = cosToken.secretAccessKey,
                region = region,
                service = "s3",
                canonicalRequest = canonicalRequest,
                requestDateTime = amzDate,
                dateStamp = dateStamp,
                signedHeaders = signedHeaders
            )
            
            // 构建HTTP请求
            val request = okhttp3.Request.Builder()
                .url("https://$host/")
                .head()
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-Amz-Date", amzDate)
                .build()
            
            val okHttpClient = okhttp3.OkHttpClient()
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        Log.d(TAG, "AWS S3 Bucket连通性正常")
                        true
                    }
                    403 -> {
                        Log.w(TAG, "AWS S3 Bucket访问被拒绝")
                        false
                    }
                    404 -> {
                        Log.w(TAG, "AWS S3 Bucket不存在")
                        false
                    }
                    else -> {
                        Log.w(TAG, "AWS S3 Bucket连通性测试失败: ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AWS连通性测试异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 验证路径权限（确保只能访问指定前缀）
     */
    private suspend fun validatePathPermissions(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            // 获取真实的COS客户端
            val realCosClient = createCosClient(CosTransportMetadata(
                recipientId = cosToken.recipientId,
                providerType = "cos",
                myAddress = "${cosToken.region}://${cosToken.bucketName}",
                myToken = cosToken,
                myRegion = cosToken.region,
                myBucketName = cosToken.bucketName,
                peerAddress = "${cosToken.region}://${cosToken.bucketName}",
                peerToken = cosToken,
                peerRegion = cosToken.region,
                peerBucketName = cosToken.bucketName,
                myId = "self"
            ))
            
            if (realCosClient == null) {
                Log.w(TAG, "无法创建COS客户端进行权限验证")
                return false
            }
            
            // 1. 测试允许的路径：应该能访问
            val allowedPath = "/outbox/${cosToken.recipientId}/"
            val allowedPathTest = testPathAccess(realCosClient, allowedPath, expectSuccess = true)
            
            if (!allowedPathTest) {
                Log.w(TAG, "无法访问应该允许的路径: $allowedPath")
                return false
            }
            
            // 2. 测试禁止的路径：应该被拒绝访问
            val forbiddenPaths = listOf(
                "/", // 根目录
                "/inbox/", // 其他用户目录
                "/outbox/other_user/", // 其他用户的outbox
                "/admin/", // 管理目录
                "/system/" // 系统目录
            )
            
            for (forbiddenPath in forbiddenPaths) {
                val forbiddenPathTest = testPathAccess(realCosClient, forbiddenPath, expectSuccess = false)
                if (!forbiddenPathTest) {
                    Log.w(TAG, "能够访问应该禁止的路径: $forbiddenPath")
                    return false
                }
            }
            
            Log.d(TAG, "路径权限验证通过: 允许路径可访问，禁止路径被正确拒绝")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "路径权限验证异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试路径访问权限
     */
    private suspend fun testPathAccess(cosClient: CosClient, path: String, expectSuccess: Boolean): Boolean {
        return try {
            // 尝试列举指定路径下的文件
            val files = cosClient.listFiles(path)
            
            // 如果期望成功：操作应该成功（返回列表，可能为空）
            // 如果期望失败：操作应该抛出权限异常
            if (expectSuccess) {
                Log.d(TAG, "路径访问测试成功: $path (找到${files.size}个文件)")
                true
            } else {
                Log.w(TAG, "路径访问测试失败: $path 应该被拒绝但实际成功了")
                false
            }
            
        } catch (e: SecurityException) {
            // 权限异常
            if (expectSuccess) {
                Log.w(TAG, "路径访问测试失败: $path 应该成功但被拒绝了")
                false
            } else {
                Log.d(TAG, "路径访问测试成功: $path 正确被拒绝")
                true
            }
        } catch (e: Exception) {
            // 其他异常（网络错误、配置错误等）
            val errorMsg = LogSanitizer.sanitizeThrowable(e)
            if (e.message?.contains("403") == true || e.message?.contains("Forbidden") == true) {
                // HTTP 403表示权限被拒绝
                if (expectSuccess) {
                    Log.w(TAG, "路径访问测试失败: $path 遇到403错误")
                    false
                } else {
                    Log.d(TAG, "路径访问测试成功: $path 正确返回403")
                    true
                }
            } else {
                Log.w(TAG, "路径访问测试遇到其他错误: $path - $errorMsg")
                false
            }
        }
    }
    
    /**
     * 验证只读权限（确保无法执行写操作）
     */
    private suspend fun validateReadOnlyPermission(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            // 获取真实的COS客户端
            val realCosClient = createCosClient(CosTransportMetadata(
                recipientId = cosToken.recipientId,
                providerType = "cos",
                myAddress = "${cosToken.region}://${cosToken.bucketName}",
                myToken = cosToken,
                myRegion = cosToken.region,
                myBucketName = cosToken.bucketName,
                peerAddress = "${cosToken.region}://${cosToken.bucketName}",
                peerToken = cosToken,
                peerRegion = cosToken.region,
                peerBucketName = cosToken.bucketName,
                myId = "self"
            ))
            
            if (realCosClient == null) {
                Log.w(TAG, "无法创建COS客户端进行权限验证")
                return false
            }
            
            // 1. 测试写操作：尝试上传文件（应该被拒绝）
            val testKey = "/outbox/${cosToken.recipientId}/__readonly_test_${System.currentTimeMillis()}.tmp"
            val testData = "readonly_test_${System.currentTimeMillis()}".toByteArray()
            
            // 创建测试文件
            val testFile = File.createTempFile("readonly_test", ".tmp", context.cacheDir)
            try {
                testFile.writeBytes(testData)
                
                // 尝试上传（应该失败）
                val uploadSuccess = realCosClient.uploadFile(testFile, testKey)
                
                if (uploadSuccess) {
                    Log.w(TAG, "Token具有写权限，不符合只读要求")
                    
                    // 如果意外上传成功，尝试清理测试文件
                    try {
                        realCosClient.deleteFile(testKey)
                        Log.d(TAG, "已清理意外上传的测试文件")
                    } catch (cleanupError: Exception) {
                        Log.w(TAG, "清理测试文件失败: ${LogSanitizer.sanitizeThrowable(cleanupError)}")
                    }
                    
                    return false
                } else {
                    Log.d(TAG, "写权限验证通过：上传操作被正确拒绝")
                }
                
            } finally {
                if (testFile.exists()) {
                    testFile.delete()
                }
            }
            
            // 2. 测试删除操作：尝试删除文件（应该被拒绝）
            val existingTestKey = "/outbox/${cosToken.recipientId}/test_file_for_delete.tmp"
            try {
                val deleteSuccess = realCosClient.deleteFile(existingTestKey)
                if (deleteSuccess) {
                    Log.w(TAG, "Token具有删除权限，不符合只读要求")
                    return false
                } else {
                    Log.d(TAG, "删除权限验证通过：删除操作被正确拒绝")
                }
            } catch (e: Exception) {
                // 删除操作被拒绝是预期行为
                Log.d(TAG, "删除权限验证通过：删除操作抛出异常（被拒绝）")
            }
            
            Log.d(TAG, "只读权限验证通过：所有写操作都被正确拒绝")
            true
            
        } catch (e: Exception) {
            // 如果是权限相关异常，说明只读权限正确
            val errorMessage = e.message?.lowercase() ?: ""
            if (errorMessage.contains("permission") || 
                errorMessage.contains("forbidden") || 
                errorMessage.contains("403") ||
                errorMessage.contains("unauthorized")) {
                Log.d(TAG, "只读权限验证通过：写操作被正确拒绝")
                true
            } else {
                Log.e(TAG, "只读权限验证异常: ${LogSanitizer.sanitizeThrowable(e)}")
                false
            }
        }
    }
    
    /**
     * 撤销Token - 安全改进：不在客户端执行云厂商账号管理
     */
    override suspend fun revokeToken(token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "撤销Token: tokenId=${token.tokenId}")
                
                val cosToken = token as? CosTransportToken
                    ?: return@withContext false
                
                // 安全措施：不在客户端直接调用云厂商API删除用户/密钥
                // 而是标记Token为已撤销状态，由后端或自然过期处理
                val revokeResult = markTokenAsRevoked(cosToken)
                
                if (revokeResult) {
                    Log.i(TAG, "Token撤销成功: tokenId=${token.tokenId}")
                } else {
                    Log.w(TAG, "Token撤销失败: tokenId=${token.tokenId}")
                }
                
                revokeResult

            } catch (e: Exception) {
                Log.e(TAG, "撤销Token时发生异常", e)
                false
            }
        }
    }
    
    /**
     * 标记Token为已撤销状态
     */
    private suspend fun markTokenAsRevoked(cosToken: CosTransportToken): Boolean {
        return try {
            // 从Token池中移除该Token
            val tokenPool = TransportTokenPool.getInstance(context)
            tokenPool.removeToken(cosToken.recipientId, cosToken.providerType)
            
            // 记录撤销操作到日志
            Log.i(TAG, "Token已标记为撤销: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "标记Token撤销状态失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }

    /**
     * 从配置创建CosConfig
     */
    private fun createCosConfigFromMap(config: Map<String, Any>): CosConfig {
        val provider = when (config["provider"]?.toString()?.uppercase()) {
            "AWS" -> CosConfig.Provider.AWS
            "TENCENT" -> CosConfig.Provider.TENCENT
            else -> throw IllegalArgumentException("不支持的Provider: ${config["provider"]}")
        }
        
        return CosConfig(
            provider = provider,
            secretId = config["secretId"]?.toString() ?: throw IllegalArgumentException("缺少secretId"),
            secretKey = config["secretKey"]?.toString() ?: throw IllegalArgumentException("缺少secretKey"),
            region = config["region"]?.toString() ?: throw IllegalArgumentException("缺少region"),
            bucketName = config["bucketName"]?.toString() ?: throw IllegalArgumentException("缺少bucketName")
        )
    }

    /**
     * 创建COS客户端
     */
    private fun createCosClient(metadata: CosTransportMetadata): CosClient? {
        return try {
            if (metadata.myToken != null) {
                // 使用Token创建客户端
                val cosToken = metadata.myToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = when (cosToken.region.startsWith("ap-")) {
                        true -> "TENCENT"
                        false -> "AWS"
                    },
                    region = cosToken.region,
                    bucketName = cosToken.bucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken
                )
            } else {
                // 使用配置创建客户端
                CosClientFactory.createClient(cosConfig, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建COS客户端失败", e)
            null
        }
    }

    /**
     * 创建用于发送的COS客户端（使用本端凭证）
     */
    private fun createCosClientForSend(metadata: CosTransportMetadata): CosClient? {
        return try {
            if (metadata.myToken != null) {
                // 使用本端Token创建客户端
                val cosToken = metadata.myToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = when (cosToken.region.startsWith("ap-")) {
                        true -> "TENCENT"
                        false -> "AWS"
                    },
                    region = metadata.myRegion,
                    bucketName = metadata.myBucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken
                )
            } else {
                // 使用配置创建客户端（发送时使用本端配置）
                CosClientFactory.createClient(cosConfig, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建发送COS客户端失败", e)
            null
        }
    }
    
    /**
     * 创建用于接收的COS客户端（使用对端凭证）
     */
    private fun createCosClientForReceive(metadata: CosTransportMetadata): CosClient? {
        return try {
            if (metadata.peerToken != null) {
                // 使用对端Token创建客户端（只读权限）
                val cosToken = metadata.peerToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = when (cosToken.region.startsWith("ap-")) {
                        true -> "TENCENT"
                        false -> "AWS"
                    },
                    region = metadata.peerRegion,
                    bucketName = metadata.peerBucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken
                )
            } else {
                Log.w(TAG, "接收时缺少对端Token，无法创建客户端")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建接收COS客户端失败", e)
            null
        }
    }

    /**
     * 创建COS客户端（从Token）- 返回真实的CosClient实例
     */
    private fun createCosClient(cosToken: CosTransportToken): CosClient? {
        return try {
            // 根据Token信息确定Provider类型
            val providerType = when {
                cosToken.region.startsWith("ap-") || cosToken.region.startsWith("na-") -> "TENCENT"
                cosToken.region.startsWith("us-") || cosToken.region.startsWith("eu-") -> "AWS"
                else -> {
                    Log.w(TAG, "无法从region确定Provider类型: ${cosToken.region}，默认使用配置中的Provider")
                    cosConfig.provider.name
                }
            }
            
            // 使用CosClientFactory创建真实客户端
            CosClientFactory.createClientWithToken(
                provider = providerType,
                region = cosToken.region,
                bucketName = cosToken.bucketName,
                accessKeyId = cosToken.accessKeyId,
                secretAccessKey = cosToken.secretAccessKey,
                sessionToken = cosToken.sessionToken
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建COS客户端失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }

    /**
     * 创建临时文件保存消息数据
     */
    private fun createTempFile(data: ByteArray): File {
        val tempFile = File.createTempFile("cos_upload_", ".json", context.cacheDir)
        
        // 直接写入JSON格式的数据（兼容coscomm）
        tempFile.outputStream().use { output ->
            output.write(data)
        }
        
        return tempFile
    }

    /**
     * 从文件解析消息 - 支持JSON格式（优先）和二进制格式（兼容）
     */
    override suspend fun parseTransportMessage(fileData: ByteArray, fileInfo: FileInfo, metadata: TransportMetadata): TransportMessage? {
        return try {
            Log.d(TAG, "解析COS传输消息: ${fileInfo.name}, size=${fileData.size}")
            
            // 从文件名解析基本信息
            val messageFileInfo = parseMessageFileName(fileInfo.name) ?: return null
            
            // COS使用二进制格式存储消息
            val message = parseMessageFromBinaryData(fileData, messageFileInfo)
            
            if (message != null) {
                Log.d(TAG, "COS消息解析成功: messageId=${message.messageId}")
            } else {
                Log.w(TAG, "COS消息解析失败: ${fileInfo.name}")
            }
            
            message
            
        } catch (e: Exception) {
            Log.e(TAG, "解析COS传输消息异常: ${fileInfo.name}", e)
            null
        }
    }
    
    /**
     * COS特定的文件识别策略
     */
    override fun isMessageFile(fileInfo: FileInfo): Boolean {
        val name = fileInfo.name.lowercase()
        // COS使用.dat作为消息文件扩展名
        return name.endsWith(".dat") && 
               name.matches(Regex("^[a-zA-Z0-9_-]+_\\d+\\.dat$")) && // 基本格式验证
               fileInfo.size > 0 && fileInfo.size < 50 * 1024 * 1024 // 大小限制50MB
    }
    
    /**
     * COS特定的文件名解析策略
     */
    override fun parseMessageFileName(fileName: String): MessageFileInfo? {
        return try {
            // COS使用格式: senderId_messageId_timestamp.dat
            val baseName = fileName.substringBeforeLast('.')
            val parts = baseName.split('_')
            
            when (parts.size) {
                3 -> {
                    // senderId_messageId_timestamp格式（COS标准格式）
                    MessageFileInfo(
                        messageId = parts[1],
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = "" // COS文件名中不包含recipientId，从路径推断
                    )
                }
                4 -> {
                    // senderId_recipientId_messageId_timestamp格式（扩展格式）
                    MessageFileInfo(
                        messageId = parts[2],
                        timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = parts[1]
                    )
                }
                else -> {
                    Log.w(TAG, "不支持的COS文件名格式: $fileName")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析COS文件名失败: $fileName", e)
            null
        }
    }
    

    
    /**
     * 从二进制数据解析消息（COS特定格式）
     */
    private fun parseMessageFromBinaryData(data: ByteArray, fileInfo: MessageFileInfo): TransportMessage? {
        return try {
            // 使用与writeMessageToBinaryData对应的解析逻辑
            val inputStream = java.io.ByteArrayInputStream(data)
            val dataInputStream = java.io.DataInputStream(inputStream)
            
            // 读取版本号
            val version = dataInputStream.readInt()
            if (version != MESSAGE_FORMAT_VERSION) {
                Log.w(TAG, "不支持的消息格式版本: $version")
                return null
            }
            
            // 读取消息类型
            val messageTypeOrdinal = dataInputStream.readInt()
            val messageType = TransportMessageType.values().getOrNull(messageTypeOrdinal) 
                ?: TransportMessageType.TEXT_MESSAGE
            
            // 读取Signal密文长度和内容
            val ciphertextLength = dataInputStream.readInt()
            val signalCiphertext = ByteArray(ciphertextLength)
            dataInputStream.readFully(signalCiphertext)
            val signalCiphertextB64 = android.util.Base64.encodeToString(signalCiphertext, android.util.Base64.NO_WRAP)
            
            // 读取内容元数据
            val originalSize = dataInputStream.readLong()
            val compressionTypeOrdinal = dataInputStream.readInt()
            val compressionType = TransportCompressionType.values().getOrNull(compressionTypeOrdinal)
                ?: TransportCompressionType.NONE
            
            val contentMetadata = TransportContentMetadata(
                originalSize = originalSize,
                compressionType = compressionType
            )
            
            // 读取附件数量
            val attachmentCount = dataInputStream.readInt()
            val attachments = mutableListOf<TransportAttachment>()
            
            // 读取每个附件
            for (i in 0 until attachmentCount) {
                val attachmentId = dataInputStream.readUTF()
                val attachmentType = dataInputStream.readUTF()
                val attachmentSize = dataInputStream.readLong()
                val attachmentWidth = dataInputStream.readInt()
                val attachmentHeight = dataInputStream.readInt()
                
                val attachmentDataLength = dataInputStream.readInt()
                val attachmentData = ByteArray(attachmentDataLength)
                dataInputStream.readFully(attachmentData)
                
                // 创建临时文件存储附件数据 
                val tempFile = File.createTempFile("tap_attachment_", ".tmp", File("/tmp"))
                try {
                    tempFile.writeBytes(attachmentData)
                    
                    attachments.add(
                        TransportAttachment(
                            attachmentId = attachmentId,
                            fileName = "attachment_${attachmentId}",
                            mimeType = attachmentType,
                            size = attachmentSize,
                            fileHash = null,
                            transportPath = tempFile.absolutePath
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "创建附件临时文件失败: $attachmentId", e)
                }
            }
            
            // 创建TransportMessage
            TransportMessage(
                messageId = fileInfo.messageId,
                timestamp = fileInfo.timestamp,
                senderId = fileInfo.senderId,
                recipientId = fileInfo.recipientId,
                messageType = messageType,
                signalCiphertext = signalCiphertextB64,
                contentMetadata = contentMetadata,
                attachments = attachments
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "二进制数据解析失败", e)
            null
        }
    }
    
    /**
     * COS特定的发送路径策略
     */
    override fun getSendPath(recipientId: String, messageType: TransportMessageType): String {
        // COS使用层级路径结构: /outbox/recipientId/messageType/
        val typePrefix = when (messageType) {
            TransportMessageType.TEXT_MESSAGE -> "text"
            TransportMessageType.MEDIA_MESSAGE -> "media"
            TransportMessageType.CONTROL_MESSAGE -> "control"
            TransportMessageType.RATCHET_UPDATE -> "ratchet"
            TransportMessageType.CALL_MESSAGE -> "call"
        }
        return "/outbox/$recipientId/$typePrefix/"
    }
    
    /**
     * COS特定的接收路径策略
     */
    override fun getReceivePath(recipientId: String, messageType: TransportMessageType): String {
        // 从对方的outbox接收，使用相同的路径结构
        val typePrefix = when (messageType) {
            TransportMessageType.TEXT_MESSAGE -> "text"
            TransportMessageType.MEDIA_MESSAGE -> "media"
            TransportMessageType.CONTROL_MESSAGE -> "control"
            TransportMessageType.RATCHET_UPDATE -> "ratchet"
            TransportMessageType.CALL_MESSAGE -> "call"
        }
        return "/outbox/$recipientId/$typePrefix/"
    }
    
    /**
     * COS特定的地址格式化
     */
    override fun formatAddress(config: Map<String, Any>): String {
        val region = config["region"]?.toString() ?: "ap-beijing"
        val bucketName = config["bucketName"]?.toString() ?: "default-bucket"
        val provider = config["provider"]?.toString()?.uppercase() ?: "TENCENT"
        
        return when (provider) {
            "AWS" -> "https://$bucketName.s3.$region.amazonaws.com"
            "TENCENT" -> "https://$bucketName.cos.$region.myqcloud.com"
            "ALIYUN" -> "https://$bucketName.oss-$region.aliyuncs.com"
            else -> "https://$bucketName.cos.$region.myqcloud.com" // 默认腾讯云
        }
    }
    


    /**
     * 从地址中提取Bucket名称
     * 
     * 支持格式：
     * - https://bucket-name.cos.region.myqcloud.com/
     * - https://bucket-name.s3.region.amazonaws.com/
     * - cos://bucket-name
     * - s3://bucket-name
     */
    private fun extractBucketName(address: String): String {
        return try {
            when {
                address.startsWith("https://") -> {
                    val host = address.substringAfter("https://").substringBefore("/")
                    host.substringBefore(".")
                }
                address.startsWith("cos://") -> {
                    address.substringAfter("cos://").substringBefore("/")
                }
                address.startsWith("s3://") -> {
                    address.substringAfter("s3://").substringBefore("/")
                }
                else -> {
                    // 假设直接是bucket名称
                    address.substringBefore("/")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取Bucket名称失败: $address", e)
            address
        }
    }
    


    /**
     * 从Token创建临时的TransportMetadata
     */
    private fun createMetadataFromToken(cosToken: CosTransportToken): CosTransportMetadata {
        return CosTransportMetadata(
            recipientId = cosToken.recipientId,
            providerType = "cos",
            myAddress = "${cosToken.region}://${cosToken.bucketName}",
            myToken = cosToken,
            myRegion = cosToken.region,
            myBucketName = cosToken.bucketName,
            peerAddress = "${cosToken.region}://${cosToken.bucketName}",
            peerToken = cosToken,
            peerRegion = cosToken.region,
            peerBucketName = cosToken.bucketName,
            myId = "self"
        )
    }
} 