package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.signal.core.util.Base64.decodeBase64OrThrow
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import java.io.File
import java.io.FileOutputStream

/**
 * Tap v3 附件下载拦截器
 * 
 * 拦截 AttachmentDownloadJob，从 IPFS 下载附件：
 * 1. 识别 cdnNumber=888 的附件
 * 2. 从 remoteKey 提取 IPFS CID
 * 3. 先尝试从 TapV3AttachmentCache 获取（预下载的数据）
 * 4. 如果缓存未命中，从 IPFS 下载
 * 5. 保存到 Signal 数据库
 */
class TapV3AttachmentDownloadInterceptor private constructor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(TapV3AttachmentDownloadInterceptor::class.java)
        
        @Volatile
        private var INSTANCE: TapV3AttachmentDownloadInterceptor? = null
        
        fun getInstance(context: Context): TapV3AttachmentDownloadInterceptor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3AttachmentDownloadInterceptor(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    private val ipfsGatewayManager = IpfsGatewayManager.getInstance(context)
    
    /**
     * 检查附件是否为 Tap v3 IPFS 附件
     */
    fun isTapV3Attachment(attachment: DatabaseAttachment): Boolean {
        return try {
            val isTapV3 = attachment.cdn.cdnNumber == TapV3AttachmentPointerBuilder.IPFS_CDN_NUMBER
            
            Log.d(TAG, "Checking attachment type: attachmentId=${attachment.attachmentId}, " +
                      "cdnNumber=${attachment.cdn.cdnNumber}, " +
                      "isTapV3Attachment=$isTapV3")
            
            isTapV3
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check attachment type: attachmentId=${attachment.attachmentId}", e)
            false
        }
    }
    
    /**
     * 拦截并下载 Tap v3 附件
     */
    fun interceptAndDownload(messageId: Long, attachment: DatabaseAttachment): Boolean {
        return try {
            Log.i(TAG, "Downloading Tap v3 attachment: messageId=$messageId, attachmentId=${attachment.attachmentId}")
            
            // 从 remoteKey 提取 IPFS CID
            val remoteKeyBytes = attachment.remoteKey?.decodeBase64OrThrow()
            val cid = TapV3AttachmentPointerBuilder.extractCid(remoteKeyBytes)
            if (cid == null) {
                Log.e(TAG, "Failed to extract CID from attachment: attachmentId=${attachment.attachmentId}")
                return false
            }
            
            Log.d(TAG, "Extracted CID: ${cid.take(8)}... for attachmentId=${attachment.attachmentId}")
            
            // 先尝试从缓存获取预下载的数据
            var attachmentData = TapV3AttachmentCache.retrieve(cid)
            
            if (attachmentData != null) {
                Log.i(TAG, "Found pre-downloaded attachment in cache: cid=${cid.take(8)}..., size=${attachmentData.size}")
            } else {
                // 缓存未命中，从 IPFS 下载
                Log.d(TAG, "Cache miss, downloading from IPFS: cid=${cid.take(8)}...")
                
                val downloadResult = runBlocking {
                    ipfsGatewayManager.download(cid)
                }
                
                if (downloadResult.isFailure()) {
                    val failure = downloadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Failure
                    Log.e(TAG, "Failed to download attachment from IPFS: ${failure.message}")
                    return false
                }
                
                attachmentData = (downloadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Success).data
                Log.i(TAG, "Downloaded attachment from IPFS: cid=${cid.take(8)}..., size=${attachmentData.size}")
            }
            
            // 保存到 Signal 数据库
            saveAttachmentDataToSignal(messageId, attachment.attachmentId, attachmentData)
            
            // 更新附件状态为已完成
            SignalDatabase.attachments.setTransferState(
                messageId,
                attachment.attachmentId,
                AttachmentTable.TRANSFER_PROGRESS_DONE
            )
            
            Log.i(TAG, "Tap v3 attachment download successful: attachmentId=${attachment.attachmentId}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to intercept and download Tap v3 attachment: messageId=$messageId, attachmentId=${attachment.attachmentId}", e)
            false
        }
    }
    
    /**
     * 将附件数据保存到 Signal 数据库
     * 参考 Tap v2 的实现
     */
    private fun saveAttachmentDataToSignal(
        messageId: Long,
        attachmentId: AttachmentId,
        data: ByteArray
    ) {
        SignalDatabase.attachments.finalizeAttachmentAfterDownload(
            messageId,
            attachmentId,
            data.inputStream()
        )
        
        Log.d(TAG, "Saved attachment data to Signal: attachmentId=$attachmentId, size=${data.size}")
    }
}
