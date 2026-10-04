package com.aris.emojichan.sender

import android.content.ClipboardManager
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * 剪贴板链路的取证工具：把「复制时是什么格式 / 剪贴板里是什么格式 / 取用方拿走了什么」
 * 这三处分别发生在三个进程里的现场，变成可以直接读的文字。
 *
 * 为什么要做这件事：微信粘出来是静图，至少有四种互不相同的原因 ——
 *   a. 交出去的就是静图（文件本身就是单帧，或声明的类型不是 gif）；
 *   b. 微信根本没来看这个 uri（它的粘贴只认文本，图片剪贴板对它等于不存在）；
 *   c. 微信按别的类型来取、被提供者拒了（FileProvider 只放行自己申明的那个类型）；
 *   d. 微信取了完整 GIF，只是自己只画第一帧。
 * 只看微信里的结果永远分不清是哪一种。[fileReport] / [clipboardReport] / [uriReport]
 * 加上 [LoggingFileProvider] 记下的取图流水，正好把这四种情况分开。
 */
object ClipForensics {

    private const val TAG = "取证"

    /** 取证时最多读文件开头这么多字节（文件头与 GIF 帧头都在最前面，够用且不会 OOM）。 */
    private const val MAX_FORENSICS_BYTES = 1024 * 1024

    /** 取图流水里要盯着的接收方。 */
    private val WATCHED = listOf("com.tencent.mm", "com.tencent.mobileqq")

    /**
     * 复制完之后在后台把三份现场写进日志。
     * 要读整个文件、还要解码首帧，不能占主线程（复制按钮是在主线程被点的）。
     */
    fun reportAsync(context: Context, file: File, uri: Uri) {
        val app = context.applicationContext
        Thread {
            runCatching { SendLog.d(TAG, fileReport(file)) }
            runCatching { SendLog.d(TAG, clipboardReport(app)) }
            runCatching { SendLog.d(TAG, uriReport(app, uri)) }
        }.apply {
            isDaemon = true
            name = "clip-forensics"
        }.start()
    }

    // ---------------------------------------------------------------- 一、磁盘上的字节

