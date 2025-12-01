package org.thoughtcrime.securesms.tapv3.ipfs

import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result
import java.io.IOException
import java.util.concurrent.TimeUnit

class PinataGateway(
    private val apiKey: String,
    private val apiSecret: String
) : IpfsGateway {
    
    override val name: String = "Pinata"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private var cachedQuota: Long = -1L
    
    override val quotaRemaining: Long
        get() = cachedQuota
    
    override suspend fun pin(data: ByteArray): TapV3Result<String> {
        return try {
            withTimeout(TapV3Constants.IPFS_UPLOAD_TIMEOUT_MS) {
                val requestBody = data.toRequestBody("application/octet-stream".toMediaType())
                
                val request = Request.Builder()
                    .url("https://api.pinata.cloud/pinning/pinFileToIPFS")
                    .addHeader("pinata_api_key", apiKey)
                    .addHeader("pinata_secret_api_key", apiSecret)
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Pinata pin failed: ${response.code}")
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Pinata upload failed: HTTP ${response.code}"
                    )
                }
                
                val body = response.body?.string()
                if (body == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Empty response from Pinata"
                    )
                }
                
                val cid = extractCidFromPinataResponse(body)
                if (cid == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Failed to extract CID from Pinata response"
                    )
                }
                
                Log.d(TAG, "Successfully pinned to Pinata: $cid")
                TapV3Result.Success(cid)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Pinata pin IO error", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Pinata pin error", e)
            TapV3Result.Failure(TapV3Error.IPFS_UPLOAD_ERROR, "Upload error: ${e.message}", e)
        }
    }
    
    override suspend fun get(cid: String): TapV3Result<ByteArray> {
        return try {
            withTimeout(TapV3Constants.IPFS_DOWNLOAD_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url("https://gateway.pinata.cloud/ipfs/$cid")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Pinata get failed: ${response.code}")
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_DOWNLOAD_ERROR,
                        "Pinata download failed: HTTP ${response.code}"
                    )
                }
                
                val data = response.body?.bytes()
                if (data == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_DOWNLOAD_ERROR,
                        "Empty response from Pinata"
                    )
                }
                
                Log.d(TAG, "Successfully downloaded from Pinata: $cid (${data.size} bytes)")
                TapV3Result.Success(data)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Pinata get IO error", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Pinata get error", e)
            TapV3Result.Failure(TapV3Error.IPFS_DOWNLOAD_ERROR, "Download error: ${e.message}", e)
        }
    }
    
    override suspend fun unpin(cid: String): TapV3Result<Unit> {
        return try {
            val request = Request.Builder()
                .url("https://api.pinata.cloud/pinning/unpin/$cid")
                .addHeader("pinata_api_key", apiKey)
                .addHeader("pinata_secret_api_key", apiSecret)
                .delete()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Pinata unpin failed: ${response.code}")
                return TapV3Result.Failure(
                    TapV3Error.IPFS_UPLOAD_ERROR,
                    "Pinata unpin failed: HTTP ${response.code}"
                )
            }
            
            Log.d(TAG, "Successfully unpinned from Pinata: $cid")
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Pinata unpin error", e)
            TapV3Result.Failure(TapV3Error.IPFS_UPLOAD_ERROR, "Unpin error: ${e.message}", e)
        }
    }
    
    override suspend fun getUsageStats(): TapV3Result<IpfsGateway.UsageStats> {
        return try {
            val request = Request.Builder()
                .url("https://api.pinata.cloud/data/userPinnedDataTotal")
                .addHeader("pinata_api_key", apiKey)
                .addHeader("pinata_secret_api_key", apiSecret)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return TapV3Result.Failure(
                    TapV3Error.NETWORK_ERROR,
                    "Failed to get Pinata usage: HTTP ${response.code}"
                )
            }
            
            val body = response.body?.string()
            if (body == null) {
                return TapV3Result.Failure(
                    TapV3Error.NETWORK_ERROR,
                    "Empty response from Pinata usage API"
                )
            }
            
            val stats = parsePinataUsageStats(body)
            cachedQuota = stats.remaining
            
            TapV3Result.Success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Pinata usage stats", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Usage stats error: ${e.message}", e)
        }
    }
    
    private fun extractCidFromPinataResponse(json: String): String? {
        return try {
            val cidPattern = """"IpfsHash"\s*:\s*"([^"]+)"""".toRegex()
            cidPattern.find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CID from response", e)
            null
        }
    }
    
    private fun parsePinataUsageStats(json: String): IpfsGateway.UsageStats {
        val sizePattern = """"pin_size_total"\s*:\s*(\d+)""".toRegex()
        val used = sizePattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        
        val totalBytes = 1L * 1024 * 1024 * 1024
        
        return IpfsGateway.UsageStats(
            used = used,
            total = totalBytes,
            remaining = (totalBytes - used).coerceAtLeast(0)
        )
    }
    
    companion object {
        private val TAG = Log.tag(PinataGateway::class.java)
    }
}
