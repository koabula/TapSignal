package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * COS模块测试示例
 * 演示如何使用修复后的COS模块
 */
object CosTestExample {
    private val TAG = Log.tag(CosTestExample::class.java)
    
    /**
     * 示例：测试修复后的COS模块
     * 在应用中调用此方法来验证COS模块是否正常工作
     */
    fun testFixedCosModule(context: Context) {
        Log.i(TAG, "开始测试修复后的COS模块...")
        
        try {
            // 运行完整测试
            val testResult = CosModuleTest.runFullTest(context)
            
            if (testResult) {
                Log.i(TAG, "✅ COS模块修复成功！所有测试通过")
                Log.i(TAG, "现在可以正常使用COS请求功能了")
            } else {
                Log.e(TAG, "❌ COS模块仍有问题，请检查配置和网络连接")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "测试COS模块时发生异常", e)
        }
    }
    
    /**
     * 示例：发送COS请求（修复后应该可以正常工作）
     */
    fun exampleSendCosRequest(context: Context, recipientId: String) {
        Log.i(TAG, "示例：发送COS请求到 $recipientId")
        
        try {
            // 使用修复后的CosRequestManager
            val cosRequestManager = org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosRequestManager.getInstance(context)
            
            // 发送COS请求（这之前会失败，现在应该可以正常工作）
            val future = cosRequestManager.sendCosRequest(
                recipientId = recipientId,
                durationType = org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosDuration.ONE_WEEK,
                message = "请求建立COS通信通道"
            )
            
            future.thenAccept { result ->
                when (result) {
                    is org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosRequestResult.Success -> {
                        Log.i(TAG, "✅ COS请求发送成功！RequestId: ${result.requestId}")
                    }
                    is org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.CosRequestResult.Failure -> {
                        Log.e(TAG, "❌ COS请求发送失败: ${result.errorMessage}")
                    }
                }
            }.exceptionally { throwable ->
                Log.e(TAG, "COS请求发送异常", throwable)
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "发送COS请求时发生异常", e)
        }
    }
}

/**
 * 在应用启动时或需要测试时调用此方法
 * 例如在MainActivity或SettingsActivity中：
 * 
 * ```kotlin
 * // 测试COS模块修复结果
 * CosTestExample.testFixedCosModule(this)
 * 
 * // 或者发送测试COS请求
 * CosTestExample.exampleSendCosRequest(this, "recipient-service-id")
 * ```
 */
