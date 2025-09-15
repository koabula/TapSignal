/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.coscomm.manager

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.CosAccessInfo
import org.thoughtcrime.securesms.coscomm.data.CosResult
import org.thoughtcrime.securesms.coscomm.data.CosException
import org.thoughtcrime.securesms.coscomm.data.CosErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 子账户池管理器
 * 替代原有的CAM Pool，专门管理子账户永久凭证
 *
 * 修复内容：
 * 1. 加强单例模式线程安全
 * 2. 实现数据持久化存储
 * 3. 添加凭证验证机制
 * 4. 完善资源管理
 */
class SubAccountPoolManager private constructor(private val context: Context) {

    companion object {
        private val TAG = Log.tag(SubAccountPoolManager::class.java)

        // 持久化存储键名
        private const val PREF_NAME = "cos_subaccount_pool"
        private const val KEY_RECEIVED_SUBACCOUNTS = "received_subaccounts"
        private const val KEY_SHARED_SUBACCOUNTS = "shared_subaccounts"
        private const val KEY_LAST_CLEANUP_TIME = "last_cleanup_time"

        // 清理间隔：24小时
        private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: SubAccountPoolManager? = null

        // 双重检查锁定，确保线程安全
        fun getInstance(context: Context): SubAccountPoolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SubAccountPoolManager(context.applicationContext).also { instance ->
                    INSTANCE = instance
                    Log.d(TAG, "创建新的SubAccountPoolManager实例: ${instance.hashCode()}")
                    // 初始化时加载持久化数据
                    instance.loadPersistedData()
                }
            }
        }
    }

    // 读写锁，保护数据访问
    private val rwLock = ReentrantReadWriteLock()

    // JSON序列化工具
    private val objectMapper = ObjectMapper().apply {
        // 忽略未知属性，确保向后兼容性
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private val subAccountMapType = object : TypeReference<MutableMap<String, SubAccountEntry>>() {}

    // SharedPreferences存储
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // 接收到的子账户凭证（对方分享给我的）
    private val receivedSubAccounts: MutableMap<String, SubAccountEntry> = ConcurrentHashMap()

    // 分享出去的子账户凭证（我分享给对方的）
    private val sharedSubAccounts: MutableMap<String, SubAccountEntry> = ConcurrentHashMap()

    // 凭证验证缓存（避免频繁验证）
    private val credentialValidationCache: MutableMap<String, CredentialValidationResult> = ConcurrentHashMap()

    // 最后验证时间
    private var lastValidationTime: Long = 0
    
    /**
     * 加载持久化数据
     */
    private fun loadPersistedData() {
        rwLock.write {
            try {
                Log.d(TAG, "开始加载持久化的子账户数据")

                // 加载接收的子账户
                val receivedJson = sharedPreferences.getString(KEY_RECEIVED_SUBACCOUNTS, null)
                if (!receivedJson.isNullOrEmpty()) {
                    val receivedMap: MutableMap<String, SubAccountEntry> = objectMapper.readValue(receivedJson, subAccountMapType)
                    receivedSubAccounts.clear()
                    receivedSubAccounts.putAll(receivedMap)
                    Log.d(TAG, "加载接收子账户数据: ${receivedSubAccounts.size}个条目")
                }

                // 加载分享的子账户
                val sharedJson = sharedPreferences.getString(KEY_SHARED_SUBACCOUNTS, null)
                if (!sharedJson.isNullOrEmpty()) {
                    val sharedMap: MutableMap<String, SubAccountEntry> = objectMapper.readValue(sharedJson, subAccountMapType)
                    sharedSubAccounts.clear()
                    sharedSubAccounts.putAll(sharedMap)
                    Log.d(TAG, "加载分享子账户数据: ${sharedSubAccounts.size}个条目")
                }

                Log.i(TAG, "持久化数据加载完成: 接收=${receivedSubAccounts.size}, 分享=${sharedSubAccounts.size}")

                // 执行清理检查
                performCleanupIfNeeded()

            } catch (e: Exception) {
                Log.e(TAG, "加载持久化数据失败", e)
                // 清空可能损坏的数据
                receivedSubAccounts.clear()
                sharedSubAccounts.clear()
            }
        }
    }

    /**
     * 持久化数据到存储
     */
    private fun persistData() {
        rwLock.read {
            try {
                Log.d(TAG, "开始持久化子账户数据")

                val editor = sharedPreferences.edit()

                // 持久化接收的子账户
                val receivedJson = objectMapper.writeValueAsString(receivedSubAccounts)
                editor.putString(KEY_RECEIVED_SUBACCOUNTS, receivedJson)

                // 持久化分享的子账户
                val sharedJson = objectMapper.writeValueAsString(sharedSubAccounts)
                editor.putString(KEY_SHARED_SUBACCOUNTS, sharedJson)

                // 更新最后清理时间
                editor.putLong(KEY_LAST_CLEANUP_TIME, System.currentTimeMillis())

                editor.apply()

                Log.d(TAG, "子账户数据持久化完成: 接收=${receivedSubAccounts.size}, 分享=${sharedSubAccounts.size}")

            } catch (e: Exception) {
                Log.e(TAG, "持久化数据失败", e)
            }
        }
    }

    /**
     * 如果需要则执行清理
     */
    private fun performCleanupIfNeeded() {
        val lastCleanupTime = sharedPreferences.getLong(KEY_LAST_CLEANUP_TIME, 0)
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            Log.d(TAG, "执行定期清理检查")
            cleanExpiredSubAccounts()
        }
    }

    /**
     * 添加接收到的子账户凭证
     */
    fun addReceivedSubAccount(recipientId: String, accessInfo: CosAccessInfo): CosResult<Unit> {
        return rwLock.write {
            try {
                Log.i(TAG, "添加接收子账户凭证: recipientId=$recipientId, 实例=${this.hashCode()}")
                Log.d(TAG, "子账户详情: provider=${accessInfo.provider}, region=${accessInfo.region}")
                Log.d(TAG, "子账户详情: bucket=${accessInfo.bucketName}, directory=${accessInfo.sharedDirectory}")
                Log.d(TAG, "子账户详情: accessKeyId=${accessInfo.accessKeyId.take(8)}...")
                Log.d(TAG, "子账户详情: expireTime=${if (accessInfo.expireTime == Long.MAX_VALUE) "永久" else java.util.Date(accessInfo.expireTime)}")

                // 验证凭证有效性
                val validationResult = validateCredential(recipientId, accessInfo)
                if (!validationResult.isValid) {
                    Log.w(TAG, "子账户凭证验证失败: recipientId=$recipientId, reason=${validationResult.reason}")
                    return@write CosResult.Error(CosException(validationResult.errorCode ?: CosErrorCode.INVALID_CREDENTIALS))
                }

                if (accessInfo.isExpired()) {
                    Log.w(TAG, "子账户凭证已过期，拒绝添加: recipientId=$recipientId")
                    return@write CosResult.Error(CosException(CosErrorCode.TOKEN_EXPIRED))
                }

                val subAccountEntry = SubAccountEntry.create(recipientId, accessInfo)
                receivedSubAccounts[recipientId] = subAccountEntry

                Log.i(TAG, "接收子账户凭证添加成功: recipientId=$recipientId")
                Log.d(TAG, "子账户条目状态: isActive=${subAccountEntry.isActive}, isValid=${subAccountEntry.isValid()}")
                Log.d(TAG, "当前接收子账户Pool大小: ${receivedSubAccounts.size}")

                // 立即验证添加结果
                val verifyEntry = receivedSubAccounts[recipientId]
                if (verifyEntry != null) {
                    Log.d(TAG, "验证添加结果: 成功找到条目，isValid=${verifyEntry.isValid()}")
                } else {
                    Log.e(TAG, "验证添加结果: 未找到条目！")
                }

                // 持久化数据
                persistData()

                CosResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "添加接收子账户凭证失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
    
    /**
     * 添加分享的子账户凭证
     */
    fun addSharedSubAccount(recipientId: String, accessInfo: CosAccessInfo): CosResult<Unit> {
        return rwLock.write {
            try {
                Log.i(TAG, "添加分享子账户凭证: recipientId=$recipientId")

                // 验证凭证有效性
                val validationResult = validateCredential(recipientId, accessInfo)
                if (!validationResult.isValid) {
                    Log.w(TAG, "分享子账户凭证验证失败: recipientId=$recipientId, reason=${validationResult.reason}")
                    return@write CosResult.Error(CosException(validationResult.errorCode ?: CosErrorCode.INVALID_CREDENTIALS))
                }

                if (accessInfo.isExpired()) {
                    Log.w(TAG, "子账户凭证已过期，拒绝添加: recipientId=$recipientId")
                    return@write CosResult.Error(CosException(CosErrorCode.TOKEN_EXPIRED))
                }

                val subAccountEntry = SubAccountEntry.create(recipientId, accessInfo)
                sharedSubAccounts[recipientId] = subAccountEntry

                Log.i(TAG, "分享子账户凭证添加成功: recipientId=$recipientId")
                Log.d(TAG, "当前分享子账户Pool大小: ${sharedSubAccounts.size}")

                // 持久化数据
                persistData()

                CosResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "添加分享子账户凭证失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
    
    /**
     * 获取所有有效的接收子账户条目（用于轮询）
     */
    fun getAllValidReceivedSubAccounts(): List<SubAccountEntry> {
        return rwLock.read {
            Log.d(TAG, "获取所有有效的接收子账户条目: 实例=${this.hashCode()}, 线程=${Thread.currentThread().name}")
            Log.d(TAG, "  - 总Pool大小: ${receivedSubAccounts.size}")

            val allEntries = receivedSubAccounts.values.toList()
            allEntries.forEachIndexed { index, entry ->
                Log.d(TAG, "  - 条目[$index]: recipientId=${entry.recipientId}")
                Log.d(TAG, "    * isActive: ${entry.isActive}")
                Log.d(TAG, "    * accessInfo.isExpired(): ${entry.accessInfo.isExpired()}")
                Log.d(TAG, "    * isValid(): ${entry.isValid()}")
            }

            val validEntries = receivedSubAccounts.values.filter { it.isValid() && it.isActive }
            Log.d(TAG, "  - 有效条目数量: ${validEntries.size}")

            validEntries
        }
    }

    /**
     * 获取指定接收方的有效子账户凭证
     */
    fun getValidReceivedSubAccount(recipientId: String): SubAccountEntry? {
        return rwLock.read {
            Log.d(TAG, "获取接收子账户凭证: recipientId=$recipientId")
            val entry = receivedSubAccounts[recipientId]
            val isValid = entry?.isValid() == true && entry.isActive
            Log.d(TAG, "查询结果: found=${entry != null}, isValid=$isValid")
            if (isValid) entry else null
        }
    }

    /**
     * 获取指定接收方的有效分享子账户凭证
     */
    fun getValidSharedSubAccount(recipientId: String): SubAccountEntry? {
        return rwLock.read {
            Log.d(TAG, "获取分享子账户凭证: recipientId=$recipientId")
            val entry = sharedSubAccounts[recipientId]
            val isValid = entry?.isValid() == true && entry.isActive
            Log.d(TAG, "查询结果: found=${entry != null}, isValid=$isValid")
            if (isValid) entry else null
        }
    }
    
    /**
     * 验证凭证有效性
     */
    private fun validateCredential(recipientId: String, accessInfo: CosAccessInfo): CredentialValidationResult {
        try {
            // 检查缓存
            val cacheKey = "${recipientId}_${accessInfo.accessKeyId}"
            val cachedResult = credentialValidationCache[cacheKey]
            val currentTime = System.currentTimeMillis()

            // 如果缓存结果在5分钟内且有效，直接返回
            if (cachedResult != null && (currentTime - cachedResult.validationTime) < 5 * 60 * 1000) {
                return cachedResult
            }

            // 基本验证
            if (accessInfo.accessKeyId.isBlank() || accessInfo.secretAccessKey.isBlank()) {
                val result = CredentialValidationResult(false, "访问密钥不能为空", CosErrorCode.INVALID_CREDENTIALS)
                credentialValidationCache[cacheKey] = result
                return result
            }

            if (accessInfo.bucketName.isBlank()) {
                val result = CredentialValidationResult(false, "存储桶名称不能为空", CosErrorCode.INVALID_CREDENTIALS)
                credentialValidationCache[cacheKey] = result
                return result
            }

            if (accessInfo.sharedDirectory.isBlank()) {
                val result = CredentialValidationResult(false, "共享目录不能为空", CosErrorCode.INVALID_CREDENTIALS)
                credentialValidationCache[cacheKey] = result
                return result
            }

            // 检查是否过期
            if (accessInfo.isExpired()) {
                val result = CredentialValidationResult(false, "凭证已过期", CosErrorCode.TOKEN_EXPIRED)
                credentialValidationCache[cacheKey] = result
                return result
            }

            // 验证通过
            val result = CredentialValidationResult(true, "验证通过", null)
            credentialValidationCache[cacheKey] = result
            return result

        } catch (e: Exception) {
            Log.e(TAG, "凭证验证异常: recipientId=$recipientId", e)
            return CredentialValidationResult(false, "验证异常: ${e.message}", CosErrorCode.SYSTEM_ERROR)
        }
    }

    /**
     * 移除子账户凭证
     */
    fun removeSubAccount(recipientId: String): CosResult<Unit> {
        return rwLock.write {
            try {
                val removedShared = sharedSubAccounts.remove(recipientId)
                val removedReceived = receivedSubAccounts.remove(recipientId)

                if (removedShared != null || removedReceived != null) {
                    Log.i(TAG, "子账户凭证移除成功: recipientId=$recipientId")

                    // 清理验证缓存
                    credentialValidationCache.keys.removeAll { it.startsWith("${recipientId}_") }

                    // 持久化数据
                    persistData()

                    CosResult.Success(Unit)
                } else {
                    CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND))
                }
            } catch (e: Exception) {
                Log.e(TAG, "移除子账户凭证失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
    
    /**
     * 增加轮询错误计数
     */
    fun incrementPollingErrors(recipientId: String): CosResult<Unit> {
        return rwLock.write {
            try {
                val subAccountEntry = receivedSubAccounts[recipientId]
                if (subAccountEntry != null) {
                    val updatedEntry = subAccountEntry.incrementPollingError()
                    receivedSubAccounts[recipientId] = updatedEntry

                    Log.w(TAG, "轮询错误增加: recipientId=$recipientId, errors=${updatedEntry.pollingErrors}")

                    // 如果错误次数过多，暂时停用
                    if (updatedEntry.pollingErrors >= 5) { // MAX_POLLING_ERRORS
                        val suspendedEntry = updatedEntry.copy(isActive = false)
                        receivedSubAccounts[recipientId] = suspendedEntry
                        Log.w(TAG, "子账户条目因错误过多被暂停: recipientId=$recipientId")
                    }

                    // 持久化数据
                    persistData()

                    CosResult.Success(Unit)
                } else {
                    CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND, "子账户条目不存在"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "增加轮询错误失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }

    /**
     * 重置轮询错误计数
     */
    fun resetPollingErrors(recipientId: String): CosResult<Unit> {
        return rwLock.write {
            try {
                val subAccountEntry = receivedSubAccounts[recipientId]
                if (subAccountEntry != null) {
                    val updatedEntry = subAccountEntry.copy(pollingErrors = 0, isActive = true)
                    receivedSubAccounts[recipientId] = updatedEntry
                    Log.d(TAG, "轮询错误重置成功: recipientId=$recipientId")

                    // 持久化数据
                    persistData()

                    CosResult.Success(Unit)
                } else {
                    CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND, "子账户条目不存在"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "重置轮询错误失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }

    /**
     * 清理过期的子账户凭证
     */
    fun cleanExpiredSubAccounts(): CosResult<Int> {
        return rwLock.write {
            try {
                val now = System.currentTimeMillis()
                var cleanedCount = 0

                // 清理过期的分享子账户
                val expiredShared = sharedSubAccounts.values.filter { it.isExpired(now) }
                expiredShared.forEach { entry ->
                    sharedSubAccounts.remove(entry.recipientId)
                    cleanedCount++
                }

                // 清理过期的接收子账户
                val expiredReceived = receivedSubAccounts.values.filter { it.isExpired(now) }
                expiredReceived.forEach { entry ->
                    receivedSubAccounts.remove(entry.recipientId)
                    cleanedCount++
                }

                // 清理验证缓存中的过期条目
                val expiredCacheKeys = credentialValidationCache.entries.filter { (_, result) ->
                    (now - result.validationTime) > 30 * 60 * 1000 // 30分钟过期
                }.map { it.key }

                expiredCacheKeys.forEach { key ->
                    credentialValidationCache.remove(key)
                }

                if (cleanedCount > 0) {
                    Log.i(TAG, "清理过期子账户凭证: 共清理${cleanedCount}个")
                    // 持久化数据
                    persistData()
                }

                if (expiredCacheKeys.isNotEmpty()) {
                    Log.d(TAG, "清理过期验证缓存: ${expiredCacheKeys.size}个")
                }

                CosResult.Success(cleanedCount)
            } catch (e: Exception) {
                Log.e(TAG, "清理过期子账户凭证失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }

    /**
     * 停用子账户（用于权限错误等情况）
     */
    fun deactivateSubAccount(recipientId: String): CosResult<Unit> {
        return rwLock.write {
            try {
                val subAccountEntry = receivedSubAccounts[recipientId]
                if (subAccountEntry != null) {
                    val deactivatedEntry = subAccountEntry.copy(isActive = false)
                    receivedSubAccounts[recipientId] = deactivatedEntry
                    Log.w(TAG, "子账户已停用: recipientId=$recipientId")

                    // 持久化数据
                    persistData()

                    CosResult.Success(Unit)
                } else {
                    CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND, "子账户条目不存在"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "停用子账户失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }

    /**
     * 标记子账户为过期
     */
    fun markSubAccountExpired(recipientId: String): CosResult<Unit> {
        return rwLock.write {
            try {
                val subAccountEntry = receivedSubAccounts[recipientId]
                if (subAccountEntry != null) {
                    // 创建过期的访问信息
                    val expiredAccessInfo = subAccountEntry.accessInfo.copy(
                        expireTime = System.currentTimeMillis() - 1000 // 设置为已过期
                    )
                    val expiredEntry = subAccountEntry.copy(
                        accessInfo = expiredAccessInfo,
                        isActive = false
                    )
                    receivedSubAccounts[recipientId] = expiredEntry
                    Log.w(TAG, "子账户已标记为过期: recipientId=$recipientId")

                    // 持久化数据
                    persistData()

                    CosResult.Success(Unit)
                } else {
                    CosResult.Error(CosException(CosErrorCode.CHANNEL_NOT_FOUND, "子账户条目不存在"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "标记子账户过期失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }

    /**
     * 获取管理器统计信息
     */
    fun getStatistics(): SubAccountPoolStatistics {
        return rwLock.read {
            val activeReceived = receivedSubAccounts.values.count { it.isActive && it.isValid() }
            val activeShared = sharedSubAccounts.values.count { it.isActive && it.isValid() }
            val expiredReceived = receivedSubAccounts.values.count { it.isExpired() }
            val expiredShared = sharedSubAccounts.values.count { it.isExpired() }
            val errorReceived = receivedSubAccounts.values.count { it.pollingErrors >= 5 }

            SubAccountPoolStatistics(
                totalReceivedSubAccounts = receivedSubAccounts.size,
                totalSharedSubAccounts = sharedSubAccounts.size,
                activeReceivedSubAccounts = activeReceived,
                activeSharedSubAccounts = activeShared,
                expiredReceivedSubAccounts = expiredReceived,
                expiredSharedSubAccounts = expiredShared,
                errorSubAccounts = errorReceived,
                cacheSize = credentialValidationCache.size
            )
        }
    }

    /**
     * 清理所有数据（用于测试或重置）
     */
    fun clearAllData(): CosResult<Unit> {
        return rwLock.write {
            try {
                receivedSubAccounts.clear()
                sharedSubAccounts.clear()
                credentialValidationCache.clear()

                // 清理持久化数据
                sharedPreferences.edit()
                    .remove(KEY_RECEIVED_SUBACCOUNTS)
                    .remove(KEY_SHARED_SUBACCOUNTS)
                    .remove(KEY_LAST_CLEANUP_TIME)
                    .apply()

                Log.i(TAG, "所有子账户数据已清理")
                CosResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "清理所有数据失败", e)
                CosResult.Error(CosException(CosErrorCode.SYSTEM_ERROR, e))
            }
        }
    }
}

/**
 * 子账户条目
 */
data class SubAccountEntry(
    @JsonProperty("recipientId")
    val recipientId: String,

    @JsonProperty("accessInfo")
    val accessInfo: CosAccessInfo,

    @JsonProperty("active")
    val isActive: Boolean = true,

    @JsonProperty("pollingErrors")
    val pollingErrors: Int = 0,

    @JsonProperty("createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @JsonProperty("lastUsed")
    val lastUsed: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(recipientId: String, accessInfo: CosAccessInfo): SubAccountEntry {
            return SubAccountEntry(
                recipientId = recipientId,
                accessInfo = accessInfo,
                isActive = true,
                pollingErrors = 0,
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis()
            )
        }
    }

    /**
     * 检查子账户是否有效
     */
    @JsonIgnore
    fun isValid(): Boolean {
        return isActive && !accessInfo.isExpired() && pollingErrors < 5
    }

    /**
     * 检查子账户是否过期
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return accessInfo.isExpired()
    }

    /**
     * 增加轮询错误
     */
    fun incrementPollingError(): SubAccountEntry {
        return copy(
            pollingErrors = pollingErrors + 1
        )
    }

    /**
     * 解析通道目录
     */
    fun resolveChannelDirectory(): String {
        return accessInfo.sharedDirectory
    }
}

/**
 * 凭证验证结果
 */
data class CredentialValidationResult(
    val isValid: Boolean,
    val reason: String,
    val errorCode: CosErrorCode?,
    val validationTime: Long = System.currentTimeMillis()
)

/**
 * 子账户池统计信息
 */
data class SubAccountPoolStatistics(
    val totalReceivedSubAccounts: Int,
    val totalSharedSubAccounts: Int,
    val activeReceivedSubAccounts: Int,
    val activeSharedSubAccounts: Int,
    val expiredReceivedSubAccounts: Int,
    val expiredSharedSubAccounts: Int,
    val errorSubAccounts: Int,
    val cacheSize: Int
)
