package org.thoughtcrime.securesms.tap.polling

/**
 * Tap轮询系统常量配置
 * 
 * 集中管理轮询系统中的所有配置常量，避免硬编码分散和重复定义。
 * 所有轮询相关的组件都应从此处读取配置，便于统一管理和调整。
 */
object TapPollingConstants {
    
    // ==================== Provider配置 ====================
    
    /**
     * Provider特定的基础轮询间隔（毫秒）
     * 根据不同存储服务的特性和性能优化设置
     */
    object ProviderIntervals {
        const val COS_BASE_INTERVAL_MS = 1000L        // COS: 5秒，云存储响应快
        const val DEFAULT_BASE_INTERVAL_MS = 30000L   // 未知Provider默认间隔
        
        /**
         * 根据Provider类型获取基础间隔
         */
        fun getBaseInterval(providerType: String): Long {
            return when (providerType.lowercase()) {
                "cos" -> COS_BASE_INTERVAL_MS
                else -> DEFAULT_BASE_INTERVAL_MS
            }
        }
    }
    
    /**
     * Provider轮询间隔限制（毫秒）
     * 防止轮询频率过高或过低
     */
    object ProviderLimits {
        const val COS_MIN_INTERVAL_MS = 1000L         // COS: 最小1秒
        const val COS_MAX_INTERVAL_MS = 300000L       // COS: 最大5分钟
        
        const val DEFAULT_MIN_INTERVAL_MS = 5000L     // 默认最小间隔
        const val DEFAULT_MAX_INTERVAL_MS = 600000L   // 默认最大间隔
        
        /**
         * 获取Provider的间隔限制
         */
        fun getLimits(providerType: String): Pair<Long, Long> {
            return when (providerType.lowercase()) {
                "cos" -> COS_MIN_INTERVAL_MS to COS_MAX_INTERVAL_MS
                else -> DEFAULT_MIN_INTERVAL_MS to DEFAULT_MAX_INTERVAL_MS
            }
        }
    }
    
    // ==================== 活跃度级别配置 ====================
    
    /**
     * 传输活跃度判断阈值
     * 基于最后活动时间划分活跃度级别
     */
    object ActivityThresholds {
        const val ACTIVE_THRESHOLD_MS = 5 * 60 * 1000L           // 5分钟内视为活跃
        const val INACTIVE_THRESHOLD_MS = 60 * 60 * 1000L        // 1小时内视为非活跃
        const val BACKGROUND_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24小时内视为后台
        const val SUSPENDED_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L // 7天内视为暂停
        // 超过7天视为休眠
    }
    
    /**
     * 活跃度级别对应的基础间隔倍数
     */
    object ActivityMultipliers {
        const val ACTIVE_MULTIPLIER = 1.0        // 活跃级别：标准频率
        const val INACTIVE_MULTIPLIER = 1.5      // 非活跃：降低33%频率
        const val BACKGROUND_MULTIPLIER = 2.0    // 后台：降低50%频率
        const val SUSPENDED_MULTIPLIER = 4.0     // 暂停：降低75%频率
        const val DORMANT_MULTIPLIER = 8.0       // 休眠：降低87.5%频率
    }
    
    // ==================== 错误处理配置 ====================
    
    /**
     * 错误退避算法配置
     */
    object ErrorBackoff {
        const val MAX_CONSECUTIVE_ERRORS = 10         // 最大连续错误次数
        const val BASE_BACKOFF_MS = 2000L            // 基础退避时间: 2秒
        const val MAX_BACKOFF_MS = 180000L           // 最大退避时间: 3分钟
        const val BACKOFF_MULTIPLIER = 2.0           // 指数退避倍数
        
        // 文件处理失败重试配置
        const val MAX_FILE_RETRY_ATTEMPTS = 3        // 最大文件重试次数
        const val FILE_RETRY_BACKOFF_MS = 60000L     // 文件重试退避时间: 1分钟
        const val FILE_FAILURE_EXPIRY_MS = 3600000L  // 文件失败记录过期时间: 1小时
    }
    
    // ==================== 系统负载监控配置 ====================
    
