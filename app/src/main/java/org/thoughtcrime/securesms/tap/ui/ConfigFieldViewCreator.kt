/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.text.InputType
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.components.settings.models.InlineTextInput
import org.thoughtcrime.securesms.tap.ConfigField
import org.thoughtcrime.securesms.tap.ConfigFieldType
import org.thoughtcrime.securesms.tap.ConfigOption

/**
 * 配置字段UI创建器
 * 
 * 负责根据ConfigField定义动态生成Signal风格的UI组件
 */
object ConfigFieldViewCreator {

    /**
     * 根据ConfigField列表创建DSL配置项
     * 
     * @param fields 配置字段列表
     * @param values 当前配置值
     * @param onValueChanged 值变更回调
     */
    fun createFieldConfigs(
        fields: List<ConfigField>,
        values: Map<String, Any>,
        onValueChanged: (key: String, value: Any) -> Unit
    ): List<DSLConfiguration.() -> Unit> {
        return fields.map { field ->
            when (field.fieldType) {
                ConfigFieldType.TEXT -> createTextFieldConfig(field, values, onValueChanged)
                ConfigFieldType.PASSWORD -> createPasswordFieldConfig(field, values, onValueChanged)
                ConfigFieldType.EMAIL -> createEmailFieldConfig(field, values, onValueChanged)
                ConfigFieldType.URL -> createUrlFieldConfig(field, values, onValueChanged)
                ConfigFieldType.NUMBER -> createNumberFieldConfig(field, values, onValueChanged)
                ConfigFieldType.SELECT -> createSelectFieldConfig(field, values, onValueChanged)
                ConfigFieldType.MULTI_SELECT -> createMultiSelectFieldConfig(field, values, onValueChanged)
                ConfigFieldType.CHECKBOX -> createCheckboxFieldConfig(field, values, onValueChanged)
                ConfigFieldType.TEXTAREA -> createTextAreaFieldConfig(field, values, onValueChanged)
                ConfigFieldType.FILE_PATH -> createFilePathFieldConfig(field, values, onValueChanged)
                ConfigFieldType.REGION_SELECT -> createRegionSelectFieldConfig(field, values, onValueChanged)
                ConfigFieldType.HIDDEN -> createHiddenFieldConfig(field, values, onValueChanged)
            }
        }
    }

    /**
     * 创建文本输入字段
     */
    private fun createTextFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入${field.displayName}"),
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        // 添加帮助文本
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建密码输入字段
     */
    private fun createPasswordFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入${field.displayName}"),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        // 添加帮助文本
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建邮箱输入字段
     */
    private fun createEmailFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入邮箱地址"),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建URL输入字段
     */
    private fun createUrlFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入URL地址"),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建数字输入字段
     */
    private fun createNumberFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入数字"),
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建下拉选择字段
     */
    private fun createSelectFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        val options = field.options ?: emptyList()
        val currentValue = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: ""
        val selectedIndex = options.indexOfFirst { it.value == currentValue }.let { if (it >= 0) it else 0 }
        
        radioListPref(
            title = DSLSettingsText.from(field.displayName),
            listItems = options.map { option: ConfigOption -> option.displayText }.toTypedArray(),
            selected = selectedIndex,
            onSelected = { index ->
                if (index in options.indices) {
                    onValueChanged(field.key, options[index].value)
                }
            }
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建多选字段（作为文本输入实现）
     */
    private fun createMultiSelectFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        // 多选暂时作为逗号分隔的文本输入实现
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from("多个选项请用逗号分隔"),
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建复选框字段
     */
    private fun createCheckboxFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        val currentValue = values[field.key] as? Boolean ?: field.defaultValue as? Boolean ?: false
        
        switchPref(
            title = DSLSettingsText.from(field.displayName),
            summary = field.helpText?.let { DSLSettingsText.from(it) },
            isChecked = currentValue,
            onClick = { onValueChanged(field.key, !currentValue) }
        )
    }

    /**
     * 创建多行文本框字段
     */
    private fun createTextAreaFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from(field.placeholder ?: "请输入${field.displayName}"),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建文件路径选择字段
     */
    private fun createFilePathFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        customPref(
            InlineTextInput.Model(
                title = DSLSettingsText.from(field.displayName),
                value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                hint = DSLSettingsText.from("请输入文件路径"),
                onValueChanged = { onValueChanged(field.key, it) }
            )
        )
        
        field.helpText?.let { helpText ->
            textPref(
                title = DSLSettingsText.from(""),
                summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
            )
        }
    }

    /**
     * 创建区域选择字段
     */
    private fun createRegionSelectFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        // 区域选择可以复用下拉选择或文本输入
        if (field.options?.isNotEmpty() == true) {
            createSelectFieldConfig(field, values, onValueChanged)()
        } else {
            customPref(
                InlineTextInput.Model(
                    title = DSLSettingsText.from(field.displayName),
                    value = values[field.key]?.toString() ?: field.defaultValue?.toString() ?: "",
                    hint = DSLSettingsText.from(field.placeholder ?: "请输入区域标识"),
                    onValueChanged = { onValueChanged(field.key, it) }
                )
            )
            
            field.helpText?.let { helpText ->
                textPref(
                    title = DSLSettingsText.from(""),
                    summary = DSLSettingsText.from(helpText, DSLSettingsText.ColorModifier(0xFF666666.toInt()))
                )
            }
        }
    }

    /**
     * 创建隐藏字段（不显示UI）
     */
    private fun createHiddenFieldConfig(
        field: ConfigField,
        values: Map<String, Any>,
        onValueChanged: (String, Any) -> Unit
    ): DSLConfiguration.() -> Unit = {
        // 隐藏字段不显示任何UI，但可以存储默认值
        field.defaultValue?.let { defaultValue ->
            if (!values.containsKey(field.key)) {
                onValueChanged(field.key, defaultValue)
            }
        }
    }
} 