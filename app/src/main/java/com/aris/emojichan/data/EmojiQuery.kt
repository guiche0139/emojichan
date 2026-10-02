package com.aris.emojichan.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 把 [EmojiFilter] 拼成一条 SQL。
 *
 * 用 RawQuery 手工拼 WHERE，而不是给每种组合写一个 @Query：条件是组合出来的
 * （收藏 × 搜索条件 × 标签集合 × AND/OR），穷举要写十几份几乎一样的 SQL。
 * 拼接的只有占位符与常量，用户输入一律走参数绑定。
 */
object EmojiQuery {

    /** LIKE 的转义声明；配 [escapeLike] 用。 */
    private const val LIKE_ESCAPE = " ESCAPE '\\'"

    /** 标签 id 的上限：再多的条件不如让用户直接搜标签名。 */
    private const val MAX_TAG_IDS = 200

    fun build(filter: EmojiFilter): SupportSQLiteQuery {
        val sql = StringBuilder("SELECT * FROM emojis WHERE 1 = 1")
        val args = ArrayList<Any?>()

        if (filter.favoritesOnly) {
            sql.append(" AND isFavorite = 1")
        }

        // 搜索条件：组内 AND，组间 OR（& 先算、/ 后算）
        val groups = filter.expr?.groups?.filter { it.isNotEmpty() }.orEmpty()
        if (groups.isNotEmpty()) {
            sql.append(" AND (")
            groups.forEachIndexed { index, group ->
                if (index > 0) sql.append(" OR ")
                sql.append("(")
                group.forEachIndexed { termIndex, term ->
                    if (termIndex > 0) sql.append(" AND ")
                    sql.append(termSql(term, args))
                }
                sql.append(")")
            }
            sql.append(")")
        }

        // 表达式里已经写了标签条件（$…$）时，面板上的标签 chip 不参与：两者是 AND 关系，
        // 叠起来多半只会撞出空结果（$TAG:猫$ 再配一个「狗」chip），用户看到的是莫名其妙的空白。
        val exprHasTag = groups.any { group -> group.any { it is SearchTerm.Tag } }
        if (filter.tagIds.isNotEmpty() && !exprHasTag) {
            val ids = filter.tagIds.distinct().take(MAX_TAG_IDS)
            val holders = ids.joinToString(", ") { "?" }
            if (filter.tagMatchAll) {
                // 同时满足：先捞出「命中任一标签」的表情，再要求命中数等于标签数
                sql.append(" AND id IN (SELECT emojiId FROM emoji_tags WHERE tagId IN (")
                sql.append(holders)
                sql.append(") GROUP BY emojiId HAVING COUNT(DISTINCT tagId) = ")
                sql.append(ids.size)
                sql.append(")")
            } else {
                sql.append(" AND id IN (SELECT emojiId FROM emoji_tags WHERE tagId IN (")
                sql.append(holders)
                sql.append("))")
            }
            args.addAll(ids)
        }

        sql.append(" ORDER BY createTime DESC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /** 一项条件对应的 SQL 片段；用到的参数就地塞进 [args]。 */
    private fun termSql(term: SearchTerm, args: MutableList<Any?>): String {
        val pattern = "%" + escapeLike(term.text.trim()) + "%"
        return when (term) {
            is SearchTerm.Name -> {
                args.add(pattern)
                "name LIKE ?$LIKE_ESCAPE"
            }
            is SearchTerm.Tag -> {
                args.add(pattern)
                "id IN (SELECT l.emojiId FROM emoji_tags l" +
                    " INNER JOIN tags t ON t.id = l.tagId WHERE t.name LIKE ?$LIKE_ESCAPE)"
            }
            is SearchTerm.Keyword -> {
                // 名字命中，或者挂着名字命中的标签
                args.add(pattern)
                args.add(pattern)
                "(name LIKE ?$LIKE_ESCAPE OR id IN (SELECT l.emojiId FROM emoji_tags l" +
                    " INNER JOIN tags t ON t.id = l.tagId WHERE t.name LIKE ?$LIKE_ESCAPE))"
            }
        }
    }

    /**
     * LIKE 的通配符要转义：搜一个「%」会把全部记录捞出来，搜「_」会命中任意单字符。
     * 反斜杠必须先转，否则会把后面补上的转义符再转一遍。
     */
    fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
