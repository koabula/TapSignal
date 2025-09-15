package org.thoughtcrime.securesms.components.settings.app.cos

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.components.settings.models.InlineTextInput
import org.thoughtcrime.securesms.cos.CosConfig
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

/**
 * COS 配置页面
 */
class CosSettingsFragment : DSLSettingsFragment() {

    private val viewModel: CosSettingsViewModel by viewModels()

    override fun bindAdapter(adapter: MappingAdapter) {
        // 注册内联文本输入组件
        InlineTextInput.register(adapter)
        
        viewModel.state.observe(viewLifecycleOwner) { state ->
            adapter.submitList(getConfiguration(state).toMappingModelList())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.loadCurrentConfig(requireContext())
    }

    private fun getConfiguration(state: CosSettingsState): DSLConfiguration {
        return configure {
            sectionHeaderPref(R.string.CosSettingsFragment__cos_settings)

            radioListPref(
                title = DSLSettingsText.from(R.string.CosSettingsFragment__provider),
                listItems = arrayOf(
                    getString(R.string.CosSettingsFragment__aws_s3),
                    getString(R.string.CosSettingsFragment__tencent_cos)
                ),
                selected = if (state.provider == CosConfig.Provider.AWS) 0 else 1,
                onSelected = { viewModel.setProvider(if (it == 0) CosConfig.Provider.AWS else CosConfig.Provider.TENCENT) }
            )

            customPref(
                InlineTextInput.Model(
                    title = DSLSettingsText.from(R.string.CosSettingsFragment__secret_id),
                    value = state.secretId,
                    hint = DSLSettingsText.from(R.string.CosSettingsFragment__enter_secret_id),
                    onValueChanged = { viewModel.updateSecretId(it) }
                )
            )

            customPref(
                InlineTextInput.Model(
                    title = DSLSettingsText.from(R.string.CosSettingsFragment__secret_key),
                    value = state.secretKey,
                    hint = DSLSettingsText.from(R.string.CosSettingsFragment__enter_secret_key),
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    onValueChanged = { viewModel.updateSecretKey(it) }
                )
            )

            customPref(
                InlineTextInput.Model(
                    title = DSLSettingsText.from(R.string.CosSettingsFragment__region),
                    value = state.region,
                    hint = DSLSettingsText.from(R.string.CosSettingsFragment__enter_region),
                    onValueChanged = { viewModel.updateRegion(it) }
                )
            )

            customPref(
                InlineTextInput.Model(
                    title = DSLSettingsText.from(R.string.CosSettingsFragment__bucket_name),
                    value = state.bucketName,
                    hint = DSLSettingsText.from(R.string.CosSettingsFragment__enter_bucket_name),
                    onValueChanged = { viewModel.updateBucketName(it) }
                )
            )

            dividerPref()

            // 添加说明文本：现在使用长期CAM凭证
            textPref(
                title = DSLSettingsText.from(R.string.CosSettingsFragment__cam_info),
                summary = DSLSettingsText.from(R.string.CosSettingsFragment__cam_info_summary)
            )

            dividerPref()

            clickPref(
                title = DSLSettingsText.from(R.string.CosSettingsFragment__save),
                onClick = {
                    val success = viewModel.saveConfig(requireContext())
                    Toast.makeText(
                        requireContext(),
                        if (success) R.string.CosSettingsFragment__saved else R.string.CosSettingsFragment__error_saving,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
} 