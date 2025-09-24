/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.content.Intent
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
import org.thoughtcrime.securesms.components.settings.models.InlineTextInput
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter

/**
 * Tap配置页面
 * 
 * 根据用户需求实现的功能：
 * 1. 如果有配置，直接显示存储的配置
 * 2. 如果没有配置，显示Provider选择框（动态生成，不硬编码）
 * 3. 选择Provider后根据配置字段生成UI界面
 * 4. 测试功能：填写必需项后显示测试按钮，显示测试结果状态
 * 5. 符合Signal原生UI风格
 */
class TapConfigFragment : DSLSettingsFragment(
    titleId = R.string.TapConfigFragment__tap_config
) {

    private val viewModel: TapConfigViewModel by viewModels()

    override fun bindAdapter(adapter: MappingAdapter) {
        // 注册内联文本输入组件
        InlineTextInput.register(adapter)
        // 注册多选组件
        MultiSelectModel.register(adapter)
        // 注册文件路径组件
        FilePathModel.register(adapter)

        viewModel.state.observe(viewLifecycleOwner) { state ->
            adapter.submitList(getConfiguration(state).toMappingModelList())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initialize(requireContext())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // 处理文件选择结果
        if (requestCode == FilePathConfigView.FILE_PICKER_REQUEST_CODE) {
            // 遍历当前显示的所有FilePathConfigView，让它们处理结果
            view?.let { rootView ->
                findFilePathViews(rootView).forEach { filePathView ->
                    filePathView.handleActivityResult(requestCode, resultCode, data)
                }
            }
        }
    }

    /**
     * 递归查找所有FilePathConfigView
     */
    private fun findFilePathViews(view: View): List<FilePathConfigView> {
        val filePathViews = mutableListOf<FilePathConfigView>()
        
        if (view is FilePathConfigView) {
            filePathViews.add(view)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                filePathViews.addAll(findFilePathViews(view.getChildAt(i)))
            }
        }
        
        return filePathViews
    }

    private fun getConfiguration(state: TapConfigState): DSLConfiguration {
        return configure {
            when {
                state.isLoading -> {
                    // 加载中状态
                    sectionHeaderPref(R.string.TapConfigFragment__loading)
                    textPref(
                        title = DSLSettingsText.from("正在加载配置..."),
                        summary = DSLSettingsText.from("请稍候")
                    )
                }
                
                state.selectedProviderType == null -> {
                    // 显示Provider选择界面
                    showProviderSelectionUI(state)
                }
                
                else -> {
                    // 显示具体Provider配置界面
                    showProviderConfigUI(state)
                }
            }
        }
    }

    /**
     * 显示Provider选择界面
     */
    private fun DSLConfiguration.showProviderSelectionUI(state: TapConfigState) {
        sectionHeaderPref(DSLSettingsText.from(getString(R.string.TapConfigFragment__choose_transport_provider)))
        
        textPref(
            title = DSLSettingsText.from(""),
            summary = DSLSettingsText.from(getString(R.string.TapConfigFragment__provider_selection_help))
        )
        
        dividerPref()

        if (state.availableProviders.isEmpty()) {
            textPref(
                title = DSLSettingsText.from(getString(R.string.TapConfigFragment__no_providers_available)),
                summary = DSLSettingsText.from(getString(R.string.TapConfigFragment__no_providers_summary))
            )
        } else {
            state.availableProviders.forEach { provider ->
                clickPref(
                    title = DSLSettingsText.from(provider.displayName),
                    summary = DSLSettingsText.from(provider.description),
                    onClick = {
                        viewModel.selectProvider(provider.type)
                    }
                )
            }
        }
    }

    /**
     * 显示Provider配置界面
     */
    private fun DSLConfiguration.showProviderConfigUI(state: TapConfigState) {
        val descriptor = state.currentProviderDescriptor ?: return
        
        // 页面标题和描述
        sectionHeaderPref(DSLSettingsText.from(descriptor.displayName))
        
        textPref(
            title = DSLSettingsText.from(""),
            summary = DSLSettingsText.from(descriptor.description)
        )

        // 如果有现有配置，显示切换Provider的选项
        if (state.hasExistingConfig) {
            dividerPref()
            clickPref(
                title = DSLSettingsText.from(getString(R.string.TapConfigFragment__switch_provider)),
                summary = DSLSettingsText.from(getString(R.string.TapConfigFragment__switch_provider_summary)),
                onClick = {
                    showSwitchProviderConfirmDialog()
                }
            )
        }

        dividerPref()
        
        // 动态生成配置字段UI
        val configFields = descriptor.getConfigFields()
        val fieldConfigs = ConfigFieldViewCreator.createFieldConfigs(
            fields = configFields,
            values = state.configValues,
            onValueChanged = { key, value ->
                viewModel.updateConfigValue(key, value)
            },
            fragment = this@TapConfigFragment
        )
        
        fieldConfigs.forEach { fieldConfig ->
            fieldConfig()
        }

        // 测试按钮（如果支持）
        if (descriptor.supportsConfigTest) {
            ConfigTestButtonCreator.createTestButton(
                context = requireContext(),
                state = state.testState,
                testResult = state.testResult,
                enabled = state.isConfigValid,
                onClick = {
                    viewModel.testConfig()
                }
            ).invoke(this)
        }

        // 保存按钮
        ConfigTestButtonCreator.createSaveButton(
            context = requireContext(),
            enabled = state.isConfigValid,
            onClick = {
                val success = viewModel.saveConfig()
                if (success) {
                    Toast.makeText(requireContext(), getString(R.string.TapConfigFragment__save_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.TapConfigFragment__save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        ).invoke(this)

        // 删除配置按钮（仅在有现有配置时显示）
        if (state.hasExistingConfig) {
            ConfigTestButtonCreator.createDeleteButton(
                context = requireContext()
            ) {
                showDeleteConfigConfirmDialog()
            }.invoke(this)
        }
    }

    /**
     * 获取Provider图标
     */
    private fun getProviderIcon(providerType: String): Int {
        return when (providerType) {
            "cos" -> R.drawable.symbol_data_bold_24
            "email" -> R.drawable.symbol_at_24
            "ipfs" -> R.drawable.symbol_folder_24
            else -> R.drawable.symbol_settings_android_24
        }
    }

    /**
     * 显示切换Provider确认对话框
     */
    private fun showSwitchProviderConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.TapConfigFragment__switch_provider_dialog_title))
            .setMessage(getString(R.string.TapConfigFragment__switch_provider_dialog_message))
            .setPositiveButton(getString(R.string.TapConfigFragment__confirm)) { _, _ ->
                viewModel.backToProviderSelection()
            }
            .setNegativeButton(getString(R.string.TapConfigFragment__cancel), null)
            .show()
    }

    /**
     * 显示删除配置确认对话框
     */
    private fun showDeleteConfigConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.TapConfigFragment__delete_config_dialog_title))
            .setMessage(getString(R.string.TapConfigFragment__delete_config_dialog_message))
            .setPositiveButton(getString(R.string.TapConfigFragment__delete)) { _, _ ->
                val success = viewModel.deleteConfig()
                if (success) {
                    Toast.makeText(requireContext(), getString(R.string.TapConfigFragment__delete_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.TapConfigFragment__delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.TapConfigFragment__cancel), null)
            .show()
    }
} 