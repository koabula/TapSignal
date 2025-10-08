# 群组V2模式架构修复 - Accept消息和轮询修复指南

## 已完成的修复

### 1. Token生成架构修正 ✅
- **修改前**：为每个成员生成N-1个个人通道token（错误架构）
- **修改后**：每个成员只生成1个群组token

**相关文件**：
- `TransportProvider.kt`: 添加 `generateGroupToken()` 接口
- `CosTransportProvider.kt`: 实现 `generateGroupToken()` - 创建群组目录结构
- `GroupTransportManager.kt`: 重构 `generateGroupTokens()` → `generateMyGroupToken()`

### 2. 目录结构统一 ✅
- **群组目录**：`/group/{groupId}/outbox/messages/` 和 `/group/{groupId}/outbox/attachments/`
- **个人通道**：`/v2-channels/{hash}/outbox/` (保持不变)

### 3. Token池管理增强 ✅  
- 添加 `getGroupSharedToken()` - 获取我的群组token
- 添加 `getGroupReceivedTokens()` - 获取其他成员的群组tokens

### 4. 消息发送优化 ✅
- **修改前**：并发上传到N-1个成员的个人目录（复杂度O(N)）
- **修改后**：只上传一次到自己的群组目录（复杂度O(1)）

### 5. 消息metadata修正 ✅
- `buildGroupOfferMetadata()`: 只包含单个token（myToken）而不是token map
- `buildGroupAcceptMetadata()`: 同上


## 待完成的关键修复

### 修复1：TapMessageProcessor中的token保存逻辑

**问题**：接收OFFER/ACCEPT消息时，需要正确保存发送者的token为receivedToken

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`

**修复位置**：`processGroupTokenOffer()` 和 `processGroupTokenAccept()` 方法

**修改要点**：
```kotlin
// 从metadata中提取发送者的单个token
val senderToken = metadata["myToken"] as? Map<String, Any> // 注意是myToken而不是tokens
val token = TransportToken.fromMap(senderToken)

// 保存为receivedToken（而不是混淆的sharedToken）
tokenPool.addReceivedToken(senderAci, token, groupId)
```

### 修复2：GroupTokenExchangeReceiver中的Accept逻辑

**问题**：用户点击"同意"时，需要生成自己的群组token并发送

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeReceiver.kt`

**修复位置**：`handleGroupTokenAcceptance()` 方法

**修改要点**：
```kotlin
// 1. 生成我的群组token（只生成一个）
val myGroupToken = groupManager.generateMyGroupToken(groupId, providerType)

// 2. 保存为sharedToken
tokenPool.addSharedToken(groupId, myGroupToken, groupId)

// 3. 发送ACCEPT消息（包含我的token）
helper.sendGroupAcceptMessage(
    groupId = groupId,
    accepterAci = myAci,
    proposerAci = proposerAci,
    memberRecipientIds = memberRecipientIds,
    myToken = myGroupToken,  // 只发送我的token
    providerType = providerType
)
```

### 修复3：启动群组轮询时构建正确的metadata

**问题**：需要从receivedTokens为每个成员构建指向其群组目录的metadata

**文件**：
- `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeReceiver.kt`

**修复位置**：`startGroupPolling()` 方法

**修改要点**：
```kotlin
suspend fun startGroupPolling(groupId: String) {
    val tokenPool = TransportTokenPool.getInstance(context)
    val pollingService = TapPollingService.getInstance(context)
    
    // 1. 获取所有其他成员的receivedTokens
    val memberTokens = tokenPool.getGroupReceivedTokens(groupId, providerType)
    
    // 2. 为每个成员构建群组metadata
    val memberMetadatas = memberTokens.mapValues { (memberAci, token) ->
        buildGroupPollingMetadata(groupId, memberAci, token)
    }
    
    // 3. 批量添加轮询目标
    pollingService.addGroupPollingTargets(groupId, memberMetadatas)
}

private fun buildGroupPollingMetadata(
    groupId: String,
    memberAci: String,
    memberToken: CosTransportToken
): CosTransportMetadata {
    val groupPath = "/group/${groupId}/outbox/"  // 成员的群组目录
    
    return CosTransportMetadata(
        recipientId = memberAci,
        providerType = "cos",
        peerAddress = "${memberToken.bucketName}.cos.${memberToken.region}.myqcloud.com",
        peerToken = memberToken,
        peerRegion = memberToken.region,
        peerBucketName = memberToken.bucketName,
        peerReceivePath = groupPath,  // 轮询成员的群组目录
        // ... 其他字段
    )
}
```

## 架构对比

### 错误架构（修复前）
```
群组3人（A,B,C）
A为B生成: /v2-channels/xxx_B/outbox/ → token_A_for_B
A为C生成: /v2-channels/yyy_C/outbox/ → token_A_for_C
B为A生成: /v2-channels/zzz_A/outbox/ → token_B_for_A
B为C生成: /v2-channels/www_C/outbox/ → token_B_for_C
...

问题：6个个人目录，6个token，架构错误
```

### 正确架构（修复后）
```
群组3人（A,B,C）
A创建: /group/{groupId}/outbox/ → token_A (分享给B和C)
B创建: /group/{groupId}/outbox/ → token_B (分享给A和C)
C创建: /group/{groupId}/outbox/ → token_C (分享给A和B)

正确：3个群组目录，3个token，复杂度O(N)
A上传密文到自己的目录，B和C轮询A的目录
```

## 测试checklist

- [ ] token生成：每个成员生成一个群组token
- [ ] 目录创建：检查COS上是否创建了`/group/{groupId}/outbox/`
- [ ] token保存：检查tokenPool中sharedToken和receivedTokens是否正确
- [ ] 消息发送：验证只上传一次到自己的群组目录
- [ ] 轮询启动：验证为每个成员添加了轮询任务，路径指向成员的群组目录
- [ ] 消息接收：验证能从其他成员的群组目录拉取消息

---
*修复日期：2025-10-08*
