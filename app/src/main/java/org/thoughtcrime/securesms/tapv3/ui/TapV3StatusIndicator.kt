package org.thoughtcrime.securesms.tapv3.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.integration.TapV3MessageRouter

class TapV3StatusIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val indicatorText: TextView
    private val indicatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentUpdateJob: Job? = null
    
    private val messageRouter: TapV3MessageRouter by lazy {
        TapV3MessageRouter.getInstance(context)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.tap_v3_status_indicator, this, true)
        indicatorText = findViewById(R.id.tap_v3_indicator_text)
        
        orientation = HORIZONTAL
        setupDefaultStyle()
    }

    private fun setupDefaultStyle() {
        indicatorText.apply {
            text = "v3"
            textSize = 10f
            setTextColor(ContextCompat.getColor(context, R.color.signal_accent_primary))
            background = ContextCompat.getDrawable(context, R.drawable.tap_v3_indicator_background)
            setPadding(8, 2, 8, 2)
        }
    }

    fun updateStatus(recipient: Recipient) {
        currentUpdateJob?.cancel()
        
        currentUpdateJob = indicatorScope.launch {
            try {
                val status: TapV3ChannelTable.ChannelStatus?
                
                if (recipient.isGroup) {
                    val groupId = recipient.requireGroupId().toString()
                    val groupState = withContext(Dispatchers.IO) {
                        SignalDatabase.tapV3GroupStates.getGroupState(groupId)
                    }
                    status = if (groupState != null && groupState.status == org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.ACTIVE) {
                        TapV3ChannelTable.ChannelStatus.ACTIVE
                    } else if (groupState != null && groupState.status == org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.PROPOSING) {
                        TapV3ChannelTable.ChannelStatus.PENDING
                    } else {
                        null
                    }
                } else {
                    val recipientId = try {
                        recipient.requireAci().toString()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Unable to get recipient ACI: ${e.message}")
                        visibility = GONE
                        return@launch
                    }

                    status = withContext(Dispatchers.IO) {
                        messageRouter.getTapV3ChannelStatus(recipientId)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (status != null) {
                        visibility = VISIBLE
                        updateIndicatorStyle(status)
                        Log.d(TAG, "Showing Tap v3 indicator: status=$status")
                    } else {
                        visibility = GONE
                        Log.d(TAG, "Hiding Tap v3 indicator")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating Tap v3 indicator status", e)
                withContext(Dispatchers.Main) {
                    visibility = GONE
                }
            }
        }
    }

    private fun updateIndicatorStyle(status: TapV3ChannelTable.ChannelStatus) {
        when (status) {
            TapV3ChannelTable.ChannelStatus.ACTIVE -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorPrimary))
                indicatorText.text = "v3"
            }
            TapV3ChannelTable.ChannelStatus.PENDING -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorSecondary))
                indicatorText.text = "v3?"
            }
            TapV3ChannelTable.ChannelStatus.FAILED -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorError))
                indicatorText.text = "v3!"
            }
            TapV3ChannelTable.ChannelStatus.GROUP_ONLY -> {
                indicatorText.setTextColor(ContextCompat.getColor(context, R.color.signal_colorSecondary))
                indicatorText.text = "v3 (G)"
            }
        }
    }

    fun setText(text: String) {
        indicatorText.text = text
    }

    fun setTextColor(color: Int) {
        indicatorText.setTextColor(ContextCompat.getColor(context, color))
    }

    fun setIndicatorBackground(drawable: Drawable?) {
        indicatorText.background = drawable
    }

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        indicatorScope.cancel()
    }

    companion object {
        private val TAG = Log.tag(TapV3StatusIndicator::class.java)
    }
}
