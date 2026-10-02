package com.aris.emojichan.util

import android.content.Context
import android.net.Uri
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.sender.SendLog

/**
 * 把一个外部 Uri 变成一条待入库的表情记录。
 *
 * 相册导入、文件导入、文件夹导入三条路都走这里：名字怎么取、动图怎么判、宽高体积从哪来
 * 只写一份，免得三条路各有各的口径（emc-2-008 就是两处口径不一致留下的坑）。
 *
 * 会读文件、解图片，必须在 IO 线程上调用。
 */
object EmojiImport {

    /** @param usedFallback true = 上游没给真名，用了导入时间兜底（emc-2-008）。 */
    data class Result(val emoji: EmojiEntity?, val usedFallback: Boolean)

    /**
     * @param log 是否把取名的过程写进日志（tag「导入」）。批量导入几百张时逐个写会把日志撑爆。
     */
    fun fromUri(context: Context, uri: Uri, log: Boolean = true): Result {
        // 名字保持原样：拿系统给的显示名（掐掉扩展名）当表情名。重名不在这里处理，
        // 数据层会在同一个事务里加 (1)(2)…，这样多选导入的同一批也不会撞名。
        val fromUpstream = ImageUtil.queryDisplayName(context, uri)
            ?.substringBeforeLast('.')?.trim().orEmpty()
        val usedFallback = fromUpstream.isEmpty()
        val baseName = fromUpstream.ifEmpty { ImageUtil.fallbackName(context) }
        if (log) {
            // 名字这块只有这里知道真相：用户再说「名字又不对」时，日志里能看到上游给了什么
            SendLog.d("导入", "采用名=" + baseName + (if (usedFallback) "（兜底：上游没给真名）" else "（上游原名）"))
            SendLog.d("导入", ImageUtil.describeNames(context, uri))
        }
        val filePath = ImageUtil.copyImageToInternal(context, uri)
            ?: return Result(null, usedFallback)
        val (width, height) = ImageUtil.getImageDimensions(filePath)
        return Result(
            emoji = EmojiEntity(
                name = baseName,
                filePath = filePath,
                fileType = ImageTypes.fileTypeOf(filePath),
                fileSize = ImageUtil.getFileSize(filePath),
                width = width,
                height = height
            ),
            usedFallback = usedFallback
        )
    }
}
