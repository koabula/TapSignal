package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.mms.MmsException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream

/**
 * Tap附件下载拦截器
 * 替代CosAttachmentDownloadInterceptor，使用统一的Tap传输层
 */
class TapAttachmentDownloadInterceptor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapAttachmentDownloadInterceptor::class.java)
        
        @Volatile
        private var INSTANCE: TapAttachmentDownloadInterceptor? = null
        
        fun getInstance(context: Context): TapAttachmentDownloadInterceptor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapAttachmentDownloadInterceptor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val channelManager = TransportChannelManager.getInstance(context)
    private val transportManager = TransportManager.getInstance(context)
    
    /**
     * 检查附件是否为Tap传输的附件
     * 方案一优化：主要通过CDN 999识别TAP附件
     */
    fun isTapAttachment(attachment: DatabaseAttachment): Boolean {
        return try {
            // 主要识别方式：CDN 999 (COS CDN)
            val isCosAttachment = attachment.cdn.cdnNumber == 999
            
            if (isCosAttachment) {
                Log.d(TAG, "检查附件类型: attachmentId=${attachment.attachmentId}, " +
                          "cdnNumber=${attachment.cdn.cdnNumber}, " +
                          "fileName=${attachment.fileName}, " +
                          "isTapAttachment=true (CDN 999)")
                return true
            }
            
            // 兼容性检查：检查附件是否有其他TAP标识
            val remoteDigest = attachment.remoteDigest
            val remoteKey = attachment.remoteKey
            val fileName = attachment.fileName
            
            // 备用识别特征：
            // 1. cdnKey包含"tap_attachment"前缀
            // 2. remoteDigest包含"tap_"前缀
            // 3. 或者通过其他元数据标识
            val hasTapCdnKey = remoteKey?.toString()?.startsWith("tap_attachment:") == true
            val hasTapDigestPrefix = remoteDigest?.toString()?.startsWith("tap_") == true
            
            // 检查文件名是否符合Tap传输的模式
            val hasTapFileName = fileName?.contains("attachment_msg_") == true
            
            val isLegacyTapAttachment = hasTapCdnKey || hasTapDigestPrefix || hasTapFileName
            
            Log.d(TAG, "检查附件类型: attachmentId=${attachment.attachmentId}, " +
                      "cdnNumber=${attachment.cdn.cdnNumber}, " +
                      "remoteKey=$remoteKey, " +
                      "fileName=$fileName, " +
                      "isTapAttachment=$isLegacyTapAttachment")
            
            isLegacyTapAttachment
            
        } catch (e: Exception) {
            Log.e(TAG, "检查附件类型异常: attachmentId=${attachment.attachmentId}", e)
            false
        }
    }
    
    /**
     * 拦截并下载Tap附件
     */
    fun interceptAndDownload(messageId: Long, attachment: DatabaseAttachment): Boolean {
        return try {
            Log.i(TAG, "开始下载Tap附件: messageId=$messageId, attachmentId=${attachment.attachmentId}")
            
            // 首先检查是否有预下载的附件数据
            val preDownloadedData = getPreDownloadedAttachmentData(attachment)
            if (preDownloadedData != null) {
                Log.i(TAG, "发现预下载的TAP附件数据: attachmentId=${attachment.attachmentId}, size=${preDownloadedData.size}")
                
                // 直接使用预下载的数据
                saveAttachmentDataToSignal(messageId, attachment.attachmentId, preDownloadedData)
                
                // 清理临时文件
                cleanupPreDownloadedFile(attachment)
                
                Log.i(TAG, "TAP附件使用预下载数据完成: attachmentId=${attachment.attachmentId}")
                return true
            }
            
            // 如果没有预下载数据，则使用原来的下载逻辑（降级处理）
            Log.w(TAG, "未找到预下载数据，使用降级下载: attachmentId=${attachment.attachmentId}")
            
            // 获取发送者信息
            val message = SignalDatabase.messages.getMessageRecord(messageId)
            if (message == null) {
                Log.w(TAG, "找不到消息记录: messageId=$messageId")
                return false
            }
            
            // 修复：使用发送者ACI字符串查询通道状态，与通道管理保持一致
            val senderAci = try {
                message.fromRecipient.requireAci().toString()
            } catch (e: Exception) {
                Log.w(TAG, "无法获取发送者ACI: messageId=$messageId, error=${e.message}")
                return false
            }
            
            // 检查是否有活跃的私聊Tap通道（附件下载应该只在私聊v2 mode下进行）
            if (!channelManager.hasActivePrivateChannel(senderAci)) {
                Log.w(TAG, "没有活跃的私聊Tap通道: senderAci=${senderAci.take(10)}...")
                return false
            }
            
            // 通过Tap下载附件
            val downloadResult = runBlocking {
                downloadAttachmentViaTap(senderAci, attachment)
            }
            
            if (downloadResult) {
                Log.i(TAG, "Tap附件下载成功: attachmentId=${attachment.attachmentId}")
                
                // 更新附件状态为已完成
                SignalDatabase.attachments.setTransferState(
                    messageId, 
                    attachment.attachmentId, 
                    AttachmentTable.TRANSFER_PROGRESS_DONE
                )
                
                return true
            } else {
                Log.w(TAG, "Tap附件下载失败: attachmentId=${attachment.attachmentId}")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "拦截下载Tap附件异常: messageId=$messageId, attachmentId=${attachment.attachmentId}", e)
            false
        }
    }
    
    /**
     * 通过Tap下载附件
     */
    private suspend fun downloadAttachmentViaTap(senderId: String, attachment: DatabaseAttachment): Boolean {
        return try {
            // 构建附件路径
            val attachmentPath = buildAttachmentPath(attachment)
            if (attachmentPath == null) {
                Log.w(TAG, "无法构建附件路径: attachmentId=${attachment.attachmentId}")
                return false
            }
            
            // 通过TransportManager轮询消息来获取附件
            val messages = transportManager.pollMessages(senderId)
            
            // 查找匹配的附件消息
            val attachmentMessage = messages.find { message ->
                message.attachments.any { att -> att.fileName == attachmentPath || att.transportPath == attachmentPath }
            }
            
            if (attachmentMessage != null) {
                val transportAttachment = attachmentMessage.attachments.find { att -> 
                    att.fileName == attachmentPath || att.transportPath == attachmentPath 
                }
                if (transportAttachment != null) {
                    // 从TransportAttachment构建FileInfo
                    val fileInfo = buildFileInfoFromTransportAttachment(transportAttachment, attachmentPath)
                    if (fileInfo == null) {
                        Log.w(TAG, "无法构建FileInfo: senderId=$senderId, path=$attachmentPath")
                        return false
                    }
                    
                    // 获取传输通道
                    val activeChannels = channelManager.getActiveChannels(senderId)
                    if (activeChannels.isEmpty()) {
                        Log.w(TAG, "没有活跃的传输通道: senderId=$senderId")
                        return false
                    }
                    val channel = activeChannels.first() // 选择优先级最高的通道
                    
                    // 获取对应的TransportProvider
                    val provider = transportManager.getProvider(channel.providerType)
                    if (provider == null) {
                        Log.w(TAG, "获取TransportProvider失败: providerType=${channel.providerType}")
                        return false
                    }
                    
                    // 通过TransportProvider下载文件数据
                    val downloadResult = provider.downloadFile(fileInfo, channel.metadata)
                    
                    when (downloadResult) {
                        is TransportResult.Success -> {
                            val fileData = downloadResult.data
                            if (fileData != null && fileData.isNotEmpty()) {
                                Log.i(TAG, "Tap附件数据下载成功: fileName=${transportAttachment.fileName}, dataSize=${fileData.size}")
                                
                                // 保存附件数据到Signal存储
                                saveAttachmentDataToSignal(attachment.mmsId, attachment.attachmentId, fileData)
                                return true
                            } else {
                                Log.w(TAG, "下载的附件数据为空: fileName=${transportAttachment.fileName}")
                                return false
                            }
                        }
                        is TransportResult.Failed -> {
                            Log.e(TAG, "Tap附件数据下载失败: fileName=${transportAttachment.fileName}, error=${downloadResult.error}, message=${downloadResult.errorMessage}")
                            return false
                        }
                        else -> {
                            Log.w(TAG, "未知的下载结果类型: ${downloadResult::class.java.simpleName}")
                            return false
                        }
                    }
                } else {
                    Log.w(TAG, "附件信息为空: senderId=$senderId, path=$attachmentPath")
                    return false
                }
            } else {
                Log.w(TAG, "未找到匹配的附件消息: senderId=$senderId, path=$attachmentPath")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "通过Tap下载附件异常: senderId=$senderId", e)
            false
        }
    }
    
    /**
     * 构建附件在Tap传输层的路径
     * 方案一优化：从CDN 999附件的key字段解析传输路径
     */
    private fun buildAttachmentPath(attachment: DatabaseAttachment): String? {
        return try {
            // 方案一：对于CDN 999附件，从key字段解析传输路径
            if (attachment.cdn.cdnNumber == 999) {
                val remoteKey = attachment.remoteKey
                if (remoteKey != null) {
                    try {
                        // 解码key字段获取传输路径，使用统一的解码逻辑
                        val keyContent = decodeAttachmentKey(remoteKey)
                        
                        val transportPath = if (keyContent.startsWith("TAP:")) {
                            keyContent.substring(4)  // 去掉"TAP:"前缀
                        } else {
                            keyContent
                        }
                        
                        Log.d(TAG, "从CDN 999附件key解析路径: attachmentId=${attachment.attachmentId}, path=$transportPath")
                        return transportPath
                    } catch (e: Exception) {
                        Log.w(TAG, "解码CDN 999附件key失败: attachmentId=${attachment.attachmentId}", e)
                    }
                }
            }
            
            // 兼容性处理：从附件的remote信息中提取Tap路径
            val remoteDigest = attachment.remoteDigest
            val remoteKey = attachment.remoteKey
            val fileName = attachment.fileName
            val attachmentId = attachment.attachmentId.id
            
            // 根据Tap附件的路径格式构建
            when {
                // 优先使用cdnKey中的路径信息
                remoteKey?.toString()?.startsWith("tap_attachment:") == true -> {
                    // 从cdnKey中提取时间戳和哈希信息
                    val keyInfo = remoteKey.toString().substring(15) // 去掉"tap_attachment:"前缀
                    "attachments/$attachmentId/${fileName ?: "data"}"
                }
                
                // 其次使用remoteDigest中的信息
                remoteDigest?.toString()?.startsWith("tap_") == true -> {
                    val tapPath = remoteDigest.toString().substring(4) // 去掉"tap_"前缀
                    "attachments/$tapPath"
                }
                
                // 检查fileName是否包含消息ID信息
                fileName?.contains("attachment_msg_") == true -> {
                    "attachments/$attachmentId/$fileName"
                }
                
                else -> {
                    // 默认路径构建方式
                    val safeFileName = fileName ?: "attachment_${attachmentId}"
                    "attachments/$attachmentId/$safeFileName"
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "构建附件路径异常: attachmentId=${attachment.attachmentId}", e)
            null
        }
    }
    
    /**
     * 从TransportAttachment构建FileInfo
     */
    private fun buildFileInfoFromTransportAttachment(
        transportAttachment: org.thoughtcrime.securesms.tap.TransportAttachment,
        attachmentPath: String
    ): org.thoughtcrime.securesms.tap.FileInfo? {
        return try {
            org.thoughtcrime.securesms.tap.FileInfo(
                name = transportAttachment.fileName,
                path = transportAttachment.transportPath ?: attachmentPath,
                size = transportAttachment.size,
                lastModified = System.currentTimeMillis(), // 使用当前时间，因为TransportAttachment中没有时间信息
                etag = transportAttachment.fileHash,
                mimeType = transportAttachment.mimeType,
                metadata = mapOf(
                    "attachmentId" to transportAttachment.attachmentId,
                    "originalPath" to attachmentPath
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "构建FileInfo异常: fileName=${transportAttachment.fileName}", e)
            null
        }
    }
    
    /**
     * 保存附件数据到Signal存储
     */
    private fun saveAttachmentDataToSignal(messageId: Long, attachmentId: AttachmentId, data: ByteArray) {
        var inputStreamCreated = false
        try {
            Log.d(TAG, "保存附件数据到Signal存储: attachmentId=$attachmentId, messageId=$messageId, dataSize=${data.size}")
            
            // 验证数据完整性
            if (data.isEmpty()) {
                throw IllegalArgumentException("附件数据为空")
            }
            
            if (data.size > 100 * 1024 * 1024) { // 100MB限制
                throw IllegalArgumentException("附件数据过大: ${data.size} bytes")
            }
            
            // 创建输入流并标记资源已创建
            data.inputStream().use { inputStream ->
                inputStreamCreated = true
                
                // 使用Signal原生的finalizeAttachmentAfterDownload方法保存附件
                SignalDatabase.attachments.finalizeAttachmentAfterDownload(
                    messageId,
                    attachmentId,
                    inputStream
                )
            }
            
            Log.i(TAG, "附件数据保存完成: attachmentId=$attachmentId, messageId=$messageId, size=${data.size}")
            
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "附件数据验证失败: attachmentId=$attachmentId, messageId=$messageId", e)
            // 标记附件为失败状态
            try {
                SignalDatabase.attachments.setTransferState(
                    messageId, 
                    attachmentId, 
                    AttachmentTable.TRANSFER_PROGRESS_FAILED
                )
            } catch (updateException: Exception) {
                Log.w(TAG, "更新附件失败状态时发生异常", updateException)
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "保存附件数据异常: attachmentId=$attachmentId, messageId=$messageId", e)
            
            // 尝试清理可能的部分数据
            try {
                if (inputStreamCreated) {
                    Log.d(TAG, "尝试清理部分保存的附件数据")
                    // 这里可以添加清理逻辑，如果Signal提供相应API
                }
                
                // 标记附件为失败状态
                SignalDatabase.attachments.setTransferState(
                    messageId, 
                    attachmentId, 
                    AttachmentTable.TRANSFER_PROGRESS_FAILED
                )
                
            } catch (cleanupException: Exception) {
                Log.w(TAG, "附件数据保存失败后清理资源时发生异常", cleanupException)
            }
            
            throw e
        }
    }
    
    /**
     * 获取预下载的附件数据
     * 查找TapEnvelopeAdapter立即下载时保存的临时文件
     */
    private fun getPreDownloadedAttachmentData(attachment: DatabaseAttachment): ByteArray? {
        return try {
            // 从attachment的key中解析出路径信息，使用统一的解码逻辑
            val attachmentKey = attachment.remoteKey
            if (attachmentKey == null) {
                Log.d(TAG, "附件key为空: attachmentId=${attachment.attachmentId}")
                return null
            }
            
            // 使用与buildAttachmentPath相同的key解码逻辑
            val keyContent = decodeAttachmentKey(attachmentKey)
            if (!keyContent.startsWith("TAP:")) {
                Log.d(TAG, "非TAP附件key: attachmentId=${attachment.attachmentId}")
                return null
            }
            
            // 解析TAP通道路径：TAP:/v2-channels/{hash}/outbox/attachments/{fileName}
            val fullTapPath = keyContent.substring(4) // 去掉"TAP:"前缀
            
            // 从完整路径中提取文件名
            val fileName = fullTapPath.substringAfterLast("/")
            if (fileName.isBlank()) {
                Log.w(TAG, "无法从TAP路径中提取文件名: $fullTapPath")
                return null
            }
            
            // 生成临时文件标识符：使用路径的哈希值确保唯一性
            val pathHash = fullTapPath.hashCode().toString()
            val tempFileKey = "${pathHash}_${fileName}"
            
            // 查找临时文件
            val tempDir = File(context.cacheDir, "tap_attachments")
            val tempFile = File(tempDir, tempFileKey)
            
            if (tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "找到预下载附件文件: ${tempFile.absolutePath}, size=${tempFile.length()}")
                return tempFile.readBytes()
            } else {
                Log.d(TAG, "未找到预下载附件文件: ${tempFile.absolutePath}")
                return null
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "获取预下载附件数据异常: attachmentId=${attachment.attachmentId}", e)
            null
        }
    }
    
    /**
     * 清理预下载的临时文件
     */
    private fun cleanupPreDownloadedFile(attachment: DatabaseAttachment) {
        try {
            val attachmentKey = attachment.remoteKey
            if (attachmentKey == null || !attachmentKey.startsWith("TAP:")) {
                return
            }
            
            // 解析TAP通道路径
            val fullTapPath = attachmentKey.substring(4)
            val fileName = fullTapPath.substringAfterLast("/")
            if (fileName.isBlank()) {
                return
            }
            
            // 生成与getPreDownloadedAttachmentData一致的临时文件标识符
            val pathHash = fullTapPath.hashCode().toString()
            val tempFileKey = "${pathHash}_${fileName}"
            
            val tempDir = File(context.cacheDir, "tap_attachments")
            val tempFile = File(tempDir, tempFileKey)
            
            if (tempFile.exists()) {
                val deleted = tempFile.delete()
                Log.d(TAG, "清理预下载临时文件: ${tempFile.absolutePath}, deleted=$deleted")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "清理预下载临时文件异常: attachmentId=${attachment.attachmentId}", e)
        }
    }
    
    /**
     * 统一的附件key解码逻辑，确保buildAttachmentPath和getPreDownloadedAttachmentData使用相同逻辑
     */
    private fun decodeAttachmentKey(remoteKey: String): String {
        return if (remoteKey.startsWith("TAP:")) {
            remoteKey
        } else {
            try {
                // 尝试base64解码
                val decodedBytes = java.util.Base64.getDecoder().decode(remoteKey)
                String(decodedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                // 如果解码失败，直接使用原始字符串
                remoteKey
            }
        }
    }
} 