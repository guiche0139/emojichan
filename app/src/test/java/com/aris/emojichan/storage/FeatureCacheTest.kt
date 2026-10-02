package com.aris.emojichan.storage

import com.aris.emojichan.data.ImageFeatureEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹行的写回规则。
 *
 * 体积 + 修改时间就是这一行的「版本号」：对得上，另一项特征继续用；对不上，旧特征对新文件
 * 已经无效，必须丢掉 —— 留着它，「完全相同」页会拿旧摘要比出一个根本不存在的重复。
 */
class FeatureCacheTest {

    private fun item(bytes: Long = 100L, modifiedAt: Long = 1L) = DuplicateFinder.Item(
        id = 7,
        name = "表情7",
        path = "/data/emojis/7.webp",
        bytes = bytes,
        modifiedAt = modifiedAt,
        createTime = 1L
    )

    private fun old(
        bytes: Long = 100L,
        modifiedAt: Long = 1L,
        sha256: String? = null,
        dhash: Long? = null
    ) = ImageFeatureEntity(
        emojiId = 7,
        bytes = bytes,
        modifiedAt = modifiedAt,
        sha256 = sha256,
        dhash = dhash,
        computedAt = 1L
    )

    @Test
    fun freshRowCarriesTheNewFeatureAndTheVersionItWasReadFrom() {
        val row = FeatureCache.merged(item(bytes = 4096, modifiedAt = 99), old = null, dhash = 0x55L)

        assertEquals(7L, row.emojiId)
        assertEquals(4096L, row.bytes)
        assertEquals(99L, row.modifiedAt)
        assertNull(row.sha256)
        assertEquals(0x55L, row.dhash)
        assertTrue(row.computedAt > 0L)
    }

    @Test
    fun sameVersionKeepsTheOtherFeature() {
        val row = FeatureCache.merged(item(), old(sha256 = "abc"), dhash = 12L)

        assertEquals("abc", row.sha256)
        assertEquals(12L, row.dhash)
    }

    @Test
    fun changedBytesDropTheOtherFeature() {
        val row = FeatureCache.merged(item(bytes = 200), old(bytes = 100, sha256 = "abc"), dhash = 12L)

        assertNull(row.sha256)
        assertEquals(12L, row.dhash)
    }

    @Test
    fun changedModifiedAtDropsTheOtherFeature() {
        val row = FeatureCache.merged(item(modifiedAt = 5), old(modifiedAt = 4, sha256 = "abc"), dhash = 12L)

        assertNull(row.sha256)
        assertEquals(12L, row.dhash)
    }

    @Test
    fun newValueWinsOverTheCachedOne() {
        val row = FeatureCache.merged(item(), old(sha256 = "abc", dhash = 1L), sha256 = "def", dhash = 2L)

        assertEquals("def", row.sha256)
        assertEquals(2L, row.dhash)
    }

    @Test
    fun cachedFeaturesAreOnlyTrustedWhenTheVersionMatches() {
        val stale = old(bytes = 100, modifiedAt = 1, sha256 = "abc", dhash = 5L)

        assertEquals("abc", FeatureCache.sha256Of(stale, item(bytes = 100, modifiedAt = 1)))
        assertEquals(5L, FeatureCache.dhashOf(stale, item(bytes = 100, modifiedAt = 1)))

        assertNull(FeatureCache.sha256Of(stale, item(bytes = 101, modifiedAt = 1)))
        assertNull(FeatureCache.dhashOf(stale, item(bytes = 100, modifiedAt = 2)))
        assertNull(FeatureCache.sha256Of(null, item()))
        assertNull(FeatureCache.dhashOf(null, item()))
    }
}
