package org.thoughtcrime.securesms.tapv3.protocol

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.groups.GroupId

class TapV3GroupControlMessageSender(
    private val context: Context
) {

    companion object {
        private val TAG = Log.tag(TapV3GroupControlMessageSender::class.java)
        const val PREFIX_OFFER = "TAP_V3_GROUP_OFFER:"
        const val PREFIX_ACCEPT = "TAP_V3_GROUP_ACCEPT:"
        const val PREFIX_DISABLE = "TAP_V3_GROUP_DISABLE:"
    }

    fun sendOffer(groupId: String, offer: TapV3GroupControl.Offer): TapV3Result<Unit> {
        forceSignalForGroup(groupId)
        return sendGroupControlMessage(groupId, TapV3GroupControl.serialize(offer), PREFIX_OFFER)
    }

    fun sendAccept(groupId: String, accept: TapV3GroupControl.Accept): TapV3Result<Unit> {
        forceSignalForGroup(groupId)
        return sendGroupControlMessage(groupId, TapV3GroupControl.serialize(accept), PREFIX_ACCEPT)
    }

    fun sendDisable(groupId: String, disable: TapV3GroupControl.Disable): TapV3Result<Unit> {
        forceSignalForGroup(groupId)
        return sendGroupControlMessage(groupId, TapV3GroupControl.serialize(disable), PREFIX_DISABLE)
    }

    private fun forceSignalForGroup(groupId: String) {
        try {
            val gid = GroupId.parseOrThrow(groupId)
            val recipient = Recipient.externalGroupExact(gid)
            val memberIds = recipient.participantIds
            
            for (memberId in memberIds) {
                // We need the ACI string for the manager.
                // Resolving recipient to get ACI might be async or require DB.
                // Recipient.resolved(memberId).aci...
                // This is running on main thread potentially? No, usually background.
                // Let's try to get it safely.
                val member = Recipient.resolved(memberId)
                if (member.hasAci) {
                org.thoughtcrime.securesms.tapv3.integration.TapV3ForceSignalManager.forceSignal(
                    member.aci.get().toString(),
                        60000 // 1 minute force
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force signal for group members", e)
        }
    }

    private fun sendGroupControlMessage(
        groupId: String,
        serializedData: ByteArray,
        prefix: String
    ): TapV3Result<Unit> {
        return try {
            val base64 = Base64.encodeToString(serializedData, Base64.NO_WRAP)
            val messageBody = "$prefix$base64"

            val recipient = try {
                val gid = GroupId.parseOrThrow(groupId)
                Recipient.externalGroupExact(gid)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid Group ID: $groupId", e)
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Invalid Group ID: ${e.message}",
                    e
                )
            }

            if (!recipient.isGroup) {
                 return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Recipient is not a group"
                )
            }

            val outgoingMessage = OutgoingMessage(
                recipient = recipient,
                body = messageBody,
                attachments = emptyList(),
                timestamp = System.currentTimeMillis(),
                expiresIn = 0L,
                viewOnce = false,
                isSecure = true,
                mentions = emptyList(),
                bodyRanges = null
            )
            
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
            
             MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null,
                null
            )

            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send group control message", e)
             TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Failed to send group control message",
                e
            )
        }
    }
}
