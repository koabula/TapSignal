package org.thoughtcrime.securesms.tap.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.polling.NetworkQuality
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 网络质量检测器
 * 
 * 负责检测和监控当前网络质量，包括网络类型、连接速度、延迟等
 * 为轮询调度和传输优化提供准确的网络状态信息
 */
class NetworkQualityDetector private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(NetworkQualityDetector::class.java)
        
        // 网络质量判断阈值
        private const val EXCELLENT_LATENCY_MS = 50L
        private const val GOOD_LATENCY_MS = 200L
        private const val FAIR_LATENCY_MS = 500L
        private const val POOR_LATENCY_MS = 1000L
        
        // 测试服务器（使用可靠的公共服务器）
        private val TEST_SERVERS = listOf(
            "8.8.8.8" to 53,          // Google DNS
            "1.1.1.1" to 53,          // Cloudflare DNS
            "208.67.222.222" to 53    // OpenDNS
        )
        
        // 网络监控更新间隔
        private const val NETWORK_MONITOR_INTERVAL_MS = 30000L // 30秒
        
        @Volatile
        private var INSTANCE: NetworkQualityDetector? = null
        
        fun getInstance(context: Context): NetworkQualityDetector {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkQualityDetector(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    
    // 当前网络质量状态
    private val currentNetworkQuality = AtomicReference(NetworkQuality.UNKNOWN)
    private val networkQualityLock = ReentrantReadWriteLock()
    private var lastQualityCheck = 0L
    
    // 网络监控
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    
    /**
     * 获取当前网络质量
     */
    fun getCurrentNetworkQuality(): NetworkQuality {
        networkQualityLock.read {
            val currentTime = System.currentTimeMillis()
            
            // 如果缓存的质量检测结果还新鲜（30秒内），直接返回
            if (currentTime - lastQualityCheck < NETWORK_MONITOR_INTERVAL_MS) {
                return currentNetworkQuality.get()
            }
            
            // 否则触发新的质量检测
            return NetworkQuality.UNKNOWN
        }
    }
    
    /**
     * 异步更新网络质量
     */
    suspend fun updateNetworkQuality(): NetworkQuality {
        return withContext(Dispatchers.IO) {
            networkQualityLock.write {
                try {
                    Log.d(TAG, "开始更新网络质量检测")
                    
                    val quality = detectNetworkQuality()
                    currentNetworkQuality.set(quality)
                    lastQualityCheck = System.currentTimeMillis()
                    
                    Log.i(TAG, "网络质量更新完成: $quality")
                    quality
                    
                } catch (e: Exception) {
                    Log.e(TAG, "网络质量检测失败", e)
                    val fallbackQuality = getFallbackNetworkQuality()
                    currentNetworkQuality.set(fallbackQuality)
                    lastQualityCheck = System.currentTimeMillis()
                    fallbackQuality
                }
            }
        }
    }
    
    /**
     * 检测网络质量
     */
    private suspend fun detectNetworkQuality(): NetworkQuality {
        return withContext(Dispatchers.IO) {
            // 1. 检查网络连接可用性
            if (!isNetworkAvailable()) {
                Log.d(TAG, "网络不可用")
                return@withContext NetworkQuality.NO_CONNECTION
            }
            
            // 2. 检测网络类型
            val networkType = detectNetworkType()
            Log.d(TAG, "检测到网络类型: $networkType")
            
            // 3. 测试网络延迟
            val latency = measureNetworkLatency()
            Log.d(TAG, "网络延迟: ${latency}ms")
            
            // 4. 检测网络带宽能力
            val bandwidthCapability = detectBandwidthCapability()
            Log.d(TAG, "带宽能力: $bandwidthCapability")
            
            // 5. 综合判断网络质量
            determineNetworkQuality(networkType, latency, bandwidthCapability)
        }
    }
    
    /**
     * 检查网络可用性
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                Log.d(TAG, "无活跃网络")
                return false
            }
            
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities == null) {
                Log.d(TAG, "无法获取网络能力信息")
                return false
            }
            
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            Log.d(TAG, "网络状态: hasInternet=$hasInternet, isValidated=$isValidated")
            hasInternet && isValidated
            
        } catch (e: Exception) {
            Log.e(TAG, "检查网络可用性异常", e)
            false
        }
    }
    
    /**
     * 检测网络类型
     */
    private fun detectNetworkType(): NetworkType {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            if (networkCapabilities == null) {
                return NetworkType.UNKNOWN
            }
            
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    Log.d(TAG, "WiFi网络")
                    NetworkType.WIFI
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // 进一步检测移动网络类型
                    val cellularType = detectCellularType()
                    Log.d(TAG, "移动网络: $cellularType")
                    cellularType
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    Log.d(TAG, "以太网")
                    NetworkType.ETHERNET
                }
                else -> {
                    Log.d(TAG, "未知网络类型")
                    NetworkType.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测网络类型异常", e)
            NetworkType.UNKNOWN
        }
    }
    
    /**
     * 检测移动网络具体类型
     */
    private fun detectCellularType(): NetworkType {
        return try {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.CELLULAR_5G
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.CELLULAR_4G
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> NetworkType.CELLULAR_3G
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> NetworkType.CELLULAR_2G
                else -> NetworkType.CELLULAR_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测移动网络类型异常", e)
            NetworkType.CELLULAR_UNKNOWN
        }
    }
    
    /**
     * 测量网络延迟
     */
    private suspend fun measureNetworkLatency(): Long {
        return withContext(Dispatchers.IO) {
            var totalLatency = 0L
            var successCount = 0
            
            TEST_SERVERS.forEach { (host, port) ->
                try {
                    val startTime = System.currentTimeMillis()
                    
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 3000)
                        val endTime = System.currentTimeMillis()
                        val latency = endTime - startTime
                        
                        totalLatency += latency
                        successCount++
                        
                        Log.d(TAG, "服务器 $host:$port 延迟: ${latency}ms")
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "测试服务器 $host:$port 失败: ${e.message}")
                }
            }
            
            if (successCount == 0) {
                Log.w(TAG, "所有延迟测试都失败")
                return@withContext POOR_LATENCY_MS
            }
            
            val averageLatency = totalLatency / successCount
            Log.d(TAG, "平均延迟: ${averageLatency}ms (测试成功: $successCount/${TEST_SERVERS.size})")
            averageLatency
        }
    }
    
    /**
     * 检测带宽能力
     */
    private fun detectBandwidthCapability(): BandwidthCapability {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            if (networkCapabilities == null) {
                return BandwidthCapability.UNKNOWN
            }
            
            val downstreamKbps = networkCapabilities.linkDownstreamBandwidthKbps
            val upstreamKbps = networkCapabilities.linkUpstreamBandwidthKbps
            
            Log.d(TAG, "带宽信息: 下行=${downstreamKbps}Kbps, 上行=${upstreamKbps}Kbps")
            
            when {
                downstreamKbps >= 10000 && upstreamKbps >= 5000 -> BandwidthCapability.HIGH  // >= 10Mbps下行, 5Mbps上行
                downstreamKbps >= 5000 && upstreamKbps >= 2000 -> BandwidthCapability.MEDIUM  // >= 5Mbps下行, 2Mbps上行
                downstreamKbps >= 1000 && upstreamKbps >= 500 -> BandwidthCapability.LOW      // >= 1Mbps下行, 500Kbps上行
                else -> BandwidthCapability.VERY_LOW
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "检测带宽能力异常", e)
            BandwidthCapability.UNKNOWN
        }
    }
    
    /**
     * 综合判断网络质量
     */
    private fun determineNetworkQuality(
        networkType: NetworkType,
        latency: Long,
        bandwidthCapability: BandwidthCapability
    ): NetworkQuality {
        
        // 基于延迟的基础评分
        val latencyScore = when {
            latency <= EXCELLENT_LATENCY_MS -> 4
            latency <= GOOD_LATENCY_MS -> 3
            latency <= FAIR_LATENCY_MS -> 2
            latency <= POOR_LATENCY_MS -> 1
            else -> 0
        }
        
        // 基于网络类型的调整
        val networkTypeScore = when (networkType) {
            NetworkType.WIFI, NetworkType.ETHERNET -> 4
            NetworkType.CELLULAR_5G -> 4
            NetworkType.CELLULAR_4G -> 3
            NetworkType.CELLULAR_3G -> 2
            NetworkType.CELLULAR_2G -> 1
            else -> 1
        }
        
        // 基于带宽能力的调整
        val bandwidthScore = when (bandwidthCapability) {
            BandwidthCapability.HIGH -> 4
            BandwidthCapability.MEDIUM -> 3
            BandwidthCapability.LOW -> 2
            BandwidthCapability.VERY_LOW -> 1
            else -> 2
        }
        
        // 综合评分（加权平均）
        val totalScore = (latencyScore * 0.5 + networkTypeScore * 0.3 + bandwidthScore * 0.2).toInt()
        
        val quality = when {
            totalScore >= 4 -> NetworkQuality.EXCELLENT
            totalScore >= 3 -> NetworkQuality.GOOD
            totalScore >= 2 -> NetworkQuality.FAIR
            totalScore >= 1 -> NetworkQuality.POOR
            else -> NetworkQuality.VERY_POOR
        }
        
        Log.i(TAG, "网络质量判断: 延迟评分=$latencyScore, 网络类型评分=$networkTypeScore, 带宽评分=$bandwidthScore, 综合评分=$totalScore, 质量=$quality")
        
        return quality
    }
    
    /**
     * 获取备用网络质量（当检测失败时）
     */
    private fun getFallbackNetworkQuality(): NetworkQuality {
        return try {
            // 基于网络类型做简单判断
            val networkType = detectNetworkType()
            when (networkType) {
                NetworkType.WIFI, NetworkType.ETHERNET -> NetworkQuality.GOOD
                NetworkType.CELLULAR_5G -> NetworkQuality.GOOD
                NetworkType.CELLULAR_4G -> NetworkQuality.FAIR
                NetworkType.CELLULAR_3G -> NetworkQuality.POOR
                NetworkType.CELLULAR_2G -> NetworkQuality.VERY_POOR
                else -> NetworkQuality.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取备用网络质量失败", e)
            NetworkQuality.UNKNOWN
        }
    }
    
    /**
     * 开始网络监控
     */
    fun startNetworkMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "网络监控已在运行")
            return
        }
        
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "网络连接可用: $network")
                    // 异步更新网络质量
                    GlobalScope.launch {
                        updateNetworkQuality()
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "网络连接丢失: $network")
                    currentNetworkQuality.set(NetworkQuality.NO_CONNECTION)
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    Log.d(TAG, "网络能力变化: $network")
                    // 异步更新网络质量
                    GlobalScope.launch {
                        updateNetworkQuality()
                    }
                }
            }
            
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            isMonitoring = true
            Log.i(TAG, "网络监控启动成功")
            
        } catch (e: Exception) {
            Log.e(TAG, "启动网络监控失败", e)
        }
    }
    
    /**
     * 停止网络监控
     */
    fun stopNetworkMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        try {
            networkCallback?.let { callback ->
                connectivityManager.unregisterNetworkCallback(callback)
            }
            networkCallback = null
            isMonitoring = false
            Log.i(TAG, "网络监控已停止")
            
        } catch (e: Exception) {
            Log.e(TAG, "停止网络监控失败", e)
        }
    }
}

/**
 * 网络类型枚举
 */
enum class NetworkType {
    WIFI,
    ETHERNET,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_3G,
    CELLULAR_2G,
    CELLULAR_UNKNOWN,
    UNKNOWN
}

/**
 * 带宽能力枚举
 */
enum class BandwidthCapability {
    HIGH,       // 高带宽
    MEDIUM,     // 中等带宽
    LOW,        // 低带宽
    VERY_LOW,   // 极低带宽
    UNKNOWN     // 未知
} 