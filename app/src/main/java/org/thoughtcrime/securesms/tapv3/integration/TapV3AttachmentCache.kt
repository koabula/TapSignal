package org.thoughtcrime.securesms.tapv3.integration

import org.signal.core.util.logging.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Tap v3 附件预下载缓存
 * 
 * 当通过 UnifiedPush 接收到带有 IPFS 附件引用的消息时：
 * 1. TapV3ReceiveIntegrator 从 IPFS 下载附件数据
 * 2. 将附件数据存入此缓存（key = IPFS CID）
 * 3. Signal 解密消息，发现 AttachmentPointer (cdnNumber=888)
 * 4. AttachmentDownloadJob 被触发
 * 5. TapV3AttachmentDownloadInterceptor 从缓存中获取数据
 * 6. 保存到 Signal 数据库，显示在界面上
 */
object TapV3AttachmentCache {
    
    private val TAG = Log.tag(TapV3AttachmentCache::class.java)
    
    private val cache = ConcurrentHashMap<String, ByteArray>()
    
    /**
     * 存储附件数据到缓存
     * @param cid IPFS CID
     * @param data 附件数据
     */
    fun store(cid: String, data: ByteArray) {
        cache[cid] = data
        Log.d(TAG, "Stored attachment in cache: cid=${cid.take(8)}..., size=${data.size}")
    }
    
    /**
     * 从缓存中获取并删除附件数据
     * @param cid IPFS CID
     * @return 附件数据，如果不存在则返回 null
     */
    fun retrieve(cid: String): ByteArray? {
        val data = cache.remove(cid)
        if (data != null) {
            Log.d(TAG, "Retrieved attachment from cache: cid=${cid.take(8)}..., size=${data.size}")
        } else {
            Log.d(TAG, "Attachment not found in cache: cid=${cid.take(8)}...")
        }
        return data
    }
    
    /**
     * 检查缓存中是否存在指定的附件
     * @param cid IPFS CID
     * @return true 如果存在，false 如果不存在
     */
    fun contains(cid: String): Boolean {
        return cache.containsKey(cid)
    }
    
    /**
     * 清除指定的附件缓存（不返回数据）
     * @param cid IPFS CID
     */
    fun clear(cid: String) {
        val removed = cache.remove(cid)
        if (removed != null) {
            Log.d(TAG, "Cleared attachment from cache: cid=${cid.take(8)}...")
        }
    }
    
    /**
     * 清除所有附件缓存
     */
    fun clearAll() {
        val size = cache.size
        cache.clear()
        Log.d(TAG, "Cleared all attachments from cache: count=$size")
    }
    
    /**
     * 获取当前缓存的附件数量
     */
    fun size(): Int {
        return cache.size
    }
}
