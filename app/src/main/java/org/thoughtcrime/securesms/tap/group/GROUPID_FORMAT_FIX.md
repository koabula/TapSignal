# Group ID Format Fix

## Problem Description

Messages in group v2 mode were still being sent through Signal Server instead of tap layer, even though v2 mode was successfully established.

## Root Cause

**Group ID format inconsistency:**

1. **Storage Phase (ConversationFragment.kt)**
   - Used: `Base64.encodeToString(groupId.getDecodedId(), Base64.NO_WRAP)`
   - Format: Pure Base64 string (e.g., `"ABC123xyz..."`)

2. **Query Phase (PushGroupSendJob.java line 228)**
   - Used: `groupRecipient.requireGroupId().toString()`
   - Format: Prefixed string (e.g., `"__signal_group__v2__!ABC123xyz..."`)

3. **Result**
   - Database stores format A (pure Base64)
   - Query uses format B (with prefix)
   - Format mismatch → Query fails → Returns NATIVE status → Messages sent via Signal Server

## Solution

Unified to use Base64 format for group ID encoding in PushGroupSendJob.java.

### Modified File

**File:** `app/src/main/java/org/thoughtcrime/securesms/jobs/PushGroupSendJob.java`

**Line 228-231:**

```java
// Before:
String groupIdString = groupRecipient.requireGroupId().toString();

// After:
String groupIdString = android.util.Base64.encodeToString(
    groupRecipient.requireGroupId().getDecodedId(),
    android.util.Base64.NO_WRAP
);
```

## Impact

- Group v2 mode status query now works correctly
- Messages in FULL_V2_ACTIVE groups are now sent through tap layer
- Consistent group ID format across the codebase

## Testing Steps

1. Clear old group v2 status data (if any)
2. Create a new group with member B
3. Select "Use v2 mode" from group menu
4. Member B accepts the proposal
5. Send a message
6. Verify the message is sent through tap layer (check logs for "群组处于 v2 mode，通过 tap 层发送")

## Related Files

- `app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt` (lines 2016-2019, 4271-4274)
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`
- `app/src/main/java/org/thoughtcrime/securesms/tap/group/utils/GroupIdConverter.kt`

## Date

2025-10-07
