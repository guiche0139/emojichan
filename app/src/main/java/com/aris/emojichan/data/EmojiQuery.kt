package com.aris.emojichan.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 把 [EmojiFilter] 拼成一条 SQL。
 *
 * 用 RawQuery 手工拼 WHERE，而不是给每种组合写一个 @Query：条件是组合出来的
 * （收藏 × 关键词模式 × 标签集合 × AND/OR），穷举要写十几份几乎一样的 SQL。
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

        val keyword = filter.keyword.trim()
        if (keyword.isNotEmpty()) {
            val pattern = "%" + escapeLike(keyword) + "%"
            when (filter.searchMode) {
                SearchMode.NAME -> {
                    // 名字命中，或者挂着名字命中的标签
                    sql.append(" AND (name LIKE ?" + LIKE_ESCAPE)
                    sql.append(" OR id IN (SELECT l.emojiId FROM emoji_tags l")
                    sql.append(" INNER JOIN tags t ON t.id = l.tagId WHERE t.name LIKE ?" + LIKE_ESCAPE + "))")
                    args.add(pattern)
                    args.add(pattern)
                }
                SearchMode.TAG -> {
                    sql.append(" AND id IN (SELECT l.emojiId FROM emoji_tags l")
                    sql.append(" INNER JOIN tags t ON t.id = l.tagId WHERE t.name LIKE ?" + LIKE_ESCAPE + ")")
                    args.add(pattern)
                }
            }
        }

        // 已经用 $TAG: 在搜某个标签时，再叠标签 chip 只会撞出空结果
        if (filter.tagIds.isNotEmpty() && filter.searchMode != SearchMode.TAG) {
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

    /**
     * LIKE 的通配符要转义：搜一个「%」会把全部记录捞出来，搜「_」会命中任意单字符。
     * 反斜杠必须先转，否则会把后面补上的转义符再转一遍。
     */
    fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
