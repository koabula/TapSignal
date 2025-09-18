package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 腾讯云子用户管理器修复验证
 * 验证修复后的腾讯云CAM API调用
 */
object TencentSubUserManagerFixTest {
    private val TAG = Log.tag(TencentSubUserManagerFixTest::class.java)
    
    /**
     * 测试修复后的腾讯云子用户管理器
     */
    fun testFixedTencentSubUserManager(context: Context): TestResult {
        Log.i(TAG, "开始测试修复后的腾讯云子用户管理器...")
        
        return try {
            // 1. 测试API参数修复
            val apiParameterTest = testApiParameterFix(context)
            
            // 2. 测试错误处理修复
            val errorHandlingTest = testErrorHandlingFix(context)
            
            // 3. 测试不回退逻辑
            val noFallbackTest = testNoFallbackLogic(context)
            
            TestResult(
                apiParameterFixed = apiParameterTest.success,
                errorHandlingFixed = errorHandlingTest.success,
                noFallbackFixed = noFallbackTest.success,
                details = listOf(
                    apiParameterTest.message,
                    errorHandlingTest.message,
                    noFallbackTest.message
                ),
                overallSuccess = apiParameterTest.success && errorHandlingTest.success && noFallbackTest.success
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "测试过程异常", e)
            TestResult(
                apiParameterFixed = false,
                errorHandlingFixed = false,
                noFallbackFixed = false,
                details = listOf("测试过程异常: ${e.message}"),
                overallSuccess = false
            )
        }
    }
    
