/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.view.View
import androidx.fragment.app.Fragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.R

/**
 * 文件路径配置项的DSL模型
 */
data class FilePathModel(
    private val titleText: String,
    val currentPath: String,
    val hint: String? = null,
    val helpText: String? = null,
    val fragment: Fragment? = null,
    val onPathChanged: (String) -> Unit
) : PreferenceModel<FilePathModel>() {
    
    override val title: DSLSettingsText = DSLSettingsText.from(titleText)

    override fun areItemsTheSame(newItem: FilePathModel): Boolean {
        return title == newItem.title
    }

    override fun areContentsTheSame(newItem: FilePathModel): Boolean {
        return this == newItem
    }

    class ViewHolder(itemView: View) : MappingViewHolder<FilePathModel>(itemView) {
        
        private val filePathView: FilePathConfigView

        init {
            filePathView = FilePathConfigView(context)
            (itemView as android.view.ViewGroup).addView(filePathView)
        }

        override fun bind(model: FilePathModel) {
            filePathView.apply {
                setTitle(model.titleText)
                setFilePath(model.currentPath)
                model.hint?.let { setHint(it) }
                setHelpText(model.helpText)
                model.fragment?.let { setFragment(it) }
                setOnPathChangedListener(model.onPathChanged)
            }
        }
    }

    companion object {
        fun register(adapter: MappingAdapter) {
            adapter.registerFactory(
                FilePathModel::class.java,
                LayoutFactory({ ViewHolder(it) }, R.layout.dsl_file_path_config)
            )
        }
    }
} 