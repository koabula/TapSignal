# COS Marker字典序问题修复总结

## 问题描述

### 根本原因
COS的 `marker` 参数基于**字典序（lexicographical order）**过滤文件：
- Marker记录最后一个已读取文件的key
- 下次查询时，COS只返回字典序**大于**marker的文件
- 旧文件名格式 `UUID_timestamp.dat` 导致字典序混乱

### 问题场景
```
已有文件:
  - a1234567-..._1760933420000.dat  (旧消息)
  - z9876543-..._1760933430000.dat  (旧消息) ← marker指向这里

新消息到来:
  - 3322112e-..._1760933440000.dat  (新消息)

字典序比较:
  '3' < 'z' (ASCII: 51 < 122)
  
结果: 文件3的字典序 < marker
❌ COS不会返回文件3，导致消息无法被检测到！
```

### 实际症状
- 发送方A能看到自己上传的文件
- 接收方B轮询时一直显示"未找到文件"
- 新消息字典序可能小于marker，被COS过滤掉

---

## 解决方案

### 核心修改：调整文件命名格式

**旧格式**: `UUID_timestamp.dat`  
**新格式**: `timestamp_UUID.dat` ✅

#### 优势
- ✅ 时间戳在前，保证新文件字典序**始终大于**旧文件
- ✅ Marker机制完美工作
- ✅ 文件按时间自然排序
- ✅ 便于调试和查看

---

## 修改内容

### 1. 修改文件上传（发送端）

**文件**: `CosTransportProvider.kt` 行112-113

```kotlin
// 旧代码
val remotePath = "${fullPath}${message.messageId}_${System.currentTimeMillis()}.dat"

// 新代码
// 文件名格式: timestamp_messageId.dat (时间戳在前，确保COS marker字典序正确)
val remotePath = "${fullPath}${System.currentTimeMillis()}_${message.messageId}.dat"
```

### 2. 更新文件名验证

**文件**: `CosTransportProvider.kt` 行1648-1650

```kotlin
// 支持新旧两种格式
return name.endsWith(".dat") && 
       (name.matches(Regex("^\\d+_[a-zA-Z0-9_-]+\\.dat$")) || // 新格式
        name.matches(Regex("^[a-zA-Z0-9_-]+_\\d+\\.dat$"))) && // 旧格式兼容
       fileInfo.size > 0 && fileInfo.size < 50 * 1024 * 1024
```

### 3. 更新文件名解析逻辑（接收端）

**文件**: `CosTransportProvider.kt` 行1657-1710

```kotlin
override fun parseMessageFileName(fileName: String): MessageFileInfo? {
    val baseName = fileName.substringBeforeLast('.')
    val parts = baseName.split('_')
    
    // 判断是新格式还是旧格式
    val isNewFormat = parts.firstOrNull()?.all { it.isDigit() } == true
    
    when {
        // 新格式: timestamp_messageId.dat (2段，时间戳在前)
        parts.size == 2 && isNewFormat -> {
            MessageFileInfo(
                messageId = parts[1],
                timestamp = parts[0].toLongOrNull() ?: System.currentTimeMillis(),
                ...
            )
        }
        // 旧格式: messageId_timestamp.dat (2段，时间戳在后) - 向后兼容
        parts.size == 2 && !isNewFormat -> {
            MessageFileInfo(
                messageId = parts[0],
                timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis(),
                ...
            )
        }
        ...
    }
}
```

### 4. 修改FileInfo工具类

**文件**: `FileInfo.kt` 行244-245

```kotlin
// 旧代码
val fileName = "${messageId}_${timestamp}.dat"

// 新代码
// 文件名格式: timestamp_messageId.dat (时间戳在前，确保COS marker字典序正确)
val fileName = "${timestamp}_${messageId}.dat"
```

### 5. 修改附件文件名

**文件**: `TapSignalServiceAdapter.kt` 行473, 663

```kotlin
// 旧代码
val attachmentFileName = "${messageId}_${timestamp}.dat"

// 新代码
// 文件名格式: timestamp_messageId.dat (时间戳在前，确保COS marker字典序正确)
val attachmentFileName = "${timestamp}_${messageId}.dat"
```

---

## 向后兼容性

### 支持的文件名格式（按优先级）

1. ✅ **新格式**: `timestamp_messageId.dat` (2段，时间戳在前)
   - 示例: `1760937074627_3322112e-de8a-49f5-9a91-f39baedeb3e9.dat`

