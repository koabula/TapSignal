package org.thoughtcrime.securesms.coscomm.service

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.manager.CosRequestManager
import org.thoughtcrime.securesms.coscomm.manager.CosRequestNotificationManager
import org.thoughtcrime.securesms.coscomm.processor.CosSignalMessageProcessor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * COS请求处理服务
 * 协调COS请求的完整处理流程，包括接收、通知、用户确认和响应
 */
class CosRequestHandlingService private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosRequestHandlingService::class.java)
        
        @Volatile
        private var INSTANCE: CosRequestHandlingService? = null
        
        fun getInstance(context: Context): CosRequestHandlingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosRequestHandlingService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val cosRequestManager = CosRequestManager.getInstance(context)
    private val notificationManager = CosRequestNotificationManager.getInstance(context)
    private val messageProcessor = CosSignalMessageProcessor.getInstance(context)
    
    // 跟踪正在处理的请求
    private val processingRequests: MutableMap<String, RequestProcessingState> = ConcurrentHashMap()
    
    /**
     * 处理接收到的COS请求消息
     * 这是从Signal消息接收流程中调用的入口点
     */
    fun handleIncomingCosRequest(
        senderId: String,
        requestMessage: CosSignalMessage.Request
    ): CosMessageProcessResult {
        Log.i(TAG, "处理传入的COS请求: senderId=$senderId, requestId=${requestMessage.cosRequest.requestId}")
        
        try {
            val requestId = requestMessage.cosRequest.requestId
            
            // 1. 检查是否已在处理中
            if (processingRequests.containsKey(requestId)) {
                Log.w(TAG, "请求已在处理中: requestId=$requestId")
                return CosMessageProcessResult.failure(requestMessage.messageId, "请求已在处理中")
            }
            
            // 2. 标记为处理中
            processingRequests[requestId] = RequestProcessingState(
                requestId = requestId,
                senderId = senderId,
                status = ProcessingStatus.RECEIVED,
                timestamp = System.currentTimeMillis()
            )
            
            // 3. 使用CosRequestManager处理请求
            val processResult = cosRequestManager.handleReceivedRequest(senderId, requestMessage)
            
            if (processResult.status == CosMessageStatus.COMPLETED) {
                // 4. 显示通知给用户
                notificationManager.showRequestNotification(senderId, requestMessage.cosRequest)
                
                // 5. 更新处理状态
                processingRequests[requestId] = processingRequests[requestId]!!.copy(
                    status = ProcessingStatus.AWAITING_USER_ACTION
                )
                
                Log.i(TAG, "COS请求处理完成，等待用户确认: requestId=$requestId")
            } else {
                // 处理失败，清理状态
                processingRequests.remove(requestId)
                Log.e(TAG, "COS请求处理失败: requestId=$requestId, error=${processResult.errorMessage}")
            }
            
            return processResult
            
        } catch (e: Exception) {
            Log.e(TAG, "处理COS请求时发生异常", e)
            processingRequests.remove(requestMessage.cosRequest.requestId)
            return CosMessageProcessResult.failure(
                requestMessage.messageId,
                "处理请求时发生异常: ${e.message}"
            )
        }
    }
    
    /**
     * 用户接受COS请求
     * @param requestId 请求ID
     * @param agreedDuration 同意的访问时长
     * @return 处理结果
     */
    fun acceptRequest(
        requestId: String,
        agreedDuration: CosDuration
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "用户接受COS请求: requestId=$requestId, duration=$agreedDuration")
        
        return CompletableFuture.supplyAsync {
            try {
                val processingState = processingRequests[requestId]
                if (processingState == null) {
                    Log.e(TAG, "未找到处理中的请求: requestId=$requestId")
                    return@supplyAsync CosRequestResult.Failure("未找到处理中的请求")
                }
                
                // 1. 更新处理状态
                processingRequests[requestId] = processingState.copy(
                    status = ProcessingStatus.ACCEPTING
                )
                
                // 2. 调用CosRequestManager接受请求
                val acceptResult = cosRequestManager.acceptCosRequest(
                    processingState.senderId,
                    requestId,
                    agreedDuration
                ).get()
                
                // 3. 取消通知
                notificationManager.cancelNotification(requestId)
                
                // 4. 清理处理状态
                processingRequests.remove(requestId)
                
                Log.i(TAG, "COS请求接受完成: requestId=$requestId")
                acceptResult
                
            } catch (e: Exception) {
                Log.e(TAG, "接受COS请求时发生异常", e)
                processingRequests.remove(requestId)
                CosRequestResult.Failure("接受请求时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 用户拒绝COS请求
     * @param requestId 请求ID
     * @param rejectionReason 拒绝原因
     * @return 处理结果
     */
    fun rejectRequest(
        requestId: String,
        rejectionReason: String
    ): CompletableFuture<CosRequestResult> {
        Log.i(TAG, "用户拒绝COS请求: requestId=$requestId, reason=$rejectionReason")
        
        return CompletableFuture.supplyAsync {
            try {
                val processingState = processingRequests[requestId]
                if (processingState == null) {
                    Log.e(TAG, "未找到处理中的请求: requestId=$requestId")
                    return@supplyAsync CosRequestResult.Failure("未找到处理中的请求")
                }
                
                // 1. 更新处理状态
                processingRequests[requestId] = processingState.copy(
                    status = ProcessingStatus.REJECTING
                )
                
                // 2. 调用CosRequestManager拒绝请求
                val rejectResult = cosRequestManager.rejectCosRequest(
                    processingState.senderId,
                    requestId,
                    rejectionReason
                ).get()
                
                // 3. 取消通知
                notificationManager.cancelNotification(requestId)
                
                // 4. 清理处理状态
                processingRequests.remove(requestId)
                
                Log.i(TAG, "COS请求拒绝完成: requestId=$requestId")
                rejectResult
                
            } catch (e: Exception) {
                Log.e(TAG, "拒绝COS请求时发生异常", e)
                processingRequests.remove(requestId)
                CosRequestResult.Failure("拒绝请求时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 处理接收到的COS响应消息
     */
    fun handleIncomingCosResponse(
        senderId: String,
        responseMessage: CosSignalMessage.Response
    ): CosMessageProcessResult {
        Log.i(TAG, "处理传入的COS响应: senderId=$senderId, requestId=${responseMessage.cosResponse.requestId}")
        
        try {
            // 1. 使用CosRequestManager处理响应
            val processResult = cosRequestManager.handleReceivedResponse(senderId, responseMessage)
            
            // 2. 显示响应通知
            notificationManager.showResponseNotification(senderId, responseMessage.cosResponse)
            
            return processResult
            
        } catch (e: Exception) {
            Log.e(TAG, "处理COS响应时发生异常", e)
            return CosMessageProcessResult.failure(
                responseMessage.messageId,
                "处理响应时发生异常: ${e.message}"
            )
        }
    }
    
    /**
     * 处理接收到的COS撤销消息
     */
    fun handleIncomingCosRevocation(
        senderId: String,
        revocationMessage: CosSignalMessage.Revocation
    ): CosMessageProcessResult {
        Log.i(TAG, "处理传入的COS撤销: senderId=$senderId, requestId=${revocationMessage.cosRevocation.requestId}")
        
        try {
            // 1. 使用CosRequestManager处理撤销
            val processResult = cosRequestManager.handleReceivedRevocation(senderId, revocationMessage)
            
            // 2. 显示撤销通知
            notificationManager.showRevocationNotification(senderId, revocationMessage.cosRevocation)
            
            // 3. 清理相关的处理状态
            processingRequests.remove(revocationMessage.cosRevocation.requestId)
            
            return processResult
            
        } catch (e: Exception) {
            Log.e(TAG, "处理COS撤销时发生异常", e)
            return CosMessageProcessResult.failure(
                revocationMessage.messageId,
                "处理撤销时发生异常: ${e.message}"
            )
        }
    }
    
    /**
     * 获取当前处理中的请求
     */
    fun getProcessingRequests(): List<RequestProcessingState> {
        return processingRequests.values.toList()
    }
    
    /**
     * 清理过期的处理状态
     */
    fun cleanupExpiredProcessingStates() {
        val now = System.currentTimeMillis()
        val expiredThreshold = 24 * 60 * 60 * 1000L // 24小时
        
        val expiredRequests = processingRequests.values.filter { state ->
            now - state.timestamp > expiredThreshold
        }
        
        expiredRequests.forEach { state ->
            processingRequests.remove(state.requestId)
            notificationManager.cancelNotification(state.requestId)
        }
        
        if (expiredRequests.isNotEmpty()) {
            Log.i(TAG, "清理了 ${expiredRequests.size} 个过期的处理状态")
        }
    }
}

/**
 * 请求处理状态
 */
data class RequestProcessingState(
    val requestId: String,
    val senderId: String,
    val status: ProcessingStatus,
    val timestamp: Long
)

/**
 * 处理状态枚举
 */
enum class ProcessingStatus {
    RECEIVED,               // 已接收
    AWAITING_USER_ACTION,   // 等待用户操作
    ACCEPTING,              // 接受中
    REJECTING,              // 拒绝中
    COMPLETED,              // 已完成
    FAILED                  // 失败
}
