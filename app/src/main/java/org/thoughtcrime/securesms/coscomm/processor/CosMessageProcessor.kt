package org.thoughtcrime.securesms.coscomm.processor

import android.content.Context
import android.net.Uri
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.*
import org.thoughtcrime.securesms.coscomm.cache.CosEarlyMessageCache
import org.thoughtcrime.securesms.coscomm.concurrent.CosClientPoolManager
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.crypto.SignalSessionCipher
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.mms.PartAuthority
import okio.ByteString.Companion.toByteString
import okio.ByteString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.message.SignalMessage
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.messages.MessageDecryptor
import org.thoughtcrime.securesms.messages.protocol.BufferedProtocolStore
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupsV2ProcessingLock
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.signal.core.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.attachments.Attachment
import java.security.MessageDigest
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.database.MessageType as DbMessageType
import org.thoughtcrime.securesms.coscomm.data.MessageType as CosMessageType

/**
 * 附件事务数据类
 * 用于管理附件创建过程中的临时文件和附件记录，支持事务回滚
 */
data class AttachmentTransaction(
    val tempFiles: MutableList<String> = mutableListOf(),
    val attachments: MutableList<Attachment> = mutableListOf()
)


// 顶级函数使用的TAG - 创建临时类来生成TAG
private class CosMessageProcessorExt
private val TAG_PROCESSOR_EXT = Log.tag(CosMessageProcessorExt::class.java)

/**
 * COS消息处理器
 * 将COS接收的消息集成到现有的Double Ratchet解密流程，提取Ratchet信息并更新状态
 */
