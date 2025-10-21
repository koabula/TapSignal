package org.thoughtcrime.securesms.tap.provider.cos.utils.auth

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.utils.common.CosConfig
import org.thoughtcrime.securesms.tap.provider.cos.utils.client.aws.AwsSigner
import java.text.SimpleDateFormat
import java.util.*

/**
 * AWS IAM子用户管理器
 * 使用AWS IAM API管理子用户和访问密钥
 */
class AwsSubUserManager(private val config: CosConfig) : CosSubUserManager {
    private val TAG = Log.tag(AwsSubUserManager::class.java)
    private val okHttpClient = OkHttpClient()
    private val service = "iam"
    private val host = "iam.amazonaws.com"
    
    // 清理后的凭证，避免签名错误（与AwsS3Client保持一致）
    private val cleanedSecretId = config.secretId.trim()
    private val cleanedSecretKey = config.secretKey.trim()
    
    override fun createSubUser(
        userName: String, 
        directoryPath: String, 
        permissions: CosPermission
    ): CosSubUserCredential {
        Log.d(TAG, "开始创建AWS IAM子用户: $userName")
        
        try {
            // 1. 创建IAM用户
            createIamUser(userName)
            
            // 2. 创建访问密钥
            val accessKey = createAccessKey(userName)
            
            // 3. 附加权限策略
            attachUserPolicy(userName, directoryPath, permissions)
            
            Log.i(TAG, "AWS IAM子用户创建成功: $userName")
            
            return CosSubUserCredential(
                userName = userName,
                accessKeyId = accessKey.accessKeyId,
                secretAccessKey = accessKey.secretAccessKey,
                allowedDirectory = directoryPath,
                permissions = permissions,
                createdTime = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "创建AWS IAM子用户失败: $userName", e)
            // 清理可能已创建的资源
            try {
                deleteSubUser(userName)
            } catch (cleanupException: Exception) {
                Log.w(TAG, "清理失败的用户时出错", cleanupException)
            }
            throw CosSubUserException("创建AWS IAM子用户失败: ${e.message}", e)
        }
    }
    
