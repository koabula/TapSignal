package org.thoughtcrime.securesms.tap.group

import org.thoughtcrime.securesms.tap.GatewayConfigData
import org.thoughtcrime.securesms.tap.WebhookConfigData

/**
 * 群组 V2 Mode 状态数据类
 * 
 * 存储群组的 V2 模式状态信息，包括成员同意情况、provider 类型等
 */
data class GroupV2State(
    /** 群组 ID */
    val groupId: String,
    
    /** 当前状态 */
    val status: GroupV2Status,
    
    /** 发起人 ACI（提议者）*/
    val proposerAci: String?,
    
    /** 已同意成员 ACI 集合 */
    val agreedMembers: Set<String>,
    
    /** 全部成员 ACI 集合 */
    val totalMembers: Set<String>,

    /** 群成员的 Gateway 信息（key = member ACI） */
    val memberGatewayInfo: Map<String, GroupMemberGatewayInfo> = emptyMap(),
    
    /** 使用的 provider 类型 */
    val providerType: String,
    
    /** 创建时间 */
    val createdAt: Long,
    
    /** 最后更新时间 */
    val updatedAt: Long,
    
    /** 版本号（用于乐观锁） */
    val version: Long = 0
) {
    /**
     * 检查是否全员同意
     */
    fun isFullyAgreed(): Boolean {
        return agreedMembers.isNotEmpty() && 
               agreedMembers.containsAll(totalMembers) && 
               agreedMembers.size == totalMembers.size
    }
    
    /**
     * 获取未同意成员数量
     */
    fun getPendingMembersCount(): Int {
        return totalMembers.size - agreedMembers.size
    }
    
    /**
     * 获取未同意成员列表
     */
    fun getPendingMembers(): Set<String> {
        return totalMembers - agreedMembers
    }
    
    /**
     * 添加同意成员
     */
    fun withAgreedMember(memberAci: String): GroupV2State {
        return copy(
            agreedMembers = agreedMembers + memberAci,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 更新状态
     */
    fun withStatus(newStatus: GroupV2Status): GroupV2State {
        return copy(
            status = newStatus,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 更新成员列表（当有新成员加入或离开时）
     */
    fun withTotalMembers(newTotalMembers: Set<String>): GroupV2State {
        val filteredGateways = memberGatewayInfo.filterKeys { it in newTotalMembers }
        return copy(
            totalMembers = newTotalMembers,
            memberGatewayInfo = filteredGateways,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 重置为原生状态（清除所有 v2 信息）
     */
    fun reset(): GroupV2State {
        return copy(
            status = GroupV2Status.NATIVE,
            proposerAci = null,
            agreedMembers = emptySet(),
            memberGatewayInfo = emptyMap(),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 验证状态是否有效
     */
    fun validate(): Boolean {
        return groupId.isNotBlank() &&
               providerType.isNotBlank() &&
               totalMembers.isNotEmpty() &&
               agreedMembers.all { it in totalMembers } &&
               memberGatewayInfo.keys.all { it in totalMembers } &&
               when (status) {
                    GroupV2Status.NATIVE -> agreedMembers.isEmpty() && proposerAci == null
                    GroupV2Status.PROPOSING -> agreedMembers.isNotEmpty() && proposerAci != null
                    GroupV2Status.FULL_V2_ACTIVE -> isFullyAgreed() && proposerAci != null
               }
    }

    fun withMemberGateway(memberAci: String, gatewayInfo: GroupMemberGatewayInfo): GroupV2State {
        if (memberAci.isBlank()) return this
        return copy(
            memberGatewayInfo = memberGatewayInfo.toMutableMap().apply {
                put(memberAci, gatewayInfo)
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withoutMemberGateway(memberAci: String): GroupV2State {
        if (!memberGatewayInfo.containsKey(memberAci)) {
            return this
        }
        return copy(
            memberGatewayInfo = memberGatewayInfo - memberAci,
            updatedAt = System.currentTimeMillis()
        )
    }
}

data class GroupMemberGatewayInfo(
    val memberAci: String,
    val provider: String? = null,
    val endpoint: String? = null,
    val region: String? = null,
    val offlineBucket: String? = null,
    val presignDelegation: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    val webhookUrl: String? = null,
    val notifySecret: String? = null,
    val userId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isDeliverable: Boolean
        get() = !webhookUrl.isNullOrBlank() && !notifySecret.isNullOrBlank() && !userId.isNullOrBlank()

    companion object {
        fun fromConfigs(
            memberAci: String,
            webhookConfig: WebhookConfigData?,
            gatewayConfig: GatewayConfigData?
        ): GroupMemberGatewayInfo? {
            if (memberAci.isBlank() || webhookConfig == null || !webhookConfig.validate()) {
                return null
            }

            return GroupMemberGatewayInfo(
                memberAci = memberAci,
                provider = gatewayConfig?.provider,
                endpoint = gatewayConfig?.endpoint,
                region = gatewayConfig?.region,
                offlineBucket = gatewayConfig?.offlineBucket,
                presignDelegation = gatewayConfig?.presignDelegation ?: false,
                metadata = gatewayConfig?.metadata ?: emptyMap(),
                webhookUrl = webhookConfig.webhookUrl,
                notifySecret = webhookConfig.notifySecret,
                userId = webhookConfig.userId,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

