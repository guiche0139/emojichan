package com.aris.emojichan.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「表情自动发送」无障碍服务。
 *
 * 与 [ChatProbeService]（探测器）的关键区别：这个服务**平时什么都不做**。
 * 它订阅的事件只有「换页面」，处理时只读事件里的包名，不碰界面内容，
 * 因此不会像探测器那样持续占用微信的 UI 线程。
 * 真正读界面只发生在两个时刻，各一次：
 *
 *  - 用户点开悬浮球：[readChatTitle] 读一次聊天页标题，得到「将发给谁」；
 *  - 用户选完表情：  [clickChatByName] 在微信「选择聊天」页里找同名项点掉。
 *
 * 安全底线：名字读不到、列表里找不到、或者找到多个同名 —— 一律不点，
 * 让用户自己选。绝不点「最近第一个」，否则刚跟别人聊完就会发错人。
 */
class AutoSendService : AccessibilityService() {

    companion object {
        const val PACKAGE_WECHAT = "com.tencent.mm"
        const val PACKAGE_QQ = "com.tencent.mobileqq"

        /** 悬浮球只在这些应用里出现。 */
        val WATCHED_PACKAGES = setOf(PACKAGE_WECHAT, PACKAGE_QQ)

        private const val EDIT_TEXT_CLASS = "android.widget.EditText"

        /** 轮询「选择聊天」列表的间隔。 */
        private const val POLL_INTERVAL_MS = 250L
        private const val DEFAULT_CLICK_TIMEOUT_MS = 8000L

        /**
         * 收到「非白名单包」的换页事件后，隔多久再复查一次前台到底是谁。
         * 进微信时经常先收到桌面那条事件、后收到微信那条（顺序还会反过来），
         * 立刻下结论就会把「正在切进来」误判成「已经切走」，球于是不出现。
         */
        private const val FOREGROUND_RECHECK_DELAY_MS = 400L
        private const val FOREGROUND_RECHECK_LATE_MS = 1400L

        /** 聊天页判据：输入框必须落在屏幕这个比例以下（可排除微信首页顶部的搜索框）。 */
        private const val INPUT_TOP_RATIO = 0.45f

        /** 标题只在这个比例以上的区域里找。 */
        private const val TITLE_ZONE_RATIO = 0.18f

        /** 标题候选不能偏到右侧这个比例以外 —— 那里是「…」「Q我吧」这类按钮，不是聊天对象名。 */
        private const val TITLE_MAX_CENTER_RATIO = 0.72f

        /** 只在屏幕这个比例以下的行里找目标：上面是标题栏，不是列表行。 */
        private const val ROW_MIN_TOP_RATIO = 0.12f

        /** 点完名等一会儿再复查一次，确认那一行真的消失了（只写日志，不改结果）。 */
        private const val CLICK_VERIFY_DELAY_MS = 900L

        /** 有些机型选完联系人还会弹一个「发送给：X」确认框，等这么久再看一眼。 */
        private const val CONFIRM_DIALOG_DELAY_MS = 700L
        private const val CONFIRM_SEND_TEXT = "发送"
        private const val CONFIRM_CANCEL_TEXT = "取消"

        /** 候选名里带这些字符，才允许用「列表名是候选名的子串」这条兜底匹配。 */
        private const val CONTAINED_SEPARATORS = "，,、：: （("

        /**
         * 微信给消息气泡的无障碍文案，绝不是人名。
         * 「图片」就是靠这个混进来顶掉真名字的。
         */
        private val IGNORED_MEDIA = setOf(
            "图片", "视频", "语音", "表情", "动画表情", "文件", "位置", "名片", "链接",
            "红包", "转账", "音乐", "小程序", "聊天记录", "收藏", "拍一拍", "地图", "直播"
        )

        /** 微信 / QQ 给头像的无障碍文案是「昵称头像」，这是聊天对象名最可靠的来源。 */
        private const val AVATAR_SUFFIX = "头像"

        /** 聊天页右上角那个「…」按钮的无障碍文案；直接读不到名字时，点它进聊天信息页补读。 */
        private val INFO_BUTTON_TEXTS = setOf("更多信息", "聊天信息")
        private const val INFO_PAGE_DELAY_MS = 900L
        private const val INFO_BACK_DELAY_MS = 600L

        /** 剪贴板路线：长按输入框弹出的菜单里那一项。 */
        private const val PASTE_TEXT = "粘贴"
        private const val LONG_PRESS_MS = 700L
        private const val PASTE_MENU_DELAY_MS = 600L
        /** 先点一下输入框让它聚焦，等键盘弹出来的时间。 */
        private const val PASTE_FOCUS_DELAY_MS = 500L
        /** 轻点一次按住的时长。 */
        private const val TAP_MS = 60L
        private const val PASTE_RESULT_DELAY_MS = 900L

        /** 判定「输入区多出一张图」时，只看输入框上方这段像素。 */
        private const val PASTE_BAND_PX = 420
        /** 输入框下沿再往下这么多也算「输入区」，微信有时把预览缩略图放进输入框里侧。 */
        private const val PASTE_GAP_PX = 80
        /** 只认够大的图片：输入栏里的小图标（表情、加号）不能被误当成粘进来的图。 */
        private const val PASTE_IMAGE_MIN_WIDTH_RATIO = 0.15f

        /** 相册路线：「+」按钮的 contentDescription（微信 / QQ 都是这个）。 */
        private const val PLUS_CD = "更多功能"
        private const val PLUS_TEXT = "+"
        private const val PLUS_TEXT_FULL = "＋"
        /** 「+」面板里那一项。 */
        private const val ALBUM_ENTRY_TEXT = "相册"
        /** 相册第一格图的最小边长（占屏宽比例）——比这小的是图标，不是格子。 */
        private const val ALBUM_CELL_MIN_RATIO = 0.2f
        /** 输入栏在这一条线以下。 */
        private const val ALBUM_BAR_TOP_RATIO = 0.85f
        private const val ALBUM_PANEL_DELAY_MS = 700L
        private const val ALBUM_PICKER_DELAY_MS = 1500L
        private const val ALBUM_PREVIEW_DELAY_MS = 1200L
        /** 摊开「+」面板现场时，从输入框往上多看这么多像素。 */
        private const val ALBUM_PANEL_BAND_PX = 700
        /** 相册首次使用会弹权限框，顺手点掉。 */
        private val ALBUM_ALLOW_TEXTS = setOf("允许", "始终允许")

        private const val MAX_TITLE_LENGTH = 24
        private const val MAX_DEPTH = 40

        /** 顶部区域里的固定文案，不该被当成聊天对象名。 */
        private val IGNORED_TITLES = setOf(
            "微信", "通讯录", "发现", "我", "聊天", "消息",
            "QQ", "联系人", "动态", "看点"
        )

        /** 标题栏右侧那些按钮的无障碍文案，同样不能当名字。 */
        private val IGNORED_TITLE_BUTTONS = setOf(
            "聊天信息", "群聊信息", "聊天设置", "更多", "返回", "关闭", "设置", "搜索"
        )

        /** 群聊标题常带未读数，如「群名(12)」；只去掉纯数字的尾括号。 */
        private val TRAILING_COUNT = Regex("""\s*\(\d+\)$""")

        @Volatile
        var isConnected: Boolean = false
            private set

        /** 当前前台应用；只为 [WATCHED_PACKAGES] 里的包名赋值，其余一律 null。 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        @Volatile
        private var instance: AutoSendService? = null

        private val foregroundListeners = CopyOnWriteArrayList<(String?) -> Unit>()

        /** 注册前台应用变化回调，并立刻用当前值回调一次。 */
        fun addForegroundListener(listener: (String?) -> Unit) {
            foregroundListeners.addIfAbsent(listener)
            listener(foregroundPackage)
        }

        fun removeForegroundListener(listener: (String?) -> Unit) {
            foregroundListeners.remove(listener)
        }

        /**
         * 读一次当前聊天页的标题，返回聊天对象名；不在聊天页或读不到时返回 null。
         * 由后台线程调用则是阻塞的，所以调用方应保证在主线程上。
         */
        fun readChatTitle(): String? = instance?.let { service ->
            runCatching { service.doReadChatTitle() }.getOrNull()
        }

        /**
         * 备选读法：聊天页面上读不到名字时，点开「更多信息」进聊天信息页，
         * 从对端头像的「昵称头像」文案里取名字，再按返回键回到聊天页。
         * 会切一下页面，所以只在直接读不到时调用。
         */
        suspend fun readChatTitleViaInfo(): String? {
            val service = instance ?: return null
            return withContext(Dispatchers.Main) {
                runCatching { service.doReadChatTitleViaInfo() }.getOrNull()
            }
        }

        /**
         * 剪贴板路线：长按输入框 → 点「粘贴」→ 看输入区有没有多出图片 → 点「发送」。
         * 这条路的目的地天然是当前聊天，不需要认人；成功返回 true，
         * 任何一步没把握都返回 false，由调用方退回分享路线。
         */
        suspend fun pasteIntoChat(): Boolean {
            val service = instance ?: return false
            return withContext(Dispatchers.Main) {
                runCatching { service.doPasteIntoChat() }.getOrDefault(false)
            }
        }

        /**
         * 相册路线：让微信自己走「+ → 相册 → 第一格 → 发送」。
         * 不用认聊天对象名字，动图也能保住动画；任何一步没把握都返回 false。
         */
        suspend fun sendPhotoViaAlbum(): Boolean {
            val service = instance ?: return false
            return withContext(Dispatchers.Main) {
                runCatching { service.doAlbumRoute() }.getOrDefault(false)
            }
        }

        /**
         * 在微信 / QQ 的「选择聊天」页里，找到文本与 [name] 相同的那一项并点掉。
         * 必须恰好命中一项才动手；超时或命中多项都返回 false。
         */
        suspend fun clickChatByName(
            name: String,
            timeoutMs: Long = DEFAULT_CLICK_TIMEOUT_MS,
            appPackage: String? = null
        ): Boolean {
            val service = instance ?: return false
            return withContext(Dispatchers.Main) {
                runCatching { service.doClickChatByName(name, timeoutMs, appPackage) }
                    .getOrDefault(false)
            }
        }

        private fun normalizeName(text: String): String =
            TRAILING_COUNT.replace(text.trim(), "").trim()
    }

