package com.aris.emojichan

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.util.PermissionUtil
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var adapter: EmojiGridAdapter

    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchBar: EditText
    private lateinit var categoryContainer: LinearLayout
    private lateinit var emojiGrid: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnImport: Button
    private lateinit var btnDelete: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnCancel: Button

    private var currentCategoryChips = mutableListOf<TextView>()

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importImage(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            imagePicker.launch("image/*")
        } else {
            Toast.makeText(this, "需要存储权限才能导入表情", Toast.LENGTH_SHORT).show()
            PermissionUtil.openAppSettings(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        initViews()
        setupToolbar()
        setupSearchBar()
        setupCategoryTabs()
        setupEmojiGrid()
        setupBottomBar()
        observeViewModel()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        searchBar = findViewById(R.id.searchBar)
        categoryContainer = findViewById(R.id.categoryContainer)
        emojiGrid = findViewById(R.id.emojiGrid)
        emptyView = findViewById(R.id.emptyView)
        bottomBar = findViewById(R.id.bottomBar)
        btnImport = findViewById(R.id.btnImport)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancel = findViewById(R.id.btnCancel)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }

    private fun setupSearchBar() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
        })
    }

    private fun setupCategoryTabs() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { categories ->
                updateCategoryTabs(categories)
            }
        }
    }

    private fun updateCategoryTabs(categories: List<String>) {
        categoryContainer.removeAllViews()
        currentCategoryChips.clear()

        val allCategories = listOf("全部", "收藏") +
                categories.filter { it != "全部" && it != "收藏" }.distinct()

        allCategories.forEach { category ->
            val chip = TextView(this).apply {
                text = category
                setPadding(32, 12, 32, 12)
                textSize = 14f
                setOnClickListener {
                    viewModel.setSelectedCategory(category)
                    updateCategorySelection(category)
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            chip.layoutParams = params

            categoryContainer.addView(chip)
            currentCategoryChips.add(chip)
        }

        updateCategorySelection(viewModel.selectedCategory.value)
    }

    private fun updateCategorySelection(selected: String) {
        currentCategoryChips.forEach { chip ->
            if (chip.text == selected) {
                chip.setBackgroundResource(R.drawable.category_chip_selected_bg)
            } else {
                chip.setBackgroundResource(R.drawable.category_chip_bg)
            }
        }
    }

    private fun setupEmojiGrid() {
        adapter = EmojiGridAdapter(
            onEmojiClick = { emoji ->
                if (viewModel.isSelectionMode.value) {
                    viewModel.toggleSelection(emoji.id)
                } else {
                    openEmojiDetail(emoji)
                }
            },
            onEmojiLongClick = { emoji ->
                if (!viewModel.isSelectionMode.value) {
                    viewModel.toggleSelectionMode()
                }
                viewModel.toggleSelection(emoji.id)
            },
            onFavoriteClick = { emoji ->
                viewModel.toggleFavorite(emoji)
            },
            isSelectionMode = { viewModel.isSelectionMode.value },
            selectedIds = { viewModel.selectedIds.value }
        )

        emojiGrid.layoutManager = GridLayoutManager(this, 3)
        emojiGrid.adapter = adapter
    }

    private fun setupBottomBar() {
        btnImport.setOnClickListener {
            if (PermissionUtil.hasReadPermission(this)) {
                imagePicker.launch("image/*")
            } else {
                permissionLauncher.launch(PermissionUtil.getRequiredPermissions())
            }
        }

        btnDelete.setOnClickListener {
            val count = viewModel.selectedIds.value.size
            if (count > 0) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除确认")
                    .setMessage("确定要删除选中的 $count 个表情吗？")
                    .setPositiveButton("删除") { _, _ ->
                        viewModel.deleteSelected()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        btnSelectAll.setOnClickListener {
            viewModel.selectAll()
        }

        btnCancel.setOnClickListener {
            viewModel.toggleSelectionMode()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.emojis.collectLatest { emojis ->
                adapter.submitList(emojis)
                emptyView.visibility = if (emojis.isEmpty()) View.VISIBLE else View.GONE
                emojiGrid.visibility = if (emojis.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.isSelectionMode.collectLatest { isSelectionMode ->
                // 底部栏常驻，仅内部按钮按选择模式切换
                bottomBar.visibility = View.VISIBLE
                btnImport.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                btnDelete.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                btnSelectAll.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                btnCancel.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedIds.collectLatest { selectedIds ->
                btnDelete.text = "删除 (${selectedIds.size})"
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun importImage(uri: android.net.Uri) {
        lifecycleScope.launch {
            val filePath = ImageUtil.copyImageToInternal(this@MainActivity, uri)
            if (filePath != null) {
                val (width, height) = ImageUtil.getImageDimensions(filePath)
                val emoji = EmojiEntity(
                    name = "表情 ${System.currentTimeMillis()}",
                    filePath = filePath,
                    fileType = if (ImageUtil.isGif(filePath)) "gif" else "image",
                    category = viewModel.selectedCategory.value.let {
                        if (it == "全部" || it == "收藏") "默认" else it
                    },
                    fileSize = ImageUtil.getFileSize(filePath),
                    width = width,
                    height = height
                )
                viewModel.insertEmoji(emoji)
                Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openEmojiDetail(emoji: EmojiEntity) {
        val intent = Intent(this, EmojiDetailActivity::class.java).apply {
            putExtra("emoji_id", emoji.id)
            putExtra("emoji_name", emoji.name)
            putExtra("emoji_file_path", emoji.filePath)
            putExtra("emoji_file_type", emoji.fileType)
            putExtra("emoji_category", emoji.category)
            putExtra("emoji_is_favorite", emoji.isFavorite)
            putExtra("emoji_file_size", emoji.fileSize)
            putExtra("emoji_create_time", emoji.createTime)
            putExtra("emoji_width", emoji.width)
            putExtra("emoji_height", emoji.height)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isSelectionMode.value) {
            viewModel.toggleSelectionMode()
        }
    }
}
