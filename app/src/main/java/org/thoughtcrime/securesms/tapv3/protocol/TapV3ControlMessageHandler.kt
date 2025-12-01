package org.thoughtcrime.securesms.tapv3.protocol

import android.content.Context
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Result

class TapV3ControlMessageHandler private constructor(
    private val context: Context
) {
    
    private val handshakeManager = TapV3HandshakeManager.getInstance(context)
    
    fun handleControlMessage(
        messageBody: String,
        senderId: String
    ): TapV3Result<Unit> {
        return when {
            messageBody.startsWith("TAP_V3_REQ:") -> {
                handleHandshakeRequest(messageBody.substring(11), senderId)
            }
            messageBody.startsWith("TAP_V3_RESP:") -> {
                handleHandshakeResponse(messageBody.substring(12), senderId)
            }
            messageBody.startsWith("TAP_V3_ACK:") -> {
                handleHandshakeAck(messageBody.substring(11), senderId)
            }
            messageBody.startsWith("TAP_V3_KEY_ROTATION:") -> {
                handleKeyRotation(messageBody.substring(20), senderId)
            }
            messageBody.startsWith("TAP_V3_CLOSE:") -> {
                handleChannelClose(messageBody.substring(13), senderId)
            }
            else -> {
                Log.w(TAG, "Unknown Tap v3 control message type: ${messageBody.take(20)}")
                TapV3Result.Success(Unit)
            }
        }
    }
    
    private fun handleHandshakeRequest(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeRequest) {
                Log.e(TAG, "Expected HandshakeRequest but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake request from ${senderId.take(8)}...")
            val result = handshakeManager.handleHandshakeRequest(senderId, message)
            
            if (result.isFailure()) {
                Log.e(TAG, "Failed to handle handshake request: ${(result as TapV3Result.Failure).message}")
            } else {
                Log.i(TAG, "Successfully handled handshake request")
            }
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake request", e)
            TapV3Result.Success(Unit)
        }
    }
    
    private fun handleHandshakeResponse(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeResponse) {
                Log.e(TAG, "Expected HandshakeResponse but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake response from ${senderId.take(8)}...")
            val result = handshakeManager.handleHandshakeResponse(senderId, message)
            
            if (result.isFailure()) {
                Log.e(TAG, "Failed to handle handshake response: ${(result as TapV3Result.Failure).message}")
            } else {
                Log.i(TAG, "Successfully handled handshake response")
            }
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake response", e)
            TapV3Result.Success(Unit)
        }
    }
    
    private fun handleHandshakeAck(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        return try {
            val serialized = Base64.decode(base64Data, Base64.NO_WRAP)
            val message = TapV3ControlMessage.deserialize(serialized)
            
            if (message !is TapV3ControlMessage.HandshakeAck) {
                Log.e(TAG, "Expected HandshakeAck but got ${message::class.simpleName}")
                return TapV3Result.Success(Unit)
            }
            
            Log.i(TAG, "Received handshake ack from ${senderId.take(8)}...")
            val result = handshakeManager.handleHandshakeAck(senderId, message)
            
            if (result.isFailure()) {
                Log.e(TAG, "Failed to handle handshake ack: ${(result as TapV3Result.Failure).message}")
            } else {
                Log.i(TAG, "Successfully handled handshake ack, channel established")
            }
            
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling handshake ack", e)
            TapV3Result.Success(Unit)
        }
    }
    
    private fun handleKeyRotation(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        Log.d(TAG, "Key rotation received from ${senderId.take(8)}... (not yet implemented)")
        return TapV3Result.Success(Unit)
    }
    
    private fun handleChannelClose(
        base64Data: String,
        senderId: String
    ): TapV3Result<Unit> {
        Log.d(TAG, "Channel close received from ${senderId.take(8)}... (not yet implemented)")
        return TapV3Result.Success(Unit)
    }
    
    fun isControlMessage(messageBody: String): Boolean {
        return messageBody.startsWith("TAP_V3_REQ:") ||
               messageBody.startsWith("TAP_V3_RESP:") ||
               messageBody.startsWith("TAP_V3_ACK:") ||
               messageBody.startsWith("TAP_V3_KEY_ROTATION:") ||
               messageBody.startsWith("TAP_V3_CLOSE:")
    }
    
    companion object {
        private val TAG = Log.tag(TapV3ControlMessageHandler::class.java)
        
        @Volatile
        private var INSTANCE: TapV3ControlMessageHandler? = null
        
        fun getInstance(context: Context): TapV3ControlMessageHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapV3ControlMessageHandler(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
