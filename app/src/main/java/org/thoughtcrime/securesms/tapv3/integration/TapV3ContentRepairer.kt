package org.thoughtcrime.securesms.tapv3.integration

import okio.ByteString
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * Tap v3 Content 修复器
 * 
 * 在接收端解密 Envelope 后,修复 Content 中的 AttachmentPointer。
 * 将 AttachmentPointer 的 key 字段更新为实际的 IPFS CID。
 * 
 * 流程:
 * 1. 接收端解密 Envelope 得到 SignalServiceCipherResult
 * 2. 从 TapV3ReceiveIntegrator 获取附件 CID 列表
 * 3. 遍历 Content.dataMessage.attachments,更新 key 为 "TAPV3:CID:{cid}"
 * 4. 返回修复后的 SignalServiceCipherResult
 */
object TapV3ContentRepairer {
    
    private val TAG = Log.tag(TapV3ContentRepairer::class.java)
    
    /**
     * 修复 Content 中的 AttachmentPointer
     * 
     * @param content 解密后的 Content
     * @param attachmentCids 附件 CID 列表,按附件顺序排列
     * @return 修复后的 Content
     */
    fun repairContentWithCids(
        content: Content,
        attachmentCids: List<String>
    ): Content {
        if (attachmentCids.isEmpty()) {
            Log.d(TAG, "No attachment CIDs to repair")
            return content
        }
        
        return try {
            val dataMessage = content.dataMessage
            
            if (dataMessage == null) {
                Log.d(TAG, "Content has no dataMessage, skipping repair")
                return content
            }
            
            val attachmentList = dataMessage.attachments
            if (attachmentList.isNullOrEmpty()) {
                Log.d(TAG, "DataMessage has no attachments, skipping repair")
                return content
            }
            
            Log.d(TAG, "Repairing ${attachmentList.size} attachments with ${attachmentCids.size} CIDs")
            
            val repairedAttachments = repairAttachmentPointers(attachmentList, attachmentCids)
            
            val repairedDataMessage = rebuildDataMessage(dataMessage, repairedAttachments)
            val repairedContent = rebuildContent(content, repairedDataMessage)
            
            Log.i(TAG, "Content repair successful: ${repairedAttachments.size} attachments updated")
            
            repairedContent
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to repair content, returning original", e)
            content
        }
    }
    
    /**
     * 修复 AttachmentPointer 列表
     */
    private fun repairAttachmentPointers(
        pointers: List<AttachmentPointer>,
        cids: List<String>
    ): List<AttachmentPointer> {
        val repairedPointers = mutableListOf<AttachmentPointer>()
        
        for (i in pointers.indices) {
            val pointer = pointers[i]
            val cid = if (i < cids.size) cids[i] else null
            
            val repairedPointer = if (cid != null) {
                repairSingleAttachmentPointer(pointer, cid, i)
            } else {
                Log.w(TAG, "No CID for attachment #$i, keeping original")
                pointer
            }
            
            repairedPointers.add(repairedPointer)
        }
        
        return repairedPointers
    }
    
    /**
     * 修复单个 AttachmentPointer
     */
    private fun repairSingleAttachmentPointer(
        pointer: AttachmentPointer,
        cid: String,
        index: Int
    ): AttachmentPointer {
        Log.d(TAG, "Repairing attachment #$index with CID ${cid.take(8)}...")
        
        val keyContent = "TAPV3:CID:$cid"
        val encodedKey = keyContent.toByteArray(Charsets.UTF_8)
        
        // 必须设置 cdnNumber 为 IPFS_CDN_NUMBER，否则 AttachmentDownloadJob 不会拦截
        return pointer.newBuilder()
            .key(ByteString.of(*encodedKey))
            .cdnNumber(TapV3AttachmentPointerBuilder.IPFS_CDN_NUMBER)
            .build()
    }
    
    /**
     * 重建 DataMessage - 只复制必要字段
     * 参考 v2 TapEnvelopeAdapter 的实现
     */
    private fun rebuildDataMessage(
        original: DataMessage,
        repairedAttachments: List<AttachmentPointer>
    ): DataMessage {
        val builder = DataMessage.Builder()
        
        // 复制基本字段
        original.body?.let { builder.body = it }
        original.timestamp?.let { builder.timestamp = it }
        original.expireTimer?.let { builder.expireTimer = it }
        original.flags?.let { builder.flags = it }
        
        // 设置修复后的附件
        builder.attachments = repairedAttachments
        
        return builder.build()
    }
    
    /**
     * 重建 Content
     */
    private fun rebuildContent(
        original: Content,
        repairedDataMessage: DataMessage
    ): Content {
        val builder = Content.Builder()
        
        builder.dataMessage = repairedDataMessage
        
        original.syncMessage?.let { builder.syncMessage = it }
        original.callMessage?.let { builder.callMessage = it }
        original.receiptMessage?.let { builder.receiptMessage = it }
        original.typingMessage?.let { builder.typingMessage = it }
        original.senderKeyDistributionMessage?.let { builder.senderKeyDistributionMessage = it }
        original.decryptionErrorMessage?.let { builder.decryptionErrorMessage = it }
        original.storyMessage?.let { builder.storyMessage = it }
        original.pniSignatureMessage?.let { builder.pniSignatureMessage = it }
        original.editMessage?.let { builder.editMessage = it }
        
        return builder.build()
    }
}
