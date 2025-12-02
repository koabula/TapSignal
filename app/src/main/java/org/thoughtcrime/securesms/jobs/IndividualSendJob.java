package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;

import java.util.Collections;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PaymentTable;
import org.thoughtcrime.securesms.database.RecipientTable.SealedSenderAccessMode;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.ratelimit.ProofRequiredExceptionHandler;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.MessageUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.tap.integration.TapMessageSendIntegrator;
import org.thoughtcrime.securesms.tap.integration.IntegratedTapSendResult;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEditMessage;
import org.whispersystems.signalservice.api.messages.SignalServicePreview;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.BodyRange;
import org.whispersystems.signalservice.internal.push.DataMessage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okio.Utf8;

public class IndividualSendJob extends PushSendJob {

  public static final String KEY = "PushMediaSendJob";

  private static final String TAG = Log.tag(IndividualSendJob.class);

  private static final String KEY_MESSAGE_ID = "message_id";

  private final long messageId;

  public IndividualSendJob(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    this(new Parameters.Builder()
             .setQueue(isScheduledSend ? recipient.getId().toScheduledSendQueueKey() : recipient.getId().toQueueKey(hasMedia))
             .addConstraint(NetworkConstraint.KEY)
             .setLifespan(TimeUnit.DAYS.toMillis(1))
             .setMaxAttempts(Parameters.UNLIMITED)
             .build(),
         messageId);
  }

  private IndividualSendJob(Job.Parameters parameters, long messageId) {
    super(parameters);
    this.messageId = messageId;
  }

  public static Job create(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    if (!recipient.getHasServiceId()) {
      throw new AssertionError("No ServiceId!");
    }

    if (recipient.isGroup()) {
      throw new AssertionError("This job does not send group messages!");
    }

    return new IndividualSendJob(messageId, recipient, hasMedia, isScheduledSend);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    try {
      OutgoingMessage message = SignalDatabase.messages().getOutgoingMessage(messageId);
      if (message.getScheduledDate() != -1) {
        AppDependencies.getScheduledMessageManager().scheduleIfNecessary();
        return;
      }

      // 检查是否为Tap模式
      boolean isTapMode = TapMessageSendIntegrator.Companion.getInstance(context).canUseTapForSending(recipient.getId());
      
      Set<String> attachmentUploadIds;
      if (isTapMode) {
        // Tap模式：附件通过Tap层传输，不需要预先上传到Signal CDN
        Log.i(TAG, "Tap模式：跳过附件CDN上传，附件将通过Tap层传输");
        attachmentUploadIds = java.util.Collections.emptySet();
      } else {
        // 原生模式：正常上传到Signal CDN
        attachmentUploadIds = enqueueCompressingAndUploadAttachmentsChains(jobManager, message);
      }
      
      boolean hasMedia            = attachmentUploadIds.size() > 0;
      boolean addHardDependencies = hasMedia && !isScheduledSend;

      jobManager.add(IndividualSendJob.create(messageId, recipient, hasMedia, isScheduledSend),
                     attachmentUploadIds,
                     addHardDependencies ? recipient.getId().toQueueKey() : null);
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      SignalDatabase.messages().markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_MESSAGE_ID, messageId).serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    SignalDatabase.messages().markAsSending(messageId);
  }

