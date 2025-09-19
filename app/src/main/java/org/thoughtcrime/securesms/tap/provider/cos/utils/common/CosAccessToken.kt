package org.thoughtcrime.securesms.tap.provider.cos.utils.common

/**
 * COS临时访问令牌
 */
data class CosAccessToken(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expiration: Long
) 