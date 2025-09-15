package org.thoughtcrime.securesms.coscomm.service

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager
import org.thoughtcrime.securesms.coscomm.manager.CosMessageService
import org.thoughtcrime.securesms.coscomm.processor.CosMessageProcessor
import org.thoughtcrime.securesms.coscomm.strategy.IntelligentPollingStrategy
import org.thoughtcrime.securesms.coscomm.utils.CosPathManager
import org.thoughtcrime.securesms.cos.CosFileInfo
import java.util.concurrent.CompletableFuture

/**
 * Double Ratchet状态保护测试
 * 验证修复后的系统不会重复处理已解密的消息，保护Double Ratchet状态
 */
@RunWith(RobolectricTestRunner::class)
class DoubleRatchetProtectionTest {

    companion object {
        private const val TAG = "DoubleRatchetProtectionTest"
        private const val TEST_RECIPIENT_ID = "test-recipient-protection"
    }

    private lateinit var context: Context
    private lateinit var cosPollingService: CosPollingService
    
    @Mock
    private lateinit var mockSubAccountPoolManager: SubAccountPoolManager
    
    @Mock
    private lateinit var mockCosMessageService: CosMessageService
    
    @Mock
    private lateinit var mockCosMessageProcessor: CosMessageProcessor
    
    @Mock
    private lateinit var mockPollingStrategy: IntelligentPollingStrategy

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        
        // 初始化CosPollingService
        cosPollingService = CosPollingService(context)
        
