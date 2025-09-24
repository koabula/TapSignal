package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.signal.core.util.Base64
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.api.push.ServiceId
import java.util.*
import kotlinx.coroutines.runBlocking

/**
 * TAP-Signal集成验证工具
 * 
 * 负责验证TAP传输层与Signal的完整集成，确保消息兼容性和处理正确性
 * 包括消息格式验证、解密链路测试、去重机制验证等功能
 */
class TapSignalIntegrationValidator private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapSignalIntegrationValidator::class.java)
        
        @Volatile
        private var INSTANCE: TapSignalIntegrationValidator? = null
        
        fun getInstance(context: Context): TapSignalIntegrationValidator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapSignalIntegrationValidator(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /**
         * 仅用于内部测试的验证方法
         * 生产环境不应调用此方法
         */
        @JvmStatic
        internal fun validateFullIntegrationInternal(context: Context): ValidationResult {
            val validator = getInstance(context)
            return runBlocking {
                validator.validateFullIntegration()
            }
        }
    }
    
    private val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    
    /**
     * 完整的集成验证
     */
    suspend fun validateFullIntegration(): ValidationResult {
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "开始完整的TAP-Signal集成验证")
            
            val results = mutableListOf<ValidationStep>()
            
            try {
                // 1. 验证消息格式兼容性
                val formatResult = validateMessageFormatCompatibility()
                results.add(formatResult)
                
                // 2. 验证解密链路
                val decryptionResult = validateDecryptionPipeline()
                results.add(decryptionResult)
                
                // 3. 验证去重机制
                val deduplicationResult = validateDeduplicationMechanism()
                results.add(deduplicationResult)
                
                // 4. 验证消息处理流程
                val processingResult = validateMessageProcessingFlow()
                results.add(processingResult)
                
                // 5. 验证错误处理
                val errorHandlingResult = validateErrorHandling()
                results.add(errorHandlingResult)
                
                val overallSuccess = results.all { it.passed }
                val summary = "集成验证完成: ${results.count { it.passed }}/${results.size} 项通过"
                
                Log.i(TAG, summary)
                ValidationResult(overallSuccess, summary, results)
                
            } catch (e: Exception) {
                Log.e(TAG, "集成验证异常", e)
                ValidationResult(
                    false, 
                    "验证异常: ${e.message}",
                    results
                )
            }
        }
    }
    
    /**
     * 验证消息格式兼容性
     */
    private suspend fun validateMessageFormatCompatibility(): ValidationStep {
        return try {
            Log.d(TAG, "验证消息格式兼容性")
            
            val testMessages = createTestTransportMessages()
            var passedCount = 0
            val issues = mutableListOf<String>()
            
            for (testMessage in testMessages) {
                try {
                    // 验证TransportMessage -> Envelope转换
                    val envelope = envelopeAdapter.adaptToEnvelope(testMessage)
                    if (envelope != null && envelopeAdapter.validateEnvelope(envelope)) {
                        passedCount++
                        Log.d(TAG, "消息格式转换成功: ${testMessage.messageType}")
                    } else {
                        issues.add("消息类型 ${testMessage.messageType} 转换失败")
                    }
                } catch (e: Exception) {
                    issues.add("消息类型 ${testMessage.messageType} 转换异常: ${e.message}")
                }
            }
            
            val passed = passedCount == testMessages.size
            val details = if (passed) {
                "所有消息类型转换成功 ($passedCount/${testMessages.size})"
            } else {
                "部分消息类型转换失败: ${issues.joinToString("; ")}"
            }
            
            ValidationStep("消息格式兼容性", passed, details)
            
        } catch (e: Exception) {
            Log.e(TAG, "验证消息格式兼容性异常", e)
            ValidationStep("消息格式兼容性", false, "验证异常: ${e.message}")
        }
    }
    
    /**
     * 验证解密链路
     */
    private suspend fun validateDecryptionPipeline(): ValidationStep {
        return try {
            Log.d(TAG, "验证Signal解密链路")
            
            // 检查关键组件是否正确配置
            val issues = mutableListOf<String>()
            
            // 1. 检查本地账户配置
            if (!SignalStore.account.isRegistered) {
                issues.add("Signal账户未注册")
            }
            
            // 2. 检查协议存储
            try {
                val protocolStore = org.thoughtcrime.securesms.dependencies.AppDependencies.protocolStore.aci()
                val localRegistrationId = protocolStore.localRegistrationId
                if (localRegistrationId <= 0) {
                    issues.add("本地注册ID无效")
                }
            } catch (e: Exception) {
                issues.add("协议存储访问异常: ${e.message}")
            }
            
            // 3. 检查消息处理器
            try {
                val messageContentProcessor = org.thoughtcrime.securesms.messages.MessageContentProcessor.create(context)
                // 验证处理器可以正常创建
                Log.d(TAG, "MessageContentProcessor创建成功")
            } catch (e: Exception) {
                issues.add("MessageContentProcessor创建失败: ${e.message}")
            }
            
            val passed = issues.isEmpty()
            val details = if (passed) {
                "解密链路组件配置正确"
            } else {
                "解密链路问题: ${issues.joinToString("; ")}"
            }
            
            ValidationStep("解密链路验证", passed, details)
            
        } catch (e: Exception) {
            Log.e(TAG, "验证解密链路异常", e)
            ValidationStep("解密链路验证", false, "验证异常: ${e.message}")
        }
    }
    
    /**
     * 验证去重机制
     */
    private suspend fun validateDeduplicationMechanism(): ValidationStep {
        return try {
            Log.d(TAG, "验证去重机制")
            
            val testMessageId = "test_${UUID.randomUUID()}"
            val testSenderId = "test_sender"
            val testTimestamp = System.currentTimeMillis()
            
            // 1. 首次检查应该不重复
            val firstCheck = messageDeduplicator.isDuplicate(testMessageId, testSenderId, testTimestamp)
            if (firstCheck) {
                return ValidationStep("去重机制验证", false, "新消息被误判为重复")
            }
            
            // 2. 标记为已处理
            messageDeduplicator.markAsProcessed(testMessageId, testSenderId, testTimestamp)
            
            // 3. 再次检查应该检测到重复
            val secondCheck = messageDeduplicator.isDuplicate(testMessageId, testSenderId, testTimestamp)
            if (!secondCheck) {
                return ValidationStep("去重机制验证", false, "已处理消息未被检测为重复")
            }
            
            // 4. 验证幂等性 - 多次标记处理不应出错
            messageDeduplicator.markAsProcessed(testMessageId, testSenderId, testTimestamp)
            messageDeduplicator.markAsProcessed(testMessageId, testSenderId, testTimestamp)
            
            ValidationStep("去重机制验证", true, "去重和幂等性验证通过")
            
        } catch (e: Exception) {
            Log.e(TAG, "验证去重机制异常", e)
            ValidationStep("去重机制验证", false, "验证异常: ${e.message}")
        }
    }
    
    /**
     * 验证消息处理流程
     */
    private suspend fun validateMessageProcessingFlow(): ValidationStep {
        return try {
            Log.d(TAG, "验证消息处理流程")
            
            val issues = mutableListOf<String>()
            
            // 1. 验证TAP控制消息识别
            val controlMessages = listOf(
                "TAP_REQ:test_request",
                "TAP_RESP:test_response", 
                "TAP_REVOKE:test_revoke",
                "TAP_MSG:test_control"
            )
            
            controlMessages.forEach { messageBody ->
                if (!messageProcessor.isTapMessage(messageBody)) {
                    issues.add("TAP控制消息识别失败: $messageBody")
                }
            }
            
            // 2. 验证非TAP消息不被误识别
            val nonTapMessages = listOf(
                "Hello world",
                "TAPMSG:invalid_format",
                "This is a normal message",
                ""
            )
            
            nonTapMessages.forEach { messageBody ->
                if (messageProcessor.isTapMessage(messageBody)) {
                    issues.add("非TAP消息被误识别: $messageBody")
                }
            }
            
            val passed = issues.isEmpty()
            val details = if (passed) {
                "消息处理流程验证通过"
            } else {
                "消息处理流程问题: ${issues.joinToString("; ")}"
            }
            
            ValidationStep("消息处理流程验证", passed, details)
            
        } catch (e: Exception) {
            Log.e(TAG, "验证消息处理流程异常", e)
            ValidationStep("消息处理流程验证", false, "验证异常: ${e.message}")
        }
    }
    
    /**
     * 验证错误处理
     */
    private suspend fun validateErrorHandling(): ValidationStep {
        return try {
            Log.d(TAG, "验证错误处理")
            
            val issues = mutableListOf<String>()
            
            // 1. 验证无效TransportMessage处理
            try {
                val invalidMessage = TransportMessage(
                    version = "1.0",
                    messageId = "",  // 无效的空ID
                    timestamp = System.currentTimeMillis(),
                    senderId = "test_sender",
                    recipientId = "test_recipient",
                    messageType = TransportMessageType.TEXT_MESSAGE,
                    signalCiphertext = "",  // 无效的空密文
                    contentMetadata = TransportContentMetadata(originalSize = 0L)
                )
                
                val result = messageProcessor.processTapTransportMessage(invalidMessage)
                if (result !is TapProcessResult.Failed) {
                    issues.add("无效消息未被正确拒绝")
                }
            } catch (e: Exception) {
                // 异常也是可接受的错误处理方式
                Log.d(TAG, "无效消息处理产生异常，这是正常的错误处理")
            }
            
            // 2. 验证Envelope转换错误处理
            try {
                val invalidMessage = TransportMessage(
                    version = "1.0",
                    messageId = "test_invalid",
                    timestamp = System.currentTimeMillis(),
                    senderId = "invalid_format_sender",  // 无效格式
                    recipientId = "test_recipient",
                    messageType = TransportMessageType.TEXT_MESSAGE,
                    signalCiphertext = "invalid_base64",  // 无效base64
                    contentMetadata = TransportContentMetadata(originalSize = 0L)
                )
                
                val envelope = envelopeAdapter.adaptToEnvelope(invalidMessage)
                if (envelope != null) {
                    issues.add("无效格式消息转换应该失败")
                }
            } catch (e: Exception) {
                // 异常处理也是正确的
                Log.d(TAG, "无效格式转换产生异常，这是正常的错误处理")
            }
            
            val passed = issues.isEmpty()
            val details = if (passed) {
                "错误处理验证通过"
            } else {
                "错误处理问题: ${issues.joinToString("; ")}"
            }
            
            ValidationStep("错误处理验证", passed, details)
            
        } catch (e: Exception) {
            Log.e(TAG, "验证错误处理异常", e)
            ValidationStep("错误处理验证", false, "验证异常: ${e.message}")
        }
    }
    
    /**
     * 创建测试用的TransportMessage
     */
    private fun createTestTransportMessages(): List<TransportMessage> {
        val testTimestamp = System.currentTimeMillis()
        val testBase64Content = Base64.encodeWithPadding("test_encrypted_content".toByteArray())
        
        return TransportMessageType.values().map { messageType ->
            TransportMessage(
                version = "1.0",
                messageId = "test_${messageType.name.lowercase()}_${UUID.randomUUID()}",
                timestamp = testTimestamp,
                senderId = SignalStore.account.requireAci().toString(),
                recipientId = SignalStore.account.requireAci().toString(), 
                messageType = messageType,
                signalCiphertext = testBase64Content,
                contentMetadata = TransportContentMetadata(originalSize = testBase64Content.length.toLong())
            )
        }
    }
    
    /**
     * 验证特定联系人的TAP通道状态
     */
    suspend fun validateContactTapStatus(recipientId: RecipientId): ContactValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "验证联系人TAP状态: ${LogSanitizer.sanitize(recipientId.toString())}")
                
                val channelManager = TransportChannelManager.getInstance(context)
                val tokenPool = TransportTokenPool.getInstance(context)
                
                val issues = mutableListOf<String>()
                
                // 1. 检查活跃通道
                val hasActiveChannel = channelManager.hasActiveChannel(recipientId.toString())
                if (!hasActiveChannel) {
                    issues.add("无活跃传输通道")
                }
                
                // 2. 检查可用Token
                val transportManager = TransportManager.getInstance(context)
                val availableProviders = transportManager.getAvailableProviders()
                val tokenInfos = mutableListOf<TokenValidationInfo>()
                
                availableProviders.forEach { provider ->
                    val providerType = provider.providerType
                    val receivedToken = tokenPool.getValidReceivedToken(recipientId.toString(), providerType)
                    val sharedToken = tokenPool.getValidSharedToken(recipientId.toString(), providerType)
                    
                    tokenInfos.add(TokenValidationInfo(
                        providerType = providerType,
                        hasReceivedToken = receivedToken != null,
                        hasSharedToken = sharedToken != null,
                        receivedTokenValid = receivedToken?.validate() == true,
                        sharedTokenValid = sharedToken?.validate() == true
                    ))
                }
                
                val hasValidTokens = tokenInfos.any { it.hasValidReceivedToken() || it.hasValidSharedToken() }
                if (!hasValidTokens) {
                    issues.add("无有效传输Token")
                }
                
                val canUseTap = hasActiveChannel && hasValidTokens
                
                ContactValidationResult(
                    recipientId = recipientId.toString(),
                    canUseTap = canUseTap,
                    hasActiveChannel = hasActiveChannel,
                    tokenInfos = tokenInfos,
                    issues = issues
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "验证联系人TAP状态异常", e)
                ContactValidationResult(
                    recipientId = recipientId.toString(),
                    canUseTap = false,
                    hasActiveChannel = false,
                    tokenInfos = emptyList(),
                    issues = listOf("验证异常: ${e.message}")
                )
            }
        }
    }
    
    /**
     * 检测是否为Tap v2模式的消息
     * v2模式的特征：通过Tap传输且使用长期Token（永久凭证）
     */
    fun isTapV2ModeMessage(
        envelope: org.whispersystems.signalservice.internal.push.Envelope,
        senderRecipient: org.thoughtcrime.securesms.recipients.Recipient
    ): Boolean {
        return try {
            // 首先检查是否为Tap传输层传输的消息
            if (!isTapDeliveredMessage(envelope)) {
                return false
            }
            
            val channelManager = TransportChannelManager.getInstance(context)
            val tokenPool = TransportTokenPool.getInstance(context)
            
            // 检查是否有活跃的通道
            val hasActiveChannel = channelManager.hasActiveChannel(senderRecipient.id.toString())
            if (!hasActiveChannel) {
                return false
            }
            
            // 检查是否使用长期Token（永久凭证）
            // 获取所有可用的Provider类型
            val transportManager = TransportManager.getInstance(context)
            val availableProviders = transportManager.getAvailableProviders()
            
            var hasLongTermCredentials = false
            for (provider in availableProviders) {
                val providerType = provider.providerType
                val receivedToken = tokenPool.getValidReceivedToken(senderRecipient.id.toString(), providerType)
                val sharedToken = tokenPool.getValidSharedToken(senderRecipient.id.toString(), providerType)
                
                // 检查Token是否为长期Token（没有过期时间或过期时间很远）
                val isReceivedLongTerm = receivedToken?.let { isLongTermToken(it) } ?: false
                val isSharedLongTerm = sharedToken?.let { isLongTermToken(it) } ?: false
                
                if (isReceivedLongTerm || isSharedLongTerm) {
                    hasLongTermCredentials = true
                    break
                }
            }
            
            val isV2Mode = hasActiveChannel && hasLongTermCredentials
            
            Log.d(TAG, "Tap v2模式检测 (接收端): senderId=${senderRecipient.id}, " +
                    "hasActiveChannel=$hasActiveChannel, " +
                    "hasLongTermCredentials=$hasLongTermCredentials, " +
                    "isV2Mode=$isV2Mode")
            
            isV2Mode
            
        } catch (e: Exception) {
            Log.e(TAG, "检测Tap v2模式时发生异常: senderId=${senderRecipient.id}", e)
            false
        }
    }
    
    /**
     * 检查消息是否为通过Tap传输层传输的消息
     */
    private fun isTapDeliveredMessage(envelope: org.whispersystems.signalservice.internal.push.Envelope): Boolean {
        // 通过Tap传输层传输的消息会有特殊的serverGuid格式
        // Tap传输层消息的serverGuid通常是Tap消息ID，而不是Signal服务器的UUID格式
        val serverGuid = envelope.serverGuid
        if (serverGuid != null) {
            // Tap传输层消息ID格式通常是时间戳+随机数，不是标准UUID格式
            // 如果不是标准UUID格式，可能是Tap传输层消息
            return !isStandardUuid(serverGuid.toString())
        }
        return false
    }
    
    /**
     * 检查字符串是否为标准UUID格式
     */
    private fun isStandardUuid(str: String): Boolean {
        return try {
            // 标准UUID格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
            val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            uuidPattern.matches(str)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查Token是否为长期Token（永久凭证）
     */
    private fun isLongTermToken(token: TransportToken): Boolean {
        return when (token) {
            is org.thoughtcrime.securesms.tap.CosTransportToken -> {
                // COS Token如果没有sessionToken则为永久凭证
                token.sessionToken.isNullOrEmpty()
            }
            else -> {
                // 其他类型的Token，检查是否有过期时间且过期时间很远（假设为永久）
                // 这里可以根据具体Provider类型进行扩展
                false
            }
        }
    }
}

/**
 * 验证结果
 */
data class ValidationResult(
    val success: Boolean,
    val summary: String,
    val steps: List<ValidationStep>
)

/**
 * 验证步骤
 */
data class ValidationStep(
    val name: String,
    val passed: Boolean,
    val details: String
)

/**
 * 联系人验证结果
 */
data class ContactValidationResult(
    val recipientId: String,
    val canUseTap: Boolean,
    val hasActiveChannel: Boolean,
    val tokenInfos: List<TokenValidationInfo>,
    val issues: List<String>
)

/**
 * Token验证信息
 */
data class TokenValidationInfo(
    val providerType: String,
    val hasReceivedToken: Boolean,
    val hasSharedToken: Boolean,
    val receivedTokenValid: Boolean,
    val sharedTokenValid: Boolean
) {
    fun hasValidReceivedToken(): Boolean = hasReceivedToken && receivedTokenValid
    fun hasValidSharedToken(): Boolean = hasSharedToken && sharedTokenValid
} 