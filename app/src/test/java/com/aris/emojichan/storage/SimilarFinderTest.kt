package com.aris.emojichan.storage

import com.aris.emojichan.util.PerceptualHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「相似」的分组规则。
 *
 * 这里不读盘：哈希由测试给。要守的是三条 —— 底线内跟「最近的组代表」走；相似不传递
 * （A 像 B、B 像 C 不代表 A 像 C），第二条决定了它不会把整库串成一组；结果按差距由近到远排
 * （v0.1.412，用户 m08998：页面上不再切档，靠顺序代替），页面上把差距显示成相似度百分比
 * （v0.1.413，用户 m09083：相似度 = 相同位数 / 总位数）。
 */
class SimilarFinderTest {

    private fun item(id: Long, bytes: Long = 1000L, createTime: Long = id) = DuplicateFinder.Item(
        id = id,
        name = "表情$id",
        path = "/data/emojis/$id.webp",
        bytes = bytes,
        modifiedAt = 1L,
        createTime = createTime
    )

    /** 只亮低位的哈希：两个这样的哈希差几位，就是两个 n 的差。 */
    private fun ones(count: Int): Long = if (count >= 64) -1L else (1L shl count) - 1L

    @Test
    fun itemsWithinThresholdShareAGroup() = runTest {
        val groups = SimilarFinder.find(
            items = listOf(item(1), item(2), item(3)),
            threshold = SimilarFinder.DEFAULT,
            hashOf = { if (it.id == 3L) ones(40) else ones(2) }
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups.single().members.map { it.id })
        assertEquals(listOf(0, 0), groups.single().distances)
    }

