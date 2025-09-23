/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

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
        state: ConfigTestState,
        testResult: ConfigTestResult?,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        val (titleRes, summaryText) = when (state) {
            ConfigTestState.READY -> {
                Pair(
                    if (enabled) "测试配置" else "测试配置（请填写必需字段）",
                    "点击测试配置连接性"
                )
            }
            ConfigTestState.TESTING -> {
                Pair(
                    "测试中...",
                    "正在验证配置，请稍候"
                )
            }
            ConfigTestState.SUCCESS -> {
                Pair(
                    "测试成功",
                    (testResult as? ConfigTestResult.Success)?.message ?: "配置有效，连接正常"
                )
            }
            ConfigTestState.FAILED -> {
                Pair(
                    "测试失败",
                    (testResult as? ConfigTestResult.Failed)?.error ?: "配置测试失败"
                )
            }
        }

        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(titleRes),
            summary = DSLSettingsText.from(
                summaryText, 
                when (state) {
                    ConfigTestState.SUCCESS -> DSLSettingsText.ColorModifier(0xFF4CAF50.toInt()) // 绿色
                    ConfigTestState.FAILED -> DSLSettingsText.ColorModifier(0xFFf44336.toInt()) // 红色
                    ConfigTestState.TESTING -> DSLSettingsText.ColorModifier(0xFF2196F3.toInt()) // 蓝色
                    else -> DSLSettingsText.ColorModifier(0xFF666666.toInt()) // 默认灰色
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
                    "详细信息：$details",
                    DSLSettingsText.ColorModifier(0xFF666666.toInt())
                )
            )
        }
    }
    
    /**
     * 创建保存按钮
     */
    fun createSaveButton(
        enabled: Boolean = true,
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(if (enabled) "保存配置" else "保存配置（请填写必需字段）"),
            summary = DSLSettingsText.from("保存当前配置并启用传输服务"),
            isEnabled = enabled,
            onClick = if (enabled) onClick else { -> }
        )
    }
    
    /**
     * 创建删除配置按钮
     */
    fun createDeleteButton(
        onClick: () -> Unit
    ): DSLConfiguration.() -> Unit = {
        
        dividerPref()
        
        clickPref(
            title = DSLSettingsText.from(
                "删除配置",
                DSLSettingsText.ColorModifier(0xFFf44336.toInt()) // 红色警告文字
            ),
            summary = DSLSettingsText.from("删除当前Provider配置"),
            onClick = onClick
        )
    }
} 