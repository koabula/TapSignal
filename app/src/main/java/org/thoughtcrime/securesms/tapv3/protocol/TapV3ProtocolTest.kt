package org.thoughtcrime.securesms.tapv3.protocol

import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Payload
import org.thoughtcrime.securesms.tapv3.TapV3Result
import org.thoughtcrime.securesms.tapv3.crypto.TapV3Crypto

object TapV3ProtocolTest {
    
    fun testMessageCodec() {
        println("=== Testing TapV3MessageCodec ===")
        
        val kPush = TapV3Crypto.generateKPushKey()
        println("Generated k_push key: ${kPush.size} bytes")
        
        val shortMessage = "Hello, this is a short message".toByteArray()
        val inlinePayload = TapV3Payload.Inline(encrypted = shortMessage)
        
        val encodeResult = TapV3MessageCodec.encodeMessage(inlinePayload, kPush)
        if (encodeResult.isSuccess()) {
            val encoded = encodeResult.getOrNull()!!
            println("Encoded inline message:")
            println("  Raw size: ${encoded.rawSize} bytes")
            println("  Encoded size: ${encoded.encodedSize} chars")
            println("  Base64 length: ${encoded.base64Data.length}")
            
            val decodeResult = TapV3MessageCodec.decodeMessage(encoded.base64Data, kPush)
            if (decodeResult.isSuccess()) {
                val decoded = decodeResult.getOrNull()!!
                println("  Decoded successfully: ${decoded::class.simpleName}")
                
                if (decoded is TapV3Payload.Inline) {
                    val match = decoded.encrypted.contentEquals(shortMessage)
                    println("  Content match: $match")
                }
            } else {
                println("  Decode failed: ${(decodeResult as TapV3Result.Failure).message}")
            }
        } else {
            println("Encode failed: ${(encodeResult as TapV3Result.Failure).message}")
        }
        
        val ipfsPayload = TapV3Payload.IpfsRefs(
            messageCid = "QmXxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            attachments = listOf(
                TapV3Payload.AttachmentRef(
                    cid = "QmYyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy",
                    size = 1048576,
                    mimeType = "image/jpeg"
                )
            )
        )
        
        val encodeIpfsResult = TapV3MessageCodec.encodeMessage(ipfsPayload, kPush)
        if (encodeIpfsResult.isSuccess()) {
            val encoded = encodeIpfsResult.getOrNull()!!
            println("\nEncoded IPFS refs message:")
            println("  Raw size: ${encoded.rawSize} bytes")
            println("  Encoded size: ${encoded.encodedSize} chars")
            
            val decodeIpfsResult = TapV3MessageCodec.decodeMessage(encoded.base64Data, kPush)
            if (decodeIpfsResult.isSuccess()) {
                val decoded = decodeIpfsResult.getOrNull()!!
                println("  Decoded successfully: ${decoded::class.simpleName}")
                
                if (decoded is TapV3Payload.IpfsRefs) {
                    println("  Message CID: ${decoded.messageCid}")
                    println("  Attachments: ${decoded.attachments.size}")
                }
            }
        }
        
        println("\n=== Inline Threshold Test ===")
        val smallSize = 100
        val mediumSize = 1500
        val largeSize = 3000
        