        Log.d(TAG, "设置Double Ratchet保护测试环境")
    }

    /**
     * 测试核心修复：已处理消息不会被重复传递给处理器
     */
    @Test
    fun testProcessedMessagesAreFiltered() {
        Log.i(TAG, "🧪 测试已处理消息过滤机制")

        // 清理状态
        cosPollingService.clearProcessingState()

        // 创建测试消息
        val message1 = createTestMessage("msg-001", 23, 0)
        val message2 = createTestMessage("msg-002", 24, 0)
        val message3 = createTestMessage("msg-003", 25, 0)
        val message4 = createTestMessage("msg-004", 26, 0)

        val allMessages = listOf(message1, message2, message3, message4)

        // 模拟前两条消息已经被处理过
        cosPollingService.markMessageAsProcessed(message1.messageId)
        cosPollingService.markMessageAsProcessed(message2.messageId)
        
        Log.d(TAG, "标记前两条消息为已处理")

        // 使用反射调用私有方法filterAlreadyProcessedMessages
        val filterMethod = CosPollingService::class.java.getDeclaredMethod(
            "filterAlreadyProcessedMessages", 
            String::class.java, 
            List::class.java
        )
        filterMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val filteredMessages = filterMethod.invoke(cosPollingService, TEST_RECIPIENT_ID, allMessages) as List<CosMessage>

        // 验证过滤结果
        assert(filteredMessages.size == 2) { "应该只有2条新消息被保留，实际: ${filteredMessages.size}" }
        assert(filteredMessages.none { it.messageId == message1.messageId }) { "已处理消息1不应被保留" }
        assert(filteredMessages.none { it.messageId == message2.messageId }) { "已处理消息2不应被保留" }
        assert(filteredMessages.any { it.messageId == message3.messageId }) { "新消息3应该被保留" }
        assert(filteredMessages.any { it.messageId == message4.messageId }) { "新消息4应该被保留" }

        Log.i(TAG, "✅ 已处理消息过滤测试通过")
    }

    /**
     * 测试失败消息重试逻辑
     */
    @Test
    fun testFailedMessageRetryLogic() {
        Log.i(TAG, "🧪 测试失败消息重试逻辑")

        // 清理状态
        cosPollingService.clearProcessingState()

        val message = createTestMessage("msg-failed", 30, 0)

        // 标记消息为已尝试处理（模拟解密失败）
        cosPollingService.markMessageAsAttempted(message.messageId)
        
        Log.d(TAG, "标记消息为已尝试处理")

        // 立即检查过滤结果（应该被过滤掉，因为重试时间未到）
        val filterMethod = CosPollingService::class.java.getDeclaredMethod(
            "filterAlreadyProcessedMessages",
            String::class.java,
            List::class.java
        )
        filterMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val immediateResult = filterMethod.invoke(cosPollingService, TEST_RECIPIENT_ID, listOf(message)) as List<CosMessage>
        
        assert(immediateResult.isEmpty()) { "重试时间未到的消息应该被过滤" }

        // 模拟时间过去（通过修改尝试时间）
        val attemptedField = CosPollingService::class.java.getDeclaredField("attemptedMessageIds")
        attemptedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val attemptedMap = attemptedField.get(cosPollingService) as MutableMap<String, Long>
        attemptedMap[message.messageId] = System.currentTimeMillis() - 11000L // 11秒前

        @Suppress("UNCHECKED_CAST")
        val retryResult = filterMethod.invoke(cosPollingService, TEST_RECIPIENT_ID, listOf(message)) as List<CosMessage>
        
        assert(retryResult.size == 1) { "重试时间到了的消息应该被保留" }
        assert(retryResult[0].messageId == message.messageId) { "重试消息ID应该匹配" }

        Log.i(TAG, "✅ 失败消息重试逻辑测试通过")
    }

    /**
     * 测试混合消息场景（Log_A中的实际问题场景）
     */
    @Test
    fun testMixedMessageScenario() {
        Log.i(TAG, "🧪 测试混合消息场景（模拟Log_A问题）")

        // 清理状态
        cosPollingService.clearProcessingState()

        // 模拟Log_A中的场景：历史消息序列号23、24已处理，新消息序列号25、26
        val historicalMessage1 = createTestMessage("historical-23", 23, 0)
        val historicalMessage2 = createTestMessage("historical-24", 24, 0)
        val newMessage1 = createTestMessage("new-25", 25, 0)
        val newMessage2 = createTestMessage("new-26", 26, 0)

        // 标记历史消息为已处理
        cosPollingService.markMessageAsProcessed(historicalMessage1.messageId)
        cosPollingService.markMessageAsProcessed(historicalMessage2.messageId)

        // 模拟轮询返回的消息列表（包含历史和新消息）
        val pollingResult = listOf(historicalMessage1, historicalMessage2, newMessage1, newMessage2)

        Log.d(TAG, "模拟轮询结果包含${pollingResult.size}条消息（2条历史，2条新消息）")

        // 使用过滤方法
        val filterMethod = CosPollingService::class.java.getDeclaredMethod(
            "filterAlreadyProcessedMessages",
            String::class.java,
            List::class.java
        )
        filterMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val filteredMessages = filterMethod.invoke(cosPollingService, TEST_RECIPIENT_ID, pollingResult) as List<CosMessage>

        // 验证结果
        assert(filteredMessages.size == 2) { "应该只有2条新消息，实际: ${filteredMessages.size}" }
        
        val filteredIds = filteredMessages.map { it.messageId }.toSet()
        assert(!filteredIds.contains(historicalMessage1.messageId)) { "历史消息23不应被保留" }
        assert(!filteredIds.contains(historicalMessage2.messageId)) { "历史消息24不应被保留" }
        assert(filteredIds.contains(newMessage1.messageId)) { "新消息25应该被保留" }
        assert(filteredIds.contains(newMessage2.messageId)) { "新消息26应该被保留" }

        Log.i(TAG, "✅ 混合消息场景测试通过 - Double Ratchet状态得到保护")
    }

    /**
     * 创建测试消息
     */
    private fun createTestMessage(
        messageId: String,
        messageNumber: Int,
        chainNumber: Int,
        timestamp: Long = System.currentTimeMillis()
    ): CosMessage {
        val ratchetInfo = RatchetInfo(
            messageNumber = messageNumber,
            chainNumber = chainNumber,
            ratchetPublicKey = "test-key-$messageNumber",
            previousChainLength = 0
        )

        val contentMetadata = ContentMetadata(
            originalSize = 100L,
            compressionType = CompressionType.NONE,
            encryptionAlgorithm = "AES-256-GCM"
        )

        return CosMessage(
            messageId = messageId,
            senderId = "test-sender",
            recipientId = TEST_RECIPIENT_ID,
            timestamp = timestamp,
            messageType = MessageType.TEXT,
            encryptedContent = "test-encrypted-content",
            ratchetInfo = ratchetInfo,
            attachmentInfo = null,
            contentMetadata = contentMetadata
        )
    }
}