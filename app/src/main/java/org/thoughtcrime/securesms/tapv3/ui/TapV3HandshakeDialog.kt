package org.thoughtcrime.securesms.tapv3.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.databinding.TapV3HandshakeDialogBinding
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

class TapV3HandshakeDialog : BottomSheetDialogFragment() {

    private val binding by ViewBinderDelegate(TapV3HandshakeDialogBinding::bind)
    private val viewModel: TapV3HandshakeViewModel by viewModels()

    private lateinit var recipientId: RecipientId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        recipientId = requireArguments().getParcelable(ARG_RECIPIENT_ID)!!
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.tap_v3_handshake_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recipient = Recipient.resolved(recipientId)

        binding.recipientName.text = recipient.getDisplayName(requireContext())

        binding.initiateHandshakeButton.setOnClickListener {
            val recipient = Recipient.resolved(recipientId)
            val recipientAci = recipient.requireAci().toString()
            viewModel.initiateHandshake(recipientAci)
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            updateUI(state)
        }

        viewModel.initialize(requireContext())
    }

    private fun updateUI(state: HandshakeState) {
        when (state.status) {
            HandshakeStatus.IDLE -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__ready_to_initiate)
                binding.progressBar.visibility = View.GONE
                binding.initiateHandshakeButton.isEnabled = true
                binding.initiateHandshakeButton.text = getString(R.string.TapV3HandshakeDialog__initiate_handshake)
            }

            HandshakeStatus.INITIATING -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__initiating)
                binding.progressBar.visibility = View.VISIBLE
                binding.initiateHandshakeButton.isEnabled = false
            }

            HandshakeStatus.WAITING_RESPONSE -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__waiting_for_response)
                binding.progressBar.visibility = View.VISIBLE
                binding.initiateHandshakeButton.isEnabled = false
            }

            HandshakeStatus.COMPLETING -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__completing)
                binding.progressBar.visibility = View.VISIBLE
                binding.initiateHandshakeButton.isEnabled = false
            }

            HandshakeStatus.COMPLETED -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__handshake_successful)
                binding.progressBar.visibility = View.GONE
                binding.initiateHandshakeButton.isEnabled = false
                binding.initiateHandshakeButton.text = getString(R.string.TapV3HandshakeDialog__completed)
                binding.cancelButton.text = getString(R.string.TapV3HandshakeDialog__close)

                state.channelInfo?.let { info ->
                    binding.channelInfoContainer.visibility = View.VISIBLE
                    binding.peerEndpointText.text = info.peerEndpoint
                }
            }

            HandshakeStatus.FAILED -> {
                binding.statusText.text = getString(R.string.TapV3HandshakeDialog__handshake_failed)
                binding.progressBar.visibility = View.GONE
                binding.initiateHandshakeButton.isEnabled = true
                binding.initiateHandshakeButton.text = getString(R.string.TapV3HandshakeDialog__retry)

                state.errorMessage?.let { error ->
                    binding.errorContainer.visibility = View.VISIBLE
                    binding.errorText.text = error
                }
            }
        }
    }

    companion object {
        private val TAG = Log.tag(TapV3HandshakeDialog::class.java)
        private const val ARG_RECIPIENT_ID = "recipient_id"

        fun create(recipientId: RecipientId): TapV3HandshakeDialog {
            return TapV3HandshakeDialog().apply {
                arguments = bundleOf(ARG_RECIPIENT_ID to recipientId)
            }
        }
    }
}
