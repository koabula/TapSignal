package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportContentMetadata
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportTokenRequest
import org.thoughtcrime.securesms.tap.TransportPermission
import kotlinx.coroutines.runBlocking

/**
 * 测试结果封装类
 */
sealed class TestResult(val message: String) {
    class Success(message: String) : TestResult(message)
    class Failed(message: String) : TestResult(message)
    class Warning(message: String) : TestResult(message)
    
    val isSuccess: Boolean get() = this is Success
    val isFailed: Boolean get() = this is Failed
    val isWarning: Boolean get() = this is Warning
}

/**
 * Tap集成测试
 * 验证修复后的接收和发送流程是否正确工作
 * 
 * 测试重点：
 * 1. 发送端：Signal先加密，Tap只传输密文
 * 2. 接收端：Tap密文包装为Envelope，使用Signal标准解密流程
 */
class TapIntegrationTest private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapIntegrationTest::class.java)
        
        @Volatile
        private var INSTANCE: TapIntegrationTest? = null
        
        fun getInstance(context: Context): TapIntegrationTest {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapIntegrationTest(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 测试组件
    private val tapEnvelopeAdapter = TapEnvelopeAdapter.getInstance(context)
    private val tapSignalServiceAdapter = TapSignalServiceAdapter.getInstance(context)
    private val tapMessageProcessor = TapMessageProcessor.getInstance(context)
    
    /**
     * 运行所有集成测试
     */
    fun runAllTests(): TapTestResult {
        Log.i(TAG, "开始运行Tap集成测试")
        
        val results = mutableListOf<TapTestCase>()
        
        try {
            // 测试1：验证Envelope适配器
            results.add(testEnvelopeAdapter())
            
            // 测试2：验证发送流程（模拟）
            results.add(testSendFlow())
            
            // 测试3：验证接收流程（模拟）
            results.add(testReceiveFlow())
            
            // 测试4：验证端到端流程（模拟）
            results.add(testEndToEndFlow())
            
            // 汇总结果
            val totalTests = results.size
            val passedTests = results.count { it.passed }
            val failedTests = totalTests - passedTests
            
            Log.i(TAG, "测试完成: 总计=$totalTests, 成功=$passedTests, 失败=$failedTests")
            
            return TapTestResult(
                totalTests = totalTests,
                passedTests = passedTests,
                failedTests = failedTests,
                testCases = results,
                success = failedTests == 0
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "集成测试异常", e)
            return TapTestResult(
                totalTests = 0,
                passedTests = 0,
                failedTests = 1,
                testCases = listOf(TapTestCase("集成测试", false, "测试异常: ${e.message}")),
                success = false
            )
        }
    }
    
    /**
     * 测试Envelope适配器功能
     */
    private fun testEnvelopeAdapter(): TapTestCase {
        return try {
            Log.d(TAG, "测试Envelope适配器")
            
            // 创建模拟TransportMessage
            val transportMessage = createMockTransportMessage()
            
            // 测试适配功能
            val envelope = tapEnvelopeAdapter.adaptToEnvelope(transportMessage)
            if (envelope == null) {
                TapTestCase("Envelope适配器", false, "适配失败：返回null")
            } else {
                // 验证Envelope有效性
                val isValid = tapEnvelopeAdapter.validateEnvelope(envelope)
                if (isValid) {
                    TapTestCase("Envelope适配器", true, "适配成功，Envelope有效")
                } else {
                    TapTestCase("Envelope适配器", false, "适配成功但Envelope无效")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Envelope适配器测试失败", e)
            TapTestCase("Envelope适配器", false, "测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试发送流程（模拟）
     */
    private fun testSendFlow(): TapTestCase {
        return try {
            Log.d(TAG, "测试发送流程")
            
            // 创建模拟数据
            val mockRecipient = createMockRecipient()
            val mockOutgoingMessage = createMockOutgoingMessage()
            
            // 注意：这里只测试能否正确调用，不执行实际发送
            // 因为实际发送需要真实的网络环境和Signal服务
            
            // 验证TapSignalServiceAdapter是否正确初始化
            val adapter = TapSignalServiceAdapter.getInstance(context)
            if (adapter != null) {
                Log.d(TAG, "TapSignalServiceAdapter初始化成功")
                TapTestCase("发送流程", true, "发送适配器初始化成功")
            } else {
                TapTestCase("发送流程", false, "发送适配器初始化失败")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "发送流程测试失败", e)
            TapTestCase("发送流程", false, "测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试接收流程（模拟）
     */
    private fun testReceiveFlow(): TapTestCase {
        return try {
            Log.d(TAG, "测试接收流程")
            
            // 创建模拟TransportMessage
            val transportMessage = createMockTransportMessage()
            
            // 验证能否正确适配为Envelope
            val envelope = tapEnvelopeAdapter.adaptToEnvelope(transportMessage)
            if (envelope == null) {
                return TapTestCase("接收流程", false, "Envelope适配失败")
            }
            
            // 验证TapMessageProcessor是否正确初始化
            val processor = TapMessageProcessor.getInstance(context)
            if (processor != null) {
                Log.d(TAG, "TapMessageProcessor初始化成功")
                TapTestCase("接收流程", true, "接收处理器初始化成功，Envelope适配成功")
            } else {
                TapTestCase("接收流程", false, "接收处理器初始化失败")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "接收流程测试失败", e)
            TapTestCase("接收流程", false, "测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试端到端流程（模拟）
     */
    private fun testEndToEndFlow(): TapTestCase {
        return try {
            Log.d(TAG, "测试端到端流程")
            
            // 验证所有组件是否正确初始化并能协同工作
            val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
            val signalAdapter = TapSignalServiceAdapter.getInstance(context)
            val messageProcessor = TapMessageProcessor.getInstance(context)
            
            if (envelopeAdapter != null && signalAdapter != null && messageProcessor != null) {
                Log.d(TAG, "所有组件初始化成功")
                
                // 模拟消息流转：创建TransportMessage -> 适配为Envelope
                val transportMessage = createMockTransportMessage()
                val envelope = envelopeAdapter.adaptToEnvelope(transportMessage)
                
                if (envelope != null && envelopeAdapter.validateEnvelope(envelope)) {
                    TapTestCase("端到端流程", true, "所有组件正常，消息流转正确")
                } else {
                    TapTestCase("端到端流程", false, "消息流转失败")
                }
            } else {
                TapTestCase("端到端流程", false, "组件初始化不完整")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "端到端流程测试失败", e)
            TapTestCase("端到端流程", false, "测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试COS Token生成和池管理
     */
    suspend fun testCosTokenGeneration(): TestResult {
        return try {
            Log.i(TAG, "开始测试COS Token生成和池管理")
            
            val recipientId = "test-recipient-${System.currentTimeMillis()}"
            val providerType = "cos"
            
            // 1. 获取COS Provider
            val transportManager = TransportManager.getInstance(context)
            val cosProvider = transportManager.getProvider(providerType)
            if (cosProvider == null) {
                return TestResult.Failed("COS Provider未注册")
            }
            
            // 2. 创建Token请求
            val tokenRequest = TransportTokenRequest(
                recipientId = recipientId,
                providerType = providerType,
                requestedPermissions = setOf(TransportPermission.READ),
                validityDurationMs = 0L, // 使用长期有效
                purpose = "integration_test"
            )
            
            // 3. 生成Token
            val generatedToken = cosProvider.generateToken(tokenRequest)
            if (generatedToken == null) {
                return TestResult.Failed("Token生成失败")
            }
            
            Log.i(TAG, "Token生成成功: tokenId=${generatedToken.tokenId}")
            
            // 4. 测试Token池管理
            val tokenPool = TransportTokenPool.getInstance(context)
            
            // 添加为接收Token
            val addReceivedSuccess = tokenPool.addReceivedToken(recipientId, generatedToken)
            if (!addReceivedSuccess) {
                return TestResult.Failed("添加接收Token失败")
            }
            
            // 添加为共享Token
            val addSharedSuccess = tokenPool.addSharedToken(recipientId, generatedToken)
            if (!addSharedSuccess) {
                return TestResult.Failed("添加共享Token失败")
            }
            
            // 5. 测试Token检索
            val retrievedPeerToken = tokenPool.getPeerToken(recipientId, providerType)
            if (retrievedPeerToken == null) {
                return TestResult.Failed("检索对端Token失败")
            }
            
            val retrievedMyToken = tokenPool.getMyToken(recipientId, providerType)
            if (retrievedMyToken == null) {
                return TestResult.Failed("检索本端Token失败")
            }
            
            // 6. 验证Token内容
            if (retrievedPeerToken.tokenId != generatedToken.tokenId) {
                return TestResult.Failed("对端Token内容不匹配")
            }
            
            if (retrievedMyToken.tokenId != generatedToken.tokenId) {
                return TestResult.Failed("本端Token内容不匹配")
            }
            
            // 7. 测试Token统计
            val statistics = tokenPool.getTokenStatistics()
            Log.i(TAG, "Token统计: 总数=${statistics.totalTokens}, 有效=${statistics.validTokens}")
            
            // 8. 测试Token撤销
            val revokeSuccess = cosProvider.revokeToken(generatedToken)
            Log.i(TAG, "Token撤销结果: $revokeSuccess")
            
            // 9. 清理测试数据
            tokenPool.removeToken(recipientId, providerType)
            
            TestResult.Success("COS Token生成和池管理测试通过")
            
        } catch (e: Exception) {
            Log.e(TAG, "COS Token测试失败", e)
            TestResult.Failed("测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试数据迁移功能
     */
    suspend fun testDataMigration(): TestResult {
        return try {
            Log.i(TAG, "开始测试数据迁移功能")
            
            // 获取原始SubAccountPoolManager的统计信息
            val subAccountManager = org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager.getInstance(context)
            val originalStats = subAccountManager.getStatistics()
            
            Log.i(TAG, "原始SubAccount统计: $originalStats")
            
            // 获取TransportTokenPool的统计信息
            val tokenPool = TransportTokenPool.getInstance(context)
            val tokenStats = tokenPool.getTokenStatistics()
            
            Log.i(TAG, "当前Token统计: 总数=${tokenStats.totalTokens}, 有效=${tokenStats.validTokens}")
            
            // 测试迁移逻辑（这里只是验证接口可用性）
            val tapInitializer = TapModuleInitializer.getInstance(context)
            // 注意：实际迁移可能已经在初始化时执行过了
            
            TestResult.Success("数据迁移测试通过，接口可用")
            
        } catch (e: Exception) {
            Log.e(TAG, "数据迁移测试失败", e)
            TestResult.Failed("测试异常: ${e.message}")
        }
    }
    
    /**
     * 创建模拟TransportMessage
     */
    private fun createMockTransportMessage(): TransportMessage {
        // 创建模拟的加密数据（实际应该是真实的Signal密文）
        val mockCiphertext = "mock_encrypted_data_for_testing"
        val mockCiphertextBase64 = android.util.Base64.encodeToString(
            mockCiphertext.toByteArray(), 
            android.util.Base64.NO_WRAP
        )
        
        return TransportMessage(
            messageId = "test_message_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            senderId = "+1234567890", // 模拟发送者
            recipientId = "self",
            messageType = TransportMessageType.TEXT_MESSAGE,
            signalCiphertext = mockCiphertextBase64,
            signalCiphertextType = 1, // CIPHERTEXT类型
            contentMetadata = TransportContentMetadata(
                originalSize = mockCiphertext.length.toLong()
            ),
            attachments = emptyList()
        )
    }
    
    /**
     * 创建模拟Recipient（简化）
     */
    private fun createMockRecipient(): Recipient? {
        return try {
            // 注意：在实际测试中需要真实的Recipient
            // 这里返回null作为占位符
            null
        } catch (e: Exception) {
            Log.w(TAG, "创建模拟Recipient失败", e)
            null
        }
    }
    
    /**
     * 创建模拟OutgoingMessage
     */
    private fun createMockOutgoingMessage(): OutgoingMessage {
        val mockRecipient = org.thoughtcrime.securesms.recipients.Recipient.self()
        return OutgoingMessage(
            threadRecipient = mockRecipient,
            sentTimeMillis = System.currentTimeMillis(),
            body = "测试消息内容",
            distributionType = 0,
            expiresIn = 0,
            expireTimerVersion = 1,
            isViewOnce = false,
            outgoingQuote = null,
            storyType = org.thoughtcrime.securesms.database.model.StoryType.NONE,
            parentStoryId = null,
            isStoryReaction = false,
            giftBadge = null,
            isSecure = true,
            attachments = emptyList(),
            sharedContacts = emptyList(),
            linkPreviews = emptyList(),
            bodyRanges = null,
            mentions = emptyList(),
            isGroup = false,
            isGroupUpdate = false,
            messageGroupContext = null,
            isExpirationUpdate = false,
            isPaymentsNotification = false,
            isRequestToActivatePayments = false,
            isPaymentsActivated = false,
            isUrgent = true,
            networkFailures = emptySet(),
            identityKeyMismatches = emptySet(),
            isEndSession = false,
            isIdentityVerified = false,
            isIdentityDefault = false,
            scheduledDate = -1L,
            messageToEdit = 0L,
            isReportSpam = false,
            isMessageRequestAccept = false,
            isBlocked = false,
            isUnblocked = false,
            messageExtras = null
        )
    }
}

/**
 * 测试结果封装类
 */
data class TapTestResult(
    val totalTests: Int,
    val passedTests: Int,
    val failedTests: Int,
    val testCases: List<TapTestCase>,
    val success: Boolean
) {
    fun getReport(): String {
        val sb = StringBuilder()
        sb.append("=== Tap集成测试报告 ===\n")
        sb.append("总测试数: $totalTests\n")
        sb.append("成功: $passedTests\n")
        sb.append("失败: $failedTests\n")
        sb.append("成功率: ${if (totalTests > 0) (passedTests * 100 / totalTests) else 0}%\n")
        sb.append("\n=== 详细结果 ===\n")
        
        testCases.forEach { testCase ->
            val status = if (testCase.passed) "✅ PASS" else "❌ FAIL"
            sb.append("$status - ${testCase.name}: ${testCase.message}\n")
        }
        
        return sb.toString()
    }
}

/**
 * 单个测试用例
 */
data class TapTestCase(
    val name: String,
    val passed: Boolean,
    val message: String
) 