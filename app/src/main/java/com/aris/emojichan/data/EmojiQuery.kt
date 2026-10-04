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

        // 「最近」= 用过的（发送过一次以上）：没发过的 lastUsedTime 还是 0，见 EmojiDao.getRecentEmojis。
        // 顺序不在这里定 —— 界面上「最近」按使用时间倒序，那是 ViewModel 的事。
        if (filter.recentOnly) {
            sql.append(" AND lastUsedTime > 0")
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

        // 表达式里已经写了「要某标签」的条件（$…$）时，面板上的标签 chip 不参与：两者是 AND 关系，
        // 叠起来多半只会撞出空结果（$TAG:猫$ 再配一个「狗」chip），用户看到的是莫名其妙的空白。
        // 「除」的写法（!$TAG=猫$）不占这个位置：它跟 chip 叠起来是「要 A 且不要 B」，是有意义的结果。
        val exprHasTag = groups.any { group -> group.any { it is SearchTerm.Tag && !it.not } }
        if (filter.tagIds.isNotEmpty() && !exprHasTag) {
            val ids = filter.tagIds.distinct().take(MAX_TAG_IDS)
            // 每个标签各有各的合并方式（用户 m10282），按方式分成三组：
            // 非 → 挂着其中任一个的整段排除（NOT IN 里没有 NULL，安全）；
            // 交 → 这一组全都要有（先捞出「命中任一」的，再要求命中数等于个数）；
            // 并 → 这一组有一个就行。
            // 交、并两组的读法跟搜索框那套语法同一套优先级（& 比 | 紧）：两组都有时是
            // 「交的那组都要有」或者「并的那组里有一个」；只写一组时就是那一组自己的意思。
            val notIds = ids.filter { filter.modeOf(it) == TagMode.EXCLUDE }
            val anyIds = ids.filter { filter.modeOf(it) == TagMode.ANY }
            val allIds = ids.filter { filter.modeOf(it) == TagMode.ALL }
            if (notIds.isNotEmpty()) {
                sql.append(" AND id NOT IN (SELECT emojiId FROM emoji_tags WHERE tagId IN (")
                sql.append(notIds.joinToString(", ") { "?" })
                sql.append("))")
                args.addAll(notIds)
            }
            val anyCond = if (anyIds.isEmpty()) null else {
                "id IN (SELECT emojiId FROM emoji_tags WHERE tagId IN (" +
                    anyIds.joinToString(", ") { "?" } + "))"
            }
            val allCond = if (allIds.isEmpty()) null else {
                "id IN (SELECT emojiId FROM emoji_tags WHERE tagId IN (" +
                    allIds.joinToString(", ") { "?" } +
                    ") GROUP BY emojiId HAVING COUNT(DISTINCT tagId) = " + allIds.size + ")"
            }
            when {
                anyCond != null && allCond != null -> {
                    // 括号不能省：外面还挂着搜索条件、非标签、收藏这些 AND
                    sql.append(" AND (").append(allCond).append(" OR ").append(anyCond).append(")")
                    args.addAll(allIds)
                    args.addAll(anyIds)
                }
                anyCond != null -> {
                    sql.append(" AND ").append(anyCond)
                    args.addAll(anyIds)
                }
                allCond != null -> {
                    sql.append(" AND ").append(allCond)
                    args.addAll(allIds)
                }
            }
        }

        sql.append(" ORDER BY createTime DESC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /** 一项条件对应的 SQL 片段；用到的参数就地塞进 [args]。 */
    private fun termSql(term: SearchTerm, args: MutableList<Any?>): String {
        val pattern = "%" + escapeLike(term.text.trim()) + "%"
        val body = when (term) {
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
        // 「除」：整项取反。在外面包一层 NOT 而不是塞进 LIKE，三种写法才能共用同一段 SQL。
        return if (term.not) "NOT ($body)" else body
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
