package org.thoughtcrime.securesms.components.settings.app.cos

import org.thoughtcrime.securesms.cos.CosConfig

/**
 * COS设置页面的状态
 * 注意：现在使用长期CAM凭证，不再需要设置过期时间
 */
data class CosSettingsState(
    val provider: CosConfig.Provider = CosConfig.Provider.AWS,
    val secretId: String = "",
    val secretKey: String = "",
    val region: String = "",
    val bucketName: String = ""
)