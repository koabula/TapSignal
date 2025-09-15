package org.thoughtcrime.securesms.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 在应用启动时调用, 以确保 COS 配置合法。
 */
object CosModuleInitializer {
    private val TAG = Log.tag(CosModuleInitializer::class.java)

    fun initialize(context: Context) {
        try {
            val config = CosConfigStorage.getConfig(context)
            if (config != null) {
                Log.i(TAG, "COS Config loaded: Provider=${config.provider}, Bucket=${config.bucketName}")
            } else {
                Log.i(TAG, "No COS config found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize COS module", e)
        }
    }
} 