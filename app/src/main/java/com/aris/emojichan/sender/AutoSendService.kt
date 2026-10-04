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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「表情自动发送」无障碍服务。
 *
 * 与「页面探测」那套开发期工具（只在 debug 包里）的关键区别：这个服务**平时什么都不做**。
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

    /**
     * 「点会话」的结果。
     *
     * 以前是 Boolean，分不清「没找到那一行」和「走到确认框、但按设置留给你点」——
     * 这两种情况该给用户的提示完全不同，所以拆开。
     *
     * 放在类里（而不是 companion 里）：外部直接写 AutoSendService.ChatClick.DEFERRED。
     */
    enum class ChatClick { CLICKED, NO_TARGET, DEFERRED }

    /**
     * 相册路线的结果。
     *
     * - [SENT]：我们自己点掉了确认键。
     * - [STAGED]：按设置停在最后一步，图已经选好，用户点一下就走。
     * - [ALREADY_SENT]：回头找确认键时，预览页已经关了、聊天页的输入栏回来了 ——
     *   大概率是用户抢在我们前面自己点了那一下。调用方**同样必须当成「走通了」**：
     *   退回去走分享，等于把同一张表情再发一遍。
     * - [FAILED]：真没走通（还站在选图页上），可以落回分享路线。
     * - [UNCERTAIN]：点完格子之后页面确实换了，但既没读到确认键、也认不出聊天页
     *   （QQ 上点第一格常常就是直接发）。发没发出去说不准 —— 调用方**绝不能**落回分享路线：
     *   宁可少发一张，也不能同一张发两遍（emc-2-031）。
     */
    enum class AlbumOutcome { SENT, STAGED, ALREADY_SENT, FAILED, UNCERTAIN }

    /**
     * 粘贴路线的结果。
     *
     * STAGED = 图已经进了输入框 / 弹出了预览小窗，但按设置（或者点失败了）把最后那一下
     * 「发送」留给了用户 —— 和 [AlbumOutcome.STAGED] 一样，调用方必须当成「这条路走通了」，
     * 绝不能再退回分享路线：图已经摆在微信里，再弹一个分享面板等于同一张图摆两遍。
     *
     * ALREADY_SENT = 用户在采样空档里自己点掉了「发送」（emc-2-017）：和
     * [AlbumOutcome.ALREADY_SENT] 一个道理，调用方同样必须当成「走通了」——
     * 退回分享会把同一张图再发一遍。
     */
    enum class PasteOutcome { SENT, STAGED, ALREADY_SENT, FAILED }

    companion object {
        const val PACKAGE_WECHAT = "com.tencent.mm"
        const val PACKAGE_QQ = "com.tencent.mobileqq"

        /**
         * 自动发送支持的应用：只有这两家的分享页流程是照着实测一点点写出来的。
         * 悬浮球的显示范围与它无关（见 [SenderPrefs.ballPackages]），球可以出现在任何应用里，
         * 那种情况下挑完表情走系统分享面板。
         */
        val WATCHED_PACKAGES = setOf(PACKAGE_WECHAT, PACKAGE_QQ)

        private const val EDIT_TEXT_CLASS = "android.widget.EditText"

        /**
         * 轮询「选择聊天」列表的间隔。
         * 每轮都要把整棵无障碍树深遍历一遍（节点数据由微信进程提供，全是跨进程调用），
         * 250ms 一次会明显拖慢它 —— 放宽到 600ms，15 秒超时内仍有约 25 轮（emc-1-016）。
         */
        private const val POLL_INTERVAL_MS = 600L
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

        /**
         * 标题只在这个比例以上的区域里找。
         * 不能太大：聊天页标题栏底边大约在屏高 9% 处，0.18 一直盖到消息列表头一两屏，
         * 会把消息正文当成聊天对象名（emc-1-017）。配合「可滑动容器里的节点不算」一起用。
         */
        private const val TITLE_ZONE_RATIO = 0.12f

        /** 形如「21:03」的消息时间标签。 */
        private val TIME_ONLY = Regex("^\\d{1,2}:\\d{2}$")

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

        /**
         * 所有认得的「确认发送」按钮文案。
         *
         * 微信的确认框按钮叫「发送」，QQ 相册预览页那一下叫「确定」—— 以前只认「发送」，
         * QQ 就永远停在最后一步（用户实测：卡在「确定」按钮）。判定时命中
         * [CONFIRM_TEXTS] 里任意一个即可；[CONFIRM_SEND_TEXT] 单独留着指聊天页输入栏
         * 那个「发送」（粘贴路线只认它，绝不认「确定」）。
         */
        private val CONFIRM_TEXTS = listOf(CONFIRM_SEND_TEXT, "确定")
        private val CONFIRM_CANCEL_TEXTS = listOf(CONFIRM_CANCEL_TEXT, "返回")

        /** 「发送给：X」确认框标题的前缀。正文里随便出现这三个字不算（emc-0-005）。 */
        private const val MARKER_SEND_TO = "发送给"

        /** 屏幕这个比例以下属于聊天页输入栏那条带子，那里的「发送」绝不能碰。 */
        private const val INPUT_BAR_GUARD_RATIO = 0.82f

        /**
         * 候选名里带这些字符，才允许走三级兜底匹配。
         * 这里**不含空格** —— 名字里出现一个空格就开启兜底，风险远大于收益（emc-0-004）。
         */
        private const val CONTAINED_SEPARATORS = "，,、：:（("

        /**
         * 兜底匹配时，候选名里除名字以外剩下的部分只允许是这些固定装饰文案。
         * 微信聊天页的标题会读成「岩星，点击进入聊天信息」这种形状，名字后面挂的就是它。
         */
        private val CONTAINED_DECORATIONS = setOf(
            "点击进入聊天信息", "点击进入", "聊天信息", "更多信息", "详情", "点击查看"
        )

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
        /**
         * 等粘贴结果时的采样间隔。
         * 等满 [PASTE_RESULT_DELAY_MS] 之后只看一眼是不够的：用户能在这一眼里自己点掉
         * 「发送」，那之后两种形态都看不到了（emc-2-017）。所以从粘贴动作下发就开始轮询。
         */
        private const val PASTE_POLL_MS = 250L
        /** 点掉「发送」之后隔这么久复查一眼：只写日志（确认预览小窗真的关掉了）。 */
        private const val PASTE_SEND_CHECK_MS = 1200L

        /** 判定「输入区多出一张图」时，只看输入框上方这段像素。 */
        private const val PASTE_BAND_PX = 420
        /**
         * 粘贴前后量「附近大图」时，带子再往上放宽多少（按屏幕高度的比例算）。
         *
         * 粘贴这一下必然把键盘顶起来，输入栏跟着整体上移（差不多一个键盘的高度）。
         * 「粘贴前」是按键盘没弹起的位置量的，「粘贴后」是按弹起后的位置量的 —— 两条带子一错位，
         * 图明明进去了也可能数不出来，于是判成失败、退回分享，同一张表情发两遍（emc-2-032，
         * 和相册路线 ALBUM_BAR_SLACK_RATIO 是同一个道理）。统一往上放宽，前后用同一条带子。
         */
        private const val PASTE_KEYBOARD_SLACK_RATIO = 0.45f
        /** 输入框下沿再往下这么多也算「输入区」，微信有时把预览缩略图放进输入框里侧。 */
        private const val PASTE_GAP_PX = 80
        /** 只认够大的图片：输入栏里的小图标（表情、加号）不能被误当成粘进来的图。 */
        private const val PASTE_IMAGE_MIN_WIDTH_RATIO = 0.15f

        /** 相册路线：「+」按钮的 contentDescription（微信 / QQ 都是这个）。 */
        private const val PLUS_CD = "更多功能"
        private const val PLUS_TEXT = "+"
        private const val PLUS_TEXT_FULL = "＋"
        /** 「+」面板里那一项。微信叫「相册」，QQ 叫「图片」——点进去才是图库（用户 m05486 实测）。 */
        private const val ALBUM_ENTRY_TEXT = "相册"
        /** 面板里当作相册入口的文案：先认精确的「相册」，认不到再认这些同义项。 */
        private val ALBUM_ENTRY_TEXTS = setOf(
            "相册", "图片", "照片", "图库", "本地相册", "手机相册", "从相册选择", "所有图片", "图片和视频"
        )
        /** 二级入口：QQ 点「图片」之后那一层菜单里的「相册」。这里不放「图片」，免得原地打转。 */
        private val ALBUM_ENTRY_SECOND_TEXTS = setOf(
            "相册", "本地相册", "手机相册", "从相册选择", "所有图片", "图片和视频", "图库"
        )
        /** 相册第一格图的最小边长（占屏宽比例）——比这小的是图标，不是格子。 */
        private const val ALBUM_CELL_MIN_RATIO = 0.2f
        /**
         * 输入栏那条带子按**输入框**上下各放宽这么多（占输入框高度的比例）。
         *
         * 不能用屏高比例：键盘 / 表情面板一弹出来，整条输入栏会被顶到屏幕中间
         * （实测 2456 高的屏上输入框落在 y 1385-1503，而「0.85 屏高」那条线在 y 2087），
         * 按比例找只能捞到键盘自己的节点，「+」怎么都找不到（emc-1-029）。
         */
        private const val ALBUM_BAR_SLACK_RATIO = 0.75f
        private const val ALBUM_PANEL_DELAY_MS = 700L
        private const val ALBUM_PICKER_DELAY_MS = 1500L
        private const val ALBUM_PREVIEW_DELAY_MS = 1200L
        /**
         * 点完格子之后还愿意再看多久（毫秒，接在 [ALBUM_PREVIEW_DELAY_MS] 那一拍后面）。
         *
         * QQ 上点格子是「进预览页」，底栏（原图 / 编辑 / 发送）要等这张图 —— 动图尤其慢 ——
         * 解码完才画出来。原来只看 [ALBUM_PREVIEW_DELAY_MS] 那一眼，正好落在「标题栏画好了、
         * 底栏还没画」的缝里，于是判失败、按返回、还落回分享路线（emc-2-031）。
         */
        private const val ALBUM_SEND_TIMEOUT_MS = 3000L
        /** 等确认键时的取样间隔（emc-2-031）。 */
        private const val ALBUM_SEND_POLL_MS = 400L
        /** 摊开「+」面板现场时，从输入框往上多看这么多像素。 */
        private const val ALBUM_PANEL_BAND_PX = 700
        /** 相册首次使用会弹权限框，顺手点掉。 */
        private val ALBUM_ALLOW_TEXTS = setOf("允许", "始终允许")

    /** 只有选图页 / 预览页才有的按钮文案：用来确认「预览页还开着」（emc-2-016）。 */
    private val ALBUM_PICKER_MARK_TEXTS = setOf("原图", "预览", "编辑")

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

        /**
         * 当前前台应用，**任何应用都算**（认不出时为 null）。
         *
         * [foregroundPackage] 表达的是「自动发送现在能不能用」，只认微信 / QQ；
         * 这一个给悬浮球判断「用户勾的应用列表里有没有它」，所以一个包都不能过滤。
         */
        @Volatile
        var foregroundApp: String? = null
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

        private val appListeners = CopyOnWriteArrayList<(String?) -> Unit>()

        /** 订阅「前台是哪个应用」（任何应用，见 [foregroundApp]），注册即回调一次。 */
        fun addAppListener(listener: (String?) -> Unit) {
            appListeners.addIfAbsent(listener)
            listener(foregroundApp)
        }

        fun removeAppListener(listener: (String?) -> Unit) {
            appListeners.remove(listener)
        }

        /**
         * 读一次当前聊天页的标题，返回聊天对象名；不在聊天页或读不到时返回 null。
         * 由后台线程调用则是阻塞的，所以调用方应保证在主线程上。
         */
        fun readChatTitle(): String? = instance?.let { service ->
            runCatching { service.doReadChatTitle() }
                .onFailure { logFailure("读聊天页标题", it) }
                .getOrNull()
        }

        /**
         * 这些入口把异常吞掉是**故意的**：无障碍服务可能已经被系统断连，
         * 抛到调用方只会把悬浮窗那条链路一起带崩。但以前吞得一点痕迹不留，
         * 用户报「点了没反应」时日志里什么也看不到（emc-1-038）—— 所以至少记一行。
         */
        private fun logFailure(what: String, e: Throwable) {
            SendLog.e("自动发送", what + "出错：" + (e.message?.take(80) ?: e.javaClass.simpleName))
        }

        /**
         * 串行化闸门：整套自动化（读标题 / 粘贴 / 相册路线 / 点会话）共用一把锁。
         * 这些都是「一次性的界面操作」，内部全是 delay() 挂起点（最长 15 秒轮询），
         * 任意两条交错都会让匹配与点击落在被对方改过的页面上（emc-0-006）。
         * 拿不到锁就**立刻放弃**，不排队 —— 排队会在几秒后突然点一下屏幕，更糟。
         */
        private val automation = Mutex()

        private fun acquireAutomation(what: String): Boolean {
            if (automation.tryLock()) return true
            SendLog.d("自动发送", "上一次自动操作还没结束，放弃本次「" + what + "」")
            return false
        }

        private fun releaseAutomation() {
            runCatching { automation.unlock() }
        }

        /**
         * 整套自动化的总时限。里面的每一段本来都有自己的轮询上限，但读树这一步是往
         * 微信 / QQ 的进程要节点 —— 对方卡住时那个调用会一直不返回，这一轮就永远不结束，
         * 闸门也就永远不放开：之后每次点悬浮球都是「上一次还没结束」（emc-2-032）。
         * 60 秒比最慢的一条路（相册路线十几秒）宽得多，正常操作碰不到这条线。
         */
        private const val AUTOMATION_TIMEOUT_MS = 60_000L

        /**
         * 备选读法：聊天页面上读不到名字时，点开「更多信息」进聊天信息页，
         * 从对端头像的「昵称头像」文案里取名字，再按返回键回到聊天页。
         * 会切一下页面，所以只在直接读不到时调用。
         */
        suspend fun readChatTitleViaInfo(): String? {
            val service = instance ?: return null
            if (!acquireAutomation("读聊天信息页")) return null
            return try {
                withContext(Dispatchers.Main) {
                    runCatching { withTimeout(AUTOMATION_TIMEOUT_MS) { service.doReadChatTitleViaInfo() } }
                        .onFailure { logFailure("读聊天信息页", it) }
                        .getOrNull()
                }
            } finally {
                releaseAutomation()
            }
        }

        /**
         * 剪贴板路线：长按输入框 → 点「粘贴」→ 看输入区有没有多出图片 → 点「发送」。
         * 这条路的目的地天然是当前聊天，不需要认人；[PasteOutcome.FAILED] 由调用方退回分享路线，
         * [PasteOutcome.STAGED] 表示图已经就位、最后那一下留给了用户 —— 同样算这条路的终点；
         * [PasteOutcome.ALREADY_SENT] 是用户在采样空档里自己把「发送」点了（emc-2-017），同样算走通了。
         */
        suspend fun pasteIntoChat(): PasteOutcome {
            val service = instance ?: return PasteOutcome.FAILED
            if (!acquireAutomation("粘贴到聊天")) return PasteOutcome.FAILED
            return try {
                withContext(Dispatchers.Main) {
                    runCatching { withTimeout(AUTOMATION_TIMEOUT_MS) { service.doPasteIntoChat() } }
                        .onFailure { logFailure("粘贴到聊天", it) }
                        .getOrDefault(PasteOutcome.FAILED)
                }
            } finally {
                releaseAutomation()
            }
        }

        /**
         * 相册路线：让微信 / QQ 自己走「+ → 相册 → 第一格 → 确认」。
         * 不用认聊天对象名字，动图也能保住动画；点第一格之前任何一步没把握都返回 [AlbumOutcome.FAILED]；
         * 点了第一格之后页面换了却认不出结果，则返回 [AlbumOutcome.UNCERTAIN]（emc-2-031）。
         * 用户关掉「最后一步自动确认」时会返回 [AlbumOutcome.STAGED]：图已经选好停在预览页，
         * 由他自己点那一下。
         */
        suspend fun sendPhotoViaAlbum(): AlbumOutcome {
            val service = instance ?: return AlbumOutcome.FAILED
            if (!acquireAutomation("相册路线")) return AlbumOutcome.FAILED
            return try {
                withContext(Dispatchers.Main) {
                    runCatching { withTimeout(AUTOMATION_TIMEOUT_MS) { service.doAlbumRoute() } }
                        .onFailure { logFailure("相册路线", it) }
                        .getOrDefault(AlbumOutcome.FAILED)
                }
            } finally {
                releaseAutomation()
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
        ): ChatClick {
            val service = instance ?: return ChatClick.NO_TARGET
            if (!acquireAutomation("点会话")) return ChatClick.NO_TARGET
            return try {
                withContext(Dispatchers.Main) {
                    runCatching {
                        withTimeout(timeoutMs + AUTOMATION_TIMEOUT_MS) {
                            service.doClickChatByName(name, timeoutMs, appPackage)
                        }
                    }
                        .onFailure { logFailure("点会话", it) }
                        .getOrDefault(ChatClick.NO_TARGET)
                }
            } finally {
                releaseAutomation()
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
        SendLog.e("服务", "无障碍服务被系统断开（应用更新或手动关闭后会这样）")
        instance = null
        isConnected = false
        updateForeground(null)
        updateForegroundApp(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(foregroundRecheck)
        SendLog.d("服务", "无障碍服务销毁")
        instance = null
        isConnected = false
        updateForeground(null)
        updateForegroundApp(null)
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
            updateForegroundApp(pkg)
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
        // 悬浮球不看白名单：只要认得出前台是谁就记下来，它自己按用户勾的列表判断。
        updateForegroundApp(active)
    }

    // ---------- 前台应用 ----------

    private fun updateForeground(next: String?) {
        if (next == foregroundPackage) return
        SendLog.d(
            "前台",
            (foregroundPackage ?: "无") + " → " + (next ?: "无") +
                "（自动发送：" + (if (next == null) "关" else "可用") + "）"
        )
        foregroundPackage = next
        foregroundListeners.forEach { listener ->
            runCatching { listener(next) }
        }
    }

    /**
     * 记录「前台是哪个应用」，任何应用都收 —— 悬浮球按它和用户勾的应用列表决定显不显示。
     *
     * 和 [updateForeground] 分开记是这次改造的核心：那个字段还兼着「自动发送能不能用」的
     * 语义（非微信 / QQ 一律 null），球要是继续跟着它，就只能出现在微信 / QQ 里。
     */
    private fun updateForegroundApp(next: String?) {
        if (next == foregroundApp) return
        SendLog.d(
            "前台",
            "球范围：" + (foregroundApp ?: "无") + " → " + (next ?: "无")
        )
        foregroundApp = next
        appListeners.forEach { listener ->
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
            SendLog.w("读标题", "拿不到当前应用窗口的根节点")
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
            SendLog.d("读标题", "命中 " + SendLog.mask(winTitle) + "（来源=活动窗口标题）。标题区候选：" + census)
            return winTitle
        }

        // 微信聊天页不暴露标题，但每条消息的头像带着「昵称头像」——从左半屏那个头像取名字。
        val avatarName = partnerNameFromAvatars(nodes, screenWidth)
        if (avatarName != null) {
            SendLog.d("读标题", "命中 " + SendLog.mask(avatarName) + "（来源=对端头像：" + avatarCensus(nodes, screenWidth) + "）")
            return avatarName
        }

        // 判据二：聊天对象名是顶部那条窄带里最靠上的短文本。
        val picked = pickTitle(nodes, titleZone, screenWidth)
        SendLog.d(
            "读标题",
            (if (picked != null) "命中 " + SendLog.mask(picked) else "没认出来") +
                "（根=" + owner + "，节点 " + nodes.size + " 个，窗口 " + roots.size + " 个：" + windowInfo +
                "，窗口事件文本=" + SendLog.mask(lastWindowText) + "）。标题区候选：" + census +
                "。头像：" + avatarCensus(nodes, screenWidth)
        )
        if (picked != null) return picked

        // 兜底：微信的换页事件常常直接带着聊天对象名。只在名字像样时才敢用。
        val fallback = lastWindowText?.takeIf { looksLikeName(it) }
        if (fallback != null) SendLog.w("读标题", "改用窗口事件文本兜底：" + fallback)
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
            SendLog.w("读标题", "补读放弃：下半屏没找到输入框，不在聊天页")
            return null
        }
        val infoButton = nodes.firstOrNull { node ->
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc !in INFO_BUTTON_TEXTS || !node.isClickable) return@firstOrNull false
            val r = boundsOf(node)
            r.width() > 0 && r.height() > 0 && r.centerX() > screenWidth * TITLE_MAX_CENTER_RATIO
        }
        if (infoButton == null) {
            SendLog.w("读标题", "补读放弃：没找到右上角的「更多信息」按钮")
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
    private suspend fun doPasteIntoChat(): PasteOutcome {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val inputMinTop = (screenHeight * INPUT_TOP_RATIO).toInt()
        val before = collectAll()
        if (!hasInputBelow(before, inputMinTop)) {
            SendLog.w("粘贴", "放弃：下半屏没找到输入框，不在聊天页")
            return PasteOutcome.FAILED
        }
        val input = before.lastOrNull { isEditableNode(it) }
        if (input == null) {
            SendLog.w("粘贴", "放弃：没找到输入框节点")
            return PasteOutcome.FAILED
        }
        val box = boundsOf(input)
        // 量图用带子（会顺着键盘上移放宽），点坐标仍用 box —— 两者不能混。
        val band = pasteBand(box)
        val beforeImages = imageNodesNearInput(before, band, screenWidth)
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
        val outcome = clickPasteInMenu()
        if (outcome == MenuOutcome.CLICKED) {
            return finishPaste(band, beforeImages, inputMinTop, screenWidth, input)
        }

        // 尝试 2：手势长按。先轻点一下输入框 —— 输入框没聚焦时（键盘没弹出来）
        // 微信那类输入框常常不吃长按；点过之后位置也会变，所以要重新找一次输入框。
        // 只有菜单确实弹出来过才按返回键：菜单没弹出来时按返回键就是退出聊天（emc-1-013）。
        if (outcome == MenuOutcome.NO_PASTE && !dismissPasteMenu(inputMinTop)) {
            SendLog.d("粘贴", "已经离开聊天页，放弃粘贴")
            return PasteOutcome.FAILED
        }
        if (!hasInputBelow(collectAll(), inputMinTop)) {
            SendLog.d("粘贴", "聊天页已经不在了，放弃粘贴")
            return PasteOutcome.FAILED
        }
        SendLog.d("粘贴", "尝试 2 · 先点输入框再长按")
        tap(box.exactCenterX(), box.exactCenterY())
        delay(PASTE_FOCUS_DELAY_MS)
        val focused = collectAll()
        val focusedInput = focused.lastOrNull { isEditableNode(it) }
        val pressBox = if (focusedInput != null) boundsOf(focusedInput) else box
        val pressBand = pasteBand(pressBox)
        val pressImages = imageNodesNearInput(focused, pressBand, screenWidth)
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
            if (clickPasteInMenu() == MenuOutcome.CLICKED) {
                return finishPaste(pressBand, pressImages, inputMinTop, screenWidth, input)
            }
        }

        // 尝试 3：让输入框自己执行粘贴动作（EditText 的 ACTION_PASTE）。
        dismissPasteMenu(inputMinTop)
        if (!hasInputBelow(collectAll(), inputMinTop)) {
            SendLog.d("粘贴", "聊天页已经不在了，放弃粘贴")
            return PasteOutcome.FAILED
        }
        val target = collectAll().lastOrNull { isEditableNode(it) } ?: input
        val actionPaste = runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
        SendLog.d("粘贴", "尝试 3 · 输入框 ACTION_PASTE = " + actionPaste)
        if (actionPaste) {
            val done = finishPaste(band, beforeImages, inputMinTop, screenWidth, target)
            if (done != PasteOutcome.FAILED) return done
            SendLog.d("粘贴", "ACTION_PASTE 之后输入区没变化")
        }
        SendLog.d("粘贴", "三种办法都没能把图粘进去")
        return PasteOutcome.FAILED
    }

    /**
     * 菜单这次到底出现没有。
     * 原来的返回值只有 true/false，把「菜单根本没弹出来」和「菜单弹出来了但没有粘贴项」
     * 混成了一种情况，调用方只好一律按返回键 —— 而在微信里返回键等于退出聊天（emc-1-013）。
     */
    private enum class MenuOutcome { NOT_SHOWN, NO_PASTE, CLICKED }

    /**
     * 在刚弹出来的菜单里点「粘贴」。三种触发方式共用这一步。
     * 顺带把菜单内容和当前窗口列表写进日志 —— 菜单是谁弹的、长什么样，全靠这一行。
     */
    private fun clickPasteInMenu(): MenuOutcome {
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
            // 一条候选文本都读不到 = 菜单压根没弹出来；读到别的项才叫「有菜单但没有粘贴」。
            val outcome = if (entries.isEmpty()) MenuOutcome.NOT_SHOWN else MenuOutcome.NO_PASTE
            SendLog.w("粘贴", "菜单里没有「粘贴」（" + outcome + "）")
            return outcome
        }
        val ok = click(pasteNode)
        SendLog.d("粘贴", "点「粘贴」= " + ok)
        return if (ok) MenuOutcome.CLICKED else MenuOutcome.NO_PASTE
    }

    /**
     * 收起刚弹出来的菜单 / 键盘，免得挡着下一次尝试或后面的分享。
     * **只在聊天页还在时才按返回键**：微信里返回键等于退出聊天，
     * 盲按一下会把后面的轻点、长按、ACTION_PASTE 全落到会话列表上（emc-1-013）。
     * 返回「按完之后是否还在聊天页」。
     */
    private suspend fun dismissPasteMenu(inputMinTop: Int): Boolean {
        if (!hasInputBelow(collectAll(), inputMinTop)) {
            SendLog.d("粘贴", "不按返回键：页面上已经没有输入框，再按就退出去了")
            return false
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(INFO_BACK_DELAY_MS)
        val stillOnChat = hasInputBelow(collectAll(), inputMinTop)
        if (!stillOnChat) SendLog.d("粘贴", "按返回键之后已经离开聊天页")
        return stillOnChat
    }

    /**
     * 粘贴动作发出去之后：判断微信到底把图放进去了没有，放进去就把「发送」点掉。
     *
     * 两种形态都要认：① 图进了输入框（聊天页还在，输入框上方多出一张缩略图）；
     * ② 微信弹出预览小窗（这时聊天页的输入框通常已经不在无障碍树里了）。
     *
     * 采样不能等 [PASTE_RESULT_DELAY_MS] 之后只看一眼（emc-2-017）：用户完全可以在这段时间里
     * 自己点掉「发送」，等我们回头看时两种形态都已经消失，于是判成失败、退回分享，
     * 同一张表情被再发一遍。改成从粘贴动作下发那一刻就开始轮询，并记下两个
     * 「这次确实粘成功过」的实据（见过预览小窗 / 见过输入区多出图）：结束时实据还在、
     * 现场却空了，那就是用户抢在前面发出去了。
     */
    private suspend fun finishPaste(
        band: Rect,
        beforeImages: Int,
        inputMinTop: Int,
        screenWidth: Int,
        input: AccessibilityNodeInfo
    ): PasteOutcome {
        var sawPopup = false
        var sawImage = false
        // 用单调时钟：挂钟会被用户改时间 / NTP 校时拽走，那样这个 deadline 要么立刻到期、
        // 要么空转很久（同文件 doClickChatByName 用的就是 uptimeMillis）。
        val deadline = SystemClock.uptimeMillis() + PASTE_RESULT_DELAY_MS
        var nodes: List<AccessibilityNodeInfo> = collectAll()
        while (true) {
            if (!hasInputBelow(nodes, inputMinTop)) {
                sawPopup = true
            } else if (imageNodesNearInput(nodes, band, screenWidth) > beforeImages) {
                sawImage = true
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            delay(PASTE_POLL_MS)
            nodes = collectAll()
        }
        if (!hasInputBelow(nodes, inputMinTop)) {
            SendLog.d("粘贴", "粘贴后下半屏没有输入框，应该是开了预览小窗，找「发送」")
            val send = sendButtonOnPage(nodes)
            if (send == null) {
                if (sawPopup) {
                    // 小窗可能正在关：用户刚点掉「发送」时，按钮节点已经不在了（emc-2-017）。
                    delay(PASTE_POLL_MS)
                    val again = collectAll()
                    if (hasInputBelow(again, inputMinTop) &&
                        imageNodesNearInput(again, band, screenWidth) <= beforeImages
                    ) {
                        SendLog.d(
                            "粘贴",
                            "预览小窗已经不在了、聊天页的输入框也回来了 —— 图已经发出去了，不退回分享"
                        )
                        return PasteOutcome.ALREADY_SENT
                    }
                }
                SendLog.e("粘贴", "预览小窗上没找到「发送」，现场：" + dumpRows(nodes, foregroundPackage, 0))
                return PasteOutcome.FAILED
            }
            return clickFinalSend(send, "预览小窗", inputMinTop)
        }
        val afterImages = imageNodesNearInput(nodes, band, screenWidth)
        SendLog.d("粘贴", "输入区附近图片节点：" + beforeImages + " → " + afterImages)
        if (afterImages > beforeImages) {
            val send = nodes.firstOrNull {
                titleTextOf(it)?.trim() == CONFIRM_SEND_TEXT && boundsOf(it).top >= inputMinTop
            }
            SendLog.d("粘贴", "输入区多出图片，找「发送」：" + (send != null))
            // 图确实进了输入框：就算没找到「发送」也不算失败 —— 用户自己点一下就行，
            // 退回分享路线反而会在微信上面再糊一层分享面板（emc-2-014）。
            if (send == null) return PasteOutcome.STAGED
            return clickFinalSend(send, "聊天页", inputMinTop)
        }
        if (sawPopup || sawImage) {
            SendLog.d(
                "粘贴",
                "输入区没多出图片，但刚才见过" + (if (sawPopup) "预览小窗" else "输入区里的图") +
                    " —— 用户抢在前面发出去了，不退回分享"
            )
            return PasteOutcome.ALREADY_SENT
        }
        SendLog.d(
            "粘贴",
            // 输入框里可能就是用户刚写的草稿，打码后再写（emc-1-028）。
            "输入区没多出图片，放弃。输入框文字=" + SendLog.mask(input.text?.toString())
        )
        return PasteOutcome.FAILED
    }

    /**
     * 最后那一下「发送」：按设置决定替不替用户点，点完再复查一眼。
     *
     * 关掉「最后一步自动确认」时**必须停手**：粘贴路线以前是唯一一条不受那个开关管的路
     * （用户实测 v0.1.321：关着它也照样点）。点了但没点着同样返回 [PasteOutcome.STAGED] ——
     * 图已经摆在微信里了，交给用户点，绝不能退回分享。
     */
    private suspend fun clickFinalSend(
        send: AccessibilityNodeInfo,
        spot: String,
        inputMinTop: Int
    ): PasteOutcome {
        val label = titleTextOf(send)?.trim().orEmpty()
        val at = "@" + boundsOf(send).flattenToString() + " " + send.className
        if (!SenderPrefs.autoConfirmLastStep(this)) {
            SendLog.d("粘贴", spot + "上有「" + label + "」" + at + "，但设置里关了最后一步自动确认，交给你点")
            return PasteOutcome.STAGED
        }
        val ok = click(send)
        SendLog.d("粘贴", "点" + spot + "的「" + label + "」" + at + " = " + ok)
        if (!ok) return PasteOutcome.STAGED
        delay(PASTE_SEND_CHECK_MS)
        val after = collectAll()
        SendLog.d(
            "粘贴",
            "点「发送」后 " + PASTE_SEND_CHECK_MS + "ms 复查：" +
                (if (hasInputBelow(after, inputMinTop)) "聊天页的输入框回来了" else "输入框仍然不在树里") +
                "，窗口 " + windows.orEmpty().size + " 个：" + windowSummary()
        )
        return PasteOutcome.SENT
    }

    /** 候选们长什么样（文本@四边），放弃自动点时写进日志用。 */
    private fun describeSends(nodes: List<AccessibilityNodeInfo>): String =
        nodes.joinToString(" / ") {
            titleTextOf(it)?.trim().orEmpty() + "@" + boundsOf(it).flattenToString()
        }

    /**
     * 预览小窗 / 预览页上的确认键：先要求文本正好是「发送」或「确定」（含 contentDescription）；
     * 找不到才放宽到以这两个词开头的短文本，而且必须唯一命中 —— 宁可放弃也不乱点。
     */
    private fun sendButtonOnPage(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val exact = distinctNodes(
            nodes.filter {
                titleTextOf(it)?.trim() in CONFIRM_TEXTS && boundsOf(it).width() > 0
            }
        )
        if (exact.size > 1) {
            // 同一屏上不止一个「发送」：多半是预览小窗盖在聊天页上，聊天页那个也还在树里。
            // 点错就是把草稿或别的图发出去，宁可放弃（emc-1-014）。
            SendLog.d(
                "发送键",
                "页面上有 " + exact.size + " 个「发送」，位置分不清，放弃自动点：" + describeSends(exact)
            )
            return null
        }
        if (exact.size == 1) return exact.first()
        val loose = distinctNodes(
            nodes.filter {
                val text = titleTextOf(it)?.trim().orEmpty()
                CONFIRM_TEXTS.any { text.startsWith(it) } && text.length <= 4 &&
                    boundsOf(it).width() > 0
            }
        )
        if (loose.size > 1) {
            SendLog.d("发送键", "页面上有 " + loose.size + " 个疑似「发送」，放弃自动点：" + describeSends(loose))
            return null
        }
        return loose.firstOrNull()
    }

    /** 按屏幕位置去重：同一个按钮在无障碍树里常出现多次（父子节点各一份）。 */
    private fun distinctNodes(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> =
        nodes.distinctBy { node ->
            val r = boundsOf(node)
            r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom
        }

    // ---------- 相册路线（走微信 / QQ 自己的「+ → 相册」，动图能保住动画） ----------

    /**
     * 聊天页 →「+」→「相册」→ 第一格图 →「发送」/「确定」。
     *
     * 为什么要有这条路：微信的「粘贴」会把动图压成一张静态图（实测），只有走它自己的
     * 相册入口，发出的动图才会动。全程只按节点点击，不用认聊天对象名字。
     *
     * 最后一步受 [SenderPrefs.autoConfirmLastStep] 控制：开着就点掉（微信「发送」、QQ「确定」），
     * 关掉就停在预览页返回 [AlbumOutcome.STAGED]，图已经选好了，用户点一下就走。
     */
    private suspend fun doAlbumRoute(): AlbumOutcome {
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val inputTop = (screenHeight * INPUT_TOP_RATIO).toInt()

        val page = collectAll()
        val box = inputBoxNode(page, inputTop)
        if (box == null) {
            SendLog.w("相册", "放弃：下半屏没找到输入框，不在聊天页")
            return AlbumOutcome.FAILED
        }
        val inputBox = boundsOf(box)

        // ① 输入栏最右边那个「+」。面板已经开着就别再点了 —— 那一下是把它关掉。
        val albumShown = albumEntryNode(page, inputBox, setOf(ALBUM_ENTRY_TEXT))
        if (albumShown != null) {
            SendLog.d(
                "相册",
                "「+」面板已经开着（看到「相册」@" + boundsOf(albumShown).flattenToString() + "），跳过点「+」"
            )
        } else {
            val plus = plusButtonNode(page, inputBox)
            if (plus == null) {
                SendLog.d(
                    "相册",
                    "没找到「+」按钮。输入框=" + inputBox.flattenToString() +
                        "；输入栏现场：" + dumpBar(page, inputBox, foregroundPackage)
                )
                return AlbumOutcome.FAILED
            }
            SendLog.d(
                "相册",
                "点「+」@" + boundsOf(plus).flattenToString() + "（输入框 " + inputBox.flattenToString() + "）"
            )
            if (!click(plus)) {
                SendLog.w("相册", "点「+」没成功")
                return AlbumOutcome.FAILED
            }
            delay(ALBUM_PANEL_DELAY_MS)
        }

        // ② 「+」面板里的相册入口（微信写「相册」，QQ 写「图片」）
        val panel = collectAll()
        val entry = albumEntryNode(panel, inputBox)
        if (entry == null) {
            SendLog.d(
                "相册",
                "「+」面板里没看到相册入口（在找「" + ALBUM_ENTRY_TEXTS.joinToString(" / ") + "」）。现场：" +
                    dumpRows(panel, foregroundPackage, inputBox.top - ALBUM_PANEL_BAND_PX, raw = true, limit = 24)
            )
            exitAlbumPage()
            return AlbumOutcome.FAILED
        }
        val entryLabel = titleTextOf(entry)?.trim().orEmpty()
        SendLog.d("相册", "点面板入口「" + entryLabel + "」@" + boundsOf(entry).flattenToString())
        if (!click(entry)) {
            SendLog.w("相册", "点面板入口「" + entryLabel + "」没成功")
            exitAlbumPage()
            return AlbumOutcome.FAILED
        }
        delay(ALBUM_PICKER_DELAY_MS)

        // ②′ 二级入口：QQ 的「图片」进去以后还是个菜单，得再点一次「相册」。
        //     只在第一下点的不是「相册」本身时才补这一下 —— 微信点「相册」直接进选择器，不用多点。
        if (entryLabel != ALBUM_ENTRY_TEXT) {
            val after = collectAll()
            val second = albumEntryNode(after, inputBox, ALBUM_ENTRY_SECOND_TEXTS)
            if (second != null) {
                SendLog.d(
                    "相册",
                    "再点一层入口「" + titleTextOf(second)?.trim() + "」@" + boundsOf(second).flattenToString()
                )
                click(second)
                delay(ALBUM_PICKER_DELAY_MS)
            } else {
                SendLog.d(
                    "相册",
                    "点「" + entryLabel + "」之后没看到第二层入口，直接找图片格子。现场：" +
                        dumpRows(after, foregroundPackage, inputBox.top - ALBUM_PANEL_BAND_PX, raw = true, limit = 24)
                )
            }
        }

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
            SendLog.d(
                "相册",
                "相册里没找到图片格子。现场：" + dumpRows(picker, foregroundPackage, 0, raw = true, limit = 16)
            )
            exitAlbumPage()
            return AlbumOutcome.FAILED
        }
        SendLog.d("相册", "点第一格图@" + boundsOf(cell).flattenToString())
        if (!click(cell)) {
            SendLog.w("相册", "点第一格图没成功")
            exitAlbumPage()
            return AlbumOutcome.FAILED
        }
        delay(ALBUM_PREVIEW_DELAY_MS)

        // ④ 预览页 / 选择页上的确认键（微信「发送」、QQ「确定」）
        //
        // 点完格子之后不能只看一眼就下结论（emc-2-031）：QQ 上点格子是「进预览页」，
        // 底栏要等这张图解码完才画出来（动图尤其慢）。实测那次失败就卡在这个缝里 ——
        // 读树时只剩标题栏，而 110 毫秒之后才来一个换页事件，说明下结论的时候那一页还在切。
        //
        // 所以一边等一边判两件事：
        //   a. 看到确认键 → 走下面的老逻辑；
        //   b. 选图页的标记（原图 / 预览 / 编辑）全没了、聊天页的输入栏回来了
        //      → 图已经在路上（QQ 点第一格常常就是直接发），绝不能退回分享；
        //   c. 等到超时还是两不像 → [AlbumOutcome.UNCERTAIN]：不点，也不退回分享。
        var preview = collectAll()
        // 相册路线优先用按位置挑的那个（预览页上常有不止一个「发送」）；挑不出来才退回旧的两条路。
        var send = albumSendButton(preview, inputBox, screenWidth)
            ?: sendButtonOnPage(preview)
            ?: looseSendButton(preview)
        var waited = ALBUM_PREVIEW_DELAY_MS
        var backOnChat = false
        if (send == null) {
            SendLog.d(
                "相册",
                "第一拍（" + waited + "ms）没看到确认键，继续等：选图标记=" +
                    stillOnPickerPage(preview) + "，聊天页=" + chatPageIsBack(preview, inputBox, inputTop)
            )
        }
        while (send == null && waited < ALBUM_PREVIEW_DELAY_MS + ALBUM_SEND_TIMEOUT_MS) {
            delay(ALBUM_SEND_POLL_MS)
            waited += ALBUM_SEND_POLL_MS
            preview = collectAll()
            send = albumSendButton(preview, inputBox, screenWidth)
                ?: sendButtonOnPage(preview)
                ?: looseSendButton(preview)
            // 选图页的标记还在 = 还站在选图页上，继续等确认键（别把它的底栏当成聊天页输入栏）。
            if (send == null && !stillOnPickerPage(preview) &&
                chatPageIsBack(preview, inputBox, inputTop)
            ) {
                backOnChat = true
                break
            }
        }
        if (send == null) {
            SendLog.d(
                "相册",
                "等了 " + waited + "ms 也没找到确认键。现场：" +
                    dumpRows(preview, foregroundPackage, 0, raw = true, limit = 16)
            )
            SendLog.d("相册", "页面线索：" + pageIdentity(preview))
            // 找不到确认键有三种截然不同的原因，处理方式完全不同：
            //   ① 用户抢在我们前面自己点了「发送」，或者 QQ 点第一格本来就是直接发 ——
            //      预览页一关，聊天页的输入栏就回来了。绝不能退回分享，否则同一张表情再发一遍
            //      （emc-2-016 / emc-2-031）；
            //   ② 页面确实换了，可既没有确认键、也认不出聊天页 —— 发没发出去说不准，
            //      同样绝不退回分享：宁可少发一张，也不能同一张发两遍（emc-2-031）；
            //   ③ 还站在选图页上 —— 页面根本没走到预览页。这才叫失败，可以落回分享。
            // 输入框自己不带文本，所以现场 dump（只列有文本的节点）里永远看不见它，必须直接问节点。
            if (backOnChat || (inputBoxNode(preview, inputTop) != null && !stillOnPickerPage(preview))) {
                SendLog.d(
                    "相册",
                    "不过聊天页的输入栏已经回来了、选图页那些标记也不在了 —— 图已经发出去了，不退回分享"
                )
                return AlbumOutcome.ALREADY_SENT
            }
            if (!stillOnPickerPage(preview)) {
                SendLog.w("相册", "页面换了、又认不出是不是聊天页：既不点，也不退回分享")
                return AlbumOutcome.UNCERTAIN
            }
            exitAlbumPage()
            return AlbumOutcome.FAILED
        }
        val label = titleTextOf(send)?.trim().orEmpty()
        if (!SenderPrefs.autoConfirmLastStep(this)) {
            // 用户关掉了「最后一步自动确认」：图已经选好停在预览页，让他自己点那一下。
            SendLog.d("相册", "按设置停在最后一步：界面上有「" + label + "」，交给用户点")
            return AlbumOutcome.STAGED
        }
        val ok = click(send)
        SendLog.d("相册", "点「" + label + "」@" + boundsOf(send).flattenToString() + " = " + ok)
        if (!ok) {
            // 图已经进了应用（上一步点掉了相册第一格、页面已经走到预览页），只是最后一下没点着。
            // 这种情况返回 FAILED 会落进调用方的「退回分享」分支，同一张表情就发两遍 ——
            // 本文件那条红线写得很清楚：图已经在应用里的失败只能是 STAGED / UNCERTAIN / ALREADY_SENT。
            SendLog.w("相册", "点「" + label + "」没点着：图已经在预览页，停手交给用户，不退回分享")
            return AlbumOutcome.STAGED
        }
        delay(PASTE_RESULT_DELAY_MS)
        return AlbumOutcome.SENT
    }

    /** 聊天页那个输入框：屏幕下半部分里最宽的那个可编辑节点。 */
    private fun inputBoxNode(nodes: List<AccessibilityNodeInfo>, minTop: Int): AccessibilityNodeInfo? =
        nodes.filter {
            val r = boundsOf(it)
            r.width() > 0 && r.height() > 0 && r.top >= minTop && isEditableNode(it)
        }.maxByOrNull { boundsOf(it).width() }

    /**
     * 还在选图 / 预览页上吗。
     *
     * 「原图」「预览」「编辑」这些按钮只有微信 / QQ 的选图页和预览页才有，聊天页上一个都读不到。
     * 用它把「预览页还在、只是没读到确认键」（真失败）和「预览页已经没了、用户自己点了发送」
     * 分开 —— 这两种情况在树里同样看不到「发送」，处理方式却正相反（emc-2-016）。
     */
    private fun stillOnPickerPage(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.any {
            titleTextOf(it)?.trim() in ALBUM_PICKER_MARK_TEXTS && boundsOf(it).width() > 0
        }

    /**
     * 聊天页回来了吗（＝图已经在路上）。
     *
     * 先看输入框还能不能编辑；再补一条更钝的线索 —— 输入栏那条带子里又出现了能点的小图标
     * （「表情」「+」这些）。QQ 把选好的图放进输入框之后，那块区域未必还是 EditText，
     * 只认 isEditable 会漏（emc-2-031 的日志里就漏了）。
     */
    private fun chatPageIsBack(
        nodes: List<AccessibilityNodeInfo>,
        inputBox: Rect,
        inputTop: Int
    ): Boolean {
        if (inputBoxNode(nodes, inputTop) != null) return true
        return inputBarIconCount(nodes, inputBox) >= 2
    }

    /**
     * 输入栏那条带子里能点的小图标有几个。
     *
     * 只当辅证：选图页的底栏（原图 / 编辑 / 发送）也落在这条带子里，所以调用方必须先确认
     * 「选图页的标记一个都不在」，再问它（emc-2-031）。
     */
    private fun inputBarIconCount(nodes: List<AccessibilityNodeInfo>, inputBox: Rect): Int =
        nodes.count {
            val r = boundsOf(it)
            it.isClickable && r.width() > 0 && r.height() > 0 &&
                r.width() < inputBox.width() && inInputBarBand(r, inputBox)
        }

    /**
     * 一页的「身份线索」：最上面那几个有文字的节点，带上类名和可点性。
     *
     * 失败日志原来只有文本，认不出那一页到底是预览页、相机页还是聊天页 —— emc-2-031 就卡在
     * 这里，用户也没看清闪过去的是什么页。类名 + 可点性 + 位置能把这几种分开。
     * 文本照 [dumpRows] 的老规矩：在可滚动容器里的打码，其余照原样（截到 [MAX_TITLE_LENGTH]）。
     */
    private fun pageIdentity(nodes: List<AccessibilityNodeInfo>, limit: Int = 6): String {
        val rows = nodes.filter {
            val r = boundsOf(it)
            r.width() > 0 && r.height() > 0 && !titleTextOf(it).isNullOrBlank()
        }.sortedBy { boundsOf(it).top }.take(limit).map { node ->
            val r = boundsOf(node)
            val raw = titleTextOf(node)!!.trim()
            val shown = if (hasScrollableAncestor(node)) SendLog.mask(raw) else raw.take(MAX_TITLE_LENGTH)
            val mark = if (node.isClickable) ",可点" else ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            shown + "(" + cls + mark + ")" + "@" + r.top + "[" + r.left + "-" + r.right + "]"
        }
        return if (rows.isEmpty()) "没有可读节点" else rows.joinToString(" / ")
    }

    /** 输入栏那条带子上下各放宽多少像素。 */
    private fun barSlack(inputBox: Rect): Int =
        (inputBox.height() * ALBUM_BAR_SLACK_RATIO).toInt().coerceAtLeast(32)

    /** 输入栏那条带子（比输入框本身上下各放宽一点，给「表情」「+」这些图标留余量）。 */
    private fun inInputBarBand(r: Rect, inputBox: Rect): Boolean {
        val slack = barSlack(inputBox)
        return r.width() > 0 && r.height() > 0 &&
            r.bottom >= inputBox.top - slack && r.top <= inputBox.bottom + slack
    }

    /** 和输入框处在同一横排（上下不超过四分之一个输入框高）。 */
    private fun inInputBarRow(r: Rect, inputBox: Rect): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        val pad = inputBox.height() / 4
        val cy = r.centerY()
        return cy >= inputBox.top - pad && cy <= inputBox.bottom + pad
    }

    /**
     * 输入栏那一行里最靠右的图标：正常就是「+」；拿不准时宁可不走这条路。
     *
     * 这条带子必须**跟着输入框走**（[inInputBarBand]）：键盘 / 表情面板一弹出来，
     * 整条输入栏会被顶到屏幕中间，按屏高比例算会整条错过（emc-1-029）。
     */
    /**
     * 「+」面板里那个相册入口。
     *
     * 微信的面板写「相册」，QQ 写「图片」（点进去才是图库）——只认一个「相册」时 QQ 永远走不通
     * （用户 m05486 实测：QQ 面板里 8 个节点一个都对不上）。[texts] 里若有精确的「相册」，优先用它。
     *
     * 位置判据用 [inPanelBand]，**不能用「输入框上方」**：键盘弹起时整条输入栏会被顶到屏幕中间，
     * 面板落在那条输入栏的**下面**（实测 2456 高的屏上输入栏在 y1391、面板在 y1618），按
     * 「bottom <= 输入框 top」找会把面板整片漏掉，于是明明开着面板却报「没看到相册入口」
     * （用户 m05542 第一次尝试，emc-2-013）。
     */
    private fun albumEntryNode(
        nodes: List<AccessibilityNodeInfo>,
        inputBox: Rect?,
        texts: Set<String> = ALBUM_ENTRY_TEXTS
    ): AccessibilityNodeInfo? {
        val hits = nodes.mapNotNull { node ->
            val text = titleTextOf(node)?.replace(" ", "").orEmpty()
            if (text.isEmpty() || text !in texts) return@mapNotNull null
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
            if (inputBox != null && !inPanelBand(r, inputBox)) return@mapNotNull null
            text to node
        }
        if (hits.isEmpty()) return null
        return (hits.firstOrNull { it.first == ALBUM_ENTRY_TEXT } ?: hits.first()).second
    }

    /**
     * 「+」面板落在哪：**输入栏下面那一块**（键盘的位置 —— 键盘弹起时输入栏被顶上去，面板就在它下方），
     * 或者**输入栏上面一个面板高度以内**。输入栏那一排自己不算，免得把「表情」「+」认成入口。
     */
    private fun inPanelBand(r: Rect, inputBox: Rect): Boolean {
        if (inInputBarBand(r, inputBox)) return false
        if (r.top >= inputBox.bottom) return true
        return r.bottom <= inputBox.top && r.top >= inputBox.top - ALBUM_PANEL_BAND_PX
    }

    /**
     * 相册路线最后那一下的确认键。
     *
     * 预览页上常常同时挂着两个「发送」（真正的按钮 + 它父容器 / 工具栏上的副本），旧逻辑一见
     * 两个就放弃（emc-1-014 是「宁可放弃也不乱点」的产物，用在粘贴路线的小窗上是对的），但相册
     * 路线此时人在 QQ / 微信**自己的**图片预览页上，聊天页那个「发送」最多只是残留 —— 所以这里
     * 按位置挑：先剔掉输入栏那一排里的（那是聊天页自己的），再优先右半屏、最靠下的那个。
     * 挑的时候把候选全写进日志，点错了也查得出。
     */
    private fun albumSendButton(
        nodes: List<AccessibilityNodeInfo>,
        inputBox: Rect?,
        screenWidth: Int
    ): AccessibilityNodeInfo? {
        val exact = distinctNodes(
            nodes.filter {
                titleTextOf(it)?.trim() in CONFIRM_TEXTS && boundsOf(it).width() > 0
            }
        )
        val outside = if (inputBox == null) exact else exact.filterNot { inInputBarBand(boundsOf(it), inputBox) }
        val pool = outside.ifEmpty { exact }
        if (pool.isEmpty()) return null
        if (pool.size > 1) {
            SendLog.d(
                "发送键",
                "页面上有 " + pool.size + " 个「发送」，按位置取最靠下的那个：" +
                    pool.joinToString(" / ") {
                        titleTextOf(it)?.trim().orEmpty() + "@" + boundsOf(it).flattenToString()
                    }
            )
        }
        val rightSide = pool.filter { boundsOf(it).centerX() > screenWidth / 2 }
        val pick = rightSide.ifEmpty { pool }
        return pick.maxWithOrNull(compareBy({ boundsOf(it).top }, { boundsOf(it).right }))
    }

    private fun plusButtonNode(nodes: List<AccessibilityNodeInfo>, inputBox: Rect): AccessibilityNodeInfo? {
        val bar = nodes.filter { inInputBarBand(boundsOf(it), inputBox) }
        // 输入框里已经有字的时候，最右边那个键是「发送」而不是「+」—— 这种时候绝不瞎点。
        if (bar.any { titleTextOf(it)?.trim() == CONFIRM_SEND_TEXT }) {
            SendLog.d("相册", "输入栏里已经出现「发送」，这次不走相册路线")
            return null
        }
        // ① 认文本：各版本微信 / QQ 的「+」是「更多功能」「更多功能按钮」这类无障碍文案。
        bar.firstOrNull {
            val text = titleTextOf(it)?.trim().orEmpty()
            text == PLUS_TEXT || text == PLUS_TEXT_FULL || text.startsWith(PLUS_CD)
        }?.let { return it }
        // ② 认位置：输入框右边、同一排里最靠右那个能点的小图标（右边依次是「表情」「+」）。
        val rightSide = bar.filter {
            val r = boundsOf(it)
            it.isClickable && r.left >= inputBox.right - 8 && r.width() < inputBox.width() &&
                inInputBarRow(r, inputBox)
        }
        rightSide.maxByOrNull { boundsOf(it).right }?.let {
            SendLog.w("相册", "「+」没有可认的文本，取输入框右边最靠右的图标@" + boundsOf(it).flattenToString())
            return it
        }
        // ③ 再兜一层：整排里最靠右能点的。
        SendLog.w("相册", "「+」没有可认的文本，取输入栏最靠右那个能点的图标")
        return bar.filter { it.isClickable && inInputBarRow(boundsOf(it), inputBox) }
            .maxByOrNull { boundsOf(it).right }
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
                dumpRows(pool, foregroundPackage, 0, limit = 16) + "）"
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

    /** 选择页上的「发送(N)」「确定」：允许比精确匹配长一点，但必须唯一命中。 */
    private fun looseSendButton(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val loose = nodes.filter {
            val text = titleTextOf(it)?.trim().orEmpty()
            CONFIRM_TEXTS.any { c -> text.startsWith(c) } && text.length <= 6 &&
                boundsOf(it).width() > 0
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
     * 量图用的那条带子：在输入框的基础上往上放宽（键盘会把它顶上去）。
     *
     * 前后两次采样必须用同一条带子，数字才可比 —— 详见 [PASTE_KEYBOARD_SLACK_RATIO]。
     */
    private fun pasteBand(box: Rect): Rect {
        val screenHeight = resources.displayMetrics.heightPixels
        return Rect(box).apply { top -= (screenHeight * PASTE_KEYBOARD_SLACK_RATIO).toInt() }
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
    private fun tap(x: Float, y: Float): Boolean = gestureAt(x, y, TAP_MS, "轻点输入框")

    /** 长按某个点：微信输入框的菜单只有长按才会出来。 */
    private fun longPress(x: Float, y: Float): Boolean =
        gestureAt(x, y, LONG_PRESS_MS, "长按输入框")

    /**
     * 按坐标点一下（或长按）。
     * dispatchGesture 的返回值只表示「手势被系统收下了」——不代表点中了，
     * 真正结果要看回调，所以回调单独写一条日志，调用方要下结论请用 [verifyGone] 这类复查（emc-1-015）。
     */
    private fun gestureAt(x: Float, y: Float, durationMs: Long, what: String): Boolean = runCatching {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, durationMs)
            )
            .build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                SendLog.d("手势", what + "：系统报告手势已执行完")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                SendLog.d("手势", what + "：系统报告手势被取消（多半是页面已经变了）")
            }
        }
        dispatchGesture(gesture, callback, null)
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
                // 标题区里躺着的往往就是聊天对象名，写进日志前先打码（emc-1-028）。
                r.top to (SendLog.mask(text) + "(" + src + ")@" + r.top + "[" + r.left + "-" + r.right + "]")
            }
            .sortedBy { it.first }
            .take(12)
            .map { it.second }
        return if (items.isEmpty()) "无" else items.joinToString(" / ")
    }

    /** 当前所有窗口的摘要（类型 / 包名 / 窗口标题打码），用于诊断。 */
    private fun windowSummary(): String = runCatching {
        windows.orEmpty().joinToString(" / ") { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "-"
            val title = runCatching { w.title?.toString() }.getOrNull() ?: "-"
            val kind = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) "APP" else "T" + w.type
            // 窗口标题在微信里就等于聊天对象名，同样打码（emc-1-028）。
            kind + ":" + pkg + ":" + SendLog.mask(title)
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
                // 消息列表里的气泡与时间标签绝不能当名字（emc-1-017）：
                // 它们在可滑动容器里，真正的标题栏不是。
                if (hasScrollableAncestor(node)) return@mapNotNull null
                r.top to text
            }
            .minByOrNull { it.first }
            ?.second

    /** 节点是不是在可滑动容器（消息列表、会话列表）里。 */
    private fun hasScrollableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < 8) {
            val parent = current
            if (runCatching { parent.isScrollable }.getOrDefault(false)) return true
            current = runCatching { parent.parent }.getOrNull()
            depth++
        }
        return false
    }

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
            SendLog.d("读标题", "头像「" + SendLog.mask(name) + "」在别处也有同名文本，像是群聊，不敢认")
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

    /** 名字像不像人名：长度合适，且不是微信的按钮 / 消息文案 / 时间标签。 */
    private fun looksLikeName(text: String): Boolean =
        text.length in 1..MAX_TITLE_LENGTH &&
            text !in IGNORED_TITLES &&
            text !in IGNORED_TITLE_BUTTONS &&
            text !in IGNORED_MEDIA &&
            !looksLikeTimestamp(text)

    /**
     * 消息列表里的时间标签：「21:03」「昨天 21:03」「3月5日」……
     * 它们又短又靠上，不排掉就会被当成聊天对象名（emc-1-017）。
     * 注意别把「周杰伦」这类正常昵称一起排掉，所以只认「星期 / 周几」不认单个「周」。
     */
    private fun looksLikeTimestamp(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (TIME_ONLY.matches(t)) return true
        if (t.startsWith("昨天") || t.startsWith("今天") || t.startsWith("星期") || t.startsWith("周几")) {
            return true
        }
        return t.contains("月") && t.contains("日")
    }

    /** 认不出来时，把屏上所有头像摊开写日志。 */
    private fun avatarCensus(nodes: List<AccessibilityNodeInfo>, screenWidth: Int): String {
        val items = nodes.mapNotNull { node ->
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) return@mapNotNull null
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
            val side = if (r.centerX() > screenWidth * 0.5f) "右" else "左"
            SendLog.mask(desc) + "@" + side + r.top
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
    ): ChatClick {
        val target = normalizeName(name)
        if (target.isEmpty()) return ChatClick.NO_TARGET

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
                if (round == 1) SendLog.w("点会话", "第 1 轮：拿不到根节点")
            } else {
                val nodes = ArrayList<AccessibilityNodeInfo>()
                roots.forEach { collect(it, nodes, 0) }

                // 用户自己点了人（或微信自己跳到确认框）时会弹「发送给：X」——
                // 这时只管把「发送」点掉，绝不能再在对话框上找名字，否则会点到图片预览之类的东西。
                val confirmSend = confirmDialogSendNode(nodes, rowMinTop)
                if (confirmSend != null) {
                    val label = titleTextOf(confirmSend)?.trim().orEmpty()
                    if (!SenderPrefs.autoConfirmLastStep(this)) {
                        // 用户关掉了「最后一步自动确认」：走到这儿就撒手，让他自己点那一下。
                        SendLog.d(
                            "点会话",
                            "第 " + round + " 轮：看到确认框（按钮「" + label + "」），按设置交给你自己点"
                        )
                        return ChatClick.DEFERRED
                    }
                    val ok = click(confirmSend)
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮：看到确认框，点「" + label + "」" + (if (ok) "成功" else "失败")
                    )
                    return if (ok) ChatClick.CLICKED else ChatClick.NO_TARGET
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
                    return ChatClick.NO_TARGET
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
                        val gone = verifyGone(target, appPackage, rowMinTop)
                        if (!gone) {
                            // 点过了但列表里还有同名行：多半没点中。
                            // 这里**不能再点一次** —— 万一是无障碍树没刷新，就成了连点两下，
                            // 让用户自己点最安全（调用方只会把提示换成「自己点」）。
                            SendLog.d("点会话", "点击手势发出去了但没确认到效果，交给用户自己点")
                        }
                        return if (gone) ChatClick.CLICKED else ChatClick.NO_TARGET
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
        SendLog.e("点会话", "超时未点到：" + target)
        return ChatClick.NO_TARGET
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
            SendLog.w("点会话", "复查：界面上没有「发送给」确认框，不再动手")
            return false
        }
        if (!SenderPrefs.autoConfirmLastStep(this)) {
            SendLog.d("点会话", "复查：看到确认框，按设置交给你自己点")
            return false
        }
        val ok = click(sendNode)
        SendLog.d(
            "点会话",
            "复查：看到确认框，点「" + titleTextOf(sendNode)?.trim() + "」" + (if (ok) "成功" else "失败")
        )
        return ok
    }

    /**
     * 从节点里挑出确认框的确认键（微信「发送」、QQ「确定」）。判据必须严：
     * 要么界面上有「发送给」字样，要么确认键和「取消 / 返回」并排挨着 ——
     * 聊天页输入框旁边那个「发送」是绝不能碰的（会把你打的字发出去）。
     */
    private fun confirmDialogSendNode(
        nodes: List<AccessibilityNodeInfo>,
        minTop: Int
    ): AccessibilityNodeInfo? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        var send: AccessibilityNodeInfo? = null
        var cancel: AccessibilityNodeInfo? = null
        var marker = false
        for (node in nodes) {
            val text = titleTextOf(node) ?: continue
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0 || r.top < minTop) continue
            // ① 只认「发送给…」开头的标题节点；正文里出现这三个字不算（emc-0-005）。
            if (text.startsWith(MARKER_SEND_TO)) marker = true
            if (text in CONFIRM_TEXTS) send = node
            if (text in CONFIRM_CANCEL_TEXTS) cancel = node
        }
        val target = send ?: return null
        val other = cancel
        val buttons = boundsOf(target)
        // ② 「发送」与「取消」成对并排，且这对按钮不在屏幕最底部那条输入栏带子里 ——
        //    聊天页输入框旁边那个「发送」永远贴着底部，而且它旁边没有「取消」。
        val sameRow = other != null &&
            alongside(buttons, boundsOf(other), screenWidth, screenHeight) &&
            !inInputBar(buttons, screenHeight)
        if (sameRow) return target
        // ③ 只有成对按钮凑不齐时，才允许单靠「发送给：X」标题 + 按钮浮在屏幕中段来判断。
        if (marker && inDialogZone(buttons, screenHeight)) return target
        return null
    }

    /** 聊天页输入栏那条带子：这上面的「发送」绝不能点。 */
    private fun inInputBar(r: Rect, screenHeight: Int): Boolean =
        r.centerY() > screenHeight * INPUT_BAR_GUARD_RATIO

    /** 确认框浮在屏幕中段，不会贴着任何一条边。 */
    private fun inDialogZone(r: Rect, screenHeight: Int): Boolean {
        val centerY = r.centerY()
        return centerY > screenHeight * 0.12f && centerY < screenHeight * (INPUT_BAR_GUARD_RATIO - 0.02f)
    }

    /**
     * 两个按钮是不是并排在一起（确认框里「发送」和「取消」总是挨着）。
     * 阈值必须按屏幕比例算：老实现写死 1100px，在 720/1080px 屏上恒为真，等于没有判据（emc-0-005）。
     */
    private fun alongside(a: Rect, b: Rect, screenWidth: Int, screenHeight: Int): Boolean =
        Math.abs(a.centerY() - b.centerY()) < screenHeight * 0.05f &&
            Math.abs(a.centerX() - b.centerX()) < screenWidth * 0.6f

    /**
     * 点完复查一次：那一行还在不在。
     * 结论以复查为准 —— 手势被系统收下不等于点到了（emc-1-015）。
     * 拿不到根节点时无法判断，回 true（按「已点开」处理，避免调用方重来一次）。
     */
    private suspend fun verifyGone(target: String, appPackage: String?, rowMinTop: Int): Boolean {
        delay(CLICK_VERIFY_DELAY_MS)
        val roots = applicationRoots()
        if (roots.isEmpty()) {
            SendLog.w("点会话", "点击后复查：拿不到根节点，无法确认，按已点开处理")
            return true
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        roots.forEach { collect(it, nodes, 0) }
        val left = matchRows(nodes, target, appPackage, rowMinTop).size
        SendLog.d("点会话", "点击后复查：列表里还剩 " + left + " 行同名（0 表示确实进去了）")
        return left == 0
    }

    /**
     * 把标题栏以下看得见的短文本按位置摊开 —— 列表里到底写了什么，一看就知道。
     *
     * [raw] 为真时，**不在可滑动容器里**的节点直接写原文：应用自己的固定菜单（「+」面板、
     * 相册选择器）不打码就完全定位不了（上一次 QQ 面板里 8 个节点全是「＊e2dc0（2 字）」这种
     * 指纹，谁也对不上「相册」）；聊天内容都在可滑动列表里，照旧打码（emc-1-028 / emc-1-029）。
     */
    private fun dumpRows(
        nodes: List<AccessibilityNodeInfo>,
        appPackage: String?,
        minTop: Int,
        raw: Boolean = false,
        limit: Int = 8
    ): String {
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
                val shown = if (raw && !hasScrollableAncestor(node)) text else SendLog.mask(text)
                r.top to (shown + "@" + r.top + "[" + r.left + "-" + r.right + "]")
            }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
        return if (items.isEmpty()) "标题栏以下没有可读文本" else items.joinToString(" / ")
    }

    /**
     * 把输入框那一排整个摊开：不管有没有可读文本、能不能点，全列出来。
     *
     * 相册路线失败时全靠这一行定位 —— 上一次只列了「有文本且在某条线以下」的节点，
     * 结果只看到键盘自己的东西，「+」在不在树里、是不是可点，一个字都没写（emc-1-029）。
     */
    private fun dumpBar(nodes: List<AccessibilityNodeInfo>, inputBox: Rect, appPackage: String?): String {
        val items = nodes
            .filter { node ->
                val pkg = runCatching { node.packageName?.toString() }.getOrNull()
                appPackage == null || pkg == null || pkg == appPackage
            }
            .mapNotNull { node ->
                val r = boundsOf(node)
                if (!inInputBarBand(r, inputBox)) return@mapNotNull null
                val text = titleTextOf(node).orEmpty().take(MAX_TITLE_LENGTH)
                // 输入栏这一排是图标按钮，原文（「更多功能」这类无障碍文案）才认得出谁是谁；
                // 输入框自己可能是用户草稿，照旧打码（emc-1-028）。
                val shown = if (!isEditableNode(node) && !hasScrollableAncestor(node)) text else SendLog.mask(text)
                shown + "[" + shortClass(node) +
                    (if (node.isClickable) "|可点" else "") + "]" +
                    "@" + r.left + "," + r.top + "-" + r.right + "," + r.bottom
            }
            .take(16)
        return if (items.isEmpty()) "这一排里一个节点都没有" else items.joinToString(" / ")
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
     * 判据必须严 —— 宁可让用户自己点，也不能点到别人：
     *   ① 候选名本身得带分隔符（说明它是从标题栏文案里拼出来的，不是纯名字）；
     *   ② 列表文本必须整段落在候选名的**头或尾**（原来的 `contains` 连「星」「岩」这种单字也算命中，emc-0-004）；
     *   ③ 去掉列表文本后，候选名剩下的部分只能是固定装饰文案，不含任何其它字符。
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
            if (!isNameInsideTarget(target, text)) continue
            val own = boundsOf(node)
            if (own.width() <= 0 || own.height() <= 0 || own.top < minTop) continue
            val clickable = findClickableAncestor(node) ?: node
            val r = boundsOf(clickable)
            if (r.width() <= 0 || r.height() <= 0) continue
            val key = r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom
            found.putIfAbsent(key, clickable)
        }
        if (found.isNotEmpty()) {
            SendLog.d("点会话", "兜底匹配命中 " + found.size + " 行（目标=" + SendLog.mask(target) + "）")
        }
        return found.values.toList()
    }

    /**
     * 兜底匹配的唯一判据：列表文本整段出现在候选名的头或尾，剩下的部分只能是装饰文案。
     * 例如候选名「岩星，点击进入聊天信息」与列表文本「岩星」：剩下「，点击进入聊天信息」，通过。
     */
    private fun isNameInsideTarget(target: String, text: String): Boolean {
        if (text.length !in 2..12) return false
        val rest = when {
            target.startsWith(text) -> target.substring(text.length)
            target.endsWith(text) -> target.substring(0, target.length - text.length)
            else -> return false
        }
        val trimmed = rest.trim { it.isWhitespace() || it in CONTAINED_SEPARATORS || it == '）' || it == ')' }
        return trimmed.isEmpty() || trimmed in CONTAINED_DECORATIONS
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
        return gestureAt(r.exactCenterX(), r.exactCenterY(), 40L, "节点坐标点击")
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
