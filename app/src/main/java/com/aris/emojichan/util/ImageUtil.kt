package com.aris.emojichan.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.aris.emojichan.R
import com.aris.emojichan.sender.SendLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtil {
    private const val EMOJI_DIR = "emojis"

    /** 单个表情的体积上限：超过它多半是误选的视频或超大图，宁可不导入（emc-1-019）。 */
    private const val MAX_IMPORT_BYTES = 20L * 1024 * 1024

    fun getEmojiDir(context: Context): File {
        val dir = File(context.filesDir, EMOJI_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 把「先写到缓存里、用户确认过」的文件搬进表情目录。
     *
     * 优先改名（同一个分区，几乎不花时间），改不动再退回复制；两种都失败就返回 null，
     * 调用方据此判定这张没替换成功。文件名已经是 [newEmojiFileName] 那一套，落地后就是正式名。
     */
    fun promoteTempFile(context: Context, temp: File): File? {
        if (!temp.isFile) return null
        val target = File(getEmojiDir(context), temp.name)
        if (temp.renameTo(target) && target.isFile) return target
        return try {
            temp.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (!target.isFile || target.length() <= 0L) {
                target.delete()
                null
            } else {
                temp.delete()
                target
            }
        } catch (e: Exception) {
            e.printStackTrace()
            target.delete()
            null
        }
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
                val target = File(getEmojiDir(context), newEmojiFileName(ext))
                destFile = target
                FileOutputStream(target).use { output -> copyWithLimit(stream, output) }
                target.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 失败原因也要落进日志：以前只在 logcat 里，用户报「导入失败」时无从下手。
            SendLog.e("导入", "读取失败：" + uri + " → " + e)
            // 写了一半的文件绝不能留在库里 —— 列表里会多出一张打不开的空图（emc-1-019）。
            destFile?.let { if (it.exists()) it.delete() }
            null
        }
    }

    /**
     * 生成一个库内文件名。
     *
     * 加随机后缀：纯时间戳在同一毫秒内连续导入会撞名，后者覆盖前者，
     * 结果是两条记录指向同一个文件，删掉其中一个另一个也会失效。
     *
     * 从 [copyImageToInternal] 里抽出来是为了让打包导入也用同一套命名 ——
     * 两处各写一遍，迟早会分叉成两种格式。
     */
    fun newEmojiFileName(ext: String): String =
        "emoji_${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}.$ext"

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

    /**
     * 取上游给的显示名（相册/分享面板的文件名）。导入时用它当表情名，
     * 这样「图片.png」进来就叫「图片」，不用再手动改名。
     *
     * 四个来源挨个试，每一处都用 [isIdLeak] 挡一遍选择器漏出来的 _id
     * （v0.1.310 只在第一处挡，云相册那种「换算完还是给 id」的路上又漏了出去）。
     */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        val candidates = namesOf(context, uri)

        // ① Uri 自己答的：显示名列优先，其次 DATA 列里的文件名（已废弃但不少 OEM 还在给）
        firstUsableName(uri, candidates)?.let { return it }

        // ② 系统的图片选择器（Android 14 起 ACTION_GET_CONTENT 会被重定向到它）答不上真名时
        // 会把 MediaStore 的 _id 当显示名返回 —— 用户看到的就是「1000000033」这种数字。
        // 原图在云相册、本机没有索引时几乎必然如此：先把选择器 Uri 换算成真实的媒体 Uri，
        // 那上面的名字通常是对的（API 29+）；换出来的还可能是 id，所以照样要挡。
        if (isPickerUri(uri)) {
            mediaUriFromPicker(context, uri)?.let { media ->
                firstUsableName(media, namesOf(context, media))?.let { return it }
            }
        }

        // ③ 选择器给的这串数字其实就是 MediaStore 的 _id。两条路去问真名：
        //    先问 MediaDocumentsProvider（文件选择器背后的 provider，实测它对同一个 id
        //    是肯说真名的），再按 id 反查 MediaStore 自己那一行。
        //    只有 Uri 本身就来自 MediaStore 时才敢这么用：别的 provider 的数字段和
        //    MediaStore 的 id 毫无关系，查出来的是**另一张图**的名字。
        val id = candidates.firstNotNullOfOrNull { leakedId(it) }
            ?: leakedId(uri.lastPathSegment)
        if (id != null && isMediaStoreUri(uri)) {
            mediaDocumentName(context, uri, id)?.let { return it }
            mediaNameById(context, id)?.let { return it }
        }

        // ④ 最后才看 Uri 末段：.../IMG_0001.jpg 这种带扩展名的可以当名字，
        // .../media/1000000033 这种纯数字 id 不行，宁可让调用方给兜底名。
        val segment = uri.lastPathSegment?.trim().orEmpty()
        return segment.takeIf { it.isNotEmpty() && !isIdLeak(uri, it) }
    }

    /**
     * 拿不到原名时的兜底名：按导入时间给一个看得懂、能排序的名字。
     * 「未命名(1)(2)(3)」既看不出是什么图，也没法和相册里的东西对上。
     */
    fun fallbackName(context: Context): String =
        context.getString(
            R.string.import_name_fallback,
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        )

    private fun queryColumn(context: Context, uri: Uri, column: String): String? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        // provider 不认识这一列、或者没给我们读它的权限，一律当拿不到
        null
    }

    /** 一个 Uri 上问得出来的名字候选：显示名列优先，其次 DATA 列里的文件名（目录已去掉）。 */
    private fun namesOf(context: Context, uri: Uri): List<String> {
        val out = mutableListOf<String>()
        queryColumn(context, uri, OpenableColumns.DISPLAY_NAME)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { out += it }
        queryColumn(context, uri, MediaStore.MediaColumns.DATA)
            ?.substringAfterLast('/')?.trim()
            ?.takeIf { it.isNotEmpty() && it !in out }?.let { out += it }
        return out
    }

    /** 从候选里挑第一个能当名字用的：跳过「就是这一行的 id」那种泄漏值。 */
    private fun firstUsableName(uri: Uri, names: List<String>): String? =
        names.firstOrNull { it.isNotEmpty() && !isIdLeak(uri, it) }

    /**
     * 导入诊断串：把每个候选来源的原始值原样写出来，供发送日志排查「名字怎么又是数字」。
     * 这里只记文件名和路径，不碰聊天内容。
     */
    fun describeNames(context: Context, uri: Uri): String {
        val display = queryColumn(context, uri, OpenableColumns.DISPLAY_NAME)
        val text = StringBuilder()
        text.append("uri=").append(uri.toString())
        text.append("，路径段=").append(uri.pathSegments?.joinToString("/") ?: "（无）")
        text.append("，显示名=").append(valueOrEmpty(display))
        text.append("，DATA=").append(valueOrEmpty(queryColumn(context, uri, MediaStore.MediaColumns.DATA)))
        text.append("，末段=").append(valueOrEmpty(uri.lastPathSegment))
        text.append("，picker 换算=").append(pickerConversion(context, uri))
        text.append("，相册权限=").append(mediaAccessLevel(context))
        // 编号反查也要写清结论：没有这一行 / 被权限拦下，处置办法完全不同。
        (leakedId(display) ?: leakedId(uri.lastPathSegment))?.let { id ->
            text.append("，doc 反查(").append(id).append(")=").append(documentLookup(context, uri, id))
            text.append("，id 反查(").append(id).append(")=").append(reverseLookup(context, id))
        }
        text.append("，全部列=").append(dumpColumns(context, uri))
        return text.toString()
    }

    private fun valueOrEmpty(raw: String?): String = raw?.trim().orEmpty().ifEmpty { "（空）" }

    /**
     * 选择器 Uri 换算成真媒体 Uri 的结果（诊断用）。不管前缀认不认都把话说清楚，
     * 免得下次又出现「这一步根本没跑，但日志上看不出来」。
     */
    private fun pickerConversion(context: Context, uri: Uri): String {
        if (!isPickerUri(uri)) return "跳过（前缀不是 picker/picker_get_content）"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "跳过（系统低于 Android 10）"
        return try {
            val media = MediaStore.getMediaUri(context, uri)
            if (media == null) {
                "（空）"
            } else {
                "成功 → " + media + "（显示名=" +
                    valueOrEmpty(queryColumn(context, media, OpenableColumns.DISPLAY_NAME)) + "）"
            }
        } catch (e: Exception) {
            "失败：" + e.javaClass.simpleName + "：" + (e.message ?: "无消息")
        }
    }

    /** 用 id 问 MediaDocumentsProvider 的结果（诊断用）。 */
    private fun documentLookup(context: Context, uri: Uri, id: Long): String {
        val doc = mediaDocumentUri(context, uri, id)
        return try {
            context.contentResolver
                .query(doc, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) {
                        "有行：" + valueOrEmpty(cursor.getString(0))
                    } else {
                        "没有这一行"
                    }
                } ?: "（空游标）"
        } catch (e: Exception) {
            e.javaClass.simpleName + (if (e is SecurityException) "（没有读权限）" else "")
        }
    }

    /** 按数字 id 反查 MediaStore 的结果（诊断用：区分「没有这一行」与「被权限拦下」，逐卷列出）。 */
    private fun reverseLookup(context: Context, id: Long): String {
        val notes = mutableListOf<String>()
        volumes(context).forEach { volume ->
            basesFor(volume).forEach { base ->
                val item = ContentUris.withAppendedId(base, id)
                val label = volume + "/" + (base.lastPathSegment ?: "?")
                try {
                    context.contentResolver
                        .query(item, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            notes += if (cursor.moveToFirst() && cursor.columnCount > 0) {
                                label + " 有行：" + valueOrEmpty(cursor.getString(0))
                            } else {
                                label + " 没有这一行"
                            }
                        } ?: run { notes += label + "（空游标）" }
                } catch (e: Exception) {
                    notes += label + "：" + e.javaClass.simpleName +
                        (if (e is SecurityException) "（没有媒体库读权限）" else "")
                }
            }
        }
        return notes.joinToString("；")
    }

    /** 把这个 Uri 查得到的列全 dump 出来（诊断用：看看 provider 有没有藏着真名的列）。 */
    private fun dumpColumns(context: Context, uri: Uri, maxChars: Int = 700): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                "（没有数据行）"
            } else {
                cursor.columnNames.joinToString("，") { name ->
                    val index = cursor.getColumnIndex(name)
                    val value = if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                    name + "=" + valueOrEmpty(value)
                }.take(maxChars)
            }
        } ?: "（空游标）"
    } catch (e: Exception) {
        "（查询失败：" + e.javaClass.simpleName + "）"
    }

    /**
     * 显示名是不是图片选择器漏出来的 _id。判据两条：**Uri 末段本身是纯数字**，
     * 且显示名去掉扩展名后的主干和它一模一样 —— 选择器在本地索引里查不到真名时，
     * 给的就是自己那一行的 id，偶尔还带个扩展名，于是「1000000033」和「1000000033.jpg」
     * 都是它（v0.1.310 只认前者，带扩展名的那种漏了过去）。
     *
     * 为什么必须要求末段是纯数字：不能只看「文本是不是数字」。很多图的真名本身就是数字 ——
     * 微信、QQ 存下来的图常叫「1727512345678.jpg」，此时 Uri 末段也是文件名
     * （如「1790521090844.gif」），不是纯数字；只看形状就会把真名当泄漏丢掉（emc-2-008）。
     */
    private fun isIdLeak(uri: Uri, raw: String?): Boolean {
        val tail = uri.lastPathSegment?.trim().orEmpty()
        if (tail.isEmpty() || !tail.all { it.isDigit() }) return false
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        val stem = text.substringBeforeLast('.')
        return stem.isNotEmpty() && stem.all { it.isDigit() } && stem == tail
    }

    /**
     * 图片选择器的 Uri。**两种前缀都要认**（v0.1.312）：
     * · `picker` —— ACTION_PICK_IMAGES 直接给的那种；
     * · `picker_get_content` —— ACTION_GET_CONTENT 被系统重定向到选择器后给的那种
     *   （用户真机就是它；只认前一种时「换算成真媒体 Uri」这一步被整段跳过，
     *   真名压根没机会被问到，日志里也就看不出差别）。
     */
    private fun isPickerUri(uri: Uri): Boolean {
        val first = uri.pathSegments?.firstOrNull() ?: return false
        return first == "picker" || first == "picker_get_content"
    }

    /** 只有 MediaStore 自己的 Uri，它的数字段才是 _id。 */
    private fun isMediaStoreUri(uri: Uri): Boolean = uri.authority == MediaStore.AUTHORITY

    /** 名字里的数字主干（≥5 位）才可以当 _id 去反查：「1000000033」「1000000033.jpg」都算。 */
    private fun leakedId(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val stem = text.substringBeforeLast('.')
        if (stem.length < 5 || !stem.all { it.isDigit() }) return null
        return stem.toLongOrNull()
    }

    /**
     * 按 _id 反查 MediaStore 里的真名。问的是**这一行**本身，它答什么就用什么 ——
     * 这里不能再套泄漏判据：微信存的「1790521090844.jpg」主干也是纯数字，套了又会把真名丢掉。
     */
    private fun mediaNameById(context: Context, id: Long): String? {
        volumes(context).forEach { volume ->
            basesFor(volume).forEach { base ->
                val media = ContentUris.withAppendedId(base, id)
                namesOf(context, media).firstOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 用同一个 id 去问 MediaDocumentsProvider（`.../document/image:<id>`）。
     * 这条路来自用户实测：同一张图，图片选择器只肯说「1000531098.jpg」，
     * 而文件选择器背后的这个 provider 说的是「1790668073824.jpeg」（emc-2-008）。
     * 它按调用方的读权限放行，没权限时拒绝 —— 所以只是链上的一步，失败就往下走。
     */
    private fun mediaDocumentName(context: Context, uri: Uri, id: Long): String? {
        val doc = mediaDocumentUri(context, uri, id)
        return namesOf(context, doc).firstOrNull()
    }

    private fun mediaDocumentUri(context: Context, uri: Uri, id: Long): Uri {
        val type = queryColumn(context, uri, MediaStore.MediaColumns.MIME_TYPE)
            ?.substringBefore('/')
            ?.takeIf { it == "video" || it == "audio" }
            ?: "image"
        return DocumentsContract.buildDocumentUri(MEDIA_DOCUMENTS_AUTHORITY, type + ":" + id)
    }

    /** 主存储 + 各外置卷（SD 卡等）：选择器给的 id 属于哪个卷，只有挨个问才知道。 */
    private fun volumes(context: Context): List<String> {
        val out = mutableListOf(MediaStore.VOLUME_EXTERNAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaStore.getExternalVolumeNames(context).forEach { if (it !in out) out += it }
            } catch (e: Exception) {
                // 拿不到卷列表就当只有主存储
            }
        }
        return out
    }

    private fun basesFor(volume: String): List<Uri> = when {
        volume == MediaStore.VOLUME_EXTERNAL -> listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
            MediaStore.Images.Media.getContentUri(volume),
            MediaStore.Files.getContentUri(volume)
        )
        else -> emptyList()
    }

    /**
     * 相册读权限的等级：全部 / 部分（Android 14 的「仅选择的照片」）/ 无。
     * 有权限时上面两步才问得出真名 —— 没权限时 provider 会把别人的行挡掉。
     */
    fun mediaAccessLevel(context: Context): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) "全部" else "无"
        Build.VERSION.SDK_INT >= 34 -> when {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> "全部"
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "部分"
            else -> "无"
        }
        else -> if (granted(context, Manifest.permission.READ_MEDIA_IMAGES)) "全部" else "无"
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** 文件选择器背后的 MediaStore 文档 provider：同一个 id，它肯说真名。 */
    private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

    /** 图片选择器的 Uri 能换算成真正的媒体 Uri（API 29+），后者上的名字通常是对的。 */
    private fun mediaUriFromPicker(context: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            MediaStore.getMediaUri(context, uri)
        } catch (e: Exception) {
            null
        }
    }

    fun getFileSize(filePath: String): Long {
        return File(filePath).length()
    }

    /** 支持哪些扩展名只看 [ImageTypes]，别在这里再抄一份。 */
    fun isImageFile(filePath: String): Boolean = ImageTypes.isSupported(filePath)

    fun isGif(filePath: String): Boolean {
        return filePath.lowercase().endsWith(".gif")
    }
}
