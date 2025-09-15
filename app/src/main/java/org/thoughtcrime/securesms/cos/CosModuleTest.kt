package org.thoughtcrime.securesms.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.coscomm.data.CosAccessInfo
import org.thoughtcrime.securesms.coscomm.data.toCosAccessToken
import org.thoughtcrime.securesms.coscomm.data.toCosAccessInfo

/**
 * COS模块测试工具
 * 用于验证修复后的COS模块功能
 */
object CosModuleTest {
    private val TAG = Log.tag(CosModuleTest::class.java)
    
    /**
     * 测试COS配置和客户端创建
     */
    fun testCosClientCreation(context: Context): Boolean {
        return try {
            Log.i(TAG, "开始测试COS客户端创建...")
            
            // 1. 检查COS配置
            val config = CosConfigStorage.getConfig(context)
            if (config == null) {
                Log.w(TAG, "COS配置未找到，请先配置COS服务")
                return false
            }
            
            Log.i(TAG, "COS配置: provider=${config.provider}, region=${config.region}, bucket=${config.bucketName}")
            
            // 2. 创建COS客户端
            val client = CosClientFactory.createClient(config, context)
            Log.i(TAG, "COS客户端创建成功: ${client::class.simpleName}")
            
            // 3. 测试临时凭证生成（这是之前出错的地方）
            Log.i(TAG, "开始测试临时凭证生成...")
            val accessToken = client.generateTemporaryAccessToken("/outbox/", 15)
            
            Log.i(TAG, "临时凭证生成成功:")
            Log.i(TAG, "  AccessKeyId: ${maskCredential(accessToken.accessKeyId)}")
            Log.i(TAG, "  SecretAccessKey: ${maskCredential(accessToken.secretAccessKey)}")
            Log.i(TAG, "  SessionToken: ${if (accessToken.sessionToken != null) "存在" else "无"}")
            Log.i(TAG, "  ExpireTime: ${accessToken.expireTime}")
            Log.i(TAG, "  IsExpired: ${accessToken.isExpired()}")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "COS模块测试失败", e)
            false
        }
    }
    
    /**
     * 测试CAM凭证转换
     */
    fun testCamCredentialConversion(context: Context): Boolean {
        return try {
            Log.i(TAG, "开始测试CAM凭证转换...")
            
            val config = CosConfigStorage.getConfig(context) ?: return false
            val client = CosClientFactory.createClient(config, context)
            
            // 生成临时凭证
            val accessToken = client.generateTemporaryAccessToken("/outbox/", 30)
            
            // 转换为CosAccessInfo
            val accessInfo = accessToken.toCosAccessInfo(
                provider = config.provider.name,
                region = config.region,
                bucketName = config.bucketName
            )
            
            Log.i(TAG, "CosAccessInfo转换成功:")
            Log.i(TAG, "  Provider: ${accessInfo.provider}")
            Log.i(TAG, "  Region: ${accessInfo.region}")
            Log.i(TAG, "  BucketName: ${accessInfo.bucketName}")
            Log.i(TAG, "  SharedDirectory: ${accessInfo.sharedDirectory}")
            
            // 转换回CosAccessToken
            val convertedToken = accessInfo.toCosAccessToken()
            
            Log.i(TAG, "转换验证:")
            Log.i(TAG, "  AccessKeyId匹配: ${accessToken.accessKeyId == convertedToken.accessKeyId}")
            Log.i(TAG, "  SecretAccessKey匹配: ${accessToken.secretAccessKey == convertedToken.secretAccessKey}")
            Log.i(TAG, "  SessionToken匹配: ${accessToken.sessionToken == convertedToken.sessionToken}")
            Log.i(TAG, "  ExpireTime匹配: ${accessToken.expireTime == convertedToken.expireTime}")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "CAM凭证转换测试失败", e)
            false
        }
    }
    
    /**
     * 运行完整的COS模块测试
     */
    fun runFullTest(context: Context): Boolean {
        Log.i(TAG, "========== COS模块完整测试开始 ==========")
        
        var allTestsPassed = true
        
        // 测试1: COS客户端创建和临时凭证生成
        if (!testCosClientCreation(context)) {
            Log.e(TAG, "测试1失败: COS客户端创建和临时凭证生成")
            allTestsPassed = false
        } else {
            Log.i(TAG, "测试1通过: COS客户端创建和临时凭证生成")
        }
        
        // 测试2: CAM凭证转换
        if (!testCamCredentialConversion(context)) {
            Log.e(TAG, "测试2失败: CAM凭证转换")
            allTestsPassed = false
        } else {
            Log.i(TAG, "测试2通过: CAM凭证转换")
        }
        
        Log.i(TAG, "========== COS模块完整测试结束 ==========")
        Log.i(TAG, "测试结果: ${if (allTestsPassed) "全部通过" else "存在失败"}")
        
        return allTestsPassed
    }
    
    /**
     * 安全地显示凭证信息（只显示部分字符）
     */
    private fun maskCredential(credential: String): String {
        if (credential.length <= 8) return "***"
        return "${credential.take(4)}...${credential.takeLast(4)}"
    }
}
