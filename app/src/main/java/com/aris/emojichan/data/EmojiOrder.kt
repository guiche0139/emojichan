package com.aris.emojichan.data

/**
 * 列表顺序里跟「哪一档」有关的那部分规则。
 *
 * 单独拎出来是因为它是条纯函数，能被单测盯着：顺序是「最近」这一档的全部意义，
 * 排错了它跟别的分类看上去就没区别（v0.2.007，用户 m10764）。
 */
object EmojiOrder {

    /**
     * 「最近」那一档的顺序：最近用过的在前。
     *
     * 时间一样时拿 id 兜底 —— 连着发好几张时 lastUsedTime 常常毫秒都相同，不兜底的话
     * 每次都可能有不一样的先后，看着像列表在自己乱跳。没用过的（0）自然沉到最下面。
     */
    fun recentFirst(list: List<EmojiEntity>): List<EmojiEntity> =
        list.sortedWith(
            compareByDescending<EmojiEntity> { it.lastUsedTime }.thenBy { it.id }
        )
}