# Signal COS 混合通信技术规范

## 概述

本文档定义了Signal COS混合通信架构中所有数据结构、消息格式、存储规范和命名约定。

## 1. COS存储目录结构

### 1.1 v2通道目录结构

```
COS Bucket Root (每个用户自己的存储桶):
├── v2-channels/                     # COS v2模式通道目录
│   ├── signal-v2-{timestamp}-{random}/  # 为联系人A创建的专用通道目录
│   │   ├── outbox/                  # 发送给联系人A的消息
│   │   │   ├── messages/            # 普通消息文件
│   │   │   ├── attachments/         # 附件文件
│   │   │   └── metadata/            # 元数据和索引文件
│   │   ├── inbox/                   # 从联系人A接收的消息(本地使用)
│   │   └── metadata/                # 通道元数据
│   ├── signal-v2-{timestamp2}-{random2}/ # 为联系人B创建的专用通道目录
│   │   ├── outbox/                  # 发送给联系人B的消息
│   │   ├── inbox/                   # 从联系人B接收的消息
│   │   └── metadata/                # 通道元数据
│   └── ...                          # 其他联系人的通道目录
├── temp/                            # 临时文件目录
│   ├── uploads/                     # 上传中的文件
│   └── processing/                  # 处理中的文件
└── system/                          # 系统文件
    ├── config/                      # 配置文件
    └── logs/                        # 日志文件(如果需要)
```

### 1.2 v2通道权限设计

**核心原理**: 每个联系人都有专门的通道目录，通过子账户分享特定目录的**只读权限**

```
用户A的COS存储桶:
├── v2-channels/
│   ├── signal-v2-1640995200000-1234/    # 为联系人B创建的通道
│   │   ├── outbox/          ← A分享给B只读权限，B轮询此目录获取A发送的消息
│   │   │   ├── messages/
│   │   │   └── attachments/
│   │   ├── inbox/           ← A本地使用，存储从B接收的消息
│   │   └── metadata/        ← 通道状态和配置信息
│   └── signal-v2-1640995300000-5678/    # 为联系人C创建的通道
│       ├── outbox/          ← A分享给C只读权限
│       ├── inbox/           ← A本地使用
│       └── metadata/

用户B的COS存储桶:
├── v2-channels/
│   ├── signal-v2-1640995250000-9876/    # 为联系人A创建的通道
│   │   ├── outbox/          ← B分享给A只读权限，A轮询此目录获取B发送的消息
│   │   │   ├── messages/
│   │   │   └── attachments/
│   │   ├── inbox/           ← B本地使用，存储从A接收的消息
│   │   └── metadata/        ← 通道状态和配置信息

权限说明:
- A发送给B: 上传到A的/v2-channels/signal-v2-xxx-xxx/outbox/ (A有写权限)
- B接收A的消息: 轮询A的/v2-channels/signal-v2-xxx-xxx/outbox/ (B有只读权限)
- B发送给A: 上传到B的/v2-channels/signal-v2-yyy-yyy/outbox/ (B有写权限)
- A接收B的消息: 轮询B的/v2-channels/signal-v2-yyy-yyy/outbox/ (A有只读权限)
```

### 1.3 v2通道命名规范

```
通道目录命名格式:
signal-v2-{timestamp}-{random}

组成部分:
- signal-v2: 固定前缀，标识这是Signal v2通道
- timestamp: 13位毫秒时间戳，确保时间唯一性
- random: 4位随机数字，防止同一毫秒内的冲突

示例:
signal-v2-1640995200000-1234
signal-v2-1640995200001-5678
```

### 1.4 子账户权限管理

```kotlin
// 子账户凭证: 维护所有有效的子账户凭证
data class CamPoolEntry(
    val recipientId: String,           // 对方的Service ID
    val token: CosAccessToken,         // 对方分享给我的子账户凭证
    val bucketName: String,            // 对方的COS存储桶名
    val region: String,                // 对方的COS区域
    val channelDirectory: String,      // 对方的通道目录路径
    val expireTime: Long,              // 凭证过期时间(永久凭证为Long.MAX_VALUE)
    val lastPollingTime: Long,         // 最后轮询时间
    val isActive: Boolean              // 是否活跃
)
```

## 2. Signal消息格式扩展

