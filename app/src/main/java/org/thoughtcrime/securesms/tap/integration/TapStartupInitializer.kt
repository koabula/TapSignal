package org.thoughtcrime.securesms.tap.integration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import org.signal.core.util.logging.Log

/**
 * 通过 ContentProvider 提前初始化 TaP 模块，确保 ProviderRegistry 已注册可用 provider。
 * 不做任何数据访问，仅在 onCreate() 中触发初始化。
 */
class TapStartupInitializer : ContentProvider() {

    companion object {
        private val TAG = Log.tag(TapStartupInitializer::class.java)
    }

    override fun onCreate(): Boolean {
        return try {
            context?.let { ctx ->
                // 使用同步初始化确保数据完全恢复后再启动轮询服务
                TapModuleInitializer.getInstance(ctx).initializeSync(false)
                Log.i(TAG, "TapStartupInitializer synchronized initialization completed")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "TapStartupInitializer initialization failed", e)
            true
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
} 