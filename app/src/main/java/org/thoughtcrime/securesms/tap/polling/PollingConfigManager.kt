package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * 轮询配置管理器
 * 
 * 提供动态配置管理功能，支持运行时配置更新和设备性能自适应调整。
 * 配置来源优先级：运行时设置 > 本地配置文件 > 设备自适应 > 默认值
 */
class PollingConfigManager private constructor(
    private val context: Context,
    private val deviceCapabilityProvider: DeviceCapabilityProvider
) {
    
    companion object {
        private const val TAG = "PollingConfigManager"
        private const val PREFS_NAME = "tap_polling_config"
        
        // 单例实例
        @Volatile
        private var INSTANCE: PollingConfigManager? = null
        
        /**
         * 获取单例实例
         */
        @JvmStatic
        fun getInstance(context: Context, deviceCapabilityProvider: DeviceCapabilityProvider): PollingConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PollingConfigManager(
                    context.applicationContext, 
                    deviceCapabilityProvider
                ).also { INSTANCE = it }
            }
        }
    }
    
    // 配置存储
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val runtimeConfig = ConcurrentHashMap<String, Any>()
    
    // 配置缓存
    private val configCache = AtomicReference<PollingConfigSnapshot?>(null)
    
    // 配置变更监听器
    private val configChangeListeners = mutableSetOf<ConfigChangeListener>()
    
    // 初始化标记
    @Volatile
    private var isInitialized = false
    
    /**
     * 初始化配置管理器
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }
        
        return try {
            Log.i(TAG, "初始化轮询配置管理器")
            
            // 加载配置
            reloadConfiguration()
            
            // 注册设备状态变化监听
            registerDeviceStateListener()
            
            isInitialized = true
            Log.i(TAG, "轮询配置管理器初始化成功")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "配置管理器初始化失败", e)
            false
        }
    }
    
    /**
     * 重新加载配置
     */
    fun reloadConfiguration() {
        try {
            Log.d(TAG, "重新加载配置")
            
            val deviceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
            val snapshot = buildConfigurationSnapshot(deviceLevel)
            
            configCache.set(snapshot)
            
            // 通知配置变更
            notifyConfigurationChanged(snapshot)
            
            Log.i(TAG, "配置重新加载完成: deviceLevel=$deviceLevel")
            
        } catch (e: Exception) {
            Log.e(TAG, "重新加载配置失败", e)
        }
    }
    
    /**
     * 获取当前配置快照
     */
    fun getCurrentConfig(): PollingConfigSnapshot {
        return configCache.get() ?: run {
            // 如果缓存为空，重新构建
            reloadConfiguration()
            configCache.get() ?: getDefaultConfig()
        }
    }
    
    /**
     * 更新运行时配置
     */
    fun updateRuntimeConfig(key: String, value: Any) {
        Log.d(TAG, "更新运行时配置: $key = $value")
        
        runtimeConfig[key] = value
        
        // 重新计算配置快照
        reloadConfiguration()
    }
    
    /**
     * 批量更新运行时配置
     */
    fun updateRuntimeConfigs(configs: Map<String, Any>) {
        Log.d(TAG, "批量更新运行时配置: ${configs.size}项")
        
        runtimeConfig.putAll(configs)
        reloadConfiguration()
    }
    
    /**
     * 保存配置到持久化存储
     */
    fun saveConfigToPersistent(configs: Map<String, Any>) {
        try {
            val editor = preferences.edit()
            
            configs.forEach { (key, value) ->
                when (value) {
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    else -> editor.putString(key, value.toString())
                }
            }
            
            editor.apply()
            
            Log.i(TAG, "配置已保存到持久化存储: ${configs.size}项")
            
        } catch (e: Exception) {
            Log.e(TAG, "保存配置到持久化存储失败", e)
        }
    }
    
    /**
     * 清除运行时配置
     */
    fun clearRuntimeConfig() {
        runtimeConfig.clear()
        reloadConfiguration()
        
        Log.d(TAG, "已清除运行时配置")
    }
    
    /**
     * 重置到默认配置
     */
    fun resetToDefault() {
        clearRuntimeConfig()
        preferences.edit().clear().apply()
        reloadConfiguration()
        
        Log.i(TAG, "已重置到默认配置")
    }
    
    /**
     * 添加配置变更监听器
     */
    fun addConfigChangeListener(listener: ConfigChangeListener) {
        synchronized(configChangeListeners) {
            configChangeListeners.add(listener)
        }
    }
    
    /**
     * 移除配置变更监听器
     */
    fun removeConfigChangeListener(listener: ConfigChangeListener) {
        synchronized(configChangeListeners) {
            configChangeListeners.remove(listener)
        }
    }
    
    /**
     * 获取配置摘要
     */
    fun getConfigSummary(): String {
        val config = getCurrentConfig()
        return "PollingConfig[device=${deviceCapabilityProvider.getDevicePerformanceLevel()}, " +
                "baseInterval=${config.baseIntervals}, " +
                "threadPool=${config.threadPoolConfig.getSummary()}, " +
                "errorBackoff=${config.errorBackoffConfig}, " +
                "memoryThresholds=${config.memoryThresholds}]"
    }
    
    // === 私有方法实现 ===
    
    /**
     * 构建配置快照
     */
    private fun buildConfigurationSnapshot(deviceLevel: DevicePerformanceLevel): PollingConfigSnapshot {
        return PollingConfigSnapshot(
            deviceLevel = deviceLevel,
            baseIntervals = buildBaseIntervals(),
            intervalLimits = buildIntervalLimits(),
            activityThresholds = buildActivityThresholds(),
            activityMultipliers = buildActivityMultipliers(),
            errorBackoffConfig = buildErrorBackoffConfig(),
            memoryThresholds = buildMemoryThresholds(),
            threadPoolConfig = buildThreadPoolConfig(deviceLevel),
            statisticsConfig = buildStatisticsConfig(),
            cleanupConfig = buildCleanupConfig()
        )
    }
    
    /**
     * 构建基础间隔配置
     */
    private fun buildBaseIntervals(): BaseIntervalsConfig {
        val cosInterval = getRuntimeConfigOrDefault(
            "base_interval_cos", 
            preferences.getLong("base_interval_cos", getDefaultCosInterval())
        )
        
        val defaultInterval = getRuntimeConfigOrDefault(
            "base_interval_default",
            preferences.getLong("base_interval_default", getDefaultBaseInterval())
        )
        
        return BaseIntervalsConfig(
            cosBaseInterval = cosInterval,
            defaultBaseInterval = defaultInterval
        )
    }
    
    /**
     * 构建间隔限制配置
     */
    private fun buildIntervalLimits(): IntervalLimitsConfig {
        val deviceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
        
        // 根据设备性能调整限制
        val (cosMin, cosMax) = when (deviceLevel) {
            DevicePerformanceLevel.LOW -> Pair(3000L, 600000L)      // 低端设备：3秒-10分钟
            DevicePerformanceLevel.MEDIUM -> Pair(2000L, 300000L)   // 中端设备：2秒-5分钟
            DevicePerformanceLevel.HIGH -> Pair(1000L, 300000L)     // 高端设备：1秒-5分钟
        }
        
        return IntervalLimitsConfig(
            cosMinInterval = getRuntimeConfigOrDefault("cos_min_interval", cosMin),
            cosMaxInterval = getRuntimeConfigOrDefault("cos_max_interval", cosMax),
            defaultMinInterval = getRuntimeConfigOrDefault("default_min_interval", 5000L),
            defaultMaxInterval = getRuntimeConfigOrDefault("default_max_interval", 600000L)
        )
    }
    
    /**
     * 构建活跃度阈值配置
     */
    private fun buildActivityThresholds(): ActivityThresholdsConfig {
        return ActivityThresholdsConfig(
            activeThreshold = getRuntimeConfigOrDefault("active_threshold", 5 * 60 * 1000L),
            inactiveThreshold = getRuntimeConfigOrDefault("inactive_threshold", 60 * 60 * 1000L),
            backgroundThreshold = getRuntimeConfigOrDefault("background_threshold", 24 * 60 * 60 * 1000L),
            suspendedThreshold = getRuntimeConfigOrDefault("suspended_threshold", 7 * 24 * 60 * 60 * 1000L)
        )
    }
    
    /**
     * 构建活跃度倍数配置
     */
    private fun buildActivityMultipliers(): ActivityMultipliersConfig {
        val deviceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
        
        // 根据设备性能调整倍数
        val adjustmentFactor = when (deviceLevel) {
            DevicePerformanceLevel.LOW -> 1.5       // 低端设备降低频率
            DevicePerformanceLevel.MEDIUM -> 1.2    // 中端设备适当降频
            DevicePerformanceLevel.HIGH -> 1.0      // 高端设备保持标准频率
        }
        
        return ActivityMultipliersConfig(
            activeMultiplier = getRuntimeConfigOrDefault("active_multiplier", 1.0 * adjustmentFactor),
            inactiveMultiplier = getRuntimeConfigOrDefault("inactive_multiplier", 1.5 * adjustmentFactor),
            backgroundMultiplier = getRuntimeConfigOrDefault("background_multiplier", 2.0 * adjustmentFactor),
            suspendedMultiplier = getRuntimeConfigOrDefault("suspended_multiplier", 4.0 * adjustmentFactor),
            dormantMultiplier = getRuntimeConfigOrDefault("dormant_multiplier", 8.0 * adjustmentFactor)
        )
    }
    
    /**
     * 构建错误退避配置
     */
    private fun buildErrorBackoffConfig(): ErrorBackoffConfig {
        return ErrorBackoffConfig(
            maxConsecutiveErrors = getRuntimeConfigOrDefault("max_consecutive_errors", 10),
            baseBackoffMs = getRuntimeConfigOrDefault("base_backoff_ms", 2000L),
            maxBackoffMs = getRuntimeConfigOrDefault("max_backoff_ms", 180000L),
            backoffMultiplier = getRuntimeConfigOrDefault("backoff_multiplier", 2.0),
            maxFileRetryAttempts = getRuntimeConfigOrDefault("max_file_retry_attempts", 3),
            fileRetryBackoffMs = getRuntimeConfigOrDefault("file_retry_backoff_ms", 60000L),
            fileFailureExpiryMs = getRuntimeConfigOrDefault("file_failure_expiry_ms", 3600000L)
        )
    }
    
    /**
     * 构建内存阈值配置
     */
    private fun buildMemoryThresholds(): MemoryThresholdsConfig {
        val deviceMemoryMB = deviceCapabilityProvider.getTotalMemoryMB()
        
        // 根据设备内存动态调整阈值
        val highMemoryThreshold = when {
            deviceMemoryMB < 1024 -> 80 * 1024L     // 1GB以下：80MB
            deviceMemoryMB < 2048 -> 120 * 1024L    // 2GB以下：120MB
            else -> 150 * 1024L                     // 2GB以上：150MB
        }
        
        val mediumMemoryThreshold = (highMemoryThreshold * 0.6).toLong()
        
        return MemoryThresholdsConfig(
            highMemoryThresholdKB = getRuntimeConfigOrDefault("high_memory_threshold", highMemoryThreshold),
            mediumMemoryThresholdKB = getRuntimeConfigOrDefault("medium_memory_threshold", mediumMemoryThreshold),
            memoryPressureThresholdKB = getRuntimeConfigOrDefault("memory_pressure_threshold", 100 * 1024L)
        )
    }
    
    /**
     * 构建线程池配置
     */
    private fun buildThreadPoolConfig(deviceLevel: DevicePerformanceLevel): ThreadPoolConfig {
        // 使用设备性能检测器计算最优配置
        return deviceCapabilityProvider.calculateOptimalThreadPoolSize()
    }
    
    /**
     * 构建统计配置
     */
    private fun buildStatisticsConfig(): StatisticsConfig {
        return StatisticsConfig(
            maxPollRecords = getRuntimeConfigOrDefault("max_poll_records", 1000),
            hourlyStatsWindow = getRuntimeConfigOrDefault("hourly_stats_window", 3600000L),
            dailyStatsWindow = getRuntimeConfigOrDefault("daily_stats_window", 86400000L),
            peakRateWindow = getRuntimeConfigOrDefault("peak_rate_window", 5 * 60 * 1000L),
            statisticsCleanupInterval = getRuntimeConfigOrDefault("statistics_cleanup_interval", 300000L)
        )
    }
    
    /**
     * 构建清理配置
     */
    private fun buildCleanupConfig(): CleanupConfig {
        return CleanupConfig(
            cleanupInterval = getRuntimeConfigOrDefault("cleanup_interval", 300000L),
            errorTaskCleanupMs = getRuntimeConfigOrDefault("error_task_cleanup_ms", 24 * 60 * 60 * 1000L),
            dormantTaskCleanupMs = getRuntimeConfigOrDefault("dormant_task_cleanup_ms", 7 * 24 * 60 * 60 * 1000L),
            maxFileFailureRecords = getRuntimeConfigOrDefault("max_file_failure_records", 500),
            pollingTimeoutMs = getRuntimeConfigOrDefault("polling_timeout_ms", 30000L),
            initialDelayJitterMaxMs = getRuntimeConfigOrDefault("initial_delay_jitter_max_ms", 5000L)
        )
    }
    
    /**
     * 获取运行时配置或默认值
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getRuntimeConfigOrDefault(key: String, defaultValue: T): T {
        return runtimeConfig[key] as? T ?: defaultValue
    }
    
    /**
     * 获取默认COS间隔
     */
    private fun getDefaultCosInterval(): Long {
        val deviceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
        return when (deviceLevel) {
            DevicePerformanceLevel.LOW -> 10000L     // 10秒
            DevicePerformanceLevel.MEDIUM -> 7000L   // 7秒
            DevicePerformanceLevel.HIGH -> 5000L     // 5秒
        }
    }
    
    /**
     * 获取默认基础间隔
     */
    private fun getDefaultBaseInterval(): Long {
        val deviceLevel = deviceCapabilityProvider.getDevicePerformanceLevel()
        return when (deviceLevel) {
            DevicePerformanceLevel.LOW -> 60000L     // 60秒
            DevicePerformanceLevel.MEDIUM -> 45000L  // 45秒
            DevicePerformanceLevel.HIGH -> 30000L    // 30秒
        }
    }
    
    /**
     * 获取默认配置
     */
    private fun getDefaultConfig(): PollingConfigSnapshot {
        val deviceLevel = DevicePerformanceLevel.MEDIUM // 保守默认值
        return buildConfigurationSnapshot(deviceLevel)
    }
    
    /**
     * 注册设备状态变化监听
     */
    private fun registerDeviceStateListener() {
        // 这里可以注册电池状态、内存状态等变化监听器
        // 当设备状态变化时自动重新加载配置
    }
    
    /**
     * 通知配置变更
     */
    private fun notifyConfigurationChanged(newConfig: PollingConfigSnapshot) {
        synchronized(configChangeListeners) {
            configChangeListeners.forEach { listener ->
                try {
                    listener.onConfigurationChanged(newConfig)
                } catch (e: Exception) {
                    Log.w(TAG, "配置变更监听器异常", e)
                }
            }
        }
    }
}

