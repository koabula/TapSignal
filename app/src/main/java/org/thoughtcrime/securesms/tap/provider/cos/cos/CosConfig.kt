package org.thoughtcrime.securesms.tap.provider.cos.cos

/**
 * 保存用户的 COS/S3 配置信息。
 * 目前通过 getCosConfig() 函数硬编码返回, 后续将由 UI 写入。
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