/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.tap.ConfigOption
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.R

/**
 * 多选配置项的DSL模型
 */
data class MultiSelectModel(
    private val titleText: String,
    val options: List<ConfigOption>,
    val selectedValues: Set<String>,
    val helpText: String? = null,
    val onSelectionChanged: (Set<String>) -> Unit
) : PreferenceModel<MultiSelectModel>() {
    
    override val title: DSLSettingsText = DSLSettingsText.from(titleText)

    override fun areItemsTheSame(newItem: MultiSelectModel): Boolean {
        return title == newItem.title
    }

    override fun areContentsTheSame(newItem: MultiSelectModel): Boolean {
        return this == newItem
    }

    class ViewHolder(itemView: View) : MappingViewHolder<MultiSelectModel>(itemView) {
        
        private val multiSelectView: MultiSelectConfigView

        init {
            multiSelectView = MultiSelectConfigView(context)
            (itemView as android.view.ViewGroup).addView(multiSelectView)
        }

        override fun bind(model: MultiSelectModel) {
            multiSelectView.apply {
                setTitle(model.titleText)
                setHelpText(model.helpText)
                setOptions(model.options)
                setSelectedValues(model.selectedValues)
                setOnSelectionChangedListener(model.onSelectionChanged)
            }
        }
    }

    companion object {
        fun register(adapter: MappingAdapter) {
            adapter.registerFactory(
                MultiSelectModel::class.java,
                LayoutFactory({ ViewHolder(it) }, R.layout.dsl_multi_select_config)
            )
        }
    }
} 