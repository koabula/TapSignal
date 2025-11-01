package org.thoughtcrime.securesms.tap.notification

import org.signal.core.util.logging.Log

/**
 * 推送通知处理器
 */
class NotificationHandler {
    
    companion object {
        private val TAG = Log.tag(NotificationHandler::class.java)
    }
    
    private var downloadTriggerCallback: ((String) -> Unit)? = null
    
    fun setDownloadTriggerCallback(callback: (String) -> Unit) {
        downloadTriggerCallback = callback
    }
    
    fun handleNotification(notification: NotificationMessage) {
        try {
            if (!notification.validate()) {
                Log.w(TAG, "收到无效的推送通知")
                return
            }
            
            when (notification.type) {
                NotificationMessage.TYPE_NEW_MESSAGE -> {
                    handleNewMessageNotification(notification)
                }
                NotificationMessage.TYPE_HEARTBEAT -> {
                    handleHeartbeatNotification(notification)
                }
                else -> {
                    Log.w(TAG, "未知的通知类型: ${notification.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理推送通知失败", e)
        }
    }
    
    private fun handleNewMessageNotification(notification: NotificationMessage) {
        try {
            val senderId = notification.senderId
            
            Log.d(TAG, "处理新消息通知: senderId=$senderId")
            
            downloadTriggerCallback?.invoke(senderId)
                ?: Log.w(TAG, "下载触发回调未设置")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理新消息通知失败", e)
        }
    }
    
    private fun handleHeartbeatNotification(notification: NotificationMessage) {
        Log.d(TAG, "收到心跳通知: ${notification.senderId}")
    }
}

