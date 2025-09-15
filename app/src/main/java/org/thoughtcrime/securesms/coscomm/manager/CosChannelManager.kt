package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.*
import org.thoughtcrime.securesms.coscomm.storage.CosChannelStorage
import java.util.concurrent.ConcurrentHashMap

/**
 * COS通道管理器
 * 负责管理每个联系人的COS通信通道状态
 */
class CosChannelManager(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosChannelManager::class.java)
        
        @Volatile
        private var INSTANCE: CosChannelManager? = null
        
        fun getInstance(context: Context): CosChannelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosChannelManager(context.applicationContext).also {
                    INSTANCE = it
                    Log.d(TAG, "创建新的CosChannelManager实例: ${it.hashCode()}")
                }
            }
        }
    }
    
    private val channelStorage = CosChannelStorage(context)
    private val activeChannels: MutableMap<String, CosChannel> = ConcurrentHashMap()
    
    init {
        // 启动时加载所有活跃通道
        loadActiveChannels()
    }
    
    /**
     * 创建新的COS通道
     */
    fun createChannel(
        recipientId: String,
        requestId: String,
        status: ChannelStatus = ChannelStatus.PENDING
    ): CosChannel {
        val normalizedId = normalizeRecipientId(recipientId)
        Log.i(TAG, "创建新的COS通道: recipientId=$recipientId, 标准化后=$normalizedId, requestId=$requestId")

        val channel = CosChannel.create(normalizedId, requestId, status)

        // 保存到存储和缓存（saveChannel会处理标准化）
        saveChannel(channel)

        return channel
    }
    
    /**
     * 获取指定联系人的通道
     */
    fun getChannel(recipientId: String): CosChannel? {
        val normalizedId = normalizeRecipientId(recipientId)
        Log.d(TAG, "查找通道: recipientId=$recipientId, 标准化后=$normalizedId, 实例=${this.hashCode()}, 线程=${Thread.currentThread().name}")
        Log.d(TAG, "内存缓存大小: ${activeChannels.size}, 包含key: ${activeChannels.containsKey(normalizedId)}")

        val memoryChannel = activeChannels[normalizedId]
        if (memoryChannel != null) {
            Log.d(TAG, "从内存缓存找到通道: status=${memoryChannel.status}")
            return memoryChannel
        }

        Log.d(TAG, "内存缓存未找到，查询持久化存储")
        val storageChannel = channelStorage.getChannel(normalizedId)
        if (storageChannel != null) {
            Log.d(TAG, "从持久化存储找到通道: status=${storageChannel.status}")
            activeChannels[normalizedId] = storageChannel
            return storageChannel
        }

        Log.d(TAG, "持久化存储也未找到通道")
        return null
    }
    
    /**
     * 更新通道状态
     */
    fun updateChannelStatus(recipientId: String, newStatus: ChannelStatus): Boolean {
        val normalizedId = normalizeRecipientId(recipientId)
        Log.i(TAG, "更新通道状态: recipientId=$recipientId, 标准化后=$normalizedId, newStatus=$newStatus")

        val channel = getChannel(recipientId) ?: return false
        val updatedChannel = channel.updateStatus(newStatus)

        val success = saveChannel(updatedChannel)
        if (success) {
            Log.i(TAG, "通道状态更新成功，通知UI更新: recipientId=$recipientId, newStatus=$newStatus")
            // 通知UI更新v2标识
            notifyChannelStatusChanged(normalizedId)
        }
        return success
    }
    
    /**
     * 更新通道的访问信息
     */
    fun updateChannelAccessInfo(
        recipientId: String,
        myAccessInfo: CosAccessInfo? = null,
        theirAccessInfo: CosAccessInfo? = null
    ): Boolean {
        Log.i(TAG, "更新通道访问信息: recipientId=$recipientId")

        val channel = getChannel(recipientId) ?: return false

        // 从访问信息中提取通道目录名
        val channelDirectory = myAccessInfo?.let { extractChannelDirectoryFromAccessInfo(it) }
            ?: channel.channelDirectory

        val updatedChannel = channel.copy(
            myAccessInfo = myAccessInfo ?: channel.myAccessInfo,
            theirAccessInfo = theirAccessInfo ?: channel.theirAccessInfo,
            channelDirectory = channelDirectory,
            updatedAt = System.currentTimeMillis()
        )

        Log.i(TAG, "通道访问信息更新成功: recipientId=$recipientId, channelDirectory=$channelDirectory")
        return saveChannel(updatedChannel)
    }

    /**
     * 从CosAccessInfo中提取通道目录名
     */
    private fun extractChannelDirectoryFromAccessInfo(accessInfo: CosAccessInfo): String? {
        val sharedDirectory = accessInfo.sharedDirectory
        if (sharedDirectory.isNullOrEmpty()) return null

        val regex = Regex("/v2-channels/(signal-v2-\\d+-\\d+)/")
        val matchResult = regex.find(sharedDirectory)
        return matchResult?.groupValues?.get(1)
    }
    
    /**
     * 建立通道（双方都有访问信息时）
     */
    fun establishChannel(recipientId: String): Boolean {
        Log.i(TAG, "建立COS通道: recipientId=$recipientId")

        val channel = getChannel(recipientId) ?: return false

        if (channel.myAccessInfo != null && channel.theirAccessInfo != null) {
            val establishedChannel = channel.copy(
                status = ChannelStatus.ACTIVE,
                establishedTime = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val success = saveChannel(establishedChannel)
            if (success) {
                Log.i(TAG, "COS通道建立成功，通知UI更新: recipientId=$recipientId")
                // 通知UI更新v2标识
                notifyChannelStatusChanged(recipientId)
            }
            return success
        }

        Log.w(TAG, "无法建立通道，缺少访问信息: recipientId=$recipientId")
        return false
    }
    
    /**
     * 更新通道活动时间
     */
    fun updateChannelActivity(recipientId: String): Boolean {
        val channel = getChannel(recipientId) ?: return false
        val updatedChannel = channel.updateActivity()
        
        return saveChannel(updatedChannel)
    }
    
    /**
     * 更新通道统计信息
     */
    fun updateChannelStatistics(
        recipientId: String,
        messagesSent: Int = 0,
        messagesReceived: Int = 0,
        dataSent: Long = 0,
        dataReceived: Long = 0,
        errorCount: Int = 0
    ): Boolean {
        val channel = getChannel(recipientId) ?: return false
        
        var statistics = channel.statistics
        
        if (messagesSent > 0) {
            statistics = statistics.incrementSent(dataSent)
        }
        
        if (messagesReceived > 0) {
            statistics = statistics.incrementReceived(dataReceived)
        }
        
        if (errorCount > 0) {
            statistics = statistics.incrementError()
        }
        
        val updatedChannel = channel.copy(
            statistics = statistics,
            updatedAt = System.currentTimeMillis()
        )
        
        return saveChannel(updatedChannel)
    }
    
    /**
     * 获取所有活跃通道
     */
    fun getActiveChannels(): List<CosChannel> {
        return activeChannels.values.filter { it.isActive() }
    }
    
    /**
     * 获取所有通道
     */
    fun getAllChannels(): List<CosChannel> {
        return channelStorage.getAllChannels()
    }

    /**
     * 通知通道状态变化，触发UI更新
     */
    private fun notifyChannelStatusChanged(recipientId: String) {
        try {
            // 解析RecipientId，处理"RecipientId::X"格式
            val recipientIdObj = parseRecipientId(recipientId)
            if (recipientIdObj != null) {
                // 刷新Recipient数据
                org.thoughtcrime.securesms.database.SignalDatabase.runPostSuccessfulTransaction {
                    org.thoughtcrime.securesms.recipients.Recipient.live(recipientIdObj).refresh()
                }

                // 强制刷新聊天列表
                val threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads.getThreadIdIfExistsFor(recipientIdObj)
                if (threadId > 0) {
                    org.thoughtcrime.securesms.database.SignalDatabase.threads.update(threadId, false)
                }

                // 通知会话列表更新
                org.thoughtcrime.securesms.dependencies.AppDependencies.databaseObserver.notifyConversationListListeners()

                Log.d(TAG, "已通知UI更新v2标识: recipientId=$recipientId, threadId=$threadId")
            } else {
                Log.w(TAG, "无法解析RecipientId，跳过UI更新: recipientId=$recipientId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "通知UI更新失败: recipientId=$recipientId", e)
        }
    }

    /**
     * 标准化RecipientId字符串格式
     * 将所有格式统一为纯数字字符串，确保缓存键一致性
     */
    private fun normalizeRecipientId(recipientId: String): String {
        return try {
            // 情况1: 如果是RecipientId::X格式，提取数字部分
            if (recipientId.contains("::")) {
                val idPart = recipientId.split("::").lastOrNull()
                if (idPart != null && idPart.all { it.isDigit() }) {
                    return idPart
                }
            }

            // 情况2: 如果是纯数字ID，直接返回
            if (recipientId.all { it.isDigit() }) {
                return recipientId
            }

            // 情况3: 其他格式，返回原始字符串
            recipientId
        } catch (e: Exception) {
            Log.w(TAG, "标准化RecipientId失败: $recipientId", e)
            recipientId
        }
    }

    /**
     * 解析RecipientId字符串，支持多种格式
     */
    private fun parseRecipientId(recipientId: String): org.thoughtcrime.securesms.recipients.RecipientId? {
        val normalizedId = normalizeRecipientId(recipientId)
        return try {
            // 使用标准化后的ID进行解析
            if (normalizedId.all { it.isDigit() }) {
                val numericId = normalizedId.toLong()
                return org.thoughtcrime.securesms.recipients.RecipientId.from(numericId)
            }

            // 尝试直接解析
            org.thoughtcrime.securesms.recipients.RecipientId.from(normalizedId)
        } catch (e: Exception) {
            Log.w(TAG, "解析RecipientId失败: $recipientId -> $normalizedId", e)
            null
        }
    }
    
    /**
     * 删除通道
     */
    fun deleteChannel(recipientId: String): Boolean {
        val normalizedId = normalizeRecipientId(recipientId)
        Log.i(TAG, "删除COS通道: recipientId=$recipientId, 标准化后=$normalizedId")

        activeChannels.remove(normalizedId)
        return channelStorage.deleteChannel(normalizedId)
    }
    
    /**
     * 清理过期和无效的通道
     */
    fun cleanupExpiredChannels() {
        Log.i(TAG, "清理过期的COS通道")
        
        val allChannels = getAllChannels()
        val now = System.currentTimeMillis()
        
        allChannels.forEach { channel ->
            var shouldCleanup = false
            var newStatus = channel.status
            
            // 检查访问信息是否过期
            if (channel.theirAccessInfo?.isExpired() == true) {
                shouldCleanup = true
                newStatus = ChannelStatus.EXPIRED
            }
            
            // 检查通道是否长时间无活动
            val inactiveTime = now - channel.lastActivity
            if (inactiveTime > CosConstants.MESSAGE_RETENTION_DAYS * 24 * 60 * 60 * 1000L) {
                shouldCleanup = true
            }
            
            if (shouldCleanup) {
                if (newStatus != channel.status) {
                    updateChannelStatus(channel.recipientId, newStatus)
                } else {
                    deleteChannel(channel.recipientId)
                }
            }
        }
    }
    
    /**
     * 保存通道到存储和缓存
     */
    private fun saveChannel(channel: CosChannel): Boolean {
        return try {
            val normalizedId = normalizeRecipientId(channel.recipientId)
            Log.d(TAG, "保存通道: recipientId=${channel.recipientId}, 标准化后=$normalizedId, status=${channel.status}, 实例=${this.hashCode()}")

            // 创建标准化ID的通道副本
            val normalizedChannel = if (channel.recipientId != normalizedId) {
                channel.copy(recipientId = normalizedId)
            } else {
                channel
            }

            channelStorage.saveChannel(normalizedChannel)
            activeChannels[normalizedId] = normalizedChannel
            Log.d(TAG, "通道保存成功，内存缓存大小: ${activeChannels.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存通道失败", e)
            false
        }
    }
    
    /**
     * 加载所有活跃通道到内存
     */
    private fun loadActiveChannels() {
        try {
            val channels = channelStorage.getActiveChannels()
            channels.forEach { channel ->
                activeChannels[channel.recipientId] = channel
            }
            Log.i(TAG, "加载了 ${channels.size} 个活跃通道")
        } catch (e: Exception) {
            Log.e(TAG, "加载活跃通道失败", e)
        }
    }
}
