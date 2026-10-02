package com.aris.emojichan.util

import kotlin.math.abs

/**
 * 感知哈希（v0.1.409）：把一张图压成 64 位，长得像的图哈希也像，「差几位」就是相似程度。
 *
 * 为什么用 dHash（差分哈希）：
 * - aHash 拿全图平均亮度当基准，整张图一亮一暗就全变位；
 * - pHash 要手写 DCT，多出来的代码量在表情库上换不来相应收益；
 * - dHash 比的是「相邻像素谁更亮」，对整体亮度、对比度、压缩噪声都不敏感 ——
 *   而表情库里最常见的变化恰恰是「被压过一次」「换过尺寸」「换过格式」。
 *
 * 这个文件不碰 Android：进来的是 ARGB 像素数组，出去是 Long。把文件解码成像素那一步在
 * [com.aris.emojichan.storage.ImageDHash]。
 */
object PerceptualHash {

    /** 位数：8 行 × 每行 8 次横向比较。 */
    const val BITS = 64

    /** 裁留白时允许的灰度差：比它大才算「不是背景」。 */
    const val TRIM_TOLERANCE = 10

    private const val HASH_COLS = 9
    private const val HASH_ROWS = 8

    /** 裁过留白的一块灰度图。 */
    class Trimmed(val data: IntArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean = other is Trimmed &&
            width == other.width && height == other.height && data.contentEquals(other.data)

        override fun hashCode(): Int = (width * 31 + height) * 31 + data.contentHashCode()

        override fun toString(): String = "Trimmed(${width}x${height})"
    }

    /**
     * ARGB 像素 → 8 位灰度。
     *
     * 透明像素先合成到白底上：表情库里大量 PNG 是透明底，直接取 RGB 会读到 0（黑），
     * 同一张图换个解码方式就会算出完全不同的哈希。
     */
    fun gray(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "尺寸必须是正数" }
        require(pixels.size >= width * height) { "像素数量比尺寸少" }
        val out = IntArray(width * height)
        for (index in out.indices) {
            val argb = pixels[index]
            val alpha = (argb ushr 24) and 0xFF
            val red = over(alpha, (argb ushr 16) and 0xFF)
            val green = over(alpha, (argb ushr 8) and 0xFF)
            val blue = over(alpha, argb and 0xFF)
            // 人眼权重的定点写法：0.299 / 0.587 / 0.114 各乘 256
            out[index] = (77 * red + 150 * green + 29 * blue) shr 8
        }
        return out
    }

    /** 半透明按「盖在白底上」算。 */
    private fun over(alpha: Int, channel: Int): Int =
        (channel * alpha + 255 * (255 - alpha)) / 255

    /**
     * 裁掉四周的纯色留白：只留下跟四角颜色明显不同的那一块。
     *
     * 表情包里「一张 500×500 的白底图里画着 200×200 的小人」很常见。不裁的话哈希算的
     * 大半是留白，两张八竿子打不着的贴纸都会因为「一大片是白的」而算出很小的距离。
     *
     * 两种情况下原样返回：整张图都是背景色（没东西可裁），以及裁完某一维只剩不到四分之一
     * （宁可当成没有留白，也不要把正常图片裁坏）。
     */
    fun trim(gray: IntArray, width: Int, height: Int, tolerance: Int = TRIM_TOLERANCE): Trimmed {
        require(width > 0 && height > 0) { "尺寸必须是正数" }
        require(gray.size >= width * height) { "灰度数据比尺寸少" }
        val backdrop = backdropColor(gray, width, height)

        fun rowIsBackdrop(y: Int): Boolean {
            val start = y * width
            for (x in 0 until width) {
                if (abs(gray[start + x] - backdrop) > tolerance) return false
            }
            return true
        }

        fun columnIsBackdrop(x: Int, fromRow: Int, toRow: Int): Boolean {
            for (y in fromRow..toRow) {
                if (abs(gray[y * width + x] - backdrop) > tolerance) return false
            }
            return true
        }

        var top = 0
        while (top < height && rowIsBackdrop(top)) top++
        if (top == height) return Trimmed(gray, width, height)
        var bottom = height - 1
        while (bottom > top && rowIsBackdrop(bottom)) bottom--
        var left = 0
        while (left < width && columnIsBackdrop(left, top, bottom)) left++
        var right = width - 1
        while (right > left && columnIsBackdrop(right, top, bottom)) right--

        val contentWidth = right - left + 1
        val contentHeight = bottom - top + 1
        if (contentWidth * 4L < width || contentHeight * 4L < height) {
            return Trimmed(gray, width, height)
        }
        val cropped = IntArray(contentWidth * contentHeight)
        for (row in 0 until contentHeight) {
            System.arraycopy(gray, (top + row) * width + left, cropped, row * contentWidth, contentWidth)
        }
        return Trimmed(cropped, contentWidth, contentHeight)
    }

    /** 背景色取四个角的平均值 —— 只看一个角容易被噪点带偏。 */
    private fun backdropColor(gray: IntArray, width: Int, height: Int): Int =
        (gray[0] + gray[width - 1] + gray[(height - 1) * width] + gray[height * width - 1]) / 4

    /**
     * 面积平均重采样：每个输出像素取它在原图里覆盖的那一块的平均值。
     *
     * 缩小时不能抽点：抽点会让哈希取决于「刚好抽到了哪些像素」，同一张图换个尺寸
     * 就可能算出不同的哈希。
     */
    fun resample(data: IntArray, width: Int, height: Int, outWidth: Int, outHeight: Int): IntArray {
        require(width > 0 && height > 0 && outWidth > 0 && outHeight > 0) { "尺寸必须是正数" }
        require(data.size >= width * height) { "数据比尺寸少" }
        val out = IntArray(outWidth * outHeight)
        for (outY in 0 until outHeight) {
            val startY = outY * height / outHeight
            val endY = minOf(maxOf(startY + 1, (outY + 1) * height / outHeight), height)
            for (outX in 0 until outWidth) {
                val startX = outX * width / outWidth
                val endX = minOf(maxOf(startX + 1, (outX + 1) * width / outWidth), width)
                var sum = 0
                var count = 0
                for (y in startY until endY) {
                    val rowStart = y * width
                    for (x in startX until endX) {
                        sum += data[rowStart + x]
                        count++
                    }
                }
                out[outY * outWidth + outX] = if (count == 0) 0 else sum / count
            }
        }
        return out
    }

    /**
     * dHash：缩到 9×8 灰度，右边比左边亮就记 1，逐行拼成 64 位。
     *
     * 9 列而不是 8 列：每行要比 8 次相邻像素，8 位 × 8 行正好 64 位。
     */
    fun dHash(gray: IntArray, width: Int, height: Int): Long {
        val grid = resample(gray, width, height, HASH_COLS, HASH_ROWS)
        var hash = 0L
        for (row in 0 until HASH_ROWS) {
            for (col in 0 until HASH_COLS - 1) {
                val bit = row * (HASH_COLS - 1) + col
                if (grid[row * HASH_COLS + col] < grid[row * HASH_COLS + col + 1]) {
                    hash = hash or (1L shl bit)
                }
            }
        }
        return hash
    }

    /** 一步到位：ARGB 像素 → 灰度 → 裁留白 → 64 位哈希。 */
    fun of(pixels: IntArray, width: Int, height: Int): Long {
        val trimmed = trim(gray(pixels, width, height), width, height)
        return dHash(trimmed.data, trimmed.width, trimmed.height)
    }

    /** 两个哈希差几位：0 是几乎同一张，越大越不像。 */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
