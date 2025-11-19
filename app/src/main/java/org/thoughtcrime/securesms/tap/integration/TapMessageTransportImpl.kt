package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.tap.TransportContentMetadata
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportCompressionType
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.group.GroupSendResult
import org.thoughtcrime.securesms.tap.group.GroupTransportManager
import org.thoughtcrime.securesms.tap.group.GroupV2Status
import org.whispersystems.signalservice.api.TapMessageTransport
import org.whispersystems.signalservice.api.messages.SendMessageResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Envelope
import java.io.IOException
import java.util.Optional

/**
 * TAP 消息传输实现
 * 将 Signal 加密后的消息通过 TAP 层传输
 */
class TapMessageTransportImpl(private val context: Context) : TapMessageTransport {
    
    companion object {
        private const val TAG = "TapMessageTransportImpl"
    }

    private fun mapEnvelopeTypeToSignalCiphertextType(type: Envelope.Type?): Int {
        return when (type) {
            Envelope.Type.CIPHERTEXT -> 2
            Envelope.Type.PREKEY_BUNDLE -> 3
            Envelope.Type.SENDERKEY_MESSAGE -> 7
            Envelope.Type.UNIDENTIFIED_SENDER -> 6
            Envelope.Type.PLAINTEXT_CONTENT -> 8
            else -> 2
        }
    }

    private val transportManager = org.thoughtcrime.securesms.tap.TransportManager.getInstance(context)
    
    override fun shouldUseTapForGroup(groupId: Optional<ByteArray>): Boolean {
        if (!groupId.isPresent) {
            Log.d(TAG, "shouldUseTapForGroup: groupId not present, returning false")
            return false
        }
        
        val groupIdBytes = groupId.get()
        val groupIdString = Base64.encodeToString(groupIdBytes, Base64.NO_WRAP)
        
        Log.d(TAG, "shouldUseTapForGroup: groupId bytes size=${groupIdBytes.size}, base64=${groupIdString.substring(0, Math.min(20, groupIdString.length))}...")
        
        val groupTransportManager = GroupTransportManager.getInstance(context)
        val status = groupTransportManager.getGroupStatusSync(groupIdString)
        
        val shouldUse = status == GroupV2Status.FULL_V2_ACTIVE
        Log.d(TAG, "shouldUseTapForGroup: groupId=$groupIdString, status=$status, shouldUse=$shouldUse")
        
        if (!shouldUse && status != GroupV2Status.NATIVE) {
            Log.w(TAG, "shouldUseTapForGroup: Group is in $status state, not FULL_V2_ACTIVE")
        }
        
        return shouldUse
    }
    
    override fun shouldUseTapForRecipient(recipient: SignalServiceAddress): Boolean {
        try {
            // 只检查是否为私聊的 v2 mode
            // 2人群组现在通过 shouldUseTapForGroup() 检查，不再在这里处理
            val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
            val hasPrivateChannel = channelManager.hasActivePrivateChannel(recipient.identifier)
            
            Log.d(TAG, "shouldUseTapForRecipient: recipient=${recipient.identifier}, hasPrivateChannel=$hasPrivateChannel")
            return hasPrivateChannel
            
        } catch (e: Exception) {
            Log.w(TAG, "Error checking TAP status for recipient", e)
            return false
        }
    }
    
