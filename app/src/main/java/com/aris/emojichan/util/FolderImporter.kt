package com.aris.emojichan.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity

/**
 * 文件夹导入：把用户选中的那棵文件夹树递归读一遍，按「文件夹」分组交回去。
 *
 * 只走 DocumentsContract 的查询接口（系统文件选择器给的就是它），不引第三方库；
 * 用户选中文件夹那一刻拿到的是**整棵树**的读权限，子文件夹不需要再授权。
 *
 * 分两步走（v0.2.003 起，用户 m09930）：[plan] 只查不读，边查边报「已经找到多少张」；
 * [decode] 再逐张解码。两者之间才知道总数，进度条才有分母。以前是边扫边解码，
 * 只能转圈转到底 —— 几百张的文件夹，用户看不出它到底在动还是死了。
 *
 * 会读文件、解图片，必须在 IO 线程上调用。
 */
object FolderImporter {

    /**
     * 一个文件夹里的图片，外加它从最外层到自身这一路的文件夹名。
     *
     * 名字链就是标签链：直接放在根文件夹里的图片挂根文件夹的名字，子文件夹里的图片
     * 同时挂上「根文件夹名」和「子文件夹名」两个标签。
     */
    data class Batch(val tagNames: List<String>, val emojis: List<EmojiEntity>)

    /**
     * 扫描阶段的产物：待解码的一张图，它该挂的标签链，以及源文件的最后修改时间。
     *
     * [lastModified] 只用来排序（用户 m11668 第 4 条：从时间靠前的开始导入）；
     * 读不到就是 0，排在最前。
     */
    data class Entry(
        val tagNames: List<String>,
        val documentUri: Uri,
        val lastModified: Long = 0L
)

    /**
     * 一份清单：要解码的图，以及有没有撞上 [MAX_FILES] 这道闸。
     *
     * 撞上就得说 —— 悄悄少导入一批，用户只会以为图片丢了。
     */
    data class Plan(val entries: List<Entry>, val truncated: Boolean)

    /**
     * 一次最多读多少张，挡住误选整个内部存储的情况。
     *
     * v0.2.003 起 2000 → 20000：用户实测导入两千多张的文件夹时只进来 2000 张（用户 m09998），
     * 说明这道闸卡在了正常用法上。加一个数量级之后，它只对「误选整机」这类离谱选择生效，
     * 几千张的正常文件夹能一次读完；真撞上上限时 [plan] 会带出 truncated，由界面如实告诉用户。
     */
    const val MAX_FILES = 20000

    /**
     * 第一步：只列清单，不解码。扫的时候按「先本层、后子层」，交回去之前再按源文件时间从早到晚排一遍。
     *
     * [onScanned] 每认出一张图报一次累计张数 —— 这时总数还没数出来，进度条只能转圈。
     *
     * 排序是全局的（用户 m11668 第 4 条）：同一个文件夹被时间打散成几段之后，
     * [decode] 会按「标签链相同的连续段」多分几个批次 —— 批次只影响挂标签的写法，
     * 每张图带的标签链仍是它自己那一条，不会挂错。
     *
     * @return 连根文件夹都打不开时返回 null。
     */
    fun plan(context: Context, treeUri: Uri, onScanned: (Int) -> Unit = {}): Plan? {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(context, rootUri)
            ?: context.getString(R.string.import_folder_default_name)

        val entries = mutableListOf<Entry>()
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.add(rootId to listOf(rootName))
        var remaining = MAX_FILES
        var truncated = false

        while (queue.isNotEmpty() && remaining > 0) {
            val (docId, names) = queue.removeFirst()
            val dirs = mutableListOf<Pair<String, List<String>>>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext() && remaining > 0) {
                        val childId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1).orEmpty()
                        val mime = cursor.getString(2).orEmpty()
                        val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirs.add(childId to (names + name))
                        } else if (mime.startsWith("image/")) {
                            remaining--
                            entries.add(
                                Entry(
                                    names,
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                                    modified
                                )
                            )
                            onScanned(entries.size)
                        }
                    }
                    // 循环停下有两个原因：扫完了，或者配额正好用光。后者得再看一眼后面还有没有图 ——
                    // 真有才是截断；目录里恰好 20000 张时不该报「有图没被导进来」（emc-2-032）。
                    if (remaining == 0) {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(2).orEmpty().startsWith("image/")) {
                                truncated = true
                                break
                            }
                        }
                    }
                }
            }
            queue.addAll(dirs)
        }
        // 从早到晚交出去：导入时 createTime 记的是当下，网格按它倒序，
        // 于是源文件时间最新的那批最后入库、排在最前（用户 m11668 第 4 条）。sortedBy 是稳定排序。
        return Plan(entries.sortedBy { it.lastModified }, truncated = truncated)
    }

    /**
     * 第二步：把清单里的图一张张解码成表情，按标签链相同的连续段分组。
     *
     * [onProgress] 每处理一张报一次（已处理, 总数）。解不出来的那张直接跳过 ——
     * 一张坏图不该把整批带停，跟以前边扫边解时一样。
     */
    fun decode(
        context: Context,
        entries: List<Entry>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Batch> {
        val batches = mutableListOf<Batch>()
        var tagNames: List<String>? = null
        var emojis = mutableListOf<EmojiEntity>()
        entries.forEachIndexed { index, entry ->
            onProgress(index + 1, entries.size)
            // 批量导入不逐张写日志：几百张会把日志文件撑满
            val emoji = EmojiImport.fromUri(context, entry.documentUri, log = false).emoji
                ?: return@forEachIndexed
            if (tagNames != entry.tagNames) {
                if (emojis.isNotEmpty()) batches.add(Batch(tagNames ?: emptyList(), emojis))
                tagNames = entry.tagNames
                emojis = mutableListOf()
            }
            emojis.add(emoji)
        }
        if (emojis.isNotEmpty()) batches.add(Batch(tagNames ?: emptyList(), emojis))
        return batches
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
