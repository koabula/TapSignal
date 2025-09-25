package org.thoughtcrime.securesms.tap.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.DatabaseTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.util.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 传输轮询状态持久化表
 * 
 * 存储轮询状态信息，包括已处理文件列表、最后轮询时间、错误计数等
 * 用于避免消息重复处理和丢失
 */
public class TransportPollingStateTable extends DatabaseTable {

    private static final String TAG = Log.tag(TransportPollingStateTable.class);

    public static final String TABLE_NAME = "transport_polling_state";

    private static final String ID                    = "_id";
    private static final String RECIPIENT_ID          = "recipient_id";
    private static final String PROVIDER_TYPE         = "provider_type";
    private static final String LAST_PROCESSED_TIME   = "last_processed_time";
    private static final String PROCESSED_FILES_JSON  = "processed_files_json";
    private static final String LAST_POLLING_CURSOR   = "last_polling_cursor";
    private static final String CONSECUTIVE_ERRORS    = "consecutive_errors";
    private static final String LAST_ERROR_TIME       = "last_error_time";
    private static final String LAST_ERROR_MESSAGE    = "last_error_message";
    private static final String TOTAL_POLLS           = "total_polls";
    private static final String SUCCESSFUL_POLLS      = "successful_polls";
    private static final String MESSAGES_FOUND        = "messages_found";
    private static final String VERSION               = "version";
    private static final String UPDATED_AT            = "updated_at";

    // 消息去重表常量
    public static final String PROCESSED_MESSAGES_TABLE = "transport_processed_messages";
    private static final String PM_ID                    = "_id";
    private static final String PM_DUPLICATION_KEY      = "duplication_key";
    private static final String PM_PROCESSED_TIMESTAMP  = "processed_timestamp";
    private static final String PM_CREATED_AT           = "created_at";

    public static final String CREATE_TABLE = 
        "CREATE TABLE " + TABLE_NAME + "(" +
            ID                    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            RECIPIENT_ID          + " TEXT NOT NULL, " +
            PROVIDER_TYPE         + " TEXT NOT NULL, " +
            LAST_PROCESSED_TIME   + " INTEGER NOT NULL DEFAULT 0, " +
            PROCESSED_FILES_JSON  + " TEXT NOT NULL DEFAULT '[]', " +
            LAST_POLLING_CURSOR   + " TEXT DEFAULT NULL, " +
            CONSECUTIVE_ERRORS    + " INTEGER NOT NULL DEFAULT 0, " +
            LAST_ERROR_TIME       + " INTEGER DEFAULT NULL, " +
            LAST_ERROR_MESSAGE    + " TEXT DEFAULT NULL, " +
            TOTAL_POLLS           + " INTEGER NOT NULL DEFAULT 0, " +
            SUCCESSFUL_POLLS      + " INTEGER NOT NULL DEFAULT 0, " +
            MESSAGES_FOUND        + " INTEGER NOT NULL DEFAULT 0, " +
            VERSION               + " INTEGER NOT NULL DEFAULT 1, " +
            UPDATED_AT            + " INTEGER NOT NULL DEFAULT 0, " +
            "UNIQUE(" + RECIPIENT_ID + ", " + PROVIDER_TYPE + ") ON CONFLICT REPLACE" +
        ")";

    public static final String CREATE_PROCESSED_MESSAGES_TABLE = 
        "CREATE TABLE " + PROCESSED_MESSAGES_TABLE + "(" +
            PM_ID                    + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            PM_DUPLICATION_KEY       + " TEXT UNIQUE NOT NULL, " +
            PM_PROCESSED_TIMESTAMP   + " INTEGER NOT NULL, " +
            PM_CREATED_AT           + " INTEGER NOT NULL" +
        ")";

    public static final String[] CREATE_INDEXES = {
        "CREATE INDEX IF NOT EXISTS transport_polling_state_recipient_idx ON " + TABLE_NAME + " (" + RECIPIENT_ID + ")",
        "CREATE INDEX IF NOT EXISTS transport_polling_state_provider_idx ON " + TABLE_NAME + " (" + PROVIDER_TYPE + ")",
        "CREATE INDEX IF NOT EXISTS transport_polling_state_last_processed_idx ON " + TABLE_NAME + " (" + LAST_PROCESSED_TIME + ")",
        "CREATE INDEX IF NOT EXISTS transport_polling_state_errors_idx ON " + TABLE_NAME + " (" + CONSECUTIVE_ERRORS + ", " + LAST_ERROR_TIME + ")"
    };

    /**
     * 处理文件集合的JSON包装类
     */
    public static class ProcessedFilesData {
        public Set<String> files = new HashSet<>();

        public ProcessedFilesData() {}

        public ProcessedFilesData(Set<String> files) {
            this.files = files != null ? files : new HashSet<>();
        }
    }

