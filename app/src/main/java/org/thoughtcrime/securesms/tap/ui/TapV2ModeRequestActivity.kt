package org.thoughtcrime.securesms.tap.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage

/**
 * 显示 Tap v2 mode 请求对话框的 Activity
 * 使用透明主题，仅显示对话框
 */
class TapV2ModeRequestActivity : FragmentActivity() {

    companion object {
        private const val EXTRA_SENDER_ID = "sender_id"
        private const val EXTRA_SENDER_NAME = "sender_name"
        private const val EXTRA_TOKEN_EXCHANGE_MESSAGE = "token_exchange_message"

        fun createIntent(
            context: Context,
            senderId: String,
            senderName: String,
            tokenExchangeMessageJson: String
        ): Intent {
            return Intent(context, TapV2ModeRequestActivity::class.java).apply {
                putExtra(EXTRA_SENDER_ID, senderId)
                putExtra(EXTRA_SENDER_NAME, senderName)
                putExtra(EXTRA_TOKEN_EXCHANGE_MESSAGE, tokenExchangeMessageJson)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val senderId = intent.getStringExtra(EXTRA_SENDER_ID)
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME)
        val tokenExchangeMessageJson = intent.getStringExtra(EXTRA_TOKEN_EXCHANGE_MESSAGE)

        if (senderId == null || senderName == null || tokenExchangeMessageJson == null) {
            finish()
            return
        }

        if (savedInstanceState == null) {
            val fragment = TapV2ModeRequestDialogFragment.newInstance(
                senderId = senderId,
                senderName = senderName,
                tokenExchangeMessageJson = tokenExchangeMessageJson
            )
            fragment.show(supportFragmentManager, "tap_v2_mode_request_dialog")
        }
    }
}
