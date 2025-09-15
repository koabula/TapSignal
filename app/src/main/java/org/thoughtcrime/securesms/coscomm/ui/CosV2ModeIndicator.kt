/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.coscomm.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.coscomm.manager.CosChannelManager
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * COS v2模式指示器组件
 * 用于在聊天列表中显示v2模式状态
 */
class CosV2ModeIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val indicatorText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.cos_v2_mode_indicator, this, true)
        indicatorText = findViewById(R.id.cos_v2_indicator_text)
        
        // 设置默认样式
        orientation = HORIZONTAL
        setupDefaultStyle()
    }

    /**
     * 设置默认样式
     */
    private fun setupDefaultStyle() {
        indicatorText.apply {
            text = "v2"
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, R.color.signal_accent_primary))
            background = ContextCompat.getDrawable(context, R.drawable.cos_v2_indicator_background)
            setPadding(8, 2, 8, 2)
        }
    }

    /**
     * 更新指示器状态
     *
     * @param recipient 接收方
     */
    fun updateStatus(recipient: Recipient) {
        val channelManager = CosChannelManager.getInstance(context)
        val recipientIdStr = recipient.id.toString()

      Log.d("CosV2ModeIndicator", "更新v2指示器状态: recipientId=$recipientIdStr")

        val channel = channelManager.getChannel(recipientIdStr)
        val hasActiveChannel = channel?.isActive() == true

        Log.d("CosV2ModeIndicator", "通道查询结果: channel=${channel != null}, isActive=${channel?.isActive()}, status=${channel?.status}")

        visibility = if (hasActiveChannel) VISIBLE else GONE

        if (hasActiveChannel && channel != null) {
            // 可以根据通道状态显示不同的样式
            val channelStatus = channel.status.name
            updateIndicatorStyle(channelStatus)
            Log.d("CosV2ModeIndicator", "显示v2指示器: status=$channelStatus")
        } else {
            Log.d("CosV2ModeIndicator", "隐藏v2指示器: hasActiveChannel=$hasActiveChannel")
        }
    }

    /**
     * 根据通道状态更新指示器样式
     * 
     * @param status 通道状态
     */
    private fun updateIndicatorStyle(status: String?) {
        when (status) {
            "ACTIVE" -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorPrimary))
                indicatorText.text = "v2"
            }
            "PENDING" -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorSecondary))
                indicatorText.text = "v2?"
            }
            "ERROR" -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorError))
                indicatorText.text = "v2!"
            }
            else -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_accent_primary))
                indicatorText.text = "v2"
            }
        }
    }

    /**
     * 设置自定义文本
     * 
     * @param text 显示文本
     */
    fun setText(text: String) {
        indicatorText.text = text
    }

    /**
     * 设置文本颜色
     * 
     * @param color 颜色资源ID
     */
    fun setTextColor(color: Int) {
        indicatorText.setTextColor(ContextCompat.getColor(context, color))
    }

    /**
     * 设置背景
     * 
     * @param drawable 背景drawable
     */
    fun setIndicatorBackground(drawable: Drawable?) {
        indicatorText.background = drawable
    }

    /**
     * 强制显示指示器
     */
    fun show() {
        visibility = VISIBLE
    }

    /**
     * 隐藏指示器
     */
    fun hide() {
        visibility = GONE
    }
}
