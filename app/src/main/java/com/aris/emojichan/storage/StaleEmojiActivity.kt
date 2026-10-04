package com.aris.emojichan.storage

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.util.EmojiArchive
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 长时间不使用的表情（v0.2.013，用户 m11668 第 5 条）：把「一段时间没发过」的表情挑出来，
 * 一条路是导出成一份只含这些表情的备份包（导出完再删就踏实了），另一条是直接删掉。
 *
 * 判定口径见 [StaleRule]（v0.2.013 初版只看 [EmojiEntity.lastUsedTime]，用户 m11937 指出新导入的
 * 表情会立刻掉进那三档，起算点因此改成：发过的看最后一次发送时间，从未发过的从入库那天算起）——
 * 刚加进来的表情得先躺够天数才会出现在「90 / 180 / 365 天没发过」里；「从未发过」那一档不看到库
 * 时间，只要一次没发过就在里面。
 *
 * 这一页不订阅数据库的 Flow：进来读一次快照，删完再读一次。列表要的就是一份能反复筛的定表，
 * 换范围只是重筛不重读；顺带也让「换范围时把看不见的勾选摘掉」这件事有个确定的依据。
 */
class StaleEmojiActivity : AppCompatActivity() {

    /** 挑范围的口径。天数 = 阈值，0 表示「从未发过」那一档；从什么时刻起算见 [StaleRule]。 */
    private enum class Window(val days: Int) { NEVER(0), D90(90), D180(180), D365(365) }

    private lateinit var viewModel: EmojiViewModel
    private lateinit var grid: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var summary: TextView
    private lateinit var selectionView: TextView
    private lateinit var selectAllButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var deleteButton: MaterialButton

    /** 库里全部表情：进页面读一次，删完再读一次，之后换范围只重筛。 */
    private var all: List<EmojiEntity> = emptyList()

    /** 勾中的 id。换范围时会把看不见的摘掉（见 [render]）—— 绝不动用户没看着的东西。 */
    private val selected = mutableSetOf<Long>()

    private var window = Window.NEVER

    private val adapter = StaleEmojiAdapter(
        ageText = { ageTextOf(it) },
        onEmojiClick = { toggle(it) },
        selectedIds = { selected }
    )

