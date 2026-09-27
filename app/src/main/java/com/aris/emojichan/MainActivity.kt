package com.aris.emojichan

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.sender.AutoSendService
import com.aris.emojichan.sender.EmojiShare
import com.aris.emojichan.sender.FloatingBallService
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aris.emojichan.sender.SendLogActivity
import com.aris.emojichan.sender.SenderPrefs

/** 搜索防抖窗口：停止输入多久之后才真正查询数据库。 */
private const val SEARCH_DEBOUNCE_MS = 250L

/** 悬浮球服务从 start() 到真的把球挂上，中间隔着一次 onCreate；这段时间 isRunning 还是 false。 */
private const val OVERLAY_SWITCH_SETTLE_MS = 600L

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
    private lateinit var senderBanner: TextView
    private lateinit var emojiPage: View
    private lateinit var settingsPage: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var switchOverlay: MaterialSwitch
    private lateinit var gifToggle: MaterialButtonToggleGroup
    private lateinit var gifHint: TextView
    private lateinit var settingsVersion: TextView
    private lateinit var themeSwatch: TextView
    private lateinit var settingsTheme: TextView
    private lateinit var settingsNight: TextView
    private lateinit var settingsBall: TextView
    private lateinit var ballSizeValue: TextView
    private lateinit var ballSizeSlider: Slider

    /** 程序改开关/选项状态时别再回调自己一次，否则会和用户操作来回打架。 */
    private var syncingOverlaySwitch = false
    private var syncingGifToggle = false

    private var currentCategoryChips: List<Chip> = emptyList()

    /** 搜索防抖用的任务句柄：新输入到来时取消上一次尚未触发的查询。 */
    private var searchJob: Job? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importImage(it) }
    }

    /** 从相册挑一张图当悬浮球：只是换个外观，不落进表情库。 */
    private val ballPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { saveBallImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色是「基础主题 + overlay」，必须赶在 setContentView 之前套上。
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]

        initViews()
        setupToolbar()
        setupSearchBar()
        setupCategoryTabs()
        setupEmojiGrid()
        setupBottomBar()
        setupBottomNav()
        setupBackPress()
        observeViewModel()
        // 从系统分享面板进来的图片 / GIF：界面都准备好了再导入，导完就能在表情页看到。
        handleShareIntent(intent)
    }

    /**
     * 底栏选中项是系统替我们恢复的（在 onRestoreInstanceState 里），两个页面的可见性却是我们自己管的。
     * 主题色一改会重建 Activity，不在这里对一次，就会出现「底栏停在设置、页面却是表情页」的错位。
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        showSettingsPage(bottomNav.selectedItemId == R.id.tab_settings)
    }

    /** 本应用开着时又分享进来（singleTop）：走这里，不重新创建界面。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        // 标题栏副标题显示版本号，和 APK 文件名里的版本对得上，方便确认装的是哪一版。
        toolbar.subtitle = "v" + versionName

        emojiPage = findViewById(R.id.emojiPage)
        settingsPage = findViewById(R.id.settingsPage)
        bottomNav = findViewById(R.id.bottomNav)

        searchBar = findViewById(R.id.searchBar)
        categoryContainer = findViewById(R.id.categoryContainer)
        emojiGrid = findViewById(R.id.emojiGrid)
        emptyView = findViewById(R.id.emptyView)
        bottomBar = findViewById(R.id.bottomBar)
        btnImport = findViewById(R.id.btnImport)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancel = findViewById(R.id.btnCancel)

        // 主界面只留「服务没开」这一条提示（开着时它不占地方），点它直接去系统设置。
        senderBanner = findViewById(R.id.senderBanner)
        senderBanner.setOnClickListener { openAccessibilitySettings() }

        senderStatus = findViewById(R.id.senderStatus)
        findViewById<View>(R.id.rowA11y).setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.rowLog).setOnClickListener {
            startActivity(Intent(this, SendLogActivity::class.java))
        }

        switchOverlay = findViewById(R.id.switchOverlay)
        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (!syncingOverlaySwitch) setOverlayEnabled(checked)
        }

        gifToggle = findViewById(R.id.gifToggle)
        gifHint = findViewById(R.id.gifHint)
        gifToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !syncingGifToggle) {
                applyGifRoute(album = checkedId == R.id.btnGifAlbum, fromUser = true)
            }
        }

        settingsVersion = findViewById(R.id.settingsVersion)
        settingsVersion.text = getString(R.string.settings_version_sub, versionName)

        setupUiSettings()
    }

    /** 底部两页：表情 / 设置。两个页面都在同一个 Activity 里，切换只是显示和隐藏。 */
    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_emoji -> {
                    showSettingsPage(false)
                    true
                }

                R.id.tab_settings -> {
                    showSettingsPage(true)
                    true
                }

                else -> false
            }
        }
    }

    private fun showSettingsPage(show: Boolean) {
        emojiPage.visibility = if (show) View.GONE else View.VISIBLE
        settingsPage.visibility = if (show) View.VISIBLE else View.GONE
        if (show) refreshSettings()
    }

    /** 设置页每次露面都重新取一遍状态：权限可能刚在系统设置里被改过。 */
    private fun refreshSettings() {
        refreshSenderStatus()
        refreshGifRoute()
        refreshUiSettings()
    }


    // ---------- UI 设置：主题色 / 深浅色 / 悬浮球 ----------

    /**
     * 主题色走「基础主题 + overlay」：overlay 只覆盖 Material 的颜色槽位，
     * 所以改一次，按钮、chip、滑杆、对话框全都跟着变，不用一个个改 View。
     */
    private fun setupUiSettings() {
        themeSwatch = findViewById(R.id.themeSwatch)
        settingsTheme = findViewById(R.id.settingsTheme)
        settingsNight = findViewById(R.id.settingsNight)
        settingsBall = findViewById(R.id.settingsBall)
        ballSizeValue = findViewById(R.id.ballSizeValue)
        ballSizeSlider = findViewById(R.id.ballSizeSlider)

        findViewById<View>(R.id.rowTheme).setOnClickListener { showThemeDialog() }
        findViewById<View>(R.id.rowNight).setOnClickListener { showNightDialog() }
        findViewById<View>(R.id.rowBall).setOnClickListener { showBallDialog() }

        ballSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) ballSizeValue.text = getString(R.string.ball_size_value, value.toInt())
        }
        ballSizeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                UiPrefs.setBallSizeDp(this@MainActivity, slider.value.toInt())
                // 改完大小顺手贴边：球变大变小后原来的贴边位置会跑偏。
                refreshBallIfRunning(snapToEdge = true)
            }
        })
    }

    /** 设置页那三行副文案 + 色块 + 滑杆位置，都按当前偏好重新贴一遍。 */
    private fun refreshUiSettings() {
        val themeKey = UiPrefs.themeKey(this)
        settingsTheme.text = getString(themeLabelOf(themeKey))
        themeSwatch.backgroundTintList = ColorStateList.valueOf(getColor(themeColorOf(themeKey)))

        settingsNight.text = getString(
            when (UiPrefs.nightChoice(this)) {
                UiPrefs.NIGHT_LIGHT -> R.string.night_light
                UiPrefs.NIGHT_DARK -> R.string.night_dark
                else -> R.string.night_follow
            }
        )
        settingsBall.text = getString(
            when (UiPrefs.ballStyle(this)) {
                UiPrefs.BALL_DOT -> R.string.ball_style_dot
                UiPrefs.BALL_CUSTOM -> R.string.ball_style_custom
                else -> R.string.ball_style_default
            }
        )

        val size = UiPrefs.ballSizeDp(this)
        ballSizeValue.text = getString(R.string.ball_size_value, size)
        if (ballSizeSlider.value != size.toFloat()) ballSizeSlider.value = size.toFloat()
    }

    private fun themeLabelOf(key: String): Int = when (key) {
        UiPrefs.THEME_PURPLE -> R.string.theme_purple
        UiPrefs.THEME_BLUE -> R.string.theme_blue
        UiPrefs.THEME_GREEN -> R.string.theme_green
        UiPrefs.THEME_ORANGE -> R.string.theme_orange
        UiPrefs.THEME_PINK -> R.string.theme_pink
        else -> R.string.theme_navy
    }

    /** 色块的圆点颜色：直接取调色板资源，省得在代码里解析主题属性。 */
    private fun themeColorOf(key: String): Int = when (key) {
        UiPrefs.THEME_PURPLE -> R.color.palette_purple_primary
        UiPrefs.THEME_BLUE -> R.color.palette_blue_primary
        UiPrefs.THEME_GREEN -> R.color.palette_green_primary
        UiPrefs.THEME_ORANGE -> R.color.palette_orange_primary
        UiPrefs.THEME_PINK -> R.color.palette_pink_primary
        else -> R.color.palette_navy_primary
    }

    private fun showThemeDialog() {
        val keys = UiPrefs.THEMES
        val labels = keys.map { getString(themeLabelOf(it)) }.toTypedArray()
        val checked = keys.indexOf(UiPrefs.themeKey(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_theme_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val key = keys[which]
                if (key != UiPrefs.themeKey(this)) {
                    UiPrefs.setThemeKey(this, key)
                    // 悬浮球是 Service 挂的窗口，重建本界面轮不到它：
                    // 叫它按新主色重画一遍（球的描边就是 ?attr/colorPrimary）。
                    FloatingBallService.refreshAppearance()
                    // overlay 得在 setContentView 之前生效，所以重建一遍：
                    // 数据在 ViewModel / Room 里，重建不会丢。
                    recreate()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showNightDialog() {
        val choices = intArrayOf(UiPrefs.NIGHT_FOLLOW, UiPrefs.NIGHT_LIGHT, UiPrefs.NIGHT_DARK)
        val labels = arrayOf(
            getString(R.string.night_follow),
            getString(R.string.night_light),
            getString(R.string.night_dark)
        )
        val checked = choices.indexOf(UiPrefs.nightChoice(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_night_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                // setNightChoice 内部会让 AppCompat 重建界面；但悬浮球是 Service 的窗口，
                // 重建界面轮不到它 —— 不叫这一声，球会一直停在旧深浅色上。
                UiPrefs.setNightChoice(this, choices[which])
                FloatingBallService.refreshAppearance()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showBallDialog() {
        val labels = arrayOf(
            getString(R.string.ball_style_default),
            getString(R.string.ball_style_dot),
            getString(R.string.ball_pick)
        )
        val current = when (UiPrefs.ballStyle(this)) {
            UiPrefs.BALL_DOT -> 1
            UiPrefs.BALL_CUSTOM -> 2
            else -> 0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_ball_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    1 -> {
                        UiPrefs.setBallStyle(this, UiPrefs.BALL_DOT)
                        refreshUiSettings()
                        refreshBallIfRunning()
                    }

                    2 -> ballPicker.launch("image/*")

                    else -> {
                        UiPrefs.setBallStyle(this, UiPrefs.BALL_DEFAULT)
                        refreshUiSettings()
                        refreshBallIfRunning()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** 用户挑的球图复制进私有目录：不看相册权限的脸色，也不怕原图被删。 */
    private fun saveBallImage(uri: android.net.Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val target = UiPrefs.ballFile(this@MainActivity)
                    target.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target.exists() && target.length() > 0L
                }.getOrDefault(false)
            }

            if (ok) {
                UiPrefs.setBallStyle(this@MainActivity, UiPrefs.BALL_CUSTOM)
                refreshUiSettings()
                refreshBallIfRunning()
                Toast.makeText(this@MainActivity, R.string.ball_pick_ok, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.ball_pick_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 球正挂在屏幕上时，改大小 / 换样式得重挂一次才看得见。 */
    private fun refreshBallIfRunning(snapToEdge: Boolean = false) {
        if (!FloatingBallService.isRunning) return
        FloatingBallService.stop(this)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) FloatingBallService.start(this, snapToEdge)
        }, 250L)
    }

    // ---------- 接收系统分享 ----------

    /**
     * 别的应用分享图片 / GIF 过来（清单里注册了 ACTION_SEND / SEND_MULTIPLE + image 类型）。
     * 收下就入库，落到表情页，让用户马上看到 —— 不做「要不要导入」的二次确认，
     * 分享本身就是用户的确认动作。
     */
    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        @Suppress("DEPRECATION")
        val uris: List<android.net.Uri> = if (action == Intent.ACTION_SEND) {
            listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        } else {
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }

        // 处理完就把这次分享从 Intent 上抹掉：转屏、回前台时 onCreate 拿到的是同一个
        // Intent，不抹掉就会把同一张图反复导进来。
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        if (uris.isEmpty()) return

        showEmojiPage()
        lifecycleScope.launch {
            var ok = 0
            uris.forEach { if (copyUriToLibrary(it)) ok++ }
            val text = if (ok > 0) {
                getString(R.string.share_import_ok, ok)
            } else {
                getString(R.string.share_import_failed)
            }
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    /** 分享进来的图要落在表情页上，别让用户对着设置页找。 */
    private fun showEmojiPage() {
        if (bottomNav.selectedItemId == R.id.tab_emoji) {
            showSettingsPage(false)
        } else {
            bottomNav.selectedItemId = R.id.tab_emoji
        }
    }

    /** 在设置页按返回先回表情页，而不是直接退出应用。 */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPage.visibility == View.VISIBLE) {
                    bottomNav.selectedItemId = R.id.tab_emoji
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
        senderStatus.setTextColor(getColor(if (on) R.color.status_on else R.color.status_off))
        // 只有关着的时候主界面才顶一条提示：开着就不占地方。
        senderBanner.visibility = if (on) View.GONE else View.VISIBLE
    }

    /**
     * 微信动图走哪条路。默认「分享」：选完表情，在微信的选人页点一下聊天对象就发出去。
     * 也可以切回「+ → 相册」。两条路都实测保动画，区别只是相册要多翻两个页面。
     * 这一项只影响「微信里发动图」，静态图和 QQ 完全不受影响。
     */
    private fun refreshGifRoute() {
        val album = SenderPrefs.wechatGifViaAlbum(this)
        syncingGifToggle = true
        gifToggle.check(if (album) R.id.btnGifAlbum else R.id.btnGifShare)
        syncingGifToggle = false
        gifHint.setText(
            if (album) R.string.settings_gif_hint_album else R.string.settings_gif_hint_share
        )
    }

    private fun applyGifRoute(album: Boolean, fromUser: Boolean) {
        SenderPrefs.setWechatGifViaAlbum(this, album)
        gifHint.setText(
            if (album) R.string.settings_gif_hint_album else R.string.settings_gif_hint_share
        )
        if (fromUser) {
            Toast.makeText(
                this,
                if (album) R.string.gif_route_switched_album else R.string.gif_route_switched_share,
                Toast.LENGTH_LONG
            ).show()
        }
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

    /** 用户在系统设置里授权后要接着把悬浮球开起来，免得回来还得再点一次。 */
    private var pendingOverlayStart = false

    /**
     * 设置页里的悬浮球开关。
     *
     * 只需要「显示在其他应用上层」这一个权限：不用无障碍，也就没有
     * Android 13 那套「受限设置」引导。权限还没给时先把开关弹回去，
     * 跳去系统设置，回来时 onResume 接着把它打开。
     */
    private fun setOverlayEnabled(enable: Boolean) {
        if (enable == FloatingBallService.isRunning) return

        if (!enable) {
            FloatingBallService.stop(this)
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            syncOverlaySwitch(false)
            pendingOverlayStart = true
            Toast.makeText(this, R.string.settings_overlay_permission_needed, Toast.LENGTH_LONG).show()
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

    /** 程序性地同步开关状态：不触发上面那套逻辑。 */
    private fun syncOverlaySwitch(checked: Boolean) {
        syncingOverlaySwitch = true
        switchOverlay.isChecked = checked
        syncingOverlaySwitch = false
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

    /** Material Chip：选中态由 state_checked 驱动，红/紫配色一次定义完，不用再切背景图。 */
    private fun createCategoryChip(category: String): Chip = Chip(this).apply {
        text = category
        textSize = 14f
        isCheckable = true
        isChecked = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(false)
        chipStrokeWidth = resources.displayMetrics.density
        chipStrokeColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary), getColor(R.color.divider))
        )
        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary), getColor(R.color.surface))
        )
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary), getColor(R.color.text_primary))
            )
        )
        setOnClickListener {
            viewModel.setSelectedCategory(category)
            updateCategorySelection(category)
        }
        layoutParams = chipLayoutParams()
    }

    /** 分类栏末尾的固定入口；不加入 [currentCategoryChips]，因此不参与选中态。 */
    private fun createCategoryManageChip(): Chip = Chip(this).apply {
        text = getString(R.string.btn_manage_category)
        textSize = 14f
        isCheckable = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(false)
        chipStrokeWidth = 0f
        chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer))
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer))
        setOnClickListener { showCategoryManager() }
        layoutParams = chipLayoutParams()
    }

    private fun chipLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = 8 }

    private fun updateCategorySelection(selected: String) {
        currentCategoryChips.forEach { chip ->
            chip.isChecked = chip.text.toString() == selected
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
            // 直接拉起系统的「选择图片」：ACTION_GET_CONTENT 本来就不需要任何存储权限，
            // 而 Android 14 的「仅选择的照片」会让 READ_MEDIA_IMAGES 一直返回拒绝 ——
            // 原来的权限门槛把导入卡死在「权限被拒」上，进不去也退不出来（emc-1-021）。
            imagePicker.launch("image/*")
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
            // 落库要整份拷贝文件、还要解码图片读宽高，全是阻塞 IO：
            // 放在主线程上，导入一张大图就会卡住界面甚至 ANR（emc-1-018）。
            val ok = withContext(Dispatchers.IO) { copyUriToLibrary(uri) }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.toast_import_success else R.string.toast_import_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 把一个外部 Uri 落进表情库，返回是否成功。
     * 相册点「导入」和系统分享面板进来共用这一段，免得两处逻辑跑偏。
     */
    private suspend fun copyUriToLibrary(uri: android.net.Uri): Boolean {
        val filePath = ImageUtil.copyImageToInternal(this, uri) ?: return false
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
        return true
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
        var justStarted = false
        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {
            pendingOverlayStart = false
            FloatingBallService.start(this)
            justStarted = true
            Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_LONG).show()
        }
        if (justStarted) {
            // start() 只是把服务拉起来，这一刻它还跑到 onStartCommand 呢，
            // isRunning 仍是 false —— 立刻同步会把刚打开的开关又拨回「关」（emc-1-036）。
            // 等它把球挂上去之后再同步一次。
            window.decorView.postDelayed(
                { syncOverlaySwitch(FloatingBallService.isRunning) },
                OVERLAY_SWITCH_SETTLE_MS
            )
        } else {
            // 无障碍 / 悬浮窗权限 / 悬浮球运行状态都可能刚在系统设置里被改过，
            // 回来统一同步一遍：开关、状态条、动图路线、版本号。
            syncOverlaySwitch(FloatingBallService.isRunning)
        }
        refreshSettings()
    }
}
