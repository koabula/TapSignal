package org.thoughtcrime.securesms.coscomm.processor

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.CosRequestManager
import org.thoughtcrime.securesms.coscomm.manager.CosDisconnectionManager
import org.thoughtcrime.securesms.coscomm.utils.CosMessageSerializer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * COS Signal消息处理器
 * 负责处理通过Signal Server接收到的COS相关消息（请求、响应、撤销）
 */
class CosSignalMessageProcessor private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosSignalMessageProcessor::class.java)
        
        @Volatile
        private var INSTANCE: CosSignalMessageProcessor? = null
        
        fun getInstance(context: Context): CosSignalMessageProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosSignalMessageProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // COS消息的特殊标识符
        const val COS_MESSAGE_PREFIX = "COS_MSG:"
    }
    
    private val cosRequestManager = CosRequestManager.getInstance(context)
    private val notificationManager = org.thoughtcrime.securesms.coscomm.ui.CosRequestNotificationManager.getInstance(context)
    
    /**
     * 检查消息是否为COS消息
     * @param messageBody 消息内容
     * @return 是否为COS消息
     */
    fun isCosMessage(messageBody: String?): Boolean {
        return messageBody?.startsWith(COS_MESSAGE_PREFIX) == true
    }
    
    /**
     * 处理接收到的COS消息
     * @param senderId 发送方ID
     * @param messageBody 消息内容
     * @return 处理结果
     */
    fun processCosMessage(
        senderId: String,
        messageBody: String
    ): CosMessageProcessResult {
        Log.i(TAG, "处理COS消息: sender=$senderId")

        try {
            // 1. 提取COS消息内容
            val cosMessageJson = messageBody.removePrefix(COS_MESSAGE_PREFIX)

            // 2. 解析COS消息类型
            val messageType = detectCosMessageType(cosMessageJson)
            if (messageType == null) {
                Log.e(TAG, "无法识别COS消息类型")
                return CosMessageProcessResult.failure("unknown", "无法识别COS消息类型")
            }

            // 3. 根据消息类型进行处理
            return when (messageType) {
                CosMessageType.COS_REQUEST -> {
                    processRequestMessage(senderId, cosMessageJson)
                }
                CosMessageType.COS_RESPONSE -> {
                    processResponseMessage(senderId, cosMessageJson)
                }
                CosMessageType.COS_REVOCATION -> {
                    processRevocationMessage(senderId, cosMessageJson)
                }
                CosMessageType.COS_DISCONNECTION -> {
                    processDisconnectionMessage(senderId, cosMessageJson)
                }
                else -> {
                    Log.e(TAG, "不支持的COS消息类型: $messageType")
                    CosMessageProcessResult.failure("unknown", "不支持的COS消息类型")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理COS消息时发生异常", e)
            return CosMessageProcessResult.failure("unknown", "处理消息时发生异常: ${e.message}")
        }
    }
    
    /**
     * 创建COS消息的显示信息
     * @param cosMessage COS消息
     * @return 显示信息
     */
    fun createDisplayInfo(cosMessage: CosSignalMessage): CosMessageDisplayInfo {
        return when (cosMessage) {
            is CosSignalMessage.Request -> {
                CosMessageDisplayInfo.forRequest(cosMessage.cosRequest)
            }
            is CosSignalMessage.Response -> {
                CosMessageDisplayInfo.forResponse(cosMessage.cosResponse)
            }
            is CosSignalMessage.Revocation -> {
                CosMessageDisplayInfo.forRevocation(cosMessage.cosRevocation)
            }
            is CosSignalMessage.Disconnection -> {
                CosMessageDisplayInfo.forDisconnection(cosMessage.cosDisconnection)
            }
        }
    }
    
    /**
     * 检查是否应该在UI中显示COS消息
     * COS消息通常不作为普通消息显示，而是显示为特殊的请求/响应界面
     */
    fun shouldDisplayInChat(messageType: String): Boolean {
        return when (messageType) {
            CosMessageType.COS_REQUEST -> true  // 显示请求确认界面
            CosMessageType.COS_RESPONSE -> true // 显示响应结果
            CosMessageType.COS_REVOCATION -> true // 显示撤销通知
            else -> false
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 检测COS消息类型
     */
    private fun detectCosMessageType(messageJson: String): String? {
        return try {
            // 简单的JSON解析来检测消息类型
            when {
                messageJson.contains("\"cosRequest\"") -> CosMessageType.COS_REQUEST
                messageJson.contains("\"cosResponse\"") -> CosMessageType.COS_RESPONSE
                messageJson.contains("\"cosRevocation\"") -> CosMessageType.COS_REVOCATION
                messageJson.contains("\"cosDisconnection\"") -> CosMessageType.COS_DISCONNECTION
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测COS消息类型失败", e)
            null
        }
    }
    
    /**
     * 处理COS请求消息
     */
    private fun processRequestMessage(senderId: String, messageJson: String): CosMessageProcessResult {
        return try {
            Log.d(TAG, "解析COS请求消息: $messageJson")

            // 使用CosMessageSerializer反序列化Signal消息
            val deserializeResult = CosMessageSerializer.deserializeSignalMessage(messageJson)
            if (deserializeResult !is CosResult.Success) {
                Log.e(TAG, "反序列化COS Signal消息失败")
                return CosMessageProcessResult.failure("unknown", "反序列化消息失败")
            }

            val signalMessage = deserializeResult.data
            if (signalMessage !is CosSignalMessage.Request) {
                Log.e(TAG, "消息类型不匹配，期望Request但得到: ${signalMessage::class.simpleName}")
                return CosMessageProcessResult.failure("unknown", "消息类型不匹配")
            }

            Log.i(TAG, "成功解析COS请求: requestId=${signalMessage.cosRequest.requestId}")

            // 通过通知管理器处理请求，显示UI
            notificationManager.handleReceivedRequest(senderId, signalMessage)

            // 同时通过请求管理器处理后台逻辑
            cosRequestManager.handleReceivedRequest(senderId, signalMessage)
        } catch (e: Exception) {
            Log.e(TAG, "处理COS请求消息失败", e)
            CosMessageProcessResult.failure("unknown", "解析请求消息失败: ${e.message}")
        }
    }

    /**
     * 处理COS响应消息
     */
    private fun processResponseMessage(senderId: String, messageJson: String): CosMessageProcessResult {
        return try {
            Log.d(TAG, "解析COS响应消息: $messageJson")

            // 使用CosMessageSerializer反序列化Signal消息
            val deserializeResult = CosMessageSerializer.deserializeSignalMessage(messageJson)
            if (deserializeResult !is CosResult.Success) {
                Log.e(TAG, "反序列化COS Signal响应消息失败")
                return CosMessageProcessResult.failure("unknown", "反序列化响应消息失败")
            }

            val signalMessage = deserializeResult.data
            if (signalMessage !is CosSignalMessage.Response) {
                Log.e(TAG, "消息类型不匹配，期望Response但得到: ${signalMessage::class.simpleName}")
                return CosMessageProcessResult.failure("unknown", "响应消息类型不匹配")
            }

            Log.i(TAG, "成功解析COS响应: requestId=${signalMessage.cosResponse.requestId}")

            return cosRequestManager.handleReceivedResponse(senderId, signalMessage)
        } catch (e: Exception) {
            Log.e(TAG, "处理COS响应消息失败", e)
            return CosMessageProcessResult.failure("unknown", "解析响应消息失败: ${e.message}")
        }
    }

    /**
     * 处理COS撤销消息
     */
    private fun processRevocationMessage(senderId: String, messageJson: String): CosMessageProcessResult {
        return try {
            Log.d(TAG, "解析COS撤销消息: $messageJson")

            // 使用CosMessageSerializer反序列化Signal消息
            val deserializeResult = CosMessageSerializer.deserializeSignalMessage(messageJson)
            if (deserializeResult !is CosResult.Success) {
                Log.e(TAG, "反序列化COS Signal撤销消息失败")
                return CosMessageProcessResult.failure("unknown", "反序列化撤销消息失败")
            }

            val signalMessage = deserializeResult.data
            if (signalMessage !is CosSignalMessage.Revocation) {
                Log.e(TAG, "消息类型不匹配，期望Revocation但得到: ${signalMessage::class.simpleName}")
                return CosMessageProcessResult.failure("unknown", "撤销消息类型不匹配")
            }

            Log.i(TAG, "成功解析COS撤销: requestId=${signalMessage.cosRevocation.requestId}")

            cosRequestManager.handleReceivedRevocation(senderId, signalMessage)
        } catch (e: Exception) {
            Log.e(TAG, "处理COS撤销消息失败", e)
            CosMessageProcessResult.failure("unknown", "解析撤销消息失败: ${e.message}")
        }
    }

    /**
     * 处理断开连接消息
     */
    private fun processDisconnectionMessage(senderId: String, messageJson: String): CosMessageProcessResult {
        Log.i(TAG, "处理COS断开连接消息: sender=$senderId")

        return try {
            // 解析断开连接消息
            val messageSerializer = org.thoughtcrime.securesms.coscomm.utils.CosMessageSerializer
            val parseResult = messageSerializer.deserializeSignalMessage(messageJson)

            if (parseResult is org.thoughtcrime.securesms.coscomm.data.CosResult.Error) {
                Log.e(TAG, "解析断开连接消息失败: ${parseResult.exception.message}")
                return CosMessageProcessResult.failure("unknown", "解析断开连接消息失败")
            }

            val signalMessage = (parseResult as org.thoughtcrime.securesms.coscomm.data.CosResult.Success).data

            // 验证消息类型
            if (signalMessage !is CosSignalMessage.Disconnection) {
                Log.e(TAG, "消息类型不匹配，期望Disconnection，实际: ${signalMessage::class.simpleName}")
                return CosMessageProcessResult.failure("unknown", "消息类型不匹配")
            }

            // 验证消息格式
            if (signalMessage.cosDisconnection.requestId.isBlank()) {
                Log.e(TAG, "断开连接消息格式无效: requestId为空")
                return CosMessageProcessResult.failure(signalMessage.messageId, "断开连接消息格式无效")
            }

            Log.i(TAG, "成功解析COS断开连接: requestId=${signalMessage.cosDisconnection.requestId}")

            // 使用断开连接管理器处理
            val disconnectionManager = CosDisconnectionManager.getInstance(context)
            val handleResult = disconnectionManager.handleDisconnectionMessage(senderId, signalMessage.cosDisconnection)

            handleResult.whenComplete { result, throwable ->
                when {
                    throwable != null -> {
                        Log.e(TAG, "处理断开连接消息异常", throwable)
                    }
                    result is org.thoughtcrime.securesms.coscomm.data.CosResult.Success -> {
                        Log.i(TAG, "断开连接消息处理成功: senderId=$senderId")

                        // 通知UI更新
                        try {
                            org.thoughtcrime.securesms.database.SignalDatabase.runPostSuccessfulTransaction {
                                val recipientId = org.thoughtcrime.securesms.recipients.RecipientId.from(senderId)
                                org.thoughtcrime.securesms.recipients.Recipient.live(recipientId).refresh()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "通知UI更新失败", e)
                        }
                    }
                    result is org.thoughtcrime.securesms.coscomm.data.CosResult.Error -> {
                        Log.e(TAG, "处理断开连接消息失败: ${result.exception.message}")
                    }
                }
            }

            CosMessageProcessResult.success(signalMessage.messageId)

        } catch (e: Exception) {
            Log.e(TAG, "处理COS断开连接消息失败", e)
            CosMessageProcessResult.failure("unknown", "解析断开连接消息失败: ${e.message}")
        }
    }
}

/**
 * COS消息处理结果扩展
 */
data class CosProcessingContext(
    val messageId: String,
    val senderId: String,
    val messageType: String,
    val displayInfo: CosMessageDisplayInfo,
    val shouldShowInChat: Boolean,
    val requiresUserAction: Boolean
)
