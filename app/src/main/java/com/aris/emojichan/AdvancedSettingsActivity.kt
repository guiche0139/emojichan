package com.aris.emojichan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.aris.emojichan.sender.SendLogActivity
import com.aris.emojichan.storage.EmojiCompressor
import com.aris.emojichan.storage.SimilarFinder
import com.aris.emojichan.sender.SenderPrefs
import com.aris.emojichan.util.SourceFiles
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 高级设置页：导入的原文件怎么处理、大表情压缩的编码方式、画面相近判定的底线、表情发送方式、发送日志。
 *
 * 为什么另开一页而不是继续堆在主设置页：这几项都是「改坏了要花时间收拾」的东西
 * （删源文件、把整库换成剪贴板贴图），跟 UI 主题那种随手拨的开关不该摆在一起。
 * 表情库迁移（导出 / 导入备份包）自 v0.1.406 起搬回主设置页，这里不再管。
 *
 * 主题：跟主界面同一套（overlay 要在 setContentView 之前套）。
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel

    // ---- 表情发送方式（从主设置页整段搬来的，逻辑一字未改） ----
    private lateinit var routeToggle: MaterialButtonToggleGroup
    private lateinit var routeHint: TextView
    private lateinit var wechatImageToggle: MaterialButtonToggleGroup
    private lateinit var wechatImageHint: TextView
    private var syncingRouteToggle = false
    private var syncingWechatImageToggle = false

    // ---- 大表情压缩（v0.1.408） ----
    private lateinit var compressToggle: MaterialButtonToggleGroup
    private lateinit var compressModeHint: TextView
    private lateinit var compressAnimToggle: MaterialButtonToggleGroup
    private lateinit var compressAnimHint: TextView
    private var syncingCompressToggle = false
    private var syncingAnimToggle = false

    // ---- 画面相近判定的底线（v0.1.412，用户 m08998：页面上那个开关没用，挪到这里来） ----
    private lateinit var searchLineToggle: MaterialButtonToggleGroup
    private lateinit var searchLineHint: TextView
    private var syncingSearchLineToggle = false

    // ---- 表情原文件 ----
    private lateinit var sourceRadio: RadioGroup
    private lateinit var sourceHint: TextView
    private lateinit var rowSourceDir: View
    private lateinit var sourceDirValue: TextView
    private lateinit var sourceNote: TextView
    private var syncingSource = false

    /** 压缩包存到哪个目录。 */
    private val dirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onSourceDirPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_advanced_settings)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        findViewById<MaterialToolbar>(R.id.advancedToolbar)
            .setNavigationOnClickListener { finish() }

        setupSource()
        setupCompress()
        setupSearch()
        setupRoute()
    }

    override fun onResume() {
        super.onResume()
        refreshSource()
        refreshCompress()
        refreshSearch()
    }

    // ---------------- 表情原文件 ----------------

    private fun setupSource() {
        sourceRadio = findViewById(R.id.sourceRadio)
        sourceHint = findViewById(R.id.sourceHint)
        rowSourceDir = findViewById(R.id.rowSourceDir)
        sourceDirValue = findViewById(R.id.sourceDirValue)
        sourceNote = findViewById(R.id.sourceNote)

        sourceRadio.setOnCheckedChangeListener { _, checkedId ->
            if (syncingSource) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.radioSourceZip -> {
                    ImportPrefs.setPolicy(this, ImportPrefs.POLICY_ZIP)
                    refreshSource()
                }

                R.id.radioSourceDelete -> askBeforeEnableDelete()

                else -> {
                    ImportPrefs.setPolicy(this, ImportPrefs.POLICY_KEEP)
                    refreshSource()
                }
            }
        }

        rowSourceDir.setOnClickListener { dirPicker.launch(savedSourceTree()) }
        refreshSource()
    }

    private fun refreshSource() {
        val policy = ImportPrefs.policy(this)
        syncingSource = true
        sourceRadio.check(
            when (policy) {
                ImportPrefs.POLICY_ZIP -> R.id.radioSourceZip
                ImportPrefs.POLICY_DELETE -> R.id.radioSourceDelete
                else -> R.id.radioSourceKeep
            }
        )
        syncingSource = false
        sourceHint.setText(
            when (policy) {
                ImportPrefs.POLICY_ZIP -> R.string.settings_source_hint_zip
                ImportPrefs.POLICY_DELETE -> R.string.settings_source_hint_delete
                else -> R.string.settings_source_hint_keep
            }
        )
        val zip = policy == ImportPrefs.POLICY_ZIP
        rowSourceDir.visibility = if (zip) View.VISIBLE else View.GONE
        sourceNote.visibility = if (zip) View.VISIBLE else View.GONE

        val tree = savedSourceTree()
        sourceDirValue.text = if (tree == null) {
            getString(R.string.settings_source_dir_none)
        } else {
            SourceFiles.treeDisplayName(this, tree) ?: tree.toString()
        }
    }

    private fun savedSourceTree(): Uri? =
        ImportPrefs.zipTreeUri(this)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun onSourceDirPicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        ImportPrefs.setZipTreeUri(this, uri.toString())
        refreshSource()
    }

    /** 打开「删除原文件」要先问一句：这条策略生效之后，导入完就会删用户自己的图。 */
    private fun askBeforeEnableDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_enable_delete_title)
            .setMessage(R.string.source_enable_delete_message)
            .setPositiveButton(R.string.source_enable_ok) { _, _ ->
                ImportPrefs.setPolicy(this, ImportPrefs.POLICY_DELETE)
                refreshSource()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> refreshSource() }
            .setOnCancelListener { refreshSource() }
            .show()
    }

    // ---------------- 大表情压缩（v0.1.408） ----------------

    private fun setupCompress() {
        compressToggle = findViewById(R.id.compressToggle)
        compressModeHint = findViewById(R.id.compressModeHint)
        compressToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingCompressToggle) {
                applyLossless(checkedId == R.id.btnCompressLossless, fromUser = true)
            }
        }

        compressAnimToggle = findViewById(R.id.compressAnimToggle)
        compressAnimHint = findViewById(R.id.compressAnimHint)
        compressAnimToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingAnimToggle) {
                applyShowAnimated(checkedId == R.id.btnCompressAnimShow, fromUser = true)
            }
        }
        refreshCompress()
    }

    private fun refreshCompress() {
        val lossless = CompressPrefs.lossless(this)
        syncingCompressToggle = true
        compressToggle.check(if (lossless) R.id.btnCompressLossless else R.id.btnCompressLossy)
        syncingCompressToggle = false
        compressModeHint.setText(compressHintRes())

        val showAnimated = CompressPrefs.showAnimated(this)
        syncingAnimToggle = true
        compressAnimToggle.check(if (showAnimated) R.id.btnCompressAnimShow else R.id.btnCompressAnimHide)
        syncingAnimToggle = false
        compressAnimHint.setText(animHintRes())
    }

    private fun applyLossless(lossless: Boolean, fromUser: Boolean) {
        CompressPrefs.setLossless(this, lossless)
        compressModeHint.setText(compressHintRes())
        if (fromUser) {
            Toast.makeText(
                this,
                if (lossless) R.string.compress_switched_lossless else R.string.compress_switched_lossy,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyShowAnimated(showAnimated: Boolean, fromUser: Boolean) {
        CompressPrefs.setShowAnimated(this, showAnimated)
        compressAnimHint.setText(animHintRes())
        if (fromUser) {
            Toast.makeText(
                this,
                if (showAnimated) R.string.compress_switched_anim_shown else R.string.compress_switched_anim_hidden,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 「无损」在不同 Android 版本上落的格式不一样（11 以下退成 PNG），说明得跟着变。 */
    private fun compressHintRes(): Int = when {
        !CompressPrefs.lossless(this) -> R.string.settings_compress_hint_lossy
        EmojiCompressor.losslessUsesPng() -> R.string.settings_compress_hint_lossless_png
        else -> R.string.settings_compress_hint_lossless
    }

    private fun animHintRes(): Int = if (CompressPrefs.showAnimated(this)) {
        R.string.settings_compress_anim_hint_shown
    } else {
        R.string.settings_compress_anim_hint_hidden
    }

    // ---------------- 画面相近判定的底线（v0.1.412，用户 m08998） ----------------

    private fun setupSearch() {
        searchLineToggle = findViewById(R.id.searchLineToggle)
        searchLineHint = findViewById(R.id.searchLineHint)
        searchLineToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingSearchLineToggle) {
                applySearchLine(
                    when (checkedId) {
                        R.id.btnSearchLine4 -> SimilarFinder.STRICT
                        R.id.btnSearchLine12 -> SimilarFinder.LOOSE
                        else -> SimilarFinder.STANDARD
                    },
                    fromUser = true
                )
            }
        }
        refreshSearch()
    }

    private fun refreshSearch() {
        val threshold = SimilarPrefs.threshold(this)
        syncingSearchLineToggle = true
        searchLineToggle.check(
            when (threshold) {
                SimilarFinder.STRICT -> R.id.btnSearchLine4
                SimilarFinder.LOOSE -> R.id.btnSearchLine12
                else -> R.id.btnSearchLine8
            }
        )
        syncingSearchLineToggle = false
        searchLineHint.setText(searchLineHintRes(threshold))
    }

    private fun applySearchLine(threshold: Int, fromUser: Boolean) {
        SimilarPrefs.setThreshold(this, threshold)
        searchLineHint.setText(searchLineHintRes(threshold))
        if (fromUser) {
            Toast.makeText(
                this,
                getString(R.string.search_line_switched, SimilarFinder.similarity(threshold)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun searchLineHintRes(threshold: Int): Int = when (threshold) {
        SimilarFinder.STRICT -> R.string.settings_search_hint_4
        SimilarFinder.LOOSE -> R.string.settings_search_hint_12
        else -> R.string.settings_search_hint_8
    }

    // ---------------- 表情发送方式（原样搬自主设置页） ----------------

    private fun setupRoute() {
        routeToggle = findViewById(R.id.routeToggle)
        routeHint = findViewById(R.id.routeHint)
        routeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingRouteToggle) {
                applySendRoute(album = checkedId == R.id.btnRouteAlbum, fromUser = true)
            }
        }

        wechatImageToggle = findViewById(R.id.wechatImageToggle)
        wechatImageHint = findViewById(R.id.wechatImageHint)
        wechatImageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingWechatImageToggle) {
                applyWechatImage(clipboard = checkedId == R.id.btnWechatImageClipboard, fromUser = true)
            }
        }

        findViewById<View>(R.id.rowOnboarding).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<View>(R.id.rowLog).setOnClickListener {
            startActivity(Intent(this, SendLogActivity::class.java))
        }
        refreshSendRoute()
    }

    private fun refreshSendRoute() {
        val album = SenderPrefs.sendViaAlbum(this)
        syncingRouteToggle = true
        routeToggle.check(if (album) R.id.btnRouteAlbum else R.id.btnRouteShare)
        syncingRouteToggle = false
        routeHint.setText(
            if (album) R.string.settings_route_hint_album else R.string.settings_route_hint_share
        )

        val clipboard = SenderPrefs.wechatImageClipboard(this)
        syncingWechatImageToggle = true
        wechatImageToggle.check(
            if (clipboard) R.id.btnWechatImageClipboard else R.id.btnWechatImageInherit
        )
        syncingWechatImageToggle = false
        wechatImageHint.setText(
            if (clipboard) R.string.settings_wechat_image_hint_clipboard
            else R.string.settings_wechat_image_hint_inherit
        )
    }

    private fun applySendRoute(album: Boolean, fromUser: Boolean) {
        SenderPrefs.setSendViaAlbum(this, album)
        routeHint.setText(
            if (album) R.string.settings_route_hint_album else R.string.settings_route_hint_share
        )
        if (fromUser) {
            Toast.makeText(
                this,
                if (album) R.string.route_switched_album else R.string.route_switched_share,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyWechatImage(clipboard: Boolean, fromUser: Boolean) {
        SenderPrefs.setWechatImageClipboard(this, clipboard)
        wechatImageHint.setText(
            if (clipboard) R.string.settings_wechat_image_hint_clipboard
            else R.string.settings_wechat_image_hint_inherit
        )
        if (fromUser) {
            Toast.makeText(
                this,
                if (clipboard) R.string.wechat_image_switched_clipboard
                else R.string.wechat_image_switched_inherit,
                Toast.LENGTH_LONG
            ).show()
        }
    }

}
