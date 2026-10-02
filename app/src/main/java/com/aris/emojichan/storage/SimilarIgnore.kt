package com.aris.emojichan.storage

/**
 * 「这两张不像同一个表情」的记录（v0.2.006，用户 m10497）。
 *
 * 相似判定天生带主观：底线是统一的，可「像不像」是人说了算。用户点一次「忽略这组」，就把这一组里
 * 的每一对都记下来，之后无论底线怎么调、顺序怎么变，这一对都不会再同时出现在一组里。
 *
 * 为什么记「对」而不是记「组」：分组结果本身不稳定 —— 换一档底线、库里增删一张，同一批图就会重新
 * 分组，昨天那组的名单今天可能就散了。记成对就与怎么分组无关：A 与 B 被忽略过，无论它们各自跟谁
 * 成组，都不会落进同一组。
 *
 * 键是有序对（小的在前，`a:b`），一次查询同时盖住两个方向；它存在
 * [com.aris.emojichan.SimilarPrefs] 的字符串集合里，不进数据库 —— 表情删掉之后这一对自然失效，
 * 读的时候顺手剪掉就行，不必为它改表结构、写迁移。
 */
object SimilarIgnore {

    /** 一对的键。小的在前，所以 (a, b) 与 (b, a) 是同一个键。 */
    fun key(a: Long, b: Long): String = if (a <= b) "$a:$b" else "$b:$a"

    /** 一组里所有的两两组合（n 张有 n(n-1)/2 对）。「忽略这组」传的就是它。 */
    fun keysIn(ids: List<Long>): Set<String> {
        val keys = HashSet<String>()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) keys += key(ids[i], ids[j])
        }
        return keys
    }

    /** 这一对在不在忽略名单里。 */
    fun contains(keys: Set<String>, a: Long, b: Long): Boolean = keys.contains(key(a, b))

    /** 从键解回两张的 id；存的格式不对（偏好被手改过）返回 null。 */
    fun idsOf(key: String): Pair<Long, Long>? {
        val parts = key.split(':')
        if (parts.size != 2) return null
        val a = parts[0].toLongOrNull() ?: return null
        val b = parts[1].toLongOrNull() ?: return null
        return a to b
    }

    /**
     * 剪掉不再成立的那些：任一张已经不在库里（被删了、文件没了）就整对作废。
     *
     * 不剪的话名单会越攒越多，用户翻到一堆「某张 ↔ 某张」，而其中一张早就删了。
     */
    fun keepAlive(keys: Set<String>, alive: Set<Long>): Set<String> =
        keys.filterTo(HashSet()) { entry ->
            val pair = idsOf(entry)
            pair != null && pair.first in alive && pair.second in alive
        }
}
