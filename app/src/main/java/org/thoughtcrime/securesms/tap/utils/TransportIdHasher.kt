package org.thoughtcrime.securesms.tap.utils

import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.push.ServiceId
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.charset.StandardCharsets

/**
 * 传输ID哈希工具
 * 
 * 负责将Signal ACI转换为安全的哈希，用作传输路径的标识符。
 * 确保双方使用相同的哈希算法生成一致的路径标识符。
 * 使用64位哈希增强安全性，降低碰撞概率。
 */
object TransportIdHasher {
    
    private val TAG = Log.tag(TransportIdHasher::class.java)
    
    // 哈希配置常量
    private const val HASH_LENGTH_BYTES = 8  // 64位哈希
    private const val HASH_LENGTH_HEX = HASH_LENGTH_BYTES * 2  // 16位十六进制字符串
    private const val HASH_ALGORITHM = "SHA-256"
    private const val FALLBACK_SALT = "transport_aci_fallback"
    
    /**
     * 将ACI转换为安全哈希（用于路径标识符）
     * 
     * @param aci Signal ACI
     * @return 16位十六进制哈希字符串
     */
    fun hashAci(aci: ServiceId.ACI): String {
        return try {
            val aciString = aci.toString()
            val hash = generateSecureHash(aciString)
            
            Log.d(TAG, "ACI哈希生成成功: ${LogSanitizer.sanitize(aciString)} -> ${hash.take(8)}...")
            hash
        } catch (e: Exception) {
            Log.e(TAG, "ACI哈希生成失败，使用安全回退", e)
            generateFallbackHash(aci.toString())
        }
    }
    
    /**
     * 从完整ACI字符串生成哈希
     * 
     * @param aciString ACI字符串
     * @return 16位十六进制哈希字符串
     */
    fun hashAciString(aciString: String): String {
        return try {
            val aci = ServiceId.ACI.parseOrThrow(aciString)
            hashAci(aci)
        } catch (e: Exception) {
            Log.w(TAG, "解析ACI字符串失败，使用直接哈希: ${LogSanitizer.sanitize(aciString)}", e)
            generateSecureHash(aciString)
        }
    }
    
    /**
     * 验证哈希是否有效
     * 
     * @param hash 要验证的哈希字符串
     * @return true如果哈希格式有效，false否则
     */
    fun isValidHash(hash: String): Boolean {
        return hash.matches(Regex("^[0-9a-f]{$HASH_LENGTH_HEX}$"))
    }
    
    /**
     * 生成双方通信的组合哈希
     * 
     * @param myAci 本端ACI
     * @param peerAci 对端ACI  
     * @return 通信对的组合哈希
     */
    fun generateChannelHash(myAci: ServiceId.ACI, peerAci: ServiceId.ACI): String {
        return try {
            val myHash = hashAci(myAci)
            val peerHash = hashAci(peerAci)
            
            // 确保组合哈希的确定性：按字典序排序
            val combined = if (myHash <= peerHash) {
                "$myHash:$peerHash"
            } else {
                "$peerHash:$myHash"
            }
            
            generateSecureHash(combined)
        } catch (e: Exception) {
            Log.e(TAG, "生成通道哈希失败", e)
            generateFallbackHash("${myAci}:${peerAci}")
        }
    }
    
    /**
     * 生成安全哈希
     * 
     * @param input 输入字符串
     * @return 16位十六进制哈希字符串
     */
    private fun generateSecureHash(input: String): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        
        // 取前8字节转为16位十六进制字符串
        return hashBytes.take(HASH_LENGTH_BYTES).joinToString("") { 
            "%02x".format(it) 
        }
    }
    
    /**
     * 生成安全的回退哈希
     * 当主要哈希方法失败时使用，确保仍然具有足够的安全性
     * 
     * @param input 输入字符串
     * @return 16位十六进制哈希字符串
     */
    private fun generateFallbackHash(input: String): String {
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            
            // 添加盐值增强安全性
            digest.update(FALLBACK_SALT.toByteArray(StandardCharsets.UTF_8))
            digest.update(input.toByteArray(StandardCharsets.UTF_8))
            
            val hashBytes = digest.digest()
            hashBytes.take(HASH_LENGTH_BYTES).joinToString("") { 
                "%02x".format(it) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "回退哈希也失败，使用最终安全回退", e)
            // 最终回退：使用安全随机数生成器
            generateSecureRandomHash()
        }
    }
    
    /**
     * 生成安全随机哈希（最终回退方案）
     * 
     * @return 16位十六进制随机字符串
     */
    private fun generateSecureRandomHash(): String {
        val random = SecureRandom()
        val bytes = ByteArray(HASH_LENGTH_BYTES)
        random.nextBytes(bytes)
        
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 验证两个哈希是否可能产生碰撞
     * 
     * @param hash1 第一个哈希
     * @param hash2 第二个哈希
     * @return true如果相同（可能碰撞），false如果不同
     */
    fun checkHashCollision(hash1: String, hash2: String): Boolean {
        return hash1.equals(hash2, ignoreCase = true)
    }
    
    /**
     * 获取哈希统计信息
     * 
     * @return 哈希配置信息
     */
    fun getHashInfo(): Map<String, Any> {
        return mapOf(
            "algorithm" to HASH_ALGORITHM,
            "lengthBytes" to HASH_LENGTH_BYTES,
            "lengthHex" to HASH_LENGTH_HEX,
            "securityLevel" to "${HASH_LENGTH_BYTES * 8}-bit"
        )
    }
} 