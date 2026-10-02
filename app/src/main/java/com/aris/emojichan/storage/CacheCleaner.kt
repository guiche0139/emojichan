package com.aris.emojichan.storage

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 清缓存（v0.1.409 起，用户 m08399 第 1 条）：把 cacheDir 里的东西全删掉 —— 缩略图、
 * 压缩预览留下的临时文件……表情库、数据库、标签一律不碰。
 *
 * 为什么删之前先跟 Glide 打声招呼：缩略图的磁盘缓存目录（cacheDir/image_manager_disk_cache）
 * 自带一份日志，从外面把文件删掉，它下次读缓存时可能读到条目已经不在；交给它自己清，顺手把
 * 内存缓存也丢掉，省得刚清完又把旧缩略图从内存里画出来。
 *
 * 线程：Glide 的磁盘清理要求不在主线程、内存清理要求在主线程，这里两边都切好，调用方在协程里直接用。
 */
object CacheCleaner {

    /** 清一次，返回实际释放的字节数。 */
    suspend fun clear(context: Context): Long {
        val glide = withContext(Dispatchers.Main) { runCatching { Glide.get(context) }.getOrNull() }
        return withContext(Dispatchers.IO) {
            val before = StorageUsage.dirSize(context.cacheDir)
            if (glide != null) runCatching { glide.clearDiskCache() }
            deleteChildren(context.cacheDir)
            if (glide != null) {
                withContext(Dispatchers.Main) { runCatching { glide.clearMemory() } }
            }
            (before - StorageUsage.dirSize(context.cacheDir)).coerceAtLeast(0L)
        }
    }

    /** 只删目录里的东西，目录本身留着 —— 那是系统给的缓存位置，删掉还得重建。 */
    private fun deleteChildren(dir: File?) {
        val children = dir?.listFiles() ?: return
        children.forEach { child -> runCatching { child.deleteRecursively() } }
    }
}
