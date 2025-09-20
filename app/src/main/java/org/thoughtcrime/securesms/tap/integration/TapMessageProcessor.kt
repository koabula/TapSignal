package org.thoughtcrime.securesms.tap.integration

import android.content.Context
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.push.ServiceId
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.notifications.v2.ConversationId
import org.thoughtcrime.securesms.tap.TransportMessage
import org.thoughtcrime.securesms.tap.TransportMessageType
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tap消息处理器
 * 
 * 作为TransportMessage与Signal消息处理系统之间的适配器，负责：
 * 1. 将轮询获得的TransportMessage转换为Signal可以处理的格式
 * 2. 通过Signal的消息入库管道进行处理
 * 3. 确保消息去重、幂等性和错误处理
 */
class TapMessageProcessor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapMessageProcessor::class.java)
        
        @JvmStatic
        fun getInstance(context: Context): TapMessageProcessor {
            return TapMessageProcessor(context)
        }
    }
    
    private val jobManager: JobManager = AppDependencies.jobManager
    
    // 消息去重缓存 - 基于messageId + recipientId
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val maxCacheSize = 10000
    private val cacheExpireMs = 24 * 60 * 60 * 1000L // 24小时
    
    /**
     * 处理来自轮询的传输消息
     * 
     * @param transportMessage 来自传输层的消息
     * @param recipientId 发送者ID
     * @return 是否成功处理
     */
    suspend fun processIncomingMessage(transportMessage: TransportMessage, recipientId: String): Boolean {
        return try {
            Log.d(TAG, "开始处理传输消息: messageId=${transportMessage.messageId}, from=$recipientId")
            
            // 1. 验证消息基本信息
            if (!validateTransportMessage(transportMessage, recipientId)) {
                Log.w(TAG, "传输消息验证失败: messageId=${transportMessage.messageId}")
                return false
            }
            
            // 2. 检查消息去重
            if (isDuplicateMessage(transportMessage.messageId, recipientId)) {
                Log.d(TAG, "跳过重复消息: messageId=${transportMessage.messageId}")
                return true // 重复消息视为处理成功
            }
            
            // 3. 转换为Signal消息格式并入库
            val success = processMessageDirectly(transportMessage, recipientId)
            if (!success) {
                Log.w(TAG, "消息处理失败: messageId=${transportMessage.messageId}")
                return false
            }
            
            // 4. 标记消息已处理（防重放）
            markMessageAsProcessed(transportMessage.messageId, recipientId)
            
            Log.d(TAG, "传输消息处理成功: messageId=${transportMessage.messageId}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "处理传输消息失败: messageId=${transportMessage.messageId} - ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 验证传输消息
     */
    private fun validateTransportMessage(message: TransportMessage, recipientId: String): Boolean {
        // 基本字段验证
        if (message.messageId.isBlank()) {
            Log.w(TAG, "消息ID为空")
            return false
        }
        
        if (message.encryptedContent.isEmpty()) {
            Log.w(TAG, "消息内容为空")
            return false
        }
        
        if (recipientId.isBlank()) {
            Log.w(TAG, "发送者ID为空")
            return false
        }
        
        // 时间戳验证（不能是未来时间）
        val currentTime = System.currentTimeMillis()
        if (message.timestamp > currentTime + 60000) { // 允许1分钟的时间偏差
            Log.w(TAG, "消息时间戳异常: ${message.timestamp}, 当前时间: $currentTime")
            return false
        }
        
        return true
    }
    
    /**
     * 直接处理消息并入库
     * 
     * 将TaP消息作为特殊类型的消息直接存储到Signal数据库中，
     * 标记为来自传输层的消息，后续可以通过Signal的解密管道处理
     */
    private suspend fun processMessageDirectly(transportMessage: TransportMessage, recipientId: String): Boolean {
        return try {
            // 1. 获取或创建发送者Recipient
            val senderRecipient = getOrCreateRecipient(recipientId)
            if (senderRecipient == null) {
                Log.w(TAG, "无法获取发送者信息: $recipientId")
                return false
            }
            
            // 2. 构造IncomingMessage
            val incomingMessage = IncomingMessage(
                type = MessageType.NORMAL,
                from = senderRecipient.id,
                body = buildMessageBody(transportMessage), // 消息内容（包含加密数据）
                sentTimeMillis = transportMessage.timestamp,
                serverTimeMillis = System.currentTimeMillis(), // 服务器时间戳
                receivedTimeMillis = System.currentTimeMillis() // 接收时间戳
            )
            
            // 3. 插入到数据库
            val insertResult = SignalDatabase.messages.insertMessageInbox(incomingMessage, SignalDatabase.threads.getOrCreateThreadIdFor(senderRecipient))
            
            if (insertResult.isPresent) {
                Log.d(TAG, "TaP消息入库成功: messageId=${insertResult.get().messageId}")
                
                // 4. 触发通知
                AppDependencies.messageNotifier.updateNotification(context, ConversationId.forConversation(insertResult.get().threadId))
                
                return true
            } else {
                Log.w(TAG, "TaP消息入库失败")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "直接处理消息失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }
    
    /**
     * 获取或创建Recipient
     */
    private fun getOrCreateRecipient(recipientId: String): Recipient? {
        return try {
            // 尝试解析为ServiceId
            val serviceId = ServiceId.parseOrNull(recipientId)
            if (serviceId != null) {
                Recipient.resolved(RecipientId.from(serviceId))
            } else {
                // 尝试作为E164号码处理
                Recipient.external(recipientId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建Recipient失败: $recipientId - ${LogSanitizer.sanitizeThrowable(e)}")
            null
        }
    }
    
    /**
     * 构建消息内容
     * 
     * 将加密的传输消息内容包装成可识别的格式，
     * 包含必要的元数据用于后续解密处理
     */
    private fun buildMessageBody(transportMessage: TransportMessage): String {
        return try {
            // 构造一个包含TaP消息信息的JSON格式字符串
            // 这样可以保持与Signal现有消息格式的兼容性
            val messageInfo = mapOf(
                "tap_version" to "1.0",
                "message_id" to transportMessage.messageId,
                "message_type" to transportMessage.messageType.name,
                "timestamp" to transportMessage.timestamp,
                "encrypted_content" to android.util.Base64.encodeToString(
                    transportMessage.encryptedContent, 
                    android.util.Base64.NO_WRAP
                ),
                "attachments_count" to transportMessage.attachments.size
            )
            
            // 简单的JSON序列化
            val json = StringBuilder("{")
            messageInfo.entries.forEachIndexed { index, entry ->
                if (index > 0) json.append(",")
                json.append("\"${entry.key}\":\"${entry.value}\"")
            }
            json.append("}")
            
            json.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "构建消息内容失败: ${LogSanitizer.sanitizeThrowable(e)}")
            "[TaP消息] ID: ${transportMessage.messageId}" // 降级方案
        }
    }
    
    /**
     * 处理消息去重（参考coscomm机制）
     * 
     * 基于messageId + recipientId组合键进行去重，
     * 结合时间戳防止重放攻击
     */
    fun isDuplicateMessage(messageId: String, recipientId: String): Boolean {
        return try {
            val key = "${recipientId}:${messageId}"
            val currentTime = System.currentTimeMillis()
            
            // 检查是否已处理过
            val processedTime = processedMessages[key]
            if (processedTime != null) {
                // 检查是否在缓存有效期内
                if (currentTime - processedTime < cacheExpireMs) {
                    Log.d(TAG, "发现重复消息: messageId=$messageId, recipientId=$recipientId")
                    return true
                } else {
                    // 过期则清理
                    processedMessages.remove(key)
                }
            }
            
            // 定期清理过期缓存
            cleanupExpiredCache()
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查重复消息失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false // 出错时不阻止处理
        }
    }
    
    /**
     * 标记消息已处理
     */
    private fun markMessageAsProcessed(messageId: String, recipientId: String) {
        val key = "${recipientId}:${messageId}"
        val currentTime = System.currentTimeMillis()
        
        // 如果缓存满了，清理一些旧条目
        if (processedMessages.size >= maxCacheSize) {
            cleanupExpiredCache()
            // 如果清理后仍然满，强制清理最旧的一半
            if (processedMessages.size >= maxCacheSize) {
                val toRemove = processedMessages.entries
                    .sortedBy { it.value }
                    .take(maxCacheSize / 2)
                    .map { it.key }
                toRemove.forEach { processedMessages.remove(it) }
            }
        }
        
        processedMessages[key] = currentTime
        Log.v(TAG, "标记消息已处理: messageId=$messageId")
    }
    
    /**
     * 清理过期的缓存条目
     */
    private fun cleanupExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val iterator = processedMessages.entries.iterator()
        var cleanedCount = 0
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > cacheExpireMs) {
                iterator.remove()
                cleanedCount++
            }
        }
        
        if (cleanedCount > 0) {
            Log.d(TAG, "清理过期缓存条目: $cleanedCount 个")
        }
    }
} 