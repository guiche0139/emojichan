package com.aris.emojichan.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object ImageUtil {
    private const val EMOJI_DIR = "emojis"

    /** 单个表情的体积上限：超过它多半是误选的视频或超大图，宁可不导入（emc-1-019）。 */
    private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

    fun getEmojiDir(context: Context): File {
        val dir = File(context.filesDir, EMOJI_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun copyImageToInternal(context: Context, uri: Uri): String? {
        var destFile: File? = null
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { raw ->
                // 读文件头需要能 mark/reset 的流，而且必须拿同一个流去写文件：
                // 换一个流会把已经读掉的头几个字节吞掉，GIF 存下来就成了坏文件。
                val stream = if (raw.markSupported()) raw else BufferedInputStream(raw, 8192)
                // 先看文件头：认不出图片、而且上游声明的类型也不是 image/* 时直接拒收。
                // 分享面板里什么文件都能塞进 image/*，不挡一道就会往库里收垃圾（emc-1-019）。
                val sniffed = sniffImageExtension(stream)
                val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                if (sniffed == null && declared?.startsWith("image/") != true) {
                    throw IOException("上游内容不是图片（声明类型=" + declared + "），放弃导入")
                }
                val ext = getImageExtension(context, uri, stream, sniffed)
                // 加随机后缀：纯时间戳在同一毫秒内连续导入会撞名，后者覆盖前者，
                // 结果是两条记录指向同一个文件，删掉其中一个另一个也会失效。
                val fileName = "emoji_${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}.$ext"
                val target = File(getEmojiDir(context), fileName)
                destFile = target
                FileOutputStream(target).use { output -> copyWithLimit(stream, output) }
                target.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 写了一半的文件绝不能留在库里 —— 列表里会多出一张打不开的空图（emc-1-019）。
            destFile?.let { if (it.exists()) it.delete() }
            null
        }
    }

    /**
     * 边复制边卡上限：上游 Uri 是外部给的（相册、分享面板），
     * 可能是一段没有尽头的流，也可能是 0 字节的空内容（emc-1-019）。
     */
    private fun copyWithLimit(stream: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            written += read
            if (written > MAX_IMPORT_BYTES) {
                throw IOException("文件超过 " + (MAX_IMPORT_BYTES / 1024 / 1024) + " MB，放弃导入")
            }
            output.write(buffer, 0, read)
        }
        if (written == 0L) throw IOException("上游给的是空内容，放弃导入")
    }

    /**
     * 推断图片扩展名，优先保证 GIF/PNG/WEBP 不被误存为 .jpg（存错扩展名 = 丢掉动画）。
     * 顺序：文件头 → 声明的 MIME → 文件名扩展名 → 兜底 .jpg。
     * 文件头排第一是刻意的：上游经常谎报类型（GIF 声明成 image/png），
     * 而扩展名决定后面走静态图还是动图分支，信错就把动画丢了（emc-1-037）。
     */
    private fun getImageExtension(
        context: Context,
        uri: Uri,
        stream: InputStream,
        sniffed: String?
    ): String {
        val byHead = sniffed ?: sniffImageExtension(stream)
        if (byHead != null) return byHead
        val byMime = when (context.contentResolver.getType(uri)?.lowercase()) {
            "image/gif" -> "gif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> null
        }
        if (byMime != null) return byMime
        var displayName: String? = null
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(idx)
            }
        }
        val ext = displayName?.substringAfterLast('.', "")
        // DISPLAY_NAME 完全由上游决定，只留字母数字再拼文件名（emc-1-034）。
        val safe = ext?.filter { it.isLetterOrDigit() }
        return if (!safe.isNullOrBlank() && safe.length <= 5) safe.lowercase() else "jpg"
    }

    /**
     * 看开头几个字节判类型，然后再把读掉的部分还回去（mark/reset）。
     * 只认得出图片格式，认不出返回 null，交给调用方继续猜。
     */
    private fun sniffImageExtension(stream: InputStream): String? {
        if (!stream.markSupported()) return null
        return try {
            stream.mark(16)
            val head = ByteArray(12)
            val read = stream.read(head)
            stream.reset()
            if (read < 4) return null
            when {
                head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() -> "gif"
                head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() -> "png"
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
                head[0] == 0x42.toByte() && head[1] == 0x4D.toByte() -> "bmp"
                head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                    head[8] == 0x57.toByte() && head[9] == 0x45.toByte() -> "webp"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取图片宽高（不解码完整位图，仅读边界信息）。 */
    fun getImageDimensions(filePath: String): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)
            options.outWidth to options.outHeight
        } catch (e: Exception) {
            e.printStackTrace()
            0 to 0
        }
    }

    /**
     * 删除文件。
     *
     * 返回「文件最终是否已不存在」，而不是 [File.delete] 的原始返回值：
     * 文件本来就不存在同样算成功，避免把「已不存在」误判成删除失败。
     *
     * @return true 表示文件已不存在（删除成功或原本就没有）；false 表示文件仍在。
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) true else file.delete() || !file.exists()
        } catch (e: Exception) {
            e.printStackTrace()
            !File(filePath).exists()
        }
    }

    /** 列出表情目录下的全部文件绝对路径。 */
    fun listEmojiFiles(context: Context): List<String> =
        getEmojiDir(context).listFiles()?.map { it.absolutePath } ?: emptyList()

    /**
     * 清理孤儿文件：目录里存在、但数据库没有任何记录引用的文件
     * （导入中断、记录被手工删除等场景留下的残留），避免白占存储。
     *
     * @param validPaths 数据库当前引用的全部文件路径。
     * @param minAgeMillis 只清理「最后修改时间早于该毫秒数」的文件，避免误删正在导入的文件。
     * @return 实际清理掉的文件数。
     */
    fun cleanOrphanFiles(
        context: Context,
        validPaths: Set<String>,
        minAgeMillis: Long = 60_000L
    ): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        getEmojiDir(context).listFiles()?.forEach { file ->
            val tooNew = now - file.lastModified() < minAgeMillis
            if (file.isFile && !tooNew && file.absolutePath !in validPaths) {
                if (deleteFile(file.absolutePath)) removed++
            }
        }
        return removed
    }

    fun getFileSize(filePath: String): Long {
        return File(filePath).length()
    }

    fun isImageFile(filePath: String): Boolean {
        val lower = filePath.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp")
    }

    fun isGif(filePath: String): Boolean {
        return filePath.lowercase().endsWith(".gif")
    }
}
