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
    suspend fun createDirectory(directoryPath: String): Boolean

    /** 上传本地文件到远端路径。*/
    suspend fun uploadFile(localFile: File, remotePath: String): Boolean

    /** 下载远端文件到本地。*/
    suspend fun downloadFile(remotePath: String, localFile: File): Boolean
    
    /**
     * 下载远端文件直接到内存（优化版本，避免临时文件I/O）
     * 
     * @param remotePath 远端文件路径
     * @return 文件内容的字节数组，失败返回null
     */
    suspend fun downloadFileToMemory(remotePath: String): ByteArray?

    /** 
     * 列举目录中文件。
     * 
     * @param directoryPath 目录路径
     * @return 文件信息列表
     */
    suspend fun listFiles(directoryPath: String): List<CosFileInfo>
    
    /**
     * 列举目录中文件（支持增量查询）
     * 
     * @param directoryPath 目录路径
     * @param marker 起始标记，用于分页或增量查询。传入上次查询返回的nextMarker可以获取增量数据
     * @param maxKeys 返回的最大文件数量，默认1000
     * @return 包含文件列表和下一个标记的结果
     */
    suspend fun listFilesWithMarker(
        directoryPath: String, 
        marker: String? = null,
        maxKeys: Int = 1000
    ): CosListResult

    /** 生成指定目录的临时访问凭证。*/
    suspend fun generateTemporaryAccessToken(directoryPath: String, durationMinutes: Int = 15): CosAccessToken
    
    /** 删除远端文件 */
    suspend fun deleteFile(remotePath: String): Boolean
    
    /** 检查文件是否存在 */
    suspend fun fileExists(remotePath: String): Boolean
}

/**
 * COS列举文件结果
 * 
 * @param files 文件列表
 * @param nextMarker 下一页的标记，如果为null表示已经是最后一页
 * @param isTruncated 是否还有更多数据
 */
data class CosListResult(
    val files: List<CosFileInfo>,
    val nextMarker: String? = null,
    val isTruncated: Boolean = false
) 