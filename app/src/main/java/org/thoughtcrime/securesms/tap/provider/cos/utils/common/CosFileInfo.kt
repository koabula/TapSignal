package org.thoughtcrime.securesms.tap.provider.cos.utils.common

/**
 * COS文件信息
 */
data class CosFileInfo(
    val name: String,
    val size: Long,
    val lastModified: Long
) 