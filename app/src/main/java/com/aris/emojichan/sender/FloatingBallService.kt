package com.aris.emojichan.sender

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮球：屏幕上那颗小圆球 + 点开后弹出的表情面板。
 *
 * 用 Service 而不是 Activity，是因为用户离开本应用（切到微信）之后窗口还得留在屏幕上。
 * 这里刻意**不用前台服务**（不显示常驻通知）：只在用户主动开启时挂窗口，
 * 关闭开关或系统回收进程窗口就消失。
 *
 * 已知风险：部分国产 ROM（小米 HyperOS 等）在应用退到后台后会冻结整个进程，
 * 冻结期间窗口还在但点不动 —— 这一版就是用来实测这个的。
 */
class FloatingBallService : Service() {

    companion object {
        private const val PREFS = "sender_overlay"
        private const val KEY_X = "ball_x"
        private const val KEY_Y = "ball_y"
        /** 触摸余量：窗口比视觉直径大 2×这个值，小球也按得着。 */
        private const val BALL_TOUCH_PAD_DP = 4
        private const val BALL_MARGIN_DP = 8

        /** 面板高度占屏幕的比例。 */
        private const val PANEL_HEIGHT_RATIO = 0.56f

        /** 相册路线发完之后，再等这么久才把相册里那张临时图删掉（留够微信读文件的时间）。 */
        private const val ALBUM_CLEANUP_DELAY_MS = 15000L

        /** 重挂窗口时把球吸到最近的边（改完大小用，见 snapBallToEdge）。 */
        private const val EXTRA_SNAP_EDGE = "snap_edge"

        /** 供界面侧查询开关状态；进程被杀后随之复位，与窗口是否还在保持一致。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 正在跑的这一个实例：深浅色 / 主题色改了要照着它把球重画一遍。 */
        @Volatile
        private var instance: FloatingBallService? = null

        /**
         * 主题色或深浅色改了之后叫一声。
         *
         * 球（连同底色、描边）是按「挂上屏幕那一刻」的配置 inflate 的，颜色又是
         * ?attr/colorSurface、?attr/colorPrimary 这类主题属性画出来的 —— 用户不手动
         * 关开一次悬浮球，它就一直停在旧颜色上。这里只让已经在跑的实例重画，没在跑
         * 就什么都不做。
         */
        fun refreshAppearance() {
            val service = instance ?: return
            service.handler.post { service.refreshBallAppearance() }
        }

        fun start(context: Context, snapToEdge: Boolean = false) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (snapToEdge) intent.putExtra(EXTRA_SNAP_EDGE, true)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: EmojiRepository

    /** 只跑主线程任务（数据库读写由 Room 自己切线程），销毁时整体取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 挂球时用的是不是深色配置；跟当前不一致就说明该重画一遍。 */
    private var ballNight = false
    private var panelView: View? = null
    private var panelController: SendPanelController? = null

    /** 面板是在哪个应用里打开的（微信 / QQ）—— 分享要发给它。 */
    private var panelAppPackage: String? = null

    /** 最近一条系统 Toast：新消息来了先把旧的收掉，免得排队弹半天。 */
    private var toast: Toast? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 球只跟着「前台应用在不在微信 / QQ」走。
     * 无障碍服务没开时收不到这个信号，显隐逻辑会退化成一直在。
     */
    private val foregroundListener: (String?) -> Unit = { syncBallVisibility() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        SendLog.init(this)
        SendLog.d("球", "悬浮球服务启动")
        windowManager = getSystemService(WindowManager::class.java)
        repository = EmojiRepository(this)
        showBall()
        // 注册时会立刻回调一次当前的前台应用，所以刚挂上的球马上就能被收掉。
        AutoSendService.addForegroundListener(foregroundListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统按 START_STICKY 重建时不会再走 onCreate 之外的分支，这里兜底补挂窗口。
        if (ballView == null) showBall()
        if (intent?.getBooleanExtra(EXTRA_SNAP_EDGE, false) == true) snapBallToEdge()
        return START_STICKY
    }

    /**
     * 把球吸到最近的一侧边。改完大小后原来的贴边位置会跑偏、变大还可能顶出屏幕，
     * 用户就得再手动摆一次，所以重挂窗口时顺手贴一次边；纵向位置保留，只保证不出屏。
     */
    private fun snapBallToEdge() {
        val view = ballView ?: return
        val params = ballParams ?: return
        val (screenWidth, screenHeight) = screenSize()
        val margin = dp(BALL_MARGIN_DP)
        val centerX = params.x + params.width / 2
        params.x = if (centerX <= screenWidth / 2) margin else screenWidth - params.width - margin
        params.y = params.y.coerceIn(0, maxOf(0, screenHeight - params.height))
        runCatching { windowManager.updateViewLayout(view, params) }
        persistBallPosition(params)
    }

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        AutoSendService.removeForegroundListener(foregroundListener)
        hidePanel()
        toast?.cancel()
        toast = null
        removeBall()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 悬浮球 ----------

    private fun showBall() {
        if (ballView != null) return

        val themed = com.aris.emojichan.UiPrefs.themedContext(this)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_floating_ball, null)

        ballNight = com.aris.emojichan.UiPrefs.isNight(this)
        applyBallStyle(view)

        // 视觉直径是用户在「UI 设置」里挑的那个值，窗口再放宽 2×4dp 当触摸余量。
        val size = dp(com.aris.emojichan.UiPrefs.ballSizeDp(this))
        val touchSize = size + dp(BALL_TOUCH_PAD_DP)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val (screenWidth, screenHeight) = screenSize()

        val params = WindowManager.LayoutParams(
            touchSize,
            touchSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点：否则悬浮球一挂上，微信里正在打字的输入法会被顶掉。
            // NOT_FOCUSABLE 本身已隐含 NOT_TOUCH_MODAL（球以外的触摸继续传给下层），
            // 这里显式写出来是为了让意图清楚。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次的位置，但一定要夹回当前屏幕范围内：
            // 横竖屏一换、分辨率一变（或换了设备），旧坐标可能整颗球都在屏幕外，
            // 那时球既看不见也点不到，用户只能去设置里关了再开（emc-1-024）。
            x = prefs.getInt(KEY_X, screenWidth - touchSize - dp(BALL_MARGIN_DP))
                .coerceIn(0, maxOf(0, screenWidth - touchSize))
            y = prefs.getInt(KEY_Y, screenHeight / 3)
                .coerceIn(0, maxOf(0, screenHeight - touchSize))
        }

        attachBallTouch(view, params)
        windowManager.addView(view, params)

        ballView = view
        ballParams = params
        SendLog.d("球", "悬浮球已挂上屏幕")
    }

    private fun removeBall() {
        ballView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        ballView = null
        ballParams = null
    }

    /**
     * 球的三种长相：默认图案（布局里自带）、纯色表情球、用户自己挑的图。
     * 自定义图不在（清了数据、换了手机）就退回默认图案，不给用户看一个空白球。
     */
    private fun applyBallStyle(view: View) {
        val themed = com.aris.emojichan.UiPrefs.themedContext(this)
        val image = view.findViewById<android.widget.ImageView>(R.id.floatingBallImage)
        val icon = view.findViewById<android.widget.TextView>(R.id.floatingBallIcon)

        // 底色和描边是主题属性（?attr/colorSurface、?attr/colorPrimary）画出来的，
        // 所以每次都拿「当前」配置重新解析一遍：切深浅色、换主题色后球能自己变过来，
        // 不用把窗口摘下来重挂。
        image.background = androidx.core.content.ContextCompat
            .getDrawable(themed, R.drawable.bg_floating_ball_photo)
        icon.background = androidx.core.content.ContextCompat
            .getDrawable(themed, R.drawable.bg_floating_ball)

        when (com.aris.emojichan.UiPrefs.ballStyle(this)) {
            com.aris.emojichan.UiPrefs.BALL_DOT -> {
                image.visibility = View.GONE
                icon.visibility = View.VISIBLE
            }

            com.aris.emojichan.UiPrefs.BALL_CUSTOM -> {
                val file = com.aris.emojichan.UiPrefs.ballFile(this)
                image.visibility = View.VISIBLE
                icon.visibility = View.GONE
                if (file.exists()) {
                    // Glide 的磁盘缓存键只有「文件路径」这一个字符串（不含修改时间），
                    // 而换自定义球图时覆盖的正是同一个 ball.png —— 不加 signature，
                    // 用户换完图很可能还看到上一张（emc-1-027）。
                    com.bumptech.glide.Glide.with(this)
                        .load(file)
                        .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                        .into(image)
                } else {
                    image.setImageResource(R.drawable.game)
                }
            }

            else -> {
                image.visibility = View.VISIBLE
                icon.visibility = View.GONE
                image.setImageResource(R.drawable.game)
            }
        }
    }

    /** 深浅色 / 主题色变了：位置不动，只按新配置把球重画一遍（不重挂窗口）。 */
    private fun refreshBallAppearance() {
        val view = ballView ?: return
        ballNight = com.aris.emojichan.UiPrefs.isNight(this)
        applyBallStyle(view)
    }

    private fun attachBallTouch(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80L).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (screenWidth, screenHeight) = screenSize()
                        params.x = (originX + dx.toInt())
                            .coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                        params.y = (originY + dy.toInt())
                            .coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80L).start()
                    if (dragging) {
                        persistBallPosition(params)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        togglePanel()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun persistBallPosition(params: WindowManager.LayoutParams) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    // ---------- 表情面板 ----------

    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return

        // 面板自带全屏遮罩，会把悬浮球压在下面，所以先把它藏起来，
        // 关闭面板时再恢复 —— 比调窗口层级简单，也不会闪。
        ballView?.visibility = View.GONE

        val themed = com.aris.emojichan.UiPrefs.themedContext(this)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_send_panel, null)

