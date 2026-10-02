package com.aris.emojichan.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aris.emojichan.util.PerceptualHash
import java.io.File

/**
 * 从磁盘上的图片文件算感知哈希（v0.1.409）。
 *
 * 先把最长边缩到 [SAMPLE_EDGE] 像素再解码：哈希要的只是 9×8 的灰度，全尺寸解码纯属浪费，
 * 表情库里几千万像素的长截图还可能直接把内存顶掉。这一层还负责把「读不出来」变成 null ——
 * 文件被删、后缀不是图片、内存不够，都只是这一张跳过，不该让整轮比对崩掉。
 */
object ImageDHash {

    /** 采样后的最长边：比 9×8 大得多，够哈希用，解码又足够便宜。 */
    private const val SAMPLE_EDGE = 64

    /** 算不出来就返回 null。 */
    fun of(file: File): Long? {
        // 文件被删了、或者只剩个空壳：别去打扰解码器
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val sourceWidth = bounds.outWidth
            val sourceHeight = bounds.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(sourceWidth, sourceHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decodeAndHash(BitmapFactory.decodeFile(file.path, options))
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun decodeAndHash(bitmap: Bitmap?): Long? {
        if (bitmap == null) return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) {
                null
            } else {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                PerceptualHash.of(pixels, width, height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 采样率只能是 2 的幂：缩到最长边不超过 [SAMPLE_EDGE]。 */
    internal fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        while (maxOf(width, height) / (size * 2) >= SAMPLE_EDGE) size *= 2
        return size
    }
}
