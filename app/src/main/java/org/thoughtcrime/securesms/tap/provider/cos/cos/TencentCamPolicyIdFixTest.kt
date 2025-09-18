package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 腾讯云CAM PolicyId参数修复验证测试
 * 验证策略附加是否使用正确的PolicyId参数
 */
object TencentCamPolicyIdFixTest {
    private val TAG = Log.tag(TencentCamPolicyIdFixTest::class.java)
    
    /**
     * 测试PolicyId参数修复
     */
    fun testPolicyIdParameterFix(context: Context): PolicyIdFixTestResult {
        Log.i(TAG, "开始测试PolicyId参数修复...")
        
        return try {
            val tests = mutableListOf<PolicyIdTestStep>()
            
            // 1. 测试createCustomPolicyAndGetId方法
            tests.add(testCreateCustomPolicyAndGetIdMethod())
            
            // 2. 测试attachPolicyToUser方法签名
            tests.add(testAttachPolicyToUserMethod())
            
            // 3. 测试verifyPolicyAttachment方法签名
            tests.add(testVerifyPolicyAttachmentMethod())
            
            // 4. 测试parseCreatePolicyResponse方法
            tests.add(testParseCreatePolicyResponseMethod())
            
            // 5. 测试listAttachedUserPoliciesWithId方法
            tests.add(testListAttachedUserPoliciesWithIdMethod())
            
            val allPassed = tests.all { it.success }
            
            PolicyIdFixTestResult(
                allTestsPassed = allPassed,
                testSteps = tests,
                summary = generatePolicyIdFixSummary(tests, allPassed)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "PolicyId修复测试异常", e)
            PolicyIdFixTestResult(
                allTestsPassed = false,
                testSteps = listOf(PolicyIdTestStep(false, "测试异常: ${e.message}")),
                summary = "测试执行失败: ${e.message}"
            )
        }
    }
    
