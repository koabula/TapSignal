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
import org.thoughtcrime.securesms.tap.TransportPermission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 传输Token持久化表
 * 
 * 存储传输Token信息，支持接收Token和共享Token的管理
 */
public class TransportTokenTable extends DatabaseTable {

    private static final String TAG = Log.tag(TransportTokenTable.class);

    public static final String TABLE_NAME = "transport_tokens";

    private static final String ID                = "_id";
    private static final String TOKEN_ID          = "token_id";
    private static final String RECIPIENT_ID      = "recipient_id";
    private static final String PROVIDER_TYPE     = "provider_type";
    private static final String TOKEN_TYPE        = "token_type"; // 0=RECEIVED, 1=SHARED
    private static final String PERMISSIONS_JSON  = "permissions_json";
    private static final String EXPIRATION_TIME   = "expiration_time";
    private static final String TOKEN_DATA_JSON   = "token_data_json";
    private static final String IS_REVOKED        = "is_revoked";
    private static final String CREATED_AT        = "created_at";
    private static final String LAST_USED_AT      = "last_used_at";
    private static final String USAGE_COUNT       = "usage_count";
    private static final String VERSION           = "version";

    public static final String CREATE_TABLE = 
        "CREATE TABLE " + TABLE_NAME + "(" +
            ID                + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            TOKEN_ID          + " TEXT NOT NULL UNIQUE, " +
            RECIPIENT_ID      + " TEXT NOT NULL, " +
            PROVIDER_TYPE     + " TEXT NOT NULL, " +
            TOKEN_TYPE        + " INTEGER NOT NULL, " +
            PERMISSIONS_JSON  + " TEXT NOT NULL, " +
            EXPIRATION_TIME   + " INTEGER NOT NULL, " +
            TOKEN_DATA_JSON   + " TEXT NOT NULL, " +
            IS_REVOKED        + " INTEGER NOT NULL DEFAULT 0, " +
            CREATED_AT        + " INTEGER NOT NULL, " +
            LAST_USED_AT      + " INTEGER DEFAULT NULL, " +
            USAGE_COUNT       + " INTEGER NOT NULL DEFAULT 0, " +
            VERSION           + " INTEGER NOT NULL DEFAULT 1" +
        ")";

    public static final String[] CREATE_INDEXES = {
        "CREATE INDEX IF NOT EXISTS transport_tokens_recipient_provider_idx ON " + TABLE_NAME + " (" + RECIPIENT_ID + ", " + PROVIDER_TYPE + ")",
        "CREATE INDEX IF NOT EXISTS transport_tokens_expiration_idx ON " + TABLE_NAME + " (" + EXPIRATION_TIME + ")",
        "CREATE INDEX IF NOT EXISTS transport_tokens_type_idx ON " + TABLE_NAME + " (" + TOKEN_TYPE + ")",
        "CREATE INDEX IF NOT EXISTS transport_tokens_revoked_idx ON " + TABLE_NAME + " (" + IS_REVOKED + ")",
        "CREATE INDEX IF NOT EXISTS transport_tokens_provider_idx ON " + TABLE_NAME + " (" + PROVIDER_TYPE + ")"
    };

    /**
     * Token类型枚举
     */
    public enum TokenType {
        RECEIVED(0),  // 接收到的Token
        SHARED(1);    // 共享给他人的Token

        private final int value;

        TokenType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static TokenType fromValue(int value) {
            for (TokenType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return RECEIVED;
        }
    }

    /**
     * Token记录数据类
     */
    public static class TokenRecord {
        public final String tokenId;
        public final String recipientId;
        public final String providerType;
        public final TokenType tokenType;
        public final Set<TransportPermission> permissions;
        public final long expirationTime;
        public final String tokenDataJson;
        public final boolean isRevoked;
        public final long createdAt;
        public final long lastUsedAt;
        public final int usageCount;
        public final long version;

