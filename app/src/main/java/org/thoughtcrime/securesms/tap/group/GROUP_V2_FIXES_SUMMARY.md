# Group V2 Mode Critical Fixes Summary

## Overview

Two critical issues were identified and fixed that prevented group v2 mode from working correctly.

## Fix 1: Group ID Format Inconsistency

**File**: `GROUPID_FORMAT_FIX.md`  
**Date**: 2025-10-07

### Problem
Messages were sent via Signal Server instead of tap layer even when v2 mode was active.

### Root Cause
- Storage: Used Base64 encoding (`"ABC123xyz..."`)
- Query: Used toString() with prefix (`"__signal_group__v2__!ABC123xyz..."`)
- Result: Format mismatch → Query returns NATIVE → Wrong routing

### Solution
Modified `PushGroupSendJob.java` line 228-231:
```java
// Before:
String groupIdString = groupRecipient.requireGroupId().toString();

// After:
String groupIdString = android.util.Base64.encodeToString(
    groupRecipient.requireGroupId().getDecodedId(),
    android.util.Base64.NO_WRAP
);
```

### Impact
- ✓ Group status query works correctly
- ✓ Messages route through tap layer in FULL_V2_ACTIVE state
- ✓ Consistent format across codebase

## Fix 2: Async State Creation Race Condition

**File**: `ASYNC_STATE_CREATION_FIX.md`  
**Date**: 2025-10-07

### Problem
User acceptance fails with error: "群组不在提议状态: status=NATIVE"

### Root Cause
```
1. Receive GROUP_OFFER → Launch async task to create state
2. Return immediately → TapProcessResult.Success()
3. User clicks "Accept" → Call acceptV2Proposal()
4. Query fails → Async task not completed yet
```

### Solution
Modified `TapMessageProcessor.kt` lines 1212-1295:

**Before (Async everything)**:
```kotlin
processorScope.launch(Dispatchers.IO) {
    createState()  // async
    saveToken()    // async
    showNotification()  // async
}
return Success()  // Returns immediately!
```

**After (Sync critical operations)**:
```kotlin
return withContext(Dispatchers.IO) {
    createState()  // SYNC - must complete
    saveToken()    // SYNC - must complete
    
    processorScope.launch {
        showNotification()  // ASYNC - can be delayed
    }
    
    Success()  // Returns after state created
}
```

### Impact
- ✓ State creation completes before user interaction
- ✓ No race condition
- ✓ Fail-fast error handling
- ✓ UI remains responsive

## Combined Testing

### Test Flow

1. **Device A**: Create group, add Device B
2. **Device A**: Click "Use v2 mode"
3. **Verify Fix 1**: Check groupId format in database
   ```sql
   SELECT group_id FROM group_v2_status;
   -- Should be Base64 format without prefix
   ```

4. **Device B**: Receive notification
5. **Verify Fix 2**: Check state exists before user action
   ```
   同步处理群组提议
   群组状态创建成功: status=PROPOSING  ← Must appear
   ```

6. **Device B**: Click "Accept"
7. **Verify Fix 2**: Acceptance succeeds
   ```
   当前群组状态: status=PROPOSING, agreedMembers=1/2
   成员接受 V2 提议成功
   ```

8. **Both devices**: Send messages
9. **Verify Fix 1**: Messages use tap layer
   ```
   群组处于 v2 mode，通过 tap 层发送
   群组消息 v2 mode 发送成功
   ```

### Expected Logs (Success Path)

**Device A (Proposer)**:
```
为群组其他成员生成 tokens
群组 Token 生成完成
群组 V2 模式提议成功
v2 mode 提议已发起
```

**Device B (Receiver)**:
```
处理群组 V2 提议
同步处理群组提议
群组状态创建成功: status=PROPOSING  ← Fix 2
已保存提议者的 token
异步获取群组 RecipientId 并显示通知
```

**Device B (After Accept)**:
```
开始接受 V2 提议
当前群组状态: status=PROPOSING, agreedMembers=1/2  ← Fix 2 working
成员接受 V2 提议成功
```

**Both devices (After activation)**:
```
群组 V2 模式已激活
群组已启用 v2 mode
```

**Message sending (Fix 1 verification)**:
```
检查群组是否处于 v2 mode
群组处于 v2 mode，通过 tap 层发送  ← Fix 1 working
群组消息 v2 mode 发送成功
```

### Failure Indicators

If fixes don't work, you'll see:

**Fix 1 not working**:
```
群组处于 v2 mode，通过 tap 层发送  ← This won't appear
// Messages go through Signal Server instead
```

**Fix 2 not working**:
```
当前群组状态: status=NATIVE, agreedMembers=0/2
群组不在提议状态
标记自己为已同意失败
```

## Modified Files

1. `app/src/main/java/org/thoughtcrime/securesms/jobs/PushGroupSendJob.java`
   - Line 228-231: Group ID format fix

2. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
   - Lines 1212-1295: Sync state creation fix

## Documentation

- `GROUPID_FORMAT_FIX.md` - Format inconsistency details
- `ASYNC_STATE_CREATION_FIX.md` - Race condition details
- `FIX_VERIFICATION_CHECKLIST.md` - Testing procedures
- `GROUP_V2_FIXES_SUMMARY.md` - This file

## Status

- [x] Fix 1 Applied: Group ID format
- [x] Fix 2 Applied: Async race condition
- [x] No lint errors
- [x] Documentation complete
- [ ] Testing verification needed

## Next Steps

1. Build application
2. Test with 2 devices
3. Verify both fixes work together
4. Monitor logs for success indicators
5. Confirm messages use tap layer

