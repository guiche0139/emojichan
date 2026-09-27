package com.aris.emojichan.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aris.emojichan.R

/**
 * 发送日志页：把整条链路的现场记录摆出来，一键复制或分享，用户直接发给我就能定位问题。
 */
class SendLogActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView

    private val TAG = "自检"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色 / 深浅色跟主界面保持一致（overlay 必须在 setContentView 之前套）。
        com.aris.emojichan.UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_send_log)
        SendLog.init(this)

        header = findViewById(R.id.logHeader)
        logText = findViewById(R.id.logText)
        scroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.logCopy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.logClipCheck).setOnClickListener { clipCheck() }
        findViewById<Button>(R.id.logShare).setOnClickListener { share() }
        findViewById<Button>(R.id.logRefresh).setOnClickListener { render() }
        findViewById<Button>(R.id.logClear).setOnClickListener {
            SendLog.clear()
            render()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        val text = SendLog.readAll()
        logText.text = if (text.isEmpty()) getString(R.string.log_empty) else text
        header.text = getString(R.string.log_title) + " · " + SendLog.lineCount() + " 行"
        // 打开就停在最新一条：出问题时大家想看的是最后几行。
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 剪贴板自检：把系统里现在这一份剪贴板原样摊开，并对里面的 uri 跑一遍
     * 「接收方会怎么取」的模拟（含读授权检查）。
     *
     * 用法：复制动图 → 去微信粘贴发送 → 回到这里点一下，日志里就有完整现场。
     */
    private fun clipCheck() {
        SendLog.d(TAG, "----- 手动剪贴板自检 -----")
        SendLog.d(TAG, ClipForensics.clipboardReport(this))
        val clipUri = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
        if (clipUri == null) {
            SendLog.d(TAG, "剪贴板第 0 条没有 uri（纯文本，或者里面已经没有内容）")
        } else {
            SendLog.d(TAG, ClipForensics.uriReport(this, clipUri))
        }
        render()
        Toast.makeText(this, R.string.log_clip_checked, Toast.LENGTH_SHORT).show()
    }

    private fun copyAll() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("emoji-send-log", SendLog.readAll()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 分享前先确认一次：用户可能在完全没看过内容的情况下把日志发出去，
     * 而日志里带着他什么时候在哪个应用做了什么（内容已打码，见 [SendLog.mask]）。
     */
    private fun share() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_share_confirm_title, SendLog.lineCount()))
            .setMessage(R.string.log_share_confirm_message)
            .setPositiveButton(R.string.log_share) { _, _ -> doShare() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun doShare() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
            putExtra(Intent.EXTRA_TEXT, SendLog.readAll())
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.log_share))) }
            .onFailure { Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_SHORT).show() }
    }
}