# Signal Android 加密机制分析报告

## 概述

Signal Android 应用实现了端到端加密通信，采用了多层加密架构，包括 X3DH 密钥协商协议、Double Ratchet 算法、以及群组通信的 Sender Key 机制。本报告详细分析了 Signal 项目中这些加密机制的代码实现。

## 1. 整体架构

### 1.1 核心加密组件

Signal 的加密实现主要分布在以下模块：

- **libsignal-service**: 提供核心加密服务和协议实现
- **app/src/main/java/org/thoughtcrime/securesms/crypto**: 应用层加密功能
- **libsignal 原生库**: 底层密码学原语实现

### 1.2 主要加密类

| 类名 | 位置 | 功能 |
|------|------|------|
| `SignalServiceCipher` | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/` | 主要加密/解密入口 |
| `SignalSessionCipher` | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/` | 会话加密封装 |
| `SignalSessionBuilder` | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/` | 会话建立封装 |
| `SignalGroupCipher` | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/` | 群组加密封装 |

## 2. X3DH 密钥协商协议

### 2.1 预密钥生成

X3DH 协议的预密钥生成主要在 `PreKeyUtil.java` 中实现：

**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/PreKeyUtil.java`

#### 2.1.1 一次性预密钥生成

```java
// 生成 EC 一次性预密钥
public synchronized static @NonNull List<PreKeyRecord> generateAndStoreOneTimeEcPreKeys(
    @NonNull SignalServiceAccountDataStore protocolStore,
    @NonNull PreKeyMetadataStore metadataStore) {
    int startingId = metadataStore.getNextEcOneTimePreKeyId();
    final List<PreKeyRecord> records = generateOneTimeEcPreKeys(startingId);

    protocolStore.markAllOneTimeEcPreKeysStaleIfNecessary(System.currentTimeMillis());
    storeOneTimeEcPreKeys(protocolStore, metadataStore, records);

    return records;
}
```

#### 2.1.2 Kyber 后量子密钥生成

```java
// 生成 Kyber 一次性预密钥（后量子加密）
public synchronized static @NonNull List<KyberPreKeyRecord> generateAndStoreOneTimeKyberPreKeys(
    @NonNull SignalServiceAccountDataStore protocolStore,
    @NonNull PreKeyMetadataStore metadataStore) {
    int startingId = metadataStore.getNextKyberPreKeyId();
    List<KyberPreKeyRecord> records = generateOneTimeKyberPreKeyRecords(startingId,
        protocolStore.getIdentityKeyPair().getPrivateKey());

    protocolStore.markAllOneTimeKyberPreKeysStaleIfNecessary(System.currentTimeMillis());
    storeOneTimeKyberPreKeys(protocolStore, metadataStore, records);

    return records;
}
```

#### 2.1.3 签名预密钥生成

```java
// 生成签名预密钥
public synchronized static @NonNull SignedPreKeyRecord generateAndStoreSignedPreKey(
    @NonNull SignalProtocolStore protocolStore,
    @NonNull PreKeyMetadataStore metadataStore,
    @NonNull ECPrivateKey privateKey) {
    int signedPreKeyId = metadataStore.getNextSignedPreKeyId();
    SignedPreKeyRecord record = generateSignedPreKey(signedPreKeyId, privateKey);

    storeSignedPreKey(protocolStore, metadataStore, record);

    return record;
}

private synchronized static @NonNull SignedPreKeyRecord generateSignedPreKey(
    int signedPreKeyId, @NonNull ECPrivateKey privateKey) {
    ECKeyPair keyPair = ECKeyPair.generate();
    byte[] signature = privateKey.calculateSignature(keyPair.getPublicKey().serialize());

    return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
}
```

### 2.2 会话建立

X3DH 协议的会话建立通过 `SignalSessionBuilder` 实现：

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalSessionBuilder.java`

