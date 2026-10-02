package com.aris.emojichan.util

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 干重活时挡一下的进度框。
 *
 * 压缩、导出、导入这类要跑几秒的活都得有这么一块：标题写死在调用处，
 * 正文按「第几张」刷新。刷新和关闭都走 UI 线程，调用方在协程里想怎么调怎么调。
 */
object BusyDialog {

    fun show(activity: Activity, titleRes: Int, message: CharSequence, cancelable: Boolean = false): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(cancelable)
            .create()
        dialog.show()
        return dialog
    }

    fun update(activity: Activity, dialog: AlertDialog, message: CharSequence) {
        activity.runOnUiThread {
            if (dialog.isShowing && !activity.isFinishing) dialog.setMessage(message)
        }
    }

    fun dismiss(activity: Activity, dialog: AlertDialog) {
        activity.runOnUiThread {
            if (dialog.isShowing && !activity.isFinishing) dialog.dismiss()
        }
    }
}
