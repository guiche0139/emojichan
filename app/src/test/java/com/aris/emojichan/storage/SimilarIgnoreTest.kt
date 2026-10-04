package com.aris.emojichan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 忽略名单怎么记（v0.2.006 记成对；v0.2.009 起记「一次忽略」，用户 m11102）。
 *
 * 要守的是四条：一条记录升序去重、一张不成记录；一次忽略盖住的是组内两两（判定跟从前逐对记完全
 * 等价）；旧版存下来的对本来就是合法的两成员记录（所以不用写迁移）；表情删掉之后记录自动收窄。
 */
class SimilarIgnoreTest {

    @Test
    fun aRecordIsSortedDedupedAndNeedsTwoMembers() {
        assertEquals("3:7:12", SimilarIgnore.recordOf(listOf(12, 3, 7, 3)))
        assertNull("一张不成记录", SimilarIgnore.recordOf(listOf(5)))
        assertNull(SimilarIgnore.recordOf(emptyList()))
    }

    @Test
    fun idsOfReadsBackARecordAndRejectsJunk() {
        assertEquals(listOf(3L, 7L, 9L), SimilarIgnore.idsOf("3:7:9"))
        assertNull("只有一个数不成记录", SimilarIgnore.idsOf("3"))
        assertNull("不是数字就解不出来", SimilarIgnore.idsOf("猫:7"))
        assertNull(SimilarIgnore.idsOf(""))
    }

    /** 旧版（v0.2.006）存下来的对就是这么长的一条记录：这一条保证不用写迁移代码。 */
    @Test
    fun aStoredPairIsAlreadyAValidRecord() {
        assertEquals(listOf(3L, 7L), SimilarIgnore.idsOf("3:7"))
        assertEquals(setOf(7L), SimilarIgnore.bannedOf(setOf("3:7"))[3L])
        assertEquals(setOf("3:7"), SimilarIgnore.keepAlive(setOf("3:7"), setOf(3, 7)))
    }

    @Test
    fun pairsInCountsEveryCombination() {
        assertEquals(1, SimilarIgnore.pairsIn("3:7"))
        assertEquals(6, SimilarIgnore.pairsIn("5:6:7:8"))
        assertEquals(0, SimilarIgnore.pairsIn("坏键"))
        assertEquals(7, SimilarIgnore.pairsOf(setOf("5:6:7:8", "3:7")))
    }

    @Test
    fun containsNeedsBothInTheSameRecord() {
        val records = setOf("1:2:3", "8:9")

        assertTrue(SimilarIgnore.contains(records, 1, 3))
        assertTrue(SimilarIgnore.contains(records, 3, 1))
        assertFalse("分属两条记录不算一对", SimilarIgnore.contains(records, 1, 8))
        assertFalse(SimilarIgnore.contains(records, 1, 99))
    }

    /** 查表版（v0.2.007）：一次忽略 4 张，这 4 张彼此都成了对方的禁忌。 */
    @Test
    fun bannedOfSpreadsEveryRecordPairwise() {
        val banned = SimilarIgnore.bannedOf(setOf("5:6:7:8"))

        assertEquals(setOf(6L, 7L, 8L), banned[5L])
        assertEquals(setOf(5L, 6L, 7L), banned[8L])
        assertNull("不在名单里的图没有禁忌", banned[10L])
    }

    @Test
    fun bannedOfSkipsUnreadableRecordsAndEmptyInput() {
        assertTrue(SimilarIgnore.bannedOf(emptySet()).isEmpty())
        assertTrue("解不出来的记录整条作废", SimilarIgnore.bannedOf(setOf("坏键")).isEmpty())
        assertTrue("只有一张的记录不成记录", SimilarIgnore.bannedOf(setOf("4")).isEmpty())
    }

    @Test
    fun recordsNarrowWhenAMemberLeavesTheLibrary() {
        val records = setOf("1:2:3", "4:5")

        // 3 走了：那条记录只剩 1 和 2，还是成立的
        assertEquals(setOf("1:2", "4:5"), SimilarIgnore.keepAlive(records, setOf(1, 2, 4, 5)))
        // 4 也走了：那边只剩 5 一张，整条作废
        assertEquals(setOf("1:2"), SimilarIgnore.keepAlive(records, setOf(1, 2, 5)))
    }

    @Test
    fun keepAliveAlsoDropsUnreadableRecords() {
        assertEquals(emptySet<String>(), SimilarIgnore.keepAlive(setOf("坏键"), setOf(1, 2)))
        assertEquals(emptySet<String>(), SimilarIgnore.keepAlive(setOf("4"), setOf(4, 5)))
    }

    /** 随包恢复（v0.2.012，用户 m11327 第 3 条）：包里的 id 换成库里新的 id，一条都不能少。 */
    @Test
    fun restoreMapsEveryMemberOfARecord() {
        val restored = SimilarIgnore.restore(
            setOf("3:7", "11:12:13"),
            mapOf(3L to 30L, 7L to 70L, 11L to 1L, 12L to 2L, 13L to 3L)
        )

        assertEquals(setOf("1:2:3", "30:70"), restored.records)
        assertEquals(0, restored.dropped)
    }

    /** 有一张没进包：整条丢，不把剩下的那张留下 —— 用户从没单独忽略过它。 */
    @Test
    fun restoreDropsAWholeRecordWhenAMemberDidNotComeOver() {
        val restored = SimilarIgnore.restore(setOf("3:7"), mapOf(3L to 30L))

        assertEquals(emptySet<String>(), restored.records)
        assertEquals(1, restored.dropped)
    }

    @Test
    fun restoreCountsUnreadableRecordsAsDropped() {
        val restored = SimilarIgnore.restore(setOf("坏键", "4", "5:6"), mapOf(5L to 50L, 6L to 60L))

        assertEquals(setOf("50:60"), restored.records)
        assertEquals("解不开的、只有一张的，各算丢一条", 2, restored.dropped)
    }

    /** 映射值 <= 0 不是本机的 id（rowid 从 1 起）：这一条同样留不住。 */
    @Test
    fun restoreRejectsBlankTargets() {
        val restored = SimilarIgnore.restore(setOf("3:7"), mapOf(3L to 0L, 7L to 70L))

        assertEquals(emptySet<String>(), restored.records)
        assertEquals(1, restored.dropped)
    }

    @Test
    fun restoreOfNothingIsNothing() {
        val restored = SimilarIgnore.restore(emptyList(), mapOf(1L to 2L))

        assertTrue(restored.records.isEmpty())
        assertEquals(0, restored.dropped)
    }
}
