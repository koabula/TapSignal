package org.thoughtcrime.securesms.tap.provider.cos

import android.util.Log
import org.thoughtcrime.securesms.tap.TransportMetadata
import org.thoughtcrime.securesms.tap.SendMetadata
import org.thoughtcrime.securesms.tap.ReceiveMetadata
import org.thoughtcrime.securesms.tap.TransportToken
import org.thoughtcrime.securesms.tap.utils.LogSanitizer
import org.thoughtcrime.securesms.tap.group.GroupMemberGatewayInfo

/**
 * COS传输元数据实现
 * 
 * 用于COS（云对象存储）服务的传输元数据，包含COS特有的region和bucketName信息。
 * 支持发送和接收的分离元数据，使用哈希化ID和显式路径信息。
 */
data class CosTransportMetadata(
    override val recipientId: String,
    override val providerType: String = "cos",
    
    // 本端COS配置（用于发送）
    val myAddress: String,         // 本端COS bucket URL
    val myToken: TransportToken?,  // 本端访问凭证
    val myRegion: String,
    val myBucketName: String,
    val mySendPath: String,        // 显式发送路径（如 /outbox/abc123def456/）
    
    // 对端COS配置（用于接收）
    val peerAddress: String,       // 对端COS bucket URL  
    val peerToken: TransportToken?, // 对端访问凭证（只读）
    val peerRegion: String,
    val peerBucketName: String,
    val peerReceivePath: String,   // 显式接收路径（如 /outbox/def456abc123/）
    
    // 哈希化的用户ID
    val myHashedId: String,        // 本端哈希ID
    val peerHashedId: String,       // 对端哈希ID

    val groupDispatchOverrides: GroupDispatchOverrides? = null
) : TransportMetadata {
    
    override fun getSendMetadata(): SendMetadata {
        return SendMetadata(
            address = myAddress,
            token = myToken,
            path = mySendPath,
            recipientId = peerHashedId
        )
    }
    
    override fun getReceiveMetadata(): ReceiveMetadata {
        return ReceiveMetadata(
            address = peerAddress,
            token = peerToken,
            path = peerReceivePath,
            myId = myHashedId
        )
    }
    
    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "recipientId" to recipientId,
            "providerType" to providerType,
            "myAddress" to myAddress,
            "myRegion" to myRegion,
            "myBucketName" to myBucketName,
            "mySendPath" to mySendPath,
            "peerAddress" to peerAddress,
            "peerRegion" to peerRegion,
            "peerBucketName" to peerBucketName,
            "peerReceivePath" to peerReceivePath,
            "myHashedId" to myHashedId,
            "peerHashedId" to peerHashedId
        )
        
        myToken?.let { map["myToken"] = it.toMap() }
        peerToken?.let { map["peerToken"] = it.toMap() }
        
        groupDispatchOverrides?.let { overrides ->
            map["groupDispatchOverrides"] = mapOf(
                "groupId" to overrides.groupId,
                "recipientAci" to overrides.recipientAci,
                "recipientHash" to overrides.recipientHash,
                "gatewayInfo" to overrides.gatewayInfo.toMap(),
                "groupMembers" to overrides.groupMembers
            )
        }
        return map
    }
    
    override fun validate(): Boolean {
        return recipientId.isNotBlank() &&
               myAddress.isNotBlank() &&
               myRegion.isNotBlank() &&
               myBucketName.isNotBlank() &&
               mySendPath.isNotBlank() &&
               peerAddress.isNotBlank() &&
               peerRegion.isNotBlank() &&
               peerBucketName.isNotBlank() &&
               peerReceivePath.isNotBlank() &&
               myHashedId.isNotBlank() &&
               peerHashedId.isNotBlank() &&
               providerType == "cos" &&
               getSendMetadata().validate() &&
               getReceiveMetadata().validate()
    }
    
    companion object {
        private const val TAG = "CosTransportMetadata"
        
        /**
         * 从Map数据创建CosTransportMetadata实例
         */
        fun fromMap(data: Map<String, Any>): CosTransportMetadata? {
            return try {
                // 安全的类型检查和转换
                val recipientId = data["recipientId"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val providerType = data["providerType"]?.let {
                    if (it is String && it.isNotBlank()) it else "cos"
                } ?: "cos"
                
                // 验证Provider类型
                if (providerType != "cos") {
                    return null
                }
                
                val myAddress = data["myAddress"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val myRegion = data["myRegion"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val myBucketName = data["myBucketName"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val mySendPath = data["mySendPath"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val peerAddress = data["peerAddress"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val peerRegion = data["peerRegion"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val peerBucketName = data["peerBucketName"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val peerReceivePath = data["peerReceivePath"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val myHashedId = data["myHashedId"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                val peerHashedId = data["peerHashedId"]?.let { 
                    if (it is String && it.isNotBlank()) it else return null 
                } ?: return null
                
                // 处理Token数据
                val myToken: TransportToken? = data["myToken"]?.let { tokenData ->
                    if (tokenData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val tokenMap = tokenData as Map<String, Any>
                        org.thoughtcrime.securesms.tap.CosTransportToken.fromMap(tokenMap)
                    } else null
                }
                
                val peerToken: TransportToken? = data["peerToken"]?.let { tokenData ->
                    if (tokenData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val tokenMap = tokenData as Map<String, Any>
                        org.thoughtcrime.securesms.tap.CosTransportToken.fromMap(tokenMap)
                    } else null
                }
                
                CosTransportMetadata(
                    recipientId = recipientId,
                    providerType = providerType,
                    myAddress = myAddress,
                    myToken = myToken,
                    myRegion = myRegion,
                    myBucketName = myBucketName,
                    mySendPath = mySendPath,
                    peerAddress = peerAddress,
                    peerToken = peerToken,
                    peerRegion = peerRegion,
                    peerBucketName = peerBucketName,
                    peerReceivePath = peerReceivePath,
                    myHashedId = myHashedId,
                    peerHashedId = peerHashedId,
                    groupDispatchOverrides = parseGroupOverrides(data)
                )
            } catch (e: ClassCastException) {
                Log.e(TAG, "类型转换错误", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "反序列化失败", e)
                null
            }
        }

        private fun parseGroupOverrides(data: Map<String, Any>): GroupDispatchOverrides? {
            val overridesMap = data["groupDispatchOverrides"] as? Map<*, *> ?: return null
            val groupId = overridesMap["groupId"] as? String ?: return null
            val recipientAci = overridesMap["recipientAci"] as? String ?: return null
            val recipientHash = overridesMap["recipientHash"] as? String ?: return null
            val gatewayMap = overridesMap["gatewayInfo"] as? Map<*, *> ?: return null
            val gatewayInfo = try {
                GroupMemberGatewayInfo(
                    memberAci = recipientAci,
                    provider = gatewayMap["provider"] as? String,
                    endpoint = gatewayMap["endpoint"] as? String,
                    region = gatewayMap["region"] as? String,
                    offlineBucket = gatewayMap["offlineBucket"] as? String,
                    presignDelegation = (gatewayMap["presignDelegation"] as? Boolean) ?: false,
                    metadata = (gatewayMap["metadata"] as? Map<String, String>) ?: emptyMap(),
                    webhookUrl = gatewayMap["webhookUrl"] as? String,
                    notifySecret = gatewayMap["notifySecret"] as? String,
                    userId = gatewayMap["userId"] as? String,
                    updatedAt = (gatewayMap["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "解析groupDispatchOverrides失败", e)
                return null
            }
            val members = (overridesMap["groupMembers"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()
            return GroupDispatchOverrides(
                groupId = groupId,
                recipientAci = recipientAci,
                recipientHash = recipientHash,
                gatewayInfo = gatewayInfo,
                groupMembers = members
            )
        }
    }
}

data class GroupDispatchOverrides(
    val groupId: String,
    val recipientAci: String,
    val recipientHash: String,
    val gatewayInfo: GroupMemberGatewayInfo,
    val groupMembers: List<String> = emptyList()
)

private fun GroupMemberGatewayInfo.toMap(): Map<String, Any?> {
    return mapOf(
        "memberAci" to memberAci,
        "provider" to provider,
        "endpoint" to endpoint,
        "region" to region,
        "offlineBucket" to offlineBucket,
        "presignDelegation" to presignDelegation,
        "metadata" to metadata,
        "webhookUrl" to webhookUrl,
        "notifySecret" to notifySecret,
        "userId" to userId,
        "updatedAt" to updatedAt
    )
}
