package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.FileInfo
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject
import java.net.URI

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
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private data class AttachmentPathInfo(
        val canonicalPath: String,
        val presignedUrl: String?
    )
    
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
            
            val pathInfo = resolveAttachmentPaths(attachment)
            if (pathInfo == null) {
                Log.w(TAG, "无法解析附件路径: attachmentId=${attachment.attachmentId}")
                return false
            }
            
            // 优先尝试HTTP直链
            val httpUrl = pathInfo.presignedUrl
            if (!httpUrl.isNullOrBlank()) {
                val httpData = runBlocking { downloadAttachmentFromUrl(httpUrl) }
                if (httpData != null && httpData.isNotEmpty()) {
                    saveAttachmentDataToSignal(messageId, attachment.attachmentId, httpData)
                    cleanupPreDownloadedFile(attachment)
                    Log.i(TAG, "通过HTTP下载Tap附件成功: attachmentId=${attachment.attachmentId}, size=${httpData.size}")
                    SignalDatabase.attachments.setTransferState(
                        messageId,
                        attachment.attachmentId,
                        AttachmentTable.TRANSFER_PROGRESS_DONE
                    )
                    return true
                } else {
                    Log.w(TAG, "HTTP下载Tap附件失败或数据为空，尝试回退: url=$httpUrl")
                }
            }
            
            // 通过Tap下载附件
            val downloadResult = runBlocking {
                downloadAttachmentViaTap(senderAci, attachment, pathInfo.canonicalPath)
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
    private suspend fun downloadAttachmentViaTap(
        senderId: String,
        attachment: DatabaseAttachment,
        attachmentPath: String
    ): Boolean {
        return try {
            val activeChannels = channelManager.getActiveChannels(senderId)
            if (activeChannels.isEmpty()) {
                Log.w(TAG, "没有活跃的传输通道: senderId=$senderId")
                return false
            }
            val channel = activeChannels.first()
            val provider = transportManager.getProvider(channel.providerType)
            if (provider == null) {
                Log.w(TAG, "获取TransportProvider失败: providerType=${channel.providerType}")
                return false
            }

            val fileInfo = buildFileInfoForPath(attachment, attachmentPath)
            val downloadResult = provider.downloadFile(fileInfo, channel.metadata)
            when (downloadResult) {
                is TransportResult.Success -> {
                    val data = downloadResult.data
                    if (data != null && data.isNotEmpty()) {
                        saveAttachmentDataToSignal(attachment.mmsId, attachment.attachmentId, data)
                        Log.i(TAG, "通过Provider下载Tap附件成功: path=${fileInfo.path}, size=${data.size}")
                        true
                    } else {
                        Log.w(TAG, "Provider返回的附件数据为空: path=${fileInfo.path}")
                        false
                    }
                }
                is TransportResult.Failed -> {
                    Log.e(TAG, "Provider下载Tap附件失败: path=${fileInfo.path}, error=${downloadResult.error}, message=${downloadResult.errorMessage}")
                    false
                }
                else -> {
                    Log.w(TAG, "未知下载结果类型: ${downloadResult::class.java.simpleName}")
                    false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "通过Tap下载附件异常: senderId=$senderId", e)
            false
        }
    }
    
    /**
     * 解析附件路径信息（规范路径 + 预签名URL）
     */
    private fun resolveAttachmentPaths(attachment: DatabaseAttachment): AttachmentPathInfo? {
        return try {
            if (attachment.cdn.cdnNumber == 999) {
                val remoteKey = attachment.remoteKey
                if (remoteKey != null) {
                    parseTapKey(remoteKey, attachment)?.let { return it }
                }
            }
            
            val fallback = legacyFallbackPath(attachment)
            AttachmentPathInfo(fallback, null)
        } catch (e: Exception) {
            Log.e(TAG, "解析附件路径异常: attachmentId=${attachment.attachmentId}", e)
            null
        }
    }

    private fun isHttpUrl(path: String): Boolean {
        return path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)
    }

    private fun parseTapKey(remoteKey: String, attachment: DatabaseAttachment): AttachmentPathInfo? {
        return try {
            val decoded = decodeAttachmentKey(remoteKey)
            val payload = if (decoded.startsWith("TAP:")) decoded.substring(4) else decoded
            val fallback = legacyFallbackPath(attachment)
            val trimmed = payload.trim()
            
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val json = JSONObject(trimmed)
                val path = json.optString("path")
                val http = json.optString("http").takeIf { !it.isNullOrBlank() }
                val canonical = canonicalizePath(path, fallback)
                AttachmentPathInfo(canonical, http)
            } else if (isHttpUrl(trimmed)) {
                val canonical = canonicalizeUrlPath(trimmed, fallback)
                AttachmentPathInfo(canonical, trimmed)
            } else {
                val canonical = canonicalizePath(trimmed, fallback)
                AttachmentPathInfo(canonical, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析TAP附件key失败: attachmentId=${attachment.attachmentId}", e)
            null
        }
    }

    private fun legacyFallbackPath(attachment: DatabaseAttachment): String {
        val attachmentId = attachment.attachmentId.id
        val safeFileName = attachment.fileName ?: "attachment_${attachmentId}"
        val defaultPath = ensureLeadingSlash("attachments/$attachmentId/$safeFileName")
        
        val remoteKey = attachment.remoteKey
        val remoteDigestString = attachment.remoteDigest?.toString()
        
        return when {
            remoteKey?.startsWith("tap_attachment:") == true -> {
                ensureLeadingSlash("attachments/$attachmentId/${attachment.fileName ?: "data"}")
            }
            remoteDigestString?.startsWith("tap_") == true -> {
                ensureLeadingSlash("attachments/${remoteDigestString.substring(4)}")
            }
            safeFileName.contains("attachment_msg_") -> defaultPath
            else -> defaultPath
        }
    }

    private fun canonicalizePath(rawPath: String?, fallback: String): String {
        if (rawPath.isNullOrBlank()) return fallback
        val withoutQuery = rawPath.trim().substringBefore('?')
        val normalized = ensureLeadingSlash(withoutQuery)
        return if (normalized.isNotBlank()) normalized else fallback
    }

    private fun canonicalizeUrlPath(url: String, fallback: String): String {
        return try {
            val uri = URI(url)
            val path = uri.path?.takeIf { it.isNotBlank() } ?: fallback
            canonicalizePath(path, fallback)
        } catch (e: Exception) {
            Log.w(TAG, "解析HTTP附件路径失败: url=$url", e)
            fallback
        }
    }

    private fun ensureLeadingSlash(path: String): String {
        return if (path.startsWith("/")) path else "/$path"
    }

    private suspend fun downloadAttachmentFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP下载Tap附件失败: url=$url, code=${response.code}")
                    null
                } else {
                    response.body?.bytes()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过HTTP下载Tap附件异常: url=$url", e)
            null
        }
    }
    
    /**
     * 基于Attachment记录构建FileInfo
     */
    private fun buildFileInfoForPath(
        attachment: DatabaseAttachment,
        attachmentPath: String
    ): FileInfo {
        val safeName = when {
            !attachment.fileName.isNullOrBlank() -> attachment.fileName!!
            attachmentPath.contains('/') -> attachmentPath.substringAfterLast('/').ifBlank { "attachment_${attachment.attachmentId.id}" }
            else -> "attachment_${attachment.attachmentId.id}"
        }
        val safeMime = attachment.contentType ?: "application/octet-stream"
        return FileInfo(
            name = safeName,
            path = attachmentPath,
            size = attachment.size,
            lastModified = System.currentTimeMillis(),
            etag = attachment.remoteDigest?.toString(),
            mimeType = safeMime,
            metadata = mapOf(
                "attachmentId" to attachment.attachmentId.id.toString()
            )
        )
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
            val pathInfo = resolveAttachmentPaths(attachment)
            if (pathInfo == null) {
                Log.d(TAG, "无法解析附件路径: attachmentId=${attachment.attachmentId}")
                return null
            }
            
            val fullTapPath = pathInfo.canonicalPath
            
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
            val pathInfo = resolveAttachmentPaths(attachment)
            if (pathInfo == null) {
                return
            }
            
            val fullTapPath = pathInfo.canonicalPath
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
     * 统一的附件key解码逻辑，供路径解析和缓存使用
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
