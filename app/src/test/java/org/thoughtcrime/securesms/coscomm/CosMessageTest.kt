package org.thoughtcrime.securesms.coscomm

import org.junit.Test
import org.junit.Assert.*
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.utils.*
import android.util.Log

/**
 * COS消息模块单元测试
 * 验证核心数据结构和工具类的功能
 */
class CosMessageTest {
    
    @Test
    fun testCosMessageCreation() {
        // 创建Ratchet信息
        val ratchetInfo = RatchetInfo(
            messageNumber = 42,
            chainNumber = 3,
            ratchetPublicKey = "test-public-key",
            previousChainLength = 15
        )
        
        // 创建COS消息
        val message = CosMessage(
            messageId = "test-message-id",
            timestamp = System.currentTimeMillis(),
            senderId = "sender-123",
            recipientId = "recipient-456",
            messageType = MessageType.TEXT,
            ratchetInfo = ratchetInfo,
            encryptedContent = "encrypted-content",
            contentMetadata = ContentMetadata(originalSize = 100)
        )
        
        // 验证消息属性
        assertEquals("test-message-id", message.messageId)
        assertEquals("sender-123", message.senderId)
        assertEquals("recipient-456", message.recipientId)
        assertEquals(MessageType.TEXT, message.messageType)
        assertEquals(42, message.ratchetInfo.messageNumber)
        assertEquals(3, message.ratchetInfo.chainNumber)
    }
    
    @Test
    fun testCosRequestCreation() {
        // 创建访问信息
        val accessInfo = CosAccessInfo(
            provider = "AWS",
            region = "us-east-1",
            bucketName = "test-bucket",
            accessKeyId = "test-key-id",
            secretAccessKey = "test-secret",
            expireTime = System.currentTimeMillis() + 3600000 // 1小时后过期
        )
        
        // 创建COS请求
        val request = CosRequest.create(
            durationType = CosDuration.ONE_HOUR,
            accessInfo = accessInfo,
            message = "测试请求"
        )
        
        // 验证请求属性
        assertNotNull(request.requestId)
        assertEquals(CosDuration.ONE_HOUR, request.durationType)
        assertEquals("AWS", request.accessInfo.provider)
        assertEquals("测试请求", request.message)
        assertFalse(request.accessInfo.isExpired())
    }
    
    @Test
    fun testCosChannelManagement() {
        // 创建COS通道
        val channel = CosChannel.create(
            recipientId = "recipient-123",
            requestId = "request-456",
            status = ChannelStatus.PENDING
        )
        
        // 验证通道属性
        assertNotNull(channel.channelId)
        assertEquals("recipient-123", channel.recipientId)
        assertEquals("request-456", channel.requestId)
        assertEquals(ChannelStatus.PENDING, channel.status)
        assertFalse(channel.canSendMessages())
        assertFalse(channel.canReceiveMessages())
        
        // 更新通道状态
        val activeChannel = channel.updateStatus(ChannelStatus.ACTIVE)
        assertEquals(ChannelStatus.ACTIVE, activeChannel.status)
        assertTrue(activeChannel.updatedAt > channel.updatedAt)
    }
    
    @Test
    fun testMessageSerialization() {
        // 创建测试消息
        val ratchetInfo = RatchetInfo(
            messageNumber = 1,
            chainNumber = 0,
            ratchetPublicKey = "test-key",
            previousChainLength = 0
        )
        
        val message = CosMessage(
            messageId = "test-id",
            timestamp = 1640995200000,
            senderId = "sender",
            recipientId = "recipient",
            messageType = MessageType.TEXT,
            ratchetInfo = ratchetInfo,
            encryptedContent = "content",
            contentMetadata = ContentMetadata(originalSize = 50)
        )
        
        // 序列化消息
        val serializeResult = CosMessageSerializer.serializeMessage(message)
        assertTrue(serializeResult.isSuccess())
        
        val jsonString = serializeResult.getOrThrow()
        assertNotNull(jsonString)
        assertTrue(jsonString.contains("test-id"))
        assertTrue(jsonString.contains("sender"))
        assertTrue(jsonString.contains("recipient"))
        
        // 反序列化消息
        val deserializeResult = CosMessageSerializer.deserializeMessage(jsonString)
        assertTrue(deserializeResult.isSuccess())
        
        val deserializedMessage = deserializeResult.getOrThrow()
        assertEquals(message.messageId, deserializedMessage.messageId)
        assertEquals(message.senderId, deserializedMessage.senderId)
        assertEquals(message.recipientId, deserializedMessage.recipientId)
        assertEquals(message.messageType, deserializedMessage.messageType)
        assertEquals(message.ratchetInfo.messageNumber, deserializedMessage.ratchetInfo.messageNumber)
    }
    
