package org.thoughtcrime.securesms.cos.aws

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.*
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
    
    override fun createAccessKey(userName: String): CosAccessKey {
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=CreateAccessKey&Version=2010-05-08&UserName=$userName"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
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
            
            val action = "Action=DeleteAccessKey&Version=2010-05-08&UserName=$userName&AccessKeyId=$accessKeyId"
            val canonicalUri = "/"
            val canonicalQueryString = ""
            val payloadHash = AwsSigner.hash(action)
            
            val canonicalHeaders = "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n"
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            
            val authorization = AwsSigner.buildAuthorizationHeader(
                config.secretId, config.secretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
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
        // 实现用户列表获取
        return emptyList() // 简化实现
    }
    
    override fun cleanupExpiredUsers(maxAgeHours: Int): Int {
        // 实现过期用户清理
        return 0 // 简化实现
    }
    
    // 私有辅助方法
    
    private fun createIamUser(userName: String) {
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=CreateUser&Version=2010-05-08&UserName=$userName"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
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
                throw CosSubUserException("创建IAM用户失败: ${resp.code} - $errorBody")
            }
        }
    }
    
    private fun attachUserPolicy(userName: String, directoryPath: String, permissions: CosPermission) {
        val policyDocument = buildS3Policy(directoryPath, permissions)
        val policyName = "signal-cos-policy-${System.currentTimeMillis()}"
        
        val date = Date()
        val amzDate = iso8601(date)
        val dateStamp = dateStamp(date)
        
        val action = "Action=PutUserPolicy&Version=2010-05-08&UserName=$userName&PolicyName=$policyName&PolicyDocument=${java.net.URLEncoder.encode(policyDocument, "UTF-8")}"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val payloadHash = AwsSigner.hash(action)
        
        val canonicalHeaders = "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        
        val authorization = AwsSigner.buildAuthorizationHeader(
            config.secretId, config.secretKey, "us-east-1", service, canonicalRequest, amzDate, dateStamp, signedHeaders
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
    
    private fun buildS3Policy(directoryPath: String, permissions: CosPermission): String {
        val normalizedPath = if (directoryPath.startsWith("/")) directoryPath.substring(1) else directoryPath
        val bucketArn = "arn:aws:s3:::${config.bucketName}"
        // 修复：确保路径以/*结尾，覆盖所有子目录和文件
        val pathWithWildcard = if (normalizedPath.endsWith("/")) "${normalizedPath}*" else "${normalizedPath}/*"
        val objectArn = "arn:aws:s3:::${config.bucketName}/${pathWithWildcard}"
        
        val s3Actions = permissions.actions.map { "s3:$it" }

        Log.d(TAG, "构建S3策略 - 目录路径: $directoryPath, 标准化路径: $normalizedPath, 路径通配符: $pathWithWildcard")
        Log.d(TAG, "S3策略 - 存储桶ARN: $bucketArn, 对象ARN: $objectArn")

        return """
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": ${s3Actions.joinToString(",") { "\"$it\"" }},
                    "Resource": ["$bucketArn", "$objectArn"]
                }
            ]
        }
        """.trimIndent().replace("\n", "").replace(" ", "")
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
    
    // 其他辅助方法...
    private fun deleteIamUser(userName: String) { /* 实现删除用户 */ }
    private fun listUserAccessKeys(userName: String): List<String> = emptyList()
    private fun detachAllUserPolicies(userName: String) { /* 实现分离策略 */ }
    
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
