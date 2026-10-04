package com.aris.emojichan.sender

import android.content.Context
import android.content.Intent
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
import com.aris.emojichan.data.EmojiFilter
import com.aris.emojichan.data.EmojiRepository
import com.aris.emojichan.data.TagEntity
import com.aris.emojichan.data.TagMode
import com.aris.emojichan.data.TagOrder
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
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
 * 只做三件事：列出表情（最近 / 全部 / 收藏，可再叠加任意多个标签）→ 用户点一张 → 交给
 * [EmojiShare] 分享出去。窗口本身由 [FloatingBallService] 挂载和移除，
 * 这里只管窗口里那棵树。
 *
 * 上排三颗用字符串（而不是资源 id）作为键：与标签行共享一套 chip 渲染逻辑，
 * 字符串比较最省事。
 *
 * 标签行跟主界面筛选条是同一套读法（v0.2.007，用户 m10764）：可以同时选多个，每个标签各记
 * 一份合并方式，chip 上写着它（& 交 / | 并 / ! 非）。点没选中的＝选上（默认交），再点已选中的＝
 * 换下一种、第四下取消。悬浮球是「赶紧挑一张发出去」的场景，所以没再摆一颗 ×：多按
 * 一下就回到没选，也少一个点不中的小目标。
 *
 * v0.2.201（用户 m14344）改了两处：行首那颗「不限」删掉 —— 一个标签都不选本来就是「不限」，
 * 那颗 chip 白占一个位置；顺序交给 [TagOrder.forPanel]，跟主界面标签下拉同一条规则（「图片」
 * 「动图」永远在最前，其余按挂着的张数从多到少）。清空挪到长按上（见 [clearSelectedTags]）：
 * 选了三个标签时才需要它，平时多按的那几下不算什么。
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
    private val tagRow: View = root.findViewById(R.id.sendPanelTagRow)
    private val tagChipContainer: LinearLayout = root.findViewById(R.id.sendPanelTagChips)
    private val list: RecyclerView = root.findViewById(R.id.sendPanelList)
    private val emptyView: TextView = root.findViewById(R.id.sendPanelEmpty)
    private val targetView: TextView = root.findViewById(R.id.sendPanelTarget)

    /**
     * 取颜色 / 尺寸用的上下文。
     *
     * host 是 [FloatingBallService]，Service 自己拿不到 AppCompat 的深色覆盖，
     * 用它取 R.color.text_primary 永远得到浅色那套值 —— 深色模式下 chip 文字会看不清（emc-1-025）。
     */
    private val themed: Context = com.aris.emojichan.UiPrefs.themedContext(host)
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

    /** 标签行上的 chip。 */
    private var tagChips: List<TagChip> = emptyList()

    /**
     * 选中的标签 → 它自己的合并方式，按选中的先后保序。
     *
     * 只有写进这张表的标签参与筛选，没写的按「交」算；表空＝不按标签筛。
     */
    private val selectedTags = LinkedHashMap<Long, TagMode>()

    private data class TagChip(val id: Long, val name: String, val view: TextView)

    fun bind() {
        root.findViewById<View>(R.id.sendPanelMask).setOnClickListener { onClose() }
        root.findViewById<View>(R.id.sendPanelClose).setOnClickListener { onClose() }

        list.layoutManager = GridLayoutManager(host, COLUMNS)
        list.adapter = adapter

        refreshTarget()

        scope.launch {
            val tags = orderedTags()
            val hasRecent = repository.getRecentEmojis(RECENT_LIMIT).first().isNotEmpty()
            buildChips()
            buildTagChips(tags)
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

    /** 上排永远是这三颗；标签单独一行，两个维度各占一行（见 [buildTagChips]）。 */
    private fun buildChips() {
        chipContainer.removeAllViews()
        val labels = listOf(
            host.getString(R.string.overlay_chip_recent),
            host.getString(R.string.overlay_chip_all),
            host.getString(R.string.overlay_chip_favorites)
        )

        chipViews = labels.map { label ->
            val chip = createChip(label) { select(label) }
            chipContainer.addView(chip, chipParams())
            chip
        }
    }

    /**
     * 标签行的顺序：跟主界面标签下拉共用 [TagOrder.forPanel] —— 「图片」「动图」永远在最前，
     * 其余按挂着的表情张数从多到少（用户 m14344）。张数要读一次 `observeTagCounts`，
     * 所以这里是 suspend，只在 [bind] 里调一次；之后增删标签要重新开面板才看得出来。
     */
    private suspend fun orderedTags(): List<TagEntity> {
        val counts = repository.observeTagCounts().first().associate { it.tagId to it.count }
        return TagOrder.forPanel(repository.getTags(), counts)
    }

    /**
     * 标签行。每个标签一颗 chip：可以同时选多个，每个各记一份合并方式（用户 m10764）。
     * 行首没有「不限」—— 一颗都没选就是这个意思（用户 m14344）；长按任意一颗＝一次清空。
     *
     * 一个标签都没有时整行藏起来，否则面板上会多出一行只有「标签」两个字和空白的空壳。
     */
    private fun buildTagChips(tags: List<TagEntity>) {
        tagRow.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        tagChipContainer.removeAllViews()
        if (tags.isEmpty()) {
            tagChips = emptyList()
            selectedTags.clear()
            return
        }

        tagChips = tags.map { tag ->
            val chip = createChip(tag.name) { onTagChip(tag.id) }
            // 长按＝清空：原来干这活的是行首那颗「不限」（用户 m14344 把它删了）
            chip.setOnLongClickListener { clearSelectedTags(); true }
            tagChipContainer.addView(chip, chipParams())
            TagChip(tag.id, tag.name, chip)
        }
        refreshTagChips()
    }

    /**
     * chip 一律建在 [themed] 上：host 是 Service，拿不到 AppCompat 的深色覆盖，
     * 用 host 建出来的 TextView 解析 @color/category_chip_bg 只会得到浅色那套值 ——
     * 深色模式下就是浅底配浅字，选没选中分不清（emc-1-025）。
     */
    private fun createChip(label: String, onClick: () -> Unit): TextView =
        TextView(themed).apply {
            text = label
            textSize = 13f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onClick() }
        }

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(8) }

    private fun select(label: String) {
        current = label
        refreshChips()

        // 上一次查询可能还没回来（Flow 首值要走 Room 线程），先取消再发起，
        // 否则快速连点 chip 时后到的旧结果会覆盖新结果。
        loadJob?.cancel()
        loadJob = scope.launch {
            val items = loadItems()

            val blank = items.isEmpty()
            emptyView.visibility = if (blank) View.VISIBLE else View.GONE
            if (blank) {
                emptyView.text = when {
                    selectedTags.size > 1 -> host.getString(R.string.overlay_empty_tags)
                    selectedTags.isNotEmpty() -> host.getString(R.string.overlay_empty_tag)
                    current == host.getString(R.string.overlay_chip_recent) ->
                        host.getString(R.string.overlay_empty_recent)
                    else -> host.getString(R.string.overlay_empty)
                }
            }
            adapter.submitList(items)
        }
    }

    /**
     * 点标签行上的 chip。
     *
     * 没选中的选上（默认交），已经选上的换下一种：交 → 并 → 非 → 取消（用户 m10764，
     * 跟主界面筛选条上那套循环同一个读法）。
     *
     * 标签和上排那三颗是两个独立维度，可以叠加。唯一的例外是「最近」：它本来就不是筛选，
     * 叠加标签后语义会变成「所有挂了这个标签的图」，与 chip 上的字对不上，
     * 所以一旦选了标签就自动切到「全部」。
     */
    private fun onTagChip(tagId: Long) {
        when (selectedTags[tagId]) {
            null -> selectedTags[tagId] = TagMode.ALL
            TagMode.ALL -> selectedTags[tagId] = TagMode.ANY
            TagMode.ANY -> selectedTags[tagId] = TagMode.EXCLUDE
            TagMode.EXCLUDE -> selectedTags.remove(tagId)
        }
        if (selectedTags.isNotEmpty() && current == host.getString(R.string.overlay_chip_recent)) {
            current = host.getString(R.string.overlay_chip_all)
            refreshChips()
        }
        refreshTagChips()
        select(current)
    }

    /**
     * 长按标签 chip：已选的标签一次清空，回到「不限」（用户 m14344 删掉行首那颗 chip 之后，
     * 把这个动作挪到了这里）。一颗都没选时什么也不做 —— 否则白刷一遍列表，还得把「最近」
     * 重新查一次。
     */
    private fun clearSelectedTags() {
        if (selectedTags.isEmpty()) return
        selectedTags.clear()
        refreshTagChips()
        select(current)
    }

    private suspend fun loadItems(): List<EmojiEntity> {
        if (selectedTags.isEmpty()) return query(current).first()
        val favorites = host.getString(R.string.overlay_chip_favorites)
        // 多个标签 + 每个自己的合并方式，跟主界面走的是同一条查询路径（EmojiQuery 拼 SQL）
        return repository.findFiltered(
            EmojiFilter(
                favoritesOnly = current == favorites,
                tagIds = selectedTags.keys.toList(),
                tagModes = selectedTags.toMap()
            )
        )
    }

    private fun refreshChips() {
        chipViews.forEach { chip -> paintChip(chip, chip.text.toString() == current) }
    }

    /**
     * 标签 chip 的样子：选中的写成「& 猫」（符号是它自己的合并方式），没选的只写名字。
     * 一颗都没选时整行都是未选中态 —— 那就是「不限」，没有单独的 chip 去点亮它。
     */
    private fun refreshTagChips() {
        tagChips.forEach { chip ->
            val mode = selectedTags[chip.id]
            chip.view.text = if (mode != null) matchSymbol(mode) + " " + chip.name else chip.name
            paintChip(chip.view, mode != null)
        }
    }

    /** 交 / 并 / 非的符号，跟主界面筛选条、搜索框那套语法（& | !）是同一套写法。 */
    private fun matchSymbol(mode: TagMode): String = host.getString(
        when (mode) {
            TagMode.ALL -> R.string.tag_mode_symbol_all
            TagMode.ANY -> R.string.tag_mode_symbol_any
            TagMode.EXCLUDE -> R.string.tag_mode_symbol_not
        }
    )

    private fun paintChip(chip: TextView, selected: Boolean) {
        // 选中态是「主题色填充 + 深墨字」：字色得从主题取 —— 浅色模式的主色是图标
        // 原色（淡蓝/淡粉），写死白字压上去只有 1.5:1，等于看不见
        val ink = if (selected) {
            // chip 建在 themed 上，所以直接拿它去取主题属性就行
            MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary)
        } else {
            themed.getColor(R.color.text_primary)
        }
        chip.setTextColor(ink)
        chip.setBackgroundResource(
            if (selected) R.drawable.category_chip_selected_bg
            else R.drawable.category_chip_bg
        )
    }

    /** 标签为空时按上排那三颗查；label 只可能是这三颗之一，兜底给「全部」。 */
    private fun query(label: String): Flow<List<EmojiEntity>> = when (label) {
        host.getString(R.string.overlay_chip_recent) -> repository.getRecentEmojis(RECENT_LIMIT)
        host.getString(R.string.overlay_chip_favorites) -> repository.getFavorites()
        else -> repository.getAllEmojis()
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
