package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosFileInfo
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosAccessToken
import org.thoughtcrime.securesms.tap.provider.cos.utils.auth.CosSubUserManagerFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.auth.CosPermission
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
        
        // 已移除硬编码配置，改为使用可配置的 CosProviderConfig
    }

    override val providerType: String = "cos"
    override val supportsAuth: Boolean = true
    override val supportsGroup: Boolean = true
    override val displayName: String = "云对象存储 (COS)"
    override val description: String = "支持AWS S3和腾讯云COS的云存储服务"
    override val maxMessageSize: Long get() = providerConfig.maxFileSize
    
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
    
    // Provider配置（可配置参数）
    private val providerConfig: CosProviderConfig by lazy {
        config["providerConfig"] as? CosProviderConfig ?: CosProviderConfig()
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
                        
                        // 使用Tap通用格式解析消息
                        val message = TransportMessage.deserialize(downloadResult.data)
                        if (message != null) {
                            Log.i(TAG, "消息拉取成功: messageId=${message.messageId}")
                            TransportResult.Success(message)
                        } else {
                            Log.w(TAG, "消息解析失败: ${latestFile.name}")
                            TransportResult.Failed(TransportError.INVALID_FORMAT, false, "消息解析失败")
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
                val groupPath = "${providerConfig.groupPathPrefix}${groupMetadata.groupId}${providerConfig.groupOutboxSuffix}"
                
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
                        val groupPath = "${providerConfig.groupPathPrefix}${groupMetadata.groupId}${providerConfig.groupOutboxSuffix}"
                        
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
                Log.i(TAG, "开始生成COS传输Token: recipientId=${request.recipientId}")
                
                // 1. 使用已有的COS配置
                val tapCosConfig = cosConfig
                
                // 2. 验证请求参数
                if (!request.validate()) {
                    Log.w(TAG, "Token请求参数无效: $request")
                    return@withContext null
                }
                
                // 3. 生成唯一的子用户标识
                val timestamp = System.currentTimeMillis()
                val randomSuffix = (1000..9999).random()
                val channelDirectoryName = "signal-v2-${timestamp}-${randomSuffix}"
                val channelDirectoryPath = "/v2-channels/$channelDirectoryName/"
                val subUserName = "signal-cos-$channelDirectoryName"
                
                Log.d(TAG, "生成子用户标识: userName=$subUserName, directoryPath=${channelDirectoryPath}outbox/")
                
                // 4. 创建COS客户端和子用户管理器
                val cosClient = CosClientFactory.createClient(tapCosConfig, context)
                
                val subUserManager = CosSubUserManagerFactory.createManager(cosConfig, context)
                
                // 5. 创建通道目录结构
                try {
                    Log.d(TAG, "创建COS v2通道目录结构: $channelDirectoryPath")
                    
                    // 创建主通道目录
                    cosClient.createDirectory(channelDirectoryPath)
                    
                    // 创建子目录结构
                    cosClient.createDirectory("${channelDirectoryPath}outbox/")           // 我发送给对方的消息
                    cosClient.createDirectory("${channelDirectoryPath}outbox/messages/")  // 消息文件
                    cosClient.createDirectory("${channelDirectoryPath}outbox/attachments/") // 附件文件
                    cosClient.createDirectory("${channelDirectoryPath}outbox/metadata/")  // 消息索引
                    cosClient.createDirectory("${channelDirectoryPath}inbox/")            // 对方发送给我的消息（本地使用）
                    cosClient.createDirectory("${channelDirectoryPath}metadata/")         // 通道元数据和状态信息
                    
                    Log.i(TAG, "COS v2通道目录结构创建成功")
                } catch (e: Exception) {
                    Log.e(TAG, "创建COS v2通道目录结构失败", e)
                    throw e
                }
                
                // 6. 创建子用户并分配权限
                val cosPermission = mapTransportPermissionToCosPermission(request.requestedPermissions)
                val subUserCredential = subUserManager.createSubUser(
                    userName = subUserName,
                    directoryPath = "${channelDirectoryPath}outbox", // 允许对方访问我的outbox目录及其子目录
                    permissions = cosPermission
                )
                
                Log.i(TAG, "子用户创建成功: userName=${LogSanitizer.sanitize(subUserName)}, accessKeyId=${LogSanitizer.sanitize(subUserCredential.accessKeyId, "accessKeyId")}")
                
                // 7. 计算过期时间
                val expirationTime = if (request.validityDurationMs > 0L) {
                    System.currentTimeMillis() + request.validityDurationMs
                } else {
                    // 按照设计要求，使用长期有效的Token
                    Long.MAX_VALUE
                }
                
                // 8. 创建TransportToken
                val token = org.thoughtcrime.securesms.tap.CosTransportToken(
                    tokenId = "cos-${subUserCredential.userName}-${timestamp}",
                    recipientId = request.recipientId,
                    permissions = request.requestedPermissions,
                    expirationTime = expirationTime,
                    accessKeyId = subUserCredential.accessKeyId,
                    secretAccessKey = subUserCredential.secretAccessKey,
                    sessionToken = null, // 永久凭证不需要sessionToken
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName
                )
                
                Log.i(TAG, "COS传输Token生成成功: tokenId=${LogSanitizer.sanitize(token.tokenId)}, recipientId=${LogSanitizer.sanitize(request.recipientId)}")
                token
                
            } catch (e: Exception) {
                Log.e(TAG, "生成COS传输Token失败: recipientId=${LogSanitizer.sanitize(request.recipientId)}, 错误: ${LogSanitizer.sanitizeThrowable(e)}")
                null
            }
        }
    }
    
    
    /**
     * 将TransportPermission映射到CosPermission
     */
    private fun mapTransportPermissionToCosPermission(permissions: Set<TransportPermission>): CosPermission {
        return when {
            permissions.contains(TransportPermission.WRITE) -> CosPermission.READ_WRITE
            permissions.contains(TransportPermission.READ) -> CosPermission.READ_ONLY
            else -> CosPermission.READ_ONLY // 默认只读权限
        }
    }

    /**
     * 验证Token有效性和权限范围
     * 
     * 对于长期最高权限Token，验证：
     * 1. 凭证是否有效（能否访问服务）
     * 2. 基本连通性测试（能否连接到存储服务）
     * 3. 权限范围测试（验证完整的读写权限）
     * 4. 路径访问权限（验证能够访问指定的路径前缀）
     */
    override suspend fun validateToken(token: TransportToken): Boolean {
        return try {
            val cosToken = token as? CosTransportToken ?: return false
            Log.d(TAG, "开始验证长期最高权限Token: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
            
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
            
            // 2. 验证完整的读写权限（长期最高权限应具备所有操作能力）
            val fullPermissionsValid = validateFullPermissions(cosClient, cosToken)
            if (!fullPermissionsValid) {
                Log.w(TAG, "完整权限验证失败")
                return false
            }
            
            // 3. 路径访问权限验证：测试能够访问预期的路径前缀
            val pathPermission = validatePathPermissions(cosClient, cosToken)
            if (!pathPermission) {
                Log.w(TAG, "路径权限验证失败")
                return false
            }
            
            // 4. 验证Token的有效期（长期Token应该没有过期或有很长的有效期）
            val expirationValid = validateTokenExpiration(cosToken)
            if (!expirationValid) {
                Log.w(TAG, "Token过期验证失败")
                return false
            }
            
            Log.d(TAG, "长期最高权限Token验证成功: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
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
     * 验证完整权限（读、写、删除、列举）
     */
    private suspend fun validateFullPermissions(cosClient: Any, cosToken: CosTransportToken): Boolean {
        return try {
            Log.d(TAG, "开始验证完整权限")
            
            // 获取真实的COS客户端
            val realCosClient = getRealCosClient(cosClient, cosToken)
            if (realCosClient == null) {
                Log.w(TAG, "无法获取真实的COS客户端进行权限验证")
                return false
            }
            
            // 创建测试路径（使用专门的测试目录）
            val testPrefix = "/test_permissions/${cosToken.recipientId}/"
            val testKey = "${testPrefix}token_validation_${System.currentTimeMillis()}.test"
            val testData = "Token validation test - ${System.currentTimeMillis()}".toByteArray()
            
            // 创建临时测试文件
            val testFile = File.createTempFile("token_validation", ".test", context.cacheDir)
            try {
                testFile.writeBytes(testData)
                
                // 1. 测试写权限：上传文件
                val uploadSuccess = when (cosConfig.provider) {
                    CosConfig.Provider.TENCENT -> testTencentUpload(realCosClient, testFile, testKey)
                    CosConfig.Provider.AWS -> testAwsUpload(realCosClient, testFile, testKey)
                    else -> false
                }
                
                if (!uploadSuccess) {
                    Log.w(TAG, "写权限测试失败：无法上传测试文件")
                    return false
                }
                Log.d(TAG, "写权限验证通过")
                
                // 2. 测试读权限：下载文件并验证内容
                val downloadFile = File.createTempFile("token_download", ".test", context.cacheDir)
                try {
                    val downloadSuccess = when (cosConfig.provider) {
                        CosConfig.Provider.TENCENT -> testTencentDownload(realCosClient, testKey, downloadFile)
                        CosConfig.Provider.AWS -> testAwsDownload(realCosClient, testKey, downloadFile)
                        else -> false
                    }
                    
                    if (!downloadSuccess) {
                        Log.w(TAG, "读权限测试失败：无法下载测试文件")
                        return false
                    }
                    
                    // 验证下载内容
                    val downloadedData = downloadFile.readBytes()
                    if (!downloadedData.contentEquals(testData)) {
                        Log.w(TAG, "读权限测试失败：下载内容不匹配")
                        return false
                    }
                    Log.d(TAG, "读权限验证通过")
                    
                } finally {
                    if (downloadFile.exists()) {
                        downloadFile.delete()
                    }
                }
                
                // 3. 测试列举权限：列出文件
                val listResult = when (cosConfig.provider) {
                    CosConfig.Provider.TENCENT -> testTencentListFiles(realCosClient, testPrefix)
                    CosConfig.Provider.AWS -> testAwsListFiles(realCosClient, testPrefix)
                    else -> emptyList()
                }
                
                if (listResult.isEmpty()) {
                    Log.w(TAG, "列举权限测试失败：无法列出文件")
                    return false
                }
                
                val foundTestFile = listResult.any { it.contains(testKey.substringAfterLast("/")) }
                if (!foundTestFile) {
                    Log.w(TAG, "列举权限测试失败：未能找到测试文件")
                    return false
                }
                Log.d(TAG, "列举权限验证通过")
                
                // 4. 测试删除权限：删除测试文件
                val deleteSuccess = when (cosConfig.provider) {
                    CosConfig.Provider.TENCENT -> testTencentDelete(realCosClient, testKey)
                    CosConfig.Provider.AWS -> testAwsDelete(realCosClient, testKey)
                    else -> false
                }
                
                if (!deleteSuccess) {
                    Log.w(TAG, "删除权限测试失败：无法删除测试文件")
                    return false
                }
                Log.d(TAG, "删除权限验证通过")
                
                // 验证文件确实被删除
                val listAfterDelete = when (cosConfig.provider) {
                    CosConfig.Provider.TENCENT -> testTencentListFiles(realCosClient, testPrefix)
                    CosConfig.Provider.AWS -> testAwsListFiles(realCosClient, testPrefix)
                    else -> emptyList()
                }
                
                val fileStillExists = listAfterDelete.any { it.contains(testKey.substringAfterLast("/")) }
                if (fileStillExists) {
                    Log.w(TAG, "删除权限测试失败：文件删除后仍然存在")
                    return false
                }
                
                Log.i(TAG, "完整权限验证成功：读、写、列举、删除权限均可用")
                return true
                
            } finally {
                if (testFile.exists()) {
                    testFile.delete()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "完整权限验证异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试腾讯云COS上传
     */
    private suspend fun testTencentUpload(cosClient: Any, file: File, key: String): Boolean {
        return try {
            // 使用反射调用上传方法（避免直接类型依赖）
            val uploadMethod = cosClient.javaClass.getMethod("uploadFile", File::class.java, String::class.java)
            val result = uploadMethod.invoke(cosClient, file, key) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS上传测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3上传
     */
    private suspend fun testAwsUpload(cosClient: Any, file: File, key: String): Boolean {
        return try {
            // 使用反射调用上传方法
            val uploadMethod = cosClient.javaClass.getMethod("uploadFile", File::class.java, String::class.java)
            val result = uploadMethod.invoke(cosClient, file, key) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3上传测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试腾讯云COS下载
     */
    private suspend fun testTencentDownload(cosClient: Any, key: String, file: File): Boolean {
        return try {
            val downloadMethod = cosClient.javaClass.getMethod("downloadFile", String::class.java, File::class.java)
            val result = downloadMethod.invoke(cosClient, key, file) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS下载测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3下载
     */
    private suspend fun testAwsDownload(cosClient: Any, key: String, file: File): Boolean {
        return try {
            val downloadMethod = cosClient.javaClass.getMethod("downloadFile", String::class.java, File::class.java)
            val result = downloadMethod.invoke(cosClient, key, file) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3下载测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试腾讯云COS列举文件
     */
    private suspend fun testTencentListFiles(cosClient: Any, prefix: String): List<String> {
        return try {
            val listMethod = cosClient.javaClass.getMethod("listFiles", String::class.java)
            val result = listMethod.invoke(cosClient, prefix) as List<String>
            result
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS列举测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptyList()
        }
    }
    
    /**
     * 测试AWS S3列举文件
     */
    private suspend fun testAwsListFiles(cosClient: Any, prefix: String): List<String> {
        return try {
            val listMethod = cosClient.javaClass.getMethod("listFiles", String::class.java)
            val result = listMethod.invoke(cosClient, prefix) as List<String>
            result
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3列举测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptyList()
        }
    }
    
    /**
     * 测试腾讯云COS删除文件
     */
    private suspend fun testTencentDelete(cosClient: Any, key: String): Boolean {
        return try {
            val deleteMethod = cosClient.javaClass.getMethod("deleteFile", String::class.java)
            val result = deleteMethod.invoke(cosClient, key) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS删除测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3删除文件
     */
    private suspend fun testAwsDelete(cosClient: Any, key: String): Boolean {
        return try {
            val deleteMethod = cosClient.javaClass.getMethod("deleteFile", String::class.java)
            val result = deleteMethod.invoke(cosClient, key) as Boolean
            result
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3删除测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 验证Token有效期
     */
    private fun validateTokenExpiration(cosToken: CosTransportToken): Boolean {
        return try {
            val currentTime = System.currentTimeMillis()
            val expirationTime = cosToken.expirationTime
            
            if (expirationTime != null && expirationTime > 0) {
                // 检查是否过期
                if (currentTime >= expirationTime) {
                    Log.w(TAG, "Token已过期: currentTime=$currentTime, expirationTime=$expirationTime")
                    return false
                }
                
                // 检查剩余有效期（长期Token应该有足够长的有效期）
                val remainingTime = expirationTime - currentTime
                val minimumValidTime = 7 * 24 * 60 * 60 * 1000L // 7天
                
                if (remainingTime < minimumValidTime) {
                    Log.w(TAG, "Token剩余有效期过短: remainingDays=${remainingTime / (24 * 60 * 60 * 1000L)}")
                    return false
                }
                
                Log.d(TAG, "Token有效期验证通过: 剩余${remainingTime / (24 * 60 * 60 * 1000L)}天")
            } else {
                // 无限期Token
                Log.d(TAG, "Token无过期时间限制")
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Token有效期验证异常: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    override suspend fun revokeToken(token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始撤销COS传输Token: tokenId=${LogSanitizer.sanitize(token.tokenId)}")
                
                if (token !is CosTransportToken) {
                    Log.w(TAG, "Token类型不匹配，无法撤销: ${token::class.simpleName}")
                    return@withContext false
                }
                
                // 1. 使用已有的COS配置
                
                // 2. 从tokenId中提取子用户名
                val subUserName = extractSubUserNameFromTokenId(token.tokenId)
                if (subUserName == null) {
                    Log.w(TAG, "无法从tokenId中提取子用户名: ${LogSanitizer.sanitize(token.tokenId)}")
                    return@withContext false
                }
                
                // 3. 创建子用户管理器
                val subUserManager = CosSubUserManagerFactory.createManager(cosConfig, context)
                
                // 4. 删除子用户（这会自动撤销所有相关权限和访问密钥）
                val success = subUserManager.deleteSubUser(subUserName)
                
                if (success) {
                    Log.i(TAG, "COS传输Token撤销成功: tokenId=${token.tokenId}, subUser=$subUserName")
                } else {
                    Log.w(TAG, "COS传输Token撤销失败: tokenId=${token.tokenId}, subUser=$subUserName")
                }
                
                success
                
            } catch (e: Exception) {
                Log.e(TAG, "撤销COS传输Token时发生异常: tokenId=${token.tokenId}", e)
                false
            }
        }
    }
    
    /**
     * 从tokenId中提取子用户名
     */
    private fun extractSubUserNameFromTokenId(tokenId: String): String? {
        return try {
            // tokenId格式: cos-signal-cos-signal-v2-{timestamp}-{randomSuffix}-{timestamp}
            // 需要提取: signal-cos-signal-v2-{timestamp}-{randomSuffix}
            if (tokenId.startsWith("cos-")) {
                val parts = tokenId.split("-")
                if (parts.size >= 6) {
                    // 重构子用户名: signal-cos-signal-v2-{timestamp}-{randomSuffix}
                    val userName = parts.drop(1).dropLast(1).joinToString("-")
                    Log.d(TAG, "提取子用户名: $userName from tokenId: $tokenId")
                    userName
                } else {
                    Log.w(TAG, "tokenId格式不正确: $tokenId")
                    null
                }
            } else {
                Log.w(TAG, "tokenId不是COS格式: $tokenId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取子用户名失败: tokenId=$tokenId", e)
            null
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
        val tempFile = File.createTempFile("cos_upload_", ".dat", context.cacheDir)
        
        // 写入Tap通用格式的消息数据（与远端文件扩展名保持一致）
        tempFile.outputStream().use { output ->
            output.write(data)
        }
        
        return tempFile
    }

    /**
     * 从文件解析消息 - 使用Tap通用格式
     */
    override suspend fun parseTransportMessage(fileData: ByteArray, fileInfo: FileInfo, metadata: TransportMetadata): TransportMessage? {
        return try {
            Log.d(TAG, "解析COS传输消息: ${fileInfo.name}, size=${fileData.size}")
            
            // 使用Tap通用格式反序列化
            val message = TransportMessage.deserialize(fileData)
            
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

    /**
     * 获取真实的COS客户端（类型安全）
     */
    private fun getRealCosClient(cosClient: Any, cosToken: CosTransportToken): Any? {
        return try {
            // 确保客户端类型正确
            when (cosConfig.provider) {
                CosConfig.Provider.TENCENT -> {
                    // 对于腾讯云COS，确保客户端配置正确
                    if (cosClient.javaClass.simpleName.contains("TencentCos", ignoreCase = true)) {
                        cosClient
                    } else {
                        Log.w(TAG, "COS客户端类型不匹配腾讯云配置")
                        null
                    }
                }
                CosConfig.Provider.AWS -> {
                    // 对于AWS S3，确保客户端配置正确
                    if (cosClient.javaClass.simpleName.contains("S3", ignoreCase = true) ||
                        cosClient.javaClass.simpleName.contains("Aws", ignoreCase = true)) {
                        cosClient
                    } else {
                        Log.w(TAG, "COS客户端类型不匹配AWS配置")
                        null
                    }
                }
                else -> {
                    Log.w(TAG, "不支持的COS Provider: ${cosConfig.provider}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取真实COS客户端失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
} 