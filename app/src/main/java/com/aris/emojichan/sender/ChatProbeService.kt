package com.aris.emojichan.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aris.emojichan.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 探测用无障碍服务：**只观测，不做任何点击或手势**。
 *
 * 分两档：
 *  - 省电档（默认）：只订阅「换页面」事件，记录包名与页面类名，完全不读节点树。
 *  - 详细档：额外订阅内容变化事件，并在节流后读取当前页面的节点树。
 *
 * 白名单通过 [AccessibilityServiceInfo.packageNames] 在运行时设置，
 * 由系统在事件分发阶段过滤，而不是收到之后在代码里丢弃，因此几乎不产生额外唤醒。
 */
class ChatProbeService : AccessibilityService() {

    private var lastTreeProbeAt = 0L
    private var lastContentProbeAt = 0L
    private var lastDiagAt = 0L

    /**
     * 读树专用线程。
     *
     * onAccessibilityEvent 跑在服务主线程上，而读一棵上千节点的树是几百次跨进程调用，
     * 放在主线程里做会把整个界面拖住——上一版被感知到的卡顿就来自这里。
     */
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startWorker()
        ProbeState.onConfigChanged = { applyServiceConfig() }
        applyServiceConfig()
        ProbeState.setConnected(true)
        ProbeState.setWindowsDump(dumpWindows())
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private fun detach() {
        if (instance === this) instance = null
        ProbeState.setConnected(false)
        ProbeState.onConfigChanged = null
        stopWorker()
    }

    private fun startWorker() {
        if (worker != null) return
        val thread = HandlerThread("emoji-probe-tree")
        thread.start()
        worker = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        handler.post(heartbeat)
    }

    /**
     * 心跳：每秒钟往状态里放一个时间戳，除此之外什么也不做。
     *
     * 它存在的唯一理由是回答「进程到底有没有在跑」。如果切到微信之后心跳断了，
     * 那就是系统冻结了整个进程——事件积压、连读挤成一堆、读树时前台永远不是微信，
     * 这三个现象都只是同一个原因的不同表现。
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            ProbeState.recordHeartbeat(System.currentTimeMillis())
            workerHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun stopWorker() {
        workerHandler?.removeCallbacksAndMessages(null)
        workerHandler = null
        worker?.quitSafely()
        worker = null
    }

    /** 把白名单与订阅范围写回服务配置；档位切换时会再次调用。 */
    private fun applyServiceConfig() {
        val info = serviceInfo
        if (info == null) {
            ProbeState.setSubscribedEventTypes(getString(R.string.probe_info_unavailable))
            return
        }

        info.packageNames = WATCHED_PACKAGES.toTypedArray()
        info.eventTypes = if (ProbeState.snapshot.value.detailedMode) {
            DETAILED_EVENT_TYPES
        } else {
            LIGHT_EVENT_TYPES
        }
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        ProbeState.setWatchingPackages(WATCHED_PACKAGES)
        ProbeState.setSubscribedEventTypes(describeEventTypes(info.eventTypes))

        // 下发配置后立刻验一次能力：万一 setServiceInfo 会削掉读屏能力，
        // 这里是唯一能留下现场的地方。
        ProbeState.setDiag(
            getString(R.string.probe_diag_after_config) + "\n" + capabilityReport()
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val sourcePackage = event.packageName?.toString().orEmpty()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                ProbeState.recordPage(
                    sourcePackage,
                    event.className?.toString().orEmpty(),
                    event.windowId,
                    event.eventTime,
                    System.currentTimeMillis()
                )
                // 换页面时看一眼节点树。
                if (ProbeState.snapshot.value.detailedMode) {
                    probeTreeThrottled(sourcePackage)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                ProbeState.recordContentChanged(
                    System.currentTimeMillis(),
                    sourcePackage,
                    event.className?.toString().orEmpty(),
                    describeChangeTypes(event.contentChangeTypes),
                    event.eventTime
                )
                // 聊天页不产生换页事件，只会持续产生内容变化事件。既然读树只挂在
                // 换页上，就永远读不到聊天页——而聊天页恰恰是唯一必须认出来的页面。
                // 所以内容变化也要能触发读树，只是间隔放宽，别在打字时拖慢设备。
                if (ProbeState.snapshot.value.detailedMode) {
                    probeTreeOnContent(sourcePackage)
                }
            }

            else -> return
        }
    }

