package com.aris.emojichan.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtil {
    private const val THUMBNAIL_SIZE = 200
    private const val EMOJI_DIR = "emojis"

    fun getEmojiDir(context: Context): File {
        val dir = File(context.filesDir, EMOJI_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun copyImageToInternal(context: Context, uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val ext = getImageExtension(context, uri)
                val fileName = "emoji_${System.currentTimeMillis()}.$ext"
                val destFile = File(getEmojiDir(context), fileName)
                FileOutputStream(destFile).use { output ->
                    stream.copyTo(output)
                }
                destFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 根据 Uri 的 MIME 类型推断图片扩展名，优先保证 GIF/PNG/WEBP 不被误存为 .jpg。
     * MIME 不可用时回退到文件名扩展名，最终兜底 .jpg。
     */
    private fun getImageExtension(context: Context, uri: Uri): String {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            return when (mime) {
                "image/gif" -> "gif"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                else -> "jpg"
            }
        }
        var displayName: String? = null
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(idx)
            }
        }
        val ext = displayName?.substringAfterLast('.', "")
        return if (!ext.isNullOrBlank() && ext.length <= 5) ext.lowercase() else "jpg"
    }

    /** 读取图片宽高（不解码完整位图，仅读边界信息）。 */
    fun getImageDimensions(filePath: String): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, options)
            options.outWidth to options.outHeight
        } catch (e: Exception) {
            e.printStackTrace()
            0 to 0
        }
    }

    fun createThumbnail(filePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            BitmapFactory.decodeFile(filePath, options)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > THUMBNAIL_SIZE || height / sampleSize > THUMBNAIL_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getFileSize(filePath: String): Long {
        return File(filePath).length()
    }

    fun isImageFile(filePath: String): Boolean {
        val lower = filePath.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp")
    }

    fun isGif(filePath: String): Boolean {
        return filePath.lowercase().endsWith(".gif")
    }
}
