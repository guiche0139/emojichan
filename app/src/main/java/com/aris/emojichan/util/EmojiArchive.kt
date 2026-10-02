package com.aris.emojichan.util

import android.content.Context
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiTagCrossRef
import com.aris.emojichan.data.TagEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 表情库的打包备份：一个 zip 里装「一份清单 + 全部图片」，存成 .emcpack 后缀。
 *
 * 后缀不叫 .zip，是为了跟手机里别的压缩包分开：里面照样是标准 zip，改后缀不影响读写，
 * 但备份包在文件管理器里一眼就能认出来。
 *
 * 为什么需要它：表情本体只躺在私有目录 files/emojis 里（应用是 allowBackup=false），
 * 换机、卸载重装就等于全丢。备份包把「记录」和「文件」装进同一个能搬运的容器。
 *
 * 为什么清单放在最前面：zip 只能顺着写、顺着读，读包的人必须先拿到全貌才知道
 * 里面有什么。而条目名只由「序号 + 名字 + 扩展名」决定、与文件内容无关，
 * 所以清单可以在写第一张图之前一次性算好、一次写出，读的时候也就只需要读它一次。
 *
 * 纯数据层：不碰界面、不碰资源、不弹提示，一切都通过 [Report] 交回调用方 ——
 * 文案和提示由界面层决定。
 */
object EmojiArchive {

    /** 备份包的后缀。 */
    const val EXTENSION = "emcpack"

    /**
     * 导出时交给系统的类型。
     *
     * 用 octet-stream 而不是 application/zip：各家文件管理器都认这个类型，也不会把备份包
     * 当成普通压缩包去预览。包的真假只看里面的清单（[FORMAT]），跟类型、后缀都没有关系。
     */
    const val MIME = "application/octet-stream"

    /** 清单里的格式标记。不是这个值就说明这压根不是本应用的备份包。 */
    const val FORMAT = "emojichan-archive"

    /** 清单格式版本。读到比它更高的版本，说明包来自更新版的应用，本版读不了。 */
    const val VERSION = 1

    const val MANIFEST_NAME = "emojichan-archive.json"

    /** 图片条目统一放在这个前缀下，解开后一眼能分出哪些是图。 */
    const val IMAGES_DIR = "images"

    /** 复制缓冲区。图片动辄几 MB，小块读写会让导入导出慢得没道理。 */
    private const val COPY_BUFFER = 64 * 1024

    /** 清单条目最多允许这么大，免得一个畸形包拿超大清单把内存撑爆。 */
    private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024

    /** 条目名里那段「安全名」的长度上限。 */
    private const val MAX_NAME_IN_ENTRY = 40

    /** 名字里不能出现的字符：路径分隔符 + Windows 全禁字符。控制字符另算。 */
    private const val ILLEGAL_IN_NAME = "\\/:*?\"<>|"

    enum class Mode { MERGE, REPLACE }

    enum class Error { NOT_ARCHIVE, BROKEN, UNREADABLE, IO }

    /**
     * 一次导出/导入的结果。
     *
     * @param emojis 成功的张数（导出 = 写进包里的；导入 = 解出来待入库的）。
     * @param skipped 因为「库里已经有一模一样的」而跳过的张数（只有 MERGE 会用到）。
     * @param failed 失败的张数（文件读不到、条目在包里缺失、解不出来）。
     * @param tags 标签数（导出 = 写进清单的；导入 = 由调用方入库时补上实际 ensure 出的数量）。
     * @param bytes 实际搬动的字节数。
     * @param error 非空表示这次操作整体失败，界面该按它来提示。
     */
    data class Report(
        val emojis: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val tags: Int = 0,
        val bytes: Long = 0L,
        val error: Error? = null
    )

    /**
     * 一次导入的解包结果。
     *
     * @param emojis 解出来的待入库记录，id 一律是 0（入库时由数据库分配）。
     * @param tagsOf 与 [emojis] 同序同长：每一项是这张表情在包里挂着的标签名。
     * @param report 只对「解包」负责：它的 tags 恒为 0，标签是调用方入库时 ensure 出来的。
     */
    data class Imported(
        val emojis: List<EmojiEntity>,
        val tagsOf: List<List<String>>,
        val report: Report
    )

