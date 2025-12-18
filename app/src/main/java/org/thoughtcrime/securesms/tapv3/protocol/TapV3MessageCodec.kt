package org.thoughtcrime.securesms.tapv3.protocol

import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto
import org.thoughtcrime.securesms.tapv3.utils.TapV3Validator
import java.nio.ByteBuffer

/**
 * Tap v3 消息编解码器
 * 
 * 消息格式（k_push 加密前）:
 * +--------+--------+------------------+---------------+
 * | 版本   | 类型   | senderId         | payload       |
 * | 1 byte | 1 byte | 1+N bytes        | 变长          |
 * +--------+--------+------------------+---------------+
 *                   |
 *                   +-> 长度(1B) + UTF-8字符串(NB)
 */
object TapV3MessageCodec {
    
    private val TAG = Log.tag(TapV3MessageCodec::class.java)
    
    private const val VERSION_BYTE: Byte = 0x03
    private const val TYPE_INLINE: Byte = 0x01
    private const val TYPE_IPFS_REFS: Byte = 0x02
    private const val TYPE_CONTROL: Byte = 0x03
    private const val TYPE_GROUP_MESSAGE: Byte = 0x04
    
    private const val MAX_SENDER_ID_LENGTH = 255
    
    data class EncodedMessage(
        val base64Data: String,
        val rawSize: Int,
        val encodedSize: Int
    )
    
    data class DecodedMessage(
        val senderId: String,
        val payload: TapV3Payload
    )
    
    /**
     * 编码消息，包含 senderId
     * senderId 被包含在 k_push 加密层内部，增强隐私保护
     */
    fun encodeMessage(
        payload: TapV3Payload,
        kPush: ByteArray,
        senderId: String
    ): TapV3Result<EncodedMessage> {
        return try {
            val payloadBytes = TapV3Payload.serialize(payload)
            Log.d(TAG, "Serialized payload: ${payloadBytes.size} bytes")
            
            val typeFlag = when (payload) {
                is TapV3Payload.Inline -> TYPE_INLINE
                is TapV3Payload.IpfsRefs -> TYPE_IPFS_REFS
                is TapV3Payload.GroupMessage -> TYPE_GROUP_MESSAGE
            }
            
            val senderIdBytes = senderId.toByteArray(Charsets.UTF_8)
            if (senderIdBytes.size > MAX_SENDER_ID_LENGTH) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Sender ID too long: ${senderIdBytes.size} > $MAX_SENDER_ID_LENGTH"
                )
            }
            
            // 构建: version(1) + type(1) + senderIdLen(1) + senderId(N) + payload
            val plainData = ByteArray(2 + 1 + senderIdBytes.size + payloadBytes.size)
            plainData[0] = VERSION_BYTE
            plainData[1] = typeFlag
            plainData[2] = senderIdBytes.size.toByte()
            System.arraycopy(senderIdBytes, 0, plainData, 3, senderIdBytes.size)
            System.arraycopy(payloadBytes, 0, plainData, 3 + senderIdBytes.size, payloadBytes.size)
            
            Log.d(TAG, "Plain data (header + senderId + payload): ${plainData.size} bytes")
            
            val encryptResult = TapV3Crypto.encrypt(plainData, kPush)
            if (encryptResult is TapV3Result.Failure) {
                return encryptResult
            }
            
            val encrypted = (encryptResult as TapV3Result.Success).data
            Log.d(TAG, "Encrypted data: ${encrypted.size} bytes")
            
            val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            Log.d(TAG, "Base64 encoded: ${base64.length} chars")
            
