package com.aris.emojichan.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.util.ImageUtil
import com.bumptech.glide.Glide
import java.io.File

/** 压缩页里的一行：分段标题，或者网格里的一格。 */
sealed interface CompressRow {
    data class Section(val titleRes: Int, val count: Int, val bytes: Long) : CompressRow
    data class Item(val file: EmojiFile) : CompressRow
}

/**
 * 压缩页的网格适配器。
 *
 * 分段按体积划：10 MB 以上、1–10 MB、1 MB 以下。段标题占满一整行，格子占一格。
 * 勾选状态放在这里，页面只问「选了几张、多少字节」。
 */
class CompressAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val SPAN = 3

        private const val TYPE_SECTION = 0
        private const val TYPE_ITEM = 1

        private const val MB = 1024L * 1024L
        private const val BIG = 10 * MB

        /** 段的先后就是按体积从大到小；划段的标准只有 [sectionKeyOf] 一处。 */
        private val SECTION_ORDER = listOf(
            R.string.compress_section_big,
            R.string.compress_section_mid,
            R.string.compress_section_small
        )
    }

    private val rows = mutableListOf<CompressRow>()
    private val selectedIds = linkedSetOf<Long>()
    private var files: List<EmojiFile> = emptyList()

    /**
     * 折叠起来的段（键是段标题的资源 id，用户 m11668 第 2 条）。
     * 折叠只是不铺这一段的格子，勾选照旧保留 —— 折起来不等于放弃。
     */
    private val collapsedSections = mutableSetOf<Int>()

    var onSelectionChanged: (() -> Unit)? = null

    val selectedCount: Int get() = selectedIds.size

    val selectedBytes: Long get() = files.filter { selectedIds.contains(it.id) }.sumOf { it.bytes }

    /** 参与压缩的张数（动图和文件不在了的不算），用来判断「全选」还值不值得点。 */
    val selectableCount: Int get() = files.count { it.selectable }

    fun selectedFiles(): List<EmojiFile> = files.filter { selectedIds.contains(it.id) }

    fun submit(list: List<EmojiFile>) {
        files = list
        // 动图和文件已经不在的不参与压缩，之前勾上的要去掉。
        val selectable = list.filter { it.selectable }.map { it.id }.toSet()
        selectedIds.retainAll(selectable)
        rebuild()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectAllStatic() {
        files.filter { it.selectable }.forEach { selectedIds.add(it.id) }
        notifySelectionChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifySelectionChanged()
    }

    /** 段标题整行点一下：折叠 / 展开这一段（用户 m11668 第 2 条）。 */
    fun toggleSectionFold(titleRes: Int) {
        if (!collapsedSections.add(titleRes)) collapsedSections.remove(titleRes)
        rebuild()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    /** 段标题右边的「全选本档」：这一段里能压的全勾上，已经全勾上就全摘掉。 */
    fun toggleSectionSelection(titleRes: Int) {
        val group = sectionFiles(titleRes).filter { it.selectable }
        if (group.isEmpty()) return
        if (group.all { selectedIds.contains(it.id) }) {
            group.forEach { selectedIds.remove(it.id) }
        } else {
            group.forEach { selectedIds.add(it.id) }
        }
        notifySelectionChanged()
    }

    private fun rebuild() {
        rows.clear()
        val sorted = files.sortedByDescending { it.bytes }
        SECTION_ORDER.forEach { key -> addSection(key, sorted.filter { sectionKeyOf(it) == key }) }
    }

    /** 这张图落在哪一段。段的划分只写在这里，[rebuild] 和段上的「全选本档」都问它。 */
    private fun sectionKeyOf(file: EmojiFile): Int = when {
        file.bytes >= BIG -> R.string.compress_section_big
        file.bytes >= MB -> R.string.compress_section_mid
        else -> R.string.compress_section_small
    }

    /** 某一段里的全部文件（不分可不可压）。 */
    private fun sectionFiles(key: Int): List<EmojiFile> = files.filter { sectionKeyOf(it) == key }

    private fun addSection(titleRes: Int, group: List<EmojiFile>) {
        if (group.isEmpty()) return
        rows.add(CompressRow.Section(titleRes, group.size, group.sumOf { it.bytes }))
        // 折起来的那一段只留标题这一行；勾选不动，展开还是原来那些
        if (titleRes in collapsedSections) return
        group.forEach { rows.add(CompressRow.Item(it)) }
    }

    private fun notifySelectionChanged() {
        notifyItemRangeChanged(0, rows.size)
        onSelectionChanged?.invoke()
    }

    private fun toggle(file: EmojiFile) {
        if (!file.selectable) return
        if (!selectedIds.add(file.id)) selectedIds.remove(file.id)
        notifySelectionChanged()
    }

    /** 段标题占满整行，格子占一格 —— 交给 [androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup]。 */
    fun spanSize(position: Int): Int =
        if (rows.getOrNull(position) is CompressRow.Section) SPAN else 1

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is CompressRow.Section) TYPE_SECTION else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionHolder(inflater.inflate(R.layout.item_compress_section, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_compress_emoji, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CompressRow.Section -> (holder as SectionHolder).bind(row)
            is CompressRow.Item -> (holder as ItemHolder).bind(row.file)
        }
    }

    private inner class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val fold: TextView = view.findViewById(R.id.compressSectionFold)
        private val title: TextView = view.findViewById(R.id.compressSectionTitle)
        private val meta: TextView = view.findViewById(R.id.compressSectionMeta)
        private val selectAll: TextView = view.findViewById(R.id.compressSectionSelectAll)

        fun bind(row: CompressRow.Section) {
            val context = itemView.context
            val collapsed = row.titleRes in collapsedSections
            val group = sectionFiles(row.titleRes)
            val selectable = group.filter { it.selectable }
            val chosen = group.count { selectedIds.contains(it.id) }

            fold.text = if (collapsed) "▸" else "▾"
            title.setText(row.titleRes)
            meta.text = buildString {
                append(
                    context.getString(
                        R.string.compress_section_meta,
                        row.count,
                        StorageUsage.formatBytes(context, row.bytes)
                    )
                )
                // 折起来之后，这一段的勾选在屏幕上只剩这行字看得见（底部合计只算总数）
                if (chosen > 0) append(context.getString(R.string.compress_section_chosen, chosen))
            }

            val allChosen = selectable.isNotEmpty() && selectable.all { selectedIds.contains(it.id) }
            selectAll.setText(if (allChosen) R.string.compress_section_clear else R.string.compress_section_all)
            selectAll.isEnabled = selectable.isNotEmpty()
            selectAll.alpha = if (selectable.isNotEmpty()) 1f else 0.4f
            selectAll.setOnClickListener { toggleSectionSelection(row.titleRes) }
            itemView.setOnClickListener { toggleSectionFold(row.titleRes) }
        }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.compressThumb)
        private val check: CheckBox = view.findViewById(R.id.compressCheck)
        private val badge: TextView = view.findViewById(R.id.compressBadge)
        private val name: TextView = view.findViewById(R.id.compressItemName)
        private val size: TextView = view.findViewById(R.id.compressItemSize)

        fun bind(file: EmojiFile) {
            name.text = file.name
            size.text = itemView.context.getString(R.string.compress_item_meta, file.width, file.height)

            val exists = !file.missing && File(file.path).isFile
            if (exists) {
                Glide.with(thumb)
                    .load(File(file.path))
                    .centerCrop()
                    .into(thumb)
            } else {
                Glide.with(thumb).clear(thumb)
                thumb.setImageDrawable(null)
            }

            when {
                file.missing -> {
                    badge.setText(R.string.compress_badge_missing)
                    badge.visibility = View.VISIBLE
                }
                file.animated -> {
                    badge.setText(R.string.compress_badge_animated)
                    badge.visibility = View.VISIBLE
                }
                else -> badge.visibility = View.GONE
            }

            // 不参与压缩的那些整格压暗，勾选框也不给点。
            val selectable = file.selectable
            itemView.alpha = if (selectable) 1f else 0.45f
            check.isEnabled = selectable
            check.isChecked = selectedIds.contains(file.id)
            itemView.setOnClickListener { toggle(file) }
        }
    }

    private val EmojiFile.selectable: Boolean
        get() = !animated && !missing && ImageUtil.isImageFile(path)
}
