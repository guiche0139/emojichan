package com.aris.emojichan.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

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
open class AutoSendService : AccessibilityService() {

    /**
     * 「点会话」的结果。
     *
     * 以前是 Boolean，分不清「没找到那一行」和「走到确认框、但按设置留给你点」——
     * 这两种情况该给用户的提示完全不同，所以拆开。
     *
     * 放在类里（而不是 companion 里）：外部直接写 AutoSendService.ChatClick.DEFERRED。
     *
     * v0.2.218 加 [CANCELLED]：用户自己把这屏关了 —— 他从「选择聊天」页退出来，或者我们还没跑完
     * 他又点了一次表情。这种情况**什么都别做、什么都别说**：既不报「未自动选中」，
     * 也绝不能退回分享路线（同一张图会发两遍，用户 m16984）。
     */
    enum class ChatClick { CLICKED, NO_TARGET, DEFERRED, CANCELLED }

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
     * - [CANCELLED]：这一轮**不该发**。两种来源：用户又点了一次表情（这一次请求把上一次作废），
     *   或者他已经自己退出当前应用。调用方同样**绝不能**落回分享路线：这不是「没成功」，
     *   而是「这次别发了」（用户 m16984：手动退出之后还弹了一次分享）。
     * - [MANUAL]（v0.2.219）：选图页已经开着，但认不出哪一格是我们刚写进去的那张。这一页
     *   就留在屏幕上交给用户自己点（用户 m17147：认不出还退回分享，等于把选图页盖掉、再拉一次
     *   分享面板）。调用方**绝不能**落回分享路线，也别当成失败提示。
     */
    enum class AlbumOutcome { SENT, STAGED, ALREADY_SENT, FAILED, UNCERTAIN, CANCELLED, MANUAL }

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
     *
     * v0.2.218 加 [CANCELLED]：和 [AlbumOutcome.CANCELLED] 一样 —— 这一轮不该发，
     * 调用方既不要退回分享，也不要弹任何提示。
     */
    enum class PasteOutcome { SENT, STAGED, ALREADY_SENT, FAILED, CANCELLED }

