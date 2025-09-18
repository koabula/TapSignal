package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 编译验证检查
 * 确保所有COS模块组件都能正确编译和实例化
 */
object CompilationCheck {
    private val TAG = Log.tag(CompilationCheck::class.java)
    
    /**
     * 验证所有COS模块组件是否能正确编译和实例化
     */
    fun verifyCompilation(context: Context): Boolean {
        Log.i(TAG, "开始验证COS模块编译状态...")
        
        return try {
            // 1. 验证核心接口和数据类
            verifyDataClasses()
            
            // 2. 验证客户端实现
            verifyClientImplementations()
            
            // 3. 验证工厂类
            verifyFactoryClasses(context)
            
            // 4. 验证签名器和解析器
            verifyUtilityClasses()
            
            // 5. 验证测试工具
            verifyTestTools(context)
            
            Log.i(TAG, "✅ 所有COS模块组件编译验证通过")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ COS模块编译验证失败", e)
            false
        }
    }
    
    private fun verifyDataClasses() {
        Log.d(TAG, "验证数据类...")
        
        // 验证CosConfig
        val config = CosConfig(
            provider = CosConfig.Provider.AWS,
            secretId = "test",
            secretKey = "test",
            region = "us-east-1",
            bucketName = "test-bucket",
            sessionToken = "test-token"
        )
        
        // 验证CosAccessToken
        val token = CosAccessToken(
            accessKeyId = "test",
            secretAccessKey = "test",
            sessionToken = "test",
            expireTime = System.currentTimeMillis() + 3600000
        )
        
        // 验证CosFileInfo
        val fileInfo = CosFileInfo(
            key = "test.txt",
            size = 1024,
            lastModified = System.currentTimeMillis()
        )
        
        Log.d(TAG, "数据类验证通过")
    }
    
    private fun verifyClientImplementations() {
        Log.d(TAG, "验证客户端实现...")
        
        val awsConfig = CosConfig(
            provider = CosConfig.Provider.AWS,
            secretId = "test",
            secretKey = "test",
            region = "us-east-1",
            bucketName = "test-bucket"
        )
        
        val tencentConfig = CosConfig(
            provider = CosConfig.Provider.TENCENT,
            secretId = "test",
            secretKey = "test",
            region = "ap-beijing",
            bucketName = "test-bucket-123456789"
        )
        
        // 验证AWS S3客户端可以实例化
        val awsClient = AwsS3Client(awsConfig)
        
        // 验证腾讯云COS客户端可以实例化
        val tencentClient = TencentCosClient(tencentConfig, null)
        
        Log.d(TAG, "客户端实现验证通过")
    }
    
    private fun verifyFactoryClasses(context: Context) {
        Log.d(TAG, "验证工厂类...")
        
        // 验证CosClientFactory
        val config = CosConfig(
            provider = CosConfig.Provider.AWS,
            secretId = "test",
            secretKey = "test",
            region = "us-east-1",
            bucketName = "test-bucket"
        )
        
        val client = CosClientFactory.createClient(config, context)
        
        // 验证临时凭证客户端创建
        val tempClient = CosClientFactory.createClientWithToken(
            provider = "AWS",
            region = "us-east-1",
            bucketName = "test-bucket",
            accessKeyId = "test",
            secretAccessKey = "test",
            sessionToken = "test"
        )
        
        Log.d(TAG, "工厂类验证通过")
    }
    
    private fun verifyUtilityClasses() {
        Log.d(TAG, "验证工具类...")
        
        // 验证AwsSigner可以调用
        val hash = org.thoughtcrime.securesms.tap.provider.cos.cos.aws.AwsSigner.hash("test")
        
        // 验证TencentSigner可以调用
        val authorization = org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSigner.buildTC3AuthorizationHeader(
            secretId = "test",
            secretKey = "test",
            service = "sts",
            region = "ap-beijing",
            action = "GetFederationToken",
            timestamp = System.currentTimeMillis() / 1000,
            payload = "{}",
            host = "sts.tencentcloudapi.com"
        )
        
        Log.d(TAG, "工具类验证通过")
    }
    
    private fun verifyTestTools(context: Context) {
        Log.d(TAG, "验证测试工具...")
        
        // 验证测试工具可以实例化
        val testModule = CosModuleTest
        val testExample = CosTestExample
        
        Log.d(TAG, "测试工具验证通过")
    }
}
