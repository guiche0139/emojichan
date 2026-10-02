package com.aris.emojichan.storage

import com.aris.emojichan.util.PerceptualHash
import kotlinx.coroutines.CancellationException

/**
 * 「相似」检索（v0.1.409）：按感知哈希找出画面结构接近的表情。
 *
 * 它跟「完全相同」不是一回事：那个是字节级的是非题，这个是「差几位」的程度问题，
 * 没有唯一正确的答案。所以这里给出来的永远是候选，界面上必须并排显示、让人自己挑。
 *
 * 为什么是「跟组代表比」而不是「跟组里任意一张比」：相似不传递。A 像 B、B 像 C 的时候
 * A 未必像 C，一路连下去能把整个库串成一组；跟代表比就不会滚成雪球，代价只是漏掉
 * 「隔了一手的像」——那本来就该是另一个决定。
 *
 * 结果按差距由近到远排（v0.1.412，用户 m08998）：一页里从差距最小的往下看，看到不像的就停，
 * 判定范围因此只剩一个底线（[STRICT] / [STANDARD] / [LOOSE]，存在 [com.aris.emojichan.SimilarPrefs]
 * 里），页面上不再需要切档的开关。
 *
 * 「算哈希」和「分组」是分开的两件事：算哈希要读盘（贵，进页面做一次），分组只动内存里的
 * 64 位数字（便宜，改了底线随时重来）。所以 [find] 只负责前者加一次分组，改了底线走 [group]。
 */
object SimilarFinder {

    /** 相似度 94% 及以上（内部记的是差 0~4 位）：同一张图缩过、压过、换过格式都在这以内。最紧的一档。 */
    const val STRICT = 4

    /** 相似度 88% 及以上（内部记的是差 8 位以内）：默认的一档。同一张图的不同版本、同一个模板换过文字的图大多落在这里。 */
    const val STANDARD = 8

    /** 相似度 81% 及以上（内部记的是差 12 位以内）：最松的一档。画面相近但内容不同的图也会进来。 */
    const val LOOSE = 12

    /** 界面上能选的几档；也是「存进来的值还算不算数」的白名单。 */
    val LEVELS = intArrayOf(STRICT, STANDARD, LOOSE)

    /** 默认底线。 */
    const val DEFAULT = STANDARD

    /**
     * 相似度 = 相同位数 / 总位数（64 位）：距离 d 位就是 (64 - d) / 64，取整成百分比。
     *
     * 只做展示口径，判定本身仍按距离走；四舍五入到整数，所以 8 位 → 88%、4 位 → 94%。
     */
    fun similarity(distance: Int): Int =
        ((PerceptualHash.BITS - distance) * 100 + PerceptualHash.BITS / 2) / PerceptualHash.BITS

    /**
     * 结果顺序：这一组里最不像的那张相似度高的排前面（内部按 [Group.maxDistance] 升序，v0.1.413 起页面显示成百分比）。
     *
     * 并列时张数多的在前 —— 同样接近的两组，能一次看完更多张的那组更值得先看；
     * 张数也一样时按「只留一张能省多少」排，理由同上。
     */
    val SIMILARITY_ORDER: Comparator<Group> = compareBy<Group> { it.maxDistance }
        .thenByDescending { it.members.size }
        .thenByDescending { it.spareBytes }

    /**
     * 一组相似的表情。
     *
     * [members] 第一位是代表，[distances] 与它一一对应，记录每张跟代表差几位（代表自己是 0）。
     */
    data class Group(val members: List<DuplicateFinder.Item>, val distances: List<Int>) {

        val representative: DuplicateFinder.Item get() = members.first()

        /** 组里跟代表差得最远的那一下 —— 界面上写「最多差 X 位」，排序也按它。 */
        val maxDistance: Int get() = distances.maxOrNull() ?: 0

        /** 只留最大的一张、其余都删掉的话能省下的字节（用户实际能省多少由他勾哪几张决定）。 */
        val spareBytes: Long get() = members.sumOf { it.bytes } - members.maxOf { it.bytes }