### 2.1 COS请求消息 (CosRequest)

```protobuf
// 添加到 SignalService.proto
message CosRequest {
  string request_id = 1;              // UUID v4 请求唯一标识
  uint64 timestamp = 2;               // 请求时间戳(毫秒)
  CosDuration duration_type = 3;      // 访问时长类型
  CosAccessInfo access_info = 4;      // COS访问信息
  string message = 5;                 // 可选的请求说明
}

message CosAccessInfo {
  string provider = 1;                // "AWS" | "TENCENT" | "ALIYUN"
  string region = 2;                  // COS区域
  string bucket_name = 3;             // 存储桶名称
  string access_key_id = 4;           // 子账户访问密钥ID
  string secret_access_key = 5;       // 子账户访问密钥
  string session_token = 6;           // 会话令牌(子账户为null)
  uint64 expire_time = 7;             // 凭证过期时间(子账户为Long.MAX_VALUE，永久有效)
  string shared_directory = 8;        // 共享目录路径 (格式: "/v2-channels/signal-v2-{timestamp}-{random}/outbox/")
}

enum CosDuration {
  ONE_HOUR = 0;                       // 1小时
  ONE_DAY = 1;                        // 1天
  ONE_WEEK = 2;                       // 1周
  ONE_MONTH = 3;                      // 1个月
  PERMANENT = 4;                      // 永久(直到撤销)
}
```

### 2.2 COS响应消息 (CosResponse)

```protobuf
message CosResponse {
  string request_id = 1;              // 对应的请求ID
  bool accepted = 2;                  // 是否接受请求
  uint64 timestamp = 3;               // 响应时间戳
  CosAccessInfo access_info = 4;      // 响应方的COS访问信息(如果接受)
  string rejection_reason = 5;        // 拒绝原因(如果拒绝)
  CosDuration agreed_duration = 6;    // 同意的访问时长(可能与请求不同)
}
```

### 2.3 COS撤销消息 (CosRevocation)

```protobuf
message CosRevocation {
  string request_id = 1;              // 原请求ID
  uint64 revocation_time = 2;         // 撤销时间戳
  string revocation_reason = 3;       // 撤销原因
  RevocationType type = 4;            // 撤销类型
}

enum RevocationType {
  USER_INITIATED = 0;                 // 用户主动撤销
  TOKEN_EXPIRED = 1;                  // 令牌过期
  SECURITY_BREACH = 2;                // 安全问题
  SYSTEM_ERROR = 3;                   // 系统错误
}
```

## 3. COS消息存储格式

### 3.1 消息文件格式

```json
{
  "version": "1.0",
  "messageId": "uuid-v4-string",
  "timestamp": 1640995200000,
  "senderId": "sender-service-id",
  "recipientId": "recipient-service-id",
  "messageType": "text|attachment|typing|receipt|call",
  "ratchetInfo": {
    "messageNumber": 42,
    "chainNumber": 3,
    "ratchetPublicKey": "base64-encoded-ec-public-key",
    "previousChainLength": 15
  },
  "encryptedContent": "base64-encoded-ciphertext",
  "contentMetadata": {
    "originalSize": 1024,
    "compressionType": "gzip|none",
    "encryptionAlgorithm": "AES-256-GCM"
  },
  "attachmentInfo": {
    "fileName": "encrypted-filename",
    "mimeType": "application/octet-stream",
    "size": 2048,
    "attachmentId": "uuid-v4-string"
  }
}
```

### 3.2 消息文件命名规范

```
消息文件存储路径:
/v2-channels/signal-v2-{timestamp}-{random}/outbox/messages/{timestamp}_{message_number}_{chain_number}_{random_suffix}.json

示例:
/v2-channels/signal-v2-1640995200000-1234/outbox/messages/1640995200000_00042_003_a1b2c3d4.json

组成部分:
- v2-channels/signal-v2-{timestamp}-{random}: 专用通道目录
- timestamp: 13位毫秒时间戳
- message_number: 5位零填充的消息序号
- chain_number: 3位零填充的链序号
- random_suffix: 8位随机十六进制字符串(防止文件名冲突)
```

### 3.3 附件文件命名规范

