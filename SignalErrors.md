### Retry
There are some key design elements here:
1. ContentHint: Three types: RESENDABLE,IMPLICIT,DEFAULT

    a. ContentHint.RESENDABLE:
    Do not display the error immediately; first send a retry receipt to request retransmission. Only show the failure message if it still fails after a certain period of time(1 hour).

    Most text and attachment messages fall into this category.

    b. ContentHint.IMPLICIT:
    Send retry receipts and never display errors.

    Unimportant messages such as ReadReceipt.

    c. ContentHint.DEFAULT:
    Display failure message immediately. Meanwhile, send a retry receipt.

    Certain special messages, and messages for which ContentHint was not successfully extracted.


2. retryReceiptMaxCount:
Limit the number of retry requests sent to a single sender within a time period. If this limit is exceeded, no further retry requests will be sent.

3. MessageSendLog（MSL）:
For each message sent, the sender will store the message plaintext and some related information in the local MSL.
When a read receipt is received, or after a period of time has passed, the record will be deleted.


### What happens when receive a message that cannot be decrypted:

```
Receive encrypted message  

Decryption fails  

Count the number of errors (tracked separately for each sender)  

Check: Has it been more than 3 hours since the last error?  
    Yes -> Reset the error count to 0  
    No -> Keep the current count  

Check: Is the error count > 10 (retryReceiptMaxCount)?  
    Yes -> Do not send a retry receipt  
    No -> Send a retry receipt  

Check ContentHint to determine whether and when to display an error message  
```

### What happens when receive a retry receipt:

```
Receive Retry Receipt  

Check: Is the target device the current device?  
    No -> Ignore (intended for other devices)  
    Yes -> Continue  

Look up the original message record in MessageSendLog (MSL)

Check: Is the message type Sender Key (group message)?  

[Group Message] 
- Delete the Sender Key sharing record for that recipient  
- Check: Is there a record in MSL?  
    Yes -> Resend the message with a new Sender Key attached  
    No -> Send only the new Sender Key  
- End  

[Personal Message]
Check: Does the Ratchet Key match?  
    Yes -> Archive the Session (the Session itself is faulty)  
    No -> Keep the Session unchanged (the issue is not with the Session)  

Check: Is there a record in MSL?  
    Yes ->  resend with the original content
            During sending, a new Session will be automatically established  
            Perform X3DH key agreement  
            Encrypt with the new key and send  

    No ->   Check: Was the Session archived?  
            Yes -> Send NullMessageSendJob (trigger key reset)  
            No -> Do nothing  
```


### Error message
In Signal, the types of error messages include:


1. BAD_DECRYPT_TYPE:
   Unable to decrypt the message. May cause the other party to retry or re-establish the session.

2. ENCRYPTION_REMOTE_FAILED_BIT:
    Such messages are inserted when the chat session is refreshed. This is a system message and unrelated to the chat content.

3. UNSUPPORTED_MESSAGE_TYPE:
    The protocol versions of both parties are inconsistent.

4. INVALID_MESSAGE_TYPE:
    The message structure does not comply with the protocol specification.

5. ENCRYPTION_REMOTE_LEGACY_BIT:
    This message uses a outdated encryption format.

6. KEY_EXCHANGE_INVALID_VERSION_BIT:
   The received key exchange message uses an unsupported version.

7. KEY_EXCHANGE_IDENTITY_UPDATE_BIT:
   The recipient's safety number has changed, which occurs when they reinstall Signal. A new session will be established.



### Retry Receipt

```kt
// libsignal-protocol
public class DecryptionErrorMessage {
    private final byte[] serialized;
    
    // 主要字段
    private int deviceId;          // 目标设备ID
    private long timestamp;        // 原始消息的时间戳
    private ECPublicKey ratchetKey;  // 当前的ratchet key（可选）
    
    // 构造函数
    public DecryptionErrorMessage(byte[] serialized);
    
    public static DecryptionErrorMessage forOriginalMessage(
        byte[] originalBytes,      // 原始消息内容
        int messageType,          // 消息类型（WHISPER, PREKEY等）
        long timestamp,           // 时间戳
        int sourceDevice          // 源设备ID
    );
}
```