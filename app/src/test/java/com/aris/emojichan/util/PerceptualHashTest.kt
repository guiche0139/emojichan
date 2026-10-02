package com.aris.emojichan.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 感知哈希的纯计算部分（不碰 Android、不读文件）。
 *
 * 这些用例守的是「同一张图换个尺寸、换个亮度还算同一张」这条底线：dHash 一旦改成看亮度
 * 绝对值、或者缩略时改成抽点采样，表情库里最常见的那几种变化就会算出完全不同的哈希，
 * 相似检索也就成了摆设。
 */
class PerceptualHashTest {

    /** 灰度值 → 不透明像素。 */
    private fun argb(gray: Int): Int = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray

    /** 棋盘图：亮暗按 (x + y) 交替。四角不全同色，所以裁留白那一步不会动它。 */
    private fun checkerboard(width: Int, height: Int, dark: Int = 40, light: Int = 90): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            argb(if ((x + y) % 2 == 0) dark else light)
        }

    /** 上面那张棋盘图算出来的 64 位：偶数行 0x55、奇数行 0xAA，第 0 行在最低字节。 */
    private fun checkerHash(): Long {
        var hash = 0L
        for (row in 0 until 8) {
            val bits = if (row % 2 == 0) 0x55L else 0xAAL
            hash = hash or (bits shl (row * 8))
        }
        return hash
    }

    @Test
    fun grayCompositesTransparencyOntoWhite() {
        val gray = PerceptualHash.gray(
            intArrayOf(
                0x00000000,          // 全透明：盖在白底上就是白
                0xFF000000.toInt(),  // 不透明黑
                0xFFFFFFFF.toInt(),  // 不透明白
                Int.MIN_VALUE        // 半透明黑（0x80000000）：127
            ),
            4,
            1
        )

        assertArrayEquals(intArrayOf(255, 0, 255, 127), gray)
    }

    @Test
    fun grayUsesLumaWeights() {
        val gray = PerceptualHash.gray(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            3,
            1
        )

        // 77 / 150 / 29 各乘 256 的定点写法
        assertArrayEquals(intArrayOf(76, 149, 28), gray)
    }

    @Test
    fun grayRejectsImpossibleInput() {
        var rejectedPixels = false
        try {
            PerceptualHash.gray(IntArray(4), 3, 3)
        } catch (e: IllegalArgumentException) {
            rejectedPixels = true
        }
        assertTrue("像素数量比尺寸少时必须拒绝，否则会读到数组外面", rejectedPixels)

        var rejectedSize = false
        try {
            PerceptualHash.gray(IntArray(4), 2, 0)
        } catch (e: IllegalArgumentException) {
            rejectedSize = true
        }
        assertTrue("尺寸为 0 必须拒绝", rejectedSize)
    }

    @Test
    fun trimCutsUniformBorder() {
        val width = 10
        val height = 10
        val gray = IntArray(width * height) { 255 }
        for (y in 3..6) for (x in 3..6) gray[y * width + x] = 0

        val trimmed = PerceptualHash.trim(gray, width, height)

        assertEquals(4, trimmed.width)
        assertEquals(4, trimmed.height)
        assertTrue("裁出来的应该正好是中间那块黑的", trimmed.data.all { it == 0 })
    }

    @Test
    fun trimKeepsWholeImageWhenEverythingIsBackdrop() {
        val gray = IntArray(20) { 200 }

        val trimmed = PerceptualHash.trim(gray, 4, 5)

        assertEquals(4, trimmed.width)
        assertEquals(5, trimmed.height)
        assertSame("没东西可裁时应该原样返回，不必再拷一份", gray, trimmed.data)
    }

    @Test
    fun trimKeepsWholeImageWhenContentIsTooSmall() {
        val width = 40
        val height = 20
        val gray = IntArray(width * height) { 255 }
        gray[10 * width + 10] = 0

        val trimmed = PerceptualHash.trim(gray, width, height)

        // 裁完只剩 1/40 宽：宁可当成没有留白，也不要把正常图片裁坏
        assertEquals(width, trimmed.width)
        assertEquals(height, trimmed.height)
    }

    @Test
    fun resampleAveragesInsteadOfPickingPixels() {
        // 2×2 的平均是 15，抽任何一点都得不到它
        assertArrayEquals(intArrayOf(15), PerceptualHash.resample(intArrayOf(0, 10, 20, 30), 2, 2, 1, 1))
        // 除不尽时余下的列并入最后一块，不能被丢掉
        assertArrayEquals(intArrayOf(0, 150), PerceptualHash.resample(intArrayOf(0, 100, 200), 3, 1, 2, 1))
        // 放大：每个输出像素都取到那一个输入像素
        assertArrayEquals(intArrayOf(5, 5, 5, 5), PerceptualHash.resample(intArrayOf(5), 1, 1, 2, 2))
    }

    @Test
    fun dHashRecordsWhichSideIsBrighter() {
        val increasing = IntArray(9 * 8) { index -> (index % 9) * 10 }
        val decreasing = IntArray(9 * 8) { index -> 255 - (index % 9) * 10 }

        assertEquals("每行 8 次比较全为真 → 64 位全 1", -1L, PerceptualHash.dHash(increasing, 9, 8))
        assertEquals("全为假 → 64 位全 0", 0L, PerceptualHash.dHash(decreasing, 9, 8))
    }

    @Test
    fun dHashPacksBitsRowByRow() {
        val width = 9
        val height = 8

        // 偶数行是「暗亮暗亮」（0x55），奇数行反过来（0xAA）；低位字节是第 0 行
        assertEquals(checkerHash(), PerceptualHash.of(checkerboard(width, height), width, height))
    }

    @Test
    fun overallBrightnessDoesNotChangeTheHash() {
        val width = 9
        val height = 8

        assertEquals(
            PerceptualHash.of(checkerboard(width, height, dark = 40, light = 90), width, height),
            PerceptualHash.of(checkerboard(width, height, dark = 140, light = 190), width, height)
        )
    }

    @Test
    fun uniformBorderIsIgnored() {
        val figureWidth = 9
        val figureHeight = 8
        val figure = checkerboard(figureWidth, figureHeight)
        val tight = PerceptualHash.of(figure, figureWidth, figureHeight)

        val canvasWidth = 30
        val canvasHeight = 28
        val canvas = IntArray(canvasWidth * canvasHeight) { argb(255) }
        val left = 10
        val top = 10
        for (y in 0 until figureHeight) {
            for (x in 0 until figureWidth) {
                canvas[(top + y) * canvasWidth + left + x] = figure[y * figureWidth + x]
            }
        }

        assertEquals(
            "一张白底大图里画着的小图，应该跟裁掉留白的它自己算出同一个哈希",
            tight,
            PerceptualHash.of(canvas, canvasWidth, canvasHeight)
        )
    }

    @Test
    fun distanceCountsDifferingBits() {
        assertEquals(64, PerceptualHash.BITS)
        assertEquals(0, PerceptualHash.distance(0L, 0L))
        assertEquals(3, PerceptualHash.distance(0L, 0b111L))
        assertEquals(1, PerceptualHash.distance(checkerHash(), checkerHash() xor 1L))
        assertEquals(64, PerceptualHash.distance(checkerHash(), checkerHash().inv()))
        assertEquals(64, PerceptualHash.distance(-1L, 0L))
    }

    @Test
    fun trimmedComparesByContent() {
        assertEquals(
            PerceptualHash.Trimmed(intArrayOf(1, 2), 1, 2),
            PerceptualHash.Trimmed(intArrayOf(1, 2), 1, 2)
        )
        assertNotEquals(
            PerceptualHash.Trimmed(intArrayOf(1, 2), 1, 2),
            PerceptualHash.Trimmed(intArrayOf(1, 3), 1, 2)
        )
    }
}
