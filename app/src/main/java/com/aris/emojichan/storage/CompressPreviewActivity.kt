package com.aris.emojichan.storage

import android.os.Bundle
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.UiPrefs
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 压缩结果预览页。
 *
 * 进来的时候原图一张都没动：新图全在缓存目录里。用户看过之后点「替换」才逐张
 * 搬进表情库目录、回写数据库、删掉原图；点「放弃」或直接返回就把缓存里的新图删掉。
 *
 * 顺序是「搬文件 → 回写数据库 → 删原图」，回写失败就把搬过去的文件删掉、原图留着，
 * 不会出现数据库指着空路径的状态。
 */
class CompressPreviewActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel

    /** 替换过就不能再「放弃」了 —— 原图已经删了。 */
    private var applied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_compress_preview)

        val items = CompressSession.items()
        if (items.isEmpty()) {
            finish()
            return
        }
        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        findViewById<MaterialToolbar>(R.id.previewToolbar).setNavigationOnClickListener { confirmDiscard() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        findViewById<TextView>(R.id.previewHint).text =
            getString(R.string.preview_hint) + "\n" +
                getString(R.string.preview_saved, items.size, StorageUsage.formatBytes(this, CompressSession.savedBytes))

        val list = findViewById<RecyclerView>(R.id.previewList)
        list.layoutManager = GridLayoutManager(this, CompressAdapter.SPAN)
        list.adapter = CompressPreviewAdapter(items) { showCompare(it) }

        val btnApply = findViewById<MaterialButton>(R.id.btnPreviewApply)
        btnApply.text = getString(R.string.preview_apply, items.size)
        btnApply.setOnClickListener { applyAll() }
        findViewById<MaterialButton>(R.id.btnPreviewDiscard).setOnClickListener { confirmDiscard() }
    }

    private fun confirmDiscard() {
        val items = CompressSession.items()
        if (applied || items.isEmpty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_discard_title)
            .setMessage(getString(R.string.preview_discard_message, items.size))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.preview_discard) { _, _ ->
                CompressSession.drop()
                finish()
            }
            .show()
    }

    private fun applyAll() {
        val items = CompressSession.items()
        if (items.isEmpty()) {
            finish()
            return
        }
        val dialog = BusyDialog.showProgress(
            this,
            R.string.compress_progress_title,
            getString(R.string.compress_progress, 1, items.size)
        )
        lifecycleScope.launch {
            var done = 0
            var failed = 0
            var saved = 0L

            items.forEachIndexed { index, item ->
                BusyDialog.update(
                    this@CompressPreviewActivity,
                    dialog,
                    index + 1,
                    items.size,
                    getString(R.string.compress_progress, index + 1, items.size)
                )
                val replaced = withContext(Dispatchers.IO) { replace(item) }
                if (replaced) {
                    done++
                    saved += item.savedBytes
                } else {
                    failed++
                }
            }

            BusyDialog.dismiss(this@CompressPreviewActivity, dialog)
            applied = true
            CompressSession.clear()
            showResult(done, failed, saved)
        }
    }

    /** 搬文件 → 回写数据库 → 删原图。任何一步不成，就把这次留下的东西收拾干净。 */
    private suspend fun replace(item: PendingCompress): Boolean {
        val promoted = ImageUtil.promoteTempFile(this, File(item.compressed)) ?: return false
        val written = viewModel.replaceEmojiFile(
            item.id,
            promoted.absolutePath,
            promoted.length(),
            item.width,
            item.height
        )
        if (!written) {
            runCatching { promoted.delete() }
            return false
        }
        ImageUtil.deleteFile(item.original)
        return true
    }

    private fun showResult(done: Int, failed: Int, saved: Long) {
        val message = if (done == 0) {
            getString(R.string.compress_result_none)
        } else {
            getString(R.string.preview_applied, done, StorageUsage.formatBytes(this, saved)) +
                if (failed > 0) getString(R.string.preview_applied_partial, failed) else ""
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    /** 大图对比：默认看压缩后的，按住按钮临时换成原图。 */
    private fun showCompare(item: PendingCompress) {
        val view = layoutInflater.inflate(R.layout.dialog_compare_image, null)
        val image = view.findViewById<ImageView>(R.id.compareImage)
        val tag = view.findViewById<TextView>(R.id.compareTag)
        val hold = view.findViewById<MaterialButton>(R.id.btnCompareHold)

        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        fun show(original: Boolean) {
            Glide.with(this)
                .load(File(if (original) item.original else item.compressed))
                .override(1440, 1440)
                .fitCenter()
                .into(image)
            tag.text = getString(if (original) R.string.preview_showing_original else R.string.preview_showing_compressed)
        }

        hold.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    show(true)
                    button.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    show(false)
                    button.isPressed = false
                    true
                }
                else -> false
            }
        }
        view.findViewById<MaterialButton>(R.id.btnCompareClose).setOnClickListener { dialog.dismiss() }

        show(false)
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 被系统回收（不是正常关闭）时把缓存里的新图清掉，原图不动。
        if (isFinishing && !applied) CompressSession.drop()
    }
}
