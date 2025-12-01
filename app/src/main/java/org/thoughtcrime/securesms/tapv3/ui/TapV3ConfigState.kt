package org.thoughtcrime.securesms.tapv3.ui

data class TapV3ConfigState(
    val isLoading: Boolean = false,
    
    val pinataApiKey: String = "",
    val pinataApiSecret: String = "",
    val web3StorageToken: String = "",
    
    val isPushRegistered: Boolean = false,
    val myPushEndpoint: String? = null,
    
    val testState: TestState = TestState.IDLE,
    val testError: String? = null
) {
    val hasAnyGatewayConfigured: Boolean
        get() = (pinataApiKey.isNotEmpty() && pinataApiSecret.isNotEmpty()) || web3StorageToken.isNotEmpty()
}

enum class TestState {
    IDLE,
    TESTING,
    SUCCESS,
    FAILED
}
