package org.thoughtcrime.securesms.tap.utils

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.signal.core.util.logging.Log

/**
 * 网络错误分类器
 * 
 * 用于判断网络异常是否可以重试
 */
object NetworkErrorClassifier {
    
    private val TAG = Log.tag(NetworkErrorClassifier::class.java)
    
    /**
     * 判断异常是否可重试
     * 
     * @param throwable 待判断的异常
     * @return true表示可以重试，false表示不应重试
     */
    fun isRetryable(throwable: Throwable): Boolean {
        return when (throwable) {
            // SSL相关错误 - 可重试
            is SSLHandshakeException -> {
                Log.d(TAG, "SSL握手失败，可重试: ${throwable.message}")
                true
            }
            is SSLException -> {
                // 大多数SSL异常都可以重试，除非是证书验证失败
                val message = throwable.message?.lowercase() ?: ""
                val isRetryable = !message.contains("certificate") || message.contains("connection")
                Log.d(TAG, "SSL异常，可重试=$isRetryable: ${throwable.message}")
                isRetryable
            }
            
            // 连接超时 - 可重试
            is SocketTimeoutException -> {
                Log.d(TAG, "连接超时，可重试: ${throwable.message}")
                true
            }
            
            // 连接失败 - 可重试
            is ConnectException -> {
                Log.d(TAG, "连接失败，可重试: ${throwable.message}")
                true
            }
            
            // Socket异常 - 可重试
            is SocketException -> {
                Log.d(TAG, "Socket异常，可重试: ${throwable.message}")
                true
            }
            
            // EOF异常（连接意外关闭）- 可重试
            is EOFException -> {
                Log.d(TAG, "连接意外关闭，可重试: ${throwable.message}")
                true
            }
            
            // DNS解析失败 - 不可重试（除非是暂时性的）
            is UnknownHostException -> {
                Log.w(TAG, "DNS解析失败，不可重试: ${throwable.message}")
                false
            }
            
            // 一般IO异常 - 检查具体原因
            is IOException -> {
                val message = throwable.message?.lowercase() ?: ""
                val isRetryable = when {
                    message.contains("connection reset") -> true
                    message.contains("connection closed") -> true
                    message.contains("broken pipe") -> true
                    message.contains("connection refused") -> true
                    message.contains("network is unreachable") -> true
                    message.contains("software caused connection abort") -> true
                    message.contains("read timed out") -> true
                    message.contains("write timed out") -> true
                    else -> false
                }
                Log.d(TAG, "IO异常，可重试=$isRetryable: ${throwable.message}")
                isRetryable
            }
            
            // 其他异常 - 不重试
            else -> {
                Log.d(TAG, "未知异常类型，不重试: ${throwable.javaClass.simpleName}")
                false
            }
        }
    }
    
    /**
     * 判断是否是网络连接问题
     */
    fun isNetworkConnectivityIssue(throwable: Throwable): Boolean {
        return throwable is ConnectException ||
               throwable is SocketException ||
               throwable is UnknownHostException ||
               (throwable is IOException && 
                throwable.message?.contains("network", ignoreCase = true) == true)
    }
    
    /**
     * 判断是否是超时问题
     */
    fun isTimeoutIssue(throwable: Throwable): Boolean {
        return throwable is SocketTimeoutException ||
               (throwable is IOException && 
                throwable.message?.contains("timeout", ignoreCase = true) == true)
    }
    
    /**
     * 判断是否是SSL/TLS问题
     */
    fun isSslIssue(throwable: Throwable): Boolean {
        return throwable is SSLException || throwable is SSLHandshakeException
    }
    
    /**
     * 获取用户友好的错误描述
     */
    fun getUserFriendlyMessage(throwable: Throwable): String {
        return when {
            isSslIssue(throwable) -> "安全连接建立失败，请检查网络环境"
            isTimeoutIssue(throwable) -> "网络连接超时，请检查网络状态"
            isNetworkConnectivityIssue(throwable) -> "网络连接失败，请检查网络设置"
            throwable is UnknownHostException -> "无法解析服务器地址，请检查DNS设置"
            else -> "网络请求失败: ${throwable.message ?: "未知错误"}"
        }
    }
}