```java
public class SignalSessionBuilder {
    private final SignalSessionLock lock;
    private final SessionBuilder builder;

    public void process(PreKeyBundle preKey, UsePqRatchet usePqRatchet)
        throws InvalidKeyException, UntrustedIdentityException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            builder.process(preKey, usePqRatchet);
        }
    }
}
```

### 2.3 预密钥获取

在 `SignalServiceMessageSender.java` 中实现预密钥获取和会话初始化：

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

```java
private List<PreKeyBundle> getPreKeys(SignalServiceAddress recipient,
    @Nullable SealedSenderAccess sealedSenderAccess, int deviceId, boolean story)
    throws IOException {
    try {
        return NetworkResultUtil.toPreKeysLegacy(
            keysApi.getPreKeys(recipient, sealedSenderAccess, deviceId));
    } catch (NonSuccessfulResponseCodeException e) {
        if (e.code == 401 && story) {
            Log.d(TAG, "Got 401 when fetching prekey for story. Trying without UD.");
            return NetworkResultUtil.toPreKeysLegacy(
                keysApi.getPreKeys(recipient, null, deviceId));
        } else {
            throw e;
        }
    }
}
```

## 3. Double Ratchet 算法

### 3.1 会话加密

Double Ratchet 的核心实现通过 `SignalSessionCipher` 封装：

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalSessionCipher.java`

```java
public class SignalSessionCipher {
    private final SignalSessionLock lock;
    private final SessionCipher cipher;

    // 加密消息
    public CiphertextMessage encrypt(byte[] paddedMessage)
        throws UntrustedIdentityException, NoSessionException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return cipher.encrypt(paddedMessage);
        }
    }

    // 解密 PreKey 消息
    public byte[] decrypt(PreKeySignalMessage ciphertext, UsePqRatchet usePqRatchet)
        throws DuplicateMessageException, LegacyMessageException,
               InvalidMessageException, InvalidKeyIdException,
               InvalidKeyException, UntrustedIdentityException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return cipher.decrypt(ciphertext, usePqRatchet);
        }
    }

    // 解密普通消息
    public byte[] decrypt(SignalMessage ciphertext)
        throws InvalidMessageException, InvalidVersionException,
               DuplicateMessageException, LegacyMessageException,
               NoSessionException, UntrustedIdentityException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return cipher.decrypt(ciphertext);
        }
    }
}
```

### 3.2 会话状态管理

会话状态存储在 `TextSecureSessionStore` 中：

**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/TextSecureSessionStore.java`

```java
public class TextSecureSessionStore implements SignalServiceSessionStore {

    @Override
    public SessionRecord loadSession(@NonNull SignalProtocolAddress address) {
        try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
            SessionRecord sessionRecord = SignalDatabase.sessions().load(accountId, address);

            if (sessionRecord == null) {
                Log.w(TAG, "No existing session information found for " + address);
                return new SessionRecord();
            }

            return sessionRecord;
        }
    }

    @Override
    public void storeSession(@NonNull SignalProtocolAddress address,
                           @NonNull SessionRecord record) {
        try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
            SignalDatabase.sessions().store(accountId, address, record);
        }
    }

    @Override
    public void archiveSession(SignalProtocolAddress address) {
        try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
            SessionRecord session = SignalDatabase.sessions().load(accountId, address);
            if (session != null) {
                session.archiveCurrentState();
                SignalDatabase.sessions().store(accountId, address, session);
            }
        }
    }
}
```

## 4. 群组加密 (Sender Key)

### 4.1 群组密钥分发

群组加密使用 Sender Key 机制，通过 `SignalGroupCipher` 实现：

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalGroupCipher.java`

```java
public class SignalGroupCipher {
    private final SignalSessionLock lock;
    private final GroupCipher cipher;

    public CiphertextMessage encrypt(UUID distributionId, byte[] paddedPlaintext)
        throws NoSessionException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return cipher.encrypt(distributionId, paddedPlaintext);
        }
    }

    public byte[] decrypt(byte[] senderKeyMessageBytes)
        throws LegacyMessageException, InvalidMessageException,
               DuplicateMessageException, NoSessionException {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return cipher.decrypt(senderKeyMessageBytes);
        }
    }
}
```

### 4.2 Sender Key 存储

Sender Key 的存储通过 `SignalSenderKeyStore` 管理：

**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalSenderKeyStore.java`

