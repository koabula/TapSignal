package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.signal.core.util.Base64
import okio.ByteString.Companion.toByteString
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportContentMetadata
import org.thoughtcrime.securesms.tap.TransportAttachment
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.mms.PartAuthority
import org.whispersystems.signalservice.internal.push.Envelope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import java.util.Optional

/**
 * Tap Signal Service适配器
 * 负责让Signal先完成消息加密，然后将密文交给Tap进行传输
 * 实现发送端的"先加密、后传输"分离架构
 */
class TapSignalServiceAdapter private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapSignalServiceAdapter::class.java)
        
        @Volatile
        private var INSTANCE: TapSignalServiceAdapter? = null
        
        @JvmStatic
        fun getInstance(context: Context): TapSignalServiceAdapter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapSignalServiceAdapter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val transportManager = TransportManager.getInstance(context)
    private val signalServiceMessageSender = AppDependencies.signalServiceMessageSender
    
    // 当前发送上下文，用于确保附件上传和消息构建使用一致的recipient信息
    @Volatile
    private var currentSendingRecipient: Recipient? = null
    
    // 全局路径构建参数，确保同一消息的所有附件使用一致的参数
    @Volatile
    private var globalMessageId: String? = null
    @Volatile
    private var globalTimestamp: Long? = null
    private val attachmentPaths = mutableMapOf<String, String>() // attachmentId -> transportPath
    
    /**
     * 获取当前发送上下文中的recipient
     */
    private fun getCurrentSendingRecipient(): Recipient? {
        return currentSendingRecipient
    }
    
    /**
     * 设置当前发送上下文
     */
    private fun setCurrentSendingRecipient(recipient: Recipient?) {
        currentSendingRecipient = recipient
        if (recipient != null) {
            // 初始化全局路径构建参数
            globalMessageId = org.thoughtcrime.securesms.tap.TransportMessage.generateMessageId()
            globalTimestamp = System.currentTimeMillis()
            attachmentPaths.clear()
        } else {
            // 清理发送上下文
            clearSendingContext()
        }
    }
    
    /**
     * 清理发送上下文
     */
    private fun clearSendingContext() {
        currentSendingRecipient = null
        globalMessageId = null
        globalTimestamp = null
        attachmentPaths.clear()
    }
    
    
    /**
     * 使用Signal加密然后通过Tap传输的发送方法
     * 
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @return 发送结果
     */
    suspend fun sendWithSignalEncryption(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): TapSignalSendResult {
        return try {
            Log.i(TAG, "开始Signal加密+Tap传输: messageId=$messageId, recipient=${recipient.id}")
            
            // 设置当前发送上下文，确保附件处理过程中能获取正确的recipient信息
            setCurrentSendingRecipient(recipient)
            
            // 0. 发送前状态验证和同步
            if (!validateAndSyncStateBeforeSend(recipient)) {
                Log.e(TAG, "发送前状态验证失败: messageId=$messageId, recipientId=${recipient.id}")
                clearSendingContext() // 清理发送上下文
                return TapSignalSendResult.Failed("发送前状态验证失败")
            }
            
            // 1. 构建Signal数据消息（异步预处理附件）
            val signalDataMessage = buildSignalDataMessage(outgoingMessage)
            val signalServiceAddress = SignalServiceAddress(recipient.requireServiceId())
            
            // 2. 使用Signal进行加密（不发送）
            val encryptedData = encryptWithSignal(signalServiceAddress, signalDataMessage)
            if (encryptedData == null) {
                Log.e(TAG, "Signal加密失败: messageId=$messageId")
                clearSendingContext()
                return TapSignalSendResult.Failed("Signal加密失败")
            }
            
            Log.d(TAG, "Signal加密成功: messageId=$messageId, ciphertextLength=${encryptedData.ciphertext.size}")
            Log.d(TAG, "[TapTimeTest] T2_ENCRYPT_END | msgId=${outgoingMessage.sentTimeMillis} | timestamp=${System.currentTimeMillis()}")
            
            // 3. 构建TransportMessage
            val transportMessage = buildTransportMessage(
                messageId = messageId,
                recipient = recipient,
                outgoingMessage = outgoingMessage,
                encryptedData = encryptedData
            )
            
            // 4. 通过Tap传输层发送
            val sendResult = sendViaTapTransport(transportMessage, recipient)
            
            when (sendResult) {
                is org.thoughtcrime.securesms.tap.TransportResult.Success -> {
                    Log.i(TAG, "TAP传输成功: messageId=$messageId")
                    clearSendingContext() // 清理发送上下文
                    TapSignalSendResult.Success(
                        transportPath = sendResult.metadata?.get("remotePath") as? String,
                        providerType = sendResult.metadata?.get("providerType") as? String ?: "unknown"
                    )
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.Failed -> {
                    Log.e(TAG, "TAP传输失败: messageId=$messageId, error=${sendResult.error}")
                    clearSendingContext() // 清理发送上下文
                    if (sendResult.retryable) {
                        TapSignalSendResult.RetryLater("TAP传输失败: ${sendResult.errorMessage}")
                    } else {
                        TapSignalSendResult.Failed("TAP传输失败: ${sendResult.errorMessage}")
                    }
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.RetryScheduled -> {
                    Log.w(TAG, "TAP传输重试: messageId=$messageId")
                    clearSendingContext() // 清理发送上下文
                    TapSignalSendResult.RetryLater("TAP传输重试")
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.PartialSuccess -> {
                    Log.w(TAG, "TAP传输部分成功: messageId=$messageId")
                    clearSendingContext() // 清理发送上下文
                    TapSignalSendResult.Success(
                        transportPath = sendResult.metadata?.get("remotePath") as? String,
                        providerType = sendResult.metadata?.get("providerType") as? String ?: "unknown"
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Signal加密+Tap传输异常: messageId=$messageId", e)
            clearSendingContext() // 清理发送上下文
            TapSignalSendResult.Failed("加密传输异常: ${e.message}")
        }
    }
    
    /**
     * 使用Signal原生加密方法加密消息
     */
    private suspend fun encryptWithSignal(
        address: SignalServiceAddress,
        message: SignalServiceDataMessage
    ): EncryptedMessageData? {
        return try {
            Log.d(TAG, "使用Signal加密消息: address=${address.serviceId}")
            
            // 使用协程包装同步的加密调用
            suspendCancellableCoroutine { continuation ->
                try {
                    // 获取Signal的消息发送器
                    val messageSender = signalServiceMessageSender
                    
                    // 使用Signal的SessionCipher进行加密
                    val protocolStore = org.thoughtcrime.securesms.dependencies.AppDependencies.protocolStore.aci()
                    val sessionCipher = org.signal.libsignal.protocol.SessionCipher(
                        protocolStore,
                        org.signal.libsignal.protocol.SignalProtocolAddress(
                            address.serviceId.toString(),
                            org.whispersystems.signalservice.api.push.SignalServiceAddress.DEFAULT_DEVICE_ID
                        )
                    )
                    
                    // 序列化消息内容
                    val messageBytes = serializeSignalMessage(message)
                    
                    // 执行加密
                    val ciphertextMessage = sessionCipher.encrypt(messageBytes)
                    
                    val result = EncryptedMessageData(
                        ciphertext = ciphertextMessage.serialize(),
                        ciphertextType = ciphertextMessage.type
                    )
                    
                    Log.d(TAG, "Signal加密完成: type=${result.ciphertextType}, size=${result.ciphertext.size}")
                    continuation.resume(result)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Signal加密异常", e)
                    continuation.resumeWithException(e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Signal加密失败", e)
            null
        }
    }
    
    /**
     * 序列化Signal消息
     */
    private fun serializeSignalMessage(message: SignalServiceDataMessage): ByteArray {
        // 构建protobuf Content
        val content = Content.Builder()
        val dataMessage = DataMessage.Builder()
        
        // 设置消息内容
        message.body.ifPresent { body ->
            dataMessage.body = body
        }
        
        // 设置时间戳
        dataMessage.timestamp = message.timestamp
        
        // 设置过期时间
        if (message.expiresInSeconds > 0) {
            dataMessage.expireTimer = message.expiresInSeconds
        }
        
        // 设置附件（如果有）
        message.attachments.ifPresent { attachments ->
            val attachmentPointers = attachments.map { attachment ->
                convertToAttachmentPointer(attachment)
            }
            dataMessage.attachments = attachmentPointers
        }
        
        content.dataMessage = dataMessage.build()
        return content.build().encode()
    }
    
    /**
     * 转换SignalServiceAttachment为AttachmentPointer
     */
    private fun convertToAttachmentPointer(attachment: SignalServiceAttachment): org.whispersystems.signalservice.internal.push.AttachmentPointer {
        return when (attachment) {
            is SignalServiceAttachmentStream -> {
                // 流式附件在Tap传输层中直接作为二进制数据传输
                // 不需要上传到CDN，而是通过Transport层直接传输
                val attachmentData = try {
                    attachment.inputStream.readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "读取流式附件数据失败", e)
                    ByteArray(0)
                }
                
                // 创建一个特殊的AttachmentPointer，标识这是Tap传输的附件
                org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
                    .contentType(attachment.contentType ?: "application/octet-stream")
                    .size(if (attachmentData.isNotEmpty()) attachmentData.size else (attachment.length ?: 0L).toInt())
                    .fileName(attachment.fileName.orElse(""))
                    // 使用消息相关的ID标识这是Tap传输的附件
                    .cdnKey("tap_attachment:${System.currentTimeMillis()}_${attachmentData.hashCode()}")
                    .digest(calculateDigest(attachmentData).toByteString())
                    .build()
            }
            is SignalServiceAttachmentPointer -> {
                // 指针附件直接转换
                val builder = org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
                    .contentType(attachment.contentType ?: "application/octet-stream")
                    .size(attachment.size.orElse(0))
                    .fileName(attachment.fileName.orElse(""))
                
                // 根据remoteId类型设置ID
                when (val remoteId = attachment.remoteId) {
                    is SignalServiceAttachmentRemoteId.V2 -> {
                        builder.cdnId(remoteId.cdnId)
                    }
                    is SignalServiceAttachmentRemoteId.V4 -> {
                        builder.cdnKey(remoteId.cdnKey)
                    }
                    else -> {
                        // 其他类型或null情况，使用TAP传输特有的标识符
                        builder.cdnKey("tap_pointer_attachment:${System.currentTimeMillis()}_${attachment.hashCode()}")
                    }
                }
                
                // 添加digest字段以防为空
                if (attachment.digest.isPresent) {
                    builder.digest(attachment.digest.get().toByteString())
                } else {
                    // 生成一个默认的digest
                    builder.digest(calculateDigest(ByteArray(0)).toByteString())
                }
                
                builder.build()
            }
            else -> {
                // 其他类型的默认处理 - 确保有必需字段
                org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
                    .contentType(attachment.contentType ?: "application/octet-stream")
                    .fileName("")
                    .size(0)
                    .cdnKey("tap_unknown_attachment:${System.currentTimeMillis()}_${attachment.hashCode()}")
                    .digest(calculateDigest(ByteArray(0)).toByteString())
                    .build()
            }
        }
    }
    
    /**
     * 构建Signal数据消息
     */
    private suspend fun buildSignalDataMessage(outgoingMessage: OutgoingMessage): SignalServiceDataMessage {
        Log.d(TAG, "构建Signal数据消息: body长度=${outgoingMessage.body.length}, 附件数=${outgoingMessage.attachments.size}")
        
        val builder = SignalServiceDataMessage.newBuilder()
            .withBody(outgoingMessage.body)
            .withTimestamp(outgoingMessage.sentTimeMillis)
        
        // 处理过期时间
        if (outgoingMessage.expiresIn > 0) {
            builder.withExpiration((outgoingMessage.expiresIn / 1000).toInt())
        }
        
        // 处理附件 - 现在是异步预处理
        if (outgoingMessage.attachments.isNotEmpty()) {
            val signalAttachments = mutableListOf<SignalServiceAttachment>()
            
            for (attachment in outgoingMessage.attachments) {
                val signalAttachment = convertToSignalServiceAttachment(attachment)
                if (signalAttachment != null) {
                    signalAttachments.add(signalAttachment)
                }
            }
            
            if (signalAttachments.isNotEmpty()) {
                builder.withAttachments(signalAttachments)
            }
        }
        
        return builder.build()
    }
    
    /**
     * 转换附件为Signal格式 - 方案一：预处理附件指针
     * 
     * 为TAP传输创建特殊的AttachmentPointer（cdnNumber=999），
     * 将附件路径信息编码到key字段，避免接收时的空指针异常
     */
    private suspend fun convertToSignalServiceAttachment(attachment: Attachment): SignalServiceAttachment? {
        return try {
            when (attachment) {
                is DatabaseAttachment -> {
                    if (attachment.hasData) {
                        // 为TAP传输预处理附件：创建AttachmentPointer而不是AttachmentStream
                        createTapAttachmentPointer(attachment)
                    } else {
                        Log.w(TAG, "数据库附件没有数据: ${attachment.attachmentId}")
                        null
                    }
                }
                
                is UriAttachment -> {
                    // 为TAP传输预处理附件：创建AttachmentPointer而不是AttachmentStream
                    createTapAttachmentPointer(attachment)
                }
                
                else -> {
                    Log.w(TAG, "不支持的附件类型: ${attachment::class.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "转换附件失败", e)
            null
        }
    }
    
    /**
     * 为TAP传输创建附件指针并上传附件
     * 使用CDN 999标识TAP附件，将传输路径编码到key字段
     */
    private suspend fun createTapAttachmentPointer(attachment: Attachment): SignalServiceAttachmentPointer? {
        return try {
            // 获取当前发送上下文中的recipient信息
            val currentRecipient = getCurrentSendingRecipient()
            if (currentRecipient == null) {
                Log.e(TAG, "无法获取当前发送目标，无法创建TAP附件指针")
                return null
            }
            
            // 获取全局路径构建参数
            val messageId = globalMessageId
            val timestamp = globalTimestamp
            if (messageId == null || timestamp == null) {
                Log.e(TAG, "全局路径参数未初始化，无法创建TAP附件指针")
                return null
            }
            
            // 生成附件ID和文件名
            val attachmentId = when (attachment) {
                is DatabaseAttachment -> attachment.attachmentId.id.toString()
                else -> "uri_${timestamp}"
            }
            
            val fileName = attachment.fileName ?: "attachment_${attachmentId}"
            
            // 通过TransportChannelManager获取正确的发送通道信息
            val recipientAci = currentRecipient.requireAci().toString()
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val activeChannels = channelManager.getActiveChannels(recipientAci)
            
            if (activeChannels.isEmpty()) {
                Log.e(TAG, "没有活跃的传输通道，无法创建TAP附件指针")
                return null
            }
            
            // 使用第一个活跃通道获取发送元数据
            val activeChannel = activeChannels.first()
            val sendMetadata = activeChannel.metadata.getSendMetadata()
            val basePath = sendMetadata.path
            
            // 构建与消息路由完全一致的TAP通道路径
            // 文件名格式: timestamp_messageId.dat (时间戳在前，确保COS marker字典序正确)
            val attachmentFileName = "${timestamp}_${messageId}.dat"
            val fullTapPath = "${basePath}attachments/$attachmentFileName"
            
            // 存储路径映射，供buildTransportMessage使用
            attachmentPaths[attachmentId] = fullTapPath
            
            Log.d(TAG, "使用通道路径构建附件路径: basePath=$basePath, fullPath=$fullTapPath")
            
            // 读取附件数据
            val attachmentData = readAttachmentData(attachment)
            if (attachmentData == null || attachmentData.isEmpty()) {
                Log.e(TAG, "无法读取附件数据: ${attachment.fileName}")
                return null
            }
            
            // 上传附件到TAP传输层 - 使用与消息路由一致的TAP通道路径
            val uploadResult = uploadAttachmentToTap(attachmentData, fullTapPath, attachment)
            if (!uploadResult) {
                Log.e(TAG, "上传附件到TAP失败: ${attachment.fileName}")
                return null
            }
            
            // 计算文件哈希
            val fileHash = try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                digest.digest(attachmentData)
            } catch (e: Exception) {
                Log.w(TAG, "计算附件哈希失败: ${attachment.fileName}", e)
                null
            }
            
            // 将完整TAP通道路径编码为key
            val keyContent = "TAP:$fullTapPath"
            val encodedKey = keyContent.toByteArray(Charsets.UTF_8)
            
            Log.i(TAG, "TAP附件上传成功，创建AttachmentPointer: path=$fullTapPath, size=${attachmentData.size}")
            
            // 创建TAP专用的AttachmentPointer
            SignalServiceAttachmentPointer(
                cdnNumber = 999, // 使用已定义的COS CDN
                remoteId = SignalServiceAttachmentRemoteId.from(attachmentId),
                contentType = attachment.contentType ?: "application/octet-stream",
                key = encodedKey,
                size = Optional.of(attachmentData.size),
                preview = Optional.empty(),
                width = attachment.width,
                height = attachment.height,
                digest = Optional.ofNullable(fileHash),
                incrementalDigest = Optional.empty(),
                incrementalMacChunkSize = 0,
                fileName = Optional.ofNullable(fileName),
                voiceNote = attachment.voiceNote,
                isBorderless = attachment.borderless,
                isGif = attachment.videoGif,
                caption = Optional.empty(),
                blurHash = Optional.ofNullable(attachment.blurHash?.hash),
                uploadTimestamp = timestamp,
                uuid = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "创建TAP附件指针失败: ${attachment.fileName}", e)
            null
        }
    }
    
    /**
     * 读取附件数据
     */
    private fun readAttachmentData(attachment: Attachment): ByteArray? {
        return try {
            when (attachment) {
                is DatabaseAttachment -> {
                    if (attachment.hasData) {
                        org.thoughtcrime.securesms.database.SignalDatabase.attachments
                            .getAttachmentStream(attachment.attachmentId, 0)
                            .use { it.readBytes() }
                    } else {
                        null
                    }
                }
                
                is UriAttachment -> {
                    context.contentResolver.openInputStream(attachment.uri)
                        ?.use { it.readBytes() }
                }
                
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取附件数据失败: ${attachment.fileName}", e)
            null
        }
    }
    
    /**
     * 上传附件到TAP传输层
     */
    private suspend fun uploadAttachmentToTap(
        attachmentData: ByteArray,
        transportPath: String,
        attachment: Attachment
    ): Boolean {
        return try {
            // 获取可用的TransportProvider
            val enabledProviders = transportManager.getEnabledProviders()
            if (enabledProviders.isEmpty()) {
                Log.e(TAG, "没有可用的TransportProvider")
                return false
            }
            
            val provider = enabledProviders.first() // 使用第一个可用的provider
            
            // 从TransportChannelManager获取现有的metadata
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            
            // 尝试获取任何一个活跃通道的metadata
            val activeChannels = channelManager.getAllActiveChannels()
            if (activeChannels.isEmpty()) {
                Log.e(TAG, "没有活跃的传输通道")
                return false
            }
            
            // 使用第一个活跃通道的metadata
            val metadata = activeChannels.first().metadata
            
            // 通过TransportProvider上传文件
            val uploadResult = provider.uploadFile(attachmentData, transportPath, metadata)
            
            when (uploadResult) {
                is org.thoughtcrime.securesms.tap.TransportResult.Success -> {
                    Log.i(TAG, "TAP附件上传成功: path=$transportPath, size=${attachmentData.size}")
                    true
                }
                is org.thoughtcrime.securesms.tap.TransportResult.Failed -> {
                    Log.e(TAG, "TAP附件上传失败: path=$transportPath, error=${uploadResult.error}")
                    false
                }
                else -> {
                    Log.w(TAG, "TAP附件上传未知结果: path=$transportPath, result=${uploadResult::class.simpleName}")
                    false
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "上传附件到TAP异常: path=$transportPath", e)
            false
        }
    }
    
    /**
     * 构建TransportMessage
     */
    private fun buildTransportMessage(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        encryptedData: EncryptedMessageData
    ): org.thoughtcrime.securesms.tap.TransportMessage {
        
        // 构建内容元数据
        val contentMetadata = org.thoughtcrime.securesms.tap.TransportContentMetadata(
            originalSize = encryptedData.ciphertext.size.toLong(),
            compressionType = org.thoughtcrime.securesms.tap.TransportCompressionType.NONE,
            encryptionAlgorithm = "signal-protocol",
            sourceDeviceId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.deviceId,
            isSessionCipherEncrypted = true  // 私聊使用 SessionCipher 加密
        )
        
        // 构建附件列表
        val transportAttachments = outgoingMessage.attachments.mapIndexed { index, attachment ->
            val attachmentId = when (attachment) {
                is DatabaseAttachment -> attachment.attachmentId.id.toString()
                else -> "msg_${messageId}_att_${index}"
            }
            
            // 使用实际上传时的路径，确保与createTapAttachmentPointer完全一致
            val actualUploadPath = attachmentPaths[attachmentId]
            val fullTapPath = if (actualUploadPath != null) {
                // 使用实际上传的路径
                actualUploadPath
            } else {
                // 降级处理：重新构建路径（这种情况不应该发生）
                Log.w(TAG, "未找到附件的实际上传路径，重新构建: attachmentId=$attachmentId")
                val provider = transportManager.getEnabledProviders().firstOrNull()
                val basePath = provider?.getSendPath(recipient.requireAci().toString(), org.thoughtcrime.securesms.tap.TransportMessageType.MEDIA_MESSAGE)
                    ?: "/v2-channels/${recipient.requireAci()}/outbox/"
                val fallbackTimestamp = globalTimestamp ?: System.currentTimeMillis()
                val fallbackMessageId = globalMessageId ?: org.thoughtcrime.securesms.tap.TransportMessage.generateMessageId()
                // 文件名格式: timestamp_messageId.dat (时间戳在前，确保COS marker字典序正确)
                "${basePath}attachments/${fallbackTimestamp}_${fallbackMessageId}.dat"
            }
            
            org.thoughtcrime.securesms.tap.TransportAttachment(
                attachmentId = attachmentId,
                fileName = attachment.fileName ?: "attachment_${attachmentId}",
                mimeType = attachment.contentType ?: "application/octet-stream",
                size = attachment.size,
                fileHash = calculateAttachmentHashForTransport(attachment),
                transportPath = fullTapPath
            )
        }
        
        // 消息本身始终作为文本消息处理，附件单独存储在attachments目录
        val messageType = org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE
        
        return org.thoughtcrime.securesms.tap.TransportMessage(
            messageId = org.thoughtcrime.securesms.tap.TransportMessage.generateMessageId(),
            timestamp = outgoingMessage.sentTimeMillis,
            senderId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString(),
            recipientId = recipient.requireAci().toString(),
            messageType = messageType,
            signalCiphertext = Base64.encodeWithPadding(encryptedData.ciphertext),
            signalCiphertextType = encryptedData.ciphertextType,
            contentMetadata = contentMetadata,
            attachments = transportAttachments
        )
    }
    
    /**
     * 通过TAP传输层发送消息
     */
    private suspend fun sendViaTapTransport(
        transportMessage: org.thoughtcrime.securesms.tap.TransportMessage,
        recipient: Recipient
    ): org.thoughtcrime.securesms.tap.TransportResult {
        return try {
            Log.d(TAG, "通过TAP传输层发送: messageId=${transportMessage.messageId}")
            
            // 路由前诊断
            val routingResult = routeMessageWithDiagnostics(transportMessage, recipient.requireAci().toString())
            
            when (routingResult) {
                is org.thoughtcrime.securesms.tap.TransportResult.Success -> {
                    Log.i(TAG, "TAP路由成功: messageId=${transportMessage.messageId}")
                    routingResult
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.Failed -> {
                    Log.e(TAG, "TAP路由失败: messageId=${transportMessage.messageId}, error=${routingResult.error}")
                    routingResult
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.RetryScheduled -> {
                    Log.w(TAG, "TAP路由重试调度: messageId=${transportMessage.messageId}")
                    routingResult
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.PartialSuccess -> {
                    Log.w(TAG, "TAP路由部分成功: messageId=${transportMessage.messageId}")
                    routingResult
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "TAP传输异常: messageId=${transportMessage.messageId}", e)
            org.thoughtcrime.securesms.tap.TransportResult.failure(
                org.thoughtcrime.securesms.tap.TransportError.NETWORK_ERROR,
                true,
                "TAP传输异常: ${e.message}"
            )
        }
    }
    
    /**
     * 带诊断信息的消息路由
     */
    private suspend fun routeMessageWithDiagnostics(
        transportMessage: org.thoughtcrime.securesms.tap.TransportMessage,
        recipientAci: String
    ): org.thoughtcrime.securesms.tap.TransportResult {
        Log.d(TAG, "开始路由消息，进行详细诊断")
        
        // 1. 检查TransportManager状态
        if (!transportManager.isInitialized()) {
            Log.w(TAG, "路由时发现TransportManager未初始化，尝试重新初始化")
            val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
            tapInitializer.initializeSync(false)
        }
        
        // 2. 详细检查provider状态
        val availableProviders = transportManager.getAvailableProviders()
        val enabledProviders = transportManager.getEnabledProviders()
        
        Log.d(TAG, "路由诊断: 可用providers=${availableProviders.size}, 启用providers=${enabledProviders.size}")
        availableProviders.forEach { provider ->
            Log.d(TAG, "可用provider: ${provider.providerType} - ${provider.displayName}")
        }
        enabledProviders.forEach { provider ->
            Log.d(TAG, "启用provider: ${provider.providerType} - ${provider.displayName}")
        }
        
        // 3. 检查通道状态
        val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
        val activeChannels = channelManager.getActiveChannels(recipientAci)
        
        Log.d(TAG, "路由诊断: 活跃通道数=${activeChannels.size}")
        activeChannels.forEach { channel ->
            Log.d(TAG, "活跃通道: ${channel.channelId}, status=${channel.status}, provider=${channel.providerType}")
        }
        
        // 4. 检查Token状态（详细诊断）
        val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
        
        Log.d(TAG, "===== Token状态详细检查开始 =====")
        Log.d(TAG, "检查recipientAci: $recipientAci, 通道数: ${activeChannels.size}")
        
        for (channel in activeChannels) {
            Log.d(TAG, "检查通道: ${channel.channelId}, provider=${channel.providerType}")
            
            val receivedToken = tokenPool.getValidReceivedToken(recipientAci, channel.providerType)
            val sharedToken = tokenPool.getValidSharedToken(recipientAci, channel.providerType)
            
            if (receivedToken != null) {
                Log.d(TAG, "  ✓ receivedToken存在: tokenId=${receivedToken.tokenId}, type=${receivedToken.javaClass.simpleName}")
            } else {
                Log.w(TAG, "  ✗ receivedToken不存在")
                // 尝试用其他格式查询
                val allReceivedTokens = tokenPool.getAllValidReceivedTokens()
                Log.d(TAG, "  所有receivedToken数量: ${allReceivedTokens.size}")
                allReceivedTokens.filter { (_, token) -> token.providerType == channel.providerType }.forEach { (recipientId, token) ->
                    Log.d(TAG, "    - 其他receivedToken: recipientId=$recipientId, tokenId=${token.tokenId}")
                }
            }
            
            if (sharedToken != null) {
                Log.d(TAG, "  ✓ sharedToken存在: tokenId=${sharedToken.tokenId}, type=${sharedToken.javaClass.simpleName}")
            } else {
                Log.w(TAG, "  ✗ sharedToken不存在")
                // 尝试用其他格式查询
                val allSharedTokens = tokenPool.getAllValidSharedTokens()
                Log.d(TAG, "  所有sharedToken数量: ${allSharedTokens.size}")
                allSharedTokens.filter { (_, token) -> token.providerType == channel.providerType }.forEach { (recipientId, token) ->
                    Log.d(TAG, "    - 其他sharedToken: recipientId=$recipientId, tokenId=${token.tokenId}")
                }
            }
            
            Log.d(TAG, "Token状态检查: provider=${channel.providerType}, hasReceived=${receivedToken != null}, hasShared=${sharedToken != null}")
        }
        Log.d(TAG, "===== Token状态详细检查结束 =====")
        
        // 5. 尝试路由
        val routeResult = transportManager.routeMessage(transportMessage, recipientAci)
        
        if (routeResult is org.thoughtcrime.securesms.tap.TransportResult.Failed) {
            // 输出详细的系统诊断信息
            Log.e(TAG, "TAP路由失败后的系统诊断:")
            val diagnostics = diagnoseTapSystemState(recipientAci)
            Log.e(TAG, diagnostics)
            
            // 尝试自动修复
            Log.w(TAG, "TAP路由失败，尝试自动修复...")
            try {
                val fixResult = autoFixTapSystemIssues(recipientAci)
                if (fixResult) {
                    Log.i(TAG, "自动修复完成，重新尝试路由...")
                    // 重新尝试路由
                    val retryResult = transportManager.routeMessage(transportMessage, recipientAci)
                    if (retryResult is org.thoughtcrime.securesms.tap.TransportResult.Success) {
                        Log.i(TAG, "自动修复后路由成功")
                        return retryResult
                    } else {
                        Log.w(TAG, "自动修复后路由仍然失败")
                    }
                } else {
                    Log.w(TAG, "自动修复未能解决问题")
                }
            } catch (fixException: Exception) {
                Log.e(TAG, "自动修复过程异常", fixException)
            }
            
            // 重新检查状态，看是否在路由过程中发生了变化
            val newEnabledProviders = transportManager.getEnabledProviders()
            Log.e(TAG, "路由失败后的启用providers=${newEnabledProviders.size}")
            newEnabledProviders.forEach { provider ->
                Log.e(TAG, "路由失败后的启用provider: ${provider.providerType} - ${provider.displayName}")
            }
        }
        
        return routeResult
    }
    
    /**
     * 处理附件转换
     */
    private fun processAttachments(attachments: List<Attachment>, messageId: Long): List<TransportAttachment> {
        if (attachments.isEmpty()) {
            return emptyList()
        }
        
        return attachments.mapIndexed { index, attachment ->
            val attachmentId = "${messageId}_${index}_${System.currentTimeMillis()}"
            val fileName = attachment.fileName ?: "attachment_$attachmentId"
            val mimeType = attachment.contentType ?: "application/octet-stream"
            val size = attachment.size
            
            // 计算文件哈希
            val fileHash = try {
                calculateAttachmentHashForTransport(attachment)
            } catch (e: Exception) {
                Log.w(TAG, "计算附件哈希失败: $attachmentId", e)
                null
            }
            
            TransportAttachment(
                attachmentId = attachmentId,
                fileName = fileName,
                mimeType = mimeType,
                size = size,
                fileHash = fileHash,
                transportPath = null // 将在上传时设置
            )
        }
    }
    
    // calculateAttachmentHash方法已合并到calculateAttachmentHashForTransport

    /**
     * 计算附件哈希（用于TransportMessage）
     */
    private fun calculateAttachmentHashForTransport(attachment: Attachment): String? {
        return try {
            when (attachment) {
                is DatabaseAttachment -> {
                    // 使用已有的摘要
                    attachment.remoteDigest?.let { Base64.encodeWithPadding(it) }
                }
                is UriAttachment -> {
                    // 读取文件内容并计算真实的SHA-256哈希
                    try {
                        val inputStream = PartAuthority.getAttachmentStream(context, attachment.uri)
                        val data = inputStream.readBytes()
                        val digest = calculateDigest(data)
                        Base64.encodeWithPadding(digest)
                    } catch (e: Exception) {
                        Log.e(TAG, "读取附件文件失败，使用URI哈希作为备选", e)
                        attachment.uri.toString().hashCode().toString()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "计算哈希失败", e)
            null
        }
    }
    
    /**
     * 加密消息数据
     */
    data class EncryptedMessageData(
        val ciphertext: ByteArray,
        val ciphertextType: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedMessageData

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (ciphertextType != other.ciphertextType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + ciphertextType
            return result
        }
    }
    
    /**
     * 计算数据的SHA-256摘要
     */
    private fun calculateDigest(data: ByteArray): ByteArray {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(data)
        } catch (e: Exception) {
            Log.e(TAG, "计算数据摘要失败", e)
            ByteArray(0)
        }
    }
    
    /**
     * 发送前状态验证和同步
     * 
     * @param recipient 接收者Recipient对象
     * @return 是否验证通过
     */
    private suspend fun validateAndSyncStateBeforeSend(recipient: Recipient): Boolean {
        val recipientId = recipient.id.toString()  // RecipientId格式，如 "RecipientId::8"
        val recipientAci = recipient.requireAci().toString()  // ACI格式，如 "c716a84d-..."
        
        Log.d(TAG, "发送前状态验证和同步: recipientId=$recipientId, recipientAci=$recipientAci")
        
        return try {
            // 1. 确保TransportManager已初始化
            if (!transportManager.isInitialized()) {
                Log.w(TAG, "发送前发现TransportManager未初始化，强制初始化")
                val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
                tapInitializer.initializeSync(true)
            }
            
            // 2. 验证通道状态（使用ACI格式，与Token保存格式一致）
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val activeChannels = channelManager.getActiveChannels(recipientAci)
            val fullActiveChannels = activeChannels.filter { it.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FULL_ACTIVE }
            
            if (fullActiveChannels.isEmpty()) {
                Log.w(TAG, "发送前验证失败：没有FULL_ACTIVE状态的通道, recipientId=$recipientId, recipientAci=$recipientAci")
                return false
            }
            
            // 3. 验证Token状态（使用ACI格式查询，与Token保存格式一致）
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            for (channel in fullActiveChannels) {
                val hasReceivedToken = tokenPool.getValidReceivedToken(recipientAci, channel.providerType) != null
                val hasSharedToken = tokenPool.getValidSharedToken(recipientAci, channel.providerType) != null
                
                if (!hasReceivedToken || !hasSharedToken) {
                    Log.w(TAG, "发送前验证失败：Token状态不完整 provider=${channel.providerType}, hasReceived=$hasReceivedToken, hasShared=$hasSharedToken, recipientId=$recipientId, recipientAci=$recipientAci")
                    return false
                }
            }
            
            // 4. 验证Provider状态
            val enabledProviders = transportManager.getEnabledProviders()
            val matchingProviders = enabledProviders.filter { provider ->
                fullActiveChannels.any { channel -> channel.providerType == provider.providerType }
            }
            
            if (matchingProviders.isEmpty()) {
                Log.w(TAG, "发送前验证失败：没有匹配的启用Provider")
                return false
            }
            
            Log.i(TAG, "发送前状态验证通过：${fullActiveChannels.size}个FULL_ACTIVE通道，${matchingProviders.size}个匹配Provider, recipientId=$recipientId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "发送前状态验证异常: recipientId=$recipientId", e)
            return false
        }
    }
    
    /**
     * 获取与指定接收者匹配的可用Provider
     */
    private fun getAvailableProviderForRecipient(recipientAci: String): org.thoughtcrime.securesms.tap.TransportProvider? {
        return try {
            // 使用与TapMessageSendIntegrator.canUseTapForSending相同的逻辑
            if (!transportManager.isInitialized()) {
                Log.w(TAG, "传输管理器未初始化，尝试按需初始化")
                val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
                tapInitializer.initializeSync(false)
            }
            
            val enabledProviders = transportManager.getEnabledProviders()
            if (enabledProviders.isEmpty()) {
                Log.w(TAG, "没有启用的Provider")
                return null
            }
            
            // 检查是否有与recipient匹配的活跃通道
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val activeChannels = channelManager.getActiveChannels(recipientAci)
            
            // 优先选择有活跃通道的provider
            for (channel in activeChannels) {
                if (channel.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FULL_ACTIVE) {
                    val provider = enabledProviders.find { it.providerType == channel.providerType }
                    if (provider != null) {
                        Log.d(TAG, "找到匹配的活跃provider: ${provider.providerType}")
                        return provider
                    }
                }
            }
            
            Log.w(TAG, "未找到匹配的活跃provider")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "获取可用provider失败", e)
            return null
        }
    }

    /**
     * 系统诊断方法 - 全面检查TAP系统状态
     */
    private fun diagnoseTapSystemState(recipientAci: String): String {
        val sb = StringBuilder("=== TAP系统状态诊断 ===\n")
        
        try {
            // 1. TransportManager状态
            val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
            sb.append("1. TransportManager状态:\n")
            sb.append("   - 初始化状态: ${transportManager.isInitialized()}\n")
            
            // 2. Provider状态
            try {
                val availableProviders = transportManager.getAvailableProviders()
                val enabledProviders = transportManager.getEnabledProviders()
                sb.append("2. Provider状态:\n")
                sb.append("   - 可用Provider数: ${availableProviders.size}\n")
                sb.append("   - 启用Provider数: ${enabledProviders.size}\n")
                
                availableProviders.forEach { provider ->
                    sb.append("   - 可用: ${provider.providerType} (${provider.displayName})\n")
                }
                enabledProviders.forEach { provider ->
                    sb.append("   - 启用: ${provider.providerType} (${provider.displayName})\n")
                }
            } catch (providerEx: Exception) {
                sb.append("   - Provider状态检查异常: ${providerEx.message}\n")
            }
            
            // 3. 通道状态
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            sb.append("3. 通道状态:\n")
            try {
                val activeChannels = channelManager.getActiveChannels(recipientAci)
                sb.append("   - 活跃通道数: ${activeChannels.size}\n")
                
                activeChannels.forEach { channel ->
                    sb.append("   - 通道: ${channel.channelId}\n")
                    sb.append("     * 状态: ${channel.status}\n")
                    sb.append("     * Provider: ${channel.providerType}\n")
                    sb.append("     * recipientId: ${channel.recipientId}\n")
                    sb.append("     * 是否活跃: ${channel.isActive()}\n")
                }
                
                // 检查所有通道（不仅是活跃的）
                val allChannels = channelManager.getAllActiveChannels()
                val relevantChannels = allChannels.filter { it.recipientId == recipientAci }
                if (relevantChannels.size != activeChannels.size) {
                    sb.append("   - 注意: 总通道数(${relevantChannels.size}) != getActiveChannels结果(${activeChannels.size})\n")
                    relevantChannels.forEach { channel ->
                        sb.append("   - 所有通道: ${channel.channelId} (${channel.status})\n")
                    }
                }
            } catch (channelEx: Exception) {
                sb.append("   - 通道状态检查异常: ${channelEx.message}\n")
            }
            
            // 4. Token状态
            val tokenPool = org.thoughtcrime.securesms.tap.TransportTokenPool.getInstance(context)
            sb.append("4. Token状态:\n")
            try {
                val receivedTokens = tokenPool.getAllValidReceivedTokens()
                val sharedTokens = tokenPool.getAllValidSharedTokens()
                sb.append("   - 接收Token数: ${receivedTokens.size}\n")
                sb.append("   - 共享Token数: ${sharedTokens.size}\n")
                
                receivedTokens.filter { it.first == recipientAci }.forEach { (recipientId, token) ->
                    sb.append("   - 接收Token: ${token.tokenId} (${token.providerType})\n")
                }
                sharedTokens.filter { it.first == recipientAci }.forEach { (recipientId, token) ->
                    sb.append("   - 共享Token: ${token.tokenId} (${token.providerType})\n")
                }
            } catch (tokenEx: Exception) {
                sb.append("   - Token状态检查异常: ${tokenEx.message}\n")
            }
            
            // 5. 轮询状态
            sb.append("5. 轮询状态:\n")
            try {
                val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                // 由于没有直接的状态查询方法，我们通过尝试启动来检查
                val startResult = pollingService.startPolling()
                sb.append("   - 轮询服务启动结果: $startResult\n")
                
                // 尝试添加轮询目标来验证功能
                val channels = channelManager.getActiveChannels(recipientAci)
                if (channels.isNotEmpty()) {
                    val testChannel = channels.first()
                    if (testChannel.metadata != null) {
                        val addResult = pollingService.addPollingTarget(recipientAci, testChannel.metadata!!, testChannel)
                        sb.append("   - 轮询目标添加测试: $addResult\n")
                    } else {
                        sb.append("   - 轮询目标添加测试: 失败 (元数据为空)\n")
                    }
                } else {
                    sb.append("   - 轮询目标添加测试: 跳过 (无活跃通道)\n")
                }
            } catch (pollingEx: Exception) {
                sb.append("   - 轮询状态检查异常: ${pollingEx.message}\n")
            }
            
            // 6. 配置状态
            sb.append("6. 配置状态:\n")
            try {
                val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                val configManager = org.thoughtcrime.securesms.tap.TransportProviderConfigManager.getInstance(context)
                val configuredProviders = configManager.getConfiguredProviders()
                val enabledProviders = tapValues.getEnabledProviders()
                sb.append("   - 已配置Provider: ${configuredProviders.joinToString(", ")}\n")
                sb.append("   - 持久化启用Provider: ${enabledProviders.joinToString(", ")}\n")
                sb.append("   - 应执行初始化: ${tapValues.shouldPerformInitialization()}\n")
            } catch (configEx: Exception) {
                sb.append("   - 配置状态检查异常: ${configEx.message}\n")
            }
            
        } catch (e: Exception) {
            sb.append("诊断过程异常: ${e.message}\n")
        }
        
        sb.append("=== 诊断完成 ===")
        return sb.toString()
    }
    
    /**
     * 自动修复TAP系统问题
     */
    private suspend fun autoFixTapSystemIssues(recipientAci: String): Boolean {
        Log.i(TAG, "开始自动修复TAP系统问题")
        
        try {
            var fixedIssues = 0
            
            // 1. 修复TransportManager初始化问题
            val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
            if (!transportManager.isInitialized()) {
                Log.w(TAG, "自动修复: TransportManager未初始化")
                try {
                    val tapInitializer = org.thoughtcrime.securesms.tap.integration.TapModuleInitializer.getInstance(context)
                    tapInitializer.initializeSync(forceReinit = true)
                    if (transportManager.isInitialized()) {
                        Log.i(TAG, "自动修复成功: TransportManager已初始化")
                        fixedIssues++
                    }
                } catch (initEx: Exception) {
                    Log.e(TAG, "自动修复失败: TransportManager初始化异常", initEx)
                }
            }
            
            // 2. 修复Provider配置问题
            val enabledProviders = transportManager.getEnabledProviders()
            val availableProviders = transportManager.getAvailableProviders()
            if (enabledProviders.isEmpty() && availableProviders.isNotEmpty()) {
                Log.w(TAG, "自动修复: 有可用Provider但未启用")
                try {
                    val tapValues = org.thoughtcrime.securesms.keyvalue.SignalStore.tap
                    val activeProviderTypes = availableProviders.map { it.providerType }.toSet()
                    tapValues.setEnabledProviders(activeProviderTypes)
                    Log.i(TAG, "自动修复成功: 已启用可用Provider: ${activeProviderTypes.joinToString(", ")}")
                    fixedIssues++
                } catch (providerEx: Exception) {
                    Log.e(TAG, "自动修复失败: Provider启用异常", providerEx)
                }
            }
            
            // 3. 修复轮询问题
            try {
                val pollingService = org.thoughtcrime.securesms.tap.polling.TapPollingService.getInstance(context)
                val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
                val channels = channelManager.getActiveChannels(recipientAci)
                
                if (channels.isNotEmpty()) {
                    val startResult = pollingService.startPolling()
                    if (startResult) {
                        channels.forEach { channel ->
                            if (channel.metadata != null) {
                                val addResult = pollingService.addPollingTarget(recipientAci, channel.metadata!!, channel)
                                if (addResult) {
                                    Log.i(TAG, "自动修复成功: 已添加轮询目标")
                                    fixedIssues++
                                }
                            }
                        }
                    }
                }
            } catch (pollingEx: Exception) {
                Log.e(TAG, "自动修复失败: 轮询启动异常", pollingEx)
            }
            
            Log.i(TAG, "自动修复完成，修复问题数: $fixedIssues")
            return fixedIssues > 0
            
        } catch (e: Exception) {
            Log.e(TAG, "自动修复过程异常", e)
            return false
        }
    }
}

/**
 * TAP Signal发送结果
 */
sealed class TapSignalSendResult {
    data class Success(val transportPath: String?, val providerType: String) : TapSignalSendResult()
    data class Failed(val reason: String) : TapSignalSendResult()
    data class RetryLater(val reason: String) : TapSignalSendResult()
} 