package org.thoughtcrime.securesms.tap

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import kotlinx.coroutines.*

/**
 * 传输消息路由器
 * 
 * 负责消息发送的路由逻辑，从TransportManager中分离出来
 * 专注于消息路由、Provider选择和发送协调
 */
class TransportMessageRouter private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TransportMessageRouter::class.java)
        
        @Volatile
        private var INSTANCE: TransportMessageRouter? = null
        
        fun getInstance(context: Context): TransportMessageRouter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransportMessageRouter(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
        
        internal fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
    
    // 依赖的管理器
    private val providerManager = TransportProviderManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val routingManager = TransportRoutingManager.getInstance(context)
    
    /**
     * 发送消息
     * 
     * @param message 要发送的消息
     * @param recipientId 接收方ID
     * @return 发送结果
     */
    suspend fun sendMessage(message: TransportMessage, recipientId: String): TransportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始路由消息发送: messageId=${message.messageId}, recipientId=${LogSanitizer.sanitize(recipientId)}")
                
                // 1. 获取可用的通道
                val availableChannels = channelManager.getActiveChannels(recipientId)
                if (availableChannels.isEmpty()) {
                    Log.w(TAG, "没有可用的传输通道: recipientId=${LogSanitizer.sanitize(recipientId)}")
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "没有可用的传输通道"
                    )
                }
                
                // 2. 选择最优Provider和通道
                val selectedChannel = routingManager.selectBestChannel(availableChannels, message)
                if (selectedChannel == null) {
                    Log.w(TAG, "无法选择合适的传输通道: recipientId=${LogSanitizer.sanitize(recipientId)}")
                    return@withContext TransportResult.failure(
                        TransportError.ROUTING_ERROR,
                        true,
                        "无法选择合适的传输通道"
                    )
                }
                
                // 3. 获取Provider实例
                val provider = providerManager.getProvider(selectedChannel.providerType)
                if (provider == null) {
                    Log.w(TAG, "Provider实例不存在: ${selectedChannel.providerType}")
                    
                    // 详细诊断Provider状态
                    val diagnostics = StringBuilder()
                    diagnostics.append("Provider诊断信息:\n")
                    diagnostics.append("- 请求的Provider类型: ${selectedChannel.providerType}\n")
                    
                    try {
                        val availableProviders = providerManager.getActiveProviders()
                        diagnostics.append("- 可用Provider数量: ${availableProviders.size}\n")
                        availableProviders.forEach { p ->
                            diagnostics.append("  * ${p.providerType} (${p.displayName})\n")
                        }
                        
                        val transportManager = TransportManager.getInstance(context)
                        val allProviders = transportManager.getAvailableProviders()
                        diagnostics.append("- TransportManager中Provider数量: ${allProviders.size}\n")
                        allProviders.forEach { provider ->
                            diagnostics.append("  * ${provider.providerType} (${provider.displayName})\n")
                        }
                        
                    } catch (diagEx: Exception) {
                        diagnostics.append("- 诊断过程异常: ${diagEx.message}\n")
                    }
                    
                    Log.w(TAG, diagnostics.toString())
                    
                    return@withContext TransportResult.failure(
                        TransportError.PROVIDER_UNAVAILABLE,
                        true,
                        "Provider实例不存在: ${selectedChannel.providerType}"
                    )
                }
                
                Log.d(TAG, "选中Provider: ${selectedChannel.providerType}, channelId=${selectedChannel.channelId}")
                
                // 4. 执行发送
                val sendResult = executeSend(provider, message, selectedChannel)
                
                // 5. 更新路由统计
                routingManager.recordSendResult(selectedChannel.providerType, sendResult)
                
                sendResult
                
            } catch (e: Exception) {
                Log.e(TAG, "消息路由发送异常: ${LogSanitizer.sanitizeThrowable(e)}")
                TransportResult.fromException(e, true)
            }
        }
    }
    
    /**
     * 批量发送消息
     * 
     * @param messages 消息列表
     * @param recipientId 接收方ID
     * @return 发送结果列表
     */
    suspend fun sendMessages(messages: List<TransportMessage>, recipientId: String): List<TransportResult> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始批量消息发送: count=${messages.size}, recipientId=${LogSanitizer.sanitize(recipientId)}")
                
                // 并发发送所有消息
                val sendJobs = messages.map { message ->
                    async { sendMessage(message, recipientId) }
                }
                
                sendJobs.awaitAll()
                
            } catch (e: Exception) {
                Log.e(TAG, "批量消息发送异常: ${LogSanitizer.sanitizeThrowable(e)}")
                // 返回相应数量的失败结果
                messages.map { 
                    TransportResult.fromException(e, true)
                }
            }
        }
    }
    
    /**
     * 检查消息发送能力
     * 
     * @param recipientId 接收方ID
     * @return 是否能够发送消息
     */
    suspend fun canSendMessage(recipientId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val availableChannels = channelManager.getActiveChannels(recipientId)
                val activeProviders = providerManager.getActiveProviders()
                
                availableChannels.any { channel ->
                    activeProviders.any { provider ->
                        provider.providerType == channel.providerType
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查消息发送能力异常: ${LogSanitizer.sanitizeThrowable(e)}")
                false
            }
        }
    }
    
    /**
     * 获取推荐的Provider列表
     * 
     * @param recipientId 接收方ID
     * @return Provider类型列表，按优先级排序
     */
    suspend fun getRecommendedProviders(recipientId: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val availableChannels = channelManager.getActiveChannels(recipientId)
                val providerScores = routingManager.evaluateProviders(availableChannels)
                
                providerScores
                    .sortedByDescending { it.second }
                    .map { it.first }
            } catch (e: Exception) {
                Log.e(TAG, "获取推荐Provider异常: ${LogSanitizer.sanitizeThrowable(e)}")
                emptyList()
            }
        }
    }
    
    /**
     * 执行实际的消息发送
     */
    private suspend fun executeSend(
        provider: TransportProvider, 
        message: TransportMessage, 
        channel: TransportChannel
    ): TransportResult {
        return try {
            // 检查Provider状态
            if (!providerManager.checkProviderHealth(provider.providerType)) {
                Log.w(TAG, "Provider健康检查失败: ${provider.providerType}")
                return TransportResult.failure(
                    TransportError.PROVIDER_UNAVAILABLE,
                    true,
                    "Provider健康检查失败"
                )
            }
            
            // 检查消息大小限制
            if (provider.isMessageTooLarge(message)) {
                Log.w(TAG, "消息过大: messageId=${message.messageId}, provider=${provider.providerType}")
                return TransportResult.failure(
                    TransportError.MESSAGE_TOO_LARGE,
                    false,
                    "消息大小超过Provider限制: ${provider.maxMessageSize}字节"
                )
            }
            
            // 执行发送
            val startTime = System.currentTimeMillis()
            val result = provider.push(message, channel.metadata)
            val duration = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "消息发送完成: messageId=${message.messageId}, " +
                      "provider=${provider.providerType}, duration=${duration}ms, " +
                      "success=${result.isSuccess()}")
            
            // 更新通道使用统计
            if (result.isSuccess()) {
                channelManager.recordSuccessfulSend(channel.channelId)
                // 更新Provider健康状态
                providerManager.updateProviderHealth(provider.providerType, true)
            } else {
                channelManager.recordFailedSend(channel.channelId, result.getResultError() ?: TransportError.UNKNOWN_ERROR)
                // 更新Provider健康状态
                providerManager.updateProviderHealth(provider.providerType, false)
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "执行消息发送异常: provider=${provider.providerType} - ${LogSanitizer.sanitizeThrowable(e)}")
            
            // 记录通道发送失败
            channelManager.recordFailedSend(channel.channelId, TransportError.UNKNOWN_ERROR)
            
            TransportResult.fromException(e, isRetryableException(e))
        }
    }
    
    /**
     * 判断异常是否可重试
     */
    private fun isRetryableException(e: Throwable): Boolean {
        return when (e) {
            is SecurityException,
            is IllegalArgumentException,
            is IllegalStateException -> false
            is java.net.UnknownHostException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.io.IOException -> true
            is InterruptedException,
            is CancellationException -> false
            else -> {
                val message = e.message?.lowercase() ?: ""
                when {
                    message.contains("permission") || 
                    message.contains("unauthorized") ||
                    message.contains("forbidden") -> false
                    message.contains("timeout") ||
                    message.contains("connection") ||
                    message.contains("network") -> true
                    else -> false
                }
            }
        }
    }
} 