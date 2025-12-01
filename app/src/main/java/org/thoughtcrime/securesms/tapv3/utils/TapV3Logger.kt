package org.thoughtcrime.securesms.tapv3.utils

import org.signal.core.util.logging.Log

object TapV3Logger {
    
    private const val TAG_PREFIX = "TapV3"
    
    fun tag(clazz: Class<*>): String {
        return "$TAG_PREFIX:${clazz.simpleName}"
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
