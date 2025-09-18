package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log

/**
 * COS发送配置管理器
 * 负责管理COS消息发送的各种配置选项和用户偏好
 */
class CosSendConfigManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosSendConfigManager::class.java)
        private const val PREFS_NAME = "cos_send_config"
        
        // 配置键名
        private const val KEY_COS_SEND_ENABLED = "cos_send_enabled"
        private const val KEY_AUTO_FALLBACK_ENABLED = "auto_fallback_enabled"
        private const val KEY_MAX_MESSAGE_SIZE = "max_message_size"
        private const val KEY_MAX_ATTACHMENT_SIZE = "max_attachment_size"
        private const val KEY_RETRY_COUNT = "retry_count"
        private const val KEY_RETRY_DELAY_BASE = "retry_delay_base"
        private const val KEY_SEND_TIMEOUT = "send_timeout"
        private const val KEY_PREFER_COS_FOR_LARGE_FILES = "prefer_cos_for_large_files"
        private const val KEY_COS_SEND_STATISTICS_ENABLED = "cos_send_statistics_enabled"
        private const val KEY_DEBUG_MODE_ENABLED = "debug_mode_enabled"
        
        // 默认值
        private const val DEFAULT_COS_SEND_ENABLED = true
        private const val DEFAULT_AUTO_FALLBACK_ENABLED = true
        private const val DEFAULT_MAX_MESSAGE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val DEFAULT_MAX_ATTACHMENT_SIZE = 100 * 1024 * 1024L // 100MB
        private const val DEFAULT_RETRY_COUNT = 3
        private const val DEFAULT_RETRY_DELAY_BASE = 5000L // 5秒
        private const val DEFAULT_SEND_TIMEOUT = 5 * 60 * 1000L // 5分钟
        private const val DEFAULT_PREFER_COS_FOR_LARGE_FILES = true
        private const val DEFAULT_COS_SEND_STATISTICS_ENABLED = true
        private const val DEFAULT_DEBUG_MODE_ENABLED = false
        
        @Volatile
        private var INSTANCE: CosSendConfigManager? = null
        
        fun getInstance(context: Context): CosSendConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosSendConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * COS发送是否启用
     */
    var isCosSeendEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_COS_SEND_ENABLED, DEFAULT_COS_SEND_ENABLED)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_COS_SEND_ENABLED, value).apply()
            Log.i(TAG, "COS发送启用状态更新: $value")
        }
    
    /**
     * 自动回退到Signal Server是否启用
     */
    var isAutoFallbackEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_FALLBACK_ENABLED, DEFAULT_AUTO_FALLBACK_ENABLED)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_AUTO_FALLBACK_ENABLED, value).apply()
            Log.i(TAG, "自动回退启用状态更新: $value")
        }
    
    /**
     * 最大消息大小（字节）
     */
    var maxMessageSize: Long
        get() = sharedPreferences.getLong(KEY_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE)
        set(value) {
            sharedPreferences.edit().putLong(KEY_MAX_MESSAGE_SIZE, value).apply()
            Log.i(TAG, "最大消息大小更新: $value")
        }
    
    /**
     * 最大附件大小（字节）
     */
    var maxAttachmentSize: Long
        get() = sharedPreferences.getLong(KEY_MAX_ATTACHMENT_SIZE, DEFAULT_MAX_ATTACHMENT_SIZE)
        set(value) {
            sharedPreferences.edit().putLong(KEY_MAX_ATTACHMENT_SIZE, value).apply()
            Log.i(TAG, "最大附件大小更新: $value")
        }
    
    /**
     * 最大重试次数
     */
    var maxRetryCount: Int
        get() = sharedPreferences.getInt(KEY_RETRY_COUNT, DEFAULT_RETRY_COUNT)
        set(value) {
            sharedPreferences.edit().putInt(KEY_RETRY_COUNT, value).apply()
            Log.i(TAG, "最大重试次数更新: $value")
        }
    
    /**
     * 重试基础延迟（毫秒）
     */
    var retryDelayBase: Long
        get() = sharedPreferences.getLong(KEY_RETRY_DELAY_BASE, DEFAULT_RETRY_DELAY_BASE)
        set(value) {
            sharedPreferences.edit().putLong(KEY_RETRY_DELAY_BASE, value).apply()
            Log.i(TAG, "重试基础延迟更新: $value")
        }
    
    /**
     * 发送超时时间（毫秒）
     */
    var sendTimeout: Long
        get() = sharedPreferences.getLong(KEY_SEND_TIMEOUT, DEFAULT_SEND_TIMEOUT)
        set(value) {
            sharedPreferences.edit().putLong(KEY_SEND_TIMEOUT, value).apply()
            Log.i(TAG, "发送超时时间更新: $value")
        }
    
    /**
     * 大文件优先使用COS
     */
    var preferCosForLargeFiles: Boolean
        get() = sharedPreferences.getBoolean(KEY_PREFER_COS_FOR_LARGE_FILES, DEFAULT_PREFER_COS_FOR_LARGE_FILES)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_PREFER_COS_FOR_LARGE_FILES, value).apply()
            Log.i(TAG, "大文件优先COS状态更新: $value")
        }
    
    /**
     * COS发送统计是否启用
     */
    var isCosStatisticsEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_COS_SEND_STATISTICS_ENABLED, DEFAULT_COS_SEND_STATISTICS_ENABLED)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_COS_SEND_STATISTICS_ENABLED, value).apply()
            Log.i(TAG, "COS统计启用状态更新: $value")
        }
    
    /**
     * 调试模式是否启用
     */
    var isDebugModeEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DEBUG_MODE_ENABLED, DEFAULT_DEBUG_MODE_ENABLED)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_DEBUG_MODE_ENABLED, value).apply()
            Log.i(TAG, "调试模式启用状态更新: $value")
        }
    
    /**
     * 获取完整配置信息
     */
    fun getConfigInfo(): CosSendConfigInfo {
        return CosSendConfigInfo(
            cosSeendEnabled = isCosSeendEnabled,
            autoFallbackEnabled = isAutoFallbackEnabled,
            maxMessageSize = maxMessageSize,
            maxAttachmentSize = maxAttachmentSize,
            maxRetryCount = maxRetryCount,
            retryDelayBase = retryDelayBase,
            sendTimeout = sendTimeout,
            preferCosForLargeFiles = preferCosForLargeFiles,
            cosStatisticsEnabled = isCosStatisticsEnabled,
            debugModeEnabled = isDebugModeEnabled
        )
    }
    
    /**
     * 批量更新配置
     */
    fun updateConfig(config: CosSendConfigInfo) {
        sharedPreferences.edit().apply {
            putBoolean(KEY_COS_SEND_ENABLED, config.cosSeendEnabled)
            putBoolean(KEY_AUTO_FALLBACK_ENABLED, config.autoFallbackEnabled)
            putLong(KEY_MAX_MESSAGE_SIZE, config.maxMessageSize)
            putLong(KEY_MAX_ATTACHMENT_SIZE, config.maxAttachmentSize)
            putInt(KEY_RETRY_COUNT, config.maxRetryCount)
            putLong(KEY_RETRY_DELAY_BASE, config.retryDelayBase)
            putLong(KEY_SEND_TIMEOUT, config.sendTimeout)
            putBoolean(KEY_PREFER_COS_FOR_LARGE_FILES, config.preferCosForLargeFiles)
            putBoolean(KEY_COS_SEND_STATISTICS_ENABLED, config.cosStatisticsEnabled)
            putBoolean(KEY_DEBUG_MODE_ENABLED, config.debugModeEnabled)
        }.apply()
        
        Log.i(TAG, "批量更新配置完成")
    }
    
    /**
     * 重置为默认配置
     */
    fun resetToDefaults() {
        sharedPreferences.edit().clear().apply()
        Log.i(TAG, "配置已重置为默认值")
    }
    
    /**
     * 验证配置有效性
     */
    fun validateConfig(): List<String> {
        val errors = mutableListOf<String>()
        
        if (maxMessageSize <= 0) {
            errors.add("最大消息大小必须大于0")
        }
        
        if (maxAttachmentSize <= 0) {
            errors.add("最大附件大小必须大于0")
        }
        
        if (maxRetryCount < 0) {
            errors.add("最大重试次数不能为负数")
        }
        
        if (retryDelayBase <= 0) {
            errors.add("重试基础延迟必须大于0")
        }
        
        if (sendTimeout <= 0) {
            errors.add("发送超时时间必须大于0")
        }
        
        if (maxMessageSize > 1024 * 1024 * 1024L) { // 1GB
            errors.add("最大消息大小不应超过1GB")
        }
        
        if (maxAttachmentSize > 10L * 1024 * 1024 * 1024) { // 10GB
            errors.add("最大附件大小不应超过10GB")
        }
        
        return errors
    }
    
    /**
     * 获取推荐配置（基于设备性能和网络状况）
     */
    fun getRecommendedConfig(): CosSendConfigInfo {
        // 这里可以根据设备性能、网络状况等因素提供推荐配置
        return CosSendConfigInfo(
            cosSeendEnabled = true,
            autoFallbackEnabled = true,
            maxMessageSize = 50 * 1024 * 1024L, // 50MB
            maxAttachmentSize = 200 * 1024 * 1024L, // 200MB
            maxRetryCount = 3,
            retryDelayBase = 3000L, // 3秒
            sendTimeout = 10 * 60 * 1000L, // 10分钟
            preferCosForLargeFiles = true,
            cosStatisticsEnabled = true,
            debugModeEnabled = false
        )
    }
    
    /**
     * 导出配置为JSON字符串
     */
    fun exportConfig(): String {
        val config = getConfigInfo()
        // TODO: 实现JSON序列化
        return config.toString()
    }
    
    /**
     * 从JSON字符串导入配置
     */
    fun importConfig(jsonString: String): Boolean {
        return try {
            // TODO: 实现JSON反序列化
            Log.i(TAG, "配置导入成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "配置导入失败", e)
            false
        }
    }
}

/**
 * COS发送配置信息
 */
data class CosSendConfigInfo(
    val cosSeendEnabled: Boolean,
    val autoFallbackEnabled: Boolean,
    val maxMessageSize: Long,
    val maxAttachmentSize: Long,
    val maxRetryCount: Int,
    val retryDelayBase: Long,
    val sendTimeout: Long,
    val preferCosForLargeFiles: Boolean,
    val cosStatisticsEnabled: Boolean,
    val debugModeEnabled: Boolean
) {
    /**
     * 获取配置摘要
     */
    fun getSummary(): String {
        return "COS发送: ${if (cosSeendEnabled) "启用" else "禁用"}, " +
                "自动回退: ${if (autoFallbackEnabled) "启用" else "禁用"}, " +
                "最大消息: ${maxMessageSize / 1024 / 1024}MB, " +
                "最大附件: ${maxAttachmentSize / 1024 / 1024}MB, " +
                "重试次数: $maxRetryCount"
    }
}