        /**
         * 去掉几张之后剩下的。
         *
         * 距离是按「跟代表比」算的，代表被删掉之后整组的距离都得重算 —— 这正是 [hashes] 要
         * 传进来的原因：不能让用户删掉一张之后，剩下的组顶着过期的差距继续显示。
         */
        fun without(ids: Set<Long>, hashes: Map<Long, Long>): Group {
            val kept = members.filterNot { it.id in ids }
            return Group(kept, distances(kept, hashes))
        }
    }

    /**
     * 算哈希并分组。
     *
     * @param threshold 底线：跟组代表差这么多位以内就算一组（页面上换算成相似度百分比），取 [STRICT] / [STANDARD] / [LOOSE] 之一
     * @param hashOf 算一张图的感知哈希；返回 null 表示算不出来（文件没了、不是图片），这一张跳过。
     *   取消要照原样抛出去 —— 用户点了「停止」不能还闷头把剩下的算完。
     * @param onProgress 每处理一张报一次（已处理, 总数）
     * @param ignored 用户标过「这两张不像同一个表情」的判定（v0.2.006，见 [SimilarIgnore]）。
     *   返回 true 的两张永远不会落进同一组。
     */
    suspend fun find(
        items: List<DuplicateFinder.Item>,
        threshold: Int,
        hashOf: suspend (DuplicateFinder.Item) -> Long?,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        ignored: (Long, Long) -> Boolean = { _, _ -> false }
    ): List<Group> {
        val todo = items.filter { it.present }.sortedBy { it.createTime }
        val hashes = HashMap<Long, Long>()
        todo.forEachIndexed { index, item ->
            onProgress(index + 1, todo.size)
            val hash = try {
                hashOf(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (hash != null) hashes[item.id] = hash
        }
        onProgress(todo.size, todo.size)
        return group(todo, hashes, threshold, ignored)
    }

    /**
     * 拿现成的哈希分组（改了底线走这条路，不再读盘）。
     *
     * 按导入时间依次入组，每张跟「底线内最近的组代表」走；都不够近就自己当新组的代表。
     * 结果按差距由近到远排（[SIMILARITY_ORDER]）。
     *
     * @param ignored 用户标过「这两张不像同一个表情」的判定（v0.2.006，见 [SimilarIgnore]）：
     *   跟候选组里任何一张被忽略过就不进这一组。不能只比代表 —— A 与 C 被忽略、B 先进了这组
     *   （B 跟谁都没被忽略），C 会顺着代表 B 混进来，用户看到的还是原来那一组。
     */
    fun group(
        items: List<DuplicateFinder.Item>,
        hashes: Map<Long, Long>,
        threshold: Int,
        ignored: (Long, Long) -> Boolean = { _, _ -> false }
    ): List<Group> {
        val ordered = items.filter { it.present }.sortedBy { it.createTime }
        val groups = mutableListOf<MutableList<DuplicateFinder.Item>>()
        val representatives = mutableListOf<Long>()
        val distances = mutableListOf<MutableList<Int>>()

        ordered.forEach { item ->
            val hash = hashes[item.id] ?: return@forEach
            var best = -1
            var bestDistance = Int.MAX_VALUE
            for (index in groups.indices) {
                if (groups[index].any { ignored(item.id, it.id) }) continue
                val distance = PerceptualHash.distance(hash, representatives[index])
                if (distance <= threshold && distance < bestDistance) {
                    best = index
                    bestDistance = distance
                }
            }
            if (best < 0) {
                groups += mutableListOf(item)
                representatives += hash
                distances += mutableListOf(0)
            } else {
                groups[best] += item
                distances[best] += bestDistance
            }
        }

        return groups.indices
            .filter { groups[it].size > 1 }
            .map { Group(groups[it].toList(), distances[it].toList()) }
            .sortedWith(SIMILARITY_ORDER)
    }

    /** 一组里每张跟组代表的差距（第一位是代表，写 0）。算不出来的当 0 —— 它已经在组里了。 */
    fun distances(members: List<DuplicateFinder.Item>, hashes: Map<Long, Long>): List<Int> {
        val first = members.firstOrNull()?.let { hashes[it.id] } ?: return members.map { 0 }
        return members.map { member -> hashes[member.id]?.let { PerceptualHash.distance(first, it) } ?: 0 }
    }
}
