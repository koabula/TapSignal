package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/**
 * 自适应间隔调整器
 * 
 * 基于历史数据和智能算法动态调整轮询间隔，实现最优的轮询性能。
 * 主要功能：
 * 1. 历史数据分析 - 分析轮询历史，识别模式
 * 2. 预测性调整 - 基于历史数据预测最优间隔
 * 3. 机器学习优化 - 使用简单的机器学习算法优化间隔
 * 4. 时间模式识别 - 识别用户活动的时间模式
 * 5. 自适应学习 - 持续学习和改进调整策略
 */
class AdaptiveIntervalAdjuster(private val context: Context) {
    
    companion object {
        private const val TAG = "AdaptiveIntervalAdjuster"
        
        // 历史数据配置
        private const val MAX_HISTORY_RECORDS = 1000      // 最大历史记录数
        private const val MIN_HISTORY_FOR_PREDICTION = 10 // 预测所需最小历史记录数
        
        // 学习参数
        private const val LEARNING_RATE = 0.1             // 学习率
        private const val MOMENTUM = 0.9                  // 动量
        private const val DECAY_FACTOR = 0.95             // 衰减因子
        
        // 时间特征
        private const val HOUR_IN_MS = 60 * 60 * 1000L    // 1小时毫秒数
        private const val DAY_IN_MS = 24 * HOUR_IN_MS      // 1天毫秒数
        private const val WEEK_IN_MS = 7 * DAY_IN_MS       // 1周毫秒数
        
        // 调整阈值
        private const val MIN_ADJUSTMENT_RATIO = 0.5      // 最小调整比例
        private const val MAX_ADJUSTMENT_RATIO = 2.0      // 最大调整比例
        private const val CONFIDENCE_THRESHOLD = 0.7     // 预测信心阈值
    }
    
    // 历史数据存储
    private val pollingHistory = ConcurrentHashMap<String, MutableList<PollingRecord>>()
    private val intervalHistory = ConcurrentHashMap<String, MutableList<IntervalRecord>>()
    
    // 学习模型参数
    private val modelWeights = ConcurrentHashMap<String, ModelWeights>()
    private val adaptationStats = ConcurrentHashMap<String, AdaptationStatistics>()
    
    // 时间模式分析
    private val timePatterns = ConcurrentHashMap<String, TimePattern>()
    
    /**
     * 基于历史数据预测最优间隔
     * 
     * @param recipientId 接收者ID
     * @param historicalData 历史轮询数据
     * @return 预测的最优间隔（毫秒）
     */
    fun predictOptimalInterval(
        recipientId: String,
        historicalData: List<PollingResult>
    ): Long {
        try {
            Log.d(TAG, "预测最优间隔: recipient=$recipientId, historySize=${historicalData.size}")
            
            // 获取或创建历史记录
            val history = pollingHistory.getOrPut(recipientId) { mutableListOf() }
            
            // 添加新的历史数据
            updatePollingHistory(recipientId, historicalData)
            
            // 检查是否有足够的历史数据
            if (history.size < MIN_HISTORY_FOR_PREDICTION) {
                Log.d(TAG, "历史数据不足，使用默认间隔")
                return getDefaultInterval(recipientId)
            }
            
            // 进行多维度预测
            val timeBased = predictTimeBasedInterval(recipientId)
            val patternBased = predictPatternBasedInterval(recipientId)
            val performanceBased = predictPerformanceBasedInterval(recipientId)
            
            // 加权综合预测结果
            val weightedInterval = combineIntervalPredictions(
                timeBased, patternBased, performanceBased
            )
            
            // 应用置信度调整
            val confidence = calculatePredictionConfidence(recipientId)
            val finalInterval = if (confidence >= CONFIDENCE_THRESHOLD) {
                weightedInterval
            } else {
                // 低置信度时向默认值靠拢
                val defaultInterval = getDefaultInterval(recipientId)
                (weightedInterval * confidence + defaultInterval * (1 - confidence)).toLong()
            }
            
            // 应用调整范围限制
            val adjustedInterval = applyAdjustmentLimits(recipientId, finalInterval)
            
            Log.d(TAG, "预测完成: interval=${adjustedInterval}ms, confidence=${String.format("%.2f", confidence)}")
            
            // 记录预测结果
            recordPrediction(recipientId, adjustedInterval, confidence)
            
            return adjustedInterval
            
        } catch (e: Exception) {
            Log.e(TAG, "预测最优间隔时发生错误", e)
            return getDefaultInterval(recipientId)
        }
    }
    
