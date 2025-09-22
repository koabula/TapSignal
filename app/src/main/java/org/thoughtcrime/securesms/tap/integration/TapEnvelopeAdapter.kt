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
                
                // 3. 使用Signal标准解密流程
                val decryptionResult = decryptEnvelopeWithSignal(envelope)
                if (decryptionResult == null) {
                    Log.e(TAG, "Signal解密失败: messageId=${transportMessage.messageId}")
                    return@withContext TapEnvelopeProcessResult.Failed("消息解密失败")
                }
                
                // 4. 使用Signal标准消息处理流程
                val processSuccess = processWithMessageContentProcessor(
                    envelope = envelope,
                    cipherResult = decryptionResult
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
                
            } catch (e: Exception) {
                Log.e(TAG, "处理传输消息异常: messageId=${transportMessage.messageId}", e)
                TapEnvelopeProcessResult.Failed("处理异常: ${e.message}")
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
            
            // 构建Envelope
            val envelopeBuilder = Envelope.Builder()
                .type(Envelope.Type.CIPHERTEXT)  // 假设是密文消息
                .timestamp(transportMessage.timestamp)
                .content(Base64.decode(transportMessage.signalCiphertext).toByteString())
                .sourceServiceId(sourceServiceId.toString())
                .sourceDevice(1) // 默认设备ID
            
            // 如果有目标ServiceId，设置它
            val localServiceId = SignalStore.account.requireAci()
            envelopeBuilder.destinationServiceId(localServiceId.toString())
            
            // 使用消息时间戳设置serverGuid
            envelopeBuilder.serverGuid(transportMessage.messageId)
            
            val envelope = envelopeBuilder.build()
            Log.d(TAG, "Envelope转换成功: messageId=${transportMessage.messageId}")
            envelope
            
        } catch (e: Exception) {
            Log.e(TAG, "转换Envelope失败: messageId=${transportMessage.messageId}", e)
            null
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
            Log.d(TAG, "使用Signal解密Envelope: timestamp=${envelope.timestamp}")
            
            // 获取Signal的解密服务
            val signalServiceCipher = getSignalServiceCipher()
            
            // 执行解密操作
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
            
            // 允许24小时的时间偏差
            if (timeDiff > 24 * 60 * 60 * 1000L) {
                Log.w(TAG, "Envelope时间戳异常: envelopeTime=$timestamp, currentTime=$currentTime")
                return false
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "验证Envelope失败", e)
            false
        }
    }
} 