    override fun sendGroupMessageViaTap(
        groupId: Optional<ByteArray>,
        recipients: List<SignalServiceAddress>,
        ciphertext: ByteArray,
        timestamp: Long,
        urgent: Boolean,
        online: Boolean,
        isSessionCipherEncrypted: Boolean
    ): List<SendMessageResult> {
        Log.i(TAG, "sendGroupMessageViaTap: groupId present=${groupId.isPresent}, recipients=${recipients.size}, ciphertextSize=${ciphertext.size}, timestamp=$timestamp, isSessionCipher=$isSessionCipherEncrypted")
        
        if (!groupId.isPresent) {
            Log.e(TAG, "sendGroupMessageViaTap: ❌ groupId is not present")
            throw IOException("Group ID is required for group TAP transport")
        }
        
        val groupIdBytes = groupId.get()
        val groupIdString = Base64.encodeToString(groupIdBytes, Base64.NO_WRAP)
        val messageId = System.currentTimeMillis().toString()
        
        Log.d(TAG, "sendGroupMessageViaTap: groupId bytes size=${groupIdBytes.size}, base64=${groupIdString.substring(0, Math.min(20, groupIdString.length))}..., messageId=$messageId")
        Log.d(TAG, "sendGroupMessageViaTap: recipients list: ${recipients.joinToString { it.identifier }}")
        
        try {
            // 使用 GroupTransportManager 通过 TAP 上传消息
            val groupTransportManager = GroupTransportManager.getInstance(context)
            
            Log.d(TAG, "sendGroupMessageViaTap: 调用 GroupTransportManager.sendGroupMessage")
            
            // 使用 runBlocking 调用 suspend 函数
            val result = runBlocking {
                groupTransportManager.sendGroupMessage(
                    groupId = groupIdString,
                    encryptedMessage = ciphertext,
                    messageId = messageId,
                    isSessionCipherEncrypted = isSessionCipherEncrypted
                )
            }
            
            when (result) {
                is GroupSendResult.Success -> {
                    Log.i(TAG, "TAP 群组消息发送成功: groupId=$groupIdString")
                    // 返回所有收件人的成功结果
                    return recipients.map { recipient ->
                        SendMessageResult.success(
                            recipient,
                            emptyList(),
                            true,
                            false,
                            timestamp,
                            Optional.empty()
                        )
                    }
                }
                is GroupSendResult.Failed -> {
                    Log.e(TAG, "TAP 群组消息发送失败: groupId=$groupIdString, reason=${result.reason}")
                    throw IOException("TAP transport failed: ${result.reason}")
                }
                is GroupSendResult.PartialSuccess -> {
                    Log.w(TAG, "TAP 群组消息部分成功: groupId=$groupIdString, success=${result.successCount}, failed=${result.failureCount}")
                    // 返回成功结果（部分成功也视为成功）
                    return recipients.map { recipient ->
                        SendMessageResult.success(
                            recipient,
                            emptyList(),
                            true,
                            false,
                            timestamp,
                            Optional.empty()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TAP 群组消息传输异常", e)
            throw IOException("TAP transport exception: ${e.message}", e)
        }
    }
    
    override fun sendMessageViaTap(
        recipient: SignalServiceAddress,
        ciphertext: ByteArray,
        timestamp: Long,
        urgent: Boolean,
        online: Boolean
    ): SendMessageResult {
        Log.i(TAG, "sendMessageViaTap(control): recipient=${recipient.identifier}, ciphertextSize=${ciphertext.size}, timestamp=$timestamp")
        
        return try {
            val senderAci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            val recipientAci = recipient.identifier

            val envelope = try {
                Envelope.ADAPTER.decode(ciphertext)
            } catch (e: Exception) {
                Log.w(TAG, "无法解析Envelope，使用原始密文包装: recipient=${recipient.identifier}", e)
                null
            }

            val signalCiphertextType = envelope?.let { mapEnvelopeTypeToSignalCiphertextType(it.type) } ?: 2

            val metadata = TransportContentMetadata(
                originalSize = ciphertext.size.toLong(),
                compressionType = TransportCompressionType.NONE,
                encryptionAlgorithm = "signal-protocol",
                sourceDeviceId = org.thoughtcrime.securesms.keyvalue.SignalStore.account.deviceId,
                isSessionCipherEncrypted = true,
                deliveryChannel = "tap-control"
            )
            val transportMessage = TransportMessage(
                messageId = TransportMessage.generateMessageId(),
                timestamp = timestamp,
                senderId = senderAci,
                recipientId = recipientAci,
                messageType = TransportMessageType.CONTROL_MESSAGE,
                signalCiphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                signalCiphertextType = signalCiphertextType,
                contentMetadata = metadata,
                attachments = emptyList()
            )
            
            val routeResult = runBlocking {
                transportManager.routeMessage(transportMessage, recipientAci)
            }
            when (routeResult) {
                is TransportResult.Success -> {
                    Log.i(TAG, "TAP控制消息发送成功: recipient=${recipient.identifier}")
                    SendMessageResult.success(
                        recipient,
                        emptyList(),
                        true,
                        false,
                        timestamp,
                        Optional.empty()
                    )
                }
                is TransportResult.PartialSuccess -> {
                    Log.w(TAG, "TAP控制消息部分成功: recipient=${recipient.identifier}")
                    SendMessageResult.success(
                        recipient,
                        emptyList(),
                        true,
                        false,
                        timestamp,
                        Optional.empty()
                    )
                }
                is TransportResult.RetryScheduled -> {
                    Log.w(TAG, "TAP控制消息已计划重试: recipient=${recipient.identifier}")
                    throw IOException("TAP control message scheduled for retry")
                }
                is TransportResult.Failed -> {
                    Log.e(TAG, "TAP控制消息发送失败: ${routeResult.errorMessage}")
                    throw IOException("TAP control message failed: ${routeResult.errorMessage}")
                }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            Log.e(TAG, "TAP控制消息发送异常", e)
            throw IOException("TAP control message exception: ${e.message}", e)
        }
    }
}
