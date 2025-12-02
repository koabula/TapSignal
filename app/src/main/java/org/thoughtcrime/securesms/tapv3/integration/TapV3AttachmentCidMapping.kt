package org.thoughtcrime.securesms.tapv3.integration

import org.thoughtcrime.securesms.attachments.Attachment
import java.util.concurrent.ConcurrentHashMap

/**
 * Tap v3 附件 CID 映射
 * 
 * 用于在发送流程中传递附件的 IPFS CID 信息：
 * 1. TapV3SendIntegrator 上传附件到 IPFS，获得 CID
 * 2. 将 CID 存入此映射（key = attachmentId）
 * 3. 发送端在构建消息时，从此映射获取 CID
 * 4. 将 CID 编码到 AttachmentPointer 的 remoteKey 字段
 */
object TapV3AttachmentCidMapping {
    
    data class AttachmentCidInfo(
        val cid: String,
        val size: Long,
        val mimeType: String?
    )
    
    private val cidMap = ConcurrentHashMap<Long, AttachmentCidInfo>()
    
    /**
     * 存储附件的 CID 信息
     * @param attachmentId 附件 ID (使用 rowId)
     * @param cid IPFS CID
     * @param size 附件大小
     * @param mimeType MIME 类型
     */
    fun storeCid(attachmentId: Long, cid: String, size: Long, mimeType: String?) {
        cidMap[attachmentId] = AttachmentCidInfo(cid, size, mimeType)
    }
    
    /**
     * 获取附件的 CID 信息
     * @param attachmentId 附件 ID (使用 rowId)
     * @return CID 信息，如果不存在则返回 null
     */
    fun getCid(attachmentId: Long): AttachmentCidInfo? {
        return cidMap[attachmentId]
    }
    
    /**
     * 清除附件的 CID 信息
     * @param attachmentId 附件 ID
     */
    fun clearCid(attachmentId: Long) {
        cidMap.remove(attachmentId)
    }
    
    /**
     * 清除所有 CID 信息
     */
    fun clearAll() {
        cidMap.clear()
    }
}