        val (_, screenHeight) = screenSize()
        view.findViewById<View>(R.id.sendPanelSheet).let { sheet ->
            val sheetParams = sheet.layoutParams as FrameLayout.LayoutParams
            sheetParams.height = (screenHeight * PANEL_HEIGHT_RATIO).toInt()
            sheet.layoutParams = sheetParams
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val controller = SendPanelController(
            host = this,
            root = view,
            scope = scope,
            repository = repository,
            onPick = { emoji -> onEmojiPicked(emoji) },
            onClose = { hidePanel() }
        )
        controller.bind()
        // 读一次聊天页标题，面板顶部就能显示「将发给：谁」。
        // 读的是微信的节点树，几十毫秒，所以赶在面板挂上屏幕之前一次做完。
        panelAppPackage = AutoSendService.foregroundPackage
        val title = AutoSendService.readChatTitle()
        controller.setTarget(title)
        SendLog.d(
            "面板",
            "面板打开，所在应用 = " + (panelAppPackage ?: "未知") + "，顶部目标 = " + (title ?: "（没认出来）")
        )

        windowManager.addView(view, params)
        panelView = view
        panelController = controller
    }

    private fun hidePanel() {
        panelController?.release()
        panelController = null
        panelView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        panelView = null
        syncBallVisibility()
    }

