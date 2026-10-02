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
 * 删除的两种口子也各归各的：完全相同的组里「该留哪张」有默认答案，按钮一按删掉其余；
 * 画面相近的组没有对错，点缩略图选中要删的，底部那一条按一下才删。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var adapter: SearchAdapter
    private lateinit var summary: TextView
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var selectionBar: View
    private lateinit var selectionText: TextView

    /** 这一轮比对了多少张（含没找到伴的），汇总要用。 */
    private var scanned = 0
    private var scanJob: Job? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_search)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]
        threshold = SimilarPrefs.threshold(this)

        findViewById<MaterialToolbar>(R.id.searchToolbar).setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.searchSummary)
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
        adapter.threshold = threshold
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnSearchDelete).setOnClickListener { askDeleteSelected() }
        renderSelection()
    }

    /** 每次回到这一页都重新比一遍：删过图、导入过新图，结果就不一样了。缓存命中时很便宜。 */
    override fun onResume() {
        super.onResume()
        if (scanJob?.isActive == true) return
        // 用户可能刚去高级设置改了底线：哈希都在内存里，重排一次分组就好，不用重读盘。
        val current = SimilarPrefs.threshold(this)
        if (current != threshold) {
            threshold = current
            regroup()
        }
        scan()
    }

    private fun scan() {
        // 懒启动：先把 job 记下来再跑，取消按钮才有东西可取消。
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val loaded = viewModel.getAllEmojisOnce().map { DuplicateFinder.itemOf(it) }
            val cached = viewModel.allImageFeatures().associateBy { it.emojiId }
            val fresh = HashMap<Long, ImageFeatureEntity>()
            val collected = HashMap<Long, Long>()
            val self = coroutineContext[Job]

            val dialog = BusyDialog.show(
                this@SearchActivity,
                R.string.search_scan_title,
                getString(R.string.search_scan_prepare),
                cancelable = true
            )
            dialog.setOnCancelListener { self?.cancel() }

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
                return@launch
            }
            BusyDialog.dismiss(this@SearchActivity, dialog)

            // 算出来的指纹先存住 —— 用户可能这一轮不删，下次进来就不必重算了。
            if (fresh.isNotEmpty()) viewModel.saveImageFeatures(fresh.values.toList())

            items = result.items
            itemById = result.items.associateBy { it.id }
            duplicateIds = result.duplicateIds.toMutableSet()
            pool = result.pool
            hashes.clear()
            hashes.putAll(collected)
            scanned = result.items.size

            adapter.threshold = threshold
            adapter.submitDuplicate(result.duplicates)
            adapter.submitSimilar(result.similar, hashes)
            renderSelection()
            summary.visibility = View.VISIBLE
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

    /** 改了底线：不需要读盘，哈希都在内存里，重算一次分组就好。 */
    private fun regroup() {
        if (scanned == 0) return
        val groups = SimilarFinder.group(pool, hashes, threshold)
        adapter.threshold = threshold
        adapter.submitSimilar(groups, hashes)
        renderSelection()
        renderSummary()
        renderEmpty()
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

    /** 库里一张都没有时，整页只在中间说一句。 */
    private fun renderEmpty() {
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
        val dialog = BusyDialog.show(
            this,
            R.string.search_section_duplicate,
            getString(R.string.duplicate_delete_working, redundant.size)
        )
        lifecycleScope.launch {
            try {
                val failed = viewModel.deleteEmojisForTool(redundant.map { it.id })
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
        val dialog = BusyDialog.show(
            this,
            R.string.search_section_similar,
            getString(R.string.similar_delete_working, chosen.size)
        )
        lifecycleScope.launch {
            try {
                val failed = viewModel.deleteEmojisForTool(ids.toList())
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
