package org.thoughtcrime.securesms.tapv3.group

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupMembersTable
import org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupStatusTable
import org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessage
import org.thoughtcrime.securesms.tapv3.protocol.TapV3HandshakeManager
import org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.thoughtcrime.securesms.R

class TapV3GroupManager private constructor(private val context: Context) {

    private val groupStatusTable = SignalDatabase.tapV3GroupStatus
    private val groupMembersTable = SignalDatabase.tapV3GroupMembers
    private val handshakeManager = TapV3HandshakeManager.getInstance(context)
    private val tokenExchangeHelper = TapV3GroupTokenExchangeHelper.getInstance(context)

    fun isGroupV3Active(groupId: String): Boolean {
        val group = groupStatusTable.getGroup(groupId)
        return group?.status == TapV3GroupStatusTable.GroupStatus.ACTIVE
    }

    fun getGroupStatus(groupId: String): TapV3GroupStatusTable.GroupRecord? {
        return groupStatusTable.getGroup(groupId)
    }

    fun getGroupMembers(groupId: String): List<TapV3GroupMembersTable.MemberRecord> {
        return groupMembersTable.getMembers(groupId)
    }

    fun proposeV3Mode(groupId: String): Boolean {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val myHandshakeInfo = handshakeManager.createMyHandshakeInfo()

            // Update status to PROPOSING
            groupStatusTable.insertOrUpdate(
                groupId = groupId,
                status = TapV3GroupStatusTable.GroupStatus.PROPOSING,
                proposerAci = myAci,
                myKPush = myHandshakeInfo.kPush
            )

            // Save my info as a member
            groupMembersTable.insertOrUpdate(
                groupId = groupId,
                memberAci = myAci,
                endpoint = myHandshakeInfo.unifiedPushEndpoint,
                kPush = myHandshakeInfo.kPush,
                keyVersion = myHandshakeInfo.keyVersion,
                ipfsGateways = myHandshakeInfo.ipfsGateways,
                status = TapV3GroupMembersTable.MemberStatus.ACCEPTED
            )

            // Send Offer
            val offer = TapV3ControlMessage.GroupOffer(
                groupId = groupId,
                proposerAci = myAci,
                handshakeInfo = myHandshakeInfo
            )
            tokenExchangeHelper.sendGroupOffer(groupId, offer)

            Log.i(TAG, "Proposed v3 mode for group: $groupId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to propose v3 mode", e)
            return false
        }
    }

    fun acceptV3Mode(groupId: String): Boolean {
        try {
            val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val myHandshakeInfo = handshakeManager.createMyHandshakeInfo()

            // Update my status in members table
            groupMembersTable.insertOrUpdate(
                groupId = groupId,
                memberAci = myAci,
                endpoint = myHandshakeInfo.unifiedPushEndpoint,
                kPush = myHandshakeInfo.kPush,
                keyVersion = myHandshakeInfo.keyVersion,
                ipfsGateways = myHandshakeInfo.ipfsGateways,
                status = TapV3GroupMembersTable.MemberStatus.ACCEPTED
            )

            // Send Accept
            val accept = TapV3ControlMessage.GroupAccept(
                groupId = groupId,
                accepterAci = myAci,
                handshakeInfo = myHandshakeInfo
            )
            tokenExchangeHelper.sendGroupAccept(groupId, accept)

            Log.i(TAG, "Accepted v3 mode for group: $groupId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept v3 mode", e)
            return false
        }
    }

    fun handleGroupOffer(offer: TapV3ControlMessage.GroupOffer) {
        Log.i(TAG, "Handling Group Offer for: ${offer.groupId}")
        
        // Update group status
        groupStatusTable.insertOrUpdate(
            groupId = offer.groupId,
            status = TapV3GroupStatusTable.GroupStatus.PROPOSING,
            proposerAci = offer.proposerAci,
            myKPush = null // Will be set when I accept
        )

        // Save proposer info
        groupMembersTable.insertOrUpdate(
            groupId = offer.groupId,
            memberAci = offer.proposerAci,
            endpoint = offer.handshakeInfo.unifiedPushEndpoint,
            kPush = offer.handshakeInfo.kPush,
            keyVersion = offer.handshakeInfo.keyVersion,
            ipfsGateways = offer.handshakeInfo.ipfsGateways,
            status = TapV3GroupMembersTable.MemberStatus.ACCEPTED
        )

        // Show notification to user
        showGroupOfferNotification(offer)
    }

    private fun showGroupOfferNotification(offer: TapV3ControlMessage.GroupOffer) {
        try {
            val groupId = offer.groupId
            val proposerAci = offer.proposerAci
            
            // Try to resolve proposer name
            val proposerName = try {
                val aci = org.whispersystems.signalservice.api.push.ServiceId.ACI.parseOrThrow(proposerAci)
                val recipientId = SignalDatabase.recipients.getByAci(aci).orElse(null)
                if (recipientId != null) {
                    Recipient.resolved(recipientId).getDisplayName(context)
                } else {
                    proposerAci.take(8)
                }
            } catch (e: Exception) {
                proposerAci.take(8)
            }

            // Try to resolve group name
            val groupName = try {
                val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
                if (groupRecipientId != null) {
                    Recipient.resolved(groupRecipientId).getDisplayName(context)
                } else {
                    "Group"
                }
            } catch (e: Exception) {
                "Group"
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Tap v3 Group Handshake",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Tap v3 group handshake request notifications"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // Accept Intent
            val acceptIntent = Intent(context, TapV3GroupHandshakeReceiver::class.java).apply {
                action = TapV3GroupHandshakeReceiver.ACTION_ACCEPT_GROUP_HANDSHAKE
                putExtra(TapV3GroupHandshakeReceiver.EXTRA_GROUP_ID, groupId)
            }
            val acceptPendingIntent = PendingIntent.getBroadcast(
                context,
                (groupId + "accept").hashCode(),
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Reject Intent
            val rejectIntent = Intent(context, TapV3GroupHandshakeReceiver::class.java).apply {
                action = TapV3GroupHandshakeReceiver.ACTION_REJECT_GROUP_HANDSHAKE
                putExtra(TapV3GroupHandshakeReceiver.EXTRA_GROUP_ID, groupId)
            }
            val rejectPendingIntent = PendingIntent.getBroadcast(
                context,
                (groupId + "reject").hashCode(),
                rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Build Notification
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Tap v3 Group Upgrade")
                .setContentText("$proposerName proposes to upgrade $groupName to Tap v3 mode")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$proposerName proposes to upgrade group '$groupName' to Tap v3 mode. This will use UnifiedPush/IPFS for transport."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(R.drawable.v2_media_check, "Accept", acceptPendingIntent)
                .addAction(R.drawable.symbol_x_white_24, "Reject", rejectPendingIntent)
                .build()
            
            notificationManager.notify(groupId.hashCode(), notification)
            Log.i(TAG, "Shown group offer notification for $groupId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show group offer notification", e)
        }
    }

    fun handleGroupAccept(accept: TapV3ControlMessage.GroupAccept) {
        Log.i(TAG, "Handling Group Accept for: ${accept.groupId} from ${accept.accepterAci}")

        // Save accepter info
        groupMembersTable.insertOrUpdate(
            groupId = accept.groupId,
            memberAci = accept.accepterAci,
            endpoint = accept.handshakeInfo.unifiedPushEndpoint,
            kPush = accept.handshakeInfo.kPush,
            keyVersion = accept.handshakeInfo.keyVersion,
            ipfsGateways = accept.handshakeInfo.ipfsGateways,
            status = TapV3GroupMembersTable.MemberStatus.ACCEPTED
        )

        // Check if we should activate (if I am the proposer)
        val group = groupStatusTable.getGroup(accept.groupId)
        val myAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()

        if (group != null && group.proposerAci == myAci && group.status == TapV3GroupStatusTable.GroupStatus.PROPOSING) {
            checkAndActivate(accept.groupId)
        }
    }

    private fun checkAndActivate(groupId: String) {
        val acceptedMembers = groupMembersTable.getMembers(groupId).filter { 
            it.status == TapV3GroupMembersTable.MemberStatus.ACCEPTED 
        }.map { it.memberAci }.toSet()

        val groupRecipientId = getGroupRecipientIdFromGroupId(groupId) ?: return
        val groupRecipient = Recipient.resolved(groupRecipientId)
        val allMemberIds = groupRecipient.participantIds.mapNotNull { 
            try { Recipient.resolved(it).requireAci().toString() } catch (e: Exception) { null } 
        }.toSet()

        // If all members have accepted (including myself)
        if (acceptedMembers.containsAll(allMemberIds)) {
            Log.i(TAG, "All members accepted v3 mode for $groupId. Activating...")
            
            // Activate
            groupStatusTable.insertOrUpdate(
                groupId = groupId,
                status = TapV3GroupStatusTable.GroupStatus.ACTIVE,
                proposerAci = null, // Or keep it
                myKPush = null // Keep existing
            )

            val activate = TapV3ControlMessage.GroupActivate(groupId = groupId)
            tokenExchangeHelper.sendGroupActivate(groupId, activate)
        } else {
            Log.d(TAG, "Not all members accepted yet. Accepted: ${acceptedMembers.size}, Total: ${allMemberIds.size}")
        }
    }

    fun handleGroupActivate(activate: TapV3ControlMessage.GroupActivate) {
        Log.i(TAG, "Handling Group Activate for: ${activate.groupId}")
        
        val existing = groupStatusTable.getGroup(activate.groupId)
        if (existing != null) {
            groupStatusTable.insertOrUpdate(
                groupId = activate.groupId,
                status = TapV3GroupStatusTable.GroupStatus.ACTIVE,
                proposerAci = existing.proposerAci,
                myKPush = existing.myKPush
            )
        }
    }

    fun handleGroupDisable(disable: TapV3ControlMessage.GroupDisable) {
        Log.i(TAG, "Handling Group Disable for: ${disable.groupId}")
        
        val existing = groupStatusTable.getGroup(disable.groupId)
        if (existing != null) {
            groupStatusTable.insertOrUpdate(
                groupId = disable.groupId,
                status = TapV3GroupStatusTable.GroupStatus.NATIVE,
                proposerAci = null,
                myKPush = null
            )
        }
    }

    fun disableV3Mode(groupId: String): Boolean {
        try {
            Log.i(TAG, "Disabling v3 mode for group: $groupId")

            // 1. Update local status to NATIVE
            groupStatusTable.insertOrUpdate(
                groupId = groupId,
                status = TapV3GroupStatusTable.GroupStatus.NATIVE,
                proposerAci = null,
                myKPush = null
            )

            // 2. Send GroupDisable message
            val disable = TapV3ControlMessage.GroupDisable(groupId = groupId)
            tokenExchangeHelper.sendGroupDisable(groupId, disable)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable v3 mode", e)
            return false
        }
    }

    fun onGroupMessageReceived(groupId: String) {
        try {
            // Self-repair logic: If we receive a Tap v3 message for this group,
            // it means the group is active (at least for the sender).
            // If we are in PROPOSING state, we should upgrade to ACTIVE.
            val group = groupStatusTable.getGroup(groupId) ?: return

            if (group.status == TapV3GroupStatusTable.GroupStatus.PROPOSING) {
                Log.i(TAG, "Received Tap v3 message for PROPOSING group $groupId. Auto-upgrading to ACTIVE.")
                
                groupStatusTable.insertOrUpdate(
                    groupId = groupId,
                    status = TapV3GroupStatusTable.GroupStatus.ACTIVE,
                    proposerAci = group.proposerAci,
                    myKPush = group.myKPush
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onGroupMessageReceived", e)
        }
    }

    private fun getGroupRecipientIdFromGroupId(groupId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            val result = GroupIdConverter.convert(groupId, context)
            when (result) {
                is GroupIdConverter.ConversionResult.Success -> result.recipientId
                is GroupIdConverter.ConversionResult.Failed -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val TAG = Log.tag(TapV3GroupManager::class.java)
        private const val NOTIFICATION_CHANNEL_ID = "tap_v3_group_handshake"

        @Volatile
        private var INSTANCE: TapV3GroupManager? = null

        @JvmStatic
        fun getInstance(context: Context): TapV3GroupManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3GroupManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
