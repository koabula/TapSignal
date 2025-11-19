package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.notification.TapLambdaDispatcher
import org.thoughtcrime.securesms.tap.provider.cos.notification.TapLambdaDispatcherFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosFileInfo
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosAccessToken
import org.thoughtcrime.securesms.tap.provider.cos.utils.auth.CosSubUserManagerFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.auth.CosPermission
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.GroupTransportManager.GroupTransportMetadata
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.tap.notification.*
import org.thoughtcrime.securesms.tap.provider.cos.utils.notification.*
import org.thoughtcrime.securesms.tap.provider.cos.utils.PresignedUrlGenerator
import org.thoughtcrime.securesms.tap.provider.cos.utils.S3CompatiblePresignedUrlGenerator
import org.thoughtcrime.securesms.tap.provider.cos.notification.LambdaDispatchResult
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
        private const val PREF_NAME = "cos_transport_provider"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val OFFLINE_PREFIX = "tap-offline/"
        
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
    
    // 推送通知相关组件
    private val notificationManager: NotificationManager by lazy {
        NotificationManager.getInstance(context)
    }
    private val notificationPrefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    private var notificationProvider: NotificationProvider? = null
    private var contactWebhookManager: ContactWebhookManager? = null
    private var eventTriggerConfigurator: CosEventTriggerConfigurator? = null
    private var cloudFunctionDeployer: CloudFunctionDeployer? = null
    private var notificationEnabled: Boolean = false
        get() {
            if (!field && notificationPrefs.getBoolean(KEY_NOTIFICATION_ENABLED, false)) {
                field = true
            }
            return field
        }
        set(value) {
            field = value
            notificationPrefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()
        }

    private val presignedUrlGenerator: PresignedUrlGenerator by lazy {
        S3CompatiblePresignedUrlGenerator(cosConfig)
    }

    @Volatile
    private var lambdaDispatcher: TapLambdaDispatcher? = null

    /**
     * 推送消息到COS
     */
    override suspend fun push(message: TransportMessage, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始推送消息: messageId=${message.messageId}, recipientId=${metadata.recipientId}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 检查消息大小
                if (isMessageTooLarge(message)) {
                    return@withContext TransportResult.failure(
                        TransportError.MESSAGE_TOO_LARGE,
                        false,
                        "消息大小超过限制: ${maxMessageSize}字节"
                    )
                }

                val dispatcher = getLambdaDispatcher()
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "推送服务未初始化"
                    )

                val payload = buildLambdaPayload(message, cosMetadata)
                val traceId = payload.optString("traceId", "")
                
                // 指数退避重试: 最多3次, 间隔 1s, 2s, 4s
                var dispatchResult: LambdaDispatchResult? = null
                var lastError: String? = null
                val maxRetries = 3
                
                for (attempt in 1..maxRetries) {
                    try {
                        dispatchResult = dispatcher.dispatch(payload)
                        
                        if (dispatchResult.success) {
                            if (attempt > 1) {
                                Log.i(TAG, "Lambda推送重试成功: messageId=${message.messageId}, attempt=$attempt, traceId=$traceId")
                            } else {
                                Log.i(TAG, "Lambda推送成功: messageId=${message.messageId}, traceId=$traceId")
                            }
                            break
                        } else {
                            lastError = dispatchResult.errorMessage ?: "未知错误"
                            Log.w(TAG, "Lambda推送失败 (attempt $attempt/$maxRetries): $lastError, traceId=$traceId")
                            
                            if (attempt < maxRetries) {
                                val delayMs = (1L shl (attempt - 1)) * 1000L // 1s, 2s, 4s
                                kotlinx.coroutines.delay(delayMs)
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "调用异常"
                        Log.w(TAG, "Lambda推送异常 (attempt $attempt/$maxRetries): $lastError, traceId=$traceId", e)
                        
                        if (attempt < maxRetries) {
                            val delayMs = (1L shl (attempt - 1)) * 1000L
                            kotlinx.coroutines.delay(delayMs)
                        } else {
                            throw e
                        }
                    }
                }

                if (dispatchResult?.success == true) {
                    TransportResult.Success(
                        metadata = mapOf(
                            "providerType" to providerType,
                            "deliveryChannel" to "lambda-websocket",
                            "traceId" to traceId,
                            "lambdaStatus" to (dispatchResult.statusCode ?: 200)
                        )
                    )
                } else {
                    Log.e(TAG, "Lambda推送最终失败: messageId=${message.messageId}, traceId=$traceId, error=$lastError")
                    TransportResult.failure(
                        TransportError.NETWORK_ERROR,
                        true,
                        lastError ?: "Lambda推送失败"
                    )
                }

            } catch (e: Exception) {
                Log.  e(TAG, "推送消息时发生异常: messageId=${LogSanitizer.sanitize(message.messageId, "messageId")}, recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
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
                
                // 轮询messages和attachments目录查找最新文件
                val basePath = metadata.getReceiveMetadata().path
                val pollingPaths = listOf("${basePath}messages/", "${basePath}attachments/")
                
                val allFiles = mutableListOf<FileInfo>()
                for (path in pollingPaths) {
                    val listResult = listFiles(path, metadata)
                    if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                        allFiles.addAll(listResult.files)
                    }
                }
                
                if (allFiles.isEmpty()) {
                    return@withContext TransportResult.Success(null)
                }

                // 获取最新的文件（保持兼容性）
                val latestFile = allFiles.maxByOrNull { it.lastModified }
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
                            TransportResult.failure(TransportError.INVALID_FORMAT, false, "消息解析失败")
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
                Log.e(TAG, "拉取消息时发生异常: recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
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
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
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
                        name = cosFile.name.substringAfterLast('/'), // 只取文件名部分
                        path = cosFile.name, // cosFile.name已经是完整的对象key路径，无需拼接
                        size = cosFile.size,
                        lastModified = cosFile.lastModified,
                        etag = null, // CosFileInfo中暂无etag字段，保持null
                        mimeType = "application/octet-stream"
                    )
                }.filter { it.isMessageFile() } // 只返回消息文件
                
                Log.d(TAG, "找到文件数量: ${fileInfos.size}")
                TransportResult.success(fileInfos)

            } catch (e: Exception) {
                Log.e(TAG, "列举文件时发生异常: path=${LogSanitizer.sanitize(path, "path")}, recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
                TransportResult.fromException(e, true)
            }
        }
    }
    
    /**
     * 列出指定路径下的文件（支持增量查询）
     * 
     * 使用marker机制实现增量查询，显著减少网络传输和处理时间
     */
    override suspend fun listFilesWithMarker(
        path: String,
        metadata: TransportMetadata,
        marker: String?,
        maxKeys: Int
    ): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "增量列举文件: path=$path, marker=${marker ?: "null"}, recipientId=${metadata.recipientId}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
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

                // 使用marker进行增量列举
                val listResult = cosClient.listFilesWithMarker(path, marker, maxKeys)
                
                // 转换为FileInfo列表
                val fileInfos = listResult.files.map { cosFile ->
                    FileInfo(
                        name = cosFile.name.substringAfterLast('/'), // 只取文件名部分
                        path = cosFile.name, // cosFile.name已经是完整的对象key路径，无需拼接
                        size = cosFile.size,
                        lastModified = cosFile.lastModified,
                        etag = null, // CosFileInfo中暂无etag字段，保持null
                        mimeType = "application/octet-stream"
                    )
                }.filter { it.isMessageFile() } // 只返回消息文件
                
                Log.d(TAG, "增量查询找到文件数量: ${fileInfos.size}, nextMarker=${listResult.nextMarker ?: "null"}")
                
                // 返回包含marker信息的结果
                TransportResult.success(
                    files = fileInfos,
                    metadata = mapOf(
                        "nextMarker" to (listResult.nextMarker ?: ""),
                        "isTruncated" to listResult.isTruncated,
                        "hasMore" to (listResult.nextMarker != null)
                    )
                )

            } catch (e: Exception) {
                Log.e(TAG, "增量列举文件时发生异常: path=${LogSanitizer.sanitize(path, "path")}, marker=${marker?.let { LogSanitizer.sanitize(it, "marker") } ?: "null"}, recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
                TransportResult.fromException(e, true)
            }
        }
    }

    /**
     * 下载指定文件（优化版本：直接下载到内存，避免临时文件I/O）
     */
    override suspend fun downloadFile(fileInfo: FileInfo, metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "下载文件: ${fileInfo.name}, size=${fileInfo.size}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 创建COS客户端（使用对端配置进行接收）
                val cosClient = createCosClientForReceive(cosMetadata)
                    ?: return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建接收COS客户端"
                    )

                // 优化：直接下载到内存，跳过临时文件I/O
                val data = cosClient.downloadFileToMemory(fileInfo.path)
                
                if (data != null && data.isNotEmpty()) {
                    Log.d(TAG, "文件下载成功: ${fileInfo.name}, 大小=${data.size} bytes")
                    TransportResult.success(data)
                } else {
                    Log.e(TAG, "文件下载失败: ${fileInfo.name}")
                    TransportResult.failure(
                        TransportError.NETWORK_ERROR,
                        true,
                        "文件下载失败"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载文件时发生异常: fileName=${LogSanitizer.sanitize(fileInfo.name, "fileName")}, size=${fileInfo.size}, recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
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
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
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
                        val presignedResult = if (shouldGeneratePresignedUrl(path)) {
                            presignedUrlGenerator.generate(path.removePrefix("/"))
                        } else {
                            null
                        }

                        val metadataMap = mutableMapOf<String, Any>(
                            "remotePath" to path,
                            "uploadTime" to System.currentTimeMillis(),
                            "fileSize" to data.size
                        )
                        presignedResult?.let {
                            metadataMap["presignedUrl"] = it.url
                            metadataMap["presignedExpiresAt"] = it.expiresAtEpochMillis
                            metadataMap["presignedExpiresIn"] = it.expiresInSeconds
                        }

                        Log.d(TAG, "文件上传成功: $path")
                        TransportResult.Success(metadata = metadataMap)
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
                Log.e(TAG, "上传文件时发生异常: path=${LogSanitizer.sanitize(path, "path")}, size=${data.size}, recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
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
                
                // 构造群组专用路径（简化路径：/group/{groupId}/）
                val groupPath = "${providerConfig.groupPathPrefix}${groupMetadata.groupId}/"
                
                // 使用自己的COS配置创建元数据
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
                val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
                val myAddress = formatAddress(mapOf(
                    "region" to cosConfig.region,
                    "bucketName" to cosConfig.bucketName,
                    "provider" to cosConfig.provider.name
                ))
                val groupSendPath = getSendPath("group", TransportMessageType.TEXT_MESSAGE)
                val groupReceivePath = getReceivePath(myHashedId, TransportMessageType.TEXT_MESSAGE)
                
                val cosMetadata = org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
                    recipientId = myAci.toString(), // 上传到自己的COS
                    providerType = "cos",
                    myAddress = myAddress,
                    myToken = null, // 使用自己的凭证
                    myRegion = cosConfig.region,
                    myBucketName = cosConfig.bucketName,
                    mySendPath = groupSendPath,
                    peerAddress = myAddress, // 群组消息，对端就是自己
                    peerToken = null,
                    peerRegion = cosConfig.region,
                    peerBucketName = cosConfig.bucketName,
                    peerReceivePath = groupReceivePath,
                    myHashedId = myHashedId,
                    peerHashedId = myHashedId
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
                        Log.d(TAG, "处理群组成员: ${memberMetadata.recipientId}")
                        
                        // 将通用TransportMetadata转换为CosTransportMetadata
                        val cosMetadata = convertToCosTransportMetadata(memberMetadata, groupMetadata.groupId)
                        if (cosMetadata == null) {
                            Log.w(TAG, "无法转换群组成员的传输元数据: ${memberMetadata.recipientId}")
                            results.add(TransportResult.failure(
                                TransportError.INVALID_FORMAT,
                                false,
                                "无法转换群组成员的传输元数据"
                            ))
                            continue
                        }
                        
                        // 构造群组消息路径（简化路径：/group/{groupId}/）
                        val groupPath = "${providerConfig.groupPathPrefix}${groupMetadata.groupId}/"
                        
                        Log.d(TAG, "从群组成员拉取消息: ${memberMetadata.recipientId}, 路径: $groupPath")
                        
                        // 列举群组目录中的文件
                        val listResult = listFiles(groupPath, cosMetadata)
                        
                        if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                            Log.d(TAG, "找到群组消息文件数量: ${listResult.files.size}, 来自成员: ${memberMetadata.recipientId}")
                            
                            // 下载并解析每个消息文件
                            for (fileInfo in listResult.files) {
                                try {
                                    val downloadResult = downloadFile(fileInfo, cosMetadata)
                                    if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                                        // 解析消息
                                        val message = parseTransportMessage(downloadResult.data, fileInfo, cosMetadata)
                                        if (message != null) {
                                            Log.d(TAG, "成功解析群组消息: ${message.messageId}, 来自: ${memberMetadata.recipientId}")
                                            results.add(TransportResult.Success(message, mapOf(
                                                "groupId" to groupMetadata.groupId,
                                                "memberId" to memberMetadata.recipientId,
                                                "filePath" to fileInfo.path
                                            )))
                                        } else {
                                            Log.w(TAG, "群组消息解析失败: ${fileInfo.name}")
                                            results.add(TransportResult.failure(
                                                TransportError.INVALID_FORMAT,
                                                false,
                                                "群组消息解析失败"
                                            ))
                                        }
                                    } else {
                                        Log.w(TAG, "下载群组消息文件失败: ${fileInfo.name}")
                                        results.add(TransportResult.failure(
                                            TransportError.NETWORK_ERROR,
                                            true,
                                            "下载群组消息文件失败"
                                        ))
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "处理群组消息文件失败: ${fileInfo.name}", e)
                                    results.add(TransportResult.fromException(e, true))
                                }
                            }
                        } else {
                            Log.d(TAG, "群组成员无新消息: ${memberMetadata.recipientId}")
                            // 无消息不算错误，只是记录日志
                        }
                        
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
     * 生成访问Token - 简化版，不再创建IAM子用户
     * 
     * 在新的Lambda+Gateway架构下，不再需要为每个会话创建独立的IAM子用户。
     * Token仅作为会话标识和逻辑隔离使用。
     */
    override suspend fun generateToken(request: TransportTokenRequest): TransportToken? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始生成COS传输Token (V2 Mode): recipientId=${request.recipientId}")
                
                // 1. 验证请求参数
                if (!request.validate()) {
                    Log.w(TAG, "Token请求参数无效: $request")
                    return@withContext null
                }
                
                // 2. 生成目录名（添加时间戳避免复用旧目录）
                val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
                val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
                val timestamp = System.currentTimeMillis()
                val channelDirectoryName = "${myHashedId}_${timestamp}"
                val channelDirectoryPath = "/v2-channels/$channelDirectoryName/"
                
                // 3. 计算过期时间
                val expirationTime = if (request.validityDurationMs > 0L) {
                    System.currentTimeMillis() + request.validityDurationMs
                } else {
                    Long.MAX_VALUE
                }
                
                // 4. 创建TransportToken（使用占位符凭证）
                // 在新架构中，实际的数据传输通过Lambda/Gateway进行，不需要S3凭证
                val token = org.thoughtcrime.securesms.tap.CosTransportToken(
                    tokenId = "cos-v2-${timestamp}",
                    recipientId = request.recipientId,
                    permissions = request.requestedPermissions,
                    expirationTime = expirationTime,
                    accessKeyId = "PLACEHOLDER_KEY", // 不再创建真实IAM用户
                    secretAccessKey = "PLACEHOLDER_SECRET",
                    sessionToken = null,
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName,
                    cloudProvider = cosConfig.provider.name,
                    channelPath = "${channelDirectoryPath}outbox/"
                )
                
                Log.i(TAG, "COS传输Token生成成功 (无IAM): tokenId=${LogSanitizer.sanitize(token.tokenId)}")
                token
                
            } catch (e: Exception) {
                Log.e(TAG, "生成COS传输Token失败: recipientId=${LogSanitizer.sanitize(request.recipientId)}, 错误: ${LogSanitizer.sanitizeThrowable(e)}")
                null
            }
        }
    }
    
    /**
     * 为群组生成Token - 简化版，不再创建IAM子用户
     */
    override suspend fun generateGroupToken(groupId: String, request: TransportTokenRequest): TransportToken? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始生成群组Token (V2 Mode): groupId=${LogSanitizer.sanitize(groupId)}")
                
                // 1. 验证请求参数
                if (!request.validate()) {
                    Log.w(TAG, "Token请求参数无效: $request")
                    return@withContext null
                }
                
                // 2. 构建群组目录路径
                val groupDirectoryPath = "${providerConfig.groupPathPrefix}${groupId}/"
                val timestamp = System.currentTimeMillis()
                
                // 3. 计算过期时间
                val expirationTime = if (request.validityDurationMs > 0L) {
                    System.currentTimeMillis() + request.validityDurationMs
                } else {
                    Long.MAX_VALUE
                }
                
                // 4. 创建群组TransportToken（使用占位符凭证）
                val token = org.thoughtcrime.securesms.tap.CosTransportToken(
                    tokenId = "cos-group-${groupId}-${timestamp}",
                    recipientId = groupId,
                    permissions = request.requestedPermissions,
                    expirationTime = expirationTime,
                    accessKeyId = "PLACEHOLDER_KEY",
                    secretAccessKey = "PLACEHOLDER_SECRET",
                    sessionToken = null,
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName,
                    cloudProvider = cosConfig.provider.name
                )
                
                Log.i(TAG, "群组Token生成成功 (无IAM): tokenId=${LogSanitizer.sanitize(token.tokenId)}")
                token
                
            } catch (e: Exception) {
                Log.e(TAG, "生成群组Token失败: groupId=${LogSanitizer.sanitize(groupId)}, 错误: ${LogSanitizer.sanitizeThrowable(e)}")
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
     */
    override suspend fun validateToken(token: TransportToken): Boolean {
        return try {
            val cosToken = token as? CosTransportToken ?: return false
            Log.d(TAG, "开始验证Token: recipientId=${LogSanitizer.sanitize(cosToken.recipientId)}")
            
            // 检查是否为占位符Token
            if (cosToken.accessKeyId == "PLACEHOLDER_KEY") {
                Log.d(TAG, "验证占位符Token: 视为有效 (V2 Mode)")
                return true
            }
            
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
    private suspend fun testBucketConnectivity(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
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
    private suspend fun testTencentBucketConnectivity(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
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
    private suspend fun testAwsBucketConnectivity(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
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
    private suspend fun validatePathPermissions(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
        return try {
            // 获取真实的COS客户端
            val realCosClient = createCosClient(createMetadataFromToken(cosToken))
            
            if (realCosClient == null) {
                Log.w(TAG, "无法创建COS客户端进行权限验证")
                return false
            }
            
            // 1. 测试允许的路径：应该能访问v2-channels结构中的outbox目录
            // 从Token中提取实际的通道目录名
            val channelPattern = "signal-v2-"
            val tokenParts = cosToken.tokenId.split("-")
            val channelName = if (tokenParts.size >= 4 && tokenParts[1] == "signal") {
                // 从tokenId重建通道名: signal-v2-{timestamp}-{suffix}
                tokenParts.drop(1).dropLast(1).joinToString("-")
            } else {
                // fallback: 使用recipientId作为通道名（这不是正确的，但用于测试）
                "test-channel"
            }
            val allowedPath = "/v2-channels/$channelName/outbox/"
            val allowedPathTest = testPathAccess(realCosClient, allowedPath, expectSuccess = true)
            
            if (!allowedPathTest) {
                Log.w(TAG, "无法访问应该允许的路径: $allowedPath")
                return false
            }
            
            // 2. 测试禁止的路径：应该被拒绝访问
            val forbiddenPaths = listOf(
                "/", // 根目录
                "/v2-channels/", // v2-channels根目录（不应该有列举权限）
                "/outbox/", // 旧格式根目录
                "/inbox/", // 其他用户目录
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
     * 测试发送能力（无需对端Token）
     * 用于健康检查时验证是否可以正常发送，不依赖对端的Token
     */
    suspend fun testSendCapability(metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "测试发送能力: recipientId=${metadata.recipientId}")
                
                // 验证元数据类型
                val cosMetadata = metadata as? org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
                    ?: return@withContext TransportResult.failure(
                        TransportError.INVALID_FORMAT,
                        false,
                        "元数据不是COS类型"
                    )

                // 获取发送元数据（使用本端凭证和存储）
                val sendMetadata = cosMetadata.getSendMetadata()
                
                // 创建COS客户端（使用本端原始配置，完整权限）
                val cosClient = try {
                    CosClientFactory.createClient(cosConfig, context)
                } catch (e: Exception) {
                    Log.e(TAG, "创建发送COS客户端失败", e)
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "无法创建发送COS客户端: ${e.message}"
                    )
                }

                // 测试列举自己的发送目录（不需要对端Token）
                val testPath = sendMetadata.path
                val files = cosClient.listFiles(testPath)
                
                Log.d(TAG, "发送能力测试成功: 可访问发送目录 $testPath，包含 ${files.size} 个文件")
                TransportResult.success(null, mapOf("testPath" to testPath, "fileCount" to files.size))

            } catch (e: Exception) {
                Log.e(TAG, "发送能力测试失败: recipientId=${LogSanitizer.sanitize(metadata.recipientId, "recipientId")}, error=${LogSanitizer.sanitizeThrowable(e)}")
                TransportResult.fromException(e, true)
            }
        }
    }
    
    /**
     * 验证完整权限（读、写、删除、列举）
     */
    private suspend fun validateFullPermissions(cosClient: CosClient, cosToken: CosTransportToken): Boolean {
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
    private suspend fun testTencentUpload(cosClient: CosClient, file: File, key: String): Boolean {
        return try {
            // 使用类型安全的接口调用
            cosClient.uploadFile(file, key)
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS上传测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3上传
     */
    private suspend fun testAwsUpload(cosClient: CosClient, file: File, key: String): Boolean {
        return try {
            // 使用类型安全的接口调用
            cosClient.uploadFile(file, key)
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3上传测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试腾讯云COS下载
     */
    private suspend fun testTencentDownload(cosClient: CosClient, key: String, file: File): Boolean {
        return try {
            cosClient.downloadFile(key, file)
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS下载测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3下载
     */
    private suspend fun testAwsDownload(cosClient: CosClient, key: String, file: File): Boolean {
        return try {
            cosClient.downloadFile(key, file)
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3下载测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试腾讯云COS列举文件
     */
    private suspend fun testTencentListFiles(cosClient: CosClient, prefix: String): List<String> {
        return try {
            val cosFileInfos = cosClient.listFiles(prefix)
            cosFileInfos.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS列举测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptyList()
        }
    }
    
    /**
     * 测试AWS S3列举文件
     */
    private suspend fun testAwsListFiles(cosClient: CosClient, prefix: String): List<String> {
        return try {
            val cosFileInfos = cosClient.listFiles(prefix)
            cosFileInfos.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "AWS S3列举测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptyList()
        }
    }
    
    /**
     * 测试腾讯云COS删除文件
     */
    private suspend fun testTencentDelete(cosClient: CosClient, key: String): Boolean {
        return try {
            cosClient.deleteFile(key)
        } catch (e: Exception) {
            Log.e(TAG, "腾讯云COS删除测试失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 测试AWS S3删除文件
     */
    private suspend fun testAwsDelete(cosClient: CosClient, key: String): Boolean {
        return try {
            cosClient.deleteFile(key)
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
                
                // 检查是否为占位符Token
                if (token.accessKeyId == "PLACEHOLDER_KEY") {
                    Log.i(TAG, "撤销占位符Token，无需操作IAM: tokenId=${token.tokenId}")
                    return@withContext true
                }
                
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
     * 从配置中获取Provider类型
     */
    private fun getProviderFromConfig(): String {
        return config["provider"]?.toString()?.uppercase() ?: cosConfig.provider.name
    }

    /**
     * 创建COS客户端
     */
    private fun createCosClient(metadata: org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata): CosClient? {
        return try {
            if (metadata.myToken != null) {
                // 使用Token创建客户端（使用token中的云服务商类型）
                val cosToken = metadata.myToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = cosToken.cloudProvider,
                    region = cosToken.region,
                    bucketName = cosToken.bucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken,
                    context = context
                )
            } else {
                // 使用配置创建客户端
                CosClientFactory.createClient(cosConfig, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建COS客户端失败: provider=${metadata.myToken?.let { (it as CosTransportToken).cloudProvider } ?: getProviderFromConfig()}, region=${cosConfig.region}, error=${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }

    /**
     * 创建用于发送的COS客户端（使用本端凭证）
     */
    private fun createCosClientForSend(metadata: org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata): CosClient? {
        return try {
            if (metadata.myToken != null) {
                // 使用本端Token创建客户端（使用token中的云服务商类型）
                val cosToken = metadata.myToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = cosToken.cloudProvider,
                    region = metadata.myRegion,
                    bucketName = metadata.myBucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken,
                    context = context
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
    private fun createCosClientForReceive(metadata: org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata): CosClient? {
        return try {
            if (metadata.peerToken != null) {
                // 使用对端Token创建客户端（只读权限）
                val cosToken = metadata.peerToken as CosTransportToken
                CosClientFactory.createClientWithToken(
                    provider = cosToken.cloudProvider,
                    region = metadata.peerRegion,
                    bucketName = metadata.peerBucketName,
                    accessKeyId = cosToken.accessKeyId,
                    secretAccessKey = cosToken.secretAccessKey,
                    sessionToken = cosToken.sessionToken,
                    context = context
                )
            } else {
                Log.d(TAG, "接收操作跳过：缺少对端Token，当前为单向发送模式，recipientId=${metadata.recipientId}")
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
            // 直接使用Token中的Provider信息，更可靠的provider类型识别
            val providerType = cosToken.cloudProvider
            
            // 使用CosClientFactory创建真实客户端
            CosClientFactory.createClientWithToken(
                provider = providerType,
                region = cosToken.region,
                bucketName = cosToken.bucketName,
                accessKeyId = cosToken.accessKeyId,
                secretAccessKey = cosToken.secretAccessKey,
                sessionToken = cosToken.sessionToken,
                context = context
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建COS客户端失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
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
               (name.matches(Regex("^\\d+_[a-zA-Z0-9_-]+\\.dat$")) || // 新格式: timestamp_messageId.dat
                name.matches(Regex("^[a-zA-Z0-9_-]+_\\d+\\.dat$"))) && // 旧格式兼容: messageId_timestamp.dat
               fileInfo.size > 0 && fileInfo.size < 50 * 1024 * 1024 // 大小限制50MB
    }
    
    /**
     * COS特定的文件名解析策略
     */
    override fun parseMessageFileName(fileName: String): MessageFileInfo? {
        return try {
            val baseName = fileName.substringBeforeLast('.')
            val parts = baseName.split('_')
            
            // 判断是新格式还是旧格式
            val isNewFormat = parts.firstOrNull()?.all { it.isDigit() } == true
            
            when {
                // 新格式: timestamp_messageId.dat (2段，时间戳在前)
                parts.size == 2 && isNewFormat -> {
                    MessageFileInfo(
                        messageId = parts[1],
                        timestamp = parts[0].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = "",  // 从路径推断
                        recipientId = "" // 从路径推断
                    )
                }
                // 旧格式: messageId_timestamp.dat (2段，时间戳在后) - 向后兼容
                parts.size == 2 && !isNewFormat -> {
                    MessageFileInfo(
                        messageId = parts[0],
                        timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = "",
                        recipientId = ""
                    )
                }
                // 旧格式: senderId_messageId_timestamp.dat (3段)
                parts.size == 3 -> {
                    MessageFileInfo(
                        messageId = parts[1],
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = parts[0],
                        recipientId = ""
                    )
                }
                // 旧格式: senderId_recipientId_messageId_timestamp.dat (4段)
                parts.size == 4 -> {
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
        // 返回v2-channels基础路径，具体的messages/attachments目录在使用时添加
        return "/v2-channels/$recipientId/outbox/"
    }
    
    /**
     * COS特定的接收路径策略
     */
    override fun getReceivePath(recipientId: String, messageType: TransportMessageType): String {
        // 返回v2-channels基础路径，具体的messages/attachments目录在使用时添加
        return "/v2-channels/$recipientId/outbox/"
    }
    
    /**
     * COS特定的地址格式化
     */
    override fun formatAddress(config: Map<String, Any>): String {
        val region = config["region"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Region配置不能为空")
        val bucketName = config["bucketName"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("BucketName配置不能为空")
        val provider = config["provider"]?.toString()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Provider配置不能为空")
        
        return when (provider) {
            "AWS" -> "https://$bucketName.s3.$region.amazonaws.com"
            "TENCENT" -> "https://$bucketName.cos.$region.myqcloud.com"
            "ALIYUN" -> "https://$bucketName.oss-$region.aliyuncs.com"
            else -> throw IllegalArgumentException("不支持的Provider类型: $provider，仅支持: AWS, TENCENT, ALIYUN")
        }
    }
    


    /**
     * 将通用TransportMetadata转换为CosTransportMetadata（群组功能支持）
     */
    private fun convertToCosTransportMetadata(metadata: TransportMetadata, groupId: String? = null): org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata? {
        return try {
            when (metadata) {
                is org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata -> {
                    // 已经是CosTransportMetadata，直接返回
                    metadata
                }
                else -> {
                    // 尝试从通用TransportMetadata构造CosTransportMetadata
                    Log.d(TAG, "尝试从通用TransportMetadata构造CosTransportMetadata: ${metadata.recipientId}")
                    
                    // 从metadata.toMap()获取数据
                    val metadataMap = metadata.toMap()
                    
                    // 尝试使用fromMap方法重构
                    val cosMetadata = org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata.fromMap(metadataMap)
                    
                    if (cosMetadata != null) {
                        Log.d(TAG, "成功从Map重构CosTransportMetadata: ${metadata.recipientId}")
                        cosMetadata
                    } else {
                        // 如果fromMap失败，尝试手动构造最小可用的CosTransportMetadata
                        Log.w(TAG, "从Map重构失败，尝试手动构造CosTransportMetadata: ${metadata.recipientId}")
                        createMinimalCosMetadata(metadata, groupId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "转换TransportMetadata为CosTransportMetadata失败: ${metadata.recipientId}", e)
            null
        }
    }
    
    /**
     * 创建最小可用的CosTransportMetadata（当转换失败时的备用方案）
     */
    private fun createMinimalCosMetadata(metadata: TransportMetadata, groupId: String?): org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata? {
        return try {
            Log.d(TAG, "创建最小CosTransportMetadata: ${metadata.recipientId}")
            
            // 获取当前用户的ACI
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
            val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
            val peerHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAciString(metadata.recipientId)
            
            // 构造COS地址
            val myAddress = formatAddress(mapOf(
                "region" to cosConfig.region,
                "bucketName" to cosConfig.bucketName,
                "provider" to cosConfig.provider.name
            ))
            
                         // 对于群组消息，使用群组特定的路径（简化路径：/group/{groupId}/）
             val basePath = if (groupId != null) {
                 "${providerConfig.groupPathPrefix}${groupId}/"
             } else {
                 // ✅ basePath是发送路径，应该使用myHashedId（我的outbox）
                 getSendPath(myHashedId, TransportMessageType.TEXT_MESSAGE)
             }
             
             val receivePath = if (groupId != null) {
                 "${providerConfig.groupPathPrefix}${groupId}/"
             } else {
                 // ✅ receivePath是接收路径，应该使用peerHashedId（对方的outbox）
                 getReceivePath(peerHashedId, TransportMessageType.TEXT_MESSAGE)
             }
            
            // 创建CosTransportMetadata实例
            org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
                recipientId = metadata.recipientId,
                providerType = "cos",
                myAddress = myAddress,
                myToken = null, // 使用配置中的凭证
                myRegion = cosConfig.region,
                myBucketName = cosConfig.bucketName,
                mySendPath = basePath,
                peerAddress = myAddress, // 群组消息使用相同的COS服务
                peerToken = null, // 群组消息使用相同的凭证
                peerRegion = cosConfig.region,
                peerBucketName = cosConfig.bucketName,
                peerReceivePath = receivePath,
                myHashedId = myHashedId,
                peerHashedId = peerHashedId
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建最小CosTransportMetadata失败: ${metadata.recipientId}", e)
            null
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
    private fun createMetadataFromToken(cosToken: CosTransportToken): org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata {
        // 生成哈希化ID
        val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
        val myHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAci(myAci)
        val peerHashedId = org.thoughtcrime.securesms.tap.utils.TransportIdHasher.hashAciString(cosToken.recipientId)
        
        // 构建路径
        // ✅ mySendPath: 我发送到我自己的outbox
        val mySendPath = getSendPath(myHashedId, TransportMessageType.TEXT_MESSAGE)
        // ✅ peerReceivePath: 我轮询对方的outbox
        val peerReceivePath = getReceivePath(peerHashedId, TransportMessageType.TEXT_MESSAGE)
        
        return org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata(
            recipientId = cosToken.recipientId,
            providerType = "cos",
            myAddress = "https://${cosToken.bucketName}.cos.${cosToken.region}.myqcloud.com",
            myToken = cosToken,
            myRegion = cosToken.region,
            myBucketName = cosToken.bucketName,
            mySendPath = mySendPath,
            peerAddress = "https://${cosToken.bucketName}.cos.${cosToken.region}.myqcloud.com",
            peerToken = cosToken,
            peerRegion = cosToken.region,
            peerBucketName = cosToken.bucketName,
            peerReceivePath = peerReceivePath,
            myHashedId = myHashedId,
            peerHashedId = peerHashedId
        )
    }

    /**
     * 获取真实的COS客户端（类型安全）
     */
    private fun getRealCosClient(cosClient: CosClient, cosToken: CosTransportToken): CosClient? {
        return try {
            // 直接返回CosClient实例，已经是类型安全的
            cosClient
        } catch (e: Exception) {
            Log.e(TAG, "获取真实COS客户端失败: ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    // ============== 推送通知集成 ==============
    
    /**
     * 设置推送通知触发器
     * 
     * 配置S3/COS事件触发器，当有新消息上传时自动发送推送通知
     * 
     * @return 设置是否成功
     */
    suspend fun setupNotificationTrigger(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始设置推送通知触发器")
                
                // 1. 初始化组件
                initializeNotificationComponents()
                
                // 2. 部署云函数F_A（如果尚未部署）
                val deployer = getCloudFunctionDeployer()
                val config = deployer.loadConfiguration()
                
                val triggerInfo = if (config == null) {
                    Log.i(TAG, "首次部署，开始部署云函数F_A")
                    deployer.deployTriggerFunction()
                } else {
                    Log.d(TAG, "云函数已部署，跳过部署步骤")
                    null
                }
                
                if (triggerInfo == null && config == null) {
                    Log.e(TAG, "云函数F_A部署失败")
                    return@withContext false
                }
                
                // 3. 配置事件触发器
                val configurator = getEventTriggerConfigurator()
                val functionIdentifier = triggerInfo?.triggerArn ?: config?.pushServiceInfo?.metadata?.get("triggerArn") as? String
                
                if (functionIdentifier != null) {
                    val eventConfigured = configurator.configureEventTrigger(functionIdentifier)
                    
                    if (eventConfigured) {
                        Log.i(TAG, "事件触发器配置成功")
                        notificationEnabled = true
                        true
                    } else {
                        Log.e(TAG, "事件触发器配置失败")
                        false
                    }
                } else {
                    Log.w(TAG, "无法获取函数标识，跳过事件触发器配置")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "设置推送通知触发器失败", e)
                false
            }
        }
    }
    
    /**
     * 部署完整的推送服务
     * 
     * @return NotificationConfig 部署后的配置
     */
    suspend fun deployNotificationService(): NotificationConfig? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始部署完整推送服务")
                
                initializeNotificationComponents()
                
                val deployer = getCloudFunctionDeployer()
                val config = deployer.deployFullNotificationService()
                
                if (config != null) {
                    Log.i(TAG, "完整推送服务部署成功")
                    
                    // 初始化NotificationManager
                    val initSuccess = initializeNotificationManager(config)
                    if (initSuccess) {
                        notificationEnabled = true
                        Log.i(TAG, "NotificationManager初始化成功，推送通知已启用")
                    } else {
                        Log.w(TAG, "NotificationManager初始化失败，但部署成功")
                        // 即使初始化失败，如果部署成功也应该启用，因为可以使用事件触发
                        notificationEnabled = true
                        Log.i(TAG, "推送通知已启用（基于部署状态）")
                    }
                } else {
                    Log.e(TAG, "完整推送服务部署失败")
                }
                
                config
                
            } catch (e: Exception) {
                Log.e(TAG, "部署完整推送服务失败", e)
                null
            }
        }
    }
    
    /**
     * 初始化NotificationManager
     * 
     * @param config 推送服务配置
     * @return 初始化是否成功
     */
    private suspend fun initializeNotificationManager(config: NotificationConfig): Boolean {
        return try {
            Log.i(TAG, "初始化NotificationManager: provider=${config.provider}")
            
            // 创建对应的NotificationProvider实例
            val factory = NotificationProviderFactory.getInstance()
            val credentials = mapOf(
                "apiKey" to cosConfig.secretId,
                "secretKey" to cosConfig.secretKey,
                "region" to cosConfig.region
            )
            val provider = factory.createProvider(
                context = context,
                providerType = config.provider,
                credentials = credentials
            )
            
            if (provider == null) {
                Log.e(TAG, "无法创建NotificationProvider: ${config.provider}")
                return false
            }
            
            notificationProvider = provider
            
            // 初始化NotificationManager
            val success = notificationManager.initialize(provider, config)
            
            if (success) {
                Log.i(TAG, "NotificationManager初始化成功")
            } else {
                Log.e(TAG, "NotificationManager初始化失败")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化NotificationManager失败", e)
            false
        }
    }
    
    /**
     * 保存联系人的webhook配置
     * 
     * 当用户与联系人建立tap连接后，交换webhook配置并保存到COS
     * 
     * @param contactConfig 联系人的webhook配置
     * @return 保存是否成功
     */
    suspend fun saveContactWebhookConfig(contactConfig: ContactNotificationConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "保存联系人webhook配置: contactId=${LogSanitizer.sanitize(contactConfig.contactId)}")
                
                initializeNotificationComponents()
                
                val webhookManager = getContactWebhookManager()
                val success = webhookManager.saveContactConfig(contactConfig)
                
                if (success) {
                    Log.i(TAG, "联系人webhook配置保存成功")
                } else {
                    Log.e(TAG, "联系人webhook配置保存失败")
                }
                
                success
                
            } catch (e: Exception) {
                Log.e(TAG, "保存联系人webhook配置失败", e)
                false
            }
        }
    }
    
    /**
     * 加载联系人的webhook配置
     * 
     * @param contactId 联系人ID
     * @return ContactNotificationConfig 配置信息
     */
    suspend fun loadContactWebhookConfig(contactId: String): ContactNotificationConfig? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "加载联系人webhook配置: contactId=${LogSanitizer.sanitize(contactId)}")
                
                initializeNotificationComponents()
                
                val webhookManager = getContactWebhookManager()
                webhookManager.loadContactConfig(contactId)
                
            } catch (e: Exception) {
                Log.e(TAG, "加载联系人webhook配置失败", e)
                null
            }
        }
    }
    
    /**
     * 批量保存联系人webhook配置
     * 
     * @param configs 联系人配置列表
     * @return 成功保存的数量
     */
    suspend fun batchSaveContactWebhookConfigs(configs: List<ContactNotificationConfig>): Int {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "批量保存联系人webhook配置: 共${configs.size}个")
                
                initializeNotificationComponents()
                
                val webhookManager = getContactWebhookManager()
                webhookManager.batchSaveContactConfigs(configs)
                
            } catch (e: Exception) {
                Log.e(TAG, "批量保存联系人webhook配置失败", e)
                0
            }
        }
    }
    
    /**
     * 获取当前的推送服务配置
     * 
     * @return NotificationConfig 配置信息
     */
    suspend fun getNotificationConfig(): NotificationConfig? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "获取推送服务配置")
                
                initializeNotificationComponents()
                
                val deployer = getCloudFunctionDeployer()
                deployer.loadConfiguration()
                
            } catch (e: Exception) {
                Log.e(TAG, "获取推送服务配置失败", e)
                null
            }
        }
    }
    
    /**
     * 检查推送通知是否已启用
     */
    fun isNotificationEnabled(): Boolean {
        return notificationEnabled
    }

    /**
     * 上传成功后触发推送通知（通过S3事件触发云函数）
     * P1修复: 简化逻辑，完全依赖S3触发机制，移除Client端配置验证
     * 
     * @param remotePath 上传文件的远程路径
     * @param metadata 传输元数据
     * @param bucketName COS bucket名称
     */
    private suspend fun triggerNotificationAfterUpload(
        remotePath: String,
        metadata: CosTransportMetadata,
        bucketName: String
    ) {
        try {
            Log.d(TAG, "[推送触发] 通过S3触发器发送推送通知: remotePath=$remotePath, recipientId=${metadata.recipientId}")
            
            // P1修复: 直接调用S3触发器（云函数F_A），由云函数负责：
            // 1. 从COS读取联系人webhook配置
            // 2. 验证配置完整性
            // 3. 调用对方的webhook
            // 这样避免了Client端的重复逻辑和配置不一致问题
            
            try {
                when (cosConfig.provider) {
                    org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig.Provider.AWS -> {
                        val deployer = org.thoughtcrime.securesms.tap.notification.provider.aws.AwsApiGatewayDeployer(
                            context,
                            cosConfig.secretId,
                            cosConfig.secretKey,
                            cosConfig.region
                        )
                        val invoked = deployer.invokeTriggerFunction(remotePath, bucketName)
                        Log.i(TAG, "S3触发器调用成功 (AWS): $invoked")
                    }
                    org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig.Provider.TENCENT -> {
                        val deployer = org.thoughtcrime.securesms.tap.notification.provider.tencent.TencentApiGatewayDeployer(
                            context,
                            cosConfig.secretId,
                            cosConfig.secretKey,
                            cosConfig.region
                        )
                        val invoked = deployer.invokeTriggerFunction(remotePath, bucketName)
                        Log.i(TAG, "S3触发器调用成功 (TENCENT): $invoked")
                    }
                    else -> {
                        Log.w(TAG, "不支持的provider类型，无法触发推送: ${cosConfig.provider}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "S3触发器调用失败", e)
                // P1修复: 触发失败时记录错误，但不阻塞主流程
                // 系统将依赖轮询机制作为降级方案
            }
        } catch (t: Throwable) {
            // 触发失败不影响主流程，仅记录错误
            Log.e(TAG, "Error triggering notification after upload", t)
        }
    }

    /**
     * 将任意标识（RecipientId::N / UUID ACI / e164）解析为对方的 ACI 字符串。
     * 解析失败返回 null。
     */
    private fun resolveAciFromAnyId(idStr: String): String? {
        return try {
            val recipient = if (idStr.startsWith("RecipientId::")) {
                val numeric = idStr.substringAfter("RecipientId::")
                val rid = org.thoughtcrime.securesms.recipients.RecipientId.from(numeric)
                org.thoughtcrime.securesms.recipients.Recipient.resolved(rid)
            } else {
                val rid = org.thoughtcrime.securesms.recipients.RecipientId.fromSidOrE164(idStr)
                org.thoughtcrime.securesms.recipients.Recipient.resolved(rid)
            }
            recipient.requireAci().toString()
        } catch (t: Throwable) {
            Log.w(TAG, "解析ACI失败: $idStr", t)
            null
        }
    }
    
    /**
     * 连接到推送服务
     * 
     * @param userId 用户ID
     * @param onNotification 通知回调
     * @return ConnectionResult 连接结果
     */
    suspend fun connectNotificationService(
        userId: String,
        onNotification: (NotificationMessage) -> Unit
    ): ConnectionResult {
        return try {
            if (!notificationEnabled) {
                Log.w(TAG, "推送服务未启用")
                return ConnectionResult.failure("推送服务未启用")
            }
            
            if (notificationManager.isConnected()) {
                Log.d(TAG, "推送服务已连接")
                return ConnectionResult.success("already_connected")
            }
            
            Log.i(TAG, "开始连接推送服务: userId=$userId")
            val result = notificationManager.connect(userId, onNotification)
            
            if (result.success) {
                Log.i(TAG, "推送服务连接成功")
            } else {
                Log.e(TAG, "推送服务连接失败: ${result.errorMessage}")
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "连接推送服务失败", e)
            ConnectionResult.failure(e.message ?: "连接异常")
        }
    }
    
    /**
     * 断开推送服务连接
     */
    suspend fun disconnectNotificationService() {
        try {
            if (notificationManager.isConnected()) {
                Log.i(TAG, "断开推送服务连接")
                notificationManager.disconnect()
            } else {
                Log.d(TAG, "推送服务未连接")
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开推送服务失败", e)
        }
    }
    
    /**
     * 检查推送服务健康状态
     */
    suspend fun checkNotificationHealth(): HealthStatus {
        return try {
            notificationManager.healthCheck()
        } catch (e: Exception) {
            Log.e(TAG, "检查推送服务健康状态失败", e)
            HealthStatus.unhealthy(e.message ?: "健康检查异常")
        }
    }
    
    /**
     * 交换并验证Webhook配置（TAP握手阶段调用）
     * 
     * 在TAP握手时同步交换webhook配置，确保双方都保存了对方的推送配置
     * 
     * @param recipientId 接收方ID
     * @param myWebhookConfig 本地的webhook配置
     * @return 交换是否成功
     */
    suspend fun exchangeWebhookConfiguration(
        recipientId: String,
        myWebhookConfig: ContactNotificationConfig
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始交换webhook配置: recipientId=$recipientId")
                
                // 保存本地的webhook配置供对方读取
                val webhookManager = getContactWebhookManager()
                val saveSuccess = webhookManager.saveContactConfig(myWebhookConfig)
                
                if (!saveSuccess) {
                    Log.e(TAG, "保存本地webhook配置失败")
                    return@withContext false
                }
                
                // 验证配置是否可用
                val verifySuccess = verifyWebhookConfiguration(myWebhookConfig)
                if (!verifySuccess) {
                    Log.w(TAG, "webhook配置验证失败，但已保存")
                }
                
                Log.i(TAG, "Webhook配置交换完成: recipientId=$recipientId, verified=$verifySuccess")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "交换webhook配置失败: recipientId=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 验证Webhook配置
     * 
     * @param config webhook配置
     * @return 验证是否成功
     */
    private suspend fun verifyWebhookConfiguration(config: ContactNotificationConfig): Boolean {
        return try {
            // 简单验证：检查配置字段是否完整
            val isValid = config.webhookUrl.isNotBlank() &&
                         config.notifySecret.isNotBlank() &&
                         config.userId.isNotBlank()
            
            if (!isValid) {
                Log.w(TAG, "Webhook配置字段不完整")
                return false
            }
            
            Log.d(TAG, "Webhook配置验证通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "验证webhook配置失败", e)
            false
        }
    }
    
    /**
     * 确保推送通知通道就绪（消息发送后异步检查）
     * 
     * 检查对方的webhook配置是否存在，如果不存在则记录警告
     * 注意：这是消息发送后的异步检查，不阻塞发送流程
     * 真正的配置交换应该在TAP握手时通过exchangeWebhookConfiguration()完成
     * 
     * @param recipientId 接收方ID
     * @param metadata COS传输元数据
     */
    private fun ensureNotificationChannelReady(
        recipientId: String,
        metadata: CosTransportMetadata
    ) {
        try {
            Log.d(TAG, "异步检查推送通知通道: recipientId=$recipientId")
            
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    val cfgManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                    val aciContactId = resolveAciFromAnyId(recipientId) ?: recipientId
                    val config = cfgManager.getContactNotificationConfig(aciContactId)
                    
                    if (config == null) {
                        Log.w(TAG, "推送通知通道未配置: recipientId=$recipientId，对方可能无法收到实时推送")
                        Log.d(TAG, "提示：应在TAP握手阶段交换配置；尝试从本地DB补偿发布到COS")
                        try {
                            val cfgManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                            val localConfig = cfgManager.getContactNotificationConfig(recipientId)
                            if (localConfig != null && localConfig.validate()) {
                                Log.i(TAG, "发现本地联系人Webhook配置，尝试补偿写入COS: recipientId=$recipientId")
                                val deployed = getCloudFunctionDeployer().saveContactWebhookConfig(localConfig)
                                if (deployed) {
                                    Log.i(TAG, "补偿发布联系人Webhook配置到COS成功: recipientId=$recipientId")
                                } else {
                                    Log.w(TAG, "补偿发布联系人Webhook配置到COS失败: recipientId=$recipientId")
                                }
                            } else {
                                Log.d(TAG, "本地未找到可用联系人Webhook配置，跳过补偿发布")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "补偿发布联系人Webhook配置到COS时异常: recipientId=$recipientId", e)
                        }
                    } else if (!config.verified) {
                        Log.d(TAG, "推送通知通道已配置但未验证: recipientId=$recipientId")
                    } else {
                        Log.d(TAG, "推送通知通道已就绪: recipientId=$recipientId, platform=${config.platform}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "检查推送通知通道失败: recipientId=$recipientId", e)
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "启动推送通知通道检查失败", e)
        }
    }
    
    /**
     * 初始化推送通知相关组件
     */
    private fun initializeNotificationComponents() {
        if (contactWebhookManager == null) {
            val cosClient = CosClientFactory.createClient(cosConfig, context)
            contactWebhookManager = ContactWebhookManager(context, cosClient)
            Log.d(TAG, "ContactWebhookManager已初始化")
        }
        
        if (eventTriggerConfigurator == null) {
            eventTriggerConfigurator = CosEventTriggerConfigurator(context, cosConfig)
            Log.d(TAG, "CosEventTriggerConfigurator已初始化")
        }
        
        if (cloudFunctionDeployer == null) {
            cloudFunctionDeployer = CloudFunctionDeployer(context, cosConfig)
            Log.d(TAG, "CloudFunctionDeployer已初始化")
        }
    }
    
    /**
     * 获取ContactWebhookManager实例
     */
    private fun getContactWebhookManager(): ContactWebhookManager {
        if (contactWebhookManager == null) {
            initializeNotificationComponents()
        }
        return contactWebhookManager!!
    }
    
    /**
     * 获取CosEventTriggerConfigurator实例
     */
    private fun getEventTriggerConfigurator(): CosEventTriggerConfigurator {
        if (eventTriggerConfigurator == null) {
            initializeNotificationComponents()
        }
        return eventTriggerConfigurator!!
    }
    
    /**
     * 获取CloudFunctionDeployer实例
     */
    private fun getCloudFunctionDeployer(): CloudFunctionDeployer {
        if (cloudFunctionDeployer == null) {
            initializeNotificationComponents()
        }
        return cloudFunctionDeployer!!
    }

    data class OfflineMessageDescriptor(
        val key: String,
        val size: Long,
        val lastModified: Long
    )

    suspend fun listOfflineMessages(recipientHash: String): List<OfflineMessageDescriptor> {
        return withContext(Dispatchers.IO) {
            try {
                val cosClient = CosClientFactory.createClient(cosConfig, context)
                val prefix = buildOfflinePrefix(recipientHash)
                val files = cosClient.listFiles(prefix)
                files.map { cosFile ->
                    OfflineMessageDescriptor(
                        key = cosFile.name,
                        size = cosFile.size,
                        lastModified = cosFile.lastModified
                    )
                }.sortedBy { it.lastModified }
            } catch (e: Exception) {
                Log.w(TAG, "列举离线消息失败: recipientHash=${LogSanitizer.sanitize(recipientHash)}", e)
                emptyList()
            }
        }
    }

    suspend fun downloadOfflineMessage(objectKey: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val cosClient = CosClientFactory.createClient(cosConfig, context)
                cosClient.downloadFileToMemory(objectKey)
            } catch (e: Exception) {
                Log.w(TAG, "下载离线消息失败: key=${LogSanitizer.sanitize(objectKey)}", e)
                null
            }
        }
    }

    suspend fun deleteOfflineMessage(objectKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cosClient = CosClientFactory.createClient(cosConfig, context)
                cosClient.deleteFile(objectKey)
            } catch (e: Exception) {
                Log.w(TAG, "删除离线消息失败: key=${LogSanitizer.sanitize(objectKey)}", e)
                false
            }
        }
    }

    private fun buildOfflinePrefix(recipientHash: String): String {
        val normalized = recipientHash.trim().lowercase()
        return if (normalized.isEmpty()) {
            OFFLINE_PREFIX
        } else {
            "$OFFLINE_PREFIX$normalized/"
        }
    }

    private fun shouldGeneratePresignedUrl(path: String): Boolean {
        return path.contains("/attachments/")
    }

    private suspend fun getLambdaDispatcher(): TapLambdaDispatcher? {
        lambdaDispatcher?.let { return it }
        return withContext(Dispatchers.IO) {
            val config = org.thoughtcrime.securesms.tap.TransportProviderConfigManager
                .getInstance(context)
                .getNotificationConfig()
            if (config == null) {
                Log.w(TAG, "未找到推送服务配置，无法创建Lambda分发器")
                null
            } else {
                TapLambdaDispatcherFactory.create(context, cosConfig, config).also {
                    lambdaDispatcher = it
                }
            }
        }
    }

    private fun buildLambdaPayload(
        message: TransportMessage,
        metadata: org.thoughtcrime.securesms.tap.provider.cos.CosTransportMetadata
    ): JSONObject {
        val messageJson = JSONObject(TransportMessage.objectMapper.writeValueAsString(message))
        val attachmentsArray = JSONArray().apply {
            message.contentMetadata.attachmentsPresigned.forEach { presigned ->
                put(
                    JSONObject().apply {
                        put("attachmentId", presigned.attachmentId)
                        put("url", presigned.url)
                        put("expiresAt", presigned.expiresAt)
                    }
                )
            }
        }
        val traceId = UUID.randomUUID().toString()

        return JSONObject().apply {
            put("operation", "direct_message")
            put("version", "3.0")
            put("traceId", traceId)
            put("timestamp", System.currentTimeMillis())
            put("sender", JSONObject().apply {
                put("aci", message.senderId)
                put("hash", metadata.myHashedId)
                put("bucket", metadata.myBucketName)
                put("region", metadata.myRegion)
                put("provider", cosConfig.provider.name.lowercase())
            })
            put("recipient", JSONObject().apply {
                put("aci", message.recipientId)
                put("hash", metadata.peerHashedId)
            })
            put("recipientHash", metadata.peerHashedId)
            put("configBucket", metadata.myBucketName)
            put("message", messageJson)
            put("attachmentsPresigned", attachmentsArray)
            put("deliveryHint", JSONObject().apply {
                put("channel", "websocket")
                put("fallback", "s3-offline")
            })
        }
    }
}
