package com.aris.emojichan.storage

import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.aris.emojichan.R
import com.aris.emojichan.SimilarPrefs
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 忽略列表页（v0.2.008，用户 m10953；v0.2.009 起一行是一条忽略记录，用户 m11102）：
 * 查看、逐条撤销或一次全部撤销「不像同一张」的那些组合。
 *
 * 原来它是个对话框（v0.2.006，用户 m10497），只给 40% 屏高的一块 ListView。用户 m10953 要的是
 * 「单独做一页，方便查看」—— 名单几十对的时候对话框里一次只看得到五六行，翻起来还得跟标题、
 * 按钮抢地方；整页之后列表有多长就滚多长，两个恢复入口也各归各位（逐条在行尾，全部在页底）。
 *
 * 一行对应「用户点过一次的那个『忽略这组』」（见 [SimilarIgnore]），不是一行一对：v0.2.006 记的是
 * 组内两两（38 张点一次就是 703 对，用户 m11084 攒到 700 多行，翻不回自己刚点的那次）。改记整条
 * 之后行数等于点过的次数、一行一次撤销，判定与从前完全等价。
 *
 * 名单本身仍然只存在偏好里（见 [com.aris.emojichan.SimilarPrefs]），这一页不写库、不碰图片：
 * 撤销一条就是把它从那个字符串集合里拿掉，检索页回到前台时自己会发现名单变了并重排分组。
 *
 * 进页面顺手剪一次名单（[SimilarIgnore.keepAlive]）：表情被删掉之后它牵涉的记录就不成立了，
 * 不剪的话用户会翻到一堆「某张 ↔ 某张」而其中一张早就不在库里。
 */
class SimilarIgnoreActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private lateinit var emptyState: View
    private lateinit var clearAllButton: MaterialButton

    private val rows = IgnoreListAdapter(this)

    /** 名单里的记录，进页面读一份；改动当场写回偏好。 */
    private var ignored: Set<String> = emptySet()

    /** 这一次读到的表情：按 id 查名字、缩略图与导入时间（排序用）。 */
    private var items: Map<Long, DuplicateFinder.Item> = emptyMap()

    /** 取表情那一趟，走了或又回来就换一趟，别让旧结果盖住新的。 */
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色 / 深浅色跟其它页保持一致（overlay 必须在 setContentView 之前套）。
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_similar_ignore)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        findViewById<MaterialToolbar>(R.id.ignoreToolbar).setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.ignoreSummary)
        list = findViewById(R.id.ignoreList)
        emptyState = findViewById(R.id.ignoreEmptyState)
        clearAllButton = findViewById(R.id.btnIgnoreClearAll)

        list.adapter = rows
        rows.onRemove = { row -> remove(row) }
        clearAllButton.setOnClickListener { askClearAll() }
    }

    /**
     * 每次回到这一页都重读一份：别处可能又忽略了几组（检索页），也可能删过表情（详情页），
     * 两种变化都得照最新的算。剪名单只认 id、不读盘，顺手做完。
     */
    override fun onResume() {
        super.onResume()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val stored = SimilarPrefs.ignoredRecords(this@SimilarIgnoreActivity)
            items = viewModel.getAllEmojisOnce().associate { it.id to DuplicateFinder.itemOf(it) }
            val kept = SimilarIgnore.keepAlive(stored, items.keys)
            if (kept != stored) SimilarPrefs.setIgnoredRecords(this@SimilarIgnoreActivity, kept)
            ignored = kept
            render()
        }
    }

    /**
     * 名单里的每一条：解不开的记录、成员不足两张的、认不出表情的都跳过。
     * 排序按「这条记录里最新那张的入库时间」倒序 —— 跟主列表一样新图在前，最近点过的排在上面。
     */
    private fun recordRows(): List<IgnoreListAdapter.Row> =
        ignored.mapNotNull { record ->
            val ids = SimilarIgnore.idsOf(record) ?: return@mapNotNull null
            val members = ids.mapNotNull { items[it] }
            if (members.size < 2) return@mapNotNull null
            IgnoreListAdapter.Row(record, members, SimilarIgnore.pairsIn(record))
        }.sortedWith(
            compareByDescending<IgnoreListAdapter.Row> { row -> row.members.maxOf { it.createTime } }
                .thenBy { it.record }
        )

    private fun render() {
        val list = recordRows()
        rows.submit(list)
        val any = list.isNotEmpty()
        summary.text = getString(
            R.string.similar_ignore_page_count,
            list.size,
            list.sumOf { it.pairs }
        )
        summary.visibility = if (any) View.VISIBLE else View.GONE
        emptyState.visibility = if (any) View.GONE else View.VISIBLE
        // 一条都没有时「全部恢复」没有对象，收起来
        clearAllButton.visibility = if (any) View.VISIBLE else View.GONE
    }

    /** 撤销一条：拿掉这一条记录，写回偏好 —— 检索页回来时会照新名单重排。 */
    private fun remove(row: IgnoreListAdapter.Row) {
        val kept = ignored - row.record
        if (kept.size == ignored.size) return
        ignored = kept
        SimilarPrefs.setIgnoredRecords(this, kept)
        render()
        Toast.makeText(
            this,
            getString(R.string.similar_ignore_undone, row.members.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 全部恢复先说清后果：恢复之后检索页会按原来的规则重新分组。 */
    private fun askClearAll() {
        val total = ignored.size
        if (total == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.similar_ignore_clear_title, total))
            .setMessage(R.string.similar_ignore_clear_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.similar_ignore_clear) { _, _ -> clearAll(total) }
            .show()
    }

    private fun clearAll(total: Int) {
        ignored = emptySet()
        SimilarPrefs.setIgnoredRecords(this, ignored)
        render()
        Toast.makeText(this, getString(R.string.similar_ignore_cleared, total), Toast.LENGTH_SHORT)
            .show()
    }
}
