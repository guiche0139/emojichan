package com.aris.emojichan.storage

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.aris.emojichan.R
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储管理页：上面是概览，下面是大表情压缩与重复检索两个入口。
 *
 * v0.1.406 时压缩列表直接铺在这一页上，图一多就得靠 RecyclerView 撑着；
 * v0.1.407 起压缩和重复检索各自成页（三级页），这一页只剩下「看一眼占用 + 选个工具」；
 * v0.1.410 起两个检索入口合成一个（用户 m08845）；v0.1.411 起两类结果同页显示（用户 m08893），
 * 点开直接比对，不用再先选查哪一类。
 *
 * 统计按磁盘实际占用现算：进页面算一次，从三级页回来再算一次，中间不跟数据库的变化。
 */
class StorageActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var loading: View
    private lateinit var content: View
    private lateinit var cacheRow: View

    /** 最近一次统计结果：清缓存前要拿里面的 cacheBytes 写确认文案。 */
    private var lastSnapshot: StorageSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_storage)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        findViewById<MaterialToolbar>(R.id.storageToolbar).setNavigationOnClickListener { finish() }
        loading = findViewById(R.id.storageLoading)
        content = findViewById(R.id.storageContent)

        bindMenuRow(findViewById(R.id.rowCompress), R.string.storage_tool_compress_title, R.string.storage_tool_compress_sub) {
            startActivity(Intent(this, CompressActivity::class.java))
        }
        bindMenuRow(findViewById(R.id.rowSearch), R.string.storage_tool_search_title, R.string.storage_tool_search_sub) {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        cacheRow = findViewById(R.id.rowClearCache)
        bindMenuRow(cacheRow, R.string.storage_clear_cache_title, R.string.storage_clear_cache_sub) {
            askClearCache()
        }

        loadStats()
    }

    /** 从三级页回来时体积可能已经变了（压缩替换过），重新算一遍。 */
    override fun onResume() {
        super.onResume()
        if (content.visibility == View.VISIBLE) loadStats()
    }

    private fun bindMenuRow(row: View, titleRes: Int, subRes: Int, onClick: () -> Unit) {
        row.findViewById<TextView>(R.id.menuTitle).setText(titleRes)
        row.findViewById<TextView>(R.id.menuSub).setText(subRes)
        row.setOnClickListener { onClick() }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                StorageUsage.load(this@StorageActivity, viewModel.getAllEmojisOnce())
            }
            render(snapshot)
            loading.visibility = View.GONE
            content.visibility = View.VISIBLE
        }
    }

    private fun render(snapshot: StorageSnapshot) {
        lastSnapshot = snapshot
        cacheRow.findViewById<TextView>(R.id.menuSub).text = if (snapshot.cacheBytes > 0L) {
            getString(
                R.string.storage_clear_cache_sub_size,
                StorageUsage.formatBytes(this, snapshot.cacheBytes)
            )
        } else {
            getString(R.string.storage_clear_cache_empty)
        }

        findViewById<TextView>(R.id.overviewTotal).text = getString(
            R.string.storage_overview_total,
            snapshot.files.size,
            StorageUsage.formatBytes(this, snapshot.libraryBytes)
        )

        val colors = pieColors()
        val pie = findViewById<StoragePieView>(R.id.storagePie)
        pie.setSlices(
            snapshot.byFormat.mapIndexed { index, usage ->
                StoragePieView.Slice(usage.format, usage.bytes, colors[index % colors.size])
            },
            StorageUsage.formatBytes(this, snapshot.libraryBytes)
        )

        val legend = findViewById<LinearLayout>(R.id.storageLegend)
        legend.removeAllViews()
        val inflater = LayoutInflater.from(this)
        snapshot.byFormat.forEachIndexed { index, usage ->
            val row = inflater.inflate(R.layout.item_storage_legend, legend, false)
            row.findViewById<View>(R.id.legendDot).backgroundTintList =
                ColorStateList.valueOf(colors[index % colors.size])
            row.findViewById<TextView>(R.id.legendLabel).text = usage.format
            row.findViewById<TextView>(R.id.legendMeta).text = getString(
                R.string.storage_legend_meta,
                usage.count,
                StorageUsage.formatBytes(this, usage.bytes)
            )
            row.findViewById<TextView>(R.id.legendPercent).text = getString(
                R.string.storage_legend_percent,
                StorageUsage.percentOf(usage.bytes, snapshot.libraryBytes)
            )
            legend.addView(row)
        }

        val missing = findViewById<TextView>(R.id.storageMissing)
        if (snapshot.missingCount > 0) {
            missing.text = getString(R.string.storage_missing_note, snapshot.missingCount)
            missing.visibility = View.VISIBLE
        } else {
            missing.visibility = View.GONE
        }

        findViewById<TextView>(R.id.otherDatabaseValue).text =
            StorageUsage.formatBytes(this, snapshot.databaseBytes)
        findViewById<TextView>(R.id.otherCacheValue).text =
            StorageUsage.formatBytes(this, snapshot.cacheBytes)
        findViewById<TextView>(R.id.storageTotal).text =
            getString(R.string.storage_total, StorageUsage.formatBytes(this, snapshot.totalBytes))
    }

    /**
     * 清缓存：先问一句。删掉的都是能再生成的临时文件，但清完第一次打开表情要重新解码，
     * 快的时候没感觉、慢的时候卡一下，先说清楚再动手。
     */
    private fun askClearCache() {
        val bytes = lastSnapshot?.cacheBytes ?: 0L
        if (bytes <= 0L) {
            Toast.makeText(this, R.string.storage_clear_cache_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_clear_cache_confirm_title)
            .setMessage(
                getString(
                    R.string.storage_clear_cache_confirm_message,
                    StorageUsage.formatBytes(this, bytes)
                )
            )
            .setPositiveButton(R.string.storage_clear_cache_title) { _, _ -> runClearCache() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun runClearCache() {
        lifecycleScope.launch {
            val dialog = BusyDialog.show(
                this@StorageActivity,
                R.string.storage_clear_cache_title,
                getString(R.string.storage_clear_cache_working)
            )
            val freed = CacheCleaner.clear(this@StorageActivity)
            BusyDialog.dismiss(this@StorageActivity, dialog)

            val text = if (freed > 0L) {
                getString(
                    R.string.storage_clear_cache_done,
                    StorageUsage.formatBytes(this@StorageActivity, freed)
                )
            } else {
                getString(R.string.storage_clear_cache_empty)
            }
            Toast.makeText(this@StorageActivity, text, Toast.LENGTH_SHORT).show()
            loadStats()
        }
    }

    /** 饼图配色：浅色 / 深色各一套，都用主题里那几个色名，跟着日夜模式走。 */
    private fun pieColors(): IntArray = intArrayOf(
        ContextCompat.getColor(this, R.color.pie_1),
        ContextCompat.getColor(this, R.color.pie_2),
        ContextCompat.getColor(this, R.color.pie_3),
        ContextCompat.getColor(this, R.color.pie_4),
        ContextCompat.getColor(this, R.color.pie_5),
        ContextCompat.getColor(this, R.color.pie_6),
        ContextCompat.getColor(this, R.color.pie_other)
    )
}