        public TokenRecord(String tokenId, String recipientId, String providerType,
                         TokenType tokenType, Set<TransportPermission> permissions,
                         long expirationTime, String tokenDataJson, boolean isRevoked,
                         long createdAt, long lastUsedAt, int usageCount, long version) {
            this.tokenId = tokenId;
            this.recipientId = recipientId;
            this.providerType = providerType;
            this.tokenType = tokenType;
            this.permissions = permissions != null ? permissions : new HashSet<>();
            this.expirationTime = expirationTime;
            this.tokenDataJson = tokenDataJson;
            this.isRevoked = isRevoked;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.usageCount = usageCount;
            this.version = version;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        public boolean isValid() {
            return !isRevoked && !isExpired();
        }

        public boolean hasPermission(@NonNull TransportPermission permission) {
            return permissions.contains(permission);
        }
    }

    public TransportTokenTable(@NonNull Context context, @NonNull SignalDatabase databaseHelper) {
        super(context, databaseHelper);
    }

    /**
     * 添加接收到的Token
     */
    @WorkerThread
    public void addReceivedToken(@NonNull String recipientId, @NonNull String tokenId,
                               @NonNull String providerType, @NonNull Set<TransportPermission> permissions,
                               long expirationTime, @NonNull String tokenDataJson) {
        insertToken(tokenId, recipientId, providerType, TokenType.RECEIVED,
                   permissions, expirationTime, tokenDataJson);
        Log.d(TAG, "添加接收Token: " + tokenId + ", recipient: " + recipientId);
    }

    /**
     * 添加共享的Token
     */
    @WorkerThread
    public void addSharedToken(@NonNull String recipientId, @NonNull String tokenId,
                             @NonNull String providerType, @NonNull Set<TransportPermission> permissions,
                             long expirationTime, @NonNull String tokenDataJson) {
        insertToken(tokenId, recipientId, providerType, TokenType.SHARED,
                   permissions, expirationTime, tokenDataJson);
        Log.d(TAG, "添加共享Token: " + tokenId + ", recipient: " + recipientId);
    }

    /**
     * 插入Token记录
     */
    @WorkerThread
    private void insertToken(@NonNull String tokenId, @NonNull String recipientId,
                           @NonNull String providerType, @NonNull TokenType tokenType,
                           @NonNull Set<TransportPermission> permissions,
                           long expirationTime, @NonNull String tokenDataJson) {
        try {
            ContentValues values = new ContentValues();
            values.put(TOKEN_ID, tokenId);
            values.put(RECIPIENT_ID, recipientId);
            values.put(PROVIDER_TYPE, providerType);
            values.put(TOKEN_TYPE, tokenType.getValue());
            values.put(PERMISSIONS_JSON, serializePermissions(permissions));
            values.put(EXPIRATION_TIME, expirationTime);
            values.put(TOKEN_DATA_JSON, tokenDataJson);
            values.put(IS_REVOKED, 0);
            values.put(CREATED_AT, System.currentTimeMillis());
            values.put(USAGE_COUNT, 0);
            values.put(VERSION, 1);

            // 使用标准的upsert逻辑
            int updatedRows = getWritableDatabase().update(TABLE_NAME, values, TOKEN_ID + " = ?", new String[]{tokenId});
            if (updatedRows == 0) {
                getWritableDatabase().insert(TABLE_NAME, null, values);
            }
        } catch (Exception e) {
            Log.e(TAG, "插入Token失败: " + tokenId, e);
        }
    }

