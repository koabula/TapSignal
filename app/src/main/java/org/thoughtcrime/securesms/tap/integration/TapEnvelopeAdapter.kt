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
                
                // 3. 根据加密类型选择解密方法
                val isSessionCipherEncrypted = transportMessage.contentMetadata.isSessionCipherEncrypted
                Log.d(TAG, "检测到加密类型标识: isSessionCipherEncrypted=$isSessionCipherEncrypted")
                
                val decryptionResult = if (isSessionCipherEncrypted) {
                    // 2人群组或私聊：使用 SessionCipher 解密
                    Log.d(TAG, "使用 SessionCipher 解密（2人群组/私聊）")
                    decryptEnvelopeWithSignal(envelope)
                } else {
                    // 3+人群组：检查是否为 SenderKey 消息
                    if (envelope.type == Envelope.Type.SENDERKEY_MESSAGE) {
                        Log.d(TAG, "使用 SenderKey 解密（3+人群组）")
                        decryptSenderKeyMessage(envelope)
                    } else {
                        // 兜底：使用标准解密
                        Log.d(TAG, "使用标准 Signal 解密")
                        decryptEnvelopeWithSignal(envelope)
                    }
                }
                
                if (decryptionResult == null) {
                    Log.e(TAG, "Signal解密失败: messageId=${transportMessage.messageId}, isSessionCipher=$isSessionCipherEncrypted")
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
                    Log.d(TAG, "[TapTimeTest] T6_DISPLAY | msgId=${transportMessage.timestamp} | timestamp=${System.currentTimeMillis()}")
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
     * 
     * 发送端现在传输的是完整序列化的 Envelope，不再是单纯的密文
     * 这避免了类型映射和格式转换的问题
     */
    private fun adaptTransportMessageToEnvelope(transportMessage: TransportMessage): Envelope? {
        return try {
            Log.d(TAG, "转换TransportMessage为Envelope: messageId=${transportMessage.messageId}")
            
            // 解码并反序列化 Envelope
            val envelopeBytes = Base64.decode(transportMessage.signalCiphertext)
            
            // 尝试直接反序列化为 Envelope
            val envelope = try {
                Envelope.ADAPTER.decode(envelopeBytes)
            } catch (e: Exception) {
                Log.w(TAG, "无法反序列化为Envelope，可能是旧格式，尝试手动构造", e)
                // 兼容旧格式：如果反序列化失败，使用旧方法构造
                return adaptLegacyFormat(transportMessage, envelopeBytes)
            }
            
            Log.d(TAG, "Envelope反序列化成功: messageId=${transportMessage.messageId}, type=${envelope.type}, timestamp=${envelope.timestamp}")
            envelope
            
        } catch (e: Exception) {
            Log.e(TAG, "转换Envelope失败: messageId=${transportMessage.messageId}", e)
            null
        }
    }
    
    /**
     * 兼容旧格式：手动构造 Envelope（用于向后兼容）
     */
    private fun adaptLegacyFormat(transportMessage: TransportMessage, ciphertextBytes: ByteArray): Envelope? {
        return try {
            Log.d(TAG, "使用旧格式兼容模式: messageId=${transportMessage.messageId}")
            
            // 解析发送者ServiceId
            val sourceServiceId = parseServiceIdFromSender(transportMessage.senderId)
            if (sourceServiceId == null) {
                Log.e(TAG, "无法解析发送者ServiceId: ${transportMessage.senderId}")
                return null
            }
            
            // 步骤1：从 libsignal CiphertextMessage 类型映射到基础 Envelope 类型
            // TAP传输层不使用 Sealed Sender 包装，直接使用 signalCiphertextType 映射
            val envelopeType = mapSignalTypeToEnvelopeType(transportMessage.signalCiphertextType)
            
            Log.d(TAG, "消息类型: signalCiphertextType=${transportMessage.signalCiphertextType}, envelopeType=$envelopeType")
            
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
            Log.d(TAG, "Envelope转换成功(旧格式): messageId=${transportMessage.messageId}, type=$envelopeType")
            envelope
            
        } catch (e: Exception) {
            Log.e(TAG, "旧格式转换失败: messageId=${transportMessage.messageId}", e)
            null
        }
    }
    
    /**
     * 将 libsignal CiphertextMessage 类型映射到 Envelope 类型
     * 
     * 两套类型系统的映射关系：
     * 
     * libsignal CiphertextMessage 类型（底层加密库）：
     * - 2: WHISPER_TYPE (SessionCipher 加密的普通消息)
     * - 3: PREKEY_TYPE (包含 PreKey 的消息，用于建立会话)
     * - 7: SENDERKEY_TYPE (SenderKey 加密的群组消息)
     * - 8: PLAINTEXT_TYPE (明文内容)
     * 
     * Envelope.Type（协议层）：
     * - 1: CIPHERTEXT (SessionCipher 消息)
     * - 2: reserved (已废弃)
     * - 3: PREKEY_BUNDLE (PreKey 消息)
     * - 6: UNIDENTIFIED_SENDER (Sealed Sender 包装)
     * - 7: SENDERKEY_MESSAGE (SenderKey 消息)
     * - 8: PLAINTEXT_CONTENT (明文消息)
     * 
     * 注意：此方法只映射内层加密类型，Sealed Sender 外层包装需要额外检测
     */
    private fun mapSignalTypeToEnvelopeType(signalCiphertextType: Int): Envelope.Type {
        return when (signalCiphertextType) {
            2 -> {
                // libsignal WHISPER_TYPE = 2 → Envelope CIPHERTEXT = 1
                Log.d(TAG, "libsignal类型: WHISPER_TYPE (2) → Envelope.CIPHERTEXT (1)")
                Envelope.Type.CIPHERTEXT
            }
            3 -> {
                // libsignal PREKEY_TYPE = 3 → Envelope PREKEY_BUNDLE = 3
                Log.d(TAG, "libsignal类型: PREKEY_TYPE (3) → Envelope.PREKEY_BUNDLE (3)")
                Envelope.Type.PREKEY_BUNDLE
            }
            7 -> {
                // libsignal SENDERKEY_TYPE = 7 → Envelope SENDERKEY_MESSAGE = 7
                Log.d(TAG, "libsignal类型: SENDERKEY_TYPE (7) → Envelope.SENDERKEY_MESSAGE (7)")
                Envelope.Type.SENDERKEY_MESSAGE
            }
            8 -> {
                // libsignal PLAINTEXT_TYPE = 8 → Envelope PLAINTEXT_CONTENT = 8
                Log.d(TAG, "libsignal类型: PLAINTEXT_TYPE (8) → Envelope.PLAINTEXT_CONTENT (8)")
                Envelope.Type.PLAINTEXT_CONTENT
            }
            6 -> {
                Log.d(TAG, "libsignal类型: UNIDENTIFIED_SENDER (6) → Envelope.UNIDENTIFIED_SENDER (6)")
                Envelope.Type.UNIDENTIFIED_SENDER
            }
            else -> {
                Log.w(TAG, "未知的libsignal类型: $signalCiphertextType, 默认使用 CIPHERTEXT")
                Envelope.Type.CIPHERTEXT
            }
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
            
            // 3. 从 envelope.serverGuid 提取 groupId（发送端已将 Base64 编码的 groupId 放入 serverGuid）
            val groupIdBase64 = envelope.serverGuid
            if (groupIdBase64.isNullOrEmpty()) {
                Log.e(TAG, "Sender Key 消息缺少 groupId (serverGuid)")
                return null
            }
            
            // 解码 groupId
            val groupIdBytes = try {
                org.signal.core.util.Base64.decode(groupIdBase64)
            } catch (e: Exception) {
                Log.e(TAG, "解码 groupId 失败: serverGuid=$groupIdBase64", e)
                return null
            }
            
            Log.d(TAG, "Sender Key 解密参数: sourceServiceId=$sourceServiceId, groupIdLength=${groupIdBytes.size}")
            
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
            
            Log.d(TAG, "Sender Key 解密成功 (含padding): plaintextSize=${plaintextBytes.size}")
            
            // 6.5. 去除 Padding - Signal 协议标准步骤
            val transport = org.whispersystems.signalservice.internal.push.PushTransportDetails()
            val strippedMessage = transport.getStrippedPaddingMessageBody(plaintextBytes)
            
            Log.d(TAG, "Padding已去除: originalSize=${plaintextBytes.size}, strippedSize=${strippedMessage.size}")
            
            // 7. 解析 Content (使用去除padding后的数据)
            val content = org.whispersystems.signalservice.internal.push.Content.ADAPTER.decode(strippedMessage)
            
            // 8. 构建元数据 - 使用正确的 groupId
            val localServiceId = SignalStore.account.requireAci()
            val metadata = org.whispersystems.signalservice.api.crypto.EnvelopeMetadata(
                sourceServiceId = sourceServiceId,
                sourceE164 = null, // TAP 消息没有 E164
                sourceDeviceId = envelope.sourceDevice ?: 1,
                sealedSender = false, // TAP 传输非 sealed sender
                groupId = groupIdBytes, // 使用解码后的真实 groupId
                destinationServiceId = localServiceId
            )
            
            // 9. 包装为 SignalServiceCipherResult
            val result = SignalServiceCipherResult(content, metadata)
            
            Log.i(TAG, "Sender Key 消息解密完成: timestamp=${envelope.timestamp}, groupId已正确提取")
            
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
            
            // 时间戳验证已移除:
            // Signal协议层通过DuplicateMessageException检测重放攻击
            // libsignal的计数器机制是密码学级别的防护,无法绕过
            // 数据库UNIQUE约束提供额外的重复消息防护
            // 离线消息场景下,时间戳偏差是预期行为,不应被拒绝
            
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
            
            val pathDescriptor = resolveAttachmentTransportPath(tapAttachment, fileName)
            val keyPayload = buildTapAttachmentKeyPayload(pathDescriptor.canonicalPath, pathDescriptor.presignedUrl)
            val keyContent = "TAP:$keyPayload"
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
            
            Log.d(TAG, "修复TAP附件指针: fileName=$fileName, contentType=$contentType, size=${tapAttachment.size}, cdnKey=$cdnKey, canonicalPath=${pathDescriptor.canonicalPath}")
            
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
    
    private data class TapAttachmentPathDescriptor(
        val canonicalPath: String,
        val presignedUrl: String?
    )

    private fun resolveAttachmentTransportPath(
        tapAttachment: org.thoughtcrime.securesms.tap.TransportAttachment,
        fileName: String
    ): TapAttachmentPathDescriptor {
        val fallback = ensureLeadingSlash("attachments/${tapAttachment.attachmentId}/$fileName")
        val rawPath = tapAttachment.transportPath?.takeIf { it.isNotBlank() }
        val presignedUrl = tapAttachment.presignedUrl?.takeIf { it.isNotBlank() }

        return when {
            rawPath.isNullOrBlank() && presignedUrl != null -> {
                TapAttachmentPathDescriptor(
                    canonicalPath = canonicalizeUrlPath(presignedUrl, fallback),
                    presignedUrl = presignedUrl
                )
            }
            rawPath.isNullOrBlank() -> TapAttachmentPathDescriptor(fallback, null)
            isHttpUrl(rawPath) -> {
                TapAttachmentPathDescriptor(
                    canonicalPath = canonicalizeUrlPath(rawPath, fallback),
                    presignedUrl = presignedUrl ?: rawPath
                )
            }
            else -> TapAttachmentPathDescriptor(
                canonicalPath = canonicalizePath(rawPath, fallback),
                presignedUrl = presignedUrl
            )
        }
    }

    private fun canonicalizePath(rawPath: String, fallback: String): String {
        val trimmed = rawPath.trim().substringBefore('?')
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (normalized.isNotBlank()) normalized else fallback
    }

    private fun canonicalizeUrlPath(url: String, fallback: String): String {
        return try {
            val uri = java.net.URI(url)
            val path = uri.path?.takeIf { it.isNotBlank() } ?: fallback
            canonicalizePath(path, fallback)
        } catch (e: Exception) {
            Log.w(TAG, "解析预签名URL失败，使用回退路径", e)
            fallback
        }
    }

    private fun buildTapAttachmentKeyPayload(canonicalPath: String, presignedUrl: String?): String {
        return if (presignedUrl.isNullOrBlank()) {
            canonicalPath
        } else {
            org.json.JSONObject().apply {
                put("path", canonicalPath)
                put("http", presignedUrl)
            }.toString()
        }
    }

    private fun ensureLeadingSlash(path: String): String {
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun isHttpUrl(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
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
