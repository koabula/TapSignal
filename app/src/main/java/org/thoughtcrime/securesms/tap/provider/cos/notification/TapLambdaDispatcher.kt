package org.thoughtcrime.securesms.tap.provider.cos.notification

import android.content.Context
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.InvocationType
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.lambda.model.ListFunctionsRequest
import aws.sdk.kotlin.services.lambda.model.LogType
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig

data class LambdaDispatchResult(
    val success: Boolean,
    val statusCode: Int? = null,
    val errorMessage: String? = null,
    val requestId: String? = null
)

interface TapLambdaDispatcher {
    suspend fun dispatch(payload: JSONObject): LambdaDispatchResult
}

object TapLambdaDispatcherFactory {
    fun create(
        context: Context,
        cosConfig: CosConfig,
        notificationConfig: NotificationConfig?
    ): TapLambdaDispatcher? {
        return when (cosConfig.provider) {
            CosConfig.Provider.AWS -> AwsTapLambdaDispatcher(
                cosConfig = cosConfig,
                initialFunctionName = notificationConfig?.pushServiceInfo?.metadata?.get("triggerFunctionName") as? String,
                initialFunctionArn = notificationConfig?.pushServiceInfo?.metadata?.get("triggerFunctionArn") as? String
            )
            CosConfig.Provider.TENCENT -> TencentTapLambdaDispatcher(
                cosConfig = cosConfig,
                initialFunctionName = notificationConfig?.pushServiceInfo?.metadata?.get("triggerFunctionName") as? String
            )
        }
    }
}

private class AwsTapLambdaDispatcher(
    private val cosConfig: CosConfig,
    initialFunctionName: String?,
    private val initialFunctionArn: String?
) : TapLambdaDispatcher {

    companion object {
        private const val TRIGGER_PREFIX = "tap-notification-trigger"
    }

    private val tag = Log.tag(AwsTapLambdaDispatcher::class.java)
    private val mutex = Mutex()
    private var cachedFunctionName: String? = initialFunctionName

    private val lambdaClient: LambdaClient by lazy {
        LambdaClient {
            region = cosConfig.region
            credentialsProvider = object : CredentialsProvider {
                override suspend fun resolve(attributes: Attributes): Credentials {
                    val accessKey = cosConfig.secretId.trim()
                    val secretKey = cosConfig.secretKey.trim()
                    val sessionToken = cosConfig.sessionToken?.trim()
                    return if (sessionToken.isNullOrEmpty()) {
                        Credentials(accessKeyId = accessKey, secretAccessKey = secretKey)
                    } else {
                        Credentials(accessKeyId = accessKey, secretAccessKey = secretKey, sessionToken = sessionToken)
                    }
                }
            }
        }
    }

    override suspend fun dispatch(payload: JSONObject): LambdaDispatchResult {
        return try {
            val functionName = resolveFunctionName() ?: return LambdaDispatchResult(
                success = false,
                errorMessage = "Trigger function未配置"
            )

            val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
            
            val response = lambdaClient.invoke(
                InvokeRequest {
                    this.functionName = functionName
                    this.invocationType = InvocationType.RequestResponse
                    this.logType = LogType.None
                    this.payload = payloadBytes
                }
            )

            val statusCode = response.statusCode ?: 0
            val body = response.payload?.let { String(it, Charsets.UTF_8) }
            val functionError = response.functionError
            val success = functionError.isNullOrEmpty() && statusCode in 200..299

            LambdaDispatchResult(
                success = success,
                statusCode = statusCode,
                errorMessage = functionError ?: body,
                requestId = response.executedVersion
            )
        } catch (e: Exception) {
            Log.e(tag, "Lambda dispatch失败", e)
            LambdaDispatchResult(success = false, errorMessage = e.message)
        }
    }

    private suspend fun resolveFunctionName(): String? {
        cachedFunctionName?.let { return it }
        return mutex.withLock {
            cachedFunctionName?.let { return@withLock it }
            val discovered = discoverFunctionName()
            cachedFunctionName = discovered
            discovered
        }
    }

    private suspend fun discoverFunctionName(): String? {
        if (!initialFunctionArn.isNullOrEmpty()) {
            val name = initialFunctionArn.substringAfterLast(":")
            Log.d(tag, "使用ARN解析触发函数: $name")
            return name
        }

        var marker: String? = null
        do {
            val response = lambdaClient.listFunctions(
                ListFunctionsRequest {
                    this.marker = marker
                }
            )
            val match = response.functions?.firstOrNull { fn ->
                val name = fn.functionName ?: return@firstOrNull false
                name.startsWith(TRIGGER_PREFIX)
            }?.functionName
            if (!match.isNullOrEmpty()) {
                Log.i(tag, "发现触发函数: $match")
                return match
            }
            marker = response.nextMarker
        } while (!marker.isNullOrEmpty())

        Log.w(tag, "未找到触发函数，需重新部署?")
        return null
    }
}