    public TransportPollingStateTable(@NonNull Context context, @NonNull SignalDatabase databaseHelper) {
        super(context, databaseHelper);
    }

    /**
     * 轮询状态数据类
     */
    public static class PollingState {
        public final String recipientId;
        public final String providerType;
        public final long lastProcessedTime;
        public final Set<String> processedFiles;
        public final String lastPollingCursor;
        public final int consecutiveErrors;
        public final long lastErrorTime;
        public final String lastErrorMessage;
        public final long totalPolls;
        public final long successfulPolls;
        public final long messagesFound;
        public final long version;
        public final long updatedAt;

        public PollingState(String recipientId, String providerType, long lastProcessedTime,
                          Set<String> processedFiles, String lastPollingCursor,
                          int consecutiveErrors, long lastErrorTime, String lastErrorMessage,
                          long totalPolls, long successfulPolls, long messagesFound,
                          long version, long updatedAt) {
            this.recipientId = recipientId;
            this.providerType = providerType;
            this.lastProcessedTime = lastProcessedTime;
            this.processedFiles = processedFiles != null ? processedFiles : new HashSet<>();
            this.lastPollingCursor = lastPollingCursor;
            this.consecutiveErrors = consecutiveErrors;
            this.lastErrorTime = lastErrorTime;
            this.lastErrorMessage = lastErrorMessage;
            this.totalPolls = totalPolls;
            this.successfulPolls = successfulPolls;
            this.messagesFound = messagesFound;
            this.version = version;
            this.updatedAt = updatedAt;
        }

        public double getSuccessRate() {
            return totalPolls > 0 ? (double) successfulPolls / totalPolls : 0.0;
        }

        public boolean hasConsecutiveErrors() {
            return consecutiveErrors > 0;
        }

        public boolean isFileProcessed(@NonNull String fileName) {
            return processedFiles.contains(fileName);
        }
    }