    /** 图片文件本身长什么样：扩展名、大小、文件头、GIF 帧数、系统解码器怎么看它。 */
    fun fileReport(file: File): String {
        if (!file.exists()) return "文件：不存在 " + file.absolutePath
        // 只读文件开头这一小段：这是取证工具，用户导入的超大图也可能走到这里，
        // 全量 readBytes() 在低端机上会直接 OOM（emc-1-033）。文件头与 GIF 帧头都在最前面。
        val bytes = runCatching {
            val limit = minOf(file.length(), MAX_FORENSICS_BYTES.toLong()).toInt().coerceAtLeast(1)
            file.inputStream().use { input ->
                val buffer = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = input.read(buffer, read, limit - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull()
            ?: return "文件：" + file.name + " ｜ 读不出来（权限或已损坏）"
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val frames = gifFrameCount(bytes)
        val framesText = when {
            frames < 0 -> "按字节看不是 GIF"
            frames > 1 -> "GIF 帧数 " + frames + "（文件里确实是动图）"
            frames == 1 -> "GIF 帧数 1（文件里只有一帧，是静图）"
            else -> "GIF 帧数 0（不是合法 GIF）"
        }
        val truncated = if (file.length() > bytes.size) "（只读了前 " + bytes.size + " 字节）" else ""
        return "文件：" + file.name +
                " ｜ 扩展名 ." + ext +
                " ｜ " + file.length() + " 字节" + truncated +
                " ｜ 头 " + hex(bytes, 8) + " (\"" + ascii(bytes, 6) + "\")" +
                " ｜ " + framesText +
                decoderReport(file)
    }

    /** 系统解码器的看法：它认不认这是动图（和微信属于同一套系统能力，参考价值高）。 */
    private fun decoderReport(file: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ""
        val text = runCatching {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            if (drawable is AnimatedImageDrawable) {
                // 注意：AnimatedImageDrawable 只暴露「重复次数/是否在播」，没有帧数 API，
                // 帧数只能在字节层面数（见 fileReport）。
                "动图（系统给了 AnimatedImageDrawable，重复次数 " + drawable.repeatCount +
                        "，正在播放 " + drawable.isRunning + "）"
            } else {
                "静态图（" + drawable.javaClass.simpleName + "）"
            }
        }.getOrElse { "解不开：" + describe(it) }
        return " ｜ 系统解码器：看它是" + text
    }

    // ---------------------------------------------------------------- 二、剪贴板里的内容

    /** 系统剪贴板这一刻到底存着什么（这是系统那一份，不是我们手里的对象）。 */
    fun clipboardReport(context: Context): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { manager.primaryClip }.getOrNull()
            ?: return "系统剪贴板：读不到内容（是空的，或本应用当时不在前台、被系统挡了）"
        val desc = clip.description
        val types = (0 until desc.mimeTypeCount).joinToString("、") { desc.getMimeType(it) }
        val builder = StringBuilder()
        builder.append("系统剪贴板：条目数 ").append(clip.itemCount)
            .append(" ｜ 声明类型 [").append(if (types.isEmpty()) "空" else types).append("]")
            .append(" ｜ 标签 ").append(desc.label ?: "-")
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.text
            builder.append("\n    条目 ").append(i)
                .append("：uri=").append(item.uri?.toString() ?: "-")
                .append(" ｜ text=").append(
                    // 剪贴板里常常是聊天记录 / 短信 / 刚复制的正文，而这份日志是能被用户
                    // 一键导出、分享出去的：只留指纹和字数，不写前 40 个字（emc-2-032）。
                    if (text == null) "-" else SendLog.mask(text.toString())
                )
                .append(" ｜ html=").append(if (item.htmlText == null) "无" else "有")
                .append(" ｜ intent=").append(if (item.intent == null) "无" else "有")
        }
        return builder.toString()
    }

    // ---------------------------------------------------------------- 三、接收方会拿到什么

    /**
     * 接收方按不同方式取图时会拿到什么：普通字节流、以及按类型取的四条路径。
     * 「被拒」那一行最关键 —— FileProvider 只放行和自身类型相容的请求，
     * 万一微信是按 image/png 来取的，它拿到的就是异常、于是粘贴失败或退化成静图。
     */
    fun uriReport(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val builder = StringBuilder("URI 自检：").append(uri)
        builder.append("\n    resolver.getType = ")
            .append(runCatching { resolver.getType(uri) }.getOrNull() ?: "null")
        builder.append("\n    按字节流取：").append(runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8)
                val n = input.read(buf)
                "成功，前 " + maxOf(n, 0) + " 字节 = " + hex(buf, maxOf(n, 0)) +
                        " (\"" + ascii(buf, maxOf(n, 0)) + "\")"
            } ?: "返回 null"
        }.getOrElse { "失败：" + describe(it) })
        for (filter in listOf("image/*", "image/gif", "image/png", "*/*")) {
            builder.append("\n    按类型取 ").append(filter).append("：").append(runCatching {
                resolver.openTypedAssetFileDescriptor(uri, filter, null)?.use { afd ->
                    // AssetFileDescriptor 没有「类型」这个东西（类型由 filter 决定），
                    // 这里能看到的是长度和底层 fd 的真实大小 —— 两者不一致就说明被截断/包装过。
                    "成功，长度 " + afd.length + " 字节（声明 " + afd.declaredLength + "），" +
                            "fd 真实大小 " + runCatching { afd.parcelFileDescriptor.statSize }.getOrDefault(-1L)
                } ?: "返回 null"
            }.getOrElse { "被拒：" + describe(it) })
        }
        builder.append("\n    ").append(permissionReport(context, uri))
        return builder.toString()
    }

    /**
     * 我们给出去的读授权到底生效没有。
     * 微信如果连读权限都没有，它既不会出现在取图流水里，也粘不出任何东西 ——
     * 这一行就是用来把「没授权」和「没来取」分开的。
     */
    private fun permissionReport(context: Context, uri: Uri): String {
        val parts = WATCHED.map { pkg ->
            val uid = runCatching {
                context.packageManager.getApplicationInfo(pkg, 0).uid
            }.getOrNull()
            if (uid == null) {
                pkg + "（未安装）"
            } else {
                val state = runCatching {
                    context.checkUriPermission(
                        uri,
                        android.os.Process.myPid(),
                        uid,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.getOrDefault(-1)
                pkg + "(uid " + uid + ")=" +
                        if (state == android.content.pm.PackageManager.PERMISSION_GRANTED) "有读权限" else "没有读权限"
            }
        }
        return "读授权：" + parts.joinToString(" ｜ ")
    }

    // ---------------------------------------------------------------- 工具

    /** GIF 的帧数（按块结构数图像描述符）。返回 -1 表示这不是 GIF。 */
    private fun gifFrameCount(data: ByteArray): Int {
        if (data.size < 13) return -1
        val signature = String(data, 0, 6, Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") return -1
        val packed = data[10].toInt() and 0xFF
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 0x07) + 1))
        var frames = 0
        while (pos < data.size) {
            when (data[pos].toInt() and 0xFF) {
                0x2C -> {                                  // 图像描述符 = 一帧
                    frames++
                    if (pos + 10 > data.size) return frames
                    val local = data[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (local and 0x80 != 0) pos += 3 * (1 shl ((local and 0x07) + 1))
                    if (pos >= data.size) return frames
                    pos++                                  // LZW 最小码长
                    pos = skipSubBlocks(data, pos)
                }
                0x21 -> { pos = skipSubBlocks(data, pos + 2) }   // 扩展块：引入符 + 标签
                0x3B -> return frames                      // 结束符
                else -> return frames
            }
        }
        return frames
    }

    private fun skipSubBlocks(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos < data.size) {
            val length = data[pos].toInt() and 0xFF
            pos++
            if (length == 0) return pos
            pos += length
        }
        return pos
    }

    private fun hex(data: ByteArray, count: Int): String =
        (0 until minOf(count, data.size)).joinToString(" ") {
            String.format(Locale.ROOT, "%02X", data[it])
        }

    private fun ascii(data: ByteArray, count: Int): String =
        (0 until minOf(count, data.size)).joinToString("") {
            val c = data[it].toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "."
        }

    private fun describe(t: Throwable): String {
        val message = t.message?.substringBefore('\n')?.trim()?.take(90)
        return if (message.isNullOrEmpty()) t.javaClass.simpleName
        else t.javaClass.simpleName + ": " + message
    }
}
