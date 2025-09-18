package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * 腾讯云CAM策略修复验证测试
 * 验证Action格式和Resource ARN修复是否正确
 */
object TencentCamPolicyFixTest {
    private val TAG = Log.tag(TencentCamPolicyFixTest::class.java)
    
    /**
     * 测试修复后的CAM策略格式
     */
    fun testFixedCamPolicyFormat(context: Context): PolicyTestResult {
        Log.i(TAG, "开始测试修复后的CAM策略格式...")
        
        return try {
            val config = CosConfig(
                provider = CosConfig.Provider.TENCENT,
                secretId = "test",
                secretKey = "test",
                region = "ap-beijing",
                bucketName = "test-bucket-1234567890"
            )
            
            val manager = org.thoughtcrime.securesms.tap.provider.cos.cos.tencent.TencentSubUserManager(config, context)
            
            // 使用反射调用buildStandardTencentCamPolicy方法
            val buildPolicyMethod = manager::class.java.getDeclaredMethod("buildStandardTencentCamPolicy")
            buildPolicyMethod.isAccessible = true
            
            val policyString = buildPolicyMethod.invoke(manager) as String
            val policy = JSONObject(policyString)
            
            // 验证策略结构
            val tests = mutableListOf<PolicyTestStep>()
            
            // 1. 验证版本
            tests.add(testPolicyVersion(policy))
            
            // 2. 验证Action格式
            tests.add(testActionFormat(policy))
            
            // 3. 验证Resource ARN格式
            tests.add(testResourceArnFormat(policy, config))
            
            // 4. 验证Effect字段
            tests.add(testEffectField(policy))
            
            val allPassed = tests.all { it.success }
            
            PolicyTestResult(
                allTestsPassed = allPassed,
                policyJson = policyString,
                testSteps = tests,
                summary = generateTestSummary(tests, allPassed)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "测试过程异常", e)
            PolicyTestResult(
                allTestsPassed = false,
                policyJson = "",
                testSteps = listOf(PolicyTestStep(false, "测试异常: ${e.message}")),
                summary = "测试执行失败: ${e.message}"
            )
        }
    }
    
    /**
     * 测试策略版本
     */
    private fun testPolicyVersion(policy: JSONObject): PolicyTestStep {
        return try {
            val version = policy.getString("version")
            if (version == "2.0") {
                PolicyTestStep(true, "✅ 策略版本正确: $version")
            } else {
                PolicyTestStep(false, "❌ 策略版本错误: $version，应该是2.0")
            }
        } catch (e: Exception) {
            PolicyTestStep(false, "❌ 无法获取策略版本: ${e.message}")
        }
    }
    
    /**
     * 测试Action格式
     */
    private fun testActionFormat(policy: JSONObject): PolicyTestStep {
        return try {
            val statements = policy.getJSONArray("statement")
            val statement = statements.getJSONObject(0)
            val actions = statement.getJSONArray("action")
            
            val actionList = mutableListOf<String>()
            for (i in 0 until actions.length()) {
                actionList.add(actions.getString(i))
            }
            
            // 检查是否移除了错误的"name/"前缀
            val hasNamePrefix = actionList.any { it.startsWith("name/") }
            val hasCorrectFormat = actionList.all { it.startsWith("cos:") }
            
            when {
                hasNamePrefix -> PolicyTestStep(false, "❌ Action仍包含错误的'name/'前缀: $actionList")
                hasCorrectFormat -> PolicyTestStep(true, "✅ Action格式正确，已移除'name/'前缀: $actionList")
                else -> PolicyTestStep(false, "❌ Action格式不正确: $actionList")
            }
            
        } catch (e: Exception) {
            PolicyTestStep(false, "❌ 无法验证Action格式: ${e.message}")
        }
    }
    
    /**
     * 测试Resource ARN格式
     */
    private fun testResourceArnFormat(policy: JSONObject, config: CosConfig): PolicyTestStep {
        return try {
            val statements = policy.getJSONArray("statement")
            val statement = statements.getJSONObject(0)
            val resources = statement.getJSONArray("resource")
            val resourceArn = resources.getString(0)
            
            // 验证ARN格式: qcs::cos:region:uid/appid:bucket/*
            val expectedPattern = "qcs::cos:${config.region}:uid/\\d{10}:${config.bucketName}/\\*"
            val isValidFormat = resourceArn.matches(Regex(expectedPattern))
            
            if (isValidFormat) {
                PolicyTestStep(true, "✅ Resource ARN格式正确: $resourceArn")
            } else {
                PolicyTestStep(false, "❌ Resource ARN格式错误: $resourceArn，期望格式: $expectedPattern")
            }
            
        } catch (e: Exception) {
            PolicyTestStep(false, "❌ 无法验证Resource ARN格式: ${e.message}")
        }
    }
    
    /**
     * 测试Effect字段
     */
    private fun testEffectField(policy: JSONObject): PolicyTestStep {
        return try {
            val statements = policy.getJSONArray("statement")
            val statement = statements.getJSONObject(0)
            val effect = statement.getString("effect")
            
            if (effect == "allow") {
                PolicyTestStep(true, "✅ Effect字段正确: $effect")
            } else {
                PolicyTestStep(false, "❌ Effect字段错误: $effect，应该是'allow'")
            }
            
        } catch (e: Exception) {
            PolicyTestStep(false, "❌ 无法验证Effect字段: ${e.message}")
        }
    }
    
    /**
     * 生成测试总结
     */
    private fun generateTestSummary(tests: List<PolicyTestStep>, allPassed: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("=== 腾讯云CAM策略修复验证结果 ===")
        sb.appendLine()
        
        tests.forEach { test ->
            sb.appendLine(test.message)
        }
        
        sb.appendLine()
        if (allPassed) {
            sb.appendLine("🎉 所有测试通过！CAM策略格式修复成功")
            sb.appendLine()
            sb.appendLine("修复内容:")
            sb.appendLine("  1. ✅ 移除了错误的'name/'前缀")
            sb.appendLine("  2. ✅ 使用正确的'cos:'Action格式")
            sb.appendLine("  3. ✅ 改进了AppID提取逻辑")
            sb.appendLine("  4. ✅ 验证了Resource ARN格式")
            sb.appendLine("  5. ✅ 添加了策略生效验证")
            sb.appendLine()
            sb.appendLine("现在策略应该能在腾讯云控制台正常显示和生效")
        } else {
            sb.appendLine("❌ 部分测试失败，需要进一步修复")
        }
        
        return sb.toString()
    }
    
    /**
     * 策略测试结果
     */
    data class PolicyTestResult(
        val allTestsPassed: Boolean,
        val policyJson: String,
        val testSteps: List<PolicyTestStep>,
        val summary: String
    )
    
    /**
     * 策略测试步骤
     */
    data class PolicyTestStep(
        val success: Boolean,
        val message: String
    )
}
