package org.thoughtcrime.securesms.tapv3.protocol

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.KPushManager
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.ipfs.IpfsGatewayManager
import org.thoughtcrime.securesms.tapv3.push.PushEndpointManager
import org.thoughtcrime.securesms.tapv3.push.UnifiedPushProvider
import org.thoughtcrime.securesms.tapv3.utils.TapV3Validator

/**
 * Tap v3 握手管理器
 * 
 * 密钥交换模型:
 * - 握手时，双方交换各自的 k_push（myKPush）
 * - A 发送自己的 k_push_A 给 B，B 存储为 peerKPush[A]
 * - B 发送自己的 k_push_B 给 A，A 存储为 peerKPush[B]
 * - 之后 A 给 B 发消息时用 k_push_B 加密，B 用自己的 myKPush 解密
 */
class TapV3HandshakeManager private constructor(
    private val context: Context
) {
    
    private val kPushManager = KPushManager.getInstance(context)
    private val pushEndpointManager = PushEndpointManager.getInstance(context)
    private val ipfsGatewayManager = IpfsGatewayManager.getInstance(context)
    private val channelTable = SignalDatabase.tapV3Channels
    private val controlMessageSender = TapV3ControlMessageSender.getInstance(context)
    
    data class HandshakeState(
        val recipientId: String,
        val state: State,
        val myInfo: TapV3HandshakeInfo?,
        val peerInfo: TapV3HandshakeInfo?,
        val error: String? = null
    ) {
        enum class State {
            IDLE,
            INITIATING,
            WAITING_RESPONSE,
            RESPONDING,
            COMPLETING,
            COMPLETED,
            FAILED
        }
    }
    
    private val handshakeStates = mutableMapOf<String, HandshakeState>()
    
    fun initiateHandshake(recipientId: String): TapV3Result<TapV3ControlMessage.HandshakeRequest> {
        synchronized(handshakeStates) {
            val currentState = handshakeStates[recipientId]
            if (currentState?.state in listOf(
                    HandshakeState.State.INITIATING,
                    HandshakeState.State.WAITING_RESPONSE,
                    HandshakeState.State.RESPONDING
                )) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Handshake already in progress for recipient: ${recipientId.take(8)}..."
                )
            }
            
            handshakeStates[recipientId] = HandshakeState(
                recipientId = recipientId,
                state = HandshakeState.State.INITIATING,
                myInfo = null,
                peerInfo = null
            )
        }
        
        try {
            val myEndpoint = pushEndpointManager.getMyEndpoint()
            if (myEndpoint == null) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "UnifiedPush endpoint not registered")
                return TapV3Result.Failure(
                    TapV3Error.PUSH_ERROR,
                    "UnifiedPush endpoint not registered. Please configure UnifiedPush first."
                )
            }
            
            if (!TapV3Validator.isValidEndpoint(myEndpoint)) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "Invalid endpoint")
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Invalid UnifiedPush endpoint: $myEndpoint"
                )
            }
            
            // 获取或创建自己的 myKPush
            val myKPush = kPushManager.getOrCreateMyKPush()
            
            val ipfsGateways: List<String> = getConfiguredGateways()
            
            if (ipfsGateways.isEmpty()) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "No IPFS gateway configured")
                return TapV3Result.Failure(
                    TapV3Error.IPFS_UPLOAD_ERROR,
                    "No IPFS gateway configured. Please configure at least one gateway."
                )
            }
            
            val handshakeInfo = TapV3HandshakeInfo(
                version = TapV3Constants.VERSION,
                unifiedPushEndpoint = myEndpoint,
                kPush = myKPush,
                keyVersion = kPushManager.getMyKPushVersion(),
                ipfsGateways = ipfsGateways,
                capabilities = setOf("inline", "ipfs", "multi-attachment")
            )
            
            synchronized(handshakeStates) {
                handshakeStates[recipientId] = HandshakeState(
                    recipientId = recipientId,
                    state = HandshakeState.State.WAITING_RESPONSE,
                    myInfo = handshakeInfo,
                    peerInfo = null
                )
            }
            
            // 初始化时先创建一个待定状态的通道记录
            channelTable.insertOrUpdate(
                recipientId = recipientId,
                status = TapV3ChannelTable.ChannelStatus.PENDING,
                pushEndpoint = "",
                kPush = ByteArray(32), // 临时占位
                keyVersion = TapV3Constants.KPUSH_KEY_VERSION_INITIAL,
                ipfsGateways = emptyList()
            )
            
            Log.d(TAG, "Initiated handshake for recipient: ${recipientId.take(8)}...")
            
            val request = TapV3ControlMessage.HandshakeRequest(
                handshakeInfo = handshakeInfo
            )
            
            val sendResult = controlMessageSender.sendHandshakeRequest(recipientId, request)
            if (sendResult.isFailure()) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "Failed to send handshake request")
                return TapV3Result.Failure(
                    (sendResult as TapV3Result.Failure).error,
                    sendResult.message,
                    sendResult.cause
                )
            }
            
            return TapV3Result.Success(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake", e)
            updateHandshakeState(recipientId, HandshakeState.State.FAILED, e.message)
            return TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Handshake initiation failed: ${e.message}",
                e
            )
        }
    }
    
    fun handleHandshakeRequest(
        recipientId: String,
        request: TapV3ControlMessage.HandshakeRequest
    ): TapV3Result<TapV3ControlMessage.HandshakeResponse> {
        synchronized(handshakeStates) {
            handshakeStates[recipientId] = HandshakeState(
                recipientId = recipientId,
                state = HandshakeState.State.RESPONDING,
                myInfo = null,
                peerInfo = request.handshakeInfo
            )
        }
        
        try {
            val peerInfo = request.handshakeInfo
            
            if (peerInfo.version != TapV3Constants.VERSION) {
                val errorMsg = "Unsupported version: ${peerInfo.version}, expected: ${TapV3Constants.VERSION}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Success(
                    TapV3ControlMessage.HandshakeResponse(
                        handshakeInfo = createMyHandshakeInfo(),
                        accepted = false,
                        reason = errorMsg
                    )
                )
            }
            
            if (!TapV3Validator.isValidEndpoint(peerInfo.unifiedPushEndpoint)) {
                val errorMsg = "Invalid peer endpoint: ${peerInfo.unifiedPushEndpoint}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Success(
                    TapV3ControlMessage.HandshakeResponse(
                        handshakeInfo = createMyHandshakeInfo(),
                        accepted = false,
                        reason = errorMsg
                    )
                )
            }
            
            if (!TapV3Validator.isValidKeySize(peerInfo.kPush)) {
                val errorMsg = "Invalid k_push size: ${peerInfo.kPush.size}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Success(
                    TapV3ControlMessage.HandshakeResponse(
                        handshakeInfo = createMyHandshakeInfo(),
                        accepted = false,
                        reason = errorMsg
                    )
                )
            }
            
            if (peerInfo.ipfsGateways.isEmpty()) {
                val errorMsg = "Peer has no IPFS gateways configured"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Success(
                    TapV3ControlMessage.HandshakeResponse(
                        handshakeInfo = createMyHandshakeInfo(),
                        accepted = false,
                        reason = errorMsg
                    )
                )
            }
            
            val myHandshakeInfo = createMyHandshakeInfo()
            
            // 保存对方的 k_push，用于给对方发消息时加密
            kPushManager.savePeerKPush(recipientId, peerInfo.kPush, peerInfo.keyVersion)
            pushEndpointManager.saveEndpoint(recipientId, peerInfo.unifiedPushEndpoint)
            
            // 通道表中存储对方的 k_push 和端点信息
            channelTable.insertOrUpdate(
                recipientId = recipientId,
                status = TapV3ChannelTable.ChannelStatus.ACTIVE,
                pushEndpoint = peerInfo.unifiedPushEndpoint,
                kPush = peerInfo.kPush,
                keyVersion = peerInfo.keyVersion,
                ipfsGateways = peerInfo.ipfsGateways
            )
            
            synchronized(handshakeStates) {
                handshakeStates[recipientId] = HandshakeState(
                    recipientId = recipientId,
                    state = HandshakeState.State.COMPLETING,
                    myInfo = myHandshakeInfo,
                    peerInfo = peerInfo
                )
            }
            
            Log.d(TAG, "Accepted handshake request from recipient: ${recipientId.take(8)}...")
            
            val response = TapV3ControlMessage.HandshakeResponse(
                handshakeInfo = myHandshakeInfo,
                accepted = true
            )
            
            val sendResult = controlMessageSender.sendHandshakeResponse(recipientId, response)
            if (sendResult.isFailure()) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "Failed to send handshake response")
                return TapV3Result.Failure(
                    (sendResult as TapV3Result.Failure).error,
                    sendResult.message,
                    sendResult.cause
                )
            }
            
            return TapV3Result.Success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle handshake request", e)
            updateHandshakeState(recipientId, HandshakeState.State.FAILED, e.message)
            return TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Failed to handle handshake request: ${e.message}",
                e
            )
        }
    }
    
    fun handleHandshakeResponse(
        recipientId: String,
        response: TapV3ControlMessage.HandshakeResponse
    ): TapV3Result<TapV3ControlMessage.HandshakeAck> {
        val currentState = synchronized(handshakeStates) {
            handshakeStates[recipientId]
        }
        
        if (currentState?.state != HandshakeState.State.WAITING_RESPONSE) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Not waiting for handshake response from recipient: ${recipientId.take(8)}..."
            )
        }
        
        try {
            if (!response.accepted) {
                val errorMsg = "Handshake rejected by peer: ${response.reason}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    errorMsg
                )
            }
            
            val peerInfo = response.handshakeInfo
            
            if (!TapV3Validator.isValidEndpoint(peerInfo.unifiedPushEndpoint)) {
                val errorMsg = "Invalid peer endpoint in response: ${peerInfo.unifiedPushEndpoint}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Failure(TapV3Error.INVALID_DATA, errorMsg)
            }
            
            if (!TapV3Validator.isValidKeySize(peerInfo.kPush)) {
                val errorMsg = "Invalid k_push size in response: ${peerInfo.kPush.size}"
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, errorMsg)
                return TapV3Result.Failure(TapV3Error.INVALID_DATA, errorMsg)
            }
            
            // 保存对方的 k_push，用于给对方发消息时加密
            kPushManager.savePeerKPush(recipientId, peerInfo.kPush, peerInfo.keyVersion)
            pushEndpointManager.saveEndpoint(recipientId, peerInfo.unifiedPushEndpoint)
            
            // 通道表中存储对方的 k_push 和端点信息
            channelTable.insertOrUpdate(
                recipientId = recipientId,
                status = TapV3ChannelTable.ChannelStatus.ACTIVE,
                pushEndpoint = peerInfo.unifiedPushEndpoint,
                kPush = peerInfo.kPush,
                keyVersion = peerInfo.keyVersion,
                ipfsGateways = peerInfo.ipfsGateways
            )
            
            synchronized(handshakeStates) {
                handshakeStates[recipientId] = HandshakeState(
                    recipientId = recipientId,
                    state = HandshakeState.State.COMPLETED,
                    myInfo = currentState.myInfo,
                    peerInfo = peerInfo
                )
            }
            
            Log.d(TAG, "Handshake completed successfully for recipient: ${recipientId.take(8)}...")
            
            val ack = TapV3ControlMessage.HandshakeAck(success = true)
            
            val sendResult = controlMessageSender.sendHandshakeAck(recipientId, ack)
            if (sendResult.isFailure()) {
                updateHandshakeState(recipientId, HandshakeState.State.FAILED, 
                    "Failed to send handshake ack")
                return TapV3Result.Failure(
                    (sendResult as TapV3Result.Failure).error,
                    sendResult.message,
                    sendResult.cause
                )
            }
            
            return TapV3Result.Success(ack)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle handshake response", e)
            updateHandshakeState(recipientId, HandshakeState.State.FAILED, e.message)
            return TapV3Result.Failure(
                TapV3Error.UNKNOWN_ERROR,
                "Failed to handle handshake response: ${e.message}",
                e
            )
        }
    }
    
    fun handleHandshakeAck(
        recipientId: String,
        ack: TapV3ControlMessage.HandshakeAck
    ): TapV3Result<Unit> {
        val currentState = synchronized(handshakeStates) {
            handshakeStates[recipientId]
        }
        
        if (currentState?.state != HandshakeState.State.COMPLETING) {
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Not waiting for handshake ack from recipient: ${recipientId.take(8)}..."
            )
        }
        
        if (!ack.success) {
            updateHandshakeState(recipientId, HandshakeState.State.FAILED, "Peer sent negative ack")
            return TapV3Result.Failure(
                TapV3Error.INVALID_DATA,
                "Handshake ack indicated failure"
            )
        }
        
        synchronized(handshakeStates) {
            handshakeStates[recipientId] = currentState.copy(
                state = HandshakeState.State.COMPLETED
            )
        }
        
        Log.d(TAG, "Handshake fully completed for recipient: ${recipientId.take(8)}...")
        
        return TapV3Result.Success(Unit)
    }
    
    fun getHandshakeState(recipientId: String): HandshakeState? {
        return synchronized(handshakeStates) {
            handshakeStates[recipientId]
        }
    }
    
    fun clearHandshakeState(recipientId: String) {
        synchronized(handshakeStates) {
            handshakeStates.remove(recipientId)
        }
        Log.d(TAG, "Cleared handshake state for recipient: ${recipientId.take(8)}...")
    }
    
    fun isHandshakeCompleted(recipientId: String): Boolean {
        val channel = channelTable.getChannel(recipientId)
        return channel?.status == TapV3ChannelTable.ChannelStatus.ACTIVE
    }
    
    fun createMyHandshakeInfo(): TapV3HandshakeInfo {
        val myEndpoint = pushEndpointManager.getMyEndpoint()
            ?: throw IllegalStateException("UnifiedPush endpoint not registered")
        
        // 获取或创建自己的 myKPush
        val myKPush = kPushManager.getOrCreateMyKPush()
        
        val ipfsGateways = getConfiguredGateways()
        
        return TapV3HandshakeInfo(
            version = TapV3Constants.VERSION,
            unifiedPushEndpoint = myEndpoint,
            kPush = myKPush,
            keyVersion = kPushManager.getMyKPushVersion(),
            ipfsGateways = ipfsGateways,
            capabilities = setOf("inline", "ipfs", "multi-attachment")
        )
    }
    
    private fun getConfiguredGateways(): List<String> {
        return listOf("pinata.cloud", "web3.storage")
    }
    
    private fun updateHandshakeState(recipientId: String, state: HandshakeState.State, error: String?) {
        synchronized(handshakeStates) {
            val current = handshakeStates[recipientId]
            if (current != null) {
                handshakeStates[recipientId] = current.copy(
                    state = state,
                    error = error
                )
            }
        }
    }
    
    companion object {
        private val TAG = Log.tag(TapV3HandshakeManager::class.java)
        
        @Volatile
        private var INSTANCE: TapV3HandshakeManager? = null
        
        fun getInstance(context: Context): TapV3HandshakeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3HandshakeManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