private class TencentTapLambdaDispatcher(
    private val cosConfig: CosConfig,
    initialFunctionName: String?
) : TapLambdaDispatcher {

    companion object {
        private const val HOST = "scf.tencentcloudapi.com"
        private const val API_VERSION = "2018-04-16"
        private const val TRIGGER_PREFIX = "tap-notification-trigger"
    }

    private val tag = Log.tag(TencentTapLambdaDispatcher::class.java)
    private val mutex = Mutex()
    private var cachedFunctionName: String? = initialFunctionName

    override suspend fun dispatch(payload: JSONObject): LambdaDispatchResult {
        return try {
            val functionName = resolveFunctionName() ?: return LambdaDispatchResult(
                success = false,
                errorMessage = "未配置触发函数"
            )

            val params = JSONObject().apply {
                put("FunctionName", functionName)
                put("InvocationType", "RequestResponse")
                put("Namespace", "default")
                put("Event", payload.toString())
            }

            val response = callTencentApi(
                action = "Invoke",
                params = params
            )
            val status = response.optJSONObject("Result")?.optInt("RetCode") ?: 0
            val success = status == 0
            val errorMessage = if (success) null else response.optJSONObject("Result")?.optString("ErrMsg")

            LambdaDispatchResult(
                success = success,
                statusCode = status,
                errorMessage = errorMessage,
                requestId = response.optString("RequestId")
            )
        } catch (e: Exception) {
            Log.e(tag, "SCF dispatch失败", e)
            LambdaDispatchResult(success = false, errorMessage = e.message)
        }
    }

    private suspend fun resolveFunctionName(): String? {
        cachedFunctionName?.let { return it }
        return mutex.withLock {
            cachedFunctionName?.let { return@withLock it }
            val discovered = discoverFunctionName()
            cachedFunctionName = discovered
            discovered
        }
    }

    private suspend fun discoverFunctionName(): String? {
        val params = JSONObject().apply {
            put("Order", "ASC")
            put("Limit", 200)
        }
        val response = callTencentApi(
            action = "ListFunctions",
            params = params
        )
        val functions = response.optJSONArray("Functions") ?: JSONArray()
        for (i in 0 until functions.length()) {
            val obj = functions.optJSONObject(i) ?: continue
            val name = obj.optString("FunctionName")
            if (name.startsWith(TRIGGER_PREFIX)) {
                Log.i(tag, "发现触发函数: $name")
                return name
            }
        }
        Log.w(tag, "未找到触发函数")
        return null
    }

    private suspend fun callTencentApi(
        action: String,
        params: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val timestamp = Instant.now().epochSecond
        val payload = params.toString()
        val signature = generateTencentSignature(action, timestamp, payload)

        val url = URL("https://$HOST/")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Host", HOST)
            setRequestProperty("X-TC-Action", action)
            setRequestProperty("X-TC-Version", API_VERSION)
            setRequestProperty("X-TC-Timestamp", timestamp.toString())
            setRequestProperty("X-TC-Region", cosConfig.region)
            cosConfig.sessionToken?.takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("X-TC-Token", token.trim())
            }
            setRequestProperty("Authorization", signature)
            doOutput = true
            connectTimeout = 30000
            readTimeout = 30000
        }

        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val body = if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val json = JSONObject(body).optJSONObject("Response")
                ?: throw IllegalStateException("无效响应: $body")

            json.optJSONObject("Error")?.let { error ->
                val code = error.optString("Code", "Unknown")
                val message = error.optString("Message", "Unknown")
                throw IllegalStateException("API错误[$code]: $message")
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun generateTencentSignature(
        action: String,
        timestamp: Long,
        payload: String
    ): String {
        val service = HOST.substringBefore(".")
        val date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(timestamp))

        val hashedPayload = sha256Hex(payload)
        val canonicalHeaders = "content-type:application/json\nhost:$HOST\n"
        val signedHeaders = "content-type;host"

        val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"
        val credentialScope = "$date/$service/tc3_request"
        val hashedCanonicalRequest = sha256Hex(canonicalRequest)

        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n$hashedCanonicalRequest"

        val secretKey = cosConfig.secretKey.trim()
        val secretDate = hmacSHA256("TC3$secretKey".toByteArray(), date)
        val secretService = hmacSHA256(secretDate, service)
        val secretSigning = hmacSHA256(secretService, "tc3_request")
        val signature = hmacSHA256Hex(secretSigning, stringToSign)

        return "TC3-HMAC-SHA256 Credential=${cosConfig.secretId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSHA256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun hmacSHA256Hex(key: ByteArray, data: String): String {
        return hmacSHA256(key, data).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
