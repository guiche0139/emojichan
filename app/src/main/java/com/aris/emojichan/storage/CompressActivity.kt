package com.aris.emojichan.storage

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.CompressPrefs
import com.aris.emojichan.R
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.sender.SendLog
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 大表情压缩页（三级页）。
 *
 * 库里所有图按体积分三段铺成网格，勾选之后才动手。压出来的新文件先落在缓存目录，
 * 用户到预览页看过、点过「替换」才回写数据库并删原图 —— 中间任何一步退出，原图都还在。
 *
 * 每张图压之前都算一遍磁盘实际字节，压完只有确实更小才算数，所以这里给出的数字
 * 就是用户点下去会省下的量。
 */
class CompressActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var adapter: CompressAdapter
    private lateinit var summary: TextView
    private lateinit var btnCompress: MaterialButton
    private lateinit var btnExportOriginal: MaterialButton
    private lateinit var btnSelectAll: TextView
    private lateinit var btnClear: TextView
    private lateinit var empty: TextView
    private lateinit var tempDir: File

    /** 按当前设置被藏起来的动图张数 —— 页面要如实说明为什么少了几张。 */
    private var hiddenAnimated = 0

    private val zipPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_compress)
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        tempDir = File(cacheDir, "compress-preview")
        // 删文件不进主线程：上一次的预览可能留了几十张图，慢盘上够卡一帧（emc-2-032）。
        lifecycleScope.launch(Dispatchers.IO) { cleanTempDir() }

        findViewById<MaterialToolbar>(R.id.compressToolbar).setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.compressSummary)
        empty = findViewById(R.id.compressEmpty)
        btnSelectAll = findViewById(R.id.btnCompressSelectAll)
        btnClear = findViewById(R.id.btnCompressClear)
        btnCompress = findViewById(R.id.btnCompress)
        btnExportOriginal = findViewById(R.id.btnExportOriginal)

        adapter = CompressAdapter()
        adapter.onSelectionChanged = { syncBar() }

        val list = findViewById<RecyclerView>(R.id.compressList)
        list.layoutManager = GridLayoutManager(this, CompressAdapter.SPAN).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = adapter.spanSize(position)
            }
        }
        list.adapter = adapter

        btnSelectAll.setOnClickListener { adapter.selectAllStatic() }
        btnClear.setOnClickListener { adapter.clearSelection() }
        btnCompress.setOnClickListener { askCompress() }
        btnExportOriginal.setOnClickListener { askExportOriginal() }
    }

    /** 从预览页回来时体积可能已经变了，重新算一遍；选中的那些只要还在就继续选中。 */
    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                StorageUsage.load(this@CompressActivity, viewModel.getAllEmojisOnce())
            }
            // 动图本来就不参与压缩，默认不列出来；高级设置里可以改回显示。
            hiddenAnimated = if (CompressPrefs.showAnimated(this@CompressActivity)) {
                0
            } else {
                snapshot.files.count { it.animated }
            }
            val files = if (hiddenAnimated == 0) snapshot.files else snapshot.files.filterNot { it.animated }
            adapter.submit(files)
            empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            if (files.isEmpty()) {
                empty.setText(
                    if (snapshot.files.isEmpty()) R.string.compress_empty
                    else R.string.compress_empty_hidden_animated
                )
            }
            syncBar()
        }
    }

    private fun syncBar() {
        val count = adapter.selectedCount
        summary.text = if (count == 0) {
            if (hiddenAnimated > 0) {
                getString(R.string.compress_hint_hidden, modeLabel(), hiddenAnimated)
            } else {
                getString(R.string.compress_hint, modeLabel())
            }
        } else {
            getString(R.string.compress_selected, count, StorageUsage.formatBytes(this, adapter.selectedBytes)) +
                if (hiddenAnimated > 0) getString(R.string.compress_hidden_note, hiddenAnimated) else ""
        }
        btnCompress.text = getString(R.string.compress_action, count)
        val hasSelection = count > 0
        btnCompress.isEnabled = hasSelection
        btnExportOriginal.isEnabled = hasSelection
        setActionEnabled(btnSelectAll, adapter.selectableCount > count)
        setActionEnabled(btnClear, hasSelection)
    }

    /** 当前这台机器上「无损」实际落到哪个格式 —— Android 11 以下没有无损 WebP 编码器，退成 PNG。 */
    private fun modeLabel(): String = getString(
        when {
            !CompressPrefs.lossless(this) -> R.string.compress_mode_lossy
            EmojiCompressor.losslessUsesPng() -> R.string.compress_mode_lossless_png
            else -> R.string.compress_mode_lossless
        }
    )

    /** 顶部那两个纯文字按钮没有 disabled 样式，自己压暗。 */
    private fun setActionEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    private fun askCompress() {
        val selected = adapter.selectedFiles()
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.compress_confirm_title, selected.size))
            .setMessage(
                getString(
                    when {
                        !CompressPrefs.lossless(this) -> R.string.compress_confirm_message_lossy
                        EmojiCompressor.losslessUsesPng() -> R.string.compress_confirm_message_lossless_png
                        else -> R.string.compress_confirm_message_lossless
                    },
                    StorageUsage.formatBytes(this, selected.sumOf { it.bytes })
                )
            )
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.compress_confirm_ok) { _, _ -> runCompress(selected) }
            .show()
    }

    private fun runCompress(selected: List<EmojiFile>) {
        val lossless = CompressPrefs.lossless(this)
        val dialog = BusyDialog.showProgress(
            this,
            R.string.compress_progress_title,
            getString(R.string.compress_progress, 1, selected.size)
        )
        lifecycleScope.launch {
            val results = mutableListOf<PendingCompress>()
            // 没进结果的三种原因分开记账 —— 「没变小」和「装不下」对用户是两件事，值得分开说。
            var noGain = 0
            var tooBig = 0
            var failed = 0
            selected.forEachIndexed { index, file ->
                BusyDialog.update(
                    this@CompressActivity,
                    dialog,
                    index + 1,
                    selected.size,
                    getString(R.string.compress_progress, index + 1, selected.size)
                )
                val outcome = withContext(Dispatchers.IO) { EmojiCompressor.compressTo(file.path, tempDir, lossless) }
                val path = outcome.path
                if (outcome.status == EmojiCompressor.Status.DONE && path != null) {
                    results += PendingCompress(
                        id = file.id,
                        name = file.name,
                        original = file.path,
                        originalBytes = file.bytes,
                        compressed = path,
                        compressedBytes = outcome.bytes,
                        width = outcome.width,
                        height = outcome.height
                    )
                } else {
                    when (outcome.status) {
                        EmojiCompressor.Status.NO_GAIN -> noGain++
                        EmojiCompressor.Status.TOO_BIG -> tooBig++
                        else -> failed++
                    }
                }
            }
            BusyDialog.dismiss(this@CompressActivity, dialog)

            if (results.isEmpty()) {
                MaterialAlertDialogBuilder(this@CompressActivity)
                    .setTitle(R.string.compress_title)
                    .setMessage(
                        when {
                            tooBig == selected.size -> R.string.compress_result_none_too_big
                            noGain == selected.size -> R.string.compress_result_none_no_gain
                            else -> R.string.compress_result_none
                        }
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            CompressSession.put(results)
            val kept = noGain + tooBig + failed
            if (kept > 0) {
                val parts = buildList {
                    if (noGain > 0) add(getString(R.string.compress_result_part_no_gain, noGain))
                    if (tooBig > 0) add(getString(R.string.compress_result_part_too_big, tooBig))
                    if (failed > 0) add(getString(R.string.compress_result_part_failed, failed))
                }
                Toast.makeText(
                    this@CompressActivity,
                    getString(R.string.compress_result_partial_wrap, parts.joinToString("、")),
                    Toast.LENGTH_LONG
                ).show()
            }
            startActivity(android.content.Intent(this@CompressActivity, CompressPreviewActivity::class.java))
        }.invokeOnCompletion {
            // 正常走完时上面已经收过一次；这里管的是「用户按返回键取消 / 半路抛出来」那条路 ——
            // 少了它，进度框会永远留在屏幕上（WindowLeaked，emc-2-032）。
            BusyDialog.dismiss(this@CompressActivity, dialog)
        }
    }

    /** 把选中的原图打包成一个 zip 存出去 —— 压之前先留一份底。 */
    private fun askExportOriginal() {
        if (adapter.selectedCount == 0) return
        zipPicker.launch(getString(R.string.export_original_zip_name, timestamp()))
    }

    private fun exportZip(uri: Uri) {
        val targets = adapter.selectedFiles().map { ExportTarget(it.name, it.path) }
        val dialog = BusyDialog.showProgress(
            this,
            R.string.export_progress_title,
            getString(R.string.export_progress, 1, targets.size)
        )
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                // 目标文档被撤权 / 已被删除 / 分区写满时，openOutputStream 会抛
                // SecurityException / FileNotFoundException / IOException —— 以前这里
                // 一点兜底都没有（同库别处都包了 runCatching），异常逃出去就是崩进程（emc-2-032）。
                val out = runCatching { contentResolver.openOutputStream(uri, "w") }
                    .onFailure {
                        SendLog.e(
                            "导出",
                            "打不开目标文件：" + it.javaClass.simpleName + "：" + (it.message?.take(60) ?: "")
                        )
                    }
                    .getOrNull()
                if (out == null) {
                    ImageExporter.Report(0, targets.size, 0L)
                } else {
                    runCatching {
                        out.use { stream ->
                            ImageExporter.zip(stream, targets) { done, total ->
                                BusyDialog.update(
                                    this@CompressActivity,
                                    dialog,
                                    done,
                                    total,
                                    getString(R.string.export_progress, done, total)
                                )
                            }
                        }
                    }.onFailure {
                        SendLog.e(
                            "导出",
                            "写 zip 失败：" + it.javaClass.simpleName + "：" + (it.message?.take(60) ?: "")
                        )
                    }.getOrElse { ImageExporter.Report(0, targets.size, 0L) }
                }
            }
            BusyDialog.dismiss(this@CompressActivity, dialog)
            Toast.makeText(this@CompressActivity, exportResult(report), Toast.LENGTH_LONG).show()
        }.invokeOnCompletion { BusyDialog.dismiss(this@CompressActivity, dialog) }
    }

    private fun exportResult(report: ImageExporter.Report): String = when {
        report.written == 0 -> getString(R.string.export_none)
        report.failed > 0 -> getString(
            R.string.export_done_zip,
            report.written,
            StorageUsage.formatBytes(this, report.bytes)
        ) + getString(R.string.export_partial, report.failed)
        else -> getString(
            R.string.export_done_zip,
            report.written,
            StorageUsage.formatBytes(this, report.bytes)
        )
    }

    /** 文件名一律 Locale.US：地区数字不是 ASCII 时，文件名在别的应用里会变成乱码（emc-2-032）。 */
    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    /** 上一次没走完的预览结果留在缓存里，进页面先清掉。 */
    private fun cleanTempDir() {
        tempDir.listFiles()?.forEach { it.delete() }
    }
}
