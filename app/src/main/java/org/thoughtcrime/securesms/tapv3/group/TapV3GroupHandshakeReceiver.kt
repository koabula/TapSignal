package org.thoughtcrime.securesms.tapv3.group

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.signal.core.util.logging.Log

class TapV3GroupHandshakeReceiver : BroadcastReceiver() {

    companion object {
        private val TAG = Log.tag(TapV3GroupHandshakeReceiver::class.java)
        
        const val ACTION_ACCEPT_GROUP_HANDSHAKE = "org.thoughtcrime.securesms.tapv3.group.ACCEPT_HANDSHAKE"
        const val ACTION_REJECT_GROUP_HANDSHAKE = "org.thoughtcrime.securesms.tapv3.group.REJECT_HANDSHAKE"
        const val EXTRA_GROUP_ID = "groupId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: return
        
        Log.i(TAG, "Received group handshake action: action=${intent.action}, groupId=${groupId.take(8)}...")
        
        when (intent.action) {
            ACTION_ACCEPT_GROUP_HANDSHAKE -> {
                Log.i(TAG, "User accepted Tap v3 group handshake for: ${groupId.take(8)}...")
                TapV3GroupManager.getInstance(context).acceptV3Mode(groupId)
                
                // Close notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(groupId.hashCode())
            }
            ACTION_REJECT_GROUP_HANDSHAKE -> {
                Log.i(TAG, "User rejected Tap v3 group handshake for: ${groupId.take(8)}...")
                // Ideally send reject message, but for now just close notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(groupId.hashCode())
            }
        }
    }
}
