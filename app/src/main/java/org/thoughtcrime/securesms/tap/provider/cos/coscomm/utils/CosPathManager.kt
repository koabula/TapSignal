package org.thoughtcrime.securesms.tap.provider.cos.coscomm.utils

import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosMessage
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosPathTemplates
import org.signal.core.util.logging.Log
import java.security.SecureRandom
import java.util.*

/**
 * COS文件路径管理工具类
 * 负责生成和管理COS中的文件存储路径，确保文件名唯一性和安全性
 */
object CosPathManager {
    private val TAG = "CosPathManager"
    private val secureRandom = SecureRandom()
    
    /**
     * 生成消息文件路径
     * 使用时间戳和随机数生成唯一文件名，不再依赖序列号排序
     * 修复双发问题：让Signal原生Double Ratchet机制处理消息顺序
     */
    fun generateMessageFilePath(
        messageId: String
    ): String {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = generateRandomSuffix()
        val fileName = String.format(
            "%d_%s.json",
            timestamp,
            randomSuffix
        )
        val path = "${CosPathTemplates.MESSAGE_TEMPLATE}/$fileName"
        
        Log.d(TAG, "生成消息文件路径(新格式): $path")
        return path
    }
    
    /**
     * 旧版本兼容方法，将被逐步废弃
     * @deprecated 使用 generateMessageFilePath(messageId: String) 替代
     */
    @Deprecated("使用简化版本的 generateMessageFilePath(messageId: String)")
    fun generateMessageFilePath(
        sequenceNumber: Long,
        messageNumber: Int,
        chainNumber: Int
    ): String {
        Log.w(TAG, "使用已废弃的方法，建议使用简化版本")
        return generateMessageFilePath("seq-$sequenceNumber")
    }
    
    /**
     * 根据COS消息生成文件路径
     */
    fun generateMessageFilePath(message: CosMessage): String {
        return generateMessageFilePath(message.messageId)
    }
    
    /**
     * 生成附件文件路径
     * 格式: /outbox/attachments/{attachment_id}_{random}.bin
     */
    fun generateAttachmentFilePath(attachmentId: String): String {
        val randomSuffix = generateRandomSuffix()
        val fileName = String.format(
            CosPathTemplates.ATTACHMENT_FILE_TEMPLATE,
            attachmentId,
            randomSuffix
        )
        val path = "${CosPathTemplates.ATTACHMENT_TEMPLATE}/$fileName"
        
        Log.d(TAG, "生成附件文件路径: $path")
        return path
    }
    
    /**
     * 生成临时文件路径
     */
    fun generateTempFilePath(prefix: String = "temp"): String {
        val randomName = "${prefix}_${generateRandomSuffix()}_${System.currentTimeMillis()}"
        val path = "${CosPathTemplates.TEMP_UPLOADS}/$randomName"
        
        Log.d(TAG, "生成临时文件路径: $path")
        return path
    }
    
    /**
     * 获取消息索引文件路径
     */
    fun getMessageIndexPath(): String {
        return "${CosPathTemplates.METADATA_TEMPLATE}/${CosPathTemplates.INDEX_FILE_NAME}"
    }
    
    /**
     * 获取子账户Pool状态文件路径
     */
    fun getSubAccountPoolStatePath(): String {
        return CosPathTemplates.SUB_ACCOUNT_POOL_STATE_FILE
    }

    /**
     * 获取子账户Pool备份文件路径
     */
    fun getSubAccountPoolBackupPath(): String {
        return CosPathTemplates.SUB_ACCOUNT_POOL_BACKUP_FILE
    }

    /**
     * @deprecated 使用getSubAccountPoolStatePath()替代
     */
    fun getCamPoolStatePath(): String {
        return getSubAccountPoolStatePath()
    }

    /**
     * @deprecated 使用getSubAccountPoolBackupPath()替代
     */
    fun getCamPoolBackupPath(): String {
        return getSubAccountPoolBackupPath()
    }
    
