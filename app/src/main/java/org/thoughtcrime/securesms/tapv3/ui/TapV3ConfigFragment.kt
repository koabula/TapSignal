package org.thoughtcrime.securesms.tapv3.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

class TapV3ConfigFragment : DSLSettingsFragment(
    titleId = R.string.TapV3ConfigFragment__tap_v3_config
) {

    private val viewModel: TapV3ConfigViewModel by viewModels()

    override fun bindAdapter(adapter: MappingAdapter) {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            adapter.submitList(getConfiguration(state).toMappingModelList())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(requireContext())
    }

    private fun getConfiguration(state: TapV3ConfigState): DSLConfiguration {
        return configure {
            when {
                state.isLoading -> {
                    sectionHeaderPref(R.string.TapV3ConfigFragment__loading)
                    textPref(
                        title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__loading_config)),
                        summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__please_wait))
                    )
                }
                
                else -> {
                    showConfigUI(state)
                }
            }
        }
    }

    private fun DSLConfiguration.showConfigUI(state: TapV3ConfigState) {
        sectionHeaderPref(DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__ipfs_gateway_config)))
        
        textPref(
            title = DSLSettingsText.from(""),
            summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__ipfs_help_text))
        )

        dividerPref()

        clickPref(
            title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__pinata_api_key)),
            summary = when {
                state.pinataApiKey.isNotEmpty() -> DSLSettingsText.from(maskApiKey(state.pinataApiKey))
                else -> DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__optional))
            },
            onClick = {
                showTextInputDialog(
                    getString(R.string.TapV3ConfigFragment__pinata_api_key),
                    state.pinataApiKey
                ) { newValue: String ->
                    viewModel.updatePinataApiKey(newValue)
                }
            }
        )

        clickPref(
            title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__pinata_api_secret)),
            summary = when {
                state.pinataApiSecret.isNotEmpty() -> DSLSettingsText.from(maskApiKey(state.pinataApiSecret))
                else -> DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__optional))
            },
            onClick = {
                showTextInputDialog(
                    getString(R.string.TapV3ConfigFragment__pinata_api_secret),
                    state.pinataApiSecret
                ) { newValue: String ->
                    viewModel.updatePinataApiSecret(newValue)
                }
            }
        )

        dividerPref()

        clickPref(
            title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__web3storage_token)),
            summary = when {
                state.web3StorageToken.isNotEmpty() -> DSLSettingsText.from(maskApiKey(state.web3StorageToken))
                else -> DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__optional))
            },
            onClick = {
                showTextInputDialog(
                    getString(R.string.TapV3ConfigFragment__web3storage_token),
                    state.web3StorageToken
                ) { newValue: String ->
                    viewModel.updateWeb3StorageToken(newValue)
                }
            }
        )

        dividerPref()

        sectionHeaderPref(DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__unifiedpush_config)))

        textPref(
            title = DSLSettingsText.from(""),
            summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__unifiedpush_help_text))
        )

        dividerPref()

        val pushStatusText = when {
            state.isPushRegistered -> getString(R.string.TapV3ConfigFragment__registered)
            else -> getString(R.string.TapV3ConfigFragment__not_registered)
        }

        val pushStatusColor = when {
            state.isPushRegistered -> R.color.signal_colorPrimary
            else -> R.color.signal_text_secondary
        }

        textPref(
            title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__push_status)),
            summary = DSLSettingsText.from(
                pushStatusText,
                DSLSettingsText.ColorModifier(requireContext().getColor(pushStatusColor))
            )
        )

        if (state.myPushEndpoint != null) {
            textPref(
                title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__my_endpoint)),
                summary = DSLSettingsText.from(state.myPushEndpoint)
            )
        }

        dividerPref()

        if (!state.isPushRegistered) {
            clickPref(
                title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__register_push)),
                summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__register_push_summary)),
                onClick = {
                    viewModel.registerPush()
                }
            )
        } else {
            clickPref(
                title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__unregister_push)),
                summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__unregister_push_summary)),
                onClick = {
                    showUnregisterPushConfirmDialog()
                }
            )
        }

        dividerPref()

        sectionHeaderPref(DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__actions)))

        if (state.hasAnyGatewayConfigured) {
            clickPref(
                title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__test_config)),
                summary = DSLSettingsText.from(
                    when (state.testState) {
                        TestState.IDLE -> getString(R.string.TapV3ConfigFragment__test_config_summary)
                        TestState.TESTING -> getString(R.string.TapV3ConfigFragment__testing)
                        TestState.SUCCESS -> getString(R.string.TapV3ConfigFragment__test_success)
                        TestState.FAILED -> getString(R.string.TapV3ConfigFragment__test_failed)
                    }
                ),
                isEnabled = state.testState != TestState.TESTING,
                onClick = {
                    viewModel.testConfiguration()
                }
            )

            if (state.testState == TestState.FAILED && state.testError != null) {
                textPref(
                    title = DSLSettingsText.from(""),
                    summary = DSLSettingsText.from(
                        state.testError,
                        DSLSettingsText.ColorModifier(requireContext().getColor(R.color.signal_colorError))
                    )
                )
            }

            dividerPref()
        }

        clickPref(
            title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__save_config)),
            summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__save_config_summary)),
            isEnabled = state.hasAnyGatewayConfigured,
            onClick = {
                val success = viewModel.saveConfiguration()
                if (success) {
                    Toast.makeText(requireContext(), getString(R.string.TapV3ConfigFragment__save_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.TapV3ConfigFragment__save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        )

        if (state.hasAnyGatewayConfigured) {
            dividerPref()

            clickPref(
                title = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__clear_config)),
                summary = DSLSettingsText.from(getString(R.string.TapV3ConfigFragment__clear_config_summary)),
                onClick = {
                    showClearConfigConfirmDialog()
                }
            )
        }
    }

    private fun maskApiKey(key: String): String {
        return when {
            key.length <= 8 -> "*".repeat(key.length)
            else -> "${key.take(4)}${"*".repeat(key.length - 8)}${key.takeLast(4)}"
        }
    }

    private fun showUnregisterPushConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.TapV3ConfigFragment__unregister_push_dialog_title))
            .setMessage(getString(R.string.TapV3ConfigFragment__unregister_push_dialog_message))
            .setPositiveButton(getString(R.string.TapV3ConfigFragment__confirm)) { _, _ ->
                viewModel.unregisterPush()
            }
            .setNegativeButton(getString(R.string.TapV3ConfigFragment__cancel), null)
            .show()
    }

    private fun showClearConfigConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.TapV3ConfigFragment__clear_config_dialog_title))
            .setMessage(getString(R.string.TapV3ConfigFragment__clear_config_dialog_message))
            .setPositiveButton(getString(R.string.TapV3ConfigFragment__clear)) { _, _ ->
                viewModel.clearConfiguration()
                Toast.makeText(requireContext(), getString(R.string.TapV3ConfigFragment__clear_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.TapV3ConfigFragment__cancel), null)
            .show()
    }

    private fun showTextInputDialog(
        title: String,
        initialValue: String,
        onValueSet: (String) -> Unit
    ) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(initialValue)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val padding = resources.getDimensionPixelSize(R.dimen.dsl_settings_gutter)
            setPadding(padding, padding, padding, padding)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onValueSet(editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