    /**
     * 获取轮询状态
     */
    @WorkerThread
    @Nullable
    public PollingState getPollingState(@NonNull String recipientId, @NonNull String providerType) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            RECIPIENT_ID + " = ? AND " + PROVIDER_TYPE + " = ?",
            new String[]{recipientId, providerType},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return readPollingState(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取轮询状态失败: " + recipientId + ", " + providerType, e);
        }
        return null;
    }

    /**
     * 更新轮询状态
     */
    @WorkerThread
    public void updatePollingState(@NonNull PollingState state) {
        try {
            ContentValues values = new ContentValues();
            values.put(RECIPIENT_ID, state.recipientId);
            values.put(PROVIDER_TYPE, state.providerType);
            values.put(LAST_PROCESSED_TIME, state.lastProcessedTime);
            values.put(PROCESSED_FILES_JSON, serializeProcessedFiles(state.processedFiles));
            values.put(LAST_POLLING_CURSOR, state.lastPollingCursor);
            values.put(CONSECUTIVE_ERRORS, state.consecutiveErrors);
            values.put(LAST_ERROR_TIME, state.lastErrorTime);
            values.put(LAST_ERROR_MESSAGE, state.lastErrorMessage);
            values.put(TOTAL_POLLS, state.totalPolls);
            values.put(SUCCESSFUL_POLLS, state.successfulPolls);
            values.put(MESSAGES_FOUND, state.messagesFound);
            values.put(VERSION, state.version + 1);
            values.put(UPDATED_AT, System.currentTimeMillis());

            // 使用标准的upsert逻辑
            int updatedRows = getWritableDatabase().update(
                TABLE_NAME,
                values,
                RECIPIENT_ID + " = ? AND " + PROVIDER_TYPE + " = ?",
                new String[]{state.recipientId, state.providerType}
            );
            if (updatedRows == 0) {
                getWritableDatabase().insert(TABLE_NAME, null, values);
            }

            Log.d(TAG, "轮询状态更新成功: " + state.recipientId + ", " + state.providerType);
        } catch (Exception e) {
            Log.e(TAG, "更新轮询状态失败: " + state.recipientId + ", " + state.providerType, e);
        }
    }

    /**
     * 记录成功轮询
     */
    @WorkerThread
    public void recordSuccessfulPoll(@NonNull String recipientId, @NonNull String providerType,
                                   @NonNull Set<String> newProcessedFiles, int messagesFoundCount) {
        PollingState currentState = getPollingState(recipientId, providerType);
        long currentTime = System.currentTimeMillis();
        
        if (currentState == null) {
            // 创建新状态
            PollingState newState = new PollingState(
                recipientId, providerType, currentTime, newProcessedFiles, null,
                0, 0, null, 1, 1, messagesFoundCount, 1, currentTime
            );
            updatePollingState(newState);
        } else {
            // 更新现有状态
            Set<String> allProcessedFiles = new HashSet<>(currentState.processedFiles);
            allProcessedFiles.addAll(newProcessedFiles);
            
            PollingState updatedState = new PollingState(
                recipientId, providerType, currentTime, allProcessedFiles, currentState.lastPollingCursor,
                0, // 成功时重置错误计数
                currentState.lastErrorTime, currentState.lastErrorMessage,
                currentState.totalPolls + 1, currentState.successfulPolls + 1,
                currentState.messagesFound + messagesFoundCount,
                currentState.version, currentTime
            );
            updatePollingState(updatedState);
        }
    }

    /**
     * 记录失败轮询
     */
    @WorkerThread
    public void recordFailedPoll(@NonNull String recipientId, @NonNull String providerType,
                                @NonNull String errorMessage) {
        PollingState currentState = getPollingState(recipientId, providerType);
        long currentTime = System.currentTimeMillis();
        
        if (currentState == null) {
            // 创建新状态
            PollingState newState = new PollingState(
                recipientId, providerType, 0, new HashSet<>(), null,
                1, currentTime, errorMessage, 1, 0, 0, 1, currentTime
            );
            updatePollingState(newState);
        } else {
            // 更新现有状态
            PollingState updatedState = new PollingState(
                recipientId, providerType, currentState.lastProcessedTime, currentState.processedFiles,
                currentState.lastPollingCursor, currentState.consecutiveErrors + 1,
                currentTime, errorMessage,
                currentState.totalPolls + 1, currentState.successfulPolls, currentState.messagesFound,
                currentState.version, currentTime
            );
            updatePollingState(updatedState);
        }
    }

    /**
     * 清理过期的轮询状态
     */
    @WorkerThread
    public int cleanupExpiredStates(long expirationTime) {
        try {
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                UPDATED_AT + " < ?",
                new String[]{String.valueOf(expirationTime)}
            );
            Log.d(TAG, "清理过期轮询状态: " + deletedCount);
            return deletedCount;
        } catch (Exception e) {
            Log.e(TAG, "清理过期轮询状态失败", e);
            return 0;
        }
    }

    /**
     * 获取所有轮询状态
     */
    @WorkerThread
    @NonNull
    public List<PollingState> getAllPollingStates() {
        List<PollingState> states = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            UPDATED_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    PollingState state = readPollingState(cursor);
                    if (state != null) {
                        states.add(state);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取所有轮询状态失败", e);
        }
        
        return states;
    }

    /**
     * 获取错误过多的轮询状态
     */
    @WorkerThread
    @NonNull
    public List<PollingState> getStatesWithErrors(int errorThreshold) {
        List<PollingState> states = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            CONSECUTIVE_ERRORS + " >= ?",
            new String[]{String.valueOf(errorThreshold)},
            null,
            null,
            LAST_ERROR_TIME + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    PollingState state = readPollingState(cursor);
                    if (state != null) {
                        states.add(state);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取错误轮询状态失败", e);
        }
        
        return states;
    }

    /**
     * 删除指定的轮询状态
     */
    @WorkerThread
    public boolean deletePollingState(@NonNull String recipientId, @NonNull String providerType) {
        try {
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                RECIPIENT_ID + " = ? AND " + PROVIDER_TYPE + " = ?",
                new String[]{recipientId, providerType}
            );
            Log.d(TAG, "删除轮询状态: " + recipientId + ", " + providerType + ", 结果: " + deletedCount);
            return deletedCount > 0;
        } catch (Exception e) {
            Log.e(TAG, "删除轮询状态失败: " + recipientId + ", " + providerType, e);
            return false;
        }
    }

    /**
     * 从Cursor读取PollingState
     */
    @Nullable
    private PollingState readPollingState(@NonNull Cursor cursor) {
        try {
            String recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID));
            String providerType = cursor.getString(cursor.getColumnIndexOrThrow(PROVIDER_TYPE));
            long lastProcessedTime = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_PROCESSED_TIME));
            String processedFilesJson = cursor.getString(cursor.getColumnIndexOrThrow(PROCESSED_FILES_JSON));
            String lastPollingCursor = cursor.getString(cursor.getColumnIndexOrThrow(LAST_POLLING_CURSOR));
            int consecutiveErrors = cursor.getInt(cursor.getColumnIndexOrThrow(CONSECUTIVE_ERRORS));
            long lastErrorTime = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_ERROR_TIME));
            String lastErrorMessage = cursor.getString(cursor.getColumnIndexOrThrow(LAST_ERROR_MESSAGE));
            long totalPolls = cursor.getLong(cursor.getColumnIndexOrThrow(TOTAL_POLLS));
            long successfulPolls = cursor.getLong(cursor.getColumnIndexOrThrow(SUCCESSFUL_POLLS));
            long messagesFound = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGES_FOUND));
            long version = cursor.getLong(cursor.getColumnIndexOrThrow(VERSION));
            long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(UPDATED_AT));

            Set<String> processedFiles = deserializeProcessedFiles(processedFilesJson);

            return new PollingState(
                recipientId, providerType, lastProcessedTime, processedFiles, lastPollingCursor,
                consecutiveErrors, lastErrorTime, lastErrorMessage,
                totalPolls, successfulPolls, messagesFound, version, updatedAt
            );
        } catch (Exception e) {
            Log.e(TAG, "读取轮询状态数据失败", e);
            return null;
        }
    }

    /**
     * 使用Jackson序列化处理文件集合
     */
    @NonNull
    private String serializeProcessedFiles(@NonNull Set<String> files) {
        try {
            ProcessedFilesData data = new ProcessedFilesData(files);
            return JsonUtils.toJson(data);
        } catch (IOException e) {
            Log.e(TAG, "序列化处理文件集合失败", e);
            return "{\"files\":[]}";
        }
    }

    /**
     * 使用Jackson反序列化处理文件集合
     */
    @NonNull
    private Set<String> deserializeProcessedFiles(@NonNull String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return new HashSet<>();
            }
            
            // 兼容旧格式的简单数组
            if (json.trim().startsWith("[")) {
                List<String> fileList = JsonUtils.fromJsonArray(json, String.class);
                return new HashSet<>(fileList);
            }
            
            // 新格式的对象结构
            ProcessedFilesData data = JsonUtils.fromJson(json, ProcessedFilesData.class);
            return data.files != null ? data.files : new HashSet<>();
            
        } catch (IOException e) {
            Log.w(TAG, "反序列化处理文件集合失败，使用空集合: " + json, e);
            return new HashSet<>();
        }
    }

    // ==================== 消息去重支持方法 ====================
    
    /**
     * 标记消息为已处理（用于去重）
     */
    @WorkerThread
    public void markMessageAsProcessed(@NonNull String duplicationKey, long timestamp) {
        try {
            ContentValues values = new ContentValues();
            values.put(PM_DUPLICATION_KEY, duplicationKey);
            values.put(PM_PROCESSED_TIMESTAMP, timestamp);
            values.put(PM_CREATED_AT, System.currentTimeMillis());

            // 使用标准的upsert逻辑
            int updatedRows = getWritableDatabase().update(
                PROCESSED_MESSAGES_TABLE, 
                values, 
                PM_DUPLICATION_KEY + " = ?", 
                new String[]{duplicationKey}
            );
            if (updatedRows == 0) {
                getWritableDatabase().insert(PROCESSED_MESSAGES_TABLE, null, values);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "标记消息已处理失败: " + duplicationKey, e);
        }
    }
    
    /**
     * 检查消息是否已处理
     */
    @WorkerThread
    public boolean isMessageProcessed(@NonNull String duplicationKey) {
        try (Cursor cursor = getReadableDatabase().query(
            PROCESSED_MESSAGES_TABLE,
            new String[]{"COUNT(*)"},
            PM_DUPLICATION_KEY + " = ?",
            new String[]{duplicationKey},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查消息处理状态失败: " + duplicationKey, e);
        }
        return false;
    }
    
    /**
     * 获取最近处理的消息键列表
     */
    @WorkerThread
    @NonNull
    public List<String> getRecentProcessedMessageKeys(long cutoffTime) {
        List<String> keys = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            PROCESSED_MESSAGES_TABLE,
            new String[]{PM_DUPLICATION_KEY},
            PM_PROCESSED_TIMESTAMP + " >= ?",
            new String[]{String.valueOf(cutoffTime)},
            null,
            null,
            PM_PROCESSED_TIMESTAMP + " DESC",
            "1000"
        )) {
            while (cursor != null && cursor.moveToNext()) {
                keys.add(cursor.getString(0));
            }
        } catch (Exception e) {
            Log.e(TAG, "获取最近处理消息键失败", e);
        }
        
        return keys;
    }
    
    /**
     * 清理过期的消息去重记录
     */
    @WorkerThread
    public int cleanupExpiredMessages(long cutoffTime) {
        try {
            int deletedCount = getWritableDatabase().delete(
                PROCESSED_MESSAGES_TABLE,
                PM_PROCESSED_TIMESTAMP + " < ?",
                new String[]{String.valueOf(cutoffTime)}
            );
            
            if (deletedCount > 0) {
                Log.d(TAG, "清理过期消息去重记录: " + deletedCount + "条");
            }
            
            return deletedCount;
        } catch (Exception e) {
            Log.e(TAG, "清理过期消息去重记录失败", e);
            return 0;
        }
    }

} 