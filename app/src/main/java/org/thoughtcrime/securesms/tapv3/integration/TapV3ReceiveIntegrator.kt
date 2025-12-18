package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
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
        val attachmentCids: List<String> = emptyList(),
        val transportMethod: TransportMethod,
        val senderId: String,
        val isDecrypted: Boolean = false,
        val groupId: String? = null,
        val body: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReceivedMessage
            if (!signalEncrypted.contentEquals(other.signalEncrypted)) return false
            if (attachments != other.attachments) return false
            if (attachmentCids != other.attachmentCids) return false
            if (transportMethod != other.transportMethod) return false
            if (senderId != other.senderId) return false
            if (isDecrypted != other.isDecrypted) return false
            if (groupId != other.groupId) return false
            if (body != other.body) return false
            return true
        }

        override fun hashCode(): Int {
            var result = signalEncrypted.contentHashCode()
            result = 31 * result + attachments.hashCode()
            result = 31 * result + attachmentCids.hashCode()
            result = 31 * result + transportMethod.hashCode()
            result = 31 * result + senderId.hashCode()
            result = 31 * result + isDecrypted.hashCode()
            result = 31 * result + (groupId?.hashCode() ?: 0)
            result = 31 * result + (body?.hashCode() ?: 0)
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
    
    /**
     * 接收并解密消息
     * 使用本地的 myKPush 解密，senderId 从解密后的消息中获取
     */
    suspend fun receiveMessage(base64Data: String): ReceiveResult {
        TapV3Logger.i(TAG, "Receiving message, data length: ${base64Data.length}")
        
        // 获取自己的 k_push 用于解密
        val myKPush = kPushManager.getMyKPush()
        if (myKPush == null) {
            TapV3Logger.e(TAG, "My k_push not found, cannot decrypt message")
            return ReceiveResult(
                success = false,
                error = "My k_push not configured"
            )
        }
        
        // 解码消息，senderId 包含在加密数据中
        val decodeResult = TapV3MessageCodec.decodeMessage(base64Data, myKPush)
        if (decodeResult.isFailure()) {
            val failure = decodeResult as TapV3Result.Failure
            TapV3Logger.e(TAG, "Failed to decode message: ${failure.message}")
            return ReceiveResult(
                success = false,
                error = "Message decoding failed: ${failure.message}"
            )
        }
        
        val decoded = (decodeResult as TapV3Result.Success<TapV3MessageCodec.DecodedMessage>).data
        val senderId = decoded.senderId
        val payload = decoded.payload
        
        TapV3Logger.i(TAG, "Message decoded, senderId: ${senderId.take(8)}...")
        
        return when (payload) {
            is TapV3Payload.Inline -> {
                receiveInlineMessage(payload, senderId)
            }
            is TapV3Payload.IpfsRefs -> {
                receiveIpfsMessage(payload, senderId)
            }
            is TapV3Payload.GroupMessage -> {
                receiveGroupMessage(payload, senderId)
            }
        }
    }
    
    /**
     * 兼容旧 API，保留 senderId 参数但不再使用它来获取密钥
     */
    @Deprecated("Use receiveMessage(base64Data) instead", ReplaceWith("receiveMessage(base64Data)"))
    suspend fun receiveMessage(
        base64Data: String,
        senderId: String
    ): ReceiveResult {
        return receiveMessage(base64Data)
    }
    
    private fun receiveInlineMessage(payload: TapV3Payload.Inline, senderId: String): ReceiveResult {
        TapV3Logger.d(TAG, "Receiving inline message: ${payload.encrypted.size} bytes")
        
        val message = ReceivedMessage(
            signalEncrypted = payload.encrypted,
            attachments = emptyList(),
            attachmentCids = emptyList(),
            transportMethod = TransportMethod.INLINE,
            senderId = senderId
        )
        
        TapV3Logger.i(TAG, "Inline message received successfully from ${senderId.take(8)}...")
        
        return ReceiveResult(
            success = true,
            message = message
        )
    }
    
    private suspend fun receiveGroupMessage(
        payload: TapV3Payload.GroupMessage,
        senderId: String
    ): ReceiveResult {
        TapV3Logger.d(TAG, "Receiving Group message for group ${payload.groupId}")
        
        // 1. Decrypt Body
        val bodyBytes: ByteArray = if (payload.encryptedContent != null) {
            TapV3Crypto.decrypt(payload.encryptedContent, payload.encryptionKey).let {
                if (it is TapV3Result.Success<*>) (it as TapV3Result.Success<ByteArray>).data else return ReceiveResult(false, error = "Body decryption failed")
            }
        } else if (payload.contentCid != null) {
            val downloadResult = ipfsGatewayManager.download(payload.contentCid)
            if (downloadResult is TapV3Result.Failure) return ReceiveResult(false, error = "Body download failed: ${downloadResult.message}")
            TapV3Crypto.decrypt((downloadResult as TapV3Result.Success<ByteArray>).data, payload.encryptionKey).let {
                if (it is TapV3Result.Success<*>) (it as TapV3Result.Success<ByteArray>).data else return ReceiveResult(false, error = "Body decryption failed")
            }
        } else {
            ByteArray(0)
        }
        
        val body = String(bodyBytes, Charsets.UTF_8)
        
        // 2. Decrypt Attachments
        val attachments = mutableListOf<ReceivedAttachment>()
        val attachmentCids = mutableListOf<String>()
        
        for (ref in payload.attachments) {
            val downloadResult = ipfsGatewayManager.download(ref.cid)
            if (downloadResult is TapV3Result.Failure) return ReceiveResult(false, error = "Attachment download failed: ${ref.cid}")
            
            val decryptedData = TapV3Crypto.decrypt((downloadResult as TapV3Result.Success<ByteArray>).data, payload.encryptionKey).let {
                 if (it is TapV3Result.Success<*>) (it as TapV3Result.Success<ByteArray>).data else return ReceiveResult(false, error = "Attachment decryption failed")
            }
            
            val attachment = ReceivedAttachment(decryptedData, ref.size, ref.mimeType, ref.cid)
            attachments.add(attachment)
            attachmentCids.add(ref.cid)
            TapV3AttachmentCache.store(ref.cid, decryptedData)
        }
        
        val message = ReceivedMessage(
            signalEncrypted = ByteArray(0), // No Signal Ciphertext
            attachments = attachments,
            attachmentCids = attachmentCids,
            transportMethod = TransportMethod.IPFS, // Or GROUP?
            senderId = senderId,
            isDecrypted = true,
            groupId = payload.groupId,
            body = body
        )
        
        return ReceiveResult(true, message)
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
        val attachmentCids = mutableListOf<String>()
        
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
            attachmentCids.add(ref.cid)
            TapV3Logger.d(TAG, "Downloaded attachment from IPFS: ${attachment.cid}, ${attachment.size} bytes")
            
            // 将附件数据存入缓存，供 AttachmentDownloadJob 使用
            TapV3AttachmentCache.store(ref.cid, attachment.data)
        }
        
        val message = ReceivedMessage(
            signalEncrypted = signalEncrypted,
            attachments = attachments,
            attachmentCids = attachmentCids,
            transportMethod = TransportMethod.IPFS,
            senderId = senderId
        )
        
        TapV3Logger.i(TAG, "IPFS message received successfully from ${senderId.take(8)}...")
        
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
        
        val data = (downloadResult as TapV3Result.Success<ByteArray>).data
        
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
