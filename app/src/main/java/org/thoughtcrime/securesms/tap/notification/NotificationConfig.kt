package org.thoughtcrime.securesms.tap.notification

/**
 * 本地推送服务配置
 *
 * 存储当前用户的推送服务配置信息，用于建立连接和生成Token交换消息
 */
data class NotificationConfig(
    val provider: String,
    val webhookUrl: String,
    val notifySecret: String,
    val pushServiceInfo: PushServiceInfo,
    val deployedAt: Long,
    val version: String = "1.0",
    val websocketManagementEndpoint: String? = null
) {
    /**
     * 验证配置是否有效
     */
    fun validate(): Boolean {
        return provider.isNotEmpty() && 
               webhookUrl.isNotEmpty() && 
               notifySecret.isNotEmpty() && 
               pushServiceInfo.validate()
    }
}
