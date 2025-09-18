package org.thoughtcrime.securesms.tap.provider.cos.coscomm.examples

import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.*

/**
 * COS消息使用示例
 * 展示如何使用COS通信模块的核心功能
 */
object CosMessageExample {
    
    /**
     * 示例1：创建和序列化COS消息
     */
    fun createAndSerializeCosMessage(): String {
        // 1. 模拟Signal密文
        val signalCiphertext = "这是Signal加密后的消息内容".toByteArray()
        
        // 2. 创建COS消息
        val message = CosMessageUtils.createCosMessage(
            senderId = "sender-service-id-12345",
            recipientId = "recipient-service-id-67890",
            messageType = MessageType.TEXT,
            signalCiphertext = signalCiphertext,
            signalCiphertextType = 2 // WHISPER_TYPE
        )
        
        // 4. 验证消息
        val validationResult = CosMessageValidator.validateCosMessage(message)
        if (validationResult.isError()) {
            throw Exception("消息验证失败: ${validationResult.getErrorOrNull()?.message}")
        }
        
        // 5. 序列化为JSON
        val serializationResult = CosMessageSerializer.serializeMessage(message)
        return serializationResult.getOrThrow()
    }
    
    /**
     * 示例2：创建COS请求（使用配置）
     */
    fun createCosRequest(context: android.content.Context): CosRequest? {
        // 从配置存储获取COS配置
        val cosConfig = org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfigStorage.getConfig(context)
            ?: return null

        // 生成临时访问凭证
        val cosClient = org.thoughtcrime.securesms.tap.provider.cos.cos.CosClientFactory.createClient(cosConfig, context)
        val accessToken = cosClient.generateTemporaryAccessToken("/outbox/", 7 * 24 * 60) // 7天

        return CosMessageUtils.createCosRequest(
            provider = cosConfig.provider.name,
            region = cosConfig.region,
            bucketName = cosConfig.bucketName,
            accessKeyId = accessToken.accessKeyId,
            secretAccessKey = accessToken.secretAccessKey,
            sessionToken = accessToken.sessionToken,
            durationType = CosDuration.ONE_WEEK,
            message = "希望与您建立COS通信通道，提高通信可靠性"
        )
    }
    
    /**
     * 示例3：处理COS请求和响应（使用配置）
     */
    fun handleCosRequestResponse(context: android.content.Context) {
        // 创建请求
        val request = createCosRequest(context) ?: run {
            println("无法创建COS请求：配置未找到")
            return
        }

        // 验证请求
        val requestValidation = CosMessageValidator.validateCosRequest(request)
        if (requestValidation.isError()) {
            println("请求验证失败: ${requestValidation.getErrorOrNull()?.message}")
            return
        }

        // 序列化请求
        val requestJson = CosMessageSerializer.serializeRequest(request).getOrThrow()
        println("COS请求JSON: $requestJson")

        // 模拟接受请求，创建响应（使用配置）
        val cosConfig = org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfigStorage.getConfig(context)!!
        val cosClient = org.thoughtcrime.securesms.tap.provider.cos.cos.CosClientFactory.createClient(cosConfig, context)
        val responseToken = cosClient.generateTemporaryAccessToken("/outbox/", 7 * 24 * 60)

        val responseAccessInfo = CosAccessInfo(
            provider = cosConfig.provider.name,
            region = cosConfig.region,
            bucketName = cosConfig.bucketName,
            accessKeyId = responseToken.accessKeyId,
            secretAccessKey = responseToken.secretAccessKey,
            sessionToken = responseToken.sessionToken,
            expireTime = CosDuration.ONE_WEEK.getExpireTime()
        )
        
        val response = CosResponse.createAccepted(
            requestId = request.requestId,
            accessInfo = responseAccessInfo,
            agreedDuration = CosDuration.ONE_WEEK
        )
        
        // 验证响应
        val responseValidation = CosMessageValidator.validateCosResponse(response)
        if (responseValidation.isSuccess()) {
            println("响应验证成功")
        }
        
        // 序列化响应
        val responseJson = CosMessageSerializer.serializeResponse(response).getOrThrow()
        println("COS响应JSON: $responseJson")
    }
    
    /**
     * 示例4：文件路径管理
     */
    fun demonstratePathManagement() {
        // 生成消息文件路径
        val messageId = CosMessage.generateMessageId()
        val messagePath = CosPathManager.generateMessageFilePath(messageId)
        println("消息文件路径: $messagePath")
        
        // 生成附件文件路径
        val attachmentId = AttachmentInfo.generateAttachmentId()
        val attachmentPath = CosPathManager.generateAttachmentFilePath(attachmentId)
        println("附件文件路径: $attachmentPath")
        
        // 解析文件名信息
        val fileName = CosPathManager.getFileName(messagePath)
        val fileInfo = CosPathManager.parseMessageFileName(fileName)
        if (fileInfo != null) {
            println("解析文件信息: timestamp=${fileInfo.timestamp}, msgNum=${fileInfo.messageNumber}, chainNum=${fileInfo.chainNumber}")
        }
        
        // 检查路径安全性
        val isSafe = CosPathManager.isPathSafe(messagePath)
        println("路径安全检查: $isSafe")
    }
    
