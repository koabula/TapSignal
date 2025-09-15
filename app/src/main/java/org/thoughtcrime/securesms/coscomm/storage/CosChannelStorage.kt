package org.thoughtcrime.securesms.coscomm.storage

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.CosChannel
import org.thoughtcrime.securesms.coscomm.data.ChannelStatus

/**
 * COS通道存储管理器
 * 负责COS通道数据的持久化存储
 */
class CosChannelStorage(context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosChannelStorage::class.java)
        private const val PREFS_NAME = "cos_channels"
        private const val KEY_CHANNEL_PREFIX = "channel_"
        private const val KEY_CHANNEL_LIST = "channel_list"
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    
    /**
     * 保存通道信息
     */
    fun saveChannel(channel: CosChannel): Boolean {
        return try {
            val json = objectMapper.writeValueAsString(channel)
            val key = KEY_CHANNEL_PREFIX + channel.recipientId
            
            sharedPrefs.edit()
                .putString(key, json)
                .apply()
            
            // 更新通道列表
            updateChannelList(channel.recipientId, add = true)
            
            Log.d(TAG, "保存通道成功: ${channel.recipientId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存通道失败: ${channel.recipientId}", e)
            false
        }
    }
    
    /**
     * 获取指定联系人的通道
     */
    fun getChannel(recipientId: String): CosChannel? {
        return try {
            val key = KEY_CHANNEL_PREFIX + recipientId
            val json = sharedPrefs.getString(key, null) ?: return null
            
            objectMapper.readValue<CosChannel>(json)
        } catch (e: Exception) {
            Log.e(TAG, "获取通道失败: $recipientId", e)
            null
        }
    }
    
    /**
     * 删除指定联系人的通道
     */
    fun deleteChannel(recipientId: String): Boolean {
        return try {
            val key = KEY_CHANNEL_PREFIX + recipientId
            
            sharedPrefs.edit()
                .remove(key)
                .apply()
            
            // 从通道列表中移除
            updateChannelList(recipientId, add = false)
            
            Log.d(TAG, "删除通道成功: $recipientId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除通道失败: $recipientId", e)
            false
        }
    }
    
    /**
     * 获取所有通道
     */
    fun getAllChannels(): List<CosChannel> {
        return try {
            val channelIds = getChannelList()
            val channels = mutableListOf<CosChannel>()
            
            channelIds.forEach { recipientId ->
                getChannel(recipientId)?.let { channel ->
                    channels.add(channel)
                }
            }
            
            channels
        } catch (e: Exception) {
            Log.e(TAG, "获取所有通道失败", e)
            emptyList()
        }
    }
    
    /**
     * 获取活跃通道
     */
    fun getActiveChannels(): List<CosChannel> {
        return getAllChannels().filter { it.isActive() }
    }
    
    /**
     * 获取指定状态的通道
     */
    fun getChannelsByStatus(status: ChannelStatus): List<CosChannel> {
        return getAllChannels().filter { it.status == status }
    }
    
    /**
     * 检查通道是否存在
     */
    fun hasChannel(recipientId: String): Boolean {
        val key = KEY_CHANNEL_PREFIX + recipientId
        return sharedPrefs.contains(key)
    }
    
    /**
     * 清理所有通道数据
     */
    fun clearAllChannels(): Boolean {
        return try {
            val channelIds = getChannelList()
            val editor = sharedPrefs.edit()
            
            // 删除所有通道数据
            channelIds.forEach { recipientId ->
                val key = KEY_CHANNEL_PREFIX + recipientId
                editor.remove(key)
            }
            
            // 清空通道列表
            editor.remove(KEY_CHANNEL_LIST)
            editor.apply()
            
            Log.i(TAG, "清理所有通道数据成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "清理所有通道数据失败", e)
            false
        }
    }
    
    /**
     * 获取通道数量
     */
    fun getChannelCount(): Int {
        return getChannelList().size
    }
    
    /**
     * 获取通道列表
     */
    private fun getChannelList(): Set<String> {
        return try {
            val json = sharedPrefs.getString(KEY_CHANNEL_LIST, null) ?: return emptySet()
            objectMapper.readValue<Set<String>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "获取通道列表失败", e)
            emptySet()
        }
    }
    
    /**
     * 更新通道列表
     */
    private fun updateChannelList(recipientId: String, add: Boolean) {
        try {
            val currentList = getChannelList().toMutableSet()
            
            if (add) {
                currentList.add(recipientId)
            } else {
                currentList.remove(recipientId)
            }
            
            val json = objectMapper.writeValueAsString(currentList)
            sharedPrefs.edit()
                .putString(KEY_CHANNEL_LIST, json)
                .apply()
                
        } catch (e: Exception) {
            Log.e(TAG, "更新通道列表失败", e)
        }
    }
    
    /**
     * 备份通道数据
     */
    fun backupChannels(): String? {
        return try {
            val allChannels = getAllChannels()
            objectMapper.writeValueAsString(allChannels)
        } catch (e: Exception) {
            Log.e(TAG, "备份通道数据失败", e)
            null
        }
    }
    
    /**
     * 从备份恢复通道数据
     */
    fun restoreChannels(backupData: String): Boolean {
        return try {
            val channels = objectMapper.readValue<List<CosChannel>>(backupData)
            
            // 清空现有数据
            clearAllChannels()
            
            // 恢复通道数据
            channels.forEach { channel ->
                saveChannel(channel)
            }
            
            Log.i(TAG, "恢复通道数据成功，共恢复 ${channels.size} 个通道")
            true
        } catch (e: Exception) {
            Log.e(TAG, "恢复通道数据失败", e)
            false
        }
    }
}
