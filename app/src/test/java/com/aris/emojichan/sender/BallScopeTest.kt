package com.aris.emojichan.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球显示范围（与无障碍自动发送解耦）：勾了哪些应用就只在那里显示；
 * 一个都不勾 = 不限制；前台认不出来时宁可显示（球是用户自己开的，别让它莫名消失）。
 */
class BallScopeTest {

    @Test
    fun emptySelectionShowsEverywhere() {
        assertTrue(BallScope.shouldShow(emptySet(), CHROME))
    }

    @Test
    fun unknownForegroundShowsAnyway() {
        assertTrue(BallScope.shouldShow(setOf(WECHAT, QQ), null))
    }

    @Test
    fun selectedAppShows() {
        assertTrue(BallScope.shouldShow(setOf(WECHAT, QQ), QQ))
    }

    @Test
    fun unselectedAppHides() {
        assertFalse(BallScope.shouldShow(setOf(WECHAT, QQ), CHROME))
    }

    @Test
    fun describeUsesFallbackWhenUnrestricted() {
        assertEquals("所有应用", BallScope.describe(emptySet(), "所有应用") { it })
    }

    @Test
    fun describeJoinsLabels() {
        assertEquals(
            "微信、QQ",
            BallScope.describe(setOf(WECHAT, QQ), "所有应用") { if (it == WECHAT) "微信" else "QQ" }
        )
    }

    private companion object {
        const val WECHAT = "com.tencent.mm"
        const val QQ = "com.tencent.mobileqq"
        const val CHROME = "com.android.chrome"
    }
}
