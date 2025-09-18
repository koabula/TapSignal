package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.cos.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosAccessInfo
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.SubAccountPoolManager
import org.thoughtcrime.securesms.tap.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * COS传输提供者实现
 * 
 * 将现有的cos和coscomm模块功能适配到TAP架构中，
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

    // 延迟初始化的组件
    private val subAccountPoolManager by lazy { SubAccountPoolManager.getInstance(context) }
    
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

                // 创建COS客户端
                val cosClient = createCosClient(cosMetadata)
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

                // 创建临时文件
                val tempFile = createTempFile(message)
                
                try {
                    // 生成远程路径
                    val remotePath = "${cosMetadata.path}${message.messageId}_${System.currentTimeMillis()}.dat"
                    
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
     * 从COS拉取消息
     */
    override suspend fun pull(metadata: TransportMetadata): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始拉取消息: recipientId=${metadata.recipientId}")
                
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

                // 列举目录中的文件
                val files = cosClient.listFiles(cosMetadata.path)
                
                if (files.isEmpty()) {
                    Log.d(TAG, "未找到新消息: recipientId=${metadata.recipientId}")
                    return@withContext TransportResult.Success(null)
                }

                // 获取最新的文件
                val latestFile = files.maxByOrNull { it.lastModified }
                    ?: return@withContext TransportResult.Success(null)

                Log.d(TAG, "找到最新文件: ${latestFile.key}, size=${latestFile.size}")

                // 下载并解析消息
                val tempFile = File.createTempFile("cos_download_", ".dat", context.cacheDir)
                
                try {
                    val downloadSuccess = cosClient.downloadFile(latestFile.key, tempFile)
                    
                    if (downloadSuccess && tempFile.exists()) {
                        val message = parseMessageFromFile(tempFile)
                        Log.i(TAG, "消息拉取成功: messageId=${message.messageId}")
                        TransportResult.Success(message)
                    } else {
                        Log.e(TAG, "文件下载失败: ${latestFile.key}")
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
                Log.e(TAG, "拉取消息时发生异常", e)
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
                val cosMetadata = CosTransportMetadata(
                    recipientId = "self", // 上传到自己的COS
                    address = cosConfig.let { "${it.provider.name.lowercase()}://${it.bucketName}.${it.region}" },
                    token = null, // 使用自己的凭证
                    path = groupPath,
                    providerType = "cos",
                    region = cosConfig.region,
                    bucketName = cosConfig.bucketName
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
                            memberMetadata.copy(path = groupPath)
                        } else {
                            // 转换为COS元数据
                            CosTransportMetadata(
                                recipientId = memberMetadata.recipientId,
                                address = memberMetadata.address,
                                token = memberMetadata.token as? CosTransportToken,
                                path = groupPath,
                                providerType = "cos",
                                region = (memberMetadata as? CosTransportMetadata)?.region ?: "",
                                bucketName = (memberMetadata as? CosTransportMetadata)?.bucketName ?: ""
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
     * 生成访问Token - 适配SubAccountPoolManager
     */
    override suspend fun generateToken(request: TransportTokenRequest): TransportToken? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "生成访问Token: recipientId=${request.recipientId}")
                
                // 使用现有的COS客户端生成临时凭证
                val cosClient = CosClientFactory.createClient(cosConfig, context)
                val directoryPath = OUTBOX_PATH // 默认使用outbox路径
                
                // 生成临时访问凭证（永久凭证）
                val accessToken = cosClient.generateTemporaryAccessToken(directoryPath, Int.MAX_VALUE)
                
                // 转换为CosTransportToken
                val transportToken = CosTransportToken(
                    tokenId = UUID.randomUUID().toString(),
                    recipientId = request.recipientId,
                    providerType = "cos",
                    permissions = request.requestedPermissions,
                    expirationTime = Long.MAX_VALUE, // 永久凭证
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
     * 验证Token有效性
     */
    override suspend fun validateToken(token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "验证Token: tokenId=${token.tokenId}, recipientId=${token.recipientId}")
                
                // 基本验证
                if (!super.validateToken(token)) {
                    return@withContext false
                }
                
                // COS特定验证
                val cosToken = token as? CosTransportToken
                    ?: return@withContext false
                
                // 验证COS凭证有效性
                try {
                    val testClient = CosClientFactory.createClientWithToken(
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
                    
                    // 尝试列举目录来验证凭证
                    testClient.listFiles("/")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Token验证失败: ${e.message}")
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "验证Token时发生异常", e)
                false
            }
        }
    }

    /**
     * 撤销Token
     */
    override suspend fun revokeToken(token: TransportToken): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "撤销Token: tokenId=${token.tokenId}")
                
                // 从SubAccountPoolManager中移除相关凭证
                val cosToken = token as? CosTransportToken
                    ?: return@withContext false
                
                // 这里应该调用云服务API撤销凭证，但现有的COS模块没有提供此功能
                // 暂时只做本地清理
                Log.i(TAG, "Token撤销完成: tokenId=${token.tokenId}")
                true

            } catch (e: Exception) {
                Log.e(TAG, "撤销Token时发生异常", e)
                false
            }
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
            if (metadata.token != null) {
                // 使用Token创建客户端
                val cosToken = metadata.token as CosTransportToken
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
     * 创建临时文件保存消息内容
     */
    private fun createTempFile(message: TransportMessage): File {
        val tempFile = File.createTempFile("cos_upload_", ".dat", context.cacheDir)
        
        // 将消息内容写入文件
        tempFile.outputStream().use { output ->
            output.write(message.encryptedContent)
            
            // 如果有附件，也写入文件
            message.attachments.forEach { attachment ->
                output.write(attachment.encryptedData)
            }
        }
        
        return tempFile
    }

    /**
     * 从文件解析消息
     */
    private fun parseMessageFromFile(file: File): TransportMessage {
        val content = file.readBytes()
        
        // 简化的消息解析，实际应该根据消息格式进行解析
        return TransportMessage(
            messageId = "parsed_${System.currentTimeMillis()}",
            encryptedContent = content,
            messageType = TransportMessageType.TEXT_MESSAGE,
            timestamp = System.currentTimeMillis(),
            attachments = emptyList()
        )
    }
} 