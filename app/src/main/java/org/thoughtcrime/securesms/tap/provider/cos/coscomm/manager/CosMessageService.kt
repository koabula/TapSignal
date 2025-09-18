package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.cos.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.cos.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.cos.AwsS3Client
import org.thoughtcrime.securesms.tap.provider.cos.cos.TencentCosClient
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.concurrent.CosClientPoolManager
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.CosMessageSerializer
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.CosPathManager
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * COS消息上传下载服务
 * 封装COS客户端操作，提供消息级别的上传下载接口，支持重试机制
 */
class CosMessageService(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosMessageService::class.java)
        
        @Volatile
        private var INSTANCE: CosMessageService? = null
        
        fun getInstance(context: Context): CosMessageService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosMessageService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val executor = Executors.newCachedThreadPool()
    // 子账户Pool管理器
    private val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
    
    // 安全的私有临时目录
    private val secureServiceDir: File by lazy {
        val dir = File(context.getDir("secure_attachments", Context.MODE_PRIVATE), "service_temp")
        if (!dir.exists()) {
            dir.mkdirs()
            // 设置目录权限：只有当前应用可以访问
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
        dir
    }
    
    /**
     * 安全创建临时文件
     */
    private fun createSecureTempFile(prefix: String, suffix: String = ".tmp"): File {
        val tempFile = File.createTempFile(prefix, suffix, secureServiceDir)
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        return tempFile
    }
    
    // 🆕 集成客户端池管理，解决Log_A中的死锁问题
    private val clientPoolManager = CosClientPoolManager.getInstance(context)
    
    // 🆕 添加通道管理器实例
    private val channelManager = CosChannelManager.getInstance(context)
    
    /**
     * 上传COS消息
     */
    fun uploadMessage(
        recipientId: String,
        message: CosMessage,
        attachmentFile: File? = null,
        sequenceNumber: Long? = null
    ): CompletableFuture<CosUploadResult> {
        return CompletableFuture.supplyAsync({
            try {
                Log.i(TAG, "开始上传消息: messageId=${message.messageId}, recipientId=$recipientId")
                
                // 获取自己的COS客户端
                val cosClient = CosClientFactory.createClient(context)
                    ?: return@supplyAsync CosUploadResult.failure("无法创建COS客户端")

                // 获取通道信息以确定上传目录
                val channel = channelManager.getChannel(recipientId)
                if (channel == null) {
                    return@supplyAsync CosUploadResult.failure("未找到COS通道: recipientId=$recipientId")
                }

                val myChannelDirectory = channel.getMyChannelDirectory()
                if (myChannelDirectory == null) {
                    return@supplyAsync CosUploadResult.failure("无法获取通道目录: recipientId=$recipientId")
                }

                // 序列化消息
                val serializationResult = CosMessageSerializer.serializeMessage(message)
                if (serializationResult.isError()) {
                    return@supplyAsync CosUploadResult.failure("消息序列化失败: ${serializationResult.getErrorOrNull()?.message}")
                }

                val messageJson = serializationResult.getOrNull()!!

                // 生成消息文件路径（使用新的v2通道结构）
                val messagePath = generateV2MessageFilePath(myChannelDirectory!!, message)
                
                // 创建临时文件
                val tempFile = createTempFile(messageJson)
                
                try {
                    // 上传消息文件
                    val uploadSuccess = retryOperation<Boolean> {
                        cosClient.uploadFile(tempFile, messagePath)
                    }

                    if (uploadSuccess != true) {
                        return@supplyAsync CosUploadResult.failure("消息文件上传失败")
                    }
                    
                    // 如果有附件，上传附件
                    var attachmentPath: String? = null
                    if (attachmentFile != null && message.attachmentInfo != null) {
                        // 优先使用消息中的cosPath，确保路径一致性
                        attachmentPath = if (!message.attachmentInfo.cosPath.isNullOrEmpty()) {
                            Log.d(TAG, "使用消息中的附件路径进行上传: ${message.attachmentInfo.cosPath}")
                            message.attachmentInfo.cosPath!!
                        } else {
                            Log.w(TAG, "消息中缺少cosPath，回退到生成路径: attachmentId=${message.attachmentInfo.attachmentId}")
                            generateV2AttachmentFilePath(myChannelDirectory!!, message.attachmentInfo.attachmentId)
                        }
                        
                        val attachmentUploadSuccess = retryOperation<Boolean> {
                            cosClient.uploadFile(attachmentFile, attachmentPath)
                        }

                        if (attachmentUploadSuccess != true) {
                            // 清理已上传的消息文件
                            try {
                                // TODO: 实现删除文件功能
                                Log.w(TAG, "需要清理消息文件: $messagePath")
                            } catch (e: Exception) {
                                Log.w(TAG, "清理消息文件失败", e)
                            }
                            return@supplyAsync CosUploadResult.failure("附件文件上传失败")
                        }
                    }
                    
                    Log.i(TAG, "消息上传成功: messageId=${message.messageId}")
                    CosUploadResult.success(messagePath, attachmentPath)
                    
                } finally {
                    // 清理临时文件
                    tempFile.delete()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "上传消息失败: messageId=${message.messageId}", e)
                CosUploadResult.failure("上传失败: ${e.message}")
            }
        }, executor)
    }
    
    /**
     * 下载COS消息
     */
    fun downloadMessage(
        recipientId: String,
        messagePath: String
    ): CompletableFuture<CosDownloadResult> {
        return CompletableFuture.supplyAsync({
            try {
                Log.i(TAG, "开始下载消息: recipientId=$recipientId, path=$messagePath")
                
                // 获取对方的子账户凭证
                val subAccountEntry = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
                    ?: return@supplyAsync CosDownloadResult.failure("没有有效的子账户凭证")
                val accessInfo = subAccountEntry.accessInfo

                // 🆕 使用客户端池管理创建临时客户端，增强回退机制
                var usedPooledClient = false
                var cosClient = clientPoolManager.getCosClient(accessInfo)
                
                // 🔧 修复：池化客户端失败时回退到主客户端
                if (cosClient == null) {
                    Log.w(TAG, "⚠️ 池化客户端创建失败，尝试回退到主COS客户端: recipientId=$recipientId")
                    
                    // 尝试使用主COS客户端（使用相同的SubAccount凭证）
                    cosClient = createTempCosClient(accessInfo)
                    
                    if (cosClient == null) {
                        // 记录详细的失败信息
                        Log.e(TAG, "❌ 主客户端和池化客户端都创建失败")
                        Log.e(TAG, "  - 存储桶: ${accessInfo.bucketName}")
                        Log.e(TAG, "  - 区域: ${accessInfo.region}")
                        Log.e(TAG, "  - 提供商: ${accessInfo.provider}")
                        Log.e(TAG, "  - AccessKeyId: ${accessInfo.accessKeyId.take(8)}...")
                        Log.e(TAG, "  - 是否有SessionToken: ${!accessInfo.sessionToken.isNullOrEmpty()}")
                        Log.e(TAG, "  - 凭证是否过期: ${accessInfo.isExpired()}")
                        
                        return@supplyAsync CosDownloadResult.failure("无法创建临时COS客户端：池化客户端和主客户端都失败")
                    } else {
                        Log.i(TAG, "✅ 成功回退到主COS客户端: recipientId=$recipientId")
                    }
                } else {
                    usedPooledClient = true
                    Log.d(TAG, "✅ 使用池化客户端下载: recipientId=$recipientId")
                }
                
                // 资源管理：确保池化客户端在使用后归还
                try {
                    val tempDir = File(context.cacheDir, "cos_downloads")
                    if (!tempDir.exists()) {
                        tempDir.mkdirs()
                    }

                    // 从消息路径提取文件名，并确保文件名安全
                    val originalFileName = messagePath.substringAfterLast("/")
                    val safeFileName = sanitizeFileName(originalFileName)

                    // 生成唯一的临时文件名，避免冲突
                    val uniqueFileName = generateUniqueFileName(tempDir, safeFileName)
                    val tempFile = File(tempDir, uniqueFileName)

                    Log.d(TAG, "📁 文件名处理:")
                    Log.d(TAG, "  - 原始文件名: $originalFileName")
                    Log.d(TAG, "  - 安全文件名: $safeFileName")
                    Log.d(TAG, "  - 唯一文件名: $uniqueFileName")
                    Log.d(TAG, "  - 临时文件路径: ${tempFile.absolutePath}")
                    Log.d(TAG, "  - 父目录存在: ${tempFile.parentFile?.exists()}")
                    Log.d(TAG, "  - 目标文件存在: ${tempFile.exists()}")
                    Log.d(TAG, "  - 目标是否为目录: ${tempFile.isDirectory()}")

                    // 确保目标路径不是目录
                    if (tempFile.exists() && tempFile.isDirectory()) {
                        Log.w(TAG, "⚠️ 目标路径是目录，删除并重新创建: ${tempFile.absolutePath}")
                        tempFile.deleteRecursively()
                        Log.d(TAG, "✅ 目录删除完成，重新检查: exists=${tempFile.exists()}")
                    }

                    try {
                        // 下载消息文件
                        val downloadSuccess = retryOperation<Boolean> {
                            cosClient.downloadFile(messagePath, tempFile)
                        }

                        if (downloadSuccess != true) {
                            return@supplyAsync CosDownloadResult.failure("消息文件下载失败")
                        }

                        // 验证下载的文件状态
                        if (!tempFile.exists()) {
                            return@supplyAsync CosDownloadResult.failure("下载的文件不存在: ${tempFile.absolutePath}")
                        }

                        if (tempFile.isDirectory()) {
                            Log.e(TAG, "下载的目标是目录而不是文件，删除并重新下载: ${tempFile.absolutePath}")
                            tempFile.deleteRecursively()

                            // 重新创建文件并下载
                            val retrySuccess = retryOperation<Boolean> {
                                cosClient.downloadFile(messagePath, tempFile)
                            }

                            if (retrySuccess != true || !tempFile.exists() || tempFile.isDirectory()) {
                                return@supplyAsync CosDownloadResult.failure("重试下载失败或仍然是目录")
                            }
                        }

                        if (tempFile.length() == 0L) {
                            return@supplyAsync CosDownloadResult.failure("下载的文件为空: ${tempFile.absolutePath}")
                        }

                        Log.d(TAG, "文件验证通过: ${tempFile.absolutePath}, 大小: ${tempFile.length()} bytes, 是否为文件: ${tempFile.isFile()}")

                        // 读取并反序列化消息
                        val messageJson = try {
                            tempFile.readText()
                        } catch (e: Exception) {
                            Log.e(TAG, "读取文件内容失败: ${tempFile.absolutePath}", e)
                            return@supplyAsync CosDownloadResult.failure("读取文件失败: ${e.message}")
                        }
                        val deserializationResult = CosMessageSerializer.deserializeMessage(messageJson)
                        
                        if (deserializationResult.isError()) {
                            return@supplyAsync CosDownloadResult.failure("消息反序列化失败: ${deserializationResult.getErrorOrNull()?.message}")
                        }
                        
                        val message = deserializationResult.getOrNull()!!
                        
                        // 如果有附件，下载附件
                        var attachmentFile: File? = null
                        if (message.attachmentInfo != null) {
                            // 优先使用消息中的cosPath，确保路径一致性
                            val attachmentPath = if (!message.attachmentInfo.cosPath.isNullOrEmpty()) {
                                Log.d(TAG, "使用消息中的附件路径进行下载: ${message.attachmentInfo.cosPath}")
                                message.attachmentInfo.cosPath!!
                            } else {
                                Log.w(TAG, "消息中缺少cosPath，回退到生成路径: attachmentId=${message.attachmentInfo.attachmentId}")
                                CosPathManager.generateAttachmentFilePath(message.attachmentInfo.attachmentId)
                            }
                            val tempAttachmentFile = File.createTempFile("cos_attachment_", ".bin", context.cacheDir)

                            val attachmentDownloadSuccess = retryOperation<Boolean> {
                                cosClient.downloadFile(attachmentPath, tempAttachmentFile)
                            }

                            if (attachmentDownloadSuccess == true) {
                                attachmentFile = tempAttachmentFile
                            } else {
                                Log.w(TAG, "附件下载失败，但消息下载成功")
                                tempAttachmentFile.delete()
                            }
                        }
                        
                        // 更新轮询时间（子账户不需要更新轮询时间，因为是永久凭证）
                        Log.d(TAG, "子账户凭证无需更新轮询时间: recipientId=$recipientId")
                        
                        Log.i(TAG, "消息下载成功: messageId=${message.messageId}")
                        CosDownloadResult.success(message, attachmentFile)
                        
                    } finally {
                        // 清理临时消息文件
                        try {
                            tempFile.delete()
                        } catch (_: Exception) { }
                    }
                } finally {
                    if (usedPooledClient) {
                        try {
                            clientPoolManager.returnClient(accessInfo, cosClient!!)
                            Log.d(TAG, "🔁 归还池化COS客户端: ${accessInfo.provider}-${accessInfo.region}-${accessInfo.bucketName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "归还池化COS客户端时出错", e)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "下载消息失败: recipientId=$recipientId, path=$messagePath", e)
                
                // 🔧 修复：增强错误信息记录，帮助调试
                Log.e(TAG, "下载失败详细信息:")
                Log.e(TAG, "  - 消息路径: $messagePath")
                Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - 异常消息: ${e.message}")
                Log.e(TAG, "  - 异常堆栈: ${e.stackTrace.take(3).joinToString("; ") { "${it.className}.${it.methodName}:${it.lineNumber}" }}")
                
                // 增加轮询错误计数
                subAccountPoolManager.incrementPollingErrors(recipientId).onError { exception ->
                    Log.w(TAG, "增加轮询错误失败: ${exception.message}")
                }
                
                CosDownloadResult.failure("下载失败: ${e.message}")
            }
        }, executor)
    }

    /**
     * 上传附件到COS
     */
    fun uploadAttachment(
        recipientId: String,
        attachmentFile: File,
        cosPath: String
    ): CompletableFuture<CosUploadResult> {
        return CompletableFuture.supplyAsync({
            try {
                Log.i(TAG, "开始上传附件: recipientId=$recipientId, path=$cosPath")

                // 获取自己的COS客户端
                val cosClient = CosClientFactory.createClient(context)
                    ?: return@supplyAsync CosUploadResult.failure("无法创建COS客户端")

                // 上传附件文件
                val uploadSuccess = retryOperation {
                    cosClient.uploadFile(attachmentFile, cosPath)
                }

                if (uploadSuccess == true) {
                    Log.i(TAG, "附件上传成功: path=$cosPath")
                    CosUploadResult.success("", cosPath)
                } else {
                    Log.e(TAG, "附件上传失败: path=$cosPath")
                    CosUploadResult.failure("附件上传失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "上传附件失败: path=$cosPath", e)
                
                // 🔧 修复：增强错误信息记录
                Log.e(TAG, "附件上传失败详细信息:")
                Log.e(TAG, "  - 附件路径: $cosPath")
                Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - 异常消息: ${e.message}")
                
                CosUploadResult.failure("上传失败: ${e.message}")
            }
        }, executor)
    }

    /**
     * 下载附件从COS
     */
    fun downloadAttachment(
        recipientId: String,
        cosPath: String
    ): CompletableFuture<CosDownloadResult> {
        return CompletableFuture.supplyAsync({
            try {
                Log.i(TAG, "开始下载附件: recipientId=$recipientId, path=$cosPath")

                // 获取对方的子账户凭证
                val subAccountEntry = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
                    ?: return@supplyAsync CosDownloadResult.failure("没有有效的子账户凭证")
                val accessInfo = subAccountEntry.accessInfo

                // 🔧 修复：使用增强的客户端创建逻辑，支持回退机制
                var usedPooledClient = false
                var cosClient = clientPoolManager.getCosClient(accessInfo)
                
                // 回退到主客户端
                if (cosClient == null) {
                    Log.w(TAG, "⚠️ 池化客户端创建失败，回退到主客户端进行附件下载: recipientId=$recipientId")
                    cosClient = createTempCosClient(accessInfo)
                    
                    if (cosClient == null) {
                        Log.e(TAG, "❌ 附件下载：池化客户端和主客户端都创建失败")
                        return@supplyAsync CosDownloadResult.failure("无法创建临时COS客户端：池化客户端和主客户端都失败")
                    }
                } else {
                    usedPooledClient = true
                }
                
                // 资源管理：确保池化客户端在使用后归还
                try {
                    val tempFile = createSecureTempFile("cos_attachment_", ".bin")

                    try {
                        // 下载附件文件
                        val downloadSuccess = retryOperation<Boolean> {
                            cosClient.downloadFile(cosPath, tempFile)
                        }

                        if (downloadSuccess == true) {
                            Log.i(TAG, "附件下载成功: path=$cosPath")
                            // 创建一个虚拟的CosMessage用于返回
                            val dummyMessage = CosMessage(
                                messageId = "dummy-attachment-message",
                                timestamp = System.currentTimeMillis(),
                                senderId = "",
                                recipientId = recipientId,
                                messageType = MessageType.ATTACHMENT,
                                signalCiphertext = "",
                                signalCiphertextType = 2, // 默认WHISPER_TYPE
                                contentMetadata = ContentMetadata(0, CompressionType.NONE, "Signal-Protocol"),
                                attachmentInfo = null
                            )
                            CosDownloadResult.success(dummyMessage, tempFile)
                        } else {
                            Log.e(TAG, "附件下载失败: path=$cosPath")
                            tempFile.delete()
                            CosDownloadResult.failure("附件下载失败")
                        }
                    } catch (e: Exception) {
                        // 失败时清理
                        // tempFile在上面已删除或由上层处理
                        throw e
                    }
                } finally {
                    if (usedPooledClient) {
                        try {
                            clientPoolManager.returnClient(accessInfo, cosClient!!)
                            Log.d(TAG, "🔁 归还池化COS客户端: ${accessInfo.provider}-${accessInfo.region}-${accessInfo.bucketName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "归还池化COS客户端时出错", e)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载附件失败: path=$cosPath", e)
                
                // 🔧 修复：增强错误信息记录，帮助调试
                Log.e(TAG, "附件下载失败详细信息:")
                Log.e(TAG, "  - 附件路径: $cosPath")
                Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - 异常消息: ${e.message}")
                
                CosDownloadResult.failure("下载失败: ${e.message}")
            }
        }, executor)
    }
    
    /**
     * 列举对方的消息文件
     */
    fun listMessages(recipientId: String): CompletableFuture<CosListResult> {
        return CompletableFuture.supplyAsync({
            try {
                Log.d(TAG, "列举消息文件: recipientId=$recipientId")

                // 获取通道信息
                val channel = channelManager.getChannel(recipientId)
                if (channel == null) {
                    Log.w(TAG, "未找到COS通道: recipientId=$recipientId")
                    return@supplyAsync CosListResult.failure("未找到COS通道")
                }

                // 获取对方的通道目录（发送方的通道目录）
                val theirChannelDirectory = channel.getTheirChannelDirectory()
                if (theirChannelDirectory == null) {
                    Log.w(TAG, "无法获取对方的通道目录: recipientId=$recipientId")
                    return@supplyAsync CosListResult.failure("无法获取对方的通道目录")
                }

                // 获取对方的子账户凭证来访问对方的存储桶
                val subAccountEntry = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
                    ?: return@supplyAsync CosListResult.failure("没有有效的子账户凭证")
                val accessInfo = subAccountEntry.accessInfo

                // 详细日志：调试SubAccount信息
                Log.i(TAG, "=== 轮询调试信息 ===")
                Log.i(TAG, "对方通道目录: $theirChannelDirectory")
                Log.i(TAG, "SubAccount存储桶: ${accessInfo.bucketName}")
                Log.i(TAG, "SubAccount区域: ${accessInfo.region}")
                Log.i(TAG, "SubAccount共享目录: ${accessInfo.sharedDirectory}")
                Log.i(TAG, "SubAccount AccessKeyId: ${accessInfo.accessKeyId.take(8)}...")
                Log.i(TAG, "SubAccount是否有SessionToken: ${!accessInfo.sessionToken.isNullOrEmpty()}")
                Log.i(TAG, "=== 调试信息结束 ===")

                // 🔧 修复：使用增强的客户端创建逻辑，支持回退机制
                var usedPooledClient = false
                var cosClient = clientPoolManager.getCosClient(accessInfo)
                
                // 回退到主客户端
                if (cosClient == null) {
                    Log.w(TAG, "⚠️ 池化客户端创建失败，回退到主客户端进行列举: recipientId=$recipientId")
                    cosClient = createTempCosClient(accessInfo)
                    
                    if (cosClient == null) {
                        Log.e(TAG, "❌ 列举消息：池化客户端和主客户端都创建失败")
                        return@supplyAsync CosListResult.failure("无法创建临时COS客户端：池化客户端和主客户端都失败")
                    }
                } else {
                    usedPooledClient = true
                }

                // 资源管理：确保池化客户端在使用后归还
                try {
                    // 构建消息目录路径（使用发送方的通道目录）
                    val messageDirectoryPath = "/v2-channels/$theirChannelDirectory/outbox/messages/"

                    Log.d(TAG, "轮询发送方的通道目录: $messageDirectoryPath, bucket=${accessInfo.bucketName}")
                    Log.i(TAG, "=== 权限路径匹配检查 ===")
                    Log.i(TAG, "SubAccount权限路径: ${accessInfo.sharedDirectory}")
                    Log.i(TAG, "实际访问路径: $messageDirectoryPath")
                    Log.i(TAG, "路径是否匹配: ${messageDirectoryPath.startsWith(accessInfo.sharedDirectory.removeSuffix("/"))}")
                    Log.i(TAG, "=== 权限路径检查结束 ===")

                    // 列举消息目录
                    val files = retryOperation {
                        cosClient.listFiles(messageDirectoryPath)
                    } ?: emptyList()
                    
                    // 过滤和排序消息文件
                    val messageFiles = files
                        .filter { CosPathManager.isMessageFilePath(it.key) }
                        .sortedBy { it.lastModified } // 按时间排序
                    
                    Log.d(TAG, "找到 ${messageFiles.size} 个消息文件")
                    CosListResult.success(messageFiles)
                } finally {
                    if (usedPooledClient) {
                        try {
                            clientPoolManager.returnClient(accessInfo, cosClient!!)
                            Log.d(TAG, "🔁 归还池化COS客户端: ${accessInfo.provider}-${accessInfo.region}-${accessInfo.bucketName}")
                        } catch (e: Exception) {
                            Log.w(TAG, "归还池化COS客户端时出错", e)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "列举消息失败: recipientId=$recipientId", e)
                
                // 增加轮询错误计数
                subAccountPoolManager.incrementPollingErrors(recipientId).onError { exception ->
                    Log.w(TAG, "增加轮询错误失败: ${exception.message}")
                }
                
                CosListResult.failure("列举失败: ${e.message}")
            }
        }, executor)
    }
    
    /**
     * 删除已处理的消息文件
     */
    fun deleteProcessedMessage(messagePath: String): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            try {
                val cosClient = CosClientFactory.createClient(context) ?: return@supplyAsync false

                // TODO: 实现删除文件功能，当前COS客户端接口不包含删除方法
                Log.i(TAG, "标记删除消息文件: $messagePath")
                true

            } catch (e: Exception) {
                Log.e(TAG, "删除消息文件失败: $messagePath", e)
                false
            }
        }, executor)
    }
    
    /**
     * 创建临时文件
     */
    private fun createTempFile(content: String): File {
        val tempFile = createSecureTempFile("cos_upload_", ".json")
        tempFile.writeText(content)
        return tempFile
    }
    
    /**
     * 使用CAM凭证创建COS客户端
     * 支持永久凭证（子账户）和临时凭证（STS）
     */
    private fun createTempCosClient(accessInfo: CosAccessInfo): CosClient? {
        return try {
            Log.d(TAG, "创建COS客户端: provider=${accessInfo.provider}, bucket=${accessInfo.bucketName}")

            // 检查凭证是否过期
            if (accessInfo.isExpired()) {
                Log.w(TAG, "CAM凭证已过期，无法创建客户端")
                return null
            }

            // 判断凭证类型并记录
            val isPermanentCredential = accessInfo.sessionToken.isNullOrEmpty()
            val credentialType = if (isPermanentCredential) "子账户永久凭证" else "STS临时凭证"
            Log.i(TAG, "使用${credentialType}创建COS客户端")

            // 创建配置，正确处理sessionToken
            val clientConfig = CosConfig(
                provider = when (accessInfo.provider) {
                    "AWS" -> CosConfig.Provider.AWS
                    "TENCENT" -> CosConfig.Provider.TENCENT
                    else -> CosConfig.Provider.AWS
                },
                secretId = accessInfo.accessKeyId,
                secretKey = accessInfo.secretAccessKey,
                region = accessInfo.region,
                bucketName = accessInfo.bucketName,
                sessionToken = accessInfo.sessionToken // 永久凭证时为null，临时凭证时有值
            )

            // 根据凭证类型记录额外信息
            if (isPermanentCredential) {
                Log.d(TAG, "✅ 使用子账户永久凭证，避免STS时间限制")
            } else {
                Log.d(TAG, "⚠️ 使用STS临时凭证，有时间限制")
            }

            // 创建客户端
            when (clientConfig.provider) {
                CosConfig.Provider.AWS ->
                    AwsS3Client(clientConfig)
                CosConfig.Provider.TENCENT ->
                    TencentCosClient(clientConfig, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建COS客户端失败", e)
            
            // 🔧 修复：增强错误日志记录，提供更多调试信息
            Log.e(TAG, "COS客户端创建失败详细信息:")
            Log.e(TAG, "  - 提供商: ${accessInfo.provider}")
            Log.e(TAG, "  - 区域: ${accessInfo.region}")
            Log.e(TAG, "  - 存储桶: ${accessInfo.bucketName}")
            Log.e(TAG, "  - AccessKeyId: ${accessInfo.accessKeyId.take(8)}...")
            Log.e(TAG, "  - 凭证类型: ${if (accessInfo.sessionToken.isNullOrEmpty()) "永久凭证" else "临时凭证"}")
            Log.e(TAG, "  - 凭证是否过期: ${accessInfo.isExpired()}")
            Log.e(TAG, "  - 异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "  - 异常消息: ${e.message}")
            
            null
        }
    }

    /**
     * 验证当前轮询使用的凭证类型
     */
    fun verifyPollingCredentialType(recipientId: String): String {
        val subAccountEntry = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        if (subAccountEntry == null) {
            return "❌ 没有有效的子账户凭证"
        }

        val accessInfo = subAccountEntry.accessInfo
        val isPermanentCredential = accessInfo.sessionToken.isNullOrEmpty()

        return if (isPermanentCredential) {
            "✅ 使用子账户永久凭证 (accessKeyId=${accessInfo.accessKeyId.take(8)}...)"
        } else {
            "⚠️ 使用STS临时凭证 (sessionToken=${accessInfo.sessionToken?.take(8)}...)"
        }
    }

    /**
     * 获取轮询凭证统计信息
     */
    fun getPollingCredentialStatistics(): Map<String, Any> {
        val allEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()
        var permanentCount = 0
        var temporaryCount = 0

        allEntries.forEach { entry ->
            if (entry.accessInfo.sessionToken.isNullOrEmpty()) {
                permanentCount++
            } else {
                temporaryCount++
            }
        }

        return mapOf(
            "totalEntries" to allEntries.size,
            "permanentCredentials" to permanentCount,
            "temporaryCredentials" to temporaryCount,
            "preferredType" to if (permanentCount > temporaryCount) "永久凭证" else "临时凭证"
        )
    }

    /**
     * 重试操作
     */
    private fun <T> retryOperation(operation: () -> T): T? {
        var lastException: Exception? = null
        
        repeat(CosConstants.MAX_RETRY_COUNT) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "操作失败，第 ${attempt + 1} 次重试", e)
                
                if (attempt < CosConstants.MAX_RETRY_COUNT - 1) {
                    // 指数退避
                    val delay = CosConstants.RETRY_BACKOFF_BASE * (1L shl attempt)
                    Thread.sleep(delay)
                }
            }
        }
        
        Log.e(TAG, "操作重试失败", lastException)
        return null
    }
    
    /**
     * 生成v2通道消息文件路径
     */
    private fun generateV2MessageFilePath(channelDirectory: String, message: CosMessage): String {
        val randomSuffix = (10000000..99999999).random().toString(16)

        val fileName = String.format(
            "%s_%s.json",
            message.messageId.substring(0, 8), // 使用messageId前8位
            randomSuffix
        )
        return "/v2-channels/$channelDirectory/outbox/messages/$fileName"
    }

    /**
     * 生成v2通道附件文件路径
     */
    private fun generateV2AttachmentFilePath(channelDirectory: String, attachmentId: String): String {
        val randomSuffix = (10000000..99999999).random().toString(16)
        val fileName = "${attachmentId}_${randomSuffix}.bin"
        return "/v2-channels/$channelDirectory/outbox/attachments/$fileName"
    }

    /**
     * 从CosAccessInfo中提取通道目录名
     */
    private fun extractChannelDirectoryFromAccessInfo(accessInfo: CosAccessInfo): String? {
        val sharedDirectory = accessInfo.sharedDirectory
        if (sharedDirectory.isNullOrEmpty()) return null

        val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
        val matchResult = regex.find(sharedDirectory)
        return matchResult?.groupValues?.get(1)
    }

    /**
     * 关闭服务
     */
    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }

    /**
     * 安全化文件名，移除不安全字符
     */
    private fun sanitizeFileName(fileName: String): String {
        if (fileName.isEmpty()) return "unknown_file"

        // 移除路径分隔符和其他不安全字符
        val sanitized = fileName
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.')
            .take(100) // 限制文件名长度

        return if (sanitized.isEmpty()) "unknown_file" else sanitized
    }

    /**
     * 生成唯一的文件名，避免冲突
     */
    private fun generateUniqueFileName(directory: File, baseName: String): String {
        var fileName = baseName
        var counter = 1

        // 分离文件名和扩展名
        val lastDotIndex = baseName.lastIndexOf('.')
        val nameWithoutExt = if (lastDotIndex > 0) baseName.substring(0, lastDotIndex) else baseName
        val extension = if (lastDotIndex > 0) baseName.substring(lastDotIndex) else ""

        // 如果文件不存在，直接返回
        var targetFile = File(directory, fileName)
        if (!targetFile.exists()) {
            return fileName
        }

        // 如果存在，生成唯一名称
        while (targetFile.exists()) {
            fileName = "${nameWithoutExt}_${counter}${extension}"
            targetFile = File(directory, fileName)
            counter++

            // 防止无限循环
            if (counter > 1000) {
                fileName = "${nameWithoutExt}_${System.currentTimeMillis()}${extension}"
                break
            }
        }

        return fileName
    }
}

/**
 * COS上传结果
 */
sealed class CosUploadResult {
    data class Success(
        val messagePath: String,
        val attachmentPath: String? = null
    ) : CosUploadResult()

    data class Failure(val error: String) : CosUploadResult()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    companion object {
        fun success(messagePath: String, attachmentPath: String? = null) = Success(messagePath, attachmentPath)
        fun failure(error: String) = Failure(error)
    }
}

/**
 * COS下载结果
 */
sealed class CosDownloadResult {
    data class Success(
        val message: CosMessage,
        val attachmentFile: File? = null
    ) : CosDownloadResult()

    data class Failure(val error: String) : CosDownloadResult()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    companion object {
        fun success(message: CosMessage, attachmentFile: File? = null) = Success(message, attachmentFile)
        fun failure(error: String) = Failure(error)
    }
}

/**
 * COS列举结果
 */
sealed class CosListResult {
    data class Success(val files: List<org.thoughtcrime.securesms.tap.provider.cos.cos.CosFileInfo>) : CosListResult()
    data class Failure(val error: String) : CosListResult()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    companion object {
        fun success(files: List<org.thoughtcrime.securesms.tap.provider.cos.cos.CosFileInfo>) = Success(files)
        fun failure(error: String) = Failure(error)
    }
}
