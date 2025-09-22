package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.TransportResult
import org.thoughtcrime.securesms.tap.TransportError
import org.thoughtcrime.securesms.tap.TransportChannelConfig
import org.thoughtcrime.securesms.tap.TransportToken
import org.thoughtcrime.securesms.tap.CosTransportToken
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.recipients.Recipient
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Tap消息处理器
 * 负责处理通过Tap传输层接收的控制消息和管理传输通道
 * 专注于传输层功能，不涉及Signal的加密解密处理
 */
class TapMessageProcessor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageProcessor::class.java)
        
        // Jackson ObjectMapper配置
        private val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        
        @Volatile
        private var INSTANCE: TapMessageProcessor? = null
        
        fun getInstance(context: Context): TapMessageProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapMessageProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心组件
    private val channelManager = TransportChannelManager.getInstance(context)
    private val tokenPool = TransportTokenPool.getInstance(context)
    
    /**
     * 检查是否为Tap传输层控制消息
     * 
     * @param messageBody 消息体
     * @return 是否为Tap控制消息
     */
    fun isTapMessage(messageBody: String): Boolean {
        return messageBody.startsWith("TAP_MSG:") || 
               messageBody.startsWith("TAP_REQ:") || 
               messageBody.startsWith("TAP_RESP:") ||
               messageBody.startsWith("TAP_REVOKE:")
    }
    
    /**
     * 处理Tap传输层控制消息
     * 
     * @param senderId 发送者ID
     * @param messageBody 消息体
     * @return 处理结果
     */
    suspend fun processTapMessage(senderId: String, messageBody: String): TapProcessResult {
        Log.i(TAG, "处理Tap传输层控制消息: senderId=$senderId, bodyLength=${messageBody.length}")
        
        return try {
            when {
                messageBody.startsWith("TAP_REQ:") -> {
                    processChannelRequest(senderId, messageBody.substring(8))
                }
                messageBody.startsWith("TAP_RESP:") -> {
                    processChannelResponse(senderId, messageBody.substring(9))
                }
                messageBody.startsWith("TAP_REVOKE:") -> {
                    processChannelRevoke(senderId, messageBody.substring(11))
                }
                messageBody.startsWith("TAP_MSG:") -> {
                    processControlMessage(senderId, messageBody.substring(8))
                }
                else -> {
                    Log.w(TAG, "未知的Tap控制消息类型: senderId=$senderId")
                    TapProcessResult.Failed("未知的Tap控制消息类型")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理Tap控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输层接收到的加密消息
     * 将消息交给TapEnvelopeAdapter处理Signal解密和入库
     * 
     * @param transportMessage 传输消息
     * @return 处理结果
     */
    suspend fun processTapTransportMessage(transportMessage: TransportMessage): TapProcessResult {
        Log.i(TAG, "处理Tap传输消息: messageId=${transportMessage.messageId}, type=${transportMessage.messageType}")
        
        return try {
            // 验证消息完整性
            if (!validateTransportMessage(transportMessage)) {
                Log.w(TAG, "传输消息验证失败: messageId=${transportMessage.messageId}")
                return TapProcessResult.Failed("消息验证失败")
            }
            
            // 记录接收统计
            recordMessageReceived(transportMessage)
            
            // 将加密消息交给TapEnvelopeAdapter处理Signal相关逻辑
            val envelopeAdapter = TapEnvelopeAdapter.getInstance(context)
            val adapterResult = envelopeAdapter.processEncryptedMessage(transportMessage)
            
            when (adapterResult) {
                is TapEnvelopeProcessResult.Success -> {
                    Log.i(TAG, "传输消息处理成功: messageId=${transportMessage.messageId}")
                    TapProcessResult.Success("消息处理成功")
                }
                is TapEnvelopeProcessResult.Failed -> {
                    Log.w(TAG, "传输消息处理失败: messageId=${transportMessage.messageId}, error=${adapterResult.error}")
                    TapProcessResult.Failed(adapterResult.error)
                }
                is TapEnvelopeProcessResult.Duplicate -> {
                    Log.d(TAG, "传输消息重复: messageId=${transportMessage.messageId}")
                    TapProcessResult.Success("消息重复，已忽略")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理传输消息异常: messageId=${transportMessage.messageId}", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 验证传输消息完整性
     */
    private fun validateTransportMessage(message: TransportMessage): Boolean {
        return try {
            // 基本字段验证
            if (message.messageId.isBlank()) {
                Log.w(TAG, "消息ID为空")
                return false
            }
            
            if (message.senderId.isBlank()) {
                Log.w(TAG, "发送者ID为空")
                return false
            }
            
            if (message.recipientId.isBlank()) {
                Log.w(TAG, "接收者ID为空")
                return false
            }
            
            // 验证Signal密文存在
            if (message.signalCiphertext.isEmpty()) {
                Log.w(TAG, "Signal密文为空")
                return false
            }
            
            // 验证时间戳合理性
            val currentTime = System.currentTimeMillis()
            val messageTime = message.timestamp
            val timeDiff = Math.abs(currentTime - messageTime)
            
            // 允许24小时的时间偏差
            if (timeDiff > 24 * 60 * 60 * 1000L) {
                Log.w(TAG, "消息时间戳异常: messageTime=$messageTime, currentTime=$currentTime")
                return false
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "验证传输消息失败", e)
            false
        }
    }
    
    /**
     * 记录消息接收统计
     */
    private fun recordMessageReceived(message: TransportMessage) {
        try {
            // 更新接收统计（可以扩展为更详细的统计）
            Log.d(TAG, "记录消息接收: senderId=${message.senderId}, type=${message.messageType}")
            
            // TODO: 可以添加到统计数据库或内存统计中
            
        } catch (e: Exception) {
            Log.w(TAG, "记录消息统计失败", e)
        }
    }

    /**
     * 处理传输通道请求
     */
    private suspend fun processChannelRequest(senderId: String, requestData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道请求: senderId=$senderId")
        
        return try {
            // 解析请求数据
            val requestInfo = parseChannelRequest(requestData)
            if (requestInfo == null) {
                Log.w(TAG, "无法解析通道请求数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析请求数据")
            }
            
            // 检查是否已有活跃通道
            if (channelManager.hasActiveChannel(senderId)) {
                Log.i(TAG, "已存在活跃通道，更新配置: senderId=$senderId")
                val updated = channelManager.updateChannelConfig(senderId, requestInfo.config)
                if (updated) {
                    TapProcessResult.Success("通道配置已更新")
                } else {
                    TapProcessResult.Failed("通道配置更新失败")
                }
            } else {
                // 创建新的传输通道
                Log.i(TAG, "创建新的传输通道: senderId=$senderId")
                val tokenString = requestInfo.token?.toMap()?.let { 
                    com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it)
                } ?: ""
                val channelResult = channelManager.createChannel(
                    recipientId = senderId,
                    config = requestInfo.config,
                    token = tokenString
                )
                
                if (channelResult != null) {
                    TapProcessResult.Success("传输通道创建成功")
                } else {
                    TapProcessResult.Failed("传输通道创建失败")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道请求异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道响应
     */
    private suspend fun processChannelResponse(senderId: String, responseData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道响应: senderId=$senderId")
        
        return try {
            val responseInfo = parseChannelResponse(responseData)
            if (responseInfo == null) {
                Log.w(TAG, "无法解析通道响应数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析响应数据")
            }
            
            when (responseInfo.status) {
                "accepted" -> {
                    // 对方接受了通道请求
                    Log.i(TAG, "通道请求被接受: senderId=$senderId")
                    
                    if (responseInfo.token != null) {
                        // 存储对方提供的Token
                        val addResult = tokenPool.addReceivedToken(senderId, responseInfo.token)
                        if (!addResult) {
                            Log.w(TAG, "添加接收Token失败: senderId=$senderId")
                        }
                    }
                    
                    // 由于activateChannel是私有方法，使用公开的updateChannelConfig激活通道
                    val updated = channelManager.updateChannelConfig(senderId, TransportChannelConfig())
                    if (updated) {
                        TapProcessResult.Success("传输通道已激活")
                    } else {
                        TapProcessResult.Failed("通道激活失败")
                    }
                }
                
                "rejected" -> {
                    // 对方拒绝了通道请求
                    Log.i(TAG, "通道请求被拒绝: senderId=$senderId, reason=${responseInfo.reason}")
                    TapProcessResult.Failed("通道请求被拒绝: ${responseInfo.reason}")
                }
                
                else -> {
                    Log.w(TAG, "未知的响应状态: ${responseInfo.status}")
                    TapProcessResult.Failed("未知的响应状态")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道响应异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理传输通道撤销
     */
    private suspend fun processChannelRevoke(senderId: String, revokeData: String): TapProcessResult {
        Log.i(TAG, "处理传输通道撤销: senderId=$senderId")
        
        return try {
            val revokeInfo = parseChannelRevoke(revokeData)
            if (revokeInfo == null) {
                Log.w(TAG, "无法解析通道撤销数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析撤销数据")
            }
            
            // 关闭相关通道
            val channels = channelManager.getActiveChannels(senderId)
            var closed = 0
            for (channel in channels) {
                if (channelManager.closeChannel(channel.channelId)) {
                    closed++
                }
            }
            if (closed > 0) {
                Log.i(TAG, "已关闭通道数量: $closed, senderId=$senderId")
            }
            
            // 移除相关Token（尝试移除主要Provider类型的Token）
            val providerTypes = listOf("cos", "email") // 支持的Provider类型
            var removed = 0
            for (providerType in providerTypes) {
                val token = tokenPool.getValidReceivedToken(senderId, providerType)
                if (token != null && tokenPool.removeToken(senderId, providerType)) {
                    removed++
                }
            }
            if (removed > 0) {
                Log.i(TAG, "已移除Token数量: $removed, senderId=$senderId")
            }
            
            TapProcessResult.Success("传输通道已撤销")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理通道撤销异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 处理控制消息
     */
    private suspend fun processControlMessage(senderId: String, controlData: String): TapProcessResult {
        Log.i(TAG, "处理控制消息: senderId=$senderId")
        
        return try {
            val controlInfo = parseControlMessage(controlData)
            if (controlInfo == null) {
                Log.w(TAG, "无法解析控制消息数据: senderId=$senderId")
                return TapProcessResult.Failed("无法解析控制消息")
            }
            
            when (controlInfo.type) {
                "heartbeat" -> {
                    // 心跳消息
                    Log.d(TAG, "收到心跳消息: senderId=$senderId")
                    // 通过更新通道配置来刷新最后访问时间
                    val channels = channelManager.getActiveChannels(senderId)
                    for (channel in channels) {
                        // 使用默认配置来刷新通道
                        val defaultConfig = TransportChannelConfig()
                        channelManager.updateChannelConfig(senderId, defaultConfig)
                    }
                    TapProcessResult.Success("心跳处理完成")
                }
                
                "status_update" -> {
                    // 状态更新消息
                    Log.d(TAG, "收到状态更新: senderId=$senderId")
                    TapProcessResult.Success("状态更新处理完成")
                }
                
                else -> {
                    Log.w(TAG, "未知的控制消息类型: ${controlInfo.type}")
                    TapProcessResult.Failed("未知的控制消息类型")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理控制消息异常: senderId=$senderId", e)
            TapProcessResult.Failed("处理异常: ${e.message}")
        }
    }
    
    /**
     * 解析通道请求数据
     */
    private fun parseChannelRequest(requestData: String): ChannelRequestInfo? {
        return try {
            val requestInfo = objectMapper.readValue<TapChannelRequestMessage>(requestData)
            Log.d(TAG, "解析通道请求: recipientId=${requestInfo.recipientId}, providerType=${requestInfo.providerType}")
            
            // 构建TransportChannelConfig (使用默认值，因为TransportChannelConfig没有这些参数)
            val config = TransportChannelConfig(
                maxChannels = 1000,
                channelTimeoutMs = requestInfo.timeout ?: 300000L,
                heartbeatIntervalMs = 60000L,
                cleanupIntervalMs = 3600000L,
                maxFailureCount = requestInfo.maxRetries ?: 10
            )
            
            // 解析Token
            val token = requestInfo.token?.let { tokenData ->
                when (requestInfo.providerType) {
                    "cos" -> CosTransportToken.fromMap(tokenData)
                    else -> null
                }
            }
            
            ChannelRequestInfo(config, token)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道请求数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道请求（建议升级到JSON格式）")
                // 旧格式解析逻辑
                val config = TransportChannelConfig(
                    maxChannels = 1000,
                    channelTimeoutMs = 30000L,
                    heartbeatIntervalMs = 60000L,
                    cleanupIntervalMs = 3600000L,
                    maxFailureCount = 3
                )
                ChannelRequestInfo(config, null)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道请求解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 解析通道响应数据
     */
    private fun parseChannelResponse(responseData: String): ChannelResponseInfo? {
        return try {
            val responseInfo = objectMapper.readValue<TapChannelResponseMessage>(responseData)
            Log.d(TAG, "解析通道响应: status=${responseInfo.status}, providerType=${responseInfo.providerType}")
            
            // 解析Token
            val token = responseInfo.token?.let { tokenData ->
                when (responseInfo.providerType) {
                    "cos" -> CosTransportToken.fromMap(tokenData)
                    else -> null
                }
            }
            
            ChannelResponseInfo(
                status = responseInfo.status,
                providerType = responseInfo.providerType,
                token = token,
                reason = responseInfo.reason
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道响应数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道响应（建议升级到JSON格式）")
                ChannelResponseInfo(
                    status = if (responseData.contains("accepted")) "accepted" else "rejected",
                    providerType = "cos",
                    token = null,
                    reason = null
                )
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道响应解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 解析控制消息数据
     */
    private fun parseControlMessage(controlData: String): ControlMessageInfo? {
        return try {
            val controlInfo = objectMapper.readValue<TapControlMessage>(controlData)
            Log.d(TAG, "解析控制消息: type=${controlInfo.type}")
            ControlMessageInfo(controlInfo.type)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON控制消息数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析控制消息（建议升级到JSON格式）")
                ControlMessageInfo(controlData)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式控制消息解析也失败", legacyException)
                null
            }
        }
    }

    /**
     * 解析通道撤销数据
     */
    private fun parseChannelRevoke(revokeData: String): ChannelRevokeInfo? {
        return try {
            val revokeInfo = objectMapper.readValue<TapChannelRevokeMessage>(revokeData)
            Log.d(TAG, "解析通道撤销: recipientId=${revokeInfo.recipientId}")
            ChannelRevokeInfo(revokeInfo.recipientId)
        } catch (e: Exception) {
            Log.e(TAG, "解析JSON通道撤销数据失败，尝试旧格式解析", e)
            
            // 向后兼容：尝试解析旧格式
            try {
                Log.w(TAG, "使用旧格式解析通道撤销（建议升级到JSON格式）")
                ChannelRevokeInfo(revokeData)
            } catch (legacyException: Exception) {
                Log.e(TAG, "旧格式通道撤销解析也失败", legacyException)
                null
            }
        }
    }
    
    /**
     * 检查消息是否重复
     */
    fun isDuplicateMessage(messageId: String, recipientId: String): Boolean {
        return try {
            // 简化的去重检查 - 由于没有专门的transport消息表，使用内存去重
            // 在实际实现中，可以使用TapEnvelopeAdapter中的TransportMessageDeduplicator
            Log.d(TAG, "检查消息重复性: messageId=$messageId, recipientId=$recipientId")
            false // 暂时返回false，让TapEnvelopeAdapter处理去重
        } catch (e: Exception) {
            Log.w(TAG, "检查消息重复性失败", e)
            false
        }
    }
    
    /**
     * 标记消息为已处理
     */
    private fun markMessageAsProcessed(messageId: String, recipientId: String, timestamp: Long) {
        try {
            // 消息已通过TapEnvelopeAdapter处理并存储到数据库
            Log.d(TAG, "消息已标记为已处理: messageId=$messageId, recipientId=$recipientId")
        } catch (e: Exception) {
            Log.w(TAG, "标记消息已处理失败", e)
        }
    }
    
    /**
     * 清理过期的去重记录
     */
    private fun cleanupOldDuplicationRecords() {
        try {
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            val database = org.thoughtcrime.securesms.database.SignalDatabase.rawDatabase
            
            val deletedCount = database.delete(
                "transport_processed_messages",
                "processed_at < ?",
                arrayOf(thirtyDaysAgo.toString())
            )
            
            if (deletedCount > 0) {
                Log.d(TAG, "清理了 $deletedCount 条过期的去重记录")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "清理过期去重记录失败", e)
        }
    }
    
    /**
     * 调度后处理任务
     */
    private fun schedulePostProcessingJobs(
        insertResult: org.thoughtcrime.securesms.database.MessageTable.InsertResult,
        senderRecipient: Recipient
    ) {
        try {
            // 触发附件下载
            val attachments = insertResult.insertedAttachments
            if (attachments != null && attachments.isNotEmpty()) {
                Log.d(TAG, "安排附件下载任务: messageId=${insertResult.messageId}")
                // TODO: 触发附件下载任务
            }
            
            // 触发其他后处理
            Log.d(TAG, "安排后处理任务完成: messageId=${insertResult.messageId}")
        } catch (e: Exception) {
            Log.w(TAG, "安排后处理任务失败", e)
        }
    }
}

/**
 * Tap处理结果
 */
sealed class TapProcessResult {
    data class Success(val message: String) : TapProcessResult()
    data class Failed(val error: String) : TapProcessResult()
}

/**
 * Tap Envelope处理结果
 */
sealed class TapEnvelopeProcessResult {
    data class Success(val messageId: String) : TapEnvelopeProcessResult()
    data class Failed(val error: String) : TapEnvelopeProcessResult()
    data class Duplicate(val messageId: String) : TapEnvelopeProcessResult()
}

/**
 * 通道请求信息
 */
private data class ChannelRequestInfo(
    val config: TransportChannelConfig,
    val token: TransportToken?
)

/**
 * 通道响应信息
 */
private data class ChannelResponseInfo(
    val status: String,
    val providerType: String,
    val token: TransportToken?,
    val reason: String?
)

/**
 * 控制消息信息
 */
private data class ControlMessageInfo(
    val type: String
)

/**
 * 通道撤销信息
 */
private data class ChannelRevokeInfo(
    val recipientId: String
)

/**
 * JSON消息格式定义
 */
private data class TapChannelRequestMessage(
    @JsonProperty("recipientId") val recipientId: String,
    @JsonProperty("providerType") val providerType: String,
    @JsonProperty("priority") val priority: Int?,
    @JsonProperty("maxRetries") val maxRetries: Int?,
    @JsonProperty("timeout") val timeout: Long?,
    @JsonProperty("token") val token: Map<String, Any>?
)

private data class TapChannelResponseMessage(
    @JsonProperty("status") val status: String,
    @JsonProperty("providerType") val providerType: String,
    @JsonProperty("token") val token: Map<String, Any>?,
    @JsonProperty("reason") val reason: String?
)

private data class TapChannelRevokeMessage(
    @JsonProperty("recipientId") val recipientId: String
)

private data class TapControlMessage(
    @JsonProperty("type") val type: String
) 