    /** 换页面时排一次读树；用固定间隔节流，避免连续切页把设备拖慢。 */
    private fun probeTreeThrottled(packageName: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTreeProbeAt < TREE_PROBE_INTERVAL_MS) return
        lastTreeProbeAt = now
        scheduleProbe(packageName, 0L, MAX_PROBE_RETRIES, "换页")
    }

    /**
     * 内容变化触发的读树。
     *
     * 间隔比换页更长：内容变化一秒能来几十次，按换页的节奏读会把系统服务与
     * 目标应用的 UI 线程一起拖住。内容本身会反复变化，所以这次读不到也不必补读。
     */
    private fun probeTreeOnContent(packageName: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastContentProbeAt < CONTENT_PROBE_INTERVAL_MS) return
        lastContentProbeAt = now
        scheduleProbe(packageName, 0L, 0, "内容变化")
    }

    /**
     * 在后台线程读一次树，读不到根时补读一次。
     *
     * 事件对象不能跨线程使用（会被系统回收，且带着跨进程句柄），所以后台一律走
     * 「活动窗口」这条路径；代价是切换页面的那一瞬间活动窗口往往还没就绪，
     * 因此第一次读失败后隔一小会儿再补一次。
     */
    private fun scheduleProbe(
        packageName: String,
        delayMs: Long,
        retriesLeft: Int,
        trigger: String
    ) {
        val handler = workerHandler ?: return
        handler.postDelayed({
            val record = probeTree(packageName, null, trigger)
            // 拿到根也可能是「壳」：窗口切换途中系统会先给一个只有容器的空树。
            // 壳同样值得补读，否则日志里就只剩一堆节点数极少的记录。
            val shell = record.rootAvailable && record.nodeCount < SHELL_NODE_LIMIT
            if ((!record.rootAvailable || shell) && retriesLeft > 0) {
                scheduleProbe(packageName, RETRY_DELAY_MS, retriesLeft - 1, "补读")
            }
        }, delayMs)
    }

    /**
     * 面板用：延时连读几次。
     *
     * 面板自己在前台时读到的永远是本应用的界面，所以想拿到微信的树，
     * 只能由用户点完按钮立刻切过去、由服务在后台到点自动读。
     */
    fun probeBurst(delayMs: Long, times: Int, intervalMs: Long) {
        val handler = workerHandler ?: return
        val target = WATCHED_PACKAGES.firstOrNull().orEmpty()
        repeat(times) { index ->
            val label = "连读#" + (index + 1)
            handler.postDelayed(
                { probeTree(target, null, label) },
                delayMs + index * intervalMs
            )
        }
    }

    /**
     * 读一次节点树并记录结果。
     *
     * 优先读「当前活动窗口」，读不到时退回事件自带的源节点——这条退路很重要：
     * 切换页面的那一瞬间活动窗口往往还没就绪，此时只有事件源是可靠的。
     * 面板的「自检」按钮也会调这里，用来验证服务本身能不能读到屏幕内容。
     */
    fun probeTree(
        packageName: String,
        event: AccessibilityEvent? = null,
        trigger: String = ""
    ): TreeRecord {
        val stamp = System.currentTimeMillis()
        val foreground = foregroundPackage()
        val (windowText, windowErr) = windowPackageSnapshot()

        // 三条取根路径逐条试、逐条记失败原因：日志里光写「根节点不可读」没用，
        // 必须能区分是服务被削掉了能力，还是窗口列表本身就不给根节点。
        val reasons = ArrayList<String>()
        var rootSource = ""

        var root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            reasons.add(getString(R.string.probe_root_active_failed, e.javaClass.simpleName))
            null
        }
        if (root != null) rootSource = getString(R.string.probe_root_active)

        if (root == null) {
            val fromEvent = try {
                event?.source
            } catch (e: Exception) {
                null
            }
            if (fromEvent != null) {
                root = fromEvent
                rootSource = getString(R.string.probe_root_event)
            } else {
                reasons.add(getString(R.string.probe_root_event_missing))
            }
        }

        if (root == null) {
            val fromWindow = windowRoot()
            if (fromWindow != null) {
                root = fromWindow
                rootSource = getString(R.string.probe_root_window)
            } else {
                reasons.add(getString(R.string.probe_root_window_missing))
            }
        }

        if (root == null) {
            val record = TreeRecord(
                time = stamp,
                packageName = packageName,
                foregroundPackage = foreground,
                usedEventSource = false,
                rootAvailable = false,
                nodeCount = 0,
                editableNodes = emptyList(),
                sendCandidates = emptyList(),
                failureReason = reasons.joinToString("；")
            )
            ProbeState.recordTree(record)
            reportFailureContext(stamp)
            return record
        }

        // 根节点自己是谁：窗口切换途中系统会给一个「壳」，它和真树的区别
        // 只能从包名、类名、直接子节点数这三个值上看出来。
        val rootPackageName = try {
            root.packageName?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
        val rootClassName = try {
            root.className?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
        val rootChildren = try {
            root.childCount
        } catch (e: Exception) {
            -1
        }

        val editable = ArrayList<String>()
        val sendCandidates = ArrayList<String>()
        var visited = 0
        var truncated = false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        try {
            while (queue.isNotEmpty()) {
                if (visited >= MAX_NODES) {
                    truncated = true
                    break
                }
                val node = queue.removeLast()
                visited++

                if (node.isEditable && node.isVisibleToUser) {
                    editable.add(describeEditable(node))
                    // 已经拿到最关键的证据：这个界面里有没有输入框。剩下的不再遍历——
                    // 整棵树动辄上千节点，每个节点都是一次跨进程调用，读全了会把设备拖卡。
                    truncated = queue.isNotEmpty()
                    break
                }

                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val id = node.viewIdResourceName
                if (text == SEND_TEXT || desc == SEND_TEXT ||
                    (id != null && id.contains("send", ignoreCase = true))
                ) {
                    sendCandidates.add(describeNode(node))
                }

                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    queue.addLast(child)
                }
            }
        } catch (e: Exception) {
            // 节点树在遍历途中被系统回收是常态，已遍历到的部分照常记下来
        }

        val record = TreeRecord(
            time = stamp,
            packageName = packageName,
            foregroundPackage = foreground,
            usedEventSource = rootSource == getString(R.string.probe_root_event),
            rootAvailable = true,
            nodeCount = visited,
            editableNodes = editable.take(30),
            sendCandidates = sendCandidates.take(30),
            rootSource = if (truncated) {
                rootSource + getString(R.string.probe_root_partial)
            } else {
                rootSource
            },
            rootPackage = rootPackageName,
            rootClass = rootClassName,
            rootChildCount = rootChildren,
            windowPackages = windowText,
            windowError = windowErr,
            trigger = trigger
        )
        ProbeState.recordTree(record)
        return record
    }

    /**
     * 读树那一刻真正在前台的应用包名。
     *
     * 事件里的包名说的是「刚才」，这个说的是「此刻」；两者不一致时，
     * 说明这次读到的树属于别的应用，分析日志时必须区别对待。
     */
    private fun foregroundPackage(): String = try {
        windowRoot()?.packageName?.toString().orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** 第三条取根路径：从系统给出的窗口列表里挑一个应用窗口，读它的根节点。 */
    /**
     * 读树那一刻，系统窗口列表里各窗口的包名。
     *
     * 这是为了区分两件完全不同的事：
     * ① 微信窗口**不在**列表里 → 它确实退到了后台，读不到是应该的；
     * ② 微信窗口**在**列表里却读到了别的包 → 是我们挑错了窗口。
     */
    private fun windowPackageSnapshot(): Pair<String, String> {
        return try {
            val list = windows
            if (list == null) {
                return "" to "windows 返回 null"
            }
            if (list.isEmpty()) {
                return "" to "windows 返回空列表"
            }
            val names = ArrayList<String>()
            list.take(6).forEach { window ->
                val type = try {
                    when (window.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "应用"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "系统"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍层"
                        else -> "其他"
                    }
                } catch (e: Exception) {
                    "?"
                }
                // 「窗口在」和「窗口可读」是两件事：前者只说明窗口存在，
                // 后者才决定我们能不能从它身上拿到节点。分开记，才能判断
                // 到底是微信不给读，还是系统根本没把它的窗口列进来。
                val name = try {
                    val root = window.root
                    if (root == null) {
                        "有窗口但root为空"
                    } else {
                        root.packageName?.toString().orEmpty().ifEmpty { "包名为空" }
                    }
                } catch (e: Exception) {
                    "root异常:${e.javaClass.simpleName}"
                }
                names.add("$type:$name")
            }
            names.joinToString("，") to ""
        } catch (e: Exception) {
            "" to "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
        }
    }

    private fun windowRoot(): AccessibilityNodeInfo? {
        val list = try {
            windows
        } catch (e: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }
        val target = list.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: list.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        return try {
            target?.root
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 服务此刻被系统授予的真实能力。
     *
     * 这是排查「读不到屏幕内容」的关键证据：如果可读屏幕内容为 false，
     * 那就不是代码的问题，而是服务配置没被系统接受。
     */
    private fun capabilityReport(): String {
        val info = try {
            serviceInfo
        } catch (e: Exception) {
            null
        } ?: return getString(R.string.probe_info_unavailable)

        val caps = info.capabilities
        val canRead = caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        val canGesture = caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        return getString(
            R.string.probe_cap_report,
            describeEventTypes(info.eventTypes),
            info.packageNames?.joinToString(",") ?: "?",
            info.notificationTimeout,
            canRead,
            canGesture,
            dumpWindows()
        )
    }

    /** 读树失败时留一次现场，避免日志里只有「根节点不可读」这一句。 */
    private fun reportFailureContext(stamp: Long) {
        if (stamp - lastDiagAt < DIAG_INTERVAL_MS) return
        lastDiagAt = stamp
        ProbeState.setDiag(timeText() + " 读树失败\n" + capabilityReport())
    }

    private fun timeText(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /** 诊断用：把系统当前给出的窗口逐个列出来，看清到底能拿到哪些窗口、哪些读不到。 */
    fun dumpWindows(): String {
        val list = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isEmpty()) return getString(R.string.probe_windows_empty)

        val builder = StringBuilder()
        list.forEachIndexed { index, window ->
            builder.append('#').append(index)
                .append("  ").append(windowTypeName(window.type))
                .append("  active=").append(window.isActive)
                .append("  focused=").append(window.isFocused)
                .append("  layer=").append(window.layer)

            val packageName = try {
                window.root?.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            builder.append("  pkg=")
                .append(packageName ?: getString(R.string.probe_windows_no_package))
                .append('\n')
        }
        return builder.toString().trimEnd()
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> getString(R.string.probe_window_application)
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> getString(R.string.probe_window_input_method)
        AccessibilityWindowInfo.TYPE_SYSTEM -> getString(R.string.probe_window_system)
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> getString(R.string.probe_window_overlay)
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> getString(R.string.probe_window_split)
        else -> getString(R.string.probe_window_other, type)
    }

    /**
     * 可编辑节点的完整指纹。
     *
     * 光知道「有一个输入框」不够用：微信的搜索页、聊天页、朋友圈发帖页都有输入框。
     * hint 文案（搜索页写「搜索」、聊天页通常是空的）、输入框在屏幕上的位置
     * （聊天输入框贴着底边）、以及它身边的兄弟节点（聊天页的「发送」按钮），
     * 才是把这几类页面分开的依据。
     */
    private fun describeEditable(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val hint = node.hintText?.toString().orEmpty()
        val text = node.text?.toString().orEmpty().take(16)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val screenHeight = resources.displayMetrics.heightPixels
        val vertical = if (bounds.centerY() > screenHeight / 2) "下半" else "上半"

        val siblings = ArrayList<String>()
        val parent = try {
            node.parent
        } catch (e: Exception) {
            null
        }
        val parentDesc = if (parent == null) {
            "-"
        } else {
            for (i in 0 until parent.childCount) {
                if (siblings.size >= 5) break
                val child = try {
                    parent.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (child === node) continue
                val label = child.text?.toString() ?: child.contentDescription?.toString()
                val childCls = child.className?.toString()?.substringAfterLast('.') ?: "?"
                siblings.add(if (label.isNullOrBlank()) childCls else childCls + "[" + label.take(8) + "]")
            }
            (parent.className?.toString()?.substringAfterLast('.') ?: "?") + "/" +
                (parent.viewIdResourceName?.substringAfterLast('/') ?: "-")
        }

        return buildString {
            append(cls).append("  id=").append(id)
            append("  hint=").append('"').append(hint).append('"')
            if (text.isNotEmpty()) append("  text=").append('"').append(text).append('"')
            append("  bounds=").append(bounds.toShortString())
            append("  ").append(vertical)
            append("  父=").append(parentDesc)
            if (siblings.isNotEmpty()) append("  兄弟=").append(siblings.joinToString("、"))
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val hint = node.hintText?.toString().orEmpty()
        val text = node.text?.toString().orEmpty().take(20)
        val desc = node.contentDescription?.toString().orEmpty().take(20)
        return buildString {
            append(cls).append("  id=").append(id)
            if (hint.isNotEmpty()) append("  hint=").append(hint)
            if (text.isNotEmpty()) append("  text=").append(text)
            if (desc.isNotEmpty()) append("  desc=").append(desc)
        }
    }

    /** 把 contentChangeTypes 位掩码翻译成人能读的名字。 */
    private fun describeChangeTypes(mask: Int): String {
        if (mask == 0) return "未定义"
        val parts = ArrayList<String>()
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) parts.add("子树")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) parts.add("文本")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0) parts.add("描述")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0) parts.add("面板标题")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0) parts.add("出现")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0) parts.add("消失")
        if (mask and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0) parts.add("状态")
        return if (parts.isEmpty()) "0x" + Integer.toHexString(mask) else parts.joinToString("+")
    }

    private fun describeEventTypes(eventTypes: Int): String {
        val parts = ArrayList<String>()
        if (eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0) {
            parts.add(getString(R.string.probe_event_page))
        }
        if (eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0) {
            parts.add(getString(R.string.probe_event_content))
        }
        return if (parts.isEmpty()) getString(R.string.probe_nothing) else parts.joinToString(" + ")
    }

    companion object {

        /** 观察名单：现阶段预置微信与 QQ，将来由插件系统扩展。 */
        val WATCHED_PACKAGES = listOf("com.tencent.mm", "com.tencent.mobileqq")

        /**
         * 当前已连接的服务实例。
         *
         * 面板用它做「自检：读一次当前界面」，因此需要跨 Activity / Service 拿到实例。
         * 服务由系统托管，实例随时可能失效，使用处必须判空。
         */
        @Volatile
        var instance: ChatProbeService? = null

        private const val NOTIFICATION_TIMEOUT_MS = 100L

        /** 两次读树之间的最小间隔。换页本身不频繁，1 秒足以防抖。 */
        private const val TREE_PROBE_INTERVAL_MS = 1000L

        /** 内容变化触发读树的最小间隔。比换页宽，避免打字时把设备拖慢。 */
        private const val CONTENT_PROBE_INTERVAL_MS = 2500L

        /** 心跳间隔。 */
        private const val HEARTBEAT_INTERVAL_MS = 1000L

        /** 切页瞬间活动窗口常未就绪，隔这么久补读一次。 */
        private const val RETRY_DELAY_MS = 1200L

        /** 一次换页最多补读几次（加上首读共 MAX_PROBE_RETRIES + 1 次）。 */
        private const val MAX_PROBE_RETRIES = 3

        /** 节点数少于这个值就认为拿到的是「壳」而不是真树。 */
        private const val SHELL_NODE_LIMIT = 40

        /** 两次「失败现场」快照之间的最小间隔，避免刷屏。 */
        private const val DIAG_INTERVAL_MS = 5000L

        /** 单次遍历的节点上限；找到输入框会提前结束，这个值只兜底。 */
        private const val MAX_NODES = 1200
        private const val SEND_TEXT = "发送"

        private val LIGHT_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private val DETAILED_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        /**
         * 服务是否已在系统辅助功能设置里启用。
         *
         * 注意：这里判断的是「系统设置里开着」，不代表此刻已经连上；
         * 已连接的标志是 [ProbeState.snapshot] 里的 connected 字段。
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ChatProbeService::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
