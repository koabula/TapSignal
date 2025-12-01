package org.thoughtcrime.securesms.tapv3.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.integration.TapV3MessageRouter
import org.thoughtcrime.securesms.tapv3.protocol.TapV3HandshakeManager

class TapV3HandshakeViewModel : ViewModel() {

    private val _state = MutableLiveData<HandshakeState>()
    val state: LiveData<HandshakeState> = _state

    private lateinit var handshakeManager: TapV3HandshakeManager
    private lateinit var messageRouter: TapV3MessageRouter

    fun initialize(context: Context) {
        handshakeManager = TapV3HandshakeManager.getInstance(context)
        messageRouter = TapV3MessageRouter.getInstance(context)

        _state.value = HandshakeState(
            status = HandshakeStatus.IDLE,
            channelInfo = null,
            errorMessage = null
        )
    }

    fun initiateHandshake(recipientId: String) {
        _state.value = _state.value?.copy(status = HandshakeStatus.INITIATING, errorMessage = null)

        viewModelScope.launch {
            val result = handshakeManager.initiateHandshake(recipientId)

            when (result) {
                is TapV3Result.Success -> {
                    _state.value = _state.value?.copy(status = HandshakeStatus.WAITING_RESPONSE)
                    
                    pollHandshakeStatus(recipientId)
                }

                is TapV3Result.Failure -> {
                    Log.e(TAG, "Handshake initiation failed: ${result.message}")
                    _state.value = _state.value?.copy(
                        status = HandshakeStatus.FAILED,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    private fun pollHandshakeStatus(recipientId: String) {
        viewModelScope.launch {
            var attempts = 0
            val maxAttempts = 60

            while (attempts < maxAttempts) {
                kotlinx.coroutines.delay(1000)
                attempts++

                val handshakeState = handshakeManager.getHandshakeState(recipientId)

                if (handshakeState != null) {
                    when (handshakeState.state) {
                        TapV3HandshakeManager.HandshakeState.State.COMPLETING -> {
                            _state.value = _state.value?.copy(status = HandshakeStatus.COMPLETING)
                        }

                        TapV3HandshakeManager.HandshakeState.State.COMPLETED -> {
                            val channel = SignalDatabase.tapV3Channels.getChannel(recipientId)
                            
                            _state.value = _state.value?.copy(
                                status = HandshakeStatus.COMPLETED,
                                channelInfo = ChannelInfo(
                                    peerEndpoint = channel?.pushEndpoint ?: "Unknown"
                                )
                            )
                            break
                        }

                        TapV3HandshakeManager.HandshakeState.State.FAILED -> {
                            _state.value = _state.value?.copy(
                                status = HandshakeStatus.FAILED,
                                errorMessage = handshakeState.error ?: "Handshake failed"
                            )
                            break
                        }

                        else -> {
                        }
                    }
                }
            }

            if (attempts >= maxAttempts && _state.value?.status !in listOf(HandshakeStatus.COMPLETED, HandshakeStatus.FAILED)) {
                _state.value = _state.value?.copy(
                    status = HandshakeStatus.FAILED,
                    errorMessage = "Handshake timeout: no response from peer"
                )
            }
        }
    }

    companion object {
        private val TAG = Log.tag(TapV3HandshakeViewModel::class.java)
    }
}

data class HandshakeState(
    val status: HandshakeStatus,
    val channelInfo: ChannelInfo?,
    val errorMessage: String?
)

enum class HandshakeStatus {
    IDLE,
    INITIATING,
    WAITING_RESPONSE,
    COMPLETING,
    COMPLETED,
    FAILED
}

data class ChannelInfo(
    val peerEndpoint: String
)
