package org.thoughtcrime.securesms.tap.polling

import android.content.Context
import android.util.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.TransportMessageDeduplicator
import org.thoughtcrime.securesms.tap.integration.TapMessageProcessor
import org.thoughtcrime.securesms.tap.integration.TapProcessResult
import org.thoughtcrime.securesms.database.SignalDatabase
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.*
import org.thoughtcrime.securesms.tap.TransportErrorHandler
import org.thoughtcrime.securesms.tap.ErrorContext





/**
 * Tap轮询服务 - 简化版本
 * 
 * 提供每联系人独立调度的基础轮询功能，专注于：
 * 1. 每联系人独立轮询调度
 * 2. 基础错误处理和退避
 * 3. 活跃度级别调整
 * 4. 资源安全管理
 */
class TapPollingService(private val context: Context) {
    
    companion object {
        private const val TAG = "TapPollingService"
        
        @Volatile
        private var INSTANCE: TapPollingService? = null
        
        /**
         * 获取单例实例
         */
        @JvmStatic
        fun getInstance(context: Context): TapPollingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapPollingService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 核心依赖组件
    private val transportManager = TransportManager.getInstance(context)
    private val channelManager = TransportChannelManager.getInstance(context)
    private val messageDeduplicator = TransportMessageDeduplicator.getInstance(context)
    private val messageProcessor = TapMessageProcessor.getInstance(context)
    private val errorHandler = TransportErrorHandler.getInstance(context)
    
    // 数据库访问
    private val pollingStateTable = SignalDatabase.transportPollingStates
    
    // 轮询状态缓存，避免每次读数据库
    private val pollingStateCache = ConcurrentHashMap<String, org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState>()
    
    // Marker缓存：用于增量查询优化
    // Key格式: "recipientId:providerType:path"
    // Value: PathMarkerInfo包含该路径的marker和更新时间
    private val markerCache = ConcurrentHashMap<String, PathMarkerInfo>()
    
    // 轮询任务管理
    // 使用复合key: 私聊使用 recipientId，群聊使用 groupId:recipientId
    private val pollingTasks = ConcurrentHashMap<String, PollingTaskInfo>()
    private val pollingLock = ReentrantReadWriteLock()
    
    // 线程池管理
    @Volatile
    private var pollingExecutor: ScheduledThreadPoolExecutor? = null
    @Volatile
    private var cleanupTask: ScheduledFuture<*>? = null
    
    // 服务状态
    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var serviceScope: CoroutineScope? = null
    
    // 文件处理失败跟踪
    private val fileProcessingFailures = ConcurrentHashMap<String, FileProcessingFailure>()
    
    // 设备性能检测
    private val deviceCapabilityProvider = DeviceCapabilityProvider(context)
    
    /**
     * 启动轮询服务
     */
    fun startPolling(): Boolean {
        return pollingLock.write {
            try {
                if (isRunning.get()) {
                    Log.w(TAG, "轮询服务已经运行")
                    return@write true
                }
                
                Log.i(TAG, "启动Tap轮询服务...")
                
                // 根据设备性能计算最优线程池配置
                val threadPoolConfig = deviceCapabilityProvider.calculateOptimalThreadPoolSize()
                val deviceSummary = deviceCapabilityProvider.getDeviceSummary()
                
                Log.i(TAG, "设备性能: $deviceSummary")
                Log.i(TAG, "线程池配置: ${threadPoolConfig.getSummary()}")
                
                // 创建自适应线程池
                pollingExecutor = ScheduledThreadPoolExecutor(
                    threadPoolConfig.corePoolSize,
                    { r -> 
                        Thread(r, "TapPolling-${Thread.currentThread().id}").apply {
                            isDaemon = true
                            priority = if (threadPoolConfig.isConservative()) {
                                Thread.MIN_PRIORITY + 1 // 低性能设备使用较低优先级
                            } else {
                                Thread.NORM_PRIORITY
                            }
                        }
                    }
                ).apply {
                    maximumPoolSize = threadPoolConfig.maxPoolSize
                    setKeepAliveTime(threadPoolConfig.keepAliveSeconds, TimeUnit.SECONDS)
                    allowCoreThreadTimeOut(true)
                    
                    // 根据设备性能选择拒绝策略
                    setRejectedExecutionHandler(
                        if (threadPoolConfig.isConservative()) {
                            ThreadPoolExecutor.DiscardOldestPolicy() // 低性能设备丢弃最旧任务
                        } else {
                            ThreadPoolExecutor.CallerRunsPolicy()    // 高性能设备由调用线程执行
                        }
                    )
                }
                
                // 创建协程作用域
                serviceScope = CoroutineScope(
                    Dispatchers.IO + SupervisorJob() + CoroutineName("TapPollingService")
                )
                
                // 启动清理任务
                startCleanupTask()
                
                // 启动群组状态同步器
                try {
                    val stateSynchronizer = org.thoughtcrime.securesms.tap.group.GroupV2StateSynchronizer.getInstance(context)
                    stateSynchronizer.start()
                    Log.i(TAG, "群组 V2 状态同步器已启动")
                } catch (e: Exception) {
                    Log.w(TAG, "启动群组状态同步器失败，继续执行", e)
                }
                
                isRunning.set(true)
                Log.i(TAG, "Tap轮询服务启动成功")
                true
                
            } catch (e: Exception) {
                when (e) {
                    is SecurityException -> Log.e(TAG, "安全权限不足，无法启动轮询服务", e)
                    is OutOfMemoryError -> Log.e(TAG, "内存不足，无法启动轮询服务", e)
                    else -> Log.e(TAG, "启动轮询服务失败: ${e.javaClass.simpleName}", e)
                }
                cleanup()
                false
            }
        }
    }
    
    /**
     * 停止轮询服务
     */
    fun stopPolling() {
        pollingLock.write {
            try {
                if (!isRunning.get()) {
                    Log.w(TAG, "轮询服务未运行")
                    return@write
                }
                
                Log.i(TAG, "停止Tap轮询服务...")
                
                isRunning.set(false)
                
                // 优雅停止
                gracefulShutdown()
                
                // 停止群组状态同步器
                try {
                    val stateSynchronizer = org.thoughtcrime.securesms.tap.group.GroupV2StateSynchronizer.getInstance(context)
                    stateSynchronizer.stop()
                    Log.i(TAG, "群组 V2 状态同步器已停止")
                } catch (e: Exception) {
                    Log.w(TAG, "停止群组状态同步器失败", e)
                }
                
                // 清理资源
                cleanup()
                
                Log.i(TAG, "Tap轮询服务已停止")
                
            } catch (e: Exception) {
                Log.e(TAG, "停止轮询服务时发生错误: ${e.javaClass.simpleName}", e)
            }
        }
    }
    
    /**
     * 优雅停止轮询任务
     */
    private fun gracefulShutdown() {
        try {
            // 取消所有轮询任务
            val tasks = pollingTasks.values.toList()
            Log.d(TAG, "取消 ${tasks.size} 个轮询任务")
            
            tasks.forEach { taskInfo ->
                taskInfo.task?.cancel(false)
            }
            
            // 等待线程池安全关闭
            pollingExecutor?.let { executor ->
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        Log.w(TAG, "轮询任务未在30秒内完成，强制停止")
                        val unfinishedTasks = executor.shutdownNow()
                        Log.w(TAG, "强制停止了 ${unfinishedTasks.size} 个未完成任务")
                        
                        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                            Log.e(TAG, "无法停止轮询线程池")
                        }
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    executor.shutdownNow()
                }
            }
            
            // 清理任务信息
            pollingTasks.clear()
            
        } catch (e: Exception) {
            Log.e(TAG, "优雅停止轮询任务时出错: ${e.javaClass.simpleName}", e)
        }
    }
    
    /**
     * 生成轮询任务的复合key
     * 
     * @param recipientId 接收者ID
     * @param groupId 群组ID（可为null，表示私聊）
     * @return 复合key: 私聊使用 recipientId，群聊使用 groupId:recipientId
     */
    private fun getPollingTaskKey(recipientId: String, groupId: String?): String {
        return if (groupId.isNullOrEmpty()) {
            recipientId  // 私聊：只用 recipientId
        } else {
            "$groupId:$recipientId"  // 群聊：groupId:recipientId
        }
    }
    
    /**
     * 从 metadata 的接收路径中提取 groupId
     * 
     * 群组消息路径格式：/group/{groupId}/messages/
     * 私聊消息路径格式：/v2-channels/{hashedId}/outbox/messages/
     */
    private fun extractGroupId(metadata: TransportMetadata): String? {
        return try {
            val receivePath = metadata.getReceiveMetadata().path
            
            // 检查是否为群组路径
            if (receivePath.startsWith("/group/")) {
                // 提取 groupId：/group/{groupId}/... → groupId
                val parts = receivePath.split("/")
                if (parts.size >= 3 && parts[1] == "group") {
                    parts[2].takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } else {
                null  // 私聊路径，返回 null
            }
        } catch (e: Exception) {
            Log.e(TAG, "从metadata中提取groupId失败", e)
            null
        }
    }
    
    /**
     * 规范化RecipientId格式
     * 统一将各种格式的RecipientId转换为ACI格式，确保轮询任务key的一致性
     * 
     * @param recipientId 原始RecipientId（可能是 "RecipientId::8"、"8" 或 ACI格式）
     * @return 规范化后的ACI格式ID
     */
    private fun normalizeRecipientId(recipientId: String): String {
        return try {
            when {
                // 处理 "RecipientId::数字" 格式
                recipientId.startsWith("RecipientId::") -> {
                    val idNumber = recipientId.removePrefix("RecipientId::")
                    val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(idNumber.toLong())
                    val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
                    recipient.requireAci().toString()
                }
                // 处理纯数字格式
                recipientId.all { it.isDigit() } -> {
                    val recipientIdObj = org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId.toLong())
                    val recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(recipientIdObj)
                    recipient.requireAci().toString()
                }
                // 已经是ACI格式（UUID样式）或群组ID，直接返回
                recipientId.contains("-") && recipientId.length >= 32 -> {
                    recipientId
                }
                // 其他情况（可能是群组ID等），保持原样
                else -> {
                    recipientId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RecipientId格式转换失败: $recipientId, 使用原始ID", e)
            recipientId  // 转换失败时使用原始ID
        }
    }
    
    /**
     * 为群组添加轮询目标
     * 
     * 为群组的所有其他成员创建轮询任务，支持批量添加
     * 
     * @param groupId 群组 ID
     * @param memberMetadatas 成员 metadata 映射 (memberAci -> TransportMetadata)
     * @return 成功添加的成员数量
     */
    fun addGroupPollingTargets(
        groupId: String,
        memberMetadatas: Map<String, TransportMetadata>
    ): Int {
        if (!isRunning.get()) {
            Log.w(TAG, "轮询服务未运行，无法添加群组轮询目标")
            return 0
        }
        
        if (memberMetadatas.isEmpty()) {
            Log.w(TAG, "群组成员列表为空: groupId=$groupId")
            return 0
        }
        
        return try {
            Log.i(TAG, "为群组添加轮询目标: groupId=$groupId, members=${memberMetadatas.size}")
            
            var successCount = 0
            
            // 为每个成员添加轮询目标
            for ((memberAci, metadata) in memberMetadatas) {
                try {
                    // metadata 的 peerReceivePath 应该已经包含 groupId 信息 (/group/{groupId}/)
                    val added = addPollingTarget(memberAci, metadata, null)
                    if (added) {
                        successCount++
                        Log.d(TAG, "群组成员轮询目标添加成功: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                    } else {
                        Log.w(TAG, "群组成员轮询目标添加失败: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "添加群组成员轮询目标时异常: groupId=$groupId, memberAci=${org.thoughtcrime.securesms.tap.utils.LogSanitizer.sanitize(memberAci)}", e)
                }
            }
            
            Log.i(TAG, "群组轮询目标添加完成: groupId=$groupId, 成功=$successCount/${memberMetadatas.size}")
            successCount
            
        } catch (e: Exception) {
            Log.e(TAG, "添加群组轮询目标失败: groupId=$groupId", e)
            0
        }
    }
    
    /**
     * 移除群组的所有轮询目标
     * 
     * @param groupId 群组 ID
     * @param memberAcis 成员 ACI 列表
     * @param providerType Provider 类型
     * @return 成功移除的成员数量
     */
    fun removeGroupPollingTargets(
        groupId: String,
        memberAcis: Set<String>,
        providerType: String
    ): Int {
        var successCount = 0
        
        // 在锁内删除任务
        val result = pollingLock.write {
            try {
                Log.i(TAG, "移除群组轮询目标: groupId=$groupId, members=${memberAcis.size}")
                
                for (memberAci in memberAcis) {
                    // 生成群组复合key
                    val taskKey = getPollingTaskKey(memberAci, groupId)
                    val taskInfo = pollingTasks[taskKey]
                    
                    if (taskInfo != null && taskInfo.metadata.providerType == providerType) {
                        Log.d(TAG, "移除群组轮询目标: taskKey=$taskKey, memberAci=$memberAci")
                        taskInfo.cleanup()
                        pollingTasks.remove(taskKey)
                        successCount++
                    }
                }
                
                Log.i(TAG, "群组轮询目标移除完成: groupId=$groupId, 成功=$successCount/${memberAcis.size}")
                successCount
                
            } catch (e: Exception) {
                Log.e(TAG, "移除群组轮询目标失败: groupId=$groupId", e)
                0
            }
        }
        
        // ✅ 在锁外异步清理队列
        if (successCount > 0) {
            serviceScope?.launch {
                try {
                    delay(100)
                    pollingExecutor?.purge()
                    Log.d(TAG, "异步清理：已从线程池队列清理${successCount}个已取消的群组任务")
                } catch (e: Exception) {
                    Log.w(TAG, "异步清理失败", e)
                }
            }
        }
        
        return result
    }
    
    /**
     * 添加轮询目标
     */
    fun addPollingTarget(recipientId: String, metadata: TransportMetadata, channel: TransportChannel? = null): Boolean {
        if (!isRunning.get()) {
            Log.w(TAG, "轮询服务未运行，无法添加轮询目标")
            return false
        }
        
        // ✅ 统一规范化 recipientId 为 ACI 格式，确保轮询任务 key 的一致性
        val normalizedRecipientId = normalizeRecipientId(recipientId)
        
        if (normalizedRecipientId != recipientId) {
            Log.d(TAG, "RecipientId已规范化: 原始=$recipientId, 规范化=$normalizedRecipientId")
        }
        
        return try {
            
            // 从 metadata 中提取 groupId
            val groupId = extractGroupId(metadata)
            // ✅ 使用规范化后的ID生成taskKey
            val taskKey = getPollingTaskKey(normalizedRecipientId, groupId)
            
            Log.d(TAG, "添加轮询目标: 原始recipient=$recipientId, 规范化recipient=$normalizedRecipientId, groupId=$groupId, taskKey=$taskKey, provider=${metadata.providerType}")
            
            // 在锁外获取通道信息，避免死锁
            // ✅ 使用规范化后的ID查询通道
            val channelForCalculation = channel ?: channelManager.getActiveChannel(normalizedRecipientId, metadata.providerType)
            
            pollingLock.write {
                // 检查是否已存在轮询目标（使用复合key）
                val existingTask = pollingTasks[taskKey]
                if (existingTask != null) {
                    Log.d(TAG, "检测到已存在的轮询目标: taskKey=$taskKey, 现有Provider=${existingTask.metadata.providerType}, 新Provider=${metadata.providerType}")
                    
                    // 检查Provider类型和metadata是否匹配
                    val existingGroupId = extractGroupId(existingTask.metadata)
                    if (existingTask.metadata.providerType == metadata.providerType && existingGroupId == groupId) {
                        // Provider和群组都匹配，检查任务状态
                        val currentStatus = existingTask.status
                        Log.d(TAG, "Provider和群组匹配，检查任务状态: taskKey=$taskKey, status=$currentStatus")
                        
                        when (currentStatus) {
                            PollingTaskStatus.RUNNING, PollingTaskStatus.POLLING -> {
                                // 状态正常，复合key已匹配，metadata也是正确的
                                Log.i(TAG, "轮询目标已存在且状态正常: taskKey=$taskKey, status=$currentStatus")
                                return@write true
                            }
                            PollingTaskStatus.PAUSED -> {
                                // 暂停状态，重新启动任务
                                Log.i(TAG, "重新启动暂停的轮询任务: taskKey=$taskKey")
                                existingTask.setStatus(PollingTaskStatus.RUNNING)
                                val newTask = schedulePollingTask(existingTask)
                                existingTask.task = newTask
                                return@write true
                            }
                            PollingTaskStatus.ERROR_SUSPENDED, PollingTaskStatus.STOPPED, PollingTaskStatus.CREATED -> {
                                // 异常状态，需要重新创建
                                Log.w(TAG, "现有轮询任务状态异常，移除并重新创建: taskKey=$taskKey, status=$currentStatus")
                                existingTask.cleanup()
                                pollingTasks.remove(taskKey)
                            }
                        }
                    } else {
                        // Provider或群组不匹配，移除旧任务
                        Log.w(TAG, "Provider或群组不匹配，移除旧任务并创建新任务: taskKey=$taskKey, 旧Provider=${existingTask.metadata.providerType}, 新Provider=${metadata.providerType}, 旧GroupId=$existingGroupId, 新GroupId=$groupId")
                        existingTask.cleanup()
                        pollingTasks.remove(taskKey)
                    }
                }
                
                // 创建新的轮询任务信息
                // ✅ 使用规范化后的ID创建任务
                val taskInfo = PollingTaskInfo.create(normalizedRecipientId, metadata)
                
                // 使用预先获取的通道信息计算初始轮询间隔
                val initialInterval = calculatePollingInterval(metadata, channelForCalculation)
                taskInfo.setCurrentInterval(initialInterval)
                
                // 调度轮询任务
                val scheduledTask = schedulePollingTask(taskInfo)
                if (scheduledTask == null) {
                    Log.e(TAG, "调度轮询任务失败: taskKey=$taskKey, normalizedRecipient=$normalizedRecipientId")
                    return@write false
                }
                
                taskInfo.task = scheduledTask
                taskInfo.setStatus(PollingTaskStatus.RUNNING)
                
                // 添加到任务列表（使用复合key）
                pollingTasks[taskKey] = taskInfo
                
                Log.i(TAG, "轮询目标添加成功: taskKey=$taskKey, normalizedRecipient=$normalizedRecipientId, groupId=$groupId, interval=${initialInterval}ms")
                true
            }
        } catch (e: Exception) {
            when (e) {
                is IllegalArgumentException -> Log.e(TAG, "添加轮询目标失败，参数无效: 原始recipient=$recipientId, 规范化=$normalizedRecipientId", e)
                is IllegalStateException -> Log.e(TAG, "添加轮询目标失败，状态异常: 原始recipient=$recipientId, 规范化=$normalizedRecipientId", e)
                is SecurityException -> Log.e(TAG, "添加轮询目标失败，安全权限不足: 原始recipient=$recipientId, 规范化=$normalizedRecipientId", e)
                else -> Log.e(TAG, "添加轮询目标失败: 原始recipient=$recipientId, 规范化=$normalizedRecipientId, ${e.javaClass.simpleName}", e)
            }
            false
        }
    }
    
    /**
     * 移除轮询目标
     * 
     * 由于使用了复合key（groupId:recipientId），需要遍历查找匹配的任务
     * 支持多种RecipientId格式的跨格式匹配
     */
    fun removePollingTarget(recipientId: String, providerType: String): Boolean {
        var removedCount = 0
        
        // 在锁内删除任务
        val result = pollingLock.write {
            try {
                // ✅ 规范化传入的recipientId，确保能匹配到使用规范化ID创建的任务
                val normalizedRecipientId = normalizeRecipientId(recipientId)
                
                if (normalizedRecipientId != recipientId) {
                    Log.d(TAG, "移除轮询目标 - ID已规范化: 原始=$recipientId, 规范化=$normalizedRecipientId")
                }
                
                // 查找所有匹配 recipientId 和 providerType 的任务
                // ✅ 增强匹配逻辑：支持原始格式和规范化格式的跨格式匹配
                val tasksToRemove = pollingTasks.filter { (key, taskInfo) ->
                    if (taskInfo.metadata.providerType != providerType) {
                        return@filter false
                    }
                    
                    // key 格式: recipientId 或 groupId:recipientId
                    // ✅ 匹配逻辑增强：支持4种匹配方式
                    val isMatch = (key == recipientId ||                      // 原始格式完全匹配
                                   key == normalizedRecipientId ||            // 规范化格式完全匹配
                                   key.endsWith(":$recipientId") ||           // 群组-原始格式
                                   key.endsWith(":$normalizedRecipientId"))   // 群组-规范化格式
                    isMatch
                }
                
                if (tasksToRemove.isEmpty()) {
                    Log.w(TAG, "轮询目标不存在: 原始recipient=$recipientId, 规范化=$normalizedRecipientId, provider=$providerType")
                    Log.d(TAG, "当前轮询任务keys: ${pollingTasks.keys.joinToString(", ")}")
                    return@write false
                }
                
                // 移除所有匹配的任务
                for ((taskKey, taskInfo) in tasksToRemove) {
                    Log.d(TAG, "移除轮询目标: taskKey=$taskKey, taskInfo.recipientId=${taskInfo.recipientId}, provider=$providerType")
                    
                    taskInfo.cleanup()
                    pollingTasks.remove(taskKey)
                    
                    // ✅ 清理缓存时使用 taskInfo.recipientId（与添加时保持一致）
                    val cacheKey = "${taskInfo.recipientId}_${providerType}"
                    pollingStateCache.remove(cacheKey)
                    Log.d(TAG, "清理轮询状态缓存: cacheKey=$cacheKey")
                    
                    // ✅ 清理marker缓存时使用 taskInfo.recipientId
                    clearMarkersForRecipient(taskInfo.recipientId, providerType)
                    
                    removedCount++
                }
                
                Log.i(TAG, "轮询目标移除成功: 原始recipient=$recipientId, 规范化=$normalizedRecipientId, 移除数量=$removedCount")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "移除轮询目标失败: 原始recipient=$recipientId", e)
                false
            }
        }
        
        // ✅ 在锁外异步清理队列，避免死锁和阻塞
        if (removedCount > 0 && result) {
            serviceScope?.launch {
                try {
                    delay(100)  // 延迟确保 cancel() 完成
                    pollingExecutor?.purge()
                    Log.d(TAG, "异步清理：已从线程池队列清理${removedCount}个已取消的任务")
                } catch (e: Exception) {
                    Log.w(TAG, "异步清理失败", e)
                }
            }
        }
        
        return result
    }
    
    /**
     * 获取特定接收者的轮询状态
     * 
     * @param recipientId 接收者ID
     * @param providerType 提供者类型
     * @return 轮询状态，如果不存在返回null
     */
    fun getPollingState(recipientId: String, providerType: String): org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState? {
        return try {
            pollingStateTable.getPollingState(recipientId, providerType)
        } catch (e: Exception) {
            Log.e(TAG, "获取轮询状态失败: recipientId=$recipientId, providerType=$providerType", e)
            null
        }
    }
    
    /**
     * 获取轮询状态
     */
    fun getPollingStatus(): TapPollingStatus {
        return pollingLock.read {
            val activeTasks = pollingTasks.values.filter { 
                it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING 
            }
            val totalTasks = pollingTasks.size
            
            val averageInterval = if (activeTasks.isNotEmpty()) {
                activeTasks.map { it.getCurrentInterval() }.average().toLong()
            } else {
                0L
            }
            
            val lastPollingTime = pollingTasks.values.maxOfOrNull { it.getLastPollTime() } ?: 0L
            
            TapPollingStatus(
                isRunning = isRunning.get(),
                activePollingTargets = activeTasks.size,
                totalPollingTargets = totalTasks,
                averagePollingInterval = averageInterval,
                lastPollingTime = lastPollingTime,
                pollingStatistics = createSimpleStatistics(),
                resourceUsage = PollingResourceUsage(
                    memoryUsageKB = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 },
                    activeThreads = pollingExecutor?.activeCount ?: 0,
                    queueLength = pollingExecutor?.queue?.size ?: 0
                ),
                systemStartTime = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 获取当前轮询统计信息
     */
    fun getCurrentStatistics(): TapPollingStatistics {
        return pollingLock.read {
            createSimpleStatistics()
        }
    }
    
    // === 私有方法实现 ===
    
    /**
     * 调度轮询任务
     * 
     * @param taskInfo 轮询任务信息
     * @param isRescheduling 是否为重新调度（热调整）。首次调度添加抖动延迟防止冷启动风暴，重新调度立即执行
     */
    private fun schedulePollingTask(taskInfo: PollingTaskInfo, isRescheduling: Boolean = false): ScheduledFuture<*>? {
        // 首次调度添加抖动防止冷启动风暴，重新调度立即执行以快速响应活跃度变化
        val initialDelayMs = if (isRescheduling) {
            0L  // 立即执行
        } else {
            (Math.random() * TapPollingConstants.PollingService.INITIAL_DELAY_JITTER_MAX_MS).toLong()
        }
        
        // 使用scheduleAtFixedRate确保固定轮询频率，避免执行时间累积导致间隔延长
        return pollingExecutor?.scheduleAtFixedRate(
            { executePollingTask(taskInfo) },
            initialDelayMs,
            taskInfo.getCurrentInterval(),
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 执行轮询任务
     */
    private fun executePollingTask(taskInfo: PollingTaskInfo) {
        if (!isRunning.get()) {
            return
        }
        
        // ✅ 第一道防线：检查任务状态
        // 如果状态为STOPPED，抛出异常终止scheduleAtFixedRate的周期性调度
        if (taskInfo.status == PollingTaskStatus.STOPPED) {
            Log.d(TAG, "任务已停止，终止周期性调度: recipient=${taskInfo.recipientId}")
            throw kotlinx.coroutines.CancellationException("Task permanently stopped")
        }
        
        // ✅ 第二道防线：检查任务是否在Map中
        // 如果已被删除，抛出异常终止周期性调度
        val groupId = extractGroupId(taskInfo.metadata)
        val taskKey = getPollingTaskKey(taskInfo.recipientId, groupId)
        
        val stillExists = pollingLock.read {
            pollingTasks.containsKey(taskKey)
        }
        
        if (!stillExists) {
            Log.d(TAG, "任务已被删除，终止周期性调度: taskKey=$taskKey, recipient=${taskInfo.recipientId}")
            throw kotlinx.coroutines.CancellationException("Task removed from map")
        }
        
        // 尝试获取执行权，如果已在执行中则跳过
        if (!taskInfo.tryStartExecution()) {
            Log.d(TAG, "轮询任务已在执行中，跳过: ${taskInfo.recipientId}")
            return
        }
        
        serviceScope?.launch {
            try {
                // 添加超时保护，避免单次轮询卡住导致后续任务无法执行
                withTimeout(30000L) {
                    taskInfo.setStatus(PollingTaskStatus.POLLING)
                    taskInfo.updatePollTime()
                    
                    Log.v(TAG, "执行轮询: ${taskInfo.getSummary()}")
                    
                    // 检查是否应该跳过轮询
                    val channel = channelManager.getActiveChannel(
                        taskInfo.recipientId, 
                        taskInfo.metadata.providerType
                    )
                    
                    if (shouldSkipPolling(taskInfo.recipientId, taskInfo.metadata, channel)) {
                        Log.d(TAG, "跳过轮询: ${taskInfo.recipientId}")
                        taskInfo.setStatus(PollingTaskStatus.PAUSED)
                        return@withTimeout
                    }
                    
                    // 执行实际轮询
                    val result = performSinglePoll(taskInfo)
                    
                    // 处理轮询结果
                    handlePollingResult(taskInfo, result)
                    
                    // 记录任务级别的响应时间
                    taskInfo.statistics.recordResponseTime(result.responseTime)
                    
                    taskInfo.setStatus(PollingTaskStatus.RUNNING)
                }
                
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "轮询超时: ${taskInfo.recipientId}, 任务将被标记为失败并重新调度")
                handlePollingError(taskInfo, e)
            } catch (e: Exception) {
                Log.e(TAG, "轮询任务执行失败: ${taskInfo.recipientId}", e)
                handlePollingError(taskInfo, e)
            } finally {
                // 确保在任何情况下都释放执行门闩
                taskInfo.finishExecution()
            }
        }
    }
    
    /**
     * 执行单次轮询
     */
    private suspend fun performSinglePoll(taskInfo: PollingTaskInfo): PollingExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // 获取Provider
            val provider = transportManager.getProvider(taskInfo.metadata.providerType)
            if (provider == null) {
                Log.e(TAG, "Provider不可用: ${taskInfo.metadata.providerType}")
                val responseTime = System.currentTimeMillis() - startTime
                return PollingExecutionResult.failure("Provider不可用", responseTime)
            }
            
            // 获取轮询状态（优先使用缓存）
            val cacheKey = "${taskInfo.recipientId}_${taskInfo.metadata.providerType}"
            val pollingState = pollingStateCache[cacheKey] ?: run {
                // 缓存未命中时读数据库，并缓存结果
                val state = pollingStateTable.getPollingState(taskInfo.recipientId, taskInfo.metadata.providerType)
                state?.let { pollingStateCache[cacheKey] = it }
                state
            }
            
            // 执行新的文件操作轮询
            val pollingResult = performFileBasedPolling(provider, taskInfo, pollingState)
            
            val responseTime = System.currentTimeMillis() - startTime
            
            // 只要轮询成功就更新数据库，避免 processedFiles 丢失导致重复下载
            if (pollingResult.isSuccess) {
                // 始终保存 processedFiles，即使没有新消息
                // 这样可以防止附件文件等被重复下载
                pollingStateTable.recordSuccessfulPoll(
                    taskInfo.recipientId,
                    taskInfo.metadata.providerType,
                    pollingResult.processedFiles,
                    pollingResult.messagesFound
                )
                // 清除缓存，下次轮询时获取最新状态
                pollingStateCache.remove(cacheKey)
                
                if (pollingResult.messagesFound > 0) {
                    Log.d(TAG, "发现${pollingResult.messagesFound}条新消息，已更新数据库: ${taskInfo.recipientId}")
                } else {
                    Log.v(TAG, "轮询成功，无新消息: ${taskInfo.recipientId}")
                }
            } else {
                // 轮询失败，记录日志但不写数据库（除非是严重错误）
                Log.w(TAG, "轮询失败: ${taskInfo.recipientId}, error=${pollingResult.error}")
            }
            
            PollingExecutionResult(
                isSuccess = pollingResult.isSuccess,
                messagesFound = pollingResult.messagesFound,
                responseTime = responseTime,
                error = pollingResult.error,
                needsRetry = pollingResult.needsRetry
            )
            
        } catch (e: TimeoutCancellationException) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.w(TAG, "轮询超时: ${taskInfo.recipientId}")
            PollingExecutionResult.failure("TIMEOUT", responseTime)
            
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "轮询过程中发生错误: ${taskInfo.recipientId}", e)
            PollingExecutionResult.failure(e.message ?: "UNKNOWN_ERROR", responseTime)
        }
    }
    
    /**
     * 执行基于文件操作的轮询（支持增量查询优化）
     * 
     * 优化：合并messages和attachments目录的查询为一次请求，减少网络往返
     */
    private suspend fun performFileBasedPolling(
        provider: TransportProvider,
        taskInfo: PollingTaskInfo,
        pollingState: org.thoughtcrime.securesms.tap.database.TransportPollingStateTable.PollingState?
    ): FilePollingResult {
        return try {
            // 优化：直接查询basePath，而不是分别查询messages/和attachments/
            // 这样只需一次网络请求，将延迟从~400ms降低到~200ms
            val basePath = taskInfo.metadata.getReceiveMetadata().path
            
            Log.v(TAG, "轮询路径: $basePath (合并查询), recipient: ${taskInfo.recipientId}")
            
            val allFiles = mutableListOf<FileInfo>()
            val processedFiles = pollingState?.processedFiles ?: emptySet()
            
            // 生成marker缓存key（使用basePath而不是子目录）
            val markerKey = "${taskInfo.recipientId}:${taskInfo.metadata.providerType}:${basePath}"
            
            // 获取上次的marker（如果存在）
            val cachedMarkerInfo = markerCache[markerKey]
            val marker = if (cachedMarkerInfo != null && !cachedMarkerInfo.isExpired()) {
                Log.d(TAG, "使用缓存的marker进行增量查询: path=$basePath, ${cachedMarkerInfo.getSummary()}")
                cachedMarkerInfo.marker
            } else {
                if (cachedMarkerInfo != null) {
                    Log.d(TAG, "Marker已过期，重置为全量查询: path=$basePath")
                    markerCache.remove(markerKey)
                }
                null // marker不存在或已过期，执行全量查询
            }
            
            // 单次网络请求获取所有文件
            val listResult = try {
                withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                    errorHandler.executeWithRetry({
                        // 使用增量查询API - 查询basePath获取所有子文件
                        val result = provider.listFilesWithMarker(
                            path = basePath,
                            metadata = taskInfo.metadata,
                            marker = marker,
                            maxKeys = 1000
                        )
                        
                        // 保存返回的nextMarker（如果有）
                        if (result is TransportResult.Success && result.metadata != null) {
                            val nextMarker = result.metadata["nextMarker"] as? String
                            val fileCount = result.files?.size ?: 0
                            
                            if (!nextMarker.isNullOrEmpty()) {
                                // 有nextMarker，保存用于下次增量查询
                                markerCache[markerKey] = PathMarkerInfo(
                                    marker = nextMarker,
                                    lastUpdateTime = System.currentTimeMillis(),
                                    path = basePath
                                )
                                Log.d(TAG, "保存新marker: path=$basePath, marker=${nextMarker.take(20)}..., files=$fileCount")
                            } else if (fileCount > 0) {
                                // 有文件但nextMarker为null → 保存最后一个文件的key作为marker实现增量查询
                                // 修复：不清除marker，而是使用字典序最大的文件key
                                val lastFileKey = result.files?.maxByOrNull { it.path }?.path
                                if (lastFileKey != null) {
                                    markerCache[markerKey] = PathMarkerInfo(
                                        marker = lastFileKey,
                                        lastUpdateTime = System.currentTimeMillis(),
                                        path = basePath
                                    )
                                    Log.d(TAG, "保存最后文件key作为marker: path=$basePath, marker=${lastFileKey.takeLast(50)}, files=$fileCount")
                                } else {
                                    Log.w(TAG, "无法获取最后文件key，清除marker: path=$basePath")
                                    markerCache.remove(markerKey)
                                }
                            } else if (marker != null) {
                                // 使用了marker但没有新文件 → 保持marker并更新时间戳
                                val existingMarkerInfo = markerCache[markerKey]
                                if (existingMarkerInfo != null) {
                                    markerCache[markerKey] = PathMarkerInfo(
                                        marker = existingMarkerInfo.marker,
                                        lastUpdateTime = System.currentTimeMillis(),
                                        path = basePath
                                    )
                                    Log.v(TAG, "目录为空，刷新marker时间戳: path=$basePath")
                                }
                            }
                        }
                        
                        result
                    }, ErrorContext(
                        providerType = taskInfo.metadata.providerType,
                        operationType = "listFilesWithMarker",
                        targetId = taskInfo.recipientId,
                        channelId = "${taskInfo.metadata.providerType}:${taskInfo.recipientId}",
                        metadata = mapOf(
                            "pollingPath" to basePath,
                            "useMarker" to (marker != null)
                        )
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "listFilesWithMarker失败: path=$basePath, recipient=${taskInfo.recipientId}", e)
                TransportResult.failure(TransportError.NETWORK_ERROR, true, "增量列举文件失败")
            }
            
            // 收集文件列表（过滤只保留messages/和attachments/子目录的文件）
            if (listResult is TransportResult.Success && !listResult.files.isNullOrEmpty()) {
                // 只保留messages/和attachments/目录下的文件
                val filteredFiles = listResult.files.filter { file ->
                    val path = file.path.lowercase()
                    path.contains("/messages/") || path.contains("/attachments/")
                }
                allFiles.addAll(filteredFiles)
                
                if (filteredFiles.size < listResult.files.size) {
                    Log.v(TAG, "过滤后文件数: ${filteredFiles.size}/${listResult.files.size}")
                }
            }
            
            if (allFiles.isEmpty()) {
                Log.d(TAG, "未找到文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            // 修复排序：使用文件名中的timestamp而不是lastModified
            // 原因：COS的lastModified是上传时间，可能不准确
            // 文件名格式：senderId_messageId_timestamp.dat
            val sortedFiles = sortFilesByMessageTimestamp(allFiles)
            
            val newFiles = sortedFiles.filter { file ->
                !processedFiles.contains(file.name) && 
                file.lastModified > (pollingState?.lastProcessedTime ?: 0) &&
                shouldRetryFileProcessing(file.name, taskInfo.recipientId)
            }
            
            if (newFiles.isEmpty()) {
                Log.d(TAG, "没有新文件: ${taskInfo.recipientId}")
                return FilePollingResult.success(emptySet(), 0)
            }
            
            Log.d(TAG, "找到新文件数量: ${newFiles.size}, recipient: ${taskInfo.recipientId}")
            newFiles.forEach { file ->
                Log.d(TAG, "[TapTimeTest] T4_POLL_DETECT | msgId=${file.name} | timestamp=${System.currentTimeMillis()}")
            }
            
            var messagesProcessed = 0
            val newProcessedFiles = mutableSetOf<String>()
            
            // 优化：使用并发下载，设置并发限制为4，避免过多并发请求
            val maxConcurrentDownloads = TapPollingConstants.PollingService.MAX_CONCURRENT_DOWNLOADS
            
            // 将文件分组，每组最多maxConcurrentDownloads个文件
            newFiles.chunked(maxConcurrentDownloads).forEach { fileChunk ->
                // 并发下载一组文件
                val downloadResults = coroutineScope {
                    fileChunk.map { file ->
                        async {
                            try {
                                val downloadResult = withTimeout(TapPollingConstants.PollingService.POLLING_TIMEOUT_MS) {
                                    errorHandler.executeWithRetry({
                                        provider.downloadFile(file, taskInfo.metadata)
                                    }, ErrorContext(
                                        providerType = taskInfo.metadata.providerType,
                                        operationType = "downloadFile",
                                        targetId = taskInfo.recipientId,
                                        channelId = "${taskInfo.metadata.providerType}:${taskInfo.recipientId}",
                                        metadata = mapOf("fileName" to file.name)
                                    ))
                                }
                                
                                Pair(file, downloadResult)
                            } catch (e: Exception) {
                                Log.e(TAG, "下载文件时发生异常: ${file.name}", e)
                                Pair(file, null)
                            }
                        }
                    }.awaitAll()
                }
                
                // 处理下载结果（保持串行处理以维持消息顺序）
                for ((file, downloadResult) in downloadResults) {
                    try {
                        if (downloadResult is TransportResult.Success && downloadResult.data != null) {
                            // 基于路径区分文件类型，只解析消息文件
                            if (file.isInMessagesDirectory()) {
                                val message = provider.parseTransportMessage(downloadResult.data, file, taskInfo.metadata)
                                if (message != null) {
                                    Log.d(TAG, "[TapTimeTest] T5_DOWNLOAD_END | msgId=${message.timestamp} | timestamp=${System.currentTimeMillis()}")
                                    val processResult = messageProcessor.processTapTransportMessage(message)
                                    if (processResult is TapProcessResult.Success) {
                                        messagesProcessed++
                                        // 仅在成功处理消息后标记文件已处理
                                        newProcessedFiles.add(file.name)
                                        // 清除失败记录（如果存在）
                                        clearFileProcessingFailure(file.name, taskInfo.recipientId)
                                        Log.d(TAG, "消息处理成功: ${file.name}")
                                    } else {
                                        // 消息处理失败，记录失败并判断是否可重试
                                        val error = (processResult as? TapProcessResult.Failed)?.error ?: "Unknown processing error"
                                        val shouldRetry = recordFileProcessingFailure(file.name, taskInfo.recipientId, error)
                                        if (!shouldRetry) {
                                            // 超过重试次数，标记为已处理避免无限重试
                                            newProcessedFiles.add(file.name)
                                            Log.w(TAG, "消息处理失败超过重试次数，跳过: ${file.name}")
                                        } else {
                                            Log.w(TAG, "消息处理失败，将重试: ${file.name}, error=$error")
                                        }
                                    }
                                } else {
                                    // 消息文件解析失败，记录失败信息
                                    newProcessedFiles.add(file.name)
                                    Log.w(TAG, "消息文件解析失败: ${file.name}")
                                }
                            } else {
                                // 附件文件，直接标记为已处理
                                newProcessedFiles.add(file.name)
                                Log.v(TAG, "附件文件已发现，无需解析: ${file.name}")
                            }
                        } else {
                            // 下载失败，不标记已处理，下次继续尝试
                            Log.w(TAG, "文件下载失败: ${file.name}")
                            
                            // 异常情况下记录失败，判断是否可重试
                            val shouldRetry = recordFileProcessingFailure(file.name, taskInfo.recipientId, "Download failed")
                            if (!shouldRetry) {
                                // 超过重试次数，标记为已处理
                                newProcessedFiles.add(file.name)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理文件时发生异常: ${file.name}", e)
                        // 异常情况下记录失败，判断是否可重试
                        val shouldRetry = recordFileProcessingFailure(file.name, taskInfo.recipientId, e.message ?: "Exception during processing")
                        if (!shouldRetry) {
                            // 超过重试次数，标记为已处理
                            newProcessedFiles.add(file.name)
                        }
                    }
                }
            }
            
            // 更新任务信息中的已处理文件列表
            taskInfo.lastProcessedFiles = newProcessedFiles
            
            // 如果成功处理了消息，更新通道活跃时间以维持正确的活跃度级别
            if (messagesProcessed > 0) {
                try {
                    val channel = channelManager.getActiveChannel(
                        taskInfo.recipientId,
                        taskInfo.metadata.providerType
                    )
                    channel?.let {
                        channelManager.updateChannelSuccess(it.channelId)
                        Log.d(TAG, "更新通道活跃时间: recipient=${taskInfo.recipientId}, messagesProcessed=$messagesProcessed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "更新通道活跃时间失败: recipient=${taskInfo.recipientId}", e)
                }
            }
            
            FilePollingResult.success(newProcessedFiles, messagesProcessed)
            
        } catch (e: Exception) {
            Log.e(TAG, "文件轮询异常: ${taskInfo.recipientId}", e)
            FilePollingResult.failure(e.message ?: "UNKNOWN_ERROR", needsRetry = true)
        }
    }
    
    /**
     * 处理轮询结果
     * 
     * 优化：实现动态退避机制，连续空轮询时自动增加间隔
     */
    private suspend fun handlePollingResult(taskInfo: PollingTaskInfo, result: PollingExecutionResult) {
        when {
            result.isSuccess -> {
                // 轮询成功
                taskInfo.recordSuccess()
                taskInfo.recordMessagesFound(result.messagesFound)
                
                // 更新通道活跃时间（如果有新消息）
                if (result.messagesFound > 0) {
                    try {
                        val channel = channelManager.getActiveChannel(
                            taskInfo.recipientId,
                            taskInfo.metadata.providerType
                        )
                        channel?.let {
                            channelManager.updateChannelSuccess(it.channelId)
                            Log.v(TAG, "更新通道活跃时间: recipient=${taskInfo.recipientId}, messagesFound=${result.messagesFound}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "更新通道活跃时间失败: recipient=${taskInfo.recipientId}", e)
                    }
                }
                
                // 【新策略】无论有无新消息，都根据时间窗口重新计算轮询间隔
                pollingLock.write {
                    try {
                        // 获取通道信息以获取最后活跃时间
                        val channel = channelManager.getActiveChannel(
                            taskInfo.recipientId,
                            taskInfo.metadata.providerType
                        )
                        
                        // 使用新的基于时间窗口的间隔计算
                        val newInterval = calculatePollingIntervalByTimeWindow(channel)
                        val currentInterval = taskInfo.getCurrentInterval()
                        
                        if (newInterval != currentInterval) {
                            val windowDesc = if (channel != null) {
                                val timeSinceLastMsg = System.currentTimeMillis() - channel.lastActiveAt
                                TapPollingConstants.TimeBasedInterval.getWindowDescription(timeSinceLastMsg)
                            } else {
                                "无通道信息"
                            }
                            
                            if (result.messagesFound > 0) {
                                Log.d(TAG, "收到${result.messagesFound}条消息，调整轮询间隔: recipient=${taskInfo.recipientId}, ${currentInterval}ms -> ${newInterval}ms [$windowDesc]")
                            } else {
                                Log.d(TAG, "空轮询，根据时间窗口调整间隔: recipient=${taskInfo.recipientId}, ${currentInterval}ms -> ${newInterval}ms [$windowDesc]")
                            }
                            
                            // 取消当前任务
                            taskInfo.task?.cancel(false)
                            
                            // 更新间隔
                            taskInfo.setCurrentInterval(newInterval)
                            
                            // 重新调度任务（立即执行，无延迟）
                            val newTask = schedulePollingTask(taskInfo, isRescheduling = true)
                            taskInfo.task = newTask
                        } else {
                            if (result.messagesFound > 0) {
                                Log.v(TAG, "收到消息但轮询间隔无需调整: recipient=${taskInfo.recipientId}, interval=${currentInterval}ms")
                            } else {
                                Log.v(TAG, "空轮询，间隔无需调整: recipient=${taskInfo.recipientId}, interval=${currentInterval}ms")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "重新评估轮询间隔失败: recipient=${taskInfo.recipientId}", e)
                    }
                }
            }
            
            result.needsRetry -> {
                // 需要重试
                Log.d(TAG, "轮询需要重试: ${taskInfo.recipientId}, reason=${result.error}")
                // 保持当前状态，等待下次轮询
            }
            
            else -> {
                // 轮询失败
                taskInfo.recordError()
                
                // 检查是否需要暂停轮询
                if (taskInfo.consecutiveErrors.get() >= TapPollingConstants.ErrorBackoff.MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "轮询连续失败次数过多，暂停轮询: ${taskInfo.recipientId}")
                    removePollingTarget(taskInfo.recipientId, taskInfo.metadata.providerType)
                }
            }
        }
    }
    
    /**
     * 从数据库获取已处理的文件列表
     */
    private fun getProcessedFilesFromDatabase(recipientId: String, providerType: String): Set<String> {
        return try {
            // 优先使用缓存获取已处理文件列表
            val cacheKey = "${recipientId}_${providerType}"
            val pollingState = pollingStateCache[cacheKey] ?: run {
                val state = pollingStateTable.getPollingState(recipientId, providerType)
                state?.let { pollingStateCache[cacheKey] = it }
                state
            }
            pollingState?.processedFiles ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "获取已处理文件列表失败: recipientId=$recipientId", e)
            emptySet()
        }
    }
    
    /**
     * 处理轮询错误
     */
    private fun handlePollingError(taskInfo: PollingTaskInfo, error: Throwable) {
        taskInfo.recordError()
        taskInfo.setStatus(PollingTaskStatus.ERROR_SUSPENDED)
        
        Log.e(TAG, "轮询任务出现严重错误，暂停任务: ${taskInfo.recipientId}", error)
        
        // 取消当前任务
        taskInfo.task?.cancel(false)
        taskInfo.task = null
    }
    
    /**
     * 启动清理任务
     */
    private fun startCleanupTask() {
        cleanupTask = pollingExecutor?.scheduleWithFixedDelay(
            { performCleanup() },
            TapPollingConstants.PollingService.CLEANUP_INTERVAL_MS,
            TapPollingConstants.PollingService.CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 执行清理任务
     */
    private fun performCleanup() {
        var toRemoveCount = 0
        
        try {
            // 在锁内执行清理
            pollingLock.write {
                val currentTime = System.currentTimeMillis()
                val toRemove = mutableListOf<String>()
                
                // 清理异常和过期的任务
                pollingTasks.forEach { (recipientId, taskInfo) ->
                    when {
                        // 清理长时间错误暂停的任务
                        taskInfo.status == PollingTaskStatus.ERROR_SUSPENDED && 
                                currentTime - taskInfo.getLastSuccessTime() > TapPollingConstants.PollingService.ERROR_TASK_CLEANUP_MS -> {
                            Log.i(TAG, "清理长期错误任务: $recipientId")
                            toRemove.add(recipientId)
                        }
                        
                        // 清理休眠状态的任务
                        taskInfo.getActivityLevel() == TransportActivityLevel.DORMANT &&
                                currentTime - taskInfo.getLastPollTime() > TapPollingConstants.PollingService.DORMANT_TASK_CLEANUP_MS -> {
                            Log.i(TAG, "清理休眠任务: $recipientId")
                            toRemove.add(recipientId)
                        }
                    }
                }
                
                // 执行清理
                toRemove.forEach { recipientId ->
                    pollingTasks[recipientId]?.cleanup()
                    pollingTasks.remove(recipientId)
                }
                
                toRemoveCount = toRemove.size
                
                // 清理文件处理失败记录 - 修复内存泄漏
                cleanupFileProcessingFailures(currentTime)
                
                Log.d(TAG, "清理任务完成: 移除${toRemoveCount}个任务")
            }
            
            // ✅ 在锁外异步清理队列
            if (toRemoveCount > 0) {
                serviceScope?.launch {
                    try {
                        delay(100)
                        pollingExecutor?.purge()
                        Log.d(TAG, "定期清理：异步清理了${toRemoveCount}个已取消的任务")
                    } catch (e: Exception) {
                        Log.w(TAG, "定期清理：异步清理失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理任务失败", e)
        }
    }
    
    /**
     * 清理文件处理失败记录，防止内存泄漏
     */
    private fun cleanupFileProcessingFailures(currentTime: Long) {
        try {
            val keysToRemove = mutableListOf<String>()
            val maxFailureRecords = TapPollingConstants.PollingService.MAX_FILE_FAILURE_RECORDS
            val failureExpiryTime = TapPollingConstants.ErrorBackoff.FILE_FAILURE_EXPIRY_MS
            
            // 按时间清理过期记录
            fileProcessingFailures.forEach { (key, failure) ->
                if (currentTime - failure.lastFailureTime > failureExpiryTime) {
                    keysToRemove.add(key)
                }
            }
            
            // 如果记录数量超过限制，清理最老的记录
            if (fileProcessingFailures.size > maxFailureRecords) {
                val sortedFailures = fileProcessingFailures.toList()
                    .sortedBy { it.second.lastFailureTime }
                
                val excessCount = fileProcessingFailures.size - maxFailureRecords
                for (i in 0 until excessCount) {
                    keysToRemove.add(sortedFailures[i].first)
                }
            }
            
            // 执行清理
            keysToRemove.forEach { key ->
                fileProcessingFailures.remove(key)
            }
            
            if (keysToRemove.isNotEmpty()) {
                Log.d(TAG, "清理文件处理失败记录: ${keysToRemove.size}条")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理文件处理失败记录时出错", e)
        }
    }
    
    /**
     * 清理所有资源
     */
    private fun cleanup() {
        try {
            // 取消清理任务
            cleanupTask?.cancel(false)
            cleanupTask = null
            
            // 取消所有轮询任务
            pollingTasks.values.forEach { it.cleanup() }
            pollingTasks.clear()
            
            // 清理marker缓存
            markerCache.clear()
            Log.d(TAG, "已清理所有marker缓存")
            
            // 关闭协程作用域
            serviceScope?.cancel()
            serviceScope = null
            
            // 关闭线程池
            pollingExecutor?.shutdown()
            try {
                if (pollingExecutor?.awaitTermination(5, TimeUnit.SECONDS) == false) {
                    pollingExecutor?.shutdownNow()
                }
            } catch (e: InterruptedException) {
                pollingExecutor?.shutdownNow()
                Thread.currentThread().interrupt()
            }
            pollingExecutor = null
            
        } catch (e: Exception) {
            Log.e(TAG, "资源清理时发生错误", e)
        }
    }
    
    /**
     * 清理特定recipient的marker缓存
     * 
     * @param recipientId 接收者ID
     * @param providerType Provider类型
     */
    private fun clearMarkersForRecipient(recipientId: String, providerType: String) {
        try {
            val keysToRemove = markerCache.keys.filter { key ->
                key.startsWith("${recipientId}:${providerType}:")
            }
            
            for (key in keysToRemove) {
                markerCache.remove(key)
            }
            
            if (keysToRemove.isNotEmpty()) {
                Log.d(TAG, "清理marker缓存: recipient=$recipientId, provider=$providerType, 清理数量=${keysToRemove.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理marker缓存时出错: recipient=$recipientId", e)
        }
    }
    
    /**
     * 按消息时间戳排序文件
     * 
     * 从文件名中提取timestamp进行排序，而不是使用COS的lastModified时间
     * 文件名格式：senderId_messageId_timestamp.dat 或 senderId_recipientId_messageId_timestamp.dat
     * 
     * 原因：COS的lastModified是上传时间，在批量上传时可能不准确或顺序错误
     * 而文件名中的timestamp是消息的真实发送时间，应该用它来保证显示顺序正确
     * 
     * @param files 待排序的文件列表
     * @return 按消息时间戳升序排列的文件列表
     */
    private fun sortFilesByMessageTimestamp(files: List<FileInfo>): List<FileInfo> {
        return try {
            files.sortedBy { file ->
                extractTimestampFromFileName(file.name) ?: file.lastModified
            }
        } catch (e: Exception) {
            Log.w(TAG, "按消息时间戳排序失败，回退到lastModified排序", e)
            FileInfo.sortByTime(files, ascending = true)
        }
    }
    
    /**
     * 从文件名中提取时间戳
     * 
     * 支持的格式：
     * - senderId_messageId_timestamp.dat (3段)
     * - senderId_recipientId_messageId_timestamp.dat (4段)
     * - group_groupId_senderId_messageId_timestamp.dat (5段，群组消息)
     * 
     * @param fileName 文件名
     * @return 提取的时间戳，失败返回null
     */
    private fun extractTimestampFromFileName(fileName: String): Long? {
        return try {
            val baseName = fileName.substringBeforeLast('.')
            val parts = baseName.split('_')
            
            when {
                // 群组消息格式：group_groupId_senderId_messageId_timestamp
                parts.size >= 5 && parts[0] == "group" -> {
                    parts[4].toLongOrNull()
                }
                // 标准格式：senderId_recipientId_messageId_timestamp
                parts.size == 4 -> {
                    parts[3].toLongOrNull()
                }
                // 简化格式：senderId_messageId_timestamp
                parts.size == 3 -> {
                    parts[2].toLongOrNull()
                }
                // 其他格式，尝试最后一段
                parts.size >= 2 -> {
                    parts.last().toLongOrNull()
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.v(TAG, "从文件名提取时间戳失败: $fileName", e)
            null
        }
    }
    
    /**
     * 记录文件处理失败
     * @param fileName 文件名
     * @param recipientId 接收者ID
     * @param error 错误信息
     * @return true 如果应该重试，false 如果已达到重试上限
     */
    private fun recordFileProcessingFailure(fileName: String, recipientId: String, error: String): Boolean {
        val failureKey = "${recipientId}:${fileName}"
        val currentTime = System.currentTimeMillis()
        
        val existingFailure = fileProcessingFailures[failureKey]
        val newFailureCount = (existingFailure?.failureCount ?: 0) + 1
        
        // 检查是否在退避期内
        if (existingFailure != null && 
            currentTime - existingFailure.lastFailureTime < TapPollingConstants.ErrorBackoff.FILE_RETRY_BACKOFF_MS) {
            // 仍在退避期内，不重试
            return false
        }
        
        val failure = FileProcessingFailure(
            fileName = fileName,
            recipientId = recipientId,
            failureCount = newFailureCount,
            lastFailureTime = currentTime,
            lastError = error
        )
        
        fileProcessingFailures[failureKey] = failure
        
        // 如果失败次数超过上限，不再重试
        if (newFailureCount >= TapPollingConstants.ErrorBackoff.MAX_FILE_RETRY_ATTEMPTS) {
            Log.w(TAG, "文件处理失败次数达到上限: $fileName, count=$newFailureCount")
            return false
        }
        
        return true
    }
    
    /**
     * 清除文件处理失败记录
     */
    private fun clearFileProcessingFailure(fileName: String, recipientId: String) {
        val failureKey = "${recipientId}:${fileName}"
        fileProcessingFailures.remove(failureKey)
    }
    
    /**
     * 检查文件是否应该重试处理
     */
    private fun shouldRetryFileProcessing(fileName: String, recipientId: String): Boolean {
        val failureKey = "${recipientId}:${fileName}"
        val failure = fileProcessingFailures[failureKey] ?: return true
        
        val currentTime = System.currentTimeMillis()
        
        // 检查是否超过重试次数
        if (failure.failureCount >= TapPollingConstants.ErrorBackoff.MAX_FILE_RETRY_ATTEMPTS) {
            return false
        }
        
        // 检查是否过了退避时间
        return currentTime - failure.lastFailureTime >= TapPollingConstants.ErrorBackoff.FILE_RETRY_BACKOFF_MS
    }
    
    // === PollingIntervalCallback 实现 ===
    
    /**
     * 调整单个目标的轮询间隔
     */
    fun adjustPollingInterval(recipientId: String, changeType: IntervalChangeType): Boolean {
        return pollingLock.write {
            try {
                val taskInfo = pollingTasks[recipientId]
                if (taskInfo == null) {
                    Log.w(TAG, "调整轮询间隔失败，任务不存在: $recipientId")
                    return@write false
                }
                
                val currentInterval = taskInfo.getCurrentInterval()
                val newInterval = calculateNewInterval(currentInterval, changeType, taskInfo.metadata.providerType)
                
                if (newInterval != currentInterval) {
                    Log.d(TAG, "动态调整轮询间隔: recipient=$recipientId, changeType=$changeType, ${currentInterval}ms -> ${newInterval}ms")
                    
                    // 取消当前任务
                    taskInfo.task?.cancel(false)
                    
                    // 更新间隔
                    taskInfo.setCurrentInterval(newInterval)
                    
                    // 重新调度任务（立即执行，无延迟）
                    val newTask = schedulePollingTask(taskInfo, isRescheduling = true)
                    taskInfo.task = newTask
                    
                    return@write true
                } else {
                    Log.d(TAG, "轮询间隔无需调整: recipient=$recipientId, interval=${currentInterval}ms")
                    return@write false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "动态调整轮询间隔失败: recipient=$recipientId", e)
                false
            }
        }
    }
    
    /**
     * 全局轮询调整
     */
    fun adjustGlobalPolling(changeType: IntervalChangeType) {
        // 在锁外预取通道信息
        val channelCache = if (changeType == IntervalChangeType.REEVALUATE) {
            pollingLock.read {
                pollingTasks.mapValues { (recipientId, taskInfo) ->
                    channelManager.getActiveChannel(recipientId, taskInfo.metadata.providerType)
                }
            }
        } else {
            emptyMap()
        }
        
        pollingLock.write {
            try {
                Log.d(TAG, "全局轮询调整: changeType=$changeType, 影响任务数=${pollingTasks.size}")
                
                val adjustedCount = pollingTasks.values.count { taskInfo ->
                    val currentInterval = taskInfo.getCurrentInterval()
                    val newInterval = when (changeType) {
                        IntervalChangeType.REEVALUATE -> {
                            // 使用预取的通道信息计算间隔
                            val channel = channelCache[taskInfo.recipientId]
                            calculatePollingInterval(taskInfo.metadata, channel)
                        }
                        IntervalChangeType.RESET -> {
                            // 重置到Provider特定的默认间隔
                            getProviderDefaultInterval(taskInfo.metadata.providerType)
                        }
                        else -> {
                            calculateNewInterval(currentInterval, changeType, taskInfo.metadata.providerType)
                        }
                    }
                    
                    if (newInterval != currentInterval) {
                        // 取消当前任务
                        taskInfo.task?.cancel(false)
                        
                        // 更新间隔
                        taskInfo.setCurrentInterval(newInterval)
                        
                        // 重新调度任务（立即执行，无延迟）
                        val newTask = schedulePollingTask(taskInfo, isRescheduling = true)
                        taskInfo.task = newTask
                        
                        true
                    } else {
                        false
                    }
                }
                
                Log.i(TAG, "全局轮询调整完成: changeType=$changeType, 调整任务数=$adjustedCount")
                
            } catch (e: Exception) {
                Log.e(TAG, "全局轮询调整失败", e)
            }
        }
    }
    
    /**
     * 获取Provider特定的默认间隔
     */
    private fun getProviderDefaultInterval(providerType: String): Long {
        return TapPollingConstants.ProviderIntervals.getBaseInterval(providerType)
    }
    
    /**
     * 根据变化类型计算新的轮询间隔
     * @param currentInterval 当前间隔
     * @param changeType 变化类型
     * @param providerType Provider类型，用于获取特定限制
     */
    private fun calculateNewInterval(currentInterval: Long, changeType: IntervalChangeType, providerType: String = ""): Long {
        val calculatedInterval = when (changeType) {
            IntervalChangeType.INCREASE -> {
                // 增加间隔（降低频率）
                (currentInterval * 1.5).toLong()
            }
            IntervalChangeType.DECREASE -> {
                // 减少间隔（提高频率）
                (currentInterval * 0.7).toLong()
            }
            IntervalChangeType.RESET -> {
                // 重置时返回Provider默认间隔
                getProviderDefaultInterval(providerType)
            }
            IntervalChangeType.ERROR_BACKOFF -> {
                // 错误退避
                (currentInterval * 2.0).toLong()
            }
            IntervalChangeType.LOW_POWER -> {
                // 低电量模式
                (currentInterval * 3.0).toLong()
            }
            IntervalChangeType.REEVALUATE -> {
                // 重新评估时返回当前间隔，实际调整在全局调整方法中处理
                return currentInterval
            }
        }
        
        // 应用Provider特定的限制，如果没有指定Provider则使用全局限制
        return if (providerType.isNotEmpty()) {
            val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(providerType)
            calculatedInterval.coerceIn(minInterval, maxInterval)
        } else {
            // 回退到保守的全局限制
            val globalMin = TapPollingConstants.PollingService.GLOBAL_MIN_INTERVAL_MS
            val globalMax = when (changeType) {
                IntervalChangeType.ERROR_BACKOFF -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_10MIN_MS
                IntervalChangeType.LOW_POWER -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_15MIN_MS
                else -> TapPollingConstants.PollingService.GLOBAL_MAX_INTERVAL_5MIN_MS
            }
            calculatedInterval.coerceIn(globalMin, globalMax)
        }
    }

    /**
     * 计算轮询间隔 - 简化版本
     */
    private fun calculatePollingInterval(metadata: TransportMetadata, channel: TransportChannel?): Long {
        // 获取Provider特定的基础间隔
        val baseInterval = TapPollingConstants.ProviderIntervals.getBaseInterval(metadata.providerType)
        
        // 根据活跃度调整
        val activityLevel = calculateActivityLevel(channel)
        val activityMultiplier = when (activityLevel) {
            TransportActivityLevel.ACTIVE -> TapPollingConstants.ActivityMultipliers.ACTIVE_MULTIPLIER
            TransportActivityLevel.INACTIVE -> TapPollingConstants.ActivityMultipliers.INACTIVE_MULTIPLIER
            TransportActivityLevel.BACKGROUND -> TapPollingConstants.ActivityMultipliers.BACKGROUND_MULTIPLIER
            TransportActivityLevel.SUSPENDED -> TapPollingConstants.ActivityMultipliers.SUSPENDED_MULTIPLIER
            TransportActivityLevel.DORMANT -> TapPollingConstants.ActivityMultipliers.DORMANT_MULTIPLIER
        }
        
        val calculatedInterval = (baseInterval * activityMultiplier).toLong()
        
        // 应用Provider限制
        val (minInterval, maxInterval) = TapPollingConstants.ProviderLimits.getLimits(metadata.providerType)
        return calculatedInterval.coerceIn(minInterval, maxInterval)
    }
    
    /**
     * 计算传输活跃度级别 - 简化版本
     */
    private fun calculateActivityLevel(channel: TransportChannel?): TransportActivityLevel {
        if (channel == null) {
            return TransportActivityLevel.INACTIVE
        }
        
        val currentTime = System.currentTimeMillis()
        val lastActiveTime = channel.lastActiveAt
        val timeDiffMs = currentTime - lastActiveTime
        
        return when {
            timeDiffMs <= TapPollingConstants.ActivityThresholds.ACTIVE_THRESHOLD_MS -> TransportActivityLevel.ACTIVE
            timeDiffMs <= TapPollingConstants.ActivityThresholds.INACTIVE_THRESHOLD_MS -> TransportActivityLevel.INACTIVE
            timeDiffMs <= TapPollingConstants.ActivityThresholds.BACKGROUND_THRESHOLD_MS -> TransportActivityLevel.BACKGROUND
            timeDiffMs <= TapPollingConstants.ActivityThresholds.SUSPENDED_THRESHOLD_MS -> TransportActivityLevel.SUSPENDED
            else -> TransportActivityLevel.DORMANT
        }
    }
    
    /**
     * 基于时间窗口计算轮询间隔（新策略）
     * 
     * 根据距离最后一条消息的时间，采用阶梯式降级策略：
     * - 5分钟内有消息：500ms快速轮询（活跃期）
     * - 5-10分钟：10秒轮询（中等活跃期）
     * - 10-20分钟：20秒轮询（低活跃期）
     * - 20分钟-1小时：30秒轮询（静默期）
     * - 1小时以上：1分钟轮询（长期静默期）
     * 
     * @param channel 传输通道信息，包含最后活跃时间
     * @return 建议的轮询间隔（毫秒）
     */
    private fun calculatePollingIntervalByTimeWindow(channel: TransportChannel?): Long {
        if (channel == null) {
            // 没有通道信息，使用默认的低频轮询
            return TapPollingConstants.TimeBasedInterval.SILENT_INTERVAL_MS
        }
        
        val currentTime = System.currentTimeMillis()
        val timeSinceLastMessage = currentTime - channel.lastActiveAt
        
        // 使用TapPollingConstants中的阶梯式间隔计算
        return TapPollingConstants.TimeBasedInterval.calculateInterval(timeSinceLastMessage)
    }
    
    /**
     * 判断是否应该跳过轮询 - 简化版本
     */
    private fun shouldSkipPolling(recipientId: String, metadata: TransportMetadata, channel: TransportChannel?): Boolean {
        // 检查通道状态
        if (channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.FAILED ||
            channel?.status == org.thoughtcrime.securesms.tap.TransportChannelStatus.CLOSED) {
            Log.d(TAG, "跳过轮询: 通道状态异常 - recipient=$recipientId, status=${channel.status}")
            return true
        }
        
        // 检查活跃度级别
        val activityLevel = calculateActivityLevel(channel)
        if (activityLevel == TransportActivityLevel.DORMANT) {
            Log.d(TAG, "跳过轮询: 通信已休眠 - recipient=$recipientId")
            return true
        }
        
        return false
    }
    
    /**
     * 创建简单的统计信息
     */
    private fun createSimpleStatistics(): TapPollingStatistics {
        val totalPolls = pollingTasks.values.sumOf { it.statistics.totalPolls.get() }
        val successfulPolls = pollingTasks.values.sumOf { it.statistics.successfulPolls.get() }
        val failedPolls = pollingTasks.values.sumOf { it.statistics.failedPolls.get() }
        val messagesFound = pollingTasks.values.sumOf { it.statistics.messagesFound.get() }
        
        val averageResponseTime = if (totalPolls > 0) {
            pollingTasks.values
                .map { it.getAverageResponseTime() }
                .filter { it > 0 }
                .average()
                .let { if (it.isNaN()) 0L else it.toLong() }
        } else {
            0L
        }
        
        // 计算各Provider的目标数量
        val providerTargetCounts = pollingTasks.values.groupBy { it.metadata.providerType }
            .mapValues { (_, tasks) ->
                val activeCount = tasks.count { it.status == PollingTaskStatus.RUNNING || it.status == PollingTaskStatus.POLLING }
                activeCount to tasks.size
            }
        
        // 计算活跃度级别的目标数量
        val activityTargetCounts = pollingTasks.values.groupBy { it.getActivityLevel() }
            .mapValues { (_, tasks) -> tasks.size }
        
        return TapPollingStatistics(
            totalPolls = totalPolls,
            successfulPolls = successfulPolls,
            failedPolls = failedPolls,
            messagesFound = messagesFound,
            averageResponseTime = averageResponseTime,
            providerStatistics = providerTargetCounts.mapValues { (providerType, counts) ->
                ProviderPollingStats(
                    providerType = providerType,
                    totalPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.totalPolls.get() },
                    successfulPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.successfulPolls.get() },
                    failedPolls = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.failedPolls.get() },
                    messagesFound = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .sumOf { it.statistics.messagesFound.get() },
                    averageResponseTime = pollingTasks.values.filter { it.metadata.providerType == providerType }
                        .map { it.getAverageResponseTime() }
                        .filter { it > 0 }
                        .average()
                        .let { if (it.isNaN()) 0L else it.toLong() },
                    activeTargets = counts.first,
                    totalTargets = counts.second
                )
            },
            activityLevelStats = activityTargetCounts.mapValues { (activityLevel, targetCount) ->
                ActivityLevelStats(
                    activityLevel = activityLevel,
                    targetCount = targetCount,
                    totalPolls = pollingTasks.values.filter { it.getActivityLevel() == activityLevel }
                        .sumOf { it.statistics.totalPolls.get() },
                    successfulPolls = pollingTasks.values.filter { it.getActivityLevel() == activityLevel }
                        .sumOf { it.statistics.successfulPolls.get() },
                    averageInterval = activityLevel.baseIntervalMs
                )
            },
            lastHourStats = RecentPollingStats(
                timeRangeMs = 3600000L,
                totalPolls = 0L,
                successfulPolls = 0L,
                messagesFound = 0L,
                averageResponseTime = 0L,
                peakPollingRate = 0.0,
                averagePollingRate = 0.0
            ),
            last24HourStats = RecentPollingStats(
                timeRangeMs = 86400000L,
                totalPolls = 0L,
                successfulPolls = 0L,
                messagesFound = 0L,
                averageResponseTime = 0L,
                peakPollingRate = 0.0,
                averagePollingRate = 0.0
            )
        )
    }
} 

/**
 * 间隔变化类型
 */
enum class IntervalChangeType {
    INCREASE,       // 增加间隔（降低频率）
    DECREASE,       // 减少间隔（提高频率）
    RESET,          // 重置到默认间隔
    ERROR_BACKOFF,  // 错误退避
    LOW_POWER,      // 低电量模式
    REEVALUATE      // 重新评估
}

/**
 * 路径Marker信息
 * 
 * 用于增量查询优化，记录每个路径的最后marker位置和更新时间
 * 
 * @param marker COS返回的marker，用于下次增量查询
 * @param lastUpdateTime 最后更新时间戳（毫秒）
 * @param path 路径字符串（用于调试）
 */
data class PathMarkerInfo(
    val marker: String,
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val path: String = ""
) {
    /**
     * 检查marker是否过期
     * 
     * 为了避免长时间不轮询导致marker失效，超过一定时间后重置marker
     * 
     * @param maxAgeMs 最大有效期（毫秒），默认30分钟
     * @return true表示已过期，需要重新全量查询
     */
    fun isExpired(maxAgeMs: Long = 30 * 60 * 1000L): Boolean {
        return System.currentTimeMillis() - lastUpdateTime > maxAgeMs
    }
    
    /**
     * 获取摘要信息（用于日志）
     */
    fun getSummary(): String {
        val markerPreview = if (marker.length > 20) {
            "${marker.take(10)}...${marker.takeLast(10)}"
        } else {
            marker
        }
        val age = System.currentTimeMillis() - lastUpdateTime
        return "PathMarkerInfo[marker=$markerPreview, age=${age}ms, path=$path]"
    }
} 