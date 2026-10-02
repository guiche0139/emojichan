package com.aris.emojichan.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aris.emojichan.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 发送日志页：把整条链路的现场记录摆出来，一键复制、分享或导出成 TXT 文件。
 *
 * 出过错的那几份日志另存在「错误日志」里（见 [SendLog.saveErrorLog]），可以单独挑一份导出。
 */
class SendLogActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var errorButton: Button

    private val TAG = "自检"
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())

    /** 待导出的内容与建议文件名：等用户选完保存位置再写。 */
    private var pendingExport: Pair<String, String>? = null

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return@registerForActivityResult
        val outcome = runCatching {
            val out = contentResolver.openOutputStream(uri) ?: error("打不开目标文件")
            out.use { it.write(payload.first.toByteArray(Charsets.UTF_8)) }
        }
        val message = if (outcome.isSuccess) {
            getString(R.string.log_export_done)
        } else {
            getString(R.string.log_export_failed, outcome.exceptionOrNull()?.message ?: "?")
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色 / 深浅色跟主界面保持一致（overlay 必须在 setContentView 之前套）。
        com.aris.emojichan.UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_send_log)
        SendLog.init(this)

        header = findViewById(R.id.logHeader)
        logText = findViewById(R.id.logText)
        scroll = findViewById(R.id.logScroll)
        errorButton = findViewById(R.id.logErrorLogs)

        findViewById<Button>(R.id.logCopy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.logShare).setOnClickListener { share() }
        findViewById<Button>(R.id.logExport).setOnClickListener { exportCurrent() }
        findViewById<Button>(R.id.logRefresh).setOnClickListener { render() }
        errorButton.setOnClickListener { showErrorLogs() }
        findViewById<Button>(R.id.logClear).setOnClickListener {
            SendLog.clear()
            render()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        val text = SendLog.readAll()
        logText.text = if (text.isEmpty()) getString(R.string.log_empty) else leveled(text)
        header.text = getString(R.string.log_title) + " · " + SendLog.lineCount() + " 行 · " +
            getString(R.string.log_error_count, SendLog.errorCount())
        val saved = SendLog.errorLogCount()
        errorButton.visibility = if (saved == 0) View.GONE else View.VISIBLE
        if (saved > 0) errorButton.text = getString(R.string.log_error_logs, saved)
        // 打开就停在最新一条：出问题时大家想看的是最后几行。
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * 给级别词上色：错误红、警告橙，普通不上色。
     *
     * 只标级别词本身，正文颜色不动 —— 日志是等宽字体，颜色只用来让眼睛能扫到出问题的那几行。
     */
    private fun leveled(text: String): CharSequence {
        val builder = SpannableStringBuilder(text)
        val marks = listOf(SendLog.Level.WARN to R.color.warning, SendLog.Level.ERROR to R.color.danger)
        for ((level, colorRes) in marks) {
            val token = "[" + level.label + "]"
            val color = ContextCompat.getColor(this, colorRes)
            var index = text.indexOf(token)
            while (index >= 0) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    index,
                    index + token.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                index = text.indexOf(token, index + token.length)
            }
        }
        return builder
    }

    /** 当前日志导出成一个 TXT（走系统「保存到」面板，位置由用户挑）。 */
    private fun exportCurrent() {
        val text = SendLog.readAll()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val name = "EmojiChan-日志-" + stamp.format(Date()) + ".txt"
        pendingExport = text to name
        launchExport(name)
    }

    /** 已存的错误日志：挑一份导出。 */
    private fun showErrorLogs() {
        val files = SendLog.errorLogs()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.log_error_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = files.map { file ->
            file.name.removePrefix("EmojiChan-错误日志-").removeSuffix(".txt") + " · " +
                (file.length() / 1024) + " KB"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_error_files_title, files.size))
            .setItems(labels) { _, which -> exportErrorLog(files[which]) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun exportErrorLog(source: File) {
        val text = runCatching { source.readText() }.getOrNull()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.log_export_failed, source.name), Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = text to source.name
        launchExport(source.name)
    }

    private fun launchExport(name: String) {
        runCatching { exportFile.launch(name) }.onFailure {
            pendingExport = null
            Toast.makeText(this, getString(R.string.log_export_failed, it.message ?: "?"), Toast.LENGTH_SHORT).show()
        }
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
