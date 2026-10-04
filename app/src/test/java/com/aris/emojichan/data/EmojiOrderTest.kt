package com.aris.emojichan.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「最近」那一档的排序规则（用户 m10764）。 */
class EmojiOrderTest {

    private fun emoji(id: Long, lastUsedTime: Long) = EmojiEntity(
        id = id,
        name = "表情$id",
        filePath = "/data/emojis/$id.webp",
        fileType = "image",
        lastUsedTime = lastUsedTime
    )

    /** 最近用过的排在最前 —— 这一档的意义全在这里，排错了就跟别的分类没区别。 */
    @Test
    fun recentlyUsedComesFirst() {
        val ordered = EmojiOrder.recentFirst(
            listOf(emoji(1, 300L), emoji(2, 900L), emoji(3, 100L))
        )

        assertEquals(listOf(2L, 1L, 3L), ordered.map { it.id })
    }

    /** 时间相同（连着发好几张，毫秒都一样）拿 id 兜底，顺序必须每次都一样。 */
    @Test
    fun tiesFallBackToId() {
        val ordered = EmojiOrder.recentFirst(
            listOf(emoji(7, 500L), emoji(3, 500L), emoji(5, 500L))
        )

        assertEquals(listOf(3L, 5L, 7L), ordered.map { it.id })
    }

    /** 没用过的（lastUsedTime = 0）沉底；空表原样返回。 */
    @Test
    fun neverUsedSinksToTheBottom() {
        val ordered = EmojiOrder.recentFirst(
            listOf(emoji(1, 0L), emoji(2, 42L), emoji(3, 0L))
        )

        assertEquals(listOf(2L, 1L, 3L), ordered.map { it.id })
        assertEquals(emptyList<EmojiEntity>(), EmojiOrder.recentFirst(emptyList()))
    }
}