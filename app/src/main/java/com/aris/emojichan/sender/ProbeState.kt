package com.aris.emojichan.sender

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一次「换页面」事件。 */
data class PageRecord(
    val time: Long,
    val packageName: String,
    val className: String,
    /** 事件所属窗口的编号：同一编号说明是同一窗口内的变化，而不是换了一个窗口。 */
    val windowId: Int = -1,
    /** 系统标注的事件发生时间；与 [time] 相差过大说明事件是积压后补投递的。 */
    val eventTime: Long = 0L
)

/**
 * 一次「内容变化」事件。
 *
 * [className] 是事件指向的**那个控件**（不是窗口）。打字时它往往就是输入框本身，
 * 因此它是「不读节点树也能认出聊天页」最有希望的一条线索，必须逐条记下来。
 */
data class ContentRecord(
    val time: Long,
    val packageName: String,
    val className: String,
    val changeTypes: String,
    /** 系统标注的事件发生时间；与 [time] 相差过大说明事件是积压后补投递的。 */
    val eventTime: Long = 0L
)

/**
 * 详细档下对节点树的一次观测。
 *
 * [packageName] 是触发这次观测的**事件**所声明的包名，说的是「刚才」；
 * [foregroundPackage] 是读树那一刻**真正在前台**的应用包名，说的是「此刻」。
 * 两者不一致，说明这次读到的树多半属于别的应用（典型场景：用户切回了探测器面板）。
 */
data class TreeRecord(
    val time: Long,
    val packageName: String,
    val foregroundPackage: String,
    val usedEventSource: Boolean,
    val rootAvailable: Boolean,
    val nodeCount: Int,
    val editableNodes: List<String>,
    val sendCandidates: List<String>,
    val texts: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    /** 根节点是从哪条路径拿到的；为空表示这次没拿到。 */
    val rootSource: String = "",
    /** 没拿到根时，每条路径各自失败的原因。 */
    val failureReason: String = "",
    /** 根节点自己是谁：包名、类名、直接子节点数。壳与真树的区别就看这三个值。 */
    val rootPackage: String = "",
    val rootClass: String = "",
    val rootChildCount: Int = -1,
    /** 读树那一刻，系统窗口列表里各窗口的包名与可读性。用来回答「微信的窗口到底在不在」。 */
    val windowPackages: String = "",
    /** 读窗口列表本身失败时的异常文本。 */
    val windowError: String = "",
    /** 这次读树是谁触发的：换页 / 内容变化 / 补读 / 连读#n / 自检。 */
    val trigger: String = ""
)

data class ProbeSnapshot(
    val connected: Boolean = false,
    val detailedMode: Boolean = false,
    val subscribedEventTypes: String = "",
    val watchingPackages: List<String> = emptyList(),
    val windowStateCount: Int = 0,
    val contentChangedCount: Int = 0,
    val contentByPackage: Map<String, Int> = emptyMap(),
    /** 内容变化事件指向的控件类名的出现次数。 */
    val contentClasses: Map<String, Int> = emptyMap(),
    val recentContentTimes: List<Long> = emptyList(),
    val recentContents: List<ContentRecord> = emptyList(),
    /** 控件类名里带 EditText 的内容变化：输入框证据单独留一份，免得被别的记录冲掉。 */
    val editableHits: List<ContentRecord> = emptyList(),
    /** 后台线程每隔一秒留下的时间戳，按时间先后排列。它断在哪里，进程就在哪里被冻住。 */
    val recentHeartbeats: List<Long> = emptyList(),
    val pages: List<PageRecord> = emptyList(),
    val trees: List<TreeRecord> = emptyList(),
    val treeAttempts: Int = 0,
    val treeSuccess: Int = 0,
    val windowsDump: String = "",
    /** 最近一次「服务能力快照」，只在配置下发或读树失败时更新。 */
    val diag: String = ""
)

/**
 * 探测器状态桥：AccessibilityService 负责写入、Activity 负责读取。
 *
 * 两者的生命周期互不相干（服务由系统托管，可能在界面完全不存在时运行），
 * 因此只能通过进程内的单例传递状态。
 *
 * 观测结果按「最近的排在前面」保存，且保留多次而不是只留最后一次——
 * 用户要切回面板才能导出日志，只留一条会把现场覆盖掉。
 */
object ProbeState {

