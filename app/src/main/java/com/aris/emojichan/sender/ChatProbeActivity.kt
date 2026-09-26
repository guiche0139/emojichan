package com.aris.emojichan.sender

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aris.emojichan.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 发送模块探测器面板：显示无障碍状态、当前档位与实时观测到的页面 / 节点树信息。
 *
 * 这一版只做观测，不做任何点击、手势或悬浮窗。
 */
class ChatProbeActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var restrictedHint: TextView
    private lateinit var modeSwitch: MaterialSwitch
    private lateinit var configView: TextView
    private lateinit var pagesView: TextView
    private lateinit var contentView: TextView
    private lateinit var treeView: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_probe)

        val toolbar = findViewById<MaterialToolbar>(R.id.probeToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusView = findViewById(R.id.probeStatus)
        restrictedHint = findViewById(R.id.probeRestrictedHint)
        modeSwitch = findViewById(R.id.switchDetailed)
        configView = findViewById(R.id.probeConfig)
        pagesView = findViewById(R.id.probePages)
        contentView = findViewById(R.id.probeContent)
        treeView = findViewById(R.id.probeTree)

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<Button>(R.id.btnProbeNow).setOnClickListener { probeNow() }
        findViewById<Button>(R.id.btnBurstProbe).setOnClickListener { burstProbe() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { ProbeState.clear() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == ProbeState.snapshot.value.detailedMode) return@setOnCheckedChangeListener
            ProbeState.setDetailedMode(isChecked)
        }

        lifecycleScope.launch {
            ProbeState.snapshot.collectLatest { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 服务连接或断开时我们很可能不在前台，回到这里补一次状态同步
        if (!ChatProbeService.isEnabled(this)) {
            ProbeState.setConnected(false)
        }
        render(ProbeState.snapshot.value)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * 自检：立刻读一次当前界面。
     *
     * 面板自己在前台时，读到的就是本应用自己的界面。如果连这一下都拿不到根节点，
     * 说明问题出在服务能力上（配置或系统限制），而不是微信身上——这正是上一轮
     * 日志回答不了的问题，所以单独给个按钮把它固定下来。
     */
    private fun probeNow() {
        val service = ChatProbeService.instance
        if (service == null) {
            Toast.makeText(this, R.string.probe_service_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        service.probeTree(packageName, null, "自检")
        ProbeState.setWindowsDump(service.dumpWindows())
        Toast.makeText(this, R.string.probe_probed, Toast.LENGTH_SHORT).show()
    }

    /**
     * 延时连读：点完立刻切到目标页面，由服务到点自动读。
     *
     * 面板自己在前台时读到的永远是本应用的界面，所以想拿到微信聊天页的树，
     * 这是唯一可行的办法——这也是前几轮日志里最缺的那块数据。
     */
    private fun burstProbe() {
        val service = ChatProbeService.instance
        if (service == null) {
            Toast.makeText(this, R.string.probe_service_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        service.probeBurst(BURST_DELAY_MS, BURST_TIMES, BURST_INTERVAL_MS)
        Toast.makeText(
            this,
            getString(R.string.probe_burst_started, (BURST_DELAY_MS / 1000).toInt(), BURST_TIMES),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun render(state: ProbeSnapshot) {
        val enabled = ChatProbeService.isEnabled(this)
        statusView.text = when {
            state.connected -> getString(R.string.probe_status_connected)
            enabled -> getString(R.string.probe_status_waiting)
            else -> getString(R.string.probe_status_off)
        }

        restrictedHint.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !enabled) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (modeSwitch.isChecked != state.detailedMode) {
            modeSwitch.isChecked = state.detailedMode
        }

        val now = System.currentTimeMillis()
        val last10s = state.recentContentTimes.count { now - it <= 10_000L }
        val contentSources = if (state.contentByPackage.isEmpty()) {
            getString(R.string.probe_nothing)
        } else {
            state.contentByPackage.entries
                .sortedByDescending { it.value }
                .joinToString(getString(R.string.probe_separator)) { shortPackage(it.key) + " " + it.value }
        }
        configView.text = getString(
            R.string.probe_config_format,
            state.watchingPackages.joinToString(getString(R.string.probe_separator)),
            state.subscribedEventTypes,
            state.windowStateCount,
            state.contentChangedCount,
            last10s,
            state.treeAttempts,
            state.treeSuccess,
            contentSources,
            heartbeatReport(state)
        )

        pagesView.text = formatPages(state.pages)
        contentView.text = formatContents(state)
        treeView.text = formatTrees(state)
    }

    /**
     * 心跳分析：进程在哪些时间段里没有拿到 CPU。
     *
     * 事件积压、连读挤成一堆、读树时前台永远不是微信——如果这些怪现象背后是同一个原因，
     * 心跳会在对应的时间段上留下一个空洞，而那个空洞本身就是证据。
     */
    private fun heartbeatReport(state: ProbeSnapshot): String {
        val beats = state.recentHeartbeats
        if (beats.size < 2) return getString(R.string.probe_nothing)

        var maxGap = 0L
        var gapStart = beats.first()
        for (index in 1 until beats.size) {
            val gap = beats[index] - beats[index - 1]
            if (gap > maxGap) {
                maxGap = gap
                gapStart = beats[index - 1]
            }
        }

        val gapText = if (maxGap < HEARTBEAT_GAP_ALARM_MS) {
            getString(R.string.probe_heartbeat_ok)
        } else {
            getString(
                R.string.probe_heartbeat_gap,
                formatSeconds(maxGap),
                timeFormat.format(Date(gapStart))
            )
        }
        return getString(
            R.string.probe_heartbeat_format,
            beats.size,
            formatSeconds(beats.last() - beats.first()),
            gapText
        )
    }

    /** 事件时间与收到时间的差：差得越远，说明这条事件被系统压得越久。 */
    private fun lagText(eventTime: Long, receivedTime: Long): String {
        if (eventTime <= 0L) return ""
        val lag = receivedTime - eventTime
        if (lag < LAG_NOTICE_MS) return ""
        return "  " + getString(R.string.probe_lag_format, formatSeconds(lag))
    }

    private fun formatSeconds(millis: Long): String =
        String.format(Locale.getDefault(), "%.1f 秒", millis / 1000.0)

    /**
     * 内容变化观测：谁在变、变的是哪个控件。
     *
     * 聊天页不产生换页事件，只会持续产生内容变化事件，所以这里是唯一能认出它的地方：
     * 打字时事件指向的控件往往就是输入框本身。
     */
    private fun formatContents(state: ProbeSnapshot): String {
        val builder = StringBuilder()

        if (state.editableHits.isNotEmpty()) {
            builder.append(getString(R.string.probe_section_editable))
                .append("  ×").append(state.editableHits.size).append('\n')
            state.editableHits.take(6).forEach { record ->
                builder.append("    ").append(timeFormat.format(Date(record.eventTime)))
                    .append("  ").append(shortPackage(record.packageName))
                    .append("  ").append(shortClass(record.className))
                    .append("  ").append(record.changeTypes)
                    .append('\n')
            }
            builder.append('\n')
        }

        if (state.contentClasses.isEmpty()) {
            builder.append(getString(R.string.probe_nothing))
        } else {
            state.contentClasses.entries.sortedByDescending { it.value }.forEach { entry ->
                builder.append(shortClass(entry.key))
                    .append("  ×").append(entry.value)
                    .append("\n")
            }
            builder.setLength(builder.length - 1)
        }

        if (state.recentContents.isNotEmpty()) {
            builder.append("\n\n")
            state.recentContents.take(12).forEach { record ->
                builder.append(timeFormat.format(Date(record.eventTime)))
                    .append(lagText(record.eventTime, record.time))
                    .append("  ").append(shortPackage(record.packageName))
                    .append("  ").append(shortClass(record.className))
                    .append("  ").append(record.changeTypes)
                    .append("\n")
            }
            builder.setLength(builder.length - 1)
        }
        return builder.toString()
    }

    private fun formatPages(pages: List<PageRecord>): String {
        if (pages.isEmpty()) return getString(R.string.probe_nothing)
        val builder = StringBuilder()
        pages.take(80).forEach { page ->
            builder.append(timeFormat.format(Date(page.eventTime)))
                .append(lagText(page.eventTime, page.time))
                .append("  ")
                .append(shortPackage(page.packageName))
                .append("  ")
                .append(shortClass(page.className))
                .append('\n')
        }
        return builder.toString().trimEnd()
    }

    /** 节点树观测：最近的排在最前面，面板自身那次自检也在其中。 */
    private fun formatTrees(state: ProbeSnapshot): String {
        val builder = StringBuilder()

        if (state.diag.isNotEmpty()) {
            builder.append(getString(R.string.probe_section_diag)).append('\n')
            builder.append(state.diag).append('\n').append('\n')
        }

        if (state.windowsDump.isNotEmpty()) {
            builder.append(getString(R.string.probe_section_windows)).append('\n')
            builder.append(state.windowsDump).append('\n').append('\n')
        }

        builder.append(getString(R.string.probe_section_tree)).append('\n')
        if (state.trees.isEmpty()) {
            builder.append(getString(R.string.probe_nothing))
            return builder.toString().trimEnd()
        }

        state.trees.take(6).forEachIndexed { index, tree ->
            builder.append("#").append(index + 1).append("  ")
                .append(timeFormat.format(Date(tree.time)))
                .append("  ")
                .append(shortPackage(tree.packageName))
                .append('\n')

            if (tree.foregroundPackage.isNotEmpty() &&
                tree.foregroundPackage != tree.packageName
            ) {
                builder.append("  ")
                    .append(
                        getString(
                            R.string.probe_tree_foreground,
                            shortPackage(tree.foregroundPackage)
                        )
                    )
                    .append('\n')
            }

            if (!tree.rootAvailable) {
                builder.append("  ").append(getString(R.string.probe_root_short))
                if (tree.failureReason.isNotEmpty()) {
                    builder.append("（").append(tree.failureReason).append("）")
                }
                builder.append('\n')
                return@forEachIndexed
            }

            builder.append("  ")
                .append(
                    getString(
                        R.string.probe_tree_line,
                        tree.nodeCount,
                        tree.editableNodes.size,
                        tree.sendCandidates.size
                    )
                )
                .append("  根来自：").append(tree.rootSource)
                .append("  根=").append(tree.rootPackage.ifEmpty { "?" })
                .append('/').append(tree.rootClass.substringAfterLast('.').ifEmpty { "?" })
                .append(" 子").append(tree.rootChildCount)
                .append("  触发=").append(tree.trigger.ifEmpty { "?" })
                .append('\n')
                .append("      读树时窗口：").append(tree.windowPackages.ifEmpty { "（读不到）" })
                .append('\n')
                .apply {
                    if (tree.windowError.isNotEmpty()) {
                        append("      窗口列表读取失败：").append(tree.windowError).append('\n')
                    }
                }
            if (tree.classes.isNotEmpty()) {
                builder.append("    类名：").append(tree.classes.joinToString("、")).append('\n')
            }
            if (tree.texts.isNotEmpty()) {
                builder.append("    文本：").append(tree.texts.take(8).joinToString(" / ")).append('\n')
            }
            tree.editableNodes.take(3).forEach {
                builder.append("    · ").append(it).append('\n')
            }
            tree.sendCandidates.take(3).forEach {
                builder.append("    · ").append(it).append('\n')
            }
        }
        return builder.toString().trimEnd()
    }

    private fun copyLog() {
        val state = ProbeState.snapshot.value
        val builder = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        builder.append("EmojiChan 发送模块 · 探测器日志").append('\n')
        builder.append("时间：").append(stamp).append('\n')
        builder.append("系统：Android ").append(Build.VERSION.RELEASE)
            .append("（API ").append(Build.VERSION.SDK_INT).append("）").append('\n')
        builder.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        builder.append("白名单：")
            .append(state.watchingPackages.joinToString(getString(R.string.probe_separator)))
            .append('\n')
        builder.append("订阅事件：").append(state.subscribedEventTypes).append('\n')
        builder.append("累计：换页 ").append(state.windowStateCount)
            .append(" · 内容变化 ").append(state.contentChangedCount).append('\n')
        builder.append("读树：尝试 ").append(state.treeAttempts)
            .append(" · 成功 ").append(state.treeSuccess).append('\n')
        if (state.contentByPackage.isNotEmpty()) {
            builder.append("内容变化来源：")
                .append(
                    state.contentByPackage.entries.sortedByDescending { it.value }
                        .joinToString(getString(R.string.probe_separator)) {
                            it.key + " " + it.value
                        }
                )
                .append('\n')
        }

        builder.append('\n').append("--- 进程心跳（每秒一次）---").append('\n')
        builder.append(heartbeatReport(state)).append('\n')
        if (state.recentHeartbeats.size > 2) {
            builder.append("前 20 次：").append(
                state.recentHeartbeats.take(20).joinToString("、") { timeFormat.format(Date(it)) }
            ).append('\n')
        }

        builder.append('\n').append("--- 页面记录（完整类名）---").append('\n')
        if (state.pages.isEmpty()) {
            builder.append("（暂无）").append('\n')
        }
        state.pages.take(150).forEach { page ->
            builder.append(timeFormat.format(Date(page.eventTime)))
                .append(lagText(page.eventTime, page.time)).append("  ")
                .append(page.packageName).append("  ")
                .append(page.className)
            if (page.windowId != -1) {
                builder.append("  w=").append(page.windowId)
            }
            builder.append('\n')
        }

        builder.append('\n').append("--- 内容变化观测（事件指向的控件）---").append('\n')
        builder.append("输入框命中：").append(state.editableHits.size).append(" 次").append('\n')
        state.editableHits.forEach { record ->
            builder.append("  ").append(timeFormat.format(Date(record.eventTime)))
                .append(lagText(record.eventTime, record.time)).append("  ")
                .append(record.packageName).append("  ")
                .append(record.className).append("  ")
                .append(record.changeTypes).append('\n')
        }
        if (state.contentClasses.isEmpty()) {
            builder.append("（暂无）").append('\n')
        } else {
            state.contentClasses.entries.sortedByDescending { it.value }.forEach { entry ->
                builder.append(entry.key.ifEmpty { "?" })
                    .append("  ×").append(entry.value).append('\n')
            }
        }
        if (state.recentContents.isNotEmpty()) {
            builder.append('\n').append("最近内容变化（时间 · 应用 · 控件类名 · 变化类型）").append('\n')
            state.recentContents.take(MAX_CONTENT_EXPORT).forEach { record ->
                builder.append(timeFormat.format(Date(record.eventTime)))
                    .append(lagText(record.eventTime, record.time)).append("  ")
                    .append(record.packageName).append("  ")
                    .append(record.className.ifEmpty { "?" }).append("  ")
                    .append(record.changeTypes).append('\n')
            }
        }

        if (state.diag.isNotEmpty()) {
            builder.append('\n').append("--- 服务能力快照 ---").append('\n')
            builder.append(state.diag).append('\n')
        }

        if (state.windowsDump.isNotEmpty()) {
            builder.append('\n').append("--- 窗口列表（最近一次自检）---").append('\n')
            builder.append(state.windowsDump).append('\n')
        }

        builder.append('\n').append("--- 节点树观测（保留最近 ")
            .append(state.trees.size).append(" 次）---").append('\n')
        builder.append(getString(R.string.probe_privacy_note)).append('\n')
        if (state.trees.isEmpty()) {
            builder.append("（暂无）").append('\n')
        }
        state.trees.take(40).forEachIndexed { index, tree ->
            builder.append("#").append(index + 1).append("  ")
                .append(timeFormat.format(Date(tree.time))).append("  ")
                .append(tree.packageName)
                .append("  读树时前台=").append(tree.foregroundPackage.ifEmpty { "?" })
                .append("  根来自=").append(tree.rootSource.ifEmpty { "无" })
                .append("  根包名=").append(tree.rootPackage.ifEmpty { "?" })
                .append("  根类名=").append(tree.rootClass.ifEmpty { "?" })
                .append("  根子节点=").append(tree.rootChildCount)
                .append("  触发=").append(tree.trigger.ifEmpty { "?" })
                .append('\n')
                .append("  读树时窗口：").append(tree.windowPackages.ifEmpty { "（读不到）" })
                .append('\n')
                .apply {
                    if (tree.windowError.isNotEmpty()) {
                        append("  窗口列表读取失败：").append(tree.windowError).append('\n')
                    }
                }
            if (!tree.rootAvailable) {
                builder.append("    根节点不可读")
                    .append("（").append(tree.failureReason.ifEmpty { "原因未知" }).append("）")
                    .append('\n')
                return@forEachIndexed
            }
            builder.append("    节点数 ").append(tree.nodeCount)
                .append(" · 可编辑 ").append(tree.editableNodes.size)
                .append(" · 发送候选 ").append(tree.sendCandidates.size).append('\n')
            if (tree.classes.isNotEmpty()) {
                builder.append("    类名：").append(tree.classes.joinToString("、")).append('\n')
            }
            if (tree.texts.isNotEmpty()) {
                builder.append("    文本：").append(tree.texts.joinToString(" / ")).append('\n')
            }
            tree.editableNodes.forEach { builder.append("    · 可编辑 ").append(it).append('\n') }
            tree.sendCandidates.forEach { builder.append("    · 发送候选 ").append(it).append('\n') }
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EmojiChan 探测器", builder.toString()))
        Toast.makeText(this, R.string.probe_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.probe_find_service_hint, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.probe_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /**
         * 连读参数：首读前留 6 秒够切到微信，之后每 2 秒一次共 15 次，
         * 合计覆盖约 34 秒。
         *
         * 读树能不能拿到微信，取决于读的那一刻微信在不在前台，
         * 而这件事我们控制不了时机——只能多读几次，让用户在里面正常操作，
         * 总有几次恰好落在微信前台上。
         */
        private const val BURST_DELAY_MS = 6000L
        private const val BURST_TIMES = 15
        private const val BURST_INTERVAL_MS = 2000L

        /** 导出日志里最多附上多少条最近的内容变化记录。 */
        private const val MAX_CONTENT_EXPORT = 60

        /** 心跳空档超过这么久，就说明进程在那段时间里没被调度过。 */
        private const val HEARTBEAT_GAP_ALARM_MS = 2000L

        /** 事件时间与收到时间差这么多以上才值得标注，更小的差值只是正常派发开销。 */
        private const val LAG_NOTICE_MS = 500L
    }

    private fun shortPackage(packageName: String): String = when (packageName) {
        "com.tencent.mm" -> "微信"
        "com.tencent.mobileqq" -> "QQ"
        else -> packageName
    }

    private fun shortClass(className: String): String {
        if (className.isEmpty()) return "?"
        val parts = className.split('.')
        return if (parts.size <= 2) className else parts.takeLast(2).joinToString(".")
    }
}
