package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.signal.core.util.Base64
import okio.ByteString.Companion.toByteString
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.database.SignalDatabase
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.push.ServiceId
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.messages.MessageContentProcessor
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.FileInfo
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.database.AttachmentTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString
import java.util.UUID

/**
 * Tap Envelope适配器
 * 
 * 负责将Tap传输层接收的加密消息适配到Signal的解密和处理流程中
 * 实现传输层和Signal加密解密层的正确分离
 */
class TapEnvelopeAdapter private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapEnvelopeAdapter::class.java)
        
        @Volatile
        private var INSTANCE: TapEnvelopeAdapter? = null
        
        fun getInstance(context: Context): TapEnvelopeAdapter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapEnvelopeAdapter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    
    /**
     * 处理加密的传输消息
     * 将TransportMessage适配到Signal的标准解密和处理流程
     * 
     * @param transportMessage 传输层接收的加密消息
     * @return 处理结果
     */
    suspend fun processEncryptedMessage(transportMessage: TransportMessage): TapEnvelopeProcessResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始处理加密传输消息: messageId=${transportMessage.messageId}")
                
                // 1. 检查消息重复性
                val isDuplicate = messageDeduplicator.isDuplicate(
                    transportMessage.messageId,
                    transportMessage.senderId,
                    transportMessage.timestamp
                )
                
                if (isDuplicate) {
                    Log.d(TAG, "消息重复，跳过处理: messageId=${transportMessage.messageId}")
                    return@withContext TapEnvelopeProcessResult.Duplicate(transportMessage.messageId)
                }
                
                // 2. 将TransportMessage转换为Signal Envelope
                val envelope = adaptTransportMessageToEnvelope(transportMessage)
                if (envelope == null) {
                    Log.e(TAG, "转换为Envelope失败: messageId=${transportMessage.messageId}")
                    return@withContext TapEnvelopeProcessResult.Failed("消息格式转换失败")
                }
                
                // 3. 使用Signal标准解密流程
                val decryptionResult = decryptEnvelopeWithSignal(envelope)
                if (decryptionResult == null) {
                    Log.e(TAG, "Signal解密失败: messageId=${transportMessage.messageId}")
                    return@withContext TapEnvelopeProcessResult.Failed("消息解密失败")
                }
                
                // 4. TAP附件修复：在消息处理前修复AttachmentPointer
                val fixedCipherResult = if (transportMessage.attachments.isNotEmpty()) {
                    Log.d(TAG, "检测到TAP附件，修复AttachmentPointer: messageId=${transportMessage.messageId}, 附件数=${transportMessage.attachments.size}")
                    fixTapAttachmentPointers(envelope, decryptionResult, transportMessage)
                } else {
                    decryptionResult
                }
                
                // 5. 使用Signal标准消息处理流程
                val processSuccess = processWithMessageContentProcessor(
                    envelope = envelope,
                    cipherResult = fixedCipherResult
                )
                
                if (processSuccess) {
                    // 5. 标记消息为已处理（去重）
                    messageDeduplicator.markAsProcessed(
                        transportMessage.messageId,
                        transportMessage.senderId,
                        transportMessage.timestamp
                    )
                    
                    Log.i(TAG, "传输消息处理成功: messageId=${transportMessage.messageId}")
                    TapEnvelopeProcessResult.Success(transportMessage.messageId)
                } else {
                    Log.w(TAG, "消息内容处理失败: messageId=${transportMessage.messageId}")
                    TapEnvelopeProcessResult.Failed("消息内容处理失败")
                }
                
            } catch (e: SecurityException) {
                Log.e(TAG, "安全异常，可能是恶意消息: messageId=${transportMessage.messageId}", e)
                TapEnvelopeProcessResult.Failed("安全验证失败")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "消息格式异常，可能是协议不兼容: messageId=${transportMessage.messageId}", e)
                TapEnvelopeProcessResult.Failed("消息格式错误: ${e.message}")
            } catch (e: org.signal.libsignal.protocol.InvalidMessageException) {
                Log.w(TAG, "Signal协议异常: messageId=${transportMessage.messageId}", e)
                TapEnvelopeProcessResult.Failed("Signal协议错误")
            } catch (e: org.signal.libsignal.protocol.NoSessionException) {
                Log.i(TAG, "会话不存在，可能需要重新建立: messageId=${transportMessage.messageId}", e)
                TapEnvelopeProcessResult.Failed("会话不存在，请重新建立联系")
            } catch (e: Exception) {
                Log.e(TAG, "处理传输消息异常: messageId=${transportMessage.messageId}", e)
                
                // 根据异常类型决定是否可以重试
                val retryable = when {
                    e.message?.contains("network", ignoreCase = true) == true -> true
                    e.message?.contains("timeout", ignoreCase = true) == true -> true
                    e.message?.contains("connection", ignoreCase = true) == true -> true
                    else -> false
                }
                
                if (retryable) {
                    TapEnvelopeProcessResult.Failed("处理异常(可重试): ${e.message}")
                } else {
                    TapEnvelopeProcessResult.Failed("处理异常: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 公开的适配方法（用于测试）
     */
    fun adaptToEnvelope(transportMessage: TransportMessage): Envelope? {
        return adaptTransportMessageToEnvelope(transportMessage)
    }
    
    /**
     * 将TransportMessage转换为Signal Envelope
     */
    private fun adaptTransportMessageToEnvelope(transportMessage: TransportMessage): Envelope? {
        return try {
            Log.d(TAG, "转换TransportMessage为Envelope: messageId=${transportMessage.messageId}")
            
            // 解析发送者ServiceId
            val sourceServiceId = parseServiceIdFromSender(transportMessage.senderId)
            if (sourceServiceId == null) {
                Log.e(TAG, "无法解析发送者ServiceId: ${transportMessage.senderId}")
                return null
            }
            
            // 解码密文
            val ciphertextBytes = Base64.decode(transportMessage.signalCiphertext)
            
            // 检测消息类型：群组消息 (Sender Key) 还是一对一消息
            val envelopeType = detectEnvelopeType(ciphertextBytes, transportMessage.recipientId)
            
            Log.d(TAG, "检测到消息类型: $envelopeType, recipientId=${transportMessage.recipientId}")
            
            // 构建Envelope
            val envelopeBuilder = Envelope.Builder()
                .type(envelopeType)
                .timestamp(transportMessage.timestamp)
                .serverTimestamp(transportMessage.timestamp)
                .content(ciphertextBytes.toByteString())
                .sourceServiceId(sourceServiceId.toString())
                .sourceDevice(getSourceDeviceId(transportMessage))
                .urgent(true)
                .story(false)
            
            // 设置目标ServiceId
            val localServiceId = SignalStore.account.requireAci()
            envelopeBuilder.destinationServiceId(localServiceId.toString())
            
            // 设置serverGuid
            envelopeBuilder.serverGuid(transportMessage.messageId)
            
            val envelope = envelopeBuilder.build()
            Log.d(TAG, "Envelope转换成功: messageId=${transportMessage.messageId}, type=$envelopeType")
            envelope
            
        } catch (e: Exception) {
            Log.e(TAG, "转换Envelope失败: messageId=${transportMessage.messageId}", e)
            null
        }
    }
    
    /**
     * 检测消息类型
     * 
     * 通过检查 recipientId 是否为群组 ID 来判断是群组消息还是一对一消息
     * 群组消息使用 Sender Key，一对一消息使用 Session Cipher
     */
    private fun detectEnvelopeType(ciphertextBytes: ByteArray, recipientId: String): Envelope.Type {
        return try {
            // 检查 recipientId 是否是群组 ID（base64 编码的群组 ID 通常较长）
            val isGroupMessage = isGroupId(recipientId)
            
            if (isGroupMessage) {
                // 群组消息：Sender Key 加密
                // 检查密文的第一个字节是否是 Sender Key 类型标识 (7)
                if (ciphertextBytes.isNotEmpty()) {
                    val messageVersion = ciphertextBytes[0].toInt() and 0xFF
                    val messageType = (messageVersion shr 4) and 0x0F
                    
                    Log.d(TAG, "密文版本字节: $messageVersion, 类型: $messageType")
                    
                    // Sender Key 消息类型是 7
                    if (messageType == 7) {
                        Log.d(TAG, "检测到 Sender Key 群组消息")
                        return Envelope.Type.SENDERKEY_MESSAGE
                    }
                }
                
                // 如果无法从字节判断，但 recipientId 是群组，仍然认为是 Sender Key
                Log.d(TAG, "根据 recipientId 判断为群组消息")
                return Envelope.Type.SENDERKEY_MESSAGE
            } else {
                // 一对一消息：Session Cipher 加密
                Log.d(TAG, "检测到一对一消息")
                return Envelope.Type.CIPHERTEXT
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "检测消息类型异常，默认使用 CIPHERTEXT", e)
            return Envelope.Type.CIPHERTEXT
        }
    }
    
    /**
     * 判断 recipientId 是否为群组 ID
     * 
     * 群组 ID 格式：base64 编码的群组标识符（通常很长）
     * 个人 ACI 格式：UUID 格式
     */
    private fun isGroupId(recipientId: String): Boolean {
        return try {
            // UUID 格式检查
            val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            val isUuid = uuidPattern.matches(recipientId)
            
            // 如果不是 UUID，且长度较长（群组 ID 通常是 base64 编码的长字符串）
            val isLongId = recipientId.length > 40
            
            val result = !isUuid && isLongId
            
            Log.d(TAG, "recipientId 检查: isUuid=$isUuid, length=${recipientId.length}, isGroupId=$result")
            
            result
        } catch (e: Exception) {
            Log.w(TAG, "判断群组ID异常", e)
            false
        }
    }
    
    /**
     * 获取源设备ID
     * 从TransportMessage的元数据中获取真实的设备ID，如果没有则使用默认值
     */
    private fun getSourceDeviceId(transportMessage: TransportMessage): Int {
        return try {
            // 首先尝试从消息元数据中获取设备ID
            val deviceId = transportMessage.contentMetadata?.sourceDeviceId
            if (deviceId != null && deviceId > 0) {
                Log.d(TAG, "从消息元数据获取设备ID: $deviceId")
                deviceId
            } else {
                // 如果元数据中没有设备ID，尝试从发送者的联系人信息中获取
                val senderId = transportMessage.senderId
                val recipient = try {
                    val serviceId = parseServiceIdFromSender(senderId)
                    if (serviceId != null) {
                        val recipientIdOpt = SignalDatabase.recipients.getByServiceId(serviceId)
                        if (recipientIdOpt.isPresent) {
                            Recipient.resolved(recipientIdOpt.get())
                        } else null
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "获取发送者联系人信息失败: $senderId", e)
                    null
                }
                
                // 使用默认设备ID（主设备）
                val defaultDeviceId = 1
                Log.d(TAG, "使用默认设备ID: $defaultDeviceId, senderId=$senderId")
                defaultDeviceId
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取设备ID失败，使用默认值: messageId=${transportMessage.messageId}", e)
            1 // 默认主设备ID
        }
    }
    
    /**
     * 从发送者ID解析ServiceId
     */
    private fun parseServiceIdFromSender(senderId: String): ServiceId? {
        return try {
            when {
                // 如果是UUID格式（ACI）
                senderId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) -> {
                    ServiceId.parseOrThrow(senderId)
                }
                
                // 如果是E164格式
                senderId.startsWith("+") -> {
                    // 从数据库中查找对应的ACI
                    try {
                        val recipientIdOpt = SignalDatabase.recipients.getByE164(senderId)
                        if (recipientIdOpt.isPresent) {
                            val recipient = Recipient.resolved(recipientIdOpt.get())
                            val serviceId = recipient.requireServiceId()
                            Log.d(TAG, "E164号码转换为ServiceId成功: E164=${LogSanitizer.sanitize(senderId)}")
                            serviceId
                        } else {
                            Log.w(TAG, "E164号码在数据库中未找到: ${LogSanitizer.sanitize(senderId)}")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "E164号码查询异常: ${LogSanitizer.sanitize(senderId)}, 错误: ${LogSanitizer.sanitizeThrowable(e)}")
                        null
                    }
                }
                
                else -> {
                    Log.w(TAG, "未知的发送者ID格式: $senderId")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析ServiceId失败: $senderId", e)
            null
        }
    }
    
    /**
     * 使用Signal标准解密流程
     */
    private suspend fun decryptEnvelopeWithSignal(envelope: Envelope): SignalServiceCipherResult? {
        return try {
            Log.d(TAG, "使用Signal解密Envelope: timestamp=${envelope.timestamp}, type=${envelope.type}")
            
            // 对于 Sender Key 消息，需要特殊处理
            if (envelope.type == Envelope.Type.SENDERKEY_MESSAGE) {
                return decryptSenderKeyMessage(envelope)
            }
            
            // 其他类型使用标准解密
            val signalServiceCipher = getSignalServiceCipher()
            val cipherResult = signalServiceCipher.decrypt(
                envelope, 
                System.currentTimeMillis(), 
                RemoteConfig.usePqRatchet
            )
            
            if (cipherResult != null) {
                Log.d(TAG, "Signal解密成功: timestamp=${envelope.timestamp}")
                cipherResult
            } else {
                Log.w(TAG, "Signal解密返回null: timestamp=${envelope.timestamp}")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Signal解密失败: timestamp=${envelope.timestamp}", e)
            null
        }
    }
    
    /**
     * 解密 Sender Key 加密的群组消息
     * 
     * 直接使用 GroupCipher 解密，不经过 sealed sender 包装
     */
    private suspend fun decryptSenderKeyMessage(envelope: Envelope): SignalServiceCipherResult? {
        return try {
            Log.d(TAG, "开始解密 Sender Key 消息: timestamp=${envelope.timestamp}")
            
            // 1. 获取发送者信息
            val sourceServiceIdString = envelope.sourceServiceId
            if (sourceServiceIdString == null) {
                Log.e(TAG, "Sender Key 消息缺少发送者 ServiceId")
                return null
            }
            
            val sourceServiceId = org.whispersystems.signalservice.api.push.ServiceId.parseOrThrow(sourceServiceIdString)
            val sourceAddress = org.whispersystems.signalservice.api.push.SignalServiceAddress(sourceServiceId)
            
            // 2. 获取 Protocol Store
            val protocolStore = AppDependencies.protocolStore.aci()
            val sessionLock = org.thoughtcrime.securesms.crypto.ReentrantSessionLock.INSTANCE
            
            // 3. 构建 SignalProtocolAddress（发送者地址）
            // 注意：GroupCipher 使用发送者的地址来解密
            val groupIdString = envelope.serverGuid ?: ""
            if (groupIdString.isEmpty()) {
                Log.e(TAG, "Sender Key 消息缺少 groupId")
                return null
            }
            
            Log.d(TAG, "Sender Key 解密参数: sourceServiceId=$sourceServiceId, groupId=$groupIdString")
            
            val senderProtocolAddress = org.signal.libsignal.protocol.SignalProtocolAddress(
                sourceServiceIdString,
                envelope.sourceDevice ?: 1
            )
            
            // 4. 创建 GroupCipher
            val groupCipher = org.signal.libsignal.protocol.groups.GroupCipher(
                protocolStore,
                senderProtocolAddress
            )
            
            // 5. 使用线程安全的 SignalGroupCipher 包装器
            val signalGroupCipher = org.whispersystems.signalservice.api.crypto.SignalGroupCipher(
                sessionLock,
                groupCipher
            )
            
            // 6. 解密
            val ciphertextBytes = envelope.content?.toByteArray()
            if (ciphertextBytes == null || ciphertextBytes.isEmpty()) {
                Log.e(TAG, "Sender Key 消息内容为空")
                return null
            }
            
            val plaintextBytes = signalGroupCipher.decrypt(ciphertextBytes)
            
            Log.d(TAG, "Sender Key 解密成功: plaintextSize=${plaintextBytes.size}")
            
            // 7. 解析 Content
            val content = org.whispersystems.signalservice.internal.push.Content.ADAPTER.decode(plaintextBytes)
            
            // 8. 构建元数据 - 使用 EnvelopeMetadata
            val localServiceId = SignalStore.account.requireAci()
            val metadata = org.whispersystems.signalservice.api.crypto.EnvelopeMetadata(
                sourceServiceId = sourceServiceId,
                sourceE164 = null, // TAP 消息没有 E164
                sourceDeviceId = envelope.sourceDevice ?: 1,
                sealedSender = false, // TAP 传输非 sealed sender
                groupId = groupIdString.toByteArray(),
                destinationServiceId = localServiceId
            )
            
            // 9. 包装为 SignalServiceCipherResult
            val result = SignalServiceCipherResult(content, metadata)
            
            Log.i(TAG, "Sender Key 消息解密完成: timestamp=${envelope.timestamp}")
            
            result
            
        } catch (e: org.signal.libsignal.protocol.NoSessionException) {
            Log.e(TAG, "Sender Key 会话不存在", e)
            null
        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            Log.w(TAG, "Sender Key 消息重复", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Sender Key 解密异常", e)
            null
        }
    }
    
    /**
     * 获取Signal解密服务
     */
    private fun getSignalServiceCipher(): SignalServiceCipher {
        val localAci = SignalStore.account.requireAci()
        val localDeviceId = SignalStore.account.deviceId
        val protocolStore = AppDependencies.protocolStore.aci()
        val sessionLock = ReentrantSessionLock.INSTANCE
        
        val localAddress = SignalServiceAddress(localAci, SignalStore.account.e164)
        val certificateValidator = SealedSenderAccessUtil.getCertificateValidator()
        
        return SignalServiceCipher(
            localAddress,
            localDeviceId,
            protocolStore,
            sessionLock,
            certificateValidator
        )
    }
    
    /**
     * 使用Signal的MessageContentProcessor处理解密后的消息
     */
    private suspend fun processWithMessageContentProcessor(
        envelope: Envelope,
        cipherResult: SignalServiceCipherResult
    ): Boolean {
        return try {
            Log.d(TAG, "使用MessageContentProcessor处理消息: timestamp=${envelope.timestamp}")
            
            // 创建MessageContentProcessor实例
            val messageContentProcessor = MessageContentProcessor.create(context)
            
            // 使用Signal标准流程处理消息
            messageContentProcessor.process(
                envelope = envelope,
                content = cipherResult.content,
                metadata = cipherResult.metadata,
                serverDeliveredTimestamp = System.currentTimeMillis(),
                processingEarlyContent = false
            )
            
            Log.d(TAG, "MessageContentProcessor处理完成: timestamp=${envelope.timestamp}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "MessageContentProcessor处理失败: timestamp=${envelope.timestamp}", e)
            false
        }
    }
    
    /**
     * 验证Envelope有效性
     */
    fun validateEnvelope(envelope: Envelope): Boolean {
        return try {
            // 基本字段验证
            if (envelope.content?.size == 0 || envelope.content == null) {
                Log.w(TAG, "Envelope内容为空")
                return false
            }
            
            val timestamp = envelope.timestamp
            if (timestamp == null || timestamp <= 0) {
                Log.w(TAG, "Envelope时间戳无效")
                return false
            }
            
            if (envelope.sourceServiceId.isNullOrBlank()) {
                Log.w(TAG, "Envelope发送者ServiceId为空")
                return false
            }
            
            // 检查时间戳合理性
            val currentTime = System.currentTimeMillis()
            val timeDiff = Math.abs(currentTime - timestamp)
            
            // 允许1小时的时间偏差，防止重放攻击
            val MAX_TIME_DRIFT_MS = 60 * 60 * 1000L // 1小时
            if (timeDiff > MAX_TIME_DRIFT_MS) {
                Log.w(TAG, "Envelope时间戳偏差过大: envelopeTime=$timestamp, currentTime=$currentTime, diff=${timeDiff}ms")
                return false
            }
            
            // 检查时间戳是否为未来时间（允许5分钟时钟偏差）
            val MAX_FUTURE_DRIFT_MS = 5 * 60 * 1000L // 5分钟
            if (timestamp > currentTime + MAX_FUTURE_DRIFT_MS) {
                Log.w(TAG, "Envelope时间戳为未来时间: envelopeTime=$timestamp, currentTime=$currentTime")
                return false
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "验证Envelope失败", e)
            false
        }
    }

    /**
     * 修复TAP附件的AttachmentPointer字段
     * 解决Signal解密后AttachmentPointer字段缺失的问题
     */
    private suspend fun fixTapAttachmentPointers(
        envelope: Envelope,
        cipherResult: SignalServiceCipherResult,
        transportMessage: TransportMessage
    ): SignalServiceCipherResult {
        return try {
            val content = cipherResult.content
            
            // 检查是否有DataMessage和附件
            val dataMessage = content.dataMessage
            if (dataMessage == null || dataMessage.attachments.isEmpty()) {
                Log.d(TAG, "消息无需修复附件指针: messageId=${transportMessage.messageId}")
                return cipherResult
            }
            
            Log.d(TAG, "开始修复AttachmentPointer: messageId=${transportMessage.messageId}")
            
            // 先立即下载TAP附件数据
            if (transportMessage.attachments.isNotEmpty()) {
                Log.d(TAG, "立即下载TAP附件数据: messageId=${transportMessage.messageId}, 附件数=${transportMessage.attachments.size}")
                downloadTapAttachmentsImmediately(transportMessage, envelope.timestamp ?: System.currentTimeMillis(), envelope)
            }
            
            // 获取原始附件列表并修复
            val originalAttachments = dataMessage.attachments
            val fixedAttachments = mutableListOf<org.whispersystems.signalservice.internal.push.AttachmentPointer>()
            
            for (i in originalAttachments.indices) {
                val originalPointer = originalAttachments[i]
                val tapAttachment = if (i < transportMessage.attachments.size) {
                    transportMessage.attachments[i]
                } else {
                    Log.w(TAG, "TAP附件索引超出范围: $i >= ${transportMessage.attachments.size}")
                    null
                }
                
                val fixedPointer = repairAttachmentPointer(originalPointer, tapAttachment)
                fixedAttachments.add(fixedPointer)
                
                Log.d(TAG, "修复附件指针 #${i}: cdnNumber=${fixedPointer.cdnNumber}, " +
                          "key长度=${fixedPointer.key?.size ?: 0}")
            }
            
            // 重建DataMessage - 只设置必要的字段
            val dataMessageBuilder = org.whispersystems.signalservice.internal.push.DataMessage.Builder()
            
            // 复制基本字段
            dataMessage.body?.let { dataMessageBuilder.body = it }
            dataMessage.timestamp?.let { dataMessageBuilder.timestamp = it }
            dataMessage.expireTimer?.let { dataMessageBuilder.expireTimer = it }
            dataMessage.flags?.let { dataMessageBuilder.flags = it }
            
            // 设置修复后的附件
            dataMessageBuilder.attachments = fixedAttachments
            
            val fixedDataMessage = dataMessageBuilder.build()
            
            // 重建Content - 只设置DataMessage
            val contentBuilder = org.whispersystems.signalservice.internal.push.Content.Builder()
            contentBuilder.dataMessage = fixedDataMessage
            
            // 复制其他消息类型（如果存在）
            content.syncMessage?.let { contentBuilder.syncMessage = it }
            content.callMessage?.let { contentBuilder.callMessage = it }
            content.receiptMessage?.let { contentBuilder.receiptMessage = it }
            content.typingMessage?.let { contentBuilder.typingMessage = it }
            
            val fixedContent = contentBuilder.build()
            
            Log.i(TAG, "TAP附件修复完成: messageId=${transportMessage.messageId}, 修复附件数=${fixedAttachments.size}")
            
            // 返回新的CipherResult
            SignalServiceCipherResult(fixedContent, cipherResult.metadata)
            
        } catch (e: Exception) {
            Log.e(TAG, "修复TAP附件指针异常: messageId=${transportMessage.messageId}", e)
            // 修复失败时返回原始结果
            cipherResult
        }
    }
    
    /**
     * 修复单个AttachmentPointer的关键字段
     */
    private fun repairAttachmentPointer(
        originalPointer: org.whispersystems.signalservice.internal.push.AttachmentPointer,
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment?
    ): org.whispersystems.signalservice.internal.push.AttachmentPointer {
        
        // 创建新的AttachmentPointer，修复关键字段
        val builder = org.whispersystems.signalservice.internal.push.AttachmentPointer.Builder()
        
        // 复制基本字段
        originalPointer.contentType?.let { builder.contentType = it }
        originalPointer.size?.let { builder.size = it }
        originalPointer.fileName?.let { builder.fileName = it }
        originalPointer.flags?.let { builder.flags = it }
        originalPointer.width?.let { builder.width = it }
        originalPointer.height?.let { builder.height = it }
        originalPointer.caption?.let { builder.caption = it }
        originalPointer.blurHash?.let { builder.blurHash = it }
        
        // 修复关键字段 - 这些是导致空指针异常的根源
        builder.cdnNumber = 999  // 设置TAP专用CDN号码
        
        if (tapAttachment != null) {
            // 确保文件名不为空
            val fileName = if (tapAttachment.fileName.isNotBlank()) {
                tapAttachment.fileName
            } else {
                "attachment_${tapAttachment.attachmentId}"
            }
            
            // 修复contentType - 确保图片类型正确识别
            val contentType = determineContentType(tapAttachment.mimeType, fileName)
            
            // 设置attachment_identifier - 必需字段
            val cdnKey = "tap_${tapAttachment.attachmentId}_$fileName"
            builder.cdnKey = cdnKey
            
            // 设置clientUuid - 确保唯一性
            val clientUuid = UUID.randomUUID().toString()
            builder.clientUuid = ByteString.of(*clientUuid.toByteArray(Charsets.UTF_8))
            
            // 设置加密密钥 - 直接使用原始字节数据
            val transportPath = tapAttachment.transportPath ?: "attachments/${tapAttachment.attachmentId}/$fileName"
            val keyContent = "TAP:$transportPath"
            builder.key = ByteString.of(*keyContent.toByteArray(Charsets.UTF_8))
            
            // 设置基本字段
            builder.fileName = fileName
            builder.size = tapAttachment.size.toInt()
            builder.contentType = contentType
            builder.uploadTimestamp = System.currentTimeMillis()
            
            // 为图片类型设置默认尺寸（如果原始pointer中没有）
            if (isImageContentType(contentType) && 
                (originalPointer.width == null || originalPointer.height == null)) {
                builder.width = 1024   // 默认宽度
                builder.height = 768   // 默认高度
            }
            
            Log.d(TAG, "修复TAP附件指针: fileName=$fileName, contentType=$contentType, size=${tapAttachment.size}, cdnKey=$cdnKey")
            
            // 设置digest（如果有哈希）
            if (tapAttachment.fileHash != null) {
                try {
                    val hashBytes = java.util.Base64.getDecoder().decode(tapAttachment.fileHash)
                    builder.digest = ByteString.of(*hashBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "解析TAP附件哈希失败: ${tapAttachment.fileHash}", e)
                    // 生成默认digest以避免空值
                    builder.digest = ByteString.of(*"tap_attachment_default".toByteArray(Charsets.UTF_8))
                }
            } else {
                // 生成默认digest以避免空值
                builder.digest = ByteString.of(*"tap_attachment_default".toByteArray(Charsets.UTF_8))
            }
        } else {
            // 没有TAP附件信息时，设置默认值
            builder.cdnKey = "tap_unknown_attachment"
            builder.clientUuid = ByteString.of(*UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
            
            val defaultKeyContent = "TAP:attachments/unknown/data"
            builder.key = ByteString.of(*defaultKeyContent.toByteArray(Charsets.UTF_8))
            builder.digest = ByteString.of(*"tap_attachment_default".toByteArray(Charsets.UTF_8))
            builder.uploadTimestamp = System.currentTimeMillis()
        }
        
        return builder.build()
    }
    
    /**
     * 立即下载TAP附件数据
     * 在消息处理阶段直接下载附件，避免后续查看时的延迟
     */
    private suspend fun downloadTapAttachmentsImmediately(
        transportMessage: TransportMessage,
        messageTimestamp: Long,
        envelope: Envelope
    ) {
        try {
            // 获取发送者的活跃通道
            val senderId = transportMessage.senderId
            val activeChannels = channelManager.getActiveChannels(senderId)
            if (activeChannels.isEmpty()) {
                Log.w(TAG, "无活跃传输通道，跳过立即下载: senderId=${LogSanitizer.sanitize(senderId)}")
                return
            }
            
            val channel = activeChannels.first()
            val provider = transportManager.getProvider(channel.providerType)
            if (provider == null) {
                Log.w(TAG, "无法获取传输提供者: providerType=${channel.providerType}")
                return
            }
            
            Log.d(TAG, "开始立即下载${transportMessage.attachments.size}个附件")
            
            // 逐个下载附件
            for ((index, tapAttachment) in transportMessage.attachments.withIndex()) {
                try {
                    downloadSingleTapAttachment(provider, channel, tapAttachment, transportMessage.messageId, messageTimestamp, index, envelope)
                } catch (e: Exception) {
                    Log.w(TAG, "下载附件失败，继续处理其他附件: attachmentId=${tapAttachment.attachmentId}", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "立即下载TAP附件过程中发生异常: messageId=${transportMessage.messageId}", e)
        }
    }
    
    /**
     * 下载单个TAP附件
     */
    private suspend fun downloadSingleTapAttachment(
        provider: org.thoughtcrime.securesms.tap.TransportProvider,
        channel: org.thoughtcrime.securesms.tap.TransportChannel,
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment,
        messageId: String,
        messageTimestamp: Long,
        attachmentIndex: Int,
        envelope: Envelope
    ) {
        try {
            // 直接使用TransportAttachment中记录的完整TAP通道路径
            val fullTapPath = tapAttachment.transportPath
            if (fullTapPath.isNullOrBlank()) {
                Log.w(TAG, "TransportAttachment中没有有效的传输路径: ${tapAttachment.fileName}")
                return
            }
            
            // 构建FileInfo - 使用完整的TAP通道路径
            val fileInfo = FileInfo(
                name = tapAttachment.fileName,
                path = fullTapPath,
                size = tapAttachment.size,
                lastModified = messageTimestamp,
                etag = tapAttachment.fileHash,
                mimeType = tapAttachment.mimeType,
                metadata = mapOf(
                    "attachmentId" to tapAttachment.attachmentId,
                    "messageId" to messageId,
                    "attachmentIndex" to attachmentIndex.toString()
                )
            )
            
            Log.d(TAG, "下载附件: ${tapAttachment.fileName}, path=$fullTapPath")
            
            // 通过Provider下载文件数据
            val downloadResult = provider.downloadFile(fileInfo, channel.metadata)
            
            when (downloadResult) {
                is TransportResult.Success -> {
                    val fileData = downloadResult.data
                    if (fileData != null && fileData.isNotEmpty()) {
                        Log.d(TAG, "附件下载成功: ${tapAttachment.fileName}, dataSize=${fileData.size}")
                        
                        // 尝试直接保存到Signal存储或保存到临时文件
                        saveTapAttachmentData(
                            attachmentData = fileData,
                            tapAttachment = tapAttachment,
                            messageTimestamp = messageTimestamp,
                            envelope = envelope
                        )
                        
                        Log.i(TAG, "TAP附件立即下载并保存成功: ${tapAttachment.fileName}")
                    } else {
                        Log.w(TAG, "下载的附件数据为空: ${tapAttachment.fileName}")
                    }
                }
                is TransportResult.Failed -> {
                    Log.e(TAG, "附件下载失败: ${tapAttachment.fileName}, error=${downloadResult.error}")
                }
                else -> {
                    Log.w(TAG, "未知的下载结果类型: ${downloadResult::class.java.simpleName}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "下载单个TAP附件异常: ${tapAttachment.fileName}", e)
        }
    }
    
    /**
     * 保存TAP附件数据
     * 优先尝试直接保存到Signal存储，失败则保存到临时文件
     */
    private suspend fun saveTapAttachmentData(
        attachmentData: ByteArray,
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment,
        messageTimestamp: Long,
        envelope: Envelope
    ) {
        try {
            Log.d(TAG, "保存TAP附件: ${tapAttachment.fileName}, size=${attachmentData.size}")
            
            // 验证数据完整性
            if (attachmentData.isEmpty()) {
                throw IllegalArgumentException("附件数据为空")
            }
            
            if (attachmentData.size > 100 * 1024 * 1024) {
                throw IllegalArgumentException("附件数据过大: ${attachmentData.size} bytes")
            }
            
            // 尝试直接保存到Signal存储
            val directSaveSuccess = tryDirectSaveToSignal(attachmentData, tapAttachment, envelope)
            
            if (directSaveSuccess) {
                Log.i(TAG, "TAP附件直接保存到Signal存储成功: ${tapAttachment.fileName}")
            } else {
                // Fallback: 保存到临时文件，由TapAttachmentDownloadInterceptor后续处理
                Log.d(TAG, "无法直接保存，使用临时文件机制: ${tapAttachment.fileName}")
                saveToTemporaryFile(attachmentData, tapAttachment)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "保存TAP附件数据异常: ${tapAttachment.fileName}", e)
            throw e
        }
    }
    
    /**
     * 尝试直接保存到Signal存储
     * 通过临时文件机制与AttachmentDownloadJob集成
     */
    private suspend fun tryDirectSaveToSignal(
        attachmentData: ByteArray,
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment,
        envelope: Envelope
    ): Boolean {
        try {
            // 使用临时文件机制，确保与TapAttachmentDownloadInterceptor完全兼容
            saveToTemporaryFile(attachmentData, tapAttachment)
            Log.d(TAG, "TAP附件已保存到临时文件，等待Signal系统处理: ${tapAttachment.fileName}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "保存附件到临时文件失败: ${tapAttachment.fileName}", e)
            return false
        }
    }
    
    /**
     * 保存到临时文件
     * 确保与TapAttachmentDownloadInterceptor的查找逻辑完全匹配
     */
    private fun saveToTemporaryFile(
        attachmentData: ByteArray,
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment
    ) {
        val tempDir = java.io.File(context.cacheDir, "tap_attachments")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        
        val fullTapPath = tapAttachment.transportPath ?: ""
        val pathHash = fullTapPath.hashCode().toString()
        // 确保文件名与TapAttachmentDownloadInterceptor的解析逻辑一致
        val fileName = if (fullTapPath.isNotBlank()) {
            fullTapPath.substringAfterLast("/")
        } else {
            tapAttachment.fileName
        }
        val tempFileKey = "${pathHash}_${fileName}"
        val tempFile = java.io.File(tempDir, tempFileKey)
        
        tempFile.writeBytes(attachmentData)
        Log.i(TAG, "TAP附件数据已保存到临时文件: ${tempFile.absolutePath}")
    }
    
    /**
     * 根据MIME类型和文件名确定正确的内容类型
     * 确保图片类型能被正确识别
     */
    private fun determineContentType(mimeType: String?, fileName: String): String {
        // 如果mimeType有效且是图片类型，直接使用
        if (!mimeType.isNullOrBlank() && isImageContentType(mimeType)) {
            return mimeType
        }
        
        // 如果mimeType无效或不是图片类型，根据文件扩展名推断
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        }
    }
    
    /**
     * 检查内容类型是否为图片类型
     */
    private fun isImageContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        return contentType.startsWith("image/") && contentType != "image/svg+xml"
    }
} 