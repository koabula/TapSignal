package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.database.IpfsContentTable
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.protocol.TapV3MessageCodec
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager
import org.thoughtcrime.securesms.tapv3.push.UnifiedPushProvider
import org.thoughtcrime.securesms.tapv3.utils.TapV3Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TapV3SendIntegrator private constructor(
    private val context: Context
) {
    
    private val kPushManager = KPushManager.getInstance(context)
    private val pushEndpointManager = PushEndpointManager.getInstance(context)
    private val ipfsGatewayManager = IpfsGatewayManager.getInstance(context)
    private val unifiedPushProvider = UnifiedPushProvider.getInstance(context)
    private val channelTable = SignalDatabase.tapV3Channels
    private val ipfsContentTable = SignalDatabase.ipfsContent
    
    data class SendResult(
        val success: Boolean,
        val error: String? = null,
        val transportMethod: TransportMethod? = null,
        val messageCid: String? = null,
        val attachmentCids: List<String> = emptyList()
    )

    data class GroupSendResult(
        val successCount: Int,
        val failureCount: Int,
        val failedMembers: Map<String, String>, // memberId -> errorMessage
        val transportMethod: TransportMethod? = null,
        val messageCid: String? = null
    )
    
    enum class TransportMethod {
        INLINE,
        IPFS
    }
    
    /**
     * 获取本地用户的 ACI 作为 senderId
     */
    private fun getLocalSenderId(): String {
        val localRecipient = Recipient.self()
        val aci = localRecipient.serviceId.orElse(null)
            ?: throw IllegalStateException("Local user has no ServiceId")
        return aci.toString()
    }
    
    suspend fun sendMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        attachments: List<Attachment> = emptyList()
    ): SendResult {
        TapV3Logger.i(TAG, "Sending message to recipient: ${recipientId.take(8)}...")
        
        val channel = channelTable.getChannel(recipientId)
        if (channel == null) {
            TapV3Logger.e(TAG, "Channel not found for recipient: ${recipientId.take(8)}...")
            return SendResult(
                success = false,
                error = "Tap v3 channel not established. Please complete handshake first."
            )
        }
        
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
            TapV3Logger.e(TAG, "Channel not active: ${channel.status}")
            return SendResult(
                success = false,
                error = "Tap v3 channel not active: ${channel.status}"
            )
        }
        
        // 获取对方的 k_push，用于加密要发送的消息
        val peerKPushResult = kPushManager.getPeerKPush(recipientId)
        if (peerKPushResult.isFailure()) {
            TapV3Logger.e(TAG, "Peer k_push not found for recipient: ${recipientId.take(8)}...")
            return SendResult(
                success = false,
                error = "Peer k_push not found"
            )
        }
        val peerKPush = (peerKPushResult as TapV3Result.Success).data
        
        val senderId = try {
            getLocalSenderId()
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to get local sender ID", e)
            return SendResult(
                success = false,
                error = "Failed to get local sender ID: ${e.message}"
            )
        }
        
        return if (attachments.isEmpty() && TapV3MessageCodec.shouldUseInline(signalEncrypted.size)) {
            sendInlineMessage(recipientId, signalEncrypted, peerKPush, channel.pushEndpoint, senderId)
        } else {
            sendIpfsMessage(recipientId, signalEncrypted, attachments, peerKPush, channel.pushEndpoint, senderId)
        }
    }

    suspend fun sendGroupMessage(
        groupId: String,
        memberIds: List<String>,
        signalEncrypted: ByteArray,
        attachments: List<Attachment> = emptyList()
    ): GroupSendResult {
        TapV3Logger.i(TAG, "Sending group message: groupId=$groupId, members=${memberIds.size}")
        
        val senderId = try {
            getLocalSenderId()
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to get local sender ID", e)
            return GroupSendResult(0, memberIds.size, memberIds.associateWith { "Failed to get local sender ID" })
        }

        val useInline = attachments.isEmpty() && TapV3MessageCodec.shouldUseInline(signalEncrypted.size)
        
        val payload: TapV3Payload
        val transportMethod: TransportMethod
        var messageCid: String? = null
        // We track attachmentCids for logging/result, but they are inside payload
        
        if (useInline) {
            TapV3Logger.d(TAG, "Using INLINE transport for group message")
            payload = TapV3Payload.Inline(encrypted = signalEncrypted)
            transportMethod = TransportMethod.INLINE
        } else {
            TapV3Logger.d(TAG, "Using IPFS transport for group message")
            transportMethod = TransportMethod.IPFS
            
            // Upload content once (using groupId as recipientId for ownership)
            val ipfsResult = prepareIpfsPayload(groupId, signalEncrypted, attachments)
            if (ipfsResult.isFailure()) {
                val failure = ipfsResult as TapV3Result.Failure
                return GroupSendResult(0, memberIds.size, memberIds.associateWith { "IPFS upload failed: ${failure.message}" })
            }
            
            val ipfsData = (ipfsResult as TapV3Result.Success).data
            payload = ipfsData.payload
            messageCid = ipfsData.messageCid
        }

        // Fan-out
        var successCount = 0
        var failureCount = 0
        val failedMembers = mutableMapOf<String, String>()
        
        try {
            coroutineScope {
                 val deferreds = memberIds.map { memberId ->
                     async {
                         memberId to sendToMember(memberId, payload, senderId)
                     }
                 }
                 
                 deferreds.forEach { deferred ->
                     val (memberId, result) = deferred.await()
                     if (result.success) {
                         successCount++
                     } else {
                         failureCount++
                         failedMembers[memberId] = result.error ?: "Unknown error"
                     }
                 }
            }
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Error during group fan-out", e)
             return GroupSendResult(successCount, memberIds.size - successCount, failedMembers.apply { 
                 memberIds.filter { !this.containsKey(it) }.forEach { put(it, "Fan-out error: ${e.message}") }
             })
        }

        return GroupSendResult(
            successCount = successCount,
            failureCount = failureCount,
            failedMembers = failedMembers,
            transportMethod = transportMethod,
            messageCid = messageCid
        )
    }

    private suspend fun sendToMember(
        recipientId: String,
        payload: TapV3Payload,
        senderId: String
    ): SendResult {
        val channel = channelTable.getChannel(recipientId)
        if (channel == null) {
            return SendResult(false, "Channel not found")
        }
        // Allow sending to GROUP_ONLY channels for group messages
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE && 
            channel.status != TapV3ChannelTable.ChannelStatus.GROUP_ONLY) {
            return SendResult(false, "Channel not active: ${channel.status}")
        }

        val peerKPushResult = kPushManager.getPeerKPush(recipientId)
        if (peerKPushResult.isFailure()) {
             return SendResult(false, "Peer k_push not found")
        }
        val peerKPush = (peerKPushResult as TapV3Result.Success).data

        return dispatchPush(payload, peerKPush, channel.pushEndpoint, senderId)
    }

    private data class IpfsPreparationData(
        val payload: TapV3Payload.IpfsRefs,
        val messageCid: String?,
        val attachmentCids: List<String>
    )

    private suspend fun prepareIpfsPayload(
        recipientId: String, // Can be groupId
        signalEncrypted: ByteArray,
        attachments: List<Attachment>
    ): TapV3Result<IpfsPreparationData> {
        var messageCid: String? = null
        val attachmentCids = mutableListOf<String>()
        val attachmentRefs = mutableListOf<TapV3Payload.AttachmentRef>()
        
        try {
            if (attachments.isNotEmpty()) {
                for (attachment in attachments) {
                    val attachmentResult = uploadAttachment(attachment, recipientId)
                    if (attachmentResult.isFailure()) {
                        cleanupUploadedContent(messageCid, attachmentCids)
                        return TapV3Result.Failure((attachmentResult as TapV3Result.Failure).error, attachmentResult.message)
                    }
                    val ref = (attachmentResult as TapV3Result.Success).data
                    attachmentRefs.add(ref)
                    attachmentCids.add(ref.cid)
                }
            }
            
            if (signalEncrypted.isNotEmpty()) {
                val uploadResult = ipfsGatewayManager.upload(signalEncrypted)
                if (uploadResult.isFailure()) {
                    cleanupUploadedContent(messageCid, attachmentCids)
                    return TapV3Result.Failure((uploadResult as TapV3Result.Failure).error, uploadResult.message)
                }
                messageCid = (uploadResult as TapV3Result.Success).data
                
                val expiresAt = System.currentTimeMillis() + 
                    TimeUnit.DAYS.toMillis(TapV3Constants.IPFS_PIN_DURATION_DAYS_MESSAGE.toLong())
                
                ipfsContentTable.insertContent(
                    cid = messageCid!!,
                    contentType = IpfsContentTable.ContentType.MESSAGE,
                    sizeBytes = signalEncrypted.size.toLong(),
                    expiresAt = expiresAt,
                    recipientId = recipientId
                )
            }
            
            return TapV3Result.Success(IpfsPreparationData(
                TapV3Payload.IpfsRefs(messageCid, attachmentRefs),
                messageCid,
                attachmentCids
            ))
        } catch (e: Exception) {
            cleanupUploadedContent(messageCid, attachmentCids)
            return TapV3Result.Failure(TapV3Error.UNKNOWN_ERROR, e.message ?: "Unknown error")
        }
    }

    private suspend fun dispatchPush(
        payload: TapV3Payload,
        peerKPush: ByteArray,
        endpoint: String,
        senderId: String
    ): SendResult {
        val encodeResult = TapV3MessageCodec.encodeMessage(payload, peerKPush, senderId)
        if (encodeResult.isFailure()) {
            return SendResult(false, "Encode failed: ${(encodeResult as TapV3Result.Failure).message}")
        }
        val encoded = (encodeResult as TapV3Result.Success).data
        
        val sendResult = unifiedPushProvider.send(
            endpoint = endpoint,
            payload = encoded.base64Data.toByteArray(Charsets.UTF_8)
        )
        
        return if (sendResult.isSuccess()) {
            SendResult(true)
        } else {
            SendResult(false, "Push failed: ${(sendResult as TapV3Result.Failure).message}")
        }
    }
    
    private suspend fun sendInlineMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        peerKPush: ByteArray,
        endpoint: String,
        senderId: String
    ): SendResult {
        TapV3Logger.d(TAG, "Sending inline message: ${signalEncrypted.size} bytes")
        
        val payload = TapV3Payload.Inline(encrypted = signalEncrypted)
        
        val result = dispatchPush(payload, peerKPush, endpoint, senderId)
        return if (result.success) {
            TapV3Logger.i(TAG, "Inline message sent successfully")
            SendResult(true, transportMethod = TransportMethod.INLINE)
        } else {
            TapV3Logger.e(TAG, "Failed to send inline message: ${result.error}")
            result
        }
    }
    
    private suspend fun sendIpfsMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        attachments: List<Attachment>,
        peerKPush: ByteArray,
        endpoint: String,
        senderId: String
    ): SendResult {
        TapV3Logger.d(TAG, "Sending IPFS message: ${signalEncrypted.size} bytes, ${attachments.size} attachments")
        
        val prepResult = prepareIpfsPayload(recipientId, signalEncrypted, attachments)
        if (prepResult.isFailure()) {
             val failure = prepResult as TapV3Result.Failure
             TapV3Logger.e(TAG, "IPFS preparation failed: ${failure.message}")
             return SendResult(false, "IPFS preparation failed: ${failure.message}")
        }
        
        val ipfsData = (prepResult as TapV3Result.Success).data
        val result = dispatchPush(ipfsData.payload, peerKPush, endpoint, senderId)
        
        return if (result.success) {
            TapV3Logger.i(TAG, "IPFS message sent successfully: messageCid=${ipfsData.messageCid}, " +
                         "attachmentCids=${ipfsData.attachmentCids.size}")
            SendResult(
                success = true,
                transportMethod = TransportMethod.IPFS,
                messageCid = ipfsData.messageCid,
                attachmentCids = ipfsData.attachmentCids
            )
        } else {
            TapV3Logger.e(TAG, "Failed to send IPFS message: ${result.error}")
            // Original logic did not cleanup on push failure, keeping it consistent.
            result
        }
    }
    
    private suspend fun uploadAttachment(
        attachment: Attachment,
        recipientId: String
    ): TapV3Result<TapV3Payload.AttachmentRef> {
        val attachmentData = readAttachmentData(attachment)
            ?: return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Failed to read attachment data"
            )
        
        val uploadResult = ipfsGatewayManager.upload(attachmentData)
        if (uploadResult.isFailure()) {
            return TapV3Result.Failure(
                (uploadResult as TapV3Result.Failure).error,
                uploadResult.message,
                uploadResult.cause
            )
        }
        
        val cid = (uploadResult as TapV3Result.Success).data
        
        val expiresAt = System.currentTimeMillis() + 
            TimeUnit.DAYS.toMillis(TapV3Constants.IPFS_PIN_DURATION_DAYS_ATTACHMENT.toLong())
        
        ipfsContentTable.insertContent(
            cid = cid,
            contentType = IpfsContentTable.ContentType.ATTACHMENT,
            sizeBytes = attachmentData.size.toLong(),
            expiresAt = expiresAt,
            recipientId = recipientId
        )
        
        val ref = TapV3Payload.AttachmentRef(
            cid = cid,
            size = attachmentData.size.toLong(),
            mimeType = attachment.contentType
        )
        
        // 存储 CID 映射，供发送端构建 AttachmentPointer 使用
        if (attachment is DatabaseAttachment) {
            TapV3AttachmentCidMapping.storeCid(
                attachment.attachmentId.id,
                cid,
                attachmentData.size.toLong(),
                attachment.contentType
            )
            TapV3Logger.d(TAG, "Stored CID mapping: attachmentId=${attachment.attachmentId.id}, cid=${cid.take(8)}...")
        }
        
        return TapV3Result.Success(ref)
    }
    
    /**
     * 读取附件数据
     * 参考 Tap v2 的实现：区分 DatabaseAttachment 和 UriAttachment
     */
    private fun readAttachmentData(attachment: Attachment): ByteArray? {
        return try {
            when (attachment) {
                is DatabaseAttachment -> {
                    // 情况1：附件已经存储在数据库中
                    // 直接从 AttachmentTable 读取，不通过 Content Provider
                    if (attachment.hasData) {
                        TapV3Logger.d(TAG, "Reading DatabaseAttachment: id=${attachment.attachmentId}, hasData=true")
                        SignalDatabase.attachments
                            .getAttachmentStream(attachment.attachmentId, 0)
                            .use { it.readBytes() }
                    } else {
                        TapV3Logger.w(TAG, "DatabaseAttachment has no data: id=${attachment.attachmentId}")
                        null
                    }
                }
                
                is UriAttachment -> {
                    // 情况2：附件是通过 URI 引用的（如从相册选择的图片）
                    // 通过 ContentResolver 读取
                    TapV3Logger.d(TAG, "Reading UriAttachment: uri=${attachment.uri}")
                    context.contentResolver.openInputStream(attachment.uri)
                        ?.use { it.readBytes() }
                }
                
                else -> {
                    // 其他类型的附件，尝试使用 URI 读取
                    TapV3Logger.d(TAG, "Reading unknown Attachment type: ${attachment.javaClass.simpleName}")
                    val uri = attachment.uri
                    if (uri != null) {
                        context.contentResolver.openInputStream(uri)
                            ?.use { it.readBytes() }
                    } else {
                        TapV3Logger.w(TAG, "Attachment has no URI: type=${attachment.javaClass.simpleName}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to read attachment data: type=${attachment.javaClass.simpleName}, fileName=${attachment.fileName}", e)
            null
        }
    }
    
    private suspend fun cleanupUploadedContent(messageCid: String?, attachmentCids: List<String>) {
        if (messageCid != null) {
            try {
                ipfsGatewayManager.unpin(messageCid)
                ipfsContentTable.deleteContent(messageCid)
                TapV3Logger.d(TAG, "Cleaned up message CID: $messageCid")
            } catch (e: Exception) {
                TapV3Logger.e(TAG, "Failed to cleanup message CID: $messageCid", e)
            }
        }
        
        for (cid in attachmentCids) {
            try {
                ipfsGatewayManager.unpin(cid)
                ipfsContentTable.deleteContent(cid)
                TapV3Logger.d(TAG, "Cleaned up attachment CID: $cid")
            } catch (e: Exception) {
                TapV3Logger.e(TAG, "Failed to cleanup attachment CID: $cid", e)
            }
        }
    }
    
    /**
     * Send pre-encrypted ciphertext via Tap v3 transport.
     * Used by TapV3MessageTransportImpl for typing indicators, receipts, etc.
     */
    suspend fun sendCiphertext(
        recipientId: String,
        ciphertext: ByteArray
    ): SendResult {
        TapV3Logger.i(TAG, "Sending ciphertext to recipient: ${recipientId.take(8)}..., size=${ciphertext.size}")
        
        val channel = channelTable.getChannel(recipientId)
        if (channel == null) {
            TapV3Logger.e(TAG, "Channel not found for recipient: ${recipientId.take(8)}...")
            return SendResult(
                success = false,
                error = "Tap v3 channel not established"
            )
        }
        
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
            TapV3Logger.e(TAG, "Channel not active: ${channel.status}")
            return SendResult(
                success = false,
                error = "Tap v3 channel not active: ${channel.status}"
            )
        }
        
        // 获取对方的 k_push
        val peerKPushResult = kPushManager.getPeerKPush(recipientId)
        if (peerKPushResult.isFailure()) {
            TapV3Logger.e(TAG, "Peer k_push not found for recipient: ${recipientId.take(8)}...")
            return SendResult(
                success = false,
                error = "Peer k_push not found"
            )
        }
        val peerKPush = (peerKPushResult as TapV3Result.Success).data
        
        val senderId = try {
            getLocalSenderId()
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to get local sender ID", e)
            return SendResult(
                success = false,
                error = "Failed to get local sender ID: ${e.message}"
            )
        }
        
        return if (TapV3MessageCodec.shouldUseInline(ciphertext.size)) {
            sendInlineMessage(recipientId, ciphertext, peerKPush, channel.pushEndpoint, senderId)
        } else {
            sendIpfsMessage(recipientId, ciphertext, emptyList(), peerKPush, channel.pushEndpoint, senderId)
        }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3SendIntegrator::class.java)
        
        @Volatile
        private var INSTANCE: TapV3SendIntegrator? = null
        
        fun getInstance(context: Context): TapV3SendIntegrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3SendIntegrator(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
