package com.aris.emojichan.sender

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * emc-2-029 的分界线：Android 14（API 34）起 `startActivityAndCollapse(Intent)` 会抛
 * UnsupportedOperationException（targetSdk >= 34 时那个 compat change 生效），必须改走
 * PendingIntent 重载。把这条线钉在单测里，免得以后有人"顺手"改回去。
 */
class TileLaunchTest {

    @Test
    fun api33_stillUsesIntentOverload() {
        assertFalse(TileLaunch.needsPendingIntent(33))
    }

    @Test
    fun api34_needsPendingIntentOverload() {
        assertTrue(TileLaunch.needsPendingIntent(34))
    }

    @Test
    fun newerApis_keepNeedingPendingIntent() {
        assertTrue(TileLaunch.needsPendingIntent(35))
        assertTrue(TileLaunch.needsPendingIntent(36))
    }
}
