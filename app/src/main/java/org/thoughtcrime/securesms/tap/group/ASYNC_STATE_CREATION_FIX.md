# Group State Async Creation Race Condition Fix

## Problem Description

When user accepts group v2 mode proposal, the system fails with error:
```
群组不在提议状态: status=NATIVE
标记自己为已同意失败
```

## Root Cause: Race Condition

### Execution Flow (Before Fix)

1. **Receiver gets GROUP_OFFER message** → `processGroupTokenOffer()` called
2. **Async task launched** to create state:
   ```kotlin
   processorScope.launch(Dispatchers.IO) {
       val stateCreated = groupManager.createOrUpdateGroupState(...)
       // ...
   }
   ```
3. **Returns immediately** without waiting:
   ```kotlin
   return TapProcessResult.Success("群组 V2 提议处理完成，通知将异步显示")
   ```
4. **User clicks "Accept"** → `handleGroupTokenAcceptance()` called
5. **Tries to mark as agreed**:
   ```kotlin
   val accepted = groupManager.acceptV2Proposal(groupId, myAci)
   ```
6. **Query fails** - Async task not completed yet, state still NATIVE

### Evidence from Logs

```
16:22:46.391  开始接受 V2 提议
16:22:46.393  当前群组状态: status=NATIVE, agreedMembers=0/2, proposer=null
16:22:46.394  群组不在提议状态
```

**Timing Issue**: User action happens before async state creation completes.

## Solution

Changed from async to sync execution for critical operations.

### Modified File

**File**: `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`

**Method**: `processGroupTokenOffer()` (lines 1182-1300)

### Key Changes

#### Before (Async):
```kotlin
processorScope.launch(Dispatchers.IO) {
    // Create state (async)
    val stateCreated = groupManager.createOrUpdateGroupState(...)
    // Save token (async)
    tokenPool.addReceivedToken(...)
    // Show notification (async)
    showGroupTokenExchangeNotification(...)
}
return TapProcessResult.Success("通知将异步显示")  // Returns immediately!
```

#### After (Sync critical operations):
```kotlin
return withContext(Dispatchers.IO) {
    // 1. Create state (SYNC - must complete)
    val stateCreated = groupManager.createOrUpdateGroupState(...)
    if (!stateCreated) {
        return@withContext TapProcessResult.Failed("创建群组状态失败")
    }
    Log.i(TAG, "群组状态创建成功: groupId=$groupId, status=PROPOSING")
    
    // 2. Save token (SYNC - must complete)
    val saved = tokenPool.addReceivedToken(...)
    
    // 3. Show notification (ASYNC - can be delayed)
    processorScope.launch {
        showGroupTokenExchangeNotification(...)
    }
    
    TapProcessResult.Success("群组状态创建成功")  // Returns after state is created
}
```

### Benefits

1. **Guarantees state creation** before user can interact
2. **Prevents race condition** - state exists when user clicks accept
3. **Maintains responsiveness** - only notification is async
4. **Fail-fast behavior** - returns error if state creation fails

## Testing

### Expected Flow

1. Device B receives GROUP_OFFER
2. **State created synchronously** → status = PROPOSING
3. Notification shown (async, doesn't block)
4. User clicks "Accept"
5. `acceptV2Proposal()` succeeds - state already exists
6. Group v2 mode activated

### Expected Logs

```
同步处理群组提议: groupId=...
群组状态创建成功: groupId=..., status=PROPOSING
已保存提议者的 token: proposer=...
异步获取群组 RecipientId 并显示通知
开始接受 V2 提议: groupId=...
当前群组状态: status=PROPOSING, agreedMembers=1/2  ✓
成员接受 V2 提议成功
```

## Related Issues

- Group ID format fix (GROUPID_FORMAT_FIX.md)
- Sender ID missing fix (SENDERID_MISSING_FIX.md)

## Impact

- Fixes: User acceptance fails with "群组不在提议状态"
- Ensures: Critical operations complete before user interaction
- Maintains: Async notification for UI responsiveness

## Date

2025-10-07

