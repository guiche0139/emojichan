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
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                lastUri = uri
                SendLog.d("相册", "已放进系统相册：" + name + "（" + mime + "）")
                uri
            }
        }.getOrElse { e ->
            SendLog.d("相册", "放进相册出错：" + e.javaClass.simpleName + "：" + (e.message?.take(80) ?: ""))
            null
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
