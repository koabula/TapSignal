package org.thoughtcrime.securesms.cos.messages

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.CamPoolManager
import org.thoughtcrime.securesms.cos.CosConfig
import org.thoughtcrime.securesms.cos.CosConfigStorage
import org.thoughtcrime.securesms.cos.AwsS3Client
import org.thoughtcrime.securesms.cos.client.CamToken
import org.thoughtcrime.securesms.cos.client.CosClient
import org.thoughtcrime.securesms.cos.encryption.CosMessageEncryption
import org.thoughtcrime.securesms.cos.storage.CosMessageStorage
import org.thoughtcrime.securesms.cos.TencentCosClient
import org.thoughtcrime.securesms.cos.util.CosFileHelper
import org.thoughtcrime.securesms.cos.util.RecipientHasher
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.cos.CosClientFactory
import java.io.Serializable
import org.thoughtcrime.securesms.cos.CosFileInfo
import org.thoughtcrime.securesms.cos.CosAccessToken

/**
 * 负责轮询其他用户的COS存储，检查和下载新消息
 */
class CosMessagePoller private constructor(
    private val context: Context,
    private val camPoolManager: CamPoolManager,
    private val cosMessageEncryption: CosMessageEncryption,
    private val cosMessageSerializer: CosMessageSerializer,
    private val cosMessageStorage: CosMessageStorage?,
    private val messageProcessor: CosMessageProcessor?
) {
    companion object {
        private const val TAG = "CosMessagePoller"
        private const val POLLING_INTERVAL = 5000L // 5秒
        private const val MAX_POLLING_ERRORS = 3
        
        /**
         * 检查COS是否已配置
         */
        @JvmStatic
        fun isCosConfigured(context: Context): Boolean {
            return CosConfigStorage.getConfig(context) != null
        }
        
        /**
         * 创建COS客户端适配器
         */
        private fun createCosClient(context: Context): org.thoughtcrime.securesms.cos.client.CosClient? {
            // 检查是否有COS配置
            val config = CosConfigStorage.getConfig(context) ?: return null
            
            try {
                // 创建临时客户端适配器
                val orgClient = CosClientFactory.createClient(context)
                    ?: return null
                
                // 将org.thoughtcrime.securesms.cos.CosClient适配为org.thoughtcrime.securesms.cos.client.CosClient
                return object : org.thoughtcrime.securesms.cos.client.CosClient {
                    override suspend fun generateSessionToken(durationSeconds: Int, path: String): org.thoughtcrime.securesms.cos.client.CamToken {
                        val token = orgClient.generateTemporaryAccessToken(path, durationSeconds / 60)
                        return org.thoughtcrime.securesms.cos.client.CamToken(
                            secretId = token.secretId,
                            secretKey = token.secretKey,
                            token = token.sessionToken ?: "",
                            expiredTime = token.expiresAt
                        )
                    }
                    
                    override suspend fun uploadFile(localPath: String, remotePath: String): String {
                        val success = orgClient.uploadFile(java.io.File(localPath), remotePath)
                        return if (success) remotePath else ""
                    }
                    
                    override suspend fun downloadFile(remotePath: String, localPath: String): Boolean {
                        return orgClient.downloadFile(remotePath, java.io.File(localPath))
                    }
                    
                    override suspend fun listDirectory(remotePath: String): List<org.thoughtcrime.securesms.cos.client.CosFileInfo> {
                        return orgClient.listFiles(remotePath).map { fileInfo ->
                            org.thoughtcrime.securesms.cos.client.CosFileInfo(
                                name = fileInfo.key.substringAfterLast('/'),
                                path = fileInfo.key,
                                size = fileInfo.size,
                                lastModified = fileInfo.lastModified,
                                isDirectory = fileInfo.key.endsWith("/")
                            )
                        }
                    }
                    
                    override suspend fun createDirectory(remotePath: String): Boolean {
                        return orgClient.createDirectory(remotePath)
                    }
                    
                    override suspend fun fileExists(remotePath: String): Boolean {
                        return try {
                            val files = orgClient.listFiles(remotePath.substringBeforeLast("/"))
                            files.any { it.key == remotePath }
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "创建COS客户端适配器失败", e)
                return null
            }
        }
        
        /**
         * 工厂方法：创建标准实例
         */
        @JvmStatic
        fun create(context: Context): CosMessagePoller {
            val camPoolManager = AppDependencies.camPoolManager
            val encryption = CosMessageEncryption()
            val serializer = CosMessageSerializer()
            
            val client = createCosClient(context)
            val storage = if (client != null) {
                try {
                    CosMessageStorage(context, client, serializer, encryption)
                } catch (e: Exception) {
                    Log.w(TAG, "创建CosMessageStorage失败", e)
                    null
                }
            } else {
                Log.w(TAG, "无法创建CosClient，CosMessageStorage将为null")
                null
            }
            
            val processor = try {
                CosMessageProcessor(context)
            } catch (e: Exception) {
                Log.w(TAG, "创建CosMessageProcessor失败", e)
                null
            }
            
            return CosMessagePoller(
                context,
                camPoolManager,
                encryption,
                serializer,
                storage,
                processor
            )
        }
        
        /**
         * 工厂方法：使用指定的CamPoolManager创建实例
         */
        @JvmStatic
        fun create(context: Context, camPoolManager: CamPoolManager): CosMessagePoller {
            val encryption = CosMessageEncryption()
            val serializer = CosMessageSerializer()
            
            val client = createCosClient(context)
            val storage = if (client != null) {
                try {
                    CosMessageStorage(context, client, serializer, encryption)
                } catch (e: Exception) {
                    Log.w(TAG, "创建CosMessageStorage失败", e)
                    null
                }
            } else {
                Log.w(TAG, "无法创建CosClient，CosMessageStorage将为null")
                null
            }
            
            val processor = try {
                CosMessageProcessor(context)
            } catch (e: Exception) {
                Log.w(TAG, "创建CosMessageProcessor失败", e)
                null
            }
            
            return CosMessagePoller(
                context,
                camPoolManager,
                encryption,
                serializer,
                storage,
                processor
            )
        }
        
        /**
         * 创建一个空操作的CosMessagePoller实例，用于COS未配置或创建失败时
         */
        @JvmStatic
        fun createNoOp(context: Context, camPoolManager: CamPoolManager): CosMessagePoller {
            val instance = CosMessagePoller(
                context,
                camPoolManager, 
                CosMessageEncryption(), 
                CosMessageSerializer(),
                null,
                null
            )
            
            // 替换startPolling方法为空操作
            val field = CosMessagePoller::class.java.getDeclaredField("isPolling")
            field.isAccessible = true
            val isPolling = field.get(instance) as AtomicBoolean
            
            // 确保永远不会真正启动轮询
            isPolling.set(false)
            
            return instance
        }
    }

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 轮询作业映射表
    private val pollingJobs = ConcurrentHashMap<String, Job>()
    
    // 是否正在轮询
    private val isPolling = AtomicBoolean(false)
    
    // 新消息事件流
    private val _newMessageEvents = MutableSharedFlow<NewMessageEvent>()
    val newMessageEvents: Flow<NewMessageEvent> = _newMessageEvents
    
    // 轮询错误计数
    private val errorCounts = ConcurrentHashMap<String, Int>()

    /**
     * 开始轮询
     * 检查所有有效的CAM，并为每个发送者启动轮询任务
     */
    fun startPolling() {
        if (isPolling.getAndSet(true)) {
            Log.d(TAG, "轮询已经在运行中")
            return
        }
        
        // 检查COS存储是否可用
        if (cosMessageStorage == null) {
            Log.w(TAG, "CosMessageStorage为空，无法启动轮询")
            isPolling.set(false) // 重置状态
            return
        }
        
        if (messageProcessor == null) {
            Log.w(TAG, "CosMessageProcessor为空，无法启动轮询")
            isPolling.set(false) // 重置状态
            return
        }
        
        // 添加日志，打印CamPoolManager实例标识，便于排查
        Log.d(TAG, "开始轮询COS消息，CamPoolManager实例: ${System.identityHashCode(camPoolManager)}")
        
        Log.i(TAG, "开始轮询COS消息")
        
        scope.launch {
            while (isActive) {
                try {
                    // 检查并清理过期的CAM
                    camPoolManager.cleanExpiredCams()
                    
                    // 获取所有有效的接收CAM
                    val validReceivedCams = camPoolManager.getAllValidReceivedCams()
                    
                    Log.d(TAG, "当前有效的接收CAM数量: ${validReceivedCams.size}")
                    if (validReceivedCams.isEmpty()) {
                        Log.d(TAG, "没有有效的CAM，暂停轮询")
                        // 尝试从CamPoolManager直接获取所有CAM，不经过过期过滤
                        try {
                            val allCams = camPoolManager.getAllCamsForDebug()
                            if (allCams.isNotEmpty()) {
                                Log.d(TAG, "CAM池中有 ${allCams.size} 个CAM，但都被判断为过期")
                                allCams.forEach { (id, cam) ->
                                    val now = System.currentTimeMillis()
                                    val expiresIn = cam.expireTime - now
                                    Log.d(TAG, "CAM ID=$id, 过期时间=${cam.expireTime}, " +
                                        "当前时间=$now, 还有 ${expiresIn / 1000} 秒过期, " +
                                        "是否已过期=${cam.expireTime <= now}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "无法获取调试信息", e)
                        }
                    } else {
                        Log.d(TAG, "有效的接收CAM键: ${validReceivedCams.keys}")
                        // 输出有效CAM的详细过期时间信息
                        validReceivedCams.forEach { (id, cam) ->
                            val now = System.currentTimeMillis()
                            val expiresIn = cam.expireTime - now
                            Log.d(TAG, "有效CAM: ID=$id, 过期时间=${cam.expireTime}, " +
                                "还有 ${expiresIn / 60000} 分钟过期")
                        }
                    }
                    
                    if (validReceivedCams.isEmpty()) {
                        Log.d(TAG, "没有有效的CAM，暂停轮询")
                        delay(POLLING_INTERVAL * 2)
                        continue
                    }
                    
                    // 为每个发送者启动轮询任务
                    validReceivedCams.forEach { (recipientId, camSession) ->
                        Log.d(TAG, "检查发送者 $recipientId 的轮询任务")
                        if (!pollingJobs.containsKey(recipientId)) {
                            Log.i(TAG, "为发送者 $recipientId 启动新的轮询任务")
                            startPollingForSender(recipientId, camSession)
                        } else {
                            Log.d(TAG, "发送者 $recipientId 的轮询任务已存在")
                        }
                    }
                    
                    // 清理已经不需要的轮询任务
                    cleanupUnneededPollingJobs(validReceivedCams.keys)
                    
                    Log.d(TAG, "轮询周期完成，等待 ${POLLING_INTERVAL}ms")
                    delay(POLLING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "轮询过程中出错", e)
                    delay(POLLING_INTERVAL)
                }
            }
        }
    }

    /**
     * 停止轮询
     */
    fun stopPolling() {
        if (!isPolling.getAndSet(false)) {
            return
        }
        
        Log.i(TAG, "停止轮询消息")
        
        // 取消所有轮询任务
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
    }

    /**
     * 为特定发送者启动轮询任务
     */
    private fun startPollingForSender(recipientId: String, camSession: CamSession) {
        Log.d(TAG, "为发送者启动轮询: $recipientId")
        
        val job = scope.launch {
            try {
                pollMessagesFromSender(recipientId, camSession)
            } catch (e: Exception) {
                Log.e(TAG, "轮询发送者消息失败: $recipientId", e)
                
                // 增加错误计数
                val errorCount = errorCounts.compute(recipientId) { _, count -> (count ?: 0) + 1 }
                
                // 如果错误次数过多，停止该发送者的轮询
                if (errorCount != null && errorCount >= MAX_POLLING_ERRORS) {
                    Log.w(TAG, "发送者 $recipientId 的错误次数过多，停止轮询")
                    errorCounts.remove(recipientId)
                }
            } finally {
                // 关键修复：任务完成后自动从pollingJobs中移除自己
                // 这样下一轮主循环检查时会认为没有轮询任务，从而创建新任务
                pollingJobs.remove(recipientId)
                Log.d(TAG, "发送者 $recipientId 的轮询任务已完成并从任务池中移除")
            }
        }
        
        pollingJobs[recipientId] = job
    }

    /**
     * 轮询特定发送者的消息
     */
    private suspend fun pollMessagesFromSender(recipientId: String, camSession: CamSession) {
        try {
            Log.d(TAG, "开始轮询发送者消息: $recipientId")
            
            // 打印CAM会话详情
            Log.d(TAG, "CAM会话详情: 路径=${camSession.path}, 过期时间=${camSession.expireTime}, " +
                      "令牌ID=${camSession.camToken.secretId.take(8)}..., " +
                      "令牌过期时间=${camSession.camToken.expiredTime}, " +
                      "存储桶=${camSession.bucketName}, 区域=${camSession.region}")
            
            // 创建临时客户端
            val tempClient = createTemporaryClient(camSession)
            Log.d(TAG, "临时客户端创建成功: ${tempClient.javaClass.simpleName}")
            
            // 使用CAM会话中的路径，而不是重新计算
            // 移除路径开头的斜杠，以符合COS对象键格式
            val path = if (camSession.path.startsWith("/")) {
                camSession.path.substring(1)
            } else {
                camSession.path
            }
            Log.d(TAG, "使用CAM会话中的路径(已处理): $path，原始路径: ${camSession.path}")
            
            // 列出目录内容
            try {
                val files = tempClient.listDirectory(path)
                Log.d(TAG, "列出目录内容: $path, 找到 ${files.size} 个文件")
                
                // 如果有文件，打印文件详情
                if (files.isNotEmpty()) {
                    files.forEach { file ->
                        Log.d(TAG, "文件: 名称=${file.name}, 路径=${file.path}, 大小=${file.size}, 最后修改=${file.lastModified}")
                    }
                }
                
                // 从字符串ID中提取实际的RecipientId
                val actualRecipientId = extractRecipientId(recipientId)
                Log.d(TAG, "解析RecipientId: 原始=$recipientId, 提取后=$actualRecipientId")
                
                // 获取已处理消息的文件名集合
                val processedMessages = cosMessageStorage?.getProcessedMessages(actualRecipientId) ?: emptyList()
                Log.d(TAG, "已处理消息数量: ${processedMessages.size}")
                
                // 过滤出未处理的消息路径
                val unprocessedMessages = files
                    .filter { file -> 
                        // 只处理.bin结尾的文件，避免处理目录
                        val isBinFile = file.path.lowercase().endsWith(".bin")
                        // 确保文件未被处理过
                        val isUnprocessed = !processedMessages.contains(file.name)
                        
                        isBinFile && isUnprocessed
                    }
                    .map { file -> file.path }
                
                if (unprocessedMessages.isNotEmpty()) {
                    Log.i(TAG, "发现 ${unprocessedMessages.size} 条未处理消息，来自发送者: $recipientId")
                    unprocessedMessages.forEach { path ->
                        Log.d(TAG, "未处理消息路径: $path")
                    }
                } else {
                    Log.d(TAG, "没有发现未处理的消息，来自发送者: $recipientId")
                }
                
                // 处理新消息
                processNewMessages(recipientId, actualRecipientId, tempClient, unprocessedMessages, camSession)
                
                // 重置错误计数
                errorCounts.remove(recipientId)
            } catch (e: Exception) {
                Log.e(TAG, "列出目录内容失败: $path", e)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "轮询发送者消息失败: $recipientId", e)
            throw e
        }
    }

    /**
     * 从字符串ID中提取RecipientId对象
     * 处理形如"RecipientId::3"的字符串，提取出数字部分
     */
    private fun extractRecipientId(recipientIdString: String): RecipientId {
        return try {
            if (recipientIdString.contains("::")) {
                // 处理形如"RecipientId::3"的格式
                val idPart = recipientIdString.split("::").lastOrNull()
                if (idPart != null && idPart.isNotEmpty() && idPart.all { it.isDigit() }) {
                    RecipientId.from(idPart.toLong())
                } else {
                    Log.w(TAG, "无法从字符串提取有效的RecipientId: $recipientIdString")
                    throw IllegalArgumentException("无效的RecipientId字符串: $recipientIdString")
                }
            } else {
                // 尝试直接解析
                RecipientId.from(recipientIdString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析RecipientId失败: $recipientIdString", e)
            throw e
        }
    }

    /**
     * 处理新消息
     */
    private suspend fun processNewMessages(
        senderId: String,
        senderRecipientId: RecipientId,
        client: CosClient,
        messagePaths: List<String>,
        camSession: CamSession
    ) {
        if (messagePaths.isEmpty()) {
            return
        }
        
        Log.d(TAG, "开始处理 ${messagePaths.size} 条新消息，来自发送者: $senderId")
        
        // 过滤只处理.bin结尾的文件，避免处理目录
        val validMessagePaths = messagePaths.filter { path ->
            path.lowercase().endsWith(".bin")
        }
        
        if (validMessagePaths.size != messagePaths.size) {
            Log.d(TAG, "过滤后只处理 ${validMessagePaths.size} 个.bin文件，排除了 ${messagePaths.size - validMessagePaths.size} 个非消息文件")
        }
        
        for (messagePath in validMessagePaths) {
            try {
                Log.d(TAG, "处理消息: $messagePath")
                
                // 创建临时文件
                val tempFile = CosFileHelper.createTempFile(context, "cos_msg_", ".bin")
                Log.d(TAG, "创建临时文件: ${tempFile.absolutePath}")
                
                // 下载消息
                Log.d(TAG, "开始下载消息: $messagePath -> ${tempFile.absolutePath}")
                val success = client.downloadFile(messagePath, tempFile.absolutePath)
                
                if (success) {
                    Log.d(TAG, "消息下载成功: $messagePath, 文件大小: ${tempFile.length()} 字节")
                    // 处理下载的消息
                    processDownloadedMessage(senderId, senderRecipientId, tempFile, messagePath, camSession)
                } else {
                    Log.w(TAG, "下载消息失败: $messagePath")
                }
                
                // 安全删除临时文件
                Log.d(TAG, "删除临时文件: ${tempFile.absolutePath}")
                CosFileHelper.safeDelete(tempFile)
            } catch (e: Exception) {
                Log.e(TAG, "处理消息失败: $messagePath", e)
            }
        }
    }

    /**
     * 处理下载的消息文件
     */
    private suspend fun processDownloadedMessage(
        senderId: String,
        senderRecipientId: RecipientId,
        messageFile: File,
        messagePath: String,
        camSession: CamSession
    ) {
        try {
            // 读取加密数据
            val encryptedData = messageFile.readBytes()
            
            // 解密数据
            val decryptedData = cosMessageEncryption.decryptMessage(encryptedData, camSession.sessionKey)
            
            // 反序列化消息
            val cosMessage = cosMessageSerializer.deserializeMessage(decryptedData)
            
            // 处理消息
            val success = messageProcessor?.processIncomingMessage(senderRecipientId, cosMessage) ?: false
            
            if (success) {
                // 标记消息为已处理
                val fileName = messagePath.substringAfterLast('/')
                cosMessageStorage?.markMessageAsProcessed(senderRecipientId, fileName)
                
                // 发送新消息事件
                _newMessageEvents.emit(NewMessageEvent(senderRecipientId, cosMessage))
                
                Log.i(TAG, "成功处理来自 $senderId 的消息")
            } else {
                Log.w(TAG, "处理来自 $senderId 的消息失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理下载的消息失败", e)
            throw e
        }
    }

    /**
     * 创建临时COS客户端
     */
    private fun createTemporaryClient(camSession: CamSession): CosClient {
        try {
            // 获取COS配置
            val config = CosConfigStorage.getConfig(context)
                ?: throw IllegalStateException("未找到COS配置")
            
            // 确定使用的存储桶和区域
            // 关键修复：优先使用CAM会话中的bucketName和region
            val bucketName = if (camSession.bucketName.isNotEmpty()) {
                camSession.bucketName
            } else {
                config.bucketName
            }
            
            val region = if (camSession.region.isNotEmpty()) {
                camSession.region
            } else {
                config.region
            }
            
            // 确定使用的提供商类型
            val provider = if (camSession.provider.isNotEmpty()) {
                try {
                    CosConfig.Provider.valueOf(camSession.provider)
                } catch (e: Exception) {
                    Log.w(TAG, "无法解析提供商类型: ${camSession.provider}，使用默认配置: ${config.provider}")
                    config.provider
                }
            } else {
                config.provider
            }
            
            Log.d(TAG, "COS配置: 提供商=$provider, " +
                      "本地区域=${config.region}, 本地存储桶=${config.bucketName}, " +
                      "使用区域=$region, 使用存储桶=$bucketName")
            
            // 打印CAM令牌信息
            val now = System.currentTimeMillis()
            val tokenExpiresIn = (camSession.expireTime - now) / 1000
            Log.d(TAG, "CAM会话详情: 路径=${camSession.path}, 过期时间=${camSession.expireTime}, " +
                      "令牌ID=${camSession.camToken.secretId.take(8)}..., " + 
                      "令牌过期时间=${camSession.camToken.expiredTime}, " +
                      "存储桶=${bucketName}, 区域=${region}")
            Log.d(TAG, "CAM令牌: 过期时间=${camSession.expireTime}, " +
                      "还有 ${tokenExpiresIn} 秒过期, " +
                      "SecretId=${camSession.camToken.secretId.take(8)}...")
            
            // 使用临时客户端工厂创建客户端
            val client = TemporaryClientFactory.createClient(
                context,
                camSession.camToken,
                provider,
                bucketName,
                region
            )
            Log.d(TAG, "临时客户端创建成功: ${client.javaClass.simpleName}")
            
            return client
        } catch (e: Exception) {
            Log.e(TAG, "创建临时客户端失败", e)
            throw e
        }
    }

    /**
     * 清理不需要的轮询任务
     */
    private fun cleanupUnneededPollingJobs(validRecipientIds: Set<String>) {
        val jobsToRemove = pollingJobs.keys.filter { it !in validRecipientIds }
        
        jobsToRemove.forEach { recipientId ->
            pollingJobs.remove(recipientId)?.cancel()
            Log.d(TAG, "停止对发送者 $recipientId 的轮询（CAM已过期）")
        }
    }
}

/**
 * 新消息事件
 */
data class NewMessageEvent(
    val senderId: RecipientId,
    val message: CosMessage
)

/**
 * CAM会话
 */
data class CamSession(
    val camToken: CamToken,
    val sessionKey: ByteArray,
    val expireTime: Long,
    val path: String,
    val bucketName: String = "",  // 添加存储桶名称，默认空字符串保持向后兼容
    val region: String = "",      // 添加区域信息，默认空字符串保持向后兼容
    val provider: String = ""     // 添加提供商类型，默认空字符串保持向后兼容
): Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CamSession

        if (camToken != other.camToken) return false
        if (!sessionKey.contentEquals(other.sessionKey)) return false
        if (expireTime != other.expireTime) return false
        if (path != other.path) return false
        if (bucketName != other.bucketName) return false
        if (region != other.region) return false
        if (provider != other.provider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = camToken.hashCode()
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + expireTime.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + bucketName.hashCode()
        result = 31 * result + region.hashCode()
        result = 31 * result + provider.hashCode()
        return result
    }
}

/**
 * 临时客户端工厂
 * 用于创建使用CAM令牌的临时COS客户端
 */
object TemporaryClientFactory {
    private const val TAG = "TemporaryClientFactory"
    
    // 客户端缓存，避免频繁创建
    private val clientCache = ConcurrentHashMap<String, Pair<CosClient, Long>>()
    
    // 缓存过期时间（毫秒）
    private const val CACHE_EXPIRY = 60 * 1000L // 1分钟
    
    /**
     * 创建临时客户端
     * @param context 应用上下文
     * @param camToken CAM令牌
     * @param provider 服务提供商类型
     * @return 临时COS客户端
     */
    fun createClient(context: Context, camToken: CamToken, provider: CosConfig.Provider): CosClient {
        try {
            // 生成缓存键
            val cacheKey = "${camToken.secretId}:${camToken.token}"
            
            // 检查缓存
            val cachedClient = clientCache[cacheKey]
            if (cachedClient != null && System.currentTimeMillis() - cachedClient.second < CACHE_EXPIRY) {
                Log.d(TAG, "使用缓存的临时客户端: $cacheKey")
                return cachedClient.first
            }
            
            Log.d(TAG, "创建新的临时客户端，提供商: $provider")
            
            // 创建新客户端
            val client = when (provider) {
                CosConfig.Provider.AWS -> {
                    Log.d(TAG, "创建AWS S3临时客户端")
                    createAwsClient(context, camToken)
                }
                CosConfig.Provider.TENCENT -> {
                    Log.d(TAG, "创建腾讯云COS临时客户端")
                    createTencentClient(context, camToken)
                }
            }
            
            // 更新缓存
            clientCache[cacheKey] = Pair(client, System.currentTimeMillis())
            Log.d(TAG, "临时客户端已创建并缓存: $cacheKey")
            
            return client
        } catch (e: Exception) {
            Log.e(TAG, "创建临时客户端失败", e)
            throw e
        }
    }

    /**
     * 创建临时客户端
     * @param context 应用上下文
     * @param camToken CAM令牌
     * @param provider 服务提供商类型
     * @param bucketName 存储桶名称
     * @param region 区域
     * @return 临时COS客户端
     */
    fun createClient(
        context: Context,
        camToken: CamToken,
        provider: CosConfig.Provider,
        bucketName: String,
        region: String
    ): CosClient {
        try {
            // 生成缓存键
            val cacheKey = "${camToken.secretId}:${camToken.token}:$bucketName:$region"
            
            // 检查缓存
            val cachedClient = clientCache[cacheKey]
            if (cachedClient != null && System.currentTimeMillis() - cachedClient.second < CACHE_EXPIRY) {
                Log.d(TAG, "使用缓存的临时客户端: $cacheKey")
                return cachedClient.first
            }
            
            Log.d(TAG, "创建新的临时客户端，提供商: $provider, 存储桶: $bucketName, 区域: $region")
            
            // 创建新客户端
            val client = when (provider) {
                CosConfig.Provider.AWS -> {
                    Log.d(TAG, "创建AWS S3临时客户端，存储桶: $bucketName, 区域: $region")
                    createAwsClient(context, camToken, bucketName, region)
                }
                CosConfig.Provider.TENCENT -> {
                    Log.d(TAG, "创建腾讯云COS临时客户端，存储桶: $bucketName, 区域: $region")
                    createTencentClient(context, camToken, bucketName, region)
                }
            }
            
            // 更新缓存
            clientCache[cacheKey] = Pair(client, System.currentTimeMillis())
            Log.d(TAG, "临时客户端已创建并缓存: $cacheKey")
            
            return client
        } catch (e: Exception) {
            Log.e(TAG, "创建临时客户端失败", e)
            throw e
        }
    }
    
    /**
     * 创建AWS S3临时客户端
     */
    private fun createAwsClient(context: Context, camToken: CamToken): CosClient {
        // 获取基本配置
        val baseConfig = CosConfigStorage.getConfig(context)
            ?: throw IllegalStateException("未找到COS配置")
        
        // 创建临时配置
        val tempConfig = CosConfig(
            provider = CosConfig.Provider.AWS,
            secretId = camToken.secretId,
            secretKey = camToken.secretKey,
            region = baseConfig.region,
            bucketName = baseConfig.bucketName
        )
        
        // 创建临时客户端
        val awsClient = AwsS3Client(tempConfig)
        
        // 创建适配器，将org.thoughtcrime.securesms.cos.CosClient转换为org.thoughtcrime.securesms.cos.client.CosClient
        return object : CosClient {
            override suspend fun generateSessionToken(durationSeconds: Int, path: String): CamToken {
                val token = awsClient.generateTemporaryAccessToken(path, durationSeconds / 60)
                return CamToken(
                    secretId = token.secretId,
                    secretKey = token.secretKey,
                    token = token.sessionToken ?: "",
                    expiredTime = token.expiresAt
                )
            }
            
            override suspend fun uploadFile(localPath: String, remotePath: String): String {
                val success = awsClient.uploadFile(File(localPath), remotePath)
                return if (success) remotePath else ""
            }
            
            override suspend fun downloadFile(remotePath: String, localPath: String): Boolean {
                return awsClient.downloadFile(remotePath, File(localPath))
            }
            
            override suspend fun listDirectory(remotePath: String): List<org.thoughtcrime.securesms.cos.client.CosFileInfo> {
                try {
                    Log.d(TAG, "AWS客户端: 列出目录内容: $remotePath")
                    val files = awsClient.listFiles(remotePath)
                    Log.d(TAG, "AWS客户端: 列出目录内容成功，找到 ${files.size} 个文件")
                    
                    // 打印文件详情
                    if (files.isNotEmpty()) {
                        files.forEach { fileInfo ->
                            Log.d(TAG, "AWS客户端: 文件: key=${fileInfo.key}, size=${fileInfo.size}, lastModified=${fileInfo.lastModified}")
                        }
                    }
                    
                    return files.map { fileInfo ->
                        org.thoughtcrime.securesms.cos.client.CosFileInfo(
                            name = fileInfo.key.substringAfterLast('/'),
                            path = fileInfo.key,
                            size = fileInfo.size,
                            lastModified = fileInfo.lastModified,
                            isDirectory = fileInfo.key.endsWith("/")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AWS客户端: 列出目录内容失败: $remotePath", e)
                    throw e
                }
            }
            
            override suspend fun createDirectory(remotePath: String): Boolean {
                return awsClient.createDirectory(remotePath)
            }
            
            override suspend fun fileExists(remotePath: String): Boolean {
                // 实现文件存在检查逻辑
                return try {
                    val files = awsClient.listFiles(remotePath.substringBeforeLast("/"))
                    files.any { it.key == remotePath }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /**
     * 创建AWS S3临时客户端
     */
    private fun createAwsClient(context: Context, camToken: CamToken, bucketName: String, region: String): CosClient {
        // 创建临时配置
        val tempConfig = CosConfig(
            provider = CosConfig.Provider.AWS,
            secretId = camToken.secretId,
            secretKey = camToken.secretKey,
            region = region,
            bucketName = bucketName
        )
        
        // 创建临时客户端
        val awsClient = AwsS3Client(tempConfig)
        
        // 创建适配器，将org.thoughtcrime.securesms.cos.CosClient转换为org.thoughtcrime.securesms.cos.client.CosClient
        return object : CosClient {
            override suspend fun generateSessionToken(durationSeconds: Int, path: String): CamToken {
                val token = awsClient.generateTemporaryAccessToken(path, durationSeconds / 60)
                return CamToken(
                    secretId = token.secretId,
                    secretKey = token.secretKey,
                    token = token.sessionToken ?: "",
                    expiredTime = token.expiresAt
                )
            }
            
            override suspend fun uploadFile(localPath: String, remotePath: String): String {
                val success = awsClient.uploadFile(File(localPath), remotePath)
                return if (success) remotePath else ""
            }
            
            override suspend fun downloadFile(remotePath: String, localPath: String): Boolean {
                return awsClient.downloadFile(remotePath, File(localPath))
            }
            
            override suspend fun listDirectory(remotePath: String): List<org.thoughtcrime.securesms.cos.client.CosFileInfo> {
                try {
                    Log.d(TAG, "AWS客户端: 列出目录内容: $remotePath")
                    val files = awsClient.listFiles(remotePath)
                    Log.d(TAG, "AWS客户端: 列出目录内容成功，找到 ${files.size} 个文件")
                    
                    // 打印文件详情
                    if (files.isNotEmpty()) {
                        files.forEach { fileInfo ->
                            Log.d(TAG, "AWS客户端: 文件: key=${fileInfo.key}, size=${fileInfo.size}, lastModified=${fileInfo.lastModified}")
                        }
                    }
                    
                    return files.map { fileInfo ->
                        org.thoughtcrime.securesms.cos.client.CosFileInfo(
                            name = fileInfo.key.substringAfterLast('/'),
                            path = fileInfo.key,
                            size = fileInfo.size,
                            lastModified = fileInfo.lastModified,
                            isDirectory = fileInfo.key.endsWith("/")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AWS客户端: 列出目录内容失败: $remotePath", e)
                    throw e
                }
            }
            
            override suspend fun createDirectory(remotePath: String): Boolean {
                return awsClient.createDirectory(remotePath)
            }
            
            override suspend fun fileExists(remotePath: String): Boolean {
                // 实现文件存在检查逻辑
                return try {
                    val files = awsClient.listFiles(remotePath.substringBeforeLast("/"))
                    files.any { it.key == remotePath }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
    
    /**
     * 创建腾讯云COS临时客户端
     */
    private fun createTencentClient(context: Context, camToken: CamToken): CosClient {
        // 获取基本配置
        val baseConfig = CosConfigStorage.getConfig(context)
            ?: throw IllegalStateException("未找到COS配置")
        
        // 创建临时配置
        val tempConfig = CosConfig(
            provider = CosConfig.Provider.TENCENT,
            secretId = camToken.secretId,
            secretKey = camToken.secretKey,
            region = baseConfig.region,
            bucketName = baseConfig.bucketName,
            sessionToken = camToken.token
        )
        
        // 捕获配置值，使其在匿名类中可用
        val bucketNameVal = tempConfig.bucketName
        val regionVal = tempConfig.region
        
        // 创建临时客户端
        Log.d(TAG, "创建腾讯云临时客户端 - 使用存储桶: ${tempConfig.bucketName}, 区域: ${tempConfig.region}, 令牌ID: ${camToken.secretId.take(8)}...")
        val tencentClient = TencentCosClient(tempConfig)
        
        // 创建适配器，将org.thoughtcrime.securesms.cos.CosClient转换为org.thoughtcrime.securesms.cos.client.CosClient
        return object : CosClient {
            override suspend fun generateSessionToken(durationSeconds: Int, path: String): CamToken {
                val token = tencentClient.generateTemporaryAccessToken(path, durationSeconds / 60)
                return CamToken(
                    secretId = token.secretId,
                    secretKey = token.secretKey,
                    token = token.sessionToken ?: "",
                    expiredTime = token.expiresAt
                )
            }
            
            override suspend fun uploadFile(localPath: String, remotePath: String): String {
                val success = tencentClient.uploadFile(File(localPath), remotePath)
                return if (success) remotePath else ""
            }
            
            override suspend fun downloadFile(remotePath: String, localPath: String): Boolean {
                return tencentClient.downloadFile(remotePath, File(localPath))
            }
            
            override suspend fun listDirectory(remotePath: String): List<org.thoughtcrime.securesms.cos.client.CosFileInfo> {
                try {
                    // 确保日志记录完整信息以便排错
                    Log.d(TAG, "腾讯云客户端(指定存储桶): 列出目录内容: $remotePath")
                    Log.d(TAG, "腾讯云客户端配置: 存储桶=$bucketNameVal, 区域=$regionVal, 临时令牌=${camToken.token.take(20)}...")
                    
                    val files = tencentClient.listFiles(remotePath)
                    Log.d(TAG, "腾讯云客户端: 列出目录内容成功，找到 ${files.size} 个文件")
                    
                    // 打印文件详情
                    if (files.isNotEmpty()) {
                        files.forEach { fileInfo ->
                            Log.d(TAG, "腾讯云客户端: 文件: key=${fileInfo.key}, size=${fileInfo.size}, lastModified=${fileInfo.lastModified}")
                        }
                    }
                    
                    return files.map { fileInfo ->
                        org.thoughtcrime.securesms.cos.client.CosFileInfo(
                            name = fileInfo.key.substringAfterLast('/'),
                            path = fileInfo.key,
                            size = fileInfo.size,
                            lastModified = fileInfo.lastModified,
                            isDirectory = fileInfo.key.endsWith("/")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "腾讯云客户端: 列出目录内容失败: $remotePath，存储桶: $bucketNameVal, 区域: $regionVal", e)
                    throw e
                }
            }
            
            override suspend fun createDirectory(remotePath: String): Boolean {
                return tencentClient.createDirectory(remotePath)
            }
            
            override suspend fun fileExists(remotePath: String): Boolean {
                // 实现文件存在检查逻辑
                return try {
                    val files = tencentClient.listFiles(remotePath.substringBeforeLast("/"))
                    files.any { it.key == remotePath }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /**
     * 创建腾讯云COS临时客户端
     */
    private fun createTencentClient(context: Context, camToken: CamToken, bucketName: String, region: String): CosClient {
        // 创建临时配置
        val tempConfig = CosConfig(
            provider = CosConfig.Provider.TENCENT,
            secretId = camToken.secretId,
            secretKey = camToken.secretKey,
            region = region,
            bucketName = bucketName,
            sessionToken = camToken.token
        )
        
        // 创建临时客户端
        Log.d(TAG, "创建腾讯云临时客户端 - 使用存储桶: $bucketName, 区域: $region, 令牌ID: ${camToken.secretId.take(8)}...")
        val tencentClient = TencentCosClient(tempConfig)
        
        // 创建适配器，将org.thoughtcrime.securesms.cos.CosClient转换为org.thoughtcrime.securesms.cos.client.CosClient
        return object : CosClient {
            override suspend fun generateSessionToken(durationSeconds: Int, path: String): CamToken {
                val token = tencentClient.generateTemporaryAccessToken(path, durationSeconds / 60)
                return CamToken(
                    secretId = token.secretId,
                    secretKey = token.secretKey,
                    token = token.sessionToken ?: "",
                    expiredTime = token.expiresAt
                )
            }
            
            override suspend fun uploadFile(localPath: String, remotePath: String): String {
                val success = tencentClient.uploadFile(File(localPath), remotePath)
                return if (success) remotePath else ""
            }
            
            override suspend fun downloadFile(remotePath: String, localPath: String): Boolean {
                return tencentClient.downloadFile(remotePath, File(localPath))
            }
            
            override suspend fun listDirectory(remotePath: String): List<org.thoughtcrime.securesms.cos.client.CosFileInfo> {
                try {
                    // 确保日志记录完整信息以便排错
                    Log.d(TAG, "腾讯云客户端(指定存储桶): 列出目录内容: $remotePath")
                    Log.d(TAG, "腾讯云客户端配置: 存储桶=$bucketName, 区域=$region, 临时令牌=${camToken.token.take(20)}...")
                    
                    val files = tencentClient.listFiles(remotePath)
                    Log.d(TAG, "腾讯云客户端: 列出目录内容成功，找到 ${files.size} 个文件")
                    
                    // 打印文件详情
                    if (files.isNotEmpty()) {
                        files.forEach { fileInfo ->
                            Log.d(TAG, "腾讯云客户端: 文件: key=${fileInfo.key}, size=${fileInfo.size}, lastModified=${fileInfo.lastModified}")
                        }
                    }
                    
                    return files.map { fileInfo ->
                        org.thoughtcrime.securesms.cos.client.CosFileInfo(
                            name = fileInfo.key.substringAfterLast('/'),
                            path = fileInfo.key,
                            size = fileInfo.size,
                            lastModified = fileInfo.lastModified,
                            isDirectory = fileInfo.key.endsWith("/")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "腾讯云客户端: 列出目录内容失败: $remotePath，存储桶: $bucketName, 区域: $region", e)
                    throw e
                }
            }
            
            override suspend fun createDirectory(remotePath: String): Boolean {
                return tencentClient.createDirectory(remotePath)
            }
            
            override suspend fun fileExists(remotePath: String): Boolean {
                // 实现文件存在检查逻辑
                return try {
                    val files = tencentClient.listFiles(remotePath.substringBeforeLast("/"))
                    files.any { it.key == remotePath }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
    
    /**
     * 清理过期的客户端缓存
     */
    fun cleanupExpiredClients() {
        val now = System.currentTimeMillis()
        val expiredKeys = clientCache.entries
            .filter { now - it.value.second > CACHE_EXPIRY }
            .map { it.key }
        
        expiredKeys.forEach { clientCache.remove(it) }
        
        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "清理了 ${expiredKeys.size} 个过期的临时客户端")
        }
    }
} 