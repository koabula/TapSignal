package org.thoughtcrime.securesms.tap.provider.cos.utils.common

/**
 * COS配置信息数据结构
 * 保存用户的 COS/S3 配置信息
 */
data class CosConfig(
    val provider: Provider,
    val secretId: String,
    val secretKey: String,
    val region: String,
    val bucketName: String,
    val sessionToken: String? = null
) {
    enum class Provider {
        AWS,
        TENCENT
    }
} 