    /**
     * 解析消息文件名获取信息
     * 修复双发问题：支持新格式 {timestamp}_{random}.json，去除序列号依赖
     * 同时保持向后兼容，支持旧格式解析
     */
    fun parseMessageFileName(fileName: String): MessageFileInfo? {
        return try {
            // 移除.json扩展名
            val nameWithoutExt = fileName.removeSuffix(".json")
            val parts = nameWithoutExt.split("_")
            
            when (parts.size) {
                2 -> {
                    // 检测是新格式还是旧格式
                    val firstPart = parts[0].toLong()
                    val randomSuffix = parts[1]
                    
                    // 如果第一部分是13位时间戳（毫秒），则认为是新格式
                    if (firstPart > 1000000000000L) { // 大于2001年的时间戳
                        // 新格式: {timestamp}_{random} - 无序列号依赖
                        Log.d(TAG, "解析新格式文件名(无序列号): timestamp=$firstPart, random=$randomSuffix")
                        
                        MessageFileInfo(
                            sequenceNumber = 0, // 新格式不使用序列号
                            messageNumber = 0,
                            chainNumber = 0,
                            randomSuffix = randomSuffix,
                            timestamp = firstPart,
                            isNewFormat = true
                        )
                    } else {
                        // 旧格式: {sequenceNumber}_{random} - 保持兼容
                        Log.d(TAG, "解析旧格式文件名: seq=$firstPart, random=$randomSuffix")
                        
                        MessageFileInfo(
                            sequenceNumber = firstPart,
                            messageNumber = firstPart.toInt(),
                            chainNumber = 0,
                            randomSuffix = randomSuffix,
                            timestamp = null,
                            isNewFormat = false
                        )
                    }
                }
                3 -> {
                    // 旧格式: {sequenceNumber}_{timestamp}_{random}
                    val sequenceNumber = parts[0].toLong()
                    val timestamp = parts[1].toLong()
                    val randomSuffix = parts[2]
                    
                    Log.d(TAG, "解析旧格式文件名: seq=$sequenceNumber, timestamp=$timestamp")
                    
                    MessageFileInfo(
                        sequenceNumber = sequenceNumber,
                        messageNumber = sequenceNumber.toInt(),
                        chainNumber = 0,
                        randomSuffix = randomSuffix,
                        timestamp = timestamp,
                        isNewFormat = false
                    )
                }
                4 -> {
                    // 旧格式: {sequenceNumber}_{messageNumber}_{chainNumber}_{random}
                    val sequenceNumber = parts[0].toLong()
                    val messageNumber = parts[1].toInt()
                    val chainNumber = parts[2].toInt()
                    val randomSuffix = parts[3]
                    
                    Log.d(TAG, "解析旧格式文件名: seq=$sequenceNumber, msg=$messageNumber, chain=$chainNumber")
                    
                    MessageFileInfo(
                        sequenceNumber = sequenceNumber,
                        messageNumber = messageNumber,
                        chainNumber = chainNumber,
                        randomSuffix = randomSuffix,
                        timestamp = null,
                        isNewFormat = false
                    )
                }
                else -> {
                    Log.w(TAG, "无效的消息文件名格式: $fileName, parts=${parts.size}")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息文件名失败: $fileName", e)
            null
        }
    }
    
    /**
     * 解析附件文件名获取信息
     */
    fun parseAttachmentFileName(fileName: String): AttachmentFileInfo? {
        return try {
            // 移除.bin扩展名
            val nameWithoutExt = fileName.removeSuffix(".bin")
            val parts = nameWithoutExt.split("_")
            
            if (parts.size < 2) {
                Log.w(TAG, "无效的附件文件名格式: $fileName")
                return null
            }
            
            // 附件ID可能包含连字符，所以需要重新组合
            val randomSuffix = parts.last()
            val attachmentId = parts.dropLast(1).joinToString("_")
            
            AttachmentFileInfo(attachmentId, randomSuffix)
        } catch (e: Exception) {
            Log.e(TAG, "解析附件文件名失败: $fileName", e)
            null
        }
    }
    
    /**
     * 检查路径是否为消息文件路径
     * 支持传统路径格式和v2通道路径格式
     */
    fun isMessageFilePath(path: String): Boolean {
        // 支持传统格式: outbox/messages/xxx.json
        val isTraditionalFormat = path.startsWith(CosPathTemplates.MESSAGE_TEMPLATE) && path.endsWith(".json")

        // 支持v2通道格式: /v2-channels/signal-v2-xxx-xxx/outbox/messages/xxx.json
        val isV2ChannelFormat = path.contains("/outbox/messages/") && path.endsWith(".json")

        return isTraditionalFormat || isV2ChannelFormat
    }
    
    /**
     * 检查路径是否为附件文件路径
     * 支持传统路径格式和v2通道路径格式
     */
    fun isAttachmentFilePath(path: String): Boolean {
        // 支持传统格式: outbox/attachments/xxx.bin
        val isTraditionalFormat = path.startsWith(CosPathTemplates.ATTACHMENT_TEMPLATE) && path.endsWith(".bin")

        // 支持v2通道格式: /v2-channels/signal-v2-xxx-xxx/outbox/attachments/xxx.bin
        val isV2ChannelFormat = path.contains("/outbox/attachments/") && path.endsWith(".bin")

        return isTraditionalFormat || isV2ChannelFormat
    }
    
    /**
     * 检查路径是否为临时文件路径
     */
    fun isTempFilePath(path: String): Boolean {
        return path.startsWith(CosPathTemplates.TEMP_ROOT)
    }
    
    /**
     * 获取文件名（不包含路径）
     */
    fun getFileName(path: String): String {
        return path.substringAfterLast("/")
    }
    
    /**
     * 获取目录路径（不包含文件名）
     */
    fun getDirectoryPath(path: String): String {
        return path.substringBeforeLast("/")
    }
    
    /**
     * 规范化路径（移除多余的斜杠等）
     */
    fun normalizePath(path: String): String {
        return path.replace(Regex("/+"), "/")
            .removePrefix("/")
            .removeSuffix("/")
    }
    
    /**
     * 生成8位随机十六进制后缀
     */
    private fun generateRandomSuffix(): String {
        val bytes = ByteArray(4)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 生成基于时间的排序友好的文件名前缀
     */
    fun generateSortablePrefix(): String {
        val timestamp = System.currentTimeMillis()
        val random = generateRandomSuffix()
        return "${timestamp}_$random"
    }
    
    /**
     * 检查文件路径是否安全（防止路径遍历攻击）
     */
    fun isPathSafe(path: String): Boolean {
        val normalizedPath = normalizePath(path)
        
        // 检查是否包含危险字符
        if (normalizedPath.contains("..") || 
            normalizedPath.contains("~") ||
            normalizedPath.startsWith("/") ||
            normalizedPath.contains("\\")) {
            return false
        }
        
        // 检查是否在允许的目录范围内
        val allowedPrefixes = listOf(
            CosPathTemplates.OUTBOX_ROOT,
            CosPathTemplates.TEMP_ROOT,
            CosPathTemplates.SYSTEM_ROOT
        )
        
        return allowedPrefixes.any { normalizedPath.startsWith(it) }
    }
}

/**
 * 消息文件信息数据类
 * 修复双发问题：添加isNewFormat字段区分新旧格式，新格式不依赖序列号
 */
data class MessageFileInfo(
    val sequenceNumber: Long,
    val messageNumber: Int,
    val chainNumber: Int,
    val randomSuffix: String,
    val timestamp: Long? = null,
    val isNewFormat: Boolean = false // 新格式标识，新格式不使用序列号排序
) {
    /**
     * 重新生成文件名
     * 修复双发问题：根据isNewFormat选择格式，新格式不包含序列号
     */
    fun toFileName(): String {
        return if (isNewFormat && timestamp != null) {
            // 新格式: {timestamp}_{random}.json - 无序列号依赖
            String.format(
                "%d_%s.json",
                timestamp,
                randomSuffix
            )
        } else if (timestamp != null && !isNewFormat) {
            // 中间格式: {sequenceNumber}_{timestamp}_{random}.json
            String.format(
                "%010d_%d_%s.json",
                sequenceNumber,
                timestamp,
                randomSuffix
            )
        } else {
            // 旧格式（向后兼容）
            String.format(
                CosPathTemplates.MESSAGE_FILE_TEMPLATE,
                sequenceNumber,
                messageNumber,
                chainNumber,
                randomSuffix
            )
        }
    }
}

/**
 * 附件文件信息数据类
 */
data class AttachmentFileInfo(
    val attachmentId: String,
    val randomSuffix: String
) {
    /**
     * 重新生成文件名
     */
    fun toFileName(): String {
        return String.format(
            CosPathTemplates.ATTACHMENT_FILE_TEMPLATE,
            attachmentId,
            randomSuffix
        )
    }
}
