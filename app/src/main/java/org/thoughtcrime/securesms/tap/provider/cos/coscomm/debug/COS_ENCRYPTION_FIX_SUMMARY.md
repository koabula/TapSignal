# COS消息解密问题修复总结

## 问题描述

COS消息在下载后解密失败，出现以下错误：
```
org.signal.libsignal.protocol.InvalidVersionException: ciphertext version was unrecognized <5>
```

## 问题根源

**双重Base64编码导致数据损坏**

### 错误的流程（修复前）

1. **发送端加密：**
   ```kotlin
   val ciphertext = sessionCipher.encrypt(contentBytes)
   val serialized = ciphertext.serialize() // 原始密文字节数组
   ```

2. **第一次Base64编码（错误）：**
   ```kotlin
   EncryptedMessageData(
       encryptedContent = Base64.encodeWithPadding(ciphertext.serialize()), // String
       ratchetInfo = ratchetInfo
   )
   ```

3. **字符串转字节数组（错误）：**
   ```kotlin
   encryptedContent = encryptedData.encryptedContent.toByteArray(), // Base64字符串的UTF-8字节
   ```

4. **第二次Base64编码（错误）：**
   ```kotlin
   encryptedContent = CosMessageSerializer.encodeBase64(encryptedContent), // 双重编码
   ```

5. **接收端解码：**
   ```kotlin
   val encryptedBytes = Base64.decode(cosMessage.encryptedContent) // 只解码一次
   val signalMessage = SignalMessage(encryptedBytes) // 失败！
   ```

### 问题分析

- 发送端进行了两次Base64编码
- 接收端只进行了一次Base64解码
- 解码后得到的是Base64字符串的UTF-8字节表示，而不是原始密文
- SignalMessage期望原始密文格式，但得到的是伪字节数据
- 版本号解析失败：Base64字符串的第一个字符'S'（ASCII 83）被误认为版本号

## 修复方案

### 1. 修改数据类型

**修复前：**
```kotlin
data class EncryptedMessageData(
    val encryptedContent: String, // 错误：导致不必要的字符串转换
    val ratchetInfo: RatchetInfo
)
```

**修复后：**
```kotlin
data class EncryptedMessageData(
    val encryptedContent: ByteArray, // 正确：直接使用字节数组
    val ratchetInfo: RatchetInfo
) {
    // 添加正确的equals和hashCode方法处理ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMessageData
        if (!encryptedContent.contentEquals(other.encryptedContent)) return false
        if (ratchetInfo != other.ratchetInfo) return false
        return true
    }

    override fun hashCode(): Int {
        var result = encryptedContent.contentHashCode()
        result = 31 * result + ratchetInfo.hashCode()
        return result
    }
}
```

### 2. 移除第一次Base64编码

**修复前：**
```kotlin
EncryptedMessageData(
    encryptedContent = Base64.encodeWithPadding(ciphertext.serialize()), // 错误编码
    ratchetInfo = ratchetInfo
)
```

**修复后：**
```kotlin
EncryptedMessageData(
    encryptedContent = ciphertext.serialize(), // 直接使用字节数组
    ratchetInfo = ratchetInfo
)
```

### 3. 修复createCosMessage调用

**修复前：**
```kotlin
encryptedContent = encryptedData.encryptedContent.toByteArray(), // 错误转换
```

**修复后：**
```kotlin
encryptedContent = encryptedData.encryptedContent, // 直接传递字节数组
```

## 正确的流程（修复后）

1. **发送端加密：**
   ```kotlin
   val ciphertext = sessionCipher.encrypt(contentBytes)
   val encryptedData = EncryptedMessageData(
       encryptedContent = ciphertext.serialize(), // 字节数组
       ratchetInfo = ratchetInfo
   )
   ```

2. **创建COS消息（一次编码）：**
   ```kotlin
   val cosMessage = CosMessageUtils.createCosMessage(
       encryptedContent = encryptedData.encryptedContent, // 字节数组
       // ... 其他参数
   )
   // 在createCosMessage内部进行唯一的Base64编码
   ```

3. **接收端解码：**
   ```kotlin
   val encryptedBytes = Base64.decode(cosMessage.encryptedContent) // 一次解码
   val signalMessage = SignalMessage(encryptedBytes) // 成功！
   ```

## 修复验证

修复确保了：
- 发送端：原始密文字节 → 一次Base64编码 → 存储
- 接收端：Base64解码 → 原始密文字节 → 成功解密
- 编码和解码完全对称，避免了数据损坏

## 测试建议

1. 运行`CosEncryptionFixTest.testEncryptionDecryptionFlow()`验证修复
2. 运行`CosEncryptionDebugTool.verifyFix()`进行完整验证
3. 测试实际的COS消息发送和接收流程

## 总结

这个修复解决了COS消息系统中的关键问题：
- 消除了双重Base64编码
- 确保了加密和解密的对称性
- 修复了SignalMessage版本号解析错误
- 提高了系统的可靠性和数据完整性