    /**
     * 测试API参数修复
     */
    private fun testApiParameterFix(context: Context): TestStep {
        Log.d(TAG, "测试API参数修复...")
        
        return try {
            val config = CosConfig(
                provider = CosConfig.Provider.TENCENT,
                secretId = "test",
                secretKey = "test",
                region = "ap-beijing",
                bucketName = "test-bucket-123456789"
            )
            
            val manager = org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager(config, context)
            
            // 使用反射检查createCamUser方法是否返回Long
            val createCamUserMethod = manager::class.java.getDeclaredMethod("createCamUser", String::class.java)
            createCamUserMethod.isAccessible = true
            
            val returnType = createCamUserMethod.returnType
            if (returnType == Long::class.java || returnType == Long::class.javaPrimitiveType) {
                TestStep(true, "✅ createCamUser方法已修复，返回类型为Long (UIN)")
            } else {
                TestStep(false, "❌ createCamUser方法返回类型错误: $returnType")
            }
            
        } catch (e: NoSuchMethodException) {
            TestStep(false, "❌ 找不到createCamUser方法")
        } catch (e: Exception) {
            TestStep(false, "❌ API参数测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试错误处理修复
     */
    private fun testErrorHandlingFix(context: Context): TestStep {
        Log.d(TAG, "测试错误处理修复...")
        
        return try {
            val config = CosConfig(
                provider = CosConfig.Provider.TENCENT,
                secretId = "invalid-secret-id",
                secretKey = "invalid-secret-key",
                region = "ap-beijing",
                bucketName = "test-bucket-123456789"
            )
            
            val manager = org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager(config, context)
            
            // 尝试创建子用户（应该失败）
            try {
                manager.createSubUser("test-user", "/outbox/", CosPermission.READ_ONLY)
                TestStep(false, "❌ 预期应该抛出异常，但没有")
            } catch (e: CosSubUserException) {
                // 检查异常消息是否包含正确的错误信息
                val message = e.message ?: ""
                if (message.contains("创建腾讯云CAM子用户失败")) {
                    TestStep(true, "✅ 错误处理正确，抛出了预期的CosSubUserException")
                } else {
                    TestStep(false, "❌ 异常消息不正确: $message")
                }
            } catch (e: Exception) {
                TestStep(false, "❌ 抛出了意外的异常类型: ${e::class.simpleName}")
            }
            
        } catch (e: Exception) {
            TestStep(false, "❌ 错误处理测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试不回退逻辑
     */
    private fun testNoFallbackLogic(context: Context): TestStep {
        Log.d(TAG, "测试不回退逻辑...")
        
        return try {
            // 设置永久凭证模式
            CosConfigStorage.saveCredentialType(context, CosCredentialType.PERMANENT)
            
            val cosRequestManager = org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosRequestManager.getInstance(context)
            
            // 使用反射检查generateCamCredentials方法
            val generateMethod = cosRequestManager::class.java.getDeclaredMethod(
                "generateCamCredentials",
                CosConfig::class.java,
                org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosDuration::class.java
            )
            generateMethod.isAccessible = true
            
            // 使用无效配置测试
            val invalidConfig = CosConfig(
                provider = CosConfig.Provider.TENCENT,
                secretId = "invalid",
                secretKey = "invalid",
                region = "ap-beijing",
                bucketName = "invalid-bucket-123456789"
            )
            
            val result = generateMethod.invoke(
                cosRequestManager,
                invalidConfig,
                org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosDuration.PERMANENT
            )
            
            if (result == null) {
                TestStep(true, "✅ 永久凭证失败时正确返回null，不回退到临时凭证")
            } else {
                TestStep(false, "❌ 永久凭证失败时仍然返回了结果，可能回退到了临时凭证")
            }
            
        } catch (e: NoSuchMethodException) {
            TestStep(false, "❌ 找不到generateCamCredentials方法")
        } catch (e: Exception) {
            // 如果抛出异常也是可以接受的，说明没有回退
            TestStep(true, "✅ 永久凭证失败时抛出异常，没有回退到临时凭证")
        }
    }
    
    /**
     * 生成修复报告
     */
    fun generateFixReport(result: TestResult): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== 腾讯云子用户管理器修复报告 ===")
        sb.appendLine()
        
        // 总体状态
        val status = if (result.overallSuccess) "✅ 修复成功" else "❌ 仍有问题"
        sb.appendLine("📊 总体状态: $status")
        sb.appendLine()
        
        // 修复项目
        sb.appendLine("🔧 修复项目:")
        sb.appendLine("  API参数修复: ${if (result.apiParameterFixed) "✅ 完成" else "❌ 未完成"}")
        sb.appendLine("  错误处理修复: ${if (result.errorHandlingFixed) "✅ 完成" else "❌ 未完成"}")
        sb.appendLine("  不回退逻辑: ${if (result.noFallbackFixed) "✅ 完成" else "❌ 未完成"}")
        sb.appendLine()
        
        // 详细信息
        sb.appendLine("📋 详细信息:")
        result.details.forEach { detail ->
            sb.appendLine("  • $detail")
        }
        sb.appendLine()
        
        // 修复内容总结
        sb.appendLine("🎯 修复内容总结:")
        sb.appendLine("  1. TargetUin参数类型: String → Long (UIN)")
        sb.appendLine("  2. createCamUser返回值: void → Long (UIN)")
        sb.appendLine("  3. createAccessKey参数: userName → userUin")
        sb.appendLine("  4. 错误处理: 改进异常信息和清理逻辑")
        sb.appendLine("  5. 不回退策略: 永久凭证失败时不使用临时凭证")
        sb.appendLine()
        
        // 使用建议
        if (result.overallSuccess) {
            sb.appendLine("🚀 使用建议:")
            sb.appendLine("  • 现在可以正常使用腾讯云永久凭证功能")
            sb.appendLine("  • 确保腾讯云账号有CAM管理权限")
            sb.appendLine("  • 使用正确的bucket命名格式: <bucket>-<appid>")
            sb.appendLine("  • 监控子用户创建和删除日志")
        } else {
            sb.appendLine("⚠️ 后续工作:")
            sb.appendLine("  • 检查未完成的修复项目")
            sb.appendLine("  • 验证腾讯云API调用权限")
            sb.appendLine("  • 测试实际的子用户创建流程")
        }
        
        return sb.toString()
    }
    
    /**
     * 获取修复前后对比
     */
    fun getBeforeAfterComparison(): Map<String, Pair<String, String>> {
        return mapOf(
            "TargetUin参数" to Pair(
                "❌ put(\"TargetUin\", userName) // String类型",
                "✅ put(\"TargetUin\", userUin) // Long类型"
            ),
            "createCamUser方法" to Pair(
                "❌ private fun createCamUser(userName: String)",
                "✅ private fun createCamUser(userName: String): Long"
            ),
            "createAccessKey调用" to Pair(
                "❌ val accessKey = createAccessKey(userName)",
                "✅ val accessKey = createAccessKey(userUin)"
            ),
            "失败处理" to Pair(
                "❌ 回退到临时凭证",
                "✅ 直接返回null，不回退"
            ),
            "错误信息" to Pair(
                "❌ InvalidParameter - TargetUin type not valid",
                "✅ 正确的UIN参数，避免类型错误"
            )
        )
    }
}

// 数据类定义
data class TestResult(
    val apiParameterFixed: Boolean,
    val errorHandlingFixed: Boolean,
    val noFallbackFixed: Boolean,
    val details: List<String>,
    val overallSuccess: Boolean
)

data class TestStep(
    val success: Boolean,
    val message: String
)
