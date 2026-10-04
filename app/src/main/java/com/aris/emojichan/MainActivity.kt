package com.aris.emojichan

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.data.EmojiDefaults
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiFilter
import com.aris.emojichan.data.TagEntity
import com.aris.emojichan.data.TagMode
import com.aris.emojichan.sender.AutoSendService
import com.aris.emojichan.sender.BallScope
import com.aris.emojichan.sender.BallTileService
import com.aris.emojichan.sender.FloatingBallService
import com.aris.emojichan.sender.SendLog
import com.aris.emojichan.storage.ExportTarget
import com.aris.emojichan.storage.ImageExporter
import com.aris.emojichan.storage.StorageActivity
import com.aris.emojichan.util.BusyDialog
import com.aris.emojichan.util.EmojiArchive
import com.aris.emojichan.util.EmojiImport
import com.aris.emojichan.util.ImageUtil
import com.aris.emojichan.util.ImportOrder
import com.aris.emojichan.util.RecentsHider
import com.aris.emojichan.util.SourceFiles
import com.aris.emojichan.viewmodel.EmojiViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aris.emojichan.sender.SenderPrefs

/** 搜索防抖窗口：停止输入多久之后才真正查询数据库。 */
private const val SEARCH_DEBOUNCE_MS = 250L

/** 搜索补全一次最多给几条候选：再多列表里就光剩滚了。 */
private const val MAX_SEARCH_SUGGESTIONS = 8

/** 候选列表最多占多高（dp）：超出的部分在列表里滚，不整页往下顶。 */
private const val MAX_SUGGESTION_HEIGHT_DP = 240

/** 候选列表跟搜索框之间留的空隙（dp）。 */
private const val SUGGESTION_GAP_DP = 4

/** 筛选条一次刷新要用到的几项条件。 */
private data class FilterState(
    /** 按名字序的一份：筛选条上那几颗已选标签按它取名字。 */
    val tags: List<TagEntity>,
    /** 按用量序的一份（`viewModel.tagPanelTags`）：标签面板铺开的顺序，用户 m11668 第 7 条。 */
    val panelTags: List<TagEntity>,
    val ids: Set<Long>,
    val tagModes: Map<Long, TagMode>,
    val favoritesOnly: Boolean,
    val recentOnly: Boolean
)

/** 搜索补全的一条候选：[label] 是列表里显示的，[insert] 是点下去真正写进搜索框的。 */
private data class SearchSuggestion(val label: String, val insert: String)

/** 悬浮球服务从 start() 到真的把球挂上，中间隔着一次 onCreate；这段时间 isRunning 还是 false。 */
private const val OVERLAY_SWITCH_SETTLE_MS = 600L

/** 网格往下滚过这么多 dp 才让「回到置顶」露头，免得刚进列表就挂着一颗按钮（用户 m09998）。 */
private const val SCROLL_TOP_SHOW_DP = 300

/** 「回到置顶」的滚屏速度（毫秒 / 英寸）：系统默认 25，几千张时能滑好几秒（用户 m10115）。 */
private const val SCROLL_TOP_MS_PER_INCH = 4f

/** ACTION_PICK_IMAGES 一次最多让选几张（系统另有上限 [MediaStore.getPickImagesMaxLimit]）。 */
private const val MAX_PICK_IMAGES = 100

