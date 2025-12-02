package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.protocol.TapV3HandshakeManager
import org.thoughtcrime.securesms.tapv3.utils.TapV3Logger
import org.whispersystems.signalservice.api.TapV3MessageTransport
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.IOException
import java.util.Optional

/**
 * Tap v3 message transport implementation.
 * Bridges the SignalServiceMessageSender with Tap v3 transport layer.
 */
class TapV3MessageTransportImpl(private val context: Context) : TapV3MessageTransport {
    
    companion object {
        private val TAG = Log.tag(TapV3MessageTransportImpl::class.java)
    }
    
    private val channelTable = SignalDatabase.tapV3Channels
    private val handshakeManager = TapV3HandshakeManager.getInstance(context)
    private val sendIntegrator = TapV3SendIntegrator.getInstance(context)
    
    override fun shouldUseTapV3ForRecipient(recipient: SignalServiceAddress): Boolean {
        try {
            val aci = recipient.serviceId
            if (aci == null) {
                Log.d(TAG, "shouldUseTapV3ForRecipient: recipient has no ACI")
                return false
            }
            
            val recipientId = aci.toString()
            
            val channel = channelTable.getChannel(recipientId)
            if (channel == null) {
                Log.d(TAG, "shouldUseTapV3ForRecipient: no channel for ${recipientId.take(8)}...")
                return false
            }
            
            if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
                Log.d(TAG, "shouldUseTapV3ForRecipient: channel not active for ${recipientId.take(8)}...")
                return false
            }
            
            if (!handshakeManager.isHandshakeCompleted(recipientId)) {
                Log.d(TAG, "shouldUseTapV3ForRecipient: handshake not completed for ${recipientId.take(8)}...")
                return false
            }
            
            Log.d(TAG, "shouldUseTapV3ForRecipient: using Tap v3 for ${recipientId.take(8)}...")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "shouldUseTapV3ForRecipient: error checking", e)
            return false
        }
    }
    
    override fun sendMessageViaTapV3(
        recipient: SignalServiceAddress,
        ciphertext: ByteArray,
        timestamp: Long,
        urgent: Boolean,
        online: Boolean
    ): SendMessageResult {
        val aci = recipient.serviceId
            ?: throw IOException("Recipient has no ACI for Tap v3 transport")
        
        val recipientId = aci.toString()
        
        Log.i(TAG, "sendMessageViaTapV3: recipient=${recipientId.take(8)}..., ciphertextSize=${ciphertext.size}, timestamp=$timestamp")
        
        try {
            val result = runBlocking {
                sendIntegrator.sendCiphertext(recipientId, ciphertext)
            }
            
            return if (result.success) {
                Log.i(TAG, "sendMessageViaTapV3: success for ${recipientId.take(8)}...")
                SendMessageResult.success(
                    recipient,
                    emptyList(),
                    true,
                    false,
                    timestamp,
                    Optional.empty()
                )
            } else {
                Log.e(TAG, "sendMessageViaTapV3: failed for ${recipientId.take(8)}..., error=${result.error}")
                throw IOException("Tap v3 send failed: ${result.error}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageViaTapV3: exception for ${recipientId.take(8)}...", e)
            if (e is IOException) {
                throw e
            }
            throw IOException("Tap v3 send exception: ${e.message}", e)
        }
    }
}
