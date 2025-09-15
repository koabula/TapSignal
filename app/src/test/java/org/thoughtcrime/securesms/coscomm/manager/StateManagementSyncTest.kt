package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.service.CosPollingService
import java.util.concurrent.TimeUnit

/**
 * 测试MessageDeduplicationManager和CosPollingService之间的状态管理同步
 * 验证双重状态管理系统同步修复的效果
 */
@RunWith(RobolectricTestRunner::class)
class StateManagementSyncTest {

    companion object {
        private const val TAG = "StateManagementSyncTest"
        private const val TEST_RECIPIENT_ID = "test-recipient-sync"
    }

    private lateinit var context: Context
    private lateinit var messageDeduplicationManager: MessageDeduplicationManager
    private lateinit var cosPollingService: CosPollingService

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication()
        
        messageDeduplicationManager = MessageDeduplicationManager(context)
        cosPollingService = CosPollingService(context)
        
        // 清理状态
        cosPollingService.clearProcessingState()
        
        Log.d(TAG, "设置状态管理同步测试环境")
    }

    /**
     * 测试CosPollingService已处理的消息不会被MessageDeduplicationManager重复处理
     */
    @Test
    fun testProcessedMessageSyncBetweenComponents() {
        Log.i(TAG, "🧪 测试双重状态管理系统同步")

        // 创建测试消息（模拟Log_A中的场景）
        val processedMessage1 = createTestMessage("msg-23", 23, 0)
        val processedMessage2 = createTestMessage("msg-24", 24, 0)
        val newMessage1 = createTestMessage("msg-25", 25, 0)
        val newMessage2 = createTestMessage("msg-26", 26, 0)

        // 1. 在CosPollingService中标记前两条消息为已处理
        cosPollingService.markMessageAsProcessed(processedMessage1.messageId)
        cosPollingService.markMessageAsProcessed(processedMessage2.messageId)
        
        Log.d(TAG, "在CosPollingService中标记消息23、24为已处理")

        // 2. 验证CosPollingService状态
        assert(cosPollingService.isMessageProcessed(processedMessage1.messageId)) { 
            "消息23应该在CosPollingService中被标记为已处理" 
        }
        assert(cosPollingService.isMessageProcessed(processedMessage2.messageId)) { 
            "消息24应该在CosPollingService中被标记为已处理" 
        }

        // 3. 将所有消息（包括已处理和新消息）传递给MessageDeduplicationManager
        val allMessages = listOf(processedMessage1, processedMessage2, newMessage1, newMessage2)
        val filteredMessages = messageDeduplicationManager.processMessages(TEST_RECIPIENT_ID, allMessages)

        // 4. 验证MessageDeduplicationManager正确过滤了已处理消息
        assert(filteredMessages.size == 2) { 
            "MessageDeduplicationManager应该只返回2条新消息，实际: ${filteredMessages.size}" 
        }
        
        val filteredIds = filteredMessages.map { it.messageId }.toSet()
        assert(!filteredIds.contains(processedMessage1.messageId)) { 
            "已处理消息23不应被MessageDeduplicationManager保留" 
        }
        assert(!filteredIds.contains(processedMessage2.messageId)) { 
            "已处理消息24不应被MessageDeduplicationManager保留" 
        }
        assert(filteredIds.contains(newMessage1.messageId)) { 
            "新消息25应该被MessageDeduplicationManager保留" 
        }
        assert(filteredIds.contains(newMessage2.messageId)) { 
            "新消息26应该被MessageDeduplicationManager保留" 
        }

        Log.i(TAG, "✅ 双重状态管理系统同步测试通过")
    }

    /**
     * 测试状态同步的实时性
     */
    @Test
    fun testRealTimeStateSynchronization() {
        Log.i(TAG, "🧪 测试状态同步的实时性")

        val message = createTestMessage("msg-real-time", 30, 0)

        // 1. 在MessageDeduplicationManager中处理消息（第一次）
        val firstResult = messageDeduplicationManager.processMessages(TEST_RECIPIENT_ID, listOf(message))
        assert(firstResult.size == 1) { "第一次处理应该返回1条消息" }

        // 2. 在CosPollingService中标记消息为已处理
        cosPollingService.markMessageAsProcessed(message.messageId)

        // 3. 再次在MessageDeduplicationManager中处理相同消息
        val secondResult = messageDeduplicationManager.processMessages(TEST_RECIPIENT_ID, listOf(message))
        assert(secondResult.isEmpty()) { "第二次处理应该返回0条消息（因为状态同步）" }

        Log.i(TAG, "✅ 状态同步实时性测试通过")
    }

    /**
     * 测试大量消息的状态同步性能
     */
    @Test
    fun testStateSyncPerformanceWithManyMessages() {
        Log.i(TAG, "🧪 测试大量消息的状态同步性能")

        val messageCount = 100
        val messages = (1..messageCount).map { i ->
            createTestMessage("msg-perf-$i", i, 0)
        }

        // 标记前50条消息为已处理
        val startTime = System.currentTimeMillis()
        messages.take(50).forEach { message ->
            cosPollingService.markMessageAsProcessed(message.messageId)
        }

        // 处理所有消息
        val filteredMessages = messageDeduplicationManager.processMessages(TEST_RECIPIENT_ID, messages)
        val endTime = System.currentTimeMillis()

        // 验证结果
        assert(filteredMessages.size == 50) { 
            "应该只有50条新消息被保留，实际: ${filteredMessages.size}" 
        }

        val processingTime = endTime - startTime
        Log.i(TAG, "处理${messageCount}条消息耗时: ${processingTime}ms")
        assert(processingTime < 5000) { "性能测试失败：处理时间过长 ${processingTime}ms" }

        Log.i(TAG, "✅ 大量消息状态同步性能测试通过")
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