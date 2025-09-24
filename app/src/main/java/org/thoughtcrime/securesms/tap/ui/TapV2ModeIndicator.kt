/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.signal.core.util.logging.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.TransportChannelStatus
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Tap v2模式指示器组件
 * 用于在聊天列表中显示v2模式状态
 * 替代CosV2ModeIndicator，使用统一的TransportChannelManager
 */
class TapV2ModeIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private val TAG = Log.tag(TapV2ModeIndicator::class.java)
    }

    private val indicatorText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.tap_v2_mode_indicator, this, true)
        indicatorText = findViewById(R.id.tap_v2_indicator_text)
        
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
            background = ContextCompat.getDrawable(context, R.drawable.tap_v2_indicator_background)
            setPadding(8, 2, 8, 2)
        }
    }

    /**
     * 更新指示器状态
     *
     * @param recipient 接收方
     */
    fun updateStatus(recipient: Recipient) {
        try {
            val channelManager = TransportChannelManager.getInstance(context)
            val recipientIdStr = recipient.id.toString()

            Log.d(TAG, "更新Tap v2指示器状态: recipientId=$recipientIdStr")

            val hasActiveChannel = channelManager.hasActiveChannel(recipientIdStr)

            Log.d(TAG, "通道查询结果: hasActiveChannel=$hasActiveChannel")

            visibility = if (hasActiveChannel) VISIBLE else GONE

                         if (hasActiveChannel) {
                 // 获取通道详细信息
                 val channels = channelManager.getActiveChannels(recipientIdStr)
                 if (channels.isNotEmpty()) {
                     val activeChannel = channels.find { it.status == TransportChannelStatus.ACTIVE }
                     if (activeChannel != null) {
                         updateIndicatorStyle(activeChannel.status, activeChannel.providerType)
                         Log.d(TAG, "显示Tap v2指示器: status=${activeChannel.status}, provider=${activeChannel.providerType}")
                     } else {
                         // 有通道但不是活跃状态
                         val firstChannel = channels.first()
                         updateIndicatorStyle(firstChannel.status, firstChannel.providerType)
                         Log.d(TAG, "显示Tap v2指示器（非活跃）: status=${firstChannel.status}, provider=${firstChannel.providerType}")
                     }
                 } else {
                     // 理论上不应该出现这种情况，但提供备用显示
                     updateIndicatorStyle(TransportChannelStatus.ACTIVE, "tap")
                     Log.d(TAG, "显示Tap v2指示器（备用显示）")
                 }
             } else {
                 Log.d(TAG, "隐藏Tap v2指示器: hasActiveChannel=$hasActiveChannel")
             }
        } catch (e: Exception) {
            Log.e(TAG, "更新Tap v2指示器状态时出错", e)
            // 出错时隐藏指示器
            visibility = GONE
        }
    }

    /**
     * 根据通道状态和Provider类型更新指示器样式
     * 
     * @param status 通道状态
     * @param providerType Provider类型
     */
    private fun updateIndicatorStyle(status: TransportChannelStatus, providerType: String) {
        when (status) {
            TransportChannelStatus.ACTIVE -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorPrimary))
                indicatorText.text = when (providerType) {
                    "cos" -> "v2"
                    "email" -> "E2"  
                    "ipfs" -> "I2"
                    else -> "T2"  // Tap的通用标识
                }
            }
                         TransportChannelStatus.ESTABLISHING, TransportChannelStatus.INACTIVE -> {
                 indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorSecondary))
                 indicatorText.text = when (providerType) {
                     "cos" -> "v2?"
                     "email" -> "E2?"  
                     "ipfs" -> "I2?"
                     else -> "T2?"
                 }
             }
             TransportChannelStatus.FAILED, TransportChannelStatus.SUSPENDED -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorError))
                indicatorText.text = when (providerType) {
                    "cos" -> "v2!"
                    "email" -> "E2!"  
                    "ipfs" -> "I2!"
                    else -> "T2!"
                }
            }
            else -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_accent_primary))
                indicatorText.text = when (providerType) {
                    "cos" -> "v2"
                    "email" -> "E2"  
                    "ipfs" -> "I2"
                    else -> "T2"
                }
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