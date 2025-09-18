/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.provider.cos.coscomm.ui

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosRequestManager
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.util.concurrent.ConcurrentHashMap

/**
 * COS请求通知管理器
 * 负责管理COS请求的通知显示和用户交互
 */
class CosRequestNotificationManager private constructor(private val context: Context) {

    companion object {
        private val TAG = Log.tag(CosRequestNotificationManager::class.java)

        // 通知相关常量
        private const val CHANNEL_ID = "cos_requests"
        private const val NOTIFICATION_ID_BASE = 10000

        @Volatile
        private var INSTANCE: CosRequestNotificationManager? = null

        fun getInstance(context: Context): CosRequestNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosRequestNotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val cosRequestManager = CosRequestManager.getInstance(context)
    private val pendingRequests = ConcurrentHashMap<String, Pair<String, CosSignalMessage.Request>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        createNotificationChannel()
    }
    
    /**
     * 处理接收到的COS请求
     * 
     * @param senderId 发送方ID
     * @param requestMessage COS请求消息
     */
    fun handleReceivedRequest(senderId: String, requestMessage: CosSignalMessage.Request) {
        Log.i(TAG, "处理接收到的COS请求: senderId=$senderId, requestId=${requestMessage.cosRequest.requestId}")
        
        try {
            // 存储待处理的请求，同时存储senderId
            pendingRequests[requestMessage.cosRequest.requestId] = Pair(senderId, requestMessage)
            
            // 获取发送方信息
            val senderRecipient = try {
                // 如果senderId是"RecipientId::X"格式，提取实际的ID
                val actualId = if (senderId.startsWith("RecipientId::")) {
                    senderId.substring("RecipientId::".length)
                } else {
                    senderId
                }
                Log.d(TAG, "解析RecipientId: 原始=$senderId, 提取=$actualId")

                // 尝试不同的解析方式
                val recipientId = try {
                    RecipientId.from(actualId)
                } catch (e: Exception) {
                    Log.w(TAG, "RecipientId.from失败，尝试其他方式: $actualId", e)
                    // 如果是数字，尝试直接创建
                    if (actualId.matches(Regex("\\d+"))) {
                        RecipientId.from(actualId.toLong())
                    } else {
                        throw e
                    }
                }

                val recipient = Recipient.resolved(recipientId)
                Log.d(TAG, "成功解析Recipient: ${recipient.getDisplayName(context)}")
                recipient
            } catch (e: Exception) {
                Log.w(TAG, "无法解析发送方ID: $senderId", e)
                // 使用默认名称
                null
            }
            val senderName = senderRecipient?.getDisplayName(context) ?: "COS用户"
            
            // 显示请求通知对话框
            showRequestNotification(senderName, requestMessage, senderId)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理COS请求失败", e)
            Toast.makeText(context, "处理COS请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "COS通信请求"
            val descriptionText = "接收COS v2模式通信请求的通知"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示COS请求通知
     *
     * @param senderName 发送方名称
     * @param requestMessage 请求消息
     * @param senderId 发送方ID
     */
    private fun showRequestNotification(senderName: String, requestMessage: CosSignalMessage.Request, senderId: String) {
        Log.i(TAG, "显示COS请求通知: $senderName 请求建立COS v2通信模式")

        // 确保在主线程中执行通知显示
        mainHandler.post {
            showNotificationOnMainThread(senderName, requestMessage, senderId)
        }
    }

    /**
     * 在主线程中显示通知
     */
    private fun showNotificationOnMainThread(senderName: String, requestMessage: CosSignalMessage.Request, senderId: String) {
        try {
            // 保持完整的senderId格式，不要提取数字部分
            // 这样可以确保在整个调用链中ID格式保持一致
            Log.d(TAG, "显示通知，使用完整senderId: $senderId")

            // 创建点击通知后的Intent，打开COS请求处理Activity
            val notificationIntent = CosRequestActivity.createIntent(
                context,
                requestMessage.cosRequest.requestId,
                senderName,
                senderId  // 传递完整的senderId
            )

            val pendingIntent = PendingIntent.getActivity(
                context,
                requestMessage.cosRequest.requestId.hashCode(),
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 创建通知（使用简单的实现，避免图标资源问题）
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("COS v2通信请求")
                .setContentText("$senderName 请求建立COS v2通信模式")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$senderName 请求建立COS v2通信模式。点击查看详情并选择接受或拒绝。"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            // 优先尝试直接启动Activity（如果应用在前台）
            var activityStarted = false
            try {
                // 使用完整的senderId，保持ID格式一致性
                Log.d(TAG, "直接启动Activity，使用完整senderId: $senderId")

                val directIntent = CosRequestActivity.createIntent(
                    context,
                    requestMessage.cosRequest.requestId,
                    senderName,
                    senderId  // 使用完整的senderId
                )
                context.startActivity(directIntent)
                activityStarted = true
                Log.i(TAG, "直接启动COS请求Activity成功")
            } catch (e: Exception) {
                Log.d(TAG, "无法直接启动Activity，将显示通知: ${e.message}")
            }

            // 如果Activity启动失败，显示通知
            if (!activityStarted) {
                val notificationManager = NotificationManagerCompat.from(context)
                val notificationId = NOTIFICATION_ID_BASE + requestMessage.cosRequest.requestId.hashCode()

                // 检查通知权限
                if (notificationManager.areNotificationsEnabled()) {
                    notificationManager.notify(notificationId, notification)
                    Log.i(TAG, "COS请求通知已显示: notificationId=$notificationId")
                } else {
                    Log.w(TAG, "通知权限被禁用，回退到Toast显示")
                    showToastFallback(senderName)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "显示COS请求通知失败", e)
            showToastFallback(senderName)
        }
    }

    /**
     * 回退到Toast显示
     */
    private fun showToastFallback(senderName: String) {
        val message = "$senderName 请求建立COS v2通信模式"
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        Log.i(TAG, "使用Toast显示COS请求: $message")
    }

    /**
     * 创建通知操作的PendingIntent
     */
    private fun createActionPendingIntent(requestId: String, action: String): PendingIntent {
        val intent = Intent().apply {
            putExtra("requestId", requestId)
            putExtra("action", action)
        }

        return PendingIntent.getBroadcast(
            context,
            (requestId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * 在Activity中显示COS请求对话框
     *
     * @param activity 当前Activity
     * @param requestId 请求ID
     */
    fun showRequestDialogInActivity(activity: Activity, requestId: String) {
        val requestData = pendingRequests[requestId]
        if (requestData == null) {
            Log.w(TAG, "未找到请求: requestId=$requestId")
            return
        }

        val (senderId, requestMessage) = requestData
        val senderRecipient = Recipient.resolved(RecipientId.from(senderId))
        val senderName = senderRecipient.getDisplayName(activity)

        CosRequestDialog.showReceiveRequestDialog(
            activity,
            senderName,
            onAccept = {
                acceptRequest(requestId, requestMessage, senderId)
            },
            onReject = {
                rejectRequest(requestId, requestMessage, senderId)
            }
        )
    }
    
    /**
     * 接受COS请求
     *
     * @param requestId 请求ID
     * @param requestMessage 请求消息
     * @param senderId 发送方ID
     */
    private fun acceptRequest(requestId: String, requestMessage: CosSignalMessage.Request, senderId: String) {
        Log.i(TAG, "用户接受COS请求: requestId=$requestId, senderId=$senderId")

        try {
            // 先移除待处理请求，防止重复处理
            pendingRequests.remove(requestId)

            // 调用COS请求管理器处理接受逻辑
            val future = cosRequestManager.acceptRequest(requestId, senderId)

            future.thenApply { result ->
                when (result) {
                    is CosRequestResult.Success -> {
                        Log.i(TAG, "COS请求接受成功: requestId=$requestId")
                        Toast.makeText(context, R.string.cos_request_accepted, Toast.LENGTH_SHORT).show()
                    }
                    is CosRequestResult.Failure -> {
                        Log.e(TAG, "COS请求接受失败: ${result.errorMessage}")
                        Toast.makeText(context, "接受请求失败: ${result.errorMessage}", Toast.LENGTH_SHORT).show()

                        // 如果失败，重新添加到待处理请求中
                        pendingRequests[requestId] = Pair(senderId, requestMessage)
                    }
                }
            }.exceptionally { throwable ->
                Log.e(TAG, "接受COS请求异常", throwable)
                Toast.makeText(context, "接受请求异常: ${throwable.message}", Toast.LENGTH_SHORT).show()

                // 如果异常，重新添加到待处理请求中
                pendingRequests[requestId] = Pair(senderId, requestMessage)
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "接受COS请求失败", e)
            Toast.makeText(context, "接受请求失败: ${e.message}", Toast.LENGTH_SHORT).show()

            // 如果异常，重新添加到待处理请求中
            pendingRequests[requestId] = Pair(senderId, requestMessage)
        }
    }
    
    /**
     * 拒绝COS请求
     *
     * @param requestId 请求ID
     * @param requestMessage 请求消息
     * @param senderId 发送方ID
     */
    private fun rejectRequest(requestId: String, requestMessage: CosSignalMessage.Request, senderId: String) {
        Log.i(TAG, "用户拒绝COS请求: requestId=$requestId, senderId=$senderId")

        try {
            // 先移除待处理请求，防止重复处理
            pendingRequests.remove(requestId)

            // 调用COS请求管理器处理拒绝逻辑
            val future = cosRequestManager.rejectRequest(requestId, senderId, "用户拒绝")

            future.thenApply { result ->
                when (result) {
                    is CosRequestResult.Success -> {
                        Log.i(TAG, "COS请求拒绝成功: requestId=$requestId")
                        Toast.makeText(context, R.string.cos_request_rejected, Toast.LENGTH_SHORT).show()
                    }
                    is CosRequestResult.Failure -> {
                        Log.e(TAG, "COS请求拒绝失败: ${result.errorMessage}")
                        Toast.makeText(context, "拒绝请求失败: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.exceptionally { throwable ->
                Log.e(TAG, "拒绝COS请求异常", throwable)
                Toast.makeText(context, "拒绝请求异常: ${throwable.message}", Toast.LENGTH_SHORT).show()
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "拒绝COS请求失败", e)
            Toast.makeText(context, "拒绝请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 获取所有待处理的请求
     *
     * @return 待处理请求列表
     */
    fun getPendingRequests(): List<CosSignalMessage.Request> {
        return pendingRequests.values.map { it.second }.toList()
    }
    
    /**
     * 清理过期的请求
     */
    fun cleanupExpiredRequests() {
        val currentTime = System.currentTimeMillis()
        val expiredRequests = pendingRequests.filter { (_, requestData) ->
            val request = requestData.second
            val expirationTime = request.cosRequest.timestamp + (24 * 60 * 60 * 1000) // 24小时过期
            currentTime > expirationTime
        }

        expiredRequests.forEach { (requestId, _) ->
            Log.i(TAG, "清理过期请求: requestId=$requestId")
            pendingRequests.remove(requestId)
        }

        if (expiredRequests.isNotEmpty()) {
            Log.i(TAG, "清理了 ${expiredRequests.size} 个过期请求")
        }
    }
}
