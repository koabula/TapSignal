package org.thoughtcrime.securesms.tapv3.integration

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import java.util.Optional

/**
 * Tap v3 AttachmentPointer 构建器
 * 
 * 创建特殊的 AttachmentPointer，用于标识 IPFS 存储的附件：
 * - cdnNumber = 888 (表示 IPFS CDN)
 * - remoteKey = "TAPV3:CID:{ipfs_cid}" (编码 IPFS CID)
 * 
 * AttachmentDownloadJob 会识别 cdnNumber=888，并调用 TapV3AttachmentDownloadInterceptor
 */
object TapV3AttachmentPointerBuilder {
    
    private val TAG = Log.tag(TapV3AttachmentPointerBuilder::class.java)
    
    const val IPFS_CDN_NUMBER = 888
    
    /**
     * 创建占位符 AttachmentPointer
     * 在上传到 IPFS 之前调用，CID 字段先留空
     */
    fun createPlaceholder(attachment: DatabaseAttachment): SignalServiceAttachmentPointer? {
        return try {
            val attachmentId = attachment.attachmentId.id
            val fileName = attachment.fileName ?: "attachment_$attachmentId"
            
            // 使用占位符 key，稍后会被 TapV3MessageTransportImpl 更新
            val placeholderKey = "TAPV3:PLACEHOLDER:$attachmentId".toByteArray(Charsets.UTF_8)
            
            SignalServiceAttachmentPointer(
                IPFS_CDN_NUMBER,
                SignalServiceAttachmentRemoteId.from(attachmentId.toString()),
                attachment.contentType ?: "application/octet-stream",
                placeholderKey,
                Optional.of(attachment.size.toInt()),
                Optional.empty(),
                attachment.width,
                attachment.height,
                Optional.ofNullable(attachment.remoteDigest),
                Optional.empty(),
                0,
                Optional.ofNullable(fileName),
                attachment.voiceNote,
                attachment.borderless,
                attachment.videoGif,
                Optional.empty(),
                Optional.ofNullable(attachment.blurHash?.hash),
                attachment.uploadTimestamp,
                attachment.uuid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create placeholder AttachmentPointer", e)
            null
        }
    }
    
    /**
     * 使用 IPFS CID 创建 AttachmentPointer
     * 在上传到 IPFS 之后调用
     */
    fun createWithCid(
        attachment: DatabaseAttachment,
        cid: String,
        size: Long
    ): SignalServiceAttachmentPointer {
        val attachmentId = attachment.attachmentId.id
        val fileName = attachment.fileName ?: "attachment_$attachmentId"
        
        // 将 CID 编码到 remoteKey
        val keyContent = "TAPV3:CID:$cid"
        val encodedKey = keyContent.toByteArray(Charsets.UTF_8)
        
        return SignalServiceAttachmentPointer(
            IPFS_CDN_NUMBER,
            SignalServiceAttachmentRemoteId.from(attachmentId.toString()),
            attachment.contentType ?: "application/octet-stream",
            encodedKey,
            Optional.of(size.toInt()),
            Optional.empty(),
            attachment.width,
            attachment.height,
            Optional.ofNullable(attachment.remoteDigest),
            Optional.empty(),
            0,
            Optional.ofNullable(fileName),
            attachment.voiceNote,
            attachment.borderless,
            attachment.videoGif,
            Optional.empty(),
            Optional.ofNullable(attachment.blurHash?.hash),
            attachment.uploadTimestamp,
            attachment.uuid
        )
    }
    
    /**
     * 从 AttachmentPointer 的 remoteKey 中提取 IPFS CID
     */
    fun extractCid(remoteKey: ByteArray?): String? {
        return try {
            if (remoteKey == null) return null
            
            val keyString = String(remoteKey, Charsets.UTF_8)
            if (keyString.startsWith("TAPV3:CID:")) {
                keyString.substring(10)
            } else if (keyString.startsWith("TAPV3:PLACEHOLDER:")) {
                // 占位符，CID 尚未设置
                null
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CID from remoteKey", e)
            null
        }
    }
}
