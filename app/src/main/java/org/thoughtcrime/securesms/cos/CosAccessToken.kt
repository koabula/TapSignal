package org.thoughtcrime.securesms.cos

/**
 * 表示临时访问令牌(CAM / STS)。
 */
data class CosAccessToken(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expireTime: Long
) {
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean = currentTimeMillis >= expireTime
} 