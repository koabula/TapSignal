package org.thoughtcrime.securesms.tap.provider.cos.utils.auth

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.tencent.TencentSigner
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

/**
 * 腾讯云CAM子用户管理器
 * 使用腾讯云CAM API管理子用户和访问密钥
 */
class TencentSubUserManager(
    private val config: CosConfig,
    private val context: Context
) : CosSubUserManager {
    private val TAG = Log.tag(TencentSubUserManager::class.java)
    private val okHttpClient = OkHttpClient()
    private val service = "cam"
    private val host = "cam.tencentcloudapi.com"
    private val version = "2019-01-16"
    
    override fun createSubUser(
        userName: String,
        directoryPath: String,
        permissions: CosPermission
    ): CosSubUserCredential {
        Log.d(TAG, "开始创建腾讯云CAM子用户: $userName")

        try {
            // 1. 创建CAM子用户
            val userUin = createCamUser(userName)

            // 2. 创建访问密钥（使用UIN而不是用户名）
            val accessKey = createAccessKey(userUin)

            // 3. 使用自定义策略并传递UIN（修复关键问题）
            attachCustomPolicy(userName, userUin, permissions)

            // 4. 自定义策略已包含所有必要权限
            Log.i(TAG, "使用自定义CAM策略，支持跨存储桶访问")

            Log.i(TAG, "=== 腾讯云CAM子用户创建完成 ===")
            Log.i(TAG, "  用户名: $userName")
            Log.i(TAG, "  用户UIN: $userUin")
            Log.i(TAG, "  AccessKeyId: ${accessKey.accessKeyId}")
            Log.i(TAG, "  允许目录: $directoryPath")
            Log.i(TAG, "  权限类型: $permissions")
            Log.i(TAG, "=== 子用户创建总结结束 ===")

            return CosSubUserCredential(
                userName = userName,
                accessKeyId = accessKey.accessKeyId,
                secretAccessKey = accessKey.secretAccessKey,
                allowedDirectory = directoryPath,
                permissions = permissions,
                createdTime = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "创建腾讯云CAM子用户失败: $userName", e)
            // 清理可能已创建的资源
            try {
                deleteSubUser(userName)
            } catch (cleanupException: Exception) {
                Log.w(TAG, "清理失败的用户时出错", cleanupException)
            }
            throw CosSubUserException("创建腾讯云CAM子用户失败: ${e.message}", e)
        }
    }
    
    override fun deleteSubUser(userName: String): Boolean {
        return try {
            Log.d(TAG, "开始删除腾讯云CAM子用户: $userName")

            // 1. 删除所有访问密钥
            val accessKeys = listUserAccessKeys(userName)
            accessKeys.forEach { accessKeyId ->
                deleteAccessKey(userName, accessKeyId)
            }

            // 2. 分离所有策略
            detachAllUserPolicies(userName)

            // 3. 删除用户
            deleteCamUser(userName)

            Log.i(TAG, "腾讯云CAM子用户删除成功: $userName")
            true

        } catch (e: Exception) {
            Log.e(TAG, "删除腾讯云CAM子用户失败: $userName", e)
            false
        }
    }
    
    override fun createAccessKeyInternal(userIdentifier: Any): CosAccessKey {
        val userUin = userIdentifier as? Long ?: throw CosSubUserException("腾讯云需要Long类型的UIN作为用户标识符")
        return createAccessKey(userUin)
    }

    /**
     * 使用用户UIN创建访问密钥
     */
    private fun createAccessKey(userUin: Long): CosAccessKey {
        val timestamp = System.currentTimeMillis() / 1000
        val action = "CreateAccessKey"

        val requestBody = JSONObject().apply {
            put("TargetUin", userUin) // 使用Long类型的UIN
        }.toString()

        val authorization = TencentSigner.buildTC3AuthorizationHeader(
            secretId = config.secretId,
            secretKey = config.secretKey,
            service = service,
            region = config.region,
            action = action,
            timestamp = timestamp,
            payload = requestBody,
            host = host
        )

        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
            .header("Host", host)
            .header("Authorization", authorization)
            .header("X-TC-Action", action)
            .header("X-TC-Version", version)
            .header("X-TC-Region", config.region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("创建访问密钥失败: ${resp.code} - $errorBody")
            }

            val responseBody = resp.body?.string() ?: throw CosSubUserException("空的CAM响应")
            return parseCreateAccessKeyResponse(responseBody)
        }
    }
    
    override fun deleteAccessKey(userName: String, accessKeyId: String): Boolean {
        return try {
            // 先获取用户UIN
            val userUin = getUserUinByName(userName)
            if (userUin == null) {
                Log.w(TAG, "无法获取用户UIN，跳过删除访问密钥: $userName")
                return false
            }

            val timestamp = System.currentTimeMillis() / 1000
            val action = "DeleteAccessKey"

            val requestBody = JSONObject().apply {
                put("AccessKeyId", accessKeyId)
                put("TargetUin", userUin)  // 修复：使用Long类型的UIN
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                val success = resp.isSuccessful
                if (success) {
                    Log.d(TAG, "删除访问密钥成功: $accessKeyId (UIN:$userUin)")
                } else {
                    Log.w(TAG, "删除访问密钥失败: $accessKeyId (UIN:$userUin), code=${resp.code}")
                }
                success
            }

        } catch (e: Exception) {
            Log.e(TAG, "删除访问密钥失败: $accessKeyId", e)
            false
        }
    }
    
    override fun listSubUsers(): List<CosSubUserInfo> {
        return try {
            Log.d(TAG, "开始查询腾讯云CAM子用户列表")

            val timestamp = System.currentTimeMillis() / 1000
            val action = "ListUsers"

            val requestBody = JSONObject().apply {
                // 可以添加Marker和MaxItems参数来分页，这里暂时获取默认数量
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "查询子用户列表失败: ${resp.code}")
                    return emptyList()
                }

                val responseBody = resp.body?.string() ?: return emptyList()
                val json = JSONObject(responseBody)
                val response = json.getJSONObject("Response")

                if (response.has("Data")) {
                    val users = response.getJSONArray("Data")
                    val userInfoList = mutableListOf<CosSubUserInfo>()

                    for (i in 0 until users.length()) {
                        val user = users.getJSONObject(i)
                        val userName = user.optString("Name", "")
                        val uin = user.optLong("Uin", 0L)
                        val createTimeStr = user.optString("CreateTime", "")
                        val lastLoginTimeStr = user.optString("LastLoginTime", "")

                        if (userName.isNotEmpty() && uin > 0) {
                            val createTime = parseTimestamp(createTimeStr)
                            val lastActivity = parseTimestamp(lastLoginTimeStr)
                            
                            // 获取用户的访问密钥列表
                            val accessKeys = listUserAccessKeys(userName)

                            userInfoList.add(CosSubUserInfo(
                                userName = userName,
                                userId = uin.toString(),
                                createDate = createTime,
                                lastActivity = lastActivity,
                                accessKeys = accessKeys
                            ))
                        }
                    }

                    Log.i(TAG, "查询到${userInfoList.size}个腾讯云CAM子用户")
                    return userInfoList
                }

                Log.i(TAG, "未查询到任何腾讯云CAM子用户")
                return emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询腾讯云CAM子用户列表异常", e)
            emptyList()
        }
    }

    override fun cleanupExpiredUsers(maxAgeHours: Int): Int {
        return try {
            Log.d(TAG, "开始清理过期的腾讯云CAM子用户，最大存活时间: ${maxAgeHours}小时")

            val currentTime = System.currentTimeMillis()
            val maxAgeMillis = maxAgeHours * 60 * 60 * 1000L
            val expiredUsers = mutableListOf<String>()

            // 获取所有子用户
            val allUsers = listSubUsers()
            
            allUsers.forEach { userInfo ->
                val userAge = currentTime - userInfo.createDate
                val isExpired = userAge > maxAgeMillis
                
                // 检查是否有最后活动时间记录
                val lastActivityAge = userInfo.lastActivity?.let { currentTime - it }
                val hasRecentActivity = lastActivityAge?.let { it < maxAgeMillis } ?: false
                
                Log.d(TAG, "用户: ${userInfo.userName}, " +
                        "创建时间: ${userAge / (60 * 60 * 1000)}小时前, " +
                        "最后活动: ${lastActivityAge?.let { "${it / (60 * 60 * 1000)}小时前" } ?: "无记录"}, " +
                        "是否过期: $isExpired, " +
                        "有最近活动: $hasRecentActivity")

                // 如果用户过期且没有最近活动，则标记为待删除
                if (isExpired && !hasRecentActivity) {
                    expiredUsers.add(userInfo.userName)
                }
            }

            Log.i(TAG, "发现${expiredUsers.size}个过期的腾讯云CAM子用户")

            // 删除过期的用户
            var deletedCount = 0
            expiredUsers.forEach { userName ->
                try {
                    Log.d(TAG, "删除过期用户: $userName")
                    if (deleteSubUser(userName)) {
                        deletedCount++
                        Log.i(TAG, "过期用户删除成功: $userName")
                    } else {
                        Log.w(TAG, "过期用户删除失败: $userName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除过期用户时发生异常: $userName", e)
                }
            }

            Log.i(TAG, "腾讯云CAM子用户清理完成，已删除${deletedCount}个过期用户")
            deletedCount

        } catch (e: Exception) {
            Log.e(TAG, "清理过期腾讯云CAM子用户异常", e)
            0
        }
    }

    // 私有辅助方法

    /**
     * 创建CAM用户并返回用户UIN
     */
    private fun createCamUser(userName: String): Long {
        val timestamp = System.currentTimeMillis() / 1000
        val action = "AddUser"

        val requestBody = JSONObject().apply {
            put("Name", userName)
            put("Remark", "Signal COS子用户 - ${System.currentTimeMillis()}")
            put("ConsoleLogin", 0) // 不允许控制台登录
            put("UseApi", 1) // 允许API访问
            put("Password", "") // 不设置密码
            put("NeedResetPassword", 0) // 不需要重置密码
        }.toString()

        val authorization = TencentSigner.buildTC3AuthorizationHeader(
            secretId = config.secretId,
            secretKey = config.secretKey,
            service = service,
            region = config.region,
            action = action,
            timestamp = timestamp,
            payload = requestBody,
            host = host
        )

        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
            .header("Host", host)
            .header("Authorization", authorization)
            .header("X-TC-Action", action)
            .header("X-TC-Version", version)
            .header("X-TC-Region", config.region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("创建CAM用户失败: ${resp.code} - $errorBody")
            }

            val responseBody = resp.body?.string() ?: throw CosSubUserException("空的CAM响应")
            return parseAddUserResponse(responseBody)
        }
    }

    /**
     * 删除CAM用户
     */
    private fun deleteCamUser(userName: String) {
        val timestamp = System.currentTimeMillis() / 1000
        val action = "DeleteUser"

        val requestBody = JSONObject().apply {
            put("Name", userName)
            put("Force", 1) // 强制删除
        }.toString()

        val authorization = TencentSigner.buildTC3AuthorizationHeader(
            secretId = config.secretId,
            secretKey = config.secretKey,
            service = service,
            region = config.region,
            action = action,
            timestamp = timestamp,
            payload = requestBody,
            host = host
        )

        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
            .header("Host", host)
            .header("Authorization", authorization)
            .header("X-TC-Action", action)
            .header("X-TC-Version", version)
            .header("X-TC-Region", config.region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("删除CAM用户失败: ${resp.code} - $errorBody")
            }
        }
    }

    /**
     * 附加符合腾讯云标准的CAM策略
     */
    private fun attachCustomPolicy(userName: String, userUin: Long, permissions: CosPermission) {
        Log.d(TAG, "附加腾讯云标准CAM策略: userName=$userName, userUin=$userUin, permissions=$permissions")

        // 创建符合腾讯云标准的策略，使用更易识别的策略名称
        val policyName = "Signal-COS-Policy-${userName}-${System.currentTimeMillis()}"
        val policyDocument = buildStandardTencentCamPolicy()

        try {
            // 1. 创建策略并获取策略ID
            val policyId = createCustomPolicyAndGetId(policyName, policyDocument)

            // 2. 等待策略创建完成
            Thread.sleep(1000)

            // 3. 附加策略到用户（使用UIN和PolicyId）
            attachPolicyToUser(userUin, policyId)

            // 4. 验证策略是否生效（使用UIN和PolicyId）
            verifyPolicyAttachment(userUin, policyId, policyName)

            Log.i(TAG, "腾讯云标准CAM策略附加成功: $policyName (ID:$policyId)")

        } catch (e: Exception) {
            Log.e(TAG, "附加腾讯云标准CAM策略失败", e)
            // 尝试清理已创建的策略
            try {
                deleteCustomPolicy(policyName)
            } catch (cleanupException: Exception) {
                Log.w(TAG, "清理失败的策略时出错", cleanupException)
            }
            throw CosSubUserException("附加腾讯云标准CAM策略失败: ${e.message}", e)
        }
    }

    /**
     * 构建符合腾讯云标准的CAM策略
     */
    private fun buildStandardTencentCamPolicy(): String {
        val actions = listOf(
            "cos:GetBucket",
            "cos:GetBucketObjectVersions", 
            "cos:GetBucketIntelligentTiering",
            "cos:HeadBucket",
            "cos:ListMultipartUploads",
            "cos:ListParts",
            "cos:GetObject",
            "cos:HeadObject",
            "cos:OptionsObject"
        )

        // 提取AppID
        val appId = extractAppId(config.bucketName)
        if (appId.isEmpty()) {
            throw CosSubUserException("无法从存储桶名称中提取AppID: ${config.bucketName}")
        }

        // 腾讯云标准Resource格式：qcs::cos:region:uid/appid:bucket-name/path
        val resourceArn = "qcs::cos:${config.region}:uid/${appId}:${config.bucketName}/*"

        val statement = JSONObject().apply {
            put("effect", "allow")  // 腾讯云标准：小写
            put("action", JSONArray().apply {
                actions.forEach { put(it) }
            })
            put("resource", JSONArray().apply {
                put(resourceArn)
            })
        }

        val policy = JSONObject().apply {
            put("version", "2.0")
            put("statement", JSONArray().apply {
                put(statement)
            })
        }

        val policyString = policy.toString()
        Log.i(TAG, "生成的腾讯云标准CAM策略: $policyString")

        return policyString
    }

    /**
     * 创建自定义策略并返回策略ID
     */
    private fun createCustomPolicyAndGetId(policyName: String, policyDocument: String): Long {
        Log.i(TAG, "创建CAM策略: $policyName")

        val timestamp = System.currentTimeMillis() / 1000
        val action = "CreatePolicy"

        val requestBody = JSONObject().apply {
            put("PolicyName", policyName)
            put("PolicyDocument", policyDocument)
            put("Description", "Signal COS access policy - Standard Tencent CAM format")
        }.toString()

        val authorization = TencentSigner.buildTC3AuthorizationHeader(
            secretId = config.secretId,
            secretKey = config.secretKey,
            service = service,
            region = config.region,
            action = action,
            timestamp = timestamp,
            payload = requestBody,
            host = host
        )

        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
            .header("Host", host)
            .header("Authorization", authorization)
            .header("X-TC-Action", action)
            .header("X-TC-Version", version)
            .header("X-TC-Region", config.region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val responseBody = resp.body?.string() ?: "No response body"

            if (!resp.isSuccessful) {
                Log.e(TAG, "创建CAM策略失败: ${resp.code} - $responseBody")
                throw CosSubUserException("创建自定义策略失败: ${resp.code} - $responseBody")
            }

            // 解析响应获取策略ID
            val policyId = parseCreatePolicyResponse(responseBody)
            Log.i(TAG, "CAM策略创建成功: $policyName (ID:$policyId)")
            return policyId
        }
    }

    /**
     * 解析CreatePolicy响应获取策略ID
     */
    private fun parseCreatePolicyResponse(jsonResponse: String): Long {
        try {
            val json = JSONObject(jsonResponse)
            val response = json.getJSONObject("Response")

            // 检查是否有错误
            if (response.has("Error")) {
                val error = response.getJSONObject("Error")
                val errorCode = error.optString("Code", "未知错误码")
                val errorMessage = error.optString("Message", "未知错误")
                throw CosSubUserException("CAM API错误: $errorCode - $errorMessage")
            }

            // 获取策略ID
            val policyId = response.getLong("PolicyId")
            Log.d(TAG, "解析策略ID成功: $policyId")
            return policyId

        } catch (e: Exception) {
            when (e) {
                is CosSubUserException -> throw e
                else -> throw CosSubUserException("解析CreatePolicy响应失败: ${e.message}")
            }
        }
    }

    /**
     * 附加策略到用户
     */
    private fun attachPolicyToUser(userUin: Long, policyId: Long) {
        Log.i(TAG, "附加CAM策略到用户: userUin=$userUin, policyId=$policyId")

        val timestamp = System.currentTimeMillis() / 1000
        val action = "AttachUserPolicy"

        val requestBody = JSONObject().apply {
            put("AttachUin", userUin)
            put("PolicyId", policyId)
        }.toString()

        val authorization = TencentSigner.buildTC3AuthorizationHeader(
            secretId = config.secretId,
            secretKey = config.secretKey,
            service = service,
            region = config.region,
            action = action,
            timestamp = timestamp,
            payload = requestBody,
            host = host
        )

        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
            .header("Host", host)
            .header("Authorization", authorization)
            .header("X-TC-Action", action)
            .header("X-TC-Version", version)
            .header("X-TC-Region", config.region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("Content-Type", "application/json; charset=utf-8")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val responseBody = resp.body?.string() ?: "No response body"

            if (!resp.isSuccessful) {
                Log.e(TAG, "附加CAM策略到用户失败: ${resp.code} - $responseBody")
                throw CosSubUserException("附加策略到用户失败: ${resp.code} - $responseBody")
            }
            Log.i(TAG, "CAM策略附加到用户成功: PolicyId:$policyId -> UIN:$userUin")
        }
    }

    /**
     * 验证策略是否成功附加到用户
     */
    private fun verifyPolicyAttachment(userUin: Long, policyId: Long, policyName: String) {
        Log.i(TAG, "验证策略附加状态: userUin=$userUin, policyId=$policyId, policyName=$policyName")

        try {
            // 等待策略生效
            Thread.sleep(3000)

            // 调用ListAttachedUserPolicies验证策略是否附加成功
            val timestamp = System.currentTimeMillis() / 1000
            val action = "ListAttachedUserPolicies"

            val requestBody = JSONObject().apply {
                put("TargetUin", userUin)
                put("Page", 1)
                put("Rp", 200)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string() ?: "No response body"

                if (resp.isSuccessful) {
                    // 解析响应检查策略是否在列表中
                    val json = JSONObject(responseBody)
                    val response = json.getJSONObject("Response")
                    if (response.has("List")) {
                        val policies = response.getJSONArray("List")
                        var foundById = false

                        for (i in 0 until policies.length()) {
                            val policy = policies.getJSONObject(i)
                            val currentPolicyId = policy.optLong("PolicyId", -1)

                            if (currentPolicyId == policyId) {
                                foundById = true
                                break
                            }
                        }

                        if (foundById) {
                            Log.i(TAG, "策略附加验证成功: $policyName (ID:$policyId) 已正确关联到用户UIN:$userUin")
                        } else {
                            Log.w(TAG, "策略附加验证失败: 策略 $policyName (ID:$policyId) 未在用户UIN:$userUin 的策略列表中找到")
                        }
                    }
                } else {
                    Log.e(TAG, "策略验证请求失败: ${resp.code} - $responseBody")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "策略附加验证异常（不影响主流程）", e)
        }
    }

    /**
     * 从bucket名称中提取AppID
     */
    private fun extractAppId(bucketName: String): String {
        try {
            // 腾讯云存储桶命名格式：<bucket-name>-<appid>
            val parts = bucketName.split("-")
            if (parts.size >= 2) {
                val lastPart = parts.last()
                // 验证AppID格式：应该是10位数字
                if (lastPart.matches(Regex("\\d{10}"))) {
                    Log.d(TAG, "成功提取AppID: $lastPart from bucket: $bucketName")
                    return lastPart
                }
            }

            Log.w(TAG, "无法从存储桶名称提取AppID: $bucketName, parts: $parts")
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "提取AppID异常: bucketName=$bucketName", e)
            return ""
        }
    }

    /**
     * 解析创建用户响应，获取用户UIN
     */
    private fun parseAddUserResponse(jsonResponse: String): Long {
        try {
            val json = JSONObject(jsonResponse)
            val response = json.getJSONObject("Response")

            // 检查是否有错误
            if (response.has("Error")) {
                val error = response.getJSONObject("Error")
                val errorCode = error.optString("Code", "未知错误码")
                val errorMessage = error.optString("Message", "未知错误")
                throw CosSubUserException("CAM API错误: $errorCode - $errorMessage")
            }

            // 获取用户UIN
            val userUin = response.getLong("Uin")
            Log.d(TAG, "创建用户成功，获得UIN: $userUin")
            return userUin

        } catch (e: Exception) {
            when (e) {
                is CosSubUserException -> throw e
                else -> throw CosSubUserException("解析AddUser响应失败: ${e.message}")
            }
        }
    }

    /**
     * 解析创建访问密钥响应
     */
    private fun parseCreateAccessKeyResponse(jsonResponse: String): CosAccessKey {
        try {
            val json = JSONObject(jsonResponse)
            val response = json.getJSONObject("Response")

            // 检查是否有错误
            if (response.has("Error")) {
                val error = response.getJSONObject("Error")
                val errorCode = error.optString("Code", "未知错误码")
                val errorMessage = error.optString("Message", "未知错误")
                throw CosSubUserException("CAM API错误: $errorCode - $errorMessage")
            }

            val accessKey = response.getJSONObject("AccessKey")
            val accessKeyId = accessKey.getString("AccessKeyId")
            val secretAccessKey = accessKey.getString("SecretAccessKey")
            val status = accessKey.optString("Status", "Active")

            return CosAccessKey(accessKeyId, secretAccessKey, status)

        } catch (e: Exception) {
            when (e) {
                is CosSubUserException -> throw e
                else -> throw CosSubUserException("解析CAM响应失败: ${e.message}")
            }
        }
    }

    /**
     * 列出用户访问密钥
     */
    private fun listUserAccessKeys(userName: String): List<String> {
        try {
            // 先获取用户UIN
            val userUin = getUserUinByName(userName)
            if (userUin == null) {
                Log.w(TAG, "无法获取用户UIN，跳过列出访问密钥: $userName")
                return emptyList()
            }

            val timestamp = System.currentTimeMillis() / 1000
            val action = "ListAccessKeys"

            val requestBody = JSONObject().apply {
                put("TargetUin", userUin)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "列出用户访问密钥失败: ${resp.code}")
                    return emptyList()
                }

                val responseBody = resp.body?.string() ?: return emptyList()
                val json = JSONObject(responseBody)
                val response = json.getJSONObject("Response")

                if (response.has("AccessKeys")) {
                    val accessKeys = response.getJSONArray("AccessKeys")
                    val accessKeyIds = mutableListOf<String>()
                    for (i in 0 until accessKeys.length()) {
                        val accessKey = accessKeys.getJSONObject(i)
                        val accessKeyId = accessKey.optString("AccessKeyId")
                        if (accessKeyId.isNotEmpty()) {
                            accessKeyIds.add(accessKeyId)
                        }
                    }
                    return accessKeyIds
                }

                return emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出用户访问密钥异常: $userName", e)
            return emptyList()
        }
    }

    /**
     * 分离所有用户策略
     */
    private fun detachAllUserPolicies(userName: String) {
        try {
            Log.d(TAG, "开始分离用户所有策略: $userName")

            // 1. 先获取用户UIN
            val userUin = getUserUinByName(userName)
            if (userUin == null) {
                Log.w(TAG, "无法获取用户UIN，跳过策略分离: $userName")
                return
            }

            // 2. 列出用户附加的所有策略（获取PolicyId）
            val attachedPolicies = listAttachedUserPoliciesWithId(userUin)

            // 3. 逐个分离策略
            attachedPolicies.forEach { (policyId, policyName) ->
                Log.d(TAG, "分离策略: $policyName (ID:$policyId)")
                detachUserPolicy(userUin, policyId)
            }

            Log.i(TAG, "用户策略分离完成: $userName (UIN:$userUin), 共分离${attachedPolicies.size}个策略")

        } catch (e: Exception) {
            Log.e(TAG, "分离用户策略失败: $userName", e)
        }
    }

    /**
     * 根据用户名获取用户UIN
     */
    private fun getUserUinByName(userName: String): Long? {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val action = "GetUser"

            val requestBody = JSONObject().apply {
                put("Name", userName)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "获取用户信息失败: ${resp.code}")
                    return null
                }

                val responseBody = resp.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val response = json.getJSONObject("Response")

                if (response.has("Uin")) {
                    val userUin = response.getLong("Uin")
                    Log.d(TAG, "获取用户UIN成功: $userName -> $userUin")
                    return userUin
                }

                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户UIN异常: $userName", e)
            return null
        }
    }

    /**
     * 列出用户附加的策略（包含PolicyId）
     */
    private fun listAttachedUserPoliciesWithId(userUin: Long): List<Pair<Long, String>> {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val action = "ListAttachedUserPolicies"

            val requestBody = JSONObject().apply {
                put("TargetUin", userUin)
                put("Page", 1)
                put("Rp", 200)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "列出用户策略失败: ${resp.code}")
                    return emptyList()
                }

                val responseBody = resp.body?.string() ?: return emptyList()
                val json = JSONObject(responseBody)
                val response = json.getJSONObject("Response")

                if (response.has("List")) {
                    val policies = response.getJSONArray("List")
                    val policyList = mutableListOf<Pair<Long, String>>()
                    for (i in 0 until policies.length()) {
                        val policy = policies.getJSONObject(i)
                        val policyId = policy.optLong("PolicyId", -1)
                        val policyName = policy.optString("PolicyName")
                        if (policyId != -1L && policyName.isNotEmpty()) {
                            policyList.add(Pair(policyId, policyName))
                        }
                    }
                    return policyList
                }

                return emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出用户策略异常: userUin=$userUin", e)
            return emptyList()
        }
    }

    /**
     * 分离用户策略
     */
    private fun detachUserPolicy(userUin: Long, policyId: Long) {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val action = "DetachUserPolicy"

            val requestBody = JSONObject().apply {
                put("DetachUin", userUin)
                put("PolicyId", policyId)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "策略分离成功: UIN:$userUin -> PolicyId:$policyId")
                } else {
                    val responseBody = resp.body?.string() ?: "No response body"
                    Log.w(TAG, "策略分离失败: UIN:$userUin -> PolicyId:$policyId, code=${resp.code}, response=$responseBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "分离策略异常: UIN:$userUin -> PolicyId:$policyId", e)
        }
    }

    /**
     * 删除自定义策略
     */
    private fun deleteCustomPolicy(policyName: String) {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val action = "DeletePolicy"

            val requestBody = JSONObject().apply {
                put("PolicyName", policyName)
            }.toString()

            val authorization = TencentSigner.buildTC3AuthorizationHeader(
                secretId = config.secretId,
                secretKey = config.secretKey,
                service = service,
                region = config.region,
                action = action,
                timestamp = timestamp,
                payload = requestBody,
                host = host
            )

            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), requestBody))
                .header("Host", host)
                .header("Authorization", authorization)
                .header("X-TC-Action", action)
                .header("X-TC-Version", version)
                .header("X-TC-Region", config.region)
                .header("X-TC-Timestamp", timestamp.toString())
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "策略删除成功: $policyName")
                } else {
                    Log.w(TAG, "策略删除失败: $policyName, code=${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除策略异常: $policyName", e)
        }
    }

    /**
     * 解析时间戳字符串为毫秒
     */
    private fun parseTimestamp(timestampStr: String): Long {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.parse(timestampStr)?.time ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "解析时间戳失败: $timestampStr", e)
            return 0L
        }
    }
} 