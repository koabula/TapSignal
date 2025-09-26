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
import org.thoughtcrime.securesms.tap.TransportChannel;
import org.thoughtcrime.securesms.tap.TransportChannelStatus;
import org.thoughtcrime.securesms.tap.TransportError;
import org.thoughtcrime.securesms.tap.TransportMetadata;
import org.thoughtcrime.securesms.tap.utils.TransportMetadataFactory;
import org.thoughtcrime.securesms.util.JsonUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * 传输通道持久化表
 * 
 * 存储传输通道的状态信息，包括通道ID、接收者、提供者类型、状态、创建时间等
 */
public class TransportChannelTable extends DatabaseTable {

    private static final String TAG = Log.tag(TransportChannelTable.class);

    public static final String TABLE_NAME = "transport_channels";

    private static final String ID                = "_id";
    private static final String CHANNEL_ID        = "channel_id";
    private static final String RECIPIENT_ID      = "recipient_id";
    private static final String PROVIDER_TYPE     = "provider_type";
    private static final String METADATA_JSON     = "metadata_json";
    private static final String STATUS            = "status";
    private static final String PRIORITY          = "priority";
    private static final String CREATED_AT        = "created_at";
    private static final String LAST_ACTIVE_AT    = "last_active_at";
    private static final String SUCCESS_COUNT     = "success_count";
    private static final String FAILURE_COUNT     = "failure_count";
    private static final String LAST_ERROR        = "last_error";
    private static final String CONFIG_JSON       = "config_json";
    private static final String VERSION           = "version";

    public static final String CREATE_TABLE = 
        "CREATE TABLE " + TABLE_NAME + "(" +
            ID                + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            CHANNEL_ID        + " TEXT NOT NULL UNIQUE, " +
            RECIPIENT_ID      + " TEXT NOT NULL, " +
            PROVIDER_TYPE     + " TEXT NOT NULL, " +
            METADATA_JSON     + " TEXT NOT NULL, " +
            STATUS            + " INTEGER NOT NULL, " +
            PRIORITY          + " INTEGER NOT NULL DEFAULT 5, " +
            CREATED_AT        + " INTEGER NOT NULL, " +
            LAST_ACTIVE_AT    + " INTEGER NOT NULL, " +
            SUCCESS_COUNT     + " INTEGER NOT NULL DEFAULT 0, " +
            FAILURE_COUNT     + " INTEGER NOT NULL DEFAULT 0, " +
            LAST_ERROR        + " TEXT DEFAULT NULL, " +
            CONFIG_JSON       + " TEXT NOT NULL DEFAULT '{}', " +
            VERSION           + " INTEGER NOT NULL DEFAULT 1" +
        ")";

    public static final String[] CREATE_INDEXES = {
        "CREATE INDEX IF NOT EXISTS transport_channels_recipient_provider_idx ON " + TABLE_NAME + " (" + RECIPIENT_ID + ", " + PROVIDER_TYPE + ")",
        "CREATE INDEX IF NOT EXISTS transport_channels_status_idx ON " + TABLE_NAME + " (" + STATUS + ")",
        "CREATE INDEX IF NOT EXISTS transport_channels_last_active_idx ON " + TABLE_NAME + " (" + LAST_ACTIVE_AT + ")",
        "CREATE INDEX IF NOT EXISTS transport_channels_provider_idx ON " + TABLE_NAME + " (" + PROVIDER_TYPE + ")"
    };

    public TransportChannelTable(@NonNull Context context, @NonNull SignalDatabase databaseHelper) {
        super(context, databaseHelper);
    }

