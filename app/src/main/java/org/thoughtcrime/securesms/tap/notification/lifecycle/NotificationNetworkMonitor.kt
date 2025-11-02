package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import org.signal.core.util.logging.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 推送服务网络监听器
 * 
 * 监听网络连接状态变化，通知推送服务进行相应处理
 */
class NotificationNetworkMonitor private constructor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(NotificationNetworkMonitor::class.java)
        
        @Volatile
        private var instance: NotificationNetworkMonitor? = null
        
        fun getInstance(context: Context): NotificationNetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NotificationNetworkMonitor(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = false
    private var isStarted = false
    
    /**
     * 启动网络监听
     * 
     * @param onNetworkChange 网络状态变化回调，参数为网络是否可用
     */
    fun start(onNetworkChange: (Boolean) -> Unit) {
        if (isStarted) {
            Log.w(TAG, "网络监听已启动")
            return
        }
        
        Log.i(TAG, "启动网络监听")
        
        listeners.add(onNetworkChange)
        isStarted = true
        
        // 检查当前网络状态
        isNetworkAvailable = checkNetworkAvailable()
        
        // 注册网络回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerNetworkCallback()
        }
        
        // 通知初始状态
        notifyListeners(isNetworkAvailable)
    }
    
    /**
     * 停止网络监听
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "停止网络监听")
        
        isStarted = false
        listeners.clear()
        
        // 注销网络回调
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "注销网络回调失败", e)
            }
        }
        networkCallback = null
    }
    
    /**
     * 添加网络状态变化监听器
     */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        
        // 立即通知当前状态
        if (isStarted) {
            listener(isNetworkAvailable)
        }
    }
    
    /**
     * 移除网络状态变化监听器
     */
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
    
    /**
     * 检查网络是否可用
     */
    fun isNetworkAvailable(): Boolean {
        return if (isStarted) {
            isNetworkAvailable
        } else {
            checkNetworkAvailable()
        }
    }
    
    /**
     * 注册网络回调（Android N及以上）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "网络可用")
                handleNetworkChange(true)
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "网络丢失")
                handleNetworkChange(false)
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                Log.d(TAG, "网络能力变化: hasInternet=$hasInternet, validated=$validated")
                handleNetworkChange(hasInternet && validated)
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
            networkCallback = null
        }
    }
    
    /**
     * 处理网络状态变化
     */
    private fun handleNetworkChange(available: Boolean) {
        if (isNetworkAvailable == available) {
            // 状态未变化，不通知
            return
        }
        
        Log.i(TAG, "网络状态变化: $available")
        isNetworkAvailable = available
        
        notifyListeners(available)
    }
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners(available: Boolean) {
        for (listener in listeners) {
            try {
                listener(available)
            } catch (e: Exception) {
                Log.e(TAG, "通知网络状态变化失败", e)
            }
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private fun checkNetworkAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查网络可用性失败", e)
            false
        }
    }
}

