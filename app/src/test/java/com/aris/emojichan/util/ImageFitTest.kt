package com.aris.emojichan.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 详情页图片尺寸：整张可见、比例不变、小图不放大（v0.1.409，用户 m08399 第 3 条）。
 *
 * 这些数字就是「一张图在剩下的那块地方里该占多大」，改这里的期望值之前先想清楚
 * 用户会看到什么 —— 算错一点点就是裁掉一条边或者四周一大片空白。
 */
class ImageFitTest {

    /** 横图：宽度顶满，高度按比例，上下留白。 */
    @Test
    fun wideImageFillsWidth() {
        assertEquals(900 to 450, ImageFit.fit(2000, 1000, 900, 1200, 48))
    }

    /** 竖图：高度顶满，宽度按比例，左右留白。 */
    @Test
    fun tallImageFillsHeight() {
        assertEquals(600 to 1200, ImageFit.fit(1000, 2000, 900, 1200, 48))
    }

    /** 正方形：取小的那一边，不会溢出。 */
    @Test
    fun squareImageUsesShorterSide() {
        assertEquals(900 to 900, ImageFit.fit(2000, 2000, 900, 1200, 48))
    }

    /** 长截图：又细又长，宽度跟着高度走。 */
    @Test
    fun longScreenshotKeepsRatio() {
        assertEquals(130 to 1200, ImageFit.fit(1080, 10000, 900, 1200, 48))
    }

    /** 小图不放大：原图比可用区域小，就按原尺寸摆，不糊。 */
    @Test
    fun smallImageIsNotUpscaled() {
        assertEquals(64 to 64, ImageFit.fit(64, 64, 900, 1200, 48))
    }

    /** 小得离谱的图也别缩成看不见：兜到最小边。 */
    @Test
    fun tinyImageFallsBackToMinSide() {
        assertEquals(48 to 48, ImageFit.fit(10, 10, 900, 1200, 48))
    }

    /** 留白是加在原图外面的，不占图片本身的地方。 */
    @Test
    fun insetIsAddedOnTop() {
        assertEquals(24 + 64 to 24 + 64, ImageFit.fit(64, 64, 900, 1200, 48, inset = 24))
    }

    /** 留白之后可用区域变小，图跟着缩：0.438 × 2000 = 876，再加 24 的留白，正好还是 900。 */
    @Test
    fun insetShrinksAvailableArea() {
        assertEquals(900 to 462, ImageFit.fit(2000, 1000, 900, 1200, 48, inset = 24))
    }

    /** 读不到原图尺寸：按正方形放到最大。 */
    @Test
    fun unknownSizeUsesSquare() {
        assertEquals(900 to 900, ImageFit.fit(0, 0, 900, 1200, 48))
        assertEquals(900 to 900, ImageFit.fit(-1, 500, 900, 1200, 48))
    }

    /** 还没排版（可用区域为 0）：先给最小方块，等布局监听回调。 */
    @Test
    fun noAreaYetGivesMinSide() {
        assertEquals(48 to 48, ImageFit.fit(2000, 1000, 0, 0, 48))
    }

    /** 可用区域比留白还小（分屏到极窄）也别算出负数。 */
    @Test
    fun areaSmallerThanInsetDoesNotGoNegative() {
        val (w, h) = ImageFit.fit(200, 100, 20, 20, 48, inset = 40)
        assertEquals(48 to 48, w to h)
    }
}