    /**
     * 插入或更新传输通道
     * 使用乐观锁确保并发安全
     */
    @WorkerThread
    public void insertOrUpdateChannel(@NonNull TransportChannel channel) {
        synchronized (this) { // 添加对象级同步，防止同一个表的并发修改
            try {
                // 获取当前版本号（用于乐观锁检查）
                long currentVersion = getCurrentChannelVersion(channel.getChannelId());
                
                ContentValues values = new ContentValues();
                values.put(CHANNEL_ID, channel.getChannelId());
                values.put(RECIPIENT_ID, channel.getRecipientId());
                values.put(PROVIDER_TYPE, channel.getProviderType());
                values.put(METADATA_JSON, channel.getMetadata().toJson());
                values.put(STATUS, channel.getStatus().ordinal());
                values.put(PRIORITY, channel.getPriority());
                values.put(CREATED_AT, channel.getCreatedAt());
                values.put(LAST_ACTIVE_AT, channel.getLastActiveAt());
                values.put(SUCCESS_COUNT, channel.getSuccessCount());
                values.put(FAILURE_COUNT, channel.getFailureCount());
                values.put(LAST_ERROR, channel.getLastError() != null ? channel.getLastError().name() : null);
                values.put(CONFIG_JSON, serializeChannelConfig(channel.getConfig()));
                
                if (currentVersion >= 0) {
                    // 记录存在，使用乐观锁更新
                    long newVersion = currentVersion + 1;
                    values.put(VERSION, newVersion);
                    
                    int updatedRows = getWritableDatabase().update(
                        TABLE_NAME, 
                        values, 
                        CHANNEL_ID + " = ? AND " + VERSION + " = ?", 
                        new String[]{channel.getChannelId(), String.valueOf(currentVersion)}
                    );
                    
                    if (updatedRows == 0) {
                        // 乐观锁冲突，记录已被其他线程修改
                        Log.w(TAG, "乐观锁冲突，通道可能已被其他线程修改: " + channel.getChannelId());
                        throw new OptimisticLockException("通道版本冲突: " + channel.getChannelId());
                    }
                    
                    Log.d(TAG, "通道更新成功: " + channel.getChannelId() + ", 版本: " + currentVersion + " -> " + newVersion);
                } else {
                    // 记录不存在，插入新记录
                    values.put(VERSION, 1); // 新记录版本从1开始
                    
                    long insertId = getWritableDatabase().insert(TABLE_NAME, null, values);
                    if (insertId < 0) {
                        throw new IllegalStateException("插入通道失败: " + channel.getChannelId());
                    }
                    
                    Log.d(TAG, "通道插入成功: " + channel.getChannelId() + ", ID: " + insertId);
                }
                
            } catch (OptimisticLockException e) {
                // 重新抛出乐观锁异常，让调用者决定如何处理
                throw e;
            } catch (Exception e) {
                Log.e(TAG, "保存通道失败: " + channel.getChannelId(), e);
                throw new RuntimeException("保存通道失败", e);
            }
        }
    }