    /** 导出：交给系统的「保存到…」，包里只有选中的那些（标签与忽略名单照旧整份带上）。 */
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument(EmojiArchive.MIME)
    ) { uri -> if (uri != null) runExport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_stale_emoji)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        findViewById<MaterialToolbar>(R.id.staleToolbar).setNavigationOnClickListener { finish() }
        grid = findViewById(R.id.staleGrid)
        emptyState = findViewById(R.id.staleEmpty)
        summary = findViewById(R.id.staleSummary)
        selectionView = findViewById(R.id.staleSelection)
        selectAllButton = findViewById(R.id.btnStaleSelectAll)
        exportButton = findViewById(R.id.btnStaleExport)
        deleteButton = findViewById(R.id.btnStaleDelete)

        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        findViewById<MaterialButtonToggleGroup>(R.id.staleWindow).apply {
            check(R.id.windowNever)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                window = when (checkedId) {
                    R.id.window90 -> Window.D90
                    R.id.window180 -> Window.D180
                    R.id.window365 -> Window.D365
                    else -> Window.NEVER
                }
                render()
            }
        }
        selectAllButton.setOnClickListener { toggleAllVisible() }
        exportButton.setOnClickListener { askExport() }
        deleteButton.setOnClickListener { askDelete() }
    }

    /** 回到这一页就重读一份：别处可能删过表情，张数得跟着变。 */
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { viewModel.getAllEmojisOnce() }
            render()
        }
    }

    /**
     * 当前范围里的表情，最该清理的排在最前：从未发过的第一档，其余按最后使用从早到晚；
     * 同一档里新入库的在前（跟主页一个方向，找起来不别扭）。排序没变，谁进这一档由
     * [StaleRule.matches] 说了算 —— 从未发过的按「入库多久」比阈值，所以刚导入的表情不会出现
     * 在 90 / 180 / 365 这三档里（用户 m11937）。
     */
    private fun visibleItems(): List<EmojiEntity> {
        val now = System.currentTimeMillis()
        return all.filter { emoji ->
            StaleRule.matches(emoji.lastUsedTime, emoji.createTime, window.days, now)
        }.sortedWith(
            compareBy<EmojiEntity> { if (it.lastUsedTime <= 0L) 0 else 1 }
                .thenBy { it.lastUsedTime }
                .thenByDescending { it.createTime }
        )
    }

    private fun render() {
        val items = visibleItems()
        // 换范围之后勾选里可能留着这一屏看不见的 id —— 那等于「删了用户没看着的东西」，一律摘掉
        selected.retainAll(items.map { it.id }.toSet())
        adapter.submit(items)
        summary.text = getString(
            R.string.stale_summary_format,
            items.size,
            items.count { it.lastUsedTime <= 0L }
        )
        emptyState.setText(
            if (window == Window.NEVER) R.string.stale_empty_never else R.string.stale_empty_window
        )
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        renderSelection()
    }

    /** 只更新底下那行字和三个按钮：勾选状态画在格子上，由调用方决定怎么重画。 */
    private fun renderSelection() {
        val items = adapter.current()
        selectionView.text = if (selected.isEmpty()) {
            getString(R.string.stale_selection_hint)
        } else {
            getString(R.string.stale_selection_format, selected.size)
        }
        val allChosen = items.isNotEmpty() && items.all { selected.contains(it.id) }
        selectAllButton.setText(if (allChosen) R.string.stale_deselect_all else R.string.stale_select_all)
        selectAllButton.isEnabled = items.isNotEmpty()
        exportButton.isEnabled = selected.isNotEmpty()
        deleteButton.isEnabled = selected.isNotEmpty()
    }

    /** 点一下勾上、再点一下摘掉；只重画这一格。 */
    private fun toggle(emoji: EmojiEntity) {
        if (!selected.add(emoji.id)) selected.remove(emoji.id)
        renderSelection()
        val index = adapter.current().indexOfFirst { it.id == emoji.id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    /** 全选 / 取消全选：只作用在这一屏看得见的那些，看不见的本来也勾不上。 */
    private fun toggleAllVisible() {
        val items = adapter.current()
        if (items.isEmpty()) return
        if (items.all { selected.contains(it.id) }) {
            selected.removeAll(items.map { it.id }.toSet())
        } else {
            selected.addAll(items.map { it.id })
        }
        adapter.submit(items)
        renderSelection()
    }

    private fun askExport() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.stale_selection_hint, Toast.LENGTH_SHORT).show()
            return
        }
        exportPicker.launch(EmojiArchive.suggestedName())
    }

    private fun runExport(target: Uri) {
        val ids = selected.toList()
        val dialog = BusyDialog.showProgress(
            this,
            R.string.archive_export_progress_title,
            getString(R.string.archive_export_progress, 0, 0)
        )
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val out = runCatching { contentResolver.openOutputStream(target, "w") }.getOrNull()
                if (out == null) {
                    null
                } else {
                    out.use { stream ->
                        viewModel.exportEmojis(ids, stream) { done, total ->
                            BusyDialog.update(
                                this@StaleEmojiActivity,
                                dialog,
                                done,
                                total,
                                getString(R.string.archive_export_progress, done, total)
                            )
                        }
                    }
                }
            }
            BusyDialog.dismiss(this@StaleEmojiActivity, dialog)
            if (report == null) {
                Toast.makeText(
                    this@StaleEmojiActivity,
                    getString(R.string.archive_err_generic),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val text = when {
                report.error != null -> errorText(report.error)
                report.emojis == 0 && report.failed == 0 -> getString(R.string.archive_export_empty)
                else -> buildString {
                    append(
                        getString(
                            R.string.stale_export_done,
                            report.emojis,
                            Formatter.formatShortFileSize(this@StaleEmojiActivity, report.bytes)
                        )
                    )
                    if (report.failed > 0) {
                        append(getString(R.string.archive_export_done_missing, report.failed))
                    }
                    if (report.ignored > 0) {
                        append(getString(R.string.archive_export_done_ignored, report.ignored))
                    }
                }
            }
            MaterialAlertDialogBuilder(this@StaleEmojiActivity)
                .setTitle(R.string.settings_export_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 删除先说清删几张、腾多少地方，再动手。 */
    private fun askDelete() {
        val chosen = adapter.current().filter { selected.contains(it.id) }
        if (chosen.isEmpty()) {
            Toast.makeText(this, R.string.stale_selection_hint, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { chosen.sumOf { sizeOf(it.filePath) } }
            MaterialAlertDialogBuilder(this@StaleEmojiActivity)
                .setTitle(R.string.stale_delete_confirm_title)
                .setMessage(
                    getString(
                        R.string.stale_delete_confirm_message,
                        chosen.size,
                        Formatter.formatShortFileSize(this@StaleEmojiActivity, bytes)
                    )
                )
                .setPositiveButton(R.string.stale_delete) { _, _ ->
                    runDelete(chosen.map { it.id }, bytes)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    /** 删除走主页那一套（先删文件、确认删掉才删记录），进度与「删不掉的还留着」都照实说。 */
    private fun runDelete(ids: List<Long>, bytes: Long) {
        val dialog = BusyDialog.showProgress(
            this,
            R.string.delete_progress_title,
            getString(R.string.delete_progress, 1, ids.size)
        )
        lifecycleScope.launch {
            val kept = withContext(Dispatchers.IO) {
                viewModel.deleteEmojisForTool(ids) { done, total ->
                    BusyDialog.update(
                        this@StaleEmojiActivity,
                        dialog,
                        done,
                        total,
                        getString(R.string.delete_progress, done, total)
                    )
                }
            }
            BusyDialog.dismiss(this@StaleEmojiActivity, dialog)
            selected.removeAll(ids.toSet())
            load()
            val text = buildString {
                append(
                    getString(
                        R.string.stale_delete_done,
                        ids.size - kept,
                        Formatter.formatShortFileSize(this@StaleEmojiActivity, bytes)
                    )
                )
                if (kept > 0) append(getString(R.string.stale_delete_kept, kept))
            }
            Toast.makeText(this@StaleEmojiActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 「多久没发过」：从未发过的直说，再补一句入库多久 —— 它在 90 / 180 / 365 档里出现靠的就是这个
     * 天数，光写「从未发过」看不出它凭什么现在才冒出来（用户 m11937）；其余按天 / 月 / 年取整，不摆
     * 精确日期。天数从 [StaleRule.since] 算起，跟筛选用的是同一个起点，不会出现「列在这里但格底写着
     * 3 天」这种对不上的情况。
     */
    private fun ageTextOf(emoji: EmojiEntity): String {
        val age = System.currentTimeMillis() - StaleRule.since(emoji.lastUsedTime, emoji.createTime)
        val days = (age / StaleRule.DAY_MS).coerceAtLeast(0L)
        if (StaleRule.neverUsed(emoji.lastUsedTime)) {
            if (days < 1L) return getString(R.string.stale_never_used)
            val added = when {
                days < 30L -> getString(R.string.stale_added_days, days.toInt())
                days < 365L -> getString(R.string.stale_added_months, (days / 30L).toInt())
                else -> getString(R.string.stale_added_years, (days / 365L).toInt())
            }
            return getString(R.string.stale_never_used_added, added)
        }
        return when {
            days < 30L -> getString(R.string.stale_age_days, days.toInt())
            days < 365L -> getString(R.string.stale_age_months, (days / 30L).toInt())
            else -> getString(R.string.stale_age_years, (days / 365L).toInt())
        }
    }

    private fun sizeOf(path: String): Long =
        runCatching { File(path).length() }.getOrDefault(0L)

    private fun errorText(error: EmojiArchive.Error): String = when (error) {
        EmojiArchive.Error.NOT_ARCHIVE -> getString(R.string.archive_err_not_archive)
        EmojiArchive.Error.BROKEN -> getString(R.string.archive_err_broken)
        EmojiArchive.Error.UNREADABLE -> getString(R.string.archive_err_unreadable)
        EmojiArchive.Error.IO -> getString(R.string.archive_err_generic)
    }
}