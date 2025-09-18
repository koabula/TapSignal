package org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.cos.CosClient
import org.thoughtcrime.securesms.tap.provider.cos.cos.CosClientFactory
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.SubAccountPoolManager
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils.CosMessageValidator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * COS通道建立确认管理器
 * 负责验证双向CAM凭证并确认通道建立
 */
class CosChannelEstablishmentManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(CosChannelEstablishmentManager::class.java)
        
        @Volatile
        private var INSTANCE: CosChannelEstablishmentManager? = null
        
        fun getInstance(context: Context): CosChannelEstablishmentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CosChannelEstablishmentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val channelManager = CosChannelManager.getInstance(context)
    private val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
    private val pollingManager = CosPollingManager.getInstance(context)
    
    // 跟踪正在验证的通道
    private val verifyingChannels: MutableMap<String, ChannelVerificationState> = ConcurrentHashMap()
    
    /**
     * 尝试建立COS通道
     * 当双方都有访问信息时调用此方法
     * @param recipientId 对方的Service ID
     * @return 建立结果
     */
    fun attemptChannelEstablishment(recipientId: String): CompletableFuture<ChannelEstablishmentResult> {
        Log.i(TAG, "尝试建立COS通道: recipientId=$recipientId")
        
        return CompletableFuture.supplyAsync {
            try {
                // 1. 获取通道信息
                val channel = channelManager.getChannel(recipientId)
                if (channel == null) {
                    Log.e(TAG, "未找到通道信息: recipientId=$recipientId")
                    return@supplyAsync ChannelEstablishmentResult.Failure("未找到通道信息")
                }
                
                // 2. 检查是否已经建立
                if (channel.status == ChannelStatus.ACTIVE) {
                    Log.i(TAG, "通道已经建立: recipientId=$recipientId")
                    return@supplyAsync ChannelEstablishmentResult.Success(channel.channelId, "通道已经建立")
                }
                
                // 3. 检查是否有双向访问信息
                if (channel.myAccessInfo == null || channel.theirAccessInfo == null) {
                    Log.w(TAG, "缺少双向访问信息，无法建立通道: recipientId=$recipientId")
                    return@supplyAsync ChannelEstablishmentResult.Failure("缺少双向访问信息")
                }
                
                // 4. 开始验证过程
                val verificationState = ChannelVerificationState(
                    recipientId = recipientId,
                    channelId = channel.channelId,
                    startTime = System.currentTimeMillis(),
                    status = VerificationStatus.VERIFYING_ACCESS
                )
                verifyingChannels[recipientId] = verificationState
                
                // 5. 验证双向访问权限
                val verificationResult = verifyBidirectionalAccess(channel)
                if (!verificationResult.isSuccess) {
                    verifyingChannels.remove(recipientId)
                    Log.e(TAG, "双向访问验证失败: ${verificationResult.errorMessage}")
                    return@supplyAsync ChannelEstablishmentResult.Failure(verificationResult.errorMessage ?: "验证失败")
                }
                
                // 6. 建立通道
                val established = channelManager.establishChannel(recipientId)
                if (!established) {
                    verifyingChannels.remove(recipientId)
                    Log.e(TAG, "通道建立失败: recipientId=$recipientId")
                    return@supplyAsync ChannelEstablishmentResult.Failure("通道建立失败")
                }
                
                // 7. 将对方的子账户添加到子账户Pool
                channel.theirAccessInfo?.let { theirAccessInfo ->
                    subAccountPoolManager.addReceivedSubAccount(recipientId, theirAccessInfo)
                }
                
                // 8. 启动轮询（如果尚未启动）
                if (!pollingManager.isPollingActive()) {
                    pollingManager.startPolling()
                }
                
                // 9. 清理验证状态
                verifyingChannels.remove(recipientId)
                
                Log.i(TAG, "COS通道建立成功: recipientId=$recipientId, channelId=${channel.channelId}")
                ChannelEstablishmentResult.Success(channel.channelId, "通道建立成功")
                
            } catch (e: Exception) {
                Log.e(TAG, "建立COS通道时发生异常", e)
                verifyingChannels.remove(recipientId)
                ChannelEstablishmentResult.Failure("建立通道时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 验证通道健康状况
     * @param recipientId 对方的Service ID
     * @return 验证结果
     */
    fun verifyChannelHealth(recipientId: String): CompletableFuture<ChannelHealthResult> {
        Log.i(TAG, "验证通道健康状况: recipientId=$recipientId")
        
        return CompletableFuture.supplyAsync {
            try {
                val channel = channelManager.getChannel(recipientId)
                if (channel == null || channel.status != ChannelStatus.ACTIVE) {
                    return@supplyAsync ChannelHealthResult.Unhealthy("通道未激活")
                }
                
                val theirAccessInfo = channel.theirAccessInfo
                if (theirAccessInfo == null) {
                    return@supplyAsync ChannelHealthResult.Unhealthy("缺少对方访问信息")
                }
                
                // 检查CAM凭证是否过期
                if (theirAccessInfo.isExpired()) {
                    return@supplyAsync ChannelHealthResult.Unhealthy("对方CAM凭证已过期")
                }
                
                // 尝试访问对方的COS存储
                val accessTest = testCosAccess(theirAccessInfo)
                if (!accessTest.isSuccess) {
                    return@supplyAsync ChannelHealthResult.Unhealthy("无法访问对方COS存储: ${accessTest.errorMessage}")
                }
                
                ChannelHealthResult.Healthy("通道健康")
                
            } catch (e: Exception) {
                Log.e(TAG, "验证通道健康状况时发生异常", e)
                ChannelHealthResult.Unhealthy("验证时发生异常: ${e.message}")
            }
        }
    }
    
    /**
     * 获取通道建立状态
     */
    fun getChannelEstablishmentStatus(recipientId: String): ChannelEstablishmentStatus {
        val channel = channelManager.getChannel(recipientId)
        val verificationState = verifyingChannels[recipientId]
        
        return when {
            verificationState != null -> {
                ChannelEstablishmentStatus.VERIFYING
            }
            channel == null -> {
                ChannelEstablishmentStatus.NOT_STARTED
            }
            channel.status == ChannelStatus.ACTIVE -> {
                ChannelEstablishmentStatus.ESTABLISHED
            }
            channel.myAccessInfo != null && channel.theirAccessInfo != null -> {
                ChannelEstablishmentStatus.READY_TO_ESTABLISH
            }
            channel.myAccessInfo != null || channel.theirAccessInfo != null -> {
                ChannelEstablishmentStatus.PARTIAL_INFO
            }
            else -> {
                ChannelEstablishmentStatus.PENDING
            }
        }
    }
    
    /**
     * 清理过期的验证状态
     */
    fun cleanupExpiredVerifications() {
        val now = System.currentTimeMillis()
        val expiredThreshold = 5 * 60 * 1000L // 5分钟
        
        val expiredVerifications = verifyingChannels.values.filter { state ->
            now - state.startTime > expiredThreshold
        }
        
        expiredVerifications.forEach { state ->
            verifyingChannels.remove(state.recipientId)
            Log.w(TAG, "清理过期的验证状态: recipientId=${state.recipientId}")
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 验证双向访问权限
     */
    private fun verifyBidirectionalAccess(channel: CosChannel): AccessVerificationResult {
        try {
            val myAccessInfo = channel.myAccessInfo!!
            val theirAccessInfo = channel.theirAccessInfo!!
            
            // 1. 验证我的访问信息
            val myAccessResult = CosMessageValidator.validateCosAccessInfo(myAccessInfo)
            if (myAccessResult is CosResult.Error) {
                return AccessVerificationResult(false, "我的访问信息验证失败: ${myAccessResult.exception.message}")
            }

            // 2. 验证对方的访问信息
            val theirAccessResult = CosMessageValidator.validateCosAccessInfo(theirAccessInfo)
            if (theirAccessResult is CosResult.Error) {
                return AccessVerificationResult(false, "对方访问信息验证失败: ${theirAccessResult.exception.message}")
            }
            
            // 3. 测试对方的COS访问
            val accessTest = testCosAccess(theirAccessInfo)
            if (!accessTest.isSuccess) {
                return AccessVerificationResult(false, "无法访问对方COS存储: ${accessTest.errorMessage}")
            }
            
            return AccessVerificationResult(true, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "验证双向访问权限时发生异常", e)
            return AccessVerificationResult(false, "验证时发生异常: ${e.message}")
        }
    }
    
    /**
     * 测试COS访问
     */
    private fun testCosAccess(accessInfo: CosAccessInfo): AccessTestResult {
        return try {
            // 检查访问信息是否过期
            if (accessInfo.isExpired()) {
                return AccessTestResult(false, "访问凭证已过期")
            }

            // 创建临时COS客户端
            val cosClient = CosClientFactory.createClientWithToken(
                provider = accessInfo.provider,
                region = accessInfo.region,
                bucketName = accessInfo.bucketName,
                accessKeyId = accessInfo.accessKeyId,
                secretAccessKey = accessInfo.secretAccessKey,
                sessionToken = accessInfo.sessionToken
            )

            // 尝试列举目录
            val files = cosClient.listFiles(accessInfo.sharedDirectory ?: "/outbox/")

            Log.d(TAG, "COS访问测试成功，找到 ${files.size} 个文件")
            AccessTestResult(true, null)

        } catch (e: Exception) {
            Log.e(TAG, "测试COS访问失败", e)
            AccessTestResult(false, e.message ?: "未知错误")
        }
    }
}

/**
 * 通道建立结果
 */
sealed class ChannelEstablishmentResult {
    data class Success(val channelId: String, val message: String) : ChannelEstablishmentResult()
    data class Failure(val errorMessage: String) : ChannelEstablishmentResult()
}

/**
 * 通道健康结果
 */
sealed class ChannelHealthResult {
    data class Healthy(val message: String) : ChannelHealthResult()
    data class Unhealthy(val reason: String) : ChannelHealthResult()
}

/**
 * 访问验证结果
 */
data class AccessVerificationResult(
    val isSuccess: Boolean,
    val errorMessage: String?
)

/**
 * 访问测试结果
 */
data class AccessTestResult(
    val isSuccess: Boolean,
    val errorMessage: String?
)

/**
 * 通道验证状态
 */
data class ChannelVerificationState(
    val recipientId: String,
    val channelId: String,
    val startTime: Long,
    val status: VerificationStatus
)

/**
 * 验证状态枚举
 */
enum class VerificationStatus {
    VERIFYING_ACCESS,       // 验证访问权限
    TESTING_CONNECTION,     // 测试连接
    ESTABLISHING,           // 建立中
    COMPLETED,              // 已完成
    FAILED                  // 失败
}

/**
 * 通道建立状态枚举
 */
enum class ChannelEstablishmentStatus {
    NOT_STARTED,            // 未开始
    PENDING,                // 等待中
    PARTIAL_INFO,           // 部分信息
    READY_TO_ESTABLISH,     // 准备建立
    VERIFYING,              // 验证中
    ESTABLISHED,            // 已建立
    FAILED                  // 失败
}
