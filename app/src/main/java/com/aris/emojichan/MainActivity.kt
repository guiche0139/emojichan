package com.aris.emojichan

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
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
import com.aris.emojichan.sender.ChatProbeActivity
import com.aris.emojichan.sender.AutoSendService
import com.aris.emojichan.sender.EmojiShare
import com.aris.emojichan.sender.FloatingBallService
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.util.PermissionUtil
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.aris.emojichan.sender.SendLogActivity
import com.aris.emojichan.sender.SenderPrefs

/** 搜索防抖窗口：停止输入多久之后才真正查询数据库。 */
private const val SEARCH_DEBOUNCE_MS = 250L

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
    private lateinit var senderStatus: TextView
    private lateinit var senderLogLink: TextView
    private lateinit var gifRoute: TextView

    private var currentCategoryChips: List<TextView> = emptyList()

    /** 搜索防抖用的任务句柄：新输入到来时取消上一次尚未触发的查询。 */
    private var searchJob: Job? = null

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
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
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
        // 标题栏副标题显示版本号，和 APK 文件名里的版本对得上，方便确认装的是哪一版。
        toolbar.subtitle = "v" + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        searchBar = findViewById(R.id.searchBar)
        categoryContainer = findViewById(R.id.categoryContainer)
        emojiGrid = findViewById(R.id.emojiGrid)
        emptyView = findViewById(R.id.emptyView)
        bottomBar = findViewById(R.id.bottomBar)
        btnImport = findViewById(R.id.btnImport)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancel = findViewById(R.id.btnCancel)
        senderStatus = findViewById(R.id.senderStatus)
        senderStatus.setOnClickListener { openAccessibilitySettings() }
        senderLogLink = findViewById(R.id.senderLogLink)
        senderLogLink.setOnClickListener {
            startActivity(Intent(this, SendLogActivity::class.java))
        }
        gifRoute = findViewById(R.id.gifRoute)
        gifRoute.setOnClickListener { toggleGifRoute() }
    }

    /**
     * 状态条：无障碍服务开没开，直接决定两件事 —— 球是只在微信 / QQ 出现
     * 还是一直赖在屏幕上；选完表情是自动选中聊天对象，还是把微信的选人界面
     * 丢给用户自己点。这个开关藏在系统设置里，不摆出来用户就会以为功能坏了。
     */
    private fun refreshSenderStatus() {
        val on = AutoSendService.isConnected
        senderStatus.text = getString(
            if (on) R.string.sender_status_on else R.string.sender_status_off
        )
        senderStatus.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    /**
     * 微信动图走哪条路，摆出来让用户自己切。
     *
     * 两条路各有代价，而且哪条更好取决于微信自己的行为（分享通道保不保动画）——
     * 那不是我能替用户决定的，摆一行正文、点一下就换，比发版快得多。
     * 这一项只影响「微信里发动图」，静态图和 QQ 完全不受影响。
     */
    private fun refreshGifRoute() {
        val album = SenderPrefs.wechatGifViaAlbum(this)
        gifRoute.text = getString(if (album) R.string.gif_route_album else R.string.gif_route_share)
    }

    private fun toggleGifRoute() {
        val album = !SenderPrefs.wechatGifViaAlbum(this)
        SenderPrefs.setWechatGifViaAlbum(this, album)
        refreshGifRoute()
        Toast.makeText(
            this,
            if (album) R.string.gif_route_switched_album else R.string.gif_route_switched_share,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openAccessibilitySettings() {
        if (AutoSendService.isConnected) return
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                Toast.makeText(
                    this,
                    R.string.sender_status_settings_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_send_overlay)?.setTitle(
            if (FloatingBallService.isRunning) R.string.menu_send_overlay_on
            else R.string.menu_send_overlay_off
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_send_overlay -> {
                toggleSendOverlay()
                true
            }

            R.id.action_sender_probe -> {
                startActivity(Intent(this, ChatProbeActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 用户在系统设置里授权后要接着把悬浮球开起来，免得回来还得再点一次菜单。 */
    private var pendingOverlayStart = false

    /**
     * 菜单里的悬浮球开关。
     *
     * 只需要「显示在其他应用上层」这一个权限：不用无障碍，也就没有
     * Android 13 那套「受限设置」引导。
     */
    private fun toggleSendOverlay() {
        if (FloatingBallService.isRunning) {
            FloatingBallService.stop(this)
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            pendingOverlayStart = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }.onFailure {
                pendingOverlayStart = false
                Toast.makeText(
                    this,
                    R.string.overlay_permission_settings_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        FloatingBallService.start(this)
        // 无障碍服务没开时球会一直在屏幕上，文案得说清楚差别，否则用户会以为坏了。
        Toast.makeText(
            this,
            if (AutoSendService.isConnected) R.string.overlay_started
            else R.string.overlay_started_manual,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setupSearchBar() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                // 防抖：连续输入只查最后一次，避免每敲一个字就打一次数据库
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    viewModel.setSearchQuery(query)
                }
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
        val target = listOf("全部", "收藏") +
                categories.filter { it != "全部" && it != "收藏" }.distinct()

        // 分类集合没变时（绝大多数发射都是这种）只刷新选中态、不碰视图，
        // 避免整栏重建带来的闪烁与横向滚动位置丢失。
        if (currentCategoryChips.map { it.text.toString() } == target) {
            updateCategorySelection(viewModel.selectedCategory.value)
            return
        }

        // 结构确实变了：按文案复用已有 chip，只创建新增的那些
        val reusable = currentCategoryChips.associateBy { it.text.toString() }
        val chips = target.map { name -> reusable[name] ?: createCategoryChip(name) }

        currentCategoryChips = chips
        categoryContainer.removeAllViews()
        chips.forEach { categoryContainer.addView(it) }
        categoryContainer.addView(createCategoryManageChip())
        updateCategorySelection(viewModel.selectedCategory.value)
    }

    private fun createCategoryChip(category: String): TextView = TextView(this).apply {
        text = category
        setPadding(32, 12, 32, 12)
        textSize = 14f
        setOnClickListener {
            viewModel.setSelectedCategory(category)
            updateCategorySelection(category)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 8 }
    }

    /** 分类栏末尾的固定入口；不加入 [currentCategoryChips]，因此不参与选中态。 */
    private fun createCategoryManageChip(): TextView = TextView(this).apply {
        text = getString(R.string.btn_manage_category)
        setPadding(32, 12, 32, 12)
        textSize = 14f
        setBackgroundResource(R.drawable.category_chip_bg)
        setOnClickListener { showCategoryManager() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 8 }
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

    /** 自定义分类（排除「全部」「收藏」两个虚拟项）。 */
    private fun customCategories(): List<String> =
        viewModel.categories.value.filter { it != "全部" && it != "收藏" }

    private fun showCategoryManager() {
        val custom = customCategories()
        if (custom.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_manage_title)
                .setMessage(R.string.category_manage_empty)
                .setPositiveButton(R.string.category_manage_create) { _, _ ->
                    showCategoryInputDialog(
                        getString(R.string.dialog_category_title),
                        ""
                    ) { name -> viewModel.addCategory(name) }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_manage_title)
            .setItems(custom.toTypedArray()) { _, which ->
                showCategoryActions(custom[which])
            }
            .setNeutralButton(R.string.category_manage_create) { _, _ ->
                showCategoryInputDialog(
                    getString(R.string.dialog_category_title),
                    ""
                ) { name -> viewModel.addCategory(name) }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showCategoryActions(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(
                arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))
            ) { _, which ->
                when (which) {
                    0 -> showCategoryInputDialog(
                        getString(R.string.category_rename_title),
                        name
                    ) { newName ->
                        if (newName != name) viewModel.renameCategory(name, newName)
                    }
                    else -> confirmDeleteCategory(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteCategory(name: String) {
        // 「默认」是数据库 category 列的真实取值，不是纯展示文案，故保持字面量
        if (name == "默认") {
            Toast.makeText(this, R.string.category_default_immutable, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_delete_title)
            .setMessage(getString(R.string.category_delete_message, name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteCategory(name) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showCategoryInputDialog(
        title: String,
        initial: String,
        onConfirm: (String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_category, null)
        val input = view.findViewById<EditText>(R.id.categoryInput)
        input.setText(initial)
        input.setSelection(initial.length)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.category_name_empty, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 「复制」按钮：把这张表情以图片剪贴板（content URI + 真实 mime）的形式放进系统剪贴板。
     *
     * 这一步和发送模块里静态图走的是同一条路（EmojiShare.copyToClipboard），
     * 意义在于让用户自己去微信长按输入框「粘贴」——用来判定微信认不认 GIF 剪贴板，
     * 也就是「0 点击 + 保动画」那条路线唯一的未知点。
     */
    private fun copyEmojiToClipboard(emoji: EmojiEntity) {
        val kind = if (emoji.fileType.equals("gif", ignoreCase = true)) "动图 gif" else emoji.fileType
        val ok = EmojiShare.copyToClipboard(this, emoji)
        val text = if (ok) {
            getString(R.string.copy_ok_toast, emoji.name, kind)
        } else {
            getString(R.string.copy_fail_toast)
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
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
                // 只写 isFavorite 一列，避免整行覆盖清空 tags / usageCount 等字段
                viewModel.updateFavorite(emoji.id, !emoji.isFavorite)
            },
            isSelectionMode = { viewModel.isSelectionMode.value },
            selectedIds = { viewModel.selectedIds.value },
            onCopyClick = { emoji -> copyEmojiToClipboard(emoji) }
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
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(getString(R.string.delete_confirm_selected_message, count))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        viewModel.deleteSelected()
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
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
                btnDelete.text = getString(R.string.btn_delete_count, selectedIds.size)
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedCategory.collectLatest { selected ->
                updateCategorySelection(selected)
            }
        }

        lifecycleScope.launch {
            viewModel.message.collectLatest { msg ->
                if (msg != null) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    viewModel.consumeMessage()
                }
            }
        }
    }

    private fun importImage(uri: android.net.Uri) {
        lifecycleScope.launch {
            val filePath = ImageUtil.copyImageToInternal(this@MainActivity, uri)
            if (filePath != null) {
                val (width, height) = ImageUtil.getImageDimensions(filePath)
                val emoji = EmojiEntity(
                    name = getString(R.string.emoji_default_name, System.currentTimeMillis()),
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
                Toast.makeText(this@MainActivity, R.string.toast_import_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openEmojiDetail(emoji: EmojiEntity) {
        // 只传 id，详情页按 id 订阅数据库真值，避免搬运残缺实体导致字段被覆盖
        val intent = Intent(this, EmojiDetailActivity::class.java).apply {
            putExtra(EmojiDetailActivity.EXTRA_EMOJI_ID, emoji.id)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isSelectionMode.value) {
            viewModel.toggleSelectionMode()
        }
        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {
            pendingOverlayStart = false
            FloatingBallService.start(this)
            Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_LONG).show()
        }
        // 无障碍服务可能刚在系统设置里被打开或关掉，回来时同步状态条。
        refreshSenderStatus()
        refreshGifRoute()
        // 悬浮球开关状态可能在本界面不可见时变过，回来时刷新菜单标题。
        invalidateOptionsMenu()
    }
}
