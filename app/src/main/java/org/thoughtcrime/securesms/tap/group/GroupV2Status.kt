package org.thoughtcrime.securesms.tap.group

/**
 * 群组 V2 Mode 状态枚举
 * 
 * 定义群组在 V2 模式升级过程中的各种状态
 */
enum class GroupV2Status {
    /**
     * 原生群聊状态
     * 群组消息通过 Signal Server 传输，未启用 tap 层传输
     */
    NATIVE,
    
    /**
     * 提议阶段
     * 有成员提议升级到 V2 模式，但尚未全员同意
     * 此阶段消息仍通过 Signal Server 传输
     */
    PROPOSING,
    
    /**
     * 全员激活状态
     * 所有成员都同意并完成 token 交换，消息通过 tap 层传输
     */
    FULL_V2_ACTIVE;
    
    /**
     * 是否为激活状态
     */
    fun isActive(): Boolean = this == FULL_V2_ACTIVE
    
    /**
     * 是否可以发送提议
     */
    fun canPropose(): Boolean = this == NATIVE
    
    /**
     * 是否可以接受提议
     */
    fun canAccept(): Boolean = this == PROPOSING
    
    /**
     * 是否可以激活
     */
    fun canActivate(): Boolean = this == PROPOSING
}

