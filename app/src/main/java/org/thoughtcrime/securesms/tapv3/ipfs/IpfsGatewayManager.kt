package org.thoughtcrime.securesms.tapv3.ipfs

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result

class IpfsGatewayManager private constructor(
    private val context: Context
) {
    
    private var pinataGateway: PinataGateway? = null
    private var web3StorageGateway: Web3StorageGateway? = null
    
    fun configurePinata(apiKey: String, apiSecret: String) {
        pinataGateway = PinataGateway(apiKey, apiSecret)
        Log.d(TAG, "Pinata gateway configured")
    }
    
    fun configureWeb3Storage(token: String) {
        web3StorageGateway = Web3StorageGateway(token)
        Log.d(TAG, "Web3.Storage gateway configured")
    }
    
    fun clearConfiguration() {
        pinataGateway = null
        web3StorageGateway = null
        Log.d(TAG, "Gateway configuration cleared")
    }
    
    suspend fun upload(data: ByteArray): TapV3Result<String> {
        val gateway = selectGatewayForUpload(data.size.toLong())
            ?: return TapV3Result.Failure(
                TapV3Error.IPFS_UPLOAD_ERROR,
                "No IPFS gateway configured"
            )
        
        Log.d(TAG, "Uploading ${data.size} bytes to ${gateway.name}")
        
        val result = gateway.pin(data)
        
        if (result.isSuccess()) {
            return result
        }
        
        val fallbackGateway = getOtherGateway(gateway)
        if (fallbackGateway != null) {
            Log.w(TAG, "Primary gateway failed, trying fallback: ${fallbackGateway.name}")
            return fallbackGateway.pin(data)
        }
        
        return result
    }
    
    suspend fun uploadWithRedundancy(data: ByteArray): TapV3Result<String> = coroutineScope {
        val gateways = getConfiguredGateways()
        
        if (gateways.isEmpty()) {
            return@coroutineScope TapV3Result.Failure(
                TapV3Error.IPFS_UPLOAD_ERROR,
                "No IPFS gateway configured"
            )
        }
        
        if (gateways.size == 1) {
            return@coroutineScope gateways[0].pin(data)
        }
        
        Log.d(TAG, "Uploading with redundancy to ${gateways.size} gateways")
        
        val results = gateways.map { gateway ->
            async {
                gateway to gateway.pin(data)
            }
        }.map { it.await() }
        
        val successfulResults = results.filter { it.second.isSuccess() }
        
        if (successfulResults.isEmpty()) {
            val firstFailure = results.first().second as TapV3Result.Failure
            return@coroutineScope firstFailure
        }
        
        val cids = successfulResults.mapNotNull { (_, result) -> result.getOrNull() }
        
        if (cids.distinct().size > 1) {
            Log.e(TAG, "CID mismatch across gateways: $cids")
        }
        
        Log.d(TAG, "Successfully uploaded to ${successfulResults.size} gateways")
        TapV3Result.Success(cids.first())
    }
    
    suspend fun download(cid: String): TapV3Result<ByteArray> {
        val gateways = getConfiguredGateways()
        
        if (gateways.isEmpty()) {
            return TapV3Result.Failure(
                TapV3Error.IPFS_DOWNLOAD_ERROR,
                "No IPFS gateway configured"
            )
        }
        
        for (gateway in gateways) {
            Log.d(TAG, "Attempting download from ${gateway.name}: $cid")
            
            val result = gateway.get(cid)
            if (result.isSuccess()) {
                return result
            }
            
            Log.w(TAG, "Download failed from ${gateway.name}, trying next gateway")
        }
        
        return TapV3Result.Failure(
            TapV3Error.IPFS_DOWNLOAD_ERROR,
            "Failed to download from all configured gateways"
        )
    }
    
    suspend fun unpin(cid: String): TapV3Result<Unit> = coroutineScope {
        val gateways = getConfiguredGateways()
        
        if (gateways.isEmpty()) {
            return@coroutineScope TapV3Result.Failure(
                TapV3Error.IPFS_UPLOAD_ERROR,
                "No IPFS gateway configured"
            )
        }
        
        val results = gateways.map { gateway ->
            async {
                gateway.unpin(cid)
            }
        }.map { it.await() }
        
        val anySuccess = results.any { it.isSuccess() }
        
        if (anySuccess) {
            Log.d(TAG, "Successfully unpinned: $cid")
            TapV3Result.Success(Unit)
        } else {
            val firstFailure = results.first() as TapV3Result.Failure
            firstFailure
        }
    }
    
    private fun selectGatewayForUpload(dataSize: Long): IpfsGateway? {
        val pinata = pinataGateway
        val web3 = web3StorageGateway
        
        return when {
            pinata == null && web3 == null -> null
            pinata == null -> web3
            web3 == null -> pinata
            dataSize < 10 * 1024 * 1024 && pinata.quotaRemaining > dataSize -> pinata
            else -> web3
        }
    }
    
    private fun getOtherGateway(current: IpfsGateway): IpfsGateway? {
        return when (current) {
            is PinataGateway -> web3StorageGateway
            is Web3StorageGateway -> pinataGateway
            else -> null
        }
    }
    
    private fun getConfiguredGateways(): List<IpfsGateway> {
        return listOfNotNull(pinataGateway, web3StorageGateway)
    }
    
    companion object {
        private val TAG = Log.tag(IpfsGatewayManager::class.java)
        
        @Volatile
        private var INSTANCE: IpfsGatewayManager? = null
        
        fun getInstance(context: Context): IpfsGatewayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IpfsGatewayManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
