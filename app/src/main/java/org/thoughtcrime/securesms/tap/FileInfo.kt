package org.thoughtcrime.securesms.tap

import android.util.Log
import org.thoughtcrime.securesms.tap.TransportMessageType

/**
 * 文件信息数据结构
 * 
 * 用于在传输层抽象文件操作，包含文件的基本信息。
 * 不同的Provider可以根据自己的存储结构实现这个接口。
 */
data class FileInfo(
    /** 文件名称 */
    val name: String,
    
    /** 文件完整路径 */
    val path: String,
    
    /** 文件大小（字节） */
    val size: Long,
    
    /** 最后修改时间（毫秒时间戳） */
    val lastModified: Long,
    
    /** 文件ETag或版本标识（用于变更检测，可选） */
    val etag: String? = null,
    
    /** 文件MIME类型（可选） */
    val mimeType: String? = null,
    
    /** 文件URL（如果支持直接访问，可选） */
    val url: String? = null,
    
    /** 文件元数据（Provider特定的额外信息） */
    val metadata: Map<String, Any> = emptyMap()
) {
    
    /**
     * 检查文件信息是否有效
     */
    fun isValid(): Boolean {
        return name.isNotBlank() && 
               path.isNotBlank() && 
               size >= 0 && 
               lastModified > 0
    }
    
    /**
     * 获取文件扩展名
     */
    fun getExtension(): String {
        val lastDotIndex = name.lastIndexOf('.')
        return if (lastDotIndex >= 0 && lastDotIndex < name.length - 1) {
            name.substring(lastDotIndex + 1).lowercase()
        } else {
            ""
        }
    }
    
    /**
     * 基于路径判断是否为消息文件
     * 用于轮询服务区分消息文件和附件文件
     */
    fun isInMessagesDirectory(): Boolean {
        return path.contains("/messages/")
    }
    
    /**
     * 判断文件是否为消息文件
     * 
     * 基于文件内容结构判断，而非依赖文件名
     * TaP消息文件具有特定的二进制头部格式
     */
    fun isMessageFile(): Boolean {
        return try {
            // 基本检查：文件大小和名称
            if (size <= 20) { // TaP消息最小头部大小
                return false
            }
            
            // 基于命名约定的初步过滤
            val nameCheck = when {
                name.endsWith(".tmp") -> false
                name.endsWith(".partial") -> false
                name.startsWith("__readonly_test") -> false
                name.startsWith(".") -> false
                name.contains("test") && name.contains("tmp") -> false
                name.matches(Regex("msg_\\d+_\\d+\\.(tap|msg)")) -> true // 标准TaP消息格式
                name.matches(Regex("\\d{13}_[a-f0-9]{8,}\\.(tap|msg)")) -> true // 时间戳+ID格式
                else -> true // 其他文件需要内容检查
            }
            
            if (!nameCheck) {
                return false
            }
            
            // 基于修改时间的过滤
            val ageMs = System.currentTimeMillis() - lastModified
            if (ageMs > 7 * 24 * 60 * 60 * 1000L) { // 超过7天的文件
                return false
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e("FileInfo", "判断消息文件时出错: $name", e)
            false
        }
    }
    
    /**
     * 验证文件是否为有效的TaP消息文件（通过内容检查）
     * 
     * 使用统一的二进制格式验证，通过尝试完整反序列化来确保格式正确性
     */
    fun validateMessageFileContent(data: ByteArray): Boolean {
        return try {
            // 使用统一的二进制反序列化来验证格式
            TransportMessage.deserializeFromBinary(data)
            
            Log.d("FileInfo", "TaP消息文件验证通过: $name")
            true
            
        } catch (e: TransportException) {
            Log.d("FileInfo", "TaP消息文件格式无效: $name - ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("FileInfo", "验证TaP消息文件内容失败: $name", e)
            false
        }
    }
    

    
    /**
     * 从文件名提取消息ID
     */
    fun extractMessageId(): String? {
        if (!isMessageFile()) return null
        
        val underscoreIndex = name.lastIndexOf('_')
        val dotIndex = name.lastIndexOf('.')
        
        return if (underscoreIndex >= 0 && dotIndex > underscoreIndex) {
            name.substring(0, underscoreIndex)
        } else {
            null
        }
    }
    
    /**
     * 从文件名提取时间戳
     */
    fun extractTimestamp(): Long? {
        if (!isMessageFile()) return null
        
        val underscoreIndex = name.lastIndexOf('_')
        val dotIndex = name.lastIndexOf('.')
        
        return if (underscoreIndex >= 0 && dotIndex > underscoreIndex) {
            try {
                name.substring(underscoreIndex + 1, dotIndex).toLong()
            } catch (e: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * 获取相对路径（去除根路径部分）
     */
    fun getRelativePath(rootPath: String): String {
        return if (path.startsWith(rootPath)) {
            path.removePrefix(rootPath).removePrefix("/")
        } else {
            path
        }
    }
    
    /**
     * 转换为Map格式用于序列化
     */
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "name" to name,
            "path" to path,
            "size" to size,
            "lastModified" to lastModified
        )
        
        etag?.let { map["etag"] = it }
        mimeType?.let { map["mimeType"] = it }
        url?.let { map["url"] = it }
        
        if (metadata.isNotEmpty()) {
            map["metadata"] = metadata
        }
        
        return map
    }
    
    companion object {
        /**
         * 从Map数据创建FileInfo实例
         */
        fun fromMap(data: Map<String, Any>): FileInfo? {
            return try {
                val name = data["name"] as? String ?: return null
                val path = data["path"] as? String ?: return null
                val size = (data["size"] as? Number)?.toLong() ?: return null
                val lastModified = (data["lastModified"] as? Number)?.toLong() ?: return null
                val etag = data["etag"] as? String
                val mimeType = data["mimeType"] as? String
                val url = data["url"] as? String
                val metadata = data["metadata"] as? Map<String, Any> ?: emptyMap()
                
                FileInfo(
                    name = name,
                    path = path,
                    size = size,
                    lastModified = lastModified,
                    etag = etag,
                    mimeType = mimeType,
                    url = url,
                    metadata = metadata
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * 创建消息文件信息
         */
        fun createMessageFile(
            messageId: String,
            timestamp: Long,
            path: String,
            size: Long,
            etag: String? = null
        ): FileInfo {
            val fileName = "${messageId}_${timestamp}.dat"
            val fullPath = if (path.endsWith("/")) "${path}${fileName}" else "${path}/${fileName}"
            
            return FileInfo(
                name = fileName,
                path = fullPath,
                size = size,
                lastModified = timestamp,
                etag = etag,
                mimeType = "application/octet-stream"
            )
        }
        
        /**
         * 按时间排序文件列表
         */
        fun sortByTime(files: List<FileInfo>, ascending: Boolean = true): List<FileInfo> {
            return if (ascending) {
                files.sortedBy { it.lastModified }
            } else {
                files.sortedByDescending { it.lastModified }
            }
        }
        
        /**
         * 过滤消息文件
         */
        fun filterMessageFiles(files: List<FileInfo>): List<FileInfo> {
            return files.filter { it.isMessageFile() }
        }
        
        /**
         * 按大小过滤文件
         */
        fun filterBySize(files: List<FileInfo>, minSize: Long = 0, maxSize: Long = Long.MAX_VALUE): List<FileInfo> {
            return files.filter { it.size >= minSize && it.size <= maxSize }
        }
    }
} 