    companion object {
        const val PACKAGE_WECHAT = "com.tencent.mm"
        const val PACKAGE_QQ = "com.tencent.mobileqq"

        /**
         * 自动发送支持的应用：只有这两家的分享页流程是照着实测一点点写出来的。
         * 悬浮球的显示范围与它无关（见 [SenderPrefs.ballPackages]），球可以出现在任何应用里，
         * 那种情况下挑完表情走系统分享面板。
         */
        val WATCHED_PACKAGES = setOf(PACKAGE_WECHAT, PACKAGE_QQ)

        /** 日志里显示的应用名：只有白名单应用会出现在日志里（用户 m15752）。 */
        private val APP_LOG_NAMES = mapOf(
            PACKAGE_WECHAT to "微信",
            PACKAGE_QQ to "QQ"
        )

        private const val EDIT_TEXT_CLASS = "android.widget.EditText"

        /**
         * 轮询「选择聊天」列表的间隔。
         * 每轮都要把整棵无障碍树深遍历一遍（节点数据由微信进程提供，全是跨进程调用），
         * 250ms 一次会明显拖慢它 —— 放宽到 600ms，15 秒超时内仍有约 25 轮（emc-1-016）。
         */
        private const val POLL_INTERVAL_MS = 600L
        private const val DEFAULT_CLICK_TIMEOUT_MS = 8000L

        /**
         * v0.2.218：点「+」的几招，按顺序试，每招之后回头看一眼面板有没有真的长出来。
         * 微信排前面那两招是因为用户 m16984 的日志证明它既不吃无障碍点击、也不吃正中间的手势
         * （见 [openPlusPanel]）；QQ 从来没出过这个问题，就让它继续用最稳的那一招打头。
         */
        private val PLUS_ORDER_WECHAT = listOf("手势点上沿", "手势点中心", "无障碍点击", "先点输入框再点")
        private val PLUS_ORDER_DEFAULT = listOf("无障碍点击", "手势点中心", "手势点上沿", "先点输入框再点")

        /** 「+」文案里表示面板收着的那半句（微信：「更多功能按钮，已折叠」）。 */
        private const val PLUS_COLLAPSED_TEXT = "已折叠"

        /** 「选择聊天」页上那个取消按钮的文案：见过又连着两轮不见了 = 这一页被用户关掉了。 */
        private const val PICKER_CANCEL_TEXT = "取消"

        /**
         * v0.2.218：格子文案里的时间跟「现在」差多少分钟以内，才算「这张就是我们刚写进去的」。
         * 我们写完槽位到打开选图页只隔几百毫秒，5 分钟足够宽；真正要滤掉的是
         * 「今天 00:00」那种读不出时分的假时间 —— v0.2.217 就是这么把今天拍的照片全判成旧的。
         */
        private const val CELL_STAMP_MATCH_MIN = 5L

        /**
         * v0.2.220：格子时间跟「刚给槽位刷的 DATE_TAKEN」差多少分钟以内算同一张。
         * 比 [CELL_STAMP_MATCH_MIN] 紧一档 —— QQ 只印到分钟，我们写入到毫秒，48 秒的截断
         * 会落在 1 分钟里；两分钟足够容下「选图页晚开一步」，又不至于挑到隔壁的截图。
         */
        private const val CELL_STAMP_WRITTEN_MIN = 2L

        /** 选图页现场最多列几个格子（v0.2.220：原来 24 个，QQ 一行 1.4 KB，全是拍摄时间）。 */
        private const val CELL_DUMP_LIMIT = 8

        /**
         * 格子文案里的时间：微信写「10月15日」「2025年10月15日」「今天 12:03」「昨天 21:03」，
         * 也有只写「12:03」的。三条正则分别认「年月日」「月日」「时分」，
         * [cellStamp] 把手里这几段拼一个 LocalDateTime。
         */
        private val CELL_DATE_YMD =
            Regex("(\\d{4})\\s*[年/-]\\s*(\\d{1,2})\\s*[月/-]\\s*(\\d{1,2})\\s*日?")
        private val CELL_DATE_MD = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日")
        private val CELL_TIME_HM = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
        /**
         * v0.2.220：QQ 的选图页写「16点44分」「16点」。微信那种文件名里没有时分，
         * 所以这条只在前面几条都读不出时间时才用。
         */
        private val CELL_TIME_CN = Regex("(\\d{1,2})\\s*点\\s*(?:(\\d{1,2})\\s*分)?")

        /** 日志里写时间用的格式，跟系统相册里那行「10-15 12:03」一致，看着眼熟。 */
        private val CELL_STAMP_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /**
         * 收到「非白名单包」的换页事件后，隔多久再复查一次前台到底是谁。
         * 进微信时经常先收到桌面那条事件、后收到微信那条（顺序还会反过来），
         * 立刻下结论就会把「正在切进来」误判成「已经切走」，球于是不出现。
         */
        private const val FOREGROUND_RECHECK_DELAY_MS = 400L
        private const val FOREGROUND_RECHECK_LATE_MS = 1400L

        /**
         * 这些包报到时不算「用户切走了」：下拉通知栏、系统弹窗都会抢一次换页事件。
         * 旧实现靠「只看 TYPE_APPLICATION 的窗口」等价地做到这一点；v0.2.203 起不再枚举窗口
         * （flagRetrieveInteractiveWindows 关掉了），改成显式排除 —— 输入法包名从系统里查，
         * 见 [loadImePackages]。
         */
        private val SHELL_PACKAGES = setOf("com.android.systemui")

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
        /**
         * 判断「+」面板**是不是已经开着**：故意不含「图片」—— 那是聊天里图片消息的无障碍文案
         * （见 [IGNORED_MEDIA]），拿它当判据会把聊天里的图当成「面板开着」，于是跳过点「+」。
         */
        private val ALBUM_PANEL_OPEN_TEXTS = ALBUM_ENTRY_TEXTS - "图片"
        /** 二级入口：QQ 点「图片」之后那一层菜单里的「相册」。这里不放「图片」，免得原地打转。 */
        private val ALBUM_ENTRY_SECOND_TEXTS = setOf(
            "相册", "本地相册", "手机相册", "从相册选择", "所有图片", "图片和视频", "图库"
        )
        /**
         * 面板入口的最大边长（像素）。聊天里的图片消息无障碍文案也叫「图片」，是一块
         * 284×315 的大块头；「+」面板里的图标都是小方块 —— 尺寸这一条把它挡在门外
         * （v0.2.213，被略过的大块头按 1.5 秒限流记一行）。
         */
        private const val ALBUM_ENTRY_MAX_PX = 160
        /** 相册第一格图的最小边长（占内容区宽比例）——比这小的是图标，不是格子。 */
        private const val ALBUM_CELL_MIN_RATIO = 0.2f
        /** 第一格图上面那条线（占内容区高度比例）：标题栏 / 搜索框那一带不算。 */
        private const val ALBUM_CELL_TOP_RATIO = 0.05f
        /**
         * v0.2.219：进了选图页之后还愿意等格子长出来多久（毫秒），每 [ALBUM_CELL_POLL_MS] 看一眼。
         *
         * 用户 m17147 那次日志：进选图页 1.5 秒（[ALBUM_PICKER_DELAY_MS]）时「格子候选 0 个 /
         * 标题栏以下没有可读文本」—— 缩略图还没画出来就下结论了。等格子期间每看一眼都会
         * 重新认一次「哪一格是我们写的那个文件名」，认出就走。
         */
        private const val ALBUM_CELL_WAIT_MS = 3000L
        private const val ALBUM_CELL_POLL_MS = 400L
        /**
         * v0.2.219：开跑时如果上一张还停在应用的选图 / 预览页，最多按几次返回把它收回去。
         *
         * 微信上「预览页 → 选图页 → 聊天页」正好两步，多按一下就可能从聊天里退出去。
         */
        private const val ALBUM_BACKOUT_MAX = 2
        /**
         * 无障碍树少于这么多节点 = 「还没长出来」（应用刚被拉起 / 窗口刚切过去的那一下），
         * 这时所有判据都会落空。主动路线（粘贴 / 相册）先等它长出来再说。
         */
        private const val TREE_READY_MIN_NODES = 4
        private const val TREE_READY_RETRIES = 3
        private const val TREE_READY_DELAY_MS = 400L
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
        /**
         * 等「+」面板长出来：每 [ALBUM_PANEL_POLL_MS] 看一眼，最多看 [ALBUM_PANEL_WAIT_MS]。
         * 小窗 / 分屏下面板动画实测要 2.8 秒（用户 m15649），所以这三秒别再往下压。
         */
        private const val ALBUM_PANEL_POLL_MS = 400L
        private const val ALBUM_PANEL_WAIT_MS = 3000L

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

        /** 最近一次读树读到多少个节点：太少说明应用刚被拉起、树还没长出来。 */
        fun lastReadNodeCount(): Int = instance?.lastReadNodeCount ?: 0

        /** 兼容模式开关变了：让正在跑的服务立刻重新应用 flag（不用重装，也不用重开服务）。 */
        fun refreshFlags() {
            instance?.let { service -> service.handler.post { service.applyAccessibilityFlags() } }
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
         */
        private val automation = Mutex()

        /**
         * 请求号：每来一次新请求 +1。正在跑的那一轮在每个检查点都拿自己手里的号对一下，
         * 对不上就自己收手 —— 这就是「用户又点了一次 / 手动退出，流程要停」的执行机制。
         *
         * 用户 m16984 的日志里，他手动退出第一轮、又点了一次表情，我们回他
         * 「上一次自动操作还没结束，放弃本次 相册路线」，紧接着还弹了一次分享；
         * 「一张图弹两次分享」的观感就是这么来的。
         */
        private val requestSeq = AtomicLong(0L)

        /** 等上一轮收手的上限。它每个检查点都看一眼请求号，正常几十毫秒就退干净了。 */
        private const val AUTOMATION_CANCEL_WAIT_MS = 4_000L

        /** [gen] 还是最新的那一次请求吗？不是 = 该收手了。 */
        private fun stale(gen: Long): Boolean = gen != requestSeq.get()

        /**
         * 拿闸门。返回 null = 这一轮别跑，调用方要把它当成 [AlbumOutcome.CANCELLED] /
         * [PasteOutcome.CANCELLED] / [ChatClick.CANCELLED]，**绝不能退回分享路线**。
         *
         * v0.2.218 起不再「拿不到锁就放弃」：先给请求号 +1 让上一轮在下一个检查点收手，
         * 再等它把锁放开（最多 [AUTOMATION_CANCEL_WAIT_MS]）。只有上一轮卡在读树那种不返回的
         * 调用里、或者等锁期间用户又点了一次，才算真的放弃这次。
         */
        private suspend fun acquireAutomation(what: String): Long? {
            val gen = requestSeq.incrementAndGet()
            if (automation.tryLock()) return gen
            SendLog.d("自动发送", "又点了一次，先让上一次收手（本次 " + what + " 在等）")
            val got = withTimeoutOrNull(AUTOMATION_CANCEL_WAIT_MS) { automation.lock() }
            if (got == null) {
                SendLog.d("自动发送", "上一次一直没收手，放弃本次 " + what)
                return null
            }
            return if (stale(gen)) null else gen
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
         * 剪贴板路线：长按输入框 → 点「粘贴」→ 看输入区有没有多出图片 → 点「发送」。
         * 这条路的目的地天然是当前聊天，不需要认人；[PasteOutcome.FAILED] 由调用方退回分享路线，
         * [PasteOutcome.STAGED] 表示图已经就位、最后那一下留给了用户 —— 同样算这条路的终点；
         * [PasteOutcome.ALREADY_SENT] 是用户在采样空档里自己把「发送」点了（emc-2-017），同样算走通了。
         */
        suspend fun pasteIntoChat(): PasteOutcome {
            val service = instance ?: return PasteOutcome.FAILED
            // v0.2.218：拿不到闸门 = 用户已经又点了一次（或上一轮卡住），这一轮不该发。
            val gen = acquireAutomation("粘贴到聊天") ?: return PasteOutcome.CANCELLED
            return try {
                // v0.2.215：读树是逐节点 Binder IPC（微信聊天页 173 个节点就是 173 次往返），
                // 以前整段挂在主线程上，读一次树期间界面是僵的。服务里没有任何 View / UI 调用
                // （只有 AccessibilityNodeInfo + Handler），换到 Default 线程不影响功能。
                withContext(Dispatchers.Default) {
                    runCatching { withTimeout(AUTOMATION_TIMEOUT_MS) { service.doPasteIntoChat(gen) } }
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
            // v0.2.218：见 [acquireAutomation] —— 这一次不跑，而调用方绝不能因此退回分享。
            val gen = acquireAutomation("相册路线") ?: return AlbumOutcome.CANCELLED
            return try {
                withContext(Dispatchers.Default) {
                    runCatching { withTimeout(AUTOMATION_TIMEOUT_MS) { service.doAlbumRoute(gen) } }
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
            // v0.2.218：这一次不跑（用户又点了一次 / 上一轮卡住）—— 调用方什么都别说。
            val gen = acquireAutomation("点会话") ?: return ChatClick.CANCELLED
            return try {
                withContext(Dispatchers.Default) {
                    runCatching {
                        withTimeout(timeoutMs + AUTOMATION_TIMEOUT_MS) {
                            service.doClickChatByName(name, timeoutMs, appPackage, gen)
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

    /**
     * 最近一次白名单换页事件带来的节点（用的时候爬到它的根）—— 读树起点之一。
     * v0.2.203 不再枚举窗口，这个节点是「微信那个独立标题栏窗口」仅剩的一条线索。
     */
    @Volatile
    private var lastWhitelistSource: AccessibilityNodeInfo? = null

    /** 最近一次「不是输入法、也不是系统壳」的换页事件包名：计时收口时它是最新的证据。 */
    @Volatile
    private var lastNonImePackage: String? = null

    /** 待定的「非白名单前台」：先记下来，等切换落定再看它算不算数。 */
    @Volatile
    private var pendingForeign: String? = null
    @Volatile
    private var pendingForeignAt = 0L

    /** 输入法包名（连接时查一次）：键盘弹出来时前台会变成它，不能算「用户切走了」。 */
    @Volatile
    private var imePackages: Set<String> = emptySet()

    private val foregroundSettleEarly = Runnable { settleForeground() }
    private val foregroundSettleLate = Runnable { settleForeground() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        SendLog.init(applicationContext)
        SendLog.d("服务", "无障碍服务已连接")
        applyAccessibilityFlags()
        SendLog.d("服务", "已启用的无障碍服务：" + enabledServicesDump())
        imePackages = loadImePackages()
        // 服务可能是用户刚在设置里打开的，此时没有任何事件，先自己问一次。
        refreshForeground()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearForegroundTimers()
        SendLog.e("服务", "无障碍服务被系统断开（应用更新或手动关闭后会这样）")
        instance = null
        isConnected = false
        updateForeground(null)
        updateForegroundApp(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearForegroundTimers()
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

        // 输入法弹出来、通知栏拉下来，都只是「盖在上面」，不是用户切走了：直接忽略，
        // 前台维持原状（旧实现靠「只看应用窗口」等价地做到这一点）。
        if (isTransparent(pkg)) return
        lastNonImePackage = pkg

        if (pkg in WATCHED_PACKAGES) {
            // 白名单应用自己报到，最可信；顺手撤掉还没跑的收口，并把这次事件的节点留着当读树起点。
            clearForegroundTimers()
            pendingForeign = null
            // 事件自己带着的文本一并记下：微信的聊天页会把标题塞在这里。
            // 注意这里是无条件赋值（可能为 null），免得旧聊天的名字一直留着被当成当前对象。
            val windowText = eventTextOf(e)
            val textChanged = windowText != lastWindowText
            lastWindowText = windowText
            lastWhitelistSource = runCatching { e.source }.getOrNull()
            // 用户 m15752：日志只留白名单应用，而且同一段窗口文本重复报到时不重复写行 ——
            // 微信在聊天页里每换一次界面都会报一条，带回来的文本往往一直是「微信」。
            // 「事件敏感」是 v0.2.209 排查 Android 14 敏感数据门禁时留的字段，跑一次反射不额外读树。
            if (textChanged) {
                SendLog.d(
                    "事件",
                    "换页 " + appLabel(pkg) + " text=" + (windowText ?: "无") +
                        " sensitive=" + probeBool(e, "isAccessibilityDataSensitive")
                )
            }
            updateForeground(pkg)
            updateForegroundApp(pkg)
            return
        }

        // 非白名单：可能是切换动画途中的旧窗口，也可能真的切走了。不立刻下结论 ——
        // 记下来，等切换落定再看一眼（见 [settleForeground]）。用户 m15752：这一步一个字都不记
        //（以前还记个计数汇总，现在连计数也不要 —— 日志里只留白名单应用的进入 / 退出）。
        pendingForeign = pkg
        pendingForeignAt = SystemClock.uptimeMillis()
        clearForegroundTimers()
        handler.postDelayed(foregroundSettleEarly, FOREGROUND_RECHECK_DELAY_MS)
        handler.postDelayed(foregroundSettleLate, FOREGROUND_RECHECK_LATE_MS)
    }

    /**
     * 复查「当前前台是谁」，兜住切换瞬间的误判。
     *
     * v0.2.203 起不再只认窗口列表（那要 flagRetrieveInteractiveWindows，默认已经关掉）：
     * 改成「窗口列表（有就用）→ 活动窗口根 → 最近一次事件的包名」这条链，最后一个都读不出来时
     * 保持原状：宁可球多留一会儿，也别该出现时不出现。
     */
    private fun refreshForeground() {
        val active = activePackageGuess() ?: lastNonImePackage
        if (active == null) return
        // 用户 m15752：非白名单应用不写日志 —— 只有「前台是白名单」或「刚从白名单离开」
        // 这类跟自动发送有关的结论才值得留一行。
        if (active in WATCHED_PACKAGES || foregroundPackage != null) {
            SendLog.d("前台", "复查前台 = " + appLabel(active))
        }
        updateForeground(if (active in WATCHED_PACKAGES) active else null)
        // 悬浮球不看白名单：只要认得出前台是谁就记下来，它自己按用户勾的列表判断。
        updateForegroundApp(active)
    }

    /**
     * 计时收口：非白名单应用报到之后等一小会儿，看白名单会不会自己报到。
     *
     * 进微信时经常先收到桌面那条事件、后收到微信那条（顺序还会反过来），立刻下结论就会把
     * 「正在切进来」误判成「已经切走」，球于是不出现。v0.2.202 及以前这里问的是窗口列表
     * （windows），关掉那条 flag 之后没得问了 —— 改成问「活动窗口根是谁」，问不出来才按
     * 事件包名下结论。两次（400ms / 1400ms）都判一遍，第二次能纠正第一次的误判。
     */
    private fun settleForeground() {
        if (pendingForeign == null) return
        val guess = activePackageGuess()
        val verdict = guess ?: pendingForeign!!
        val waited = SystemClock.uptimeMillis() - pendingForeignAt
        // 用户 m15752：非白名单应用不写日志。收口结果只在「落定到白名单」时记一行；
        // 「切走了」这件事由 [updateForeground] 的「退出」那行表达，不会漏。
        if (verdict in WATCHED_PACKAGES) {
            SendLog.d(
                "前台",
                "收口 waited=" + waited + "ms by=" +
                    (if (guess == null) "事件包名" else "活动窗口根") + " fg=" + appLabel(verdict)
            )
        }
        updateForeground(if (verdict in WATCHED_PACKAGES) verdict else null)
        updateForegroundApp(verdict)
    }

    private fun clearForegroundTimers() {
        handler.removeCallbacks(foregroundSettleEarly)
        handler.removeCallbacks(foregroundSettleLate)
    }

    /** 系统里所有输入法的包名：它们报到时不算「用户切走了」。 */
    private fun loadImePackages(): Set<String> = runCatching {
        val imm = getSystemService(InputMethodManager::class.java) ?: return emptySet()
        (imm.enabledInputMethodList + imm.inputMethodList).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 这个包报到算不算「用户切走了」。输入法列表可能还没查出来，那时先按少见处理。 */
    private fun isTransparent(pkg: String): Boolean {
        // 我们自己的悬浮球 / 面板也是「盖在微信上面」的窗口，不能当成用户切走了：
        // 否则点球那一刻前台会被判成我们自己，读树的包名过滤失效、可读区域被压成球那么大。
        if (pkg == packageName) return true
        if (pkg in SHELL_PACKAGES) return true
        if (imePackages.isEmpty()) imePackages = loadImePackages()
        return pkg in imePackages
    }

    /**
     * 把用户在设置里选的 flag 应用到服务上。
     *
     * 清单里只留了最基础的那条（flagDefault），两条「有代价的」（读树包含全部节点 / 枚举窗口）
     * 由这里按 [SenderPrefs.a11yLowOverhead] 决定去留 —— setServiceInfo() 改 flag 是即时生效的。
     * 默认是**都加回来**（= v0.2.202 的识别方式）：实测（用户 m14729）关掉它们会让微信的粘贴
     * 路线与 QQ 的相册路线直接掉回分享，所以只作为实验开关留着，不再默认精简。
     *
     * v0.2.207 起 base 里补回 [AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS]：低开销模式的
     * 判据只删 2 / 64 两条，所以两种模式都带上它（用户 m15188 指定的实验，只动这一处）。
     *
     * v0.2.208：这条日志多打一个 isAccessibilityTool —— 它来自 auto_send_service_config.xml 的
     * android:isAccessibilityTool="true"（没有运行时 setter，只能写在 XML 里），装包后看这一行
     * 就能确认声明是不是真的生效了（用户 m15399 批准的实验）。
     *
     * v0.2.209 / v0.2.211 试过的「触摸探索」这条路已经收摊（用户 m15752 要求删掉那个开关）：
     * 常开会让整机单击变成「探索」（要双击才点得动，用户 m15531 / m15570 两次实测），脉冲版
     * 也只是诊断 —— 真正解开微信空壳根的是 v0.2.212，把服务组件名换成系统读屏「选择朗读」的
     * 包名 + 类名（见 AndroidManifest.xml 那段注释与 md/无障碍核心.md）。
     * 所以这里**永远把那条 flag 关着**，也不再有任何触摸探索开关。
     *
     * 这一行日志同时打出我们自己声明的 feedbackType / eventTypes，方便跟读屏的声明
     * （[enabledServicesDump]）对比 —— 那几个值就是「我们像不像读屏」的证据。
     */
    private fun applyAccessibilityFlags() {
        runCatching {
            val info = serviceInfo ?: return
            val low = SenderPrefs.a11yLowOverhead(this)
            // v0.2.207：把 flagReportViewIds 也补回 base —— 与 v0.2.202（微信还能正常自动化那版）
            // 相比，这是唯一还没补回来的一条。猜测（未证实）：它影响的不只是「给节点解析资源名」，
            // 去掉之后微信的窗口只给到 class=null、0 子节点的合成根（QQ 不受影响）。
            // 用户 m15188 批准：只改这一条，其他都不动，装包后复测微信小窗/全屏的相册与剪贴板。
            val base = info.flags or AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // 触摸探索永远关着：它会让整机单击变「探索」（要双击），而微信那棵树已经靠
            // 服务组件名拿到了（v0.2.212），不需要它。这一句同时兜住历史版本留下的常开状态。
            info.flags = (if (low) {
                base and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv() and
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
            } else {
                base or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }) and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            setServiceInfo(info)
            // v0.2.209 起这一行就是「微信为什么读不到树」那套排查的仪表盘，v0.2.211 又补上我们自己
            // 的声明（feedback / 事件类型）与脉冲开关 —— 和 enabledServicesDump() 打出的读屏声明
            // 摆在一起，就能直接看出「我们跟读屏差在哪」。
            SendLog.d(
                "服务",
                "flag 已应用 low=" + low + " flags=" + info.flags +
                    " isAccessibilityTool=" + runCatching { info.isAccessibilityTool }.getOrDefault(false) +
                    " feedback=" + info.feedbackType + " events=" + info.eventTypes +
                    " id=" + runCatching { info.id }.getOrNull() +
                    " touchExplore=" + touchExplorationEnabled()
            )
            // v0.2.210：XML 里的 isAccessibilityTool 是系统在「启用服务」那一刻读的 ——
            // 换了 APK 但没把服务关开一次，系统可能还在用旧配置（那我们就不是无障碍工具，
            // Android 14+ 的敏感数据门禁照样把微信的树扣住）。这一行直接把话说明白。
            if (!runCatching { info.isAccessibilityTool }.getOrDefault(false)) {
                SendLog.w(
                    "服务",
                    "isAccessibilityTool=false：系统还在用旧配置。请到系统设置里把 EmojiChan 的无障碍服务" +
                        "关掉再打开一次（必要时重启手机）"
                )
            }
        }.onFailure {
            SendLog.w("服务", "应用无障碍 flag 失败：" + (it.message?.take(60) ?: it.javaClass.simpleName))
        }
    }

    /**
     * v0.2.210 诊断：把当前启用的无障碍服务全列一遍（我们自己 + 别的读屏）。
     *
     * 用户问「能不能伪装成系统无障碍服务」，而社区实测是 TalkBack 能读到微信、普通服务读不到 ——
     * 要照着抄，先得知道对方在这台机器上到底长什么样：是不是系统应用、feedbackType、
     * capabilities、flags 各是什么。开着 TalkBack 的那次日志能一次把答案给全。
     */
    private fun enabledServicesDump(): String = runCatching {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return@runCatching "（拿不到 AccessibilityManager）"
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (list.isNullOrEmpty()) return@runCatching "（没有已启用的无障碍服务）"
        list.joinToString(" ｜ ") { info ->
            val si = info.resolveInfo?.serviceInfo
            val app = si?.applicationInfo
            val system = runCatching {
                ((app?.flags ?: 0) and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }.getOrDefault(false)
            runCatching { si?.packageName }.getOrNull() + "/" +
                runCatching { si?.name?.substringAfterLast('.') }.getOrNull() +
                " 系统应用=" + system +
                " feedback=" + info.feedbackType +
                " flags=0x" + Integer.toHexString(info.flags) +
                " caps=" + runCatching { info.capabilities }.getOrDefault(-1) +
                " 事件=0x" + Integer.toHexString(info.eventTypes) +
                " 工具身份=" + runCatching { info.isAccessibilityTool }.getOrDefault(false)
        }
    }.getOrElse { "（列举失败：" + (it.message?.take(60) ?: it.javaClass.simpleName) + "）" }

    /**
     * 系统当前是不是开着触摸探索（v0.2.209 诊断用）。
     *
     * 社区实测（droidrun/mobilerun-portal issue #142 等）：微信只在「触摸探索」打开时启动的进程里
     * 把控件树交出来，同一台机器上换成 TalkBack 就能读到完整的 62KB 树，换回别的服务立刻变空壳。
     *
     * 注意：AccessibilityService 没有 onTouchExplorationStateChanged 这个可重写的回调
     * （那个方法在 AccessibilityManager.TouchExplorationStateChangeListener 上），所以这里改成
     * 「用到的时候现查一次」—— 申请之后系统要不要真的打开，看日志里的这个值最直接。
     */
    private fun touchExplorationEnabled(): Boolean = runCatching {
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true
    }.getOrDefault(false)

    // ---------- 前台应用 ----------

    /** 打日志用：白名单应用给中文名（别的包理论上不会进日志，原样给包名）。 */
    private fun appLabel(pkg: String?): String =
        if (pkg == null) "无" else APP_LOG_NAMES[pkg]?.let { it + "(" + pkg + ")" } ?: pkg

    private fun updateForeground(next: String?) {
        if (next == foregroundPackage) return
        val prev = foregroundPackage
        // 用户 m15752：日志只记白名单应用。这个字段本身就只为微信 / QQ 赋值（别的应用一律
        // null），所以这两行正好就是「白名单的进入 / 退出」，一条不多。
        SendLog.d(
            "前台",
            if (next != null) "进入 " + appLabel(next) + " auto=on"
            else "退出 " + appLabel(prev) + " auto=off"
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
        val prev = foregroundApp
        // 用户 m15752：非白名单的应用不记录。这个字段是「任何应用」（给悬浮球判断显隐），
        // 所以只有「进 / 出白名单」这种跟自动发送有关的切换才写一行。
        if ((next != null && next in WATCHED_PACKAGES) || (prev != null && prev in WATCHED_PACKAGES)) {
            SendLog.d("前台", "球范围：" + appLabel(prev) + " → " + appLabel(next))
        }
        foregroundApp = next
        appListeners.forEach { listener ->
            runCatching { listener(next) }
        }
    }

    /**
     * 当前活动的「应用窗口」属于哪个包。
     * 刻意不看 [rootInActiveWindow]：输入法弹出来时活动窗口会变成它，
     * 而这里要找的是底下那个真正的应用。
     * 只认窗口列表，所以**只有兼容模式（flagRetrieveInteractiveWindows）开着时才有结果** ——
     * 平时前台是谁由 [activePackageGuess] 用别的证据回答。
     */
    private fun activeApplicationPackage(): String? = runCatching {
        val applicationWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = applicationWindows.firstOrNull { it.isActive }
            ?: applicationWindows.firstOrNull()
        window?.root?.packageName?.toString()
    }.getOrNull()

    /**
     * 猜当前前台是哪个包，按可信度依次试：
     *   ① 窗口列表（要兼容模式，通常是空的）；
     *   ② rootInActiveWindow（输入法弹出来时它指向输入法，用输入法包名排掉）；
     *   ③ 最近一次非输入法、非系统壳的换页事件包名。
     * 三条都读不出来时返回 null —— 由调用方决定「保持原状」。
     */
    private fun activePackageGuess(): String? {
        activeApplicationPackage()?.takeIf { !isTransparent(it) }?.let { return it }
        val rootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (rootPkg != null && !isTransparent(rootPkg)) return rootPkg
        return lastNonImePackage?.takeIf { !isTransparent(it) }
    }

    /** 顺着 parent 往上爬（最多 6 层），拿这个节点所在窗口的根。 */
    private fun rootOf(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = node ?: return null
        var depth = 0
        while (depth < 6) {
            val parent = runCatching { current.parent }.getOrNull() ?: return current
            current = parent
            depth++
        }
        return current
    }

    /**
     * 取当前应用窗口的根节点，供读界面时使用。
     * ① 活动应用窗口（要窗口列表）→ ② rootInActiveWindow（包名对得上、且不是输入法 / 系统壳）。
     * 最近一次白名单事件那个根**不在这里**给：它由 [applicationRoots] 单独加（要 refresh 验活，
     * 而且只在当前应用就是那个白名单应用时才算数），免得在别的应用里读到上一页的残留。
     */
    private fun activeRoot(): AccessibilityNodeInfo? {
        runCatching { windows.orEmpty() }.getOrDefault(emptyList())
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.let { w -> runCatching { w.root }.getOrNull() }
            ?.let { return it }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val pkg = runCatching { active.packageName?.toString() }.getOrNull()
            val wanted = foregroundPackage
            if (pkg != null && !isTransparent(pkg) && (wanted == null || pkg == wanted)) return active
        }
        return null
    }

    /**
     * 最近一次白名单换页事件带来的**当前仍有效**的窗口根。
     *
     * 用之前先 refresh()：窗口关掉之后那个节点就失效了，refresh() 返回 false 就把它丢掉 ——
     * 宁可少一个读树来源，也不能拿旧页面的节点当现场（那会让判据看见已经不存在的输入框）。
     */
    private fun freshWhitelistRoot(): AccessibilityNodeInfo? {
        val node = lastWhitelistSource ?: return null
        val alive = runCatching { node.refresh() }.getOrDefault(false)
        if (!alive) {
            lastWhitelistSource = null
            return null
        }
        return rootOf(node)
    }

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
        noteTreeSize(nodes.size)
        val owner = runCatching { roots.first().packageName?.toString() }.getOrNull()

        // 判据全部按「内容区」算，不按整块屏幕：小窗 / 分屏时应用只占屏幕一角，
        // 按屏幕比例算会得出「下半屏没有输入框」，于是认不出聊天对象（用户 m14582 报的
        // 「QQ / 微信小窗下识别不了」）。内容区由 applicationRoots() 每次读树时算好。
        val area = contentArea()
        val titleZone = area.top + (area.height() * TITLE_ZONE_RATIO).toInt()
        val maxCenterX = area.left + (area.width() * TITLE_MAX_CENTER_RATIO).toInt()
        // 无论成不成，都把标题区摊开写进日志 —— 认错名字的锅就靠它来定位。
        val census = censusOf(nodes, titleZone)
        val windowInfo = windowSummary()

        // 判据一：内容区下半部分得有输入框 —— 这是聊天页才有的结构。
        if (!hasInputBelow(nodes, area.top + (area.height() * INPUT_TOP_RATIO).toInt())) {
            SendLog.d(
                "读标题",
                "下半屏没找到输入框，判定不在聊天页 根=" + owner + " nodes=" + nodes.size + " wins=" +
                    roots.size + " " + windowInfo + " area=" + area.flattenToString() + "；标题候选：" + census
            )
            return null
        }

        // 活动窗口标题（有些 App 只在这里给出聊天对象名）优先，它最不容易认错。
        // 但兼容模式关掉时读不到它（那要枚举窗口）—— 那就只靠下面两条。
        val winTitle = plausibleWindowTitle()
        if (winTitle != null) {
            SendLog.d("读标题", "命中 " + SendLog.mask(winTitle) + " 来源=活动窗口标题；标题候选：" + census)
            return winTitle
        }

        // 微信聊天页不暴露标题，但每条消息的头像带着「昵称头像」——从内容区左半边那个头像取名字。
        val avatarName = partnerNameFromAvatars(nodes, area)
        if (avatarName != null) {
            SendLog.d("读标题", "命中 " + SendLog.mask(avatarName) + " 来源=对端头像 " + avatarCensus(nodes, area))
            return avatarName
        }

        // 判据二：聊天对象名是内容区顶部那条窄带里最靠上的短文本。
        val picked = pickTitle(nodes, area.top, titleZone, maxCenterX)
        SendLog.d(
            "读标题",
            (if (picked != null) "命中 " + SendLog.mask(picked) else "没认出来") +
                " 根=" + owner + " nodes=" + nodes.size + " wins=" + roots.size + " " + windowInfo +
                " 事件文本=" + SendLog.mask(lastWindowText) + "；标题候选：" + census +
                "；头像：" + avatarCensus(nodes, area)
        )
        if (picked != null) return picked

        // 兜底：微信的换页事件常常直接带着聊天对象名。只在名字像样时才敢用。
        val fallback = lastWindowText?.takeIf { looksLikeName(it) }
        if (fallback != null) SendLog.w("读标题", "改用窗口事件文本兜底：" + fallback)
        return fallback
    }

    /**
     * 剪贴板路线：把剪贴板里的图片粘进当前聊天并发送。
     *
     * 之所以值得一试：目的地就是眼前这个会话，既不用认人、也没有「选人页」，
     * 群聊同样适用。代价是微信可能根本不认图片剪贴板 —— 那就原样退回去用分享。
     * 安全底线：只有确实看到输入区多出东西才点「发送」，绝不盲点。
     */
    private suspend fun doPasteIntoChat(gen: Long): PasteOutcome {
        val before = collectAllReady()
        // v0.2.218：读树之前先看一眼这一轮还算不算数（用户又点了一次 / 已经退出当前应用）。
        if (shouldStop(gen, foregroundPackage, "粘贴")) return PasteOutcome.CANCELLED
        // 判据按内容区算：小窗 / 分屏时输入框不在「屏幕下半部分」（用户 m14582）。
        val area = contentArea()
        val inputMinTop = area.top + (area.height() * INPUT_TOP_RATIO).toInt()
        // 下面几处量图 / 量带子都以它为基准，所以名字仍叫 screenWidth（值是内容区宽度）。
        val screenWidth = area.width()
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
                " nearImg=" + beforeImages + " wins=" + windows.orEmpty().size +
                " " + windowSummary()
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
                pressBox.right + "," + pressBox.bottom + " nearImg=" + pressImages +
                " wins=" + windows.orEmpty().size + " " + windowSummary()
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
            "菜单候选：" + (if (entries.isEmpty()) "(没读到文本)" else entries.joinToString(" / ")) +
                " wins=" + windows.orEmpty().size + " " + windowSummary()
        )
        if (pasteNode == null) {
            // 一条候选文本都读不到 = 菜单压根没弹出来；读到别的项才叫「有菜单但没有粘贴」。
            val outcome = if (entries.isEmpty()) MenuOutcome.NOT_SHOWN else MenuOutcome.NO_PASTE
            SendLog.w("粘贴", "菜单里没有「粘贴」 outcome=" + outcome)
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
            SendLog.d("粘贴", "不按返回：页面上已无输入框，按了会退出聊天")
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
                            "预览小窗已消失、输入框已回来 ⇒ 图已发出，不退回分享"
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
                "输入区没多出图片，但见过" + (if (sawPopup) "预览小窗" else "输入区里的图") +
                    " ⇒ 用户已先发出，不退回分享"
            )
            return PasteOutcome.ALREADY_SENT
        }
        SendLog.d(
            "粘贴",
            // 输入框里可能就是用户刚写的草稿，打码后再写（emc-1-028）。
            "输入区没多出图片，放弃 输入框文字=" + SendLog.mask(input.text?.toString())
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
            SendLog.d("粘贴", spot + "上有「" + label + "」" + at + "，设置里关了最后一步自动确认，交给用户点")
            return PasteOutcome.STAGED
        }
        val ok = click(send)
        SendLog.d("粘贴", "点" + spot + "的「" + label + "」" + at + " = " + ok)
        if (!ok) return PasteOutcome.STAGED
        delay(PASTE_SEND_CHECK_MS)
        val after = collectAll()
        SendLog.d(
            "粘贴",
            "点发送后 " + PASTE_SEND_CHECK_MS + "ms 复查：" +
                (if (hasInputBelow(after, inputMinTop)) "输入框回来了" else "输入框仍不在树里") +
                " wins=" + windows.orEmpty().size + " " + windowSummary()
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
    /**
     * v0.2.218：把「+」点开 —— 一招一招试，每招之后回头看一眼面板有没有真的长出来。
     *
     * 为什么要有这一套：用户 m16723 / m16984 的日志里，点「+」这一下微信既不认无障碍点击
     * （performAction 返回 true、面板没动），也不认正中间的注入手势（「系统报告手势已执行完」，
     * 文案还是「更多功能按钮，已折叠」）—— 而同一份日志里点面板入口、点格子都点得动，
     * 说明不是「注入整个不行」，是这一下没落到微信处理它的地方。所以这里把能想到的几招摆上，
     * 谁成谁上，并把成的那一招写进日志：下次再出问题不用再猜。
     *
     * 每招都给满 [ALBUM_PANEL_WAIT_MS] 再换下一招，而且**换招之前先看面板**：
     * 小窗下面板动画实测要 2.8 秒（用户 m15649），等超时≠没打开，而面板开着的时候
     * 再点一下「+」就是把它关掉。
     *
     * 返回面板入口（成功）或 null（这一轮没打开）。
     */
    private suspend fun openPlusPanel(
        page: List<AccessibilityNodeInfo>,
        inputBox: Rect,
        inputTop: Int,
        myApp: String?,
        gen: Long
    ): AccessibilityNodeInfo? {
        var nodes = page
        var bar = inputBox
        val order = if (myApp == PACKAGE_WECHAT) PLUS_ORDER_WECHAT else PLUS_ORDER_DEFAULT
        for ((i, name) in order.withIndex()) {
            if (shouldStop(gen, myApp, "相册")) return null
            // 每一招之前都重新量一次输入栏：上一招要是把键盘叫出来了，输入栏连着「+」都上移了。
            bar = inputBoxNode(nodes, inputTop)?.let { boundsOf(it) } ?: bar
            // 动手之前再看一眼面板：上一招其实已经把它点开了的话，这一下就是关掉它。
            albumEntryNode(nodes, bar, ALBUM_PANEL_OPEN_TEXTS)?.let {
                SendLog.d("相册", "面板其实已经开了（「" + titleTextOf(it)?.trim() + "」），不再点「+」")
                return it
            }
            val plus = plusButtonNode(nodes, bar)
            if (plus == null) {
                SendLog.w("相册", "开「+」第 " + (i + 1) + " 招之前找不到「+」按钮了，收手")
                return null
            }
            val r = boundsOf(plus)
            val fired = when (name) {
                "无障碍点击" -> runCatching {
                    plus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)

                "手势点中心" -> tapNode(r, 0.5f, "点「+」")

                // 点在「+」的上沿：屏幕最底下那一条（系统手势区）有时会把落进去的触摸吃掉，
                // 往里挪一点仍然落在同一个控件里，但避开了那条带子。
                "手势点上沿" -> tapNode(r, 0.25f, "点「+」上沿")

                // 先让输入框拿到焦点（键盘弹出来、输入栏连着「+」一起上移），再点新位置上的「+」。
                else -> focusThenTapPlus(nodes, inputTop)
            }
            delay(ALBUM_PANEL_DELAY_MS)
            val entry = waitAlbumEntry(bar, gen = gen, myApp = myApp)
            // v0.2.220：一招一行 —— 原来「已发出」「不吃无障碍点击」「没打开面板」分三行写，
            // 同一件事拆成三行只是让日志变长（用户 m17288）。
            SendLog.d(
                "相册",
                "开「+」" + (i + 1) + "/" + order.size + "「" + name + "」fired=" + fired +
                    (if (!fired && name == "无障碍点击") " clickable=" + plus.isClickable + " enabled=" + plus.isEnabled else "") +
                    " @" + r.flattenToString() + " → " +
                    (if (entry != null) "面板已开" else "面板没开，文案=" + (plusDesc() ?: "读不到"))
            )
            if (entry != null) return entry
            nodes = collectAll()
        }
        val plus = plusButtonNode(nodes, bar)
        SendLog.w(
            "相册",
            order.size.toString() + " 招都没打开「+」面板（" + order.joinToString(" / ") + "）。「+」现在：" +
                (plus?.let { describeNode(it) } ?: "读不到") +
                "；输入栏现场：" + dumpBar(nodes, bar, foregroundPackage)
        )
        return null
    }

    /**
     * 先给输入框下发一次点击（键盘弹出来、输入栏连着「+」一起上移），再点新位置上的「+」。
     *
     * 这一招冲的是「屏幕最底下那一条被系统吃掉」：同一份代码在「+」位于 y≈2280 时点不动，
     * 而别的页面位置都点得动（用户 m16984）。日志里把输入框上移了多少写出来 ——
     * 没上移就说明输入框那一下也没生效，下一次不用再在这个方向费时间。
     */
    private suspend fun focusThenTapPlus(nodes: List<AccessibilityNodeInfo>, inputTop: Int): Boolean {
        val box = inputBoxNode(nodes, inputTop) ?: return false
        val before = boundsOf(box)
        val focused = runCatching {
            box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        delay(ALBUM_PANEL_DELAY_MS + 400L)
        val after = collectAll()
        val boxAfter = inputBoxNode(after, inputTop)?.let { boundsOf(it) }
        SendLog.d(
            "相册",
            "先点输入框：action=" + focused + " 输入框 " + before.flattenToString() + " → " +
                (boxAfter?.flattenToString() ?: "读不到") +
                "（上移 " + (before.top - (boxAfter?.top ?: before.top)) + "px）"
        )
        val plus = plusButtonNode(after, boxAfter ?: before) ?: plusButtonNode(after, before) ?: return false
        return tapNode(boundsOf(plus), 0.5f, "点「+」（输入栏上移之后）")
    }

    /**
     * v0.2.218 检查点：这一轮还该继续吗。
     *
     * 返回 true = 收手。两个来源：① 用户又点了一次表情（请求号变了，这一轮作废）；
     * ② 他已经自己离开当前应用（回桌面 / 切走）。用户 m16984 的复测里，他手动退出微信之后
     * 我们还在原地跑满 8 秒的「点会话」，看起来就像「手动退出没生效」。
     */
    private fun shouldStop(gen: Long, myApp: String?, tag: String): Boolean {
        val why = when {
            stale(gen) -> "用户又点了一次，这一轮作废"
            userLeftApp(myApp) -> "用户已经离开聊天应用（现在前台 " + (foregroundPackage ?: "?") + "）"
            else -> return false
        }
        SendLog.d(tag, "收手：" + why + "；这一轮不发了，也不退回分享")
        return true
    }

    /**
     * 用户是不是已经离开我们驱动的那个应用了（回桌面 / 切到别的 App）。
     *
     * 只认「前台是桌面」这一条，别的都不算：读不到包名（窗口正在切）、还在白名单里、
     * 是系统界面（systemui / android）都按「还在」处理 —— 宁可多跑一步，
     * 也不要因为一次读不到就把流程半路掐掉。权限弹窗那种系统窗口也因此不会误伤。
     */
    private fun userLeftApp(myApp: String?): Boolean {
        val now = foregroundPackage ?: return false
        if (now == myApp) return false
        if (now in WATCHED_PACKAGES) return false
        if (now == "android" || now == "com.android.systemui") return false
        val home = runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull()
        if (home != null) return now == home
        // 取不到桌面包名（Android 13+ 的包可见性）时只认读得出来的几个常见写法。
        return now == "com.miui.home" || now.endsWith(".launcher") || now.contains("launcher")
    }

    /** 在节点矩形的竖向上按比例取一个点，注入一次轻点。 */
    private fun tapNode(r: Rect, fy: Float, what: String): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        return gestureAt(r.exactCenterX(), r.top + r.height() * fy, TAP_MS, what)
    }

    /** 一个节点的一句话画像：文案 / 类名 / 可点 / enabled / 可见 / 包名 / 边界 / 窗口。 */
    private fun describeNode(node: AccessibilityNodeInfo): String =
        "文案=" + (titleTextOf(node)?.trim() ?: "-") + " cls=" + shortClass(node) +
            " click=" + node.isClickable + " enabled=" + node.isEnabled +
            " visible=" + node.isVisibleToUser + " pkg=" + node.packageName +
            " @" + boundsOf(node).flattenToString() + " win=" + runCatching { node.windowId }.getOrDefault(-1)

    /** 「+」此刻的无障碍文案（微信写「更多功能按钮，已折叠 / 已展开」）。读不到返回 null。 */
    private fun plusDesc(): String? = collectAll()
        .firstOrNull { titleTextOf(it)?.trim().orEmpty().startsWith(PLUS_CD) }
        ?.let { titleTextOf(it)?.trim() }

    /** 两格之间差多少分钟（绝对值）。 */
    private fun absMinutes(a: LocalDateTime, b: LocalDateTime): Long =
        kotlin.math.abs(Duration.between(a, b).toMinutes())

    /**
     * v0.2.219：格子现场照 —— 把**落在网格那条带子里**的节点全列出来（按位置排，不按树序），
     * 连尺寸、能不能点、以及是不是我们刚写进去的那张（名字对得上就打 ★）一起记。
     *
     * 为什么改成全列：v0.2.218 那行只印「进池的 16 格 + 没进池的大块头」，用户 m17147 的日志里
     * 我们刚写的 Emojichan.gif 两处都没出现 —— 屏幕顶上那个位置（第一行最左）到底有没有节点、
     * 是什么节点，根本没记下来，于是只能猜。这一行现在把带子里每个节点连同 WxH 一起摊开，
     * 「格子去哪儿了」当场就能看出来。
     */
    private fun cellDump(
        band: List<AccessibilityNodeInfo>,
        pool: List<AccessibilityNodeInfo>,
        mine: String?
    ): String {
        // v0.2.220：只列「带文案的格子」，容器一概不列 —— 网格带里一半是 RecyclerView / ViewPager
        // 这类 1080×1925 的大块头，位置和文案都对不上任何一格，白占地方（用户 m17288：一行 1.4 KB）。
        val cells = band.filter { titleTextOf(it)?.trim().orEmpty().isNotEmpty() }
        val mineCells = cells.filter { !mine.isNullOrEmpty() && titleTextOf(it)?.trim() == mine }
        val shown = (mineCells + cells.sortedBy { cellRank(it) })
            .distinctBy { cellRank(it) }.take(CELL_DUMP_LIMIT)
        val line = shown.joinToString(" / ") { node ->
            val r = boundsOf(node)
            val text = titleTextOf(node)?.trim().orEmpty()
            (if (text == mine) "★" else "") + shortClass(node) + "[" + text + "]@" + r.top + "," + r.left +
                " " + r.width() + "x" + r.height() + (if (node.isClickable) " c" else "")
        }
        return "网格带 " + band.size + " 个（进池 " + pool.size + "，带字 " + cells.size +
            "，列 " + shown.size + "）：" + (if (line.isEmpty()) "空" else line)
    }

    private suspend fun doAlbumRoute(gen: Long): AlbumOutcome {
        var page = collectAllReady()
        // v0.2.218：开跑前先看一眼这一轮还算不算数，顺带记住我们驱动的是哪个应用 ——
        // 之后每一步都会用 [shouldStop] 收手（用户又点了一次 / 他已经回桌面走人）。
        val myApp = foregroundPackage
        if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
        // 判据与几何全按内容区算：小窗 / 分屏下应用只占屏幕一角（用户 m14582）。
        // 下面几处沿用 screenWidth 这个名字，值是内容区的宽度。
        val area = contentArea()
        val inputTop = area.top + (area.height() * INPUT_TOP_RATIO).toInt()
        val screenWidth = area.width()
        var box = inputBoxNode(page, inputTop)
        // v0.2.219：上一张表情还停在应用的选图 / 预览页上时（用户点了球、图选好了却没点发送），
        // 聊天页的输入框读不到 —— 那时退回分享会把这一页盖掉、再拉一层分享面板（用户 m17147
        // 的第二次：什么都没做成，反而多弹了一次分享）。所以先按返回把它收回去，再找输入框。
        // 判据卡在「选图页的标记（原图 / 预览 / 编辑）还在」上：只有站在应用的选图 / 预览页才按，
        // 聊天页（没有输入框的那些页面也一样）一律不按。
        var backs = 0
        while (box == null && backs < ALBUM_BACKOUT_MAX && stillOnPickerPage(page)) {
            backs++
            SendLog.d(
                "相册",
                "上一张还停在选图 / 预览页（选图页标记还在、读不到输入框）：按返回收回去（第 " + backs + " 次）"
            )
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(ALBUM_PICKER_DELAY_MS)
            if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
            page = collectAll()
            box = inputBoxNode(page, inputTop)
        }
        if (box == null) {
            SendLog.w("相册", "放弃：下半屏没找到输入框，不在聊天页")
            return AlbumOutcome.FAILED
        }
        val inputBox = boundsOf(box)

        // ① 输入栏最右边那个「+」。面板已经开着就别再点了 —— 那一下是把它关掉。
        //    v0.2.218：点「+」改成一招一招试，见 [openPlusPanel]。v0.2.217 只试了两下
        //    （无障碍点击 + 正中间的注入手势），用户 m16984 的复测里微信两下都不认，
        //    于是又退回分享 —— 而同一份日志里点面板入口、点格子都点得动。
        var entryCandidate: AccessibilityNodeInfo? = null
        val albumShown = albumEntryNode(page, inputBox, ALBUM_PANEL_OPEN_TEXTS)
        if (albumShown != null) {
            SendLog.d(
                "相册",
                "「+」面板已开着 \"" + titleTextOf(albumShown)?.trim() +
                    "\"@" + boundsOf(albumShown).flattenToString() + "，跳过点「+」"
            )
            entryCandidate = waitAlbumEntry(inputBox, gen = gen, myApp = myApp)
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
            entryCandidate = openPlusPanel(page, inputBox, inputTop, myApp, gen)
            if (entryCandidate == null && shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
        }

        // ② 「+」面板里的相册入口（微信写「相册」，QQ 写「图片」）
        //    v0.2.213：改成等它长出来再认 —— 小窗下面板动画要 2 秒上下，早看的那一眼里
        //    只有聊天消息（用户 m15649）。v0.2.217：这一步挪进 ① 的循环里，点一次看一次。
        val entry = entryCandidate
        if (entry == null) {
            if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
            val panel = collectAll()
            SendLog.d(
                "相册",
                "等了 " + ALBUM_PANEL_WAIT_MS + "ms 也没看到相册入口（在找「" + ALBUM_ENTRY_TEXTS.joinToString(" / ") + "」）。现场：" +
                    dumpRows(
                        panel, foregroundPackage, inputBox.top - ALBUM_PANEL_BAND_PX,
                        raw = true, bounds = false, limit = 10
                    )
            )
            exitAlbumPage(inputTop)
            return AlbumOutcome.FAILED
        }
        val entryLabel = titleTextOf(entry)?.trim().orEmpty()
        SendLog.d("相册", "点面板入口「" + entryLabel + "」@" + boundsOf(entry).flattenToString())
        if (!click(entry)) {
            SendLog.w("相册", "点面板入口「" + entryLabel + "」没成功")
            exitAlbumPage(inputTop)
            return AlbumOutcome.FAILED
        }
        delay(ALBUM_PICKER_DELAY_MS)

        // ②′ 二级入口：QQ 的「图片」进去以后还是个菜单，得再点一次「相册」。
        //     只在第一下点的不是「相册」本身时才补这一下 —— 微信点「相册」直接进选择器，不用多点。
        if (entryLabel != ALBUM_ENTRY_TEXT) {
            // v0.2.213：这一层菜单也是画出来的，同样等一等（比第一层短：它就在同一个面板里）。
            // v0.2.216：等的同时盯住「是不是已经站在选图页上了」——QQ 的「照片」是直进图库的，
            // 没有第二层。原来固定等满 1200ms，用户 m16559 的日志里每次白等 1.2 秒。
            val second = waitAlbumEntry(
                inputBox, ALBUM_ENTRY_SECOND_TEXTS, ALBUM_PANEL_POLL_MS * 3, stopOnPickerPage = true
            )
            if (second != null) {
                SendLog.d(
                    "相册",
                    "二层入口=" + titleTextOf(second)?.trim() + " @" + boundsOf(second).flattenToString()
                )
                click(second)
                delay(ALBUM_PICKER_DELAY_MS)
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
        // v0.2.213：先确认「真的站在选择页上」。入口那一下要是没点对（面板还没画出来就点、
        // 或者点到了聊天里的图），我们还在聊天页上 —— 这时绝不能去「找格子」：聊天里的图片
        // 消息一样是又大又能点的图片节点，点下去就是点到刚发出去的那条（用户 m15649 小窗实测）。
        //    判据要卡死「就是我们刚才那个输入框回来了」：相册选择页顶上也常有个搜索框，
        //    只要「有可编辑节点」就算数的话，正常进到选择页也会被误判成没进去。
        val boxNow = inputBoxNode(picker, inputTop)?.let { boundsOf(it) }
        val backToSameInput = boxNow != null &&
            boxNow.centerY() - inputBox.centerY() in -inputBox.height() until inputBox.height()
        if (backToSameInput && !stillOnPickerPage(picker)) {
            SendLog.w(
                "相册",
                "还没进到选图页：输入框还在 @" + boxNow!!.flattenToString() +
                    "，选图页标记一个都没有 ⇒ 没把图交给应用，退回分享"
            )
            return AlbumOutcome.FAILED
        }
        if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
        // ③′ v0.2.219：认我们那一格 —— 先认名字（我们刚写进相册的那个文件名），认不出才按时间。
        //     缩略图是后到的（用户 m17147 那次进选图页 1.5 秒时一个格子都还没有），所以边等边认；
        //     等的时候只看名字、不再刷现场日志。
        val mine = AlbumPublish.lastSlotName()
        var grid = picker
        var cell = firstAlbumCell(grid, area, mine)
        if (cell == null && !mine.isNullOrEmpty()) {
            var waitedCell = 0L
            while (cell == null && waitedCell < ALBUM_CELL_WAIT_MS) {
                if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
                delay(ALBUM_CELL_POLL_MS)
                waitedCell += ALBUM_CELL_POLL_MS
                grid = collectAll()
                cell = firstAlbumCell(grid, area, mine, quiet = true)
            }
            if (cell != null) {
                SendLog.d("相册", "等了 " + waitedCell + "ms 才等到我们那一格（名字=" + mine + "）")
            }
        }
        if (cell == null) {
            // v0.2.220：这里原来还要再摊一次整页现场（16 个节点）—— 上面 [cellDump] 已经把
            // 带文案的格子列出来了，这一份是重复的（用户 m17288）。
            // 选图页留在屏幕上交给用户自己点：把它盖掉再拉一层分享面板只会更乱（用户 m17147）。
            SendLog.w("相册", "把选图页留给用户：不点、也不退回分享")
            return AlbumOutcome.MANUAL
        }
        SendLog.d("相册", "点我们那一格@" + boundsOf(cell).flattenToString())
        if (!click(cell)) {
            SendLog.w("相册", "点第一格图没成功")
            exitAlbumPage(inputTop)
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
                "第一拍 " + waited + "ms 没看到确认键，继续等：选图标记=" +
                    stillOnPickerPage(preview) + " 聊天页=" + chatPageIsBack(preview, inputBox, inputTop)
            )
        }
        while (send == null && waited < ALBUM_PREVIEW_DELAY_MS + ALBUM_SEND_TIMEOUT_MS) {
            if (shouldStop(gen, myApp, "相册")) return AlbumOutcome.CANCELLED
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
                    "聊天页输入栏已回来、选图标记已消失 ⇒ 图已发出，不退回分享"
                )
                return AlbumOutcome.ALREADY_SENT
            }
            if (!stillOnPickerPage(preview)) {
                SendLog.w("相册", "页面换了、又认不出是不是聊天页：既不点，也不退回分享")
                return AlbumOutcome.UNCERTAIN
            }
            exitAlbumPage(inputTop)
            return AlbumOutcome.FAILED
        }
        val label = titleTextOf(send)?.trim().orEmpty()
        if (!SenderPrefs.autoConfirmLastStep(this)) {
            // 用户关掉了「最后一步自动确认」：图已经选好停在预览页，让他自己点那一下。
            SendLog.d("相册", "按设置停在最后一步：有「" + label + "」，交给用户点")
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
        val hits = mutableListOf<AlbumHit>()
        val tooBig = mutableListOf<String>()
        for (node in nodes) {
            val text = titleTextOf(node)?.replace(" ", "").orEmpty()
            if (text.isEmpty() || text !in texts) continue
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) continue
            if (inputBox != null && !inPanelBand(r, inputBox)) continue
            // v0.2.213：聊天里的图片消息（无障碍文案也是「图片」）是又大又能点的一块；
            // 面板图标都是小方块。尺寸这一条把它挡在门外（详见 [ALBUM_ENTRY_MAX_PX]）。
            if (r.width() > ALBUM_ENTRY_MAX_PX || r.height() > ALBUM_ENTRY_MAX_PX) {
                tooBig.add(text + "@" + r.flattenToString())
                continue
            }
            hits.add(AlbumHit(text, node, r, hasScrollableAncestor(node)))
        }
        if (hits.isEmpty()) {
            // 只记一行，而且限流：等面板的时候每 [ALBUM_PANEL_POLL_MS] 轮询一次，不能刷屏。
            if (tooBig.isNotEmpty()) {
                val now = SystemClock.uptimeMillis()
                if (now - lastEntrySkipLogAt >= 1500L) {
                    lastEntrySkipLogAt = now
                    SendLog.d(
                        "相册",
                        "面板入口还没出现（略过 " + tooBig.size + " 个大块头，像是聊天里的图：" +
                            tooBig.joinToString(" / ") + "）"
                    )
                }
            }
            return null
        }
        SendLog.d(
            "相册",
            "入口候选 " + hits.size + " 个：" + hits.joinToString(" / ") { it.describe() }
        )
        // v0.2.205 这里曾加过一票否决：「候选全在可滑动容器里」就不点 —— 实测是错的。
        // QQ 的「+」面板本身就是个可滑动的翻页容器，面板里的图标照样带可滑动祖先，
        // 于是真面板项被整片拒绝（用户 m14993：点了「+」却报「候选全在聊天消息列表里」）。
        // 现在只按文案优先级挑，「在不在可滑动容器里」降级成同档次的次要判据：
        // 聊天里图片的无障碍文案就是「图片」（见 [IGNORED_MEDIA]），优先级永远低于「照片」「相册」。
        return hits.minWith(
            compareBy(
                { albumEntryRank(it.text) },
                { if (it.inChat) 1 else 0 },
                { it.rect.width().toLong() * it.rect.height() }
            )
        ).node
    }

    /**
     * v0.2.213：等「+」面板长出来，再认出它的相册入口。
     *
     * 面板是画出来的：点完「+」之后它要过一会儿才进树，小窗下尤其慢（实测 2.8 秒，用户 m15649）。
     * 等的时候只认「像面板入口」的候选（小方块、不在输入框那一排上），聊天里的大图进不来 ——
     * 所以等不到就是真的没等到，会落回分享路线，不会瞎点。
     */
    private suspend fun waitAlbumEntry(
        inputBox: Rect,
        texts: Set<String> = ALBUM_ENTRY_TEXTS,
        timeoutMs: Long = ALBUM_PANEL_WAIT_MS,
        stopOnPickerPage: Boolean = false,
        gen: Long = 0L,
        myApp: String? = null
    ): AccessibilityNodeInfo? {
        var waited = 0L
        while (true) {
            // v0.2.218：等的过程里也看一眼这一轮还算不算数（用户又点了一次 / 已经离开应用）。
            // 这里不写日志：调用方拿到 null 会自己再问一次 [shouldStop]，把原因记在它那边。
            if (gen != 0L && (stale(gen) || userLeftApp(myApp))) return null
            val nodes = collectAll()
            albumEntryNode(nodes, inputBox, texts)?.let { return it }
            // v0.2.216：已经站在选图页上 = 这一层不存在（QQ 的「照片」直进图库），别再等满超时。
            if (stopOnPickerPage && stillOnPickerPage(nodes)) return null
            if (waited >= timeoutMs) return null
            delay(ALBUM_PANEL_POLL_MS)
            waited += ALBUM_PANEL_POLL_MS
        }
    }

    /**
     * 相册入口的一个候选。注意 [inChat]（有可滑动祖先）**分不清聊天消息和面板图标**：
     * 聊天消息在消息列表里，QQ 的「+」面板图标也在它自己的翻页容器里 —— 只能当次要判据。
     */
    private data class AlbumHit(
        val text: String,
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val inChat: Boolean
    ) {
        fun describe(): String = text + (if (inChat) "(可滑动)" else "(直连)") + "@" + rect.flattenToString()
    }

    /** 面板入口的文案优先级：微信叫「相册」，QQ 叫「照片」/「图片」，越靠前越可信。 */
    private fun albumEntryRank(text: String): Int = when (text) {
        "相册", "本地相册", "手机相册", "从相册选择" -> 0
        "照片" -> 1
        "图片" -> 2
        else -> 3
    }

    /**
     * 「+」面板落在哪：**输入栏下面那一块**（键盘的位置 —— 键盘弹起时输入栏被顶上去，面板就在它下方），
     * 或者**输入栏上面一个面板高度以内**。输入栏那一排自己不算，免得把「表情」「+」认成入口。
     */
    private fun inPanelBand(r: Rect, inputBox: Rect): Boolean {
        if (inInputBoxRow(r, inputBox)) return false
        if (r.top >= inputBox.bottom) return true
        return r.bottom <= inputBox.bottom && r.top >= inputBox.top - ALBUM_PANEL_BAND_PX
    }

    /**
     * 和输入框**本体重叠**的那一条（输入栏那一排自己：「表情」「+」都在这条里）。
     *
     * v0.2.213 之前这里用的是 [inInputBarBand]（输入框上下各放宽 0.75 个输入框高）。全屏下够用，
     * 小窗下会把真面板整片误杀：实测输入框 y1474-1557、面板标签在 y1445，只差 29 像素，
     * 落在那条 62 像素的余量里，于是「相册」被判成输入栏那一排、整片面板一个候选都不剩，
     * 只剩下聊天里那条图片消息（用户 m15649）。面板本来就紧贴输入框，余量不能这么给。
     */
    private fun inInputBoxRow(r: Rect, inputBox: Rect): Boolean =
        r.centerY() in inputBox.top..inputBox.bottom

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

    /**
     * 相册里该点的那一格。
     *
     * v0.2.219 换成「**先认名字**，名字认不出才按时间，都对不上就返回 null」：
     *  · 名字 = [AlbumPublish.lastSlotName]，也就是我们刚写进相册的那个文件名。微信 / QQ 的
     *    选图页把文件名写在每一格的无障碍文案里（用户 m17147 的日志：ImageView[Emojichan.png]、
     *    ImageView[Screenshot_2026-10-15-19-36-14-652_tv.danmaku.bili.jpg]），这是这件事里
     *    唯一确定的事实 —— 名字对得上，格子就是我们的。
     *  · v0.2.218 那套「按时间挑」为什么会挑错：格子的文案就是文件名，文件名里的日期被
     *    [cellStamp] 当成了「今天」甚至「此刻」，于是我们在几张今天的截图里挑了最靠上最左的
     *    那一张（用户 m17147：他看到的第三张）。所以时间这条退路现在只认**真的写出了时分**、
     *    而且跟我们写盘时刻对得上的（±[CELL_STAMP_MATCH_MIN] 分钟）。
     *  · 两条都不中 → null。红线还是那句：宁可让用户多点一下，也不能把别人的图发出去。
     */
    private fun firstAlbumCell(
        nodes: List<AccessibilityNodeInfo>,
        area: Rect,
        mine: String?,
        quiet: Boolean = false
    ): AccessibilityNodeInfo? {
        val minSize = (area.width() * ALBUM_CELL_MIN_RATIO).toInt()
        // 顶上的标题栏按内容区算：小窗 / 分屏时应用不占满屏，按屏高算那条线会跑到窗口外面去。
        val topLine = area.top + (area.height() * ALBUM_CELL_TOP_RATIO).toInt()
        // 网格那条带子里的所有节点（「拍摄」那一格也留着，只用来打日志）。
        //   中心点必须落在内容区里：上一次点到的是图片查看器里 x=-478 那种整屏翻页的节点，
        //   点击直接失败（用户 m14898）。
        val band = nodes.filter {
            val r = boundsOf(it)
            r.width() >= minSize && r.height() >= minSize && r.top > topLine &&
                area.contains(r.centerX(), r.centerY())
        }
        val big = band.filter { !looksLikeCameraTile(it) }
        val images = big.filter { shortClass(it).contains("Image") }
        val pool = if (images.isNotEmpty()) images else big.filter { it.isClickable }
        if (!quiet) {
            SendLog.d(
                "相册",
                "格子候选 " + pool.size + " 个（图片类 " + images.size + " 个，网格带 " + band.size + " 个）"
            )
            SendLog.d("相册", cellDump(band, pool, mine))
        }
        if (!mine.isNullOrEmpty()) {
            val named = band.filter { titleTextOf(it)?.trim() == mine }
            val hit = named.minByOrNull { cellRank(it) }
            if (hit != null) {
                SendLog.d(
                    "相册",
                    "按名字挑格子：\"" + mine + "\"（同名节点 " + named.size + " 个）@" +
                        boundsOf(hit).flattenToString()
                )
                return hit
            }
            if (!quiet) SendLog.d("相册", "网格带里没有叫 \"" + mine + "\" 的那一格")
        }
        // v0.2.220：时间要在整条网格带上找，不能只看「图片类」那一池 —— QQ 的选图页不给文件名，
        // 时间挂在 266×266 的可点 ViewGroup 上，ImageView 子节点是空的（用户 m17283 的日志）。
        val dated = band.mapNotNull { node ->
            cellStamp(titleTextOf(node)?.trim().orEmpty())?.let { it to node }
        }.filter { it.first.toLocalTime() != LocalTime.MIDNIGHT }
        val now = LocalDateTime.now()
        // 第一判据：跟刚给槽位刷的 DATE_TAKEN 对齐。QQ 把拍摄时间直接印在格子上，这一条不需要
        // 「现在」—— 选图页晚开几秒、格子晚刷新一分钟都还在窗口里。
        val written = AlbumPublish.lastStampMs()?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        } ?: now
        val byWritten = written?.let { w ->
            dated.filter { absMinutes(it.first, w) <= CELL_STAMP_WRITTEN_MIN }
                .minWithOrNull(
                    compareBy<Pair<LocalDateTime, AccessibilityNodeInfo>>(
                        { absMinutes(it.first, w) },
                        { cellRank(it.second) }
                    )
                )
        }
        // 第二判据（老规矩）：跟「现在」对得上 —— 认不出名字、格子上又没写日期的靠它。
        val byNow = dated.filter { absMinutes(it.first, now) <= CELL_STAMP_MATCH_MIN }
            .minByOrNull { cellRank(it.second) }
        val timed = byWritten ?: byNow
        if (timed != null) {
            val anchor = if (byWritten != null) written else now
            val basis = if (byWritten != null) "对写入时刻 " else "对现在 "
            SendLog.d(
                "相册",
                "按时间挑格子：" + CELL_STAMP_FMT.format(timed.first) + "（" + basis +
                    absMinutes(timed.first, anchor) + " 分钟）@" + boundsOf(timed.second).flattenToString()
            )
            return timed.second
        }
        if (!quiet) {
            SendLog.w(
                "相册",
                "认不出我们那张（名字=" + (if (mine.isNullOrEmpty()) "未知" else mine) +
                    "；带时分的格子 " + dated.size + " 个都对不上）：这次不点"
            )
        }
        return null
    }

    /** 排位：越靠上、越靠左越小（和 [firstAlbumCell] 挑第一格用的是同一个序）。 */
    private fun cellRank(node: AccessibilityNodeInfo): Int {
        val r = boundsOf(node)
        return r.top.coerceAtLeast(0) * 10000 + r.left.coerceAtLeast(0)
    }

    /**
     * v0.2.217：从格子文案里抠出拍摄时间。
     *
     * 微信 / QQ 的选择页把时间写在格子的无障碍文案里（「图片，今天 20:05」这种）。抠得出来，
     * 调用方就能按时间选「最靠现在」的那一格；抠不出来返回 null，调用方退回「最上最左」。
     */
    private fun cellStamp(text: String): LocalDateTime? {
        if (text.isEmpty()) return null
        val now = LocalDateTime.now()
        var date: LocalDate? = null
        CELL_DATE_YMD.find(text)?.let { m ->
            date = runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        if (date == null) {
            CELL_DATE_MD.find(text)?.let { m ->
                date = runCatching {
                    LocalDate.of(now.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }.getOrNull()
            }
        }
        if (date == null) {
            date = when {
                text.contains("前天") -> now.toLocalDate().minusDays(2)
                text.contains("昨天") -> now.toLocalDate().minusDays(1)
                text.contains("今天") -> now.toLocalDate()
                else -> null
            }
        }
        val day = date ?: return null
        var time = LocalTime.MIDNIGHT
        CELL_TIME_HM.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) time = LocalTime.of(h, mi)
        }
        // v0.2.220：QQ 写「16点44分」。不加这一条，QQ 每一格都会停在 00:00，被下面
        // 「没有时分就排除」的规矩全部刷掉 —— 用户 m17283 的日志里就是「带时分的格子 0 个」。
        if (time == LocalTime.MIDNIGHT) {
            CELL_TIME_CN.find(text)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h in 0..23 && mi in 0..59) time = LocalTime.of(h, mi)
            }
        }
        // v0.2.219：这里原来有个补丁 ——「只有日期、日期又是今天」就返回「此刻」。
        //    用户 m17147 的日志证明它会伤人：格子文案其实是文件名，文件名里的日期（截图、相机
        //    图片都带）被当成了今天，于是我们在一堆今天的截图里挑了一张点下去。现在判据是
        //    「文案里必须真的写出时分」，读不出时分就还是 00:00，调用方会把它排除在外。
        return LocalDateTime.of(day, time)
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

    /**
     * 按返回键退出当前页面（「+」面板 / 相册），别把用户留在半路上。
     *
     * 但**聊天页还在的时候绝不能按**：那一下会把用户从他正聊着的人那里退出去
     * （相册路线失败本来就要退回去用分享，再顺手把人踢出聊天就太难受了）。
     * 判据和粘贴路线的 dismissPasteMenu 是同一条。
     */
    private suspend fun exitAlbumPage(inputMinTop: Int) {
        if (hasInputBelow(collectAll(), inputMinTop)) {
            SendLog.d("相册", "不按返回：聊天页还在，按了会退出当前聊天")
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(INFO_BACK_DELAY_MS)
    }

    /**
     * 最近一次读树读到多少个节点。面板顶部读不到名字时用它判断「是不是树还没长出来」。
     */
    private var lastReadNodeCount = 0

    /** 上一次「树几乎是空的」这类诊断日志的时间，免得轮询时刷屏。 */
    private var lastTinyTreeLogAt = 0L

    /** v0.2.213：上一次记「入口候选里有大块头被略过」的时间，同 [lastTinyTreeLogAt] 一个道理。 */
    private var lastEntrySkipLogAt = 0L

    /** 当前应用所有窗口的节点，拍平成一个列表。 */
    private fun collectAll(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        applicationRoots().forEach { collect(it, out, 0) }
        noteTreeSize(out.size)
        return out
    }

    /**
     * 树还没长出来的那一下（应用刚被拉起 / 窗口刚切）读到的节点只有窗口根自己，
     * 判据会全部落空、白跑一趟 —— 所以「几乎空」就等一等重读。
     *
     * 只在粘贴 / 相册这两条主动路线的入口用；读标题那条是主线程上的同步读，不能在这里等。
     */
    private suspend fun collectAllReady(): List<AccessibilityNodeInfo> {
        var nodes = collectAll()
        var tries = 0
        while (nodes.size <= TREE_READY_MIN_NODES && tries < TREE_READY_RETRIES) {
            tries++
            delay(TREE_READY_DELAY_MS)
            nodes = collectAll()
        }
        if (tries > 0) {
            SendLog.d(
                "读树",
                "等树长出来：重读 " + tries + " 次后 nodes=" + nodes.size +
                    "（一直这么小 = 应用还在启动画面，或界面在小窗里、根节点没暴露）"
            )
        }
        return nodes
    }

    /** v0.2.215：反射结果缓存（「类名#方法名」→ Method）。跑在后台线程上，用并发安全的表。 */
    private val probeMethods = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

    /**
     * 反射探一个无参布尔 getter（v0.2.209 诊断用）。
     *
     * 「敏感数据」这几个读取接口（AccessibilityNodeInfo.isAccessibilityDataSensitive、
     * AccessibilityEvent.isAccessibilityDataSensitive）在 android.jar 里查得到，但 api-versions.xml
     * 里查不到落地版本 —— 直接调用有在旧系统上 NoSuchMethodError 的风险，反射拿不到就返回 null，
     * 不让诊断本身把服务拖挂。
     */
    private fun probeBool(target: Any?, method: String): Boolean? = runCatching {
        val cls = target?.javaClass ?: return null
        // v0.2.215：同一个类的同一个方法只查一次（原来每次探测都重做一遍 getMethod）。
        val key = cls.name + "#" + method
        val m = probeMethods[key] ?: runCatching { cls.getMethod(method) }.getOrNull()
            ?.also { probeMethods[key] = it }
            ?: return null
        m.invoke(target) as? Boolean
    }.getOrNull()

    /** 记下这次读树的规模；树几乎是空的时候顺手把窗口现场写进日志（诊断用）。 */
    private fun noteTreeSize(count: Int) {
        lastReadNodeCount = count
        if (count > TREE_READY_MIN_NODES) return
        val now = System.currentTimeMillis()
        if (now - lastTinyTreeLogAt < 2000L) return
        lastTinyTreeLogAt = now
        SendLog.d("读树", "树几乎是空的（" + count + " 个节点）：" + windowDiagnostics())
    }

    /**
     * 树几乎是空的时候，把窗口列表摊开写一行：应用是不是在小窗里、窗口根是不是空的，
     * 一眼就能看出来（用户在微信小窗里点悬浮球那一次，日志里只有一个根节点，谁也不知道
     * 当时到底是小窗、还是应用刚启动）。
     */
    private fun windowDiagnostics(): String {
        val listed = runCatching { windows.orEmpty() }.getOrNull()
            ?: return "（读不到窗口列表：兼容模式关着）"
        if (listed.isEmpty()) return "（窗口列表是空的）"
        return "触摸探索=" + touchExplorationEnabled() + "；窗口 " + listed.size + " 个：" +
            listed.take(6).joinToString(" ｜ ") { w ->
            val bounds = runCatching { Rect().also { w.getBoundsInScreen(it) } }.getOrNull()
            val root = runCatching { w.root }.getOrNull()
            val kind = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) "APP" else "T" + w.type
            val rootInfo = if (root == null) {
                "null"
            } else {
                "有(" + runCatching { root.childCount }.getOrDefault(-1) + "子)" +
                    " class=" + runCatching { root.className?.toString() }.getOrNull() +
                    " desc=" + runCatching { root.contentDescription?.length }.getOrDefault(-1) +
                    " 敏感=" + probeBool(root, "isAccessibilityDataSensitive") +
                    " 重要=" + probeBool(root, "isImportantForAccessibility") +
                    " 可见=" + probeBool(root, "isVisibleToUser") +
                    " id=" + runCatching { root.viewIdResourceName }.getOrNull() +
                    " 窗=" + runCatching { root.windowId }.getOrDefault(-1)
            }
            kind + ":" + (runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "-") +
                " bounds=" + (bounds?.flattenToString() ?: "?") +
                " layer=" + runCatching { w.layer }.getOrDefault(-1) +
                " active=" + runCatching { w.isActive }.getOrDefault(false) +
                " focused=" + runCatching { w.isFocused }.getOrDefault(false) +
                " root=" + rootInfo
        }
    }

    /**
     * 量图用的那条带子：在输入框的基础上往上放宽（键盘会把它顶上去）。
     *
     * 前后两次采样必须用同一条带子，数字才可比 —— 详见 [PASTE_KEYBOARD_SLACK_RATIO]。
     */
    private fun pasteBand(box: Rect): Rect {
        // 键盘把输入框顶上去的那段高度按内容区算：小窗下按整块屏幕的比例没有意义（用户 m14582）。
        val areaHeight = contentArea().height()
        return Rect(box).apply { top -= (areaHeight * PASTE_KEYBOARD_SLACK_RATIO).toInt() }
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
        // v0.2.203 起默认关掉 flagRetrieveInteractiveWindows，这时列表是空的 ——
        // 日志里要说清楚，免得看日志的人以为「这台机器上一个窗口都没有」。
        if (windows.orEmpty().isEmpty()) return "（没读窗口列表：兼容模式关闭）"
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
     * 判据统一要用的「内容区」：最近一次读树时那些根节点合起来占的屏幕矩形。
     *
     * 全屏应用下它 ≈ 整个屏幕，小窗 / 分屏下就是那个小窗口 —— 所有按比例算的判据都该用它，
     * 而不是 resources.displayMetrics（那永远是整块屏幕，小窗下比例全错，用户 m14582 报的
     * 「QQ / 微信小窗识别不了」就是这么来的）。由 [applicationRoots] 每次读树时顺手算好。
     */
    @Volatile
    private var contentArea = Rect()

    /** 内容区；还没读到过树（或量不到）时退回整块屏幕。 */
    private fun contentArea(): Rect {
        val area = contentArea
        if (area.width() > 0 && area.height() > 0) return area
        val m = resources.displayMetrics
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    /** 这些根节点合起来占的屏幕矩形；一个都量不到时按整块屏幕算。 */
    private fun unionOf(roots: List<AccessibilityNodeInfo>): Rect {
        val out = Rect()
        var any = false
        for (root in roots) {
            val b = boundsOf(root)
            if (b.width() <= 0 || b.height() <= 0) continue
            if (any) out.union(b) else { out.set(b); any = true }
        }
        if (!any) {
            val m = resources.displayMetrics
            out.set(0, 0, m.widthPixels, m.heightPixels)
        }
        return out
    }

    /**
     * 当前应用的**所有**窗口的根节点，不限窗口类型。
     * 微信会把标题栏放在单独的应用窗口里，只看一个窗口就会漏掉标题 —— 这是认错名字的主因。
     * 长按弹出的菜单、粘贴后的预览小窗也是同一个包的别的窗口。
     *
     * 三个来源合起来给：
     *   ① 窗口列表 —— 要 flagRetrieveInteractiveWindows（默认开着；低开销模式下为空）；
     *   ② 活动窗口根（rootInActiveWindow，包名对得上、且不是输入法 / 系统壳 / 我们自己）；
     *   ③ 最近一次白名单换页事件带来的窗口根（爬到顶）—— 只在 ① 为空时兜底。
     * 三者按「包名 + 位置 + 子节点数」去重：同一棵树的两个根会把节点数翻倍，而不少判据
     * （「只允许命中一行同名」「多个疑似发送就放弃」）靠的正是节点的唯一性。
     */
    private fun applicationRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        val wanted = foregroundPackage
        fun add(node: AccessibilityNodeInfo?) {
            val root = node ?: return
            val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            // 我们自己的悬浮球 / 面板：永远不算「当前界面」，否则可读区域会被压成球那么大。
            if (pkg == packageName) return
            if (wanted != null && pkg != wanted) return
            val b = boundsOf(root)
            val key = pkg + "/" + b.left + "," + b.top + "," + b.right + "," + b.bottom + "/" +
                runCatching { root.childCount }.getOrDefault(-1)
            if (!seen.add(key)) return
            out.add(root)
        }
        val listed = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        listed.forEach { w -> add(runCatching { w.root }.getOrNull()) }
        add(activeRoot())
        // 事件源那条只是「读不到窗口列表」时的兜底：否则判据会看到两份同名节点，
        // 而「只允许命中一行」「多个疑似发送就放弃」靠的正是唯一性。
        if (wanted != null) {
            val shellLike = out.sumOf { runCatching { it.childCount }.getOrDefault(0) } == 0
            if (listed.isEmpty() || shellLike) {
                val src = freshWhitelistRoot()
                if (shellLike) {
                    // v0.2.210：窗口列表不是空的，但每个根都是空壳（微信就是这种：窗口在、
                    // 根 0 子节点、class=null）。窗口这条路已经废了，事件源是最后一条线索 ——
                    // 事件是应用自己发出来的，它的 source 有时还摸得到真节点。
                    SendLog.d(
                        "读树",
                        "窗口根全是空壳（" + out.size + " 个根，子节点合计 0），试事件源：class=" +
                            runCatching { src?.className?.toString() }.getOrNull() +
                            " 子=" + runCatching { src?.childCount }.getOrDefault(-1) +
                            " 文本=" + SendLog.mask(runCatching { src?.text?.toString() }.getOrNull())
                    )
                }
                add(src)
            }
        }
        contentArea = unionOf(out)
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
        zoneTop: Int,
        zoneBottom: Int,
        maxCenterX: Int
    ): String? =
        nodes
            .mapNotNull { node ->
                val text = titleTextOf(node) ?: return@mapNotNull null
                if (!looksLikeName(text)) return@mapNotNull null
                val r = boundsOf(node)
                if (r.width() <= 0 || r.height() <= 0 || r.top < zoneTop || r.top >= zoneBottom) {
                    return@mapNotNull null
                }
                if (r.centerX() > maxCenterX) return@mapNotNull null
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
     * 对端头像永远在内容区左半边，自己头像在右半边，所以左边那个名字就是聊天对象
     * （小窗 / 分屏下就是那个小窗口的左半边）。
     * 出现多个不同名字（群聊）或一个都没有（对端还没发过消息）时，一律认输，让用户自己选。
     */
    private fun partnerNameFromAvatars(
        nodes: List<AccessibilityNodeInfo>,
        area: Rect
    ): String? {
        val found = LinkedHashSet<String>()
        for (node in nodes) {
            val name = avatarNameOf(node, area) ?: continue
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

    /** 头像节点 → 昵称；不是内容区左半边的头像就返回 null。 */
    private fun avatarNameOf(node: AccessibilityNodeInfo, area: Rect): String? {
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) return null
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return null
        if (r.centerX() > area.centerX()) return null
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
    private fun avatarCensus(nodes: List<AccessibilityNodeInfo>, area: Rect): String {
        val items = nodes.mapNotNull { node ->
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.length <= AVATAR_SUFFIX.length || !desc.endsWith(AVATAR_SUFFIX)) return@mapNotNull null
            val r = boundsOf(node)
            if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
            val side = if (r.centerX() > area.centerX()) "右" else "左"
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
        appPackage: String?,
        gen: Long
    ): ChatClick {
        val target = normalizeName(name)
        if (target.isEmpty()) return ChatClick.NO_TARGET

        // 只排除屏幕最上面那条标题栏。「下半屏有没有输入框」这条判据不能用 ——
        // 微信 / QQ 的「选择聊天」页是半透明盖在聊天页上的，聊天页的输入框还在树里，
        // 用它判断只会得出「还停在聊天页」，于是永远不去点列表（v11.5 的实测日志就是这么卡住的）。
        // 判据按内容区算（小窗 / 分屏）。先读一次树把内容区取回来 —— 顺带也是这一轮的现场。
        applicationRoots()
        val area = contentArea()
        val rowMinTop = area.top + (area.height() * ROW_MIN_TOP_RATIO).toInt()
        SendLog.d(
            "点会话",
            "开始：目标=" + SendLog.mask(target) + " 应用=" + (appPackage ?: "未知") + " 超时=" + timeoutMs + "ms"
        )
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var round = 0
        var lastSummary = ""
        // v0.2.218：见过「选择聊天」页没有。见过之后它又没了 = 用户自己退出了这一页
        // （按返回 / 点「取消」），这一轮就该停 —— 原来会一路空转到超时，再弹一句
        // 「未自动选中「X」，请在列表中选择」，可用户根本没在列表里
        // （用户 m16984：「第一次没发出去手动退出了当流程没结束」）。
        var pickerSeen = false

        while (SystemClock.uptimeMillis() < deadline) {
            round++
            // 每一轮都问一次这一轮还算不算数：用户又点了一次表情，或者已经退出聊天应用。
            if (shouldStop(gen, appPackage, "点会话")) return ChatClick.CANCELLED
            val roots = applicationRoots()
            if (roots.isEmpty()) {
                if (round == 1) SendLog.w("点会话", "第 1 轮：拿不到根节点")
            } else {
                val nodes = ArrayList<AccessibilityNodeInfo>()
                roots.forEach { collect(it, nodes, 0) }

                // 「选择聊天」页上有「取消」这个按钮；这一页消失之后它就不在了。
                val hasCancel = nodes.any { titleTextOf(it)?.trim() == PICKER_CANCEL_TEXT }
                if (hasCancel) {
                    pickerSeen = true
                } else if (pickerSeen) {
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮：「选择聊天」页已经没了 —— 用户自己退出了，这一轮到此为止"
                    )
                    return ChatClick.CANCELLED
                }

                // 用户自己点了人（或微信自己跳到确认框）时会弹「发送给：X」——
                // 这时只管把「发送」点掉，绝不能再在对话框上找名字，否则会点到图片预览之类的东西。
                val confirmSend = confirmDialogSendNode(nodes, rowMinTop)
                if (confirmSend != null) {
                    val label = titleTextOf(confirmSend)?.trim().orEmpty()
                    if (!SenderPrefs.autoConfirmLastStep(this)) {
                        // 用户关掉了「最后一步自动确认」：走到这儿就撒手，让他自己点那一下。
                        SendLog.d(
                            "点会话",
                            "第 " + round + " 轮：看到确认框（按钮 \"" + label + "\"），按设置交给用户点"
                        )
                        return ChatClick.DEFERRED
                    }
                    val ok = click(confirmSend)
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮：看到确认框，点 \"" + label + "\" ok=" + ok
                    )
                    return if (ok) ChatClick.CLICKED else ChatClick.NO_TARGET
                }

                val rows = matchRows(nodes, target, appPackage, rowMinTop)

                // 命中多行说明有重名，宁可让用户自己点 —— 但要把每一行记下来，
                // 好判断到底是真有两个同名条目，还是同一个窗口被读了两遍。
                if (rows.size > 1) {
                    SendLog.d(
                        "点会话",
                        "第 " + round + " 轮命中 " + rows.size + " 行同名，放弃 各行：" +
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
                        "第 " + round + " 轮命中 1 行 @" + r.left + "," + r.top + " 点击 ok=" + ok
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
        SendLog.e("点会话", "超时未点到：" + SendLog.mask(target))
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
        val area = contentArea()
        val minTop = area.top + (area.height() * ROW_MIN_TOP_RATIO).toInt()
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
            "复查：看到确认框，点 \"" + titleTextOf(sendNode)?.trim() + "\" ok=" + ok
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
        val area = contentArea()
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
            alongside(buttons, boundsOf(other), area) &&
            !inInputBar(buttons, area)
        if (sameRow) return target
        // ③ 只有成对按钮凑不齐时，才允许单靠「发送给：X」标题 + 按钮浮在内容区中段来判断。
        if (marker && inDialogZone(buttons, area)) return target
        return null
    }

    /** 聊天页输入栏那条带子：这上面的「发送」绝不能点。 */
    private fun inInputBar(r: Rect, area: Rect): Boolean =
        r.centerY() > area.top + area.height() * INPUT_BAR_GUARD_RATIO

    /** 确认框浮在内容区中段，不会贴着任何一条边。 */
    private fun inDialogZone(r: Rect, area: Rect): Boolean {
        val centerY = r.centerY()
        val top = area.top + area.height() * 0.12f
        val bottom = area.top + area.height() * (INPUT_BAR_GUARD_RATIO - 0.02f)
        return centerY > top && centerY < bottom
    }

    /**
     * 两个按钮是不是并排在一起（确认框里「发送」和「取消」总是挨着）。
     * 阈值必须按内容区比例算：老实现写死 1100px，在 720/1080px 屏上恒为真，等于没有判据（emc-0-005）。
     */
    private fun alongside(a: Rect, b: Rect, area: Rect): Boolean =
        Math.abs(a.centerY() - b.centerY()) < area.height() * 0.05f &&
            Math.abs(a.centerX() - b.centerX()) < area.width() * 0.6f

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
        bounds: Boolean = true,
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
                // v0.2.220：[bounds] 为假时只留文案 —— 面板现场看的是「有没有这一项」，坐标没用。
                val at = if (bounds) "@" + r.top + "[" + r.left + "-" + r.right + "]" else ""
                r.top to (shown + at)
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

    /**
     * v0.2.217：点一下节点，并且报告走了哪条路（action = 无障碍点击，gesture = 按坐标的手势）。
     *
     * 相册路线要分清「点了、应用没理」和「压根没点出去」—— [click] 两种都返回 true，
     * 出了事日志里看不出来（用户 m16723 那三次点「+」就是这样）。
     */
    private fun clickVia(node: AccessibilityNodeInfo): String? {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "action"
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return null
        return if (gestureAt(r.exactCenterX(), r.exactCenterY(), TAP_MS, "节点坐标点击")) "gesture" else null
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
