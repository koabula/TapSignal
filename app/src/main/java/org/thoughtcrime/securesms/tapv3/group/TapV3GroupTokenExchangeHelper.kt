package org.thoughtcrime.securesms.tapv3.group

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessage
import org.thoughtcrime.securesms.tap.group.utils.GroupIdConverter

class TapV3GroupTokenExchangeHelper(private val context: Context) {

    fun sendGroupOffer(
        groupId: String,
        offer: TapV3ControlMessage.GroupOffer
    ): TapV3Result<Unit> {
        return sendGroupControlMessage(groupId, offer, "TAP_V3_GROUP_OFFER:")
    }

    fun sendGroupAccept(
        groupId: String,
        accept: TapV3ControlMessage.GroupAccept
    ): TapV3Result<Unit> {
        return sendGroupControlMessage(groupId, accept, "TAP_V3_GROUP_ACCEPT:")
    }

    fun sendGroupActivate(
        groupId: String,
        activate: TapV3ControlMessage.GroupActivate
    ): TapV3Result<Unit> {
        return sendGroupControlMessage(groupId, activate, "TAP_V3_GROUP_ACTIVATE:")
    }

    fun sendGroupDisable(
        groupId: String,
        disable: TapV3ControlMessage.GroupDisable
    ): TapV3Result<Unit> {
        return sendGroupControlMessage(groupId, disable, "TAP_V3_GROUP_DISABLE:")
    }

    private fun sendGroupControlMessage(
        groupId: String,
        message: TapV3ControlMessage,
        prefix: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = TapV3ControlMessage.serialize(message)
            val base64 = Base64.encodeToString(serialized, Base64.NO_WRAP)
            val messageBody = "$prefix$base64"

            val groupRecipientId = getGroupRecipientIdFromGroupId(groupId)
            if (groupRecipientId == null) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Cannot resolve RecipientId for groupId: $groupId"
                )
            }

            val recipient = Recipient.resolved(groupRecipientId)
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

            val outgoingMessage = OutgoingMessage(
                threadRecipient = recipient,
                sentTimeMillis = System.currentTimeMillis(),
                body = messageBody,
                attachments = emptyList<Attachment>(),
                expiresIn = 0L,
                isViewOnce = false,
                isSecure = true,
                mentions = emptyList<Mention>(),
                bodyRanges = null
            )

            MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null,
                null
            )

            Log.i(TAG, "Sent Tap v3 group control message: $prefix to group $groupId")
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send group control message", e)
            TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Failed to send group control message: ${e.message}",
                e
            )
        }
    }

    private fun getGroupRecipientIdFromGroupId(groupId: String): RecipientId? {
        return try {
            val result = GroupIdConverter.convert(groupId, context)
            when (result) {
                is GroupIdConverter.ConversionResult.Success -> result.recipientId
                is GroupIdConverter.ConversionResult.Failed -> {
                    Log.w(TAG, "Failed to convert groupId: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting group recipient ID", e)
            null
        }
    }

    companion object {
        private val TAG = Log.tag(TapV3GroupTokenExchangeHelper::class.java)

        @Volatile
        private var INSTANCE: TapV3GroupTokenExchangeHelper? = null

        fun getInstance(context: Context): TapV3GroupTokenExchangeHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3GroupTokenExchangeHelper(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
