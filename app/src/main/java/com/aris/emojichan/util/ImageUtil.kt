package com.aris.emojichan.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtil {
    private const val EMOJI_DIR = "emojis"

    fun getEmojiDir(context: Context): File {
        val dir = File(context.filesDir, EMOJI_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun copyImageToInternal(context: Context, uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val ext = getImageExtension(context, uri)
                // 加随机后缀：纯时间戳在同一毫秒内连续导入会撞名，后者覆盖前者，
                // 结果是两条记录指向同一个文件，删掉其中一个另一个也会失效。
                val fileName = "emoji_${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}.$ext"
                val destFile = File(getEmojiDir(context), fileName)
                FileOutputStream(destFile).use { output ->
                    stream.copyTo(output)
                }
                destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 根据 Uri 的 MIME 类型推断图片扩展名，优先保证 GIF/PNG/WEBP 不被误存为 .jpg。
     * MIME 不可用时回退到文件名扩展名，最终兜底 .jpg。
     */
    private fun getImageExtension(context: Context, uri: Uri): String {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            return when (mime) {
                "image/gif" -> "gif"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
        }
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
        return if (!ext.isNullOrBlank() && ext.length <= 5) ext.lowercase() else "jpg"
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
