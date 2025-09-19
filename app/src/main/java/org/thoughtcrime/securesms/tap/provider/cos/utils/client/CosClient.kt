package org.thoughtcrime.securesms.tap.provider.cos.utils.client

import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosFileInfo
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosAccessToken
import java.io.File

/**
 * 针对不同云存储提供方的统一接口
 * 提供与COS服务直接交互的底层方法
 */
interface CosClient {
    /** 创建目录(在对象存储中可以为0字节对象). */
    fun createDirectory(directoryPath: String): Boolean

    /** 上传本地文件到远端路径。*/
    fun uploadFile(localFile: File, remotePath: String): Boolean

    /** 下载远端文件到本地。*/
    fun downloadFile(remotePath: String, localFile: File): Boolean

    /** 列举目录中文件。*/
    fun listFiles(directoryPath: String): List<CosFileInfo>

    /** 生成指定目录的临时访问凭证。*/
    fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int = 15): CosAccessToken
    
    /** 删除远端文件 */
    fun deleteFile(remotePath: String): Boolean
    
    /** 检查文件是否存在 */
    fun fileExists(remotePath: String): Boolean
} 