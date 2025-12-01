package org.thoughtcrime.securesms.tapv3.ipfs

import org.thoughtcrime.securesms.tapv3.TapV3Result

interface IpfsGateway {
    
    val name: String
    
    val quotaRemaining: Long
    
    suspend fun pin(data: ByteArray): TapV3Result<String>
    
    suspend fun get(cid: String): TapV3Result<ByteArray>
    
    suspend fun unpin(cid: String): TapV3Result<Unit>
    
    suspend fun getUsageStats(): TapV3Result<UsageStats>
    
    data class UsageStats(
        val used: Long,
        val total: Long,
        val remaining: Long
    )
}