    /** 最近一次白名单应用换页事件里带的文本：微信常常把聊天对象名放在这里，读树失败时兜底。 */
    @Volatile
    private var lastWindowText: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val foregroundRecheck = Runnable { refreshForegroundFromWindows() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        SendLog.init(applicationContext)
        SendLog.d("服务", "无障碍服务已连接")
        // 服务可能是用户刚在设置里打开的，此时没有任何事件，先自己问一次。
        refreshForegroundFromWindows()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(foregroundRecheck)
        SendLog.d("服务", "无障碍服务被系统断开（应用更新或手动关闭后会这样）")
        instance = null
        isConnected = false
        updateForeground(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(foregroundRecheck)
        SendLog.d("服务", "无障碍服务销毁")
        instance = null
        isConnected = false
        updateForeground(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return

        if (pkg in WATCHED_PACKAGES) {
            // 白名单应用自己报到，最可信；顺手撤掉还没跑的复查。
            handler.removeCallbacks(foregroundRecheck)
            // 事件自己带着的文本一并记下：微信的聊天页会把标题塞在这里。
            // 注意这里是无条件赋值（可能为 null），免得旧聊天的名字一直留着被当成当前对象。
            val windowText = eventTextOf(e)
            lastWindowText = windowText
            SendLog.d("事件", "换页 " + pkg + "（白名单）窗口文本=" + (windowText ?: "无"))
            updateForeground(pkg)
            return
        }
        SendLog.d("事件", "换页 " + pkg + "（非白名单，稍后复查）")

        // 事件包名不在白名单里：可能是输入法 / 系统弹窗抢了这次「换页面」，
        // 也可能只是切换动画途中的旧窗口，或者真的切走了。
        // 不立刻下结论 —— 等切换落定，隔一会儿连问两次「现在前台是谁」。
        handler.removeCallbacks(foregroundRecheck)
        handler.postDelayed(foregroundRecheck, FOREGROUND_RECHECK_DELAY_MS)
        handler.postDelayed(foregroundRecheck, FOREGROUND_RECHECK_LATE_MS)
    }

    /**
     * 复查「当前前台是不是白名单应用」，兜住切换瞬间的误判。
     * 读不到应用窗口时保持原状：宁可球多留一会儿，也别该出现时不出现。
     */
    private fun refreshForegroundFromWindows() {
        val active = activeApplicationPackage()
        SendLog.d("前台", "复查活动窗口 = " + (active ?: "null"))
        if (active == null) return
        updateForeground(if (active in WATCHED_PACKAGES) active else null)
    }

    // ---------- 前台应用 ----------

    private fun updateForeground(next: String?) {
        if (next == foregroundPackage) return
        SendLog.d(
            "前台",
            (foregroundPackage ?: "无") + " → " + (next ?: "无") + "（球" + (if (next == null) "隐藏" else "显示") + "）"
        )
        foregroundPackage = next
        foregroundListeners.forEach { listener ->
            runCatching { listener(next) }
        }
    }

    /**
     * 当前活动的「应用窗口」属于哪个包。
     * 刻意不看 [rootInActiveWindow]：输入法弹出来时活动窗口会变成它，
     * 而这里要找的是底下那个真正的应用。
     */
    private fun activeApplicationPackage(): String? = runCatching {
        val applicationWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = applicationWindows.firstOrNull { it.isActive }
            ?: applicationWindows.firstOrNull()
        window?.root?.packageName?.toString()
    }.getOrNull()

    /** 取当前应用窗口的根节点，供读界面时使用。 */
    private fun activeRoot(): AccessibilityNodeInfo? = runCatching {
        windows.orEmpty()
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root
            ?: rootInActiveWindow
    }.getOrNull()

    // ---------- 读聊天对象 ----------

    /**
     * 读当前聊天对象的名字。
     *
     * 判据一「下半屏有输入框」用来确认这是聊天页而不是首页；
     * 判据二「顶部窄带里最靠上的短文本」在微信上不总成立 —— 它的标题栏有的版本
     * 只给 contentDescription，所以两条路都收，还认不出来时再摊开现场记一笔。
     */
    private fun doReadChatTitle(): String? {
        val roots = applicationRoots()
        if (roots.isEmpty()) {
            SendLog.d("读标题", "拿不到当前应用窗口的根节点")
            return null
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collect(it, nodes, 0) }
        val owner = runCatching { roots.first().packageName?.toString() }.getOrNull()

        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val titleZone = (screenHeight * TITLE_ZONE_RATIO).toInt()
        // 无论成不成，都把标题区摊开写进日志 —— 认错名字的锅就靠它来定位。
        val census = censusOf(nodes, titleZone)
        val windowInfo = windowSummary()

        // 判据一：屏幕下半部分得有输入框 —— 这是聊天页才有的结构。
        if (!hasInputBelow(nodes, (screenHeight * INPUT_TOP_RATIO).toInt())) {
            SendLog.d(
                "读标题",
                "下半屏没找到输入框，判定不在聊天页（根=" + owner + "，节点 " + nodes.size + " 个，窗口 " +
                    roots.size + " 个：" + windowInfo + "）。标题区候选：" + census
            )
            return null
        }

        // 活动窗口标题（有些 App 只在这里给出聊天对象名）优先，它最不容易认错。
        val winTitle = plausibleWindowTitle()
        if (winTitle != null) {
            SendLog.d("读标题", "命中 " + winTitle + "（来源=活动窗口标题）。标题区候选：" + census)
            return winTitle
        }

        // 微信聊天页不暴露标题，但每条消息的头像带着「昵称头像」——从左半屏那个头像取名字。
        val avatarName = partnerNameFromAvatars(nodes, screenWidth)
        if (avatarName != null) {
            SendLog.d("读标题", "命中 " + avatarName + "（来源=对端头像：" + avatarCensus(nodes, screenWidth) + "）")
            return avatarName
        }

        // 判据二：聊天对象名是顶部那条窄带里最靠上的短文本。
        val picked = pickTitle(nodes, titleZone, screenWidth)
        SendLog.d(
            "读标题",
            (if (picked != null) "命中 " + picked else "没认出来") +
                "（根=" + owner + "，节点 " + nodes.size + " 个，窗口 " + roots.size + " 个：" + windowInfo +
                "，窗口事件文本=" + (lastWindowText ?: "无") + "）。标题区候选：" + census +
                "。头像：" + avatarCensus(nodes, screenWidth)
        )
        if (picked != null) return picked

        // 兜底：微信的换页事件常常直接带着聊天对象名。只在名字像样时才敢用。
        val fallback = lastWindowText?.takeIf { looksLikeName(it) }
        if (fallback != null) SendLog.d("读标题", "改用窗口事件文本兜底：" + fallback)
        return fallback
    }

    /**
     * 备选读法：点开聊天页右上角的「…」进「聊天信息」页，那里一定摆着对端的头像
     * （无障碍文案是「昵称头像」），读到名字后按返回键回到聊天页。
     * 只在确认自己确实在聊天页时才动手；点不开、或者点了发现还停在聊天页，就原样退出
     * —— 绝不能因为补读把用户的聊天页弄乱。
     */
    private suspend fun doReadChatTitleViaInfo(): String? {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val inputMinTop = (screenHeight * INPUT_TOP_RATIO).toInt()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        applicationRoots().forEach { collect(it, nodes, 0) }
        if (!hasInputBelow(nodes, inputMinTop)) {
            SendLog.d("读标题", "补读放弃：下半屏没找到输入框，不在聊天页")
            return null
        }
        val infoButton = nodes.firstOrNull { node ->
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc !in INFO_BUTTON_TEXTS || !node.isClickable) return@firstOrNull false
            val r = boundsOf(node)
            r.width() > 0 && r.height() > 0 && r.centerX() > screenWidth * TITLE_MAX_CENTER_RATIO
        }
        if (infoButton == null) {
            SendLog.d("读标题", "补读放弃：没找到右上角的「更多信息」按钮")
            return null
        }
        SendLog.d("读标题", "补读：点开「" + infoButton.contentDescription + "」进聊天信息页")
        if (!click(infoButton)) {
            SendLog.d("读标题", "补读放弃：点不开「更多信息」")
            return null
        }
        delay(INFO_PAGE_DELAY_MS)
        val pageNodes = ArrayList<AccessibilityNodeInfo>()
        applicationRoots().forEach { collect(it, pageNodes, 0) }
        if (hasInputBelow(pageNodes, inputMinTop)) {
            SendLog.d("读标题", "补读放弃：点了「更多信息」但还停在聊天页")
            return null
        }
        val names = LinkedHashSet<String>()
        val shown = StringBuilder()
        for (node in pageNodes) {
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) continue
            if (shown.isNotEmpty()) shown.append(" / ")
            shown.append(desc)
            val bare = stripInvisible(desc.substring(0, desc.length - AVATAR_SUFFIX.length)).trim()
            if (looksLikeName(bare)) names.add(bare)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(INFO_BACK_DELAY_MS)
        SendLog.d("读标题", "聊天信息页头像：" + (if (shown.isEmpty()) "（无）" else shown.toString()))
        return if (names.size == 1) names.first() else null
    }

    /**
     * 剪贴板路线：把剪贴板里的图片粘进当前聊天并发送。
     *
     * 之所以值得一试：目的地就是眼前这个会话，既不用认人、也没有「选人页」，
     * 群聊同样适用。代价是微信可能根本不认图片剪贴板 —— 那就原样退回去用分享。
     * 安全底线：只有确实看到输入区多出东西才点「发送」，绝不盲点。
     */
    private suspend fun doPasteIntoChat(): Boolean {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val inputMinTop = (screenHeight * INPUT_TOP_RATIO).toInt()
        val before = collectAll()
        if (!hasInputBelow(before, inputMinTop)) {
            SendLog.d("粘贴", "放弃：下半屏没找到输入框，不在聊天页")
            return false
        }
        val input = before.lastOrNull { isEditableNode(it) }
        if (input == null) {
            SendLog.d("粘贴", "放弃：没找到输入框节点")
            return false
        }
        val box = boundsOf(input)
        val beforeImages = imageNodesNearInput(before, box, screenWidth)
        SendLog.d(
            "粘贴",
            "输入框 @" + box.left + "," + box.top + "-" + box.right + "," + box.bottom +
                "，附近大图 " + beforeImages + " 个，窗口 " + windows.orEmpty().size +
                " 个：" + windowSummary()
        )

        // 尝试 1：直接给输入框下发「长按」动作。
        // 微信 / QQ 的输入框多半是用 setOnLongClickListener 弹自己那套菜单，
        // 这种情况下这个动作就会直接把菜单叫出来 —— 完全不依赖触摸注入。
        val longClick = runCatching {
            input.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)
        SendLog.d("粘贴", "尝试 1 · 输入框 ACTION_LONG_CLICK = " + longClick)
        delay(PASTE_MENU_DELAY_MS)
        if (clickPasteInMenu()) {
            delay(PASTE_RESULT_DELAY_MS)
            return finishPaste(collectAll(), box, beforeImages, inputMinTop, screenWidth, input)
        }

        // 尝试 2：手势长按。先轻点一下输入框 —— 输入框没聚焦时（键盘没弹出来）
        // 微信那类输入框常常不吃长按；点过之后位置也会变，所以要重新找一次输入框。
        dismissPasteMenu()
        SendLog.d("粘贴", "尝试 2 · 先点输入框再长按")
        tap(box.exactCenterX(), box.exactCenterY())
        delay(PASTE_FOCUS_DELAY_MS)
        val focused = collectAll()
        val focusedInput = focused.lastOrNull { isEditableNode(it) }
        val pressBox = if (focusedInput != null) boundsOf(focusedInput) else box
        val pressImages = imageNodesNearInput(focused, pressBox, screenWidth)
        SendLog.d(
            "粘贴",
            "聚焦后输入框 @" + pressBox.left + "," + pressBox.top + "-" +
                pressBox.right + "," + pressBox.bottom + "，附近大图 " + pressImages +
                " 个，窗口 " + windows.orEmpty().size + " 个：" + windowSummary()
        )
        val pressed = longPress(pressBox.exactCenterX(), pressBox.exactCenterY())
        SendLog.d("粘贴", "长按手势结果 = " + pressed)
        if (pressed) {
            delay(PASTE_MENU_DELAY_MS)
            if (clickPasteInMenu()) {
                delay(PASTE_RESULT_DELAY_MS)
                val afterFocus = collectAll()
                return finishPaste(
                    afterFocus, pressBox, pressImages, inputMinTop, screenWidth, input
                )
            }
        }

        // 尝试 3：让输入框自己执行粘贴动作（EditText 的 ACTION_PASTE）。
        dismissPasteMenu()
        val target = collectAll().lastOrNull { isEditableNode(it) } ?: input
        val actionPaste = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
        SendLog.d("粘贴", "尝试 3 · 输入框 ACTION_PASTE = " + actionPaste)
        if (actionPaste) {
            delay(PASTE_RESULT_DELAY_MS)
            if (finishPaste(collectAll(), pressBox, beforeImages, inputMinTop, screenWidth, target)) {
                return true
            }
            SendLog.d("粘贴", "ACTION_PASTE 之后输入区没变化")
        }
        SendLog.d("粘贴", "三种办法都没能把图粘进去")
        return false
    }

    /**
     * 在刚弹出来的菜单里点「粘贴」。三种触发方式共用这一步。
     * 顺带把菜单内容和当前窗口列表写进日志 —— 菜单是谁弹的、长什么样，全靠这一行。
     */
    private fun clickPasteInMenu(): Boolean {
        val menu = collectAll()
        val entries = ArrayList<String>()
        var pasteNode: AccessibilityNodeInfo? = null
        for (node in menu) {
            val text = titleTextOf(node)?.trim().orEmpty()
            if (text.isEmpty() || text.length > 12) continue
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) continue
            if (entries.size < 16) entries.add(text + "@" + r.top)
            if (text == PASTE_TEXT && pasteNode == null) pasteNode = node
        }
        SendLog.d(
            "粘贴",
            "菜单候选：" + (if (entries.isEmpty()) "（没读到文本）" else entries.joinToString(" / ")) +
                "，窗口 " + windows.orEmpty().size + " 个：" + windowSummary()
        )
        if (pasteNode == null) {
            SendLog.d("粘贴", "菜单里没有「粘贴」")
            return false
        }
        val ok = click(pasteNode)
        SendLog.d("粘贴", "点「粘贴」= " + ok)
        return ok
    }

