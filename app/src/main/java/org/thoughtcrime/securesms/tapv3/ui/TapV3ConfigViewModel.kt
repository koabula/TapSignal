package org.thoughtcrime.securesms.tapv3.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.tapv3.TapV3Manager

class TapV3ConfigViewModel : ViewModel() {

    private val _state = MutableLiveData<TapV3ConfigState>()
    val state: LiveData<TapV3ConfigState> = _state

    private lateinit var tapV3Manager: TapV3Manager

    fun initialize(context: Context) {
        tapV3Manager = TapV3Manager.getInstance(context)
        loadConfiguration()
    }

    private fun loadConfiguration() {
        _state.value = TapV3ConfigState(isLoading = true)

        viewModelScope.launch {
            val pinataApiKey = SignalStore.tapV3.getStringValue(KEY_PINATA_API_KEY, "") ?: ""
            val pinataApiSecret = SignalStore.tapV3.getStringValue(KEY_PINATA_API_SECRET, "") ?: ""
            val web3StorageToken = SignalStore.tapV3.getStringValue(KEY_WEB3_STORAGE_TOKEN, "") ?: ""

            val isPushRegistered = tapV3Manager.pushProvider.isRegistered()
            val myPushEndpoint = tapV3Manager.pushEndpointManager.getMyEndpoint()

            _state.value = TapV3ConfigState(
                isLoading = false,
                pinataApiKey = pinataApiKey,
                pinataApiSecret = pinataApiSecret,
                web3StorageToken = web3StorageToken,
                isPushRegistered = isPushRegistered,
                myPushEndpoint = myPushEndpoint
            )
        }
    }

    fun updatePinataApiKey(apiKey: String) {
        _state.value = _state.value?.copy(pinataApiKey = apiKey)
    }

    fun updatePinataApiSecret(apiSecret: String) {
        _state.value = _state.value?.copy(pinataApiSecret = apiSecret)
    }

    fun updateWeb3StorageToken(token: String) {
        _state.value = _state.value?.copy(web3StorageToken = token)
    }

    fun registerPush() {
        viewModelScope.launch {
            tapV3Manager.registerPush(
                onSuccess = { endpoint ->
                    Log.d(TAG, "Push registered successfully: $endpoint")
                    _state.value = _state.value?.copy(
                        isPushRegistered = true,
                        myPushEndpoint = endpoint
                    )
                },
                onError = { error ->
                    Log.e(TAG, "Push registration failed: $error")
                }
            )
        }
    }

    fun unregisterPush() {
        viewModelScope.launch {
            tapV3Manager.unregisterPush()
            _state.value = _state.value?.copy(
                isPushRegistered = false,
                myPushEndpoint = null
            )
        }
    }

    fun testConfiguration() {
        val currentState = _state.value ?: return

        _state.value = currentState.copy(testState = TestState.TESTING, testError = null)

        viewModelScope.launch {
            try {
                val testData = "Tap v3 test".toByteArray()

                val pinataConfigured = currentState.pinataApiKey.isNotEmpty() && currentState.pinataApiSecret.isNotEmpty()
                val web3Configured = currentState.web3StorageToken.isNotEmpty()

                if (pinataConfigured) {
                    tapV3Manager.configurePinata(currentState.pinataApiKey, currentState.pinataApiSecret)
                }

                if (web3Configured) {
                    tapV3Manager.configureWeb3Storage(currentState.web3StorageToken)
                }

                val uploadResult = withContext(Dispatchers.IO) {
                    tapV3Manager.ipfsGatewayManager.upload(testData)
                }

                if (uploadResult.isSuccess()) {
                    val cid = uploadResult.getOrNull()!!
                    Log.d(TAG, "Test upload successful: $cid")

                    val downloadResult = withContext(Dispatchers.IO) {
                        tapV3Manager.ipfsGatewayManager.download(cid)
                    }

                    if (downloadResult.isSuccess()) {
                        val downloadedData = downloadResult.getOrNull()!!
                        if (testData.contentEquals(downloadedData)) {
                            _state.value = currentState.copy(testState = TestState.SUCCESS, testError = null)
                            Log.d(TAG, "Test successful: upload and download verified")
                        } else {
                            _state.value = currentState.copy(
                                testState = TestState.FAILED,
                                testError = "Downloaded data does not match uploaded data"
                            )
                        }
                    } else {
                        _state.value = currentState.copy(
                            testState = TestState.FAILED,
                            testError = "Download failed: ${(downloadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Failure).message}"
                        )
                    }
                } else {
                    _state.value = currentState.copy(
                        testState = TestState.FAILED,
                        testError = "Upload failed: ${(uploadResult as org.thoughtcrime.securesms.tapv3.TapV3Result.Failure).message}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Test failed with exception", e)
                _state.value = currentState.copy(
                    testState = TestState.FAILED,
                    testError = "Exception: ${e.message}"
                )
            }
        }
    }

    fun saveConfiguration(): Boolean {
        val currentState = _state.value ?: return false

        try {
            if (currentState.pinataApiKey.isNotEmpty() && currentState.pinataApiSecret.isNotEmpty()) {
                SignalStore.tapV3.putStringValue(KEY_PINATA_API_KEY, currentState.pinataApiKey)
                SignalStore.tapV3.putStringValue(KEY_PINATA_API_SECRET, currentState.pinataApiSecret)
                tapV3Manager.configurePinata(currentState.pinataApiKey, currentState.pinataApiSecret)
            }

            if (currentState.web3StorageToken.isNotEmpty()) {
                SignalStore.tapV3.putStringValue(KEY_WEB3_STORAGE_TOKEN, currentState.web3StorageToken)
                tapV3Manager.configureWeb3Storage(currentState.web3StorageToken)
            }

            Log.d(TAG, "Configuration saved successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save configuration", e)
            return false
        }
    }

    fun clearConfiguration() {
        SignalStore.tapV3.removeValue(KEY_PINATA_API_KEY)
        SignalStore.tapV3.removeValue(KEY_PINATA_API_SECRET)
        SignalStore.tapV3.removeValue(KEY_WEB3_STORAGE_TOKEN)

        tapV3Manager.clearConfiguration()

        _state.value = TapV3ConfigState(
            isLoading = false,
            isPushRegistered = _state.value?.isPushRegistered ?: false,
            myPushEndpoint = _state.value?.myPushEndpoint
        )

        Log.d(TAG, "Configuration cleared")
    }

    companion object {
        private val TAG = Log.tag(TapV3ConfigViewModel::class.java)

        private const val KEY_PINATA_API_KEY = "tapv3_pinata_api_key"
        private const val KEY_PINATA_API_SECRET = "tapv3_pinata_api_secret"
        private const val KEY_WEB3_STORAGE_TOKEN = "tapv3_web3storage_token"
    }
}
