package org.thoughtcrime.securesms.tapv3.integration

import org.thoughtcrime.securesms.attachments.Attachment
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe holder for passing attachments from IndividualSendJob to TapV3MessageTransportImpl.
 * 
 * This is a temporary storage mechanism to bridge the gap between the job layer
 * (which has access to raw attachments) and the transport layer (which only receives ciphertext).
 * 
 * 工作流程:
 * 1. IndividualSendJob 在发送前调用 setAttachments(recipientId, attachments)
 * 2. SignalServiceMessageSender 调用 sendDataMessageViaTapV3() 进行加密
 * 3. TapV3MessageTransportImpl.sendMessageViaTapV3() 调用 getAndClearAttachments(recipientId)
 * 4. 附件被取出后自动清除
 */
object TapV3AttachmentHolder {
    
    private val attachmentMap = ConcurrentHashMap<String, List<Attachment>>()
    
    /**
     * Store attachments for a specific recipient.
     * This should be called by IndividualSendJob before sending the message.
     */
    fun setAttachments(recipientId: String, attachments: List<Attachment>) {
        if (attachments.isNotEmpty()) {
            attachmentMap[recipientId] = attachments
        }
    }
    
    /**
     * Get and clear attachments for a specific recipient.
     * This should be called by TapV3MessageTransportImpl when sending the message.
     * Returns empty list if no attachments are stored for this recipient.
     */
    fun getAndClearAttachments(recipientId: String): List<Attachment> {
        return attachmentMap.remove(recipientId) ?: emptyList()
    }
    
    /**
     * Clear all stored attachments.
     * This can be used for cleanup in case of errors.
     */
    fun clearAll() {
        attachmentMap.clear()
    }
    
    /**
     * Clear attachments for a specific recipient without returning them.
     * This can be used for cleanup in case of errors.
     */
    fun clear(recipientId: String) {
        attachmentMap.remove(recipientId)
    }
}
