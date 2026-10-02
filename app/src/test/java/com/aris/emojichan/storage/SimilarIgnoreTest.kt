package com.aris.emojichan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 忽略名单怎么记一对（v0.2.006，用户 m10497）。
 *
 * 要守的是三条：一对只有一个键（正问反问都能查到）；「忽略这组」记的是组里每一对；表情删掉之后
 * 那一对自动作废 —— 名字里那些键不该越攒越多。
 */
class SimilarIgnoreTest {

    @Test
    fun aPairHasOneKeyWhicheverWayYouAsk() {
        assertEquals(SimilarIgnore.key(3, 7), SimilarIgnore.key(7, 3))
        assertEquals("3:7", SimilarIgnore.key(7, 3))
    }

    @Test
    fun containsCoversBothDirections() {
        val keys = setOf(SimilarIgnore.key(9, 4))

        assertTrue(SimilarIgnore.contains(keys, 4, 9))
        assertTrue(SimilarIgnore.contains(keys, 9, 4))
        assertFalse(SimilarIgnore.contains(keys, 4, 10))
    }

    /** 4 张是 6 对：忽略一组就是把这一组里所有两两组合都记下。 */
    @Test
    fun keysInCoversEveryPairInTheGroup() {
        val keys = SimilarIgnore.keysIn(listOf(5, 6, 7, 8))

        assertEquals(6, keys.size)
        assertTrue(keys.contains("5:8"))
        assertTrue(keys.contains("6:7"))
    }

    @Test
    fun keysInOfOneOrNoneIsEmpty() {
        assertEquals(emptySet<String>(), SimilarIgnore.keysIn(listOf(1)))
        assertEquals(emptySet<String>(), SimilarIgnore.keysIn(emptyList()))
    }

    @Test
    fun idsOfReadsBackAPairAndRejectsJunk() {
        assertEquals(3L to 7L, SimilarIgnore.idsOf("3:7"))
        assertNull("只有一个数不成对", SimilarIgnore.idsOf("3"))
        assertNull("三个数不成对", SimilarIgnore.idsOf("3:7:9"))
        assertNull("不是数字就解不出来", SimilarIgnore.idsOf("猫:7"))
    }

    @Test
    fun pairsDieWhenEitherSideLeavesTheLibrary() {
        val keys = setOf(SimilarIgnore.key(1, 2), SimilarIgnore.key(1, 3), SimilarIgnore.key(2, 3))

        // 3 走了：带它的两对一起作废，只剩 1 和 2 这一对
        assertEquals(setOf("1:2"), SimilarIgnore.keepAlive(keys, setOf(1, 2)))
    }

    @Test
    fun keepAliveAlsoDropsUnreadableKeys() {
        assertEquals(emptySet<String>(), SimilarIgnore.keepAlive(setOf("坏键"), setOf(1, 2)))
    }
}
