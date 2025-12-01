package org.thoughtcrime.securesms.tapv3

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
import org.thoughtcrime.securesms.tapv3.database.IpfsContentTable
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager
import org.thoughtcrime.securesms.tapv3.push.UnifiedPushProvider
import java.util.Base64

class TapV3TransportManager private constructor(
    private val context: Context
) {
    
    private val ipfsManager = IpfsGatewayManager.getInstance(context)
    private val pushProvider = UnifiedPushProvider.getInstance(context)
    private val pushEndpointManager = PushEndpointManager.getInstance(context)
    private val kPushManager = KPushManager.getInstance(context)
    
    suspend fun sendMessage(
        recipientId: String,
        signalEncrypted: ByteArray,
        attachments: List<ByteArray> = emptyList()
    ): TapV3Result<Unit> {
        val kPushResult = kPushManager.getKey(recipientId)
        if (kPushResult.isFailure()) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "k_push key not found for recipient"
            )
        }
        val kPush = kPushResult.getOrThrow()
        
        val endpoint = pushEndpointManager.getEndpoint(recipientId)
            ?: return TapV3Result.Failure(
                TapV3Error.PUSH_ERROR,
                "Push endpoint not found for recipient"
            )
        
        val payload = if (signalEncrypted.size <= TapV3Constants.INLINE_THRESHOLD_BYTES && attachments.isEmpty()) {
            Log.d(TAG, "Sending inline message: ${signalEncrypted.size} bytes")
            TapV3Payload.Inline(signalEncrypted)
        } else {
            Log.d(TAG, "Sending IPFS message: ${signalEncrypted.size} bytes, ${attachments.size} attachments")
            
            val messageCid = if (signalEncrypted.size > TapV3Constants.INLINE_THRESHOLD_BYTES) {
                val uploadResult = ipfsManager.upload(signalEncrypted)
                if (uploadResult.isFailure()) {
                    return TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Failed to upload message to IPFS"
                    )
                }
                uploadResult.getOrThrow()
            } else {
                null
            }
            
            val attachmentRefs = mutableListOf<TapV3Payload.AttachmentRef>()
            for (attachment in attachments) {
                val uploadResult = ipfsManager.upload(attachment)
                if (uploadResult.isFailure()) {
                    return TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Failed to upload attachment to IPFS"
                    )
                }
                val cid = uploadResult.getOrThrow()
                attachmentRefs.add(
                    TapV3Payload.AttachmentRef(
                        cid = cid,
                        size = attachment.size.toLong(),
                        mimeType = null
                    )
                )
            }
            
            TapV3Payload.IpfsRefs(messageCid, attachmentRefs)
        }
        
        val serialized = TapV3Payload.serialize(payload)
        
        val encryptResult = TapV3Crypto.encrypt(serialized, kPush)
        if (encryptResult.isFailure()) {
            return TapV3Result.Failure(
                TapV3Error.ENCRYPTION_ERROR,
                "Failed to encrypt with k_push"
            )
        }
        val encrypted = encryptResult.getOrThrow()
        
        val encoded = Base64.getEncoder().encode(encrypted)
        
        return pushProvider.send(endpoint, encoded)
    }
    
    suspend fun receiveMessage(encryptedData: ByteArray, senderId: String): TapV3Result<ByteArray> {
        val decoded = try {
            Base64.getDecoder().decode(encryptedData)
        } catch (e: Exception) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Failed to decode Base64: ${e.message}",
                e
            )
        }
        
        val kPushResult = kPushManager.getKey(senderId)
        if (kPushResult.isFailure()) {
            return TapV3Result.Failure(
                TapV3Error.KEY_NOT_FOUND,
                "k_push key not found for sender"
            )
        }
        val kPush = kPushResult.getOrThrow()
        
        val decryptResult = TapV3Crypto.decrypt(decoded, kPush)
        if (decryptResult.isFailure()) {
            return TapV3Result.Failure(
                TapV3Error.DECRYPTION_ERROR,
                "Failed to decrypt with k_push"
            )
        }
        val decrypted = decryptResult.getOrThrow()
        
        val payload = try {
            TapV3Payload.deserialize(decrypted)
        } catch (e: Exception) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Failed to deserialize payload: ${e.message}",
                e
            )
        }
        
        return when (payload) {
            is TapV3Payload.Inline -> {
                Log.d(TAG, "Received inline message: ${payload.encrypted.size} bytes")
                TapV3Result.Success(payload.encrypted)
            }
            
            is TapV3Payload.IpfsRefs -> {
                Log.d(TAG, "Received IPFS refs: messageCid=${payload.messageCid}, attachments=${payload.attachments.size}")
                
                if (payload.messageCid != null) {
                    val downloadResult = ipfsManager.download(payload.messageCid)
                    if (downloadResult.isFailure()) {
                        return TapV3Result.Failure(
                            TapV3Error.IPFS_DOWNLOAD_ERROR,
                            "Failed to download message from IPFS"
                        )
                    }
                    downloadResult
                } else {
                    TapV3Result.Success(ByteArray(0))
                }
            }
        }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3TransportManager::class.java)
        
        @Volatile
        private var INSTANCE: TapV3TransportManager? = null
        
        fun getInstance(context: Context): TapV3TransportManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3TransportManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