```java
public final class SignalSenderKeyStore implements SignalServiceSenderKeyStore {

    @Override
    public void storeSenderKey(@NonNull SignalProtocolAddress sender,
                              @NonNull UUID distributionId,
                              @NonNull SenderKeyRecord record) {
        try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
            SignalDatabase.senderKeys().store(sender, DistributionId.from(distributionId), record);
        }
    }

    @Override
    public @Nullable SenderKeyRecord loadSenderKey(@NonNull SignalProtocolAddress sender,
                                                  @NonNull UUID distributionId) {
        try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
            return SignalDatabase.senderKeys().load(sender, DistributionId.from(distributionId));
        }
    }
}
```

### 4.3 群组会话建立

群组会话通过 `SignalGroupSessionBuilder` 建立：

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalGroupSessionBuilder.java`

```java
public class SignalGroupSessionBuilder {
    private final SignalSessionLock lock;
    private final GroupSessionBuilder builder;

    public void process(SignalProtocolAddress sender,
                       SenderKeyDistributionMessage senderKeyDistributionMessage) {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            builder.process(sender, senderKeyDistributionMessage);
        }
    }

    public SenderKeyDistributionMessage create(SignalProtocolAddress sender,
                                             UUID distributionId) {
        try (SignalSessionLock.Lock unused = lock.acquire()) {
            return builder.create(sender, distributionId);
        }
    }
}
```

## 5. 密钥存储机制

### 5.1 身份密钥存储

身份密钥通过 `SignalBaseIdentityKeyStore` 管理：

**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalBaseIdentityKeyStore.java`

主要功能包括：
- 身份密钥验证和存储
- 信任状态管理
- 密钥变更检测

### 5.2 预密钥存储

预密钥存储分为多个组件：

#### EC 预密钥存储
**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/TextSecurePreKeyStore.java`

#### Kyber 预密钥存储
**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalKyberPreKeyStore.kt`

### 5.3 数据库存储

密钥数据存储在 SQLite 数据库中：

- **会话表**: `app/src/main/java/org/thoughtcrime/securesms/database/SessionTable.kt`
- **身份密钥表**: `app/src/main/java/org/thoughtcrime/securesms/database/IdentityTable.kt`
- **一次性预密钥表**: `app/src/main/java/org/thoughtcrime/securesms/database/OneTimePreKeyTable.kt`
- **签名预密钥表**: `app/src/main/java/org/thoughtcrime/securesms/database/SignedPreKeyTable.kt`
- **Sender Key 表**: `app/src/main/java/org/thoughtcrime/securesms/database/SenderKeyTable.kt`

## 6. 线程安全机制

### 6.1 会话锁

所有加密操作都通过 `ReentrantSessionLock` 保证线程安全：

**位置**: `app/src/main/java/org/thoughtcrime/securesms/crypto/ReentrantSessionLock.java`

```java
public enum ReentrantSessionLock implements SignalSessionLock {
    INSTANCE;

    private static final ReentrantLock LOCK = new ReentrantLock();

    @Override
    public Lock acquire() {
        LOCK.lock();
        return LOCK::unlock;
    }
}
```

## 7. 密封发送者 (Sealed Sender)