class CosMessageProcessor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosMessageProcessor::class.java)
        
        // COS附件CDN标识常量
        private const val COS_CDN_ID = 999L // 标识COS附件的特殊CDN ID，与Cdn.COS保持一致
        private const val COS_CDN_PREFIX = "cos-v2-"
        
        @Volatile
        private var INSTANCE: CosMessageProcessor? = null
        
        fun getInstance(context: Context): CosMessageProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosMessageProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val messageDeduplicationManager = MessageDeduplicationManager.getInstance(context)
    private val cosChannelManager = CosChannelManager.getInstance(context)
    private val messageContentProcessor = MessageContentProcessor(context)
    private val cosAttachmentManager = CosAttachmentManager.getInstance(context)
    private val processingExecutor = Executors.newFixedThreadPool(3) // 增加线程池大小
    

    
    // 🆕 集成早期消息缓存和客户端池管理，提升鲁棒性
    private val earlyMessageCache = CosEarlyMessageCache()
    private val clientPoolManager = CosClientPoolManager.getInstance(context)
    
    // 协程作用域用于异步处理
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 安全的私有临时目录
    private val secureAttachmentDir: File by lazy {
        val dir = File(context.getDir("secure_attachments", Context.MODE_PRIVATE), "process_temp")
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
    fun createSecureTempFile(prefix: String, suffix: String = ".tmp"): File {
        val tempFile = File.createTempFile(prefix, suffix, secureAttachmentDir)
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        return tempFile
    }
    

    

    
    /**
     * 处理从COS接收的消息列表 - 增强版本，支持早期消息缓存和网络重试
     * @param senderId 发送者ID
     * @param cosMessages COS消息列表
     * @return 处理结果的Future
     */
    fun processReceivedMessages(senderId: String, cosMessages: List<CosMessage>): CompletableFuture<ProcessingResult> {
        Log.i(CosMessageProcessor.TAG, "🔄 处理COS接收消息（增强版）: senderId=$senderId, count=${cosMessages.size}")
        
        return CompletableFuture.supplyAsync({
            try {
                // 🔍 第一步：检查网络连接和客户端池状态
                if (!clientPoolManager.isHealthy()) {
                    Log.w(CosMessageProcessor.TAG, "⚠️ 客户端池不健康，尝试恢复: senderId=$senderId")
                    clientPoolManager.resetPool()
                }
                
                // 第二步：消息去重和初步排序
                val sortedMessages = messageDeduplicationManager.processMessages(senderId, cosMessages)
                Log.d(CosMessageProcessor.TAG, "去重排序后消息数量: ${sortedMessages.size}")
                
                if (sortedMessages.isEmpty()) {
                    return@supplyAsync ProcessingResult.NoNewMessages
                }
                
                // 🆕 第三步：通过早期消息缓存处理消息乱序
                // 先尝试处理所有消息，将早期消息缓存起来
                val readyMessages = mutableListOf<CosMessage>()
                val cachedMessages = mutableListOf<CosMessage>()
                
                sortedMessages.forEach { message ->
                    // 简化逐个消息处理，直接将所有消息视为就绪
                    readyMessages.add(message)
                }
                
                Log.i(CosMessageProcessor.TAG, "📦 消息分类结果: ready=${readyMessages.size}, cached=${cachedMessages.size}")
                
                // 第四步：处理就绪消息
                val processResult = if (readyMessages.isNotEmpty()) {
                    processDecryptedMessages(senderId, readyMessages)
                } else {
                    Pair(0, emptyList<CosMessage>())
                }
                val processedCount = processResult.first
                val processedMessages = processResult.second
                
                // 🔄 第五步：检查缓存中是否有新的就绪消息
                val additionalReady = earlyMessageCache.checkPendingMessages(senderId)
                val additionalResult = if (additionalReady.isNotEmpty()) {
                    Log.i(CosMessageProcessor.TAG, "🎯 发现额外就绪消息: count=${additionalReady.size}")
                    processDecryptedMessages(senderId, additionalReady)
                } else {
                    Pair(0, emptyList<CosMessage>())
                }
                val additionalProcessed = additionalResult.first
                val additionalMessages = additionalResult.second

                // 第六步：更新通道活动时间
                updateChannelActivity(senderId)

                // 标记成功消息为已处理
                val allProcessedMessages = processedMessages + additionalMessages
                if (allProcessedMessages.isNotEmpty()) {
                    messageDeduplicationManager.markMessagesAsProcessed(allProcessedMessages)
                }
                
                val totalProcessed = processedCount + additionalProcessed
                Log.i(CosMessageProcessor.TAG, "✅ 消息处理完成: processed=$totalProcessed, cached=${cachedMessages}")

                ProcessingResult.Success(totalProcessed, sortedMessages.size, allProcessedMessages)
                
            } catch (e: Exception) {
                Log.e(CosMessageProcessor.TAG, "❌ 处理COS消息异常: senderId=$senderId", e)
                // 🔄 异常时尝试恢复客户端池
                try {
                    clientPoolManager.resetPool()
                } catch (resetException: Exception) {
                    Log.e(CosMessageProcessor.TAG, "客户端池重置失败", resetException)
                }
                ProcessingResult.Error(e.message ?: "未知错误")
            }
        }, processingExecutor)
    }
    
    /**
     * 处理消息列表
     * 修复双发问题：并行处理消息，依赖Signal原生Double Ratchet机制处理顺序和重复
     * @param senderId 发送者ID
     * @param cosMessages COS消息列表
     * @return 成功处理的消息数量和消息列表
     */
    private fun processDecryptedMessages(senderId: String, cosMessages: List<CosMessage>): Pair<Int, List<CosMessage>> {
        var processedCount = 0
        val processedMessages = mutableListOf<CosMessage>()

        Log.i(CosMessageProcessor.TAG, "开始处理消息: senderId=$senderId, 总数=${cosMessages.size}")

        // 记录消息信息，但不强调处理顺序
        cosMessages.forEachIndexed { index, message ->
            Log.d(CosMessageProcessor.TAG, "消息[$index]: messageId=${message.messageId}, timestamp=${message.timestamp}")
        }

        val failed = mutableListOf<CosMessage>()
        val networkErrors = mutableListOf<CosMessage>()

        // 处理所有消息，让Signal原生机制处理重复和排序
        cosMessages.forEach { cosMessage ->
            try {
                Log.i(CosMessageProcessor.TAG, "处理消息: messageId=${cosMessage.messageId}, timestamp=${cosMessage.timestamp}")
                
                val result = processIndividualMessageWithRetry(senderId, cosMessage)
                when (result) {
                    is ProcessResult.Success -> {
                        processedCount++
                        processedMessages.add(cosMessage)
                        Log.i(CosMessageProcessor.TAG, "消息处理成功: messageId=${cosMessage.messageId}")
                    }
                    is ProcessResult.NetworkError -> {
                        Log.w(CosMessageProcessor.TAG, "网络错误，加入重试队列: messageId=${cosMessage.messageId}")
                        networkErrors.add(cosMessage)
                    }
                    is ProcessResult.EarlyMessage -> {
                        Log.i(CosMessageProcessor.TAG, "早期消息，加入缓存: messageId=${cosMessage.messageId}")
                        earlyMessageCache.store(senderId, result.expectedSequence, cosMessage, "Early message detected")
                    }
                    is ProcessResult.Failure -> {
                        Log.w(CosMessageProcessor.TAG, "消息处理失败: messageId=${cosMessage.messageId}, reason=${result.reason}")
                        failed.add(cosMessage)
                    }
                }
            } catch (e: Exception) {
                Log.e(CosMessageProcessor.TAG, "处理消息异常: messageId=${cosMessage.messageId}", e)
                failed.add(cosMessage)
            }
        }

        // 网络错误重试
        if (networkErrors.isNotEmpty()) {
            Log.i(CosMessageProcessor.TAG, "处理网络错误重试: count=${networkErrors.size}")
            retryNetworkErrors(senderId, networkErrors, processedCount, processedMessages)
        }

        // 常规失败重试（如果有成功消息）
        if (processedCount > 0 && failed.isNotEmpty()) {
            Log.i(CosMessageProcessor.TAG, "进行失败消息重试: count=${failed.size}")
            retryFailedMessages(senderId, failed, processedCount, processedMessages)
        }

        Log.i(CosMessageProcessor.TAG, "消息处理完成: senderId=$senderId, processed=${processedMessages.size}, total=${cosMessages.size}")
        return Pair(processedMessages.size, processedMessages)
    }
    
    /**
     * 处理网络错误重试
     */
    private fun retryNetworkErrors(
        senderId: String,
        networkErrors: MutableList<CosMessage>,
        processedCount: Int,
        processedMessages: MutableList<CosMessage>
    ) {
        // 等待客户端池恢复
        try {
            clientPoolManager.waitForHealthy(5000) // 最多等待5秒
        } catch (e: Exception) {
            Log.w(CosMessageProcessor.TAG, "等待客户端池恢复超时", e)
        }
        
        val iterator = networkErrors.iterator()
        while (iterator.hasNext()) {
            val msg = iterator.next()
            try {
                val result = processIndividualMessageWithRetry(senderId, msg)
                if (result is ProcessResult.Success) {
                    processedMessages.add(msg)
                    iterator.remove()
                    Log.i(CosMessageProcessor.TAG, "✅ 网络重试成功: messageId=${msg.messageId}")
                }
            } catch (e: Exception) {
                Log.e(CosMessageProcessor.TAG, "网络重试异常: messageId=${msg.messageId}", e)
            }
        }
    }
    
    /**
     * 处理常规失败重试
     */
    private fun retryFailedMessages(
        senderId: String,
        failed: MutableList<CosMessage>,
        processedCount: Int,
        processedMessages: MutableList<CosMessage>
    ) {
        val iterator = failed.iterator()
        while (iterator.hasNext()) {
            val msg = iterator.next()
            try {
                val result = processIndividualMessageWithRetry(senderId, msg)
                if (result is ProcessResult.Success) {
                    processedMessages.add(msg)
                    iterator.remove()
                    Log.i(CosMessageProcessor.TAG, "✅ 回扫重试成功: messageId=${msg.messageId}")
                }
            } catch (e: Exception) {
                Log.e(CosMessageProcessor.TAG, "回扫重试异常: messageId=${msg.messageId}", e)
            }
        }
        Log.i(CosMessageProcessor.TAG, "回扫结束，剩余失败: ${failed.size}")
    }
    
    /**
     * 处理单条COS消息（带重试机制）
     * @param senderId 发送者ID
     * @param cosMessage COS消息
     * @return 处理结果
     */
    private fun processIndividualMessageWithRetry(senderId: String, cosMessage: CosMessage): ProcessResult {
        val maxRetries = 2
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val result = processIndividualMessage(senderId, cosMessage)
                if (result is ProcessResult.Success) {
                    return result
                }
                
                // 对于非网络错误，不进行重试
                if (result !is ProcessResult.NetworkError) {
                    return result
                }
                
                // 网络错误时等待后重试
                if (attempt < maxRetries) {
                    val delay = attempt * 500L // 递增延迟
                    Log.d(CosMessageProcessor.TAG, "网络错误重试，等待${delay}ms: messageId=${cosMessage.messageId}, attempt=$attempt")
                    Thread.sleep(delay)
                }
                
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Log.w(CosMessageProcessor.TAG, "处理异常，准备重试: messageId=${cosMessage.messageId}, attempt=$attempt", e)
                    Thread.sleep(attempt * 300L)
                } else {
                    Log.e(CosMessageProcessor.TAG, "处理异常，重试次数耗尽: messageId=${cosMessage.messageId}", e)
                }
            }
        }
        
        return ProcessResult.Failure("重试次数耗尽: ${lastException?.message}")
    }
    
    /**
     * 处理单条COS消息
     * @param senderId 发送者ID
     * @param cosMessage COS消息
     * @return 处理结果
     */
    private fun processIndividualMessage(senderId: String, cosMessage: CosMessage): ProcessResult {
        Log.i(CosMessageProcessor.TAG, "🔄 开始处理COS消息: messageId=${cosMessage.messageId}, senderId=$senderId, timestamp=${cosMessage.timestamp}")

        try {
            // 步骤1: 获取发送者Recipient
            Log.d(CosMessageProcessor.TAG, "📋 步骤1: 获取发送者Recipient: senderId=$senderId")
            val senderRecipient = getSenderRecipient(senderId)
            if (senderRecipient == null) {
                Log.w(CosMessageProcessor.TAG, "❌ 无法找到发送者Recipient: senderId=$senderId")
                return ProcessResult.Failure("Sender recipient not found")
            }
            Log.d(CosMessageProcessor.TAG, "✅ 找到发送者Recipient: ${senderRecipient.id}")

            // 步骤2: 使用Signal原生解密流程
            Log.d(TAG, "步骤2: 使用Signal原生解密流程: messageId=${cosMessage.messageId}")
            val decryptResult = decryptCosMessageWithRetry(senderRecipient, cosMessage)
            when (decryptResult) {
                null -> {
                    Log.w(TAG, "Signal原生解密失败: messageId=${cosMessage.messageId}")
                    return ProcessResult.Failure("Signal native decryption failed")
                }
                is MessageDecryptor.Result.DecryptionError -> {
                    Log.w(TAG, "Signal解密遇到解密错误: messageId=${cosMessage.messageId}")
                    return ProcessResult.Failure("Decryption error")
                }
                is MessageDecryptor.Result.InvalidVersion -> {
                    Log.w(TAG, "Signal解密遇到版本错误: messageId=${cosMessage.messageId}")
                    return ProcessResult.Failure("Invalid version")
                }
                is MessageDecryptor.Result.LegacyMessage -> {
                    Log.w(TAG, "Signal解密遇到遗留消息: messageId=${cosMessage.messageId}")
                    return ProcessResult.Failure("Legacy message")
                }
                is MessageDecryptor.Result.UnsupportedDataMessage -> {
                    Log.w(TAG, "Signal解密遇到不支持的消息: messageId=${cosMessage.messageId}")
                    return ProcessResult.Failure("Unsupported data message")
                }
                is MessageDecryptor.Result.Ignore -> {
                    Log.d(TAG, "Signal解密结果为忽略: messageId=${cosMessage.messageId}")
                    return ProcessResult.Success
                }
                is MessageDecryptor.Result.Success -> {
                    Log.i(TAG, "Signal原生解密成功: messageId=${cosMessage.messageId}")
                    
                    // 使用Signal原生的完整处理流程，包括数据库事务和后续操作
                    try {
                        GroupsV2ProcessingLock.acquireGroupProcessingLock().use {
                            ReentrantSessionLock.INSTANCE.acquire().use {
                                Log.d(TAG, "开始数据库事务处理消息: messageId=${cosMessage.messageId}")
                                
                                // 在数据库事务中处理消息
                                val followUpOperations = SignalDatabase.runInTransaction { db ->
                                    messageContentProcessor.process(
                                        envelope = decryptResult.envelope,
                                        content = decryptResult.content,
                                        metadata = decryptResult.metadata,
                                        serverDeliveredTimestamp = decryptResult.serverDeliveredTimestamp
                                    )
                                    
                                    // 返回解密结果中的后续操作
                                    decryptResult.followUpOperations
                                }
                                
                                Log.d(TAG, "数据库事务完成: messageId=${cosMessage.messageId}")
                                
                                // 执行后续操作
                                if (followUpOperations.isNotEmpty()) {
                                    Log.d(TAG, "执行${followUpOperations.size}个后续操作: messageId=${cosMessage.messageId}")
                                    val jobs = followUpOperations.mapNotNull { it.run() }
                                    if (jobs.isNotEmpty()) {
                                        AppDependencies.jobManager.addAllChains(jobs)
                                        Log.d(TAG, "添加${jobs.size}个作业链到队列: messageId=${cosMessage.messageId}")
                                    }
                                }
                                
                                Log.i(TAG, "消息处理完成: messageId=${cosMessage.messageId}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "消息处理异常: messageId=${cosMessage.messageId}", e)
                        return ProcessResult.Failure("Message processing failed: ${e.message}")
                    }
                    
                    return ProcessResult.Success
                }
            }

        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "DNS解析失败: messageId=${cosMessage.messageId}", e)
            return ProcessResult.NetworkError("DNS resolution failed: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "网络超时: messageId=${cosMessage.messageId}", e)
            return ProcessResult.NetworkError("Network timeout: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "处理COS消息异常: messageId=${cosMessage.messageId}", e)
            // 检查是否为网络相关异常
            if (e.message?.contains("cos.ap-nanjing.myqcloud.com") == true ||
                e.message?.contains("network", ignoreCase = true) == true ||
                e.message?.contains("connection", ignoreCase = true) == true) {
                return ProcessResult.NetworkError("Network related error: ${e.message}")
            }
            return ProcessResult.Failure("Processing exception: ${e.message}")
        }
    }
    
    /**
     * 解密COS消息内容（带重试和网络错误处理）
     * @param senderRecipient 发送者Recipient
     * @param cosMessage COS消息
     * @return 解密结果
     */
    private fun decryptCosMessageWithRetry(senderRecipient: Recipient, cosMessage: CosMessage): MessageDecryptor.Result? {
        Log.d(TAG, "开始解密COS消息（使用Signal原生流程）: messageId=${cosMessage.messageId}")

        try {
            // 检查客户端池状态
            if (!clientPoolManager.isHealthy()) {
                Log.w(TAG, "客户端池不健康，尝试恢复")
                clientPoolManager.resetPool()
                if (!clientPoolManager.waitForHealthy(3000)) {
                    Log.e(TAG, "客户端池恢复失败")
                    return null
                }
            }

            // 使用Signal原生解密流程
            val decryptResult = decryptCosMessage(senderRecipient, cosMessage)
            if (decryptResult != null) {
                Log.i(TAG, "Signal原生解密成功: messageId=${cosMessage.messageId}")
                return decryptResult
            } else {
                Log.w(TAG, "Signal原生解密返回null: messageId=${cosMessage.messageId}")
                return null
            }
            
        } catch (e: java.net.UnknownHostException) {
            Log.w(TAG, "DNS解析失败: messageId=${cosMessage.messageId}", e)
            return null
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "网络超时: messageId=${cosMessage.messageId}", e)
            return null
        } catch (e: Exception) {
            if (e.message?.contains("cos.ap-nanjing.myqcloud.com") == true) {
                Log.w(TAG, "COS服务器连接失败: messageId=${cosMessage.messageId}", e)
                return null
            }
            Log.e(TAG, "解密异常: messageId=${cosMessage.messageId}", e)
            return null
        }
    }
    
    /**
     * 将COS消息转换为Signal标准Envelope格式
     * @param cosMessage COS消息
     * @param senderRecipient 发送者Recipient（用于获取设备ID）
     * @return Signal Envelope对象
     */
    private fun convertCosMessageToEnvelope(cosMessage: CosMessage, senderRecipient: Recipient): Envelope {
        Log.d(TAG, "转换COS消息为Envelope: messageId=${cosMessage.messageId}")
        Log.d(TAG, "🔧 COS消息时间戳: ${cosMessage.timestamp}")
        
        // 解码Signal密文
        val ciphertextBytes = Base64.decode(cosMessage.signalCiphertext)
        
        // 根据Signal密文类型确定Envelope类型
        val envelopeType = when (cosMessage.signalCiphertextType) {
            1 -> Envelope.Type.PREKEY_BUNDLE  // CiphertextMessage.PREKEY_TYPE = 1
            2 -> Envelope.Type.CIPHERTEXT     // CiphertextMessage.WHISPER_TYPE = 2
            3 -> Envelope.Type.UNIDENTIFIED_SENDER // CiphertextMessage.SENDERKEY_TYPE = 3
            4 -> Envelope.Type.PLAINTEXT_CONTENT   // CiphertextMessage.PLAINTEXT_CONTENT_TYPE = 4
            else -> {
                Log.w(TAG, "未知的密文类型: ${cosMessage.signalCiphertextType}，使用默认CIPHERTEXT类型")
                Envelope.Type.CIPHERTEXT
            }
        }
        
        Log.d(TAG, "设置Envelope类型: signalCiphertextType=${cosMessage.signalCiphertextType} -> envelopeType=$envelopeType")
        
        // 获取发送者设备ID
        val deviceId = getSenderDeviceId(senderRecipient)
        Log.d(TAG, "获取发送者设备ID: ${senderRecipient.id} -> deviceId=$deviceId")
        
        // 获取本地ACI作为目标服务ID
        val localAci = try {
            val aci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            Log.d(TAG, "成功获取本地ACI用于Envelope: ${aci.take(8)}...")
            aci
        } catch (e: Exception) {
            Log.e(TAG, "获取本地ACI失败，COS消息接收无法继续", e)
            throw IllegalStateException("无法获取本地ACI信息，请确保已正确注册", e)
        }
        
        // 创建完整的Envelope
        return Envelope.Builder()
            .type(envelopeType)
            .sourceServiceId(senderRecipient.requireServiceId().toString())
            .sourceDevice(deviceId)
            .timestamp(cosMessage.timestamp)
            .serverTimestamp(cosMessage.timestamp)
            .serverGuid(cosMessage.messageId)
            .destinationServiceId(localAci)
            .content(ciphertextBytes.toByteString())
            .build()
    }
    
    /**
     * 使用Signal原生MessageDecryptor解密COS消息
     * @param senderRecipient 发送者Recipient
     * @param cosMessage COS消息
     * @return 解密结果，包含Content或错误信息
     */
    private fun decryptCosMessage(senderRecipient: Recipient, cosMessage: CosMessage): MessageDecryptor.Result? {
        Log.d(TAG, "使用Signal原生解密器处理COS消息: messageId=${cosMessage.messageId}")

        try {
            // 将COS消息转换为标准Envelope
            val envelope = convertCosMessageToEnvelope(cosMessage, senderRecipient)
            Log.d(TAG, "Envelope创建完成: timestamp=${envelope.timestamp}")

            // 创建BufferedProtocolStore用于解密
            val bufferedProtocolStore = BufferedProtocolStore.create()
            
            // 使用Signal原生MessageDecryptor进行解密
            val decryptResult = MessageDecryptor.decrypt(
                context = context,
                bufferedProtocolStore = bufferedProtocolStore,
                envelope = envelope,
                serverDeliveredTimestamp = envelope.serverTimestamp ?: System.currentTimeMillis()
            )
            
            // 提交协议存储的更改
            bufferedProtocolStore.flushToDisk()
            
            Log.i(TAG, "Signal原生解密完成: messageId=${cosMessage.messageId}, 结果类型=${decryptResult::class.simpleName}")
            return decryptResult

        } catch (e: Exception) {
            Log.e(TAG, "Signal原生解密失败: messageId=${cosMessage.messageId}", e)
            return null
        }
    }
    
    /**
     * 获取发送者Recipient
     * @param senderId 发送者ID（可能是RecipientId格式或ServiceId格式）
     * @return Recipient对象，失败返回null
     */
    private fun getSenderRecipient(senderId: String): Recipient? {
        return try {
            Log.d(CosMessageProcessor.TAG, "🔍 解析发送者ID: $senderId")

            // 检查是否为RecipientId格式（如：RecipientId::3）
            if (senderId.startsWith("RecipientId::")) {
                val recipientIdValue = senderId.removePrefix("RecipientId::")
                val recipientId = RecipientId.from(recipientIdValue.toLong())
                val recipient = Recipient.resolved(recipientId)
                Log.d(CosMessageProcessor.TAG, "✅ 通过RecipientId解析成功: $senderId -> ${recipient.id}")
                return recipient
            }

            // 尝试作为ServiceId解析
            val serviceId = ServiceId.parseOrThrow(senderId)
            val signalServiceAddress = SignalServiceAddress(serviceId)
            val recipient = Recipient.externalPush(signalServiceAddress)
            Log.d(CosMessageProcessor.TAG, "✅ 通过ServiceId解析成功: $senderId -> ${recipient.id}")
            return recipient

        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "❌ 获取发送者Recipient失败: senderId=$senderId", e)
            null
        }
    }

    /**
     * 获取发送者的设备ID
     * @param senderRecipient 发送者Recipient
     * @return 设备ID，默认为1
     */
    private fun getSenderDeviceId(senderRecipient: Recipient): Int {
        return try {
            // 从协议存储获取设备会话
            val sessions = AppDependencies.protocolStore.aci().getSubDeviceSessions(senderRecipient.requireServiceId().toString())
            if (sessions.isNotEmpty()) {
                // 返回第一个设备ID，通常是主设备
                sessions.first()
            } else {
                // 如果没有子设备会话，返回默认设备ID
                SignalServiceAddress.DEFAULT_DEVICE_ID
            }
        } catch (e: Exception) {
            Log.w(CosMessageProcessor.TAG, "获取发送者设备ID失败，使用默认值: ${senderRecipient.id}", e)
            SignalServiceAddress.DEFAULT_DEVICE_ID
        }
    }
    
    /**
     * 从COS消息创建Envelope（备用方法）
     * @param cosMessage COS消息
     * @param senderRecipient 发送者Recipient
     * @return Envelope对象
     */
    private fun createEnvelopeFromCosMessage(cosMessage: CosMessage, senderRecipient: Recipient): Envelope {
        val deviceId = getSenderDeviceId(senderRecipient)

        // 🔧 修复：使用本地缓存的ACI，避免触发服务器连接
        val localAci = try {
            val aci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
            Log.d(CosMessageProcessor.TAG, "成功获取本地ACI用于Envelope: ${aci.take(8)}...")
            aci
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "获取本地ACI失败，EnvelopeMetadata创建无法继续", e)
            throw IllegalStateException("无法获取本地ACI信息，请确保已正确注册", e)
        }

        // 根据Signal密文类型确定Envelope类型
        val envelopeType = when (cosMessage.signalCiphertextType) {
            1 -> Envelope.Type.PREKEY_BUNDLE  // CiphertextMessage.PREKEY_TYPE = 1
            2 -> Envelope.Type.CIPHERTEXT     // CiphertextMessage.WHISPER_TYPE = 2
            3 -> Envelope.Type.UNIDENTIFIED_SENDER // CiphertextMessage.SENDERKEY_TYPE = 3
            4 -> Envelope.Type.PLAINTEXT_CONTENT   // CiphertextMessage.PLAINTEXT_CONTENT_TYPE = 4
            else -> {
                Log.w(TAG, "未知的密文类型: ${cosMessage.signalCiphertextType}，使用默认CIPHERTEXT类型")
                Envelope.Type.CIPHERTEXT
            }
        }
        
        // 解码Signal密文
        val ciphertextBytes = Base64.decode(cosMessage.signalCiphertext)

        return Envelope.Builder()
            .type(envelopeType)
            .sourceServiceId(senderRecipient.requireServiceId().toString())
            .sourceDevice(deviceId)
            .timestamp(cosMessage.timestamp)
            .serverTimestamp(cosMessage.timestamp)
            .serverGuid(cosMessage.messageId)
            .destinationServiceId(localAci)
            .content(ciphertextBytes.toByteString())
            .build()
    }
    
    /**
     * 从解密数据创建Content
     * @param decryptedData 解密后的数据
     * @param cosMessage 原始COS消息
     * @return Content对象
     */
    private fun createContentFromDecryptedData(decryptedData: ByteArray, cosMessage: CosMessage): Content {
        Log.d(CosMessageProcessor.TAG, "创建Content: messageId=${cosMessage.messageId}, dataSize=${decryptedData.size}, messageType=${cosMessage.messageType}")

        // 根据消息类型处理
        when (cosMessage.messageType) {
            CosMessageType.ATTACHMENT -> {
                Log.i(CosMessageProcessor.TAG, "处理附件消息: messageId=${cosMessage.messageId}")
                return createAttachmentContent(decryptedData, cosMessage)
            }
            CosMessageType.TEXT -> {
                Log.d(CosMessageProcessor.TAG, "处理文本消息: messageId=${cosMessage.messageId}")
                return createTextContent(decryptedData, cosMessage)
            }
            else -> {
                Log.w(CosMessageProcessor.TAG, "不支持的消息类型，作为文本处理: messageType=${cosMessage.messageType}")
                return createTextContent(decryptedData, cosMessage)
            }
        }
    }

    /**
     * 创建文本消息Content
     */
    private fun createTextContent(decryptedData: ByteArray, cosMessage: CosMessage): Content {
        try {
            // 尝试解析解密后的数据为Content
            val content = Content.ADAPTER.decode(decryptedData)
            Log.d(CosMessageProcessor.TAG, "成功解析Content: messageId=${cosMessage.messageId}")
            return content
        } catch (e: Exception) {
            Log.w(CosMessageProcessor.TAG, "解析Content失败，尝试创建DataMessage: messageId=${cosMessage.messageId}", e)

            // 尝试将解密数据作为UTF-8文本处理
            val messageText = try {
                val text = String(decryptedData, Charsets.UTF_8)
                Log.d(CosMessageProcessor.TAG, "解密数据作为文本: messageId=${cosMessage.messageId}, textLength=${text.length}")

                // 确保文本不包含COS控制消息前缀，避免被DataMessageProcessor拦截
                if (text.startsWith("COS_MSG:")) {
                    Log.w(CosMessageProcessor.TAG, "检测到COS控制消息前缀，移除以避免拦截: messageId=${cosMessage.messageId}")
                    text.removePrefix("COS_MSG:")
                } else {
                    text
                }
            } catch (textException: Exception) {
                Log.e(CosMessageProcessor.TAG, "无法将解密数据转换为文本: messageId=${cosMessage.messageId}", textException)
                "[无法解析的消息内容]"
            }

            // 创建基本的DataMessage
            val dataMessage = DataMessage.Builder()
                .timestamp(cosMessage.timestamp)
                .body(messageText)
                .build()

            Log.d(CosMessageProcessor.TAG, "创建DataMessage: messageId=${cosMessage.messageId}, bodyLength=${messageText.length}")

            return Content.Builder()
                .dataMessage(dataMessage)
                .build()
        }
    }

    /**
     * 创建附件消息Content
     */
    private fun createAttachmentContent(decryptedData: ByteArray, cosMessage: CosMessage): Content {
        Log.i(CosMessageProcessor.TAG, "开始处理附件消息: messageId=${cosMessage.messageId}")
        
        val attachmentInfo = cosMessage.attachmentInfo
        if (attachmentInfo == null) {
            Log.e(CosMessageProcessor.TAG, "附件消息缺少附件信息: messageId=${cosMessage.messageId}")
            throw IllegalArgumentException("附件消息缺少附件信息")
        }

        // 从解密数据中提取附件加密密钥
        val attachmentEncryptionKey = extractAttachmentEncryptionKey(decryptedData)
        if (attachmentEncryptionKey == null) {
            Log.e(CosMessageProcessor.TAG, "无法提取附件加密密钥: messageId=${cosMessage.messageId}")
            throw IllegalArgumentException("无法提取附件加密密钥")
        }

        // 下载并解密附件
        val downloadResult = downloadAndDecryptAttachmentWithRetry(
            cosMessage.senderId, attachmentInfo, attachmentEncryptionKey, cosMessage.messageId
        )
        
        if (downloadResult == null) {
            Log.e(CosMessageProcessor.TAG, "附件下载失败: messageId=${cosMessage.messageId}")
            return createAttachmentPlaceholderContent(cosMessage, attachmentInfo, decryptedData)
        }

        // 创建本地附件
        val uriAttachment = createDatabaseAttachment(downloadResult.file, attachmentInfo)
        if (uriAttachment == null) {
            Log.e(CosMessageProcessor.TAG, "创建本地附件失败: messageId=${cosMessage.messageId}")
            return createAttachmentPlaceholderContent(cosMessage, attachmentInfo, decryptedData)
        }

        // 提取消息文本
        val messageText = extractMessageTextFromAttachmentData(decryptedData)

        // 直接插入消息到数据库，跳过复杂的DataMessage转换
        return insertAttachmentMessageDirectly(cosMessage, uriAttachment, messageText)
    }

    /**
     * 直接将附件消息插入数据库，跳过DataMessage转换过程
     */
    private fun insertAttachmentMessageDirectly(
        cosMessage: CosMessage, 
        attachment: UriAttachment, 
        messageText: String?
    ): Content {
        try {
            // 获取发送者信息
            val senderRecipient = getSenderRecipient(cosMessage.senderId) ?: throw IllegalArgumentException("Invalid sender ID")
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderRecipient)
            
            // 创建IncomingMessage
            val incomingMessage = IncomingMessage(
                type = DbMessageType.NORMAL,
                from = senderRecipient.id,
                sentTimeMillis = cosMessage.timestamp,
                serverTimeMillis = cosMessage.timestamp,
                receivedTimeMillis = System.currentTimeMillis(),
                body = messageText,
                attachments = listOf(attachment), // 直接使用本地附件
                isUnidentified = true
            )
            
            // 直接插入到数据库
            val insertResult = SignalDatabase.messages.insertMessageInbox(
                retrieved = incomingMessage,
                candidateThreadId = threadId
            )
            
            if (insertResult.isPresent) {
                val result = insertResult.get()
                Log.i(CosMessageProcessor.TAG, "✅ 成功直接插入附件消息: messageId=${cosMessage.messageId}, dbId=${result.messageId}")
                
                // 更新线程
                SignalDatabase.threads.update(threadId, true)
                
                // 返回一个简单的Content，表示处理完成
                return Content.Builder()
                    .dataMessage(org.whispersystems.signalservice.internal.push.DataMessage.Builder().build())
                    .build()
            } else {
                Log.e(CosMessageProcessor.TAG, "附件消息插入数据库失败: messageId=${cosMessage.messageId}")
                throw IllegalStateException("附件消息插入数据库失败")
            }
            
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "直接插入附件消息异常: messageId=${cosMessage.messageId}", e)
            throw e
        }
    }
    
    /**
     * 创建Envelope元数据
     * @param cosMessage COS消息
     * @param senderRecipient 发送者Recipient
     * @return EnvelopeMetadata对象
     */
    private fun createEnvelopeMetadata(cosMessage: CosMessage, senderRecipient: Recipient): org.whispersystems.signalservice.api.crypto.EnvelopeMetadata {
        // 🔧 修复：使用本地缓存的ACI，避免触发服务器连接
        val destinationAci = try {
            val aci = org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci()
            Log.d(CosMessageProcessor.TAG, "成功获取本地ACI用于EnvelopeMetadata: ${aci.toString().take(8)}...")
            aci
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "获取本地ACI失败，EnvelopeMetadata创建无法继续", e)
            throw IllegalStateException("无法获取本地ACI信息，请确保已正确注册", e)
        }

        val deviceId = getSenderDeviceId(senderRecipient)

        return org.whispersystems.signalservice.api.crypto.EnvelopeMetadata(
            sourceServiceId = senderRecipient.requireServiceId(),
            sourceE164 = senderRecipient.e164.orElse(null),
            sourceDeviceId = deviceId,
            sealedSender = false,
            groupId = null,
            destinationServiceId = destinationAci
        )
    }
    
    /**
     * 更新通道活动时间
     * @param senderId 发送者ID
     */
    private fun updateChannelActivity(senderId: String) {
        try {
            cosChannelManager.updateChannelActivity(senderId)
            Log.d(CosMessageProcessor.TAG, "更新通道活动时间: senderId=$senderId")
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "更新通道活动时间失败: senderId=$senderId", e)
        }
    }
    
    /**
     * 强制处理所有待排序消息
     * @param senderId 发送者ID
     * @return 处理结果的Future
     */
    fun forceProcessPendingMessages(senderId: String): CompletableFuture<ProcessingResult> {
        Log.i(CosMessageProcessor.TAG, "强制处理待排序消息: senderId=$senderId")
        
        return CompletableFuture.supplyAsync({
            try {
                val pendingMessages = messageDeduplicationManager.forceProcessPendingMessages(senderId)
                
                if (pendingMessages.isEmpty()) {
                    return@supplyAsync ProcessingResult.NoNewMessages
                }
                
                val (processedCount, processedMessages) = processDecryptedMessages(senderId, pendingMessages)
                updateChannelActivity(senderId)

                if (processedCount > 0) {
                    messageDeduplicationManager.markMessagesAsProcessed(processedMessages)
                }

                ProcessingResult.Success(processedCount, pendingMessages.size, processedMessages)
            } catch (e: Exception) {
                Log.e(CosMessageProcessor.TAG, "强制处理消息异常: senderId=$senderId", e)
                ProcessingResult.Error(e.message ?: "未知错误")
            }
        }, processingExecutor)
    }
    
    /**
     * 获取处理统计信息
     * @return 统计信息
     */
    fun getProcessingStatistics(): ProcessingStatistics {
        val deduplicationStats = messageDeduplicationManager.getDeduplicationStatistics()
        val pendingStats = messageDeduplicationManager.getPendingMessageStats()
        
        return ProcessingStatistics(
            deduplicationStats = deduplicationStats,
            pendingMessagesByRecipient = pendingStats
        )
    }
    
    /**
     * 处理结果密封类
     */
    sealed class ProcessingResult {
        data class Success(val processedCount: Int, val totalCount: Int, val processedMessages: List<CosMessage>) : ProcessingResult()
        object NoNewMessages : ProcessingResult()
        data class Error(val error: String) : ProcessingResult()
    }
    
    /**
     * 处理统计信息数据类
     */
    data class ProcessingStatistics(
        val deduplicationStats: MessageDeduplicationManager.DeduplicationStatistics.Snapshot,
        val pendingMessagesByRecipient: Map<String, Int>
    )

    /**
     * 从解密数据中提取附件加密密钥
     */
    private fun extractAttachmentEncryptionKey(decryptedData: ByteArray): ByteArray? {
        return try {
            // 解密数据格式: [32字节附件密钥][剩余消息文本数据]
            if (decryptedData.size < 32) {
                Log.e(CosMessageProcessor.TAG, "解密数据太短，无法包含附件密钥: size=${decryptedData.size}")
                return null
            }
            
            val attachmentKey = ByteArray(32)
            System.arraycopy(decryptedData, 0, attachmentKey, 0, 32)
            Log.d(CosMessageProcessor.TAG, "成功提取附件加密密钥")
            attachmentKey
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "提取附件加密密钥失败", e)
            null
        }
    }

    /**
     * 下载并解密附件（带重试机制）
     */
    private fun downloadAndDecryptAttachmentWithRetry(
        senderId: String, 
        attachmentInfo: AttachmentInfo, 
        encryptionKey: ByteArray,
        messageId: String,
        maxRetries: Int = 3
    ): CosAttachmentDownloadResult.Success? {
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                Log.d(CosMessageProcessor.TAG, "尝试下载附件: attempt=$attempt, messageId=$messageId, attachmentId=${attachmentInfo.attachmentId}")
                
                val result = downloadAndDecryptAttachment(senderId, attachmentInfo, encryptionKey)
                if (result != null) {
                    Log.i(CosMessageProcessor.TAG, "附件下载成功: attempt=$attempt, messageId=$messageId")
                    return result
                }
                
                // 如果不是最后一次尝试，等待后重试
                if (attempt < maxRetries) {
                    val delay = attempt * 1000L // 递增延迟：1秒, 2秒, 3秒
                    Log.w(CosMessageProcessor.TAG, "附件下载失败，等待${delay}ms后重试: attempt=$attempt, messageId=$messageId")
                    Thread.sleep(delay)
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(CosMessageProcessor.TAG, "附件下载异常: attempt=$attempt, messageId=$messageId", e)
                
                if (attempt < maxRetries) {
                    val delay = attempt * 1000L
                    Thread.sleep(delay)
                }
            }
        }
        
        Log.e(CosMessageProcessor.TAG, "附件下载重试耗尽: messageId=$messageId, maxRetries=$maxRetries", lastException)
        return null
    }

    /**
     * 下载并解密附件（原方法）
     */
    private fun downloadAndDecryptAttachment(
        senderId: String, 
        attachmentInfo: AttachmentInfo, 
        encryptionKey: ByteArray
    ): CosAttachmentDownloadResult.Success? {
        return try {
            Log.i(CosMessageProcessor.TAG, "开始下载附件: senderId=$senderId, attachmentId=${attachmentInfo.attachmentId}")
            
            // 🔧 修复：将ServiceId格式的senderId转换为RecipientId格式
            // 确保与子账户池中存储的键格式一致
            val senderRecipient = getSenderRecipient(senderId)
            if (senderRecipient == null) {
                Log.e(CosMessageProcessor.TAG, "无法解析发送者ID: $senderId")
                return null
            }
            
            val recipientIdForCos = senderRecipient.id.toString()
            Log.d(CosMessageProcessor.TAG, "转换SenderId: $senderId -> $recipientIdForCos")
            
            val downloadFuture = cosAttachmentManager.downloadAttachmentFromCos(
                recipientId = recipientIdForCos,
                attachmentInfo = attachmentInfo,
                encryptionKey = encryptionKey
            )
            
            val downloadResult = downloadFuture.get(30, TimeUnit.SECONDS)
            
            when (downloadResult) {
                is CosAttachmentDownloadResult.Success -> {
                    Log.i(CosMessageProcessor.TAG, "附件下载成功: fileName=${attachmentInfo.fileName}, size=${downloadResult.file.length()}")
                    downloadResult
                }
                is CosAttachmentDownloadResult.Failure -> {
                    Log.e(CosMessageProcessor.TAG, "附件下载失败: error=${downloadResult.error}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "下载附件异常", e)
            null
        }
    }

    /**
     * 创建数据库附件记录
     */
    private fun createDatabaseAttachment(attachmentFile: File, attachmentInfo: AttachmentInfo): UriAttachment? {
        return try {
            Log.i(CosMessageProcessor.TAG, "创建临时附件记录: fileName=${attachmentInfo.fileName}")
            
            // 将附件文件移动到Signal附件目录，让Signal原生流程处理
            val attachmentDir = File(context.getDir("attachments", Context.MODE_PRIVATE), "cos_temp")
            if (!attachmentDir.exists()) {
                attachmentDir.mkdirs()
            }
            
            val targetFile = File(attachmentDir, "${System.currentTimeMillis()}_${attachmentInfo.fileName}")
            if (attachmentFile.renameTo(targetFile)) {
                Log.d(CosMessageProcessor.TAG, "附件文件移动成功: ${targetFile.absolutePath}")
            } else {
                // 如果重命名失败，复制文件
                attachmentFile.copyTo(targetFile, overwrite = true)
                attachmentFile.delete()
                Log.d(CosMessageProcessor.TAG, "附件文件复制成功: ${targetFile.absolutePath}")
            }
            
            // 创建UriAttachment，让Signal原生流程处理数据库插入
            val uriAttachment = UriAttachment(
                dataUri = Uri.fromFile(targetFile),
                contentType = attachmentInfo.mimeType,
                transferState = AttachmentTable.TRANSFER_PROGRESS_DONE, // 标记为已完成下载
                size = targetFile.length(),
                width = 0,
                height = 0,
                fileName = attachmentInfo.fileName,
                fastPreflightId = null,
                voiceNote = false,
                borderless = false,
                videoGif = false,
                quote = false,
                caption = null,
                stickerLocator = null,
                blurHash = null,
                audioHash = null,
                transformProperties = null
            )
            
            Log.i(CosMessageProcessor.TAG, "成功创建临时附件: fileName=${attachmentInfo.fileName}, size=${targetFile.length()}")
            
            // 返回UriAttachment，让Signal原生流程处理后续的数据库操作
            uriAttachment
            
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "创建临时附件失败", e)
            null
        }
    }

    /**
     * 创建数据库附件记录（事务性版本）
     * 在事务上下文中创建附件，支持回滚
     */
    private fun createDatabaseAttachmentWithTransaction(
        attachmentFile: File, 
        attachmentInfo: AttachmentInfo, 
        transaction: AttachmentTransaction
    ): UriAttachment? {
        return try {
            // 记录附件文件到事务中，以便失败时清理
            transaction.tempFiles.add(attachmentFile.name)
            
            // 调用原始的createDatabaseAttachment方法
            val uriAttachment = createDatabaseAttachment(attachmentFile, attachmentInfo)
            
            if (uriAttachment != null) {
                // 记录成功创建的附件，以便事务管理
                transaction.attachments.add(uriAttachment)
            }
            
            uriAttachment
            
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "创建事务性数据库附件失败", e)
            // 异常时将在事务回滚时清理文件
            null
        }
    }

    /**
     * 创建附件指针
     * 对于COS附件，我们创建一个表示已下载附件的AttachmentPointer
     */
    fun createAttachmentPointer(attachment: Attachment, attachmentInfo: AttachmentInfo): AttachmentPointer {
        return AttachmentPointer.Builder()
            .contentType(attachmentInfo.mimeType)
            .size(attachmentInfo.size.toInt())
            .fileName(attachmentInfo.fileName)
            .key(generateCosAttachmentKey(attachmentInfo))
            .digest(calculateAttachmentDigest(attachment))
            .cdnId(COS_CDN_ID) // 使用COS特殊CDN ID
            .cdnNumber(COS_CDN_ID.toInt()) // 使用COS CDN常量，保持一致性
            .cdnKey("${COS_CDN_PREFIX}${attachmentInfo.attachmentId}") // COS特有标识
            .uploadTimestamp(System.currentTimeMillis())
            .build()
    }

    /**
     * 为COS附件生成一致的key
     */
    private fun generateCosAttachmentKey(attachmentInfo: AttachmentInfo): okio.ByteString {
        val keySource = "cos-attachment-${attachmentInfo.attachmentId}"
        return MessageDigest.getInstance("SHA-256")
            .digest(keySource.toByteArray())
            .sliceArray(0 until 32) // 取前32字节作为key
            .toByteString()
    }

    /**
     * 计算附件文件的摘要
     */
    private fun calculateAttachmentDigest(attachment: Attachment): okio.ByteString {
        return try {
            val attachmentUri = attachment.uri
            when {
                attachmentUri != null -> {
                    context.contentResolver.openInputStream(attachmentUri)?.use { inputStream ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                        digest.digest().toByteString()
                    } ?: ByteArray(32).toByteString()
                }
                else -> {
                    // 回退到基于附件信息生成摘要
                    val digestSource = "cos-digest-${attachment.fileName}-${attachment.size}"
                    MessageDigest.getInstance("SHA-256")
                        .digest(digestSource.toByteArray())
                        .toByteString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "计算附件摘要失败，使用默认摘要", e)
            ByteArray(32).toByteString()
        }
    }

    /**
     * 从附件数据中提取消息文本
     * 正确解析DataMessage格式，避免显示二进制乱码
     */
    private fun extractMessageTextFromAttachmentData(decryptedData: ByteArray): String? {
        return try {
            // 跳过前32字节的附件密钥
            if (decryptedData.size <= 32) {
                return null
            }
            
            val messageData = ByteArray(decryptedData.size - 32)
            System.arraycopy(decryptedData, 32, messageData, 0, messageData.size)
            
            // 正确解析Content，然后获取DataMessage
            val content = Content.ADAPTER.decode(messageData)
            val dataMessage = content.dataMessage
            
            // 返回消息体，如果为空则返回null（避免显示乱码）
            val body = dataMessage?.body
            if (body.isNullOrEmpty()) {
                Log.d(CosMessageProcessor.TAG, "附件消息无文本内容")
                null
            } else {
                Log.d(CosMessageProcessor.TAG, "提取到消息文本: length=${body.length}")
                body.trim()
            }
            
        } catch (e: Exception) {
            Log.w(CosMessageProcessor.TAG, "解析Content/DataMessage失败，返回空文本", e)
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
            Log.w(CosMessageProcessor.TAG, "检查ZIP文件格式失败", e)
            false
        }
    }

    /**
     * 从ZIP包中处理多个附件
     */
    private fun processMultipleAttachmentsFromZip(zipFile: File, originalAttachmentInfo: AttachmentInfo): List<AttachmentPointer> {
        val attachmentPointers = mutableListOf<AttachmentPointer>()
        
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
                var entry: ZipEntry? = zipInputStream.nextEntry
                var fileIndex = 0
                
                while (entry != null) {
                    if (!entry.isDirectory) {
                        Log.d(CosMessageProcessor.TAG, "处理ZIP条目: ${entry.name}, size=${entry.size}")
                        
                        val extractedFile = extractZipEntry(zipInputStream, entry, fileIndex++)
                        if (extractedFile != null) {
                            val attachmentInfo = createAttachmentInfoFromZipEntry(entry, originalAttachmentInfo)
                            val databaseAttachment = createDatabaseAttachment(extractedFile, attachmentInfo)
                            
                            if (databaseAttachment != null) {
                                val attachmentPointer = createAttachmentPointer(databaseAttachment, attachmentInfo)
                                attachmentPointers.add(attachmentPointer)
                                Log.i(CosMessageProcessor.TAG, "成功处理ZIP附件: ${entry.name}")
                            } else {
                                Log.w(CosMessageProcessor.TAG, "创建数据库附件失败: ${entry.name}")
                            }
                            
                            // 清理临时文件
                            try {
                                extractedFile.delete()
                            } catch (e: Exception) {
                                Log.w(CosMessageProcessor.TAG, "删除临时文件失败: ${extractedFile.name}", e)
                            }
                        }
                    }
                    
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            
            Log.i(CosMessageProcessor.TAG, "ZIP包处理完成: 总附件数=${attachmentPointers.size}")
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "处理ZIP包失败", e)
        }
        
        return attachmentPointers
    }

    /**
     * 从ZIP包中处理多个附件（事务性版本）
     */
    private fun processMultipleAttachmentsFromZipWithTransaction(
        zipFile: File, 
        originalAttachmentInfo: AttachmentInfo,
        transaction: AttachmentTransaction
    ): List<AttachmentPointer> {
        val attachmentPointers = mutableListOf<AttachmentPointer>()
        
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
                var entry: ZipEntry? = zipInputStream.nextEntry
                var fileIndex = 0
                
                while (entry != null) {
                    if (!entry.isDirectory) {
                        Log.d(CosMessageProcessor.TAG, "处理ZIP条目: ${entry.name}, size=${entry.size}")
                        
                        val extractedFile = extractZipEntryWithTransaction(zipInputStream, entry, fileIndex++, transaction)
                        if (extractedFile != null) {
                            val attachmentInfo = createAttachmentInfoFromZipEntry(entry, originalAttachmentInfo)
                            val databaseAttachment = createDatabaseAttachmentWithTransaction(extractedFile, attachmentInfo, transaction)
                            
                            if (databaseAttachment != null) {
                                val attachmentPointer = createAttachmentPointer(databaseAttachment, attachmentInfo)
                                attachmentPointers.add(attachmentPointer)
                                Log.i(CosMessageProcessor.TAG, "成功处理ZIP附件: ${entry.name}")
                            } else {
                                Log.w(CosMessageProcessor.TAG, "创建数据库附件失败: ${entry.name}")
                            }
                        }
                    }
                    
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }
            
            Log.i(CosMessageProcessor.TAG, "ZIP包处理完成: 总附件数=${attachmentPointers.size}")
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "处理ZIP包失败", e)
            // 发生异常时，事务会在调用方进行回滚
        }
        
        return attachmentPointers
    }

    /**
     * 从ZIP条目提取文件（事务性版本）
     */
    private fun extractZipEntryWithTransaction(
        zipInputStream: ZipInputStream, 
        entry: ZipEntry, 
        fileIndex: Int,
        transaction: AttachmentTransaction
    ): File? {
        return try {
            val tempFile = createSecureTempFile("zip_attachment_${fileIndex}_", ".tmp")
            
            // 记录临时文件用于回滚
            transaction.tempFiles.add(tempFile.absolutePath)
            
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (zipInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
            
            Log.d(CosMessageProcessor.TAG, "ZIP条目提取完成: ${entry.name}, size=${tempFile.length()}")
            tempFile
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "提取ZIP条目失败: ${entry.name}", e)
            null
        }
    }

    /**
     * 从ZIP条目提取文件（原始版本）
     */
    private fun extractZipEntry(zipInputStream: ZipInputStream, entry: ZipEntry, fileIndex: Int): File? {
        return try {
            val tempFile = createSecureTempFile("zip_attachment_${fileIndex}_", ".tmp")
            
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (zipInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
            
            Log.d(CosMessageProcessor.TAG, "ZIP条目提取完成: ${entry.name}, size=${tempFile.length()}")
            tempFile
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "提取ZIP条目失败: ${entry.name}", e)
            null
        }
    }

    /**
     * 从ZIP条目创建AttachmentInfo
     */
    fun createAttachmentInfoFromZipEntry(entry: ZipEntry, originalInfo: AttachmentInfo): AttachmentInfo {
        val fileName = entry.name.substringAfterLast('/')
        val mimeType = guessMimeType(fileName)
        
        return AttachmentInfo(
            fileName = fileName,
            mimeType = mimeType,
            size = entry.size,
            attachmentId = AttachmentInfo.generateAttachmentId()
        )
    }

    /**
     * 根据文件名猜测MIME类型
     */
    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * 创建附件占位符内容（当附件下载失败时）
     */
    private fun createAttachmentPlaceholderContent(
        cosMessage: CosMessage,
        attachmentInfo: AttachmentInfo,
        decryptedData: ByteArray
    ): Content {
        Log.i(CosMessageProcessor.TAG, "创建附件占位符消息: messageId=${cosMessage.messageId}, fileName=${attachmentInfo.fileName}")
        
        // 从解密数据中提取消息文本
        val messageText = extractMessageTextFromAttachmentData(decryptedData)
        
        // 创建附件下载失败的提示文本
        val placeholderText = if (!messageText.isNullOrEmpty()) {
            "$messageText\n\n附件下载失败: ${attachmentInfo.fileName} (${formatFileSize(attachmentInfo.size)})\n点击重试下载"
        } else {
            "附件下载失败: ${attachmentInfo.fileName} (${formatFileSize(attachmentInfo.size)})\n点击重试下载"
        }
        
        // 创建包含错误信息的DataMessage
        val dataMessage = DataMessage.Builder()
            .timestamp(cosMessage.timestamp)
            .body(placeholderText)
            .build()

        Log.i(CosMessageProcessor.TAG, "创建附件占位符完成: messageId=${cosMessage.messageId}")

        return Content.Builder()
            .dataMessage(dataMessage)
            .build()
    }

    /**
     * 格式化文件大小显示
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)}MB"
            else -> "${size / (1024 * 1024 * 1024)}GB"
        }
    }

    /**
     * 验证消息内容完整性
     */
    private fun validateMessageContent(content: Content, cosMessage: CosMessage): ValidationResult {
        try {
            // 基础验证
            if (content.dataMessage == null) {
                return ValidationResult(false, "缺少DataMessage")
            }

            val dataMessage = content.dataMessage
            
            // 验证时间戳
            if (dataMessage?.timestamp == null || dataMessage.timestamp!! <= 0) {
                return ValidationResult(false, "无效的时间戳")
            }

            // 根据消息类型进行特定验证
            when (cosMessage.messageType) {
                CosMessageType.TEXT -> {
                    if (dataMessage.body.isNullOrEmpty()) {
                        return ValidationResult(false, "文本消息内容为空")
                    }
                }
                CosMessageType.ATTACHMENT -> {
                    // 对于附件消息，检查是否有附件或错误提示
                    val hasAttachments = dataMessage?.attachments?.isNotEmpty() == true
                    val hasErrorMessage = dataMessage?.body?.contains("附件下载失败") == true
                    
                    if (!hasAttachments && !hasErrorMessage) {
                        return ValidationResult(false, "附件消息缺少附件和错误信息")
                    }
                    
                    // 如果有附件，验证附件指针
                    dataMessage?.attachments?.forEach { attachment ->
                        if (attachment.contentType.isNullOrEmpty()) {
                            return ValidationResult(false, "附件缺少MIME类型")
                        }
                        if (attachment.size == null || attachment.size!! <= 0) {
                            return ValidationResult(false, "附件大小无效")
                        }
                    }
                }
                else -> {
                    Log.d(CosMessageProcessor.TAG, "暂不验证消息类型: ${cosMessage.messageType}")
                }
            }

            Log.d(CosMessageProcessor.TAG, "消息内容验证通过: messageType=${cosMessage.messageType}")
            return ValidationResult(true, "验证通过")
            
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "消息内容验证异常", e)
            return ValidationResult(false, "验证异常: ${e.message}")
        }
    }

    /**
     * 验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val reason: String
    )

    /**
     * 记录处理统计信息
     */
    private fun recordProcessingStatistics(cosMessage: CosMessage, success: Boolean, reason: String = "") {
        try {
            val statisticsKey = "${cosMessage.senderId}-${cosMessage.messageType}"
            val currentTime = System.currentTimeMillis()
            
            Log.d(CosMessageProcessor.TAG, "记录处理统计: messageType=${cosMessage.messageType}, success=$success, reason=$reason")
            
            // 这里可以添加更详细的统计逻辑，比如：
            // - 消息类型分布统计
            // - 处理成功率统计
            // - 附件下载成功率统计
            // - 处理耗时统计
            
            when (cosMessage.messageType) {
                CosMessageType.ATTACHMENT -> {
                    if (success) {
                        Log.i(CosMessageProcessor.TAG, "📊 附件消息处理成功统计: messageId=${cosMessage.messageId}")
                    } else {
                        Log.w(CosMessageProcessor.TAG, "📊 附件消息处理失败统计: messageId=${cosMessage.messageId}, reason=$reason")
                    }
                }
                CosMessageType.TEXT -> {
                    if (success) {
                        Log.d(CosMessageProcessor.TAG, "📊 文本消息处理成功统计: messageId=${cosMessage.messageId}")
                    } else {
                        Log.w(CosMessageProcessor.TAG, "📊 文本消息处理失败统计: messageId=${cosMessage.messageId}, reason=$reason")
                    }
                }
                else -> {
                    Log.d(CosMessageProcessor.TAG, "📊 其他类型消息统计: messageType=${cosMessage.messageType}, success=$success")
                }
            }
            
        } catch (e: Exception) {
            Log.w(CosMessageProcessor.TAG, "记录处理统计失败", e)
        }
    }
    
    /**
     * 为COS附件创建AttachmentPointer
     * 让Signal原生的AttachmentDownloadJob处理下载，但下载时会被拦截到COS下载
     */
    private fun createAttachmentPointersForCos(
        cosMessage: CosMessage, 
        attachmentInfo: AttachmentInfo, 
        encryptionKey: ByteArray
    ): List<AttachmentPointer> {
        Log.i(CosMessageProcessor.TAG, "创建COS附件指针: messageId=${cosMessage.messageId}, attachmentId=${attachmentInfo.attachmentId}")
        
        return try {
            // 检查是否为多附件ZIP包（基于文件名和大小判断）
            val isZipPackage = attachmentInfo.mimeType == "application/zip" || 
                               attachmentInfo.fileName.lowercase().endsWith(".zip")
            
            if (isZipPackage) {
                Log.i(CosMessageProcessor.TAG, "检测到ZIP包附件，创建单个指针: messageId=${cosMessage.messageId}")
                // ZIP包作为单个附件处理，Signal下载后我们会在拦截器中解压
                listOf(createSingleAttachmentPointer(attachmentInfo, encryptionKey))
            } else {
                Log.d(CosMessageProcessor.TAG, "单个附件，创建单个指针: messageId=${cosMessage.messageId}")
                listOf(createSingleAttachmentPointer(attachmentInfo, encryptionKey))
            }
        } catch (e: Exception) {
            Log.e(CosMessageProcessor.TAG, "创建附件指针失败: messageId=${cosMessage.messageId}", e)
            emptyList()
        }
    }
    
    /**
     * 创建单个AttachmentPointer
     */
    private fun createSingleAttachmentPointer(attachmentInfo: AttachmentInfo, encryptionKey: ByteArray): AttachmentPointer {
        // 使用特殊的key格式来标识这是COS附件
        // Signal会尝试下载这个"假"的服务器附件，我们在AttachmentDownloadJob中拦截并使用COS下载
        val cosMarkerKey = "COS_ATTACHMENT:${attachmentInfo.attachmentId}:${attachmentInfo.cosPath ?: ""}"
        
        return AttachmentPointer.Builder()
            .contentType(attachmentInfo.mimeType)
            .size(attachmentInfo.size.toInt())
            .fileName(attachmentInfo.fileName)
            .key(cosMarkerKey.toByteArray().toByteString()) // 特殊标识COS附件
            .digest(encryptionKey.toByteString()) // 使用COS加密密钥作为digest传递
            .cdnNumber(COS_CDN_ID.toInt()) // 使用COS CDN常量，保持一致性
            .build()
    }

}

/**
 * 单条消息处理结果
 */
sealed class ProcessResult {
    object Success : ProcessResult()
    data class Failure(val reason: String) : ProcessResult()
    data class EarlyMessage(val expectedSequence: Long, val reason: String) : ProcessResult()
    data class NetworkError(val reason: String) : ProcessResult()
}

/**
 * 解密结果
 */
sealed class DecryptResult {
    data class Success(val data: ByteArray) : DecryptResult()
    data class NetworkError(val reason: String) : DecryptResult()
}
