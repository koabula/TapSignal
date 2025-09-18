package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.*
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.Preview
import org.whispersystems.signalservice.internal.push.BodyRange
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.contactshare.Contact
import org.signal.core.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * COS消息发送管理器
 * 负责将Signal消息通过COS发送，包括Double Ratchet加密和COS上传
 */
class CosMessageSendManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosMessageSendManager::class.java)
        
        @Volatile
        private var INSTANCE: CosMessageSendManager? = null
        
        fun getInstance(context: Context): CosMessageSendManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosMessageSendManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val cosChannelManager = CosChannelManager.getInstance(context)
    private val cosMessageService = CosMessageService.getInstance(context)

    private val sendExecutor = Executors.newFixedThreadPool(2)
    
    // 同步锁，防止并发加密时的Ratchet状态竞态条件
    private val encryptionLocks = ConcurrentHashMap<String, ReentrantLock>()
    
    // 安全的私有临时目录
    private val secureAttachmentDir: File by lazy {
        val dir = File(context.getDir("secure_attachments", Context.MODE_PRIVATE), "send_temp")
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
        val tempFile = File.createTempFile(prefix, suffix, secureAttachmentDir)
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        return tempFile
    }
    
    /**
     * 检查是否应该使用COS发送消息
     * @param recipientId 接收方ID
     * @return 是否使用COS发送
     */
    fun shouldUseCosForSending(recipientId: RecipientId): Boolean {
        return try {
            val channel = cosChannelManager.getChannel(recipientId.toString())
            Log.d(TAG, "检查COS通道状态: recipientId=$recipientId, channel=$channel")

            if (channel == null) {
                Log.d(TAG, "通道不存在: recipientId=$recipientId")
                return false
            }

            Log.d(TAG, "通道状态: status=${channel.status}, myAccessInfo=${channel.myAccessInfo != null}, theirAccessInfo=${channel.theirAccessInfo != null}")

            val isActive = channel.status == ChannelStatus.ACTIVE
            val hasMyAccess = channel.myAccessInfo != null
            val hasTheirAccess = channel.theirAccessInfo != null
            val notExpired = channel.theirAccessInfo?.isExpired() == false

            val canSend = isActive && hasMyAccess && hasTheirAccess && notExpired

            Log.d(TAG, "COS发送检查结果: recipientId=$recipientId, isActive=$isActive, hasMyAccess=$hasMyAccess, hasTheirAccess=$hasTheirAccess, notExpired=$notExpired, canSend=$canSend")

            canSend
        } catch (e: Exception) {
            Log.w(TAG, "检查COS通道状态失败: recipientId=$recipientId", e)
            false
        }
    }
    
    /**
     * 通过COS发送消息
     * @param messageId 消息ID
     * @param recipient 接收方
     * @param outgoingMessage 待发送消息
     * @return 发送结果的Future
     */
    fun sendMessageViaCos(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage
    ): CompletableFuture<CosSendResult> {
        Log.i(TAG, "开始通过COS发送消息: messageId=$messageId, recipient=${recipient.id}")
        
        return CompletableFuture.supplyAsync({
            try {
                // 1. 验证COS通道状态
                val channel = cosChannelManager.getChannel(recipient.id.toString())
                if (channel?.status != ChannelStatus.ACTIVE || channel.theirAccessInfo == null) {
                    return@supplyAsync CosSendResult.Failure("COS通道未激活或缺少访问信息")
                }
                
                // 2. 处理附件并获取密钥
                val attachmentPreparationResult = prepareAttachmentsWithKeys(outgoingMessage)
                
                // 3. 加密消息内容（包含附件密钥）
                val encryptedData = encryptMessageWithDoubleRatchet(recipient, outgoingMessage, attachmentPreparationResult?.attachmentKey)
                if (encryptedData == null) {
                    return@supplyAsync CosSendResult.Failure("消息加密失败")
                }
                
                // 4. 创建COS消息
                val cosMessage = createCosMessage(
                    messageId = messageId,
                    recipient = recipient,
                    outgoingMessage = outgoingMessage,
                    encryptedData = encryptedData
                )
                
                // 5. 上传到COS
                val uploadResult = cosMessageService.uploadMessage(
                    recipientId = recipient.id.toString(),
                    message = cosMessage,
                    attachmentFile = attachmentPreparationResult?.attachmentFile
                ).get()

                when (uploadResult) {
                    is CosUploadResult.Success -> {
                        Log.i(TAG, "COS消息发送成功: messageId=$messageId, path=${uploadResult.messagePath}")

                        // 7. 更新通道活动时间
                        cosChannelManager.updateChannelActivity(recipient.id.toString())
                        
                        CosSendResult.Success(uploadResult.messagePath, uploadResult.attachmentPath)
                    }
                    is CosUploadResult.Failure -> {
                        Log.e(TAG, "COS消息上传失败: messageId=$messageId, error=${uploadResult.error}")
                        CosSendResult.Failure(uploadResult.error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "COS消息发送异常: messageId=$messageId", e)
                CosSendResult.Failure(e.message ?: "未知错误")
            }
        }, sendExecutor)
    }
    
    /**
     * 使用Double Ratchet加密消息内容
     * 修复并发加密时的Ratchet状态竞态条件
     */
    private fun encryptMessageWithDoubleRatchet(
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        attachmentKey: ByteArray? = null
    ): EncryptedMessageData? {
        return try {
            // 获取Signal协议地址 - 使用真实的设备ID
            val signalServiceAddress = SignalServiceAddress(recipient.requireServiceId())
            val deviceId = getRecipientDeviceId(recipient)
            val protocolAddress = SignalProtocolAddress(signalServiceAddress.identifier, deviceId)
            
            // 创建加密锁key，确保同一接收者的加密操作串行化
            val lockKey = "${signalServiceAddress.identifier}-$deviceId"
            val encryptionLock = encryptionLocks.computeIfAbsent(lockKey) { ReentrantLock() }
            
            // 使用带超时的锁，防止长时间阻塞
            val lockAcquired = try {
                encryptionLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.w(TAG, "获取加密锁被中断: recipient=${recipient.id}, lockKey=$lockKey", e)
                Thread.currentThread().interrupt()
                false
            }
            
            if (!lockAcquired) {
                Log.e(TAG, "获取加密锁超时: recipient=${recipient.id}, lockKey=$lockKey")
                throw IllegalStateException("加密锁获取超时，可能存在死锁")
            }
            
            try {
                Log.d(TAG, "🔒 获取加密锁: recipient=${recipient.id}, lockKey=$lockKey")
                
                // 创建消息内容
                val messageContent = createMessageContent(outgoingMessage)
                val contentBytes = messageContent.encode()
                
                // 如果有附件密钥，将其添加到消息内容前面
                val finalContentBytes = if (attachmentKey != null) {
                    // 格式：[32字节附件密钥][消息内容]
                    ByteArray(32 + contentBytes.size).apply {
                        System.arraycopy(attachmentKey, 0, this, 0, 32)
                        System.arraycopy(contentBytes, 0, this, 32, contentBytes.size)
                    }
                } else {
                    contentBytes
                }

                // 使用Signal的加密机制
                val protocolStore = AppDependencies.protocolStore.aci()
                val sessionCipher = SessionCipher(protocolStore, protocolAddress)
                
                // 记录加密前的Session状态（暂时简化，避免复杂的Session状态获取）
                val sessionRecord = protocolStore.loadSession(protocolAddress)
                val previousCounter = if (sessionRecord.hasSenderChain()) {
                    try {
                        // 尝试获取当前会话的消息计数器
                        // 注意：这里简化处理，实际的Session状态获取较为复杂
                        Log.d(TAG, "检测到活跃的Sender链")
                        -1 // 简化处理，暂不获取具体计数器
                    } catch (e: Exception) {
                        Log.w(TAG, "无法获取前序计数器", e)
                        -1
                    }
                } else {
                    Log.d(TAG, "未检测到活跃的Sender链")
                    -1
                }
                
                Log.d(TAG, "🔐 加密前状态: recipient=${recipient.id}, previousCounter=$previousCounter")
                
                // 执行加密操作
                val ciphertext = sessionCipher.encrypt(finalContentBytes)
                
                Log.d(TAG, "🔐 加密完成: recipient=${recipient.id}, messageType=${ciphertext.type}")

                Log.d(TAG, "📦 加密完成，序列化Signal密文")

                EncryptedMessageData(
                    signalCiphertext = ciphertext.serialize(),
                    signalCiphertextType = ciphertext.type
                )
            } finally {
                encryptionLock.unlock()
                Log.d(TAG, "🔓 释放加密锁: recipient=${recipient.id}, lockKey=$lockKey")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Double Ratchet加密失败", e)
            null
        }
    }

    /**
     * 获取接收方的设备ID
     */
    private fun getRecipientDeviceId(recipient: Recipient): Int {
        return try {
            // 从Signal数据库获取设备ID，默认为1
            val sessions = AppDependencies.protocolStore.aci().getSubDeviceSessions(recipient.requireServiceId().toString())
            if (sessions.isNotEmpty()) {
                sessions.first()
            } else {
                SignalServiceAddress.DEFAULT_DEVICE_ID
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取设备ID失败，使用默认值", e)
            SignalServiceAddress.DEFAULT_DEVICE_ID
        }
    }
    

    

    
    /**
     * 创建Signal消息内容
     */
    private fun createMessageContent(outgoingMessage: OutgoingMessage): Content {
        val dataMessageBuilder = DataMessage.Builder()

        // 设置消息体
        if (outgoingMessage.body.isNotEmpty()) {
            dataMessageBuilder.body(outgoingMessage.body)
        }

        // 设置时间戳
        dataMessageBuilder.timestamp(outgoingMessage.sentTimeMillis)

        // 设置过期时间
        if (outgoingMessage.expiresIn > 0) {
            dataMessageBuilder.expireTimer((outgoingMessage.expiresIn / 1000).toInt()) // 转换为秒
        }

        // 设置阅后即焚
        if (outgoingMessage.isViewOnce) {
            dataMessageBuilder.isViewOnce(true)
        }

        // 处理附件
        if (outgoingMessage.attachments.isNotEmpty()) {
            val attachmentPointers = createAttachmentPointers(outgoingMessage.attachments)
            if (attachmentPointers.isNotEmpty()) {
                dataMessageBuilder.attachments(attachmentPointers)
            }
        }

        // 处理链接预览
        if (outgoingMessage.linkPreviews.isNotEmpty()) {
            val previews = createPreviews(outgoingMessage.linkPreviews)
            if (previews.isNotEmpty()) {
                dataMessageBuilder.preview(previews)
            }
        }

        // 处理联系人
        if (outgoingMessage.sharedContacts.isNotEmpty()) {
            // 暂时跳过联系人处理，因为Contact类型不匹配
            Log.d(TAG, "跳过联系人处理: ${outgoingMessage.sharedContacts.size}个联系人")
        }

        // 处理引用消息
        outgoingMessage.outgoingQuote?.let { quote ->
            dataMessageBuilder.quote(createQuote(quote))
        }

        // 处理提及
        if (outgoingMessage.mentions.isNotEmpty()) {
            val bodyRanges = createMentionBodyRanges(outgoingMessage.mentions)
            if (bodyRanges.isNotEmpty()) {
                dataMessageBuilder.bodyRanges(bodyRanges)
            }
        }

        // 处理样式范围
        outgoingMessage.bodyRanges?.let { bodyRangeList ->
            val ranges = createStyleBodyRanges(bodyRangeList)
            if (ranges.isNotEmpty()) {
                dataMessageBuilder.bodyRanges(ranges)
            }
        }

        return Content.Builder()
            .dataMessage(dataMessageBuilder.build())
            .build()
    }
    
    /**
     * 创建附件指针列表
     */
    private fun createAttachmentPointers(attachments: List<org.thoughtcrime.securesms.attachments.Attachment>): List<AttachmentPointer> {
        return attachments.mapNotNull { attachment ->
            try {
                if (attachment is DatabaseAttachment) {
                    // 对于COS发送，我们不需要真实的CDN指针，而是创建占位符
                    AttachmentPointer.Builder()
                        .contentType(attachment.contentType ?: "application/octet-stream")
                        .size(attachment.size.toInt())
                        .fileName(attachment.fileName)
                        .build()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "创建附件指针失败", e)
                null
            }
        }
    }

    /**
     * 创建链接预览列表
     */
    private fun createPreviews(linkPreviews: List<LinkPreview>): List<Preview> {
        return linkPreviews.mapNotNull { linkPreview ->
            try {
                val previewBuilder = Preview.Builder()
                    .url(linkPreview.url)
                    .title(linkPreview.title)
                    .description(linkPreview.description)
                    .date(linkPreview.date)

                // 处理预览图片 - 暂时跳过，避免类型问题
                if (linkPreview.thumbnail.isPresent) {
                    Log.d(TAG, "跳过链接预览图片处理")
                }

                previewBuilder.build()
            } catch (e: Exception) {
                Log.w(TAG, "创建链接预览失败", e)
                null
            }
        }
    }



    /**
     * 创建COS消息
     */
    private fun createCosMessage(
        messageId: Long,
        recipient: Recipient,
        outgoingMessage: OutgoingMessage,
        encryptedData: EncryptedMessageData
    ): CosMessage {
        // 🔧 修复：使用本地缓存的ACI，避免触发服务器连接
        val localAci = try {
            val aci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            Log.d(TAG, "成功获取本地ACI用于COS消息: ${aci.take(8)}...")
            aci
        } catch (e: Exception) {
            Log.e(TAG, "获取本地ACI失败，COS消息发送无法继续", e)
            throw IllegalStateException("无法获取本地ACI信息，请确保已正确注册", e)
        }

        Log.d(TAG, "🔧 使用原始Signal消息时间戳: sentTimeMillis=${outgoingMessage.sentTimeMillis}")

        return CosMessageUtils.createCosMessage(
            senderId = localAci,
            recipientId = recipient.requireServiceId().toString(),
            messageType = determineMessageType(outgoingMessage),
            signalCiphertext = encryptedData.signalCiphertext,
            signalCiphertextType = encryptedData.signalCiphertextType,
            attachmentInfo = createAttachmentInfo(outgoingMessage, recipient),
            timestamp = outgoingMessage.sentTimeMillis // 🔧 修复：使用原始Signal消息的时间戳
        )
    }

    /**
     * 创建引用消息
     */
    private fun createQuote(quote: QuoteModel): DataMessage.Quote {
        val quoteBuilder = DataMessage.Quote.Builder()
            .id(quote.id)
            .text(quote.text.toString())

        // 设置引用作者
        quote.author?.let { authorId ->
            val author = Recipient.resolved(authorId)
            quoteBuilder.authorAci(author.requireServiceId().toString())
        }

        // 处理引用附件
        quote.attachments.forEach { attachment ->
            val quotedAttachment = DataMessage.Quote.QuotedAttachment.Builder()
                .contentType(attachment.contentType ?: "application/octet-stream")
                .fileName(attachment.fileName)

            // 如果有缩略图 - 暂时跳过缩略图处理
            if (attachment.thumbnailUri != null) {
                Log.d(TAG, "跳过引用附件缩略图处理")
            }

            quoteBuilder.attachments(listOf(quotedAttachment.build()))
        }

        return quoteBuilder.build()
    }

    /**
     * 创建提及的BodyRange列表
     */
    private fun createMentionBodyRanges(mentions: List<Mention>): List<BodyRange> {
        return mentions.map { mention ->
            BodyRange.Builder()
                .start(mention.start)
                .length(mention.length)
                .mentionAci(mention.recipientId.serialize())
                .build()
        }
    }

    /**
     * 创建样式的BodyRange列表
     */
    private fun createStyleBodyRanges(bodyRangeList: BodyRangeList): List<BodyRange> {
        // 暂时返回空列表，避免复杂的BodyRange处理
        Log.d(TAG, "跳过BodyRange样式处理: ${bodyRangeList.ranges.size}个范围")
        return emptyList()
    }

    /**
     * 确定消息类型
     */
    private fun determineMessageType(outgoingMessage: OutgoingMessage): MessageType {
        return when {
            outgoingMessage.attachments.isNotEmpty() -> MessageType.ATTACHMENT
            outgoingMessage.linkPreviews.isNotEmpty() -> MessageType.TEXT // 带链接预览的文本
            outgoingMessage.sharedContacts.isNotEmpty() -> MessageType.TEXT // 带联系人的文本
            else -> MessageType.TEXT
        }
    }
    
    /**
     * 创建附件信息
     * 对于多附件，我们创建一个表示整个包的AttachmentInfo
     */
    private fun createAttachmentInfo(outgoingMessage: OutgoingMessage, recipient: Recipient): AttachmentInfo? {
        if (outgoingMessage.attachments.isEmpty()) return null
        
        // 获取通道目录信息以生成正确的cosPath
        val channelDirectory = try {
            val channel = cosChannelManager.getChannel(recipient.id.toString())
            extractChannelDirectoryFromAccessInfo(channel?.myAccessInfo)
        } catch (e: Exception) {
            Log.w(TAG, "获取通道目录失败，使用默认路径: recipientId=${recipient.id}", e)
            null
        }
        
        return if (outgoingMessage.attachments.size == 1) {
            // 单个附件
            val attachment = outgoingMessage.attachments.first()
            val attachmentId = CosMessage.generateMessageId()
            val cosPath = generateV2AttachmentPath(channelDirectory, attachmentId)
            
            AttachmentInfo(
                fileName = attachment.fileName ?: "attachment",
                mimeType = attachment.contentType ?: "application/octet-stream",
                size = attachment.size,
                attachmentId = attachmentId,
                cosPath = cosPath
            )
        } else {
            // 多个附件，创建ZIP包信息
            val totalSize = outgoingMessage.attachments.sumOf { it.size }
            val attachmentId = CosMessage.generateMessageId()
            val cosPath = generateV2AttachmentPath(channelDirectory, attachmentId)
            
            AttachmentInfo(
                fileName = "attachments_${System.currentTimeMillis()}.zip",
                mimeType = "application/zip",
                size = totalSize, // 估算大小，实际ZIP大小会稍有不同
                attachmentId = attachmentId,
                cosPath = cosPath
            )
        }
    }
    
    /**
     * 生成V2通道附件路径
     */
    private fun generateV2AttachmentPath(channelDirectory: String?, attachmentId: String): String {
        if (channelDirectory != null) {
            // 使用安全的随机后缀生成方式
            val randomSuffix = generateSecureRandomSuffix()
            val fileName = "${attachmentId}_${randomSuffix}.bin"
            return "/v2-channels/$channelDirectory/outbox/attachments/$fileName"
        } else {
            // 回退到传统路径格式
            Log.w(TAG, "通道目录为空，回退到传统附件路径格式")
            return "attachments/${System.currentTimeMillis()}_${attachmentId}.bin"
        }
    }
    
    /**
     * 生成安全的随机后缀
     */
    private fun generateSecureRandomSuffix(): String {
        val secureRandom = java.security.SecureRandom()
        val bytes = ByteArray(4)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 从CosAccessInfo中提取通道目录名
     */
    private fun extractChannelDirectoryFromAccessInfo(accessInfo: CosAccessInfo?): String? {
        if (accessInfo == null) return null
        
        val sharedDirectory = accessInfo.sharedDirectory
        if (sharedDirectory.isNullOrEmpty()) return null

        val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
        val matchResult = regex.find(sharedDirectory)
        return matchResult?.groupValues?.get(1)
    }
    
    /**
     * 准备附件并返回密钥和文件
     */
    private fun prepareAttachmentsWithKeys(outgoingMessage: OutgoingMessage): AttachmentPreparationResult? {
        return try {
            if (outgoingMessage.attachments.isEmpty()) {
                return null
            }

            // 使用CosAttachmentManager处理附件
            val attachmentManager = CosAttachmentManager.getInstance(context)
            // 转换Attachment到DatabaseAttachment
            val databaseAttachments: List<DatabaseAttachment> = outgoingMessage.attachments.mapNotNull { attachment ->
                if (attachment is DatabaseAttachment) attachment else null
            }
            val attachmentDataList = attachmentManager.prepareAttachmentsForCos(databaseAttachments).get()

            if (attachmentDataList.isEmpty()) {
                Log.w(TAG, "没有成功准备的附件")
                return null
            }

            // 获取第一个附件的密钥作为主密钥
            val attachmentKey = attachmentDataList.first().encryptionKey

            // 准备附件文件
            val attachmentFile = if (attachmentDataList.size == 1) {
                attachmentDataList[0].encryptedFile
            } else {
                createAttachmentPackage(attachmentDataList)
            }

            if (attachmentFile != null) {
                AttachmentPreparationResult(attachmentKey, attachmentFile)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "准备附件失败", e)
            null
        }
    }

    /**
     * 准备附件文件（保留原方法用于兼容性）
     */
    private fun prepareAttachments(outgoingMessage: OutgoingMessage): File? {
        return try {
            if (outgoingMessage.attachments.isEmpty()) {
                return null
            }

            // 使用CosAttachmentManager处理附件
            val attachmentManager = CosAttachmentManager.getInstance(context)
            // 转换Attachment到DatabaseAttachment
            val databaseAttachments: List<DatabaseAttachment> = outgoingMessage.attachments.mapNotNull { attachment ->
                if (attachment is DatabaseAttachment) attachment else null
            }
            val attachmentDataList = attachmentManager.prepareAttachmentsForCos(databaseAttachments).get()

            if (attachmentDataList.isEmpty()) {
                Log.w(TAG, "没有成功准备的附件")
                return null
            }

            // 如果只有一个附件，直接返回
            if (attachmentDataList.size == 1) {
                return attachmentDataList[0].encryptedFile
            }

            // 多个附件时，创建附件包
            createAttachmentPackage(attachmentDataList)
        } catch (e: Exception) {
            Log.e(TAG, "准备附件失败", e)
            null
        }
    }

    /**
     * 创建附件包（多个附件时）
     */
    private fun createAttachmentPackage(attachmentDataList: List<CosAttachmentData>): File? {
        return try {
            // 创建附件包文件
            val packageFile = createSecureTempFile("attachment_package_", ".zip")

            // 使用ZIP格式将多个附件打包
            ZipOutputStream(FileOutputStream(packageFile)).use { zipOut ->
                attachmentDataList.forEachIndexed { index, attachmentData ->
                    try {
                        // 保留原始文件名，确保唯一性
                        val originalFileName = attachmentData.attachmentInfo.fileName
                        val fileExtension = originalFileName.substringAfterLast('.', "")
                        val baseName = originalFileName.substringBeforeLast('.', originalFileName)
                        val uniqueFileName = if (index == 0) {
                            originalFileName
                        } else {
                            "${baseName}_$index.$fileExtension"
                        }
                        
                        // 创建ZIP条目，保留文件大小信息
                        val zipEntry = ZipEntry(uniqueFileName).apply {
                            size = attachmentData.encryptedFile.length()
                            time = System.currentTimeMillis()
                        }
                        zipOut.putNextEntry(zipEntry)

                        // 写入加密的附件数据
                        attachmentData.encryptedFile.inputStream().use { input ->
                            input.copyTo(zipOut)
                        }

                        zipOut.closeEntry()
                        Log.d(TAG, "已添加附件到ZIP包: $uniqueFileName (${attachmentData.encryptedFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "添加附件到ZIP包失败: ${attachmentData.attachmentInfo.fileName}", e)
                    }
                }
            }

            Log.i(TAG, "创建附件包成功: count=${attachmentDataList.size}, size=${packageFile.length()}")
            packageFile
        } catch (e: Exception) {
            Log.e(TAG, "创建附件包失败", e)
            null
        }
    }
}

/**
 * 加密消息数据
 */
data class EncryptedMessageData(
    val signalCiphertext: ByteArray,
    val signalCiphertextType: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedMessageData

        if (!signalCiphertext.contentEquals(other.signalCiphertext)) return false
        if (signalCiphertextType != other.signalCiphertextType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = signalCiphertext.contentHashCode()
        result = 31 * result + signalCiphertextType
        return result
    }
}

/**
 * 附件准备结果
 */
data class AttachmentPreparationResult(
    val attachmentKey: ByteArray,
    val attachmentFile: File
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttachmentPreparationResult

        if (!attachmentKey.contentEquals(other.attachmentKey)) return false
        if (attachmentFile != other.attachmentFile) return false

        return true
    }

    override fun hashCode(): Int {
        var result = attachmentKey.contentHashCode()
        result = 31 * result + attachmentFile.hashCode()
        return result
    }
}

/**
 * COS发送结果
 */
sealed class CosSendResult {
    data class Success(val messagePath: String, val attachmentPath: String?) : CosSendResult()
    data class Failure(val errorMessage: String) : CosSendResult()
}
