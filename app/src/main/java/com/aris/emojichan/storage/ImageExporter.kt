package com.aris.emojichan.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 要导出的一个表情：显示名 + 磁盘路径。 */
data class ExportTarget(val name: String, val path: String)

/**
 * 把表情图片导出到用户选的位置。
 *
 * 只读不写库里：导出完原图还在，跟「删除」是两回事。两种落地方式 ——
 * 逐张写进用户选的文件夹（[copyToTree]），或者打成一个 zip（[zip]）。
 *
 * 文件名一律用表情名 + 原扩展名，重名的加 (2)(3)；表情名里可能有斜杠、冒号这些
 * 文件系统不接受的字符，统一换成下划线，否则 createDocument 会直接抛异常。
 */
object ImageExporter {

    data class Report(val written: Int, val failed: Int, val bytes: Long)

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t')

    fun mimeOf(path: String): String = when (StorageUsage.formatOf(path)) {
        "JPG" -> "image/jpeg"
        "PNG" -> "image/png"
        "GIF" -> "image/gif"
        "WEBP" -> "image/webp"
        "BMP" -> "image/bmp"
        else -> "application/octet-stream"
    }

    fun extensionOf(path: String): String {
        val dot = path.lastIndexOf('.')
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        if (dot <= slash || dot == path.length - 1) return ""
        return path.substring(dot + 1).lowercase()
    }

    /** 导出时用的文件名：表情名 + 原扩展名。 */
    fun displayName(target: ExportTarget): String {
        val base = sanitize(target.name).ifEmpty { "emoji" }
        val ext = extensionOf(target.path)
        return if (ext.isEmpty()) base else base + "." + ext
    }

    private fun sanitize(name: String): String =
        name.trim().map { if (ILLEGAL.contains(it)) '_' else it }.joinToString("").trim(' ', '.')

    private fun uniqueName(raw: String, used: MutableMap<String, Int>): String {
        val times = (used[raw] ?: 0) + 1
        used[raw] = times
        if (times == 1) return raw
        val dot = raw.lastIndexOf('.')
        return if (dot <= 0) raw + " (" + times + ")" else raw.substring(0, dot) + " (" + times + ")" + raw.substring(dot)
    }

    /** 打成一个 zip。条目名就是导出文件名，压缩方式用默认的 deflate。 */
    fun zip(output: OutputStream, targets: List<ExportTarget>): Report {
        var written = 0
        var failed = 0
        var bytes = 0L
        val used = HashMap<String, Int>()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            targets.forEach { target ->
                val source = File(target.path)
                try {
                    if (!source.isFile) throw IllegalStateException("源文件不在了：" + target.path)
                    zip.putNextEntry(ZipEntry(uniqueName(displayName(target), used)))
                    source.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    written++
                    bytes += source.length()
                } catch (e: Exception) {
                    e.printStackTrace()
                    failed++
                    runCatching { zip.closeEntry() }
                }
            }
        }
        return Report(written, failed, bytes)
    }

    /**
     * 逐张写进用户选的文件夹（SAF 树）。
     *
     * 每张单独 try：一张写不进去不该把整批带停 —— 用户选完目录才发现前功尽弃最难接受。
     */
    fun copyToTree(
        context: Context,
        treeUri: Uri,
        targets: List<ExportTarget>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Report {
        val resolver = context.contentResolver
        var written = 0
        var failed = 0
        var bytes = 0L
        val used = HashMap<String, Int>()
        targets.forEachIndexed { index, target ->
            onProgress(index + 1, targets.size)
            val source = File(target.path)
            try {
                if (!source.isFile) throw IllegalStateException("源文件不在了：" + target.path)
                val name = uniqueName(displayName(target), used)
                val document = DocumentsContract.createDocument(resolver, treeUri, mimeOf(target.path), name)
                    ?: throw IllegalStateException("系统没有给出目标文件：" + name)
                resolver.openOutputStream(document, "w").use { out ->
                    if (out == null) throw IllegalStateException("目标文件打不开：" + name)
                    source.inputStream().use { input -> input.copyTo(out) }
                }
                written++
                bytes += source.length()
            } catch (e: Exception) {
                e.printStackTrace()
                failed++
            }
        }
        return Report(written, failed, bytes)
    }
}
