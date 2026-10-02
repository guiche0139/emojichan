package com.aris.emojichan.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.aris.emojichan.util.ImageTypes
import com.aris.emojichan.util.ImageUtil
import java.io.File
import java.io.FileOutputStream

/**
 * 压大图。
 *
 * 只做一件事：按原尺寸重新编码成 WebP，压完确实更小才替换 —— 分辨率一个像素都不动，
 * 省下来的是编码格式与质量上的冗余，不是尺寸（v0.1.408 起的口径：不改分辨率，只换编码）。
 *
 * 编码方式由 [compressTo] 的 lossless 决定：有损是 WebP 质量 80；无损逐像素和原图一致，
 * 但体积经常反而更大 —— 那就走 NO_GAIN 不替换。无损编码器 Android 11（API 30）才有，
 * 更旧的系统退成 PNG（同样无损，系统自带的编码器一定是有的）。
 *
 * 图片一旦被重新编码，动图就只剩第一帧，所以动图整个不参与 —— 省空间不该拿动图去换。
 *
 * 既然不缩分辨率，就得把整张原图读进内存：解码前先按「宽 × 高 × 4 字节」估一遍，
 * 超过可分配堆的一半就判 TOO_BIG 放弃 —— 几千万像素的图压不动好过把应用撑崩。
 *
 * 压缩完只返回新文件，不认识数据库 —— 回写由调用方做，这样它也能被单独测。
 */
object EmojiCompressor {

    /** 有损编码的质量。无损编码器忽略这个值。 */
    const val QUALITY = 80

    /** ARGB_8888 每个像素 4 字节。 */
    private const val BYTES_PER_PIXEL = 4L

    /** 解码后的位图最多允许占到可分配堆的一半，另一半留给编码器和界面。 */
    private const val HEAP_DIVISOR = 2L

    enum class Status { DONE, NO_GAIN, TOO_BIG, FAILED }

    /**
     * 一次压缩的结果。
     *
     * [Status.DONE] 时 [path] 是刚写出来的新文件，调用方负责回写数据库、再删掉旧文件；
     * NO_GAIN、TOO_BIG、FAILED 时磁盘上不留任何东西（临时文件已经删掉了）。
     *
     * [width] / [height] 是压完的尺寸 —— 现在等于原图尺寸（不缩分辨率），留着是为了让
     * 预览页能照旧显示大小，将来真要改回缩图也不用改调用方。
     */
    data class Outcome(
        val status: Status,
        val path: String? = null,
        val bytes: Long = 0L,
        val width: Int = 0,
        val height: Int = 0
    )

    /**
     * 动图判定：数据库里的类型、扩展名，再加 WebP 文件头里的动画标志位。
     * 动态 WebP 的扩展名和静图一模一样，只能看头。
     */
    fun isAnimated(path: String, fileType: String? = null): Boolean {
        if (fileType == ImageTypes.FILE_TYPE_ANIMATED || ImageUtil.isGif(path)) return true
        return ImageTypes.isAnimatedWebp(File(path))
    }

    /**
     * 无损编码在这台系统上要不要退成 PNG：Android 11（API 30）起平台才有无损 WebP 编码器，
     * 再往前的 [Bitmap.CompressFormat.WEBP] 只有有损一种写法。
     */
    fun losslessUsesPng(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** 压完写出去的后缀：无损在旧系统上落成 png，其余都是 webp。 */
    fun targetExtension(lossless: Boolean): String =
        if (lossless && losslessUsesPng()) "png" else "webp"

    fun compress(path: String): Outcome = compressTo(path, null)

    /**
     * 压出来的新文件写到哪里由调用方定：[outDir] 传 null 就写在原图旁边（直接替换的老路子），
     * 传别的目录就是「先写到一边、等用户看过再决定」—— 预览页用的就是后者。
     */
    fun compressTo(path: String, outDir: File?, lossless: Boolean = false): Outcome {
        val source = File(path)
        if (!source.isFile) return Outcome(Status.FAILED)
        if (isAnimated(path)) return Outcome(Status.FAILED)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Outcome(Status.FAILED)

        if (tooBigForHeap(bounds.outWidth, bounds.outHeight, Runtime.getRuntime().maxMemory())) {
            return Outcome(Status.TOO_BIG)
        }

        // 不缩分辨率：整张原图读进来，压缩只是换编码。内存真不够时宁可放弃也不崩。
        val decoded = try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options())
        } catch (e: OutOfMemoryError) {
            return Outcome(Status.TOO_BIG)
        } ?: return Outcome(Status.FAILED)

        var bitmap = rotateByExif(path, decoded)
        if (bitmap !== decoded) decoded.recycle()

        val width = bitmap.width
        val height = bitmap.height
        val dir = outDir ?: source.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) {
            bitmap.recycle()
            return Outcome(Status.FAILED)
        }
        val target = File(dir, ImageUtil.newEmojiFileName(targetExtension(lossless)))
        val written = try {
            FileOutputStream(target).use { out -> bitmap.compress(compressFormat(lossless), QUALITY, out) }
        } catch (e: OutOfMemoryError) {
            // 编码这一步编码器自己也要开缓冲区，同样可能撑不住 —— 走的还是「跳过」这条路。
            target.delete()
            bitmap.recycle()
            return Outcome(Status.TOO_BIG)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        bitmap.recycle()

        if (!written || !target.isFile) {
            target.delete()
            return Outcome(Status.FAILED)
        }
        val bytes = target.length()
        if (bytes <= 0L || bytes >= source.length()) {
            // 没省下空间就什么都别动：压完更大属于帮倒忙。
            target.delete()
            return Outcome(Status.NO_GAIN)
        }
        return Outcome(Status.DONE, target.absolutePath, bytes, width, height)
    }

    private fun compressFormat(lossless: Boolean): Bitmap.CompressFormat = when {
        lossless && !losslessUsesPng() -> Bitmap.CompressFormat.WEBP_LOSSLESS
        lossless -> Bitmap.CompressFormat.PNG
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }

    /**
     * 解码这张图要占的内存，是不是超过了可分配堆的一半。
     *
     * 纯算术、不碰位图，所以能直接单测「多大的图会被判太大」这条线在哪里。
     */
    fun tooBigForHeap(width: Int, height: Int, maxMemory: Long): Boolean {
        if (width <= 0 || height <= 0) return true
        if (maxMemory <= 0L) return true
        val needed = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        return needed > maxMemory / HEAP_DIVISOR
    }

    /**
     * 按 EXIF 方向摆正。重新编码会把 EXIF 丢掉，不先转正，本来躺着的照片会压成躺倒的、
     * 本来就正的会被压躺下 —— 这一趟不能省。摆正只改像素朝向，不改分辨率。
     */
    private fun rotateByExif(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