/**
 * 配置变更监听器
 */
interface ConfigChangeListener {
    fun onConfigurationChanged(newConfig: PollingConfigSnapshot)
}

/**
 * 轮询配置快照
 */
data class PollingConfigSnapshot(
    val deviceLevel: DevicePerformanceLevel,
    val baseIntervals: BaseIntervalsConfig,
    val intervalLimits: IntervalLimitsConfig,
    val activityThresholds: ActivityThresholdsConfig,
    val activityMultipliers: ActivityMultipliersConfig,
    val errorBackoffConfig: ErrorBackoffConfig,
    val memoryThresholds: MemoryThresholdsConfig,
    val threadPoolConfig: ThreadPoolConfig,
    val statisticsConfig: StatisticsConfig,
    val cleanupConfig: CleanupConfig
)

// === 配置数据类定义 ===

data class BaseIntervalsConfig(
    val cosBaseInterval: Long,
    val defaultBaseInterval: Long
)

data class IntervalLimitsConfig(
    val cosMinInterval: Long,
    val cosMaxInterval: Long,
    val defaultMinInterval: Long,
    val defaultMaxInterval: Long
)

data class ActivityThresholdsConfig(
    val activeThreshold: Long,
    val inactiveThreshold: Long,
    val backgroundThreshold: Long,
    val suspendedThreshold: Long
)

data class ActivityMultipliersConfig(
    val activeMultiplier: Double,
    val inactiveMultiplier: Double,
    val backgroundMultiplier: Double,
    val suspendedMultiplier: Double,
    val dormantMultiplier: Double
)

data class ErrorBackoffConfig(
    val maxConsecutiveErrors: Int,
    val baseBackoffMs: Long,
    val maxBackoffMs: Long,
    val backoffMultiplier: Double,
    val maxFileRetryAttempts: Int,
    val fileRetryBackoffMs: Long,
    val fileFailureExpiryMs: Long
)

data class MemoryThresholdsConfig(
    val highMemoryThresholdKB: Long,
    val mediumMemoryThresholdKB: Long,
    val memoryPressureThresholdKB: Long
)

data class StatisticsConfig(
    val maxPollRecords: Int,
    val hourlyStatsWindow: Long,
    val dailyStatsWindow: Long,
    val peakRateWindow: Long,
    val statisticsCleanupInterval: Long
)

data class CleanupConfig(
    val cleanupInterval: Long,
    val errorTaskCleanupMs: Long,
    val dormantTaskCleanupMs: Long,
    val maxFileFailureRecords: Int,
    val pollingTimeoutMs: Long,
    val initialDelayJitterMaxMs: Long
) 