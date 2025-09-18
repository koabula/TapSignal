package org.thoughtcrime.securesms.tap.provider.cos.coscomm.processor

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.AttachmentInfo
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosAttachmentDownloadResult
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosAttachmentManager
import org.thoughtcrime.securesms.database.SignalDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * COS附件下载拦截器
 * 在Signal的AttachmentDownloadJob中拦截COS附件的下载请求
 */
class CosAttachmentDownloadInterceptor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosAttachmentDownloadInterceptor::class.java)
        
        @Volatile
        private var INSTANCE: CosAttachmentDownloadInterceptor? = null
        
        fun getInstance(context: Context): CosAttachmentDownloadInterceptor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosAttachmentDownloadInterceptor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val cosAttachmentManager = CosAttachmentManager.getInstance(context)
    private val cosMessageProcessor = CosMessageProcessor.getInstance(context)
    
    /**
     * 检查附件是否为COS附件
     */
    fun isCosAttachment(attachment: DatabaseAttachment): Boolean {
        return try {
            val key = attachment.remoteKey ?: return false
            key.startsWith("COS_ATTACHMENT:") && attachment.cdn.serialize() == 999
        } catch (e: Exception) {
            Log.w(TAG, "检查COS附件标识失败", e)
            false
        }
    }
    
    /**
     * 拦截并下载COS附件
     * @param messageId Signal消息ID
     * @param attachment 需要下载的附件
     * @return 下载成功返回true，否则返回false
     */
    fun interceptAndDownload(messageId: Long, attachment: DatabaseAttachment): Boolean {
        if (!isCosAttachment(attachment)) {
            return false // 不是COS附件，不处理
        }
        
        Log.i(TAG, "拦截COS附件下载: messageId=$messageId, attachmentId=${attachment.attachmentId}")
        
        return try {
            // 解析COS附件信息
            val cosAttachmentInfo = parseCosAttachmentInfo(attachment)
            if (cosAttachmentInfo == null) {
                Log.e(TAG, "无法解析COS附件信息: attachmentId=${attachment.attachmentId}")
                return false
            }
            
            // 获取发送者RecipientId（从消息中获取）
            val senderId = getSenderIdFromMessage(messageId)
            if (senderId.isNullOrEmpty()) {
                Log.e(TAG, "无法获取消息发送者ID: messageId=$messageId")
                return false
            }
            
            // 使用COS下载附件
            val downloadResult = cosAttachmentManager.downloadAttachmentFromCos(
                recipientId = senderId,
                attachmentInfo = cosAttachmentInfo.attachmentInfo,
                encryptionKey = cosAttachmentInfo.encryptionKey
            ).get()
            
            when (downloadResult) {
                is CosAttachmentDownloadResult.Success -> {
                    Log.i(TAG, "COS附件下载成功: attachmentId=${attachment.attachmentId}")
                    
                    // 检查是否为ZIP包，需要解压
                    val finalFile = if (isZipFile(downloadResult.file)) {
                        Log.i(TAG, "检测到ZIP包，开始解压: attachmentId=${attachment.attachmentId}")
                        processZipAttachment(downloadResult.file, cosAttachmentInfo.attachmentInfo)
                    } else {
                        downloadResult.file
                    }
                    
                    if (finalFile != null) {
                        // 使用Signal原生方法完成附件下载
                        finalFile.inputStream().use { inputStream ->
                            SignalDatabase.attachments.finalizeAttachmentAfterDownload(
                                messageId, 
                                attachment.attachmentId, 
                                inputStream
                            )
                        }
                        
                        // 清理临时文件
                        cleanupTempFile(downloadResult.file)
                        if (finalFile != downloadResult.file) {
                            cleanupTempFile(finalFile)
                        }
                        
                        Log.i(TAG, "COS附件集成到Signal完成: attachmentId=${attachment.attachmentId}")
                        return true
                    } else {
                        Log.e(TAG, "处理ZIP附件失败: attachmentId=${attachment.attachmentId}")
                        return false
                    }
                }
                is CosAttachmentDownloadResult.Failure -> {
                    Log.e(TAG, "COS附件下载失败: attachmentId=${attachment.attachmentId}, error=${downloadResult.error}")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "拦截COS附件下载异常: attachmentId=${attachment.attachmentId}", e)
            false
        }
    }
    
    /**
     * 解析COS附件信息
     */
    private fun parseCosAttachmentInfo(attachment: DatabaseAttachment): CosAttachmentInfo? {
        return try {
            val key = attachment.remoteKey ?: return null
            val parts = key.split(":")
            if (parts.size < 3 || parts[0] != "COS_ATTACHMENT") {
                return null
            }
            
            val attachmentId = parts[1]
            val cosPath = if (parts.size > 2 && parts[2].isNotEmpty()) parts[2] else null
            val encryptionKey = attachment.remoteDigest ?: return null
            
            val attachmentInfo = AttachmentInfo(
                fileName = attachment.fileName ?: "attachment",
                mimeType = attachment.contentType ?: "application/octet-stream",
                size = attachment.size,
                attachmentId = attachmentId,
                cosPath = cosPath
            )
            
            CosAttachmentInfo(attachmentInfo, encryptionKey)
        } catch (e: Exception) {
            Log.e(TAG, "解析COS附件信息失败", e)
            null
        }
    }
    
    /**
     * 从消息中获取发送者ID
     */
    private fun getSenderIdFromMessage(messageId: Long): String? {
        return try {
            val message = SignalDatabase.messages.getMessageRecord(messageId)
            message?.fromRecipient?.id?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取消息发送者ID失败: messageId=$messageId", e)
            null
        }
    }
    
    /**
     * 处理ZIP附件，解压第一个文件（简化处理）
     */
    private fun processZipAttachment(zipFile: File, originalInfo: AttachmentInfo): File? {
        return try {
            Log.d(TAG, "开始处理ZIP附件: ${zipFile.name}")
            
            // 为了简化，我们只提取ZIP中的第一个文件
            // 实际的多文件处理可以在后续版本中完善
            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
                val entry = zipInputStream.nextEntry
                if (entry != null && !entry.isDirectory) {
                    val extractedFile = cosMessageProcessor.createSecureTempFile("extracted_", ".tmp")
                    
                    FileOutputStream(extractedFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (zipInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                    
                    Log.i(TAG, "ZIP文件解压成功: ${entry.name}, size=${extractedFile.length()}")
                    extractedFile
                } else {
                    Log.w(TAG, "ZIP文件中没有找到有效文件")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理ZIP附件失败", e)
            null
        }
    }
    
    /**
     * 检查文件是否为ZIP格式
     */
    private fun isZipFile(file: File): Boolean {
        return try {
            FileInputStream(file).use { inputStream ->
                val header = ByteArray(4)
                val bytesRead = inputStream.read(header)
                if (bytesRead < 4) {
                    false
                } else {
                    // ZIP文件魔术字节: 0x504B0304
                    header[0] == 0x50.toByte() && 
                    header[1] == 0x4B.toByte() && 
                    header[2] == 0x03.toByte() && 
                    header[3] == 0x04.toByte()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查ZIP文件格式失败", e)
            false
        }
    }
    
    /**
     * 清理临时文件
     */
    private fun cleanupTempFile(file: File) {
        try {
            if (file.exists() && file.delete()) {
                Log.d(TAG, "清理临时文件: ${file.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理临时文件失败: ${file.name}", e)
        }
    }
    
    /**
     * COS附件信息
     */
    private data class CosAttachmentInfo(
        val attachmentInfo: AttachmentInfo,
        val encryptionKey: ByteArray
    )
} 