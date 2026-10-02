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

    /** 不带符号的词：表情名命中或标签名命中都算。 */
    data class Keyword(override val text: String) : SearchTerm

    /** `@关键词@`：只看表情名。 */
    data class Name(override val text: String) : SearchTerm

    /** `$TAG=标签名$`：只看标签名。 */
    data class Tag(override val text: String) : SearchTerm
}

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

    /** true = 同时满足所有标签（AND），false = 任一满足（OR）。 */
    val tagMatchAll: Boolean = true
) {
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
        private fun tagTerm(body: String): SearchTerm.Tag? {
            val name = tagBody(body)
            return if (name.isEmpty()) null else SearchTerm.Tag(name)
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
         */
        fun parse(raw: String): SearchExpr? {
            val groups = ArrayList<MutableList<SearchTerm>>()
            var group = ArrayList<SearchTerm>()
            val bare = StringBuilder()

            fun flushBare() {
                val text = bare.toString().trim()
                bare.setLength(0)
                if (text.isNotEmpty()) group.add(SearchTerm.Keyword(text))
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
                    '$', '@' -> {
                        flushBare()
                        val close = raw.indexOf(c, i + 1)
                        // 没写右括号：$ 遇到 & / 就截断，@ 一直吃到结尾
                        val stop = if (close >= 0) close else if (c == '$') {
                            val k = raw.indexOfFirst(from = i + 1) { it == '&' || it == '/' }
                            if (k < 0) raw.length else k
                        } else raw.length
                        val body = raw.substring(i + 1, stop)
                        if (c == '$') tagTerm(body)?.let { group.add(it) } else {
                            val text = body.trim()
                            if (text.isNotEmpty()) group.add(SearchTerm.Name(text))
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