    /**
     * 获取有效的接收Token
     */
    @WorkerThread
    @Nullable
    public TokenRecord getValidReceivedToken(@NonNull String recipientId, @NonNull String providerType) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            RECIPIENT_ID + " = ? AND " + PROVIDER_TYPE + " = ? AND " + TOKEN_TYPE + " = ? AND " +
            IS_REVOKED + " = 0 AND " + EXPIRATION_TIME + " > ?",
            new String[]{
                recipientId,
                providerType,
                String.valueOf(TokenType.RECEIVED.getValue()),
                String.valueOf(System.currentTimeMillis())
            },
            null,
            null,
            CREATED_AT + " DESC",
            "1"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return readTokenRecord(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取接收Token失败: " + recipientId + ", " + providerType, e);
        }
        return null;
    }

    /**
     * 获取有效的共享Token
     */
    @WorkerThread
    @Nullable
    public TokenRecord getValidSharedToken(@NonNull String recipientId, @NonNull String providerType) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            RECIPIENT_ID + " = ? AND " + PROVIDER_TYPE + " = ? AND " + TOKEN_TYPE + " = ? AND " +
            IS_REVOKED + " = 0 AND " + EXPIRATION_TIME + " > ?",
            new String[]{
                recipientId,
                providerType,
                String.valueOf(TokenType.SHARED.getValue()),
                String.valueOf(System.currentTimeMillis())
            },
            null,
            null,
            CREATED_AT + " DESC",
            "1"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return readTokenRecord(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取共享Token失败: " + recipientId + ", " + providerType, e);
        }
        return null;
    }

    /**
     * 撤销Token
     */
    @WorkerThread
    public boolean revokeToken(@NonNull String tokenId) {
        try {
            ContentValues values = new ContentValues();
            values.put(IS_REVOKED, 1);
            values.put(VERSION, getTokenVersion(tokenId) + 1);

            int updatedCount = getWritableDatabase().update(
                TABLE_NAME,
                values,
                TOKEN_ID + " = ?",
                new String[]{tokenId}
            );

            Log.d(TAG, "撤销Token: " + tokenId + ", 结果: " + updatedCount);
            return updatedCount > 0;
        } catch (Exception e) {
            Log.e(TAG, "撤销Token失败: " + tokenId, e);
            return false;
        }
    }

    /**
     * 更新Token使用记录
     */
    @WorkerThread
    public void updateTokenUsage(@NonNull String tokenId) {
        try {
            ContentValues values = new ContentValues();
            values.put(LAST_USED_AT, System.currentTimeMillis());
            values.put(USAGE_COUNT, getTokenUsageCount(tokenId) + 1);

            getWritableDatabase().update(
                TABLE_NAME,
                values,
                TOKEN_ID + " = ?",
                new String[]{tokenId}
            );
        } catch (Exception e) {
            Log.e(TAG, "更新Token使用记录失败: " + tokenId, e);
        }
    }

