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
import com.aris.emojichan.util.ImageTypes
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

        /**
         * 粘贴路线走完之后，那张图的临时读权限还留多久（emc-2-015）。
         *
         * 我们自己点掉「发送」时，微信读图就在点击之后几毫秒到几百毫秒内，留几秒足够；
         * 最后一击交给用户时他可能过一会儿才点，得留久一点 —— 收回早了，他点下去就是白发。
         * 这两条都只是上限：期间又复制了别的表情，上一份的权限会被立刻收掉。
         */
        private const val CLIP_GRANT_KEEP_SENT_MS = 5_000L
        private const val CLIP_GRANT_KEEP_STAGED_MS = 180_000L

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

        /**
         * 用户改完「球出现在哪些应用里」之后叫一声，立刻按新范围重算显隐 ——
         * 不然要等到下一次切应用才生效，看起来像设置没保存。
         */
        fun refreshScope() {
            val service = instance ?: return
            service.handler.post { service.syncBallVisibility() }
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
     * 球跟着「前台是哪个应用」走 —— 到底哪些应用里要出现，由用户勾的列表决定（[BallScope]）。
     * 无障碍服务没开时收不到这个信号，显隐逻辑会退化成一直在。
     */
    private val appListener: (String?) -> Unit = { syncBallVisibility() }

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
        AutoSendService.addAppListener(appListener)
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
        AutoSendService.removeAppListener(appListener)
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
     * 球的四种长相：默认（扁平猫脸，就是应用图标那张）、游戏开发部（像素猫）、
     * 纯色表情球、用户自己挑的图。
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
                    image.setImageResource(R.drawable.ic_cat_face)
                }
            }

            com.aris.emojichan.UiPrefs.BALL_CLASSIC -> {
                image.visibility = View.VISIBLE
                icon.visibility = View.GONE
                image.setImageResource(R.drawable.game)
            }

            else -> {
                image.visibility = View.VISIBLE
                icon.visibility = View.GONE
                image.setImageResource(R.drawable.ic_cat_face)
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
        panelAppPackage = AutoSendService.foregroundApp
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
     * 球的显隐只看一条：前台应用在不在用户勾的列表里（[BallScope.shouldShow]）。
     *
     * 以前看的是「前台在不在微信 / QQ」—— 那个集合的语义是「自动发送能不能用」，和球该不该
     * 出现本是两件事：勾了别的应用（或者一个都不勾 = 不限制）时球照样要出来，
     * 只是发送走系统分享面板。无障碍服务没开启时认不出前台，退化成一直在屏幕上，
     * 功能不因此变少。面板开着的时候球本来就是 GONE，这里不能把它拽回来。
     */
    private fun syncBallVisibility() {
        val view = ballView ?: return
        // 「跟随系统」时系统的深浅色变了也要跟上；前台切应用时顺路查一次，几乎零开销。
        if (com.aris.emojichan.UiPrefs.isNight(this) != ballNight) refreshBallAppearance()
        if (panelView != null) return
        val ballPackages = SenderPrefs.ballPackages(this)
        val foreground = AutoSendService.foregroundApp
        val visible = !AutoSendService.isConnected || BallScope.shouldShow(ballPackages, foreground)
        val next = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != next) {
            SendLog.d(
                "球",
                (if (visible) "显示" else "隐藏") + "：无障碍服务" +
                    (if (AutoSendService.isConnected) "已连接" else "未连接") +
                    "，前台=" + (foreground ?: "无") +
                    "，勾选=" + (if (ballPackages.isEmpty()) "所有应用" else ballPackages.size.toString() + " 个应用")
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

        // 优先用「点发送这一刻」的前台应用；面板开着时前台仍是底下那个应用本身。
        // 两条都读不到时**不再兜底猜微信**（emc-1-026）：猜错的后果是把图塞进微信的选人页，
        // 而用户这时可能根本不在微信里；null 会一路走到系统分享面板，由用户自己挑应用。
        val appPackage = AutoSendService.foregroundApp ?: panelAppPackage
        SendLog.d(
            "发送",
            "选中表情 id=" + emoji.id + "，发给 " + (appPackage ?: "（没认出前台应用）") +
                "，目标 = " + SendLog.mask(title)
        )

        // 四条路：
        //  · 微信 / QQ + 图片或动图 → 「相册」（v0.1.319 起的全局默认，设置页可切回「分享」）：
        //    让应用自己走「＋ → 相册 → 第一格 → 发送」，直接落到当前聊天 —— 不用认人、
        //    没有选人页，图片和动图都原样发出（微信的「粘贴」会把动图压成静图）。
        //  · 微信 + 静态图 + 用户在设置里把「微信里的静态图片」单独切成「剪贴板」→ 粘贴：
        //    最快，目的地就是眼前这个会话。
        //  · 分享：只翻一次应用的聊天列表，认得出聊天对象就自动点掉，认不出用户点一下。
        //  · 其他应用 → 分享路线：球可以出现在用户勾的任何应用里，走系统分享面板/显式分享，
        //    自动点选会话只对微信 / QQ 有意义（第三方 App 的选人页没写过流程）。
        //  · QQ 永远不走剪贴板：它的输入框根本不吃图片（粘贴返回 true 但输入区没变化）。
        val animated = emoji.fileType.equals("gif", ignoreCase = true)
        val wechat = appPackage == AutoSendService.PACKAGE_WECHAT
        val qq = appPackage == AutoSendService.PACKAGE_QQ
        val chatApp = wechat || qq
        val wechatGif = animated && wechat
        val clipboardRoute = wechat && !animated && SenderPrefs.wechatImageClipboard(this)
        val albumRoute = chatApp && !clipboardRoute && SenderPrefs.sendViaAlbum(this)
        val appName = if (wechat) "微信" else "QQ"
        SendLog.d(
            "发送",
            "策略 = " + (
                if (appPackage == null) "分享（系统面板：没认出前台应用）"
                else if (!chatApp) "分享（其他应用）"
                else if (clipboardRoute) "粘贴（微信静态图，设置里选的剪贴板）"
                else if (albumRoute) "相册（" + appName + (if (animated) "动图）" else "图片）")
                else "分享（" + appName + (if (animated) "动图）" else "图片）")
                )
        )
        scope.launch {
            if (appPackage == null) {
                // 认不出前台应用就别动无障碍了：直接把选择权交给系统面板。
                showHint(getString(R.string.overlay_unknown_app))
                shareByIntent(emoji, null, title, allowInfoRead = false)
                return@launch
            }
            if (AutoSendService.isConnected && chatApp) {
                if (clipboardRoute) {
                    if (EmojiShare.copyToClipboard(this@FloatingBallService, emoji)) {
                        when (AutoSendService.pasteIntoChat()) {
                            AutoSendService.PasteOutcome.SENT -> {
                                // 不能马上收回读权限：微信读图是在我们那一下点击**之后**
                                // （emc-2-015）。留几秒再收，够它读完。
                                EmojiShare.releaseClipboardGrantsLater(
                                    this@FloatingBallService, CLIP_GRANT_KEEP_SENT_MS
                                )
                                showHint(getString(R.string.overlay_pasted))
                                return@launch
                            }

                            AutoSendService.PasteOutcome.STAGED -> {
                                // 图已经在输入框 / 预览小窗里，最后那一下按设置留给了用户：
                                // 他可能几秒后才点，读权限就得一直留着（emc-2-015）。
                                EmojiShare.releaseClipboardGrantsLater(
                                    this@FloatingBallService, CLIP_GRANT_KEEP_STAGED_MS
                                )
                                showHint(getString(R.string.overlay_pasted_staged))
                                return@launch
                            }

                            AutoSendService.PasteOutcome.ALREADY_SENT -> {
                                // 用户自己点掉了最后那一下：微信正在读图（或者他马上还会再点），
                                // 读权限照 STAGED 那档留着（emc-2-015）。
                                EmojiShare.releaseClipboardGrantsLater(
                                    this@FloatingBallService, CLIP_GRANT_KEEP_STAGED_MS
                                )
                                showHint(getString(R.string.overlay_pasted_already_sent))
                                return@launch
                            }

                            AutoSendService.PasteOutcome.FAILED -> {
                                // 没粘进去就没人在读这张图，立刻收回（emc-1-029）。
                                EmojiShare.releaseClipboardGrants(this@FloatingBallService)
                                showHint(getString(R.string.overlay_paste_failed))
                            }
                        }
                    }
                } else if (albumRoute && sendViaAlbum(emoji)) {
                    return@launch
                }
                // 走相册就等于跳过粘贴：动图经微信的粘贴会被压成静图，走过去等于白丢动画。
            }
            // 微信动图不点「更多信息」补读名字：那要再翻两次页面，而用户嫌的正是翻页面；
            // 认不出聊天对象就让他自己在选人页点一下。
            shareByIntent(emoji, appPackage, title, allowInfoRead = !wechatGif)
        }
    }

    /**
     * 相册路线：把当前这张表情覆盖进系统相册的固定槽位，再让微信 / QQ 自己走
     * 「+ → 相册 → 第一格 → 发送」。
     * 目的地天然是当前聊天（不用认聊天对象名字），而且这是微信的原生发图流程，
     * 动图不会被压成静态图 —— 这正好是「粘贴」做不到的那一点。
     *
     * v0.1.317 起不再往相册里插新行、也不再删：槽位是固定的，发完就留在那儿等下次覆盖。
     * 写完先自检「相册里排第一的是不是我们这张」：确认不是就退回分享路线 ——
     * 宁可让用户多点一下，也不能让他把别人的图发出去。
     */
    private suspend fun sendViaAlbum(emoji: EmojiEntity): Boolean {
        // 以前非 gif 一律报 image/*，相册发布那边只好兜底写成 image/png —— webp / jpg 的类型
        // 就这么被写错了。现在按扩展名报准。
        val mime = ImageTypes.mimeOf(emoji.filePath)
        val slot = AlbumPublish.publish(this, emoji.filePath, mime)
        if (slot == null) {
            showHint(getString(R.string.overlay_album_failed))
            return false
        }
        val check = AlbumPublish.verify(this, slot)
        SendLog.d("相册", "自检：" + check.verdict + " —— " + check.detail)
        if (check.verdict == AlbumPublish.Verdict.FAIL) {
            showHint(getString(R.string.overlay_album_check_failed))
            return false
        }
        if (check.verdict == AlbumPublish.Verdict.UNKNOWN) {
            showHint(getString(R.string.overlay_album_check_unknown))
        }
        return when (AutoSendService.sendPhotoViaAlbum()) {
            AutoSendService.AlbumOutcome.SENT -> {
                showHint(getString(R.string.overlay_album_sent))
                true
            }

            AutoSendService.AlbumOutcome.STAGED -> {
                // 停在最后一步也算这条路走通了：图已经选好，点一下就走。
                // 这里必须返回 true，否则 onEmojiPicked 会接着走分享路线，等于再发一次。
                showHint(getString(R.string.overlay_album_staged))
                true
            }

            AutoSendService.AlbumOutcome.ALREADY_SENT -> {
                // 预览页已经关了、聊天页回来了 —— 那一下是用户自己点的，图已经在路上。
                // 这里同样必须返回 true：退回分享路线会把同一张表情再发一遍（emc-2-016）。
                showHint(getString(R.string.overlay_album_already_sent))
                true
            }

            AutoSendService.AlbumOutcome.FAILED -> {
                showHint(getString(R.string.overlay_album_failed))
                false
            }
        }
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
        // 「在选人页点掉同名项」这套流程是照微信 / QQ 的分享页实测写出来的。球现在能出现在
        // 用户勾的任何应用里，换成别的 App 就只把图交过去、由用户自己点人：点错=发给错的人。
        if (appPackage !in AutoSendService.WATCHED_PACKAGES) {
            SendLog.d("发送", "目标应用不在自动发送白名单里，只把图交过去，不自动点选")
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
        when (AutoSendService.clickChatByName(name, timeout, appPackage)) {
            AutoSendService.ChatClick.CLICKED ->
                showHint(getString(R.string.overlay_auto_clicked, name))

            // 走到确认框但按设置撒手了：这不是失败，别再说「请在列表中选择」。
            AutoSendService.ChatClick.DEFERRED ->
                showHint(getString(R.string.overlay_confirm_manual))

            AutoSendService.ChatClick.NO_TARGET ->
                showHint(getString(R.string.overlay_auto_failed, name))
        }
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
