package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.app.ActivityManager
import android.util.Log

/**
 * 设备性能检测工具
 * 
 * 检测设备的硬件性能和系统状态，为轮询系统提供自适应配置依据。
 */
class DeviceCapabilityProvider(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceCapabilityProvider"
        private const val LOW_MEMORY_THRESHOLD_MB = 512L      // 低内存设备阈值: 512MB
        private const val MEDIUM_MEMORY_THRESHOLD_MB = 1024L  // 中等内存设备阈值: 1GB
    }
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    /**
     * 获取CPU核心数
     */
    fun getCpuCores(): Int {
        return try {
            Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Log.w(TAG, "获取CPU核心数失败，使用默认值", e)
            2 // 默认值
        }
    }
    
    /**
     * 获取可用内存大小（MB）
     */
    fun getAvailableMemoryMB(): Long {
        return try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "获取可用内存失败，使用默认值", e)
            1024L // 默认1GB
        }
    }
    
    /**
     * 获取总内存大小（MB）
     */
    fun getTotalMemoryMB(): Long {
        return try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "获取总内存失败，使用默认值", e)
            2048L // 默认2GB
        }
    }
    
    /**
     * 检查是否为低电量模式
     */
    fun isLowPowerMode(): Boolean {
        return try {
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            Log.w(TAG, "检查低电量模式失败", e)
            false
        }
    }
    
    /**
     * 检查设备是否处于内存压力状态
     */
    fun isUnderMemoryPressure(): Boolean {
        return try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.lowMemory
        } catch (e: Exception) {
            Log.w(TAG, "检查内存压力失败", e)
            false
        }
    }
    
    /**
     * 获取设备性能级别
     */
    fun getDevicePerformanceLevel(): DevicePerformanceLevel {
        val totalMemoryMB = getTotalMemoryMB()
        val cpuCores = getCpuCores()
        val isLowPower = isLowPowerMode()
        val memoryPressure = isUnderMemoryPressure()
        
        return when {
            // 低性能设备：内存小于512MB或单核CPU或处于低电量/内存压力状态
            totalMemoryMB < LOW_MEMORY_THRESHOLD_MB || 
            cpuCores <= 1 || 
            isLowPower || 
            memoryPressure -> DevicePerformanceLevel.LOW
            
            // 中等性能设备：内存在512MB-1GB之间或2核CPU
            totalMemoryMB < MEDIUM_MEMORY_THRESHOLD_MB || 
            cpuCores <= 2 -> DevicePerformanceLevel.MEDIUM
            
            // 高性能设备：内存大于1GB且多核CPU
            else -> DevicePerformanceLevel.HIGH
        }
    }
    
    /**
     * 计算最优线程池大小
     */
    fun calculateOptimalThreadPoolSize(): ThreadPoolConfig {
        val performanceLevel = getDevicePerformanceLevel()
        val cpuCores = getCpuCores()
        
        return when (performanceLevel) {
            DevicePerformanceLevel.LOW -> {
                ThreadPoolConfig(
                    corePoolSize = 1,
                    maxPoolSize = 2,
                    keepAliveSeconds = 30L,
                    queueCapacity = 50
                )
            }
            
            DevicePerformanceLevel.MEDIUM -> {
                ThreadPoolConfig(
                    corePoolSize = minOf(2, cpuCores),
                    maxPoolSize = minOf(4, cpuCores + 1),
                    keepAliveSeconds = 60L,
                    queueCapacity = 100
                )
            }
            
            DevicePerformanceLevel.HIGH -> {
                ThreadPoolConfig(
                    corePoolSize = minOf(3, cpuCores),
                    maxPoolSize = minOf(8, cpuCores * 2),
                    keepAliveSeconds = 120L,
                    queueCapacity = 200
                )
            }
        }
    }
    
    /**
     * 获取设备信息摘要
     */
    fun getDeviceSummary(): String {
        return "Device[cores=${getCpuCores()}, memory=${getTotalMemoryMB()}MB, " +
                "performance=${getDevicePerformanceLevel()}, lowPower=${isLowPowerMode()}, " +
                "memoryPressure=${isUnderMemoryPressure()}]"
    }
}

/**
 * 设备性能级别
 */
enum class DevicePerformanceLevel {
    LOW,     // 低性能设备
    MEDIUM,  // 中等性能设备
    HIGH     // 高性能设备
}

/**
 * 线程池配置
 */
data class ThreadPoolConfig(
    val corePoolSize: Int,        // 核心线程池大小
    val maxPoolSize: Int,         // 最大线程池大小
    val keepAliveSeconds: Long,   // 线程保活时间（秒）
    val queueCapacity: Int        // 队列容量
) {
    
    /**
     * 是否为保守配置（适用于低性能设备）
     */
    fun isConservative(): Boolean {
        return corePoolSize <= 1 && maxPoolSize <= 2
    }
    
    /**
     * 获取配置摘要
     */
    fun getSummary(): String {
        return "ThreadPool[core=$corePoolSize, max=$maxPoolSize, keepAlive=${keepAliveSeconds}s, queue=$queueCapacity]"
    }
} 