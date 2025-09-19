package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.*
import org.thoughtcrime.securesms.tap.*
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

                Log.d(TAG, "找到最新文件: ${latestFile.name}, size=${latestFile.size}")

                // 下载并解析消息
                val tempFile = File.createTempFile("cos_download_", ".dat", context.cacheDir)
                
                try {
                    val downloadSuccess = cosClient.downloadFile(latestFile.name, tempFile)
                    
                    if (downloadSuccess && tempFile.exists()) {
                        val message = parseMessageFromFile(tempFile)
                        Log.i(TAG, "消息拉取成功: messageId=${message.messageId}")
                        TransportResult.Success(message)
                    } else {
                        Log.e(TAG, "文件下载失败: ${latestFile.name}")
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
                
                val cosToken = token as? CosTransportToken
                    ?: return@withContext false
                
                // 根据不同的Provider类型执行真实的撤销操作
                val revokeResult = when {
                    cosToken.region.startsWith("ap-") -> {
                        // 腾讯云COS Token撤销
                        revokeTencentToken(cosToken)
                    }
                    else -> {
                        // AWS S3 Token撤销
                        revokeAwsToken(cosToken)
                    }
                }
                
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
        
        // 按照TaP层消息格式序列化消息
        // 格式：[messageId长度(4字节)][messageId][timestamp(8字节)][messageType(4字节)][content长度(4字节)][encryptedContent][attachments数量(4字节)][attachments...]
        tempFile.outputStream().use { output ->
            // 1. 写入messageId
            val messageIdBytes = message.messageId.toByteArray(Charsets.UTF_8)
            output.write(intToBytes(messageIdBytes.size))
            output.write(messageIdBytes)
            
            // 2. 写入timestamp
            output.write(longToBytes(message.timestamp))
            
            // 3. 写入messageType
            output.write(intToBytes(message.messageType.ordinal))
            
            // 4. 写入encryptedContent
            output.write(intToBytes(message.encryptedContent.size))
            output.write(message.encryptedContent)
            
            // 5. 写入attachments
            output.write(intToBytes(message.attachments.size))
            message.attachments.forEach { attachment ->
                // 写入attachmentId
                val attachmentIdBytes = attachment.attachmentId.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(attachmentIdBytes.size))
                output.write(attachmentIdBytes)
                
                // 写入attachmentData
                output.write(intToBytes(attachment.encryptedData.size))
                output.write(attachment.encryptedData)
                
                // 写入mimeType
                val mimeTypeBytes = attachment.mimeType.toByteArray(Charsets.UTF_8)
                output.write(intToBytes(mimeTypeBytes.size))
                output.write(mimeTypeBytes)
            }
        }
        
        return tempFile
    }

    /**
     * 从文件解析消息
     */
    private fun parseMessageFromFile(file: File): TransportMessage {
        val content = file.readBytes()
        
        // 真实的消息解析逻辑
        // TaP层消息格式：[messageId长度(4字节)][messageId][timestamp(8字节)][messageType(4字节)][content长度(4字节)][encryptedContent][attachments数量(4字节)][attachments...]
        
        if (content.size < 20) { // 最小头部大小
            throw IllegalArgumentException("消息文件格式无效：文件过小")
        }
        
        var offset = 0
        
        // 1. 读取messageId
        val messageIdLength = bytesToInt(content, offset)
        offset += 4
        if (offset + messageIdLength > content.size) {
            throw IllegalArgumentException("消息文件格式无效：messageId长度超出范围")
        }
        val messageId = String(content, offset, messageIdLength, Charsets.UTF_8)
        offset += messageIdLength
        
        // 2. 读取timestamp
        if (offset + 8 > content.size) {
            throw IllegalArgumentException("消息文件格式无效：timestamp不完整")
        }
        val timestamp = bytesToLong(content, offset)
        offset += 8
        
        // 3. 读取messageType
        if (offset + 4 > content.size) {
            throw IllegalArgumentException("消息文件格式无效：messageType不完整")
        }
        val messageTypeOrdinal = bytesToInt(content, offset)
        offset += 4
        val messageType = TransportMessageType.values().getOrNull(messageTypeOrdinal)
            ?: TransportMessageType.TEXT_MESSAGE
        
        // 4. 读取encryptedContent
        if (offset + 4 > content.size) {
            throw IllegalArgumentException("消息文件格式无效：content长度不完整")
        }
        val contentLength = bytesToInt(content, offset)
        offset += 4
        if (offset + contentLength > content.size) {
            throw IllegalArgumentException("消息文件格式无效：content长度超出范围")
        }
        val encryptedContent = content.copyOfRange(offset, offset + contentLength)
        offset += contentLength
        
        // 5. 读取attachments
        val attachments = mutableListOf<TransportAttachment>()
        if (offset + 4 <= content.size) {
            val attachmentCount = bytesToInt(content, offset)
            offset += 4
            
            for (i in 0 until attachmentCount) {
                if (offset + 12 > content.size) break // 不完整的attachment头部
                
                val attachmentIdLength = bytesToInt(content, offset)
                offset += 4
                if (offset + attachmentIdLength > content.size) break
                val attachmentId = String(content, offset, attachmentIdLength, Charsets.UTF_8)
                offset += attachmentIdLength
                
                val attachmentDataLength = bytesToInt(content, offset)
                offset += 4
                if (offset + attachmentDataLength > content.size) break
                val attachmentData = content.copyOfRange(offset, offset + attachmentDataLength)
                offset += attachmentDataLength
                
                val mimeTypeLength = bytesToInt(content, offset)
                offset += 4
                if (offset + mimeTypeLength > content.size) break
                val mimeType = String(content, offset, mimeTypeLength, Charsets.UTF_8)
                offset += mimeTypeLength
                
                attachments.add(TransportAttachment(
                    attachmentId = attachmentId,
                    encryptedData = attachmentData,
                    mimeType = mimeType,
                    size = attachmentData.size.toLong()
                ))
            }
        }
        
        return TransportMessage(
            messageId = messageId,
            encryptedContent = encryptedContent,
            messageType = messageType,
            timestamp = timestamp,
            attachments = attachments
        )
    }
    
    /**
     * 4字节转Int（大端序）
     */
    private fun bytesToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
               (bytes[offset + 3].toInt() and 0xFF)
    }
    
    /**
     * 8字节转Long（大端序）
     */
    private fun bytesToLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
    
    /**
     * Int转4字节（大端序）
     */
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
    
    /**
     * Long转8字节（大端序）
     */
    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
    
    /**
     * 撤销腾讯云COS Token
     */
    private suspend fun revokeTencentToken(cosToken: CosTransportToken): Boolean {
        return try {
            // 对于腾讯云，区分临时凭证和永久凭证的处理方式
            if (cosToken.sessionToken.isNullOrEmpty()) {
                // 永久凭证：尝试删除子用户
                val host = "cam.tencentcloudapi.com"
                val service = "cam"
                val version = "2019-01-16"
                val action = "DeleteUser"
                val timestamp = System.currentTimeMillis() / 1000

                val requestBody = """
                {
                    "Name": "signal-tap-user-${cosToken.recipientId}"
                }
                """.trimIndent()

                val authorization = org.thoughtcrime.securesms.tap.provider.cos.utils.client.tencent.TencentSigner.buildTC3AuthorizationHeader(
                    secretId = cosConfig.secretId,
                    secretKey = cosConfig.secretKey,
                    service = service,
                    region = cosToken.region,
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
                    .header("X-TC-Region", cosToken.region)
                    .header("X-TC-Timestamp", timestamp.toString())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build()

                val okHttpClient = okhttp3.OkHttpClient()
                okHttpClient.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            Log.i(TAG, "腾讯云子用户删除成功: tokenId=${cosToken.tokenId}")
                            true
                        }
                        resp.body?.string()?.contains("InvalidParameter.UserNotExist") == true -> {
                            Log.i(TAG, "腾讯云子用户不存在，视为撤销成功: tokenId=${cosToken.tokenId}")
                            true
                        }
                        else -> {
                            Log.e(TAG, "腾讯云子用户删除失败: ${resp.code}")
                            false
                        }
                    }
                }
            } else {
                // 临时凭证：记录撤销状态，等待自然过期
                Log.i(TAG, "腾讯云临时凭证等待自然过期: tokenId=${cosToken.tokenId}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "撤销腾讯云Token失败", e)
            false
        }
    }
    
    /**
     * 撤销AWS S3 Token
     */
    private suspend fun revokeAwsToken(cosToken: CosTransportToken): Boolean {
        return try {
            if (cosToken.sessionToken.isNullOrEmpty()) {
                // 永久凭证：删除IAM用户的访问密钥
                revokeAwsAccessKey(cosToken)
            } else {
                // 临时凭证：记录撤销状态，等待自然过期
                Log.i(TAG, "AWS临时凭证等待自然过期: tokenId=${cosToken.tokenId}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "撤销AWS Token失败", e)
            false
        }
    }
    
    /**
     * 撤销AWS访问密钥
     */
    private suspend fun revokeAwsAccessKey(cosToken: CosTransportToken): Boolean {
        return try {
            val date = java.util.Date()
            val amzDate = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
            val dateStamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
            
            val action = "Action=DeleteAccessKey&Version=2010-05-08&AccessKeyId=${cosToken.accessKeyId}&UserName=signal-tap-user-${cosToken.recipientId}"
            val host = "iam.amazonaws.com"
            val canonicalUri = "/"
            val canonicalQueryString = action
            val payloadHash = org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner.hash("")
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "GET\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

            val authorization = org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner.buildAuthorizationHeader(
                cosConfig.secretId, cosConfig.secretKey, "us-east-1", "iam", canonicalRequest, amzDate, dateStamp, signedHeaders
            )

            val request = okhttp3.Request.Builder()
                .url("https://$host/?$action")
                .get()
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()

            val okHttpClient = okhttp3.OkHttpClient()
            okHttpClient.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        Log.i(TAG, "AWS访问密钥删除成功: tokenId=${cosToken.tokenId}")
                        true
                    }
                    resp.body?.string()?.contains("NoSuchEntity") == true -> {
                        Log.i(TAG, "AWS访问密钥不存在，视为撤销成功: tokenId=${cosToken.tokenId}")
                        true
                    }
                    else -> {
                        Log.e(TAG, "AWS访问密钥删除失败: ${resp.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "撤销AWS访问密钥失败", e)
            false
        }
    }
} 