    private const val MAX_PAGES = 200
    private const val MAX_TREES = 40
    private const val MAX_CONTENTS = 300
    private const val MAX_CONTENT_TIMES = 300
    private const val MAX_EDITABLE_HITS = 50
    private const val MAX_HEARTBEATS = 400

    private val _snapshot = MutableStateFlow(ProbeSnapshot())
    val snapshot: StateFlow<ProbeSnapshot> = _snapshot.asStateFlow()

    /**
     * 档位发生变化时的回调：服务在 onServiceConnected 里注册，
     * 用于重新设置 AccessibilityServiceInfo 的订阅范围。
     */
    var onConfigChanged: (() -> Unit)? = null

    fun setConnected(connected: Boolean) {
        _snapshot.update { it.copy(connected = connected) }
    }

    fun setDetailedMode(detailed: Boolean) {
        if (_snapshot.value.detailedMode == detailed) return
        _snapshot.update { it.copy(detailedMode = detailed) }
        onConfigChanged?.invoke()
    }

    fun setSubscribedEventTypes(text: String) {
        _snapshot.update { it.copy(subscribedEventTypes = text) }
    }

    fun setWatchingPackages(packages: List<String>) {
        _snapshot.update { it.copy(watchingPackages = packages) }
    }

    fun setWindowsDump(text: String) {
        _snapshot.update { it.copy(windowsDump = text) }
    }

    fun setDiag(text: String) {
        _snapshot.update { it.copy(diag = text) }
    }

    fun recordPage(
        packageName: String,
        className: String,
        windowId: Int,
        eventTime: Long,
        now: Long
    ) {
        _snapshot.update { current ->
            val record = PageRecord(now, packageName, className, windowId, eventTime)
            val pages = (listOf(record) + current.pages).take(MAX_PAGES)
            current.copy(windowStateCount = current.windowStateCount + 1, pages = pages)
        }
    }

    /**
     * 内容变化事件的逐条归档。
     *
     * 记下包名与控件类名是关键：聊天页若不在同一个 Activity 里切换，就只会产生
     * 内容变化事件、不产生换页事件，这时「事件来自哪个控件」是唯一能用的线索。
     */
    fun recordContentChanged(
        now: Long,
        packageName: String,
        className: String,
        changeTypes: String,
        eventTime: Long
    ) {
        _snapshot.update { current ->
            val counts = current.contentByPackage.toMutableMap()
            counts[packageName] = (counts[packageName] ?: 0) + 1
            val classes = current.contentClasses.toMutableMap()
            val key = className.ifEmpty { "?" }
            classes[key] = (classes[key] ?: 0) + 1
            val record = ContentRecord(now, packageName, className, changeTypes, eventTime)
            val hits = if (className.contains("EditText", ignoreCase = true)) {
                (listOf(record) + current.editableHits).take(MAX_EDITABLE_HITS)
            } else {
                current.editableHits
            }
            current.copy(
                contentChangedCount = current.contentChangedCount + 1,
                contentByPackage = counts,
                contentClasses = classes,
                recentContentTimes = (current.recentContentTimes + now).takeLast(MAX_CONTENT_TIMES),
                recentContents = (listOf(record) + current.recentContents).take(MAX_CONTENTS),
                editableHits = hits
            )
        }
    }

    /** 后台线程的心跳。它断在哪里，就说明进程在哪里停止被调度。 */
    fun recordHeartbeat(now: Long) {
        _snapshot.update { current ->
            current.copy(recentHeartbeats = (current.recentHeartbeats + now).takeLast(MAX_HEARTBEATS))
        }
    }

    fun recordTree(record: TreeRecord) {
        _snapshot.update { current ->
            current.copy(
                trees = (listOf(record) + current.trees).take(MAX_TREES),
                treeAttempts = current.treeAttempts + 1,
                treeSuccess = current.treeSuccess + if (record.rootAvailable) 1 else 0
            )
        }
    }

    fun clear() {
        _snapshot.update {
            it.copy(
                windowStateCount = 0,
                contentChangedCount = 0,
                contentByPackage = emptyMap(),
                contentClasses = emptyMap(),
                recentContentTimes = emptyList(),
                recentContents = emptyList(),
                editableHits = emptyList(),
                recentHeartbeats = emptyList(),
                pages = emptyList(),
                trees = emptyList(),
                treeAttempts = 0,
                treeSuccess = 0,
                windowsDump = "",
                diag = ""
            )
        }
    }
}
