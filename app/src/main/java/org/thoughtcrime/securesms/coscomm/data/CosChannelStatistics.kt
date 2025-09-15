package org.thoughtcrime.securesms.coscomm.data

/**
 * COS通道统计信息
 */
data class CosChannelStatistics(
    val messagesSent: Long = 0,
    val messagesReceived: Long = 0,
    val bytesTransferred: Long = 0,
    val lastActivityTime: Long = 0,
    val errorCount: Long = 0,
    val successRate: Double = 0.0
)
