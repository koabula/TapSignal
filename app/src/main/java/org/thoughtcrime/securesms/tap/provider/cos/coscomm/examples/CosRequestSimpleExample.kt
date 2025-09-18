package org.thoughtcrime.securesms.tap.provider.cos.coscomm.examples

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosRequestManager
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.service.CosRequestHandlingService
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.processor.CosSignalMessageProcessor

/**
 * COS请求功能简化使用示例
 * 展示如何使用阶段四实现的COS请求发送和处理功能
 * 
 * 注意：这是一个简化的示例，实际使用时需要根据具体的Signal集成情况进行调整
 */
class CosRequestSimpleExample(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosRequestSimpleExample::class.java)
    }
    
    private val requestManager = CosRequestManager.getInstance(context)
    private val handlingService = CosRequestHandlingService.getInstance(context)
    private val messageProcessor = CosSignalMessageProcessor.getInstance(context)
    
    /**
     * 示例1：发送COS通信请求
     */
    fun sendCosRequestExample(recipientId: String) {
        Log.i(TAG, "=== 发送COS请求示例 ===")
        
        // 发送COS请求
        val requestFuture = requestManager.sendCosRequest(
            recipientId = recipientId,
            durationType = CosDuration.ONE_WEEK,
            message = "希望建立COS通信通道以提高通信可靠性"
        )
        
        requestFuture.thenAccept { result ->
            when (result) {
                is CosRequestResult.Success -> {
                    Log.i(TAG, "COS请求发送成功")
                    Log.i(TAG, "请求ID: ${result.requestId}")
                    Log.i(TAG, "通道ID: ${result.channelId}")
                    
                    // 可以在这里更新UI，显示请求已发送
                    onRequestSentSuccessfully(result.requestId)
                }
                is CosRequestResult.Failure -> {
                    Log.e(TAG, "COS请求发送失败: ${result.errorMessage}")
                    
                    // 可以在这里显示错误提示给用户
                    onRequestSendFailed(result.errorMessage)
                }
            }
        }.exceptionally { throwable ->
            Log.e(TAG, "发送COS请求时发生异常", throwable)
            onRequestSendFailed("发送请求时发生异常: ${throwable.message}")
            null
        }
    }
    
    /**
     * 示例2：处理接收到的COS请求
     */
    fun handleIncomingRequestExample(senderId: String, requestMessage: CosSignalMessage.Request) {
        Log.i(TAG, "=== 处理接收到的COS请求示例 ===")
        
        // 处理接收到的请求
        val processResult = handlingService.handleIncomingCosRequest(senderId, requestMessage)
        
        when (processResult.status) {
            CosMessageStatus.COMPLETED -> {
                Log.i(TAG, "COS请求处理成功，等待用户确认")
                Log.i(TAG, "请求ID: ${requestMessage.cosRequest.requestId}")
                Log.i(TAG, "发送方: $senderId")
                Log.i(TAG, "访问时长: ${requestMessage.cosRequest.durationType}")
                
                // 此时应该显示通知给用户，让用户选择接受或拒绝
                showRequestNotificationToUser(senderId, requestMessage.cosRequest)
            }
            CosMessageStatus.FAILED -> {
                Log.e(TAG, "COS请求处理失败: ${processResult.errorMessage}")
                
                // 可以在这里记录失败原因或通知用户
                onRequestProcessingFailed(processResult.errorMessage)
            }
            else -> {
                Log.w(TAG, "COS请求处理状态异常: ${processResult.status}")
            }
        }
    }
    
    /**
     * 示例3：用户接受COS请求
     */
    fun acceptRequestExample(requestId: String) {
        Log.i(TAG, "=== 用户接受COS请求示例 ===")
        
        // 用户选择接受请求
        val acceptFuture = handlingService.acceptRequest(
            requestId = requestId,
            agreedDuration = CosDuration.ONE_WEEK // 可以与原请求不同
        )
        
        acceptFuture.thenAccept { result ->
            when (result) {
                is CosRequestResult.Success -> {
                    Log.i(TAG, "COS请求接受成功")
                    Log.i(TAG, "请求ID: ${result.requestId}")
                    Log.i(TAG, "通道ID: ${result.channelId}")
                    
                    // 通知用户COS通道已建立
                    onChannelEstablished(result.channelId)
                }
                is CosRequestResult.Failure -> {
                    Log.e(TAG, "接受COS请求失败: ${result.errorMessage}")
                    
                    // 通知用户接受失败
                    onAcceptRequestFailed(result.errorMessage)
                }
            }
        }.exceptionally { throwable ->
            Log.e(TAG, "接受COS请求时发生异常", throwable)
            onAcceptRequestFailed("接受请求时发生异常: ${throwable.message}")
            null
        }
    }
    
    /**
     * 示例4：用户拒绝COS请求
     */
    fun rejectRequestExample(requestId: String, reason: String) {
        Log.i(TAG, "=== 用户拒绝COS请求示例 ===")
        
        // 用户选择拒绝请求
        val rejectFuture = handlingService.rejectRequest(
            requestId = requestId,
            rejectionReason = reason
        )
        
        rejectFuture.thenAccept { result ->
            when (result) {
                is CosRequestResult.Success -> {
                    Log.i(TAG, "COS请求拒绝成功")
                    Log.i(TAG, "请求ID: ${result.requestId}")
                    
                    // 通知用户已拒绝请求
                    onRequestRejected(result.requestId)
                }
                is CosRequestResult.Failure -> {
                    Log.e(TAG, "拒绝COS请求失败: ${result.errorMessage}")
                    
                    // 通知用户拒绝失败
                    onRejectRequestFailed(result.errorMessage)
                }
            }
        }.exceptionally { throwable ->
            Log.e(TAG, "拒绝COS请求时发生异常", throwable)
            onRejectRequestFailed("拒绝请求时发生异常: ${throwable.message}")
            null
        }
    }
    
    /**
     * 示例5：处理Signal消息中的COS消息
     */
    fun processSignalMessageExample(messageBody: String, senderId: String) {
        Log.i(TAG, "=== 处理Signal消息中的COS消息示例 ===")
        
        // 检查是否为COS消息
        if (messageProcessor.isCosMessage(messageBody)) {
            Log.i(TAG, "检测到COS消息，开始处理...")
            
            // 处理COS消息
            val processResult = messageProcessor.processCosMessage(senderId, messageBody)
            
            when (processResult.status) {
                CosMessageStatus.COMPLETED -> {
                    Log.i(TAG, "COS消息处理成功")
                }
                CosMessageStatus.FAILED -> {
                    Log.e(TAG, "COS消息处理失败: ${processResult.errorMessage}")
                }
                else -> {
                    Log.w(TAG, "COS消息处理状态异常: ${processResult.status}")
                }
            }
        } else {
            Log.d(TAG, "普通Signal消息，跳过COS处理")
        }
    }
    
    // ==================== 回调方法示例 ====================
    
    private fun onRequestSentSuccessfully(requestId: String) {
        Log.i(TAG, "请求发送成功回调: requestId=$requestId")
        // 在这里更新UI，显示请求已发送状态
    }
    
    private fun onRequestSendFailed(errorMessage: String) {
        Log.e(TAG, "请求发送失败回调: $errorMessage")
        // 在这里显示错误提示给用户
    }
    
    private fun showRequestNotificationToUser(senderId: String, cosRequest: CosRequest) {
        Log.i(TAG, "显示请求通知给用户: senderId=$senderId, requestId=${cosRequest.requestId}")
        // 在这里显示通知或对话框让用户选择接受/拒绝
    }
    
    private fun onChannelEstablished(channelId: String?) {
        Log.i(TAG, "通道建立成功回调: channelId=$channelId")
        // 在这里更新UI，显示COS通信已激活
    }
    
    private fun onAcceptRequestFailed(errorMessage: String) {
        Log.e(TAG, "接受请求失败回调: $errorMessage")
        // 在这里显示错误提示
    }
    
    private fun onRequestRejected(requestId: String) {
        Log.i(TAG, "请求拒绝成功回调: requestId=$requestId")
        // 在这里更新UI状态
    }
    
    private fun onRejectRequestFailed(errorMessage: String) {
        Log.e(TAG, "拒绝请求失败回调: $errorMessage")
        // 在这里显示错误提示
    }
    
    private fun onRequestProcessingFailed(errorMessage: String?) {
        Log.e(TAG, "请求处理失败回调: $errorMessage")
        // 在这里记录错误或通知用户
    }
}
