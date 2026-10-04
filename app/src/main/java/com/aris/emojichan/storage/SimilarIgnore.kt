package com.aris.emojichan.storage

/**
 * 「这几张不像同一个表情」的记录（v0.2.006 记成对；v0.2.009 起记「一次忽略」，用户 m11102）。
 *
 * 一次「忽略这组」记下的就是这一次点中的那几张 —— 升序 id、`:` 分隔，例如 `12:45:78`。
 * 语义跟「把这一组里每一对都记下来」完全等价：禁止的从来只是「同一次忽略的这些张两两同组」，
 * 而成员只有两张的记录就是一对，所以 v0.2.006 存下来的旧数据（`"3:7"`）本来就是合法记录，
 * 不用迁移、不用转换。改记整条省掉的是体积与行数：n 张从前要写 n(n−1)/2 条（38 张 = 703 条），
 * 现在只写 1 条，用户也能一次撤销自己刚点的那一次。
 *
 * 名单存在偏好里（[com.aris.emojichan.SimilarPrefs]，键名沿用 v0.2.006 的 `similar_ignored_pairs`），
 * 不进数据库、不碰图片：忽略只影响检索页怎么分组。
 */
object SimilarIgnore {

    /** 一条记录：升序、去重、`:` 分隔；不足两张不算记录（返回 null）。 */
    fun recordOf(ids: Collection<Long>): String? {
        val sorted = ids.toSortedSet()
        if (sorted.size < 2) return null
        return sorted.joinToString(":")
    }

    /** 从记录解回 id 列表：格式不对（偏好被手改过）或不足两张都返回 null。 */
    fun idsOf(record: String): List<Long>? {
        val parts = record.split(':')
        if (parts.size < 2) return null
        val ids = ArrayList<Long>(parts.size)
        for (part in parts) ids += part.toLongOrNull() ?: return null
        return ids
    }

    /** 这一条记录盖住多少对（n 张 = n(n−1)/2 对）；解不开算 0。 */
    fun pairsIn(record: String): Int {
        val n = idsOf(record)?.size ?: return 0
        return n * (n - 1) / 2
    }

    /** 整份名单一共盖住多少对（页面上的「覆盖 N 对」）。 */
    fun pairsOf(records: Set<String>): Int = records.sumOf { pairsIn(it) }

    /** 这一对在不在名单里：必须同属某一条记录。名单小、查得少时用它，查得密用 [bannedOf]。 */
    fun contains(records: Set<String>, a: Long, b: Long): Boolean =
        records.any { record -> idsOf(record)?.let { a in it && b in it } == true }

    /**
     * 查表版（v0.2.007）：每张图 → 不能跟它同组的那些张。一条记录摊成组内两两（[SimilarFinder]
     * 就是按「这张不能跟谁同组」问的）。名单里解不开的记录整条跳过。
     */
    fun bannedOf(records: Set<String>): Map<Long, Set<Long>> {
        if (records.isEmpty()) return emptyMap()
        val table = HashMap<Long, MutableSet<Long>>()
        records.forEach { record ->
            val ids = idsOf(record) ?: return@forEach
            for (i in ids.indices) {
                val partners = table.getOrPut(ids[i]) { HashSet() }
                for (j in ids.indices) if (i != j) partners += ids[j]
            }
        }
        return table
    }

    /**
     * 剪掉不再成立的：记录里已经不在库里的 id 摘掉，剩下不到两张就整条作废。
     * 表情删掉之后，它牵涉的每一条记录都跟着收窄 —— 名单不该越攒越多。
     */
    fun keepAlive(records: Set<String>, alive: Set<Long>): Set<String> {
        if (records.isEmpty()) return emptySet()
        val kept = HashSet<String>(records.size)
        records.forEach { record ->
            val ids = idsOf(record)?.filter { it in alive } ?: return@forEach
            recordOf(ids)?.let { kept += it }
        }
        return kept
    }

    /**
     * 一次「按包恢复」的结果（v0.2.012，用户 m11327 的第 3 条）。
     *
     * @param records 认下来的记录（成员 id 已经换成本机库里的）。
     * @param dropped 丢掉了几条：成员映射不到、或者记录本身解不开。
     */
    data class Restored(val records: Set<String>, val dropped: Int)

    /**
     * 把备份包里的记录按 old → new 映射搬进本机的库：**一条记录全有或全无**。
     *
     * 为什么整条丢、而不是把认得的成员留下：一条记录的语义是「同一次忽略的这几张彼此不像」，
     * 只剩一部分成员的话，留下的那几张本来可以同组，而用户从没这样点过 —— 与其编一条假的，
     * 不如丢掉并如实报数（[Restored.dropped]）。
     *
     * 解不开的记录（偏好被手改过、别的程序写坏）同样只算 dropped，不进名单。
     */
    fun restore(records: Collection<String>, mapping: Map<Long, Long>): Restored {
        if (records.isEmpty()) return Restored(emptySet(), 0)
        val kept = HashSet<String>(records.size)
        var dropped = 0
        for (record in records) {
            val ids = idsOf(record)
            if (ids == null) {
                dropped++
                continue
            }
            val mapped = ArrayList<Long>(ids.size)
            var whole = true
            for (id in ids) {
                val fresh = mapping[id]
                if (fresh == null || fresh <= 0L) {
                    whole = false
                    break
                }
                mapped += fresh
            }
            val fresh = if (whole) recordOf(mapped) else null
            if (fresh == null) dropped++ else kept += fresh
        }
        return Restored(kept, dropped)
    }
}
