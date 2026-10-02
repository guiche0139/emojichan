package com.aris.emojichan.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.aris.emojichan.sender.SendLog
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「导入后的原文件」处理：把这次导入选中的源文件收进 zip，或者删掉。
 *
 * 安全底线，改这个文件前先读三遍：
 * 1. 只碰**调用方明确传进来的那几个 Uri**——不扫目录、不按名字/后缀找文件、不递归。
 *    多删一个用户的照片都是事故，宁可少删。
 * 2. 压缩时先把 zip 写成功、确认哪个文件真的进了包，才允许删哪个（[zipSources] 返回写入成功的那些）。
 * 3. 删除永远优先交给系统的批量删除请求（[mediaUrisForDelete] + MediaStore.createDeleteRequest）：
 *    系统会把要删的东西摆给用户看、再让用户点一次，我们只是发起方。
 */
object SourceFiles {

    /** 压缩包文件名。一次导入一个包，不去追加写老包（zip 追加要先整包重写，代价和风险都更大）。 */
    fun zipName(): String = "EmojiChan-原表情-" +
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"

    /** 用户挑的那棵目录树的显示名，拿来在设置页上展示「存到哪儿」。 */
    fun treeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * 把 [uris] 里的文件原样收进 [treeUri] 目录下的一个新 zip。
     *
     * 返回**确实写进包里的那些 Uri**（读不出来的不返回，调用方据此决定删哪些）。
     * 一个都没写成就抛 [IOException]，并且在用户目录里不留半成品文件。
     */
    @Throws(IOException::class)
    fun zipSources(context: Context, treeUri: Uri, uris: List<Uri>, name: String): List<Uri> {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val target = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("在所选目录里建不了文件")

        val written = mutableListOf<Uri>()
        try {
            val raw = resolver.openOutputStream(target, "w")
                ?: throw IOException("所选目录写不进去")
            raw.use { out ->
                ZipOutputStream(BufferedOutputStream(out, 64 * 1024)).use { zip ->
                    uris.forEachIndexed { index, uri ->
                        val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
                            ?: run {
                                SendLog.w("导入", "原文件读不出来，跳过：" + uri)
                                return@forEachIndexed
                            }
                        input.use { source ->
                            zip.putNextEntry(ZipEntry(entryName(context, uri, index)))
                            source.copyTo(zip, 64 * 1024)
                            zip.closeEntry()
                            written += uri
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            SendLog.e("导入", "压缩原文件失败：" + t.message)
            throw IOException(t.message ?: "写压缩包出错", t)
        }
        if (written.isEmpty()) {
            runCatching { resolver.delete(target, null, null) }
            throw IOException("一个文件都没能读出来")
        }
        return written
    }

    /**
     * 这些 Uri 能不能交给系统的批量删除请求：只有 MediaStore 管的那些（相册里的图）才行。
     * Android 13 起先试着用 [MediaStore.getMediaUri] 把相册选择器给的 picker Uri 换成
     * 标准媒体 Uri——换得动才删得掉。
     */
    fun mediaUrisForDelete(context: Context, uris: List<Uri>): List<Uri> =
        uris.mapNotNull { uri ->
            val normalized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
            } else {
                uri
            }
            normalized.takeIf { it.authority == MediaStore.AUTHORITY }
        }

    /**
     * 文件选择器 / 文件夹选择器来的 Uri（DocumentsProvider 管的）：直接请它删。
     * 返回删掉的个数；没有写权限之类的会失败，失败的不算数，交给调用方报给用户。
     */
    fun deleteDocuments(context: Context, uris: List<Uri>): Int {
        var deleted = 0
        uris.forEach { uri ->
            if (!DocumentsContract.isDocumentUri(context, uri)) return@forEach
            val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                .getOrDefault(false)
            if (ok) {
                deleted++
            } else {
                SendLog.d("导入", "这个来源删不掉，留给用户手动处理：" + uri)
            }
        }
        return deleted
    }

    /** zip 里的条目名：序号前缀保证不重名，名字本身只保留一个安全的文件名。 */
    private fun entryName(context: Context, uri: Uri, index: Int): String {
        val shown = ImageUtil.queryDisplayName(context, uri)
            ?.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            ?.trim()
            ?.take(60)
            ?.takeIf { it.isNotEmpty() }
        val label = shown ?: "emoji"
        return String.format(Locale.US, "%02d_%s", index + 1, label)
    }
}
