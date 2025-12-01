package org.thoughtcrime.securesms.tapv3.push

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobs.PushProcessMessageJob
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tapv3.integration.TapV3ReceiveIntegrator
import org.unifiedpush.android.connector.MessagingReceiver
import org.whispersystems.signalservice.internal.push.Envelope
import org.json.JSONObject
import okio.ByteString
import org.whispersystems.signalservice.api.push.ServiceId

class PushMessageReceiver : MessagingReceiver() {
    
    companion object {
        private val TAG = Log.tag(PushMessageReceiver::class.java)
    }
    
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Log.d(TAG, "Received UnifiedPush message: size=${message.size}, instance=$instance")
        
        try {
            val messageString = String(message, Charsets.UTF_8)
            Log.d(TAG, "Message content: ${messageString.take(100)}...")
            
            val json = JSONObject(messageString)
            val base64Data = json.getString("data")
            val senderId = json.getString("senderId")
            
            CoroutineScope(Dispatchers.IO).launch {
                val receiveIntegrator = TapV3ReceiveIntegrator.getInstance(context)
                val result = receiveIntegrator.receiveMessage(base64Data, senderId)
                
                if (result.success && result.message != null) {
                    Log.d(TAG, "Message received successfully, injecting into Signal")
                    injectIntoSignalPipeline(context, result.message!!.signalEncrypted, senderId)
                } else {
                    Log.e(TAG, "Message processing failed: ${result.error}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle UnifiedPush message", e)
        }
    }
    
    private suspend fun injectIntoSignalPipeline(
        context: Context,
        signalEncrypted: ByteArray,
        senderId: String
    ) {
        try {
            val recipientId = RecipientId.from(senderId)
            val recipient = Recipient.resolved(recipientId)
            val serviceId = recipient.serviceId.orElse(null) ?: run {
                Log.e(TAG, "Sender has no ServiceId: ${senderId.take(8)}...")
                return
            }
            
            val localRecipient = Recipient.self()
            val localServiceId = localRecipient.serviceId.orElse(null) ?: run {
                Log.e(TAG, "Local user has no ServiceId")
                return
            }
            
            val envelope = Envelope.Builder()
                .type(Envelope.Type.CIPHERTEXT)
                .timestamp(System.currentTimeMillis())
                .serverTimestamp(System.currentTimeMillis())
                .content(ByteString.of(*signalEncrypted))
                .sourceServiceId(serviceId.toString())
                .sourceDevice(1)
                .serverGuid(java.util.UUID.randomUUID().toString())
                .destinationServiceId(localServiceId.toString())
                .urgent(true)
                .story(false)
                .build()
            
            val processor = MessageContentProcessor.create(context)
            val metadata = org.whispersystems.signalservice.api.crypto.EnvelopeMetadata(
                sourceServiceId = serviceId,
                sourceE164 = null,
                sourceDeviceId = 1,
                sealedSender = false,
                groupId = null,
                destinationServiceId = localServiceId
            )
            
            // Create Content protobuf message with the encrypted data
            val content = org.whispersystems.signalservice.internal.push.Content.Builder()
                .dataMessage(org.whispersystems.signalservice.internal.push.DataMessage.Builder()
                    .body("Tap v3 message")
                    .timestamp(System.currentTimeMillis())
                    .build())
                .build()
            
            processor.process(envelope, content, metadata, System.currentTimeMillis())
            
            Log.i(TAG, "Successfully injected Tap v3 message into Signal pipeline")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject message into Signal pipeline", e)
        }
    }
    
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Log.i(TAG, "Received new Push endpoint: endpoint=$endpoint, instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.updateEndpoint(endpoint)
            
            Log.i(TAG, "Push endpoint updated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Push endpoint", e)
        }
    }
    
    override fun onRegistrationFailed(context: Context, instance: String) {
        Log.e(TAG, "UnifiedPush registration failed: instance=$instance")
    }
    
    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "UnifiedPush已注销: instance=$instance")
        
        try {
            val pushProvider = UnifiedPushProvider.getInstance(context)
            pushProvider.unregister()
            
            Log.i(TAG, "Push服务已注销")
        } catch (e: Exception) {
            Log.e(TAG, "处理注销事件失败", e)
        }
    }
}
