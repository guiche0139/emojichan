package com.aris.emojichan.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 标签下拉的排序规则（用户 m11668 第 7 条）。 */
class TagOrderTest {

    private fun tag(id: Long, name: String) = TagEntity(id = id, name = name)

    /** 图片、动图两个自动标签永远在最前，先后就是用户写的「图片、动图」。 */
    @Test
    fun autoTagsComeFirst() {
        val ordered = TagOrder.forPanel(
            listOf(tag(1, "猫"), tag(2, "动图"), tag(3, "图片")),
            mapOf(1L to 999, 2L to 1, 3L to 1)
        )

        assertEquals(listOf(3L, 2L, 1L), ordered.map { it.id })
    }

    /** 其余标签按挂着的张数从多到少 —— 名字没有语义，用得多的得先露面。 */
    @Test
    fun restSortedByCountDescending() {
        val ordered = TagOrder.forPanel(
            listOf(tag(1, "猫"), tag(2, "狗"), tag(3, "兔子"), tag(4, "图片")),
            mapOf(1L to 5, 2L to 40, 3L to 12)
        )

        assertEquals(listOf(4L, 2L, 3L, 1L), ordered.map { it.id })
    }

    /** 张数一样时按名字兜底：不兜底的话每次刷新顺序都可能变，看着像面板在自己乱跳。 */
    @Test
    fun tiesFallBackToName() {
        val ordered = TagOrder.forPanel(
            listOf(tag(1, "b"), tag(2, "c"), tag(3, "a")),
            mapOf(1L to 7, 2L to 7, 3L to 7)
        )

        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.id })
    }

    /** 在 emoji_tags 里查不到的标签就是一张都没挂，按 0 张沉到后面。 */
    @Test
    fun missingCountsSinkToBottom() {
        val ordered = TagOrder.forPanel(
            listOf(tag(1, "没挂过"), tag(2, "挂过一张")),
            mapOf(2L to 1)
        )

        assertEquals(listOf(2L, 1L), ordered.map { it.id })
    }

    /** 空表原样返回：一个标签都没有时不该有别的动静。 */
    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<TagEntity>(), TagOrder.forPanel(emptyList(), emptyMap()))
    }
}
