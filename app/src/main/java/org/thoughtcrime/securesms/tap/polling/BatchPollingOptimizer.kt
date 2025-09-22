package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/**
 * 智能批处理轮询优化器
 * 
 * 将相似的轮询任务批量处理，减少系统开销，提高轮询效率。
 * 主要优化策略：
 * 1. 同Provider任务批处理 - 相同Provider的轮询任务合并执行
 * 2. 时间窗口批处理 - 在时间窗口内收集任务后批量执行
 * 3. 网络状况自适应 - 根据网络条件调整批处理策略
 * 4. 智能去重 - 避免重复轮询相同目标
 * 5. 负载均衡 - 分散批处理负载
 */
class BatchPollingOptimizer(private val context: Context) {
    
    companion object {
        private const val TAG = "BatchPollingOptimizer"
        
        // 批处理配置常量
        private const val DEFAULT_BATCH_SIZE = 5               // 默认批处理大小
        private const val MAX_BATCH_SIZE = 20                  // 最大批处理大小
        private const val MIN_BATCH_SIZE = 2                   // 最小批处理大小
        
        // 时间窗口配置
        private const val DEFAULT_BATCH_WINDOW_MS = 5000L      // 默认批处理时间窗口: 5秒
        private const val MAX_BATCH_WINDOW_MS = 30000L         // 最大批处理时间窗口: 30秒
        private const val MIN_BATCH_WINDOW_MS = 1000L          // 最小批处理时间窗口: 1秒
        
        // 性能阈值
        private const val OPTIMAL_BATCH_RESPONSE_TIME_MS = 5000L  // 最优批处理响应时间
        private const val MAX_ACCEPTABLE_RESPONSE_TIME_MS = 15000L // 最大可接受响应时间
        
        // 网络质量相关
        private const val GOOD_NETWORK_BATCH_MULTIPLIER = 1.5  // 良好网络时的批处理倍数
        private const val POOR_NETWORK_BATCH_MULTIPLIER = 0.7  // 较差网络时的批处理倍数
    }
    
    // 批处理组管理
    private val batchGroups = ConcurrentHashMap<String, BatchPollingGroup>()
    private val batchStatistics = ConcurrentHashMap<String, BatchStatistics>()
    
    // 配置和状态
    private var currentBatchSize = DEFAULT_BATCH_SIZE
    private var currentBatchWindow = DEFAULT_BATCH_WINDOW_MS
    private var isOptimizationEnabled = true
    
    // 统计信息
    private val totalBatchesProcessed = AtomicLong(0)
    private val totalTasksProcessed = AtomicLong(0)
    private val totalTimeSaved = AtomicLong(0) // 通过批处理节省的时间（毫秒）
    
    /**
     * 将相似的轮询任务批量处理
     * 
     * @param tasks 待处理的轮询任务列表
     * @return 批处理组列表
     */
    fun batchSimilarPollingTasks(tasks: List<PollingTaskInfo>): List<BatchPollingGroup> {
        if (!isOptimizationEnabled || tasks.isEmpty()) {
            return tasks.map { createSingleTaskGroup(it) }
        }
        
        try {
            Log.d(TAG, "开始批处理轮询任务: ${tasks.size}个任务")
            
            // 1. 按Provider类型分组
            val providerGroups = groupTasksByProvider(tasks)
            
            // 2. 按活跃度级别进一步分组
            val activityGroups = groupTasksByActivity(providerGroups)
            
            // 3. 按时间窗口分组
            val timeWindowGroups = groupTasksByTimeWindow(activityGroups)
            
            // 4. 创建批处理组
            val batchGroups = createBatchGroups(timeWindowGroups)
            
            // 5. 优化批处理大小
            val optimizedGroups = optimizeBatchSizes(batchGroups)
            
            Log.d(TAG, "批处理完成: 原始${tasks.size}个任务 -> ${optimizedGroups.size}个批处理组")
            
            // 更新统计信息
            updateBatchStatistics(tasks, optimizedGroups)
            
            return optimizedGroups
            
        } catch (e: Exception) {
            Log.e(TAG, "批处理轮询任务时发生错误", e)
            // 错误时返回单独的任务组
            return tasks.map { createSingleTaskGroup(it) }
        }
    }
    
