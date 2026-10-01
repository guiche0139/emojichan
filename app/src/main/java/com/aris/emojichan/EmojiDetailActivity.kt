package com.aris.emojichan

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.aris.emojichan.data.EmojiDefaults
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.TagEntity
import com.aris.emojichan.sender.EmojiShare
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 表情详情页。
 *
 * 设计要点：Intent 只携带 [EXTRA_EMOJI_ID]，页面数据一律来自数据库 Flow，
 * 不在页面间搬运实体。写操作只更新目标列（改名 / 收藏），不会覆盖
 * tags、source、usageCount、lastUsedTime 等字段。
 */
class EmojiDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMOJI_ID = "emoji_id"
    }

    private lateinit var viewModel: EmojiViewModel
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emojiImage: ImageView
    private lateinit var emojiName: TextView
    private lateinit var emojiInfo: TextView
    private lateinit var btnFavorite: Button
    private lateinit var btnCopy: Button
    private lateinit var btnRename: Button
    private lateinit var btnDelete: Button
    private lateinit var tagContainer: ChipGroup
    private lateinit var tagEmpty: TextView
    private lateinit var btnAddTag: Button

    /** 这张表情当前挂着的标签，[showTagPickerDialog] 用它排掉已经挂上的。 */
    private var currentTags: List<TagEntity> = emptyList()

    /** 当前表情的数据库真值；由 [observeEmoji] 持续刷新，写操作一律以它为准。 */
    private var currentEmoji: EmojiEntity? = null

    private val emojiId: Long by lazy { intent.getLongExtra(EXTRA_EMOJI_ID, 0L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色 / 深浅色跟主界面保持一致（overlay 必须在 setContentView 之前套）。
        com.aris.emojichan.UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_emoji_detail)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        initViews()
        setupToolbar()
        observeMessage()
        setupButtons()

        if (emojiId == 0L) {
            Toast.makeText(this, getString(R.string.detail_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        observeEmoji()
        observeTags()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        emojiImage = findViewById(R.id.emojiImageView)
        emojiName = findViewById(R.id.emojiName)
        emojiInfo = findViewById(R.id.emojiInfo)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnCopy = findViewById(R.id.btnCopy)
        btnRename = findViewById(R.id.btnRename)
        btnDelete = findViewById(R.id.btnDelete)
        tagContainer = findViewById(R.id.tagContainer)
        tagEmpty = findViewById(R.id.tagEmpty)
        btnAddTag = findViewById(R.id.btnAddTag)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    /** 订阅数据库：记录被删除时自动关闭页面；写入成功后 UI 自动回灌真值。 */
    private fun observeEmoji() {
        lifecycleScope.launch {
            viewModel.observeEmoji(emojiId).collectLatest { emoji ->
                if (emoji == null) {
                    currentEmoji = null
                    finish()
                    return@collectLatest
                }
                currentEmoji = emoji
                displayEmojiInfo(emoji)
            }
        }
    }

    private fun observeMessage() {
        lifecycleScope.launch {
            viewModel.message.collectLatest { msg ->
                if (msg != null) {
                    Toast.makeText(this@EmojiDetailActivity, msg, Toast.LENGTH_SHORT).show()
                    viewModel.consumeMessage()
                }
            }
        }
    }

    private fun displayEmojiInfo(emoji: EmojiEntity) {
        Glide.with(this)
            .load(File(emoji.filePath))
            .transform(CenterCrop(), RoundedCorners(16))
            .placeholder(R.drawable.ic_emoji_placeholder)
            .error(R.drawable.ic_emoji_placeholder)
            .into(emojiImage)

        emojiName.text = emoji.name

        val fileSizeFormatted = formatFileSize(emoji.fileSize)
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(emoji.createTime))
        val sizeText = if (emoji.width > 0 && emoji.height > 0) {
            getString(R.string.detail_size_suffix, emoji.width, emoji.height)
        } else {
            ""
        }
        // 格式只看文件后缀，后缀拿不到才退回 fileType（导入时就写过，值是 gif / image）
        val format = emoji.filePath.substringAfterLast('.', "")
            .uppercase(Locale.getDefault())
            .ifEmpty { emoji.fileType.uppercase(Locale.getDefault()) }
        // 标签在下面单独一行显示（还能点着改），这里就不再重复一行「包」了
        emojiInfo.text = getString(
            R.string.detail_info,
            format,
            sizeText,
            fileSizeFormatted,
            dateFormatted
        )

        updateFavoriteButton(emoji.isFavorite)
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        btnFavorite.text = getString(if (isFavorite) R.string.btn_unfavorite else R.string.btn_favorite)
    }

    private fun setupButtons() {
        btnFavorite.setOnClickListener {
            val emoji = currentEmoji ?: return@setOnClickListener
            // 只写 isFavorite 一列，不做整行覆盖
            viewModel.updateFavorite(emoji.id, !emoji.isFavorite)
        }

        btnCopy.setOnClickListener { copyToClipboard() }

        btnRename.setOnClickListener {
            showRenameDialog()
        }

        btnAddTag.setOnClickListener { showTagPickerDialog() }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_single_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    deleteEmoji()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    /**
     * 复制到剪贴板：以图片剪贴板（content URI + 真实 mime）的形式交给系统，
     * 和发送模块里静态图走的是同一条路（[EmojiShare.copyToClipboard]）。
     * 用户到聊天里长按输入框粘贴即可 —— 这条路不依赖无障碍。
     */
    private fun copyToClipboard() {
        val emoji = currentEmoji ?: return
        val kind = if (emoji.fileType.equals("gif", ignoreCase = true)) "动图 gif" else emoji.fileType
        val ok = EmojiShare.copyToClipboard(this, emoji)
        val text = if (ok) {
            getString(R.string.copy_ok_toast, emoji.name, kind)
        } else {
            getString(R.string.copy_fail_toast)
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun showRenameDialog() {
        val emoji = currentEmoji ?: return
        val input = EditText(this).apply {
            setText(emoji.name)
            setSelection(emoji.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() ->
                        Toast.makeText(this, getString(R.string.rename_name_empty), Toast.LENGTH_SHORT).show()
                    newName != emoji.name -> viewModel.renameEmoji(emoji.id, newName)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteEmoji() {
        val emoji = currentEmoji ?: return
        // 不在页面里直接删文件：删除顺序（先文件、后记录）与失败处理统一由 ViewModel 负责。
        // 删除成功后记录消失，observeEmoji 收到 null 会自动 finish()；失败则记录保留、仅弹提示。
        viewModel.deleteEmoji(emoji.id)
    }

    // ---------- 标签 ----------

    /** 标签行订阅：加/摘都由数据库 Flow 推回来，页面不自己改状态。 */
    private fun observeTags() {
        lifecycleScope.launch {
            viewModel.observeTagsOf(emojiId).collectLatest { tags -> renderTags(tags) }
        }
    }

    private fun renderTags(tags: List<TagEntity>) {
        currentTags = tags
        tagContainer.removeAllViews()
        tagEmpty.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
        tags.forEach { tag -> tagContainer.addView(createTagChip(tag)) }
    }

    private fun createTagChip(tag: TagEntity): Chip = Chip(this).apply {
        text = tag.name
        textSize = 13f
        isCheckable = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(false)
        setOnClickListener { confirmRemoveTag(tag) }
    }

    private fun confirmRemoveTag(tag: TagEntity) {
        if (EmojiDefaults.isAutoTag(tag.name)) {
            Toast.makeText(this, R.string.tag_auto_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_delete_title)
            .setMessage(getString(R.string.tag_remove_from_emoji, tag.name))
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                viewModel.removeTagFromEmoji(emojiId, tag.id)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 挑一个已有标签挂上；想新建走「新建标签…」。
     *
     * 两个分支各只留一个「新建标签…」按钮：曾经空列表那一支同时设了 positive 与 neutral，
     * 两个按钮文案一样，弹出来就是并排两个「新建标签…」。
     *
     * 清单现取：`viewModel.tags` 是 `WhileSubscribed` 的 StateFlow，而详情页从不订阅它，
     * `.value` 会一直是初始的空列表 —— 照它筛出来的候选恒为空，弹窗永远只剩「暂无标签」。
     */
    private fun showTagPickerDialog() {
        lifecycleScope.launch {
            val all = viewModel.allTagsOnce()
            val attached = currentTags.map { it.id }.toSet()
            val candidates = all.filter { it.id !in attached && !EmojiDefaults.isAutoTag(it.name) }
            val builder = MaterialAlertDialogBuilder(this@EmojiDetailActivity)
                .setTitle(R.string.tag_pick_title)
                .setNegativeButton(R.string.dialog_cancel, null)
            if (candidates.isEmpty()) {
                builder
                    .setMessage(R.string.tag_pick_none)
                    .setPositiveButton(R.string.organize_add_tag_new) { _, _ -> showNewTagDialog() }
                    .show()
                return@launch
            }
            builder
                .setItems(candidates.map { it.name }.toTypedArray()) { _, which ->
                    viewModel.addTagToEmoji(emojiId, candidates[which].name)
                }
                .setNeutralButton(R.string.organize_add_tag_new) { _, _ -> showNewTagDialog() }
                .show()
        }
    }

    private fun showNewTagDialog() {
        val input = EditText(this).apply { hint = getString(R.string.dialog_tag_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_new_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.addTagToEmoji(emojiId, name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