    /** 收起刚弹出来的菜单 / 键盘，免得挡着下一次尝试或后面的分享。 */
    private suspend fun dismissPasteMenu() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(INFO_BACK_DELAY_MS)
    }

    /**
     * 粘贴动作发出去之后：判断微信到底把图放进去了没有，放进去就把「发送」点掉。
     *
     * 两种形态都要认：① 图进了输入框（聊天页还在，输入框上方多出一张缩略图）；
     * ② 微信弹出预览小窗（这时聊天页的输入框通常已经不在无障碍树里了）。
     */
    private fun finishPaste(
        nodes: List<AccessibilityNodeInfo>,
        box: Rect,
        beforeImages: Int,
        inputMinTop: Int,
        screenWidth: Int,
        input: AccessibilityNodeInfo
    ): Boolean {
        if (!hasInputBelow(nodes, inputMinTop)) {
            SendLog.d("粘贴", "粘贴后下半屏没有输入框，应该是开了预览小窗，找「发送」")
            val send = sendButtonOnPage(nodes)
            if (send != null && click(send)) {
                SendLog.d("粘贴", "已点预览小窗的「发送」")
                return true
            }
            SendLog.d("粘贴", "预览小窗上没找到「发送」，现场：" + dumpRows(nodes, foregroundPackage, 0))
            return false
        }
        val afterImages = imageNodesNearInput(nodes, box, screenWidth)
        SendLog.d("粘贴", "输入区附近图片节点：" + beforeImages + " → " + afterImages)
        if (afterImages > beforeImages) {
            val send = nodes.firstOrNull {
                titleTextOf(it)?.trim() == CONFIRM_SEND_TEXT && boundsOf(it).top >= inputMinTop
            }
            SendLog.d("粘贴", "输入区多出图片，点「发送」：" + (send != null))
            if (send != null && click(send)) return true
            return false
        }
        SendLog.d(
            "粘贴",
            "输入区没多出图片，放弃。输入框文字=" + (input.text?.toString()?.take(40) ?: "（空）")
        )
        return false
    }

    /**
     * 预览小窗 / 预览页上的「发送」：先要求文本正好是「发送」（含 contentDescription）；
     * 找不到才放宽到以「发送」开头的短文本，而且必须唯一命中 —— 宁可放弃也不乱点。
     */
    private fun sendButtonOnPage(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val exact = nodes.firstOrNull {
            titleTextOf(it)?.trim() == CONFIRM_SEND_TEXT && boundsOf(it).width() > 0
        }
        if (exact != null) return exact
        val loose = nodes.filter {
            val text = titleTextOf(it)?.trim().orEmpty()
            text.startsWith(CONFIRM_SEND_TEXT) && text.length <= 4 && boundsOf(it).width() > 0
        }
        val distinct = loose.distinctBy { node ->
            val r = boundsOf(node)
            r.left.toString() + "," + r.top
        }
        return if (distinct.size == 1) distinct.first() else null
    }

    // ---------- 相册路线（走微信 / QQ 自己的「+ → 相册」，动图能保住动画） ----------

    /**
     * 聊天页 →「+」→「相册」→ 第一格图 →「发送」。
     *
     * 为什么要有这条路：微信的「粘贴」会把动图压成一张静态图（实测），只有走它自己的
     * 相册入口，发出的动图才会动。全程只按节点点击，不用认聊天对象名字。
     */
    private suspend fun doAlbumRoute(): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val inputTop = (screenHeight * INPUT_TOP_RATIO).toInt()
        val barTop = (screenHeight * ALBUM_BAR_TOP_RATIO).toInt()

        val page = collectAll()
        if (!hasInputBelow(page, inputTop)) {
            SendLog.d("相册", "放弃：下半屏没找到输入框，不在聊天页")
            return false
        }

        // ① 输入栏最右边那个「+」
        val plus = plusButtonNode(page, barTop)
        if (plus == null) {
            SendLog.d("相册", "没找到「+」按钮。输入栏现场：" + dumpRows(page, foregroundPackage, barTop))
            return false
        }
        SendLog.d("相册", "点「+」@" + boundsOf(plus).flattenToString())
        if (!click(plus)) {
            SendLog.d("相册", "点「+」没成功")
            return false
        }
        delay(ALBUM_PANEL_DELAY_MS)

        // ② 「+」面板里的「相册」
        val panel = collectAll()
        val album = panel.firstOrNull {
            titleTextOf(it)?.trim() == ALBUM_ENTRY_TEXT && boundsOf(it).width() > 0
        }
        if (album == null) {
            SendLog.d(
                "相册",
                "「+」面板里没看到「相册」。现场：" +
                    dumpRows(panel, foregroundPackage, inputTop - ALBUM_PANEL_BAND_PX)
            )
            exitAlbumPage()
            return false
        }
        SendLog.d("相册", "点「相册」@" + boundsOf(album).flattenToString())
        if (!click(album)) {
            SendLog.d("相册", "点「相册」没成功")
            exitAlbumPage()
            return false
        }
        delay(ALBUM_PICKER_DELAY_MS)

        // 头一次用相册会弹「允许访问照片和视频」，顺手点掉。
        val asked = collectAll()
        val allow = asked.firstOrNull {
            titleTextOf(it)?.trim() in ALBUM_ALLOW_TEXTS && boundsOf(it).width() > 0
        }
        if (allow != null) {
            SendLog.d("相册", "点权限弹窗：「" + titleTextOf(allow) + "」")
            click(allow)
            delay(ALBUM_PICKER_DELAY_MS)
        }

        // ③ 第一格图 —— 刚插进相册的那张按时间倒序永远排第一
        val picker = collectAll()
        val cell = firstAlbumCell(picker, screenWidth, screenHeight)
        if (cell == null) {
            SendLog.d("相册", "相册里没找到图片格子。现场：" + dumpRows(picker, foregroundPackage, 0))
            exitAlbumPage()
            return false
        }
        SendLog.d("相册", "点第一格图@" + boundsOf(cell).flattenToString())
        if (!click(cell)) {
            SendLog.d("相册", "点第一格图没成功")
            exitAlbumPage()
            return false
        }
        delay(ALBUM_PREVIEW_DELAY_MS)

        // ④ 预览页 / 选择页上的「发送」
        val preview = collectAll()
        val send = sendButtonOnPage(preview) ?: looseSendButton(preview)
        if (send == null) {
            SendLog.d("相册", "没找到「发送」。现场：" + dumpRows(preview, foregroundPackage, 0))
            exitAlbumPage()
            return false
        }
        val ok = click(send)
        SendLog.d("相册", "点「发送」@" + boundsOf(send).flattenToString() + " = " + ok)
        delay(PASTE_RESULT_DELAY_MS)
        return ok
    }

    /** 输入栏那一行里最右边的图标：正常就是「+」；拿不准时宁可不走这条路。 */
    private fun plusButtonNode(nodes: List<AccessibilityNodeInfo>, barTop: Int): AccessibilityNodeInfo? {
        val bar = nodes.filter {
            val r = boundsOf(it)
            r.width() > 0 && r.height() > 0 && r.top >= barTop
        }
        // 输入框里已经有字的时候，最右边那个键是「发送」而不是「+」—— 这种时候绝不瞎点。
        if (bar.any { titleTextOf(it)?.trim() == CONFIRM_SEND_TEXT }) {
            SendLog.d("相册", "输入栏里已经出现「发送」，这次不走相册路线")
            return null
        }
        bar.firstOrNull {
            val text = titleTextOf(it)?.trim().orEmpty()
            text == PLUS_CD || text == PLUS_TEXT || text == PLUS_TEXT_FULL
        }?.let { return it }
        SendLog.d("相册", "「+」没有可认的文本，改用输入栏最靠右那个能点的图标")
        return bar.filter { it.isClickable }.maxByOrNull { boundsOf(it).right }
    }

    /** 相册里排在最前（最上、最左）的那一格图。 */
    private fun firstAlbumCell(
        nodes: List<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val minSize = (screenWidth * ALBUM_CELL_MIN_RATIO).toInt()
        val topLine = (screenHeight * 0.05f).toInt()
        val big = nodes.filter {
            val r = boundsOf(it)
            r.width() >= minSize && r.height() >= minSize && r.top > topLine && !looksLikeCameraTile(it)
        }
        val images = big.filter { shortClass(it).contains("Image") }
        val pool = if (images.isNotEmpty()) images else big.filter { it.isClickable }
        SendLog.d(
            "相册",
            "格子候选 " + pool.size + " 个（其中图片类 " + images.size + " 个，现场：" +
                dumpRows(pool, foregroundPackage, 0) + "）"
        )
        return pool.minByOrNull { node ->
            val r = boundsOf(node)
            r.top.toLong() * 10000L + r.left.toLong()
        }
    }

    /** 「拍摄 / 拍照」那一格不是我们插进去的图，跳过。 */
    private fun looksLikeCameraTile(node: AccessibilityNodeInfo): Boolean {
        val text = titleTextOf(node)?.trim().orEmpty()
        return text.contains("拍摄") || text.contains("拍照") || text.contains("相机")
    }

    /** 选择页上的「发送(N)」：允许比精确匹配长一点，但必须唯一命中。 */
    private fun looseSendButton(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val loose = nodes.filter {
            val text = titleTextOf(it)?.trim().orEmpty()
            text.startsWith(CONFIRM_SEND_TEXT) && text.length <= 6 && boundsOf(it).width() > 0
        }.distinctBy { node ->
            val r = boundsOf(node)
            r.left.toString() + "," + r.top
        }
        return if (loose.size == 1) loose.first() else null
    }

    /** 按返回键退出当前页面（「+」面板 / 相册），别把用户留在半路上。 */
    private suspend fun exitAlbumPage() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(INFO_BACK_DELAY_MS)
    }

    /** 当前应用所有窗口的节点，拍平成一个列表。 */
    private fun collectAll(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        applicationRoots().forEach { collect(it, out, 0) }
        return out
    }

    /**
     * 输入框附近（含输入框自身那一条）有多少个「够大」的图片节点。
     * 只看够大的图：输入栏里表情 / 加号那一排小图标也是 ImageView，按数量算会平白多出证据。
     */
    private fun imageNodesNearInput(
        nodes: List<AccessibilityNodeInfo>,
        box: Rect,
        screenWidth: Int
    ): Int {
        val minSize = (screenWidth * PASTE_IMAGE_MIN_WIDTH_RATIO).toInt()
        val keys = HashSet<String>()
        for (node in nodes) {
            val cls = node.className?.toString().orEmpty()
            if (!cls.contains("Image")) continue
            val r = boundsOf(node)
            if (r.width() < minSize || r.height() < minSize) continue
            if (r.top < box.top - PASTE_BAND_PX || r.top > box.bottom + PASTE_GAP_PX) continue
            keys.add(r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom)
        }
        return keys.size
    }

    /** 轻点某个点：用来让输入框先拿到焦点。 */
    private fun tap(x: Float, y: Float): Boolean = runCatching {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x, y) }, 0L, TAP_MS
                )
            )
            .build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    /** 长按某个点：微信输入框的菜单只有长按才会出来。 */
    private fun longPress(x: Float, y: Float): Boolean = runCatching {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x, y) }, 0L, LONG_PRESS_MS
                )
            )
            .build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    /** 标题区里所有带文本的节点，从上到下摊开 —— 名字认错时全靠这一行定位。 */
    private fun censusOf(nodes: List<AccessibilityNodeInfo>, titleZone: Int): String {
        val items = nodes
            .mapNotNull { node ->
                val text = titleTextOf(node) ?: return@mapNotNull null
                val r = boundsOf(node)
                if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
                if (r.top < 0 || r.top > titleZone + 60) return@mapNotNull null
                val src = (if (node.text.isNullOrBlank()) "cd" else "t") +
                    (if (node.isClickable) ",可点" else "")
                r.top to (text.take(12) + "(" + src + ")@" + r.top + "[" + r.left + "-" + r.right + "]")
            }
            .sortedBy { it.first }
            .take(12)
            .map { it.second }
        return if (items.isEmpty()) "无" else items.joinToString(" / ")
    }

    /** 当前所有窗口的摘要（类型 / 包名 / 窗口标题），用于诊断。 */
    private fun windowSummary(): String = runCatching {
        windows.orEmpty().joinToString(" / ") { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "-"
            val title = runCatching { w.title?.toString() }.getOrNull() ?: "-"
            val kind = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) "APP" else "T" + w.type
            kind + ":" + pkg + ":" + title.take(16)
        }
    }.getOrDefault("-")

    /** 活动应用窗口自带的标题，看起来像聊天对象名才认。 */
    private fun plausibleWindowTitle(): String? {
        val title = runCatching {
            windows.orEmpty()
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?.title?.toString()
        }.getOrNull()?.trim() ?: return null
        return title.takeIf { looksLikeName(it) }
    }

    /**
     * 当前应用的**所有**窗口的根节点，不限窗口类型。
     * 微信会把标题栏放在单独的应用窗口里，只看一个窗口就会漏掉标题 —— 这是认错名字的主因。
     * 长按弹出的菜单、粘贴后的预览小窗也是同一个包的别的窗口，只认 TYPE_APPLICATION 会看不到它们。
     */
    private fun applicationRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val wanted = foregroundPackage
        runCatching {
            for (w in windows.orEmpty()) {
                val root = runCatching { w.root }.getOrNull() ?: continue
                val pkg = runCatching { root.packageName?.toString() }.getOrNull()
                if (wanted != null && pkg != wanted) continue
                out.add(root)
            }
        }
        if (out.isEmpty()) activeRoot()?.let { out.add(it) }
        return out
    }

    /** 节点上的候选标题文本：text 优先，没有就用 contentDescription。 */
    private fun titleTextOf(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 顶部窄带里最靠上的那个短文本。
     * 右侧那条竖带（大约 72% 屏宽往右）是「…」「Q我吧」这类按钮，先排除掉 ——
     * 微信聊天页的「…」按钮无障碍文案就是「聊天信息」，位置上比名字还靠上，不排掉就会顶掉真名字。
     */
    private fun pickTitle(
        nodes: List<AccessibilityNodeInfo>,
        titleZone: Int,
        screenWidth: Int
    ): String? =
        nodes
            .mapNotNull { node ->
                val text = titleTextOf(node) ?: return@mapNotNull null
                if (!looksLikeName(text)) return@mapNotNull null
                val r = boundsOf(node)
                if (r.width() <= 0 || r.height() <= 0 || r.top !in 0 until titleZone) return@mapNotNull null
                if (r.centerX() > screenWidth * TITLE_MAX_CENTER_RATIO) return@mapNotNull null
                r.top to text
            }
            .minByOrNull { it.first }
            ?.second

    /**
     * 从聊天页的头像里取聊天对象名。
     *
     * 微信聊天页**不暴露标题**（标题区只有「…」按钮和消息气泡），
     * 但每条消息的头像都带着「昵称头像」这样的无障碍文案 ——
     * 对端头像永远在屏幕左半边，自己头像在右半边，所以左边那个名字就是聊天对象。
     * 出现多个不同名字（群聊）或一个都没有（对端还没发过消息）时，一律认输，让用户自己选。
     */
    private fun partnerNameFromAvatars(
        nodes: List<AccessibilityNodeInfo>,
        screenWidth: Int
    ): String? {
        val found = LinkedHashSet<String>()
        for (node in nodes) {
            val name = avatarNameOf(node, screenWidth) ?: continue
            found.add(name)
        }
        if (found.size > 1) {
            SendLog.d("读标题", "左半屏出现多个头像名字，不敢认：" + found.joinToString(" / "))
            return null
        }
        val name = found.firstOrNull() ?: return null
        // 群聊里群成员的名字会在消息旁边再出现一次（微信给群消息标了发送者昵称），
        // 1:1 聊天不会。群聊里只看得见一个人的消息时，上面那条「唯一性」判据会
        // 把这个成员的名字当成聊天对象 —— 那就会把表情私发给某个人，所以这里宁可认输。
        if (nodes.any { it.text?.toString()?.trim() == name }) {
            SendLog.d("读标题", "头像「" + name + "」在别处也有同名文本，像是群聊，不敢认")
            return null
        }
        return name
    }

    /** 头像节点 → 昵称；不是左半屏的头像就返回 null。 */
    private fun avatarNameOf(node: AccessibilityNodeInfo, screenWidth: Int): String? {
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) return null
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return null
        if (r.centerX() > screenWidth * 0.5f) return null
        val name = desc.substring(0, desc.length - AVATAR_SUFFIX.length).trim()
        return name.takeIf { looksLikeName(it) }
    }

    /** 名字像不像人名：长度合适，且不是微信的按钮 / 消息文案。 */
    private fun looksLikeName(text: String): Boolean =
        text.length in 1..MAX_TITLE_LENGTH &&
            text !in IGNORED_TITLES &&
            text !in IGNORED_TITLE_BUTTONS &&
            text !in IGNORED_MEDIA

    /** 认不出来时，把屏上所有头像摊开写日志。 */
    private fun avatarCensus(nodes: List<AccessibilityNodeInfo>, screenWidth: Int): String {
        val items = nodes.mapNotNull { node ->
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) return@mapNotNull null
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
            val side = if (r.centerX() > screenWidth * 0.5f) "右" else "左"
            desc.take(12) + "@" + side + r.top
        }
        return if (items.isEmpty()) "无" else items.take(8).joinToString(" / ")
    }

    private fun shortClass(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.')?.take(14).orEmpty()

    /** 换页事件自带的文本（微信会把它当窗口标题用）。 */
    private fun eventTextOf(event: AccessibilityEvent): String? {
        val text = event.text?.joinToString("") { it ?: "" }.orEmpty()
        val raw = if (text.isNotBlank()) text else event.contentDescription?.toString().orEmpty()
        return raw.trim().takeIf { it.isNotEmpty() }?.take(40)
    }

    // ---------- 自动点会话 ----------

    private suspend fun doClickChatByName(
        name: String,
        timeoutMs: Long,
        appPackage: String?
    ): Boolean {
        val target = normalizeName(name)
        if (target.isEmpty()) return false

        // 只排除屏幕最上面那条标题栏。「下半屏有没有输入框」这条判据不能用 ——
        // 微信 / QQ 的「选择聊天」页是半透明盖在聊天页上的，聊天页的输入框还在树里，
        // 用它判断只会得出「还停在聊天页」，于是永远不去点列表（v11.5 的实测日志就是这么卡住的）。
        val rowMinTop = (resources.displayMetrics.heightPixels * ROW_MIN_TOP_RATIO).toInt()
        SendLog.d(
            "点会话",
            "开始：目标=" + target + "，应用=" + (appPackage ?: "未知") + "，超时 " + timeoutMs + "ms"
        )
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var round = 0
        var lastSummary = ""

        while (SystemClock.uptimeMillis() < deadline) {
            round++
            val roots = applicationRoots()
            if (roots.isEmpty()) {
                if (round == 1) SendLog.d("点会话", "第 1 轮：拿不到根节点")
            } else {
                val nodes = ArrayList<AccessibilityNodeInfo>()
                roots.forEach { collect(it, nodes, 0) }

                // 用户自己点了人（或微信自己跳到确认框）时会弹「发送给：X」——
                // 这时只管把「发送」点掉，绝不能再在对话框上找名字，否则会点到图片预览之类的东西。
                val confirmSend = confirmDialogSendNode(nodes, rowMinTop)
                if (confirmSend != null) {
                    val ok = click(confirmSend)
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮：看到「发送给」确认框，点「发送」" + (if (ok) "成功" else "失败")
                    )
                    return ok
                }

                val rows = matchRows(nodes, target, appPackage, rowMinTop)

                // 命中多行说明有重名，宁可让用户自己点 —— 但要把每一行记下来，
                // 好判断到底是真有两个同名条目，还是同一个窗口被读了两遍。
                if (rows.size > 1) {
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮命中 " + rows.size + " 行（重名），放弃。各行：" +
                            rows.joinToString(" / ") { node ->
                                val b = boundsOf(node)
                                "@" + b.left + "," + b.top + "-" + b.right + "," + b.bottom +
                                    "(" + shortClass(node) + "," + node.packageName + ")"
                            }
                    )
                    return false
                }
                if (rows.size == 1) {
                    val r = boundsOf(rows.first())
                    val ok = click(rows.first())
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮命中 1 行 @" + r.left + "," + r.top + "，点击" +
                            (if (ok) "成功" else "失败")
                    )
                    if (ok) {
                        clickConfirmIfDialog()
                        verifyGone(target, appPackage, rowMinTop)
                        return true
                    }
                }

                val summary = "节点 " + nodes.size + " 个，匹配 " + rows.size + " 行"
                if (summary != lastSummary) {
                    lastSummary = summary
                    SendLog.d("点会话", "第 " + round + " 轮：" + summary)
                }
                if (rows.isEmpty() && (round == 1 || round % 6 == 0)) {
                    SendLog.d("点会话", "第 " + round + " 轮现场：" + dumpRows(nodes, appPackage, rowMinTop))
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        SendLog.d("点会话", "超时未点到：" + target)
        return false
    }

    /**
     * 选完联系人后如果还冒出一个「发送给：X」的确认框（发送 / 取消），顺手把「发送」点掉。
     * 判据必须严：同时看到「发送」和「取消」两个按钮，或者界面上有「发送给」字样，
     * 否则一律不动手 —— 聊天页输入框旁边那个「发送」按钮是绝不能碰的。
     */
    private suspend fun clickConfirmIfDialog(): Boolean {
        delay(CONFIRM_DIALOG_DELAY_MS)
        val roots = applicationRoots()
        if (roots.isEmpty()) return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collect(it, nodes, 0) }
        val minTop = (resources.displayMetrics.heightPixels * ROW_MIN_TOP_RATIO).toInt()
        val sendNode = confirmDialogSendNode(nodes, minTop)
        if (sendNode == null) {
            SendLog.d("点会话", "复查：界面上没有「发送给」确认框，不再动手")
            return false
        }
        val ok = click(sendNode)
        SendLog.d("点会话", "复查：看到确认框，点「发送」" + (if (ok) "成功" else "失败"))
        return ok
    }

    /**
     * 从节点里挑出确认框的「发送」按钮。判据必须严：
     * 要么界面上有「发送给」字样，要么「发送」和「取消」并排挨着 ——
     * 聊天页输入框旁边那个「发送」是绝不能碰的（会把你打的字发出去）。
     */
    private fun confirmDialogSendNode(
        nodes: List<AccessibilityNodeInfo>,
        minTop: Int
    ): AccessibilityNodeInfo? {
        var send: AccessibilityNodeInfo? = null
        var cancel: AccessibilityNodeInfo? = null
        var marker = false
        for (node in nodes) {
            val text = titleTextOf(node) ?: continue
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0 || r.top < minTop) continue
            if (text.contains("发送给")) marker = true
            if (text == CONFIRM_SEND_TEXT) send = node
            if (text == CONFIRM_CANCEL_TEXT) cancel = node
        }
        val target = send ?: return null
        val other = cancel
        if (!marker && (other == null || !alongside(boundsOf(target), boundsOf(other)))) return null
        return target
    }

    /** 两个按钮是不是并排在一起（确认框里「发送」和「取消」总是挨着）。 */
    private fun alongside(a: Rect, b: Rect): Boolean =
        Math.abs(a.centerY() - b.centerY()) < 220 && Math.abs(a.centerX() - b.centerX()) < 1100

    /** 点完复查一次：那一行还在不在。只写日志，不改结论。 */
    private suspend fun verifyGone(target: String, appPackage: String?, rowMinTop: Int) {
        delay(CLICK_VERIFY_DELAY_MS)
        val roots = applicationRoots()
        if (roots.isEmpty()) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collect(it, nodes, 0) }
        val left = matchRows(nodes, target, appPackage, rowMinTop).size
        SendLog.d("点会话", "点击后复查：列表里还剩 " + left + " 行同名（0 表示确实进去了）")
    }

    /** 把标题栏以下看得见的短文本按位置摊开 —— 列表里到底写了什么，一看就知道。 */
    private fun dumpRows(nodes: List<AccessibilityNodeInfo>, appPackage: String?, minTop: Int): String {
        val items = nodes
            .filter { node ->
                val pkg = runCatching { node.packageName?.toString() }.getOrNull()
                appPackage == null || pkg == null || pkg == appPackage
            }
            .mapNotNull { node ->
                val text = titleTextOf(node) ?: return@mapNotNull null
                if (text.length > MAX_TITLE_LENGTH) return@mapNotNull null
                val r = boundsOf(node)
                if (r.width() <= 0 || r.height() <= 0 || r.top < minTop) return@mapNotNull null
                r.top to (text.take(16) + "@" + r.top + "[" + r.left + "-" + r.right + "]")
            }
            .sortedBy { it.first }
            .take(8)
            .map { it.second }
        return if (items.isEmpty()) "标题栏以下没有可读文本" else items.joinToString(" / ")
    }

    /**
     * 找出文本恰好等于目标名的列表项。
     * 同一行常常有多个节点挂着同样的文本（外层容器 + 内层 TextView），
     * 所以按「可点击祖先的位置」去重，避免把一行误判成重名。
     */
    private fun matchRows(
        nodes: List<AccessibilityNodeInfo>,
        target: String,
        appPackage: String?,
        minTop: Int
    ): List<AccessibilityNodeInfo> {
        val strict = LinkedHashMap<String, AccessibilityNodeInfo>()
        val loose = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (node in nodes) {
            val pkg = runCatching { node.packageName?.toString() }.getOrNull()
            if (appPackage != null && pkg != null && pkg != appPackage) continue
            val text = titleTextOf(node) ?: continue
            val own = boundsOf(node)
            if (own.width() <= 0 || own.height() <= 0 || own.top < minTop) continue
            val isStrict = normalizeName(text) == target
            val isLoose = !isStrict &&
                normalizeName(stripInvisible(text)) == normalizeName(stripInvisible(target))
            if (!isStrict && !isLoose) continue
            val clickable = findClickableAncestor(node) ?: node
            val r = boundsOf(clickable)
            if (r.width() <= 0 || r.height() <= 0) continue
            val key = r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom
            if (isStrict) strict.putIfAbsent(key, clickable) else loose.putIfAbsent(key, clickable)
        }
        if (strict.isNotEmpty()) return strict.values.toList()
        if (loose.isNotEmpty()) return loose.values.toList()
        return containedRows(nodes, target, appPackage, minTop)
    }

    /**
     * 三级兜底：候选名里包着列表里的名字（例如「岩星，点击进入聊天信息」里包着「岩星」）。
     * 只在候选名本身带分隔符时才敢用 —— 否则「图片」这类按钮文案也可能恰好包住某个真名字。
     */
    private fun containedRows(
        nodes: List<AccessibilityNodeInfo>,
        target: String,
        appPackage: String?,
        minTop: Int
    ): List<AccessibilityNodeInfo> {
        if (target.none { it in CONTAINED_SEPARATORS }) return emptyList()
        val found = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (node in nodes) {
            val pkg = runCatching { node.packageName?.toString() }.getOrNull()
            if (appPackage != null && pkg != null && pkg != appPackage) continue
            val text = normalizeName(titleTextOf(node) ?: continue)
            if (text.length !in 2..12 || !target.contains(text)) continue
            val own = boundsOf(node)
            if (own.width() <= 0 || own.height() <= 0 || own.top < minTop) continue
            val clickable = findClickableAncestor(node) ?: node
            val r = boundsOf(clickable)
            if (r.width() <= 0 || r.height() <= 0) continue
            val key = r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom
            found.putIfAbsent(key, clickable)
        }
        return found.values.toList()
    }

    /** 去掉肉眼看不见的占位字符（QQ 昵称里常见 U+3164 这种），用于宽松兜底匹配。 */
    private fun stripInvisible(text: String): String =
        text.filterNot { c ->
            c.isWhitespace() ||
                Character.getType(c) == Character.FORMAT.toInt() ||
                c.code == 0x3164 || c.code == 0xFFA0 || c.code == 0x2800 || c.code == 0xFFFC
        }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 列表项自己不可点时，退回按坐标点一下。
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------- 节点遍历 ----------

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        out.add(node)
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collect(child, out, depth + 1)
        }
    }

    private fun hasInputBelow(nodes: List<AccessibilityNodeInfo>, minTop: Int): Boolean =
        nodes.any { node ->
            val r = boundsOf(node)
            r.height() > 0 && r.top >= minTop && isEditableNode(node)
        }

    /**
     * 输入框判据放宽一点：微信 / QQ 的输入框在无障碍树里不一定报成 EditText 类名，
     * 但「可编辑」和「支持 ACTION_SET_TEXT」这两条里通常至少命中一条。
     */
    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.isEditable ||
            node.className == EDIT_TEXT_CLASS ||
            (node.actions and AccessibilityNodeInfo.ACTION_SET_TEXT) != 0
    }.getOrDefault(false)

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) return current
            current = runCatching { current!!.parent }.getOrNull()
            depth++
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { runCatching { node.getBoundsInScreen(it) } }
}
