package com.aris.emojichan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 使用引导只在第一次正常启动时自动出现一次（v0.1.414，用户 m09215）。
 *
 * 这里锁住三件事：默认没看过、看完记下、跳过之后也算看过 —— 跳过是用户明确的决定，
 * 下次启动再弹一遍就成了骚扰。
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 装上之后第一次进来就是没看过。 */
    @Test
    fun notDoneAtFirst() {
        assertFalse(OnboardingPrefs.isDone(context))
    }

    /** 走完（或跳过）之后记下，下次不再自动出现。 */
    @Test
    fun remembersDone() {
        OnboardingPrefs.markDone(context)
        assertTrue(OnboardingPrefs.isDone(context))
    }

    /** 「重新看一遍」要靠清标记：清完又回到会自动出现的状态。 */
    @Test
    fun resetBringsItBack() {
        OnboardingPrefs.markDone(context)
        OnboardingPrefs.reset(context)
        assertFalse(OnboardingPrefs.isDone(context))
    }
}