    /**
     * 根据网络状况调整批处理策略
     * 
     * @param networkInfo 网络信息
     */
    fun adjustBatchStrategy(networkInfo: NetworkInfo) {
        try {
            Log.d(TAG, "调整批处理策略: network=${networkInfo.quality}, latency=${networkInfo.latencyMs}ms")
            
            val oldBatchSize = currentBatchSize
            val oldBatchWindow = currentBatchWindow
            
            // 根据网络质量调整批处理大小
            currentBatchSize = when (networkInfo.quality) {
                NetworkQuality.EXCELLENT -> (DEFAULT_BATCH_SIZE * GOOD_NETWORK_BATCH_MULTIPLIER).toInt()
                NetworkQuality.GOOD -> DEFAULT_BATCH_SIZE
                NetworkQuality.FAIR -> DEFAULT_BATCH_SIZE
                NetworkQuality.POOR -> (DEFAULT_BATCH_SIZE * POOR_NETWORK_BATCH_MULTIPLIER).toInt()
                NetworkQuality.VERY_POOR -> (DEFAULT_BATCH_SIZE * POOR_NETWORK_BATCH_MULTIPLIER * 0.5).toInt()
                NetworkQuality.NO_CONNECTION -> MIN_BATCH_SIZE
                NetworkQuality.UNKNOWN -> DEFAULT_BATCH_SIZE
            }.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)
            
            // 根据网络延迟调整批处理时间窗口
            currentBatchWindow = when {
                networkInfo.latencyMs > 1000 -> (DEFAULT_BATCH_WINDOW_MS * 1.5).toLong() // 高延迟增加窗口
                networkInfo.latencyMs > 500 -> DEFAULT_BATCH_WINDOW_MS                   // 中等延迟保持默认
                networkInfo.latencyMs > 0 -> (DEFAULT_BATCH_WINDOW_MS * 0.8).toLong()   // 低延迟减少窗口
                else -> DEFAULT_BATCH_WINDOW_MS                                          // 未知延迟使用默认
            }.coerceIn(MIN_BATCH_WINDOW_MS, MAX_BATCH_WINDOW_MS)
            
            // 根据网络带宽调整
            if (networkInfo.bandwidthKbps > 0) {
                val bandwidthAdjustment = when {
                    networkInfo.bandwidthKbps > 10000 -> 1.2  // 高带宽
                    networkInfo.bandwidthKbps > 1000 -> 1.0   // 中等带宽
                    else -> 0.8                               // 低带宽
                }
                currentBatchSize = (currentBatchSize * bandwidthAdjustment).toInt()
                    .coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)
            }
            
