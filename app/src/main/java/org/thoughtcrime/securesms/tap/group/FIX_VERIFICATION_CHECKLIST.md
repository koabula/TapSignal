# Group V2 Mode Fix Verification Checklist

## Applied Fix

**Date:** 2025-10-07  
**Issue:** Group ID format inconsistency causing messages to be sent via Signal Server instead of tap layer

### Modified Files

1. **PushGroupSendJob.java** (Line 228-231)
   - Changed from: `groupRecipient.requireGroupId().toString()`
   - Changed to: `Base64.encodeToString(groupId.getDecodedId(), NO_WRAP)`

### Format Consistency Verified

All group ID usage points now use consistent Base64 format:

- ✓ ConversationFragment.kt (lines 2016-2019, 4271-4274) - Uses Base64
- ✓ ConversationOptionsMenu.kt (line 277-280) - Uses Base64
- ✓ PushGroupSendJob.java (line 228-231) - **FIXED** to use Base64
- ✓ GroupTransportManager.kt - Accepts Base64 format

## Verification Steps

### Step 1: Build Application
```bash
./gradlew assembleDebug
```

### Step 2: Clear Old Data (Optional)
If testing with existing groups, clear old group v2 status:
```sql
DELETE FROM group_v2_status WHERE status != 'NATIVE';
```

### Step 3: Test Group V2 Mode

1. **Create Test Group**
   - Device A: Create new group
   - Device A: Add Device B to group

2. **Propose V2 Mode**
   - Device A: Open group conversation
   - Device A: Click ⋮ menu → "Use v2 mode"
   - Verify: "v2 mode 提议已发起" system message appears

3. **Accept Proposal**
   - Device B: Receive notification
   - Device B: Click "同意" (Accept)
   - Verify: Both devices show "群组已启用 v2 mode"

4. **Send Test Message**
   - Either device: Send a text message
   - Check logs for: **"群组处于 v2 mode，通过 tap 层发送"**
   - Verify: Message received on other device

### Step 4: Verify Tap Layer Usage

Check application logs for these key messages:

**On Sender Side:**
```
群组处于 v2 mode，通过 tap 层发送: [messageId]
开始通过 v2 mode 发送群组消息: messageId=...
群组消息 v2 mode 发送成功: messageId=...
```

**On Receiver Side:**
```
处理Tap传输消息: messageId=group_[groupId]_[messageId]
群组消息重复检查: messageId=...
传输消息处理成功: messageId=...
```

## Expected Results

### Before Fix
- ✗ Group status query returns NATIVE even when FULL_V2_ACTIVE
- ✗ Messages sent via Signal Server
- ✗ Tap layer not utilized

### After Fix
- ✓ Group status query correctly returns FULL_V2_ACTIVE
- ✓ Messages sent via tap layer
- ✓ Logs show "群组处于 v2 mode，通过 tap 层发送"
- ✓ Messages received through tap polling

## Troubleshooting

### Issue: Still sending via Signal Server

**Possible causes:**
1. Group not in FULL_V2_ACTIVE status
   - Check: `SELECT * FROM group_v2_status WHERE group_id = '[groupId]'`
   - Expected: `status = 'FULL_V2_ACTIVE'`

2. Group ID format still inconsistent
   - Verify Base64 encoding in database matches query
   - Check logs for groupId values

3. Channels not established
   - Verify: Channels exist for all members
   - Check: `SELECT * FROM transport_channels WHERE config_json LIKE '%groupId%'`

### Issue: Messages not received

**Possible causes:**
1. Polling not started
   - Check logs for "群组成员轮询已启动"
   
2. Tokens not saved
   - Verify: `SELECT * FROM transport_tokens`
   
3. Channel status incorrect
   - Expected: `status = 'FULL_ACTIVE'`

## Related Documentation

- GROUPID_FORMAT_FIX.md - Detailed fix explanation
- PLAN_GROUP.md - Group v2 mode architecture
- TODO_GROUP.md - Implementation task list

## Success Criteria

- [ ] Application builds without errors
- [ ] Group v2 mode can be proposed
- [ ] All members can accept proposal
- [ ] Messages sent via tap layer (verified in logs)
- [ ] Messages received correctly
- [ ] No Signal Server communication for data messages

