package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.CosClient
import org.thoughtcrime.securesms.cos.CosClientFactory
import org.thoughtcrime.securesms.cos.CosConfig
import org.thoughtcrime.securesms.cos.CosConfigStorage
// CamPoolManager已删除，使用SubAccountPoolManager
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.storage.CosChannelStorage
import org.thoughtcrime.securesms.coscomm.utils.CosMessageSerializer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.database.ThreadTable
import org.whispersystems.signalservice.api.push.ServiceId
import kotlin.time.Duration.Companion.seconds
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * COS请求管理器
 * 负责处理COS通信请求的生成、发送、接收和响应
 */
class CosRequestManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosRequestManager::class.java)
        
        @Volatile
        private var INSTANCE: CosRequestManager? = null
        
        fun getInstance(context: Context): CosRequestManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosRequestManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // CosConfigStorage是object，不需要实例化
    private val channelManager = CosChannelManager.getInstance(context)
    private val channelStorage = CosChannelStorage(context)
    private val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
    
    /**
     * 生成并发送COS通信请求
     * @param recipientId 接收方的Service ID
     * @param durationType 请求的访问时长类型
     * @param message 可选的请求说明
     * @return 请求发送结果
     */
    fun sendCosRequest(
        recipientId: String,
        durationType: CosDuration,
        message: String? = null
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "开始发送COS请求: recipientId=$recipientId, duration=$durationType")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 检查是否已存在活跃通道
                val existingChannel = channelManager.getChannel(recipientId)
                if (existingChannel?.status == ChannelStatus.ACTIVE) {
                    Log.w(TAG, "已存在活跃的COS通道: recipientId=$recipientId")
                    return@supplyAsync CosRequestResult.Failure("已存在活跃的COS通道")
                }
                
                // 2. 获取COS配置
                val cosConfig = CosConfigStorage.getConfig(context)
                if (cosConfig == null) {
                    Log.e(TAG, "COS配置未找到，无法生成请求")
                    return@supplyAsync CosRequestResult.Failure("COS配置未找到，请先配置COS服务")
                }
                
                // 3. 生成CAM凭证
                val accessInfo = generateCamCredentials(cosConfig, durationType)
                if (accessInfo == null) {
                    Log.e(TAG, "生成CAM凭证失败")
                    return@supplyAsync CosRequestResult.Failure("生成CAM凭证失败")
                }
                
                // 4. 创建COS请求
                val cosRequest = CosRequest.create(
                    durationType = durationType,
                    accessInfo = accessInfo,
                    message = message
                )
                
                // 5. 创建通道记录
                val channel = channelManager.createChannel(
                    recipientId = recipientId,
                    requestId = cosRequest.requestId,
                    status = ChannelStatus.PENDING
                )
                
                // 6. 保存我的访问信息到通道
                channelManager.updateChannelAccessInfo(recipientId, myAccessInfo = accessInfo)
                
                // 7. 构造Signal消息并发送
                val signalMessage = CosSignalMessage.Request.create(cosRequest)
                val sendResult = sendSignalMessage(recipientId, signalMessage)
                
                if (sendResult) {
                    Log.i(TAG, "COS请求发送成功: requestId=${cosRequest.requestId}")
                    CosRequestResult.Success(cosRequest.requestId, channel.channelId)
                } else {
                    Log.e(TAG, "COS请求发送失败")
                    // 清理创建的通道
                    channelManager.deleteChannel(recipientId)
                    CosRequestResult.Failure("消息发送失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "发送COS请求时发生异常", e)
                CosRequestResult.Failure("发送请求时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 处理接收到的COS请求
     * @param senderId 发送方的Service ID
     * @param requestMessage 请求消息
     * @return 处理结果
     */
    fun handleReceivedRequest(
        senderId: String,
        requestMessage: CosSignalMessage.Request
    ): CosMessageProcessResult {
        Log.i(TAG, "处理接收到的COS请求: senderId=$senderId, requestId=${requestMessage.cosRequest.requestId}")
        
        try {
            val cosRequest = requestMessage.cosRequest
            
            // 1. 验证请求的有效性
            val validationResult = validateCosRequest(cosRequest)
            if (!validationResult.isValid) {
                Log.w(TAG, "COS请求验证失败: ${validationResult.errorMessage}")
                return CosMessageProcessResult.failure(
                    requestMessage.messageId,
                    "请求验证失败: ${validationResult.errorMessage}"
                )
            }
            
            // 2. 检查是否已存在通道
            val existingChannel = channelManager.getChannel(senderId)
            if (existingChannel != null && existingChannel.status == ChannelStatus.ACTIVE) {
                Log.w(TAG, "已存在活跃的COS通道，拒绝新请求: senderId=$senderId")
                return CosMessageProcessResult.failure(
                    requestMessage.messageId,
                    "已存在活跃的COS通道"
                )
            }
            
            // 3. 创建或更新通道记录
            val channel = channelManager.createChannel(
                recipientId = senderId,
                requestId = cosRequest.requestId,
                status = ChannelStatus.PENDING
            )
            
            // 4. 保存对方的访问信息
            channelManager.updateChannelAccessInfo(senderId, theirAccessInfo = cosRequest.accessInfo)
            
            // 5. 标记为等待用户确认
            // 这里应该触发UI显示请求确认界面
            // 实际的接受/拒绝操作由用户在UI中触发
            
            Log.i(TAG, "COS请求处理完成，等待用户确认: requestId=${cosRequest.requestId}")
            return CosMessageProcessResult.success(requestMessage.messageId, channel.channelId)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理COS请求时发生异常", e)
            return CosMessageProcessResult.failure(
                requestMessage.messageId,
                "处理请求时发生异常: ${e.message}"
            )
        }
    }

    /**
     * 接受COS请求并发送响应
     * @param senderId 发送方的Service ID
     * @param requestId 请求ID
     * @param agreedDuration 同意的访问时长
     * @return 响应发送结果
     */
    fun acceptCosRequest(
        senderId: String,
        requestId: String,
        agreedDuration: CosDuration
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "接受COS请求: senderId=$senderId, requestId=$requestId")

        return CompletableFuture.supplyAsync {
            try {
                // 1. 获取COS配置
                val cosConfig = CosConfigStorage.getConfig(context)
                if (cosConfig == null) {
                    Log.e(TAG, "COS配置未找到，无法生成响应")
                    return@supplyAsync CosRequestResult.Failure("COS配置未找到")
                }

                // 2. 生成我的CAM凭证
                val myAccessInfo = generateCamCredentials(cosConfig, agreedDuration)
                if (myAccessInfo == null) {
                    Log.e(TAG, "生成CAM凭证失败")
                    return@supplyAsync CosRequestResult.Failure("生成CAM凭证失败")
                }

                // 3. 创建COS响应
                val cosResponse = CosResponse.createAccepted(
                    requestId = requestId,
                    accessInfo = myAccessInfo,
                    agreedDuration = agreedDuration
                )

                // 4. 更新通道状态和我的访问信息
                channelManager.updateChannelAccessInfo(senderId, myAccessInfo = myAccessInfo)

                // 5. 构造Signal消息并发送
                val signalMessage = CosSignalMessage.Response.create(cosResponse)
                val sendResult = sendSignalMessage(senderId, signalMessage)

                if (sendResult) {
                    // 6. 尝试建立通道（如果双方都有访问信息）
                    val established = channelManager.establishChannel(senderId)
                    if (established) {
                        // 7. 将对方的子账户添加到Pool
                        val channel = channelManager.getChannel(senderId)
                        channel?.theirAccessInfo?.let { theirAccessInfo ->
                            subAccountPoolManager.addReceivedSubAccount(senderId, theirAccessInfo)
                        }
                        Log.i(TAG, "COS通道建立成功: senderId=$senderId")
                    }

                    Log.i(TAG, "COS响应发送成功: requestId=$requestId")
                    CosRequestResult.Success(requestId, channelManager.getChannel(senderId)?.channelId)
                } else {
                    Log.e(TAG, "COS响应发送失败")
                    CosRequestResult.Failure("响应发送失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "接受COS请求时发生异常", e)
                CosRequestResult.Failure("接受请求时发生异常: ${e.message}")
            }
        }
    }

    /**
     * 拒绝COS请求并发送响应
     * @param senderId 发送方的Service ID
     * @param requestId 请求ID
     * @param rejectionReason 拒绝原因
     * @return 响应发送结果
     */
    fun rejectCosRequest(
        senderId: String,
        requestId: String,
        rejectionReason: String
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "拒绝COS请求: senderId=$senderId, requestId=$requestId, reason=$rejectionReason")

        return CompletableFuture.supplyAsync {
            try {
                // 1. 创建拒绝响应
                val cosResponse = CosResponse.createRejected(
                    requestId = requestId,
                    rejectionReason = rejectionReason
                )

                // 2. 构造Signal消息并发送
                val signalMessage = CosSignalMessage.Response.create(cosResponse)
                val sendResult = sendSignalMessage(senderId, signalMessage)

                if (sendResult) {
                    // 3. 清理通道记录
                    channelManager.deleteChannel(senderId)

                    Log.i(TAG, "COS拒绝响应发送成功: requestId=$requestId")
                    CosRequestResult.Success(requestId, null)
                } else {
                    Log.e(TAG, "COS拒绝响应发送失败")
                    CosRequestResult.Failure("拒绝响应发送失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "拒绝COS请求时发生异常", e)
                CosRequestResult.Failure("拒绝请求时发生异常: ${e.message}")
            }
        }
    }

    /**
     * 处理接收到的COS响应
     * @param senderId 发送方的Service ID
     * @param responseMessage 响应消息
     * @return 处理结果
     */
    fun handleReceivedResponse(
        senderId: String,
        responseMessage: CosSignalMessage.Response
    ): CosMessageProcessResult {
        Log.i(TAG, "处理接收到的COS响应: senderId=$senderId, requestId=${responseMessage.cosResponse.requestId}")

        try {
            // 标准化RecipientId格式
            val standardizedSenderId = standardizeRecipientId(senderId)
            Log.d(TAG, "标准化RecipientId: 原始=$senderId, 标准化=$standardizedSenderId")

            val cosResponse = responseMessage.cosResponse

            if (cosResponse.accepted) {
                // 接受的响应
                Log.i(TAG, "COS请求被接受: requestId=${cosResponse.requestId}")

                // 1. 验证响应的访问信息
                val theirAccessInfo = cosResponse.accessInfo
                if (theirAccessInfo == null) {
                    Log.e(TAG, "接受响应中缺少访问信息")
                    return CosMessageProcessResult.failure(
                        responseMessage.messageId,
                        "接受响应中缺少访问信息"
                    )
                }

                // 2. 更新通道的对方访问信息 - 使用标准化的ID
                channelManager.updateChannelAccessInfo(standardizedSenderId, theirAccessInfo = theirAccessInfo)

                // 3. 尝试建立通道 - 使用标准化的ID
                val established = channelManager.establishChannel(standardizedSenderId)
                if (established) {
                    // 4. 插入"v2 mode enabled"系统消息（A方收到CAM响应后）
                    try {
                        org.thoughtcrime.securesms.coscomm.manager.CosDisconnectionManager.insertV2ModeEnabledMessage(context, standardizedSenderId)
                    } catch (e: Exception) {
                        Log.w(TAG, "插入v2 mode enabled消息失败，但继续处理: senderId=$standardizedSenderId", e)
                    }

                    // 5. 将对方的子账户添加到Pool - 使用标准化的ID
                    val addSubAccountResult = subAccountPoolManager.addReceivedSubAccount(standardizedSenderId, theirAccessInfo)
                    if (addSubAccountResult.isSuccess()) {
                        Log.i(TAG, "对方子账户凭证已添加到Pool: senderId=$standardizedSenderId")

                        // 6. 启动轮询服务
                        try {
                            val pollingManager = org.thoughtcrime.securesms.coscomm.manager.CosPollingManager.getInstance(context)
                            pollingManager.initialize()
                            val pollingStarted = pollingManager.startPolling()
                            if (pollingStarted) {
                                Log.i(TAG, "COS轮询服务已启动: senderId=$standardizedSenderId")
                            } else {
                                Log.w(TAG, "COS轮询服务启动失败，但通道已建立: senderId=$standardizedSenderId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "启动轮询服务异常: senderId=$standardizedSenderId", e)
                        }
                    } else {
                        Log.w(TAG, "添加子账户凭证失败: senderId=$standardizedSenderId, error=${addSubAccountResult.getErrorOrNull()?.message}")
                    }

                    Log.i(TAG, "COS通道建立成功: senderId=$standardizedSenderId")
                    return CosMessageProcessResult.success(
                        responseMessage.messageId,
                        channelManager.getChannel(standardizedSenderId)?.channelId
                    )
                } else {
                    Log.e(TAG, "COS通道建立失败")
                    return CosMessageProcessResult.failure(
                        responseMessage.messageId,
                        "通道建立失败"
                    )
                }

            } else {
                // 拒绝的响应
                Log.i(TAG, "COS请求被拒绝: requestId=${cosResponse.requestId}, reason=${cosResponse.rejectionReason}")

                // 清理通道记录 - 使用标准化的ID
                channelManager.deleteChannel(standardizedSenderId)

                return CosMessageProcessResult.success(responseMessage.messageId)
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理COS响应时发生异常", e)
            return CosMessageProcessResult.failure(
                responseMessage.messageId,
                "处理响应时发生异常: ${e.message}"
            )
        }
    }

    /**
     * 撤销COS通道
     * @param recipientId 对方的Service ID
     * @param revocationReason 撤销原因
     * @param revocationType 撤销类型
     * @return 撤销结果
     */
    fun revokeCosChannel(
        recipientId: String,
        revocationReason: String,
        revocationType: RevocationType = RevocationType.USER_INITIATED
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "撤销COS通道: recipientId=$recipientId, reason=$revocationReason")

        return CompletableFuture.supplyAsync {
            try {
                // 1. 获取通道信息
                val channel = channelManager.getChannel(recipientId)
                if (channel == null) {
                    Log.w(TAG, "未找到COS通道: recipientId=$recipientId")
                    return@supplyAsync CosRequestResult.Failure("未找到COS通道")
                }

                // 2. 创建撤销消息
                val cosRevocation = CosRevocation.create(
                    requestId = channel.requestId,
                    revocationReason = revocationReason,
                    type = revocationType
                )

                // 3. 构造Signal消息并发送
                val signalMessage = CosSignalMessage.Revocation.create(cosRevocation)
                val sendResult = sendSignalMessage(recipientId, signalMessage)

                if (sendResult) {
                    // 4. 清理本地资源
                    cleanupChannelResources(recipientId)

                    Log.i(TAG, "COS撤销消息发送成功: requestId=${channel.requestId}")
                    CosRequestResult.Success(channel.requestId, null)
                } else {
                    Log.e(TAG, "COS撤销消息发送失败")
                    CosRequestResult.Failure("撤销消息发送失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "撤销COS通道时发生异常", e)
                CosRequestResult.Failure("撤销通道时发生异常: ${e.message}")
            }
        }
    }

    /**
     * 处理接收到的COS撤销消息
     * @param senderId 发送方的Service ID
     * @param revocationMessage 撤销消息
     * @return 处理结果
     */
    fun handleReceivedRevocation(
        senderId: String,
        revocationMessage: CosSignalMessage.Revocation
    ): CosMessageProcessResult {
        Log.i(TAG, "处理接收到的COS撤销: senderId=$senderId, requestId=${revocationMessage.cosRevocation.requestId}")

        try {
            // 清理本地资源
            cleanupChannelResources(senderId)

            Log.i(TAG, "COS撤销处理完成: senderId=$senderId")
            return CosMessageProcessResult.success(revocationMessage.messageId)

        } catch (e: Exception) {
            Log.e(TAG, "处理COS撤销时发生异常", e)
            return CosMessageProcessResult.failure(
                revocationMessage.messageId,
                "处理撤销时发生异常: ${e.message}"
            )
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 生成CAM凭证
     */
    private fun generateCamCredentials(cosConfig: CosConfig, durationType: CosDuration): CosAccessInfo? {
        return try {
            // 检查是否使用永久凭证模式
            val usePermanentCredentials = shouldUsePermanentCredentials(durationType)

            if (usePermanentCredentials) {
                // 使用子用户永久凭证
                generatePermanentCredentials(cosConfig, durationType)
            } else {
                // 使用传统的临时凭证
                generateTemporaryCredentials(cosConfig, durationType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "生成CAM凭证失败", e)
            null
        }
    }

    /**
     * 判断是否应该使用永久凭证
     */
    private fun shouldUsePermanentCredentials(durationType: CosDuration): Boolean {
        val credentialType = CosConfigStorage.getCredentialType(context)

        return when (credentialType) {
            org.thoughtcrime.securesms.cos.CosCredentialType.PERMANENT -> true
            org.thoughtcrime.securesms.cos.CosCredentialType.TEMPORARY -> false
            org.thoughtcrime.securesms.cos.CosCredentialType.AUTO -> {
                // 自动模式：对于PERMANENT类型优先使用永久凭证
                durationType == CosDuration.PERMANENT
            }
        }
    }

    /**
     * 生成永久凭证（使用子用户）
     */
    private fun generatePermanentCredentials(cosConfig: CosConfig, durationType: CosDuration): CosAccessInfo? {
        return try {
            Log.d(TAG, "使用子用户生成永久凭证")

            val subUserManager = org.thoughtcrime.securesms.cos.CosSubUserManagerFactory.createManager(cosConfig, context)
            val cosClient = org.thoughtcrime.securesms.cos.CosClientFactory.createClient(cosConfig, context)

            // 生成唯一的通道目录名和子用户名（按照新技术规范）
            val timestamp = System.currentTimeMillis()
            val randomSuffix = (1000..9999).random()
            val channelDirectoryName = "signal-v2-${timestamp}-${randomSuffix}"
            val channelDirectoryPath = "/v2-channels/$channelDirectoryName/"
            val subUserName = "signal-cos-$channelDirectoryName"

            Log.i(TAG, "创建COS v2通道目录: $channelDirectoryPath")

            // 1. 创建v2通道目录结构（按照新技术规范）
            try {
                // 创建主通道目录
                cosClient.createDirectory(channelDirectoryPath)

                // 创建子目录结构
                cosClient.createDirectory("${channelDirectoryPath}outbox/")           // 我发送给对方的消息
                cosClient.createDirectory("${channelDirectoryPath}outbox/messages/")  // 消息文件
                cosClient.createDirectory("${channelDirectoryPath}outbox/attachments/") // 附件文件
                cosClient.createDirectory("${channelDirectoryPath}outbox/metadata/")  // 消息索引
                cosClient.createDirectory("${channelDirectoryPath}inbox/")            // 对方发送给我的消息（本地使用）
                cosClient.createDirectory("${channelDirectoryPath}metadata/")         // 通道元数据和状态信息

                Log.i(TAG, "COS v2通道目录结构创建成功: $channelDirectoryPath")
            } catch (e: Exception) {
                Log.e(TAG, "创建COS v2通道目录结构失败", e)
                throw e
            }

            // 2. 创建子用户并分配权限（只读权限给对方访问我的outbox目录及其子目录）
            val subUserCredential = subUserManager.createSubUser(
                userName = subUserName,
                directoryPath = "${channelDirectoryPath}outbox", // 允许对方访问我的outbox目录及其子目录（不以/结尾，让权限策略添加/*）
                permissions = org.thoughtcrime.securesms.cos.CosPermission.READ_ONLY // 对方只能读取
            )

            Log.i(TAG, "永久凭证生成成功，子用户: $subUserName, 通道目录: $channelDirectoryPath")

            // 转换为CosAccessInfo格式（按照新技术规范）
            subUserCredential.toCosAccessInfo(
                provider = cosConfig.provider.name,
                region = cosConfig.region,
                bucketName = cosConfig.bucketName
            ).copy(
                sharedDirectory = "${channelDirectoryPath}outbox/" // 明确指定共享的是outbox目录（保持原有格式用于兼容性）
            )

        } catch (e: Exception) {
            Log.e(TAG, "生成永久凭证失败，放弃发送COS请求", e)
            return null
        }
    }

    /**
     * 生成临时凭证（原有逻辑）
     */
    private fun generateTemporaryCredentials(cosConfig: CosConfig, durationType: CosDuration): CosAccessInfo? {
        return try {
            Log.d(TAG, "使用STS生成临时凭证")

            // 计算访问时长（分钟），限制在API允许范围内
            val durationMinutes = when {
                durationType == CosDuration.PERMANENT -> {
                    // 对于PERMANENT，使用最大允许时长
                    if (cosConfig.provider == CosConfig.Provider.AWS) {
                        12 * 60 // AWS最多12小时
                    } else {
                        2 * 60 // 腾讯云最多2小时
                    }
                }
                else -> durationType.hours * 60
            }

            // 使用COS客户端生成真实的临时访问凭证
            val cosClient = CosClientFactory.createClient(cosConfig, context)
            val accessToken = cosClient.generateTemporaryAccessToken("/outbox/", durationMinutes)

            Log.i(TAG, "临时凭证生成成功，有效期: $durationMinutes 分钟")

            // 转换为CosAccessInfo格式
            CosAccessInfo(
                provider = cosConfig.provider.name,
                region = cosConfig.region,
                bucketName = cosConfig.bucketName,
                accessKeyId = accessToken.accessKeyId,
                secretAccessKey = accessToken.secretAccessKey,
                sessionToken = accessToken.sessionToken,
                expireTime = accessToken.expireTime,
                sharedDirectory = "/outbox/"
            )
        } catch (e: Exception) {
            Log.e(TAG, "生成临时凭证失败", e)
            null
        }
    }

    /**
     * 接受COS请求
     */
    fun acceptRequest(requestId: String, senderId: String): CompletableFuture<CosRequestResult> {
        return CompletableFuture.supplyAsync {
            try {
                Log.i(TAG, "开始处理接受COS请求: requestId=$requestId, senderId=$senderId")

                // 标准化RecipientId格式
                val standardizedSenderId = standardizeRecipientId(senderId)
                Log.d(TAG, "标准化RecipientId: 原始=$senderId, 标准化=$standardizedSenderId")

                // 1. 获取COS配置
                val cosConfig = CosConfigStorage.getConfig(context)
                if (cosConfig == null) {
                    Log.e(TAG, "COS配置未找到")
                    return@supplyAsync CosRequestResult.Failure("COS配置未找到")
                }

                // 2. 生成我的CAM凭证（创建目录和子账户）
                val myAccessInfo = generatePermanentCredentials(cosConfig, CosDuration.PERMANENT)
                if (myAccessInfo == null) {
                    Log.e(TAG, "生成CAM凭证失败")
                    return@supplyAsync CosRequestResult.Failure("生成CAM凭证失败")
                }

                // 3. 创建响应消息
                val cosResponse = CosResponse.createAccepted(
                    requestId = requestId,
                    accessInfo = myAccessInfo,
                    agreedDuration = CosDuration.PERMANENT
                )

                // 4. 更新通道状态 - 使用标准化的ID
                val channel = channelManager.getChannel(standardizedSenderId)
                if (channel != null) {
                    // 更新我的访问信息 - 使用标准化的ID
                    channelManager.updateChannelAccessInfo(standardizedSenderId, myAccessInfo = myAccessInfo)

                    // 5. 尝试建立完整通道（B方现在有了双方的访问信息）
                    val established = channelManager.establishChannel(standardizedSenderId)
                    if (established) {
                        Log.i(TAG, "COS通道建立成功: senderId=$standardizedSenderId")

                        // 6. 插入"v2 mode enabled"系统消息（B方接受请求后）
                        try {
                            org.thoughtcrime.securesms.coscomm.manager.CosDisconnectionManager.insertV2ModeEnabledMessage(context, standardizedSenderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "插入v2 mode enabled消息失败，但继续处理: senderId=$standardizedSenderId", e)
                        }

                        // 7. 获取更新后的通道信息
                        val updatedChannel = channelManager.getChannel(standardizedSenderId)
                        if (updatedChannel?.theirAccessInfo != null) {
                            // 将对方的子账户添加到Pool - 使用标准化的ID
                            val addSubAccountResult = subAccountPoolManager.addReceivedSubAccount(standardizedSenderId, updatedChannel.theirAccessInfo!!)
                            if (addSubAccountResult.isSuccess()) {
                                Log.i(TAG, "对方子账户凭证已添加到Pool: senderId=$standardizedSenderId")

                                // 启动轮询服务
                                try {
                                    val pollingManager = org.thoughtcrime.securesms.coscomm.manager.CosPollingManager.getInstance(context)
                                    pollingManager.initialize()
                                    val pollingStarted = pollingManager.startPolling()
                                    if (pollingStarted) {
                                        Log.i(TAG, "COS轮询服务已启动: senderId=$standardizedSenderId")
                                    } else {
                                        Log.w(TAG, "COS轮询服务启动失败: senderId=$standardizedSenderId")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "启动轮询服务异常: senderId=$standardizedSenderId", e)
                                }
                            } else {
                                Log.w(TAG, "添加对方子账户凭证失败: senderId=$standardizedSenderId, error=${addSubAccountResult.getErrorOrNull()?.message}")
                            }
                        } else {
                            Log.w(TAG, "通道建立成功但缺少对方访问信息: senderId=$standardizedSenderId")
                        }
                    } else {
                        Log.w(TAG, "通道建立失败: senderId=$standardizedSenderId")
                        // 检查失败原因
                        val debugChannel = channelManager.getChannel(standardizedSenderId)
                        Log.w(TAG, "通道状态调试: myAccessInfo=${debugChannel?.myAccessInfo != null}, theirAccessInfo=${debugChannel?.theirAccessInfo != null}")
                    }
                } else {
                    Log.w(TAG, "未找到对应的通道: $standardizedSenderId (原始: $senderId)")
                }

                // 5. 发送响应消息 - 使用原始senderId发送（因为这是对方的ID）
                val signalMessage = CosSignalMessage.Response.create(cosResponse)
                val sendResult = sendSignalMessage(senderId, signalMessage)

                if (sendResult) {
                    Log.i(TAG, "COS请求接受成功: requestId=$requestId")
                    CosRequestResult.Success(requestId, channel?.channelId ?: "")
                } else {
                    Log.e(TAG, "发送响应消息失败")
                    CosRequestResult.Failure("发送响应消息失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "接受COS请求时发生异常", e)
                CosRequestResult.Failure("接受请求时发生异常: ${e.message}")
            }
        }
    }

    /**
     * 拒绝COS请求
     */
    fun rejectRequest(requestId: String, senderId: String, reason: String): CompletableFuture<CosRequestResult> {
        return CompletableFuture.supplyAsync {
            try {
                Log.i(TAG, "开始处理拒绝COS请求: requestId=$requestId, senderId=$senderId")

                // 1. 创建拒绝响应消息
                val cosResponse = CosResponse.createRejected(
                    requestId = requestId,
                    rejectionReason = reason
                )

                // 2. 删除通道（如果存在）
                channelManager.deleteChannel(senderId)

                // 3. 发送拒绝消息
                val signalMessage = CosSignalMessage.Response.create(cosResponse)
                val sendResult = sendSignalMessage(senderId, signalMessage)

                if (sendResult) {
                    Log.i(TAG, "COS请求拒绝成功: requestId=$requestId")
                    CosRequestResult.Success(requestId, "")
                } else {
                    Log.e(TAG, "发送拒绝消息失败")
                    CosRequestResult.Failure("发送拒绝消息失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "拒绝COS请求时发生异常", e)
                CosRequestResult.Failure("拒绝请求时发生异常: ${e.message}")
            }
        }
    }

    /**
     * 验证COS请求
     */
    private fun validateCosRequest(cosRequest: CosRequest): ValidationResult {
        // 1. 检查请求是否过期（24小时）
        val requestAge = System.currentTimeMillis() - cosRequest.timestamp
        if (requestAge > 24 * 60 * 60 * 1000) {
            return ValidationResult(false, "请求已过期")
        }

        // 2. 检查访问信息是否有效
        if (cosRequest.accessInfo.isExpired()) {
            return ValidationResult(false, "访问凭证已过期")
        }

        // 3. 检查访问时长是否合理
        if (cosRequest.durationType == CosDuration.PERMANENT) {
            // 可以根据需要限制永久访问
            Log.w(TAG, "收到永久访问请求，需要用户特别确认")
        }

        return ValidationResult(true, null)
    }

    /**
     * 发送Signal消息
     */
    private fun sendSignalMessage(recipientId: String, signalMessage: CosSignalMessage): Boolean {
        return try {
            // 使用CosMessageSerializer进行完整的序列化
            val serializeResult = CosMessageSerializer.serializeSignalMessage(signalMessage)
            if (serializeResult !is CosResult.Success) {
                Log.e(TAG, "序列化COS Signal消息失败")
                return false
            }

            val messageJson = serializeResult.data
            Log.d(TAG, "序列化COS消息成功: $messageJson")

            // 添加COS消息前缀，确保接收方能正确识别
            val cosMessageWithPrefix = org.thoughtcrime.securesms.coscomm.processor.CosSignalMessageProcessor.COS_MESSAGE_PREFIX + messageJson

            // 强制通过Signal Server发送COS控制消息
            Log.i(TAG, "强制通过Signal Server发送COS控制消息: ${signalMessage::class.simpleName}")
            sendSignalMessageViaServer(recipientId, cosMessageWithPrefix)
        } catch (e: Exception) {
            Log.e(TAG, "发送Signal消息失败: recipientId=$recipientId", e)
            false
        }
    }

    /**
     * 强制通过Signal Server发送消息（绕过COS路由）
     */
    private fun sendSignalMessageViaServer(recipientId: String, messageJson: String): Boolean {
        return try {
            // 解析recipientId，支持多种格式
            val recipient = parseRecipientFromId(recipientId) ?: return false

            Log.d(TAG, "强制通过Signal Server发送消息: recipientId=$recipientId")
            Log.d(TAG, "消息内容: $messageJson")

            // 创建OutgoingMessage，标记为COS控制消息
            val outgoingMessage = OutgoingMessage.text(
                threadRecipient = recipient,
                body = messageJson,
                expiresIn = recipient.expiresInSeconds.seconds.inWholeMilliseconds,
                sentTimeMillis = System.currentTimeMillis()
            ).makeSecure()

            // 获取或创建线程ID
            val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

            // 直接使用Signal的MessageSender发送，不经过COS路由
            // 由于消息包含COS_MESSAGE_PREFIX，路由器会自动识别为控制消息
            val messageId = MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                MessageSender.SendType.SIGNAL,
                null, // metricId
                null  // insertListener
            )

            Log.i(TAG, "Signal Server消息发送成功: messageId=$messageId, threadId=$threadId")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Signal Server消息发送失败", e)
            false
        }
    }

    /**
     * 从recipientId字符串解析Recipient对象
     * 支持多种格式：ServiceId、RecipientId::X、纯数字ID等
     */
    private fun parseRecipientFromId(recipientId: String): Recipient? {
        return try {
            Log.d(TAG, "解析recipientId: $recipientId")

            // 情况1: 如果是RecipientId::X格式
            if (recipientId.contains("::")) {
                val idPart = recipientId.split("::").lastOrNull()
                if (idPart != null && idPart.all { it.isDigit() }) {
                    // 从数字ID创建RecipientId
                    val numericId = idPart.toLong()
                    val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
                    return Recipient.resolved(recipientIdObj)
                }
            }

            // 情况2: 如果是纯数字ID
            if (recipientId.all { it.isDigit() }) {
                val numericId = recipientId.toLong()
                val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
                return Recipient.resolved(recipientIdObj)
            }

            // 情况3: 尝试作为ServiceId解析
            try {
                val serviceId = ServiceId.parseOrThrow(recipientId)
                val recipientIdObj = SignalDatabase.recipients.getByServiceId(serviceId).orElse(null)
                if (recipientIdObj != null) {
                    return Recipient.resolved(recipientIdObj)
                }
            } catch (e: Exception) {
                Log.d(TAG, "不是有效的ServiceId格式: $recipientId")
            }

            // 情况4: 尝试直接作为RecipientId字符串解析
            try {
                val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId)
                return Recipient.resolved(recipientIdObj)
            } catch (e: Exception) {
                Log.d(TAG, "无法解析为RecipientId: $recipientId")
            }

            Log.w(TAG, "无法解析recipientId: $recipientId")
            null

        } catch (e: Exception) {
            Log.e(TAG, "解析recipientId时发生异常: $recipientId", e)
            null
        }
    }

    /**
     * 清理通道相关资源
     */
    private fun cleanupChannelResources(recipientId: String) {
        try {
            // 1. 从子账户Pool中移除
            val removeResult = subAccountPoolManager.removeSubAccount(recipientId)
            if (removeResult.isError()) {
                Log.w(TAG, "移除子账户条目失败: ${removeResult.getErrorOrNull()?.message}")
            }

            // 2. 移除通道记录
            channelManager.deleteChannel(recipientId)

            Log.i(TAG, "通道资源清理完成: recipientId=$recipientId")
        } catch (e: Exception) {
            Log.e(TAG, "清理通道资源时发生异常", e)
        }
    }

    /**
     * 标准化RecipientId格式
     * 确保所有RecipientId都使用一致的格式进行存储和查询
     */
    private fun standardizeRecipientId(recipientId: String): String {
        Log.d(TAG, "标准化RecipientId: 输入=$recipientId")

        // 如果已经是RecipientId::X格式，直接返回
        if (recipientId.startsWith("RecipientId::")) {
            Log.d(TAG, "RecipientId已是标准格式: $recipientId")
            return recipientId
        }

        // 如果是纯数字，转换为RecipientId::X格式
        if (recipientId.all { it.isDigit() }) {
            val standardized = "RecipientId::$recipientId"
            Log.d(TAG, "RecipientId标准化: $recipientId -> $standardized")
            return standardized
        }

        // 其他情况，尝试提取数字部分
        val numericPart = recipientId.filter { it.isDigit() }
        if (numericPart.isNotEmpty()) {
            val standardized = "RecipientId::$numericPart"
            Log.d(TAG, "RecipientId提取数字标准化: $recipientId -> $standardized")
            return standardized
        }

        // 无法标准化，返回原值
        Log.w(TAG, "无法标准化RecipientId，返回原值: $recipientId")
        return recipientId
    }
}


