package org.thoughtcrime.securesms.tapv3.crypto

import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TapV3Crypto {
    
    private const val AES_GCM_TAG_LENGTH_BITS = 128
    private const val AES_GCM_IV_LENGTH_BYTES = 12
    
    fun generateKPushKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(TapV3Constants.KPUSH_KEY_SIZE_BYTES * 8)
        val secretKey = keyGen.generateKey()
        return secretKey.encoded
    }
    
    fun generateRandomBytes(size: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }
    
    fun encrypt(plaintext: ByteArray, key: ByteArray): TapV3Result<ByteArray> {
        return try {
            if (key.size != TapV3Constants.KPUSH_KEY_SIZE_BYTES) {
                return TapV3Result.Failure(
                    TapV3Error.ENCRYPTION_ERROR,
                    "Invalid key size: ${key.size}, expected ${TapV3Constants.KPUSH_KEY_SIZE_BYTES}"
                )
            }
            
            val iv = generateRandomBytes(AES_GCM_IV_LENGTH_BYTES)
            
            val secretKey: SecretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            
            val ciphertext = cipher.doFinal(plaintext)
            
            val result = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
            
            Log.d(TAG, "Encrypted ${plaintext.size} bytes to ${result.size} bytes")
            TapV3Result.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            TapV3Result.Failure(TapV3Error.ENCRYPTION_ERROR, "Encryption failed: ${e.message}", e)
        }
    }
    
    fun decrypt(ciphertext: ByteArray, key: ByteArray): TapV3Result<ByteArray> {
        return try {
            if (key.size != TapV3Constants.KPUSH_KEY_SIZE_BYTES) {
                return TapV3Result.Failure(
                    TapV3Error.DECRYPTION_ERROR,
                    "Invalid key size: ${key.size}, expected ${TapV3Constants.KPUSH_KEY_SIZE_BYTES}"
                )
            }
            
            if (ciphertext.size < AES_GCM_IV_LENGTH_BYTES + AES_GCM_TAG_LENGTH_BITS / 8) {
                return TapV3Result.Failure(
                    TapV3Error.DECRYPTION_ERROR,
                    "Ciphertext too short: ${ciphertext.size}"
                )
            }
            
            val iv = ByteArray(AES_GCM_IV_LENGTH_BYTES)
            System.arraycopy(ciphertext, 0, iv, 0, AES_GCM_IV_LENGTH_BYTES)
            
            val actualCiphertext = ByteArray(ciphertext.size - AES_GCM_IV_LENGTH_BYTES)
            System.arraycopy(ciphertext, AES_GCM_IV_LENGTH_BYTES, actualCiphertext, 0, actualCiphertext.size)
            
            val secretKey: SecretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            val plaintext = cipher.doFinal(actualCiphertext)
            
            Log.d(TAG, "Decrypted ${ciphertext.size} bytes to ${plaintext.size} bytes")
            TapV3Result.Success(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            TapV3Result.Failure(TapV3Error.DECRYPTION_ERROR, "Decryption failed: ${e.message}", e)
        }
    }
    
    private val TAG = Log.tag(TapV3Crypto::class.java)
}
