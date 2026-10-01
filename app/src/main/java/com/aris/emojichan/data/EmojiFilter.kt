package com.aris.emojichan.data

import java.util.Locale

/** 搜索框里那串字按什么解释。 */
enum class SearchMode {
    /** 普通搜索：匹配表情名，也匹配标签名。 */
    NAME,

    /** `$TAG:关键词`：按标签名找，命中标签下的全部表情。 */
    TAG
}

/**
 * 主界面当前的筛选条件（收藏 + 关键词 + 标签集合）。
 *
 * ViewModel 只管攒条件，怎么翻译成 SQL 由 [EmojiQuery] 负责。
 */
data class EmojiFilter(
    /** 只看收藏。 */
    val favoritesOnly: Boolean = false,

    /** 已经解析掉前缀的关键词。 */
    val keyword: String = "",

    val searchMode: SearchMode = SearchMode.NAME,

    /** 选中的标签 id。 */
    val tagIds: List<Long> = emptyList(),

    /** true = 同时满足所有标签（AND），false = 任一满足（OR）。 */
    val tagMatchAll: Boolean = true
) {
    companion object {
        /** 按标签搜索的前缀，5 个字符。 */
        const val PREFIX_TAG = "\$TAG:"

        /**
         * 把搜索框原文解析成「模式 + 关键词」。
         *
         * 前缀大小写不敏感；前缀后面没有内容时退回普通搜索 —— 否则用户刚敲完
         * `$TAG:`、还没输关键词，列表就已经空了，看着像卡死。
         */
        fun parseQuery(raw: String): Pair<SearchMode, String> {
            val trimmed = raw.trim()
            val upper = trimmed.uppercase(Locale.ROOT)
            if (!upper.startsWith(PREFIX_TAG)) return SearchMode.NAME to trimmed
            val keyword = trimmed.substring(PREFIX_TAG.length).trim()
            return if (keyword.isEmpty()) SearchMode.NAME to "" else SearchMode.TAG to keyword
        }
    }
}
