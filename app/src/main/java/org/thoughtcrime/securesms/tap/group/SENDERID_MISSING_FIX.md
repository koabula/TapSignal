# Group V2 Mode - Receiver State Initialization Fix

## Problem Summary

When user B receives a GROUP_OFFER message from user A and accepts it, the acceptance fails with error:
```
群组状态不存在: tcXZJms9RG3zFPDT2Oiqrnj3MscrpAKXGHenOSf9Kys=
```

### Root Cause

**Design Flaw**: The code assumed that when a proposer creates a group state in their local database, it would automatically be available to receivers. However, each user has their own local database, so the receiver needs to create their own copy of the group state when receiving the GROUP_OFFER message.

**Original Flow (Incorrect)**:
1. A calls `proposeV2Mode()` → Creates group state in A's local database
2. A sends GROUP_OFFER via Signal Server to B
3. B receives GROUP_OFFER → Shows notification
4. B clicks "Accept" → Calls `acceptV2Proposal()`
5. ❌ `acceptV2Proposal()` fails because group state doesn't exist in B's database

### Error Location

In `GroupTokenExchangeReceiver.kt` Line 127:
```kotlin
// INCORRECT ASSUMPTION (Line 124 comment):
// "群组状态已经由提议者创建，这里不需要调用 proposeV2Mode()"
val accepted = groupManager.acceptV2Proposal(groupId, myAci)
```

The `acceptV2Proposal()` method checks if group state exists (Line 388-390 in GroupTransportManager.kt):
```kotlin
val currentState = groupV2StatusTable.getGroupState(groupId)
if (currentState == null) {
    Log.w(TAG, "群组状态不存在: $groupId")  // ← ERROR HERE
    return@withContext false
}
```

## Solution

### Key Fix: Receiver creates local group state when receiving GROUP_OFFER

The receiver must create their own copy of the group state in their local database immediately upon receiving the GROUP_OFFER message, before showing the notification.

## Changes Made

### 1. GroupTransportManager.kt

**Added**: `createOrUpdateGroupState()` method (Line 201-232)

```kotlin
/**
 * Create or update group state (for receiver initialization)
 * 
 * When receiver gets GROUP_OFFER message, they need to create a local group state record.
 * If state already exists, skip creation to avoid overwriting existing data.
 */
suspend fun createOrUpdateGroupState(groupId: String, initialState: GroupV2State): Boolean
```

**Purpose**: 
- Creates group state in receiver's local database
- Idempotent: safely handles duplicate messages
- Includes proper logging for debugging

### 2. TapMessageProcessor.kt

**Modified**: `processGroupTokenOffer()` method (Line 1201-1281)

**Added logic**:
1. Extract `totalMembers` from metadata (Line 1202-1208)
2. Create local group state with:
   - Status: PROPOSING
   - ProposerAci: from message
   - AgreedMembers: {proposerAci} (proposer already agreed)
   - TotalMembers: from metadata
   - ProviderType: from message
3. Save proposer's token to TokenPool (Line 1241-1259)
4. Then show notification (Line 1261-1280)

**Key Changes**:
```kotlin
// 1. Extract members
val totalMembersList = tokenExchangeMessage.metadata["totalMembers"] as? List<*>
val totalMembers = totalMembersList?.mapNotNull { it as? String }?.toSet() ?: emptySet()

// 2. Create group state
val initialState = GroupV2State(
    groupId = groupId,
    status = GroupV2Status.PROPOSING,
    proposerAci = proposerAci,
    agreedMembers = setOf(proposerAci),  // Proposer already agreed
    totalMembers = totalMembers,
    providerType = tokenExchangeMessage.providerType,
    ...
)
val groupManager = GroupTransportManager.getInstance(context)
groupManager.createOrUpdateGroupState(groupId, initialState)

// 3. Save proposer's token
val tokensData = tokenExchangeMessage.metadata["tokens"] as? Map<String, Any>
// ... extract and save token
```

### 3. GroupTokenExchangeHelper.kt

**Modified**: `buildGroupOfferMetadata()` method (Line 263-278)

**Added parameter**: `totalMembers: Set<String>`

**Updated return**:
```kotlin
return mapOf(
    "groupId" to groupId,
    "proposerAci" to proposerAci,
    "tokens" to tokensData,
    "totalMembers" to totalMembers.toList(),  // ← NEW
    "timestamp" to System.currentTimeMillis()
)
```

**Modified**: `sendGroupOfferMessage()` method (Line 59-76)

**Added logic**:
```kotlin
// Extract all member ACIs
val totalMembers = memberRecipientIds.mapNotNull { recipientId ->
    try {
        Recipient.resolved(recipientId).requireAci().toString()
    } catch (e: Exception) {
        Log.w(TAG, "无法获取成员 ACI: recipientId=$recipientId", e)
        null
    }
}.toSet()

// Pass to metadata builder
metadata = buildGroupOfferMetadata(groupId, proposerAci, tokens, totalMembers)
```

## Verification

### Expected Flow After Fix

1. **A sends GROUP_OFFER**:
   - Creates group state in A's database
   - Sends message with metadata including `totalMembers`

2. **B receives GROUP_OFFER**:
   - Extracts `totalMembers` from metadata
   - Creates group state in B's database with:
     - Status: PROPOSING
     - AgreedMembers: {A's ACI}
     - TotalMembers: {A's ACI, B's ACI}
   - Saves A's token
   - Shows notification

3. **B clicks "Accept"**:
   - ✅ `acceptV2Proposal()` finds existing group state
   - Adds B's ACI to agreedMembers
   - Sends GROUP_ACCEPT message
   - Checks if all members agreed → Activates if ready

### Testing

Test with the original scenario:
- A creates 2-person group with B
- A selects "Use v2 mode"
- B receives notification and clicks "Accept"
- ✅ Should succeed without "群组状态不存在" error

### Log Output Expected

After fix, B's logs should show:
```
接收者创建群组状态: groupId=..., status=PROPOSING, proposer=..., totalMembers=2, agreedMembers=1
已保存提议者的 token: proposer=..., groupId=...
群组 V2 提议通知已显示: groupId=...
成功标记为已同意: groupId=..., myAci=...
```

## Files Changed

1. `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTransportManager.kt`
   - Added `createOrUpdateGroupState()` method

2. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapMessageProcessor.kt`
   - Modified `processGroupTokenOffer()` to create local group state

3. `app/src/main/java/org/thoughtcrime/securesms/tap/group/GroupTokenExchangeHelper.kt`
   - Modified `buildGroupOfferMetadata()` to include totalMembers
   - Modified `sendGroupOfferMessage()` to extract and pass member ACIs

## Impact

- ✅ Fixes the critical bug where receivers cannot accept group V2 proposals
- ✅ Ensures state consistency across all group members
- ✅ Maintains backward compatibility (idempotent state creation)
- ✅ No lint errors introduced
- ✅ Proper error handling and logging

## Date

2025-10-07
