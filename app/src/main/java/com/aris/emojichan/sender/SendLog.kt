package com.aris.emojichan.sender

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 发送链路的现场记录（内存环形缓冲 + 落盘）。
 *
 * 这条链路的每一步都发生在别的应用之上：悬浮窗、无障碍服务、微信的「选择聊天」页。
 * 一旦卡住，界面上什么也看不到 —— 所以每一步都写下来，用户导出后一次就能看清是哪一环断的。
 *
 * 写入走单线程队列，不占主线程；内存里只留最近 MAX_LINES 条，
 * 文件超过 MAX_FILE_BYTES 就砍掉前一半，避免无限长。
 */
object SendLog {

    private const val FILE_NAME = "send_log.txt"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 512 * 1024

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "send-log-writer").apply { isDaemon = true }
    }

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            file = target
            // 日志第一行写清版本，出问题时光看日志就能确认对应哪一次构建。
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            d("版本", "应用启动，版本 v" + version)
            // 把上一次运行留下的日志尾巴读回来，一次导出就能看到全过程。
            runCatching {
                if (target.exists()) {
                    lines.clear()
                    lines.addAll(target.readLines().takeLast(MAX_LINES))
                }
            }
        }
    }

    fun d(tag: String, message: String) {
        val line: String
        val target: File?
        synchronized(lock) {
            line = format.format(Date()) + "  [" + tag + "]  " + message
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            target = file
        }
        val sink = target ?: return
        runCatching { writer.execute { append(sink, line) } }
    }

    private fun append(target: File, line: String) {
        runCatching {
            if (target.length() > MAX_FILE_BYTES) {
                val kept = target.readLines().takeLast(MAX_LINES / 2)
                target.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            target.appendText(line + "\n")
        }
    }

    fun readAll(): String = synchronized(lock) { lines.joinToString("\n") }

    fun lineCount(): Int = synchronized(lock) { lines.size }

    fun clear() {
        val target: File?
        synchronized(lock) {
            lines.clear()
            target = file
        }
        val sink = target ?: return
        runCatching { writer.execute { runCatching { sink.writeText("") } } }
    }
}