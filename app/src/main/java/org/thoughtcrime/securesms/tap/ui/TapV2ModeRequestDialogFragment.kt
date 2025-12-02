package org.thoughtcrime.securesms.tap.ui

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.integration.TapV2ModeRequestHandler
import org.thoughtcrime.securesms.util.JsonUtils

/**
 * Tap v2 mode 请求确认对话框
 */
class TapV2ModeRequestDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "TapV2ModeRequestDialog"
        private const val ARG_SENDER_ID = "sender_id"
        private const val ARG_SENDER_NAME = "sender_name"
        private const val ARG_TOKEN_EXCHANGE_MESSAGE = "token_exchange_message"

        fun newInstance(
            senderId: String,
            senderName: String,
            tokenExchangeMessageJson: String
        ): TapV2ModeRequestDialogFragment {
            return TapV2ModeRequestDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SENDER_ID, senderId)
                    putString(ARG_SENDER_NAME, senderName)
                    putString(ARG_TOKEN_EXCHANGE_MESSAGE, tokenExchangeMessageJson)
                }
            }
        }
    }

    private var senderId: String? = null
    private var senderName: String? = null
    private var tokenExchangeMessageJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            senderId = it.getString(ARG_SENDER_ID)
            senderName = it.getString(ARG_SENDER_NAME)
            tokenExchangeMessageJson = it.getString(ARG_TOKEN_EXCHANGE_MESSAGE)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val name = senderName ?: "Unknown"
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.TapV2ModeRequest_title)
            .setMessage(getString(R.string.TapV2ModeRequest_message, name))
            .setPositiveButton(R.string.TapV2ModeRequest_accept) { _, _ ->
                handleAccept()
            }
            .setNegativeButton(R.string.TapV2ModeRequest_reject) { _, _ ->
                handleReject()
            }
            .setCancelable(false)
            .create()
    }

    private fun handleAccept() {
        val context = context ?: return
        val id = senderId ?: return
        val messageJson = tokenExchangeMessageJson ?: return

        Log.i(TAG, "User accepted Tap v2 mode request: senderId=$id")

        CoroutineScope(Dispatchers.IO).launch {
            TapV2ModeRequestHandler.handleAccept(context, id, messageJson)
        }

        dismissAndFinish()
    }

    private fun handleReject() {
        val context = context ?: return
        val id = senderId ?: return
        val messageJson = tokenExchangeMessageJson ?: return

        Log.i(TAG, "User rejected Tap v2 mode request: senderId=$id")

        CoroutineScope(Dispatchers.IO).launch {
            TapV2ModeRequestHandler.handleReject(context, id, messageJson)
        }

        dismissAndFinish()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        dismissAndFinish()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        finishActivity()
    }

    private fun dismissAndFinish() {
        dismissAllowingStateLoss()
        finishActivity()
    }

    private fun finishActivity() {
        activity?.finish()
    }
}
