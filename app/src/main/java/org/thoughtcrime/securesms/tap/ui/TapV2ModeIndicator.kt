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
import kotlinx.coroutines.*

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
    private val indicatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentUpdateJob: Job? = null

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
     * 更新指示器状态（异步版本，避免主线程死锁）
     *
     * @param recipient 接收方
     */
    fun updateStatus(recipient: Recipient) {
        // Groups should not call this method, they use separate group indicator logic
        if (recipient.isGroup) {
            visibility = GONE
            return
        }
        
        // 取消之前的更新任务，避免重复操作
        currentUpdateJob?.cancel()
        
        currentUpdateJob = indicatorScope.launch {
            try {
                // 获取recipient ACI（在主线程安全操作）
                val recipientAci = try {
                    recipient.requireAci().toString()
                } catch (e: Throwable) {
                    Log.w(TAG, "无法获取recipient ACI（可能是群组或其他原因），跳过Tap v2指示器更新: ${e.message}")
                    visibility = GONE
                    return@launch
                }

                Log.d(TAG, "更新Tap v2指示器状态: recipientAci=${recipientAci.take(10)}...")

                // 切换到IO线程进行通道状态检查，避免主线程阻塞
                val channelInfo = withContext(Dispatchers.IO) {
                    try {
                        val channelManager = TransportChannelManager.getInstance(context)
                        
                        // 异步检查通道状态，避免主线程死锁
                        val hasActiveChannel = channelManager.hasActiveChannel(recipientAci)
                        
                        Log.d(TAG, "通道查询结果: hasActiveChannel=$hasActiveChannel")
                        
                        if (hasActiveChannel) {
                            // 获取通道详细信息
                            val channels = channelManager.getActiveChannels(recipientAci)
                            if (channels.isNotEmpty()) {
                                val activeChannel = channels.find { it.status == TransportChannelStatus.ACTIVE }
                                if (activeChannel != null) {
                                    ChannelInfo(true, activeChannel.status, activeChannel.providerType)
                                } else {
                                    // 有通道但不是活跃状态
                                    val firstChannel = channels.first()
                                    ChannelInfo(true, firstChannel.status, firstChannel.providerType)
                                }
                            } else {
                                // 理论上不应该出现这种情况，但提供备用显示
                                ChannelInfo(true, TransportChannelStatus.ACTIVE, "tap")
                            }
                        } else {
                            ChannelInfo(false, null, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "检查通道状态时出错", e)
                        ChannelInfo(false, null, null)
                    }
                }
                
                // 确保在主线程上更新UI
                withContext(Dispatchers.Main) {
                    updateUI(channelInfo)
                }
                
            } catch (e: CancellationException) {
                Log.d(TAG, "通道状态更新被取消")
            } catch (e: Exception) {
                Log.e(TAG, "更新Tap v2指示器状态时出错", e)
                // 出错时在主线程隐藏指示器
                withContext(Dispatchers.Main) {
                    visibility = GONE
                }
            }
        }
    }
    
    /**
     * 在主线程上更新UI显示
     */
    private fun updateUI(channelInfo: ChannelInfo) {
        visibility = if (channelInfo.hasActiveChannel) VISIBLE else GONE
        
        if (channelInfo.hasActiveChannel && channelInfo.status != null && channelInfo.providerType != null) {
            updateIndicatorStyle(channelInfo.status, channelInfo.providerType)
            Log.d(TAG, "显示Tap v2指示器: status=${channelInfo.status}, provider=${channelInfo.providerType}")
        } else {
            Log.d(TAG, "隐藏Tap v2指示器: hasActiveChannel=${channelInfo.hasActiveChannel}")
        }
    }
    
    /**
     * 通道信息数据类
     */
    private data class ChannelInfo(
        val hasActiveChannel: Boolean,
        val status: TransportChannelStatus?,
        val providerType: String?
    )

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
     @param color 颜色资源ID
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
    
    /**
     * 清理资源
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        indicatorScope.cancel()
    }
} 