### 7.1 密封发送者访问

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SealedSenderAccess.kt`

提供匿名消息发送功能，隐藏发送者身份。

### 7.2 密封会话加密

**位置**: `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalSealedSessionCipher.java`

实现密封发送者的加密和解密功能。

## 8. 后量子加密支持

Signal 已经集成了后量子加密算法 Kyber，为未来的量子计算威胁做准备：

- Kyber 密钥生成和管理
- 混合加密方案（EC + Kyber）
- 向后兼容性保证

## 9. 关键代码位置总结

### 9.1 X3DH 相关代码

| 功能 | 文件位置 | 关键方法/类 |
|------|----------|-------------|
| 预密钥生成 | `app/src/main/java/org/thoughtcrime/securesms/crypto/PreKeyUtil.java` | `generateAndStoreOneTimeEcPreKeys()`, `generateAndStoreOneTimeKyberPreKeys()` |
| 会话建立 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalSessionBuilder.java` | `process()` |
| 预密钥获取 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java` | `getPreKeys()` |

### 9.2 Double Ratchet 相关代码

| 功能 | 文件位置 | 关键方法/类 |
|------|----------|-------------|
| 会话加密 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalSessionCipher.java` | `encrypt()`, `decrypt()` |
| 会话存储 | `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/TextSecureSessionStore.java` | `loadSession()`, `storeSession()` |
| 主加密入口 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalServiceCipher.java` | `encrypt()`, `decrypt()` |

### 9.3 群组加密相关代码

| 功能 | 文件位置 | 关键方法/类 |
|------|----------|-------------|
| 群组加密 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalGroupCipher.java` | `encrypt()`, `decrypt()` |
| Sender Key 存储 | `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalSenderKeyStore.java` | `storeSenderKey()`, `loadSenderKey()` |
| 群组会话建立 | `libsignal-service/src/main/java/org/whispersystems/signalservice/api/crypto/SignalGroupSessionBuilder.java` | `create()`, `process()` |

### 9.4 密钥存储相关代码

| 功能 | 文件位置 | 关键方法/类 |
|------|----------|-------------|
| 身份密钥存储 | `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalBaseIdentityKeyStore.java` | `saveIdentity()`, `getIdentity()` |
| EC 预密钥存储 | `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/TextSecurePreKeyStore.java` | `storePreKey()`, `loadPreKey()` |
| Kyber 预密钥存储 | `app/src/main/java/org/thoughtcrime/securesms/crypto/storage/SignalKyberPreKeyStore.kt` | `storeKyberPreKey()`, `loadKyberPreKey()` |
| 数据库会话表 | `app/src/main/java/org/thoughtcrime/securesms/database/SessionTable.kt` | `store()`, `load()` |
| 数据库身份表 | `app/src/main/java/org/thoughtcrime/securesms/database/IdentityTable.kt` | `saveIdentity()`, `getIdentityRecord()` |

## 10. 加密流程分析

### 10.1 首次通信流程

1. **预密钥上传**: 用户注册时生成并上传身份密钥、签名预密钥、一次性预密钥到服务器
2. **获取预密钥**: 发送方从服务器获取接收方的预密钥包
3. **X3DH 协商**: 使用 X3DH 协议计算共享密钥
4. **初始化 Double Ratchet**: 建立初始会话状态
5. **消息加密**: 使用 Double Ratchet 加密消息

### 10.2 后续通信流程

1. **加载会话**: 从数据库加载现有会话状态
2. **消息加密/解密**: 使用 Double Ratchet 处理消息
3. **密钥轮换**: 每次通信自动更新密钥链
4. **会话存储**: 更新后的会话状态保存到数据库

### 10.3 群组通信流程

1. **Sender Key 生成**: 群组发送者生成 Sender Key
2. **密钥分发**: 通过 1:1 会话分发 Sender Key 给群组成员
3. **群组消息加密**: 使用 Sender Key 加密群组消息
4. **群组消息解密**: 接收方使用对应的 Sender Key 解密

## 总结

Signal Android 的加密实现展现了现代端到端加密通信的最佳实践：

1. **多层防护**: X3DH + Double Ratchet + Sender Key 提供完整的加密方案
2. **前向安全**: Double Ratchet 确保历史消息的安全性
3. **后量子准备**: 集成 Kyber 算法应对未来威胁
4. **线程安全**: 完善的锁机制保证并发安全
5. **可否认性**: 密封发送者提供匿名通信能力

这套加密架构为 Signal 用户提供了业界领先的通信安全保障。通过详细的代码分析，我们可以看到 Signal 在实现上的严谨性和对安全性的极致追求。