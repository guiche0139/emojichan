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
    }

    private val rows = mutableListOf<CompressRow>()
    private val selectedIds = linkedSetOf<Long>()
    private var files: List<EmojiFile> = emptyList()

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

    private fun rebuild() {
        rows.clear()
        val sorted = files.sortedByDescending { it.bytes }
        addSection(R.string.compress_section_big, sorted.filter { it.bytes >= BIG })
        addSection(R.string.compress_section_mid, sorted.filter { it.bytes in MB until BIG })
        addSection(R.string.compress_section_small, sorted.filter { it.bytes < MB })
    }

    private fun addSection(titleRes: Int, group: List<EmojiFile>) {
        if (group.isEmpty()) return
        rows.add(CompressRow.Section(titleRes, group.size, group.sumOf { it.bytes }))
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
        private val title: TextView = view.findViewById(R.id.compressSectionTitle)
        private val meta: TextView = view.findViewById(R.id.compressSectionMeta)

        fun bind(row: CompressRow.Section) {
            title.setText(row.titleRes)
            meta.text = itemView.context.getString(
                R.string.compress_section_meta,
                row.count,
                StorageUsage.formatBytes(itemView.context, row.bytes)
            )
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
