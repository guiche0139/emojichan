package com.aris.emojichan.data

/**
 * 一个标签挂着多少张表情（EmojiDao.observeTagCounts 的返回行）。
 *
 * 只为标签下拉的排序而读（用户 m11668 第 7 条）。
 */
data class TagCount(
    val tagId: Long,
    val count: Int
)