```
附件文件存储路径:
/v2-channels/signal-v2-{timestamp}-{random}/outbox/attachments/{attachment_id}_{random_suffix}.bin

示例:
/v2-channels/signal-v2-1640995200000-1234/outbox/attachments/12345678-1234-1234-1234-123456789abc_f7e8d9c0.bin

组成部分:
- v2-channels/signal-v2-{timestamp}-{random}: 专用通道目录
- attachment_id: UUID v4格式的附件ID
- random_suffix: 8位随机十六进制字符串
- 扩展名: 统一使用.bin隐藏真实文件类型
```

## 4. Ratchet信息传递设计

### 4.1 Ratchet信息结构

```kotlin
data class RatchetInfo(
    val messageNumber: Int,             // 消息序号(用于排序和重放检测)
    val chainNumber: Int,               // 链序号(标识当前密钥链)
    val ratchetPublicKey: ByteArray,    // 当前Ratchet公钥(32字节)
    val previousChainLength: Int        // 前一个链的长度(用于跳过消息处理)
)
```

### 4.2 Ratchet信息传递方式

**方案: 与密文一起传递**

**优势:**
- ✅ 原子性: 消息和Ratchet信息要么都成功要么都失败
- ✅ 简化逻辑: 不需要处理Ratchet信息和消息的同步问题
- ✅ 减少文件数量: 避免产生大量小文件
- ✅ 便于排序: 文件名包含message_number和chain_number

**实现:**
- Ratchet信息作为消息JSON的ratchetInfo字段
- 与加密内容一起存储在同一个文件中
- 接收方下载文件后同时获得密文和Ratchet状态信息
- 解密时使用Ratchet信息更新本地会话状态

## 5. 状态和元数据文件

### 5.1 通道状态文件

```json
// 文件路径: /v2-channels/signal-v2-{timestamp}-{random}/metadata/channel_status.json
{
  "version": "1.0",
  "channelId": "uuid-v4-string",
  "recipientId": "recipient-service-id",
  "channelDirectory": "signal-v2-1640995200000-1234",
  "establishedTime": 1640995200000,
  "lastActivity": 1640995800000,
  "status": "active|expired|revoked|error",
  "subUserInfo": {
    "userName": "signal-cos-signal-v2-1640995200000-1234",
    "expireTime": 9223372036854775807,
    "durationType": "PERMANENT",
    "permissions": ["read"]
  },
  "statistics": {
    "messagesSent": 150,
    "messagesReceived": 142,
    "lastSyncTime": 1640995800000
  }
}
```

### 5.2 消息索引文件

```json
// 文件路径: /v2-channels/signal-v2-{timestamp}-{random}/outbox/metadata/message_index.json
{
  "version": "1.0",
  "lastUpdated": 1640995800000,
  "messageCount": 200,
  "latestMessageNumber": 199,
  "latestChainNumber": 5,
  "messages": [
    {
      "messageId": "uuid-v4-string",
      "fileName": "1640995200000_00042_003_a1b2c3d4.json",
      "timestamp": 1640995200000,
      "messageNumber": 42,
      "chainNumber": 3,
      "processed": true
    }
  ]
}
```

### 5.3 子账户Pool状态文件

```json
// 本地文件: cam_pool_state.json
{
  "version": "1.0",
  "lastUpdated": 1640995800000,
  "totalTokens": 5,
  "activeTokens": 4,
  "expiredTokens": 0,
  "tokens": [
    {
      "recipientId": "12345678-1234-1234-1234-123456789abc",
      "bucketName": "signal-cos-bucket-alice",
      "region": "us-east-1",
      "provider": "TENCENT",
      "channelDirectory": "signal-v2-1640995200000-1234",
      "accessKeyId": "AKID...",
      "secretAccessKey": "encrypted-secret-key",
      "sessionToken": null,
      "expireTime": 9223372036854775807,
      "lastPollingTime": 1640995800000,
      "isActive": true,
      "pollingErrors": 0,
      "subUserName": "signal-cos-signal-v2-1640995200000-1234"
    }
  ]
}
```

## 6. 错误和状态码定义

### 6.1 COS操作错误码

