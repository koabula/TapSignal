package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.*
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * COS附件管理器
 * 负责处理通过COS传输的消息附件，包括加密、上传、下载和解密
 */
class CosAttachmentManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosAttachmentManager::class.java)
        
        // 附件处理配置
        private const val ATTACHMENT_ENCRYPTION_ALGORITHM = "AES/CBC/PKCS5Padding"
        private const val ATTACHMENT_KEY_SIZE = 32 // 256位密钥
        private const val ATTACHMENT_IV_SIZE = 16 // 128位IV
        private const val MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024L // 100MB
        private const val CHUNK_SIZE = 8192 // 8KB块大小
        private const val LARGE_FILE_CHUNK_SIZE = 64 * 1024 // 大文件使用64KB块
        private const val LARGE_FILE_THRESHOLD = 50 * 1024 * 1024L // 50MB算作大文件
        private const val MEMORY_PRESSURE_THRESHOLD = 0.8 // 内存压力阈值80%
        
        @Volatile
        private var INSTANCE: CosAttachmentManager? = null
        
        fun getInstance(context: Context): CosAttachmentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosAttachmentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val cosMessageService = CosMessageService.getInstance(context)
    // 优化线程池：增加线程数以支持并发附件处理
    private val attachmentExecutor = Executors.newFixedThreadPool(
        maxOf(3, Runtime.getRuntime().availableProcessors())
    )
    // 专用于大文件处理的单线程池，避免内存压力
    private val largeFileExecutor = Executors.newSingleThreadExecutor()
    
    // 安全的私有临时目录
    private val secureAttachmentDir: File by lazy {
        val dir = File(context.getDir("secure_attachments", Context.MODE_PRIVATE), "temp")
        if (!dir.exists()) {
            dir.mkdirs()
            // 设置目录权限：只有当前应用可以访问
            dir.setReadable(false, false)  // 其他应用不可读
            dir.setWritable(false, false)  // 其他应用不可写
            dir.setExecutable(false, false) // 其他应用不可执行
            dir.setReadable(true, true)    // 仅当前应用可读
            dir.setWritable(true, true)    // 仅当前应用可写
            dir.setExecutable(true, true)  // 仅当前应用可执行
        }
        dir
    }
    
    init {
        // 启动定期清理任务
        scheduleCleanup()
        // 启动时立即清理一次旧文件
        cleanupTempFiles()
    }
    
    /**
     * 准备附件用于COS发送
     * 
     * @param attachments 附件列表
     * @return 准备好的附件文件和信息
     */
    fun prepareAttachmentsForCos(attachments: List<DatabaseAttachment>): CompletableFuture<List<CosAttachmentData>> {
        Log.i(TAG, "准备附件用于COS发送: count=${attachments.size}")
        
        return CompletableFuture.supplyAsync({
            val attachmentDataList = mutableListOf<CosAttachmentData>()
            
            // 根据内存情况动态调整批次大小
            val initialBatchSize = if (isMemoryUnderPressure()) 1 else 3
            val hasLargeFiles = attachments.any { it.size > LARGE_FILE_THRESHOLD }
            val batchSize = if (hasLargeFiles) 1 else initialBatchSize // 如果有大文件，串行处理
            
            Log.d(TAG, "动态批次大小: batchSize=$batchSize, hasLargeFiles=$hasLargeFiles, memoryUsage=${getMemoryUsageRatio()}")
            
            // 分批处理附件，避免大量附件同时加载到内存
            attachments.chunked(batchSize).forEach { batch ->
                Log.d(TAG, "处理附件批次: batchSize=${batch.size}")
                
                // 检查内存状况，如果压力大就串行处理
                if (isMemoryUnderPressure() || hasLargeFiles) {
                    // 串行处理以减少内存压力
                    batch.forEach { attachment ->
                        try {
                            val attachmentData = prepareAttachment(attachment)
                            if (attachmentData != null) {
                                attachmentDataList.add(attachmentData)
                                Log.d(TAG, "附件准备成功: attachmentId=${attachment.attachmentId.id}")
                            } else {
                                Log.w(TAG, "附件准备失败: attachmentId=${attachment.attachmentId.id}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "准备附件异常: attachmentId=${attachment.attachmentId.id}", e)
                        }
                        
                        // 每个附件处理完成后检查内存
                        forceGarbageCollection()
                    }
                } else {
                    // 并行处理当前批次
                    val futures = batch.map { attachment ->
                        CompletableFuture.supplyAsync({
                            try {
                                val attachmentData = prepareAttachment(attachment)
                                if (attachmentData != null) {
                                    Log.d(TAG, "附件准备成功: attachmentId=${attachment.attachmentId.id}")
                                    attachmentData
                                } else {
                                    Log.w(TAG, "附件准备失败: attachmentId=${attachment.attachmentId.id}")
                                    null
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "准备附件异常: attachmentId=${attachment.attachmentId.id}", e)
                                null
                            }
                        }, attachmentExecutor)
                    }
                    
                    // 等待当前批次完成
                    futures.forEach { future ->
                        try {
                            val result = future.get()
                            if (result != null) {
                                attachmentDataList.add(result)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "获取附件准备结果异常", e)
                        }
                    }
                }
                
                // 批次间稍作休息，让GC有机会回收内存
                forceGarbageCollection()
                try {
                    Thread.sleep(if (hasLargeFiles) 500 else 100) // 大文件需要更多休息时间
                } catch (ignored: InterruptedException) {}
            }
            
            Log.i(TAG, "附件准备完成: prepared=${attachmentDataList.size}, total=${attachments.size}")
            attachmentDataList
        }, attachmentExecutor)
    }
    
    /**
     * 上传附件到COS
     * 
     * @param recipientId 接收方ID
     * @param attachmentData 附件数据
     * @return 上传结果
     */
    fun uploadAttachmentToCos(
        recipientId: String,
        attachmentData: CosAttachmentData
    ): CompletableFuture<CosAttachmentUploadResult> {
        Log.i(TAG, "上传附件到COS: recipientId=$recipientId, attachmentId=${attachmentData.attachmentInfo.attachmentId}, size=${attachmentData.encryptedFile.length()}")
        
        // 根据文件大小选择不同的执行器
        val executor = if (attachmentData.encryptedFile.length() > 10 * 1024 * 1024) { // 10MB以上使用大文件处理器
            Log.d(TAG, "使用大文件处理器: size=${attachmentData.encryptedFile.length()}")
            largeFileExecutor
        } else {
            attachmentExecutor
        }
        
        return CompletableFuture.supplyAsync({
            try {
                // 1. 验证附件大小
                if (attachmentData.encryptedFile.length() > MAX_ATTACHMENT_SIZE) {
                    return@supplyAsync CosAttachmentUploadResult.Failure("附件过大: ${attachmentData.encryptedFile.length()}")
                }
                
                // 2. 获取COS路径 - 优先使用附件信息中的cosPath
                val cosPath = if (!attachmentData.attachmentInfo.cosPath.isNullOrEmpty()) {
                    Log.d(TAG, "使用附件信息中的路径: ${attachmentData.attachmentInfo.cosPath}")
                    attachmentData.attachmentInfo.cosPath!!
                } else {
                    Log.w(TAG, "附件信息中缺少cosPath，回退到生成路径: attachmentId=${attachmentData.attachmentInfo.attachmentId}")
                    generateAttachmentPath(attachmentData.attachmentInfo)
                }
                
                // 3. 上传到COS
                val uploadResult = cosMessageService.uploadAttachment(
                    recipientId = recipientId,
                    attachmentFile = attachmentData.encryptedFile,
                    cosPath = cosPath
                ).get()
                
                when (uploadResult) {
                    is CosUploadResult.Success -> {
                        Log.i(TAG, "附件上传成功: path=${uploadResult.attachmentPath}")
                        CosAttachmentUploadResult.Success(
                            cosPath = uploadResult.attachmentPath ?: cosPath,
                            attachmentInfo = attachmentData.attachmentInfo,
                            encryptionKey = attachmentData.encryptionKey
                        )
                    }
                    is CosUploadResult.Failure -> {
                        Log.e(TAG, "附件上传失败: error=${uploadResult.error}")
                        CosAttachmentUploadResult.Failure(uploadResult.error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传附件异常", e)
                CosAttachmentUploadResult.Failure(e.message ?: "上传异常")
            }
        }, executor)
    }
    
    /**
     * 从COS下载附件
     * 
     * @param recipientId 发送方ID
     * @param attachmentInfo 附件信息
     * @param encryptionKey 加密密钥
     * @return 下载结果
     */
    fun downloadAttachmentFromCos(
        recipientId: String,
        attachmentInfo: AttachmentInfo,
        encryptionKey: ByteArray
    ): CompletableFuture<CosAttachmentDownloadResult> {
        Log.i(TAG, "从COS下载附件: recipientId=$recipientId, attachmentId=${attachmentInfo.attachmentId}, size=${attachmentInfo.size}")
        
        // 根据文件大小选择不同的执行器
        val executor = if (attachmentInfo.size > 10 * 1024 * 1024) { // 10MB以上使用大文件处理器
            Log.d(TAG, "使用大文件处理器下载: size=${attachmentInfo.size}")
            largeFileExecutor
        } else {
            attachmentExecutor
        }
        
        return CompletableFuture.supplyAsync({
            try {
                // 1. 获取COS路径 - 优先使用消息中的cosPath，确保路径一致性
                val cosPath = if (!attachmentInfo.cosPath.isNullOrEmpty()) {
                    Log.d(TAG, "使用消息中的附件路径: ${attachmentInfo.cosPath}")
                    attachmentInfo.cosPath!!
                } else {
                    Log.w(TAG, "消息中缺少cosPath，回退到生成路径: attachmentId=${attachmentInfo.attachmentId}")
                    generateAttachmentPath(attachmentInfo)
                }
                
                // 2. 从COS下载
                val downloadResult = cosMessageService.downloadAttachment(
                    recipientId = recipientId,
                    cosPath = cosPath
                ).get()
                
                when (downloadResult) {
                    is CosDownloadResult.Success -> {
                        // 3. 解密附件
                        val decryptedFile = decryptAttachment(downloadResult.attachmentFile!!, encryptionKey)
                        if (decryptedFile != null) {
                            // 4. 验证文件完整性
                            if (verifyFileIntegrity(decryptedFile, attachmentInfo.fileHash)) {
                                Log.i(TAG, "附件下载解密成功，完整性验证通过: size=${decryptedFile.length()}")
                                CosAttachmentDownloadResult.Success(decryptedFile, attachmentInfo)
                            } else {
                                Log.e(TAG, "附件完整性验证失败")
                                // 删除不完整的文件
                                decryptedFile.delete()
                                CosAttachmentDownloadResult.Failure("附件完整性验证失败")
                            }
                        } else {
                            Log.e(TAG, "附件解密失败")
                            CosAttachmentDownloadResult.Failure("附件解密失败")
                        }
                    }
                    is CosDownloadResult.Failure -> {
                        Log.e(TAG, "附件下载失败: error=${downloadResult.error}")
                        CosAttachmentDownloadResult.Failure(downloadResult.error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "下载附件异常", e)
                CosAttachmentDownloadResult.Failure(e.message ?: "下载异常")
            }
        }, executor)
    }
    
    /**
     * 准备单个附件
     */
    private fun prepareAttachment(attachment: DatabaseAttachment): CosAttachmentData? {
        return try {
            // 1. 获取附件文件
            val attachmentFile = getAttachmentFile(attachment) ?: return null
            
            // 2. 计算原始文件哈希用于完整性验证
            val originalFileHash = calculateFileHash(attachmentFile)
            
            // 3. 创建附件信息
            val attachmentInfo = AttachmentInfo(
                fileName = attachment.fileName ?: "attachment",
                mimeType = attachment.contentType ?: "application/octet-stream",
                size = attachment.size,
                attachmentId = CosMessage.generateMessageId(),
                fileHash = originalFileHash
            )
            
            // 4. 生成加密密钥
            val encryptionKey = generateSecureRandom(ATTACHMENT_KEY_SIZE)
            
            // 5. 加密附件
            val encryptedFile = encryptAttachment(attachmentFile, encryptionKey)
            if (encryptedFile == null) {
                Log.e(TAG, "附件加密失败: attachmentId=${attachment.attachmentId}")
                return null
            }
            
            CosAttachmentData(
                attachmentInfo = attachmentInfo,
                encryptedFile = encryptedFile,
                encryptionKey = encryptionKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "准备附件失败: attachmentId=${attachment.attachmentId.id}", e)
            null
        }
    }
    
    /**
     * 获取附件文件
     */
    private fun getAttachmentFile(attachment: DatabaseAttachment): File? {
        return try {
            val attachmentUri = attachment.uri
            if (attachmentUri != null) {
                val inputStream = PartAuthority.getAttachmentStream(context, attachmentUri)
                val tempFile = createSecureTempFile("attachment_", ".tmp")
                
                // 使用安全的缓冲区处理，处理完后清零内存
                val buffer = ByteArray(CHUNK_SIZE)
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                
                // 清零缓冲区内存
                secureZeroMemory(buffer)
                
                tempFile
            } else {
                Log.w(TAG, "附件没有数据URI: attachmentId=${attachment.attachmentId.id}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取附件文件失败: attachmentId=${attachment.attachmentId.id}", e)
            null
        }
    }
    
    /**
     * 加密附件
     */
    private fun encryptAttachment(file: File, key: ByteArray): File? {
        return try {
            val iv = generateSecureRandom(ATTACHMENT_IV_SIZE)
            val cipher = Cipher.getInstance(ATTACHMENT_ENCRYPTION_ALGORITHM)
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            
            val encryptedFile = createSecureTempFile("encrypted_attachment_", ".tmp")
            
            // 根据文件大小选择块大小
            val chunkSize = if (file.length() > 10 * 1024 * 1024) {
                Log.d(TAG, "使用大文件块加密: fileSize=${file.length()}, chunkSize=$LARGE_FILE_CHUNK_SIZE")
                LARGE_FILE_CHUNK_SIZE
            } else {
                CHUNK_SIZE
            }
            
            FileInputStream(file).use { input ->
                FileOutputStream(encryptedFile).use { output ->
                    // 写入IV
                    output.write(iv)
                    
                    // 加密并写入数据
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val encryptedChunk = if (bytesRead == chunkSize) {
                            cipher.update(buffer)
                        } else {
                            cipher.update(buffer, 0, bytesRead)
                        }
                        
                        if (encryptedChunk != null) {
                            output.write(encryptedChunk)
                        }
                    }
                    
                    // 写入最终块
                    val finalChunk = cipher.doFinal()
                    output.write(finalChunk)
                }
            }
            
            Log.i(TAG, "附件加密完成: originalSize=${file.length()}, encryptedSize=${encryptedFile.length()}")
            
            // 如果原文件是临时文件，加密完成后删除原文件
            if (file.name.startsWith("attachment_") && file.parentFile == context.cacheDir) {
                if (file.delete()) {
                    Log.d(TAG, "已删除原始临时文件: ${file.name}")
                }
            }
            
            encryptedFile
        } catch (e: Exception) {
            Log.e(TAG, "加密附件失败", e)
            null
        }
    }
    
    /**
     * 解密附件
     */
    private fun decryptAttachment(encryptedFile: File, key: ByteArray): File? {
        return try {
            val decryptedFile = createSecureTempFile("decrypted_attachment_", ".tmp")
            
            // 根据文件大小选择块大小
            val chunkSize = if (encryptedFile.length() > 10 * 1024 * 1024) {
                Log.d(TAG, "使用大文件块解密: fileSize=${encryptedFile.length()}, chunkSize=$LARGE_FILE_CHUNK_SIZE")
                LARGE_FILE_CHUNK_SIZE
            } else {
                CHUNK_SIZE
            }
            
            FileInputStream(encryptedFile).use { input ->
                // 读取IV
                val iv = ByteArray(ATTACHMENT_IV_SIZE)
                input.read(iv)
                
                val cipher = Cipher.getInstance(ATTACHMENT_ENCRYPTION_ALGORITHM)
                val secretKey = SecretKeySpec(key, "AES")
                val ivSpec = IvParameterSpec(iv)
                
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                
                FileOutputStream(decryptedFile).use { output ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val decryptedChunk = if (bytesRead == chunkSize) {
                            cipher.update(buffer)
                        } else {
                            cipher.update(buffer, 0, bytesRead)
                        }
                        
                        if (decryptedChunk != null) {
                            output.write(decryptedChunk)
                        }
                    }
                    
                    // 写入最终块
                    val finalChunk = cipher.doFinal()
                    output.write(finalChunk)
                }
            }
            
            Log.i(TAG, "附件解密完成: encryptedSize=${encryptedFile.length()}, decryptedSize=${decryptedFile.length()}")
            
            // 解密完成后删除加密文件（如果是临时下载的）
            if (encryptedFile.name.startsWith("encrypted_") && encryptedFile.parentFile == context.cacheDir) {
                if (encryptedFile.delete()) {
                    Log.d(TAG, "已删除加密临时文件: ${encryptedFile.name}")
                }
            }
            
            decryptedFile
        } catch (e: Exception) {
            Log.e(TAG, "解密附件失败", e)
            null
        }
    }
    
    /**
     * 生成附件COS路径
     */
    private fun generateAttachmentPath(attachmentInfo: AttachmentInfo): String {
        val timestamp = System.currentTimeMillis()
        val extension = getFileExtension(attachmentInfo.fileName)
        return "attachments/${timestamp}_${attachmentInfo.attachmentId}$extension"
    }
    
    /**
     * 获取文件扩展名
     */
    private fun getFileExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < fileName.length - 1) {
            fileName.substring(lastDot)
        } else {
            ""
        }
    }

    /**
     * 生成安全随机字节
     */
    private fun generateSecureRandom(size: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }
    
    /**
     * 清理临时文件
     * 清理超过指定时间的临时附件文件
     */
    fun cleanupTempFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000L) { // 默认24小时
        try {
            Log.i(TAG, "开始清理临时附件文件: maxAgeMillis=$maxAgeMillis")
            
            val currentTime = System.currentTimeMillis()
            var deletedCount = 0
            var totalSize = 0L
            
            // 清理安全目录中的临时文件
            val secureFiles = secureAttachmentDir.listFiles { file ->
                file.name.startsWith("attachment_") || 
                file.name.startsWith("encrypted_attachment_") || 
                file.name.startsWith("decrypted_attachment_") ||
                file.name.startsWith("attachment_package_")
            }
            
            // 清理cache目录中的旧临时文件（向后兼容清理）
            val cacheFiles = context.cacheDir.listFiles { file ->
                file.name.startsWith("attachment_") || 
                file.name.startsWith("encrypted_attachment_") || 
                file.name.startsWith("decrypted_attachment_") ||
                file.name.startsWith("attachment_package_")
            }
            
            // 清理安全目录中的文件
            secureFiles?.forEach { file ->
                if (currentTime - file.lastModified() > maxAgeMillis) {
                    val size = file.length()
                    if (file.delete()) {
                        deletedCount++
                        totalSize += size
                        Log.d(TAG, "删除安全临时文件: ${file.name}, size=$size")
                    } else {
                        Log.w(TAG, "无法删除安全临时文件: ${file.name}")
                    }
                }
            }
            
            // 清理cache目录中的旧文件
            cacheFiles?.forEach { file ->
                if (currentTime - file.lastModified() > maxAgeMillis) {
                    val size = file.length()
                    if (file.delete()) {
                        deletedCount++
                        totalSize += size
                        Log.d(TAG, "删除旧临时文件: ${file.name}, size=$size")
                    } else {
                        Log.w(TAG, "无法删除旧临时文件: ${file.name}")
                    }
                }
            }
            
            // 清理cos_temp目录中的文件
            val attachmentDir = File(context.getDir("attachments", Context.MODE_PRIVATE), "cos_temp")
            if (attachmentDir.exists()) {
                val tempFiles = attachmentDir.listFiles()
                tempFiles?.forEach { file ->
                    if (currentTime - file.lastModified() > maxAgeMillis) {
                        val size = file.length()
                        if (file.delete()) {
                            deletedCount++
                            totalSize += size
                            Log.d(TAG, "删除COS临时文件: ${file.name}, size=$size")
                        } else {
                            Log.w(TAG, "无法删除COS临时文件: ${file.name}")
                        }
                    }
                }
            }
            
            Log.i(TAG, "临时文件清理完成: deletedCount=$deletedCount, totalSize=${formatFileSize(totalSize)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理临时文件时发生异常", e)
        }
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> String.format("%.1fKB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1fMB", size / (1024.0 * 1024))
            else -> String.format("%.1fGB", size / (1024.0 * 1024 * 1024))
        }
    }
    
    /**
     * 定期清理任务
     * 根据内存压力动态调整清理频率
     */
    fun scheduleCleanup() {
        try {
            // 在后台线程定期清理
            Thread {
                while (true) {
                    try {
                        // 根据内存使用情况动态调整清理间隔
                        val memoryUsage = getMemoryUsageRatio()
                        val cleanupInterval = when {
                            memoryUsage > 0.9 -> 5 * 60 * 1000L      // 内存使用超过90%，5分钟清理一次
                            memoryUsage > 0.8 -> 15 * 60 * 1000L     // 内存使用超过80%，15分钟清理一次
                            memoryUsage > 0.6 -> 30 * 60 * 1000L     // 内存使用超过60%，30分钟清理一次
                            else -> 60 * 60 * 1000L                  // 正常情况，1小时清理一次
                        }
                        
                        Thread.sleep(cleanupInterval)
                        
                        // 清理临时文件，根据内存压力调整清理策略
                        val maxAge = if (memoryUsage > 0.8) {
                            10 * 60 * 1000L // 内存压力大时，保留时间缩短为10分钟
                        } else {
                            24 * 60 * 60 * 1000L // 正常情况下保留24小时
                        }
                        
                        cleanupTempFiles(maxAge)
                        
                        // 如果内存压力大，额外执行垃圾回收
                        if (memoryUsage > 0.8) {
                            Log.i(TAG, "内存压力较大，执行额外垃圾回收: memoryUsage=${memoryUsage}")
                            System.gc()
                            System.runFinalization()
                        }
                        
                    } catch (e: InterruptedException) {
                        Log.i(TAG, "清理任务被中断")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "定期清理任务异常", e)
                    }
                }
            }.apply {
                isDaemon = true
                name = "cos-attachment-cleanup"
                start()
            }
            
            Log.i(TAG, "已启动动态清理任务")
        } catch (e: Exception) {
            Log.e(TAG, "启动定期清理任务失败", e)
        }
    }

    /**
     * 安全创建临时文件
     * 使用私有目录并设置正确权限
     */
    private fun createSecureTempFile(prefix: String, suffix: String = ".tmp"): File {
        val tempFile = File.createTempFile(prefix, suffix, secureAttachmentDir)
        
        // 设置文件权限：只有当前应用可以访问
        tempFile.setReadable(false, false)  // 其他应用不可读
        tempFile.setWritable(false, false)  // 其他应用不可写
        tempFile.setReadable(true, true)    // 仅当前应用可读
        tempFile.setWritable(true, true)    // 仅当前应用可写
        
        return tempFile
    }
    
    /**
     * 安全清零内存数据
     * 防止敏感数据在内存中残留
     */
    private fun secureZeroMemory(data: ByteArray) {
        for (i in data.indices) {
            data[i] = 0
        }
    }
    
    /**
     * 安全清零内存数据
     * 防止敏感数据在内存中残留
     */
    private fun secureZeroMemory(data: CharArray) {
        for (i in data.indices) {
            data[i] = '\u0000'
        }
    }
    
    /**
     * 计算文件的SHA-256哈希值
     * 用于完整性验证
     */
    private fun calculateFileHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            
            FileInputStream(file).use { inputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            
            val hashBytes = digest.digest()
            // 清零缓冲区
            secureZeroMemory(buffer)
            
            // 转换为十六进制字符串
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "计算文件哈希失败", e)
            null
        }
    }
    
    /**
     * 验证文件完整性
     * 
     * @param file 要验证的文件
     * @param expectedHash 期望的哈希值
     * @return 验证是否通过
     */
    private fun verifyFileIntegrity(file: File, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrEmpty()) {
            Log.w(TAG, "没有提供期望的哈希值，跳过完整性验证")
            return true
        }
        
        val actualHash = calculateFileHash(file)
        if (actualHash == null) {
            Log.e(TAG, "无法计算文件哈希，完整性验证失败")
            return false
        }
        
        val isValid = actualHash.equals(expectedHash, ignoreCase = true)
        if (!isValid) {
            Log.e(TAG, "文件完整性验证失败: expected=$expectedHash, actual=$actualHash")
        } else {
            Log.d(TAG, "文件完整性验证通过: hash=$actualHash")
        }
        
        return isValid
    }
     
     /**
      * 检查当前内存使用情况
      * @return 内存使用比例（0.0-1.0）
      */
     private fun getMemoryUsageRatio(): Double {
         val runtime = Runtime.getRuntime()
         val maxMemory = runtime.maxMemory()
         val totalMemory = runtime.totalMemory()
         val freeMemory = runtime.freeMemory()
         val usedMemory = totalMemory - freeMemory
         
         return usedMemory.toDouble() / maxMemory.toDouble()
     }
     
     /**
      * 检查是否处于内存压力状态
      */
     private fun isMemoryUnderPressure(): Boolean {
         return getMemoryUsageRatio() > MEMORY_PRESSURE_THRESHOLD
     }
     
     /**
      * 强制垃圾回收以缓解内存压力
      */
     private fun forceGarbageCollection() {
         if (isMemoryUnderPressure()) {
             Log.w(TAG, "检测到内存压力，执行垃圾回收")
             System.gc()
             System.runFinalization()
             
             // 等待一小段时间让GC完成
             try {
                 Thread.sleep(100)
             } catch (ignored: InterruptedException) {}
         }
     }
     
     /**
      * 检查文件是否为大文件
      */
     private fun isLargeFile(file: File): Boolean {
         return file.length() > LARGE_FILE_THRESHOLD
     }
     
     /**
      * 流式处理大文件，避免一次性加载到内存
      */
     private fun processLargeFileInChunks(
         inputFile: File,
         outputFile: File,
         processor: (ByteArray, Int) -> ByteArray
     ): Boolean {
         return try {
             val chunkSize = if (isLargeFile(inputFile)) LARGE_FILE_CHUNK_SIZE else CHUNK_SIZE
             val buffer = ByteArray(chunkSize)
             
             FileInputStream(inputFile).use { input ->
                 FileOutputStream(outputFile).use { output ->
                     var bytesRead: Int
                     var chunkCount = 0
                     
                     while (input.read(buffer).also { bytesRead = it } != -1) {
                         // 处理数据块
                         val processedData = processor(buffer, bytesRead)
                         output.write(processedData)
                         
                         chunkCount++
                         
                         // 每处理一定数量的块后检查内存并可能触发GC
                         if (chunkCount % 10 == 0) {
                             forceGarbageCollection()
                         }
                     }
                 }
             }
             
             // 处理完成后清零缓冲区
             secureZeroMemory(buffer)
             true
             
         } catch (e: Exception) {
             Log.e(TAG, "流式处理大文件失败", e)
             false
         }
     }
}

/**
 * COS附件数据
 */
data class CosAttachmentData(
    val attachmentInfo: AttachmentInfo,
    val encryptedFile: File,
    val encryptionKey: ByteArray
)

/**
 * COS附件上传结果
 */
sealed class CosAttachmentUploadResult {
    data class Success(
        val cosPath: String,
        val attachmentInfo: AttachmentInfo,
        val encryptionKey: ByteArray
    ) : CosAttachmentUploadResult()
    
    data class Failure(val error: String) : CosAttachmentUploadResult()
}

/**
 * COS附件下载结果
 */
sealed class CosAttachmentDownloadResult {
    data class Success(val file: File, val attachmentInfo: AttachmentInfo) : CosAttachmentDownloadResult()
    data class Failure(val error: String) : CosAttachmentDownloadResult()
}
