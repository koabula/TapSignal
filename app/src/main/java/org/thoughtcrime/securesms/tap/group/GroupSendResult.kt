package org.thoughtcrime.securesms.tap.group

/**
 * 群组消息发送结果
 * 
 * 密封类，表示群组消息发送的各种结果状态
 */
sealed class GroupSendResult {
    /**
     * 完全成功
     * 消息成功发送给所有成员
     */
    data class Success(
        val groupId: String,
        val messageId: String,
        val memberCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : GroupSendResult() {
        override fun isSuccess(): Boolean = true
    }
    
    /**
     * 部分成功
     * 消息成功发送给部分成员，但有些成员发送失败
     */
    data class PartialSuccess(
        val groupId: String,
        val messageId: String,
        val successMembers: Set<String>,
        val failedMembers: Map<String, String>, // memberAci -> error reason
        val timestamp: Long = System.currentTimeMillis()
    ) : GroupSendResult() {
        val successCount: Int get() = successMembers.size
        val failureCount: Int get() = failedMembers.size
        val totalCount: Int get() = successCount + failureCount
        
        override fun isSuccess(): Boolean = false
        override fun isPartialSuccess(): Boolean = true
        
        fun getSuccessRate(): Double {
            return if (totalCount > 0) successCount.toDouble() / totalCount else 0.0
        }
    }
    
    /**
     * 完全失败
     * 消息发送给所有成员都失败
     */
    data class Failed(
        val groupId: String,
        val messageId: String,
        val reason: String,
        val memberCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) : GroupSendResult() {
        override fun isSuccess(): Boolean = false
        override fun isFailed(): Boolean = true
    }
    
    /**
     * 是否成功
     */
    open fun isSuccess(): Boolean = false
    
    /**
     * 是否部分成功
     */
    open fun isPartialSuccess(): Boolean = false
    
    /**
     * 是否完全失败
     */
    open fun isFailed(): Boolean = false
}