```kotlin
enum class CosErrorCode(val code: Int, val message: String) {
    // 网络错误 (1000-1099)
    NETWORK_TIMEOUT(1001, "网络超时"),
    NETWORK_UNREACHABLE(1002, "网络不可达"),
    
    // 认证错误 (1100-1199)  
    INVALID_CREDENTIALS(1101, "无效的访问凭证"),
    TOKEN_EXPIRED(1102, "访问令牌已过期"),
    PERMISSION_DENIED(1103, "权限不足"),
    
    // 存储错误 (1200-1299)
    BUCKET_NOT_FOUND(1201, "存储桶不存在"),
    FILE_NOT_FOUND(1202, "文件不存在"),
    STORAGE_QUOTA_EXCEEDED(1203, "存储配额超限"),
    
    // 消息错误 (1300-1399)
    INVALID_MESSAGE_FORMAT(1301, "无效的消息格式"),
    MESSAGE_TOO_LARGE(1302, "消息过大"),
    DUPLICATE_MESSAGE(1303, "重复消息"),
    
    // 加密错误 (1400-1499)
    DECRYPTION_FAILED(1401, "解密失败"),
    INVALID_RATCHET_STATE(1402, "无效的Ratchet状态"),
    KEY_DERIVATION_FAILED(1403, "密钥派生失败")
}
```

### 6.2 通道状态定义

```kotlin
enum class ChannelStatus {
    PENDING,        // 等待对方响应
    ACTIVE,         // 通道活跃
    EXPIRED,        // 令牌过期
    REVOKED,        // 已撤销
    ERROR,          // 错误状态
    SUSPENDED       // 暂停(临时错误)
}
```

## 7. 配置和常量定义

### 7.1 系统配置常量

```kotlin
object CosConstants {
    // 文件大小限制
    const val MAX_MESSAGE_SIZE = 64 * 1024 * 1024      // 64MB
    const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024   // 100MB
    
    // 轮询配置
    const val ACTIVE_POLLING_INTERVAL = 5000L           // 5秒
    const val INACTIVE_POLLING_INTERVAL = 30000L        // 30秒
    const val BACKGROUND_POLLING_INTERVAL = 60000L      // 60秒
    
    // 重试配置
    const val MAX_RETRY_COUNT = 3
    const val RETRY_BACKOFF_BASE = 1000L                // 1秒基础退避
    
    // 清理配置
    const val MESSAGE_RETENTION_DAYS = 30               // 消息保留30天
    const val TEMP_FILE_CLEANUP_HOURS = 24              // 临时文件24小时清理
    
    // 安全配置
    const val MIN_TOKEN_VALIDITY_HOURS = 1              // 最小令牌有效期1小时
    const val MAX_TOKEN_VALIDITY_DAYS = 90              // 最大令牌有效期90天
}
```

### 7.2 文件路径模板

```kotlin
object CosPathTemplates {
    // v2通道目录路径
    const val V2_CHANNELS_ROOT = "v2-channels"
    const val CHANNEL_DIRECTORY_TEMPLATE = "signal-v2-{timestamp}-{random}"
    const val OUTBOX_TEMPLATE = "{channel_directory}/outbox"
    const val INBOX_TEMPLATE = "{channel_directory}/inbox"
    const val MESSAGE_TEMPLATE = "{channel_directory}/outbox/messages"
    const val ATTACHMENT_TEMPLATE = "{channel_directory}/outbox/attachments"
    const val METADATA_TEMPLATE = "{channel_directory}/metadata"

    // 文件名模板
    const val MESSAGE_FILE_TEMPLATE = "{timestamp}_{message_number:05d}_{chain_number:03d}_{random}.json"
    const val ATTACHMENT_FILE_TEMPLATE = "{attachment_id}_{random}.bin"
    const val INDEX_FILE_NAME = "message_index.json"
    const val CHANNEL_STATUS_FILE = "channel_status.json"

    // 子账户Pool相关
    const val CAM_POOL_STATE_FILE = "cam_pool_state.json"
    const val CAM_POOL_BACKUP_FILE = "cam_pool_backup.json"

    // 子账户命名模板
    const val SUB_USER_NAME_TEMPLATE = "signal-cos-{channel_directory}"
}

object CamPoolConstants {
    // 子账户Pool管理
    const val MAX_POOL_SIZE = 100                    // 最大子账户数量
    const val CLEANUP_INTERVAL_HOURS = 6            // 清理间隔6小时
    const val TOKEN_REFRESH_THRESHOLD_HOURS = 24    // 24小时内过期的令牌需要刷新(子账户永久有效，此项保留用于兼容)

    // 轮询配置
    const val MAX_POLLING_ERRORS = 5                // 最大连续轮询错误次数
    const val POLLING_ERROR_BACKOFF_BASE = 2000L    // 轮询错误退避基础时间2秒
    const val BATCH_POLLING_SIZE = 10               // 批量轮询大小

    // 子账户配置
    const val SUB_USER_PERMISSION = "READ_ONLY"     // 子账户权限类型
    const val PERMANENT_EXPIRE_TIME = 9223372036854775807L  // 永久有效时间戳
}
```

