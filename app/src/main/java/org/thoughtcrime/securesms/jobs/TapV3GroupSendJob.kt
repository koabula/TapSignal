package org.thoughtcrime.securesms.jobs

import android.content.Context
import androidx.annotation.WorkerThread
import com.annimon.stream.Stream
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.tapv3.integration.TapV3AttachmentPointerBuilder
import org.thoughtcrime.securesms.tapv3.integration.TapV3SendIntegrator
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.thoughtcrime.securesms.util.GroupUtil
import org.thoughtcrime.securesms.util.SignalLocalMetrics
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit

class TapV3GroupSendJob : PushSendJob {

    companion object {
        const val KEY = "TapV3GroupSendJob"
        private val TAG = Log.tag(TapV3GroupSendJob::class.java)
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_FILTER_RECIPIENTS = "filter_recipient"
        private const val KEY_HAS_MEDIA = "has_media"

        @JvmStatic
        @WorkerThread
        fun enqueue(
            context: Context,
            jobManager: JobManager,
            messageId: Long,
            destination: RecipientId,
            filterAddresses: Set<RecipientId>
        ) {
            try {
                val database = SignalDatabase.messages
                val message = database.getOutgoingMessage(messageId)

                // Tap v3 使用 IPFS，不需要上传附件到 Signal Server，也不需要等待附件上传任务
                // 我们会在 sendGroupMessage 中通过 TapV3SendIntegrator 上传到 IPFS
                val attachmentUploadIds = emptyList<String>() 
                val hasMedia = message.attachments.isNotEmpty()
                val addHardDependencies = false

                jobManager.add(
                    TapV3GroupSendJob(messageId, destination, filterAddresses, hasMedia),
                    attachmentUploadIds,
                    if (addHardDependencies) destination.toQueueKey() else null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enqueue message.", e)
                SignalDatabase.messages.markAsSentFailed(messageId)
            }
        }
    }

    private val messageId: Long
    private val filterRecipients: Set<RecipientId>
    private val hasMedia: Boolean
    private var destination: RecipientId = RecipientId.UNKNOWN

    // Constructor for Factory
    private constructor(
        parameters: Job.Parameters,
        messageId: Long,
        filterRecipients: Set<RecipientId>,
        hasMedia: Boolean
    ) : super(parameters) {
        this.messageId = messageId
        this.filterRecipients = filterRecipients
        this.hasMedia = hasMedia
    }

    // Constructor for enqueue
    constructor(
        messageId: Long,
        destination: RecipientId,
        filterRecipients: Set<RecipientId>,
        hasMedia: Boolean
    ) : this(
        Job.Parameters.Builder()
            .setQueue(destination.toQueueKey(hasMedia))
            .addConstraint(NetworkConstraint.KEY)
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .build(),
        messageId,
        filterRecipients,
        hasMedia
    ) {
        this.destination = destination
    }

    class Factory : Job.Factory<TapV3GroupSendJob> {
        override fun create(parameters: Job.Parameters, serializedData: ByteArray?): TapV3GroupSendJob {
            val data = JsonJobData.deserialize(serializedData)
            val messageId = data.getLong(KEY_MESSAGE_ID)
            val filterRecipients = RecipientId.fromSerializedList(data.getString(KEY_FILTER_RECIPIENTS)).toSet()
            val hasMedia = data.getBoolean(KEY_HAS_MEDIA)
            return TapV3GroupSendJob(parameters, messageId, filterRecipients, hasMedia)
        }
    }

    override fun getFactoryKey(): String = KEY

    override fun serialize(): ByteArray? {
        return JsonJobData.Builder()
            .putLong(KEY_MESSAGE_ID, messageId)
            .putString(KEY_FILTER_RECIPIENTS, RecipientId.toSerializedList(filterRecipients))
            .putBoolean(KEY_HAS_MEDIA, hasMedia)
            .serialize()
    }

    override fun onAdded() {
        SignalDatabase.messages.markAsSending(messageId)
    }

    override fun onPushSend() {
        SignalLocalMetrics.GroupMessageSend.onJobStarted(messageId)

        val database = SignalDatabase.messages
        val message = database.getOutgoingMessage(messageId)
        val threadId = database.getMessageRecord(messageId).threadId
        val existingNetworkFailures = HashSet(message.networkFailures)
        val existingIdentityMismatches = HashSet(message.identityKeyMismatches)

        SignalLocalMetrics.GroupMessageSend.setSentTimestamp(messageId, message.sentTimeMillis)

        if (database.isSent(messageId)) {
            Log.w(TAG, "Message $messageId was already sent. Ignoring.")
            return
        }

        val groupRecipient = Recipient.resolved(message.threadRecipient.id)
        if (!groupRecipient.isPushGroup) {
            throw MmsException("Message recipient isn't a group!")
        }

        Log.i(TAG, "Sending Tap V3 group message: $messageId to ${groupRecipient.id}")

        try {
            rotateSenderCertificateIfNecessary()

            val groupId = groupRecipient.requireGroupId().requirePush()
            val distributionId = SignalDatabase.groups.getGroup(groupId).get().distributionId

            // Determine targets
            val possible: List<Recipient>
            val skipped: List<RecipientId>
            val target: List<Recipient>

            if (filterRecipients.isNotEmpty()) {
                val targets = ArrayList<Recipient>(filterRecipients.size + existingNetworkFailures.size)
                targets.addAll(filterRecipients.map { Recipient.resolved(it) })
                targets.addAll(existingNetworkFailures.map { Recipient.resolved(it.recipientId) })
                target = targets.distinctBy { it.id }
                possible = target
                skipped = emptyList()
            } else if (existingNetworkFailures.isNotEmpty()) {
                target = existingNetworkFailures.map { Recipient.resolved(it.recipientId) }.distinctBy { it.id }
                possible = target
                skipped = emptyList()
            } else {
                val destinations: List<GroupReceiptTable.GroupReceiptInfo> = SignalDatabase.groupReceipts.getGroupReceiptInfo(messageId)
                possible = if (destinations.isNotEmpty()) {
                    destinations.map { Recipient.resolved(it.recipientId) }.distinctBy { it.id }
                } else {
                    SignalDatabase.groups
                        .getGroupMembers(groupId, org.thoughtcrime.securesms.database.GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF)
                        .map { Recipient.resolved(it.id) }
                        .distinctBy { it.id }
                }
                
                val eligible = RecipientUtil.getEligibleForSending(possible)
                skipped = possible.minus(eligible.toSet()).map { it.id }.toList()
                target = eligible
            }

            // Construct SignalServiceDataMessage (Needed for encryption)
            val builder = SignalServiceDataMessage.newBuilder()
                .withTimestamp(message.sentTimeMillis)

            GroupUtil.setDataMessageGroupContext(context, builder, groupId)

            val profileKey = getProfileKey(groupRecipient)
            val sticker = getStickerFor(message)
            val sharedContacts = getSharedContactsFor(message)
            val previews = getPreviewsFor(message)
            val mentions = getMentionsFor(message.mentions)
            val bodyRanges = getBodyRanges(message)
            val attachments = Stream.of(message.attachments).filterNot { it.isSticker }.toList()
            val attachmentPointers = getTapV3AttachmentPointersFor(attachments)

            builder.withAttachments(attachmentPointers)
                .withBody(message.body)
                .withExpiration((message.expiresIn / 1000).toInt())
                .withViewOnce(message.isViewOnce)
                .asExpirationUpdate(message.isExpirationUpdate)
                .withProfileKey(profileKey.orElse(null))
                .withSticker(sticker.orElse(null))
                .withSharedContacts(sharedContacts)
                .withPreviews(previews)
                .withMentions(mentions)
                .withBodyRanges(bodyRanges)

            if (message.outgoingQuote != null) {
                builder.withQuote(getQuoteFor(message).orElse(null))
            }

            val dataMessage = builder.build()

            // Get encrypted bytes using Tap v3 helper
            var signalEncrypted = AppDependencies.signalServiceMessageSender
                .getEncryptedBytesForTapV3(dataMessage, distributionId)

            if (signalEncrypted == null) {
                Log.w(TAG, "Failed to encrypt message for Tap V3 group, attempting to distribute Sender Key...")
                
                try {
                    val messageSender = AppDependencies.signalServiceMessageSender
                    // 1. 获取 SenderKeyDistributionMessage (org.signal.libsignal.protocol.message.SenderKeyDistributionMessage)
                    val distributionMessage = messageSender.getOrCreateNewGroupSession(distributionId)
                    
                    // 2. 分发给所有目标成员
                    for (recipient in target) {
                        try {
                             val address = SignalServiceAddress(recipient.serviceId.get(), recipient.e164.orElse(null))
                             val access = org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil.getSealedSenderAccessFor(recipient)
                             
                             // 使用 Signal 原生通道发送分发消息
                             val results = messageSender.sendSenderKeyDistributionMessage(
                                 distributionId,
                                 java.util.Collections.singletonList(address),
                                 java.util.Collections.singletonList(access),
                                 distributionMessage,
                                 java.util.Optional.of(groupId.decodedId),
                                 false,
                                 false
                             )
                             
                             // 标记密钥已共享
                             if (results[0].isSuccess) {
                                 val addresses = results[0].success.devices.map { deviceId ->
                                     org.signal.libsignal.protocol.SignalProtocolAddress(address.identifier, deviceId)
                                 }
                                 AppDependencies.protocolStore.aci().markSenderKeySharedWith(distributionId, addresses)
                                 Log.i(TAG, "Sent SenderKeyDistributionMessage to ${recipient.id}")
                             }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send SenderKeyDistributionMessage to ${recipient.id}", e)
                        }
                    }
                    
                    // 3. 重试加密
                    signalEncrypted = messageSender.getEncryptedBytesForTapV3(dataMessage, distributionId)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to distribute sender key", e)
                }
            }

            if (signalEncrypted == null) {
                throw MmsException("Failed to encrypt message for Tap V3 group after key distribution attempt")
            }

            // Map targets to Service IDs for TapV3SendIntegrator
            val memberIds = target.mapNotNull { it.serviceId.orElse(null)?.toString() }

            if (memberIds.isEmpty()) {
                Log.w(TAG, "No eligible members to send to.")
                // If everyone was skipped, we should still process results to mark skipped
                PushGroupSendJob.processGroupMessageResults(
                    context, messageId, threadId, groupRecipient, message, emptyList(), target, skipped, existingNetworkFailures, existingIdentityMismatches
                )
                SignalLocalMetrics.GroupMessageSend.onJobFinished(messageId)
                return
            }

            // Call TapV3SendIntegrator
            val integrator = TapV3SendIntegrator.getInstance(context)
            val sendResult = kotlinx.coroutines.runBlocking {
                integrator.sendGroupMessage(
                    groupId.toString(),
                    memberIds,
                    signalEncrypted,
                    attachments
                )
            }

            // Convert GroupSendResult to List<SendMessageResult>
            val results = ArrayList<SendMessageResult>()
            
            for (recipient in target) {
                val serviceId = recipient.serviceId.orElse(null)?.toString()
                if (serviceId != null) {
                    val address = SignalServiceAddress(recipient.serviceId.get(), recipient.e164.orElse(null))
                    
                    if (sendResult.failedMembers.containsKey(serviceId)) {
                        // Failure
                        Log.w(TAG, "Failed to send to $serviceId: ${sendResult.failedMembers[serviceId]}")
                        results.add(SendMessageResult.networkFailure(address))
                    } else {
                        // Success
                        // We need to approximate success details. 
                        // TapV3 doesn't return device list etc. assuming standard success.
                        results.add(SendMessageResult.success(
                            address, 
                            emptyList(), // devices
                            false, // unidentified
                            false, // needsSync
                            System.currentTimeMillis(), // duration (fake)
                            Optional.empty() // content
                        ))
                    }
                } else {
                    Log.w(TAG, "Recipient ${recipient.id} has no serviceId, cannot map result.")
                }
            }

            // Delegate result processing to Signal's native helper
            PushGroupSendJob.processGroupMessageResults(
                context, 
                messageId, 
                threadId, 
                groupRecipient, 
                message, 
                results, 
                target, 
                skipped, 
                existingNetworkFailures, 
                existingIdentityMismatches
            )

        } catch (e: Exception) {
            Log.w(TAG, "Failed to send Tap V3 group message", e)
            if (e is IOException || e is RetryLaterException) {
                throw e
            }
            database.markAsSentFailed(messageId)
        } finally {
             SignalLocalMetrics.GroupMessageSend.onJobFinished(messageId)
        }
    }

    override fun onRetry() {
        SignalLocalMetrics.GroupMessageSend.cancel(messageId)
        super.onRetry()
    }

    override fun onFailure() {
        SignalDatabase.messages.markAsSentFailed(messageId)
    }

    private fun getTapV3AttachmentPointersFor(attachments: List<Attachment>): List<SignalServiceAttachmentPointer> {
        val pointers = ArrayList<SignalServiceAttachmentPointer>()
        
        for (attachment in attachments) {
            if (attachment is org.thoughtcrime.securesms.attachments.DatabaseAttachment) {
                val pointer = TapV3AttachmentPointerBuilder.createPlaceholder(attachment)
                if (pointer != null) {
                    pointers.add(pointer)
                } else {
                    Log.w(TAG, "Failed to create placeholder pointer for attachment: ${attachment.attachmentId}")
                }
            } else {
                Log.w(TAG, "Skipping non-database attachment: ${attachment.javaClass.simpleName}")
            }
        }
        
        return pointers
    }
}
