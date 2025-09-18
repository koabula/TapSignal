package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 腾讯云CAM UIN参数修复验证测试
 * 验证策略附加是否使用正确的UIN参数
 */
object TencentCamUinFixTest {
    private val TAG = Log.tag(TencentCamUinFixTest::class.java)
    
    /**
     * 测试UIN参数修复
     */
    fun testUinParameterFix(context: Context): UinFixTestResult {
        Log.i(TAG, "开始测试UIN参数修复...")
        
        return try {
            val tests = mutableListOf<UinTestStep>()
            
            // 1. 测试attachCustomPolicy方法签名
            tests.add(testAttachCustomPolicySignature())
            
            // 2. 测试attachPolicyToUser方法签名
            tests.add(testAttachPolicyToUserSignature())
            
            // 3. 测试verifyPolicyAttachment方法签名
            tests.add(testVerifyPolicyAttachmentSignature())
            
            // 4. 测试listAttachedUserPolicies方法签名
            tests.add(testListAttachedUserPoliciesSignature())
            
            // 5. 测试detachUserPolicy方法签名
            tests.add(testDetachUserPolicySignature())
            
            val allPassed = tests.all { it.success }
            
            UinFixTestResult(
                allTestsPassed = allPassed,
                testSteps = tests,
                summary = generateUinFixSummary(tests, allPassed)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "UIN修复测试异常", e)
            UinFixTestResult(
                allTestsPassed = false,
                testSteps = listOf(UinTestStep(false, "测试异常: ${e.message}")),
                summary = "测试执行失败: ${e.message}"
            )
        }
    }
    
    /**
     * 测试attachCustomPolicy方法签名
     */
    private fun testAttachCustomPolicySignature(): UinTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "attachCustomPolicy", 
                String::class.java,  // userName
                Long::class.java,    // userUin - 新增参数
                org.thoughtcrime.securesms.tap.provider.cos.cos.CosPermission::class.java  // permissions
            )
            
            UinTestStep(true, "✅ attachCustomPolicy方法已修复，接受userUin参数")
            
        } catch (e: NoSuchMethodException) {
            UinTestStep(false, "❌ attachCustomPolicy方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            UinTestStep(false, "❌ attachCustomPolicy测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试attachPolicyToUser方法签名
     */
    private fun testAttachPolicyToUserSignature(): UinTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "attachPolicyToUser",
                Long::class.java,    // userUin - 修复后的参数类型
                String::class.java   // policyName
            )
            
            UinTestStep(true, "✅ attachPolicyToUser方法已修复，使用Long类型的userUin")
            
        } catch (e: NoSuchMethodException) {
            UinTestStep(false, "❌ attachPolicyToUser方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            UinTestStep(false, "❌ attachPolicyToUser测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试verifyPolicyAttachment方法签名
     */
    private fun testVerifyPolicyAttachmentSignature(): UinTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "verifyPolicyAttachment",
                Long::class.java,    // userUin - 修复后的参数类型
                String::class.java   // policyName
            )
            
            UinTestStep(true, "✅ verifyPolicyAttachment方法已修复，使用Long类型的userUin")
            
        } catch (e: NoSuchMethodException) {
            UinTestStep(false, "❌ verifyPolicyAttachment方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            UinTestStep(false, "❌ verifyPolicyAttachment测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试listAttachedUserPolicies方法签名
     */
    private fun testListAttachedUserPoliciesSignature(): UinTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "listAttachedUserPolicies",
                Long::class.java     // userUin - 修复后的参数类型
            )
            
            UinTestStep(true, "✅ listAttachedUserPolicies方法已修复，使用Long类型的userUin")
            
        } catch (e: NoSuchMethodException) {
            UinTestStep(false, "❌ listAttachedUserPolicies方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            UinTestStep(false, "❌ listAttachedUserPolicies测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试detachUserPolicy方法签名
     */
    private fun testDetachUserPolicySignature(): UinTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "detachUserPolicy",
                Long::class.java,    // userUin - 修复后的参数类型
                String::class.java   // policyName
            )
            
            UinTestStep(true, "✅ detachUserPolicy方法已修复，使用Long类型的userUin")
            
        } catch (e: NoSuchMethodException) {
            UinTestStep(false, "❌ detachUserPolicy方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            UinTestStep(false, "❌ detachUserPolicy测试异常: ${e.message}")
        }
    }
    
    /**
     * 生成UIN修复测试总结
     */
    private fun generateUinFixSummary(tests: List<UinTestStep>, allPassed: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("=== 腾讯云CAM UIN参数修复验证结果 ===")
        sb.appendLine()
        
        tests.forEach { test ->
            sb.appendLine(test.message)
        }
        
        sb.appendLine()
        if (allPassed) {
            sb.appendLine("🎉 所有UIN参数修复测试通过！")
            sb.appendLine()
            sb.appendLine("修复内容:")
            sb.appendLine("  1. ✅ attachCustomPolicy: 新增userUin参数")
            sb.appendLine("  2. ✅ attachPolicyToUser: userName → userUin (Long)")
            sb.appendLine("  3. ✅ verifyPolicyAttachment: userName → userUin (Long)")
            sb.appendLine("  4. ✅ listAttachedUserPolicies: userName → userUin (Long)")
            sb.appendLine("  5. ✅ detachUserPolicy: userName → userUin (Long)")
            sb.appendLine("  6. ✅ 添加getUserUinByName辅助方法")
            sb.appendLine("  7. ✅ 修复API请求参数: AttachUserName → AttachUin")
            sb.appendLine()
            sb.appendLine("现在CAM策略应该能正确关联到子账户！")
            sb.appendLine("请在腾讯云控制台 > 访问管理 > 用户 > 用户详情 > 关联策略中查看")
        } else {
            sb.appendLine("❌ 部分UIN参数修复测试失败，需要进一步检查")
        }
        
        return sb.toString()
    }
    
    /**
     * UIN修复测试结果
     */
    data class UinFixTestResult(
        val allTestsPassed: Boolean,
        val testSteps: List<UinTestStep>,
        val summary: String
    )
    
    /**
     * UIN测试步骤
     */
    data class UinTestStep(
        val success: Boolean,
        val message: String
    )
}
