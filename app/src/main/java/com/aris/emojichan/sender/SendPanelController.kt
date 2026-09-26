package com.aris.emojichan.sender

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.aris.emojichan.data.EmojiRepository
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 悬浮球点开后弹出的表情选择面板。
 *
 * 只做三件事：列出表情（最近 / 全部 / 收藏 / 自定义分类）→ 用户点一张 → 交给
 * [EmojiShare] 分享出去。窗口本身由 [FloatingBallService] 挂载和移除，
 * 这里只管窗口里那棵树。
 *
 * 分组名直接用字符串（而不是资源 id）作为键：分类名来自数据库，
 * 与「最近 / 全部 / 收藏」共享一套 chip 渲染逻辑，字符串比较最省事。
 */
class SendPanelController(
    private val host: Context,
    private val root: View,
    private val scope: CoroutineScope,
    private val repository: EmojiRepository,
    private val onPick: (EmojiEntity) -> Unit,
    private val onClose: () -> Unit
) {
    companion object {
        /** 「最近」最多列这么多张，够用且不让首屏查询变重。 */
        private const val RECENT_LIMIT = 40
        private const val COLUMNS = 4
    }

    private val chipContainer: LinearLayout = root.findViewById(R.id.sendPanelChips)
    private val list: RecyclerView = root.findViewById(R.id.sendPanelList)
    private val emptyView: TextView = root.findViewById(R.id.sendPanelEmpty)
    private val targetView: TextView = root.findViewById(R.id.sendPanelTarget)
    private val adapter = SendPanelAdapter(onPick)

    /**
     * 这一次打算发给谁（聊天页标题栏上的名字）。挂出面板之前由
     * [FloatingBallService] 读一次填进来；读不到就是 null，界面会提示手动选人。
     */
    var targetName: String? = null
        private set

    private var chipViews: List<TextView> = emptyList()
    private var current: String = ""
    private var loadJob: Job? = null

    fun bind() {
        root.findViewById<View>(R.id.sendPanelMask).setOnClickListener { onClose() }
        root.findViewById<View>(R.id.sendPanelClose).setOnClickListener { onClose() }

        list.layoutManager = GridLayoutManager(host, COLUMNS)
        list.adapter = adapter

        refreshTarget()

        scope.launch {
            val categories = repository.getAllCategories().first()
            val hasRecent = repository.getRecentEmojis(RECENT_LIMIT).first().isNotEmpty()
            buildChips(categories)
            // 首次使用还没有「最近」时直接落在「全部」，避免首屏是空的。
            select(
                host.getString(
                    if (hasRecent) R.string.overlay_chip_recent else R.string.overlay_chip_all
                )
            )
        }
    }

    /** 窗口被移除前调用，取消未完成的查询并断开 adapter。 */
    fun release() {
        loadJob?.cancel()
        loadJob = null
        list.adapter = null
    }

    /** 由 [FloatingBallService] 填入聊天对象名，并立刻刷新顶部那行提示。 */
    fun setTarget(name: String?) {
        targetName = name
        refreshTarget()
    }

    /**
     * 顶部那行「将发给：谁」有三种状态：服务没开（可以点去设置）、
     * 认出了聊天对象、没认出来。
     * 先把名字摆给用户看，是因为微信那边点完就发出去了，没有后悔的余地。
     */
    private fun refreshTarget() {
        val name = targetName
        when {
            !AutoSendService.isConnected -> {
                targetView.text = host.getString(R.string.overlay_target_needs_service)
                targetView.setOnClickListener { openAccessibilitySettings() }
            }

            name.isNullOrEmpty() -> {
                targetView.text = host.getString(R.string.overlay_target_unknown)
                targetView.setOnClickListener(null)
            }

            else -> {
                targetView.text = host.getString(R.string.overlay_target_prefix, name)
                targetView.setOnClickListener(null)
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { host.startActivity(intent) }
    }

    private fun buildChips(categories: List<String>) {
        chipContainer.removeAllViews()
        val labels = mutableListOf(
            host.getString(R.string.overlay_chip_recent),
            host.getString(R.string.overlay_chip_all),
            host.getString(R.string.overlay_chip_favorites)
        )
        labels += categories

        chipViews = labels.map { label ->
            val chip = TextView(host).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { select(label) }
            }
            chipContainer.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
            chip
        }
    }

    private fun select(label: String) {
        current = label
        refreshChips()

        // 上一次查询可能还没回来（Flow 首值要走 Room 线程），先取消再发起，
        // 否则快速连点 chip 时后到的旧结果会覆盖新结果。
        loadJob?.cancel()
        loadJob = scope.launch {
            val items = query(label).first()
            adapter.submitList(items)

            val blank = items.isEmpty()
            emptyView.visibility = if (blank) View.VISIBLE else View.GONE
            if (blank) {
                emptyView.text = if (label == host.getString(R.string.overlay_chip_recent)) {
                    host.getString(R.string.overlay_empty_recent)
                } else {
                    host.getString(R.string.overlay_empty)
                }
            }
        }
    }

    private fun refreshChips() {
        chipViews.forEach { chip ->
            val selected = chip.text.toString() == current
            chip.setTextColor(if (selected) Color.WHITE else host.getColor(R.color.text_primary))
            chip.setBackgroundResource(
                if (selected) R.drawable.category_chip_selected_bg
                else R.drawable.category_chip_bg
            )
        }
    }

    private fun query(label: String): Flow<List<EmojiEntity>> = when (label) {
        host.getString(R.string.overlay_chip_recent) -> repository.getRecentEmojis(RECENT_LIMIT)
        host.getString(R.string.overlay_chip_all) -> repository.getAllEmojis()
        host.getString(R.string.overlay_chip_favorites) -> repository.getFavorites()
        else -> repository.getByCategory(label)
    }

    private fun dp(value: Int): Int =
        (value * host.resources.displayMetrics.density).toInt()
}

private val SEND_PANEL_DIFF = object : DiffUtil.ItemCallback<EmojiEntity>() {
    override fun areItemsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean =
        oldItem == newItem
}

private class SendPanelAdapter(
    private val onPick: (EmojiEntity) -> Unit
) : ListAdapter<EmojiEntity, SendPanelAdapter.Holder>(SEND_PANEL_DIFF) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.sendEmojiImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_send_emoji, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val emoji = getItem(position)
        Glide.with(holder.itemView.context)
            .load(File(emoji.filePath))
            .transform(CenterCrop(), RoundedCorners(24))
            .placeholder(R.drawable.ic_emoji_placeholder)
            .error(R.drawable.ic_emoji_placeholder)
            .into(holder.image)

        holder.itemView.setOnClickListener { onPick(emoji) }
    }
}