## 8. 数据库扩展

### 8.1 COS通道表

```sql
CREATE TABLE cos_channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    channel_status INTEGER NOT NULL,
    established_time INTEGER,
    expire_time INTEGER,
    my_token_data TEXT,
    their_token_data TEXT,
    last_activity INTEGER,
    message_count INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(recipient_id)
);
```

### 8.2 COS请求表

```sql
CREATE TABLE cos_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL UNIQUE,
    recipient_id TEXT NOT NULL,
    request_type INTEGER NOT NULL, -- 0: outgoing, 1: incoming
    status INTEGER NOT NULL,        -- 0: pending, 1: accepted, 2: rejected, 3: expired
    duration_type INTEGER NOT NULL,
    request_time INTEGER NOT NULL,
    response_time INTEGER,
    token_data TEXT,
    rejection_reason TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

### 8.3 子账户Pool表

```sql
CREATE TABLE cam_pool (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient_id TEXT NOT NULL UNIQUE,
    bucket_name TEXT NOT NULL,
    region TEXT NOT NULL,
    provider TEXT NOT NULL,
    channel_directory TEXT NOT NULL,  -- v2通道目录名
    sub_user_name TEXT NOT NULL,      -- 子账户用户名
    access_key_id TEXT NOT NULL,
    secret_access_key TEXT NOT NULL,  -- 加密存储
    session_token TEXT,               -- 子账户为null
    expire_time INTEGER NOT NULL,     -- 子账户为Long.MAX_VALUE
    last_polling_time INTEGER,
    polling_errors INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_cam_pool_recipient ON cam_pool(recipient_id);
CREATE INDEX idx_cam_pool_expire ON cam_pool(expire_time);
CREATE INDEX idx_cam_pool_active ON cam_pool(is_active);
CREATE INDEX idx_cam_pool_channel ON cam_pool(channel_directory);
```

## 9. CAM Pool轮询策略

### 9.1 智能轮询算法

```kotlin
class IntelligentPollingStrategy {
    fun calculatePollingInterval(recipientId: String): Long {
        val channelInfo = cosChannelManager.getChannelInfo(recipientId)
        val lastActivity = channelInfo?.lastActivity ?: 0
        val timeSinceLastActivity = System.currentTimeMillis() - lastActivity

        return when {
            timeSinceLastActivity < TimeUnit.MINUTES.toMillis(5) -> 5000L      // 5秒
            timeSinceLastActivity < TimeUnit.HOURS.toMillis(1) -> 30000L       // 30秒
            timeSinceLastActivity < TimeUnit.HOURS.toMillis(24) -> 300000L     // 5分钟
            else -> 3600000L                                                   // 1小时
        }
    }

    fun shouldSkipPolling(recipientId: String): Boolean {
        val poolEntry = camPoolManager.getCamPoolEntry(recipientId)
        return poolEntry?.pollingErrors ?: 0 >= MAX_POLLING_ERRORS
    }
}
```

### 9.2 批量轮询优化

```kotlin
class BatchPollingManager {
    fun executeBatchPolling() {
        val activeTokens = camPoolManager.getActiveTokens()
        val batches = activeTokens.chunked(BATCH_POLLING_SIZE)

        batches.forEach { batch ->
            CompletableFuture.allOf(
                *batch.map { token ->
                    CompletableFuture.supplyAsync {
                        pollSingleTarget(token)
                    }
                }.toTypedArray()
            ).join()
        }
    }
}
```

这个技术规范文档涵盖了COS混合通信架构的所有关键技术细节，特别是正确的CAM权限模型和CAM Pool管理机制，为后续的开发实施提供了完整的指导。
