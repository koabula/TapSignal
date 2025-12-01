package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.protocol.TapV3MessageCodec
import org.thoughtcrime.securesms.tapv3.utils.TapV3Logger
import java.io.File
import java.io.FileOutputStream

class TapV3ReceiveIntegrator private constructor(
    private val context: Context
) {
    
    private val kPushManager = KPushManager.getInstance(context)
    private val ipfsGatewayManager = IpfsGatewayManager.getInstance(context)
    private val channelTable = SignalDatabase.tapV3Channels
    
    data class ReceivedMessage(
        val signalEncrypted: ByteArray,
        val attachments: List<ReceivedAttachment> = emptyList(),
        val transportMethod: TransportMethod
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReceivedMessage
            if (!signalEncrypted.contentEquals(other.signalEncrypted)) return false
            if (attachments != other.attachments) return false
            if (transportMethod != other.transportMethod) return false
            return true
        }

        override fun hashCode(): Int {
            var result = signalEncrypted.contentHashCode()
            result = 31 * result + attachments.hashCode()
            result = 31 * result + transportMethod.hashCode()
            return result
        }
    }
    
    data class ReceivedAttachment(
        val data: ByteArray,
        val size: Long,
        val mimeType: String?,
        val cid: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReceivedAttachment
            if (!data.contentEquals(other.data)) return false
            if (size != other.size) return false
            if (mimeType != other.mimeType) return false
            if (cid != other.cid) return false
            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + cid.hashCode()
            return result
        }
    }
    
    enum class TransportMethod {
        INLINE,
        IPFS
    }
    
    data class ReceiveResult(
        val success: Boolean,
        val message: ReceivedMessage? = null,
        val error: String? = null
    )
    
    suspend fun receiveMessage(
        base64Data: String,
        senderId: String
    ): ReceiveResult {
        TapV3Logger.i(TAG, "Receiving message from sender: ${senderId.take(8)}...")
        
        val channel = channelTable.getChannel(senderId)
        if (channel == null) {
            TapV3Logger.e(TAG, "Channel not found for sender: ${senderId.take(8)}...")
            return ReceiveResult(
                success = false,
                error = "Tap v3 channel not found"
            )
        }
        
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
            TapV3Logger.e(TAG, "Channel not active: ${channel.status}")
            return ReceiveResult(
                success = false,
                error = "Tap v3 channel not active"
            )
        }
        
        val kPushResult = kPushManager.getKey(senderId)
        if (kPushResult.isFailure()) {
            TapV3Logger.e(TAG, "k_push key not found for sender: ${senderId.take(8)}...")
            return ReceiveResult(
                success = false,
                error = "k_push key not found"
            )
        }
        val kPush = (kPushResult as TapV3Result.Success).data
        
        val decodeResult = TapV3MessageCodec.decodeMessage(base64Data, kPush)
        if (decodeResult.isFailure()) {
            val failure = decodeResult as TapV3Result.Failure
            TapV3Logger.e(TAG, "Failed to decode message: ${failure.message}")
            return ReceiveResult(
                success = false,
                error = "Message decoding failed: ${failure.message}"
            )
        }
        
        val payload = (decodeResult as TapV3Result.Success).data
        
        return when (payload) {
            is TapV3Payload.Inline -> {
                receiveInlineMessage(payload)
            }
            is TapV3Payload.IpfsRefs -> {
                receiveIpfsMessage(payload, senderId)
            }
        }
    }
    
    private fun receiveInlineMessage(payload: TapV3Payload.Inline): ReceiveResult {
        TapV3Logger.d(TAG, "Receiving inline message: ${payload.encrypted.size} bytes")
        
        val message = ReceivedMessage(
            signalEncrypted = payload.encrypted,
            attachments = emptyList(),
            transportMethod = TransportMethod.INLINE
        )
        
        TapV3Logger.i(TAG, "Inline message received successfully")
        
        return ReceiveResult(
            success = true,
            message = message
        )
    }
    
    private suspend fun receiveIpfsMessage(
        payload: TapV3Payload.IpfsRefs,
        senderId: String
    ): ReceiveResult {
        TapV3Logger.d(TAG, "Receiving IPFS message: messageCid=${payload.messageCid}, attachments=${payload.attachments.size}")
        
        var signalEncrypted = ByteArray(0)
        
        if (payload.messageCid != null) {
            val downloadResult = ipfsGatewayManager.download(payload.messageCid)
            if (downloadResult.isFailure()) {
                val failure = downloadResult as TapV3Result.Failure
                TapV3Logger.e(TAG, "Failed to download message from IPFS: ${failure.message}")
                return ReceiveResult(
                    success = false,
                    error = "IPFS download failed: ${failure.message}"
                )
            }
            
            signalEncrypted = (downloadResult as TapV3Result.Success).data
            TapV3Logger.d(TAG, "Downloaded message from IPFS: ${signalEncrypted.size} bytes")
        }
        
        val attachments = mutableListOf<ReceivedAttachment>()
        
        for (ref in payload.attachments) {
            val attachmentResult = downloadAttachment(ref)
            if (attachmentResult.isFailure()) {
                val failure = attachmentResult as TapV3Result.Failure
                TapV3Logger.e(TAG, "Failed to download attachment ${ref.cid}: ${failure.message}")
                
                return ReceiveResult(
                    success = false,
                    error = "Attachment download failed: ${failure.message}"
                )
            }
            
            val attachment = (attachmentResult as TapV3Result.Success).data
            attachments.add(attachment)
            TapV3Logger.d(TAG, "Downloaded attachment from IPFS: ${attachment.cid}, ${attachment.size} bytes")
        }
        
        val message = ReceivedMessage(
            signalEncrypted = signalEncrypted,
            attachments = attachments,
            transportMethod = TransportMethod.IPFS
        )
        
        TapV3Logger.i(TAG, "IPFS message received successfully")
        
        return ReceiveResult(
            success = true,
            message = message
        )
    }
    
    private suspend fun downloadAttachment(
        ref: TapV3Payload.AttachmentRef
    ): TapV3Result<ReceivedAttachment> {
        val downloadResult = ipfsGatewayManager.download(ref.cid)
        if (downloadResult.isFailure()) {
            return TapV3Result.Failure(
                (downloadResult as TapV3Result.Failure).error,
                downloadResult.message,
                downloadResult.cause
            )
        }
        
        val data = (downloadResult as TapV3Result.Success).data
        
        if (data.size.toLong() != ref.size) {
            TapV3Logger.w(TAG, "Attachment size mismatch: expected ${ref.size}, got ${data.size}")
        }
        
        val attachment = ReceivedAttachment(
            data = data,
            size = ref.size,
            mimeType = ref.mimeType,
            cid = ref.cid
        )
        
        return TapV3Result.Success(attachment)
    }
    
    fun saveAttachmentToFile(attachment: ReceivedAttachment, outputFile: File): Boolean {
        return try {
            FileOutputStream(outputFile).use { outputStream ->
                outputStream.write(attachment.data)
                outputStream.flush()
            }
            
            TapV3Logger.d(TAG, "Saved attachment to file: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to save attachment to file", e)
            false
        }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3ReceiveIntegrator::class.java)
        
        @Volatile
        private var INSTANCE: TapV3ReceiveIntegrator? = null
        
        fun getInstance(context: Context): TapV3ReceiveIntegrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3ReceiveIntegrator(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