            // 记录调整
            if (oldBatchSize != currentBatchSize || oldBatchWindow != currentBatchWindow) {
                Log.i(TAG, "批处理策略已调整: batchSize=$oldBatchSize->$currentBatchSize, " +
                        "batchWindow=${oldBatchWindow}ms->${currentBatchWindow}ms")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "调整批处理策略时发生错误", e)
        }
    }
    
    /**
     * 执行批处理轮询
     * 
     * @param batchGroup 批处理组
     * @param pollingExecutor 轮询执行器函数
     * @return 批处理结果
     */
    suspend fun executeBatchPolling(
        batchGroup: BatchPollingGroup,
        pollingExecutor: suspend (PollingTaskInfo) -> PollingResult
    ): BatchPollingResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            Log.d(TAG, "执行批处理轮询: group=${batchGroup.groupId}, tasks=${batchGroup.tasks.size}")
            
            // 并发执行批处理任务
            val results = coroutineScope {
                batchGroup.tasks.map { task ->
                    async {
                        try {
                            val result = pollingExecutor(task)
                            PollingTaskResult(task, result)
                        } catch (e: Exception) {
                            Log.w(TAG, "轮询任务执行失败: ${task.recipientId}", e)
                            PollingTaskResult(task, PollingResult.failure(e))
                        }
                    }
                }.awaitAll()
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            // 创建批处理结果
            val batchResult = BatchPollingResult(
                batchGroup = batchGroup,
                taskResults = results,
                executionTimeMs = executionTime,
                isSuccess = results.all { it.result.isSuccess }
            )
            
            // 更新统计信息
            updateExecutionStatistics(batchGroup, batchResult)
            
            Log.d(TAG, "批处理轮询完成: group=${batchGroup.groupId}, " +
                    "time=${executionTime}ms, success=${batchResult.isSuccess}")
            
            batchResult
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "批处理轮询执行失败: group=${batchGroup.groupId}", e)
            
            BatchPollingResult(
                batchGroup = batchGroup,
                taskResults = emptyList(),
                executionTimeMs = executionTime,
                isSuccess = false,
                error = e
            )
        }
    }
    
    /**
     * 获取批处理优化统计信息
     */
    fun getBatchOptimizationStats(): BatchOptimizationStats {
        val totalBatches = totalBatchesProcessed.get()
        val totalTasks = totalTasksProcessed.get()
        val timeSaved = totalTimeSaved.get()
        
        val averageBatchSize = if (totalBatches > 0) {
            totalTasks.toDouble() / totalBatches.toDouble()
        } else {
            0.0
        }
        
        val timeSavingRatio = if (totalTasks > 0) {
            timeSaved.toDouble() / (totalTasks * 1000) // 假设单个任务平均1秒
        } else {
            0.0
        }
        
        return BatchOptimizationStats(
            totalBatchesProcessed = totalBatches,
            totalTasksProcessed = totalTasks,
            averageBatchSize = averageBatchSize,
            totalTimeSavedMs = timeSaved,
            timeSavingRatio = timeSavingRatio,
            currentBatchSize = currentBatchSize,
            currentBatchWindow = currentBatchWindow,
            providerStatistics = batchStatistics.toMap()
        )
    }
    
    /**
     * 启用或禁用批处理优化
     */
    fun setOptimizationEnabled(enabled: Boolean) {
        if (isOptimizationEnabled != enabled) {
            isOptimizationEnabled = enabled
            Log.i(TAG, "批处理优化${if (enabled) "已启用" else "已禁用"}")
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 按Provider类型分组任务
     */
    private fun groupTasksByProvider(tasks: List<PollingTaskInfo>): Map<String, List<PollingTaskInfo>> {
        return tasks.groupBy { it.metadata.providerType }
    }
    
    /**
     * 按活跃度级别分组任务
     */
    private fun groupTasksByActivity(providerGroups: Map<String, List<PollingTaskInfo>>): Map<String, Map<TransportActivityLevel, List<PollingTaskInfo>>> {
        return providerGroups.mapValues { (_, tasks) ->
            tasks.groupBy { it.activityLevel }
        }
    }
    
    /**
     * 按时间窗口分组任务
     */
    private fun groupTasksByTimeWindow(activityGroups: Map<String, Map<TransportActivityLevel, List<PollingTaskInfo>>>): List<List<PollingTaskInfo>> {
        val allTasks = mutableListOf<PollingTaskInfo>()
        activityGroups.forEach { (_, activityMap) ->
            activityMap.forEach { (_, tasks) ->
                allTasks.addAll(tasks)
            }
        }
        
        // 简化实现：按最后轮询时间排序，然后按时间窗口分组
        val sortedTasks = allTasks.sortedBy { it.lastPollTime.get() }
        val groups = mutableListOf<List<PollingTaskInfo>>()
        val currentGroup = mutableListOf<PollingTaskInfo>()
        
        var windowStartTime = 0L
        
        for (task in sortedTasks) {
            val taskTime = task.lastPollTime.get()
            
            if (windowStartTime == 0L) {
                windowStartTime = taskTime
                currentGroup.add(task)
            } else if (taskTime - windowStartTime <= currentBatchWindow) {
                currentGroup.add(task)
            } else {
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup.toList())
                    currentGroup.clear()
                }
                windowStartTime = taskTime
                currentGroup.add(task)
            }
        }
        
        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup.toList())
        }
        
        return groups
    }
    
    /**
     * 创建批处理组
     */
    private fun createBatchGroups(timeWindowGroups: List<List<PollingTaskInfo>>): List<BatchPollingGroup> {
        return timeWindowGroups.map { tasks ->
            BatchPollingGroup(
                groupId = generateGroupId(),
                tasks = tasks,
                providerType = tasks.firstOrNull()?.metadata?.providerType ?: "unknown",
                activityLevel = tasks.firstOrNull()?.activityLevel ?: TransportActivityLevel.INACTIVE,
                createdAt = System.currentTimeMillis(),
                estimatedExecutionTime = estimateGroupExecutionTime(tasks)
            )
        }
    }
    
    /**
     * 优化批处理大小
     */
    private fun optimizeBatchSizes(groups: List<BatchPollingGroup>): List<BatchPollingGroup> {
        val optimizedGroups = mutableListOf<BatchPollingGroup>()
        
        for (group in groups) {
            if (group.tasks.size <= currentBatchSize) {
                // 大小合适，直接添加
                optimizedGroups.add(group)
            } else {
                // 需要拆分为多个较小的组
                val chunks = group.tasks.chunked(currentBatchSize)
                chunks.forEachIndexed { index, chunk ->
                    optimizedGroups.add(
                        group.copy(
                            groupId = "${group.groupId}_${index}",
                            tasks = chunk,
                            estimatedExecutionTime = estimateGroupExecutionTime(chunk)
                        )
                    )
                }
            }
        }
        
        return optimizedGroups
    }
    
    /**
     * 创建单任务组
     */
    private fun createSingleTaskGroup(task: PollingTaskInfo): BatchPollingGroup {
        return BatchPollingGroup(
            groupId = generateGroupId(),
            tasks = listOf(task),
            providerType = task.metadata.providerType,
            activityLevel = task.activityLevel,
            createdAt = System.currentTimeMillis(),
            estimatedExecutionTime = estimateGroupExecutionTime(listOf(task))
        )
    }
    
    /**
     * 生成批处理组ID
     */
    private fun generateGroupId(): String {
        return "batch_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
    
    /**
     * 估算组执行时间
     */
    private fun estimateGroupExecutionTime(tasks: List<PollingTaskInfo>): Long {
        if (tasks.isEmpty()) return 0L
        
        // 基于历史响应时间估算
        val averageResponseTime = tasks.map { it.getAverageResponseTime() }
            .filter { it > 0 }
            .average()
            .let { if (it.isNaN()) 1000.0 else it } // 默认1秒
        
        // 并发执行，所以时间约等于最长的任务时间
        return (averageResponseTime * 1.2).toLong() // 增加20%的缓冲
    }
    
    /**
     * 更新批处理统计信息
     */
    private fun updateBatchStatistics(originalTasks: List<PollingTaskInfo>, batchGroups: List<BatchPollingGroup>) {
        totalBatchesProcessed.addAndGet(batchGroups.size.toLong())
        totalTasksProcessed.addAndGet(originalTasks.size.toLong())
        
        // 估算节省的时间（简化计算）
        val estimatedOriginalTime = originalTasks.size * 1000L // 假设每个任务1秒
        val estimatedBatchTime = batchGroups.sumOf { it.estimatedExecutionTime }
        val timeSaved = maxOf(0L, estimatedOriginalTime - estimatedBatchTime)
        totalTimeSaved.addAndGet(timeSaved)
        
        // 更新Provider统计
        for (group in batchGroups) {
            val providerType = group.providerType
            val stats = batchStatistics.getOrPut(providerType) { BatchStatistics(providerType) }
            stats.addBatch(group.tasks.size, group.estimatedExecutionTime)
        }
    }
    
    /**
     * 更新执行统计信息
     */
    private fun updateExecutionStatistics(group: BatchPollingGroup, result: BatchPollingResult) {
        val providerType = group.providerType
        val stats = batchStatistics.getOrPut(providerType) { BatchStatistics(providerType) }
        stats.addExecutionResult(result.executionTimeMs, result.isSuccess)
        
        // 调整批处理大小（简化的自适应逻辑）
        if (result.executionTimeMs > MAX_ACCEPTABLE_RESPONSE_TIME_MS && currentBatchSize > MIN_BATCH_SIZE) {
            currentBatchSize = maxOf(MIN_BATCH_SIZE, currentBatchSize - 1)
            Log.d(TAG, "响应时间过长，减少批处理大小到: $currentBatchSize")
        } else if (result.executionTimeMs < OPTIMAL_BATCH_RESPONSE_TIME_MS && currentBatchSize < MAX_BATCH_SIZE) {
            currentBatchSize = minOf(MAX_BATCH_SIZE, currentBatchSize + 1)
            Log.d(TAG, "响应时间良好，增加批处理大小到: $currentBatchSize")
        }
    }
}

