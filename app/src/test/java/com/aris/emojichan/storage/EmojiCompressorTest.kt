package com.aris.emojichan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 压缩里跟位图无关的那几条判据。
 *
 * 真去压一张图要在设备上跑；这里守的是三件容易写错、错了又不容易发现的事：
 * 多大的图会被判「装不下」、无损在这台系统上到底落的什么格式、后缀跟编码方式是否一致。
 */
@RunWith(RobolectricTestRunner::class)
class EmojiCompressorTest {

    @Test
    fun tooBigForHeapRejectsZeroSizedOrUnknownInput() {
        assertTrue(EmojiCompressor.tooBigForHeap(0, 100, 512L * 1024 * 1024))
        assertTrue(EmojiCompressor.tooBigForHeap(100, 0, 512L * 1024 * 1024))
        assertTrue(EmojiCompressor.tooBigForHeap(-1, 100, 512L * 1024 * 1024))
        assertTrue(EmojiCompressor.tooBigForHeap(100, 100, 0L))
    }

    @Test
    fun tooBigForHeapDrawsTheLineAtHalfTheHeap() {
        val heap = 512L * 1024 * 1024
        // 堆的一半是 256 MB，按每像素 4 字节算正好 8192 × 8192 像素：正好一半不算超，再多一列就超。
        assertFalse(EmojiCompressor.tooBigForHeap(8192, 8192, heap))
        assertTrue(EmojiCompressor.tooBigForHeap(8193, 8192, heap))
    }

    /** Android 11（API 30）起平台才有无损 WebP 编码器。 */
    @Test
    @Config(sdk = [33])
    fun losslessIsWebpOnAndroidElevenAndUp() {
        assertFalse(EmojiCompressor.losslessUsesPng())
        assertEquals("webp", EmojiCompressor.targetExtension(lossless = true))
        assertEquals("webp", EmojiCompressor.targetExtension(lossless = false))
    }

    /** 更旧的系统没有无损 WebP，退成 PNG —— 无损这条口径不能因为系统老就变成有损。 */
    @Test
    @Config(sdk = [29])
    fun losslessFallsBackToPngBelowAndroidEleven() {
        assertTrue(EmojiCompressor.losslessUsesPng())
        assertEquals("png", EmojiCompressor.targetExtension(lossless = true))
        assertEquals("webp", EmojiCompressor.targetExtension(lossless = false))
    }
}