    /**
     * 系统资源使用阈值
     */
    object SystemLoadThresholds {
        const val HIGH_MEMORY_THRESHOLD_KB = 150 * 1024L   // 高内存使用阈值: 150MB
        const val MEDIUM_MEMORY_THRESHOLD_KB = 80 * 1024L  // 中等内存使用阈值: 80MB
    }
    
    // ==================== 统计收集配置 ====================
    
    /**
     * 统计收集器配置
     */
    object Statistics {
        const val MAX_POLL_RECORDS = 1000               // 最大轮询记录数，防止内存溢出
        const val HOURLY_STATS_WINDOW_MS = 3600000L     // 1小时统计窗口
        const val DAILY_STATS_WINDOW_MS = 86400000L     // 24小时统计窗口
        
        const val PEAK_RATE_WINDOW_MS = 5 * 60 * 1000L  // 峰值速率计算窗口: 5分钟
        const val MEMORY_PRESSURE_THRESHOLD_KB = 100 * 1024L // 内存压力阈值: 100MB
        const val STATISTICS_CLEANUP_INTERVAL_MS = 300000L   // 统计数据清理间隔: 5分钟
    }
    
    // ==================== 轮询服务配置 ====================
    
    /**
     * 轮询服务核心配置
     */
    object PollingService {
        const val POLLING_TIMEOUT_MS = 30000L           // 轮询超时: 30秒
        const val CLEANUP_INTERVAL_MS = 300000L         // 清理任务间隔: 5分钟
        
        // 线程池配置
        const val DEFAULT_CORE_POOL_SIZE = 3            // 默认核心线程池大小
        const val DEFAULT_MAX_POOL_SIZE = 8             // 默认最大线程池大小
        const val KEEP_ALIVE_TIME_SECONDS = 60L         // 线程保活时间: 60秒
        
        // 任务清理条件
        const val ERROR_TASK_CLEANUP_HOURS = 24         // 错误任务清理时间: 24小时
        const val DORMANT_TASK_CLEANUP_DAYS = 7         // 休眠任务清理时间: 7天
        const val ERROR_TASK_CLEANUP_MS = ERROR_TASK_CLEANUP_HOURS * 60 * 60 * 1000L
        const val DORMANT_TASK_CLEANUP_MS = DORMANT_TASK_CLEANUP_DAYS * 24 * 60 * 60 * 1000L
        
        // 间隔调整限制
        const val GLOBAL_MIN_INTERVAL_MS = 1000L        // 全局最小间隔: 1秒
        const val GLOBAL_MAX_INTERVAL_5MIN_MS = 300000L // 全局最大间隔: 5分钟
        const val GLOBAL_MAX_INTERVAL_10MIN_MS = 600000L // 全局最大间隔: 10分钟
        const val GLOBAL_MAX_INTERVAL_15MIN_MS = 900000L // 全局最大间隔: 15分钟
        
        // 初始延迟抖动
        const val INITIAL_DELAY_JITTER_MAX_MS = 5000L   // 最大初始延迟抖动: 5秒
        
        // 内存管理配置
        const val MAX_FILE_FAILURE_RECORDS = 500        // 最大文件失败记录数量
        
        // 并发下载配置
        const val MAX_CONCURRENT_DOWNLOADS = 4          // 最大并发下载数量: 4个
        
        // 动态退避配置
        const val EMPTY_POLL_BACKOFF_THRESHOLD = 3      // 连续空轮询阈值: 3次
        const val EMPTY_POLL_BACKOFF_MULTIPLIER = 1.5   // 空轮询退避倍数: 1.5x
        const val MAX_EMPTY_POLL_INTERVAL_MS = 10000L   // 空轮询最大间隔: 10秒
    }
    
    // ==================== Token管理配置 ====================
    
    /**
     * Token过期处理配置
     */
    object TokenManagement {
        const val TOKEN_EXPIRY_WARNING_MS = 300000L     // Token过期预警时间: 5分钟
        const val TOKEN_REFRESH_RETRY_ATTEMPTS = 3      // Token刷新重试次数
        const val TOKEN_REFRESH_BACKOFF_MS = 30000L     // Token刷新退避时间: 30秒
    }
} 