    override fun deleteSubUser(userName: String): Boolean {
        return try {
            Log.d(TAG, "开始删除AWS IAM子用户: $userName")
            
            // 1. 删除所有访问密钥
            val accessKeys = listUserAccessKeys(userName)
            accessKeys.forEach { accessKeyId ->
                deleteAccessKey(userName, accessKeyId)
            }
            
            // 2. 分离所有策略
            detachAllUserPolicies(userName)
            
            // 3. 删除用户
            deleteIamUser(userName)
            
            Log.i(TAG, "AWS IAM子用户删除成功: $userName")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "删除AWS IAM子用户失败: $userName", e)
            false
        }
    }
    
    override fun createAccessKeyInternal(userIdentifier: Any): CosAccessKey {
        val userName = userIdentifier as? String ?: throw CosSubUserException("AWS IAM需要String类型的用户名作为标识符")
        return createAccessKey(userName)
    }
    
    private fun createAccessKey(userName: String): CosAccessKey {
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=CreateAccessKey&Version=2010-05-08&UserName=${urlEncode(userName)}"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )
        
        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()
        
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("创建访问密钥失败: ${resp.code} - $errorBody")
            }
            
            val xml = resp.body?.string() ?: throw CosSubUserException("空的IAM响应")
            return parseCreateAccessKeyResponse(xml)
        }
    }
    
    override fun deleteAccessKey(userName: String, accessKeyId: String): Boolean {
        return try {
            val date = Date()
            val amzDate = iso8601(date)
            val dateStamp = dateStamp(date)
            
            val action = "Action=DeleteAccessKey&Version=2010-05-08&UserName=${urlEncode(userName)}&AccessKeyId=${urlEncode(accessKeyId)}"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val payloadHash = AwsSigner.hash(action)
            
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
            )
            
            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()
            
            okHttpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "删除访问密钥失败: $accessKeyId", e)
            false
        }
    }
    
    override fun listSubUsers(): List<CosSubUserInfo> {
        // 可以调用ListUsers API实现真实功能
        return emptyList()
    }
    
    override fun cleanupExpiredUsers(maxAgeHours: Int): Int {
        // 可以结合ListUsers和DeleteUser API实现真实功能
        return 0
    }
    
    // 私有辅助方法
    
    /**
     * URL编码辅助函数
     * AWS Signature V4要求所有参数都正确URL编码
     */
    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")  // AWS要求空格编码为%20而不是+
            .replace("*", "%2A")  // 编码星号
            .replace("%7E", "~")  // 波浪号不编码
    }
    
    private fun createIamUser(userName: String) {
        Log.d(TAG, "===== AWS IAM创建用户操作 =====")
        Log.d(TAG, "用户名: $userName")
        Log.d(TAG, "使用凭证 - AccessKeyId: ${cleanedSecretId.take(8)}***, SecretKey长度: ${cleanedSecretKey.length}")
        
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=CreateUser&Version=2010-05-08&UserName=${urlEncode(userName)}"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )
        
        Log.d(TAG, "发送IAM CreateUser请求...")
        
        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()
        
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                Log.e(TAG, "✗ 创建IAM用户失败: ${resp.code}")
                Log.e(TAG, "  - 错误响应: $errorBody")
                throw CosSubUserException("创建IAM用户失败: ${resp.code} - $errorBody")
            }
            Log.d(TAG, "✓ 创建IAM用户成功: $userName")
            Log.d(TAG, "===== 操作完成 =====")
        }
    }
    
    private fun attachUserPolicy(userName: String, directoryPath: String, permissions: CosPermission) {
        val policyDocument = buildS3Policy(directoryPath, permissions)
        val policyName = "signal-cos-policy-${System.currentTimeMillis()}"
        
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=PutUserPolicy&Version=2010-05-08&UserName=${urlEncode(userName)}&PolicyName=${urlEncode(policyName)}&PolicyDocument=${urlEncode(policyDocument)}"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )
        
        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()
        
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("附加用户策略失败: ${resp.code} - $errorBody")
            }
        }
    }
    
    /**
     * 构建标准AWS S3 IAM策略
     * 参考TencentSubUserManager.buildStandardTencentCamPolicy的实现，硬编码所有必需权限
     */
    private fun buildS3Policy(directoryPath: String, permissions: CosPermission): String {
        val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
        val bucketArn = "arn:aws:s3:::${config.bucketName}"
        val pathWithWildcard = "${normalizedPath}*"
        val objectArn = "arn:aws:s3:::${config.bucketName}/${pathWithWildcard}"
        
        // 硬编码所有必需的AWS S3权限（参考Tencent的实现方式）
        // Bucket级别操作：ListBucket是列举文件的必需权限
        val bucketActions = listOf("s3:ListBucket")
        
        // Object级别操作：对应Tencent的GetObject, HeadObject等
        val objectActions = listOf(
            "s3:GetObject",
            "s3:HeadObject",
            "s3:GetObjectVersion",
            "s3:ListMultipartUploadParts"
        )
        
        Log.d(TAG, "构建S3策略 - 目录: $directoryPath")
        Log.d(TAG, "  Bucket ARN: $bucketArn")
        Log.d(TAG, "  Object ARN: $objectArn")
        Log.d(TAG, "  Bucket操作: $bucketActions")
        Log.d(TAG, "  Object操作: $objectActions")

        // 构建两个Statement：bucket级别和object级别（参考Tencent的实现）
        val bucketActionsStr = bucketActions.joinToString(",") { "\"$it\"" }
        val objectActionsStr = objectActions.joinToString(",") { "\"$it\"" }
        
        val bucketStatement = """{"Effect":"Allow","Action":[$bucketActionsStr],"Resource":"$bucketArn"}"""
        val objectStatement = """{"Effect":"Allow","Action":[$objectActionsStr],"Resource":"$objectArn"}"""

        return """{"Version":"2012-10-17","Statement":[$bucketStatement,$objectStatement]}"""
    }
    
    private fun parseCreateAccessKeyResponse(xml: String): CosAccessKey {
        // 简化的XML解析，实际应该使用XML解析器
        val accessKeyIdRegex = "<AccessKeyId>([^<]+)</AccessKeyId>".toRegex()
        val secretAccessKeyRegex = "<SecretAccessKey>([^<]+)</SecretAccessKey>".toRegex()
        
        val accessKeyId = accessKeyIdRegex.find(xml)?.groupValues?.get(1)
            ?: throw CosSubUserException("无法解析AccessKeyId")
        val secretAccessKey = secretAccessKeyRegex.find(xml)?.groupValues?.get(1)
            ?: throw CosSubUserException("无法解析SecretAccessKey")
        
        return CosAccessKey(accessKeyId, secretAccessKey)
    }
    
    /**
     * 删除IAM用户
     */
    private fun deleteIamUser(userName: String) {
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=DeleteUser&Version=2010-05-08&UserName=${urlEncode(userName)}"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
        )
        
        val request = Request.Builder()
            .url("https://$host/")
            .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build()
        
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: "No error body"
                throw CosSubUserException("删除IAM用户失败: ${resp.code} - $errorBody")
            }
        }
    }
    
    /**
     * 列出用户访问密钥
     */
    private fun listUserAccessKeys(userName: String): List<String> {
        return try {
            val date = Date()
            val amzDate = iso8601(date)
            val dateStamp = dateStamp(date)
            
            val action = "Action=ListAccessKeys&Version=2010-05-08&UserName=${urlEncode(userName)}"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val payloadHash = AwsSigner.hash(action)
            
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
            )
            
            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()
            
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "列出用户访问密钥失败: ${resp.code}")
                    return emptyList()
                }
                
                val xml = resp.body?.string() ?: return emptyList()
                parseListAccessKeysResponse(xml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出用户访问密钥异常: $userName", e)
            emptyList()
        }
    }
    
    /**
     * 解析ListAccessKeys响应
     */
    private fun parseListAccessKeysResponse(xml: String): List<String> {
        val accessKeyIds = mutableListOf<String>()
        val regex = "<AccessKeyId>([^<]+)</AccessKeyId>".toRegex()
        regex.findAll(xml).forEach { matchResult ->
            accessKeyIds.add(matchResult.groupValues[1])
        }
        return accessKeyIds
    }
    
    /**
     * 分离所有用户策略
     */
    private fun detachAllUserPolicies(userName: String) {
        try {
            Log.d(TAG, "开始分离用户所有策略: $userName")
            
            // 获取用户策略列表
            val policyNames = listUserPolicies(userName)
            
            // 逐个删除策略
            policyNames.forEach { policyName ->
                Log.d(TAG, "删除用户策略: $policyName")
                deleteUserPolicy(userName, policyName)
            }
            
            Log.i(TAG, "用户策略分离完成: $userName, 共删除${policyNames.size}个策略")
            
        } catch (e: Exception) {
            Log.e(TAG, "分离用户策略失败: $userName", e)
        }
    }
    
    /**
     * 列出用户策略
     */
    private fun listUserPolicies(userName: String): List<String> {
        return try {
            val date = Date()
            val amzDate = iso8601(date)
            val dateStamp = dateStamp(date)
            
            val action = "Action=ListUserPolicies&Version=2010-05-08&UserName=${urlEncode(userName)}"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val payloadHash = AwsSigner.hash(action)
            
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
            )
            
            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()
            
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "列出用户策略失败: ${resp.code}")
                    return emptyList()
                }
                
                val xml = resp.body?.string() ?: return emptyList()
                parseListUserPoliciesResponse(xml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "列出用户策略异常: $userName", e)
            emptyList()
        }
    }
    
    /**
     * 解析ListUserPolicies响应
     */
    private fun parseListUserPoliciesResponse(xml: String): List<String> {
        val policyNames = mutableListOf<String>()
        val regex = "<PolicyName>([^<]+)</PolicyName>".toRegex()
        regex.findAll(xml).forEach { matchResult ->
            policyNames.add(matchResult.groupValues[1])
        }
        return policyNames
    }
    
    /**
     * 删除用户策略
     */
    private fun deleteUserPolicy(userName: String, policyName: String) {
        try {
            val date = Date()
            val amzDate = iso8601(date)
            val dateStamp = dateStamp(date)
            
            val action = "Action=DeleteUserPolicy&Version=2010-05-08&UserName=${urlEncode(userName)}&PolicyName=${urlEncode(policyName)}"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val payloadHash = AwsSigner.hash(action)
            
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                cleanedSecretId, cleanedSecretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
            )
            
            val request = Request.Builder()
                .url("https://$host/")
                .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), action))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build()
            
            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "用户策略删除成功: $userName -> $policyName")
                } else {
                    Log.w(TAG, "用户策略删除失败: $userName -> $policyName, code=${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除用户策略异常: $userName -> $policyName", e)
        }
    }
    
    private fun iso8601(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
    
    private fun dateStamp(date: Date): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
} 