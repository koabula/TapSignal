package org.thoughtcrime.securesms.components.settings.models

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.util.text.AfterTextChanged

/**
 * 内联文本输入组件，直接在设置页面中显示输入框
 */
object InlineTextInput {

    fun register(adapter: MappingAdapter) {
        adapter.registerFactory(Model::class.java, LayoutFactory({ ViewHolder(it) }, R.layout.dsl_inline_text_input))
    }

    class Model(
        override val title: DSLSettingsText,
        val value: String,
        val hint: DSLSettingsText? = null,
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
        val onValueChanged: (String) -> Unit
    ) : PreferenceModel<Model>() {
        override fun areItemsTheSame(newItem: Model): Boolean {
            return title == newItem.title
        }

        override fun areContentsTheSame(newItem: Model): Boolean {
            return super.areContentsTheSame(newItem) &&
                    title == newItem.title &&
                    value == newItem.value &&
                    hint == newItem.hint &&
                    inputType == newItem.inputType
        }
    }

    class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.title)
        private val inputLayout: TextInputLayout = itemView.findViewById(R.id.input_layout)
        private val input: EditText = itemView.findViewById(R.id.input)
        
        private var textChangedListener: AfterTextChanged? = null

        override fun bind(model: Model) {
            titleView.text = model.title.resolve(context)
            
            if (model.hint != null) {
                inputLayout.hint = model.hint.resolve(context)
            }
            
            input.inputType = model.inputType
            
            if (textChangedListener != null) {
                input.removeTextChangedListener(textChangedListener)
            }
            
            if (input.text.toString() != model.value) {
                input.setText(model.value)
            }
            
            textChangedListener = AfterTextChanged { model.onValueChanged(it.toString()) }
            input.addTextChangedListener(textChangedListener)
        }
    }
} 