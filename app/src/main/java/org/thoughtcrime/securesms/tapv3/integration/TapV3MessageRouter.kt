package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.protocol.TapV3HandshakeManager
import org.thoughtcrime.securesms.tapv3.utils.TapV3Logger

class TapV3MessageRouter private constructor(
    private val context: Context
) {
    
    private val channelTable = SignalDatabase.tapV3Channels
    private val handshakeManager = TapV3HandshakeManager.getInstance(context)
    
    data class RoutingDecision(
        val useTapV3: Boolean,
        val reason: String
    )
    
    fun shouldUseTapV3(recipient: Recipient): RoutingDecision {
        if (!recipient.isRegistered) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Recipient not registered on Signal"
            )
        }
        
        if (recipient.isGroup) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Group messaging not supported in Tap v3"
            )
        }
        
        if (recipient.isSelf) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Self messaging not supported in Tap v3"
            )
        }
        
        val recipientId = recipient.id.serialize()
        
        val channel = channelTable.getChannel(recipientId)
        if (channel == null) {
            TapV3Logger.d(TAG, "No Tap v3 channel for recipient: ${recipientId.take(8)}...")
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 channel not established"
            )
        }
        
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
            TapV3Logger.d(TAG, "Tap v3 channel not active: ${channel.status}")
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 channel not active: ${channel.status}"
            )
        }
        
        if (!handshakeManager.isHandshakeCompleted(recipientId)) {
            TapV3Logger.d(TAG, "Tap v3 handshake not completed")
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 handshake not completed"
            )
        }
        
        TapV3Logger.d(TAG, "Using Tap v3 transport for recipient: ${recipientId.take(8)}...")
        return RoutingDecision(
            useTapV3 = true,
            reason = "Tap v3 channel active and ready"
        )
    }
    
    fun shouldUseTapV3(recipientId: String): RoutingDecision {
        val channel = channelTable.getChannel(recipientId)
        if (channel == null) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 channel not found"
            )
        }
        
        if (channel.status != TapV3ChannelTable.ChannelStatus.ACTIVE) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 channel not active"
            )
        }
        
        if (!handshakeManager.isHandshakeCompleted(recipientId)) {
            return RoutingDecision(
                useTapV3 = false,
                reason = "Tap v3 handshake not completed"
            )
        }
        
        return RoutingDecision(
            useTapV3 = true,
            reason = "Tap v3 channel active"
        )
    }
    
    fun getTapV3ChannelStatus(recipientId: String): TapV3ChannelTable.ChannelStatus? {
        val channel = channelTable.getChannel(recipientId)
        return channel?.status
    }
    
    fun hasTapV3Channel(recipientId: String): Boolean {
        return channelTable.getChannel(recipientId) != null
    }
    
    fun isHandshakeCompleted(recipientId: String): Boolean {
        return handshakeManager.isHandshakeCompleted(recipientId)
    }
    
    fun getAllTapV3Recipients(): List<String> {
        val channels = channelTable.getAllActiveChannels()
        return channels.map { it.recipientId }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3MessageRouter::class.java)
        
        @Volatile
        private var INSTANCE: TapV3MessageRouter? = null
        
        fun getInstance(context: Context): TapV3MessageRouter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3MessageRouter(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
