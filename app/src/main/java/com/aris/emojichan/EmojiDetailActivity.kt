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
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EmojiDetailActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emojiImage: ImageView
    private lateinit var emojiName: TextView
    private lateinit var emojiInfo: TextView
    private lateinit var btnFavorite: Button
    private lateinit var btnRename: Button
    private lateinit var btnDelete: Button

    private var emojiId: Long = 0
    private var emojiNameStr: String = ""
    private var emojiFilePath: String = ""
    private var emojiFileType: String = ""
    private var emojiCategory: String = ""
    private var emojiIsFavorite: Boolean = false
    private var emojiFileSize: Long = 0
    private var emojiCreateTime: Long = 0
    private var emojiWidth: Int = 0
    private var emojiHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emoji_detail)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        intent.extras?.let { extras ->
            emojiId = extras.getLong("emoji_id", 0)
            emojiNameStr = extras.getString("emoji_name", "")
            emojiFilePath = extras.getString("emoji_file_path", "")
            emojiFileType = extras.getString("emoji_file_type", "")
            emojiCategory = extras.getString("emoji_category", "")
            emojiIsFavorite = extras.getBoolean("emoji_is_favorite", false)
            emojiFileSize = extras.getLong("emoji_file_size", 0)
            emojiCreateTime = extras.getLong("emoji_create_time", 0)
            emojiWidth = extras.getInt("emoji_width", 0)
            emojiHeight = extras.getInt("emoji_height", 0)
        }

        initViews()
        setupToolbar()
        displayEmojiInfo()
        setupButtons()
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

    private fun displayEmojiInfo() {
        // Load image
        Glide.with(this)
            .load(File(emojiFilePath))
            .transform(CenterCrop(), RoundedCorners(16))
            .placeholder(R.drawable.ic_emoji_placeholder)
            .error(R.drawable.ic_emoji_placeholder)
            .into(emojiImage)

        // Set name
        emojiName.text = emojiNameStr

        // Format info
        val fileSizeFormatted = formatFileSize(emojiFileSize)
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(emojiCreateTime))
        val sizeText = if (emojiWidth > 0 && emojiHeight > 0) {
            " | 尺寸: ${emojiWidth}×${emojiHeight}"
        } else {
            ""
        }
        emojiInfo.text = "分类: $emojiCategory | 大小: $fileSizeFormatted$sizeText | 添加于: $dateFormatted"

        // Update favorite button
        updateFavoriteButton()
    }

    private fun updateFavoriteButton() {
        if (emojiIsFavorite) {
            btnFavorite.text = "取消收藏"
        } else {
            btnFavorite.text = "收藏"
        }
    }

    private fun currentEmoji(): EmojiEntity {
        return EmojiEntity(
            id = emojiId,
            name = emojiNameStr,
            filePath = emojiFilePath,
            fileType = emojiFileType,
            category = emojiCategory,
            isFavorite = emojiIsFavorite,
            fileSize = emojiFileSize,
            createTime = emojiCreateTime,
            width = emojiWidth,
            height = emojiHeight
        )
    }

    private fun setupButtons() {
        btnFavorite.setOnClickListener {
            emojiIsFavorite = !emojiIsFavorite
            viewModel.toggleFavorite(currentEmoji())
            updateFavoriteButton()
        }

        btnRename.setOnClickListener {
            showRenameDialog()
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除确认")
                .setMessage("确定要删除这个表情吗？")
                .setPositiveButton("删除") { _, _ ->
                    deleteEmoji()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(emojiNameStr)
            setSelection(emojiNameStr.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                } else if (newName != emojiNameStr) {
                    viewModel.renameEmoji(emojiId, newName)
                    emojiNameStr = newName
                    emojiName.text = newName
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEmoji() {
        viewModel.deleteEmoji(currentEmoji())
        ImageUtil.deleteFile(emojiFilePath)
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