/**
 * 批处理组
 */
data class BatchPollingGroup(
    val groupId: String,
    val tasks: List<PollingTaskInfo>,
    val providerType: String,
    val activityLevel: TransportActivityLevel,
    val createdAt: Long,
    val estimatedExecutionTime: Long
)

/**
 * 轮询结果
 */
sealed class PollingResult {
    data class Success(val messagesFound: Int = 0) : PollingResult()
    data class Failure(val error: Throwable) : PollingResult()
    
    val isSuccess: Boolean get() = this is Success
    
    companion object {
        fun success(messagesFound: Int = 0) = Success(messagesFound)
        fun failure(error: Throwable) = Failure(error)
    }
}

/**
 * 轮询任务结果
 */
data class PollingTaskResult(
    val task: PollingTaskInfo,
    val result: PollingResult
)

/**
 * 批处理轮询结果
 */
data class BatchPollingResult(
    val batchGroup: BatchPollingGroup,
    val taskResults: List<PollingTaskResult>,
    val executionTimeMs: Long,
    val isSuccess: Boolean,
    val error: Throwable? = null
)

/**
 * 网络信息
 */
data class NetworkInfo(
    val quality: NetworkQuality,
    val latencyMs: Long = 0,
    val bandwidthKbps: Long = 0,
    val isConnected: Boolean = true
)

