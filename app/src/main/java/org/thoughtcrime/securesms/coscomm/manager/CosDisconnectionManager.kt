/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.processor.CosSignalMessageProcessor
import org.thoughtcrime.securesms.coscomm.utils.CosMessageSerializer
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.CompletableFuture

/**
 * COS断开连接管理器
 * 负责处理COS v2模式的断开连接流程
 */
class CosDisconnectionManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosDisconnectionManager::class.java)

        @Volatile
        private var INSTANCE: CosDisconnectionManager? = null

        fun getInstance(context: Context): CosDisconnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosDisconnectionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * 插入"v2 mode enabled"系统消息
         */
        fun insertV2ModeEnabledMessage(context: Context, recipientId: String) {
            try {
                // 解析RecipientId字符串
                val parsedRecipientId = parseRecipientIdString(recipientId)
                if (parsedRecipientId == null) {
                    Log.e(TAG, "无法解析RecipientId: $recipientId")
                    return
                }

                val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(parsedRecipientId)
                val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getOrCreateThreadIdFor(recipient)

                // 插入系统消息，使用COS_V2_MODE_ENABLED_TYPE类型
                val values = androidx.core.content.contentValuesOf(
                    org.thoughtcrime.securesms.database.MessageTable.FROM_RECIPIENT_ID to recipient.id.serialize(),
                    org.thoughtcrime.securesms.database.MessageTable.FROM_DEVICE_ID to 1,
                    org.thoughtcrime.securesms.database.MessageTable.TO_RECIPIENT_ID to org.thoughtcrime.securesms.recipients.Recipient.self().id.serialize(),
                    org.thoughtcrime.securesms.database.MessageTable.DATE_RECEIVED to System.currentTimeMillis(),
                    org.thoughtcrime.securesms.database.MessageTable.DATE_SENT to System.currentTimeMillis(),
                    org.thoughtcrime.securesms.database.MessageTable.READ to 1,
                    org.thoughtcrime.securesms.database.MessageTable.TYPE to org.thoughtcrime.securesms.database.MessageTypes.COS_V2_MODE_ENABLED_TYPE,
                    org.thoughtcrime.securesms.database.MessageTable.THREAD_ID to threadId,
                    org.thoughtcrime.securesms.database.MessageTable.BODY to "v2 mode enabled"
                )

                val messageId = org.thoughtcrime.securesms.database.SignalDatabase.writableDatabase.insert(
                    org.thoughtcrime.securesms.database.MessageTable.TABLE_NAME,
                    null,
                    values
                )

                // 通知UI更新
                org.thoughtcrime.securesms.dependencies.AppDependencies.databaseObserver.notifyConversationListeners(threadId)

                Log.i(TAG, "插入v2 mode enabled消息成功: recipientId=$recipientId, messageId=$messageId")

            } catch (e: Exception) {
                Log.e(TAG, "插入v2 mode enabled消息失败: recipientId=$recipientId", e)
            }
        }

        /**
         * 解析RecipientId字符串
         */
        private fun parseRecipientIdString(recipientId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
            return try {
                // 情况1: 如果是RecipientId::X格式
                if (recipientId.contains("::")) {
                    val idPart = recipientId.split("::").lastOrNull()
                    if (idPart != null && idPart.all { it.isDigit() }) {
                        val numericId = idPart.toLong()
                        org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
                    } else {
                        null
                    }
                }
                // 情况2: 如果是纯数字
                else if (recipientId.all { it.isDigit() }) {
                    org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                }
                // 情况3: 尝试直接解析
                else {
                    org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析RecipientId失败: $recipientId", e)
                null
            }
        }
    }
    
    private val channelManager = CosChannelManager.getInstance(context)
    private val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
    private val messageSerializer = CosMessageSerializer
    
    /**
     * 断开COS v2模式连接
     * 
     * @param recipientId 接收方ID
     * @param reason 断开原因
     * @return 断开结果
     */
    fun disconnectV2Mode(
        recipientId: String,
        reason: String = "User requested disconnection"
    ): CompletableFuture<CosResult<Unit>> {
        Log.i(TAG, "开始断开COS v2模式: recipientId=$recipientId, reason=$reason")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 检查当前通道状态
                val channel = channelManager.getChannel(recipientId)
                if (channel == null || !channel.isActive()) {
                    Log.w(TAG, "通道不存在或未激活，无需断开: recipientId=$recipientId")
                    return@supplyAsync CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND))
                }
                
                // 2. 发送断开控制消息
                val sendResult = sendDisconnectionMessage(recipientId, channel.requestId, reason)
                if (sendResult is CosResult.Error) {
                    Log.e(TAG, "发送断开消息失败: recipientId=$recipientId, error=${sendResult.exception.message}")
                    // 即使发送失败，也继续本地清理
                } else {
                    // 发送成功，插入提示消息
                    insertV2ModeDisabledMessage(recipientId, true)
                }
                
                // 3. 清理本地状态
                val cleanupResult = performLocalCleanup(recipientId)
                if (cleanupResult is CosResult.Error) {
                    Log.e(TAG, "本地清理失败: recipientId=$recipientId, error=${cleanupResult.exception.message}")
                    return@supplyAsync cleanupResult
                }
                
                Log.i(TAG, "COS v2模式断开成功: recipientId=$recipientId")
                CosResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "断开COS v2模式异常: recipientId=$recipientId", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
    
    /**
     * 处理接收到的断开连接消息
     * 
     * @param senderId 发送方ID
     * @param disconnection 断开连接消息
     * @return 处理结果
     */
    fun handleDisconnectionMessage(
        senderId: String,
        disconnection: CosDisconnection
    ): CompletableFuture<CosResult<Unit>> {
        Log.i(TAG, "处理接收到的断开连接消息: senderId=$senderId, requestId=${disconnection.requestId}")
        
        return CompletableFuture.supplyAsync {
            try {
                // 验证断开连接消息
                val channel = channelManager.getChannel(senderId)
                if (channel == null || channel.requestId != disconnection.requestId) {
                    Log.w(TAG, "断开连接消息验证失败: senderId=$senderId, requestId=${disconnection.requestId}")
                    return@supplyAsync CosResult.Error(CosException(CosErrorCode.INVALID_MESSAGE_FORMAT))
                }
                
                // 执行本地清理
                val cleanupResult = performLocalCleanup(senderId)
                if (cleanupResult is CosResult.Error) {
                    Log.e(TAG, "处理断开连接消息时本地清理失败: senderId=$senderId", cleanupResult.exception)
                    return@supplyAsync cleanupResult
                }

                // 插入提示消息
                insertV2ModeDisabledMessage(senderId, false)

                Log.i(TAG, "断开连接消息处理成功: senderId=$senderId")
                CosResult.Success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "处理断开连接消息异常: senderId=$senderId", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
    
    /**
     * 发送断开连接控制消息
     */
    private fun sendDisconnectionMessage(
        recipientId: String,
        requestId: String,
        reason: String
    ): CosResult<Unit> {
        return try {
            // 创建断开连接消息
            val disconnection = CosDisconnection.create(
                requestId = requestId,
                disconnectionReason = reason,
                initiatedBy = getCurrentUserId()
            )
            
            val disconnectionMessage = CosSignalMessage.Disconnection.create(disconnection)
            
            // 序列化消息
            val serializedMessage = messageSerializer.serializeSignalMessage(disconnectionMessage)
            if (serializedMessage is CosResult.Error) {
                Log.e(TAG, "序列化断开连接消息失败: recipientId=$recipientId")
                return serializedMessage
            }

            // 添加COS消息前缀
            val messageBody = CosSignalMessageProcessor.COS_MESSAGE_PREFIX + (serializedMessage as CosResult.Success).data
            
            // 发送Signal消息
            val recipient = Recipient.resolved(RecipientId.from(recipientId))
            val outgoingMessage = org.thoughtcrime.securesms.mms.OutgoingMessage.text(
                threadRecipient = recipient,
                body = messageBody,
                expiresIn = 0L,
                sentTimeMillis = System.currentTimeMillis()
            )

            // 使用MessageSender发送消息
            val threadId = SignalDatabase.threads.getOrCreateValidThreadId(recipient, -1, outgoingMessage.distributionType)
            val messageId = org.thoughtcrime.securesms.sms.MessageSender.send(
                context,
                outgoingMessage,
                threadId,
                org.thoughtcrime.securesms.sms.MessageSender.SendType.SIGNAL,
                null,
                null
            )
            
            Log.i(TAG, "断开连接消息发送成功: recipientId=$recipientId, messageId=$messageId")
            CosResult.Success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "发送断开连接消息异常: recipientId=$recipientId", e)
            CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
        }
    }
    
    /**
     * 执行本地清理
     */
    private fun performLocalCleanup(recipientId: String): CosResult<Unit> {
        return try {
            Log.i(TAG, "开始本地清理: recipientId=$recipientId")

            // 1. 停止特定联系人的轮询任务
            try {
                val pollingManager = org.thoughtcrime.securesms.coscomm.manager.CosPollingManager.getInstance(context)
                val pollingService = org.thoughtcrime.securesms.coscomm.service.CosPollingService(context)
                pollingService.stopPollingForRecipient(recipientId)
                // 强制重新评估轮询策略
                pollingService.forceReevaluatePollingStrategy()
                Log.i(TAG, "轮询任务已停止: recipientId=$recipientId")
            } catch (e: Exception) {
                Log.w(TAG, "停止轮询任务失败，继续其他清理: recipientId=$recipientId", e)
            }

            // 2. 删除子账户凭证
            val removeSubAccountResult = subAccountPoolManager.removeSubAccount(recipientId)
            if (removeSubAccountResult is CosResult.Error) {
                Log.w(TAG, "删除子账户失败，继续其他清理: recipientId=$recipientId")
            }

            // 3. 更新通道状态为已撤销
            val updateChannelResult = channelManager.updateChannelStatus(recipientId, ChannelStatus.REVOKED)
            if (!updateChannelResult) {
                Log.w(TAG, "更新通道状态失败: recipientId=$recipientId")
            }

            // 4. 删除通道
            val deleteChannelResult = channelManager.deleteChannel(recipientId)
            if (!deleteChannelResult) {
                Log.w(TAG, "删除通道失败: recipientId=$recipientId")
            }

            Log.i(TAG, "本地清理完成: recipientId=$recipientId")
            CosResult.Success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "本地清理异常: recipientId=$recipientId", e)
            CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
        }
    }
    
    /**
     * 插入v2模式断开提示消息
     */
    private fun insertV2ModeDisabledMessage(recipientId: String, isSender: Boolean) {
        try {
            val recipientIdObj = parseRecipientId(recipientId)
            if (recipientIdObj == null) {
                Log.w(TAG, "无法解析RecipientId，跳过插入提示消息: $recipientId")
                return
            }

            val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
            val threadId = SignalDatabase.threads.getOrCreateValidThreadId(recipient, -1)

            // 插入系统消息，使用COS_V2_MODE_DISABLED_TYPE类型
            val values = androidx.core.content.contentValuesOf(
                org.thoughtcrime.securesms.database.MessageTable.FROM_RECIPIENT_ID to recipient.id.serialize(),
                org.thoughtcrime.securesms.database.MessageTable.FROM_DEVICE_ID to 1,
                org.thoughtcrime.securesms.database.MessageTable.TO_RECIPIENT_ID to org.thoughtcrime.securesms.recipients.Recipient.self().id.serialize(),
                org.thoughtcrime.securesms.database.MessageTable.DATE_RECEIVED to System.currentTimeMillis(),
                org.thoughtcrime.securesms.database.MessageTable.DATE_SENT to System.currentTimeMillis(),
                org.thoughtcrime.securesms.database.MessageTable.READ to 1,
                org.thoughtcrime.securesms.database.MessageTable.TYPE to org.thoughtcrime.securesms.database.MessageTypes.COS_V2_MODE_DISABLED_TYPE,
                org.thoughtcrime.securesms.database.MessageTable.THREAD_ID to threadId,
                org.thoughtcrime.securesms.database.MessageTable.BODY to "v2 mode disabled"
            )

            val messageId = org.thoughtcrime.securesms.database.SignalDatabase.writableDatabase.insert(
                org.thoughtcrime.securesms.database.MessageTable.TABLE_NAME,
                null,
                values
            )

            // 通知UI更新
            org.thoughtcrime.securesms.dependencies.AppDependencies.databaseObserver.notifyConversationListeners(threadId)
            Log.i(TAG, "已插入v2模式断开提示消息: recipientId=$recipientId, isSender=$isSender")

        } catch (e: Exception) {
            Log.w(TAG, "插入v2模式断开提示消息失败: recipientId=$recipientId", e)
        }
    }

    /**
     * 解析RecipientId字符串
     */
    private fun parseRecipientId(recipientId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        return try {
            // 情况1: 如果是RecipientId::X格式
            if (recipientId.contains("::")) {
                val idPart = recipientId.split("::").lastOrNull()
                if (idPart != null && idPart.all { it.isDigit() }) {
                    val numericId = idPart.toLong()
                    return org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
                }
            }

            // 情况2: 如果是纯数字ID
            if (recipientId.all { it.isDigit() }) {
                val numericId = recipientId.toLong()
                return org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
            }

            // 情况3: 尝试直接解析
            org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId)
        } catch (e: Exception) {
            Log.w(TAG, "解析RecipientId失败: $recipientId", e)
            null
        }
    }

    /**
     * 获取当前用户ID
     */
    private fun getCurrentUserId(): String {
        return try {
            org.thoughtcrime.securesms.keyvalue.SignalStore.account.requireAci().toString()
        } catch (e: Exception) {
            Log.w(TAG, "获取当前用户ID失败，使用默认值", e)
            "unknown"
        }
    }
}
