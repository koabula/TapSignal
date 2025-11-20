# Crash Analysis and Fix Plan: TapMessageProcessor Group ID Parsing

## 1. Crash Analysis

### Symptom
The application crashes with a `FATAL EXCEPTION` when receiving a Tap control message related to a group (e.g., `REQUEST_TYPE_GROUP_OFFER`).

### Stack Trace
```
java.lang.AssertionError: org.thoughtcrime.securesms.groups.BadGroupIdException: Invalid encoding
    at org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(GroupId.java:133)
    at org.thoughtcrime.securesms.tap.integration.TapMessageProcessor.getGroupRecipientIdFromGroupId(TapMessageProcessor.kt:1501)
    ...
Caused by: org.thoughtcrime.securesms.groups.BadGroupIdException: Invalid encoding
    at org.thoughtcrime.securesms.groups.GroupId.parse(GroupId.java:140)
    ...
```

### Root Cause
The method `TapMessageProcessor.getGroupRecipientIdFromGroupId` calls `GroupId.parseOrThrow(groupId)` directly.
- **Input**: The `groupId` received from the Tap message metadata is a **Base64 encoded string** (e.g., `+9Hk9QAmQIXLHA5tx5aFoLDHmR0XSoCPx+qubRG4UVs=`).
- **Expectation**: `GroupId.parseOrThrow` expects a **Signal-internal formatted string** that starts with a specific prefix (e.g., `__signal_group__v2__!`).
- **Failure**: `GroupId.parse` checks for the prefix, fails, and throws `BadGroupIdException`. `GroupId.parseOrThrow` catches this exception and re-throws it as an `AssertionError`.
- **Crash**: `AssertionError` is a subclass of `Error`, not `Exception`, so the `try { ... } catch (e: Exception)` block in `TapMessageProcessor` fails to catch it, causing the app to crash.

## 2. Fix Plan

We need to modify `TapMessageProcessor.getGroupRecipientIdFromGroupId` to handle both internal Signal Group ID formats and raw Base64 encoded Group IDs.

### Proposed Changes in `TapMessageProcessor.kt`

Modify the `getGroupRecipientIdFromGroupId` function to:

1.  **Check Format**: Use `GroupId.isEncodedGroup(groupId)` to check if the string is already in the internal format.
2.  **Handle Base64**: If it is NOT in the internal format, attempt to decode it as a Base64 string.
3.  **Create GroupId**: Use `GroupId.push(bytes)` to create the `GroupId` object from the decoded bytes. This method automatically handles V1 (16 bytes) and V2 (32 bytes) group IDs.
4.  **Robust Error Handling**: Update the try-catch block to handle potential `AssertionError` or use `runCatching` to ensure the app doesn't crash even if parsing fails.

### Draft Code

```kotlin
    private fun getGroupRecipientIdFromGroupId(groupId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            val groupIdObj = if (org.thoughtcrime.securesms.groups.GroupId.isEncodedGroup(groupId)) {
                org.thoughtcrime.securesms.groups.GroupId.parseOrThrow(groupId)
            } else {
                // Assume Base64 encoded ID if not in internal format
                val bytes = android.util.Base64.decode(groupId, android.util.Base64.NO_WRAP)
                org.thoughtcrime.securesms.groups.GroupId.push(bytes)
            }
            
            org.thoughtcrime.securesms.recipients.Recipient.externalGroupExact(groupIdObj).id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse group ID: $groupId", e)
            null
        } catch (e: AssertionError) {
            Log.w(TAG, "AssertionError parsing group ID: $groupId", e)
            null
        }
    }
```

## 3. Verification
After applying the fix:
1.  Rebuild the application.
2.  Trigger the Tap Group V2 Offer flow again.
3.  Verify that the app does not crash.
4.  Verify that the notification is displayed correctly (indicating successful parsing of the Group ID).
