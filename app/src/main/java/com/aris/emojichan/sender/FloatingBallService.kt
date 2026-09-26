package com.aris.emojichan.sender

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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
        private const val BALL_SIZE_DP = 56
        private const val BALL_MARGIN_DP = 8

        /** 面板高度占屏幕的比例。 */
        private const val PANEL_HEIGHT_RATIO = 0.56f

        /** 自己画的提示条停留多久：够看清一行失败原因，也不至于杵在微信上碍事。 */
        private const val HINT_DURATION_MS = 8000L

        /** 提示条距屏幕底边的高度，躲开微信自己的输入框。 */
        private const val HINT_BOTTOM_MARGIN_DP = 150

    /** 相册路线发完之后，再等这么久才把相册里那张临时图删掉（留够微信读文件的时间）。 */
    private const val ALBUM_CLEANUP_DELAY_MS = 15000L

        /** 供界面侧查询开关状态；进程被杀后随之复位，与窗口是否还在保持一致。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startService(Intent(context, FloatingBallService::class.java))
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
    private var panelView: View? = null
    private var panelController: SendPanelController? = null

    /** 面板是在哪个应用里打开的（微信 / QQ）—— 分享要发给它。 */
    private var panelAppPackage: String? = null

    /** 正在显示的提示条；同一时刻只留一条。 */
    private var hintView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hintDismisser = Runnable { dismissHint() }

    /**
     * 球只跟着「前台应用在不在微信 / QQ」走。
     * 无障碍服务没开时收不到这个信号，显隐逻辑会退化成一直在。
     */
    private val foregroundListener: (String?) -> Unit = { syncBallVisibility() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        AutoSendService.removeForegroundListener(foregroundListener)
        hidePanel()
        dismissHint()
        handler.removeCallbacks(hintDismisser)
        removeBall()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 悬浮球 ----------

    private fun showBall() {
        if (ballView != null) return

        val themed = ContextThemeWrapper(this, R.style.Theme_EmojiChan)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_floating_ball, null)

        val size = dp(BALL_SIZE_DP)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val (screenWidth, screenHeight) = screenSize()

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点：否则悬浮球一挂上，微信里正在打字的输入法会被顶掉。
            // NOT_FOCUSABLE 本身已隐含 NOT_TOUCH_MODAL（球以外的触摸继续传给下层），
            // 这里显式写出来是为了让意图清楚。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screenWidth - size - dp(BALL_MARGIN_DP))
            y = prefs.getInt(KEY_Y, screenHeight / 3)
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

        val themed = ContextThemeWrapper(this, R.style.Theme_EmojiChan)
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
        val appPackage = AutoSendService.foregroundPackage
            ?: panelAppPackage
            ?: AutoSendService.PACKAGE_WECHAT
        SendLog.d(
            "发送",
            "选中表情 id=" + emoji.id + "，发给 " + appPackage + "，目标 = " + (title ?: "（空）")
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
                if (appPackage != AutoSendService.PACKAGE_WECHAT) "分享（QQ）"
                else if (!animated) "粘贴（微信静态图）"
                else if (albumRoute) "相册（微信动图）"
                else "分享（微信动图）"
                )
        )
        scope.launch {
            if (AutoSendService.isConnected && appPackage == AutoSendService.PACKAGE_WECHAT) {
                if (!animated) {
                    if (EmojiShare.copyToClipboard(this@FloatingBallService, emoji)) {
                        if (AutoSendService.pasteIntoChat()) {
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
     * 目标名读不到时，这里才做最后那次「点开更多信息」的补读 —— 它会切一下页面，
     * 所以必须赶在分享之前做完。allowInfoRead = false 时跳过这最后一次补读
     * （微信动图那条路用得上：补读要翻两次页面，代价比让用户点一下更高）。
     */
    private suspend fun shareByIntent(
        emoji: EmojiEntity,
        appPackage: String,
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
     * 用悬浮窗自己画一条提示，而不是 Toast。
     *
     * 这条链路上 Toast 恰恰是最不可靠的：发送时应用已经退到后台（前面是微信或系统
     * 分享面板），MIUI 之类的 ROM 会把后台应用的 Toast 一起拦掉 —— 而这条提示正是
     * 那时候唯一想知道的东西（用户实测：弹了系统分享面板，却什么也没提示）。
     * 悬浮窗权限我们本来就有（球就挂在屏幕上），不受这个限制。
     */
    private fun showHint(message: String) {
        SendLog.d("提示", message)
        dismissHint()

        val hint = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            val padding = dp(14)
            setPadding(padding, dp(10), padding, dp(10))
            // 异常消息可能很长，限宽让它在屏幕内换行，而不是横着冲出屏幕。
            maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点、也不吃触摸：提示条只给眼睛看，不该影响下面的微信。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(HINT_BOTTOM_MARGIN_DP)
        }

        runCatching { windowManager.addView(hint, params) }.onSuccess {
            hintView = hint
            handler.postDelayed(hintDismisser, HINT_DURATION_MS)
        }
    }

    private fun dismissHint() {
        val view = hintView ?: return
        hintView = null
        handler.removeCallbacks(hintDismisser)
        runCatching { windowManager.removeView(view) }
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
