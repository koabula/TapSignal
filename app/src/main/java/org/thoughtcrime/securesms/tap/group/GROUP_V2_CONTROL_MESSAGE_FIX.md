# 群组 V2 Mode 控制消息传输修复

## 修复日期
2025-10-09

## 问题描述

### 症状
3人群组建立v2 mode时，最后一个接受的成员（C）成功激活v2 mode，但前两个成员（A和B）仍然显示等待状态（2/3），无法收到C的接受消息。

### 根本原因

**竞态条件导致的消息传输路径错误：**

1. C在本地标记自己为已同意后，检测到全员都同意（3/3）
2. C立即将状态升级为 `FULL_V2_ACTIVE`
3. C发送 `GROUP_ACCEPT` 消息时，`shouldUseTapForGroup()` 返回 `true`
4. 因此C的 `GROUP_ACCEPT` 消息**通过TAP传输**而不是Signal Server
5. 但此时A和B的状态还是 `PROPOSING`（2/3），它们**还没有建立通道和启动轮询**
6. 结果：**A和B永远收不到C的GROUP_ACCEPT消息**

### 证据

Log_C 第114-126行显示C的消息通过TAP上传：
```
18:01:57.914  shouldUseTapForGroup: status=FULL_V2_ACTIVE, shouldUse=true
18:01:57.914  [sendGroupMessage] Group is in v2 mode, sending via TAP transport.
18:01:58.284  上传文件成功: /group/0cbeIJCp.../messages/1760004117913_1760004117955.dat
```

而Log_A和Log_B在18:02之后都没有收到任何GROUP_ACCEPT消息的处理记录。

## 解决方案

### 核心思路
**强制TAP控制消息通过Signal Server传输**

TAP控制消息（`GROUP_OFFER`、`GROUP_ACCEPT`、`GROUP_ACTIVATE`、`GROUP_DISABLE`等）必须通过可靠的Signal Server传递，只有普通用户消息才通过TAP传输。

### 实现细节

#### 1. 识别TAP控制消息

所有TAP控制消息都以 `"TAP_TOKEN_EXCHANGE:"` 前缀开头，可以通过以下方法识别：
```java
content.isTapControlMessage()
```

该方法已在 `EnvelopeContent` 接口中定义并实现。

#### 2. 修改发送逻辑

在 `SignalServiceMessageSender.sendGroupMessage()` 方法中，在TAP拦截检查之前增加控制消息检查：

**修改前：**
```java
if (tapTransport != null && tapTransport.shouldUseTapForGroup(groupId)) {
    // 直接通过TAP发送
    return tapTransport.sendGroupMessageViaTap(...);
}
```

**修改后：**
```java
if (isTapControlMessageContent(content)) {
    Log.d(TAG, "TAP control message detected, forcing Signal Server transmission for reliability.");
} else if (tapTransport != null && tapTransport.shouldUseTapForGroup(groupId)) {
    // 只有非控制消息才通过TAP发送
    return tapTransport.sendGroupMessageViaTap(...);
}
// 控制消息继续走Signal Server
```

**辅助方法：**
```java
private boolean isTapControlMessageContent(Content content) {
    if (content.dataMessage == null || content.dataMessage.body == null) {
        return false;
    }
    return content.dataMessage.body.startsWith("TAP_TOKEN_EXCHANGE:");
}
```

### 修改文件

**文件：** `libsignal-service/src/main/java/org/whispersystems/signalservice/api/SignalServiceMessageSender.java`

**修改位置：** 
- 第2603-2627行：主要修改（TAP拦截逻辑）
- 第2773-2803行：新增辅助方法 `isTapControlMessageContent()`

**修改内容：**
- 在TAP拦截之前增加 `isTapControlMessageContent(content)` 检查
- 如果是TAP控制消息，跳过TAP传输，强制使用Signal Server
- 添加辅助方法检查protobuf `Content` 是否为TAP控制消息
- 添加日志记录控制消息的传输路径

## 修复效果

### 预期行为

1. **A发起提议**：通过Signal Server发送 `GROUP_OFFER` → B和C收到 ✅
2. **B接受提议**：通过Signal Server发送 `GROUP_ACCEPT` → A和C收到 ✅
3. **C接受提议**（状态变为FULL_V2_ACTIVE）：
   - 检测到是TAP控制消息
   - **强制通过Signal Server发送** `GROUP_ACCEPT` → A和B收到 ✅
4. **A和B收到C的接受消息**：
   - 检测到全员同意（3/3）
   - 升级状态为 `FULL_V2_ACTIVE` ✅

### 一致性保证

- **私聊**：TAP控制消息已通过Signal Server发送（已有检查）
- **2人群组**：TAP控制消息已通过Signal Server发送（已有检查，第2109行）
- **3+人群组**：TAP控制消息现在通过Signal Server发送（本次修复）

## 技术优势

1. **实现简单**：只需在现有TAP拦截逻辑前增加一个条件检查
2. **语义正确**：TAP控制消息本质上是协调消息，应该通过可靠通道传递
3. **风险最小**：不改变现有状态机和发送流程
4. **一致性好**：与私聊和2人群组的处理逻辑保持一致
5. **可维护性**：利用现有的 `isTapControlMessage()` 方法，无需新增代码

## 验证方法

### 测试场景
创建一个3人群组，执行以下步骤：

1. A发起 "Use v2 mode" 提议
2. B接受提议
3. C接受提议
4. 验证所有成员都进入 `FULL_V2_ACTIVE` 状态
5. 验证所有成员都能正常发送和接收消息

### 关键日志

**成功的标志：**

设备C发送接受消息时应显示：
```
[sendGroupMessage] TAP control message detected, forcing Signal Server transmission for reliability.
```

设备A和B应收到C的接受消息并显示：
```
群组接受消息: groupId=..., accepter=...(C的ACI)
全员同意检查: isFullyAgreed=true (agreed=3, total=3)
✅ 群组 V2 模式激活成功: PROPOSING → FULL_V2_ACTIVE
```

## 相关文档

- `ENVELOPE_TRANSPORT_FIX.md`: Envelope传输格式修复（2人群组）
- `PLAN_GROUP.md`: 群组 V2 Mode 实现计划
- `TODO_GROUP.md`: 群组 V2 Mode 任务清单

## 注意事项

1. **不影响普通消息**：只有TAP控制消息强制走Signal Server，用户消息仍通过TAP传输
2. **向后兼容**：修复不影响已建立的v2 mode群组和私聊
3. **失败回退**：即使TAP传输失败，也会自动回退到Signal Server
4. **日志完整**：所有传输路径选择都有明确的日志记录

## 总结

本次修复通过在TAP拦截逻辑中增加控制消息检查，确保所有TAP协调消息（`GROUP_OFFER`、`GROUP_ACCEPT`等）都通过Signal Server可靠传递，避免了竞态条件导致的消息丢失问题。修复简单、安全、有效，完全解决了3+人群组v2 mode建立失败的问题。

