/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tap.ConfigOption

/**
 * 多选配置视图组件
 * 
 * 提供真正的多选UI，支持复选框选择多个选项
 */
class MultiSelectConfigView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val optionsContainer: LinearLayout
    private val helpTextView: TextView
    
    private var options: List<ConfigOption> = emptyList()
    private var selectedValues: MutableSet<String> = mutableSetOf()
    private var onSelectionChanged: ((Set<String>) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.multi_select_config_view, this, true)
        
        titleView = findViewById(R.id.multi_select_title)
        optionsContainer = findViewById(R.id.multi_select_options_container)
        helpTextView = findViewById(R.id.multi_select_help_text)
    }

    /**
     * 设置标题
     */
    fun setTitle(title: String) {
        titleView.text = title
        titleView.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 设置帮助文本
     */
    fun setHelpText(helpText: String?) {
        if (helpText.isNullOrEmpty()) {
            helpTextView.visibility = View.GONE
        } else {
            helpTextView.text = helpText
            helpTextView.visibility = View.VISIBLE
        }
    }

    /**
     * 设置选项列表
     */
    fun setOptions(options: List<ConfigOption>) {
        this.options = options
        refreshOptionsView()
    }

    /**
     * 设置选中的值
     */
    fun setSelectedValues(values: Set<String>) {
        selectedValues.clear()
        selectedValues.addAll(values)
        refreshOptionsView()
    }

    /**
     * 获取选中的值
     */
    fun getSelectedValues(): Set<String> {
        return selectedValues.toSet()
    }

    /**
     * 设置选择变化监听器
     */
    fun setOnSelectionChangedListener(listener: (Set<String>) -> Unit) {
        this.onSelectionChanged = listener
    }

    /**
     * 刷新选项视图
     */
    private fun refreshOptionsView() {
        optionsContainer.removeAllViews()
        
        options.forEach { option ->
            val checkboxView = createCheckboxView(option)
            optionsContainer.addView(checkboxView)
        }
    }

    /**
     * 创建复选框视图
     */
    private fun createCheckboxView(option: ConfigOption): View {
        val checkboxLayout = LayoutInflater.from(context).inflate(
            R.layout.multi_select_option_item, 
            optionsContainer, 
            false
        )
        
        val checkbox = checkboxLayout.findViewById<CheckBox>(R.id.option_checkbox)
        val optionText = checkboxLayout.findViewById<TextView>(R.id.option_text)
        
        checkbox.isChecked = selectedValues.contains(option.value)
        optionText.text = option.displayText
        
        // 设置点击事件
        checkboxLayout.setOnClickListener {
            toggleOption(option.value)
        }
        
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedValues.add(option.value)
            } else {
                selectedValues.remove(option.value)
            }
            onSelectionChanged?.invoke(selectedValues.toSet())
        }
        
        return checkboxLayout
    }

    /**
     * 切换选项状态
     */
    private fun toggleOption(value: String) {
        if (selectedValues.contains(value)) {
            selectedValues.remove(value)
        } else {
            selectedValues.add(value)
        }
        
        // 更新对应的复选框状态
        for (i in 0 until optionsContainer.childCount) {
            val child = optionsContainer.getChildAt(i)
            val checkbox = child.findViewById<CheckBox>(R.id.option_checkbox)
            val optionText = child.findViewById<TextView>(R.id.option_text)
            
            if (options.getOrNull(i)?.value == value) {
                checkbox.isChecked = selectedValues.contains(value)
                break
            }
        }
        
        onSelectionChanged?.invoke(selectedValues.toSet())
    }

    /**
     * 清空所有选择
     */
    fun clearSelection() {
        selectedValues.clear()
        refreshOptionsView()
        onSelectionChanged?.invoke(emptySet())
    }

    /**
     * 全选
     */
    fun selectAll() {
        selectedValues.clear()
        selectedValues.addAll(options.map { it.value })
        refreshOptionsView()
        onSelectionChanged?.invoke(selectedValues.toSet())
    }
} 