    /**
     * 清理过期Token
     */
    @WorkerThread
    public int cleanupExpiredTokens() {
        try {
            long currentTime = System.currentTimeMillis();
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                EXPIRATION_TIME + " < ? OR " + IS_REVOKED + " = 1",
                new String[]{String.valueOf(currentTime)}
            );
            Log.d(TAG, "清理过期Token: " + deletedCount);
            return deletedCount;
        } catch (Exception e) {
            Log.e(TAG, "清理过期Token失败", e);
            return 0;
        }
    }

    /**
     * 获取所有Token记录
     */
    @WorkerThread
    @NonNull
    public List<TokenRecord> getAllTokens() {
        List<TokenRecord> tokens = new ArrayList<>();
        
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            CREATED_AT + " DESC"
        )) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    TokenRecord token = readTokenRecord(cursor);
                    if (token != null) {
                        tokens.add(token);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取所有Token失败", e);
        }
        
        return tokens;
    }

    /**
     * 删除Token记录
     */
    @WorkerThread
    public boolean deleteToken(@NonNull String tokenId) {
        try {
            int deletedCount = getWritableDatabase().delete(
                TABLE_NAME,
                TOKEN_ID + " = ?",
                new String[]{tokenId}
            );
            Log.d(TAG, "删除Token: " + tokenId + ", 结果: " + deletedCount);
            return deletedCount > 0;
        } catch (Exception e) {
            Log.e(TAG, "删除Token失败: " + tokenId, e);
            return false;
        }
    }

    /**
     * 获取Token版本号
     */
    private long getTokenVersion(@NonNull String tokenId) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            new String[]{VERSION},
            TOKEN_ID + " = ?",
            new String[]{tokenId},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "获取Token版本失败: " + tokenId, e);
        }
        return 1;
    }

    /**
     * 获取Token使用次数
     */
    private int getTokenUsageCount(@NonNull String tokenId) {
        try (Cursor cursor = getReadableDatabase().query(
            TABLE_NAME,
            new String[]{USAGE_COUNT},
            TOKEN_ID + " = ?",
            new String[]{tokenId},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "获取Token使用次数失败: " + tokenId, e);
        }
        return 0;
    }

    /**
     * 从Cursor读取TokenRecord
     */
    @Nullable
    private TokenRecord readTokenRecord(@NonNull Cursor cursor) {
        try {
            String tokenId = cursor.getString(cursor.getColumnIndexOrThrow(TOKEN_ID));
            String recipientId = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID));
            String providerType = cursor.getString(cursor.getColumnIndexOrThrow(PROVIDER_TYPE));
            int tokenTypeValue = cursor.getInt(cursor.getColumnIndexOrThrow(TOKEN_TYPE));
            String permissionsJson = cursor.getString(cursor.getColumnIndexOrThrow(PERMISSIONS_JSON));
            long expirationTime = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRATION_TIME));
            String tokenDataJson = cursor.getString(cursor.getColumnIndexOrThrow(TOKEN_DATA_JSON));
            boolean isRevoked = cursor.getInt(cursor.getColumnIndexOrThrow(IS_REVOKED)) == 1;
            long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(CREATED_AT));
            long lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_USED_AT));
            int usageCount = cursor.getInt(cursor.getColumnIndexOrThrow(USAGE_COUNT));
            long version = cursor.getLong(cursor.getColumnIndexOrThrow(VERSION));

            TokenType tokenType = TokenType.fromValue(tokenTypeValue);
            Set<TransportPermission> permissions = deserializePermissions(permissionsJson);

            return new TokenRecord(
                tokenId, recipientId, providerType, tokenType, permissions,
                expirationTime, tokenDataJson, isRevoked, createdAt, lastUsedAt,
                usageCount, version
            );
        } catch (Exception e) {
            Log.e(TAG, "读取Token记录失败", e);
            return null;
        }
    }

    /**
     * 序列化权限集合为JSON字符串
     */
    @NonNull
    private String serializePermissions(@NonNull Set<TransportPermission> permissions) {
        if (permissions.isEmpty()) {
            return "[]";
        }
        
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TransportPermission permission : permissions) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(permission.name()).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 从JSON字符串反序列化权限集合
     */
    @NonNull
    private Set<TransportPermission> deserializePermissions(@NonNull String json) {
        Set<TransportPermission> permissions = new HashSet<>();
        
        try {
            if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) {
                return permissions;
            }
            
            // 简单的JSON数组解析
            String content = json.trim();
            if (content.startsWith("[") && content.endsWith("]")) {
                content = content.substring(1, content.length() - 1);
                if (!content.trim().isEmpty()) {
                    String[] parts = content.split(",");
                    for (String part : parts) {
                        String permissionName = part.trim();
                        if (permissionName.startsWith("\"") && permissionName.endsWith("\"")) {
                            permissionName = permissionName.substring(1, permissionName.length() - 1);
                            try {
                                TransportPermission permission = TransportPermission.valueOf(permissionName);
                                permissions.add(permission);
                            } catch (IllegalArgumentException e) {
                                Log.w(TAG, "未知权限: " + permissionName);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "反序列化权限集合失败: " + json, e);
        }
        
        return permissions;
    }
} 