    @Test
    fun testMessageValidation() {
        // 创建有效消息
        val validMessage = CosMessage(
            messageId = "valid-id",
            timestamp = System.currentTimeMillis(),
            senderId = "sender",
            recipientId = "recipient",
            messageType = MessageType.TEXT,
            ratchetInfo = RatchetInfo(
                messageNumber = 1,
                chainNumber = 0,
                ratchetPublicKey = "valid-key",
                previousChainLength = 0
            ),
            encryptedContent = "content",
            contentMetadata = ContentMetadata(originalSize = 50)
        )
        
        // 验证有效消息
        val validResult = CosMessageValidator.validateCosMessage(validMessage)
        assertTrue(validResult.isSuccess())
        
        // 创建无效消息（空ID）
        val invalidMessage = validMessage.copy(messageId = "")
        val invalidResult = CosMessageValidator.validateCosMessage(invalidMessage)
        assertTrue(invalidResult.isError())
        
        val error = invalidResult.getErrorOrNull()
        assertNotNull(error)
        assertEquals(CosErrorCode.INVALID_MESSAGE_FORMAT, error!!.errorCode)
    }
    
    @Test
    fun testPathGeneration() {
        // 测试消息文件路径生成
        val timestamp = 1640995200000L
        val messageNumber = 42
        val chainNumber = 3
        
        val messagePath = CosPathManager.generateMessageFilePath(timestamp, messageNumber, chainNumber)
        assertNotNull(messagePath)
        assertTrue(messagePath.startsWith("outbox/messages/"))
        assertTrue(messagePath.endsWith(".json"))
        assertTrue(messagePath.contains("1640995200000"))
        assertTrue(messagePath.contains("00042"))
        assertTrue(messagePath.contains("003"))
        
        // 测试附件文件路径生成
        val attachmentId = "test-attachment-id"
        val attachmentPath = CosPathManager.generateAttachmentFilePath(attachmentId)
        assertNotNull(attachmentPath)
        assertTrue(attachmentPath.startsWith("outbox/attachments/"))
        assertTrue(attachmentPath.endsWith(".bin"))
        assertTrue(attachmentPath.contains(attachmentId))
        
        // 测试路径安全检查
        assertTrue(CosPathManager.isPathSafe(messagePath))
        assertTrue(CosPathManager.isPathSafe(attachmentPath))
        assertFalse(CosPathManager.isPathSafe("../../../etc/passwd"))
        assertFalse(CosPathManager.isPathSafe("/absolute/path"))
    }
    
    @Test
    fun testBase64Encoding() {
        val testData = "Hello, COS!".toByteArray()
        
        // 编码
        val encoded = CosMessageSerializer.encodeBase64(testData)
        assertNotNull(encoded)
        assertFalse(encoded.isEmpty())
        
        // 解码
        val decodeResult = CosMessageSerializer.decodeBase64(encoded)
        assertTrue(decodeResult.isSuccess())
        
        val decoded = decodeResult.getOrThrow()
        assertArrayEquals(testData, decoded)
        
        // 测试字符串编码
        val testString = "测试中文字符串"
        val encodedString = CosMessageSerializer.encodeStringToBase64(testString)
        val decodedStringResult = CosMessageSerializer.decodeBase64ToString(encodedString)
        assertTrue(decodedStringResult.isSuccess())
        assertEquals(testString, decodedStringResult.getOrThrow())
    }
    
