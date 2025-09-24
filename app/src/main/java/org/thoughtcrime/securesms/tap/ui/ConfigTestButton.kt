/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.content.Context
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.tap.ConfigTestResult

/**
 * 配置测试状态
 */
enum class ConfigTestState {
    READY,      // 准备测试
    TESTING,    // 测试中
    SUCCESS,    // 测试成功  
    FAILED      // 测试失败
}

/**
 * 配置测试按钮创建器
 * 
 * 用于在DSL设置页面中创建测试按钮
 */
object ConfigTestButtonCreator {

    /**
     * 创建配置测试按钮
     */
    fun createTestButton(
        context: Context,
        state: ConfigTestState,
        testResult: ConfigTestResult?,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        val (titleRes, summaryText) = when (state) {
            ConfigTestState.READY -> {
                Pair(
                    if (enabled) context.getString(R.string.ConfigTestButton__test_config) 
                    else context.getString(R.string.ConfigTestButton__test_config_disabled),
                    context.getString(R.string.ConfigTestButton__test_config_click_hint)
                )
            }
            ConfigTestState.TESTING -> {
                Pair(
                    context.getString(R.string.ConfigTestButton__testing),
                    context.getString(R.string.ConfigTestButton__testing_progress)
                )
            }
            ConfigTestState.SUCCESS -> {
                Pair(
                    context.getString(R.string.ConfigTestButton__test_success),
                    (testResult as? ConfigTestResult.Success)?.message 
                        ?: context.getString(R.string.ConfigTestButton__test_success_default)
                )
            }
            ConfigTestState.FAILED -> {
                Pair(
                    context.getString(R.string.ConfigTestButton__test_failed),
                    (testResult as? ConfigTestResult.Failed)?.error 
                        ?: context.getString(R.string.ConfigTestButton__test_failed_default)
                )
            }
        }

        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(titleRes),
            summary = DSLSettingsText.from(
                summaryText, 
                when (state) {
                    ConfigTestState.SUCCESS -> DSLSettingsText.ColorModifier(context.getColor(R.color.signal_colorPrimary))
                    ConfigTestState.FAILED -> DSLSettingsText.ColorModifier(context.getColor(R.color.signal_colorError))
                    ConfigTestState.TESTING -> DSLSettingsText.ColorModifier(context.getColor(R.color.signal_accent_primary))
                    else -> DSLSettingsText.ColorModifier(context.getColor(R.color.signal_colorSecondary))
                }
            ),
            isEnabled = enabled && state != ConfigTestState.TESTING,
            onClick = if (enabled && state != ConfigTestState.TESTING) onClick else { -> }
        )
        
        // 如果有详细的错误信息，显示在额外的文本项中
        if (state == ConfigTestState.FAILED && testResult is ConfigTestResult.Failed) {
            val details = testResult.details?.takeIf { it.isNotEmpty() } ?: testResult.error
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(
                    "${context.getString(R.string.ConfigTestButton__details_prefix)}$details",
                    DSLSettingsText.ColorModifier(context.getColor(R.color.signal_colorSecondary))
                )
            )
        }
    }
    
    /**
     * 创建保存按钮
     */
    fun createSaveButton(
        context: Context,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(
                if (enabled) context.getString(R.string.ConfigTestButton__save_config) 
                else context.getString(R.string.ConfigTestButton__save_config_disabled)
            ),
            summary = DSLSettingsText.from(context.getString(R.string.ConfigTestButton__save_config_hint)),
            isEnabled = enabled,
            onClick = if (enabled) onClick else { -> }
        )
    }
    
    /**
     * 创建删除配置按钮
     */
    fun createDeleteButton(
        context: Context,
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(
                context.getString(R.string.ConfigTestButton__delete_config),
                DSLSettingsText.ColorModifier(context.getColor(R.color.signal_colorError))
            ),
            summary = DSLSettingsText.from(context.getString(R.string.ConfigTestButton__delete_config_hint)),
            onClick = onClick
        )
    }
} 