package org.thoughtcrime.securesms.tapv3.ipfs

import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tapv3.TapV3Constants
import org.thoughtcrime.securesms.tapv3.TapV3Error
import org.thoughtcrime.securesms.tapv3.TapV3Result
import java.io.IOException
import java.util.concurrent.TimeUnit

class Web3StorageGateway(
    private val token: String
) : IpfsGateway {
    
    override val name: String = "Web3.Storage"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    
    private var cachedQuota: Long = -1L
    
    override val quotaRemaining: Long
        get() = cachedQuota
    
    override suspend fun pin(data: ByteArray): TapV3Result<String> {
        return try {
            withTimeout(TapV3Constants.IPFS_UPLOAD_TIMEOUT_MS) {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "data",
                        data.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                
                val request = Request.Builder()
                    .url("https://api.web3.storage/upload")
                    .addHeader("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Web3.Storage pin failed: ${response.code}")
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Web3.Storage upload failed: HTTP ${response.code}"
                    )
                }
                
                val body = response.body?.string()
                if (body == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Empty response from Web3.Storage"
                    )
                }
                
                val cid = extractCidFromWeb3Response(body)
                if (cid == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_UPLOAD_ERROR,
                        "Failed to extract CID from Web3.Storage response"
                    )
                }
                
                Log.d(TAG, "Successfully pinned to Web3.Storage: $cid")
                TapV3Result.Success(cid)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Web3.Storage pin IO error", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Web3.Storage pin error", e)
            TapV3Result.Failure(TapV3Error.IPFS_UPLOAD_ERROR, "Upload error: ${e.message}", e)
        }
    }
    
    override suspend fun get(cid: String): TapV3Result<ByteArray> {
        return try {
            withTimeout(TapV3Constants.IPFS_DOWNLOAD_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url("https://$cid.ipfs.w3s.link/")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Web3.Storage get failed: ${response.code}")
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_DOWNLOAD_ERROR,
                        "Web3.Storage download failed: HTTP ${response.code}"
                    )
                }
                
                val data = response.body?.bytes()
                if (data == null) {
                    return@withTimeout TapV3Result.Failure(
                        TapV3Error.IPFS_DOWNLOAD_ERROR,
                        "Empty response from Web3.Storage"
                    )
                }
                
                Log.d(TAG, "Successfully downloaded from Web3.Storage: $cid (${data.size} bytes)")
                TapV3Result.Success(data)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Web3.Storage get IO error", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Web3.Storage get error", e)
            TapV3Result.Failure(TapV3Error.IPFS_DOWNLOAD_ERROR, "Download error: ${e.message}", e)
        }
    }
    
    override suspend fun unpin(cid: String): TapV3Result<Unit> {
        return try {
            val request = Request.Builder()
                .url("https://api.web3.storage/pins/$cid")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful && response.code != 404) {
                Log.w(TAG, "Web3.Storage unpin failed: ${response.code}")
                return TapV3Result.Failure(
                    TapV3Error.IPFS_UPLOAD_ERROR,
                    "Web3.Storage unpin failed: HTTP ${response.code}"
                )
            }
            
            Log.d(TAG, "Successfully unpinned from Web3.Storage: $cid")
            TapV3Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Web3.Storage unpin error", e)
            TapV3Result.Failure(TapV3Error.IPFS_UPLOAD_ERROR, "Unpin error: ${e.message}", e)
        }
    }
    
    override suspend fun getUsageStats(): TapV3Result<IpfsGateway.UsageStats> {
        return try {
            val request = Request.Builder()
                .url("https://api.web3.storage/user/account")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return TapV3Result.Failure(
                    TapV3Error.NETWORK_ERROR,
                    "Failed to get Web3.Storage usage: HTTP ${response.code}"
                )
            }
            
            val body = response.body?.string()
            if (body == null) {
                return TapV3Result.Failure(
                    TapV3Error.NETWORK_ERROR,
                    "Empty response from Web3.Storage usage API"
                )
            }
            
            val stats = parseWeb3UsageStats(body)
            cachedQuota = stats.remaining
            
            TapV3Result.Success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Web3.Storage usage stats", e)
            TapV3Result.Failure(TapV3Error.NETWORK_ERROR, "Usage stats error: ${e.message}", e)
        }
    }
    
    private fun extractCidFromWeb3Response(json: String): String? {
        return try {
            val cidPattern = """"cid"\s*:\s*"([^"]+)"""".toRegex()
            cidPattern.find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CID from response", e)
            null
        }
    }
    
    private fun parseWeb3UsageStats(json: String): IpfsGateway.UsageStats {
        val usedPattern = """"storageUsed"\s*:\s*(\d+)""".toRegex()
        val used = usedPattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        
        val totalBytes = 5L * 1024 * 1024 * 1024
        
        return IpfsGateway.UsageStats(
            used = used,
            total = totalBytes,
            remaining = (totalBytes - used).coerceAtLeast(0)
        )
    }
    
    companion object {
        private val TAG = Log.tag(Web3StorageGateway::class.java)
    }
}
