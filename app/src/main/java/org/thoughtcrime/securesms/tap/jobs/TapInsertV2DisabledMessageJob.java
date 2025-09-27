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

public final class TapInsertV2DisabledMessageJob extends BaseJob {

  public static final String KEY = "TapInsertV2DisabledMessageJob";
  private static final String TAG = Log.tag(TapInsertV2DisabledMessageJob.class);

  private static final String KEY_RECIPIENT_ID = "recipient_id";

  private final RecipientId recipientId;

  public TapInsertV2DisabledMessageJob(@NonNull RecipientId recipientId) {
    super(new Parameters.Builder().setQueue(recipientId.toQueueKey()).build());
    this.recipientId = recipientId;
  }

  private TapInsertV2DisabledMessageJob(@NonNull Parameters parameters, @NonNull RecipientId recipientId) {
    super(parameters);
    this.recipientId = recipientId;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    MessageTable.InsertResult result = SignalDatabase.messages().insertTapV2ModeDisabledMessage(recipientId);
    Log.i(TAG, "Inserted v2-disabled system message: messageId=" + result.getMessageId() + ", threadId=" + result.getThreadId());
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to insert v2-disabled system message for recipientId: " + recipientId);
  }

  @Override
  public byte[] serialize() {
    return new JsonJobData.Builder()
        .putString(KEY_RECIPIENT_ID, recipientId.serialize())
        .serialize();
  }

  public static final class Factory implements Job.Factory<TapInsertV2DisabledMessageJob> {
    @Override
    public @NonNull TapInsertV2DisabledMessageJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      RecipientId recipientId = RecipientId.from(data.getString(KEY_RECIPIENT_ID));
      return new TapInsertV2DisabledMessageJob(parameters, recipientId);
    }
  }
} 