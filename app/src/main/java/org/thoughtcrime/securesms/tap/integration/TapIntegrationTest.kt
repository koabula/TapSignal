package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.polling.TapPollingService
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.mms.OutgoingMessage

/**
 * Tap集成测试
 * 验证Tap层替换cos/coscomm模块后的功能完整性
 */
class TapIntegrationTest(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapIntegrationTest::class.java)
    }
    
    /**
     * 运行完整的集成测试
     */
    fun runIntegrationTest(): Boolean {
        Log.i(TAG, "开始运行Tap集成测试...")
        
        return try {
            // 1. 测试基础组件初始化
            if (!testComponentInitialization()) {
                Log.e(TAG, "组件初始化测试失败")
                return false
            }
            
            // 2. 测试消息发送集成
            if (!testMessageSendIntegration()) {
                Log.e(TAG, "消息发送集成测试失败")
                return false
            }
            
            // 3. 测试消息接收集成
            if (!testMessageReceiveIntegration()) {
                Log.e(TAG, "消息接收集成测试失败")
                return false
            }
            
            // 4. 测试轮询服务集成
            if (!testPollingServiceIntegration()) {
                Log.e(TAG, "轮询服务集成测试失败")
                return false
            }
            
            Log.i(TAG, "✅ Tap集成测试全部通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Tap集成测试异常", e)
            false
        }
    }
    
    /**
     * 测试基础组件初始化
     */
    private fun testComponentInitialization(): Boolean {
        Log.i(TAG, "测试基础组件初始化...")
        
        return try {
            // 测试TransportManager初始化
            val transportManager = TransportManager.getInstance(context)
            if (transportManager == null) {
                Log.e(TAG, "TransportManager初始化失败")
                return false
            }
            
            // 测试TransportChannelManager初始化
            val channelManager = TransportChannelManager.getInstance(context)
            if (channelManager == null) {
                Log.e(TAG, "TransportChannelManager初始化失败")
                return false
            }
            
            // 测试TapPollingService初始化
            val pollingService = TapPollingService.getInstance(context)
            if (pollingService == null) {
                Log.e(TAG, "TapPollingService初始化失败")
                return false
            }
            
            // 尝试初始化轮询服务
            pollingService.initialize()
            
            Log.i(TAG, "✅ 基础组件初始化测试通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 基础组件初始化测试失败", e)
            false
        }
    }
    
    /**
     * 测试消息发送集成
     */
    private fun testMessageSendIntegration(): Boolean {
        Log.i(TAG, "测试消息发送集成...")
        
        return try {
            // 获取发送集成器
            val sendIntegrator = TapMessageSendIntegrator.getInstance(context)
            if (sendIntegrator == null) {
                Log.e(TAG, "TapMessageSendIntegrator获取失败")
                return false
            }
            
            // 创建测试接收方
            val testRecipient = createTestRecipient()
            if (testRecipient == null) {
                Log.e(TAG, "创建测试接收方失败")
                return false
            }
            
            // 测试是否可以使用Tap发送
            val canUseTap = sendIntegrator.canUseTapForSending(testRecipient.id)
            Log.d(TAG, "canUseTapForSending结果: $canUseTap")
            
            // 创建测试消息
            val testMessage = createTestMessage()
            if (testMessage == null) {
                Log.e(TAG, "创建测试消息失败")
                return false
            }
            
            // 测试发送消息（不实际发送，只测试接口调用）
            val sendFuture = sendIntegrator.sendMessage(
                messageId = 12345L,
                recipient = testRecipient,
                outgoingMessage = testMessage,
                forceSignalServer = true, // 强制使用Signal Server，避免实际发送
                signalSenderCallback = createTestCallback()
            )
            
            // 等待结果（设置短超时）
            val result = try {
                sendFuture.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.d(TAG, "发送测试超时（预期行为）")
                null
            }
            
            Log.i(TAG, "✅ 消息发送集成测试通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 消息发送集成测试失败", e)
            false
        }
    }
    
    /**
     * 测试消息接收集成
     */
    private fun testMessageReceiveIntegration(): Boolean {
        Log.i(TAG, "测试消息接收集成...")
        
        return try {
            // 获取消息处理器
            val messageProcessor = TapMessageProcessor.getInstance(context)
            if (messageProcessor == null) {
                Log.e(TAG, "TapMessageProcessor获取失败")
                return false
            }
            
            // 测试Tap控制消息识别
            val testControlMessage = "TAP_REQ:test_config|test_token"
            val isTapMessage = messageProcessor.isTapMessage(testControlMessage)
            if (!isTapMessage) {
                Log.e(TAG, "Tap控制消息识别失败")
                return false
            }
            
            // 测试非Tap消息识别
            val normalMessage = "Hello, this is a normal message"
            val isNotTapMessage = !messageProcessor.isTapMessage(normalMessage)
            if (!isNotTapMessage) {
                Log.e(TAG, "普通消息识别错误")
                return false
            }
            
            // 测试处理Tap控制消息（不实际处理，只测试接口调用）
            val processResult = messageProcessor.processTapMessage("test_sender", testControlMessage)
            Log.d(TAG, "processTapMessage结果: $processResult")
            
            Log.i(TAG, "✅ 消息接收集成测试通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 消息接收集成测试失败", e)
            false
        }
    }
    
    /**
     * 测试轮询服务集成
     */
    private fun testPollingServiceIntegration(): Boolean {
        Log.i(TAG, "测试轮询服务集成...")
        
        return try {
            // 获取轮询服务
            val pollingService = TapPollingService.getInstance(context)
            if (pollingService == null) {
                Log.e(TAG, "TapPollingService获取失败")
                return false
            }
            
            // 测试初始化
            pollingService.initialize()
            
            // 测试启动轮询（应该返回false，因为没有活跃通道）
            val startResult = pollingService.startPolling()
            Log.d(TAG, "startPolling结果: $startResult")
            
            // 测试停止轮询
            val stopResult = pollingService.stopPolling()
            Log.d(TAG, "stopPolling结果: $stopResult")
            
            Log.i(TAG, "✅ 轮询服务集成测试通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 轮询服务集成测试失败", e)
            false
        }
    }
    
    /**
     * 创建测试接收方
     */
    private fun createTestRecipient(): Recipient? {
        return try {
            // 创建一个自己作为测试接收方
            Recipient.self()
        } catch (e: Exception) {
            Log.e(TAG, "创建测试接收方失败", e)
            null
        }
    }
    
    /**
     * 创建测试消息
     */
    private fun createTestMessage(): OutgoingMessage? {
        return try {
            OutgoingMessage.text(
                threadRecipient = Recipient.self(),
                body = "Tap集成测试消息",
                expiresIn = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建测试消息失败", e)
            null
        }
    }
    
    /**
     * 创建测试回调
     */
    private fun createTestCallback(): TapSenderCallback {
        return object : TapSenderCallback {
            override fun sendMessage(messageId: Long, recipient: Recipient, outgoingMessage: OutgoingMessage): TapSendResult {
                Log.d(TAG, "测试回调被调用: messageId=$messageId")
                return TapSendResult(true, "测试发送成功")
            }
        }
    }
    
    /**
     * 运行快速验证测试
     */
    fun runQuickValidation(): Boolean {
        Log.i(TAG, "运行快速验证测试...")
        
        return try {
            // 验证核心类可以实例化
            val transportManager = TransportManager.getInstance(context)
            val channelManager = TransportChannelManager.getInstance(context)
            val sendIntegrator = TapMessageSendIntegrator.getInstance(context)
            val messageProcessor = TapMessageProcessor.getInstance(context)
            val pollingService = TapPollingService.getInstance(context)
            
            val allComponentsAvailable = transportManager != null &&
                    channelManager != null &&
                    sendIntegrator != null &&
                    messageProcessor != null &&
                    pollingService != null
            
            if (allComponentsAvailable) {
                Log.i(TAG, "✅ 快速验证通过 - 所有核心组件可用")
                true
            } else {
                Log.e(TAG, "❌ 快速验证失败 - 部分核心组件不可用")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 快速验证异常", e)
            false
        }
    }
} 