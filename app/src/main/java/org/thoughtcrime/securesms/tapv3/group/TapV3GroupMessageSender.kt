package org.thoughtcrime.securesms.tapv3.group

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
import org.thoughtcrime.securesms.tapv3.database.IpfsContentTable
import org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupMembersTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.protocol.TapV3MessageCodec
import org.thoughtcrime.securesms.tapv3.push.UnifiedPushProvider
import java.util.concurrent.TimeUnit

class TapV3GroupMessageSender private constructor(private val context: Context) {

    private val ipfsGatewayManager = IpfsGatewayManager.getInstance(context)
    private val unifiedPushProvider = UnifiedPushProvider.getInstance(context)
    private val groupManager = TapV3GroupManager.getInstance(context)
    private val ipfsContentTable = SignalDatabase.ipfsContent

    suspend fun sendMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting Tap v3 group send for message $messageId")
            
            val message = SignalDatabase.messages.getOutgoingMessage(messageId)
            val groupIdObj = message.threadRecipient.requireGroupId().requireV2()
            val groupIdString = Base64.encodeToString(groupIdObj.decodedId, Base64.NO_WRAP)
            
            // 1. Generate Content Encryption Key
            val contentKey = TapV3Crypto.generateKPushKey()
            
            // 2. Encrypt & Upload Attachments
            val attachmentRefs = mutableListOf<TapV3Payload.AttachmentRef>()
            val attachments: List<Attachment> = message.attachments
            for (attachment in attachments) {
                val data = readAttachmentData(attachment) ?: continue
                val encryptedData = TapV3Crypto.encrypt(data, contentKey).let { 
                    if (it is org.thoughtcrime.securesms.tapv3.TapV3Result.Success) it.data else throw Exception("Encryption failed")
                }
                
                val uploadResult = ipfsGatewayManager.upload(encryptedData)
                if (uploadResult is org.thoughtcrime.securesms.tapv3.TapV3Result.Failure) {
                    throw Exception("Attachment upload failed: ${uploadResult.message}")
                }
                val cid = (uploadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Success).data
                
                // Store local reference
                ipfsContentTable.insertContent(
                    cid = cid,
                    contentType = IpfsContentTable.ContentType.ATTACHMENT,
                    sizeBytes = encryptedData.size.toLong(),
                    expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30),
                    recipientId = groupIdString
                )
                
                attachmentRefs.add(TapV3Payload.AttachmentRef(cid, encryptedData.size.toLong(), attachment.contentType))
            }
            
            // 3. Encrypt & Upload Body
            val body = message.body ?: ""
            var contentCid: String? = null
            var encryptedContent: ByteArray? = null
            
            if (body.isNotEmpty()) {
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val encryptedBody = TapV3Crypto.encrypt(bodyBytes, contentKey).let {
                    if (it is org.thoughtcrime.securesms.tapv3.TapV3Result.Success) it.data else throw Exception("Encryption failed")
                }
                
                if (encryptedBody.size < 1024) { // 1KB threshold
                    encryptedContent = encryptedBody
                } else {
                    val uploadResult = ipfsGatewayManager.upload(encryptedBody)
                    if (uploadResult is org.thoughtcrime.securesms.tapv3.TapV3Result.Failure) {
                        throw Exception("Body upload failed: ${uploadResult.message}")
                    }
                    contentCid = (uploadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Success).data
                    
                    ipfsContentTable.insertContent(
                        cid = contentCid,
                        contentType = IpfsContentTable.ContentType.MESSAGE,
                        sizeBytes = encryptedBody.size.toLong(),
                        expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30),
                        recipientId = groupIdString
                    )
                }
            }
            
            // 4. Construct Payload
            val senderAci = Recipient.self().requireAci().toString()
            val payload = TapV3Payload.GroupMessage(
                groupId = groupIdString,
                senderAci = senderAci,
                encryptedContent = encryptedContent,
                contentCid = contentCid,
                attachments = attachmentRefs,
                encryptionKey = contentKey,
                timestamp = message.sentTimeMillis
            )
            
            // 5. Fan-out
            val members = groupManager.getGroupMembers(groupIdString)
            for (member in members) {
                if (member.status == TapV3GroupMembersTable.MemberStatus.ACCEPTED) {
                    if (member.memberAci == senderAci) continue // Don't send to self
                    
                    val memberKPush = member.kPush ?: continue
                    val endpoint = member.endpoint ?: continue
                    
                    val encodeResult = TapV3MessageCodec.encodeMessage(payload, memberKPush, senderAci)
                    if (encodeResult is org.thoughtcrime.securesms.tapv3.TapV3Result.Success) {
                        val encoded = encodeResult.data
                        unifiedPushProvider.send(endpoint, encoded.base64Data.toByteArray(Charsets.UTF_8))
                    } else {
                        Log.w(TAG, "Failed to encode for member ${member.memberAci}")
                    }
                }
            }
            
            // 6. Mark Sent
            SignalDatabase.messages.markAsSent(messageId, true)
            Log.i(TAG, "Tap v3 group message sent successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Tap v3 group message", e)
            SignalDatabase.messages.markAsSentFailed(messageId)
        }
    }

    fun sendMessageBlocking(messageId: Long) {
        kotlinx.coroutines.runBlocking {
            sendMessage(messageId)
        }
    }

    private fun readAttachmentData(attachment: Attachment): ByteArray? {
        return try {
            if (attachment is DatabaseAttachment) {
                 SignalDatabase.attachments.getAttachmentStream(attachment.attachmentId, 0).use { it.readBytes() }
            } else {
                val uri = attachment.uri
                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attachment", e)
            null
        }
    }

    companion object {
        private val TAG = Log.tag(TapV3GroupMessageSender::class.java)
        
        @Volatile
        private var INSTANCE: TapV3GroupMessageSender? = null
        
        @JvmStatic
        fun getInstance(context: Context): TapV3GroupMessageSender {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3GroupMessageSender(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