  @Override
  public void onPushSend()
      throws IOException, MmsException, NoSuchMessageException, UndeliverableMessageException, RetryLaterException
  {
    SignalLocalMetrics.IndividualMessageSend.onJobStarted(messageId);

    ExpiringMessageManager expirationManager     = AppDependencies.getExpiringMessageManager();
    MessageTable           database              = SignalDatabase.messages();
    OutgoingMessage        message               = database.getOutgoingMessage(messageId);
    long                   threadId              = database.getMessageRecord(messageId).getThreadId();
    MessageRecord          originalEditedMessage = message.getMessageToEdit() > 0 ? SignalDatabase.messages().getMessageRecordOrNull(message.getMessageToEdit()) : null;

    if (database.isSent(messageId)) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    try {
      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sending message: " + messageId + ", Recipient: " + message.getThreadRecipient()
                                                                                                                       .getId() + ", Thread: " + threadId + ", Attachments: " + buildAttachmentString(message.getAttachments()) + ", Editing: " + (originalEditedMessage != null ? originalEditedMessage.getDateSent() : "N/A"));

      RecipientUtil.shareProfileIfFirstSecureMessage(message.getThreadRecipient());

      Recipient              recipient  = message.getThreadRecipient().fresh();
      byte[]                 profileKey = recipient.getProfileKey();
      SealedSenderAccessMode accessMode = recipient.getSealedSenderAccessMode();

      // 检查是否为TAP控制消息，如果是则强制使用Signal Server
      boolean unidentified = false;
      String messageBody = message.getBody();
      boolean isTapControlMessage = messageBody != null && (
          messageBody.startsWith("TAP_REQ:") || 
          messageBody.startsWith("TAP_RESP:") || 
          messageBody.startsWith("TAP_REVOKE:") || 
          messageBody.startsWith("TAP_MSG:") ||
          messageBody.startsWith("TAP_TOKEN_EXCHANGE:") ||
          messageBody.startsWith("TAP_V3_REQ:") ||
          messageBody.startsWith("TAP_V3_RESP:") ||
          messageBody.startsWith("TAP_V3_ACK:")
      );

      if (isTapControlMessage) {
        Log.i(TAG, "检测到TAP控制消息，强制使用Signal Server发送: messageId=" + messageId);
        unidentified = deliver(message, originalEditedMessage);
      } else {
        // 优先检查Tap v3通道
        try {
          org.thoughtcrime.securesms.tapv3.integration.TapV3MessageRouter tapV3Router = 
              org.thoughtcrime.securesms.tapv3.integration.TapV3MessageRouter.Companion.getInstance(context);
          org.thoughtcrime.securesms.tapv3.integration.TapV3MessageRouter.RoutingDecision v3Decision = 
              tapV3Router.shouldUseTapV3(recipient);

          if (v3Decision.getUseTapV3()) {
            Log.i(TAG, "Using Tap v3 to send: messageId=" + messageId + ", reason=" + v3Decision.getReason());
            unidentified = sendMessageViaTapV3(messageId, recipient, message, originalEditedMessage);
          } else {
            // 回退到Tap v2检查
            TapMessageSendIntegrator integrator = TapMessageSendIntegrator.Companion.getInstance(context);
            boolean canUseTapV2 = integrator.canUseTapForSending(recipient.getId());

            if (canUseTapV2) {
              Log.i(TAG, "使用Tap v2发送: messageId=" + messageId);
              unidentified = sendMessageViaTapIntegration(messageId, recipient, message, originalEditedMessage);
            } else {
              Log.d(TAG, "使用Signal Server发送: messageId=" + messageId);
              unidentified = deliver(message, originalEditedMessage);
            }
          }
        } catch (Exception e) {
          Log.w(TAG, "Tap路由检查异常，回退到原生发送: messageId=" + messageId, e);
          unidentified = deliver(message, originalEditedMessage);
        }
      }

      database.markAsSent(messageId, true);
      markAttachmentsUploaded(messageId, message);
      database.markUnidentified(messageId, unidentified);

      // For scheduled messages, which may not have updated the thread with it's snippet yet
      SignalDatabase.threads().updateSilently(threadId, false);

      if (recipient.isSelf()) {
        SignalDatabase.messages().incrementDeliveryReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
        SignalDatabase.messages().incrementReadReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
        SignalDatabase.messages().incrementViewedReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
      }

      if (unidentified && accessMode == SealedSenderAccessMode.UNKNOWN && profileKey == null) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-unrestricted following a UD send.");
        SignalDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.UNRESTRICTED);
      } else if (unidentified && accessMode == SealedSenderAccessMode.UNKNOWN) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-enabled following a UD send.");
        SignalDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.ENABLED);
      } else if (!unidentified && accessMode != SealedSenderAccessMode.DISABLED) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-disabled following a non-UD send.");
        SignalDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.DISABLED);
      }

      if (originalEditedMessage != null && originalEditedMessage.getExpireStarted() > 0) {
        database.markExpireStarted(messageId, originalEditedMessage.getExpireStarted());
        expirationManager.scheduleDeletion(messageId, true, originalEditedMessage.getExpireStarted(), originalEditedMessage.getExpiresIn());
      } else if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        database.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }

      if (message.isViewOnce()) {
        SignalDatabase.attachments().deleteAttachmentFilesForViewOnceMessage(messageId);
      }

      ConversationShortcutRankingUpdateJob.enqueueForOutgoingIfNecessary(recipient);

      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sent message: " + messageId);

    } catch (UnregisteredUserException uue) {
      warn(TAG, "Failure", uue);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
      AppDependencies.getJobManager().add(new DirectoryRefreshJob(false));
    } catch (UntrustedIdentityException uie) {
      warn(TAG, "Failure", uie);
      Recipient recipient = Recipient.external(uie.getIdentifier());
      if (recipient == null) {
        Log.w(TAG, "Failed to create a Recipient for the identifier!");
        return;
      }
      database.addMismatchedIdentity(messageId, recipient.getId(), uie.getIdentityKey());
      database.markAsSentFailed(messageId);
      RetrieveProfileJob.enqueue(recipient.getId(), true);
    } catch (ProofRequiredException e) {
      ProofRequiredExceptionHandler.Result result = ProofRequiredExceptionHandler.handle(context, e, SignalDatabase.threads().getRecipientForThreadId(threadId), threadId, messageId);
      if (result.isRetry()) {
        throw new RetryLaterException();
      } else {
        throw e;
      }
    }

    SignalLocalMetrics.IndividualMessageSend.onJobFinished(messageId);
  }

  @Override
  public void onRetry() {
    SignalLocalMetrics.IndividualMessageSend.cancel(messageId);
    super.onRetry();
  }

  @Override
  public void onFailure() {
    SignalLocalMetrics.IndividualMessageSend.cancel(messageId);
    SignalDatabase.messages().markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  private boolean deliver(OutgoingMessage message, MessageRecord originalEditedMessage)
      throws IOException, UnregisteredUserException, UntrustedIdentityException, UndeliverableMessageException
  {
    if (message.getThreadRecipient() == null) {
      throw new UndeliverableMessageException("No destination address.");
    }

    if (Utf8.size(message.getBody()) > MessageUtil.MAX_INLINE_BODY_SIZE_BYTES) {
      throw new UndeliverableMessageException("The total body size was greater than our limit of " + MessageUtil.MAX_INLINE_BODY_SIZE_BYTES + " bytes.");
    }

    try {
      rotateSenderCertificateIfNecessary();

      Recipient messageRecipient = message.getThreadRecipient().fresh();

      if (messageRecipient.isUnregistered()) {
        throw new UndeliverableMessageException(messageRecipient.getId() + " not registered!");
      }

      SignalServiceMessageSender                 messageSender      = AppDependencies.getSignalServiceMessageSender();
      SignalServiceAddress                       address            = RecipientUtil.toSignalServiceAddress(context, messageRecipient);
      List<Attachment>                           attachments        = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
      List<SignalServiceAttachment>              serviceAttachments = getAttachmentPointersFor(attachments);
      Optional<byte[]>                           profileKey         = getProfileKey(messageRecipient);
      Optional<SignalServiceDataMessage.Sticker> sticker            = getStickerFor(message);
      List<SharedContact>                        sharedContacts     = getSharedContactsFor(message);
      List<SignalServicePreview>                 previews           = getPreviewsFor(message);
      SignalServiceDataMessage.GiftBadge         giftBadge          = getGiftBadgeFor(message);
      SignalServiceDataMessage.Payment           payment            = getPayment(message);
      List<BodyRange>                            bodyRanges         = getBodyRanges(message);
      SignalServiceDataMessage.Builder mediaMessageBuilder = SignalServiceDataMessage.newBuilder()
                                                                                     .withBody(message.getBody())
                                                                                     .withAttachments(serviceAttachments)
                                                                                     .withTimestamp(message.getSentTimeMillis())
                                                                                     .withExpiration((int) (message.getExpiresIn() / 1000))
                                                                                     .withExpireTimerVersion(message.getExpireTimerVersion())
                                                                                     .withViewOnce(message.isViewOnce())
                                                                                     .withProfileKey(profileKey.orElse(null))
                                                                                     .withSticker(sticker.orElse(null))
                                                                                     .withSharedContacts(sharedContacts)
                                                                                     .withPreviews(previews)
                                                                                     .withGiftBadge(giftBadge)
                                                                                     .asExpirationUpdate(message.isExpirationUpdate())
                                                                                     .asEndSessionMessage(message.isEndSession())
                                                                                     .withPayment(payment)
                                                                                     .withBodyRanges(bodyRanges);

      if (message.getParentStoryId() != null) {
        try {
          MessageRecord storyRecord    = SignalDatabase.messages().getMessageRecord(message.getParentStoryId().asMessageId().getId());
          Recipient     storyRecipient = storyRecord.getFromRecipient();

          SignalServiceDataMessage.StoryContext storyContext = new SignalServiceDataMessage.StoryContext(storyRecipient.requireServiceId(), storyRecord.getDateSent());
          mediaMessageBuilder.withStoryContext(storyContext);

          Optional<SignalServiceDataMessage.Reaction> reaction = getStoryReactionFor(message, storyContext);
          if (reaction.isPresent()) {
            mediaMessageBuilder.withReaction(reaction.get());
            mediaMessageBuilder.withBody(null);
          }
        } catch (NoSuchMessageException e) {
          throw new UndeliverableMessageException(e);
        }
      } else {
        mediaMessageBuilder.withQuote(getQuoteFor(message).orElse(null));
      }

      if (message.getGiftBadge() != null || message.isPaymentsNotification()) {
        mediaMessageBuilder.withBody(null);
      }

      SignalServiceDataMessage mediaMessage = mediaMessageBuilder.build();

      if (originalEditedMessage != null) {
        if (Util.equals(SignalStore.account().getAci(), address.getServiceId())) {
          SendMessageResult result = messageSender.sendSelfSyncEditMessage(new SignalServiceEditMessage(originalEditedMessage.getDateSent(), mediaMessage));
          SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);

          return SealedSenderAccessUtil.getSealedSenderCertificate() != null;
        } else {
          SendMessageResult result = messageSender.sendEditMessage(address,
                                                                   SealedSenderAccessUtil.getSealedSenderAccessFor(messageRecipient),
                                                                   ContentHint.RESENDABLE,
                                                                   mediaMessage,
                                                                   IndividualSendEvents.EMPTY,
                                                                   message.isUrgent(),
                                                                   originalEditedMessage.getDateSent());
          SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);

          return result.getSuccess().isUnidentified();
        }
      } else if (Util.equals(SignalStore.account().getAci(), address.getServiceId())) {
        SendMessageResult result = messageSender.sendSyncMessage(mediaMessage);
        SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);
        return SealedSenderAccessUtil.getSealedSenderCertificate() != null;
      } else {
        SignalLocalMetrics.IndividualMessageSend.onDeliveryStarted(messageId, message.getSentTimeMillis());
        SendMessageResult result = messageSender.sendDataMessage(address,
                                                                 SealedSenderAccessUtil.getSealedSenderAccessFor(messageRecipient),
                                                                 ContentHint.RESENDABLE,
                                                                 mediaMessage,
                                                                 new MetricEventListener(messageId),
                                                                 message.isUrgent(),
                                                                 messageRecipient.getNeedsPniSignature());

        Log.d(TAG, "[SignalTimeTest] T2_ENCRYPT_END | msgId=" + message.getSentTimeMillis() + " | timestamp=" + System.currentTimeMillis());
        SignalDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), message.isUrgent());

        if (messageRecipient.getNeedsPniSignature()) {
          SignalDatabase.pendingPniSignatureMessages().insertIfNecessary(messageRecipient.getId(), message.getSentTimeMillis(), result);
        }

        return result.getSuccess().isUnidentified();
      }
    } catch (FileNotFoundException e) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), e);
      throw new UndeliverableMessageException(e);
    } catch (ServerRejectedException e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private SignalServiceDataMessage.Payment getPayment(OutgoingMessage message) {
    if (message.isPaymentsNotification()) {
      UUID                            paymentUuid = UuidUtil.parseOrThrow(message.getBody());
      PaymentTable.PaymentTransaction payment     = SignalDatabase.payments().getPayment(paymentUuid);

      if (payment == null) {
        Log.w(TAG, "Could not find payment, cannot send notification " + paymentUuid);
        return null;
      }

      if (payment.getReceipt() == null) {
        Log.w(TAG, "Could not find payment receipt, cannot send notification " + paymentUuid);
        return null;
      }

      return new SignalServiceDataMessage.Payment(new SignalServiceDataMessage.PaymentNotification(payment.getReceipt(), payment.getNote()), null);
    } else {
      DataMessage.Payment.Activation.Type type = null;

      if (message.isRequestToActivatePayments()) {
        type = DataMessage.Payment.Activation.Type.REQUEST;
      } else if (message.isPaymentsActivated()) {
        type = DataMessage.Payment.Activation.Type.ACTIVATED;
      }

      if (type != null) {
        return new SignalServiceDataMessage.Payment(null, new SignalServiceDataMessage.PaymentActivation(type));
      } else {
        return null;
      }
    }
  }

  public static long getMessageId(@Nullable byte[] serializedData) {
    JsonJobData data = JsonJobData.deserialize(serializedData);
    return data.getLong(KEY_MESSAGE_ID);
  }

  private static class MetricEventListener implements SignalServiceMessageSender.IndividualSendEvents {
    private final long messageId;

    private MetricEventListener(long messageId) {
      this.messageId = messageId;
    }

    @Override
    public void onMessageEncrypted() {
      SignalLocalMetrics.IndividualMessageSend.onMessageEncrypted(messageId);
    }

    @Override
    public void onMessageSent() {
      SignalLocalMetrics.IndividualMessageSend.onMessageSent(messageId);
    }

    @Override
    public void onSyncMessageSent() {
      SignalLocalMetrics.IndividualMessageSend.onSyncMessageSent(messageId);
    }
  }

  /**
   * 通过Tap v3发送消息
   * 使用SignalServiceMessageSender进行Signal E2EE加密，然后通过Tap v3传输层发送
   *
   * @param messageId 消息ID
   * @param recipient 接收方
   * @param message 待发送消息
   * @param originalEditedMessage 原始编辑消息（如果是编辑消息）
   * @return 是否使用未识别模式发送
   */
  private boolean sendMessageViaTapV3(long messageId, Recipient recipient, OutgoingMessage message, MessageRecord originalEditedMessage) 
      throws IOException {
    try {
      Log.i(TAG, "Starting Tap v3 send: messageId=" + messageId + ", recipient=" + recipient.getId());

      String recipientId = recipient.requireAci().toString();
      
      // Store attachments in TapV3AttachmentHolder for TapV3MessageTransportImpl to pick up
      // Note: We exclude stickers as they're handled differently
      List<org.thoughtcrime.securesms.attachments.Attachment> attachments = 
          message.getAttachments().stream()
              .filter(a -> !a.isSticker())
              .collect(java.util.stream.Collectors.toList());
      
      if (!attachments.isEmpty()) {
        Log.d(TAG, "Storing " + attachments.size() + " attachments for Tap v3 transport");
        org.thoughtcrime.securesms.tapv3.integration.TapV3AttachmentHolder.INSTANCE.setAttachments(
            recipientId, 
            attachments
        );
      }
      
      // Build SignalServiceDataMessage
      // For Tap v3, attachments will be uploaded to IPFS, but we still include AttachmentPointers
      // in the message so Signal knows attachments exist and triggers AttachmentDownloadJob
      SignalServiceMessageSender messageSender = AppDependencies.getSignalServiceMessageSender();
      SignalServiceAddress address = RecipientUtil.toSignalServiceAddress(context, recipient);
      
      // Create AttachmentPointers for Tap v3 attachments
      // These use cdnNumber=888 to indicate IPFS storage, with CID in remoteKey
      List<SignalServiceAttachment> serviceAttachments = new java.util.ArrayList<>();
      for (org.thoughtcrime.securesms.attachments.Attachment attachment : attachments) {
        if (attachment instanceof org.thoughtcrime.securesms.attachments.DatabaseAttachment) {
          org.thoughtcrime.securesms.attachments.DatabaseAttachment dbAttachment = 
              (org.thoughtcrime.securesms.attachments.DatabaseAttachment) attachment;
          
          // Create a placeholder AttachmentPointer
          // The actual CID will be set by TapV3MessageTransportImpl after upload
          SignalServiceAttachmentPointer pointer = 
              org.thoughtcrime.securesms.tapv3.integration.TapV3AttachmentPointerBuilder.INSTANCE.createPlaceholder(dbAttachment);
          
          if (pointer != null) {
            serviceAttachments.add(pointer);
          }
        }
      }
      
      SignalServiceDataMessage dataMessage = SignalServiceDataMessage.newBuilder()
          .withBody(message.getBody())
          .withAttachments(serviceAttachments)  // Include IPFS AttachmentPointers
          .withTimestamp(message.getSentTimeMillis())
          .withExpiration((int) (message.getExpiresIn() / 1000))
          .withExpireTimerVersion(message.getExpireTimerVersion())
          .withViewOnce(message.isViewOnce())
          .asExpirationUpdate(message.isExpirationUpdate())
          .asEndSessionMessage(message.isEndSession())
          .build();
      
      // Send via Signal's normal flow
      // TapV3MessageTransportImpl will intercept this and handle IPFS upload
      SendMessageResult result = messageSender.sendDataMessageViaTapV3(
          address,
          SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
          ContentHint.RESENDABLE,
          dataMessage,
          message.isUrgent()
      );
      
      Log.i(TAG, "Tap v3 send successful: messageId=" + messageId);
      return true;  // Tap v3 doesn't use sealed sender
      
    } catch (UntrustedIdentityException e) {
      Log.e(TAG, "Tap v3 send untrusted identity: messageId=" + messageId, e);
      
      // Clean up stored attachments on error
      try {
        String recipientId = recipient.requireAci().toString();
        org.thoughtcrime.securesms.tapv3.integration.TapV3AttachmentHolder.INSTANCE.clear(recipientId);
      } catch (Exception cleanupError) {
        Log.w(TAG, "Failed to cleanup attachments", cleanupError);
      }
      
      throw new IOException("Tap v3 untrusted identity", e);
    } catch (Exception e) {
      Log.e(TAG, "Tap v3 send exception: messageId=" + messageId, e);
      
      // Clean up stored attachments on error
      try {
        String recipientId = recipient.requireAci().toString();
        org.thoughtcrime.securesms.tapv3.integration.TapV3AttachmentHolder.INSTANCE.clear(recipientId);
      } catch (Exception cleanupError) {
        Log.w(TAG, "Failed to cleanup attachments", cleanupError);
      }
      
      throw new IOException("Tap v3 send exception", e);
    }
  }

  /**
   * 通过Tap传输层集成发送消息
   * 使用TapMessageSendIntegrator进行智能路由和发送
   *
   * @param messageId 消息ID
   * @param recipient 接收方
   * @param message 待发送消息
   * @param originalEditedMessage 原始编辑消息（如果是编辑消息）
   * @return 是否发送成功（用于unidentified标记）
   */
  private boolean sendMessageViaTapIntegration(long messageId, Recipient recipient, OutgoingMessage message, MessageRecord originalEditedMessage) {
    try {
      Log.i(TAG, "开始Tap传输层集成发送: messageId=" + messageId + ", recipient=" + recipient.getId());

      // 获取Tap集成器实例
      TapMessageSendIntegrator integrator = TapMessageSendIntegrator.Companion.getInstance(context);

      // v2 mode设计：仅使用Tap传输层，不提供Signal Server回退
      Log.i(TAG, "使用Tap传输层发送消息: messageId=" + messageId);

      // 执行集成发送
      IntegratedTapSendResult result = integrator.sendMessage(
        messageId,
        recipient,
        message
      ).get(); // 同步等待结果

      // 处理发送结果
      if (result instanceof IntegratedTapSendResult.Success) {
        IntegratedTapSendResult.Success successResult = (IntegratedTapSendResult.Success) result;
        Log.i(TAG, "Tap传输层集成发送成功: messageId=" + messageId +
              ", method=" + successResult.getMethod() +
              ", path=" + successResult.getPath());

        // 根据发送方法决定unidentified标记
        // Tap传输层发送通常不支持sealed sender，所以返回false
        // Signal Server发送则根据实际情况返回
        return successResult.getMethod().toString().equals("SIGNAL_SERVER");

      } else if (result instanceof IntegratedTapSendResult.Fallback) {
        IntegratedTapSendResult.Fallback fallbackResult = (IntegratedTapSendResult.Fallback) result;
        Log.w(TAG, "Tap不可用，回退到Signal Server发送: messageId=" + messageId + ", reason=" + fallbackResult.getReason());
        boolean unidentifiedFallback = deliver(message, originalEditedMessage);
        Log.i(TAG, "Signal Server回退发送完成: messageId=" + messageId);
        return unidentifiedFallback;

      } else if (result instanceof IntegratedTapSendResult.Failed) {
        IntegratedTapSendResult.Failed failedResult = (IntegratedTapSendResult.Failed) result;
        Log.e(TAG, "Tap传输层集成发送失败: messageId=" + messageId + ", reason=" + failedResult.getReason());

        // 发送失败，抛出异常让上层处理
        throw new IOException("Tap传输层集成发送失败: " + failedResult.getReason());

      } else if (result instanceof IntegratedTapSendResult.RetryScheduled) {
        IntegratedTapSendResult.RetryScheduled retryResult = (IntegratedTapSendResult.RetryScheduled) result;
        Log.i(TAG, "Tap传输层集成发送重试已安排: messageId=" + messageId + ", message=" + retryResult.getMessage());

        // 重试已安排，暂时返回false，等待重试结果
        // 这种情况下消息状态会由重试机制处理
        return false;

      } else {
        Log.e(TAG, "Tap传输层集成发送返回未知结果类型: messageId=" + messageId + ", result=" + result);
        throw new IOException("Tap传输层集成发送返回未知结果类型");
      }

    } catch (Exception e) {
      Log.e(TAG, "Tap传输层集成发送异常: messageId=" + messageId, e);

      // v2 mode设计：Tap发送异常时不回退到Signal Server，直接失败
      Log.w(TAG, "Tap发送异常，v2 mode不回退到Signal Server: messageId=" + messageId);
      
      // 直接抛出原始异常，不进行回退
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new RuntimeException("Tap发送失败，v2 mode不支持回退", e);
      }
    }
  }

  public static final class Factory implements Job.Factory<IndividualSendJob> {
    @Override
    public @NonNull IndividualSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new IndividualSendJob(parameters, data.getLong(KEY_MESSAGE_ID));
    }
  }
}
