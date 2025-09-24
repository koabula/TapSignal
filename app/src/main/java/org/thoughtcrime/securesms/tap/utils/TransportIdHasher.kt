package org.thoughtcrime.securesms.tap.utils

import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.push.ServiceId
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * 传输ID哈希工具
 * 
 * 负责将Signal ACI转换为短哈希，用作传输路径的标识符。
 * 确保双方使用相同的哈希算法生成一致的路径标识符。
 */
object TransportIdHasher {
    
    private val TAG = Log.tag(TransportIdHasher::class.java)
    
    /**
     * 将ACI转换为短哈希（用于路径标识符）
     * 
     * @param aci Signal ACI
     * @return 12位十六进制哈希字符串
     */
    fun hashAci(aci: ServiceId.ACI): String {
        return try {
            val aciString = aci.toString()
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(aciString.toByteArray(StandardCharsets.UTF_8))
            
            // 取前6字节转为12位十六进制字符串
            val shortHash = hashBytes.take(6).joinToString("") { 
                "%02x".format(it) 
            }
            
            Log.d(TAG, "ACI哈希生成: ${LogSanitizer.sanitize(aciString)} -> $shortHash")
            shortHash
        } catch (e: Exception) {
            Log.e(TAG, "ACI哈希生成失败", e)
            // 回退到ACI的最后12位字符（去掉连字符）
            aci.toString().replace("-", "").takeLast(12).lowercase()
        }
    }
    
    /**
     * 验证哈希是否有效
     */
    fun isValidHash(hash: String): Boolean {
        return hash.matches(Regex("^[0-9a-f]{12}$"))
    }
    
    /**
     * 从完整ACI字符串生成哈希
     */
    fun hashAciString(aciString: String): String {
        return try {
            val aci = ServiceId.ACI.parseOrThrow(aciString)
            hashAci(aci)
        } catch (e: Exception) {
            Log.w(TAG, "解析ACI字符串失败: $aciString", e)
            // 回退处理
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(aciString.toByteArray(StandardCharsets.UTF_8))
            hashBytes.take(6).joinToString("") { "%02x".format(it) }
        }
    }
} 