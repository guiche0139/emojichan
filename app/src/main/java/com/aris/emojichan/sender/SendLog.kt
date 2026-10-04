package com.aris.emojichan.sender

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 发送链路的现场记录（内存环形缓冲 + 落盘）。
 *
 * 这条链路的每一步都发生在别的应用之上：悬浮窗、无障碍服务、微信的「选择聊天」页。
 * 一旦卡住，界面上什么也看不到 —— 所以每一步都写下来，用户导出后一次就能看清是哪一环断的。
 *
 * 每条记录分三级：错误 = 这一趟动作最后没做成，警告 = 没按预期走但自己兜住了，其余是普通现场。
 * 出现错误时自动把当前整段日志另存一份到 filesDir/error_logs/，不必守着界面等它复现。
 *
 * 写入走单线程队列，不占主线程；内存里只留最近 MAX_LINES 条，
 * 文件超过 MAX_FILE_BYTES 就砍掉前一半，避免无限长。
 */
object SendLog {

    /** 记录级别：写在每行开头的方括号里，日志页按级别上色。 */
    enum class Level(val label: String) {
        INFO("普通"),
        WARN("警告"),
        ERROR("错误")
    }

    private const val FILE_NAME = "send_log.txt"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 512 * 1024

    private const val ERROR_DIR = "error_logs"
    private const val ERROR_KEEP = 20
    private const val ERROR_SAVE_GAP_MS = 30_000L

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    // 时间戳固定按 Locale.ROOT 格式化：日志是给人和 grep 看的，跟着系统语言变没有意义 ——
    // 泰语环境会写佛历年份（yyyy → 2569），阿拉伯语环境会写出非 ASCII 数字，反而看不清。
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "send-log-writer").apply { isDaemon = true }
    }

    @Volatile
    private var file: File? = null

    @Volatile
    private var errorDir: File? = null

    /** 上一次存错误日志的时刻（只在写入线程里读，init 换数据目录时重置）。 */
    @Volatile
    private var lastErrorSave = 0L

    fun init(context: Context) {
        val app = context.applicationContext
        val target = File(app.filesDir, FILE_NAME)
        synchronized(lock) {
            // 错误日志目录每次都指一遍：重装或测试会换数据目录。
            errorDir = File(app.filesDir, ERROR_DIR)
            if (file == target) return
            file = target
            lastErrorSave = 0L
            // 把上一次运行留下的日志尾巴读回来，一次导出就能看到全过程。
            runCatching {
                if (target.exists()) {
                    lines.clear()
                    lines.addAll(target.readLines().takeLast(MAX_LINES))
                }
            }
            // 日志第一行写清版本，出问题时光看日志就能确认对应哪一次构建。
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
            write(Level.INFO, "版本", "应用启动，版本 v" + version)
        }
    }

    /** 普通：这一趟只是把现场记下来。 */
    fun d(tag: String, message: String) = write(Level.INFO, tag, message)

    /** 警告：没按预期走，但自己兜住了或者提前退出了。 */
    fun w(tag: String, message: String) = write(Level.WARN, tag, message)

    /** 错误：这一趟动作最后没做成；顺手存一份错误日志。 */
    fun e(tag: String, message: String) = write(Level.ERROR, tag, message)

    private fun write(level: Level, tag: String, message: String) {
        val line: String
        val target: File?
        synchronized(lock) {
            line = format.format(Date()) + "  [" + level.label + "]  [" + tag + "]  " + message
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            target = file
        }
        val sink = target ?: return
        runCatching {
            writer.execute {
                append(sink, line)
                if (level == Level.ERROR) saveErrorLog()
            }
        }
    }

    /**
     * 把聊天对象名、输入框文字这类内容打码之后再写日志（emc-1-028）。
     *
     * 日志会落盘成 filesDir/send_log.txt：它会被系统云备份带走，也能被用户一键分享出去，
     * 不该出现完整的联系人昵称或草稿正文。这里只留「稳定指纹 + 字数」——
     * 指纹足以判断两处读到的是不是同一个名字（排查认错人时正是要看这个），
     * 但读不出这个名字到底是什么，也不泄露首字。
     */
    fun mask(text: String?): String {
        if (text.isNullOrEmpty()) return "（空）"
        val fingerprint = Integer.toHexString(text.hashCode())
        return "＊" + fingerprint + "（" + text.length + " 字）"
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

    /**
     * 出错时把当前整段日志另存一份（在写入线程里做，不占调用方）。
     *
     * 节流 ERROR_SAVE_GAP_MS：一条链路上常连着抛好几个错，没必要每个都存一个文件；
     * 只留最新 ERROR_KEEP 份，按文件名里的时间戳倒序砍掉更旧的。
     */
    private fun saveErrorLog() {
        val now = System.currentTimeMillis()
        if (now - lastErrorSave < ERROR_SAVE_GAP_MS) return
        val dir = errorDir ?: return
        val body = synchronized(lock) {
            if (lines.isEmpty()) return
            lines.joinToString("\n")
        }
        lastErrorSave = now
        runCatching {
            dir.mkdirs()
            // 这两个 SimpleDateFormat 是 object 级单例、天生非线程安全，而 saveErrorLog 会从
            // 写日志的线程、主线程、Binder 线程一起进来（write 里那次在锁内，这里原来漏了）：
            // 撞在一起会抛，又被外层 runCatching 吞掉 —— 表现就是错误日志偶尔少一份（emc-2-032）。
            val name: String
            val head: String
            synchronized(lock) {
                name = "EmojiChan-错误日志-" + stamp.format(Date(now)) + ".txt"
                head = "# EmojiChan 错误日志 · 保存于 " + format.format(Date(now)) +
                    " · 当前会话最近 " + lineCount() + " 行\n"
            }
            File(dir, name).writeText(head + body + "\n")
            dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(ERROR_KEEP)
                ?.forEach { runCatching { it.delete() } }
        }
    }

    /** 已保存的错误日志，最近的在最前面。 */
    fun errorLogs(): List<File> {
        val dir = errorDir ?: return emptyList()
        return runCatching {
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun errorLogCount(): Int = errorLogs().size

    /** 当前内存里这批记录中有多少条错误（日志页标题上用）。 */
    fun errorCount(): Int = synchronized(lock) {
        lines.count { it.contains("[" + Level.ERROR.label + "]") }
    }

    /** 等写入队列排空：导出、自测时避免读到还没落盘的内容。 */
    fun awaitIdle(timeoutMs: Long = 2000) {
        val latch = CountDownLatch(1)
        runCatching { writer.execute { latch.countDown() } }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
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