    /**
     * 默认文件名："EmojiChan-表情库-20240930-1421.emcpack"。
     *
     * 用 Locale.US 格式化：年月日时分这种数字串不该跟着系统语言变形
     * （某些 locale 会给出别的历法数字，文件名就成了乱码）。
     */
    fun suggestedName(): String =
        "EmojiChan-表情库-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + "." + EXTENSION

    /**
     * 把一批记录导成 zip 写到 [output]。
     *
     * 只写数据库当前引用的那些文件；读不到的（被删了、权限没了）记 [Report.failed] 跳过 ——
     * 备份包里宁可少一张，也不能出现一个指向不存在条目的清单。
     *
     * 不关 [output]：它是调用方（通常是 ContentResolver）的流，关它是调用方的事；
     * 但 zip 必须 finish()，没写中央目录的 zip 是打不开的。
     * 单张图片的输入流则可以放心 use，它和 zip 是两条独立的流。
     */
    fun export(
        context: Context,
        output: OutputStream,
        emojis: List<EmojiEntity>,
        tags: List<TagEntity>,
        links: List<EmojiTagCrossRef>,
        appVersion: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): Report {
        val total = emojis.size
        var done = 0
        var written = 0
        var failed = 0
        var bytes = 0L

        // 第一遍只算条目名、顺便剔掉读不到的文件：清单一旦写出就改不回来了，
        // 不能登记一个后面写不出来的条目。
        val plan = ArrayList<Plan>(total)
        for (emoji in emojis) {
            val file = File(emoji.filePath)
            if (!file.isFile || !file.canRead()) {
                failed++
                done++
                onProgress(done, total)
            } else {
                val ext = extensionOf(emoji.filePath, emoji.fileType)
                plan.add(Plan(emoji, file, entryNameOf(plan.size + 1, emoji.name, ext)))
            }
        }

        val zip = ZipOutputStream(BufferedOutputStream(output, COPY_BUFFER))
        return try {
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(buildManifest(plan, tags, links, appVersion).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (item in plan) {
                zip.putNextEntry(ZipEntry(item.entryName))
                // 这里只 use 输入流，绝不 use zip：它还要接着写下一张、最后写中央目录。
                item.file.inputStream().use { input -> bytes += input.copyTo(zip, COPY_BUFFER) }
                zip.closeEntry()
                written++
                done++
                onProgress(done, total)
            }
            Report(emojis = written, failed = failed, tags = tags.size, bytes = bytes)
        } catch (e: Exception) {
            // 写到一半出错：包已经不完整，不再往下写，照实返回已经落进包里的数量。
            e.printStackTrace()
            Report(emojis = written, failed = failed, tags = tags.size, bytes = bytes, error = Error.IO)
        } finally {
            // 必须 finish() 才会写下中央目录；出错时也试一把（失败就算了，反正已经报了 IO）。
            runCatching { zip.finish() }
            runCatching { zip.flush() }
        }
    }

    /**
     * 解一个备份包，把图片落到私有目录，返回待入库的记录。
     *
     * 落库不在这里做，两种模式的差别留给调用方：
     * - [Mode.MERGE]：调用方拿 [Imported.emojis] 逐条走 repository.insert（它在同一个事务里
     *   去重名并挂「图片」/「动图」自动标签），已有的同名同体积条目已经在解包阶段被跳过。
     * - [Mode.REPLACE]：调用方先 clearLibrary() 再插入（existing 传空表即可，不用去重）。
     *
     * 两趟流：zip 不能倒带，读完一张图就没法回头，所以「先看清单、再抽图」必须开两条独立的流
     * （openInput 是工厂而不是现成的流，就是为了这个）。
     *
     * 中途失败时 [Imported.emojis] 里仍可能躺着这一趟已经解出来的文件路径 ——
     * 调用方要是不打算入库，得自己把它们删掉，否则会在私有目录里留一堆孤儿文件。
     */
    fun import(
        context: Context,
        openInput: () -> InputStream?,
        mode: Mode,
        existing: List<EmojiEntity>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Imported {
        // ---------- 第一趟：只读清单 ----------
        val text = try {
            readManifest(openInput)
        } catch (e: ZipException) {
            // 连 zip 的头都不对：这不是备份包
            return failed(Error.NOT_ARCHIVE)
        } catch (e: Exception) {
            e.printStackTrace()
            // 打不开（openInput 返回 null、流读不动）：包可能是好的，是这次拿不到
            return failed(Error.UNREADABLE)
        } ?: return failed(Error.NOT_ARCHIVE)

        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            e.printStackTrace()
            return failed(Error.BROKEN)
        }
        if (root.optString("format") != FORMAT) return failed(Error.NOT_ARCHIVE)
        if (root.optInt("version", 0) > VERSION) return failed(Error.BROKEN)
        val array = root.optJSONArray("emojis") ?: return failed(Error.BROKEN)
        val entries = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) entries.add(entryOf(array.optJSONObject(i)))

        val total = entries.size

        // MERGE 的去重键：名字（忽略大小写）+ 体积。体积为 0 说明包里没记，
        // 这时只靠名字判断太容易误伤（两张不同的图同名很常见），宁可不判 ——
        // 多留一份总比把用户真有的那张丢掉强。
        val taken: Set<String> = if (mode == Mode.MERGE) {
            existing.mapTo(HashSet(existing.size)) { dedupeKey(it.name, it.fileSize) }
        } else {
            emptySet()
        }

        val dir = ImageUtil.getEmojiDir(context)
        val byEntryName = HashMap<String, Entry>(entries.size * 2)
        val outEmojis = ArrayList<EmojiEntity>(entries.size)
        val outTags = ArrayList<List<String>>(entries.size)
        var done = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L

        // 包里有、库里也有一模一样的：不抽图、不入库，只记一笔。
        // 进度照走，否则用户看着进度条停在半路会以为卡死了。
        for (item in entries) {
            if (mode == Mode.MERGE && item.size > 0 && dedupeKey(item.name, item.size) in taken) {
                item.handled = true
                skipped++
                done++
                onProgress(done, total)
            } else if (item.file.isNotBlank()) {
                // 清单里写的是完整条目名；basename 也登记一份，包被重新打过（外面套了一层目录）时还能对上。
                byEntryName[item.file] = item
                byEntryName[item.file.substringAfterLast('/')] = item
            }
        }

        // ---------- 第二趟：抽图 ----------
        if (entries.any { !it.handled }) {
            val stream = try {
                openInput()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            if (stream == null) {
                // 清单读得出来、图一个也抽不出来，如实报「打不开」：调用方不该拿它去换库。
                return Imported(
                    emptyList(),
                    emptyList(),
                    Report(skipped = skipped, failed = total - skipped, error = Error.UNREADABLE)
                )
            }
            try {
                ZipInputStream(BufferedInputStream(stream, COPY_BUFFER)).use { zip ->
                    while (true) {
                        val zipEntry = zip.nextEntry ?: break
                        val item = byEntryName[zipEntry.name]?.takeIf { !it.handled }
                            ?: byEntryName[zipEntry.name.substringAfterLast('/')]?.takeIf { !it.handled }
                            ?: continue
                        item.handled = true
                        val target = File(
                            dir,
                            ImageUtil.newEmojiFileName(extensionOf(item.file, item.fileType))
                        )
                        try {
                            // zip 在这里同样不能 use，它是整趟共用的那条流。
                            FileOutputStream(target).use { out -> zip.copyTo(out, COPY_BUFFER) }
                            bytes += target.length()
                            // 清单里的体积/宽高可能是 0（老备份、上游没写）：落盘后用真实值补上，
                            // 否则列表里的体积和排版会是一片 0。
                            val size =
                                if (item.size > 0) item.size else ImageUtil.getFileSize(target.absolutePath)
                            var width = item.width
                            var height = item.height
                            if (width <= 0 || height <= 0) {
                                val (w, h) = ImageUtil.getImageDimensions(target.absolutePath)
                                width = w
                                height = h
                            }
                            outEmojis.add(
                                EmojiEntity(
                                    id = 0,
                                    name = item.name,
                                    filePath = target.absolutePath,
                                    fileType = item.fileType,
                                    source = item.source,
                                    isFavorite = item.favorite,
                                    usageCount = item.usageCount,
                                    lastUsedTime = item.lastUsed,
                                    createTime = if (item.createdAt > 0) item.createdAt
                                    else System.currentTimeMillis(),
                                    fileSize = size,
                                    width = width,
                                    height = height
                                )
                            )
                            outTags.add(item.tags)
                        } catch (e: Exception) {
                            // 半成品文件绝不能留在库里：列表里会多出一张打不开的空图。
                            e.printStackTrace()
                            ImageUtil.deleteFile(target.absolutePath)
                            failed++
                        } finally {
                            done++
                            onProgress(done, total)
                        }
                    }
                }
            } catch (e: Exception) {
                // 流读到一半坏了：剩下的条目在下面统一记 failed，已经解出来的照常返回。
                e.printStackTrace()
            }
        }

        // 清单里登记了、包里却没有（或第二趟压根没轮到）的，一律记 failed。
        // 这一步保证 done 最终一定等于 total，进度条不会停在半路。
        for (item in entries) {
            if (!item.handled) {
                item.handled = true
                failed++
                done++
                onProgress(done, total)
            }
        }

        return Imported(
            emojis = outEmojis,
            tagsOf = outTags,
            report = Report(emojis = outEmojis.size, skipped = skipped, failed = failed, bytes = bytes)
        )
    }

    // ---------- 内部实现 ----------

    /** 清单的结构（见 export 的 [buildManifest]）。字段名一旦定下就不能再动：读老包的代码只认它们。 */
    private fun buildManifest(
        plan: List<Plan>,
        tags: List<TagEntity>,
        links: List<EmojiTagCrossRef>,
        appVersion: String
    ): String {
        // 每张表情自带一行标签名：这样清单是自解释的，导入端不用再去关联两张表。
        val nameOfTag = HashMap<Long, String>(tags.size * 2)
        for (tag in tags) nameOfTag[tag.id] = tag.name
        val tagsOfEmoji = HashMap<Long, MutableList<String>>()
        for (link in links) {
            val name = nameOfTag[link.tagId] ?: continue
            tagsOfEmoji.getOrPut(link.emojiId) { ArrayList(2) }.add(name)
        }

        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app", appVersion)
        root.put("exportedAt", System.currentTimeMillis())

        val tagArray = JSONArray()
        for (tag in tags) {
            tagArray.put(JSONObject().put("name", tag.name).put("createTime", tag.createTime))
        }
        root.put("tags", tagArray)

        val emojiArray = JSONArray()
        for (item in plan) {
            val emoji = item.emoji
            val one = JSONObject()
            one.put("name", emoji.name)
            one.put("file", item.entryName)
            one.put("fileType", emoji.fileType)
            one.put("source", emoji.source)
            one.put("favorite", emoji.isFavorite)
            one.put("usageCount", emoji.usageCount)
            one.put("lastUsed", emoji.lastUsedTime)
            one.put("createdAt", emoji.createTime)
            one.put("size", emoji.fileSize)
            one.put("width", emoji.width)
            one.put("height", emoji.height)
            val names = JSONArray()
            for (name in tagsOfEmoji[emoji.id].orEmpty()) names.put(name)
            one.put("tags", names)
            emojiArray.put(one)
        }
        root.put("emojis", emojiArray)
        return root.toString()
    }

    /** "images/0001_安全名.png"：序号保证唯一，安全名只为人用解压软件打开时看得懂。 */
    private fun entryNameOf(index: Int, name: String, ext: String): String =
        IMAGES_DIR + "/" + String.format(Locale.US, "%04d", index) + "_" + safeName(name) + "." + ext

    /** 名字里的路径分隔符、Windows 禁用字符、控制字符一律换成下划线，再裁到 40 个字符。 */
    private fun safeName(name: String): String {
        val cleaned = StringBuilder(name.length)
        for (ch in name) {
            cleaned.append(if (ch.isISOControl() || ILLEGAL_IN_NAME.indexOf(ch) >= 0) '_' else ch)
        }
        val cut = cleaned.toString().take(MAX_NAME_IN_ENTRY)
        return if (cut.isBlank()) "emoji" else cut
    }

    /**
     * 从文件名取扩展名（小写）。取不到后缀时按动图/静态图判一个：
     * 条目名没有后缀的话，读包的人就没法判它该走哪条分支。
     */
    private fun extensionOf(filePath: String, fileType: String): String {
        val raw = filePath.substringAfterLast('.', "")
        val safe = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(5)
        if (safe.isNotEmpty()) return safe
        return if (ImageUtil.isGif(filePath) || fileType.equals("gif", ignoreCase = true)) "gif" else "png"
    }

    /**
     * 第一趟：开一条流，把清单条目读成字符串。
     *
     * @return null 表示包里根本没有清单条目（不是本应用的包）。
     * @throws IOException 流打不开或读不动（[ZipException] 单列，表示对方根本不是 zip）。
     */
    private fun readManifest(openInput: () -> InputStream?): String? {
        val stream = openInput() ?: throw IOException("备份包的输入流打不开（openInput 返回 null）")
        ZipInputStream(BufferedInputStream(stream, COPY_BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST_NAME || entry.name.endsWith("/" + MANIFEST_NAME)) {
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        if (buffer.size() + read > MAX_MANIFEST_BYTES) {
                            throw IOException("清单条目超过 " + (MAX_MANIFEST_BYTES / 1024 / 1024) + " MB")
                        }
                        buffer.write(chunk, 0, read)
                    }
                    return buffer.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    /** 清单里的一条记录。缺字段一律走默认值：宁可少一点信息，也不要因为一个字段就整包拒收。 */
    private fun entryOf(one: JSONObject?): Entry {
        if (one == null) return Entry("", "", "image", "local", false, 0, 0L, 0L, 0L, 0, 0, emptyList())
        val file = one.optString("file")
        val fileType = one.optString("fileType").trim().lowercase(Locale.US)
            .ifEmpty { if (file.endsWith(".gif", ignoreCase = true)) "gif" else "image" }
        val tagArray = one.optJSONArray("tags")
        val tagNames = ArrayList<String>(tagArray?.length() ?: 0)
        if (tagArray != null) {
            for (i in 0 until tagArray.length()) {
                val name = tagArray.optString(i).trim()
                if (name.isNotEmpty() && name !in tagNames) tagNames.add(name)
            }
        }
        return Entry(
            name = one.optString("name"),
            file = file,
            fileType = fileType,
            source = one.optString("source").ifBlank { "local" },
            favorite = one.optBoolean("favorite", false),
            usageCount = one.optInt("usageCount", 0),
            lastUsed = one.optLong("lastUsed", 0L),
            createdAt = one.optLong("createdAt", 0L),
            size = one.optLong("size", 0L),
            width = one.optInt("width", 0),
            height = one.optInt("height", 0),
            tags = tagNames
        )
    }

    /** MERGE 的去重键：名字（忽略大小写）+ 体积。 */
    private fun dedupeKey(name: String, size: Long): String =
        name.trim().lowercase(Locale.US) + "|" + size

    private fun failed(error: Error): Imported =
        Imported(emptyList(), emptyList(), Report(error = error))

    /** 导出计划里的一条：记录 + 它的文件 + 算好的条目名（条目名先定下来，清单才能先写）。 */
    private class Plan(val emoji: EmojiEntity, val file: File, val entryName: String)

    /** 清单里登记的一张表情（还没落盘）。 */
    private class Entry(
        val name: String,
        val file: String,
        val fileType: String,
        val source: String,
        val favorite: Boolean,
        val usageCount: Int,
        val lastUsed: Long,
        val createdAt: Long,
        val size: Long,
        val width: Int,
        val height: Int,
        val tags: List<String>
    ) {
        /** 已经定过生死（跳过 / 成功 / 失败）：保证每条只处理一次，进度也不会重复走。 */
        var handled = false
    }
}
