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

    /** 一次最多读多少张，挡住误选整个内部存储的情况。 */
    private const val MAX_FILES = 2000

    /**
     * @return 按「先本层、后子层」排好的批次（空文件夹不占一批）；连根文件夹都打不开时返回 null。
     */
    fun collect(context: Context, treeUri: Uri): List<Batch>? {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(context, rootUri)
            ?: context.getString(R.string.import_folder_default_name)

        val batches = mutableListOf<Batch>()
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.add(rootId to listOf(rootName))
        var remaining = MAX_FILES

        while (queue.isNotEmpty() && remaining > 0) {
            val (docId, names) = queue.removeFirst()
            val images = mutableListOf<EmojiEntity>()
            val dirs = mutableListOf<Pair<String, List<String>>>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext() && remaining > 0) {
                        val childId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1).orEmpty()
                        when (val mime = cursor.getString(2).orEmpty()) {
                            DocumentsContract.Document.MIME_TYPE_DIR ->
                                dirs.add(childId to (names + name))
                            else -> if (mime.startsWith("image/")) {
                                remaining--
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                // 批量导入不逐张写日志：几百张会把日志文件撑满
                                EmojiImport.fromUri(context, childUri, log = false).emoji?.let { images.add(it) }
                            }
                        }
                    }
                }
            }
            if (images.isNotEmpty()) batches.add(Batch(names, images))
            queue.addAll(dirs)
        }
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
