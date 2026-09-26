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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_log)
        SendLog.init(this)

        header = findViewById(R.id.logHeader)
        logText = findViewById(R.id.logText)
        scroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.logCopy).setOnClickListener { copyAll() }
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

    private fun copyAll() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("emoji-send-log", SendLog.readAll()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
            putExtra(Intent.EXTRA_TEXT, SendLog.readAll())
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.log_share))) }
            .onFailure { Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_SHORT).show() }
    }
}