    /**
     * 机器学习优化轮询间隔
     * 
     * @param features 轮询特征
     * @return 优化后的间隔（毫秒）
     */
    fun mlOptimizeInterval(features: PollingFeatures): Long {
        try {
            Log.d(TAG, "机器学习优化: hour=${features.hourOfDay}, activity=${features.userActivityScore}")
            
            val recipientId = features.recipientId
            val weights = modelWeights.getOrPut(recipientId) { ModelWeights() }
            
            // 特征工程：将特征转换为模型输入
            val inputVector = extractFeatureVector(features)
            
            // 前向传播：计算预测间隔
            val predictedInterval = forwardPass(inputVector, weights)
            
            // 应用激活函数和缩放
            val scaledInterval = applyActivationAndScaling(predictedInterval, features)
            
            // 记录特征和预测结果，用于后续学习
            recordFeaturesAndPrediction(features, scaledInterval)
            
            Log.d(TAG, "ML优化完成: ${scaledInterval}ms")
            
            return scaledInterval.toLong()
            
        } catch (e: Exception) {
            Log.e(TAG, "机器学习优化时发生错误", e)
            return getDefaultInterval(features.recipientId)
        }
    }
    
    /**
     * 学习和更新模型
     * 
     * @param recipientId 接收者ID
     * @param features 输入特征
     * @param actualInterval 实际使用的间隔
     * @param performance 性能指标
     */
    fun learnFromExperience(
        recipientId: String,
        features: PollingFeatures,
        actualInterval: Long,
        performance: PollingPerformance
    ) {
        try {
            Log.d(TAG, "从经验学习: recipient=$recipientId, interval=${actualInterval}ms, " +
                    "success=${performance.successRate}")
            
            val weights = modelWeights.getOrPut(recipientId) { ModelWeights() }
            val stats = adaptationStats.getOrPut(recipientId) { AdaptationStatistics() }
            
            // 计算损失（性能指标的反向）
            val loss = calculateLoss(performance)
            
            // 如果性能良好，记录为正面经验
            if (performance.successRate >= 0.8 && performance.averageResponseTime < 10000) {
                // 反向传播：更新模型权重
                backwardPass(extractFeatureVector(features), loss, weights)
                
                // 更新统计信息
                stats.recordPositiveExperience(actualInterval, performance)
            } else {
                // 性能不佳，记录为负面经验
                stats.recordNegativeExperience(actualInterval, performance)
            }
            
            // 定期模型维护
            if (stats.totalExperiences % 50 == 0L) {
                performModelMaintenance(recipientId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "学习经验时发生错误", e)
        }
    }
    
    /**
     * 获取适应性调整统计信息
     */
    fun getAdaptationStatistics(): Map<String, AdaptiveStats> {
        return adaptationStats.mapValues { (recipientId, stats) ->
            AdaptiveStats(
                recipientId = recipientId,
                totalPredictions = stats.totalPredictions,
                accuratePredictions = stats.accuratePredictions,
                predictionAccuracy = stats.getPredictionAccuracy(),
                averageImprovement = stats.getAverageImprovement(),
                modelVersion = modelWeights[recipientId]?.version ?: 0,
                lastTrainingTime = stats.lastTrainingTime
            )
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 更新轮询历史记录
     */
    private fun updatePollingHistory(recipientId: String, results: List<PollingResult>) {
        val history = pollingHistory.getOrPut(recipientId) { mutableListOf() }
        
        val currentTime = System.currentTimeMillis()
        results.forEach { result ->
            history.add(PollingRecord(
                timestamp = currentTime,
                success = result.isSuccess,
                responseTime = if (result is PollingResult.Success) 1000L else 0L, // 简化
                messagesFound = if (result is PollingResult.Success) result.messagesFound else 0
            ))
        }
        
        // 限制历史记录大小
        if (history.size > MAX_HISTORY_RECORDS) {
            history.subList(0, history.size - MAX_HISTORY_RECORDS).clear()
        }
    }
    
    /**
     * 基于时间预测间隔
     */
    private fun predictTimeBasedInterval(recipientId: String): Long {
        val currentTime = System.currentTimeMillis()
        val hourOfDay = ((currentTime % DAY_IN_MS) / HOUR_IN_MS).toInt()
        val dayOfWeek = ((currentTime / DAY_IN_MS) % 7).toInt()
        
        // 获取时间模式
        val pattern = timePatterns.getOrPut(recipientId) { TimePattern() }
        
        // 根据时间模式调整间隔
        val hourMultiplier = pattern.getHourMultiplier(hourOfDay)
        val dayMultiplier = pattern.getDayMultiplier(dayOfWeek)
        
        val baseInterval = getDefaultInterval(recipientId)
        return (baseInterval * hourMultiplier * dayMultiplier).toLong()
    }
    
    /**
     * 基于模式预测间隔
     */
    private fun predictPatternBasedInterval(recipientId: String): Long {
        val history = pollingHistory[recipientId] ?: return getDefaultInterval(recipientId)
        
        // 分析最近的成功率趋势
        val recentHistory = history.takeLast(20)
        val successRate = recentHistory.count { it.success }.toDouble() / recentHistory.size
        
        // 分析响应时间趋势
        val averageResponseTime = recentHistory
            .filter { it.responseTime > 0 }
            .map { it.responseTime }
            .average()
            .takeIf { !it.isNaN() } ?: 1000.0
        
        // 基于趋势调整间隔
        val successMultiplier = when {
            successRate > 0.9 -> 0.8  // 高成功率，可以增加频率
            successRate > 0.7 -> 1.0  // 中等成功率，保持当前
            else -> 1.5               // 低成功率，降低频率
        }
        
        val responseMultiplier = when {
            averageResponseTime < 2000 -> 0.9   // 快响应，可以增加频率
            averageResponseTime < 5000 -> 1.0   // 正常响应，保持当前
            else -> 1.3                         // 慢响应，降低频率
        }
        
        val baseInterval = getDefaultInterval(recipientId)
        return (baseInterval * successMultiplier * responseMultiplier).toLong()
    }
    
    /**
     * 基于性能预测间隔
     */
    private fun predictPerformanceBasedInterval(recipientId: String): Long {
        val stats = adaptationStats[recipientId] ?: return getDefaultInterval(recipientId)
        
        // 基于历史性能调整
        val performanceScore = stats.getPerformanceScore()
        val multiplier = when {
            performanceScore > 0.8 -> 0.8   // 高性能，增加频率
            performanceScore > 0.6 -> 1.0   // 中等性能，保持当前
            else -> 1.4                     // 低性能，降低频率
        }
        
        val baseInterval = getDefaultInterval(recipientId)
        return (baseInterval * multiplier).toLong()
    }
    
    /**
     * 综合多个预测结果
     */
    private fun combineIntervalPredictions(
        timeBased: Long,
        patternBased: Long,
        performanceBased: Long
    ): Long {
        // 加权平均，可以根据不同预测方法的可靠性调整权重
        val timeWeight = 0.3
        val patternWeight = 0.4
        val performanceWeight = 0.3
        
        return (timeBased * timeWeight + 
                patternBased * patternWeight + 
                performanceBased * performanceWeight).toLong()
    }
    
    /**
     * 计算预测置信度
     */
    private fun calculatePredictionConfidence(recipientId: String): Double {
        val history = pollingHistory[recipientId]
        if (history == null || history.size < MIN_HISTORY_FOR_PREDICTION) {
            return 0.1
        }
        
        // 基于历史数据的一致性计算置信度
        val recentHistory = history.takeLast(30)
        val successRates = recentHistory.chunked(5).map { chunk ->
            chunk.count { it.success }.toDouble() / chunk.size
        }
        
        if (successRates.size < 2) return 0.5
        
        // 计算方差，方差越小置信度越高
        val mean = successRates.average()
        val variance = successRates.map { (it - mean).pow(2) }.average()
        val confidence = exp(-variance * 5).coerceIn(0.1, 1.0)
        
        return confidence
    }
    
    /**
     * 应用调整范围限制
     */
    private fun applyAdjustmentLimits(recipientId: String, predictedInterval: Long): Long {
        val defaultInterval = getDefaultInterval(recipientId)
        val minInterval = (defaultInterval * MIN_ADJUSTMENT_RATIO).toLong()
        val maxInterval = (defaultInterval * MAX_ADJUSTMENT_RATIO).toLong()
        
        return predictedInterval.coerceIn(minInterval, maxInterval)
    }
    
    /**
     * 获取默认间隔
     */
    private fun getDefaultInterval(recipientId: String): Long {
        // 这里可以从TransportManager或其他地方获取默认配置
        return 30000L // 默认30秒
    }
    
    /**
     * 提取特征向量
     */
    private fun extractFeatureVector(features: PollingFeatures): DoubleArray {
        return doubleArrayOf(
            features.hourOfDay / 24.0,                    // 时间特征 (0-1)
            features.dayOfWeek / 7.0,                     // 星期特征 (0-1)
            features.userActivityScore,                   // 用户活跃度 (0-1)
            features.networkQuality.ordinal / 4.0,       // 网络质量 (0-1)
            features.batteryLevel / 100.0,               // 电量 (0-1)
            features.recentMessageFrequency               // 消息频率
        )
    }
    
    /**
     * 前向传播
     */
    private fun forwardPass(input: DoubleArray, weights: ModelWeights): Double {
        // 简单的线性模型
        var output = weights.bias
        for (i in input.indices) {
            output += input[i] * weights.weights.getOrElse(i) { 0.0 }
        }
        return output
    }
    
    /**
     * 应用激活函数和缩放
     */
    private fun applyActivationAndScaling(prediction: Double, features: PollingFeatures): Double {
        // 使用Sigmoid激活函数
        val activated = 1.0 / (1.0 + exp(-prediction))
        
        // 缩放到合理的间隔范围 (5秒到10分钟)
        val minInterval = 5000.0   // 5秒
        val maxInterval = 600000.0 // 10分钟
        
        return minInterval + activated * (maxInterval - minInterval)
    }
    
    /**
     * 反向传播更新权重
     */
    private fun backwardPass(input: DoubleArray, loss: Double, weights: ModelWeights) {
        // 简单的梯度下降更新
        val gradient = loss * LEARNING_RATE
        
        // 更新权重
        for (i in input.indices) {
            val currentWeight = weights.weights.getOrElse(i) { 0.0 }
            val momentum = weights.momentum.getOrElse(i) { 0.0 }
            
            val newMomentum = MOMENTUM * momentum + gradient * input[i]
            val newWeight = currentWeight - newMomentum
            
            weights.weights[i] = newWeight
            weights.momentum[i] = newMomentum
        }
        
        // 更新偏置
        weights.bias -= gradient
    }
    
    /**
     * 计算损失函数
     */
    private fun calculateLoss(performance: PollingPerformance): Double {
        // 基于成功率和响应时间的损失函数
        val successLoss = 1.0 - performance.successRate
        val responseLoss = (performance.averageResponseTime - 1000.0) / 10000.0 // 标准化
        
        return successLoss + responseLoss.coerceAtLeast(0.0)
    }
    
    /**
     * 记录预测结果
     */
    private fun recordPrediction(recipientId: String, interval: Long, confidence: Double) {
        val intervalRecord = IntervalRecord(
            timestamp = System.currentTimeMillis(),
            predictedInterval = interval,
            confidence = confidence
        )
        
        intervalHistory.getOrPut(recipientId) { mutableListOf() }.add(intervalRecord)
    }
    
    /**
     * 记录特征和预测
     */
    private fun recordFeaturesAndPrediction(features: PollingFeatures, interval: Double) {
        // 可以用于后续的模型评估和改进
    }
    
    /**
     * 执行模型维护
     */
    private fun performModelMaintenance(recipientId: String) {
        val weights = modelWeights[recipientId] ?: return
        
        // 权重衰减，防止过拟合
        weights.weights.replaceAll { _, weight -> weight * DECAY_FACTOR }
        weights.version++
        
        Log.d(TAG, "模型维护完成: recipient=$recipientId, version=${weights.version}")
    }
}

// === 数据类定义 ===

/**
 * 轮询特征
 */
data class PollingFeatures(
    val recipientId: String,
    val hourOfDay: Int,
    val dayOfWeek: Int,
    val userActivityScore: Double,
    val networkQuality: NetworkQuality,
    val batteryLevel: Int,
    val recentMessageFrequency: Double
)

/**
 * 轮询记录
 */
private data class PollingRecord(
    val timestamp: Long,
    val success: Boolean,
    val responseTime: Long,
    val messagesFound: Int
)

/**
 * 间隔记录
 */
private data class IntervalRecord(
    val timestamp: Long,
    val predictedInterval: Long,
    val confidence: Double
)

/**
 * 模型权重
 */
private data class ModelWeights(
    var bias: Double = 0.0,
    val weights: MutableMap<Int, Double> = mutableMapOf(),
    val momentum: MutableMap<Int, Double> = mutableMapOf(),
    var version: Int = 1
)

/**
 * 适应统计信息
 */
private data class AdaptationStatistics(
    var totalPredictions: Long = 0,
    var accuratePredictions: Long = 0,
    var totalExperiences: Long = 0,
    var positiveExperiences: Long = 0,
    var totalImprovement: Double = 0.0,
    var lastTrainingTime: Long = 0
) {
    fun recordPositiveExperience(interval: Long, performance: PollingPerformance) {
        totalExperiences++
        positiveExperiences++
        totalImprovement += performance.successRate
        lastTrainingTime = System.currentTimeMillis()
    }
    
    fun recordNegativeExperience(interval: Long, performance: PollingPerformance) {
        totalExperiences++
        lastTrainingTime = System.currentTimeMillis()
    }
    
    fun getPredictionAccuracy(): Double {
        return if (totalPredictions > 0) {
            accuratePredictions.toDouble() / totalPredictions.toDouble()
        } else 0.0
    }
    
    fun getAverageImprovement(): Double {
        return if (positiveExperiences > 0) {
            totalImprovement / positiveExperiences.toDouble()
        } else 0.0
    }
    
    fun getPerformanceScore(): Double {
        return if (totalExperiences > 0) {
            positiveExperiences.toDouble() / totalExperiences.toDouble()
        } else 0.5
    }
}

/**
 * 时间模式
 */
private data class TimePattern(
    private val hourMultipliers: MutableMap<Int, Double> = mutableMapOf(),
    private val dayMultipliers: MutableMap<Int, Double> = mutableMapOf()
) {
    fun getHourMultiplier(hour: Int): Double = hourMultipliers.getOrElse(hour) { 1.0 }
    fun getDayMultiplier(day: Int): Double = dayMultipliers.getOrElse(day) { 1.0 }
}

/**
 * 轮询性能
 */
data class PollingPerformance(
    val successRate: Double,
    val averageResponseTime: Long,
    val messagesFound: Int,
    val errorCount: Int
)

/**
 * 自适应统计
 */
data class AdaptiveStats(
    val recipientId: String,
    val totalPredictions: Long,
    val accuratePredictions: Long,
    val predictionAccuracy: Double,
    val averageImprovement: Double,
    val modelVersion: Int,
    val lastTrainingTime: Long
) 