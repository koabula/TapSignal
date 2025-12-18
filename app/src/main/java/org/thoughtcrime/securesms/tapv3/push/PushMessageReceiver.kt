package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.tapv3.integration.TapV3ContentRepairer
import org.thoughtcrime.securesms.tapv3.integration.TapV3ReceiveIntegrator
import org.thoughtcrime.securesms.util.RemoteConfig
import org.unifiedpush.android.connector.MessagingReceiver
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Envelope

class PushMessageReceiver : MessagingReceiver() {
    
    companion object {
        private val TAG = Log.tag(PushMessageReceiver::class.java)
    }
    
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Log.d(TAG, "Received UnifiedPush message: size=${message.size}, instance=$instance")
        
        try {
            val messageString = String(message, Charsets.UTF_8)
            Log.d(TAG, "Message content: ${messageString.take(100)}...")
            
            val base64Data = messageString.trim()
            
            CoroutineScope(Dispatchers.IO).launch {
                val receiveIntegrator = TapV3ReceiveIntegrator.getInstance(context)
                val result = receiveIntegrator.receiveMessage(base64Data)
                
                if (result.success && result.message != null) {
                    val senderId = result.message.senderId
                    val attachmentCids = result.message.attachmentCids
                    Log.d(TAG, "Message received successfully from ${senderId.take(8)}..., " +
                              "attachments=${attachmentCids.size}, injecting into Signal")
                    injectIntoSignalPipeline(context, result.message.signalEncrypted, senderId, attachmentCids)
                } else {
                    Log.e(TAG, "Message processing failed: ${result.error}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle UnifiedPush message", e)
        }
    }
    
    /**
     * 将 Tap v3 收到的消息注入 Signal 处理管道
     * 
     * signalEncrypted 现在是发送端构建的完整 Envelope protobuf 序列化后的字节，
     * 包含正确的 type, sourceServiceId, content 等字段。
     * 直接反序列化后使用 SignalServiceCipher.decrypt() 解密。
     * 
     * @param attachmentCids 附件的 IPFS CID 列表，用于在解密后修复 Content 中的 AttachmentPointer
     */
    private suspend fun injectIntoSignalPipeline(
        context: Context,
        signalEncrypted: ByteArray,
        senderId: String,
        attachmentCids: List<String>
    ) {
        try {
            Log.d(TAG, "Processing envelope: size=${signalEncrypted.size}, sender=${senderId.take(8)}..., " +
                      "attachmentCids=${attachmentCids.size}")
            
            val envelope = try {
                Envelope.ADAPTER.decode(signalEncrypted)
            } catch (e: Exception) {
                // If decoding fails, it is raw ciphertext. Construct Envelope manually.
                Log.w(TAG, "Failed to decode as Envelope, constructing Envelope from raw bytes")
                Envelope(
                    type = Envelope.Type.UNIDENTIFIED_SENDER,
                    sourceServiceId = null,
                    sourceDevice = null,
                    timestamp = System.currentTimeMillis(),
                    content = okio.ByteString.of(*signalEncrypted),
                    serverGuid = null
                )
            }
            
            Log.d(TAG, "Envelope parsed: type=${envelope.type}, timestamp=${envelope.timestamp}, " +
                       "sourceServiceId=${envelope.sourceServiceId?.take(8)}..., contentSize=${envelope.content?.size ?: 0}")
            
            val cipherResult = decryptEnvelope(envelope)
            if (cipherResult == null) {
                Log.e(TAG, "Failed to decrypt Signal envelope from ${senderId.take(8)}...")
                return
            }
            
            // 在解密后修复 Content 中的 AttachmentPointer，将占位符替换为真实的 CID
            val repairedContent = if (attachmentCids.isNotEmpty()) {
                val repaired = TapV3ContentRepairer.repairContentWithCids(cipherResult.content, attachmentCids)
                Log.d(TAG, "Content repaired with ${attachmentCids.size} CIDs")
                repaired
            } else {
                cipherResult.content
            }
            
            val processor = MessageContentProcessor.create(context)
            processor.process(
                envelope = envelope,
                content = repairedContent,
                metadata = cipherResult.metadata,
                serverDeliveredTimestamp = System.currentTimeMillis(),
                processingEarlyContent = false
            )
            
            Log.i(TAG, "Successfully injected Tap v3 message into Signal pipeline: type=${envelope.type}")
        } catch (e: org.signal.libsignal.protocol.NoSessionException) {
            Log.e(TAG, "No session exists for sender: ${senderId.take(8)}...", e)
        } catch (e: org.signal.libsignal.protocol.InvalidMessageException) {
            Log.e(TAG, "Invalid Signal message from ${senderId.take(8)}...", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject message into Signal pipeline", e)
        }
    }
    
    private fun decryptEnvelope(envelope: Envelope): SignalServiceCipherResult? {
        return try {
            val localAci = SignalStore.account.requireAci()
            val localDeviceId = SignalStore.account.deviceId
            val protocolStore = AppDependencies.protocolStore.aci()
            val sessionLock = ReentrantSessionLock.INSTANCE
            val localAddress = SignalServiceAddress(localAci, SignalStore.account.e164)
            val certificateValidator = SealedSenderAccessUtil.getCertificateValidator()
            
            val cipher = SignalServiceCipher(
                localAddress,
                localDeviceId,
                protocolStore,
                sessionLock,
                certificateValidator
            )
            
            cipher.decrypt(envelope, System.currentTimeMillis(), RemoteConfig.usePqRatchet)
        } catch (e: Exception) {
            Log.e(TAG, "Signal decryption failed", e)
            null
        }
    }
    
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "Received new Push endpoint: endpoint=$endpoint, instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.updateEndpoint(endpoint)
            
            val endpointManager = PushEndpointManager.getInstance(context)
            endpointManager.saveMyEndpoint(endpoint)
            
            Log.i(TAG, "Push endpoint updated and saved to persistent storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Push endpoint", e)
        }
    }
    
    override fun onRegistrationFailed(context: Context, instance: String) {
        Log.e(TAG, "UnifiedPush registration failed: instance=$instance")
    }
    
    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "UnifiedPush unregistered: instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.unregister()
            
            Log.i(TAG, "Push service unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle unregister event", e)
        }
    }
}
