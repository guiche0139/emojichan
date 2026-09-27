package com.aris.emojichan

import android.os.Bundle
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
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
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
    private lateinit var btnRename: Button
    private lateinit var btnDelete: Button

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
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        emojiImage = findViewById(R.id.emojiImageView)
        emojiName = findViewById(R.id.emojiName)
        emojiInfo = findViewById(R.id.emojiInfo)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnRename = findViewById(R.id.btnRename)
        btnDelete = findViewById(R.id.btnDelete)
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
        emojiInfo.text = getString(
            R.string.detail_info,
            emoji.category,
            fileSizeFormatted,
            sizeText,
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

        btnRename.setOnClickListener {
            showRenameDialog()
        }

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

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