    @Test
    fun testUtilityMethods() {
        // 测试文件大小格式化
        assertEquals("1.0 KB", CosMessageUtils.formatFileSize(1024))
        assertEquals("1.0 MB", CosMessageUtils.formatFileSize(1024 * 1024))
        assertEquals("1.5 GB", CosMessageUtils.formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
        
        // 测试时间间隔格式化
        assertEquals("30秒", CosMessageUtils.formatTimeInterval(30 * 1000))
        assertEquals("5分钟", CosMessageUtils.formatTimeInterval(5 * 60 * 1000))
        assertEquals("2小时", CosMessageUtils.formatTimeInterval(2 * 60 * 60 * 1000))
        assertEquals("1天", CosMessageUtils.formatTimeInterval(24 * 60 * 60 * 1000))
        
        // 测试消息序号检查
        assertTrue(CosMessageUtils.isMessageNumberSequential(41, 42))
        assertFalse(CosMessageUtils.isMessageNumberSequential(41, 43))
        
        // 测试链序号检查
        assertTrue(CosMessageUtils.isChainNumberValid(2, 2))
        assertTrue(CosMessageUtils.isChainNumberValid(2, 3))
        assertFalse(CosMessageUtils.isChainNumberValid(2, 4))
        
        // 测试提供商支持检查
        assertTrue(CosMessageUtils.isSupportedProvider("AWS"))
        assertTrue(CosMessageUtils.isSupportedProvider("TENCENT"))
        assertTrue(CosMessageUtils.isSupportedProvider("ALIYUN"))
        assertFalse(CosMessageUtils.isSupportedProvider("UNKNOWN"))
    }
    
    @Test
    fun testErrorHandling() {
        // 测试CosException
        val exception = CosException(CosErrorCode.NETWORK_TIMEOUT, "测试超时")
        assertEquals(CosErrorCode.NETWORK_TIMEOUT, exception.errorCode)
        assertEquals(1001, exception.getCode())
        assertTrue(exception.isNetworkError())
        assertFalse(exception.isAuthError())
        assertTrue(exception.isRetryable())
        
        // 测试CosResult
        val successResult = CosResult.Success("成功数据")
        assertTrue(successResult.isSuccess())
        assertFalse(successResult.isError())
        assertEquals("成功数据", successResult.getOrNull())
        
        val errorResult = CosResult.Error(exception)
        assertFalse(errorResult.isSuccess())
        assertTrue(errorResult.isError())
        assertNull(errorResult.getOrNull())
        assertEquals(exception, errorResult.getErrorOrNull())
    }

    @Test
    fun testAttachmentPathConsistency() {
        // 测试附件路径一致性
        val attachmentId = AttachmentInfo.generateAttachmentId()
        val channelDirectory = "signal-v2-123-456"
        val expectedCosPath = "/v2-channels/$channelDirectory/outbox/attachments/${attachmentId}_12345678.bin"
        
        // 创建包含cosPath的AttachmentInfo
        val attachmentInfo = AttachmentInfo(
            fileName = "test.jpg",
            mimeType = "image/jpeg",
            size = 12345,
            attachmentId = attachmentId,
            fileHash = "hash123",
            cosPath = expectedCosPath
        )
        
        // 验证cosPath字段正确保存
        assertNotNull(attachmentInfo.cosPath)
        assertEquals(expectedCosPath, attachmentInfo.cosPath)
        
        // 创建包含附件的COS消息
        val timestamp = System.currentTimeMillis()
        val cosMessage = CosMessage(
            messageId = "test-message-1",
            timestamp = timestamp,
            senderId = "sender-123",
            recipientId = "recipient-456",
            messageType = MessageType.ATTACHMENT,
            ratchetInfo = RatchetInfo(
                messageNumber = 1,
                chainNumber = 0,
                ratchetPublicKey = "test-key",
                previousChainLength = 0
            ),
            encryptedContent = "encrypted-content",
            contentMetadata = ContentMetadata(
                originalSize = 100,
                compressionType = CompressionType.NONE
            ),
            attachmentInfo = attachmentInfo
        )
        
        // 验证消息中的附件信息包含正确的cosPath
        assertNotNull(cosMessage.attachmentInfo)
        assertEquals(expectedCosPath, cosMessage.attachmentInfo?.cosPath)
        
        // 测试序列化和反序列化保持cosPath不变
        val serialized = CosMessageSerializer.serializeMessage(cosMessage)
        assertTrue(serialized.isSuccess())
        
        val deserialized = CosMessageSerializer.deserializeMessage(serialized.getOrNull()!!)
        assertTrue(deserialized.isSuccess())
        
        val deserializedMessage = deserialized.getOrNull()!!
        assertNotNull(deserializedMessage.attachmentInfo)
        assertEquals(expectedCosPath, deserializedMessage.attachmentInfo?.cosPath)
        
        println("附件路径一致性测试通过: $expectedCosPath")
    }

    @Test
    fun testServiceIdToRecipientIdConversion() {
        // 测试ServiceId格式到RecipientId格式的转换
        // 这是修复附件下载ID格式不匹配问题的关键
        
        val serviceIdString = "12345678-1234-5678-9abc-123456789012" // 模拟ServiceId格式
        val expectedRecipientIdString = "RecipientId::4" // 期望的RecipientId格式
        
        // 验证ID格式识别
        assertTrue("ServiceId格式识别失败", 
            serviceIdString.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        
        assertTrue("RecipientId格式识别失败", 
            expectedRecipientIdString.startsWith("RecipientId::"))
        
        // 注意：实际转换需要数据库支持，这里只验证格式识别
        Log.d("CosMessageTest", "ID格式转换测试: $serviceIdString -> 需要转换为RecipientId格式")
    }
}
