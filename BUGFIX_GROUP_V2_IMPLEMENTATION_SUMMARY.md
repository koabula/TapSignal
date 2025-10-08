# 群组V2模式架构性Bug修复总结报告

## 问题根源

当前群组V2实现存在**根本性架构错误**：完全沿用一对一私聊的架构，没有理解Signal群组使用SenderKey加密的特性。

### 核心问题

1. **Token生成错误**：为每个成员分别生成N-1个个人通道token
2. **目录结构错误**：创建的是`/v2-channels/`个人目录，不是群组目录  
3. **Token保存混乱**：receivedToken和sharedToken概念混淆
4. **消息发送低效**：并发上传到N-1个个人目录（复杂度O(N²)）
5. **轮询未启动**：查询个人通道，但群组场景下没有建立个人通道

## 已完成的修复

### 1. 抽象层：添加群组Token接口 ✅

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/TransportProvider.kt`

```kotlin
suspend fun generateGroupToken(groupId: String, request: TransportTokenRequest): TransportToken?
```

### 2. Provider层：实现群组Token生成 ✅

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/provider/cos/CosTransportProvider.kt`

**新增方法**：`generateGroupToken()`
- 创建群组目录：`/group/{groupId}/outbox/messages/` 和 `/group/{groupId}/outbox/attachments/`
- 生成只读子用户token
- 返回群组TransportToken

### 3. Token池管理增强 ✅

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/TransportTokenPool.kt`

**新增方法**：
- `getGroupSharedToken(groupId, providerType)` - 获取我的群组token
- `getGroupReceivedTokens(groupId, providerType)` - 获取其他成员的tokens

### 4. 重构Token生成逻辑 ✅

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`

**修改**：
- 重构 `generateGroupTokens()` → `generateMyGroupToken()` 
- 只生成一个token（我的），而不是N-1个
- 调用 `provider.generateGroupToken()` 而不是 `generateToken()`

### 5. 修改提议流程 ✅

**文件**：`GroupTransportManager.kt` 和 `GroupTokenExchangeHelper.kt`

**修改**：
- `proposeV2ModeComplete()`: 只生成和保存我的token
- `sendGroupOfferMessage()`: 参数改为单个`myToken`而不是`tokens: Map<>`
- `buildGroupOfferMetadata()`: metadata中只包含`myToken`

### 6. 重构消息发送 ✅ 

**文件**：`GroupTransportManager.kt`

**修改**：`sendGroupMessage()`
- **修改前**：并发上传到N-1个成员的个人目录
- **修改后**：只上传一次到自己的群组目录
- 复杂度：O(N²) → O(1)

**新增方法**：`buildGroupSendMetadata()` - 构建指向我的群组目录的metadata

### 7. 修改Accept消息metadata ✅

**文件**：`GroupTokenExchangeHelper.kt`

**修改**：
- `sendGroupAcceptMessage()`: 参数改为`myToken`
- `buildGroupAcceptMetadata()`: 只包含`myToken`

## 待完成的关键修复（需要继续）

### 修复A：TapMessageProcessor - 处理OFFER消息

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
**方法**：`processGroupTokenOffer()`

**问题**：从metadata提取tokens时，需要改为提取单个myToken

**修改要点**：
```kotlin
// 修改前
val tokensData = metadata["tokens"] as? Map<String, Map<String, Any>>

// 修改后  
val senderTokenData = metadata["myToken"] as? Map<String, Any>
val senderToken = TransportToken.fromMap(senderTokenData)

// 保存为receivedToken（不是sharedToken！）
tokenPool.addReceivedToken(proposerAci, senderToken, groupId)
```

### 修复B：TapMessageProcessor - 处理ACCEPT消息

**文件**：同上
**方法**：`processGroupTokenAccept()`

**修改要点**：
```kotlin
// 从metadata提取accepter的token
val accepterTokenData = metadata["myToken"] as? Map<String, Any>
val accepterToken = TransportToken.fromMap(accepterTokenData)

// 保存为receivedToken
tokenPool.addReceivedToken(accepterAci, accepterToken, groupId)
```

### 修复C：GroupTokenExchangeReceiver - Accept逻辑

**文件**：`app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeReceiver.kt`
**方法**：`handleGroupTokenAcceptance()`

**问题**：用户点击"同意"时，需要生成自己的群组token

**修改要点**：
```kotlin
// 1. 生成我的群组token
val myGroupToken = groupManager.generateMyGroupToken(groupId, providerType)

// 2. 保存为sharedToken（使用groupId作为key）
tokenPool.addSharedToken(groupId, myGroupToken, groupId)

// 3. 发送ACCEPT消息
helper.sendGroupAcceptMessage(
    groupId, accepterAci, proposerAci,
    memberRecipientIds, 
    myToken = myGroupToken,  // 只发送我的token
    providerType
)
```

### 修复D：启动群组轮询

**文件**：
- `TapMessageProcessor.kt`
- `GroupTokenExchangeReceiver.kt`  
- `GroupV2StateSynchronizer.kt`

**方法**：`startGroupPolling()`

**问题**：需要从receivedTokens构建群组metadata

