package org.thoughtcrime.securesms.tapv3.integration

import java.util.concurrent.ConcurrentHashMap

object TapV3ForceSignalManager {
    private val forcedRecipients = ConcurrentHashMap<String, Long>()

    fun forceSignal(recipientId: String, durationMs: Long) {
        forcedRecipients[recipientId] = System.currentTimeMillis() + durationMs
    }

    fun shouldForceSignal(recipientId: String): Boolean {
        val expiry = forcedRecipients[recipientId] ?: return false
        if (System.currentTimeMillis() > expiry) {
            forcedRecipients.remove(recipientId)
            return false
        }
        return true
    }
}
