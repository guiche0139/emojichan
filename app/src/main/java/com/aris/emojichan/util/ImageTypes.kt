package com.aris.emojichan.util

import java.io.File

/**
 * 图片类型的一张对照表：扩展名 ↔ MIME ↔「是不是动图」。
 *
 * 库里的图片是 jpg / jpeg / png / gif / webp / bmp 六种（v0.1.408 起压缩产物一律是 webp）。
 * 类型换算原本散在分享、剪贴板、相册发布、归档、导出好几处各写一份，结果就是「这里认 webp、
 * 那里不认」—— 分享给微信时类型给错，对方按静图处理，动图就没了。所以只留这一份。
 *
 * 纯字符串与文件头处理，不碰 Android 框架，能直接单测。
 */
object ImageTypes {

    /** 支持导入 / 导出的扩展名（小写、不含点）。 */
    val SUPPORTED = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    /** 认不出的类型统一报这个；接收方本来也会按文件内容自己识别。 */
    const val UNKNOWN_MIME = "image/*"

    /** 库里 fileType 列的两个取值（沿用老值，不新造）。 */
    const val FILE_TYPE_ANIMATED = "gif"
    const val FILE_TYPE_IMAGE = "image"

    /** 路径 → 扩展名（小写、不含点）。取不到返回空串。 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isSupported(path: String): Boolean = extensionOf(path) in SUPPORTED

    /** 扩展名 → MIME。认不出给 [UNKNOWN_MIME]。 */
    fun mimeOfExtension(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> UNKNOWN_MIME
    }

    /** 路径 → MIME。 */
    fun mimeOf(path: String): String = mimeOfExtension(extensionOf(path))

    /**
     * 路径 → 库里 fileType 列的取值。
     *
     * 这一列的实际语义是「是不是动图」（详情页要显示格式时走的是文件后缀），动态 WebP 的
     * 扩展名和静图一模一样，只能读文件头认 —— 认出来当动图，才会挂「动图」标签、压缩时跳过。
     */
    fun fileTypeOf(path: String): String =
        if (extensionOf(path) == "gif" || isAnimatedWebp(File(path))) FILE_TYPE_ANIMATED
        else FILE_TYPE_IMAGE

    /** RIFF…WEBP + VP8X 块的 flags 里，bit1 就是「这是动图」。 */
    fun isAnimatedWebp(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(21)
                var read = 0
                while (read < head.size) {
                    val count = input.read(head, read, head.size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read < head.size) return@runCatching false
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return@runCatching false
                if (String(head, 8, 4, Charsets.US_ASCII) != "WEBP") return@runCatching false
                if (String(head, 12, 4, Charsets.US_ASCII) != "VP8X") return@runCatching false
                (head[20].toInt() and 0x02) != 0
            }
        }.getOrDefault(false)
    }
}
