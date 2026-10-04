package com.aris.emojichan.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * 批量导入的顺序（用户 m11668 第 4 条）：按**源文件时间从早到晚**一张张进来。
 *
 * 为什么要排：表情落库时 createTime 记的是「导入那一刻」，而网格按 createTime 倒序 ——
 * 从早到晚导入，最后进来的那批（源文件时间最新的）正好排在网格最前面。
 * 用户的说法是「一般可能用到的表情时间会更靠近一些」，这样它们在第一屏，不用在几千张里翻。
 *
 * 时间读不出来的按 0 算：排在最前、最后沉到网格底部；排序是稳定的，
 * 读不出时间的那些之间保持原来的选择顺序。
 */
object ImportOrder {

    /**
     * 按源文件时间升序排一遍；[uris] 少于两张时原样返回（连查询都省了）。
     *
     * 一趟一张、单列查询，很轻，但仍是 IO：调用方放在后台线程上。
     */
    fun sortedByTime(context: Context, uris: List<Uri>): List<Uri> {
        if (uris.size < 2) return uris
        val times = HashMap<Uri, Long>(uris.size)
        uris.forEach { times[it] = timeOf(context, it) }
        return uris.withIndex()
            .sortedWith(compareBy({ times[it.value] ?: 0L }, { it.index }))
            .map { it.value }
    }

    /**
     * 两个候选列逐个试，谁认出来就用谁：相册与照片选择器给的是 MediaStore 的
     * DATE_MODIFIED（**秒**），系统文件选择器给的 SAF 文档是 DocumentsContract 的
     * COLUMN_LAST_MODIFIED（**毫秒**）。
     *
     * 单个列不被支持时查询会抛 IllegalArgumentException，所以逐列 catch ——
     * 一个列不认识不该把整条链拖垮，最后兜底是 0。
     */
    private fun timeOf(context: Context, uri: Uri): Long =
        queryLong(context, uri, MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000L)
            ?: queryLong(context, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: 0L

    private fun queryLong(context: Context, uri: Uri, column: String): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}
