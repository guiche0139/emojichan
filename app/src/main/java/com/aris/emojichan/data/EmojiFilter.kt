package com.aris.emojichan.data

import java.util.Locale

/**
 * 搜索框里的一项条件。
 *
 * 三种写法对应三个子类：不带符号的词（[Keyword]）、`@关键词@`（[Name]）、
 * `$TAG=标签名$`（[Tag]）。
 */
sealed interface SearchTerm {
    /** 用来拼 LIKE 的那段文字。 */
    val text: String

    /** 这一项是不是「除」（`!` 开头）：true 时整项取反。 */
    val not: Boolean

    /** 不带符号的词：表情名命中或标签名命中都算。 */
    data class Keyword(override val text: String, override val not: Boolean = false) : SearchTerm

    /** `@关键词@`：只看表情名。 */
    data class Name(override val text: String, override val not: Boolean = false) : SearchTerm

    /** `$TAG=标签名$`：只看标签名。 */
    data class Tag(override val text: String, override val not: Boolean = false) : SearchTerm
}

/** 选中多个标签时的合并方式；[EXCLUDE] 就是「除」——这些标签一个都不含。 */
enum class TagMode { ALL, ANY, EXCLUDE }

/**
 * 条件表达式：外层数组是「或」，内层数组是「和」。
 *
 * `$TAG=猫$ & @生气@ / $TAG=狗$` 解析成 `[[猫, 生气], [狗]]` —— 先算 `&`（同一组内），
 * 再算 `/`（组与组之间），跟平时读算式的习惯一致。
 *
 * 组内不该出现空组：空组在 SQL 里会变成「无条件」，那样 `/` 一多就会把全部表情
 * 都放出来，所以解析时就把空组丢了。
 */
data class SearchExpr(val groups: List<List<SearchTerm>>)

/**
 * 主界面当前的筛选条件（收藏 + 搜索条件 + 标签集合）。
 *
 * ViewModel 只管攒条件，怎么翻译成 SQL 由 [EmojiQuery] 负责。
 */