        println("Should use inline for $smallSize bytes: ${TapV3MessageCodec.shouldUseInline(smallSize)}")
        println("Should use inline for $mediumSize bytes: ${TapV3MessageCodec.shouldUseInline(mediumSize)}")
        println("Should use inline for $largeSize bytes: ${TapV3MessageCodec.shouldUseInline(largeSize)}")
    }
    
    fun testControlMessages() {
        println("\n=== Testing TapV3ControlMessage ===")
        
        val kPush = TapV3Crypto.generateKPushKey()
        
        val handshakeInfo = org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo(
            version = TapV3Constants.VERSION,
            unifiedPushEndpoint = "https://ntfy.sh/test-endpoint",
            kPush = TapV3Crypto.generateKPushKey(),
            keyVersion = 1,
            ipfsGateways = listOf("pinata.cloud", "web3.storage"),
            capabilities = setOf("inline", "ipfs", "multi-attachment")
        )
        
        val request = TapV3ControlMessage.HandshakeRequest(handshakeInfo = handshakeInfo)
        
        val encodeResult = TapV3MessageCodec.encodeControlMessage(request, kPush)
        if (encodeResult.isSuccess()) {
            val encoded = encodeResult.getOrNull()!!
            println("Encoded handshake request:")
            println("  Raw size: ${encoded.rawSize} bytes")
            println("  Encoded size: ${encoded.encodedSize} chars")
            
            val decodeResult = TapV3MessageCodec.decodeControlMessage(encoded.base64Data, kPush)
            if (decodeResult.isSuccess()) {
                val decoded = decodeResult.getOrNull()!!
                println("  Decoded successfully: ${decoded::class.simpleName}")
                
                if (decoded is TapV3ControlMessage.HandshakeRequest) {
                    println("  Version: ${decoded.handshakeInfo.version}")
                    println("  Endpoint: ${decoded.handshakeInfo.unifiedPushEndpoint}")
                    println("  Gateways: ${decoded.handshakeInfo.ipfsGateways}")
                }
            } else {
                println("  Decode failed: ${(decodeResult as TapV3Result.Failure).message}")
            }
        } else {
            println("Encode failed: ${(encodeResult as TapV3Result.Failure).message}")
        }
        
        val response = TapV3ControlMessage.HandshakeResponse(
            handshakeInfo = handshakeInfo,
            accepted = true
        )
        
        val encodeRespResult = TapV3MessageCodec.encodeControlMessage(response, kPush)
        if (encodeRespResult.isSuccess()) {
            val encoded = encodeRespResult.getOrNull()!!
            println("\nEncoded handshake response:")
            println("  Encoded size: ${encoded.encodedSize} chars")
        }
        
        val ack = TapV3ControlMessage.HandshakeAck(success = true)
        val encodeAckResult = TapV3MessageCodec.encodeControlMessage(ack, kPush)
        if (encodeAckResult.isSuccess()) {
            val encoded = encodeAckResult.getOrNull()!!
            println("\nEncoded handshake ack:")
            println("  Encoded size: ${encoded.encodedSize} chars")
        }
    }
    
    fun testSerialization() {
        println("\n=== Testing Serialization ===")
        
        val payload = TapV3Payload.IpfsRefs(
            messageCid = "QmTest",
            attachments = listOf(
                TapV3Payload.AttachmentRef("QmAtt1", 1000, "image/png"),
                TapV3Payload.AttachmentRef("QmAtt2", 2000, "video/mp4")
            )
        )
        
        val serialized = TapV3Payload.serialize(payload)
        println("Serialized payload size: ${serialized.size} bytes")
        
        val deserialized = TapV3Payload.deserialize(serialized)
        println("Deserialized: ${deserialized::class.simpleName}")
        
        if (deserialized is TapV3Payload.IpfsRefs) {
            println("  Message CID: ${deserialized.messageCid}")
            println("  Attachments: ${deserialized.attachments.size}")
        }
        
        val handshakeInfo = org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo(
            version = 3,
            unifiedPushEndpoint = "https://test.com/endpoint",
            kPush = ByteArray(32) { it.toByte() },
            keyVersion = 1,
            ipfsGateways = listOf("gateway1", "gateway2")
        )
        
        val infoSerialized = org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo.serialize(handshakeInfo)
        println("\nSerialized handshake info size: ${infoSerialized.size} bytes")
        
        val infoDeserialized = org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo.deserialize(infoSerialized)
        println("Deserialized handshake info:")
        println("  Version: ${infoDeserialized.version}")
        println("  Endpoint: ${infoDeserialized.unifiedPushEndpoint}")
    }
}
