package com.aris.emojichan.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aris.emojichan.data.EmojiEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份包里的忽略名单（v0.2.012，用户 m11327 第 3 条）。
 *
 * 要守的是四条：导出时把库里的 id 和忽略名单一起写进清单；成员没进包的记录一条都不写；
 * 导入时包里的 id 原样读出来（老包一律 0）；MERGE 里被「库里已有」顶掉的那张，包里 id 要换成
 * 库里那张的 id —— 忽略名单靠它落到已有记录上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmojiArchiveTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 造一张真在磁盘上的「表情」：导出只按字节抄，内容是什么都行。 */
    private fun emojiOnDisk(dir: File, id: Long, name: String, body: String): EmojiEntity {
        val file = File(dir, "$name.png")
        file.writeBytes(body.toByteArray())
        return EmojiEntity(
            id = id,
            name = name,
            filePath = file.absolutePath,
            fileType = "png",
            fileSize = file.length(),
            width = 10,
            height = 10
        )
    }

    private fun sourceDir(): File = File(context.cacheDir, "archive-src").apply { mkdirs() }

    private fun exportPack(emojis: List<EmojiEntity>, ignored: List<String>): ByteArray {
        val pack = ByteArrayOutputStream()
        EmojiArchive.export(context, pack, emojis, emptyList(), emptyList(), ignored, "0.2.012") { _, _ -> }
        return pack.toByteArray()
    }

    private fun import(bytes: ByteArray, mode: EmojiArchive.Mode = EmojiArchive.Mode.MERGE, existing: List<EmojiEntity> = emptyList()): EmojiArchive.Imported =
        EmojiArchive.import(context, { bytes.inputStream() }, mode, existing) { _, _ -> }

    @Test
    fun aRoundTripCarriesTheIdsAndTheIgnoreList() {
        val dir = sourceDir()
        val one = emojiOnDisk(dir, 11L, "猫", "AAA")
        val two = emojiOnDisk(dir, 12L, "狗", "BBBB")

        val pack = ByteArrayOutputStream()
        val exported = EmojiArchive.export(
            context, pack, listOf(one, two), emptyList(), emptyList(), listOf("11:12"), "0.2.012"
        ) { _, _ -> }
        assertEquals(2, exported.emojis)
        assertEquals("忽略名单随包带走一条", 1, exported.ignored)

        val imported = import(pack.toByteArray())
        assertNull(imported.report.error)
        assertEquals(2, imported.emojis.size)
        assertEquals("清单里的 id 原样读回来", listOf(11L, 12L), imported.oldIds)
        assertEquals(listOf("11:12"), imported.ignored)
    }

    @Test
    fun exportOnlyCarriesRecordsWhoseMembersAreAllInThePack() {
        val one = emojiOnDisk(sourceDir(), 11L, "猫", "AAA")

        val pack = exportPack(listOf(one), listOf("11:99", "坏键"))

        assertEquals("99 没进包、坏键解不开：一条都不写", 0, EmojiArchive.import(context, { pack.inputStream() }, EmojiArchive.Mode.MERGE, emptyList()) { _, _ -> }.ignored.size)
    }

    @Test
    fun mergeMapsASkippedDuplicateOntoTheLibraryRow() {
        val dir = sourceDir()
        val one = emojiOnDisk(dir, 11L, "猫", "AAA")
        val two = emojiOnDisk(dir, 12L, "狗", "BBBB")
        val pack = exportPack(listOf(one, two), listOf("11:12"))

        // 库里已经有一模一样的猫（同名同体积，id = 7）：包里那张不入库；狗是新的一张
        val existing = listOf(
            EmojiEntity(id = 7L, name = one.name, filePath = "/nowhere.png", fileType = "png", fileSize = one.fileSize)
        )
        val imported = import(pack, existing = existing)

        assertEquals(1, imported.report.skipped)
        assertEquals(1, imported.emojis.size)
        assertEquals("只有狗真的走了入库那条路", listOf(12L), imported.oldIds)
        assertEquals("被顶掉的猫：包里的 11 指向库里那张 7", mapOf(11L to 7L), imported.takenOldIds)
        assertEquals("记录原样带出来（怎么映射是调用方的事）", listOf("11:12"), imported.ignored)
    }

    /** 老包（v0.2.011 及以前）没有 id/ignore 这两个键：读出来是 0 和空表，不能报错。 */
    @Test
    fun anOldPackWithoutIdsStillImports() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("emojichan-archive.json"))
            zip.write(
                (
                    "{\"format\":\"emojichan-archive\",\"version\":1,\"app\":\"0.1.0\"," +
                        "\"emojis\":[{\"name\":\"旧猫\",\"file\":\"images/1-旧猫.png\",\"fileType\":\"png\"," +
                        "\"size\":3,\"width\":10,\"height\":10}]}"
                    ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("images/1-旧猫.png"))
            zip.write("OLD".toByteArray())
            zip.closeEntry()
        }

        val imported = import(out.toByteArray())

        assertNull(imported.report.error)
        assertEquals(1, imported.emojis.size)
        assertEquals("老包没有 id：一律 0", listOf(0L), imported.oldIds)
        assertTrue("老包没有忽略名单", imported.ignored.isEmpty())
    }
}
