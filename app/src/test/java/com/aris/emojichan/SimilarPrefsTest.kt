package com.aris.emojichan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aris.emojichan.storage.SimilarFinder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 「重复与相似检索」那两项偏好：相似底线与忽略名单（v0.2.006，用户 m10497；v0.2.009 起记整条）。 */
@RunWith(RobolectricTestRunner::class)
class SimilarPrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun thresholdFallsBackToDefaultWhenNothingIsSaved() {
        assertEquals(SimilarFinder.DEFAULT, SimilarPrefs.threshold(context))
    }

    /** 只认界面上能选的那几档：存进来的别的值（手改过偏好）一律回到默认。 */
    @Test
    fun thresholdKeepsOnlyKnownLevels() {
        SimilarPrefs.setThreshold(context, SimilarFinder.LOOSE)
        assertEquals(SimilarFinder.LOOSE, SimilarPrefs.threshold(context))

        SimilarPrefs.setThreshold(context, 7)
        assertEquals(SimilarFinder.DEFAULT, SimilarPrefs.threshold(context))
    }

    @Test
    fun ignoredRecordsStartEmptyAndSurviveARoundTrip() {
        assertEquals(emptySet<String>(), SimilarPrefs.ignoredRecords(context))

        val saved = setOf("1:2:3", "9:11")
        SimilarPrefs.setIgnoredRecords(context, saved)

        assertEquals(saved, SimilarPrefs.ignoredRecords(context))
    }

    /** 读出来的必须是拷贝：getStringSet 给的是偏好内部那一个 Set，改它会污染已存的名单。 */
    @Test
    fun ignoredRecordsAreCopiedOut() {
        SimilarPrefs.setIgnoredRecords(context, setOf("1:2"))

        @Suppress("UNCHECKED_CAST")
        val read = SimilarPrefs.ignoredRecords(context) as MutableSet<String>
        read += "4:5"

        assertEquals(setOf("1:2"), SimilarPrefs.ignoredRecords(context))
    }
}
