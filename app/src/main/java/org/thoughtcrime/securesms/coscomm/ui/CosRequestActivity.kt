package org.thoughtcrime.securesms.coscomm.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.coscomm.data.CosSignalMessage
import org.thoughtcrime.securesms.coscomm.manager.CosRequestManager

/**
 * COS请求处理Activity
 * 用于显示COS v2模式请求的确认对话框
 */
class CosRequestActivity : AppCompatActivity() {
    
    companion object {
        private val TAG = Log.tag(CosRequestActivity::class.java)
        
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_SENDER_NAME = "sender_name"
        private const val EXTRA_SENDER_ID = "sender_id"
        
        /**
         * 创建启动Intent
         */
        fun createIntent(
            context: Context,
            requestId: String,
            senderName: String,
            senderId: String
        ): Intent {
            return Intent(context, CosRequestActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_SENDER_NAME, senderName)
                putExtra(EXTRA_SENDER_ID, senderId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
    
    private lateinit var cosRequestManager: CosRequestManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置Activity为透明背景，使其看起来像对话框
        setFinishOnTouchOutside(true)

        cosRequestManager = CosRequestManager.getInstance(this)

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME)
        val senderId = intent.getStringExtra(EXTRA_SENDER_ID)

        if (requestId == null || senderName == null || senderId == null) {
            Log.e(TAG, "缺少必要的参数: requestId=$requestId, senderName=$senderName, senderId=$senderId")
            finish()
            return
        }

        Log.i(TAG, "显示COS请求对话框: $senderName")
        showCosRequestDialog(requestId, senderName, senderId)
    }
    
    /**
     * 显示COS请求确认对话框
     */
    private fun showCosRequestDialog(requestId: String, senderName: String, senderId: String) {
        try {
            val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_Signal_MaterialAlertDialog)
                .setTitle("COS v2通信请求")
                .setMessage("$senderName 请求建立COS v2通信模式。\n\n" +
                           "这将允许双方通过云存储进行加密通信，即使Signal服务器不可用时也能保持联系。\n\n" +
                           "是否接受此请求？")
                .setPositiveButton("接受") { _, _ ->
                    handleAcceptRequest(requestId, senderId)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    handleRejectRequest(requestId, senderId)
                }
                .setCancelable(true)
                .setOnCancelListener {
                    Log.i(TAG, "用户取消了COS请求对话框")
                    finish()
                }
                .create()

            dialog.show()
            Log.i(TAG, "COS请求对话框已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示COS请求对话框失败", e)
            // 回退到简单的对话框
            showSimpleDialog(requestId, senderName, senderId)
        }
    }

    /**
     * 显示简单的对话框（回退方案）
     */
    private fun showSimpleDialog(requestId: String, senderName: String, senderId: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("COS v2通信请求")
            .setMessage("$senderName 请求建立COS v2通信模式。是否接受？")
            .setPositiveButton("接受") { _, _ ->
                handleAcceptRequest(requestId, senderId)
            }
            .setNegativeButton("拒绝") { _, _ ->
                handleRejectRequest(requestId, senderId)
            }
            .setCancelable(true)
            .setOnCancelListener {
                finish()
            }
            .create()

        dialog.show()
    }
    
    /**
     * 处理接受请求
     */
    private fun handleAcceptRequest(requestId: String, senderId: String) {
        Log.i(TAG, "用户接受COS请求: $requestId")

        try {
            // 显示处理中提示
            showToast("正在创建COS v2通道...")

            // 通过CosRequestManager处理接受逻辑
            cosRequestManager.acceptRequest(requestId, senderId)
                .thenAccept { result ->
                    runOnUiThread {
                        when (result) {
                            is org.thoughtcrime.securesms.coscomm.data.CosRequestResult.Success -> {
                                showToast("COS v2通道建立成功！")
                                Log.i(TAG, "COS请求接受成功: ${result.requestId}")
                            }
                            is org.thoughtcrime.securesms.coscomm.data.CosRequestResult.Failure -> {
                                showToast("建立通道失败: ${result.errorMessage}")
                                Log.e(TAG, "COS请求接受失败: ${result.errorMessage}")
                            }
                        }
                        finish()
                    }
                }
                .exceptionally { throwable ->
                    runOnUiThread {
                        showToast("处理请求失败: ${throwable.message}")
                        Log.e(TAG, "处理接受请求异常", throwable)
                        finish()
                    }
                    null
                }

        } catch (e: Exception) {
            Log.e(TAG, "处理接受请求失败", e)
            showToast("处理请求失败: ${e.message}")
            finish()
        }
    }
    
    /**
     * 处理拒绝请求
     */
    private fun handleRejectRequest(requestId: String, senderId: String) {
        Log.i(TAG, "用户拒绝COS请求: $requestId")

        try {
            // 通过CosRequestManager处理拒绝逻辑
            cosRequestManager.rejectRequest(requestId, senderId, "用户拒绝")
                .thenAccept { result ->
                    runOnUiThread {
                        when (result) {
                            is org.thoughtcrime.securesms.coscomm.data.CosRequestResult.Success -> {
                                showToast("已拒绝COS请求")
                                Log.i(TAG, "COS请求拒绝成功: ${result.requestId}")
                            }
                            is org.thoughtcrime.securesms.coscomm.data.CosRequestResult.Failure -> {
                                showToast("拒绝请求失败: ${result.errorMessage}")
                                Log.e(TAG, "COS请求拒绝失败: ${result.errorMessage}")
                            }
                        }
                        finish()
                    }
                }
                .exceptionally { throwable ->
                    runOnUiThread {
                        showToast("处理拒绝失败: ${throwable.message}")
                        Log.e(TAG, "处理拒绝请求异常", throwable)
                        finish()
                    }
                    null
                }

        } catch (e: Exception) {
            Log.e(TAG, "处理拒绝请求失败", e)
            showToast("处理请求失败: ${e.message}")
            finish()
        }
    }
    
    /**
     * 显示Toast消息
     */
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
