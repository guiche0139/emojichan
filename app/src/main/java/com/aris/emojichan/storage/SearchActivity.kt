package com.aris.emojichan.storage

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.EmojiDetailActivity
import com.aris.emojichan.R
import com.aris.emojichan.SimilarPrefs
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.data.ImageFeatureEntity
import com.aris.emojichan.sender.SendLog
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.util.FileHash
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 重复与相似检索页（v0.1.411，用户 m08893：两件事放同一页，不再先选查哪一类）。
 *
 * 一页两段：上面「内容完全相同」（字节级的是非题），下面「画面相近」（感知哈希的程度题）。
 * 一次比对同时算两种指纹，各算各该算的那些：内容摘要只对体积相同的那些算（不读白读的盘），
 * 感知哈希只对没归进「完全相同」的那些算 —— 已经确定一模一样的图没必要再比画面，
 * 同一张图因此只出现在一段里。
 *
 * 中途可以停：停下来的话已经算出来的指纹照样写回 image_features，下次进来少读一批文件
 * （[FeatureCache] 按体积 + 修改时间判断旧值还算不算数）。
 *
 * 进页面不再自动比一遍（v0.2.007，用户 m10764）：全库比一次要读一遍文件，而用户可能只是进来看
 * 上一轮的结果。所以中间摆一颗「开始检测」，查不查由用户说了算；检测完那颗按钮也还在，随时能重来。
 * 改了底线、改了忽略名单仍然当场重排 —— 那两件事只动内存里的哈希，不读盘。
 *
 * 删除的两种口子也各归各的：完全相同的组里「该留哪张」有默认答案，按钮一按删掉其余；
 * 画面相近的组没有对错，点缩略图选中要删的，底部那一条按一下才删。
 *
 * 「画面相近」还有第三种处理（v0.2.006，用户 m10497）：有些组本来就不是同一个表情，这是主观判断，
 * 调底线也调不出来。所以每组底下多一颗「忽略这组」—— 把这一组里每一对都记进忽略名单
 * （见 [SimilarIgnore]），之后无论底线怎么变都不再同组；名单在顶部「忽略列表」里查看与恢复
 * —— v0.2.008（用户 m10953）起那是独立一页（[SimilarIgnoreActivity]），不再是挤在对话框里的 40% 屏高。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var adapter: SearchAdapter
    private lateinit var summary: TextView
    private lateinit var summaryRow: View
    private lateinit var ignoredButton: MaterialButton
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var selectionBar: View
    private lateinit var selectionText: TextView

    /** 还没检测过时中间那块引导（说明 + 「开始检测」）。 */
    private lateinit var startState: View

    /** 这一轮比对了多少张（含没找到伴的），汇总要用。 */
    private var scanned = 0
    private var scanJob: Job? = null

    /** 重排分组那一趟（改底线 / 改忽略名单），只跑最后一次。 */
    private var regroupJob: Job? = null

    private var items: List<DuplicateFinder.Item> = emptyList()
    private var itemById: Map<Long, DuplicateFinder.Item> = emptyMap()

    /** 已经归进「完全相同」的那些：不再参与画面比对，也就不在「相近」那一段里。 */
    private var duplicateIds: MutableSet<Long> = mutableSetOf()

    /** 参与画面比对的那一批（全库减掉「完全相同」的），改了底线时拿它重新分组。 */
    private var pool: List<DuplicateFinder.Item> = emptyList()

    /** 这一轮算出来的感知哈希，留在内存里：改了底线只重分组，不再读一次盘。 */
    private val hashes = HashMap<Long, Long>()

    /** 画面相近的底线（差几位以内算相近），进页面时从 [SimilarPrefs] 读一次。 */
    private var threshold = SimilarFinder.DEFAULT

    /** 「不像同一张」的记录：一次「忽略这组」记一条（见 [SimilarIgnore]）。进页面读一次，扫完剪一次。 */
    private var ignored: Set<String> = emptySet()
        set(value) {
            field = value
            // 判定用的查表版跟着一起换：分组时问的是「这张能跟谁同组」，不再逐对拼键（v0.2.007）
            banned = SimilarIgnore.bannedOf(value)
        }

    /** [ignored] 的查表版，[SimilarFinder] 收的就是它。 */
    private var banned: Map<Long, Set<Long>> = emptyMap()

    /** 判定用的一次查询：没有禁忌的图返回空集，分组时连组里逐个成员的比对都跳过。 */
    private fun bannedOf(id: Long): Set<Long> = banned[id] ?: emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_search)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]
        threshold = SimilarPrefs.threshold(this)

        findViewById<MaterialToolbar>(R.id.searchToolbar).setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.searchSummary)
        summaryRow = findViewById(R.id.searchSummaryRow)
        ignoredButton = findViewById(R.id.btnSearchIgnored)
        list = findViewById(R.id.searchList)
        emptyState = findViewById(R.id.searchEmptyState)
        selectionBar = findViewById(R.id.searchSelectionBar)
        selectionText = findViewById(R.id.searchSelection)

        adapter = SearchAdapter()
        adapter.onDeleteDuplicateGroup = { group, redundant -> askDeleteDuplicates(group, redundant) }
        adapter.onOpen = { item ->
            startActivity(
                Intent(this, EmojiDetailActivity::class.java)
                    .putExtra(EmojiDetailActivity.EXTRA_EMOJI_ID, item.id)
            )
        }
        adapter.onSelectionChanged = { renderSelection() }
        adapter.onIgnoreGroup = { group -> askIgnoreGroup(group) }
        adapter.threshold = threshold
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnSearchDelete).setOnClickListener { askDeleteSelected() }
        ignored = SimilarPrefs.ignoredRecords(this)
        ignoredButton.setOnClickListener { startActivity(Intent(this, SimilarIgnoreActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnSearchScan).setOnClickListener { scan() }
        // 检测过之后引导那块就不见了，再想重来走汇总行上这颗
        findViewById<MaterialButton>(R.id.btnSearchRescan).setOnClickListener { scan() }
        startState = findViewById(R.id.searchStartState)
        renderSelection()
        renderIgnoredButton()
        renderEmpty()
    }

    /**
     * 回到这一页不再自动比一遍（v0.2.007，用户 m10764）：比不比由中间那颗按钮说了算。
     * 这里只处理两件不读盘的事 —— 底线和忽略名单可能在别处改过，对不上就拿内存里的哈希重排。
     */
    override fun onResume() {
        super.onResume()
        if (scanJob?.isActive == true) return
        // 用户可能刚去高级设置改了底线：哈希都在内存里，重排一次分组就好，不用重读盘。
        val current = SimilarPrefs.threshold(this)
        if (current != threshold) {
            threshold = current
            regroup()
        }
        // 忽略名单也可能变过（从详情页回来时删掉过几张）：重新读一份，对不上就照它重分组
        val currentIgnored = SimilarPrefs.ignoredRecords(this)
        if (currentIgnored != ignored) {
            ignored = currentIgnored
            regroup()
            renderIgnoredButton()
        }
    }

    private fun scan() {
        // 懒启动：先把 job 记下来再跑，取消按钮才有东西可取消。
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            // 按钮一出手就先收起来：这一轮跑完之前不该有第二个入口
            startState.visibility = View.GONE
            val loaded = viewModel.getAllEmojisOnce().map { DuplicateFinder.itemOf(it) }
            val cached = viewModel.allImageFeatures().associateBy { it.emojiId }
            val fresh = HashMap<Long, ImageFeatureEntity>()
            val collected = HashMap<Long, Long>()
            val self = coroutineContext[Job]

            // 这一步可能读上千个文件，两条进度合并成一根条：先比文件、再比画面（用户 m09940）
            val dialog = BusyDialog.showProgress(
                this@SearchActivity,
                R.string.search_scan_title,
                getString(R.string.search_scan_prepare),
                cancelable = true
            )
            BusyDialog.setOnCancel(dialog) { self?.cancel() }

            val result = try {
                val duplicates = DuplicateFinder.find(
                    items = loaded,
                    hashOf = { item ->
                        // 刚算出来的行也算「旧行」：下一段要拿它保住这一段的摘要。
                        val base = fresh[item.id] ?: cached[item.id]
                        // 旧行还算不算数由 FeatureCache 判断：体积 + 修改时间都对上才复用摘要。
                        FeatureCache.sha256Of(base, item)
                            ?: withContext(Dispatchers.IO) { FileHash.sha256(File(item.path)) }
                                ?.also { hash ->
                                    fresh[item.id] = FeatureCache.merged(item, base, sha256 = hash)
                                }
                    },
                    onProgress = { done, total ->
                        BusyDialog.update(
                            this@SearchActivity,
                            dialog,
                            done,
                            total,
                            getString(R.string.search_scan_files, done, total)
                        )
                    }
                )

                // 已经归进「完全相同」的不再比画面：同一张图只出现在一段里。
                val duplicateIds = duplicates.flatMap { it.members }.map { it.id }.toSet()
                val pool = loaded.filterNot { it.id in duplicateIds }
                val similar = SimilarFinder.find(
                    items = pool,
                    threshold = threshold,
                    banned = ::bannedOf,
                    hashOf = { item ->
                        val base = fresh[item.id] ?: cached[item.id]
                        val hash = FeatureCache.dhashOf(base, item)
                            ?: withContext(Dispatchers.IO) { ImageDHash.of(File(item.path)) }
                                ?.also { computed ->
                                    fresh[item.id] = FeatureCache.merged(item, base, dhash = computed)
                                }
                        // 算出来的哈希顺手收进这一轮的内存表，切判定范围时直接用它。
                        if (hash != null) collected[item.id] = hash
                        hash
                    },
                    onProgress = { done, total ->
                        BusyDialog.update(
                            this@SearchActivity,
                            dialog,
                            done,
                            total,
                            getString(R.string.search_scan_frames, done, total)
                        )
                    }
                )
                Scan(duplicates, similar, duplicateIds, pool, loaded)
            } catch (e: CancellationException) {
                // 取消只是停这一轮：已经算出来的指纹照样写回去，下次进来少读一批文件。
                // 协程已取消，写库这一步必须放进 NonCancellable，否则挂起调用会立刻再抛一次。
                if (fresh.isNotEmpty()) {
                    withContext(NonCancellable) { viewModel.saveImageFeatures(fresh.values.toList()) }
                }
                BusyDialog.dismiss(this@SearchActivity, dialog)
                Toast.makeText(
                    this@SearchActivity,
                    R.string.search_scan_stopped,
                    Toast.LENGTH_SHORT
                ).show()
                // 停在这一轮：上一轮的结果（如果有）照旧留着，没有结果就把「开始检测」还回去
                renderEmpty()
                return@launch
            } catch (e: Exception) {
                // 这一段要读文件、解码图片：单张坏图、外置存储掉线、解码 OOM 都会抛。
                // 以前这里只接取消，别的异常直接掀翻整个协程 —— 界面卡死在进度框上（emc-2-032）。
                SendLog.e("查重", "比对失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
                if (fresh.isNotEmpty()) {
                    withContext(NonCancellable) { viewModel.saveImageFeatures(fresh.values.toList()) }
                }
                BusyDialog.dismiss(this@SearchActivity, dialog)
                Toast.makeText(this@SearchActivity, R.string.search_scan_failed, Toast.LENGTH_SHORT).show()
                renderEmpty()
                return@launch
            }
            BusyDialog.dismiss(this@SearchActivity, dialog)

            // 算出来的指纹先存住 —— 用户可能这一轮不删，下次进来就不必重算了。
            if (fresh.isNotEmpty()) viewModel.saveImageFeatures(fresh.values.toList())

            items = result.items
            itemById = result.items.associateBy { it.id }
            // 表情删掉之后它牵涉的记录就收窄了，顺手剪一次 —— 名单不该越攒越多
            val alive = result.items.map { it.id }.toSet()
            val kept = SimilarIgnore.keepAlive(ignored, alive)
            if (kept != ignored) {
                ignored = kept
                SimilarPrefs.setIgnoredRecords(this@SearchActivity, kept)
            }
            duplicateIds = result.duplicateIds.toMutableSet()
            pool = result.pool
            hashes.clear()
            hashes.putAll(collected)
            scanned = result.items.size

            adapter.threshold = threshold
            adapter.submitDuplicate(result.duplicates)
            adapter.submitSimilar(result.similar, hashes)
            renderSelection()
            renderIgnoredButton()
            summaryRow.visibility = View.VISIBLE
            list.visibility = View.VISIBLE
            renderSummary()
            renderEmpty()
        }
        scanJob = job
        job.start()
    }

    /** 一轮比对的结果：两种分组各一段，攒齐了再交给界面。 */
    private data class Scan(
        val duplicates: List<DuplicateFinder.Group>,
        val similar: List<SimilarFinder.Group>,
        val duplicateIds: Set<Long>,
        val pool: List<DuplicateFinder.Item>,
        val items: List<DuplicateFinder.Item>
    )

    /**
     * 改了底线或忽略名单：不需要读盘，哈希都在内存里，重排一次分组就好。
     *
     * 排一遍要在几百万个数字上比一遍，搁主线程就是一次看得见的卡顿（用户 m10698），
     * 所以丢到后台线程算，算完回主线程交给 adapter。
     */
    private fun regroup() {
        if (scanned == 0) return
        regroupJob?.cancel()
        regroupJob = lifecycleScope.launch {
            val groups = withContext(Dispatchers.Default) {
                SimilarFinder.group(pool, hashes, threshold, ::bannedOf)
            }
            adapter.threshold = threshold
            adapter.submitSimilar(groups, hashes)
            renderSelection()
            renderSummary()
            renderEmpty()
        }
    }

    /** 顶部那行汇总：两段各找到几组，都写在一行里。 */
    private fun renderSummary() {
        summary.text = getString(
            R.string.search_summary,
            scanned,
            adapter.duplicateCount,
            adapter.similarCount
        )
    }

    /**
     * 中间那块空态有两种：还没检测过（摆一颗「开始检测」，见 [scan]），
     * 以及检测完了但库里一张都没有（说一句「库里还没有表情」）。
     */
    private fun renderEmpty() {
        startState.visibility = if (scanned == 0) View.VISIBLE else View.GONE
        emptyState.visibility = if (scanned > 0 && items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 底部那条只在真的选中了东西之后才出现 —— 平时不占地方，也不用解释它是干什么的。 */
    private fun renderSelection() {
        val count = adapter.selectedCount
        if (count == 0) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.visibility = View.VISIBLE
        selectionText.text = getString(
            R.string.similar_selected,
            count,
            StorageUsage.formatBytes(this, adapter.selectedBytes)
        )
    }

    /** 「忽略列表（N）」那颗按钮：数目写在按钮上，用户才知道里面有没有东西。点它去独立那一页。 */
    private fun renderIgnoredButton() {
        ignoredButton.text = getString(R.string.similar_ignore_list, ignored.size)
    }

    /** 用户觉得这一组不像同一个表情：先说清它不删图、也不是永久拉黑，再动手。 */
    private fun askIgnoreGroup(group: SimilarFinder.Group) {
        if (group.members.size < 2) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.similar_ignore_title, group.members.size))
            .setMessage(getString(R.string.similar_ignore_message, group.members.size))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.similar_ignore_ok) { _, _ -> runIgnoreGroup(group) }
            .show()
    }

    /**
     * 把这一组记成一条忽略记录（见 [SimilarIgnore]），当场重分组 —— 用户点完就该看见它消失。
     *
     * 记「这一次点中的是哪几张」而不是记「这一组」：换一档底线、库里增删一张，分组结果就会重新洗牌，
     * 按组记的话过两天就对不上了。一条记录也不等于「这几张此后永远同组」，只是它们之间不再算相近。
     */
    private fun runIgnoreGroup(group: SimilarFinder.Group) {
        val ids = group.members.map { it.id }
        val record = SimilarIgnore.recordOf(ids) ?: return
        ignored = ignored + record
        SimilarPrefs.setIgnoredRecords(this, ignored)
        regroup()
        renderIgnoredButton()
        Toast.makeText(this, getString(R.string.similar_ignore_done, ids.size * (ids.size - 1) / 2), Toast.LENGTH_SHORT)
            .show()
    }

    private fun askDeleteDuplicates(group: DuplicateFinder.Group, redundant: List<DuplicateFinder.Item>) {
        if (redundant.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.duplicate_delete_title, redundant.size))
            .setMessage(
                getString(
                    R.string.duplicate_delete_message,
                    StorageUsage.formatBytes(this, redundant.sumOf { it.bytes })
                )
            )
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.duplicate_delete_ok) { _, _ -> runDeleteDuplicates(group, redundant) }
            .show()
    }

    private fun runDeleteDuplicates(group: DuplicateFinder.Group, redundant: List<DuplicateFinder.Item>) {
        val dialog = BusyDialog.showProgress(
            this,
            R.string.delete_progress_title,
            getString(R.string.delete_progress, 1, redundant.size),
            hint = getString(R.string.duplicate_delete_working, redundant.size)
        )
        lifecycleScope.launch {
            try {
                val failed = viewModel.deleteEmojisForTool(redundant.map { it.id }) { done, total ->
                    BusyDialog.update(
                        this@SearchActivity,
                        dialog,
                        done,
                        total,
                        getString(R.string.delete_progress, done, total)
                    )
                }
                BusyDialog.dismiss(this@SearchActivity, dialog)
                // 只统计真的不在了的那几张 —— 删失败的要如实说没删掉。
                val gone = redundant.filterNot { File(it.path).isFile }
                val message = if (failed > 0) {
                    getString(R.string.duplicate_delete_partial, gone.size, failed)
                } else {
                    getString(
                        R.string.duplicate_delete_done,
                        gone.size,
                        StorageUsage.formatBytes(this@SearchActivity, gone.sumOf { it.bytes })
                    )
                }
                Toast.makeText(this@SearchActivity, message, Toast.LENGTH_LONG).show()
                // 删掉的从「全库张数」里减掉，汇总才对得上（failed 是文件没删掉、因而留下的张数）
                scanned = (scanned - (redundant.size - failed)).coerceAtLeast(0)
                val goneIds = gone.map { it.id }.toSet()
                items = items.filterNot { it.id in goneIds }
                itemById = items.associateBy { it.id }
                // 留下的那张仍然算「已经归进完全相同」，不再参与画面比对
                duplicateIds.removeAll(goneIds)
                adapter.removeDuplicateGroup(group.sha256)
                renderSummary()
                renderEmpty()
            } catch (e: Exception) {
                BusyDialog.dismiss(this@SearchActivity, dialog)
                Toast.makeText(
                    this@SearchActivity,
                    getString(
                        R.string.duplicate_delete_failed,
                        e.message ?: getString(R.string.msg_unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun askDeleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.similar_delete_title, ids.size))
            .setMessage(
                getString(
                    R.string.similar_delete_message,
                    StorageUsage.formatBytes(this, adapter.selectedBytes)
                )
            )
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.similar_delete_ok) { _, _ -> runDeleteSelected(ids) }
            .show()
    }

    private fun runDeleteSelected(ids: Set<Long>) {
        val chosen = ids.mapNotNull { itemById[it] }
        val dialog = BusyDialog.showProgress(
            this,
            R.string.delete_progress_title,
            getString(R.string.delete_progress, 1, chosen.size),
            hint = getString(R.string.similar_delete_working, chosen.size)
        )
        lifecycleScope.launch {
            try {
                val failed = viewModel.deleteEmojisForTool(ids.toList()) { done, total ->
                    BusyDialog.update(
                        this@SearchActivity,
                        dialog,
                        done,
                        total,
                        getString(R.string.delete_progress, done, total)
                    )
                }
                BusyDialog.dismiss(this@SearchActivity, dialog)
                // 只统计真的不在了的那几张 —— 删失败的要如实说没删掉。
                val gone = chosen.filterNot { File(it.path).isFile }
                val message = if (failed > 0) {
                    getString(R.string.similar_delete_partial, gone.size, failed)
                } else {
                    getString(
                        R.string.similar_delete_done,
                        gone.size,
                        StorageUsage.formatBytes(this@SearchActivity, gone.sumOf { it.bytes })
                    )
                }
                Toast.makeText(this@SearchActivity, message, Toast.LENGTH_LONG).show()
                // 删掉的从「全库张数」里减掉，汇总才对得上（failed 是文件没删掉、因而留下的张数）
                scanned = (scanned - (chosen.size - failed)).coerceAtLeast(0)
                items = items.filterNot { it.id in ids }
                itemById = items.associateBy { it.id }
                pool = pool.filterNot { it.id in ids }
                ids.forEach { hashes.remove(it) }
                adapter.removeIds(ids)
                renderSelection()
                renderSummary()
                renderEmpty()
            } catch (e: Exception) {
                BusyDialog.dismiss(this@SearchActivity, dialog)
                Toast.makeText(
                    this@SearchActivity,
                    getString(
                        R.string.similar_delete_failed,
                        e.message ?: getString(R.string.msg_unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
