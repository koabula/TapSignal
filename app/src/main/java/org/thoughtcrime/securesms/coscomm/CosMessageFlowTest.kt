package org.thoughtcrime.securesms.coscomm

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.processor.CosSignalMessageProcessor
import org.thoughtcrime.securesms.coscomm.utils.CosMessageSerializer

/**
 * COS消息流程测试类
 * 用于验证COS请求发送和接收的完整流程
 */
object CosMessageFlowTest {
    private val TAG = Log.tag(CosMessageFlowTest::class.java)
    
    /**
     * 测试完整的COS消息流程
     */
    fun testCosMessageFlow(context: Context): Boolean {
        Log.i(TAG, "开始测试COS消息流程")
        
        try {
            // 1. 创建测试用的COS请求
            val testRequest = createTestCosRequest()
            Log.i(TAG, "创建测试请求: requestId=${testRequest.requestId}")
            
            // 2. 创建COS Signal消息
            val signalMessage = CosSignalMessage.Request.create(testRequest)
            Log.i(TAG, "创建Signal消息: messageId=${signalMessage.messageId}")
            
            // 3. 序列化消息
            val serializeResult = CosMessageSerializer.serializeSignalMessage(signalMessage)
            if (serializeResult !is CosResult.Success) {
                Log.e(TAG, "序列化失败")
                return false
            }
            
            val messageJson = serializeResult.data
            Log.i(TAG, "序列化成功: $messageJson")
            
            // 4. 添加COS前缀（模拟发送过程）
            val cosMessageWithPrefix = CosSignalMessageProcessor.COS_MESSAGE_PREFIX + messageJson
            Log.i(TAG, "添加前缀后: $cosMessageWithPrefix")
            
            // 5. 测试接收方检测
            val processor = CosSignalMessageProcessor.getInstance(context)
            val isCosMessage = processor.isCosMessage(cosMessageWithPrefix)
            Log.i(TAG, "COS消息检测结果: $isCosMessage")
            
            if (!isCosMessage) {
                Log.e(TAG, "COS消息检测失败")
                return false
            }
            
            // 6. 测试消息处理（这里只测试解析部分，不触发实际的UI）
            val processResult = testMessageProcessing(cosMessageWithPrefix)
            Log.i(TAG, "消息处理测试结果: $processResult")
            
            return processResult
            
        } catch (e: Exception) {
            Log.e(TAG, "COS消息流程测试失败", e)
            return false
        }
    }
    
    /**
     * 创建测试用的COS请求
     */
    private fun createTestCosRequest(): CosRequest {
        val accessInfo = CosAccessInfo(
            provider = "TENCENT",
            region = "ap-beijing", 
            bucketName = "test-bucket",
            accessKeyId = "test-access-key",
            secretAccessKey = "test-secret-key",
            sessionToken = "test-session-token",
            expireTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000, // 24小时后过期
            sharedDirectory = "/outbox/"
        )
        
        return CosRequest.create(
            durationType = CosDuration.PERMANENT,
            accessInfo = accessInfo,
            message = "测试COS通信请求"
        )
    }
    
    /**
     * 测试消息处理（不触发实际UI）
     */
    private fun testMessageProcessing(cosMessageWithPrefix: String): Boolean {
        return try {
            // 提取JSON部分
            val messageJson = cosMessageWithPrefix.removePrefix(CosSignalMessageProcessor.COS_MESSAGE_PREFIX)
            Log.d(TAG, "提取的JSON: $messageJson")
            
            // 测试反序列化
            val deserializeResult = CosMessageSerializer.deserializeSignalMessage(messageJson)
            if (deserializeResult !is CosResult.Success) {
                Log.e(TAG, "反序列化失败")
                return false
            }
            
            val signalMessage = deserializeResult.data
            Log.i(TAG, "反序列化成功: messageId=${signalMessage.messageId}")
            
            // 验证消息类型
            if (signalMessage !is CosSignalMessage.Request) {
                Log.e(TAG, "消息类型不匹配")
                return false
            }
            
            Log.i(TAG, "消息处理测试成功: requestId=${signalMessage.cosRequest.requestId}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "消息处理测试失败", e)
            false
        }
    }
    
    /**
     * 运行所有测试
     */
    fun runAllTests(context: Context): TestResult {
        Log.i(TAG, "开始运行所有COS测试")
        
        val results = mutableMapOf<String, Boolean>()
        
        // 测试1: 消息流程
        results["messageFlow"] = testCosMessageFlow(context)
        
        // 测试2: 序列化/反序列化
        results["serialization"] = testSerialization()
        
        // 测试3: 消息检测
        results["messageDetection"] = testMessageDetection(context)
        
        val allPassed = results.values.all { it }
        val passedCount = results.values.count { it }
        val totalCount = results.size
        
        Log.i(TAG, "测试完成: $passedCount/$totalCount 通过")
        
        return TestResult(allPassed, results)
    }
    
    /**
     * 测试序列化/反序列化
     */
    private fun testSerialization(): Boolean {
        return try {
            val testRequest = createTestCosRequest()
            val signalMessage = CosSignalMessage.Request.create(testRequest)

            // 序列化
            val serializeResult = CosMessageSerializer.serializeSignalMessage(signalMessage)
            if (serializeResult !is CosResult.Success) {
                Log.e(TAG, "序列化失败")
                return false
            }

            val jsonString = serializeResult.data
            Log.d(TAG, "序列化结果: $jsonString")

            // 检查是否包含不应该存在的字段
            if (jsonString.contains("remainingTime") || jsonString.contains("expired")) {
                Log.e(TAG, "序列化结果包含不应该存在的字段")
                return false
            }

            // 反序列化
            val deserializeResult = CosMessageSerializer.deserializeSignalMessage(jsonString)
            if (deserializeResult !is CosResult.Success) {
                Log.e(TAG, "反序列化失败")
                return false
            }

            val deserializedMessage = deserializeResult.data
            val success = deserializedMessage.messageId == signalMessage.messageId

            Log.i(TAG, "序列化测试结果: $success")
            success

        } catch (e: Exception) {
            Log.e(TAG, "序列化测试失败", e)
            false
        }
    }
    
    /**
     * 测试消息检测
     */
    private fun testMessageDetection(context: Context): Boolean {
        return try {
            val processor = CosSignalMessageProcessor.getInstance(context)
            
            // 测试正确的COS消息
            val validMessage = "${CosSignalMessageProcessor.COS_MESSAGE_PREFIX}{\"messageType\":\"COS_REQUEST\"}"
            val isValid = processor.isCosMessage(validMessage)
            
            // 测试普通消息
            val normalMessage = "Hello, this is a normal message"
            val isNormal = !processor.isCosMessage(normalMessage)
            
            isValid && isNormal
            
        } catch (e: Exception) {
            Log.e(TAG, "消息检测测试失败", e)
            false
        }
    }
    
    /**
     * 测试结果数据类
     */
    data class TestResult(
        val allPassed: Boolean,
        val results: Map<String, Boolean>
    )
}
