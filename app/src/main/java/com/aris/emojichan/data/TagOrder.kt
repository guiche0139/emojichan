package com.aris.emojichan.data

/**
 * 标签下拉里各颗标签的先后顺序（用户 m11668 第 7 条）。
 *
 * 与 [EmojiOrder] 一样单独拎出来：它是条纯函数，顺序对不对能用单测盯住 ——
 * 表情名多半没有语义（导入进来就是一堆编码），面板里先出现哪几颗直接决定要滚多久。
 */
object TagOrder {

    /** 固定排在最前的两个自动标签，先后就是用户写的「图片、动图」。 */
    private val PINNED = listOf(EmojiDefaults.TAG_IMAGE, EmojiDefaults.TAG_ANIMATED)

    /**
     * 面板顺序：「图片」「动图」永远在最前，其余按挂着的表情张数从多到少。
     *
     * 张数相同再按名字兜底 —— 少了这一层，张数一样的几颗（比如刚导入、都还没挂标签的
     * 那批）每次刷新可能换位置，看着像面板在自己乱跳。名字序与标签查询里的
     * `ORDER BY name` 一致。
     */
    fun forPanel(tags: List<TagEntity>, counts: Map<Long, Int>): List<TagEntity> =
        tags.sortedWith(
            compareBy<TagEntity>(
                { pinnedRank(it.name) },
                { -(counts[it.id] ?: 0) },
                { it.name }
            )
        )

    /** 固定标签的档位；别的标签排在它们后面（档位就是它在 [PINNED] 里的下标）。 */
    private fun pinnedRank(name: String): Int =
        PINNED.indexOf(name).let { if (it >= 0) it else PINNED.size }
}