data class EmojiFilter(
    /** 只看收藏。 */
    val favoritesOnly: Boolean = false,

    /** 搜索框解析出来的条件；null = 搜索框是空的，不按文字过滤。 */
    val expr: SearchExpr? = null,

    /**
     * 选中的标签 id（筛选条上的 chip）。
     *
     * [expr] 里写了标签条件（`$…$`）时这些 chip 不参与筛选 —— 两边都是标签条件，
     * 用 AND 拼起来基本只剩空结果。判定在 [EmojiQuery.build] 里。
     */
    val tagIds: List<Long> = emptyList(),

    /**
     * 每个标签各自怎么合并（v0.2.005，用户 m10282）：交 = 都要有、并 = 有一个就行、非 = 都不要有。
     *
     * 没写进这张表的标签按 [TagMode.ALL] 算 —— 新选中的标签默认是「交」。读法与搜索框那套
     * 语法同一套优先级（& 比 | 紧）：「& 猫 & 可爱 | 生气」= 有猫且有可爱、或者有生气。
     */
    val tagModes: Map<Long, TagMode> = emptyMap(),

    /**
     * 只看用过的（v0.2.007，用户 m10764）：发送过的表情 lastUsedTime > 0，从没发过的还是 0。
     *
     * 主界面筛选条上那颗「最近」就是它。它只管「哪些图留下来」，顺序在 ViewModel 那边定成
     * 「最近用过的排在前面」——这一档的意义就是顺序。
     */
    val recentOnly: Boolean = false
) {
    /** 某个标签用哪种合并方式；没单独设过就是「交」。 */
    fun modeOf(tagId: Long): TagMode = tagModes[tagId] ?: TagMode.ALL

    companion object {
        /** `$TAG=` 与 `$TAG:` 两种头部都认，大小写不敏感。 */
        private const val TAG_HEAD_EQ = "TAG="
        private const val TAG_HEAD_COLON = "TAG:"

        /**
         * 去掉 `$…$` 里的 `TAG=` / `TAG:` 头部，只留标签名；没写头部就原样返回。
         *
         * 搜索框补全要认同一套写法，所以它是公开的 —— 两处各写一份迟早会走岔。
         */
        fun tagBody(body: String): String {
            val trimmed = body.trim()
            val upper = trimmed.uppercase(Locale.ROOT)
            return when {
                upper.startsWith(TAG_HEAD_EQ) -> trimmed.substring(TAG_HEAD_EQ.length).trim()
                upper.startsWith(TAG_HEAD_COLON) -> trimmed.substring(TAG_HEAD_COLON.length).trim()
                // 只写 $猫$ 也当标签名，省得每次都敲 TAG=
                else -> trimmed
            }
        }

        /** 把 `$...$` 里的内容当成标签名；认不出标签名时返回 null（这一项丢掉）。 */
        private fun tagTerm(body: String, not: Boolean = false): SearchTerm.Tag? {
            val name = tagBody(body)
            return if (name.isEmpty()) null else SearchTerm.Tag(name, not)
        }

        /**
         * 把搜索框原文解析成条件表达式；没有任何条件时返回 null。
         *
         * 正在输入、条件还没写全（`$TAG=猫` 或 `@猫` 没补右括号）时照样按条件算，
         * 这样筛选是边打边生效的；只写了半截符号（`$`、`@`、`&`、`/`）时这一项丢掉，
         * 免得列表突然清空。
         *
         * 没闭合的 `$TAG:猫` 在遇到 `&` 或 `/` 时截断 —— 老写法（`$TAG:猫` 不带右括号）
         * 跟新写法混着写时不会把后面的条件吞掉。
         *
         * `!` 开头的这一项取反，就是筛选面板上的「除」：`!$TAG=猫$` = 不含猫标签。
         */
        fun parse(raw: String): SearchExpr? {
            val groups = ArrayList<MutableList<SearchTerm>>()
            var group = ArrayList<SearchTerm>()
            val bare = StringBuilder()
            // 「除」只作用在紧跟着的那一项上，写完整项就清掉
            var not = false

            fun flushBare() {
                val text = bare.toString().trim()
                bare.setLength(0)
                if (text.isNotEmpty()) {
                    group.add(SearchTerm.Keyword(text, not))
                    not = false
                }
            }

            fun endGroup() {
                flushBare()
                if (group.isNotEmpty()) groups.add(group)
                group = ArrayList()
            }

            var i = 0
            while (i < raw.length) {
                when (val c = raw[i]) {
                    '&' -> {
                        flushBare()
                        i++
                    }
                    '/' -> {
                        endGroup()
                        i++
                    }
                    '!' -> {
                        // 连着敲几个 ! 还是一个「除」：多按一下不该变成「又要又不含」的空结果
                        flushBare()
                        not = true
                        i++
                    }
                    '$', '@' -> {
                        flushBare()
                        val close = raw.indexOf(c, i + 1)
                        // 没写右括号：$ 遇到 & / 就截断，@ 一直吃到结尾
                        val stop = if (close >= 0) close else if (c == '$') {
                            val k = raw.indexOfFirst(from = i + 1) { it == '&' || it == '/' }
                            if (k < 0) raw.length else k
                        } else raw.length
                        val body = raw.substring(i + 1, stop)
                        if (c == '$') {
                            val term = tagTerm(body, not)
                            if (term != null) {
                                group.add(term)
                                not = false
                            }
                        } else {
                            val text = body.trim()
                            if (text.isNotEmpty()) {
                                group.add(SearchTerm.Name(text, not))
                                not = false
                            }
                        }
                        i = if (close >= 0) close + 1 else stop
                    }
                    else -> {
                        bare.append(c)
                        i++
                    }
                }
            }
            endGroup()
            return if (groups.isEmpty()) null else SearchExpr(groups)
        }

        /** 从字符串里找第一个满足条件的下标；配合 `$TAG:猫 & x` 的截断用。 */
        private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
            for (index in from until length) if (predicate(this[index])) return index
            return -1
        }
    }
}
