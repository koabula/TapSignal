package org.thoughtcrime.securesms.tap.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.Job.Parameters;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.recipients.RecipientId;

public final class TapInsertV2EnabledMessageJob extends BaseJob {

  public static final String KEY = "TapInsertV2EnabledMessageJob";
  private static final String TAG = Log.tag(TapInsertV2EnabledMessageJob.class);

  private static final String KEY_RECIPIENT_ID = "recipient_id";

  private final RecipientId recipientId;

  public TapInsertV2EnabledMessageJob(@NonNull RecipientId recipientId) {
    super(new Parameters.Builder().setQueue(recipientId.toQueueKey()).build());
    this.recipientId = recipientId;
  }

  private TapInsertV2EnabledMessageJob(@NonNull Parameters parameters, @NonNull RecipientId recipientId) {
    super(parameters);
    this.recipientId = recipientId;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    // Run in DB context via MessageTable, relying on internal transaction mgmt.
    MessageTable.InsertResult result = SignalDatabase.messages().insertTapV2ModeEnabledMessage(recipientId);
    Log.i(TAG, "Inserted v2-enabled system message: messageId=" + result.getMessageId() + ", threadId=" + result.getThreadId());
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    // Conservative: do not retry repeatedly; it's a cosmetic system message.
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to insert v2-enabled system message for recipientId: " + recipientId);
  }

  @Override
  public byte[] serialize() {
    return new JsonJobData.Builder()
        .putString(KEY_RECIPIENT_ID, recipientId.serialize())
        .serialize();
  }

  public static final class Factory implements Job.Factory<TapInsertV2EnabledMessageJob> {
    @Override
    public @NonNull TapInsertV2EnabledMessageJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      RecipientId recipientId = RecipientId.from(data.getString(KEY_RECIPIENT_ID));
      return new TapInsertV2EnabledMessageJob(parameters, recipientId);
    }
  }
} 