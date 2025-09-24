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
        
        fun getInstance(context: Context): TapSignalServiceAdapter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapSignalServiceAdapter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val transportManager = TransportManager.getInstance(context)
    private val signalServiceMessageSender = AppDependencies.signalServiceMessageSender
    
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
            
            // 1. 构建Signal数据消息
            val signalDataMessage = buildSignalDataMessage(outgoingMessage)
            val signalServiceAddress = SignalServiceAddress(recipient.requireServiceId())
            
            // 2. 使用Signal进行加密（不发送）
            val encryptedData = encryptWithSignal(signalServiceAddress, signalDataMessage)
            if (encryptedData == null) {
                Log.e(TAG, "Signal加密失败: messageId=$messageId")
                return TapSignalSendResult.Failed("Signal加密失败")
            }
            
            Log.d(TAG, "Signal加密成功: messageId=$messageId, ciphertextLength=${encryptedData.ciphertext.size}")
            
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
                    TapSignalSendResult.Success(
                        transportPath = sendResult.metadata?.get("remotePath") as? String,
                        providerType = sendResult.metadata?.get("providerType") as? String ?: "unknown"
                    )
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.Failed -> {
                    Log.e(TAG, "TAP传输失败: messageId=$messageId, error=${sendResult.error}")
                    if (sendResult.retryable) {
                        TapSignalSendResult.RetryLater("TAP传输失败: ${sendResult.errorMessage}")
                    } else {
                        TapSignalSendResult.Failed("TAP传输失败: ${sendResult.errorMessage}")
                    }
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.RetryScheduled -> {
                    Log.w(TAG, "TAP传输重试: messageId=$messageId")
                    TapSignalSendResult.RetryLater("TAP传输重试")
                }
                
                is org.thoughtcrime.securesms.tap.TransportResult.PartialSuccess -> {
                    Log.w(TAG, "TAP传输部分成功: messageId=$messageId")
                    TapSignalSendResult.Success(
                        transportPath = sendResult.metadata?.get("remotePath") as? String,
                        providerType = sendResult.metadata?.get("providerType") as? String ?: "unknown"
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Signal加密+Tap传输异常: messageId=$messageId", e)
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
                    val localDeviceId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.deviceId
                    val sessionCipher = org.signal.libsignal.protocol.SessionCipher(
                        protocolStore,
                        org.signal.libsignal.protocol.SignalProtocolAddress(
                            address.serviceId.toString(),
                            localDeviceId  // 使用真实的设备ID
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
                    .contentType(attachment.contentType)
                    .size(attachmentData.size)
                    .fileName(attachment.fileName.orElse(""))
                    // 使用消息相关的ID标识这是Tap传输的附件
                    .cdnKey("tap_attachment:${System.currentTimeMillis()}_${attachmentData.hashCode()}")
                    .digest(calculateDigest(attachmentData).toByteString())
                    .build()
            }
            is SignalServiceAttachmentPointer -> {
                // 指针附件直接转换
                val builder = org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
                    .contentType(attachment.contentType)
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
                        // 其他类型的默认处理
                    }
                }
                
                builder.build()
            }
            else -> {
                // 其他类型的默认处理
                org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
                    .contentType(attachment.contentType)
                    .fileName("")
                    .build()
            }
        }
    }
    
    /**
     * 构建Signal数据消息
     */
    private fun buildSignalDataMessage(outgoingMessage: OutgoingMessage): SignalServiceDataMessage {
        Log.d(TAG, "构建Signal数据消息: body长度=${outgoingMessage.body.length}, 附件数=${outgoingMessage.attachments.size}")
        
        val builder = SignalServiceDataMessage.newBuilder()
            .withBody(outgoingMessage.body)
            .withTimestamp(outgoingMessage.sentTimeMillis)
        
        // 处理过期时间
        if (outgoingMessage.expiresIn > 0) {
            builder.withExpiration((outgoingMessage.expiresIn / 1000).toInt())
        }
        
        // 处理附件
        if (outgoingMessage.attachments.isNotEmpty()) {
            val signalAttachments = outgoingMessage.attachments.mapNotNull { attachment ->
                convertToSignalServiceAttachment(attachment)
            }
            if (signalAttachments.isNotEmpty()) {
                builder.withAttachments(signalAttachments)
            }
        }
        
        return builder.build()
    }
    
    /**
     * 转换附件为Signal格式
     */
    private fun convertToSignalServiceAttachment(attachment: Attachment): SignalServiceAttachment? {
        return try {
            when (attachment) {
                is DatabaseAttachment -> {
                    // 对于数据库附件，创建流式附件
                    if (attachment.hasData) {
                        val inputStream = org.thoughtcrime.securesms.database.SignalDatabase.attachments
                            .getAttachmentStream(attachment.attachmentId, 0)
                        
                        SignalServiceAttachment.newStreamBuilder()
                            .withContentType(attachment.contentType)
                            .withLength(attachment.size)
                            .withFileName(attachment.fileName)
                            .withStream(inputStream)
                            .build()
                    } else {
                        Log.w(TAG, "数据库附件没有数据: ${attachment.attachmentId}")
                        null
                    }
                }
                
                is UriAttachment -> {
                    // 对于URI附件，从URI创建流
                    val inputStream = context.contentResolver.openInputStream(attachment.uri)
                    if (inputStream != null) {
                        SignalServiceAttachment.newStreamBuilder()
                            .withContentType(attachment.contentType)
                            .withLength(attachment.size)
                            .withFileName(attachment.fileName)
                            .withStream(inputStream)
                            .build()
                    } else {
                        Log.w(TAG, "无法打开URI: ${attachment.uri}")
                        null
                    }
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
            sourceDeviceId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.deviceId
        )
        
        // 构建附件列表
        val transportAttachments = outgoingMessage.attachments.mapIndexed { index, attachment ->
            val attachmentId = when (attachment) {
                is DatabaseAttachment -> attachment.attachmentId.id.toString()
                else -> "msg_${messageId}_att_${index}"
            }
            
            org.thoughtcrime.securesms.tap.TransportAttachment(
                attachmentId = attachmentId,
                fileName = attachment.fileName ?: "attachment_${attachmentId}",
                mimeType = attachment.contentType ?: "application/octet-stream",
                size = attachment.size,
                fileHash = calculateAttachmentHashForTransport(attachment),
                transportPath = "attachments/${attachmentId}/${attachment.fileName ?: "data"}"
            )
        }
        
        return org.thoughtcrime.securesms.tap.TransportMessage(
            messageId = org.thoughtcrime.securesms.tap.TransportMessage.generateMessageId(),
            timestamp = outgoingMessage.sentTimeMillis,
            senderId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString(),
            recipientId = recipient.requireAci().toString(),
            messageType = org.thoughtcrime.securesms.tap.TransportMessageType.TEXT_MESSAGE,
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
            
            // 获取传输路由
            val routingResult = transportManager.routeMessage(transportMessage, recipient.requireAci().toString())
            
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
}

/**
 * TAP Signal发送结果
 */
sealed class TapSignalSendResult {
    data class Success(val transportPath: String?, val providerType: String) : TapSignalSendResult()
    data class Failed(val reason: String) : TapSignalSendResult()
    data class RetryLater(val reason: String) : TapSignalSendResult()
} 