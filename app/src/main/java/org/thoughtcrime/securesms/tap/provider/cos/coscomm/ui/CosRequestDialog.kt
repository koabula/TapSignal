/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.provider.cos.coscomm.ui

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * COS请求相关的对话框工具类
 * 提供发送和接收COS v2模式请求的对话框
 */
object CosRequestDialog {

    /**
     * 显示发送COS v2模式请求的确认对话框
     * 
     * @param context 上下文
     * @param recipient 接收方
     * @param onConfirm 用户确认后的回调
     */
    fun showSendRequestDialog(
        context: Context,
        recipient: Recipient,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cos_request_dialog_title)
            .setMessage(R.string.cos_request_dialog_message)
            .setPositiveButton(R.string.cos_request_dialog_agree) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(R.string.cos_request_dialog_cancel, null)
            .show()
    }

    /**
     * 显示接收到COS v2模式请求的确认对话框
     * 
     * @param context 上下文
     * @param senderName 发送方名称
     * @param onAccept 用户接受后的回调
     * @param onReject 用户拒绝后的回调
     */
    fun showReceiveRequestDialog(
        context: Context,
        senderName: String,
        onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        val message = context.getString(R.string.cos_response_dialog_message, senderName)
        
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cos_response_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.cos_response_dialog_agree) { _, _ ->
                onAccept()
            }
            .setNegativeButton(R.string.cos_response_dialog_reject) { _, _ ->
                onReject()
            }
            .setCancelable(false) // 防止用户意外取消
            .show()
    }

    /**
     * 显示简单的信息对话框
     * 
     * @param context 上下文
     * @param title 标题资源ID
     * @param message 消息资源ID
     */
    fun showInfoDialog(
        context: Context,
        @StringRes title: Int,
        @StringRes message: Int
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 显示错误对话框
     *
     * @param context 上下文
     * @param message 错误消息
     */
    fun showErrorDialog(
        context: Context,
        message: String
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 显示断开COS v2模式的确认对话框
     *
     * @param context 上下文
     * @param recipient 接收方
     * @param onConfirm 用户确认后的回调
     */
    fun showDisconnectRequestDialog(
        context: Context,
        recipient: Recipient,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.cos_disconnect_dialog_title)
            .setMessage(R.string.cos_disconnect_dialog_message)
            .setPositiveButton(R.string.cos_disconnect_dialog_confirm) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(R.string.cos_disconnect_dialog_cancel, null)
            .show()
    }
}
