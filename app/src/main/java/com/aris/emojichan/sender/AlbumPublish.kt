package com.aris.emojichan.sender

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 把表情临时放进系统相册。
 *
 * 只服务于「相册路线」：微信 / QQ 自己的相册入口只看得见系统相册里的图，
 * 看不见我们 App 私有目录里的文件。发完就删，不在用户相册里留东西。
 */
object AlbumPublish {

    private const val ALBUM_DIR = "Pictures/EmojiChan"
    private const val PREFIX = "emojichan_"

    /** 上一次插进去的那条相册记录，下次要清掉的就是它。 */
    @Volatile
    private var lastUri: Uri? = null

    /** 返回插进去的那条记录；系统版本太低或出错时返回 null（调用方退回别的路线）。 */
    fun publish(context: Context, sourcePath: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            SendLog.d("相册", "系统低于 Android 10，没有相册路线可用")
            return null
        }
        val source = java.io.File(sourcePath)
        if (!source.exists()) {
            SendLog.d("相册", "图片文件不在了：" + sourcePath)
            return null
        }
        // 先把上一条清掉，免得相册里越攒越多。
        removeLast(context)
        return runCatching {
            val name = PREFIX + System.currentTimeMillis() + "_" + source.name
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                SendLog.d("相册", "插入系统相册失败：insert 返回 null")
                null
            } else {
                // 写不进去时必须把这条记录删掉：留着一条 0 字节的 IS_PENDING 记录，
                // 相册里会多出一张永远打不开的空图（emc-1-030）。
                val out = context.contentResolver.openOutputStream(uri)
                if (out == null) {
                    SendLog.d("相册", "拿不到输出流，把刚插进去的那条记录删掉")
                    context.contentResolver.delete(uri, null, null)
                    return@runCatching null
                }
                val written: Long = out.use { target ->
                    source.inputStream().use { input -> input.copyTo(target) }
                }
                if (written <= 0L) {
                    SendLog.d("相册", "写出 0 字节，把刚插进去的那条记录删掉")
                    context.contentResolver.delete(uri, null, null)
                    return@runCatching null
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                lastUri = uri
                SendLog.d("相册", "已放进系统相册：" + name + "（" + mime + "，" + written + " 字节）")
                uri
            }
        }.getOrElse { e ->
            SendLog.d("相册", "放进相册出错：" + e.javaClass.simpleName + "：" + (e.message?.take(80) ?: ""))
            null
        }
    }

    /**
     * 清理上次运行留下的临时图（emc-1-031）。
     *
     * [removeLast] 只记得住内存里的那一条：进程被系统杀掉时它没机会跑，相册里就会
     * 一直躺着一张 emojichan_ 开头的图。这里按本应用专属的文件名前缀扫一遍删掉，
     * 不会碰到用户自己的照片；Android 10 起应用也只能删自己贡献的那部分。
     */
    fun cleanStale(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val deleted = context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                arrayOf(PREFIX + "%")
            )
            if (deleted > 0) SendLog.d("相册", "清理掉上次留下的 " + deleted + " 张临时图")
        }.onFailure { e ->
            SendLog.d("相册", "清理残留临时图失败：" + (e.message?.take(60) ?: e.javaClass.simpleName))
        }
    }

    /** 删掉刚插进去的临时图。要放在发送之后调用，删早了微信还没读完文件。 */
    fun removeLast(context: Context) {
        val uri = lastUri ?: return
        lastUri = null
        runCatching {
            val rows = context.contentResolver.delete(uri, null, null)
            SendLog.d("相册", "相册里那张临时图已清理（删除 " + rows + " 条）")
        }.onFailure { e ->
            SendLog.d("相册", "清理临时图失败：" + (e.message?.take(60) ?: e.javaClass.simpleName))
        }
    }
}