            if (base64.length > TapV3Constants.PUSH_MESSAGE_MAX_SIZE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Encoded message too large: ${base64.length} > ${TapV3Constants.PUSH_MESSAGE_MAX_SIZE}"
                )
            }
            
            TapV3Result.Success(
                EncodedMessage(
                    base64Data = base64,
                    rawSize = plainData.size,
                    encodedSize = base64.length
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode message", e)
            TapV3Result.Failure(TapV3Error.INVALID_DATA, "Message encoding failed: ${e.message}", e)
        }
    }
    
    /**
     * 解码消息，返回 senderId 和 payload
     * 使用接收方自己的 myKPush 解密
     */
    fun decodeMessage(
        base64Data: String,
        myKPush: ByteArray
    ): TapV3Result<DecodedMessage> {
        return try {
            if (base64Data.length > TapV3Constants.PUSH_MESSAGE_MAX_SIZE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Encoded message too large: ${base64Data.length}"
                )
            }
            
            val encrypted = Base64.decode(base64Data, Base64.NO_WRAP)
            Log.d(TAG, "Decoded base64 to ${encrypted.size} bytes")
            
            val decryptResult = TapV3Crypto.decrypt(encrypted, myKPush)
            if (decryptResult is TapV3Result.Failure) {
                return TapV3Result.Failure(
                    decryptResult.error,
                    decryptResult.message,
                    decryptResult.cause
                )
            }
            
            val plainData = (decryptResult as TapV3Result.Success).data
            Log.d(TAG, "Decrypted to ${plainData.size} bytes")
            
            // 至少需要: version(1) + type(1) + senderIdLen(1) = 3 bytes
            if (plainData.size < 3) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Message too short: ${plainData.size}"
                )
            }
            
            val version = plainData[0]
            if (version != VERSION_BYTE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Unsupported version: $version"
                )
            }
            
            val typeFlag = plainData[1]
            val senderIdLen = plainData[2].toInt() and 0xFF
            
            if (plainData.size < 3 + senderIdLen) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Message too short for senderId: ${plainData.size} < ${3 + senderIdLen}"
                )
            }
            
            val senderId = String(plainData, 3, senderIdLen, Charsets.UTF_8)
            val payloadBytes = plainData.copyOfRange(3 + senderIdLen, plainData.size)
            
            Log.d(TAG, "Decoded senderId: ${senderId.take(8)}..., payload type: $typeFlag, size: ${payloadBytes.size} bytes")
            
            val payload = TapV3Payload.deserialize(payloadBytes)
            
            when (typeFlag) {
                TYPE_INLINE -> {
                    if (payload !is TapV3Payload.Inline) {
                        return TapV3Result.Failure(
                            TapV3Error.INVALID_DATA,
                            "Type mismatch: expected Inline, got ${payload::class.simpleName}"
                        )
                    }
                }
                TYPE_IPFS_REFS -> {
                    if (payload !is TapV3Payload.IpfsRefs) {
                        return TapV3Result.Failure(
                            TapV3Error.INVALID_DATA,
                            "Type mismatch: expected IpfsRefs, got ${payload::class.simpleName}"
                        )
                    }
                    
                    val validationResult = TapV3Validator.validateIpfsRefs(payload)
                    if (validationResult is TapV3Result.Failure) {
                        return TapV3Result.Failure(
                            validationResult.error,
                            validationResult.message,
                            validationResult.cause
                        )
                    }
                }
                TYPE_GROUP_MESSAGE -> {
                    if (payload !is TapV3Payload.GroupMessage) {
                        return TapV3Result.Failure(
                            TapV3Error.INVALID_DATA,
                            "Type mismatch: expected GroupMessage, got ${payload::class.simpleName}"
                        )
                    }
                }
                else -> {
                    return TapV3Result.Failure(
                        TapV3Error.INVALID_DATA,
                        "Unknown message type: $typeFlag"
                    )
                }
            }
            
            TapV3Result.Success(DecodedMessage(senderId = senderId, payload = payload))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode message", e)
            TapV3Result.Failure(TapV3Error.INVALID_DATA, "Message decoding failed: ${e.message}", e)
        }
    }
    
    fun encodeControlMessage(
        message: TapV3ControlMessage,
        kPush: ByteArray
    ): TapV3Result<EncodedMessage> {
        return try {
            val messageBytes = TapV3ControlMessage.serialize(message)
            Log.d(TAG, "Serialized control message: ${messageBytes.size} bytes")
            
            val header = byteArrayOf(VERSION_BYTE, TYPE_CONTROL)
            val plainData = header + messageBytes
            
            val encryptResult = TapV3Crypto.encrypt(plainData, kPush)
            if (encryptResult is TapV3Result.Failure) {
                return encryptResult
            }
            
            val encrypted = (encryptResult as TapV3Result.Success).data
            val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            
            Log.d(TAG, "Encoded control message: ${base64.length} chars")
            
            if (base64.length > TapV3Constants.PUSH_MESSAGE_MAX_SIZE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Encoded control message too large: ${base64.length}"
                )
            }
            
            TapV3Result.Success(
                EncodedMessage(
                    base64Data = base64,
                    rawSize = plainData.size,
                    encodedSize = base64.length
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode control message", e)
            TapV3Result.Failure(TapV3Error.INVALID_DATA, "Control message encoding failed: ${e.message}", e)
        }
    }
    
    fun decodeControlMessage(
        base64Data: String,
        kPush: ByteArray
    ): TapV3Result<TapV3ControlMessage> {
        return try {
            val encrypted = Base64.decode(base64Data, Base64.NO_WRAP)
            
            val decryptResult = TapV3Crypto.decrypt(encrypted, kPush)
            if (decryptResult is TapV3Result.Failure) {
                return TapV3Result.Failure(
                    decryptResult.error,
                    decryptResult.message,
                    decryptResult.cause
                )
            }
            
            val plainData = (decryptResult as TapV3Result.Success).data
            
            if (plainData.size < 2) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Control message too short: ${plainData.size}"
                )
            }
            
            val version = plainData[0]
            if (version != VERSION_BYTE) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Unsupported version: $version"
                )
            }
            
            val typeFlag = plainData[1]
            if (typeFlag != TYPE_CONTROL) {
                return TapV3Result.Failure(
                    TapV3Error.INVALID_DATA,
                    "Not a control message: type=$typeFlag"
                )
            }
            
            val messageBytes = plainData.copyOfRange(2, plainData.size)
            val message = TapV3ControlMessage.deserialize(messageBytes)
            
            Log.d(TAG, "Decoded control message: ${message::class.simpleName}")
            
            TapV3Result.Success(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode control message", e)
            TapV3Result.Failure(TapV3Error.INVALID_DATA, "Control message decoding failed: ${e.message}", e)
        }
    }
    
    fun shouldUseInline(signalEncryptedSize: Int): Boolean {
        // 额外开销: version(1) + type(1) + senderIdLen(1) + senderId(~36 for UUID) + AES overhead(12+16)
        val senderIdOverhead = 1 + 36
        val overhead = 2 + senderIdOverhead + 12 + 16
        val estimatedSize = signalEncryptedSize + overhead
        
        val afterKPushEncryption = estimatedSize + 12 + 16
        val base64Size = (afterKPushEncryption * 4 + 2) / 3
        
        val result = base64Size <= TapV3Constants.INLINE_THRESHOLD_BYTES
        
        Log.d(TAG, "Inline decision: signalSize=$signalEncryptedSize, " +
                "estimated=$estimatedSize, afterKPush=$afterKPushEncryption, " +
                "base64=$base64Size, useInline=$result")
        
        return result
    }
}