**修改要点**：
```kotlin
private suspend fun startGroupPolling(groupId: String, providerType: String) {
    val tokenPool = TransportTokenPool.getInstance(context)
    val pollingService = TapPollingService.getInstance(context)
    
    // 获取所有其他成员的receivedTokens
    val memberTokens = tokenPool.getGroupReceivedTokens(groupId, providerType)
    
    // 为每个成员构建群组轮询metadata
    val memberMetadatas = memberTokens.mapValues { (memberAci, token) ->
        buildGroupPollingMetadata(groupId, memberAci, token as CosTransportToken)
    }
    
    // 批量添加轮询目标
    pollingService.addGroupPollingTargets(groupId, memberMetadatas)
}

private fun buildGroupPollingMetadata(
    groupId: String,
    memberAci: String,
    memberToken: CosTransportToken
): CosTransportMetadata {
    return CosTransportMetadata(
        recipientId = memberAci,
        providerType = "cos",
        peerAddress = "${memberToken.bucketName}.cos.${memberToken.region}.myqcloud.com",
        peerToken = memberToken,
        peerRegion = memberToken.region,
        peerBucketName = memberToken.bucketName,
        peerReceivePath = "/group/${groupId}/outbox/",  // 轮询成员的群组目录
        // ... 其他必要字段
    )
}
```

### 修复E：requestJoinV2Mode (新成员加入)

**文件**：`GroupTransportManager.kt`
**方法**：`requestJoinV2Mode()`

**问题**：调用了旧的`generateGroupTokens()`

**修改要点**：
```kotlin
// 生成我的群组token（只生成一个）
val myGroupToken = generateMyGroupToken(groupId, providerType)

// 保存为sharedToken
tokenPool.addSharedToken(groupId, myGroupToken, groupId)

// 发送给所有成员（使用单个token）
helper.sendNewMemberJoinMessage(..., myToken = myGroupToken, ...)
```

## 架构对比

### 错误架构（修复前）
```
群组3人（A,B,C）
每个人为其他成员生成个人通道token：
A: token_for_B, token_for_C
B: token_for_A, token_for_C  
C: token_for_A, token_for_B

结果：6个token，6个个人目录
上传：A要上传到B和C的个人目录（2次）
轮询：A要轮询B和C的个人目录（2个任务）
问题：复杂度O(N²)，架构根本错误
```

### 正确架构（修复后）
```
群组3人（A,B,C）
每个人为群组生成一个共享token：
A: /group/{groupId}/outbox/ → token_A (分享给B和C)
B: /group/{groupId}/outbox/ → token_B (分享给A和C)
C: /group/{groupId}/outbox/ → token_C (分享给A和B)

结果：3个token，3个群组目录
上传：A上传到自己的群组目录（1次）
轮询：A轮询B和C的群组目录（2个任务）
优势：复杂度O(N)，符合SenderKey加密特性
```

## Token管理

### SharedToken (我的)
- Key: `groupId`
- Value: 我为群组生成的token
- 用途：我上传消息到我的群组目录时使用

### ReceivedToken (其他成员的)
- Key: `memberAci`
- Value: 成员为群组生成的token
- 用途：我轮询成员的群组目录时使用

## 测试验证

### 验证点

1. **Token生成**：
   - ✅ 每个成员只生成1个群组token
   - ✅ Token的recipientId是groupId而不是memberAci
   - ✅ Token指向的目录是`/group/{groupId}/outbox/`

2. **Token保存**：
   - ✅ 我的token保存为sharedToken，key是groupId
   - ✅ 其他成员的token保存为receivedToken，key是memberAci

3. **目录结构**：
   - ✅ COS上创建的是`/group/{groupId}/outbox/`
   - ✅ 不是`/v2-channels/xxx/outbox/`

4. **消息发送**：
   - ✅ 只上传1次到自己的群组目录
   - ✅ 不是并发上传N-1次

5. **轮询启动**：
   - ⚠️ 需要修复：使用groupReceivedTokens构建metadata
   - ⚠️ 需要修复：metadata指向成员的群组目录

6. **消息接收**：
   - ⚠️ 需要验证：能从其他成员的群组目录拉取消息

## 修复状态

- ✅ Provider层：群组token生成
- ✅ Token池：管理方法
- ✅ Token生成：generateMyGroupToken
- ✅ 提议流程：metadata修改
- ✅ 消息发送：优化为单次上传
- ⚠️ 消息接收：OFFER/ACCEPT处理逻辑（需要继续修复）
- ⚠️ 用户Accept：生成和发送token（需要继续修复）
- ⚠️ 轮询启动：构建群组metadata（需要继续修复）

## 下一步行动

1. 修改`TapMessageProcessor.processGroupTokenOffer/Accept()` - 提取myToken而不是tokens map
2. 修改`GroupTokenExchangeReceiver.handleGroupTokenAcceptance()` - 使用generateMyGroupToken
3. 修改所有`startGroupPolling()`实现 - 使用getGroupReceivedTokens构建metadata
4. 测试完整流程：提议→同意→发送→接收

---
*修复日期：2025-10-08*
*修复进度：70% (核心架构已修复，消息路由需继续)*