    @Test
    fun everyItemJoinsItsNearestRepresentative() = runTest {
        // 0 与 13 个 1 差 13 位，超过最松的一档，所以各自当代表
        val hashes = mapOf(
            1L to ones(0),
            2L to ones(13),
            3L to ones(4),  // 离代表 1 差 4 位，离代表 2 差 9 位
            4L to ones(10)  // 离代表 1 差 10 位，离代表 2 差 3 位
        )

        // 顺序是另一条契约（见下面两条用例），这里按代表认组
        val groups = SimilarFinder.group(
            listOf(item(1), item(2), item(3), item(4)),
            hashes,
            SimilarFinder.LOOSE
        ).sortedBy { it.representative.id }

        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 3L), groups[0].members.map { it.id })
        assertEquals(listOf(0, 4), groups[0].distances)
        assertEquals(listOf(2L, 4L), groups[1].members.map { it.id })
        assertEquals(listOf(0, 3), groups[1].distances)
    }

    @Test
    fun similarityDoesNotSnowball() = runTest {
        // A≈B（差 6）、B≈C（差 6），但 A≉C（差 12）：跟代表比，C 不会顺着传递混进来
        val hashes = mapOf(1L to ones(0), 2L to ones(6), 3L to ones(12))

        val groups = SimilarFinder.group(
            listOf(item(1), item(2), item(3)),
            hashes,
            SimilarFinder.DEFAULT
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups.single().members.map { it.id })
        assertTrue(
            "差 12 位的那张不该靠传递混进小组",
            groups.none { group -> group.members.any { it.id == 3L } }
        )
    }

    @Test
    fun thresholdDecidesWhetherTheyShareAGroup() {
        val hashes = mapOf(1L to ones(0), 2L to ones(6))
        val items = listOf(item(1), item(2))

        assertEquals(1, SimilarFinder.group(items, hashes, SimilarFinder.STANDARD).size)
        assertTrue("差 6 位在阈值 5 之外，不该成组", SimilarFinder.group(items, hashes, 5).isEmpty())
    }

    @Test
    fun itemsWithoutHashAreLeftOut() {
        val groups = SimilarFinder.group(
            listOf(item(1), item(2)),
            mapOf(1L to ones(0)),
            SimilarFinder.DEFAULT
        )

        assertTrue(groups.isEmpty())
    }

    /** 一组一张不算「相似」：单独一张的组在结果里必须消失。 */
    @Test
    fun groupsOfOneAreDropped() {
        val hashes = mapOf(1L to ones(0), 2L to ones(40))

        assertTrue(SimilarFinder.group(listOf(item(1), item(2)), hashes, SimilarFinder.DEFAULT).isEmpty())
    }

    @Test
    fun unreadableImagesAreSkippedAndProgressStillReachesTheEnd() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()

        val groups = SimilarFinder.find(
            items = listOf(item(1), item(2), item(3), item(4)),
            threshold = SimilarFinder.DEFAULT,
            hashOf = { item ->
                when (item.id) {
                    1L -> null
                    2L -> throw IllegalStateException("读坏了")
                    else -> ones(0)
                }
            },
            onProgress = { done, total -> progress += done to total }
        )

        assertEquals(listOf(3L, 4L), groups.single().members.map { it.id })
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4, 4 to 4), progress)
    }

    @Test
    fun missingFilesAreNeverHashed() = runTest {
        val asked = mutableListOf<Long>()

        SimilarFinder.find(
            items = listOf(item(1, bytes = 0), item(2, bytes = 0), item(3)),
            threshold = SimilarFinder.DEFAULT,
            hashOf = { asked += it.id; ones(0) }
        )

        assertEquals(listOf(3L), asked)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        var propagated = false
        try {
            SimilarFinder.find(
                items = listOf(item(1), item(2)),
                threshold = SimilarFinder.DEFAULT,
                hashOf = { throw CancellationException("停") }
            )
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("取消没有被抛出去，比对会停不下来", propagated)
    }

    @Test
    fun groupDescribesHowMuchItSaves() {
        val group = SimilarFinder.Group(
            members = listOf(item(1, bytes = 1000), item(2, bytes = 5000), item(3, bytes = 3000)),
            distances = listOf(0, 9, 3)
        )

        assertEquals(1L, group.representative.id)
        assertEquals(9, group.maxDistance)
        // 只留最大的一张能省下的是另外两张
        assertEquals(4000L, group.spareBytes)
    }

    @Test
    fun withoutRecomputesDistancesFromTheNewRepresentative() {
        val hashes = mapOf(1L to ones(0), 2L to ones(9), 3L to ones(3))
        val group = SimilarFinder.Group(
            members = listOf(item(1), item(2), item(3)),
            distances = listOf(0, 9, 3)
        )

        val after = group.without(setOf(1L), hashes)

        assertEquals(listOf(2L, 3L), after.members.map { it.id })
        // 代表换成 2 了，3 跟它的距离得重算（9 - 3 = 6），不能留着旧的 3
        assertEquals(listOf(0, 6), after.distances)
    }

    @Test
    fun distancesFallBackToZeroWhenHashesAreGone() {
        assertEquals(listOf(0, 0), SimilarFinder.distances(listOf(item(1), item(2)), emptyMap()))
    }

    /** 越接近的组越靠前（用户 m08998：页面上不切档了，靠顺序代替）。 */
    @Test
    fun groupsAreOrderedBySimilarity() {
        val hashes = mapOf(
            1L to ones(0), 2L to ones(1), 3L to ones(2),  // 3 张，最多差 2 位
            4L to ones(20), 5L to ones(23),               // 2 张，最多差 3 位
            6L to ones(40), 7L to ones(45)                // 2 张，最多差 5 位
        )

        val groups = SimilarFinder.group(
            listOf(
                item(1), item(2), item(3),
                item(4), item(5),
                item(6), item(7)
            ),
            hashes,
            SimilarFinder.DEFAULT
        )

        assertEquals(listOf(2, 3, 5), groups.map { it.maxDistance })
        assertEquals(listOf(1L, 4L, 6L), groups.map { it.representative.id })
    }

    /** 差距一样的两组，张数多的在前 —— 能一次看完更多张的那组更值得先看。 */
    @Test
    fun groupsWithTheSameDistanceAreOrderedBySize() {
        val hashes = mapOf(
            1L to ones(0), 2L to ones(3), 3L to ones(5),  // 3 张，最多差 5 位
            4L to ones(40), 5L to ones(45)                // 2 张，最多差 5 位
        )

        val groups = SimilarFinder.group(
            listOf(item(1), item(2), item(3), item(4), item(5)),
            hashes,
            SimilarFinder.DEFAULT
        )

        assertEquals(listOf(5, 5), groups.map { it.maxDistance })
        assertEquals(listOf(3, 2), groups.map { it.members.size })
        assertEquals(1L, groups.first().representative.id)
    }

    /** 用户点过「忽略这组」的那两张，之后不该再同组（v0.2.006，用户 m10497）。 */
    @Test
    fun ignoredPairIsNotGroupedTogether() = runTest {
        val keys = setOf(SimilarIgnore.key(1, 2))

        val groups = SimilarFinder.find(
            items = listOf(item(1), item(2)),
            threshold = SimilarFinder.DEFAULT,
            hashOf = { if (it.id == 1L) ones(0) else ones(3) },
            ignored = { a, b -> SimilarIgnore.contains(keys, a, b) }
        )

        assertTrue("忽略过的两张不该还在一起", groups.isEmpty())
    }

    /**
     * 被忽略的那一对不能从第三张那里绕回来。
     *
     * A≈B（差 3）、B≈C（差 3）、A≈C（差 6）：只比代表的话，C 会顺着已进组的 B 混进来，
     * 用户看到的还是原来那一组 —— 所以判定要对准组里每一张。
     */
    @Test
    fun ignoredPairDoesNotComeBackThroughAThirdOne() {
        val items = listOf(item(1), item(2), item(3))
        val hashes = mapOf(1L to ones(0), 2L to ones(3), 3L to ones(6))
        val keys = setOf(SimilarIgnore.key(1, 3))

        assertEquals(
            listOf(1L, 2L, 3L),
            SimilarFinder.group(items, hashes, SimilarFinder.DEFAULT).single().members.map { it.id }
        )

        val after = SimilarFinder.group(items, hashes, SimilarFinder.DEFAULT) { a, b ->
            SimilarIgnore.contains(keys, a, b)
        }

        assertEquals(listOf(1L, 2L), after.single().members.map { it.id })
    }

    /** 忽略的是「这一对」，不是「这两张」：其中一张照样能跟别人成组，别的组一点不受影响。 */
    @Test
    fun ignoringAPairKeepsBothImagesGroupableElsewhere() {
        val items = listOf(item(1), item(2), item(3), item(4), item(5))
        val hashes = mapOf(
            1L to ones(0), 2L to ones(2), 3L to ones(4),  // 本来是一组
            4L to ones(40), 5L to ones(43)                // 另一组，差 3 位
        )
        val keys = setOf(SimilarIgnore.key(2, 3))

        val groups = SimilarFinder.group(items, hashes, SimilarFinder.DEFAULT) { a, b ->
            SimilarIgnore.contains(keys, a, b)
        }.sortedBy { it.representative.id }

        // 3 被挡在 1、2 那组之外，自己一张凑不成组；2 并没有被踢出去
        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 2L), groups[0].members.map { it.id })
        assertEquals(listOf(4L, 5L), groups[1].members.map { it.id })
    }

    /** 把「差几位」换算成相似度百分比：相同位数 / 总位数，四舍五入到整数。 */
    @Test
    fun distanceIsReportedAsSimilarityPercent() {
        assertEquals(100, SimilarFinder.similarity(0))
        assertEquals(94, SimilarFinder.similarity(SimilarFinder.STRICT))
        assertEquals(88, SimilarFinder.similarity(SimilarFinder.STANDARD))
        assertEquals(81, SimilarFinder.similarity(SimilarFinder.LOOSE))
        assertEquals(0, SimilarFinder.similarity(PerceptualHash.BITS))
    }
}
