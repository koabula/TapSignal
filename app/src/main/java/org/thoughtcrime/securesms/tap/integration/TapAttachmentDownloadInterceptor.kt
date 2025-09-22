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
     */
    fun isTapAttachment(attachment: DatabaseAttachment): Boolean {
        return try {
            // 检查附件是否有特殊的Tap标识
            // Tap传输的附件通常有特殊的remote ID格式或特殊标记
            val remoteDigest = attachment.remoteDigest
            val remoteKey = attachment.remoteKey
            
            // Tap附件的特征：
            // 1. remoteDigest可能包含特殊前缀
            // 2. 或者通过其他方式标识为Tap传输
            val isTapAttachment = remoteDigest?.toString()?.startsWith("tap_") == true ||
                                 remoteKey?.toString()?.startsWith("tap_") == true
            
            Log.d(TAG, "检查附件类型: attachmentId=${attachment.attachmentId}, " +
                      "remoteDigest=$remoteDigest, " +
                      "remoteKey=$remoteKey, " +
                      "isTapAttachment=$isTapAttachment")
            
            isTapAttachment
            
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
            
            // 获取发送者信息
            val message = SignalDatabase.messages.getMessageRecord(messageId)
            if (message == null) {
                Log.w(TAG, "找不到消息记录: messageId=$messageId")
                return false
            }
            
            val senderId = message.fromRecipient.id.toString()
            
            // 检查是否有活跃的Tap通道
            if (!channelManager.hasActiveChannel(senderId)) {
                Log.w(TAG, "没有活跃的Tap通道: senderId=$senderId")
                return false
            }
            
            // 通过Tap下载附件
            val downloadResult = runBlocking {
                downloadAttachmentViaTap(senderId, attachment)
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
     */
    private fun buildAttachmentPath(attachment: DatabaseAttachment): String? {
        return try {
            // 从附件的remote信息中提取Tap路径
            val remoteDigest = attachment.remoteDigest
            val remoteKey = attachment.remoteKey
            
            // 根据Tap附件的路径格式构建
            // 假设Tap附件路径格式为: attachments/{digest}/{filename}
            when {
                remoteDigest?.toString()?.startsWith("tap_") == true -> {
                    val tapPath = remoteDigest.toString().substring(4) // 去掉"tap_"前缀
                    "attachments/$tapPath"
                }
                remoteKey?.toString()?.startsWith("tap_") == true -> {
                    val tapPath = remoteKey.toString().substring(4) // 去掉"tap_"前缀
                    "attachments/$tapPath"
                }
                else -> {
                    // 如果没有特殊标识，尝试从文件名构建
                    val fileName = attachment.fileName ?: "unknown_${attachment.attachmentId.id}"
                    "attachments/${attachment.attachmentId.id}/$fileName"
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
        try {
            Log.d(TAG, "保存附件数据到Signal存储: attachmentId=$attachmentId, messageId=$messageId, dataSize=${data.size}")
            
            // 创建输入流
            data.inputStream().use { inputStream ->
                // 使用Signal原生的finalizeAttachmentAfterDownload方法保存附件
                SignalDatabase.attachments.finalizeAttachmentAfterDownload(
                    messageId,
                    attachmentId,
                    inputStream
                )
            }
            
            Log.i(TAG, "附件数据保存完成: attachmentId=$attachmentId, messageId=$messageId, size=${data.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "保存附件数据异常: attachmentId=$attachmentId, messageId=$messageId", e)
            throw e
        }
    }
} 