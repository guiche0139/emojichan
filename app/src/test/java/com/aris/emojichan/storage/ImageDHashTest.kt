package com.aris.emojichan.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 读盘这一层。
 *
 * 采样率是纯计算，这里逐个边界验；「读不出来就返回 null」用最典型的失败（文件不在）验一下 ——
 * 一张算不出来只能跳过这一张，不能把整轮比对带停。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageDHashTest {

    @Test
    fun sampleSizeHalvesUntilTheLongEdgeIsJustUnderTwiceTheTarget() {
        assertEquals(1, ImageDHash.sampleSize(64, 64))
        assertEquals(1, ImageDHash.sampleSize(127, 64))
        assertEquals(2, ImageDHash.sampleSize(128, 64))
        assertEquals(4, ImageDHash.sampleSize(256, 64))
        assertEquals(32, ImageDHash.sampleSize(4000, 3000))
    }

    @Test
    fun sampleSizeIsAlwaysAPowerOfTwo() {
        for (size in listOf(1, 63, 64, 65, 1000, 4000, 12000)) {
            val sample = ImageDHash.sampleSize(size, size)
            assertTrue(sample > 0 && (sample and (sample - 1)) == 0)
        }
    }

    @Test
    fun decodedLongEdgeStaysAtLeastTheTarget() {
        // 采样后最长边仍要比 9×8 大得多，否则哈希就成了几个像素的噪声
        for (size in listOf(64, 200, 1000, 8000)) {
            val sample = ImageDHash.sampleSize(size, size)
            assertTrue(size / sample >= 64)
        }
    }

    @Test
    fun missingFileGivesNoHash() {
        assertNull(ImageDHash.of(File("/data/definitely/not/here.png")))
    }

    @Test
    fun filesWithoutContentGiveNoHash() {
        val empty = File.createTempFile("dhash", ".png")
        try {
            assertNull("空文件不该被当成图片", ImageDHash.of(empty))
            assertNull("目录不是图片", ImageDHash.of(empty.parentFile!!))
        } finally {
            empty.delete()
        }
    }
}
