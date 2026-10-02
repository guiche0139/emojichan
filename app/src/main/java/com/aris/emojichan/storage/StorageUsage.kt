package com.aris.emojichan.storage

import android.content.Context
import android.text.format.Formatter
import com.aris.emojichan.data.EmojiEntity
import java.io.File
import kotlin.math.roundToInt

/** 一种文件格式占了多少张、多少字节。 */
data class FormatUsage(val format: String, val count: Int, val bytes: Long)

/**
 * 库里的一张表情在磁盘上的实际情况。
 *
 * [bytes] 取自文件本身，不是数据库里记的那个数 —— 数据库里的 fileSize 只是导入那一刻的快照，
 * 两边对不上时以磁盘为准。
 */
data class EmojiFile(
    val id: Long,
    val name: String,
    val path: String,
    val format: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val missing: Boolean
)

/** 存储概览要用的一整份统计结果。 */
data class StorageSnapshot(
    val files: List<EmojiFile>,
    val byFormat: List<FormatUsage>,
    val libraryBytes: Long,
    val databaseBytes: Long,
    val cacheBytes: Long,
    val missingCount: Int
) {
    val otherBytes: Long get() = databaseBytes + cacheBytes
    val totalBytes: Long get() = libraryBytes + otherBytes
}

/**
 * 存储统计。全部按文件系统实际占用算：每张表情的图片都重新 stat 一遍，数据库和缓存也现算，
 * 不图快 —— 这个页面的全部意义就是「到底占了多少」。
 */
object StorageUsage {

    private const val DATABASE_NAME = "emoji_database"
    private const val UNKNOWN_FORMAT = "其它"

    /** 格式按扩展名认。数据库里只记了「图片 / 动图」，格式得看路径。 */
    fun formatOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return UNKNOWN_FORMAT
        return when (name.substring(dot + 1).lowercase()) {
            "jpg", "jpeg" -> "JPG"
            "png" -> "PNG"
            "gif" -> "GIF"
            "webp" -> "WEBP"
            "bmp" -> "BMP"
            else -> UNKNOWN_FORMAT
        }
    }

    fun load(context: Context, emojis: List<EmojiEntity>): StorageSnapshot {
        val files = emojis.map { entity ->
            val file = File(entity.filePath)
            val exists = file.isFile
            EmojiFile(
                id = entity.id,
                name = entity.name,
                path = entity.filePath,
                format = formatOf(entity.filePath),
                bytes = if (exists) file.length() else 0L,
                width = entity.width,
                height = entity.height,
                animated = EmojiCompressor.isAnimated(entity.filePath, entity.fileType),
                missing = !exists
            )
        }
        val byFormat = files
            .groupBy { it.format }
            .map { (format, group) -> FormatUsage(format, group.size, group.sumOf { it.bytes }) }
            .sortedByDescending { it.bytes }
        return StorageSnapshot(
            files = files,
            byFormat = byFormat,
            libraryBytes = files.sumOf { it.bytes },
            databaseBytes = databaseBytes(context),
            cacheBytes = dirSize(context.cacheDir),
            missingCount = files.count { it.missing }
        )
    }

    /** 数据库自己的体积：Room 库名是 emoji_database，开了 WAL 还会多两个旁文件。 */
    fun databaseBytes(context: Context): Long {
        val base = context.getDatabasePath(DATABASE_NAME)
        return listOf(base, File(base.path + "-wal"), File(base.path + "-shm"))
            .sumOf { if (it.isFile) it.length() else 0L }
    }

    fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        val children = dir.listFiles() ?: return 0L
        return children.sumOf { dirSize(it) }
    }

    /** 体积一律走系统那套单位（1.2 MB / 340 KB），跟导出备份那页保持一致。 */
    fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun percentOf(part: Long, whole: Long): Int =
        if (whole <= 0L) 0 else ((part * 100.0) / whole).roundToInt()
}
