package org.thoughtcrime.securesms.tap.group

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
        return copy(
            totalMembers = newTotalMembers,
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
               when (status) {
                   GroupV2Status.NATIVE -> agreedMembers.isEmpty() && proposerAci == null
                   GroupV2Status.PROPOSING -> agreedMembers.isNotEmpty() && proposerAci != null
                   GroupV2Status.FULL_V2_ACTIVE -> isFullyAgreed() && proposerAci != null
               }
    }
}

