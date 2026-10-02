package com.aris.emojichan.storage

import com.aris.emojichan.data.EmojiEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「完全相同」的分组规则。
 *
 * 这一层不碰 Android、也不真读文件：摘要由测试给，所以能断言「该读的才读」——
 * 体积独一无二的图不该被打开，这是这个功能能对上几千张库的前提。
 */
class DuplicateFinderTest {

    private fun item(
        id: Long,
        bytes: Long = 1000L,
        createTime: Long = id,
        path: String = "/data/emojis/$id.webp"
    ) = DuplicateFinder.Item(
        id = id,
        name = "表情$id",
        path = path,
        bytes = bytes,
        modifiedAt = 1L,
        createTime = createTime
    )

    @Test
    fun sizesThatAppearOnceAreNeverRead() = runTest {
        val read = mutableListOf<Long>()

        val groups = DuplicateFinder.find(
            items = listOf(item(1, bytes = 10), item(2, bytes = 20), item(3, bytes = 30)),
            hashOf = { read += it.id; "same" }
        )

        assertTrue(groups.isEmpty())
        assertTrue("体积独一无二就不该读盘，实际读了：$read", read.isEmpty())
    }

    @Test
    fun onlyIdenticalHashesAreGrouped() = runTest {
        val hashes = mapOf(1L to "aaa", 2L to "aaa", 3L to "bbb")

        val groups = DuplicateFinder.find(
            items = listOf(item(1), item(2), item(3)),
            hashOf = { hashes[it.id] }
        )

        assertEquals(1, groups.size)
        assertEquals("aaa", groups.single().sha256)
        assertEquals(listOf(1L, 2L), groups.single().members.map { it.id })
        assertEquals(1000L, groups.single().bytesEach)
    }

    /** 组里第一位是默认保留的那张，必须是导入最早的那张，跟读盘顺序无关。 */
    @Test
    fun membersComeBackOldestFirst() = runTest {
        val groups = DuplicateFinder.find(
            items = listOf(
                item(1, createTime = 300),
                item(2, createTime = 100),
                item(3, createTime = 200)
            ),
            hashOf = { "same" }
        )

        assertEquals(listOf(2L, 3L, 1L), groups.single().members.map { it.id })
        assertEquals(100L, groups.single().members.first().createTime)
        assertEquals(2000L, groups.single().wastedBytes)
    }

    /** 读不出来（文件被删、没权限）的一张跳过就行，不能把整轮比对带停。 */
    @Test
    fun unreadableFilesAreSkippedWithoutStopping() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()

        val groups = DuplicateFinder.find(
            items = listOf(item(1), item(2), item(3), item(4)),
            hashOf = { item ->
                when (item.id) {
                    1L -> null
                    2L -> throw IllegalStateException("读坏了")
                    else -> "same"
                }
            },
            onProgress = { done, total -> progress += done to total }
        )

        assertEquals(listOf(3L, 4L), groups.single().members.map { it.id })
        // 每张报一次，末尾再报一次「完了」，总数是候选张数
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4, 4 to 4), progress)
    }

    /** 用户点「停止」时抛的是 CancellationException，它必须照原样出去，不能当成「读不出来」吞掉。 */
    @Test
    fun cancellationIsNotSwallowed() = runTest {
        var propagated = false
        try {
            DuplicateFinder.find(
                items = listOf(item(1), item(2)),
                hashOf = { throw CancellationException("停") }
            )
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("取消没有被抛出去，比对会停不下来", propagated)
    }

    /** 排序按「能省多少」：两张 5000 的比三张 1000 的更值得先给用户看。 */
    @Test
    fun groupsAreOrderedByHowMuchTheySave() = runTest {
        val hashes = mapOf(
            1L to "small", 2L to "small", 3L to "small",
            4L to "big", 5L to "big"
        )

        val groups = DuplicateFinder.find(
            items = listOf(
                item(1, bytes = 1000), item(2, bytes = 1000), item(3, bytes = 1000),
                item(4, bytes = 5000), item(5, bytes = 5000)
            ),
            hashOf = { hashes[it.id] }
        )

        assertEquals(listOf("big", "small"), groups.map { it.sha256 })
        assertEquals(5000L, groups.first().wastedBytes)
        assertEquals(2000L, groups.last().wastedBytes)
    }

    @Test
    fun redundantIsEverythingButTheKeptOne() = runTest {
        val group = DuplicateFinder.Group(
            sha256 = "x",
            members = listOf(item(1), item(2), item(3))
        )

        assertEquals(listOf(2L, 3L), DuplicateFinder.redundant(group, keepId = 1L).map { it.id })
        assertEquals(listOf(1L, 3L), DuplicateFinder.redundant(group, keepId = 2L).map { it.id })
        assertEquals(3, group.members.size)
    }

    @Test
    fun candidatesKeepOnlySharedSizes() {
        val result = DuplicateFinder.candidates(
            listOf(
                item(1, bytes = 100, createTime = 30),
                item(2, bytes = 100, createTime = 10),
                item(3, bytes = 200)
            )
        )

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    /** bytes = 0 表示文件已经不在了（itemOf 读不到就是 0），这种连比都不比。 */
    @Test
    fun missingFilesAreNotCandidates() {
        assertTrue(DuplicateFinder.candidates(listOf(item(1, bytes = 0), item(2, bytes = 0))).isEmpty())
        assertTrue(DuplicateFinder.candidates(listOf(item(1, bytes = 0), item(2, bytes = 100))).isEmpty())
    }

    @Test
    fun itemOfReadsWhatTheFileSays() {
        val file = File.createTempFile("duplicate", ".webp").apply {
            writeBytes(ByteArray(1234) { 7 })
            deleteOnExit()
        }

        val fromDisk = DuplicateFinder.itemOf(
            EmojiEntity(
                id = 7,
                name = "猫",
                filePath = file.absolutePath,
                fileType = "image",
                createTime = 5L
            )
        )
        assertEquals(1234L, fromDisk.bytes)
        assertEquals(file.lastModified(), fromDisk.modifiedAt)
        assertTrue(fromDisk.present)

        val gone = DuplicateFinder.itemOf(
            EmojiEntity(id = 8, name = "狗", filePath = file.absolutePath + ".gone", fileType = "image")
        )
        assertEquals(0L, gone.bytes)
        assertFalse(gone.present)
    }
}
