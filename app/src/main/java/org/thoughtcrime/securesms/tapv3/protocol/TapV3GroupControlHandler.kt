package org.thoughtcrime.securesms.tapv3.protocol

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable
import org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus
import org.thoughtcrime.securesms.tapv3.integration.GroupMemberResolver
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager
import org.thoughtcrime.securesms.tapv3.utils.TapV3Validator

class TapV3GroupControlHandler(
    private val context: Context
) {

    private val groupStateTable = SignalDatabase.tapV3GroupStates
    private val channelTable = SignalDatabase.tapV3Channels
    private val groupMemberResolver = GroupMemberResolver.getInstance(context)
    private val kPushManager = KPushManager.getInstance(context)
    private val pushEndpointManager = PushEndpointManager.getInstance(context)
    
    // We need to send our own info when accepting
    private val groupControlMessageSender = TapV3GroupControlMessageSender(context)

    companion object {
        private val TAG = Log.tag(TapV3GroupControlHandler::class.java)
    }

    fun handleGroupControlMessage(
        messageBody: String,
        senderId: String, // Sender ACI
        groupId: String // Group ID (Recipient ID string of the group)
    ): TapV3Result<Unit> {
        return when {
            messageBody.startsWith(TapV3GroupControlMessageSender.PREFIX_OFFER) -> {
                handleOffer(messageBody.substring(TapV3GroupControlMessageSender.PREFIX_OFFER.length), senderId, groupId)
            }
            messageBody.startsWith(TapV3GroupControlMessageSender.PREFIX_ACCEPT) -> {
                handleAccept(messageBody.substring(TapV3GroupControlMessageSender.PREFIX_ACCEPT.length), senderId, groupId)
            }
            messageBody.startsWith(TapV3GroupControlMessageSender.PREFIX_DISABLE) -> {
                handleDisable(messageBody.substring(TapV3GroupControlMessageSender.PREFIX_DISABLE.length), groupId)
            }
            else -> {
                TapV3Result.Success(Unit)
            }
        }
    }

    private fun handleOffer(base64Data: String, senderId: String, groupId: String): TapV3Result<Unit> {
        try {
            val data = Base64.decode(base64Data, Base64.NO_WRAP)
            val offer = TapV3GroupControl.deserialize(data) as? TapV3GroupControl.Offer ?: return TapV3Result.Success(Unit)
            
            Log.i(TAG, "Received Group Offer for group $groupId from $senderId")

            saveHandshakeInfo(senderId, offer.handshakeInfo)

            val agreedMembers = listOf(senderId)
            val record = TapV3GroupStateTable.GroupStateRecord(
                groupId = groupId,
                status = GroupStatus.PROPOSING,
                initiatorId = senderId,
                agreedMembers = agreedMembers,
                handshakeInfo = offer.handshakeInfo,
                updatedAt = System.currentTimeMillis()
            )
            groupStateTable.setGroupState(record)

            // UI notification logic
            showGroupOfferNotification(groupId, senderId)
            
            return TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling group offer", e)
            return TapV3Result.Failure(TapV3Error.UNKNOWN_ERROR, "Error handling offer", e)
        }
    }

    fun acceptGroupOffer(groupId: String, initiatorId: String) {
        try {
            val state = groupStateTable.getGroupState(groupId)
            if (state == null) {
                Log.w(TAG, "Cannot accept offer: group state not found")
                return
            }
            
            // Send accept message
            val handshakeInfo = state.handshakeInfo
            if (handshakeInfo != null) {
                val accept = TapV3GroupControl.Accept(handshakeInfo)
                groupControlMessageSender.sendAccept(groupId, accept)
                
                // Update local state
                val selfId = Recipient.self().aci.get().toString()
                val currentAgreed = state.agreedMembers.toMutableList()
                if (!currentAgreed.contains(selfId)) {
                    currentAgreed.add(selfId)
                    groupStateTable.updateAgreedMembers(groupId, currentAgreed)
                }
                
                checkAndActivate(groupId, currentAgreed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept group offer", e)
        }
    }
    
    fun declineGroupOffer(groupId: String) {
        // Just ignore for now, maybe set a flag to not show again
        Log.i(TAG, "Declined offer for group $groupId")
    }

    private fun showGroupOfferNotification(groupId: String, senderId: String) {
        try {
            val senderRecipient = Recipient.external(senderId)?.let { Recipient.resolved(it.id) } ?: return
            val groupRecipient = Recipient.resolved(Recipient.externalGroupExact(org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)).id)
            
            val senderName = senderRecipient.getDisplayName(context)
            val groupName = groupRecipient.getDisplayName(context)
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "tap_v3_group_handshake",
                    "Tap v3 Group Requests",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val notificationId = groupId.hashCode()
            
            // Accept Action
            val acceptIntent = android.content.Intent(context, TapV3GroupControlReceiver::class.java).apply {
                action = TapV3GroupControlReceiver.ACTION_ACCEPT
                putExtra(TapV3GroupControlReceiver.EXTRA_GROUP_ID, groupId)
                putExtra(TapV3GroupControlReceiver.EXTRA_SENDER_ID, senderId)
                putExtra(TapV3GroupControlReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val acceptPendingIntent = android.app.PendingIntent.getBroadcast(
                context, 
                notificationId + 1, 
                acceptIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // Decline Action
            val declineIntent = android.content.Intent(context, TapV3GroupControlReceiver::class.java).apply {
                action = TapV3GroupControlReceiver.ACTION_DECLINE
                putExtra(TapV3GroupControlReceiver.EXTRA_GROUP_ID, groupId)
                putExtra(TapV3GroupControlReceiver.EXTRA_SENDER_ID, senderId)
                putExtra(TapV3GroupControlReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val declinePendingIntent = android.app.PendingIntent.getBroadcast(
                context, 
                notificationId + 2, 
                declineIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // Resolve threadId for conversation intent
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(groupRecipient)
            
            // Create an intent to open the conversation using createBuilderSync
            val contentIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                org.thoughtcrime.securesms.conversation.ConversationIntents.createBuilderSync(context, groupRecipient.id, threadId).build(),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(context, "tap_v3_group_handshake")
                .setSmallIcon(org.thoughtcrime.securesms.R.drawable.ic_notification)
                .setContentTitle("Tap v3 Group Request")
                .setContentText("$senderName proposed Tap v3 mode for $groupName")
                .setContentIntent(contentIntent)
                .addAction(0, "Accept", acceptPendingIntent)
                .addAction(0, "Decline", declinePendingIntent)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(notificationId, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show group offer notification", e)
        }
    }

    private fun handleAccept(base64Data: String, senderId: String, groupId: String): TapV3Result<Unit> {
        try {
            val data = Base64.decode(base64Data, Base64.NO_WRAP)
            val accept = TapV3GroupControl.deserialize(data) as? TapV3GroupControl.Accept ?: return TapV3Result.Success(Unit)

            Log.i(TAG, "Received Group Accept for group $groupId from $senderId")

            saveHandshakeInfo(senderId, accept.handshakeInfo)

            val currentState = groupStateTable.getGroupState(groupId) ?: return TapV3Result.Success(Unit)
            val currentAgreed = currentState.agreedMembers.toMutableList()
            if (!currentAgreed.contains(senderId)) {
                currentAgreed.add(senderId)
                groupStateTable.updateAgreedMembers(groupId, currentAgreed)
            }

            checkAndActivate(groupId, currentAgreed)

            return TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling group accept", e)
            return TapV3Result.Failure(TapV3Error.UNKNOWN_ERROR, "Error handling accept", e)
        }
    }

    private fun handleDisable(base64Data: String, groupId: String): TapV3Result<Unit> {
        Log.i(TAG, "Received Group Disable for group $groupId")
        groupStateTable.updateStatus(groupId, GroupStatus.NATIVE)
        groupStateTable.updateAgreedMembers(groupId, emptyList())
        return TapV3Result.Success(Unit)
    }

    private fun saveHandshakeInfo(senderId: String, info: TapV3HandshakeInfo) {
        if (!TapV3Validator.isValidEndpoint(info.unifiedPushEndpoint)) {
            Log.w(TAG, "Ignoring invalid UnifiedPush endpoint from $senderId: ${info.unifiedPushEndpoint}")
            return
        }

        if (!TapV3Validator.isValidKeySize(info.kPush)) {
            Log.w(TAG, "Ignoring invalid k_push size from $senderId: ${info.kPush.size}")
            return
        }

        if (info.ipfsGateways.isEmpty()) {
            Log.w(TAG, "Ignoring group handshake info with empty IPFS gateways from $senderId")
            return
        }

        kPushManager.savePeerKPush(senderId, info.kPush, info.keyVersion)
        pushEndpointManager.saveEndpoint(senderId, info.unifiedPushEndpoint)

        channelTable.insertOrUpdate(
            recipientId = senderId,
            status = TapV3ChannelTable.ChannelStatus.GROUP_ONLY,
            pushEndpoint = info.unifiedPushEndpoint,
            kPush = info.kPush,
            keyVersion = info.keyVersion,
            ipfsGateways = info.ipfsGateways
        )
    }
    
    private fun checkAndActivate(groupId: String, agreedMembers: List<String>) {
        val allMembers = groupMemberResolver.getGroupMemberAcis(groupId).toSet()
        val selfId = Recipient.self().aci.get().toString()
        val allMembersWithSelf = allMembers + selfId
        
        if (agreedMembers.containsAll(allMembersWithSelf) && allMembersWithSelf.containsAll(agreedMembers)) {
            Log.i(TAG, "All members agreed. Activating Tap V3 for group $groupId")
            groupStateTable.updateStatus(groupId, GroupStatus.ACTIVE)
        }
    }
}