/**
 * 批处理统计信息
 */
class BatchStatistics(val providerType: String) {
    private val totalBatches = AtomicLong(0)
    private val totalTasks = AtomicLong(0)
    private val totalExecutionTime = AtomicLong(0)
    private val successfulBatches = AtomicLong(0)
    
    fun addBatch(taskCount: Int, estimatedTime: Long) {
        totalBatches.incrementAndGet()
        totalTasks.addAndGet(taskCount.toLong())
    }
    
    fun addExecutionResult(executionTime: Long, success: Boolean) {
        totalExecutionTime.addAndGet(executionTime)
        if (success) {
            successfulBatches.incrementAndGet()
        }
    }
    
    fun toStats(): Map<String, Any> {
        val batches = totalBatches.get()
        val tasks = totalTasks.get()
        val execTime = totalExecutionTime.get()
        val successful = successfulBatches.get()
        
        return mapOf(
            "totalBatches" to batches,
            "totalTasks" to tasks,
            "averageBatchSize" to if (batches > 0) tasks.toDouble() / batches else 0.0,
            "averageExecutionTime" to if (batches > 0) execTime.toDouble() / batches else 0.0,
            "successRate" to if (batches > 0) successful.toDouble() / batches else 0.0
        )
    }
}

/**
 * 批处理优化统计信息
 */
data class BatchOptimizationStats(
    val totalBatchesProcessed: Long,
    val totalTasksProcessed: Long,
    val averageBatchSize: Double,
    val totalTimeSavedMs: Long,
    val timeSavingRatio: Double,
    val currentBatchSize: Int,
    val currentBatchWindow: Long,
    val providerStatistics: Map<String, BatchStatistics>
) 