2. ✅ **旧格式兼容**: `messageId_timestamp.dat` (2段，时间戳在后)
   - 示例: `3322112e-de8a-49f5-9a91-f39baedeb3e9_1760937074627.dat`

3. ✅ **历史格式**: `senderId_messageId_timestamp.dat` (3段)

4. ✅ **历史格式**: `senderId_recipientId_messageId_timestamp.dat` (4段)

### 识别逻辑
```kotlin
val isNewFormat = parts.firstOrNull()?.all { it.isDigit() } == true
```
- 如果第一段全是数字 → 新格式（时间戳在前）
- 否则 → 旧格式（UUID在前）

---

## 测试验证

### 验证步骤

1. ✅ **编译检查**: 无语法错误
2. ⏳ **功能测试**: 
   - 发送新消息，检查文件名格式
   - 验证接收方能正确检测到新消息
   - 验证marker正确工作
3. ⏳ **兼容性测试**:
   - 验证旧格式文件仍能正确处理
   - 验证新旧混合场景

### 预期效果

**发送端（设备A）**:
```
上传消息:
  /v2-channels/053abb43f297ee1d/outbox/messages/1760937074627_3322112e-...dat ✅
```

**接收端（设备B）**:
```
轮询路径: /v2-channels/053abb43f297ee1d/outbox/
Marker: 1760937070000_... (最后一个已处理文件)

新文件到来:
  1760937074627_3322112e-...dat
  
字典序比较:
  1760937074627 > 1760937070000 ✅
  
结果: COS返回新文件，成功检测！
```

---

## 文件名字典序对比

### 旧格式问题
```
文件1: a1234567-..._1760933420000.dat
文件2: z9876543-..._1760933430000.dat  ← marker
文件3: 3322112e-..._1760933440000.dat  ❌ 字典序: 3 < z

marker过滤后: 文件3被忽略！
```

### 新格式解决
```
文件1: 1760933420000_a1234567-...dat
文件2: 1760933430000_z9876543-...dat  ← marker
文件3: 1760933440000_3322112e-...dat  ✅ 字典序: 1760933440000 > 1760933430000

marker过滤后: 文件3正确返回！
```

---

## 关键改进点

### 1. 时间戳前置
- **旧**: UUID在前，字典序不可预测
- **新**: 时间戳在前，字典序 = 时间顺序

### 2. Marker工作原理
```
COS查询: 返回 key > marker 的所有文件
新格式: timestamp递增 → key递增 → marker正确工作 ✅
```

### 3. 向后兼容
- 自动识别新旧格式
- 平滑过渡，无需数据迁移
- 新旧消息可共存

---

## 后续监控

### 需要观察的指标

1. **消息检测率**
   - 发送消息后，接收方检测延迟
   - 应该从"永远检测不到"改善为"500ms-1秒内检测"

2. **Marker工作状态**
   - 观察日志中的marker值
   - 确认marker正确递增

3. **兼容性**
   - 确认旧消息仍能正常接收
   - 新旧格式混合场景测试

### 日志关键词
```
"文件名格式: timestamp_messageId.dat"
"新格式: timestamp_messageId.dat"
"旧格式兼容: messageId_timestamp.dat"
"保存新marker"
"找到新文件数量"
```

---

## 修改文件清单

| 文件 | 修改内容 | 行数 |
|------|---------|------|
| `CosTransportProvider.kt` | 文件上传格式 | 112-113 |
| `CosTransportProvider.kt` | 文件名验证正则 | 1648-1650 |
| `CosTransportProvider.kt` | 文件名解析逻辑 | 1657-1710 |
| `FileInfo.kt` | 工具方法 | 244-245 |
| `TapSignalServiceAdapter.kt` | 附件文件名（2处）| 473, 663 |

---

## 总结

### 问题
❌ 文件名 `UUID_timestamp.dat` 导致COS marker字典序混乱，新消息无法被检测

### 解决
✅ 改为 `timestamp_UUID.dat`，确保时间戳在前，字典序 = 时间顺序

### 结果
- 🎯 Marker机制正确工作
- 🎯 新消息能被实时检测
- 🎯 向后兼容旧格式
- 🎯 无需数据迁移

---

**修复完成时间**: 2025-10-20  
**验证状态**: ✅ 代码语法检查通过，待运行时测试  
**影响范围**: 消息发送、接收、marker过滤逻辑