    /**
     * 球只在微信 / QQ 里出现。无障碍服务没开启时无从得知前台应用，
     * 退化成一直在屏幕上，功能不因此变少。
     * 面板开着的时候球本来就是 GONE，这里不能把它拽回来。
     */
    private fun syncBallVisibility() {
        val view = ballView ?: return
        // 「跟随系统」时系统的深浅色变了也要跟上；前台切应用时顺路查一次，几乎零开销。
        if (com.aris.emojichan.UiPrefs.isNight(this) != ballNight) refreshBallAppearance()
        if (panelView != null) return
        val visible = !AutoSendService.isConnected || AutoSendService.foregroundPackage != null
        val next = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != next) {
            SendLog.d(
                "球",
                (if (visible) "显示" else "隐藏") + "：无障碍服务" +
                    (if (AutoSendService.isConnected) "已连接" else "未连接") +
                    "，前台=" + (AutoSendService.foregroundPackage ?: "无")
            )
        }
        view.visibility = next
    }

    private fun onEmojiPicked(emoji: EmojiEntity) {
        // 使用记录只是统计，失败不影响这次发送。
        scope.launch { runCatching { repository.recordUsage(emoji.id) } }

        // 面板一关 controller 就没了，目标名要赶在 hidePanel 之前取出来。
        val title = panelController?.targetName?.takeIf { it.isNotEmpty() }
            ?: AutoSendService.readChatTitle()

        // 先收面板：面板窗口留着的话会盖在微信自己的界面上。
        hidePanel()

        // 优先用「点发送这一刻」的前台应用；面板开着时前台仍是微信 / QQ 本身。
        // 两条都读不到时**不再兜底猜微信**（emc-1-026）：猜错的后果是把图塞进微信的选人页，
        // 而用户这时可能根本不在微信里；null 会一路走到系统分享面板，由用户自己挑应用。
        val appPackage = AutoSendService.foregroundPackage ?: panelAppPackage
        SendLog.d(
            "发送",
            "选中表情 id=" + emoji.id + "，发给 " + (appPackage ?: "（没认出前台应用）") +
                "，目标 = " + SendLog.mask(title)
        )

        // 三条路，前两条是实测结论，第三条还等着真机验证：
        //  · 微信 + 静态图 → 剪贴板粘贴：最快，目的地就是眼前这个会话，不用认人、没有选人页。
        //  · 微信 + 动图   → 两条备选，主页那行「动图发送」随时可切：
        //                     · 分享（默认）：只翻一次微信的「选择聊天」页，认得出聊天对象就自动点掉；
        //                     · 相册：微信原生发图流程，动图必保动画，但要连翻三个页面。
        //  · QQ            → 分享路线：QQ 输入框根本不吃图片（粘贴返回 true 但输入区没变化），
        //                    所以连剪贴板都不用放，省掉那两步无用功。
        val animated = emoji.fileType.equals("gif", ignoreCase = true)
        val wechatGif = animated && appPackage == AutoSendService.PACKAGE_WECHAT
        val albumRoute = wechatGif && SenderPrefs.wechatGifViaAlbum(this)
        SendLog.d(
            "发送",
            "策略 = " + (
                if (appPackage == null) "分享（系统面板：没认出前台应用）"
                else if (appPackage != AutoSendService.PACKAGE_WECHAT) "分享（QQ）"
                else if (!animated) "粘贴（微信静态图）"
                else if (albumRoute) "相册（微信动图）"
                else "分享（微信动图）"
                )
        )
        scope.launch {
            if (appPackage == null) {
                // 认不出前台应用就别动无障碍了：直接把选择权交给系统面板。
                showHint(getString(R.string.overlay_unknown_app))
                shareByIntent(emoji, null, title, allowInfoRead = false)
                return@launch
            }
            if (AutoSendService.isConnected && appPackage == AutoSendService.PACKAGE_WECHAT) {
                if (!animated) {
                    if (EmojiShare.copyToClipboard(this@FloatingBallService, emoji)) {
                        val pasted = AutoSendService.pasteIntoChat()
                        // 不管粘成没粘成，这张图的临时读权限都该收回来了（emc-1-029）。
                        EmojiShare.releaseClipboardGrants(this@FloatingBallService)
                        if (pasted) {
                            showHint(getString(R.string.overlay_pasted))
                            return@launch
                        }
                        showHint(getString(R.string.overlay_paste_failed))
                    }
                } else if (albumRoute && sendViaAlbum(emoji)) {
                    return@launch
                }
                // 动图永远不走粘贴：微信的粘贴会把动图压成静态图，走过去等于白丢动画。
            }
            // 微信动图不点「更多信息」补读名字：那要再翻两次页面，而用户嫌的正是翻页面；
            // 认不出聊天对象就让他自己在选人页点一下。
            shareByIntent(emoji, appPackage, title, allowInfoRead = !wechatGif)
        }
    }

    /**
     * 相册路线：把表情临时放进系统相册，再让微信自己走「+ → 相册 → 第一格 → 发送」。
     * 目的地天然是当前聊天（不用认聊天对象名字），而且这是微信的原生发图流程，
     * 动图不会被压成静态图 —— 这正好是「粘贴」做不到的那一点。
     */
    private suspend fun sendViaAlbum(emoji: EmojiEntity): Boolean {
        val mime = if (emoji.fileType.equals("gif", ignoreCase = true)) "image/gif" else "image/*"
        if (AlbumPublish.publish(this, emoji.filePath, mime) == null) {
            showHint(getString(R.string.overlay_album_failed))
            return false
        }
        val ok = AutoSendService.sendPhotoViaAlbum()
        showHint(getString(if (ok) R.string.overlay_album_sent else R.string.overlay_album_failed))
        val context = this
        handler.postDelayed({ AlbumPublish.removeLast(context) }, ALBUM_CLEANUP_DELAY_MS)
        return ok
    }

    /**
     * 第二条路：把图片交给微信自己的分享入口，再在它的「选择聊天」页里点出目标。
     * [appPackage] 为 null 时（没认出前台应用）只把图交给系统分享面板，不替用户认人。
     * 目标名读不到时，这里才做最后那次「点开更多信息」的补读 —— 它会切一下页面，
     * 所以必须赶在分享之前做完。allowInfoRead = false 时跳过这最后一次补读
     * （微信动图那条路用得上：补读要翻两次页面，代价比让用户点一下更高）。
     */
    private suspend fun shareByIntent(
        emoji: EmojiEntity,
        appPackage: String?,
        knownTarget: String?,
        allowInfoRead: Boolean = true
    ) {
        var target = knownTarget ?: AutoSendService.readChatTitle()
        if (target == null && allowInfoRead && AutoSendService.isConnected) {
            target = AutoSendService.readChatTitleViaInfo()
        }

        val result = EmojiShare.send(this, emoji, appPackage)
        SendLog.d("发送", "分享结果 = " + result.javaClass.simpleName)
        EmojiShare.failureMessage(this, result)?.let { showHint(it) }

        if (!AutoSendService.isConnected) {
            // 以前这里是静默返回，用户只看到微信停在选人页，不知道发生了什么。
            showHint(getString(R.string.overlay_auto_off))
            return
        }
        if (result == EmojiShare.Result.FileMissing || result == EmojiShare.Result.NoTarget) return

        // 不知道目标应用时，后面「在选人页点掉同名项」就不能做：
        // 系统分享面板上那一页未必是微信，点错就是点到别的东西（emc-1-026）。
        if (appPackage == null) {
            SendLog.d("发送", "不知道目标应用，不自动点选会话，交给用户自己挑")
            return
        }

        val name = target
        if (name.isNullOrEmpty()) {
            showHint(getString(R.string.overlay_auto_no_target))
            return
        }

        // 微信接下来会停在「选择聊天」页，替用户把同名的那一项点掉。
        // 直达微信时列表马上出现；走了系统分享面板的话用户还得先挑应用，所以多给几秒。
        val timeout = if (result is EmojiShare.Result.SentToApp) 8000L else 15000L
        val clicked = AutoSendService.clickChatByName(name, timeout, appPackage)
        showHint(
            getString(
                if (clicked) R.string.overlay_auto_clicked else R.string.overlay_auto_failed,
                name
            )
        )
    }

    // ---------- 提示条 ----------

    /**
     * 发完表情后的那句提示，走系统 Toast（用户要求：别再自己画一条悬浮提示）。
     *
     * 记一笔历史：这里原先是拿悬浮窗自己画的 —— 因为发送那一刻应用已经退到后台
     * （前面是微信或系统分享面板），MIUI 这类 ROM 会把后台应用的 Toast 一并拦掉，
     * 而那条提示正是当时唯一想知道的东西。改成系统 Toast 后，如果在微信里看不到
     * 提示，多半就是被 ROM 拦了：原文永远写在日志「提示」那一行里，可回查。
     */
    private fun showHint(message: String) {
        SendLog.d("提示", message)
        runCatching {
            toast?.cancel()
            toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).also { it.show() }
        }
    }

    // ---------- 杂项 ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}
