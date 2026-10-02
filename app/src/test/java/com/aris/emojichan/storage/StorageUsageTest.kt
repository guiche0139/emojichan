package com.aris.emojichan.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 存储统计里跟 Android 框架无关的那几个函数。
 *
 * 真正的统计（数据库、缓存）要跑在设备上才算得准，这里只管「格式怎么认」和「目录怎么量」——
 * 这两处出错的后果是饼图上多一档「其它」、总体积少算，都是不容易察觉的那种错。
 */
class StorageUsageTest {

    @Test
    fun formatOfReadsExtensionCaseInsensitively() {
        assertEquals("JPG", StorageUsage.formatOf("/data/emojis/emoji_1_a.JPEG"))
        assertEquals("JPG", StorageUsage.formatOf("/data/emojis/emoji_2_b.jpg"))
        assertEquals("PNG", StorageUsage.formatOf("/data/emojis/emoji_3_c.Png"))
        assertEquals("WEBP", StorageUsage.formatOf("/data/emojis/emoji_4_d.webp"))
        assertEquals("GIF", StorageUsage.formatOf("/data/emojis/emoji_5_e.gif"))
        assertEquals("BMP", StorageUsage.formatOf("/data/emojis/emoji_6_f.bmp"))
    }

    @Test
    fun formatOfFallsBackForUnknownNames() {
        assertEquals("其它", StorageUsage.formatOf("/data/emojis/no_extension"))
        assertEquals("其它", StorageUsage.formatOf("/data/emojis/trailing_dot."))
        assertEquals("其它", StorageUsage.formatOf("/data/emojis/.hidden"))
        assertEquals("其它", StorageUsage.formatOf("/data/emojis/emoji_7_g.psd"))
    }

    @Test
    fun percentOfKeepsEmptyLibraryAtZero() {
        assertEquals(0, StorageUsage.percentOf(0, 0))
        assertEquals(0, StorageUsage.percentOf(100, 0))
        assertEquals(25, StorageUsage.percentOf(1, 4))
        assertEquals(33, StorageUsage.percentOf(1, 3))
        assertEquals(100, StorageUsage.percentOf(7, 7))
    }

    @Test
    fun dirSizeCountsFilesInSubdirectories() {
        val root = File.createTempFile("storage-usage", "-test")
        root.delete()
        root.mkdirs()
        try {
            File(root, "a.bin").writeBytes(ByteArray(1200))
            File(root, "nested").mkdirs()
            File(root, "nested/b.bin").writeBytes(ByteArray(300))
            assertEquals(1500L, StorageUsage.dirSize(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dirSizeIgnoresMissingDirectory() {
        assertEquals(0L, StorageUsage.dirSize(File("/definitely/not/here")))
        assertEquals(0L, StorageUsage.dirSize(null))
    }
}