    /**
     * 示例5：创建和管理COS通道
     */
    fun createAndManageCosChannel() {
        // 创建COS通道
        val channel = CosChannel.create(
            recipientId = "recipient-12345",
            requestId = "request-67890",
            status = ChannelStatus.PENDING
        )
        
        // 验证通道
        val channelValidation = CosMessageValidator.validateCosChannel(channel)
        if (channelValidation.isError()) {
            println("通道验证失败: ${channelValidation.getErrorOrNull()?.message}")
            return
        }
        
        // 更新通道状态
        val activeChannel = channel.updateStatus(ChannelStatus.ACTIVE)
        
        // 检查通道功能
        println("通道摘要: ${CosMessageUtils.generateChannelDigest(activeChannel)}")
        println("可以发送消息: ${activeChannel.canSendMessages()}")
        println("可以接收消息: ${activeChannel.canReceiveMessages()}")
    }
    
    /**
     * 示例6：子账户Pool管理（使用配置）
     */
    fun manageSubAccountPool(context: android.content.Context) {
        // 从配置获取COS信息
        val cosConfig = org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfigStorage.getConfig(context) ?: run {
            println("COS配置未找到")
            return
        }

        // 生成临时访问凭证
        val cosClient = org.thoughtcrime.securesms.tap.provider.cos.cos.CosClientFactory.createClient(cosConfig)
        val accessToken = cosClient.generateTemporaryAccessToken("/outbox/", 7 * 24 * 60)

        // 创建子账户Pool条目
        val accessInfo = CosAccessInfo(
            provider = cosConfig.provider.name,
            region = cosConfig.region,
            bucketName = cosConfig.bucketName,
            accessKeyId = accessToken.accessKeyId,
            secretAccessKey = accessToken.secretAccessKey,
            sessionToken = accessToken.sessionToken,
            expireTime = accessToken.expireTime
        )

        val subAccountEntry = org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.SubAccountEntry.create(
            recipientId = "recipient-12345",
            accessInfo = accessInfo
        )

        // 检查子账户有效性
        println("子账户有效性: ${subAccountEntry.isValid()}")
        println("访问信息过期检查: ${subAccountEntry.accessInfo.isExpired()}")
        println("剩余有效时间: ${CosMessageUtils.formatTimeInterval(subAccountEntry.accessInfo.getRemainingTime())}")

        // 创建临时的兼容性条目用于轮询策略
        val tempCamEntry = CamPoolEntry.create("recipient-12345", accessInfo)

        // 计算轮询间隔
        val pollingInterval = CosMessageUtils.getPollingInterval(tempCamEntry.lastPollingTime)
        println("建议轮询间隔: ${CosMessageUtils.formatTimeInterval(pollingInterval)}")

        // 检查是否应该跳过轮询
        val shouldSkip = CosMessageUtils.shouldSkipPolling(tempCamEntry)
        println("是否跳过轮询: $shouldSkip")
    }
    
    /**
     * 示例7：错误处理
     */
    fun demonstrateErrorHandling() {
        // 创建一个无效的消息
        val invalidMessage = CosMessage(
            messageId = "", // 无效：空ID
            timestamp = -1, // 无效：负时间戳
            senderId = "",  // 无效：空发送者
            recipientId = "recipient",
            messageType = MessageType.TEXT,
            signalCiphertext = "", // 无效：空密文
            contentMetadata = ContentMetadata(originalSize = 0)
        )
        
        // 验证无效消息
        val result = CosMessageValidator.validateCosMessage(invalidMessage)
        result.onError { exception ->
            println("验证失败: ${exception.message}")
            println("错误代码: ${exception.getCode()}")
            println("是否可重试: ${exception.isRetryable()}")
        }
    }
    
    /**
     * 运行所有示例
     */
    fun runAllExamples(context: android.content.Context) {
        println("=== COS消息模块使用示例 ===\n")

        try {
            println("1. 创建和序列化COS消息:")
            val messageJson = createAndSerializeCosMessage()
            println("消息JSON长度: ${messageJson.length} 字符\n")

            println("2. 处理COS请求和响应:")
            handleCosRequestResponse(context)
            println()

            println("3. 文件路径管理:")
            demonstratePathManagement()
            println()

            println("4. COS通道管理:")
            createAndManageCosChannel()
            println()

            println("5. 子账户Pool管理:")
            manageSubAccountPool(context)
            println()

            println("6. 错误处理:")
            demonstrateErrorHandling()
            println()

            println("=== 所有示例运行完成 ===")

        } catch (e: Exception) {
            println("示例运行出错: ${e.message}")
            e.printStackTrace()
        }
    }
}
