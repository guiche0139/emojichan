package com.aris.emojichan

import android.content.Context
import com.aris.emojichan.storage.SimilarFinder
import com.aris.emojichan.storage.SimilarIgnore

/**
 * 「重复与相似检索」这一组偏好：画面相近判定的底线（v0.1.412，用户 m08998）与「不像同一张」的
 * 忽略名单（v0.2.006 记成对；v0.2.009 起一次忽略记一条，用户 m11102）。
 *
 * 在这之前这一档摆在检索页的小标题里（很像 / 比较像两个按钮）。用户 m08998 的结论是那个开关
 * 没有实际使用价值 —— 结果改成按差距由近到远排列之后，一页里自然是从差距最小的往下看，
 * 看到不像的就停，不再需要在页面里来回切档。于是判定范围只剩一个「底线」，挪进高级设置。
 *
 * 为什么单开一份、放在高级设置里：它只决定「画面相近」那一段列多宽，改完要重新点开检索页
 * 才看得出来，属于「知道自己想要什么再调」的那一类，跟压缩方式、发送方式摆在一起。
 *
 * 跟界面长相无关，所以不塞进 [UiPrefs]。
 */
object SimilarPrefs {

    private const val PREFS = "search_settings"
    private const val KEY_THRESHOLD = "similar_threshold"
    private const val KEY_IGNORED = "similar_ignored_pairs"

    /**
     * 当前底线：跟组代表差这么多位以内算相近（这个数字是感知哈希的距离，页面上显示成相似度百分比）。
     *
     * 存坏了（手改过、上一版的旧值）就回到 [SimilarFinder.DEFAULT] —— 只认 [SimilarFinder.LEVELS]
     * 里的几档，别的值在界面上根本选不出来。
     */
    fun threshold(context: Context): Int {
        val saved = prefs(context).getInt(KEY_THRESHOLD, SimilarFinder.DEFAULT)
        return if (saved in SimilarFinder.LEVELS) saved else SimilarFinder.DEFAULT
    }

    fun setThreshold(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_THRESHOLD, value).apply()
    }

    /**
     * 「不像同一个表情」的那些记录：一次忽略一条（见 [SimilarIgnore]），存的就是它那种
     * 「升序 id 用 `:` 串起来」的字符串。
     *
     * 键名沿用 v0.2.006 的 `similar_ignored_pairs`：换名等于把用户攒下的名单丢掉，而值本身不需要
     * 迁移 —— 从前一对一条，那条 `"3:7"` 就是成员两张的记录。
     *
     * 返回的是拷贝：`getStringSet` 拿到的就是偏好里那一个 Set 本身，调用方在上面加加减减会直接
     * 写脏内存里的偏好（还未必落盘），所以必须复制一份出来。
     */
    fun ignoredRecords(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_IGNORED, emptySet()) ?: emptySet())

    fun setIgnoredRecords(context: Context, records: Set<String>) {
        prefs(context).edit().putStringSet(KEY_IGNORED, HashSet(records)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
