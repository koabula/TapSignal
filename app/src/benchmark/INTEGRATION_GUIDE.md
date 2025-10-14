# Benchmark Integration Guide

本指南说明如何将 benchmark 功能集成到现有的 Signal 代码中。

## 概述

Benchmark 模块完全位于 `app/src/benchmark/` 目录，不会污染 main 代码。只需要在关键位置添加几行调用代码即可。

## 必需的集成点

### 1. 对话界面菜单集成

在 `ConversationFragment` 的 `ConversationOptionsMenuCallback` 中添加 benchmark 菜单项：

**文件**: `app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt`

```kotlin
// 在文件顶部添加 import（仅在 benchmark variant 有效）
import org.thoughtcrime.securesms.conversation.v2.ConversationFragmentBenchmarkExtension

// 在 ConversationOptionsMenuCallback 中修改
private inner class ConversationOptionsMenuCallback : ConversationOptionsMenu.Callback {
  
  override fun onOptionsMenuCreated(menu: Menu) {
    // ... 现有代码 ...
    
    // 添加 benchmark 菜单项（仅在 benchmark build 生效）
    try {
      ConversationFragmentBenchmarkExtension.addBenchmarkMenuItems(menu)
    } catch (e: NoClassDefFoundError) {
      // 在非 benchmark build 中此类不存在，忽略
    }
  }
}

// 在 Fragment 中添加菜单项点击处理
override fun onOptionsItemSelected(item: MenuItem): Boolean {
  // 尝试处理 benchmark 菜单项
  try {
    if (ConversationFragmentBenchmarkExtension.handleBenchmarkMenuSelection(
        fragment = this,
        itemId = item.itemId,
        onSendMessage = { text, file, messageType ->
          // 调用发送消息的逻辑
          // 示例：viewModel.sendMessage(text, file)
        }
      )) {
      return true
    }
  } catch (e: NoClassDefFoundError) {
    // 在非 benchmark build 中忽略
  }
  
  return super.onOptionsItemSelected(item)
}
```

### 2. TAP 上传集成

在 TransportProvider 的上传方法中添加时间戳记录：

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/provider/cos/CosTransportProvider.kt`

```kotlin
import org.thoughtcrime.securesms.benchmark.integration.TapBenchmarkIntegration

override fun uploadFile(file: File, metadata: TransportMetadata): TransportResult {
  val messageId = metadata.messageId ?: return TransportResult.failure(...)
  
  return try {
    TapBenchmarkIntegration.recordUpload(messageId) {
      // 原有的上传逻辑
      actualUploadImplementation(file, metadata)
    }
  } catch (e: NoClassDefFoundError) {
    // 非 benchmark build，直接执行
    actualUploadImplementation(file, metadata)
  }
}
```

### 3. TAP 下载集成

在 TransportProvider 的下载方法中添加时间戳记录：

```kotlin
override fun downloadFile(key: String, metadata: TransportMetadata): File {
  val messageId = metadata.messageId ?: throw Exception(...)
  
  return try {
    TapBenchmarkIntegration.recordDownload(messageId) {
      // 原有的下载逻辑
      actualDownloadImplementation(key, metadata)
    }
  } catch (e: NoClassDefFoundError) {
    // 非 benchmark build，直接执行
    actualDownloadImplementation(key, metadata)
  }
}
```

### 4. 轮询检测集成

在 TAP 轮询器检测到新消息时记录：

**文件**: `app/src/main/java/org/thoughtcrime/securesms/tap/polling/...`

```kotlin
private fun onNewMessageDetected(messageId: String) {
  try {
    TapBenchmarkIntegration.recordPollDetection(messageId)
  } catch (e: NoClassDefFoundError) {
    // 忽略
  }
  
  // 继续处理消息...
}
```

### 5. 加密/解密集成

在 Signal 的加密层添加记录（如果可以访问 messageId）：

```kotlin
fun encryptMessage(plaintext: ByteArray, messageId: String?): ByteArray {
  return if (messageId != null) {
    try {
      TapBenchmarkIntegration.recordEncryption(messageId) {
        doActualEncryption(plaintext)
      }
    } catch (e: NoClassDefFoundError) {
      doActualEncryption(plaintext)
    }
  } else {
    doActualEncryption(plaintext)
  }
}

fun decryptMessage(ciphertext: ByteArray, messageId: String?): ByteArray {
  return if (messageId != null) {
    try {
      TapBenchmarkIntegration.recordDecryption(messageId) {
        doActualDecryption(ciphertext)
      }
    } catch (e: NoClassDefFoundError) {
      doActualDecryption(ciphertext)
    }
  } else {
    doActualDecryption(ciphertext)
  }
}
```

### 6. UI 显示集成

在消息显示到 UI 时记录：

```kotlin
private fun displayMessage(message: Message) {
  // 显示消息...
  
  // 记录 UI 显示时间
  try {
    TapBenchmarkIntegration.recordUiDisplay(message.id)
  } catch (e: NoClassDefFoundError) {
    // 忽略
  }
}
```

### 7. 发送按钮点击集成

在用户点击发送按钮时记录 T0：

```kotlin
private fun onSendButtonClicked() {
  val messageId = generateMessageId()
  val text = composeText.text.toString()
  val sizeBytes = text.toByteArray().size.toLong()
  
  try {
    TapBenchmarkIntegration.recordSendClick(
      messageId = messageId,
      messageType = MessageType.TEXT,
      sizeBytes = sizeBytes
    )
  } catch (e: NoClassDefFoundError) {
    // 忽略
  }
  
  // 继续发送流程...
}
```

## 设计说明

### 为什么使用 try-catch NoClassDefFoundError？

因为 `TapBenchmarkIntegration` 和相关类只存在于 benchmark build variant 中。在其他 build variant（debug、release 等）编译时，这些类不存在，会在运行时抛出 `NoClassDefFoundError`。

通过 try-catch 捕获这个错误，我们可以：
1. 在 benchmark build 中正常记录数据
2. 在其他 build 中无任何影响（零开销）
3. 不需要使用 BuildConfig 检查（编译时就能确定）

### 性能影响

- **非 benchmark build**: 零影响（类不存在，不会加载）
- **Benchmark build 未启用**: 几乎零影响（只有一个原子布尔变量检查）
- **Benchmark build 已启用**: 每个时间戳记录约 0.1ms

## 验证集成

1. 切换到 benchmark build variant:
   ```bash
   ./gradlew assemblePlayProdBenchmark
   ```

2. 安装到设备并测试：
   - 打开对话
   - 检查菜单是否有 "Start Benchmark Test"
   - 运行测试
   - 导出报告
   - 验证所有时间戳都被记录

3. 检查报告文件：
   ```bash
   adb shell run-as org.thoughtcrime.securesms.benchmark ls files/benchmarks/
   ```

## 故障排查

### 菜单项没有显示
- 确认是 benchmark build variant
- 检查 `BuildConfig.BUILD_VARIANT_TYPE == "Benchmark"`

### 时间戳缺失
- 检查对应的集成点是否被调用
- 查看 logcat 中的 `BenchmarkRecorder` 日志

### 报告无法导出
- 检查 FileProvider 配置
- 确认文件权限
- 查看 logcat 中的错误信息

## 可选集成

如果某些集成点难以访问 messageId，可以跳过。系统会尽可能记录可用的数据点。

## 下一步

完成集成后，参考 [README.md](README.md) 了解如何运行 benchmark 测试和分析结果。

