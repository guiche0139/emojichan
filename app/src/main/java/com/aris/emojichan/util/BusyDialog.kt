package com.aris.emojichan.util

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aris.emojichan.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 干重活时挡一下的进度框。
 *
 * 只要一个操作会碰到「多个目标」（导入一整批图、压缩一批、导出、比对、删除、清缓存），
 * 就给它配这么一块：标题 + 一根进度条 + 一行「正在…第 N / M 张」+ 一行可选的小字提示。
 * 总数还没数出来时进度条转圈（不确定态），数出来再由 [update] 切成确定的百分比 ——
 * 一根停在 0 的条比转圈更像卡死。
 *
 * 刷新与关闭都走 UI 线程，调用方在 IO 协程里想怎么调怎么调。
 *
 * v0.2.003 起从「只有一行文字」改成「文字 + 进度条」（用户 m09930、m09940）。
 */
object BusyDialog {

    /** 进度框的把手：对话框 + 进度条 + 那一行说明。 */
    class Progress internal constructor(
        internal val dialog: AlertDialog,
        internal val bar: LinearProgressIndicator,
        internal val label: TextView
    )

    /**
     * 弹出进度框。
     *
     * [message] 是首行说明（「正在读取文件夹…」），[hint] 是底下那行小字
     * （「导入过程中退出应用会中断导入。」），没有就传 null。
     * [cancelable] 为 true 时可用返回键 / 点外部取消，配合 [setOnCancel] 收拾残局。
     */
    fun showProgress(
        activity: Activity,
        titleRes: Int,
        message: CharSequence,
        hint: CharSequence? = null,
        cancelable: Boolean = false
    ): Progress {
        val view = activity.layoutInflater.inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
        val label = view.findViewById<TextView>(R.id.progressLabel)
        val hintView = view.findViewById<TextView>(R.id.progressHint)
        label.text = message
        if (hint.isNullOrEmpty()) {
            hintView.visibility = View.GONE
        } else {
            hintView.text = hint
        }
        bar.isIndeterminate = true
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(view)
            .setCancelable(cancelable)
            .create()
        dialog.show()
        return Progress(dialog, bar, label)
    }

    /**
     * 刷新进度。[total] <= 0 表示总数还不知道，进度条留在转圈态、只换文字；
     * 其余情况切成确定进度并卡在 0..total 之间。[message] 传 null 就只动进度条。
     */
    fun update(activity: Activity, progress: Progress, done: Int, total: Int, message: CharSequence? = null) {
        activity.runOnUiThread {
            if (!progress.dialog.isShowing || activity.isFinishing) return@runOnUiThread
            if (total <= 0) {
                if (!progress.bar.isIndeterminate) progress.bar.isIndeterminate = true
            } else {
                if (progress.bar.isIndeterminate) progress.bar.isIndeterminate = false
                progress.bar.max = total
                progress.bar.setProgressCompat(done.coerceIn(0, total), true)
            }
            if (message != null) progress.label.text = message
        }
    }

    fun dismiss(activity: Activity, progress: Progress) {
        activity.runOnUiThread {
            if (progress.dialog.isShowing && !activity.isFinishing) progress.dialog.dismiss()
        }
    }

    /** 只有 [showProgress] 传了 cancelable = true 才有意义：用户取消时干点什么（比如掐掉协程）。 */
    fun setOnCancel(progress: Progress, action: () -> Unit) {
        progress.dialog.setOnCancelListener { action() }
    }
}
