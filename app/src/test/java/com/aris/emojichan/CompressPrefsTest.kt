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
 * 压缩设置的两个默认值 —— 都是「默认值」，正是因为用户不该被迫先去设置里点一下。
 *
 * v0.1.408：压缩页默认不列动图；
 * v0.1.409：压缩方式默认无损（用户 m08399 第 2 条：压缩会替换原图，画质先保到底）。
 */
@RunWith(RobolectricTestRunner::class)
class CompressPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 没动过设置时按无损压缩。 */
    @Test
    fun losslessByDefault() {
        assertTrue(CompressPrefs.lossless(context))
    }

    /** 没动过设置时压缩页不列动图。 */
    @Test
    fun animatedHiddenByDefault() {
        assertFalse(CompressPrefs.showAnimated(context))
    }

    /** 改过之后记住：下次进来还是用户选的那个。 */
    @Test
    fun remembersUserChoice() {
        CompressPrefs.setLossless(context, false)
        assertFalse(CompressPrefs.lossless(context))

        CompressPrefs.setShowAnimated(context, true)
        assertTrue(CompressPrefs.showAnimated(context))
    }
}
