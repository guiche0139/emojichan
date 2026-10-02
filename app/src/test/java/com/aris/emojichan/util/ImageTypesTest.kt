package com.aris.emojichan.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型对照表：扩展名怎么认、MIME 给谁、什么算动图。
 *
 * 这张表是「一张图从库到微信」的必经之路，v0.1.408 之前散在六七处各写一份，
 * 结果是分享给微信时 webp 被当成认不出的类型、动态 webp 被当成静图。
 *
 * 最要紧的一组是动态 WebP：它的扩展名和静图完全一样，只能读文件头，认错就把动图压成静图。
 */
class ImageTypesTest {

    @Test
    fun extensionOfIgnoresCaseAndTakesTheLastDot() {
        assertEquals("jpg", ImageTypes.extensionOf("/data/emojis/emoji_1.jpg"))
        assertEquals("jpeg", ImageTypes.extensionOf("/data/emojis/emoji_2.JPEG"))
        assertEquals("webp", ImageTypes.extensionOf("/data/emojis/emoji_3.WebP"))
        assertEquals("png", ImageTypes.extensionOf("/data/emojis/emoji.4.tar.png"))
    }

    @Test
    fun extensionOfHandlesWindowsSeparatorsAndWeirdNames() {
        assertEquals("gif", ImageTypes.extensionOf("C:\\emoji\\foo.gif"))
        assertEquals("", ImageTypes.extensionOf("/data/emojis/no_extension"))
        assertEquals("", ImageTypes.extensionOf("/data/emojis/trailing_dot."))
        assertEquals("", ImageTypes.extensionOf("/data/emojis/.hidden"))
        assertEquals("", ImageTypes.extensionOf(""))
    }

    @Test
    fun supportedCoversTheSixImportableFormats() {
        listOf("a.jpg", "a.jpeg", "a.png", "a.gif", "a.webp", "a.bmp").forEach {
            assertTrue(it, ImageTypes.isSupported(it))
        }
        assertFalse(ImageTypes.isSupported("/data/emojis/a.psd"))
        assertFalse(ImageTypes.isSupported("/data/emojis/a"))
    }

    @Test
    fun mimeOfExtensionMapsEverySupportedFormat() {
        assertEquals("image/jpeg", ImageTypes.mimeOfExtension("jpg"))
        assertEquals("image/jpeg", ImageTypes.mimeOfExtension("jpeg"))
        assertEquals("image/png", ImageTypes.mimeOfExtension("png"))
        assertEquals("image/gif", ImageTypes.mimeOfExtension("gif"))
        assertEquals("image/webp", ImageTypes.mimeOfExtension("webp"))
        assertEquals("image/bmp", ImageTypes.mimeOfExtension("bmp"))
        assertEquals(ImageTypes.UNKNOWN_MIME, ImageTypes.mimeOfExtension("psd"))
        assertEquals(ImageTypes.UNKNOWN_MIME, ImageTypes.mimeOfExtension(""))
    }

    @Test
    fun mimeOfReadsThePath() {
        assertEquals("image/webp", ImageTypes.mimeOf("/data/emojis/emoji_9.webp"))
        assertEquals(ImageTypes.UNKNOWN_MIME, ImageTypes.mimeOf("/data/emojis/emoji_9"))
    }

    @Test
    fun fileTypeOfTreatsGifAsAnimated() {
        assertEquals(ImageTypes.FILE_TYPE_ANIMATED, ImageTypes.fileTypeOf("/data/emojis/emoji_1.gif"))
        assertEquals(ImageTypes.FILE_TYPE_IMAGE, ImageTypes.fileTypeOf("/data/emojis/emoji_1.png"))
        // 文件不在了也不该崩，按静图算。
        assertEquals(ImageTypes.FILE_TYPE_IMAGE, ImageTypes.fileTypeOf("/definitely/not/here.webp"))
    }

    @Test
    fun fileTypeOfReadsTheAnimationFlagOutOfTheWebpHeader() {
        val animated = tempWebp(animated = true)
        val still = tempWebp(animated = false)
        try {
            assertEquals(ImageTypes.FILE_TYPE_ANIMATED, ImageTypes.fileTypeOf(animated.absolutePath))
            assertEquals(ImageTypes.FILE_TYPE_IMAGE, ImageTypes.fileTypeOf(still.absolutePath))
        } finally {
            animated.delete()
            still.delete()
        }
    }

    @Test
    fun isAnimatedWebpRejectsShortOrForeignFiles() {
        val tooShort = File.createTempFile("image-types", "-short")
        tooShort.writeBytes("RIFF".toByteArray(Charsets.US_ASCII))
        val wrongMagic = File.createTempFile("image-types", "-magic")
        wrongMagic.writeBytes(header(chunk = "WEBP", animated = true))
        val noVp8x = File.createTempFile("image-types", "-vp8")
        noVp8x.writeBytes(header(chunk = "VP8 ", animated = true))
        try {
            assertFalse(ImageTypes.isAnimatedWebp(tooShort))
            assertFalse(ImageTypes.isAnimatedWebp(wrongMagic))
            assertFalse(ImageTypes.isAnimatedWebp(noVp8x))
            assertFalse(ImageTypes.isAnimatedWebp(File("/definitely/not/here.webp")))
        } finally {
            tooShort.delete()
            wrongMagic.delete()
            noVp8x.delete()
        }
    }

    @Test
    fun isAnimatedWebpReadsFlagsBitOne() {
        val still = tempWebp(animated = false)
        val animated = tempWebp(animated = true)
        try {
            assertFalse(ImageTypes.isAnimatedWebp(still))
            assertTrue(ImageTypes.isAnimatedWebp(animated))
        } finally {
            still.delete()
            animated.delete()
        }
    }

    /** RIFF + WEBP + 指定块 + 第 21 字节的 flags（bit1 = 有动画）。 */
    private fun header(chunk: String, animated: Boolean): ByteArray {
        val head = ByteArray(21)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(head, 8)
        chunk.toByteArray(Charsets.US_ASCII).copyInto(head, 12)
        head[20] = if (animated) 0x02 else 0x00
        return head
    }

    private fun tempWebp(animated: Boolean): File {
        val file = File.createTempFile("image-types", ".webp")
        file.writeBytes(header(chunk = "VP8X", animated = animated))
        return file
    }
}