/** 文件选择器背后的 MediaStore 文档 provider（同一个 id，它肯说真名 —— emc-2-008 实测）。 */
private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: EmojiViewModel
    private lateinit var adapter: EmojiGridAdapter

    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchBar: EditText

    /** 筛选条：只显示当前正在生效的筛选（收藏 + 已选中的标签）。 */
    private lateinit var filterContainer: LinearLayout

    /** 筛选条最左侧的展开按钮，点开/收起标签面板。 */
    private lateinit var btnExpandTags: MaterialButton
    private lateinit var tagPanel: View
    private lateinit var tagActions: ChipGroup
    private lateinit var tagGrid: ChipGroup

    /** 筛选条上现有的 chip，用来原位刷新选中态，而不是整条重建。 */
    private var filterChips: List<Chip> = emptyList()

    /** 标签面板当前画的是哪一套内容（标签名 + 匹配方式）；一样时只刷新选中态。 */
    private var tagPanelKey: String = ""
    private lateinit var emojiGrid: RecyclerView
    private lateinit var emptyView: TextView

    /** 网格右下角那颗「回到置顶」，滚过一段距离才显示（用户 m09998）。 */
    private lateinit var btnScrollTop: View

    /** 标题行上的排序按钮。 */
    private lateinit var btnSort: View
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnImport: Button
    private lateinit var btnDelete: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnCancel: Button
    private lateinit var btnOrganize: Button
    private lateinit var btnExport: Button
    private lateinit var senderStatus: TextView
    private lateinit var a11yIndicator: View
    private lateinit var a11yDot: View
    private lateinit var emojiPage: View
    private lateinit var settingsPage: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var switchOverlay: MaterialSwitch
    private lateinit var switchAutoConfirm: MaterialSwitch
    private lateinit var confirmHint: TextView
    private lateinit var ballAppsValue: TextView
    private lateinit var tileState: TextView
    private lateinit var settingsVersion: TextView

    /**
     * 版本号的唯一出处：问包管理器。不用 BuildConfig —— 它在 release 下可能被 R8 改名 / 内联，
     * 而这个调用永远在。
     */
    private val appVersion: String
        get() = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    private lateinit var themeSwatch: TextView
    private lateinit var settingsTheme: TextView
    private lateinit var settingsNight: TextView
    private lateinit var settingsBall: TextView
    private lateinit var ballSizeValue: TextView
    private lateinit var ballSizeSlider: Slider

    /** 程序改开关/选项状态时别再回调自己一次，否则会和用户操作来回打架。 */
    private var syncingOverlaySwitch = false
    private var syncingConfirmSwitch = false

    /** 搜索防抖用的任务句柄：新输入到来时取消上一次尚未触发的查询。 */
    private var searchJob: Job? = null

    /**
     * 补全候选的弹出列表。
     *
     * 用弹窗而不是页面里的一行：候选展开时不该把下面的表情挤走。它不抢焦点，
     * 所以键盘不会收起来，光标也一直留在搜索框里。
     */
    private var suggestionPopup: PopupWindow? = null

    /** 语法快捷键（$TAG=、@、&、/）那一排，以及右边那颗「用法」。 */
    private lateinit var searchSyntaxButtons: LinearLayout
    private lateinit var searchSyntaxHint: TextView

    /**
     * 导入选择器：用 GetMultipleContents 让系统相册一次能勾多张。
     * 老机型/部分相册不支持多选时它只回一张，语义和原来一样，不会退化。
     */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) importImages(uris)
    }

    /**
     * ACTION_PICK_IMAGES（系统相册选择器的正式 API，Android 13+）的结果：
     * 多选走 `clipData`，单选走 `data` —— 取值方式与系统分享面板同一种。
     */
    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
        }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) importImages(uris)
    }

    /**
     * 「相册导入」的正式入口：Android 13+ 优先用系统相册选择器自己的 API（ACTION_PICK_IMAGES）。
     *
     * 为什么要换：ACTION_GET_CONTENT 被系统转交给选择器时给的是 `picker_get_content` 那种
     * 「取内容」形态 —— 系统交出的是**合成副本**（日志里 DATA 落在
     * `/sdcard/.transforms/synthetic/...`），副本名由选择器自己的 id 拼出来，真名不在里面，
     * MediaStore.getMediaUri 也救不回来（emc-2-008）。ACTION_PICK_IMAGES 给的是 `picker` 形态的
     * Uri，对应真实媒体行，才有真名可问。系统没有这个入口（API < 33 / 厂商没提供）时退回老路。
     */
    private fun launchAlbumPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val max = runCatching { MediaStore.getPickImagesMaxLimit() }
                .getOrDefault(MAX_PICK_IMAGES)
                .coerceAtLeast(1)
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max)
            }
            if (intent.resolveActivity(packageManager) != null) {
                SendLog.d("导入", "相册入口：ACTION_PICK_IMAGES（上限 " + max + " 张）")
                pickImagesLauncher.launch(intent)
                return
            }
        }
        SendLog.d("导入", "相册入口：ACTION_GET_CONTENT（系统没有 ACTION_PICK_IMAGES）")
        imagePicker.launch("image/*")
    }

    /**
     * 「从文件导入」：系统文件选择器（DocumentsUI）给的显示名就是磁盘上的真文件名，
     * 不像图片选择器那样在本地索引里查不到时只肯给一串 id（emc-2-008）。
     * 相册那条路依旧保留：好看、能多选，名字能拿到时也更省事。
     */
    private val filePicker = registerForActivityResult(ImagesDocumentContract()) { uris ->
        if (uris.isNotEmpty()) importImages(uris)
    }

    /**
     * 「从文件夹导入」：选一个文件夹，把它（含子文件夹）里的图片整批导进来，
     * 每层文件夹各建一个以自己名字命名的标签。系统给的是整棵树的读权限，
     * 不需要任何存储权限，也不用逐个文件确认。
     */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) importFolder(uri)
    }

    /**
     * 相册读权限：**只为导入时问得出原文件名**（emc-2-008），不申请别的任何东西。
     * 没有它照样能导入，只是图片选择器只肯给 id 时我们没资格去别处问真名。
     */
    private val mediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val level = ImageUtil.mediaAccessLevel(this)
        SendLog.d(
            "导入",
            "相册权限结果=" + level + "（" + result.entries.joinToString {
                it.key.substringAfterLast('.') + "=" + it.value
            } + "）"
        )
        Toast.makeText(this, getString(R.string.import_grant_media_result, level), Toast.LENGTH_LONG)
            .show()
    }

    /** 申请相册读权限（13+ 是 READ_MEDIA_IMAGES；14+ 连「仅选择的照片」一起要）。 */
    private fun requestMediaPermission() {
        val level = ImageUtil.mediaAccessLevel(this)
        if (level != "无") {
            Toast.makeText(this, getString(R.string.import_grant_media_result, level), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val wanted = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissions.launch(wanted)
    }

    /**
     * 文件导入用 OpenMultipleDocuments，但把起始位置指到 DocumentsUI 的「图片」根 ——
     * 用起来也像相册（按时间的缩略图网格），而且显示名就是磁盘上的真文件名（emc-2-008）。
     */
    private class ImagesDocumentContract : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(MEDIA_DOCUMENTS_AUTHORITY, "images_root")
            )
    }

    /** 从相册挑一张图当悬浮球：只是换个外观，不落进表情库。 */
    private val ballPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { saveBallImage(it) }
    }

    /**
     * 「删掉导入的原文件」这一步交给系统的批量删除框：用户点了同意才会真删。
     * 程序自己删不掉相册里别人的图，也不该删得掉。
     */
    private val mediaStoreDeleteLauncher = registerForActivityResult(
        // 批量删除框是 PendingIntent，要用 IntentSender 版契约（Intent 版接不了）
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            reportSourceDelete(pendingDocDeleted + pendingMediaCount, pendingSkipped)
        } else {
            Toast.makeText(this, R.string.source_delete_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** 导出备份包：交给系统的「保存到…」，文件名先给个带时间戳的建议值。 */
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument(EmojiArchive.MIME)
    ) { uri -> if (uri != null) runExport(uri) }

    /** 导入备份包：备份包自己报 octet-stream（.emcpack 是自定义后缀，系统认不出它的类型）。 */
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) askImportMode(uri) }

    /** 批量导出成一个 zip：建议名带时间戳，存哪儿由用户挑。 */
    private val exportZipPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) runExportZip(uri) }

    /** 批量导出到一个文件夹：逐张写进去，重名的自动加序号。 */
    private val exportFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) runExportFolder(uri) }

    /** 删原文件时的中间账：文档类已经删掉几个、有几个媒体文件在等系统确认、有几个删不掉。 */
    private var pendingDocDeleted = 0
    private var pendingMediaCount = 0
    private var pendingSkipped = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色是「基础主题 + overlay」，必须赶在 setContentView 之前套上。
        UiPrefs.applyTheme(this)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[EmojiViewModel::class.java]
        // 导入时要把「上游给了什么名字」写进发送日志，所以这里就把日志准备好：
        // 以前只有两个 Service 会 init，用户从没开过悬浮球时日志文件根本不存在。
        SendLog.init(this)

        initViews()
        setupToolbar()
        setupSearchBar()
        setupFilterBar()
        setupEmojiGrid()
        setupBottomBar()
        setupBottomNav()
        setupBackPress()
        observeViewModel()
        // 是不是「从外面进来的」，要在下面两个 handle 之前问：它们会把 Intent 上的东西抹掉。
        val fromShare = intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE
        val fromTile = intent?.getBooleanExtra(BallTileService.EXTRA_FROM_TILE, false) == true
        // 从系统分享面板进来的图片 / GIF：界面都准备好了再导入，导完就能在表情页看到。
        handleShareIntent(intent)
        handleTileIntent(intent)
        maybeShowOnboarding(savedInstanceState, fromShare, fromTile)
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
        handleTileIntent(intent)
    }

    /**
     * 第一次正常启动时先走一遍使用引导（v0.1.414，用户 m09215）。
     *
     * 三种情况不打扰：转屏 / 配置变更导致的重建（savedInstanceState 非空）、
     * 从系统分享面板进来（用户是来存图的，等他导完；下次正常启动还会出现）、
     * 从下拉状态栏的磁贴进来（用户点的是球的开关，不该被一页引导挡住）。
     */
    private fun maybeShowOnboarding(
        savedInstanceState: Bundle?,
        fromShare: Boolean,
        fromTile: Boolean
    ) {
        if (savedInstanceState != null || fromShare || fromTile) return
        if (OnboardingPrefs.isDone(this)) return
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        val versionName = appVersion
        // 标题栏副标题显示版本号，和 APK 文件名里的版本对得上，方便确认装的是哪一版。
        toolbar.subtitle = "v" + versionName

        emojiPage = findViewById(R.id.emojiPage)
        settingsPage = findViewById(R.id.settingsPage)
        bottomNav = findViewById(R.id.bottomNav)

        searchBar = findViewById(R.id.searchBar)
        searchSyntaxButtons = findViewById(R.id.searchSyntaxButtons)
        searchSyntaxHint = findViewById(R.id.searchSyntaxHint)
        filterContainer = findViewById(R.id.filterContainer)
        btnExpandTags = findViewById(R.id.btnExpandTags)
        tagPanel = findViewById(R.id.tagPanelScroll)
        tagActions = findViewById(R.id.tagActions)
        tagGrid = findViewById(R.id.tagGrid)
        emojiGrid = findViewById(R.id.emojiGrid)
        emptyView = findViewById(R.id.emptyView)
        btnScrollTop = findViewById(R.id.btnScrollTop)
        btnScrollTop.setOnClickListener { scrollGridToTop() }
        btnSort = findViewById(R.id.btnSort)
        btnSort.setOnClickListener { showSortDialog() }
        bottomBar = findViewById(R.id.bottomBar)
        btnImport = findViewById(R.id.btnImport)
        btnDelete = findViewById(R.id.btnDelete)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancel = findViewById(R.id.btnCancel)
        btnOrganize = findViewById(R.id.btnOrganize)
        btnExport = findViewById(R.id.btnExport)

        // 标题行最右边的自动发送状态点（绿 = 已连上，黄 = 没开），点它直接去系统设置。
        a11yIndicator = findViewById(R.id.a11yIndicator)
        a11yDot = findViewById(R.id.a11yDot)
        a11yIndicator.setOnClickListener { openAccessibilitySettings() }

        senderStatus = findViewById(R.id.senderStatus)
        findViewById<View>(R.id.rowA11y).setOnClickListener { openAccessibilitySettings() }

        switchOverlay = findViewById(R.id.switchOverlay)
        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (!syncingOverlaySwitch) setOverlayEnabled(checked)
        }

        switchAutoConfirm = findViewById(R.id.switchAutoConfirm)
        confirmHint = findViewById(R.id.confirmHint)
        switchAutoConfirm.setOnCheckedChangeListener { _, checked ->
            if (!syncingConfirmSwitch) applyAutoConfirm(checked, fromUser = true)
        }

        ballAppsValue = findViewById(R.id.ballAppsValue)
        findViewById<View>(R.id.rowBallApps).setOnClickListener { showBallAppsDialog() }

        tileState = findViewById(R.id.tileState)
        findViewById<View>(R.id.rowAddTile).setOnClickListener { showTileHelp() }

        // 存储管理：占用概览与压缩大图（v0.1.406）。
        findViewById<View>(R.id.rowStorage).setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }

        // 表情发送方式、发送日志挪进了高级设置页；表情库迁移自 v0.1.406 起回到这里。
        findViewById<View>(R.id.rowExport).setOnClickListener {
            exportPicker.launch(EmojiArchive.suggestedName())
        }
        findViewById<View>(R.id.rowImport).setOnClickListener {
            // 旧版导出的 .zip 包还得能选，所以压缩包的两个类型也一起收着。选错文件不会出事：
            // 是不是备份包，由包里的清单说了算。
            importPicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed"
                )
            )
        }

        findViewById<View>(R.id.rowAdvanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        settingsVersion = findViewById(R.id.settingsVersion)
        settingsVersion.text = getString(R.string.settings_version_sub, versionName)

        // 版本行点开是版本页：图标 + 名字 + 版本号，GitHub 入口在那一页。
        findViewById<View>(R.id.rowVersion).setOnClickListener {
            startActivity(Intent(this, VersionActivity::class.java))
        }

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
        refreshConfirmSwitch()
        refreshBallApps()
        refreshTileState()
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
                UiPrefs.BALL_CLASSIC -> R.string.ball_style_classic
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
        UiPrefs.THEME_PINK -> R.string.theme_pink
        else -> R.string.theme_blue
    }

    /** 色块的圆点颜色：直接取调色板资源，省得在代码里解析主题属性。 */
    private fun themeColorOf(key: String): Int = when (key) {
        UiPrefs.THEME_PINK -> R.color.palette_pink_primary
        else -> R.color.palette_blue_primary
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
        // 顺序就是弹窗顺序：第一项是默认。用 key 数组而不是下标，
        // 免得以后插一项就把下面的分支全错位（原来 1/2/else 那套就是这么写歪的）。
        val keys = arrayOf(
            UiPrefs.BALL_DEFAULT,
            UiPrefs.BALL_CLASSIC,
            UiPrefs.BALL_DOT,
            UiPrefs.BALL_CUSTOM
        )
        val labels = arrayOf(
            getString(R.string.ball_style_default),
            getString(R.string.ball_style_classic),
            getString(R.string.ball_style_dot),
            getString(R.string.ball_pick)
        )
        val current = keys.indexOf(UiPrefs.ballStyle(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_ball_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val key = keys[which]
                if (key == UiPrefs.BALL_CUSTOM) {
                    // 自选图要等用户挑完才知道成不成，所以偏好由 ballPicker 的回调去写。
                    ballPicker.launch("image/*")
                } else {
                    UiPrefs.setBallStyle(this, key)
                    refreshUiSettings()
                    refreshBallIfRunning()
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
     *
     * 上游给的东西五花八门（emc-2-027）：微信规规矩矩在 EXTRA_STREAM 里放一个 content Uri，
     * QQ 放的是字符串路径，还有应用只往 ClipData / data 里放。所以这里不认「标准写法」，
     * 只认「里面有没有一个能读的图片地址」。
     */
    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        // 现场先记一笔：分享进来没反应时，这份日志是唯一能看出上游给了什么的东西。
        logShareIntent(intent)
        val uris = extractSharedUris(intent)

        // 处理完就把这次分享从 Intent 上抹掉：转屏、回前台时 onCreate 拿到的是同一个
        // Intent，不抹掉就会把同一张图反复导进来。
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
        if (uris.isEmpty()) {
            // 以前这里是静默 return：用户选了 Emojichan，界面上什么都没发生，也没留下任何线索。
            SendLog.e("分享", "没有从分享里解出任何图片地址，放弃导入")
            Toast.makeText(this@MainActivity, R.string.share_import_empty, Toast.LENGTH_LONG).show()
            return
        }
        SendLog.d("分享", "解出 " + uris.size + " 个地址，开始导入")

        showEmojiPage()
        lifecycleScope.launch {
            var ok = 0
            var noName = 0
            uris.forEach { uri ->
                when (copyUriToLibrary(uri)) {
                    ImportOutcome.OK -> ok++
                    ImportOutcome.NAME_FALLBACK -> {
                        ok++
                        noName++
                    }
                    ImportOutcome.FAILED -> Unit
                }
            }
            val base = if (ok > 0) {
                getString(R.string.share_import_ok, ok)
            } else {
                getString(R.string.share_import_failed)
            }
            // 分享进来也可能拿不到原名，跟相册导入用同一句提示（emc-2-008）
            val text = if (noName > 0) base + getString(R.string.import_no_name_suffix, noName) else base
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

    /**
     * 从分享 Intent 里尽量把图片地址抠出来。
     *
     * 标准写法是 EXTRA_STREAM 里放一个 content Uri，但各家应用并不老实：QQ 放的是字符串
     * 路径（有的是 content 字符串，有的还塞进 ArrayList），还有应用只往 ClipData / data
     * 里放。只认「EXTRA_STREAM 是 Parcelable Uri」这一种，QQ 的分享进来就一个都解不出来
     * （emc-2-027）。宁可多收：解出来的东西还要过 ImageUtil 的文件头检查，不是图片进不了库。
     */
    private fun extractSharedUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        collectSharedUris(intent.extras?.get(Intent.EXTRA_STREAM), out)
        intent.clipData?.let { collectSharedUris(it, out) }
        collectSharedUris(intent.data, out)
        // 兜底：个别应用把路径塞在 EXTRA_TEXT 里；真正的分享文字会被下面的 scheme 检查挡掉。
        if (out.isEmpty()) collectSharedUris(intent.getStringExtra(Intent.EXTRA_TEXT), out)
        return out.toList()
    }

    private fun collectSharedUris(raw: Any?, out: MutableCollection<Uri>) {
        when (raw) {
            null -> Unit
            is Uri -> out += raw
            is ClipData -> for (i in 0 until raw.itemCount) raw.getItemAt(i).uri?.let { out += it }
            is CharSequence -> sharedTextToUri(raw.toString())?.let { out += it }
            is Iterable<*> -> raw.forEach { collectSharedUris(it, out) }
            is Array<*> -> raw.forEach { collectSharedUris(it, out) }
            // 认不出来也留个痕迹：下回遇到同样的分享，日志里能直接看到上游塞的是什么类型。
            else -> SendLog.w("分享", "EXTRA_STREAM 里是 " + raw.javaClass.name + "，不认识")
        }
    }

    /** 分享里的一段文本：只有它确实像一个本地路径 / content 地址时才当图片用。 */
    private fun sharedTextToUri(text: String): Uri? {
        val trimmed = text.trim()
        // 路径里不会有空白；有空白的多半是真正的分享文字，直接放过去（不从 URL 里猜图片）。
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        return when {
            trimmed.startsWith("content://") || trimmed.startsWith("file://") -> Uri.parse(trimmed)
            trimmed.startsWith("/") -> Uri.fromFile(File(trimmed))
            else -> null
        }
    }

    /**
     * 把这次分享的现场整条写进日志：action / type / 每个 extra 的类型和取值。
     * 「某某应用分享过来没反应」时，只有这一行能对上号。
     */
    private fun logShareIntent(intent: Intent) {
        SendLog.d(
            "分享",
            "收到分享：action=" + intent.action + " type=" + intent.type +
                " clipData=" + (intent.clipData?.itemCount ?: 0) + " data=" + intent.data
        )
        val extras = intent.extras ?: return
        extras.keySet().forEach { key ->
            SendLog.d("分享", "  " + key + " = " + describeSharedValue(extras.get(key)))
        }
    }

    private fun describeSharedValue(value: Any?): String {
        if (value == null) return "null"
        val text = when (value) {
            is Uri -> value.toString()
            is CharSequence -> value.toString()
            is Iterable<*> -> value.joinToString(", ") { describeSharedValue(it) }
            is Array<*> -> value.joinToString(", ") { describeSharedValue(it) }
            else -> value.toString()
        }
        return value.javaClass.simpleName + "(" + text.take(160) + ")"
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
        // 标题行最右边那颗状态点：连着是绿的，没开是黄的。
        a11yDot.backgroundTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.status_on else R.color.status_off))
    }

    /**
     * 自动发送的最后一击（微信「发送」/ QQ「确定」）替不替用户点。
     * **默认关闭**（v0.2.002 起，用户 m09774）：流程停在按钮前，由用户点最后一下 ——
     * 装在这个按钮上的是「我看得见、我按下去」，比让程序猜更让人放心；
     * 想要全自动就在这一行打开，打开时会先问一次。
     * 分享 / 相册 / 粘贴三条路都认它（粘贴路线以前是个例外，v0.1.322 起统一 —— 见 emc-2-014）。
     */
    private fun refreshConfirmSwitch() {
        val on = SenderPrefs.autoConfirmLastStep(this)
        syncingConfirmSwitch = true
        switchAutoConfirm.isChecked = on
        syncingConfirmSwitch = false
        confirmHint.setText(
            if (on) R.string.settings_confirm_hint_on else R.string.settings_confirm_hint_off
        )
    }

    private fun applyAutoConfirm(on: Boolean, fromUser: Boolean) {
        // 高危操作：把「最后一下」交给程序点之前先问一次（关掉不用问）。取消就把开关拨回去。
        if (on && fromUser && !SenderPrefs.autoConfirmLastStep(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_auto_send_title)
                .setMessage(R.string.confirm_auto_send_message)
                .setPositiveButton(R.string.confirm_auto_send_ok) { _, _ ->
                    commitAutoConfirm(true, fromUser = true)
                }
                .setNegativeButton(R.string.confirm_auto_send_cancel) { _, _ -> refreshConfirmSwitch() }
                .setOnCancelListener { refreshConfirmSwitch() }
                .show()
            return
        }
        commitAutoConfirm(on, fromUser)
    }

    private fun commitAutoConfirm(on: Boolean, fromUser: Boolean) {
        SenderPrefs.setAutoConfirmLastStep(this, on)
        confirmHint.setText(
            if (on) R.string.settings_confirm_hint_on else R.string.settings_confirm_hint_off
        )
        if (fromUser) {
            Toast.makeText(
                this,
                if (on) R.string.confirm_switched_on else R.string.confirm_switched_off,
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
        // 「想让球开着」这个愿望跟着开关一起记下来：页面重进、进程重启之后靠它把球自己挂回来
        // （用户 m09897：默认就是开着的，不该每次都要用户手动开一次）
        SenderPrefs.setBallOn(this, enable)

        if (!enable) {
            if (!FloatingBallService.isRunning) return
            FloatingBallService.stop(this)
            Toast.makeText(this, R.string.overlay_stopped, Toast.LENGTH_SHORT).show()
            notifyBallStateChanged()
            return
        }

        if (FloatingBallService.isRunning) return
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
        notifyBallStateChanged()
        // 无障碍服务没开时球会一直在屏幕上，文案得说清楚差别，否则用户会以为坏了。
        Toast.makeText(
            this,
            if (AutoSendService.isConnected) R.string.overlay_started
            else R.string.overlay_started_manual,
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 球的开关状态变了：那行磁贴文案和磁贴自己的长相都要跟上
     * （用户很可能就是刚在状态栏里点的它）。
     */
    private fun notifyBallStateChanged() {
        // start() 只是把服务排上队，onCreate 还没跑完时 isRunning 仍是 false，
        // 等一小会儿再问状态 —— 和设置页开关快照是同一个理由。
        Handler(Looper.getMainLooper()).postDelayed({
            refreshTileState()
            BallTileService.requestRefresh(this)
        }, OVERLAY_SWITCH_SETTLE_MS)
    }

    /** 程序性地同步开关状态：不触发上面那套逻辑。 */
    private fun syncOverlaySwitch(checked: Boolean) {
        syncingOverlaySwitch = true
        switchOverlay.isChecked = checked
        syncingOverlaySwitch = false
    }

    // ---------- 悬浮球出现在哪些应用（与无障碍自动发送解耦）----------

    /** 设置页那行「悬浮球出现在哪些应用」显示当前勾选。 */
    private fun refreshBallApps() {
        val packages = SenderPrefs.ballPackages(this)
        ballAppsValue.text = getString(
            R.string.settings_ball_apps_value,
            BallScope.describe(packages, getString(R.string.settings_ball_apps_all)) { packageLabel(it) }
        )
    }

    /** 快捷开关那行的状态文案：球现在在不在跑。 */
    private fun refreshTileState() {
        tileState.text = getString(
            if (FloatingBallService.isRunning) R.string.settings_tile_on else R.string.settings_tile_off
        )
    }

    /**
     * 让用户勾「悬浮球出现在哪些应用里」。
     *
     * 一个都不勾 = 不限制（哪里都显示）；默认微信 + QQ。勾了别的应用只是球会出现在那里，
     * 发送走系统分享面板 —— 自动点选聊天对象那套流程只对微信 / QQ 有意义。
     *
     * 多选列表是自己搭的（`dialog_ball_apps.xml` + `ListView`）：用户实测 AlertDialog 自带的
     * `setMultiChoiceItems` 在这台设备上 114 个应用整列渲染成空白（emc-2-018）—— 标题、提示、
     * 按钮都在，就是一条应用都看不见。
     */
    private fun showBallAppsDialog() {
        lifecycleScope.launch {
            val (inventory, labels) = withContext(Dispatchers.IO) {
                val inv = loadLaunchableApps()
                inv to inv.apps.map { packageLabel(it) }
            }
            if (isFinishing || isDestroyed) return@launch
            if (inventory.apps.isEmpty()) {
                // 系统的包可见性把清单挡掉了（emc-2-011）：至少给一条能走通的路 ——
                // 空集合的语义本来就是「不限制」（见 SenderPrefs.ballPackages）。
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.settings_ball_apps_title)
                    .setMessage(R.string.ball_apps_none_message)
                    .setPositiveButton(R.string.ball_apps_none_unlimited) { _, _ ->
                        applyBallPackages(emptySet())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@launch
            }
            val current = SenderPrefs.ballPackages(this@MainActivity)
            val rows = inventory.apps.mapIndexed { index, pkg ->
                BallAppRow(pkg, labels[index], pkg in current)
            }
            val view = layoutInflater.inflate(R.layout.dialog_ball_apps, null)
            val search = view.findViewById<EditText>(R.id.ballAppsSearch)
            val count = view.findViewById<TextView>(R.id.ballAppsCount)
            val list = view.findViewById<ListView>(R.id.ballAppsList)
            // 列表高度跟着屏幕走：矮屏上不把按钮顶出可视区，高屏上多显示几行。
            list.layoutParams.height = minOf(
                (resources.displayMetrics.heightPixels * 0.34f).toInt(),
                list.layoutParams.height
            )
            val adapter = BallAppAdapter(this@MainActivity, rows)
            list.adapter = adapter
            view.findViewById<TextView>(R.id.ballAppsInventory).text = getString(
                R.string.ball_apps_inventory,
                inventory.byLauncher,
                inventory.byInstalled,
                inventory.apps.size
            )
            fun refreshCount() {
                count.text = getString(
                    R.string.ball_apps_count,
                    adapter.selectedCount,
                    adapter.count,
                    rows.size
                )
            }
            refreshCount()
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    adapter.setQuery(s?.toString().orEmpty())
                    refreshCount()
                }
            })
            list.setOnItemClickListener { _, _, position, _ ->
                val row = adapter.getItem(position)
                if (row != null) {
                    row.checked = !row.checked
                    adapter.notifyDataSetChanged()
                    refreshCount()
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.settings_ball_apps_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ -> applyBallPackages(adapter.selected) }
                .setNeutralButton(R.string.ball_apps_reset) { _, _ ->
                    applyBallPackages(SenderPrefs.DEFAULT_BALL_PACKAGES)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyBallPackages(packages: Set<String>) {
        SenderPrefs.setBallPackages(this, packages)
        refreshBallApps()
        // 正在跑的球立刻按新范围重算，别等用户切一次应用才生效。
        FloatingBallService.refreshScope()
    }

    /** 常见聊天 / 社交应用：包可见性万一挡掉清单时，至少这些还能勾（emc-2-011）。 */
    private val KNOWN_CHAT_APPS = listOf(
        "com.tencent.mm",              // 微信
        "com.tencent.mobileqq",        // QQ
        "com.tencent.tim",             // TIM
        "com.tencent.wework",          // 企业微信
        "com.alibaba.android.rimet",   // 钉钉
        "com.ss.android.lark",         // 飞书
        "com.sina.weibo",              // 微博
        "com.xingin.xhs",              // 小红书
        "com.ss.android.ugc.aweme",    // 抖音
        "com.smile.gifmaker",          // 快手
        "com.zhihu.android",           // 知乎
        "com.tencent.qqmail",          // QQ 邮箱
        "com.eg.android.AlipayGphone", // 支付宝
        "com.taobao.taobao",           // 淘宝
        "com.jingdong.app.mall",       // 京东
        "tv.danmaku.bili",             // 哔哩哔哩
        "com.netease.cloudmusic",      // 网易云音乐
        "com.tencent.karaoke",         // 全民 K 歌
    )

    /** 可勾选的应用清单 + 它是怎么凑出来的（计数要写进对话框，出问题一眼看出是哪一层没拿到）。 */
    private data class AppInventory(val apps: List<String>, val byLauncher: Int, val byInstalled: Int)

    /**
     * 有启动图标的用户应用（自己排除掉），微信 / QQ 排最前面。
     *
     * Android 11 起「本应用能看到哪些应用」只由清单里的 queries 决定，所以这里分三层凑：
     * ① MAIN + LAUNCHER 查询（清单里已声明这条 intent，v0.1.319 补的，emc-2-011）；
     * ② 已安装列表里能拉起启动页的那些；
     * ③ 常见聊天应用 + 用户已经勾过的包兜底。
     * 后两层是为了哪怕查询被系统挡掉，用户也不会对着一张空列表无从下手。
     */
    private fun loadLaunchableApps(): AppInventory {
        val byLauncher = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            resolved.map { it.activityInfo.packageName }
        }.getOrElse {
            SendLog.w("悬浮球", "启动项查询失败：" + (it.message ?: it.javaClass.simpleName))
            emptyList()
        }
        val byInstalled = if (byLauncher.size < 3) {
            runCatching {
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstalledPackages(0)
                }
                installed.map { it.packageName }
                    .filter { packageManager.getLaunchIntentForPackage(it) != null }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        val fallback = KNOWN_CHAT_APPS + SenderPrefs.ballPackages(this) +
            SenderPrefs.DEFAULT_BALL_PACKAGES
        val merged = LinkedHashSet<String>()
        merged += byLauncher
        merged += byInstalled
        merged += fallback.filter { it != packageName && isInstalled(it) }
        merged.remove(packageName)
        SendLog.d(
            "悬浮球",
            "应用清单：启动项 " + byLauncher.size + " 个 / 已安装 " + byInstalled.size +
                " 个 / 兜底后共 " + merged.size + " 个"
        )
        val default = SenderPrefs.DEFAULT_BALL_PACKAGES
        val sorted = merged.sortedWith(compareBy({ if (it in default) 0 else 1 }, { packageLabel(it) }))
        return AppInventory(sorted, byLauncher.size, byInstalled.size)
    }

    /** 这个包装没装：包可见性不够时 getPackageInfo 会抛 NameNotFound，一律当没装。 */
    private fun isInstalled(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
        true
    }.getOrDefault(false)

    private fun packageLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            .toString()
    }.getOrDefault(packageName)

    /** 应用清单里的一行：包名 + 显示名 + 勾没勾上。 */
    private class BallAppRow(val pkg: String, val label: String, var checked: Boolean)

    /**
     * 自建多选列表的适配器（emc-2-018）。
     *
     * 行对象是同一批，所以搜索过滤只换「显示哪些行」，勾选状态不会因为过滤而丢；
     * 勾选一律由 ListView 的行点击事件翻（行里的 `CheckBox` 设成 clickable=false / focusable=false）。
     */
    private class BallAppAdapter(
        context: Context,
        private val all: List<BallAppRow>
    ) : ArrayAdapter<BallAppRow>(context, 0, ArrayList<BallAppRow>()) {

        private var query: String = ""

        init {
            rebuild()
        }

        fun setQuery(text: String) {
            val next = text.trim()
            if (next == query) return
            query = next
            rebuild()
        }

        private fun rebuild() {
            clear()
            addAll(
                if (query.isEmpty()) all
                else all.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
            )
            notifyDataSetChanged()
        }

        /** 勾了几个（跨过滤状态，问的是全量）。 */
        val selectedCount: Int get() = all.count { it.checked }

        /** 勾上的那些包。 */
        val selected: Set<String> get() = all.filter { it.checked }.map { it.pkg }.toSet()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_ball_app, parent, false)
            val row = getItem(position) ?: return view
            view.findViewById<TextView>(R.id.appLabel).text = row.label
            view.findViewById<TextView>(R.id.appPackage).text = row.pkg
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = row.checked
            return view
        }
    }

    // ---------- 下拉状态栏快捷开关 ----------

    /**
     * 教用户把磁贴拖进状态栏。
     *
     * 没有「程序化添加磁贴」的公开 API：[android.service.quicksettings.TileService] 只有
     * requestListeningState（让磁贴刷新状态）；StatusBarManager 里那套 requestAddTileService
     * 是系统应用才有的（要 STATUS_BAR_SERVICE 权限）。所以这一步只能让用户自己拖一次。
     */
    private fun showTileHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_tile_title)
            .setMessage(R.string.settings_tile_manual)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 磁贴里点「开球」而系统不许后台起服务（或还没有悬浮窗权限）时，磁贴会打开本界面兜一下。
     * 统一走 [setOverlayEnabled]：权限没给它会跳到授权页，回来 onResume 接着把球开起来。
     */
    private fun handleTileIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(BallTileService.EXTRA_FROM_TILE, false) != true) return
        intent.removeExtra(BallTileService.EXTRA_FROM_TILE)
        val startBall = intent.getBooleanExtra(BallTileService.EXTRA_START_BALL, false)
        intent.removeExtra(BallTileService.EXTRA_START_BALL)
        SendLog.w("磁贴", "打开界面兜底：startBall=" + startBall)
        bottomNav.selectedItemId = R.id.tab_settings
        showSettingsPage(true)
        setOverlayEnabled(true)
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
                    jumpGridToTop()
                }
                // 候选只在本机内存里筛，不碰数据库，所以不用等防抖
                refreshSearchSuggestions()
            }
        })

        // 语法快捷键：$ @ & / ! 在手机键盘上要翻页才找得到，点一下直接插进光标处
        addSyntaxChip(R.string.search_syntax_tag) { insertSearchText("\$TAG=\$") }
        addSyntaxChip(R.string.search_syntax_keyword) { insertSearchText("@") }
        addSyntaxChip(R.string.search_syntax_and) { insertSearchText("&") }
        addSyntaxChip(R.string.search_syntax_or) { insertSearchText("/") }
        addSyntaxChip(R.string.search_syntax_not) { insertSearchText("!") }
        searchSyntaxHint.setOnClickListener { showSearchSyntaxDialog() }

        searchBar.setOnFocusChangeListener { _, hasFocus ->
            // 候选只认标签，标签本身是实时流，不用在这里重取数据；失焦时收起来就够了。
            if (!hasFocus) dismissSuggestions()
        }
    }

    /** 语法快捷键 chip：与标签面板里的功能 chip 同一套颜色，跟筛选条上的筛选 chip 区分开。 */
    private fun addSyntaxChip(labelRes: Int, onClick: () -> Unit) {
        searchSyntaxButtons.addView(createPanelActionChip(getString(labelRes), onClick))
    }

    /**
     * 往搜索框光标处插一段符号。
     *
     * 插完光标停在符号中间（$TAG=▍$、@▍@），接下来敲的字就是这个条件的内容。
     */
    private fun insertSearchText(snippet: String) {
        val text = searchBar.text?.toString().orEmpty()
        val at = searchBar.selectionEnd.coerceIn(0, text.length)
        searchBar.setText(text.substring(0, at) + snippet + text.substring(at))
        val back = if (snippet.length > 1 && snippet.endsWith("$")) 1 else 0
        searchBar.setSelection(at + snippet.length - back)
        searchBar.requestFocus()
        // setText 触发的那次刷新用的是旧光标位置，这里按新位置重算一遍
        refreshSearchSuggestions()
    }

    /**
     * 光标所在那一段条件的起止：上一个 & 或 / 之后，到光标之前。
     *
     * $…$、@…@ 里面的 & 与 / 算内容不算分隔符，所以成对的符号要跳过。
     */
    private fun currentChunk(text: String, cursor: Int): IntRange {
        var start = 0
        var open = ' '
        for (i in 0 until cursor) {
            val c = text[i]
            when {
                open != ' ' && c == open -> open = ' '
                open == ' ' && (c == '$' || c == '@') -> open = c
                open == ' ' && (c == '&' || c == '/') -> start = i + 1
            }
        }
        // 分隔符后面的空白不算条件的一部分，否则「这段以 $ 开头」会被一个空格带偏
        while (start < cursor && text[start].isWhitespace()) start++
        return start until cursor
    }

    /**
     * 补全候选：只补标签，关键词不参与联想。
     *
     * 光标那一段写成标签条件（$…）就补成完整写法，只是一个光秃秃的词时只把词补全、
     * 不替用户加符号（加了会悄悄改变搜索范围）。写成关键词条件（@…）时不给候选 ——
     * 表情名动辄上千条，全列出来帮不上忙，想精确匹配直接写 @名字@。
     */
    private fun suggestionsFor(text: String, cursor: Int): List<SearchSuggestion> {
        val chunk = currentChunk(text, cursor)
        val raw = text.substring(chunk.first, cursor).trim()
        if (raw.isEmpty() || raw.startsWith("@")) return emptyList()
        val tagWriting = raw.startsWith("$")
        val query = (if (tagWriting) EmojiFilter.tagBody(raw.substring(1)) else raw).trim()

        return viewModel.tags.value
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { tag ->
                if (tagWriting) {
                    val term = "\$TAG=" + tag.name + "$"
                    SearchSuggestion(term, term)
                } else {
                    SearchSuggestion(getString(R.string.search_suggest_tag, tag.name), tag.name)
                }
            }
            .filter { it.insert != raw }
            .take(MAX_SEARCH_SUGGESTIONS)
    }

    /** 画补全候选：有候选就在搜索框下面弹出列表，没有就收起来。 */
    private fun refreshSearchSuggestions() {
        val text = searchBar.text?.toString().orEmpty()
        val cursor = searchBar.selectionEnd.coerceIn(0, text.length)
        val items = suggestionsFor(text, cursor)
        val width = searchBar.width
        if (items.isEmpty() || width <= 0) {
            dismissSuggestions()
            return
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_search_suggestion, column, false) as TextView
            row.text = item.label
            row.setOnClickListener { applySuggestion(item.insert) }
            column.addView(row)
        }
        val content = ScrollView(this).apply { addView(column) }
        val maxHeight = (MAX_SUGGESTION_HEIGHT_DP * resources.displayMetrics.density).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )

        val popup = suggestionPopup ?: PopupWindow(this).apply {
            // 不抢焦点：键盘不收、光标不丢；点别处就收起来（背景非空是这条生效的前提）。
            isFocusable = false
            isOutsideTouchable = true
            setBackgroundDrawable(getDrawable(R.drawable.bg_search_dropdown))
            elevation = 6f * resources.displayMetrics.density
            suggestionPopup = this
        }
        popup.contentView = content
        if (popup.isShowing) {
            popup.update(searchBar, width, content.measuredHeight)
        } else {
            popup.width = width
            popup.height = content.measuredHeight
            popup.showAsDropDown(searchBar, 0, (SUGGESTION_GAP_DP * resources.displayMetrics.density).toInt())
        }
    }

    /** 收起补全候选。 */
    private fun dismissSuggestions() {
        suggestionPopup?.dismiss()
    }

    /** 拿一条补全结果替换光标所在的那一段，光标落到词尾，接着敲的字接在后面。 */
    private fun applySuggestion(insert: String) {
        val text = searchBar.text?.toString().orEmpty()
        val cursor = searchBar.selectionEnd.coerceIn(0, text.length)
        val chunk = currentChunk(text, cursor)
        // 光标后面正好是收尾符号时一并吃掉：$TAG=猫▍$ 补成 $TAG=猫咪$，而不是 $TAG=猫咪$$
        var end = cursor
        if (end < text.length && (insert.endsWith("$") || insert.endsWith("@")) && text[end] == insert.last()) {
            end++
        }
        searchBar.setText(text.substring(0, chunk.first) + insert + text.substring(end))
        searchBar.setSelection(chunk.first + insert.length)
        searchBar.requestFocus()
        // 补成完整条件（收尾符号都补上了）就把列表收起来，这一段没什么可再补的了；
        // 只补了一个光秃秃的词，就按新位置再算一次候选。
        if (insert.endsWith("$") || insert.endsWith("@")) {
            dismissSuggestions()
        } else {
            refreshSearchSuggestions()
        }
    }

    /** 用法说明：三种写法、两个连接符、一个例子。 */
    private fun showSearchSyntaxDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_syntax_title)
            .setMessage(R.string.search_syntax_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- 筛选条与标签面板 ----------

    /**
     * 筛选条：左边一个展开按钮，右边一条横向滚动的 chip。
     *
     * 分类（包）已经取消，标签成了唯一的组织方式，所以这里显示的是**当前正在生效的筛选**：
     * 全部 / 收藏 / 已选中的标签。所有标签不在这里逐个铺开 —— 标签一多就成了一条望不到
     * 头的横条，要看全部标签请点左边的按钮展开面板（网格排列，一眼全在）。
     */
    private fun setupFilterBar() {
        btnExpandTags.setOnClickListener { toggleTagPanel() }

        lifecycleScope.launch {
            // tagModes 必须算一路：它一变，筛选条上标签 chip 的前缀（& / | / !）得跟着换
            combine(
                viewModel.tags,
                // 面板和筛选条要两份不同的顺序，所以这里嵌一层：外面这层只有 5 个位置，
                // 再塞一路就超了（用户 m11668 第 7 条）
                combine(viewModel.tagPanelTags, viewModel.recentOnly) { panel, recent -> panel to recent },
                viewModel.selectedTagIds,
                viewModel.tagModes,
                viewModel.favoritesOnly
            ) { tags, panelAndRecent, ids, tagModes, favorites ->
                FilterState(
                    tags = tags,
                    panelTags = panelAndRecent.first,
                    ids = ids,
                    tagModes = tagModes,
                    favoritesOnly = favorites,
                    recentOnly = panelAndRecent.second
                )
            }
                .collectLatest { state ->
                    updateFilterChips(
                        state.tags,
                        state.ids,
                        state.tagModes,
                        state.favoritesOnly,
                        state.recentOnly
                    )
                    updateTagPanel(state.panelTags, state.ids)
                }
        }
    }

    /**
     * 筛选条的内容：全部（清空筛选）、收藏、最近（用过的，v0.2.007）、以及每一个正在生效的标签。
     *
     * 已选标签写成「& 猫 ×」：前缀是它自己的合并方式（& 交 / | 并 / ! 非），点标签本身换下一种，
     * 点尾巴上的 × 把它从筛选里去掉（用户 m10282）。
     */
    private fun updateFilterChips(
        tags: List<TagEntity>,
        ids: Set<Long>,
        tagModes: Map<Long, TagMode>,
        favoritesOnly: Boolean,
        recentOnly: Boolean
    ) {
        val selected = tags.filter { it.id in ids }
        val all = getString(R.string.filter_all)
        val favorites = getString(R.string.filter_favorites)
        val recent = getString(R.string.filter_recent)
        val target = listOf<Triple<String, Long?, Int>>(
            Triple(all, null, 0),
            Triple(favorites, null, 1),
            Triple(recent, null, 3)
        ) + selected.map {
            Triple(selectedTagLabel(it.name, tagModes[it.id] ?: TagMode.ALL), it.id, 2)
        }

        if (filterChips.map { it.text.toString() } != target.map { it.first }) {
            // 结构变了才重建；按文案复用已有 chip，避免每次发射都闪一下
            // （文案里带着合并方式，所以换运算也会走到这里重建）
            val reusable = filterChips.associateBy { it.text.toString() }
            val chips = target.map { (label, tagId, kind) ->
                val id = tagId
                reusable[label] ?: when {
                    // 标签 chip 一律认 id：它在条上的位置会随别的标签进出来回变，认位置会点错
                    kind == 2 && id != null ->
                        createSelectedTagChip(label, id, tagModes[id] ?: TagMode.ALL)
                    kind == 1 -> createFilterChip(label) {
                        viewModel.toggleFavoritesOnly()
                        // 条件变了就回顶上：还停在旧结果的中段，看着像筛选没生效（用户 m10115）
                        jumpGridToTop()
                    }
                    // 最近：跟收藏一样是叠加条件，不动标签
                    kind == 3 -> createFilterChip(label) {
                        viewModel.toggleRecentOnly()
                        jumpGridToTop()
                    }
                    else -> createFilterChip(label) {
                        viewModel.setFavoritesOnly(false)
                        viewModel.setRecentOnly(false)
                        viewModel.clearTagFilter()
                        jumpGridToTop()
                    }
                }
            }
            filterChips = chips
            filterContainer.removeAllViews()
            chips.forEach { filterContainer.addView(it) }
        }

        // 前三颗固定是「全部 / 收藏 / 最近」，再往后才是已选标签
        filterChips.forEachIndexed { index, chip ->
            chip.isChecked = when (index) {
                0 -> !favoritesOnly && !recentOnly && selected.isEmpty()
                1 -> favoritesOnly
                2 -> recentOnly
                else -> true
            }
        }
    }

    /** 筛选条上的一颗 chip：选中态用主色实心，未选中留白描边。 */
    private fun createFilterChip(label: String, onClick: () -> Unit): Chip = Chip(this).apply {
        text = label
        textSize = 14f
        isCheckable = true
        isChecked = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(false)
        chipStrokeWidth = resources.displayMetrics.density
        // apply 里的 this 是这个 Chip（View），MaterialColors 取色的重载要的就是 View
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        chipStrokeColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, getColor(R.color.divider))
        )
        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, getColor(R.color.surface))
        )
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary),
                    getColor(R.color.text_primary)
                )
            )
        )
        setOnClickListener { onClick() }
        layoutParams = chipLayoutParams()
    }

    private fun chipLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = 8 }

    /** 已选标签在筛选条上的写法：「& 猫」，前缀就是这个标签自己的合并方式。 */
    private fun selectedTagLabel(name: String, mode: TagMode): String =
        matchModeSymbol(mode) + " " + name

    /**
     * 筛选条上已选中的标签那颗 chip。
     *
     * 点标签本身换合并方式（交 → 并 → 非 → 交，符号跟着变），点尾巴上的 × 把它从筛选里
     * 去掉 —— 以前这三件事在面板顶上的功能行里，现在挪到标签自己身上（用户 m10282）。
     */
    private fun createSelectedTagChip(label: String, tagId: Long, mode: TagMode): Chip =
        createFilterChip(label) {
            viewModel.cycleTagMode(tagId)
            jumpGridToTop()
        }.apply {
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                viewModel.toggleTagFilter(tagId)
                jumpGridToTop()
            }
            contentDescription = getString(R.string.filter_tag_chip_desc, label, matchModeName(mode))
        }

    // ---------- 标签面板 ----------

    /** 展开 / 收起标签面板；按钮上的图标跟着翻个方向。 */
    private fun toggleTagPanel() {
        val expand = tagPanel.visibility != View.VISIBLE
        tagPanel.visibility = if (expand) View.VISIBLE else View.GONE
        btnExpandTags.setIconResource(
            if (expand) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_sort_by_size
        )
        if (expand) clampTagPanelHeight()
    }

    /**
     * 面板内容：所有标签各一颗 chip（网格排列，点一下加入 / 移出筛选），上面一行是
     * 「新建标签 / 管理标签」入口。
     *
     * 交 / 并 / 非 不在这里设了 —— 合并方式是每个标签自己的事，挂在筛选条上那颗标签上点着换
     * （用户 m10282）。长按任意一颗标签可以重命名或删除它，标签的管理动作都挂在标签自己身上。
     */
    private fun updateTagPanel(tags: List<TagEntity>, ids: Set<Long>) {
        // 传进来的是 tagPanelTags：图片、动图最前，其余按挂着的张数从多到少（用户 m11668 第 7 条）。
        // key 看的是顺序本身，所以张数变了导致换位时这里会整块重建一遍
        val key = tags.joinToString(",") { it.name }
        if (key != tagPanelKey) {
            tagPanelKey = key
            refreshTagActions()
            tagGrid.removeAllViews()
            if (tags.isEmpty()) {
                tagGrid.addView(TextView(this).apply {
                    text = getString(R.string.tag_panel_empty)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                })
            }
            tags.forEach { tag ->
                tagGrid.addView(createTagGridChip(tag) {
                    viewModel.toggleTagFilter(tag.id)
                    jumpGridToTop()
                })
            }
            clampTagPanelHeight()
        }
        // 标签始终是 tagGrid 里最前面那几颗，位置对得上
        tags.forEachIndexed { index, tag ->
            (tagGrid.getChildAt(index) as? Chip)?.isChecked = tag.id in ids
        }
    }

    /**
     * 面板顶上那一行：「重置 / 新建标签 / 管理标签」（用户 m10194、m10201；重置是 m11668 第 6 条）。
     *
     * 重置排在最前：选过的标签多了以后一颗颗点掉很烦，一次点击清干净。
     * 它只清标签选择（交 / 并 / 非 一并归零），收藏 / 最近那两颗固定筛选不动 ——
     * 那两颗不是「标签」，不在这件事里（用户 m11668 第 6 条的原话是「清空当前的 tag 选择情况」）。
     *
     * 交 / 并 / 非 三颗原本也在这里，v0.2.005 起挪到筛选条的标签本身上去了（用户 m10282）：
     * 合并方式是每个标签各自的事，挂在标签上既能一眼看见当前是什么运算，也少点几下。
     */
    private fun refreshTagActions() {
        tagActions.removeAllViews()
        tagActions.addView(createPanelActionChip(getString(R.string.tag_action_reset)) {
            viewModel.clearTagFilter()
            jumpGridToTop()
        })
        tagActions.addView(createPanelActionChip(getString(R.string.tag_action_new)) {
            showTagInputDialog { viewModel.addTag(it) }
        })
        tagActions.addView(createPanelActionChip(getString(R.string.tag_action_manage)) {
            showTagManager()
        })
    }

    /** 面板里的标签 chip：选中态同筛选条，长按弹「重命名 / 删除」。 */
    private fun createTagGridChip(tag: TagEntity, onClick: () -> Unit): Chip =
        createFilterChip(tag.name, onClick).apply {
            setOnLongClickListener {
                showTagActions(tag)
                true
            }
        }

    /** 面板里的功能 chip（新建 / 管理）：它不是筛选条件，所以不参与选中态。 */
    private fun createPanelActionChip(label: String, onClick: () -> Unit): Chip = Chip(this).apply {
        text = label
        textSize = 14f
        isCheckable = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(false)
        chipStrokeWidth = 0f
        chipBackgroundColor = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        )
        setTextColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
        )
        setOnClickListener { onClick() }
        layoutParams = chipLayoutParams()
    }

    /**
     * 标签多的时候面板不能无限长：最多占屏幕的三分之一，超出的在面板里滚动。
     * 面板是 wrap_content 的 ScrollView，得等它按内容量过一次才知道要不要封顶。
     */
    private fun clampTagPanelHeight() {
        // 量的是面板里那一整块（功能行 + 标签），不是只有标签网格；tagPanel 声明成 View，取子 View 要先当 ViewGroup
        val content = (tagPanel as? ViewGroup)?.getChildAt(0) ?: return
        content.post {
            val max = (resources.displayMetrics.heightPixels * 0.34f).toInt()
            val params = tagPanel.layoutParams
            val wanted = if (content.height > max) max else ViewGroup.LayoutParams.WRAP_CONTENT
            if (params.height != wanted) {
                params.height = wanted
                tagPanel.layoutParams = params
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 交 / 并 / 非：写在已选标签 chip 前面那个符号，跟搜索框那套语法（& | !）是同一套写法。 */
    private fun matchModeSymbol(mode: TagMode): String = getString(
        when (mode) {
            TagMode.ALL -> R.string.tag_mode_symbol_all
            TagMode.ANY -> R.string.tag_mode_symbol_any
            TagMode.EXCLUDE -> R.string.tag_mode_symbol_not
        }
    )

    /** 全称，只用作无障碍描述（「交」两个字听不出是同时满足还是任一满足）。 */
    private fun matchModeName(mode: TagMode): String = getString(
        when (mode) {
            TagMode.ALL -> R.string.tag_match_all
            TagMode.ANY -> R.string.tag_match_any
            TagMode.EXCLUDE -> R.string.tag_match_exclude
        }
    )

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
            selectedIds = { viewModel.selectedIds.value }
        )

        emojiGrid.layoutManager = GridLayoutManager(this, 3)
        emojiGrid.adapter = adapter
        emojiGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollTop()
            }
        })
    }

    /** 往下滚过一段距离才让「回到置顶」露头；滚回顶上、或者列表被筛短了，它自己收回去。 */
    private fun updateScrollTop() {
        if (!::btnScrollTop.isInitialized) return
        val show = emojiGrid.computeVerticalScrollOffset() >
            SCROLL_TOP_SHOW_DP * resources.displayMetrics.density
        btnScrollTop.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 「回到置顶」按钮：滑上去，速度比系统默认快一截。
     *
     * 系统的 LinearSmoothScroller 按 25 毫秒 / 英寸算时长，几千张时能滑好几秒（用户 m10115）。
     * 这里只把速度换成 [SCROLL_TOP_MS_PER_INCH]，别的行为（先粗滚到附近、再慢慢对准）照旧。
     */
    private fun scrollGridToTop(smooth: Boolean = true) {
        if (!::emojiGrid.isInitialized) return
        val manager = emojiGrid.layoutManager as? LinearLayoutManager
        if (!smooth || manager == null) {
            jumpGridToTop()
            return
        }
        val scroller = object : LinearSmoothScroller(this) {
            /** 停在第一张的顶上，而不是「能看到就行」。 */
            override fun getVerticalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_START

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                SCROLL_TOP_MS_PER_INCH / displayMetrics.densityDpi
        }
        scroller.targetPosition = 0
        manager.startSmoothScroll(scroller)
    }

    /** 换了筛选条件、排序或搜索词之后立刻回顶上；顺手核对一下按钮的显隐。 */
    private fun jumpGridToTop() {
        if (!::emojiGrid.isInitialized) return
        emojiGrid.scrollToPosition(0)
        emojiGrid.post { updateScrollTop() }
    }

    /**
     * 排序弹窗：两条单选 —— 按什么排（时间 / 名称 / 大小）、正序还是倒序。
     * 确定之后立刻换顺序，并回到列表顶上：刚换了顺序还停在旧顺序的第几十张，看着像没生效。
     */
    private fun showSortDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sort, null)
        val fieldGroup = view.findViewById<RadioGroup>(R.id.sortFieldGroup)
        val orderGroup = view.findViewById<RadioGroup>(R.id.sortOrderGroup)
        val current = viewModel.sort.value
        fieldGroup.check(
            when (current.field) {
                UiPrefs.SORT_NAME -> R.id.sortFieldName
                UiPrefs.SORT_SIZE -> R.id.sortFieldSize
                else -> R.id.sortFieldTime
            }
        )
        orderGroup.check(if (current.desc) R.id.sortOrderDesc else R.id.sortOrderAsc)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setView(view)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val field = when (fieldGroup.checkedRadioButtonId) {
                    R.id.sortFieldName -> UiPrefs.SORT_NAME
                    R.id.sortFieldSize -> UiPrefs.SORT_SIZE
                    else -> UiPrefs.SORT_TIME
                }
                viewModel.setSort(field, orderGroup.checkedRadioButtonId == R.id.sortOrderDesc)
                jumpGridToTop()
            }
            .show()
    }

    /**
     * 通用文本输入框（新建 / 重命名标签都用它）。空名只在这里挡一道：
     * 真正的校验（去重、长度）在 ViewModel 与数据层。
     */
    private fun showInputDialog(
        title: String,
        hint: String,
        initial: String,
        onConfirm: (String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.inputField)
        input.hint = hint
        input.setText(initial)
        input.setSelection(initial.length)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.tag_name_empty, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showTagInputDialog(onConfirm: (String) -> Unit) {
        showInputDialog(
            getString(R.string.tag_new_title),
            getString(R.string.dialog_tag_hint),
            ""
        ) { name -> onConfirm(name) }
    }

    /** 标签管理：列出所有标签，点一个进去重命名或删除。 */
    private fun showTagManager() {
        val tags = viewModel.tags.value
        if (tags.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tag_manage_title)
                .setMessage(R.string.tag_manage_empty)
                .setPositiveButton(R.string.tag_new_title) { _, _ -> showTagInputDialog { viewModel.addTag(it) } }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_manage_title)
            .setItems(tags.map { it.name }.toTypedArray()) { _, which -> showTagActions(tags[which]) }
            .setNeutralButton(R.string.tag_new_title) { _, _ -> showTagInputDialog { viewModel.addTag(it) } }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    /** 单个标签的操作：重命名（长按面板里的标签也能进来）或删除；自动标签只看不改。 */
    private fun showTagActions(tag: TagEntity) {
        if (EmojiDefaults.isAutoTag(tag.name)) {
            showAutoTagLockedDialog(tag)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(tag.name)
            .setItems(
                arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))
            ) { _, which ->
                if (which == 0) {
                    showInputDialog(
                        getString(R.string.tag_rename_title),
                        getString(R.string.dialog_tag_hint),
                        tag.name
                    ) { newName ->
                        if (newName != tag.name) viewModel.renameTag(tag.id, tag.name, newName)
                    }
                } else {
                    confirmDeleteTag(tag)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** 自动标签（图片 / 动图）的说明框：导入时按文件类型挂上，界面里只读。 */
    private fun showAutoTagLockedDialog(tag: TagEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(tag.name)
            .setMessage(R.string.tag_auto_locked)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun confirmDeleteTag(tag: TagEntity) {
        if (EmojiDefaults.isAutoTag(tag.name)) {
            showAutoTagLockedDialog(tag)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_delete_title)
            .setMessage(getString(R.string.tag_delete_message, tag.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteTag(tag.id, tag.name) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** 选择模式下的「整理」：批量改包或改标签，四项都只作用于已选表情。 */
    private fun showOrganizeDialog() {
        val count = viewModel.selectedIds.value.size
        if (count == 0) {
            Toast.makeText(this, R.string.msg_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf(
            getString(R.string.organize_add_tags),
            getString(R.string.organize_remove_tags)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.organize_title, count))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAddTagsToSelectedDialog()
                    else -> showRemoveTagsFromSelectedDialog()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAddTagsToSelectedDialog() {
        val tags = viewModel.tags.value.filterNot { EmojiDefaults.isAutoTag(it.name) }
        if (tags.isEmpty()) {
            showTagInputDialog { name -> viewModel.addNewTagToSelected(name) }
            return
        }
        val checked = BooleanArray(tags.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_pick_title)
            .setMultiChoiceItems(tags.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                viewModel.addTagsToSelected(tags.filterIndexed { index, _ -> checked[index] }.map { it.id })
            }
            .setNeutralButton(R.string.organize_add_tag_new) { _, _ ->
                showTagInputDialog { name -> viewModel.addNewTagToSelected(name) }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showRemoveTagsFromSelectedDialog() {
        if (viewModel.tags.value.isEmpty()) {
            Toast.makeText(this, R.string.tag_manage_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // 「图片」「动图」是自动标签，不在可移除之列
        val removable = viewModel.tags.value.filterNot { EmojiDefaults.isAutoTag(it.name) }
        if (removable.isEmpty()) {
            Toast.makeText(this, R.string.tag_auto_locked_toast, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tag_pick_title)
            .setItems(removable.map { it.name }.toTypedArray()) { _, which ->
                viewModel.removeTagsFromSelected(listOf(removable[which].id))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 删除选中的那几个表情。删除本身在 ViewModel 里（先删文件、确认文件没了才删记录），
     * 这里只管把进度框从头管到尾（v0.2.003 起，用户 m09940）。
     */
    private fun runDeleteSelected() {
        val total = viewModel.selectedIds.value.size
        val progress = BusyDialog.showProgress(
            this,
            R.string.delete_progress_title,
            getString(R.string.delete_progress, 1, total)
        )
        lifecycleScope.launch {
            viewModel.deleteSelectedWithProgress { done, all ->
                BusyDialog.update(
                    this@MainActivity,
                    progress,
                    done,
                    all,
                    getString(R.string.delete_progress, done, all)
                )
            }
            BusyDialog.dismiss(this@MainActivity, progress)
        }
    }

    private fun setupBottomBar() {
        btnImport.setOnClickListener { showImportSourceDialog() }

        btnDelete.setOnClickListener {
            val count = viewModel.selectedIds.value.size
            if (count > 0) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(getString(R.string.delete_confirm_selected_message, count))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        runDeleteSelected()
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

        // 「整理」只在选择模式下出现：批量改包、批量加/摘标签
        btnOrganize.setOnClickListener { showOrganizeDialog() }

        // 「导出」只把图片复制出去，库里的原图不动，所以不走删除那套二次确认。
        btnExport.setOnClickListener { showExportDialog() }
    }

    /**
     * 批量导出：先问打包成一个 zip 还是逐张放进文件夹。
     *
     * 两种都只读库里的文件，不改数据库也不删原图 —— 导出失败最多是目标位置写不进去。
     */
    private fun showExportDialog() {
        if (selectedEmojis().isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_pick_title)
            .setMessage(R.string.export_pick_message)
            .setNeutralButton(R.string.export_pick_folder) { _, _ -> exportFolderPicker.launch(null) }
            .setPositiveButton(R.string.export_pick_zip) { _, _ ->
                exportZipPicker.launch(getString(R.string.export_zip_name, timestamp()))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun selectedEmojis(): List<EmojiEntity> {
        val ids = viewModel.selectedIds.value
        return viewModel.emojis.value.filter { ids.contains(it.id) }
    }

    private fun exportTargets(): List<ExportTarget> =
        selectedEmojis().map { ExportTarget(it.name, it.filePath) }

    private fun runExportZip(target: Uri) {
        val targets = exportTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = busyDialog(R.string.export_progress_title, R.string.export_progress)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val out = runCatching { contentResolver.openOutputStream(target, "w") }.getOrNull()
                if (out == null) {
                    ImageExporter.Report(0, targets.size, 0L)
                } else {
                    out.use { stream ->
                        ImageExporter.zip(stream, targets) { done, total ->
                            showProgress(dialog, R.string.export_progress, done, total)
                        }
                    }
                }
            }
            BusyDialog.dismiss(this@MainActivity, dialog)
            Toast.makeText(this@MainActivity, exportResultText(report, zip = true), Toast.LENGTH_LONG).show()
        }
    }

    private fun runExportFolder(treeUri: Uri) {
        val targets = exportTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = busyDialog(R.string.export_progress_title, R.string.export_progress)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                ImageExporter.copyToTree(this@MainActivity, treeUri, targets) { done, total ->
                    showProgress(dialog, R.string.export_progress, done, total)
                }
            }
            BusyDialog.dismiss(this@MainActivity, dialog)
            Toast.makeText(this@MainActivity, exportResultText(report, zip = false), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportResultText(report: ImageExporter.Report, zip: Boolean): String = when {
        report.written == 0 -> getString(R.string.export_none)
        else -> buildString {
            append(
                if (zip) {
                    getString(
                        R.string.export_done_zip,
                        report.written,
                        Formatter.formatShortFileSize(this@MainActivity, report.bytes)
                    )
                } else {
                    getString(R.string.export_done_folder, report.written)
                }
            )
            if (report.failed > 0) append(getString(R.string.export_partial, report.failed))
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.emojis.collectLatest { emojis ->
                adapter.submitList(emojis)
                emptyView.visibility = if (emojis.isEmpty()) View.VISIBLE else View.GONE
                emojiGrid.visibility = if (emojis.isEmpty()) View.GONE else View.VISIBLE
                // 列表换了内容（比如搜索把结果筛短）之后按钮该收就收；等这一帧摆完再看
                emojiGrid.post { updateScrollTop() }
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
                btnOrganize.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                btnExport.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedIds.collectLatest { selectedIds ->
                btnDelete.text = getString(R.string.btn_delete_count, selectedIds.size)
                btnExport.text = getString(R.string.btn_export_count, selectedIds.size)
                btnExport.isEnabled = selectedIds.isNotEmpty()
                adapter.notifyDataSetChanged()
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

    /**
     * 依次导入一批图片：一张真的落库之后才处理下一张。
     *
     * 串行不只是为了进度条好看 —— 重名去重（[com.aris.emojichan.data.EmojiNaming.unique]）是按
     * 落库顺序算的，并发写会让同一批里的重名各算各的，最后撞成两个「猫猫」。
     */
    /**
     * 导入入口：让用户选图从哪儿来。
     * 相册（系统图片选择器）界面好看、能一次多选，但它在本地索引里查不到真名时只给一串 id；
     * 文件（DocumentsUI）界面朴素，可显示名就是磁盘上的真名 —— 名字对不上时走这条（emc-2-008）。
     * 两条路都不需要任何存储权限。
     */
    private fun showImportSourceDialog() {
        val labels = mutableListOf(
            getString(R.string.import_source_album),
            getString(R.string.import_source_files),
            getString(R.string.import_source_folder)
        )
        // 没有相册权限时多给一条：授权后图片选择器给的 id 才能拿去别处换回真名。
        val canGrant = ImageUtil.mediaAccessLevel(this) == "无"
        if (canGrant) labels += getString(R.string.import_grant_media)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_source_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    // 前两条都不需要存储权限：ACTION_PICK_IMAGES 靠选择器给的一次性授权，
                    // 文件选择器同样自带按次的授权（emc-1-021 的教训）。
                    which == 0 -> launchAlbumPicker()
                    which == 1 -> filePicker.launch(arrayOf("image/*"))
                    which == 2 -> folderPicker.launch(null)
                    else -> requestMediaPermission()
                }
            }
            .show()
    }

    // ---- 表情库迁移（导出 / 导入备份包）----
    // v0.1.406 起从高级设置页搬回这里，入口就在「高级设置」上面。

    /** 导出：交给系统的「保存到…」，拿到目标 URI 后一边写一边报进度。 */
    private fun runExport(target: Uri) {
        val dialog = busyDialog(R.string.archive_export_progress_title, R.string.archive_export_progress)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                val out = runCatching { contentResolver.openOutputStream(target, "w") }.getOrNull()
                if (out == null) {
                    null
                } else {
                    out.use { stream ->
                        viewModel.exportLibrary(stream) { done, total ->
                            showProgress(dialog, R.string.archive_export_progress, done, total)
                        }
                    }
                }
            }
            BusyDialog.dismiss(this@MainActivity, dialog)
            if (report == null) {
                Toast.makeText(this@MainActivity, getString(R.string.archive_err_generic), Toast.LENGTH_LONG).show()
                return@launch
            }
            val text = when {
                report.error != null -> errorText(report.error)
                report.emojis == 0 && report.failed == 0 -> getString(R.string.archive_export_empty)
                else -> buildString {
                    append(
                        getString(
                            R.string.archive_export_done,
                            report.emojis,
                            report.tags,
                            Formatter.formatShortFileSize(this@MainActivity, report.bytes)
                        )
                    )
                    if (report.failed > 0) append(getString(R.string.archive_export_done_missing, report.failed))
                    if (report.ignored > 0) append(getString(R.string.archive_export_done_ignored, report.ignored))
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.settings_export_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 导入：合并还是覆盖，先问一句。 */
    private fun askImportMode(source: Uri) {
        val modes = arrayOf(getString(R.string.archive_import_merge), getString(R.string.archive_import_replace))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.archive_import_mode_title)
            .setItems(modes) { _, which ->
                if (which == 0) runImport(source, EmojiArchive.Mode.MERGE) else askBeforeReplace(source)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 覆盖导入会把现有的表情和标签全清掉，先把「现在有多少张」摆给用户看。 */
    private fun askBeforeReplace(source: Uri) {
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { viewModel.countEmojisOnce() }
            if (existing == 0) {
                runImport(source, EmojiArchive.Mode.REPLACE)
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.archive_import_replace_title)
                .setMessage(getString(R.string.archive_import_replace_message, existing))
                .setPositiveButton(R.string.archive_import_replace) { _, _ ->
                    runImport(source, EmojiArchive.Mode.REPLACE)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 导入：解包 + 入库都在 importLibrary 里做完，回来的就是最终结果。 */
    private fun runImport(source: Uri, mode: EmojiArchive.Mode) {
        val dialog = busyDialog(R.string.archive_import_progress_title, R.string.archive_import_progress)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                viewModel.importLibrary(
                    openInput = { runCatching { contentResolver.openInputStream(source) }.getOrNull() },
                    mode = mode
                ) { done, total ->
                    showProgress(dialog, R.string.archive_import_progress, done, total)
                }
            }
            BusyDialog.dismiss(this@MainActivity, dialog)
            val text = when {
                report.error != null -> errorText(report.error)
                else -> buildString {
                    append(getString(R.string.archive_import_done, report.emojis, report.tags))
                    if (report.skipped > 0) append(getString(R.string.archive_import_done_skipped, report.skipped))
                    if (report.failed > 0) append(getString(R.string.archive_import_done_failed, report.failed))
                    // 忽略名单随包来回：说了恢复几条，也要说丢了几条，不然用户只会看到名单莫名少了
                    if (report.ignoredDropped > 0) {
                        append(
                            getString(
                                R.string.archive_import_done_ignored_dropped,
                                report.ignored,
                                report.ignoredDropped
                            )
                        )
                    } else if (report.ignored > 0) {
                        append(getString(R.string.archive_import_done_ignored, report.ignored))
                    }
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.settings_import_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun errorText(error: EmojiArchive.Error): String = when (error) {
        EmojiArchive.Error.NOT_ARCHIVE -> getString(R.string.archive_err_not_archive)
        EmojiArchive.Error.BROKEN -> getString(R.string.archive_err_broken)
        EmojiArchive.Error.UNREADABLE -> getString(R.string.archive_err_unreadable)
        EmojiArchive.Error.IO -> getString(R.string.archive_err_generic)
    }

    /**
     * 干重活时的进度框（v0.2.003 起带进度条，用户 m09940）：标题 + 一根条 + 「第 N / M 张」。
     *
     * 开头传 0 是因为总数往往还没数出来：进度条先转圈，第一张处理完就有分母了。
     * 迁移期间不让点掉 —— 进度框一关就不知道还在不在写。
     */
    private fun busyDialog(titleRes: Int, formatRes: Int): BusyDialog.Progress =
        BusyDialog.showProgress(this, titleRes, getString(formatRes, 0, 0))

    private fun showProgress(progress: BusyDialog.Progress, formatRes: Int, done: Int, total: Int) {
        BusyDialog.update(this, progress, done, total, getString(formatRes, done, total))
    }

    /** 一张图的导入结果：拿到真名 / 没拿到（用导入时间兜底）/ 失败。 */
    private enum class ImportOutcome { OK, NAME_FALLBACK, FAILED }

    /**
     * 相册 / 文件多选导入（用户 m11668 第 4 条起：从时间靠前的开始导）。
     *
     * 落库时 createTime 记的是「导入那一刻」，网格按它倒序 —— 于是从早到晚导入，
     * 源文件时间最新的那批最后进来、排在网格最前面，正在用的那些不用在几千张里翻。
     * 问时间戳要一张张查，所以放在 IO 线程上（单列查询，很轻）。
     */
    private fun importImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val ordered = if (uris.size > 1) {
                withContext(Dispatchers.IO) { ImportOrder.sortedByTime(this@MainActivity, uris) }
            } else {
                uris
            }
            // 单张不弹进度框：一闪而过反而像卡了。
            var progress: BusyDialog.Progress? = null
            if (ordered.size > 1) {
                progress = BusyDialog.showProgress(
                    this@MainActivity,
                    R.string.import_progress_title,
                    getString(R.string.import_progress_format, 1, ordered.size)
                )
            }

            var ok = 0
            var failed = 0
            var noName = 0
            try {
                ordered.forEachIndexed { index, uri ->
                    progress?.let { bar ->
                        BusyDialog.update(
                            this@MainActivity,
                            bar,
                            index + 1,
                            ordered.size,
                            getString(R.string.import_progress_format, index + 1, ordered.size)
                        )
                    }
                    // 落库要整份拷贝文件、还要解码图片读宽高，全是阻塞 IO：
                    // 放在主线程上，导入一张大图就会卡住界面甚至 ANR（emc-1-018）。
                    when (withContext(Dispatchers.IO) { copyUriToLibrary(uri) }) {
                        ImportOutcome.OK -> ok++
                        ImportOutcome.NAME_FALLBACK -> {
                            ok++
                            noName++
                        }
                        ImportOutcome.FAILED -> failed++
                    }
                }
            } finally {
                // 页面销毁会取消 lifecycleScope，进度框必须在这里收掉，否则窗口跟着泄漏。
                progress?.let { BusyDialog.dismiss(this@MainActivity, it) }
            }
            showImportResult(ok, failed, noName)
            // 一张都没进来就别动用户的原文件（见「导入后的原文件怎么办」这条策略）。
            if (ok > 0) handleSourceFiles(ordered)
        }
    }

    /**
     * 导入成功后按用户在「高级设置 → 表情原文件」里选的策略处理源文件。
     *
     * 只碰 [sources] 这几个 Uri —— 不扫相册、不按目录找文件（[SourceFiles] 开头写了三条底线）。
     * 文件是用户自己刚选的，所以这里不再问「要不要处理」，只在「删除」这一策略上多问一次。
     *
     * 文件夹导入（[importFolder]）暂时不套这条策略：那里一次可能进来上千个文件，
     * 逐个删要弹无数次系统框，等以后单独做。
     */
    private fun handleSourceFiles(sources: List<Uri>) {
        if (sources.isEmpty()) return
        when (ImportPrefs.policy(this)) {
            ImportPrefs.POLICY_ZIP -> {
                val tree = ImportPrefs.zipTreeUri(this)
                if (tree == null) {
                    Toast.makeText(this, R.string.source_zip_need_dir, Toast.LENGTH_LONG).show()
                    return
                }
                val dialog = BusyDialog.showProgress(
                    this,
                    R.string.source_zip_title,
                    getString(R.string.source_zip_progress, 1, sources.size)
                )
                val zipName = SourceFiles.zipName()
                lifecycleScope.launch {
                    val zipped = withContext(Dispatchers.IO) {
                        runCatching {
                            SourceFiles.zipSources(
                                this@MainActivity,
                                Uri.parse(tree),
                                sources,
                                zipName
                            ) { done, total ->
                                BusyDialog.update(
                                    this@MainActivity,
                                    dialog,
                                    done,
                                    total,
                                    getString(R.string.source_zip_progress, done, total)
                                )
                            }
                        }.getOrNull()
                    }
                    BusyDialog.dismiss(this@MainActivity, dialog)
                    if (zipped.isNullOrEmpty()) {
                        // 包没写成，源文件一个都不删（zipSources 自己也会把半成品删掉）。
                        Toast.makeText(this@MainActivity, R.string.source_zip_failed, Toast.LENGTH_LONG)
                            .show()
                        return@launch
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.source_zip_done, zipName),
                        Toast.LENGTH_LONG
                    ).show()
                    // 只删**确实进了包**的那些：读不出来的那些留在原处，不冒险。
                    startSourceDelete(zipped)
                }
            }

            ImportPrefs.POLICY_DELETE -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.source_delete_title)
                .setMessage(getString(R.string.source_delete_message, sources.size))
                .setPositiveButton(R.string.source_delete) { _, _ -> startSourceDelete(sources) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            else -> Unit
        }
    }

    /**
     * 删原文件。两条路分开走：
     * - 文件选择器 / 文件夹来的（DocumentsProvider 管的）直接请它删；
     * - 相册里的图（MediaStore 管的）交给系统的批量删除请求，用户点同意才删得掉。
     */
    private fun startSourceDelete(uris: List<Uri>) {
        lifecycleScope.launch {
            // 删的是用户自己的原文件，一次能挑几百个 —— 也得让进度条动起来（用户 m09940）
            val dialog = BusyDialog.showProgress(
                this@MainActivity,
                R.string.delete_progress_title,
                getString(R.string.source_delete_progress, 1, uris.size)
            )
            val (docCount, docDeleted, media) = withContext(Dispatchers.IO) {
                val mediaUris = SourceFiles.mediaUrisForDelete(this@MainActivity, uris)
                val docUris = uris.filterNot { it in mediaUris }
                val deleted = if (docUris.isEmpty()) 0 else
                    SourceFiles.deleteDocuments(this@MainActivity, docUris) { done, total ->
                        BusyDialog.update(
                            this@MainActivity,
                            dialog,
                            done,
                            total,
                            getString(R.string.source_delete_progress, done, total)
                        )
                    }
                Triple(docUris.size, deleted, mediaUris)
            }
            BusyDialog.dismiss(this@MainActivity, dialog)
            pendingDocDeleted = docDeleted
            pendingSkipped = docCount - docDeleted
            pendingMediaCount = 0

            if (media.isEmpty()) {
                reportSourceDelete(pendingDocDeleted, pendingSkipped)
                return@launch
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Android 11 之前没有批量删除请求，别人的图我们删不掉，如实说。
                pendingSkipped += media.size
                reportSourceDelete(pendingDocDeleted, pendingSkipped)
                return@launch
            }
            val launched = runCatching {
                val request = MediaStore.createDeleteRequest(contentResolver, media)
                mediaStoreDeleteLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
            }.isSuccess
            if (launched) {
                pendingMediaCount = media.size
            } else {
                pendingSkipped += media.size
                reportSourceDelete(pendingDocDeleted, pendingSkipped)
            }
        }
    }

    private fun reportSourceDelete(deleted: Int, skipped: Int) {
        val text = buildString {
            if (deleted > 0) append(getString(R.string.source_delete_done, deleted))
            if (deleted > 0 && skipped > 0) append("；")
            if (skipped > 0) append(getString(R.string.source_delete_skipped, skipped))
        }
        if (text.isNotEmpty()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /**
     * 文件夹导入：图片一多就要读一会儿，先弹一个不可取消的进度框 ——
     * 没有反馈的话，用户多半以为没点上，又去点一次。
     */
    private fun importFolder(treeUri: Uri) {
        lifecycleScope.launch {
            // 两段进度（用户 m09930）：先扫描，这时还没有分母，进度条转圈、只报「已找到 N 张」；
            // 扫完拿到总数，再按「已导入 / 总数」走百分比。
            val progress = BusyDialog.showProgress(
                this@MainActivity,
                R.string.import_folder_progress_title,
                getString(R.string.import_folder_scanning, 0),
                getString(R.string.import_folder_progress_message)
            )
            val result = try {
                viewModel.importFolder(
                    treeUri,
                    onScanning = { found ->
                        BusyDialog.update(
                            this@MainActivity,
                            progress,
                            0,
                            0,
                            getString(R.string.import_folder_scanning, found)
                        )
                    },
                    onProgress = { done, total ->
                        BusyDialog.update(
                            this@MainActivity,
                            progress,
                            done,
                            total,
                            getString(R.string.import_progress_format, done, total)
                        )
                    }
                )
            } finally {
                BusyDialog.dismiss(this@MainActivity, progress)
            }
            val text = when {
                result.count < 0 -> getString(R.string.import_folder_unreadable)
                result.count == 0 -> getString(R.string.import_folder_empty)
                else -> getString(R.string.import_folder_result, result.count)
            } + if (result.truncated) getString(R.string.import_folder_truncated) else ""
            Snackbar.make(findViewById(R.id.rootLayout), text, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * 导入结果：条数多时用 Snackbar，顺手挂一个「再导入一批」——
     * 一次挑一张的相册里不用来回点导入按钮，接着挑就是。
     */
    private fun showImportResult(ok: Int, failed: Int, noName: Int = 0) {
        val base = when {
            ok == 0 -> getString(R.string.toast_import_failed)
            failed > 0 -> getString(R.string.import_result_partial, ok, failed)
            ok == 1 -> getString(R.string.toast_import_success)
            else -> getString(R.string.import_result_ok, ok)
        }
        // 没读到原名时补一句：不说的话用户只看到「已导入 3 张」，
        // 然后在列表里发现三张图叫「09-28 14:20」（emc-2-008）。
        val text = if (noName > 0) base + getString(R.string.import_no_name_suffix, noName) else base
        // 名字没读到的那一批，光说「再导入一批」没用 —— 给一条真能拿到名字的路（emc-2-008）：
        // 还没授权就先请授权（授权后同一个 id 才能换回真名），已授权就换文件导入。
        val bar = Snackbar.make(findViewById(R.id.rootLayout), text, Snackbar.LENGTH_LONG)
        when {
            noName == 0 -> bar.setAction(R.string.import_continue) { launchAlbumPicker() }
            ImageUtil.mediaAccessLevel(this) == "无" ->
                bar.setAction(R.string.import_grant_media_short) { requestMediaPermission() }
            else -> bar.setAction(R.string.import_use_files) { filePicker.launch(arrayOf("image/*")) }
        }
        bar.show()
    }

    /**
     * 把一个外部 Uri 落进表情库。
     * 相册点「导入」和系统分享面板进来共用这一段，免得两处逻辑跑偏。
     */
    private suspend fun copyUriToLibrary(uri: Uri): ImportOutcome {
        // 拷贝文件、解宽高、取名全在 IO 线程上；取名与落库的口径统一在 EmojiImport 里
        val result = withContext(Dispatchers.IO) { EmojiImport.fromUri(this@MainActivity, uri) }
        val emoji = result.emoji ?: return ImportOutcome.FAILED
        // 等它真的落库才算导入成功：不然一批导入里名字去重会各算各的
        if (viewModel.importEmoji(emoji) == null) return ImportOutcome.FAILED
        return if (result.usedFallback) ImportOutcome.NAME_FALLBACK else ImportOutcome.OK
    }

    private fun openEmojiDetail(emoji: EmojiEntity) {
        // 只传 id，详情页按 id 订阅数据库真值，避免搬运残缺实体导致字段被覆盖。
        // v0.2.013（用户 m11668 第 1 条）：顺带把格子上现在这一屏的顺序带过去，
        // 详情页据此左右划换图、并在顶上显示 (第几个 / 共几个) —— 范围就是 tag 筛选之后的结果。
        val siblings = adapter.currentList.map { it.id }.toLongArray()
        val intent = Intent(this, EmojiDetailActivity::class.java).apply {
            putExtra(EmojiDetailActivity.EXTRA_EMOJI_ID, emoji.id)
            putExtra(EmojiDetailActivity.EXTRA_SIBLINGS, siblings)
        }
        startActivity(intent)
    }

    override fun onPause() {
        super.onPause()
        // 候选列表是独立窗口，页面退到后台前先收掉，免得它留在屏幕上
        dismissSuggestions()
    }

    override fun onResume() {
        super.onResume()
        // 「在最近任务里隐藏」只对当前这个 task 实例有效（划掉重开就回到默认），
        // 所以每次回前台都按设置重新套一遍（用户 m11668 第 3 条）。
        RecentsHider.apply(this)
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
        // 上次把它开着，这次进应用就自己挂回来（用户 m09897）。没权限就当没这回事，
        // 不弹提示 —— 开关会如实停在「关」，用户点它的时候再走权限引导。
        if (!justStarted && SenderPrefs.ballOn(this) &&
            Settings.canDrawOverlays(this) && !FloatingBallService.isRunning
        ) {
            FloatingBallService.start(this)
            justStarted = true
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
