package org.thoughtcrime.securesms.cos

import android.content.Context
import org.signal.core.util.logging.Log

object CosClientFactory {
    private val TAG = Log.tag(CosClientFactory::class.java)

    /**
     * 根据配置返回对应实现。
     */
    fun createClient(context: Context): CosClient? {
        val config = CosConfigStorage.getConfig(context) ?: return null
        return when (config.provider) {
            CosConfig.Provider.AWS -> AwsS3Client(config)
            CosConfig.Provider.TENCENT -> TencentCosClient(config)
        }
    }
} 