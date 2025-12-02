package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
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
    
    private suspend fun sendInlineMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        peerKPush: ByteArray,
        endpoint: String,
        senderId: String
    ): SendResult {
        TapV3Logger.d(TAG, "Sending inline message: ${signalEncrypted.size} bytes")
        
        val payload = TapV3Payload.Inline(encrypted = signalEncrypted)
        
        val encodeResult = TapV3MessageCodec.encodeMessage(payload, peerKPush, senderId)
        if (encodeResult.isFailure()) {
            val failure = encodeResult as TapV3Result.Failure
            TapV3Logger.e(TAG, "Failed to encode inline message: ${failure.message}")
            return SendResult(
                success = false,
                error = "Message encoding failed: ${failure.message}"
            )
        }
        
        val encoded = (encodeResult as TapV3Result.Success).data
        TapV3Logger.d(TAG, "Encoded inline message: ${encoded.encodedSize} chars")
        
        val sendResult = unifiedPushProvider.send(
            endpoint = endpoint,
            payload = encoded.base64Data.toByteArray(Charsets.UTF_8)
        )
        
        return if (sendResult.isSuccess()) {
            TapV3Logger.i(TAG, "Inline message sent successfully")
            SendResult(
                success = true,
                transportMethod = TransportMethod.INLINE
            )
        } else {
            val failure = sendResult as TapV3Result.Failure
            TapV3Logger.e(TAG, "Failed to send inline message: ${failure.message}")
            SendResult(
                success = false,
                error = "Push failed: ${failure.message}"
            )
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
        
        var messageCid: String? = null
        val attachmentCids = mutableListOf<String>()
        val attachmentRefs = mutableListOf<TapV3Payload.AttachmentRef>()
        
        try {
            if (signalEncrypted.isNotEmpty()) {
                val uploadResult = ipfsGatewayManager.upload(signalEncrypted)
                if (uploadResult.isFailure()) {
                    val failure = uploadResult as TapV3Result.Failure
                    TapV3Logger.e(TAG, "Failed to upload message to IPFS: ${failure.message}")
                    return SendResult(
                        success = false,
                        error = "IPFS upload failed: ${failure.message}"
                    )
                }
                
                messageCid = (uploadResult as TapV3Result.Success).data
                TapV3Logger.d(TAG, "Uploaded message to IPFS: $messageCid")
                
                val expiresAt = System.currentTimeMillis() + 
                    TimeUnit.DAYS.toMillis(TapV3Constants.IPFS_PIN_DURATION_DAYS_MESSAGE.toLong())
                
                ipfsContentTable.insertContent(
                    cid = messageCid,
                    contentType = IpfsContentTable.ContentType.MESSAGE,
                    sizeBytes = signalEncrypted.size.toLong(),
                    expiresAt = expiresAt,
                    recipientId = recipientId
                )
            }
            
            for (attachment in attachments) {
                val attachmentResult = uploadAttachment(attachment, recipientId)
                if (attachmentResult.isFailure()) {
                    val failure = attachmentResult as TapV3Result.Failure
                    TapV3Logger.e(TAG, "Failed to upload attachment: ${failure.message}")
                    
                    cleanupUploadedContent(messageCid, attachmentCids)
                    
                    return SendResult(
                        success = false,
                        error = "Attachment upload failed: ${failure.message}"
                    )
                }
                
                val ref = (attachmentResult as TapV3Result.Success).data
                attachmentRefs.add(ref)
                attachmentCids.add(ref.cid)
                
                TapV3Logger.d(TAG, "Uploaded attachment to IPFS: ${ref.cid}")
            }
            
            val payload = TapV3Payload.IpfsRefs(
                messageCid = messageCid,
                attachments = attachmentRefs
            )
            
            val encodeResult = TapV3MessageCodec.encodeMessage(payload, peerKPush, senderId)
            if (encodeResult.isFailure()) {
                val failure = encodeResult as TapV3Result.Failure
                TapV3Logger.e(TAG, "Failed to encode IPFS message: ${failure.message}")
                
                cleanupUploadedContent(messageCid, attachmentCids)
                
                return SendResult(
                    success = false,
                    error = "Message encoding failed: ${failure.message}"
                )
            }
            
            val encoded = (encodeResult as TapV3Result.Success).data
            TapV3Logger.d(TAG, "Encoded IPFS message: ${encoded.encodedSize} chars")
            
            val sendResult = unifiedPushProvider.send(
                endpoint = endpoint,
                payload = encoded.base64Data.toByteArray(Charsets.UTF_8)
            )
            
            return if (sendResult.isSuccess()) {
                TapV3Logger.i(TAG, "IPFS message sent successfully")
                SendResult(
                    success = true,
                    transportMethod = TransportMethod.IPFS,
                    messageCid = messageCid,
                    attachmentCids = attachmentCids
                )
            } else {
                val failure = sendResult as TapV3Result.Failure
                TapV3Logger.e(TAG, "Failed to send IPFS message: ${failure.message}")
                
                return SendResult(
                    success = false,
                    error = "Push failed: ${failure.message}"
                )
            }
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Unexpected error during IPFS message send", e)
            
            cleanupUploadedContent(messageCid, attachmentCids)
            
            return SendResult(
                success = false,
                error = "Unexpected error: ${e.message}"
            )
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
        
        return TapV3Result.Success(ref)
    }
    
    private fun readAttachmentData(attachment: Attachment): ByteArray? {
        return try {
            val uri = attachment.uri
            if (uri == null) {
                TapV3Logger.e(TAG, "Attachment has no URI")
                return null
            }
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to read attachment data", e)
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
