package com.aris.emojichan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「多久没发过」的起算点（v0.2.013，用户 m11937 修正）。
 *
 * 要守的是：发过的按最后一次发送算；从未发过的按入库时间算 —— 刚导入的表情不进 90 / 180 / 365
 * 那三档，等它躺够天数才出现；「从未发过」那一档与入库时间无关。
 */
class StaleRuleTest {

    private val now = 1_700_000_000_000L

    private fun daysAgo(days: Long): Long = now - days * StaleRule.DAY_MS

    @Test
    fun aFreshlyImportedEmojiStaysOutOfEveryThresholdWindow() {
        // 今天刚导进来、一次没发过：三档都不该有它
        assertFalse(StaleRule.matches(0L, now, 90, now))
        assertFalse(StaleRule.matches(0L, now, 180, now))
        assertFalse(StaleRule.matches(0L, now, 365, now))
        // 但「从未发过」那一档照样有它
        assertTrue(StaleRule.matches(0L, now, 0, now))
    }

    @Test
    fun aNeverUsedEmojiEntersTheWindowOnceItHasSatLongEnough() {
        assertTrue(StaleRule.matches(0L, daysAgo(100), 90, now))
        assertFalse("还没到 180 天", StaleRule.matches(0L, daysAgo(100), 180, now))
        assertTrue(StaleRule.matches(0L, daysAgo(400), 365, now))
    }

    /** 阈值是「过了才算」：刚好 90 天不进，多一天才进。 */
    @Test
    fun theThresholdIsStrict() {
        assertFalse(StaleRule.matches(0L, now - 90 * StaleRule.DAY_MS, 90, now))
        assertTrue(StaleRule.matches(0L, now - 90 * StaleRule.DAY_MS - 1L, 90, now))
    }

    @Test
    fun aUsedEmojiCountsFromItsLastUseNotFromWhenItWasAdded() {
        // 老图今天才发过一次：不该算「长时间不用」，入库早也没用
        assertFalse(StaleRule.matches(daysAgo(0), daysAgo(400), 90, now))
        // 从备份包导回来的表情带着原库的最后使用时间：今天才进库也算「很久没发过」
        assertTrue(StaleRule.matches(daysAgo(200), daysAgo(1), 90, now))
    }

    @Test
    fun sincePicksTheLastUseWhenThereIsOneAndTheAddedTimeOtherwise() {
        assertEquals(daysAgo(30), StaleRule.since(daysAgo(30), daysAgo(300)))
        assertEquals(daysAgo(300), StaleRule.since(0L, daysAgo(300)))
    }

    @Test
    fun neverUsedOnlyAsksWhetherItWasEverSent() {
        assertTrue(StaleRule.neverUsed(0L))
        assertTrue(StaleRule.neverUsed(-1L))
        assertFalse(StaleRule.neverUsed(daysAgo(500)))
    }
}
