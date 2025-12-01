package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.SignalDatabase
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
        
        val kPushResult = kPushManager.getKey(recipientId)
        if (kPushResult.isFailure()) {
            TapV3Logger.e(TAG, "k_push key not found for recipient: ${recipientId.take(8)}...")
            return SendResult(
                success = false,
                error = "k_push key not found"
            )
        }
        val kPush = (kPushResult as TapV3Result.Success).data
        
        return if (attachments.isEmpty() && TapV3MessageCodec.shouldUseInline(signalEncrypted.size)) {
            sendInlineMessage(recipientId, signalEncrypted, kPush, channel.pushEndpoint)
        } else {
            sendIpfsMessage(recipientId, signalEncrypted, attachments, kPush, channel.pushEndpoint)
        }
    }
    
    private suspend fun sendInlineMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        kPush: ByteArray,
        endpoint: String
    ): SendResult {
        TapV3Logger.d(TAG, "Sending inline message: ${signalEncrypted.size} bytes")
        
        val payload = TapV3Payload.Inline(encrypted = signalEncrypted)
        
        val encodeResult = TapV3MessageCodec.encodeMessage(payload, kPush)
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
        kPush: ByteArray,
        endpoint: String
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
            
            val encodeResult = TapV3MessageCodec.encodeMessage(payload, kPush)
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
