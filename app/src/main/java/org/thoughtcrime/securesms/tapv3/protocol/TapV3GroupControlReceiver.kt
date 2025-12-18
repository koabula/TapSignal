package org.thoughtcrime.securesms.tapv3.protocol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies

class TapV3GroupControlReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = Log.tag(TapV3GroupControlReceiver::class.java)
        const val ACTION_ACCEPT = "org.thoughtcrime.securesms.tapv3.ACTION_ACCEPT"
        const val ACTION_DECLINE = "org.thoughtcrime.securesms.tapv3.ACTION_DECLINE"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_SENDER_ID = "sender_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val senderId = intent.getStringExtra(EXTRA_SENDER_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        if (groupId == null || senderId == null) {
            Log.w(TAG, "Missing group or sender ID in intent")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(notificationId)

        val handler = TapV3GroupControlHandler(context)

        when (intent.action) {
            ACTION_ACCEPT -> {
                Log.i(TAG, "User accepted Tap v3 offer for group $groupId")
                // Execute in background
                org.signal.core.util.concurrent.SignalExecutors.BOUNDED.execute {
                    handler.acceptGroupOffer(groupId, senderId)
                }
            }
            ACTION_DECLINE -> {
                Log.i(TAG, "User declined Tap v3 offer for group $groupId")
                handler.declineGroupOffer(groupId)
            }
        }
    }
}