    /**
     * 根据通道ID获取通道
     */
    @WorkerThread
    @Nullable
    public TransportChannel getChannel(@NonNull String channelId) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            CHANNEL_ID + " = ?",
            new String[]{channelId},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return readTransportChannel(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "查询通道失败: " + channelId, e);
        }
        return null;
    }

    /**
     * 获取所有通道
     */
    @WorkerThread
    @NonNull
    public List<TransportChannel> getAllChannels() {
        List<TransportChannel> channels = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            LAST_ACTIVE_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    TransportChannel channel = readTransportChannel(cursor);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "查询所有通道失败", e);
        }
        
        return channels;
    }

    /**
     * 获取指定接收者的活跃通道
     */
    @WorkerThread
    @NonNull
    public List<TransportChannel> getActiveChannelsForRecipient(@NonNull String recipientId) {
        List<TransportChannel> channels = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            RECIPIENT_ID + " = ? AND " + STATUS + " IN (?, ?, ?)",
            new String[]{
                recipientId,
                String.valueOf(TransportChannelStatus.ACTIVE.ordinal()),
                String.valueOf(TransportChannelStatus.FULL_ACTIVE.ordinal()),
                String.valueOf(TransportChannelStatus.SEND_READY.ordinal())
            },
            null,
            null,
            PRIORITY + " DESC, " + LAST_ACTIVE_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    TransportChannel channel = readTransportChannel(cursor);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "查询接收者活跃通道失败: " + recipientId, e);
        }
        
        return channels;
    }

    /**
     * 获取所有活跃通道
     */
    @WorkerThread
    @NonNull
    public List<TransportChannel> getAllActiveChannels() {
        List<TransportChannel> channels = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            STATUS + " IN (?, ?, ?)",
            new String[]{
                String.valueOf(TransportChannelStatus.ACTIVE.ordinal()),
                String.valueOf(TransportChannelStatus.FULL_ACTIVE.ordinal()),
                String.valueOf(TransportChannelStatus.SEND_READY.ordinal())
            },
            null,
            null,
            PRIORITY + " DESC, " + LAST_ACTIVE_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    TransportChannel channel = readTransportChannel(cursor);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "查询所有活跃通道失败", e);
        }
        
        return channels;
    }

    /**
     * 获取指定Provider的通道
     */
    @WorkerThread
    @NonNull
    public List<TransportChannel> getChannelsForProvider(@NonNull String providerType) {
        List<TransportChannel> channels = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            PROVIDER_TYPE + " = ?",
            new String[]{providerType},
            null,
            null,
            LAST_ACTIVE_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    TransportChannel channel = readTransportChannel(cursor);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "查询Provider通道失败: " + providerType, e);
        }
        
        return channels;
    }

    /**
     * 删除过期的通道
     */
    @WorkerThread
    public int deleteExpiredChannels(long expirationTime) {
        try {
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                LAST_ACTIVE_AT + " < ? AND " + STATUS + " NOT IN (?, ?)",
                new String[]{
                    String.valueOf(expirationTime),
                    String.valueOf(TransportChannelStatus.ACTIVE.ordinal()),
                    String.valueOf(TransportChannelStatus.ESTABLISHING.ordinal())
                }
            );
            Log.d(TAG, "清理过期通道: " + deletedCount);
            return deletedCount;
        } catch (Exception e) {
            Log.e(TAG, "清理过期通道失败", e);
            return 0;
        }
    }

    /**
     * 删除指定通道
     */
    @WorkerThread
    public boolean deleteChannel(@NonNull String channelId) {
        try {
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                CHANNEL_ID + " = ?",
                new String[]{channelId}
            );
            Log.d(TAG, "删除通道: " + channelId + ", 结果: " + deletedCount);
            return deletedCount > 0;
        } catch (Exception e) {
            Log.e(TAG, "删除通道失败: " + channelId, e);
            return false;
        }
    }

    /**
     * 更新通道状态
     */
    @WorkerThread
    public boolean updateChannelStatus(@NonNull String channelId, @NonNull TransportChannelStatus status) {
        try {
            ContentValues values = new ContentValues();
            values.put(STATUS, status.ordinal());
            values.put(LAST_ACTIVE_AT, System.currentTimeMillis());

            int updatedCount = getWritableDatabase().update(
                TABLE_NAME,
                values,
                CHANNEL_ID + " = ?",
                new String[]{channelId}
            );
            
            return updatedCount > 0;
        } catch (Exception e) {
            Log.e(TAG, "更新通道状态失败: " + channelId, e);
            return false;
        }
    }

    /**
     * 获取通道当前版本号
     * 
     * @param channelId 通道ID
     * @return 版本号，如果通道不存在返回-1
     */
    private long getCurrentChannelVersion(@NonNull String channelId) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            new String[]{VERSION},
            CHANNEL_ID + " = ?",
            new String[]{channelId},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            } else {
                return -1; // 记录不存在
            }
        } catch (Exception e) {
            Log.e(TAG, "获取通道版本失败: " + channelId, e);
            return -1;
        }
    }
    
    /**
     * 从Cursor读取TransportChannel
     */
    @Nullable
    private TransportChannel readTransportChannel(@NonNull Cursor cursor) {
        try {
            String channelId = cursor.getString(cursor.getColumnIndexOrThrow(CHANNEL_ID));
            String recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID));
            String providerType = cursor.getString(cursor.getColumnIndexOrThrow(PROVIDER_TYPE));
            String metadataJson = cursor.getString(cursor.getColumnIndexOrThrow(METADATA_JSON));
            int statusOrdinal = cursor.getInt(cursor.getColumnIndexOrThrow(STATUS));
            int priority = cursor.getInt(cursor.getColumnIndexOrThrow(PRIORITY));
            long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CREATED_AT));
            long lastActiveAt = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_ACTIVE_AT));
            int successCount = cursor.getInt(cursor.getColumnIndexOrThrow(SUCCESS_COUNT));
            int failureCount = cursor.getInt(cursor.getColumnIndexOrThrow(FAILURE_COUNT));
            String lastError = cursor.getString(cursor.getColumnIndexOrThrow(LAST_ERROR));
            String configJson = cursor.getString(cursor.getColumnIndexOrThrow(CONFIG_JSON));
            long version = cursor.getLong(cursor.getColumnIndexOrThrow(VERSION));

            TransportChannelStatus status = TransportChannelStatus.values()[statusOrdinal];
            
            // 根据providerType反序列化metadata
            TransportMetadata metadata = TransportMetadataFactory.INSTANCE.createFromJson(providerType, metadataJson);
            if (metadata == null) {
                Log.w(TAG, "无法反序列化通道元数据: " + channelId + ", providerType: " + providerType);
                return null;
            }
            
            // 处理lastError
            TransportError error = null;
            if (lastError != null && !lastError.isEmpty()) {
                try {
                    error = TransportError.valueOf(lastError);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "无效的错误类型: " + lastError, e);
                }
            }
            
            // 反序列化config
            Map<String, Object> config = deserializeChannelConfig(configJson);
            
            Log.d(TAG, "从数据库读取通道: " + channelId);
            
            return new TransportChannel(
                channelId,
                recipientId,
                providerType,
                metadata,
                status,
                createdAt,
                lastActiveAt,
                failureCount,
                successCount,
                error,
                config,
                priority,
                version
            );
            
        } catch (Exception e) {
            Log.e(TAG, "读取通道数据失败", e);
            return null;
        }
    }

    /**
     * 获取通道统计信息
     */
    @WorkerThread
    @NonNull
    public ChannelStatistics getChannelStatistics() {
        int totalChannels = 0;
        int activeChannels = 0;
        int failedChannels = 0;
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            new String[]{"COUNT(*)", "SUM(CASE WHEN " + STATUS + " = " + TransportChannelStatus.ACTIVE.ordinal() + " THEN 1 ELSE 0 END)",
                        "SUM(CASE WHEN " + STATUS + " = " + TransportChannelStatus.FAILED.ordinal() + " THEN 1 ELSE 0 END)"},
            null,
            null,
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                totalChannels = cursor.getInt(0);
                activeChannels = cursor.getInt(1);
                failedChannels = cursor.getInt(2);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取通道统计失败", e);
        }
        
        return new ChannelStatistics(totalChannels, activeChannels, failedChannels);
    }

    /**
     * 通道统计信息数据类
     */
    public static class ChannelStatistics {
        public final int totalChannels;
        public final int activeChannels;
        public final int failedChannels;

        public ChannelStatistics(int totalChannels, int activeChannels, int failedChannels) {
            this.totalChannels = totalChannels;
            this.activeChannels = activeChannels;
            this.failedChannels = failedChannels;
        }
    }

    /**
     * 使用Jackson序列化通道配置
     */
    @NonNull
    private String serializeChannelConfig(@NonNull Map<String, Object> config) {
        try {
            if (config == null || config.isEmpty()) {
                return "{}";
            }
            return JsonUtils.toJson(config);
        } catch (IOException e) {
            Log.e(TAG, "序列化通道配置失败", e);
            return "{}";
        }
    }

    /**
     * 使用Jackson反序列化通道配置
     */
    @NonNull
    private Map<String, Object> deserializeChannelConfig(@NonNull String json) {
        try {
            if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) {
                return new HashMap<>();
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> config = JsonUtils.fromJson(json, Map.class);
            return config != null ? config : new HashMap<>();
            
        } catch (IOException e) {
            Log.w(TAG, "反序列化通道配置失败，使用空配置: " + json, e);
            return new HashMap<>();
        }
    }
    
    /**
     * 乐观锁异常
     * 当多个线程同时修改同一记录时抛出
     */
    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
        
        public OptimisticLockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
} 