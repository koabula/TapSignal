package org.thoughtcrime.securesms.tap.notification

/**
 * 联系人推送服务配置
 *
 * 存储联系人的推送服务配置信息，用于向联系人发送推送通知
 */
data class ContactNotificationConfig(
    val contactId: String,
    val platform: String,
    val webhookUrl: String,
    val notifySecret: String,
    val userId: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val verified: Boolean = false,
    val gatewayRegion: String? = null,
    val gatewayProvider: String? = null,
    val offlineBucket: String? = null,
    val presignDelegation: Boolean = false,
    val gatewayMetadata: Map<String, Any> = emptyMap(),
    @Deprecated("使用 NotificationConfig.websocketManagementEndpoint 替代")
    val websocketManagementEndpoint: String? = null
) {
    /**
     * 验证配置是否有效
     */
    fun validate(): Boolean {
        return contactId.isNotEmpty() && 
               webhookUrl.isNotEmpty() && 
               notifySecret.isNotEmpty()
    }
}
