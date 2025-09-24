/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.thoughtcrime.securesms.R

/**
 * 文件路径配置视图组件
 * 
 * 提供文本输入框和文件选择按钮，支持从文件系统选择文件
 */
class FilePathConfigView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val pathEditText: EditText
    private val browseButton: Button
    private val helpTextView: TextView
    
    private var onPathChanged: ((String) -> Unit)? = null
    private var fragment: Fragment? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.file_path_config_view, this, true)
        
        titleView = findViewById(R.id.file_path_title)
        pathEditText = findViewById(R.id.file_path_edit_text)
        browseButton = findViewById(R.id.file_path_browse_button)
        helpTextView = findViewById(R.id.file_path_help_text)
        
        setupListeners()
    }

    /**
     * 设置关联的Fragment，用于文件选择
     */
    fun setFragment(fragment: Fragment) {
        this.fragment = fragment
    }

    /**
     * 设置标题
     */
    fun setTitle(title: String) {
        titleView.text = title
        titleView.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 设置文件路径
     */
    fun setFilePath(path: String) {
        pathEditText.setText(path)
    }

    /**
     * 获取文件路径
     */
    fun getFilePath(): String {
        return pathEditText.text.toString().trim()
    }

    /**
     * 设置提示文本
     */
    fun setHint(hint: String) {
        pathEditText.hint = hint
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
     * 设置路径变化监听器
     */
    fun setOnPathChangedListener(listener: (String) -> Unit) {
        this.onPathChanged = listener
    }

    /**
     * 设置监听器
     */
    private fun setupListeners() {
        pathEditText.setOnFocusChangeListener { _, _ ->
            notifyPathChanged()
        }
        
        browseButton.setOnClickListener {
            openFileChooser()
        }
    }

    /**
     * 打开文件选择器
     */
    private fun openFileChooser() {
        val fragment = this.fragment ?: return
        
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"  // 支持所有文件类型
                
                // 如果需要只选择特定类型的文件，可以设置具体的MIME类型
                // 例如：type = "text/*" 只选择文本文件
                
                // 添加额外的MIME类型支持
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/*",
                    "application/*",
                    "image/*"
                ))
            }
            
            // 启动文件选择器
            fragment.startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
            
        } catch (e: Exception) {
            // 如果无法打开系统文件选择器，回退到简单的Intent
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                fragment.startActivityForResult(fallbackIntent, FILE_PICKER_REQUEST_CODE)
            } catch (ex: Exception) {
                // 如果还是无法打开，显示错误信息
                // 可以考虑显示Toast或其他错误提示
            }
        }
    }

    /**
     * 处理文件选择结果
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val path = getPathFromUri(uri)
                if (path != null) {
                    setFilePath(path)
                    notifyPathChanged()
                }
            }
        }
    }

    /**
     * 从URI获取文件路径
     */
    private fun getPathFromUri(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                // 对于content URI，返回URI字符串本身
                // 在Android中，content URI通常更安全和可靠
                uri.toString()
            }
            else -> uri.toString()
        }
    }

    /**
     * 通知路径变化
     */
    private fun notifyPathChanged() {
        onPathChanged?.invoke(getFilePath())
    }

    companion object {
        const val FILE_PICKER_REQUEST_CODE = 1001
    }
} 