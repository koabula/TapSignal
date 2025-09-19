package org.thoughtcrime.securesms.tap.provider.cos.utils.client

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.tencent.TencentCosClient
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsS3Client

object CosClientFactory {
    private val TAG = Log.tag(CosClientFactory::class.java)

    /**
     * 根据配置创建客户端
     */
    fun createClient(config: CosConfig, context: Context? = null): CosClient {
        return when (config.provider) {
            CosConfig.Provider.AWS -> AwsS3Client(config)
            CosConfig.Provider.TENCENT -> TencentCosClient(config, context)
        }
    }

    /**
     * 使用临时凭证创建客户端
     */
    fun createClientWithToken(
        provider: String,
        region: String,
        bucketName: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?
    ): CosClient {
        val cosProvider = when (provider.uppercase()) {
            "AWS" -> CosConfig.Provider.AWS
            "TENCENT" -> CosConfig.Provider.TENCENT
            else -> throw IllegalArgumentException("不支持的提供商: $provider")
        }

        val config = CosConfig(
            provider = cosProvider,
            region = region,
            bucketName = bucketName,
            secretId = accessKeyId,
            secretKey = secretAccessKey,
            sessionToken = sessionToken
        )

        return createClient(config, null)
    }
} 