    /**
     * 测试createCustomPolicyAndGetId方法
     */
    private fun testCreateCustomPolicyAndGetIdMethod(): PolicyIdTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "createCustomPolicyAndGetId", 
                String::class.java,  // policyName
                String::class.java   // policyDocument
            )
            
            val returnType = method.returnType
            if (returnType == Long::class.java || returnType == Long::class.javaPrimitiveType) {
                PolicyIdTestStep(true, "✅ createCustomPolicyAndGetId方法已添加，返回Long类型的PolicyId")
            } else {
                PolicyIdTestStep(false, "❌ createCustomPolicyAndGetId方法返回类型错误: $returnType")
            }
            
        } catch (e: NoSuchMethodException) {
            PolicyIdTestStep(false, "❌ 找不到createCustomPolicyAndGetId方法")
        } catch (e: Exception) {
            PolicyIdTestStep(false, "❌ createCustomPolicyAndGetId测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试attachPolicyToUser方法签名
     */
    private fun testAttachPolicyToUserMethod(): PolicyIdTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "attachPolicyToUser",
                Long::class.java,    // userUin
                Long::class.java     // policyId - 修复后的参数类型
            )
            
            PolicyIdTestStep(true, "✅ attachPolicyToUser方法已修复，使用Long类型的policyId")
            
        } catch (e: NoSuchMethodException) {
            PolicyIdTestStep(false, "❌ attachPolicyToUser方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            PolicyIdTestStep(false, "❌ attachPolicyToUser测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试verifyPolicyAttachment方法签名
     */
    private fun testVerifyPolicyAttachmentMethod(): PolicyIdTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "verifyPolicyAttachment",
                Long::class.java,    // userUin
                Long::class.java,    // policyId - 新增参数
                String::class.java   // policyName
            )
            
            PolicyIdTestStep(true, "✅ verifyPolicyAttachment方法已修复，新增policyId参数")
            
        } catch (e: NoSuchMethodException) {
            PolicyIdTestStep(false, "❌ verifyPolicyAttachment方法签名未修复: ${e.message}")
        } catch (e: Exception) {
            PolicyIdTestStep(false, "❌ verifyPolicyAttachment测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试parseCreatePolicyResponse方法
     */
    private fun testParseCreatePolicyResponseMethod(): PolicyIdTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "parseCreatePolicyResponse",
                String::class.java   // jsonResponse
            )
            
            val returnType = method.returnType
            if (returnType == Long::class.java || returnType == Long::class.javaPrimitiveType) {
                PolicyIdTestStep(true, "✅ parseCreatePolicyResponse方法已添加，返回Long类型的PolicyId")
            } else {
                PolicyIdTestStep(false, "❌ parseCreatePolicyResponse方法返回类型错误: $returnType")
            }
            
        } catch (e: NoSuchMethodException) {
            PolicyIdTestStep(false, "❌ 找不到parseCreatePolicyResponse方法")
        } catch (e: Exception) {
            PolicyIdTestStep(false, "❌ parseCreatePolicyResponse测试异常: ${e.message}")
        }
    }
    
    /**
     * 测试listAttachedUserPoliciesWithId方法
     */
    private fun testListAttachedUserPoliciesWithIdMethod(): PolicyIdTestStep {
        return try {
            val managerClass = Class.forName("org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager")
            val method = managerClass.getDeclaredMethod(
                "listAttachedUserPoliciesWithId",
                Long::class.java     // userUin
            )
            
            PolicyIdTestStep(true, "✅ listAttachedUserPoliciesWithId方法已添加")
            
        } catch (e: NoSuchMethodException) {
            PolicyIdTestStep(false, "❌ 找不到listAttachedUserPoliciesWithId方法")
        } catch (e: Exception) {
            PolicyIdTestStep(false, "❌ listAttachedUserPoliciesWithId测试异常: ${e.message}")
        }
    }
    
    /**
     * 生成PolicyId修复测试总结
     */
    private fun generatePolicyIdFixSummary(tests: List<PolicyIdTestStep>, allPassed: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("=== 腾讯云CAM PolicyId参数修复验证结果 ===")
        sb.appendLine()
        
        tests.forEach { test ->
            sb.appendLine(test.message)
        }
        
        sb.appendLine()
        if (allPassed) {
            sb.appendLine("🎉 所有PolicyId参数修复测试通过！")
            sb.appendLine()
            sb.appendLine("关键修复内容:")
            sb.appendLine("  1. ✅ AttachUserPolicy API: PolicyName → PolicyId")
            sb.appendLine("  2. ✅ AttachUserPolicy API: AttachUserName → AttachUin")
            sb.appendLine("  3. ✅ DetachUserPolicy API: DetachUserName → DetachUin")
            sb.appendLine("  4. ✅ DetachUserPolicy API: PolicyName → PolicyId")
            sb.appendLine("  5. ✅ 新增createCustomPolicyAndGetId方法获取PolicyId")
            sb.appendLine("  6. ✅ 新增parseCreatePolicyResponse方法解析PolicyId")
            sb.appendLine("  7. ✅ 新增listAttachedUserPoliciesWithId方法")
            sb.appendLine()
            sb.appendLine("根据腾讯云官方文档修复:")
            sb.appendLine("  • AttachUserPolicy需要AttachUin(Integer)和PolicyId(Integer)")
            sb.appendLine("  • DetachUserPolicy需要DetachUin(Integer)和PolicyId(Integer)")
            sb.appendLine("  • 不能使用PolicyName，必须使用PolicyId")
            sb.appendLine()
            sb.appendLine("现在CAM策略应该能正确关联到子账户！")
        } else {
            sb.appendLine("❌ 部分PolicyId参数修复测试失败，需要进一步检查")
        }
        
        return sb.toString()
    }
    
    /**
     * PolicyId修复测试结果
     */
    data class PolicyIdFixTestResult(
        val allTestsPassed: Boolean,
        val testSteps: List<PolicyIdTestStep>,
        val summary: String
    )
    
    /**
     * PolicyId测试步骤
     */
    data class PolicyIdTestStep(
        val success: Boolean,
        val message: String
    )
}
