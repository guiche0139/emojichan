package com.aris.emojichan.storage

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.roundToInt

/**
 * 合并检索页的列表（v0.1.411，用户 m08893：完全相同与相似放在同一页）。
 *
 * 一条列表里装四样东西：两段的小标题、内容完全相同的组、画面相近的组。两段的交互本来就不同，
 * 这里没有把它们抹平：
 * - 「内容完全相同」的组：一组里只有一张是真的，「保留哪张」有默认答案（最早导入的那张），
 *   点缩略图改保留对象，点那一组的按钮删掉其余；
 * - 「画面相近」的组：没有对错，只有用户的选择，点缩略图是「选中要删的」，选完按底部那一条才真的删。
 *
 * 两段互斥（由页面保证）：已经归进「完全相同」的图不再出现「画面相近」里，同一张图只出现一次。
 *
 * 「画面相近」那一段的顺序由 [SimilarFinder] 排好（差得少的在前），这里只照着摆；那一段的底线
 * （内部是感知哈希的距离，页面显示相似度百分比）v0.1.412 起存在 [com.aris.emojichan.SimilarPrefs] 里，页面上不再有开关。
 */
class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER_DUPLICATE = 0
        const val TYPE_HEADER_SIMILAR = 1
        const val TYPE_DUPLICATE_GROUP = 2
        const val TYPE_SIMILAR_GROUP = 3
    }

    private val duplicateGroups = mutableListOf<DuplicateFinder.Group>()
    private val similarGroups = mutableListOf<SimilarFinder.Group>()

    /** 「完全相同」每组当前保留的那张；点过缩略图就是点的那张，没点过按组里最早导入的算。 */
    private val keptIds = HashMap<String, Long>()

    /** 「相近」选中要删的那些（跨组）。用 LinkedHashSet：删除顺序跟用户点的顺序一致，排查起来好读。 */
    private val selected = LinkedHashSet<Long>()

    /** 「相近」每张的体积，算「能省多少」用。 */
    private val sizes = HashMap<Long, Long>()

    /** 这一轮的感知哈希：代表被删掉之后要拿它重算差距。 */
    private var hashes: Map<Long, Long> = emptyMap()

    /** 当前的底线（感知哈希的距离，页面按相似度百分比显示）：只用来写那一段小标题的说明，顺序由 [SimilarFinder] 排。 */
    var threshold: Int = SimilarFinder.DEFAULT

    /** 点「删掉其余 N 张」时回调：（这一组，要删的那几张）。 */
    var onDeleteDuplicateGroup: ((DuplicateFinder.Group, List<DuplicateFinder.Item>) -> Unit)? = null

    /** 长按「相近」的一张：打开它的详情页。 */
    var onOpen: ((DuplicateFinder.Item) -> Unit)? = null

    /** 「相近」的选择变了：页面更新底部那条。 */
    var onSelectionChanged: (() -> Unit)? = null

    val duplicateCount: Int get() = duplicateGroups.size

    val similarCount: Int get() = similarGroups.size

    val selectedCount: Int get() = selected.size

    val selectedBytes: Long get() = selected.sumOf { sizes[it] ?: 0L }

    fun selectedIds(): Set<Long> = selected.toSet()

    fun keptIdOf(group: DuplicateFinder.Group): Long =
        keptIds[group.sha256] ?: group.members.first().id

    fun submitDuplicate(list: List<DuplicateFinder.Group>) {
        duplicateGroups.clear()
        duplicateGroups.addAll(list)
        keptIds.keys.retainAll(list.map { it.sha256 }.toSet())
        notifyDataSetChanged()
    }

    fun submitSimilar(list: List<SimilarFinder.Group>, hashMap: Map<Long, Long>) {
        similarGroups.clear()
        similarGroups.addAll(list)
        hashes = hashMap
        sizes.clear()
        list.forEach { group -> group.members.forEach { sizes[it.id] = it.bytes } }
        // 上一轮选中的可能已经被删了，或者在新范围下不再出现 —— 只留还在列表里的。
        selected.retainAll(sizes.keys)
        notifyDataSetChanged()
    }

    /** 这一组已经处理完了，从列表里拿掉。 */
    fun removeDuplicateGroup(sha256: String) {
        val index = duplicateGroups.indexOfFirst { it.sha256 == sha256 }
        if (index < 0) return
        duplicateGroups.removeAt(index)
        keptIds.remove(sha256)
        notifyDataSetChanged()
    }

    /** 这些「相近」的已经删掉了：从组里拿掉，只剩一张的组整组消失（一张不存在「相近」问题）。 */
    fun removeIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        for (index in similarGroups.indices) {
            similarGroups[index] = similarGroups[index].without(ids, hashes)
        }
        similarGroups.removeAll { it.members.size < 2 }
        // 删掉几张之后组的「最多差几位」会变（代表可能换了），顺序得重排一次
        similarGroups.sortWith(SimilarFinder.SIMILARITY_ORDER)
        selected.removeAll(ids)
        ids.forEach { sizes.remove(it) }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = 2 + duplicateGroups.size + similarGroups.size

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_HEADER_DUPLICATE
        position == similarHeaderPosition() -> TYPE_HEADER_SIMILAR
        position < similarHeaderPosition() -> TYPE_DUPLICATE_GROUP
        else -> TYPE_SIMILAR_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER_DUPLICATE -> HeaderHolder(
                inflater.inflate(R.layout.item_search_header, parent, false),
                similar = false
            )
            TYPE_HEADER_SIMILAR -> HeaderHolder(
                inflater.inflate(R.layout.item_search_header, parent, false),
                similar = true
            )
            TYPE_DUPLICATE_GROUP -> DuplicateGroupHolder(
                inflater.inflate(R.layout.item_duplicate_group, parent, false)
            )
            else -> SimilarGroupHolder(
                inflater.inflate(R.layout.item_similar_group, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind()
            is DuplicateGroupHolder -> holder.bind(position - 1)
            is SimilarGroupHolder -> holder.bind(position - similarStart())
        }
    }

    /** 「画面相近」那一段的小标题在第几行：两段的小标题各自钉在自己那一段前面。 */
    private fun similarHeaderPosition(): Int = 1 + duplicateGroups.size

    private fun similarStart(): Int = 2 + duplicateGroups.size

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    /** 一段的小标题：标题 + 一行说明。 */
    inner class HeaderHolder(view: View, private val similar: Boolean) :
        RecyclerView.ViewHolder(view) {

        private val title: TextView = view.findViewById(R.id.searchSectionTitle)
        private val note: TextView = view.findViewById(R.id.searchSectionNote)

        fun bind() {
            title.setText(
                if (similar) R.string.search_section_similar else R.string.search_section_duplicate
            )
            val count = if (similar) similarGroups.size else duplicateGroups.size
            val context = itemView.context
            note.text = when {
                // 「画面相近」那一段的两句话里都带着当前底线，用户才知道要去哪儿改
                similar && count == 0 -> context.getString(
                    R.string.search_section_similar_none,
                    SimilarFinder.similarity(threshold)
                )
                similar -> context.getString(
                    R.string.search_section_similar_note,
                    SimilarFinder.similarity(threshold)
                )
                count == 0 -> context.getString(R.string.search_section_duplicate_none)
                else -> context.getString(R.string.search_section_duplicate_note)
            }
        }
    }

    /** 「内容完全相同」的一组：缩略图点一下换保留对象，按钮删掉其余。 */
    inner class DuplicateGroupHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val title: TextView = view.findViewById(R.id.duplicateGroupTitle)
        private val wasted: TextView = view.findViewById(R.id.duplicateGroupWasted)
        private val thumbs: LinearLayout = view.findViewById(R.id.duplicateThumbs)
        private val keep: TextView = view.findViewById(R.id.duplicateGroupKeep)
        private val delete: MaterialButton = view.findViewById(R.id.btnDuplicateDelete)

        fun bind(index: Int) {
            val group = duplicateGroups[index]
            val context = itemView.context
            val keepId = keptIdOf(group)
            val keeper = group.members.first { it.id == keepId }
            title.text = context.getString(R.string.duplicate_group_title, group.members.size)
            wasted.text = context.getString(
                R.string.duplicate_group_wasted,
                StorageUsage.formatBytes(context, group.wastedBytes)
            )
            keep.text = context.getString(R.string.duplicate_group_keep, keeper.name)
            delete.text = context.getString(R.string.duplicate_group_delete, group.members.size - 1)
            delete.setOnClickListener {
                onDeleteDuplicateGroup?.invoke(group, DuplicateFinder.redundant(group, keptIdOf(group)))
            }

            thumbs.removeAllViews()
            group.members.forEach { item ->
                thumbs.addView(duplicateThumb(context, item, item.id == keepId) {
                    keptIds[group.sha256] = item.id
                    notifyItemChanged(1 + index)
                })
            }
        }

        /** 一张缩略图：被保留的那张描边加粗，点一下就把保留换成它。 */
        private fun duplicateThumb(
            context: Context,
            item: DuplicateFinder.Item,
            kept: Boolean,
            onClick: () -> Unit
        ): ImageView {
            val side = dp(context, 76f)
            val image = ImageView(context)
            image.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                marginEnd = dp(context, 8f)
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            val inset = dp(context, 2f)
            image.setPadding(inset, inset, inset, inset)
            image.setBackgroundResource(
                if (kept) R.drawable.bg_duplicate_thumb_kept else R.drawable.bg_duplicate_thumb
            )
            image.contentDescription = context.getString(R.string.duplicate_thumb_desc, item.name)
            Glide.with(image)
                .load(File(item.path))
                .transform(RoundedCorners(dp(context, 10f)))
                .into(image)
            image.setOnClickListener { onClick() }
            return image
        }
    }

    /** 「画面相近」的一组：缩略图点一下选中／取消，长按看详情。 */
    inner class SimilarGroupHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val title: TextView = view.findViewById(R.id.similarGroupTitle)
        private val wasted: TextView = view.findViewById(R.id.similarGroupWasted)
        private val thumbs: LinearLayout = view.findViewById(R.id.similarThumbs)

        fun bind(index: Int) {
            val group = similarGroups[index]
            val context = itemView.context
            title.text = context.getString(
                R.string.similar_group_title,
                group.members.size,
                SimilarFinder.similarity(group.maxDistance)
            )
            wasted.text = context.getString(
                R.string.similar_group_wasted,
                StorageUsage.formatBytes(context, group.spareBytes)
            )
            thumbs.removeAllViews()
            group.members.forEach { item ->
                thumbs.addView(similarThumb(context, item, item.id in selected))
            }
        }

        /** 一张缩略图：选中的右上角挂个勾。点一下选中／取消，长按看详情。 */
        private fun similarThumb(context: Context, item: DuplicateFinder.Item, chosen: Boolean): View {
            val side = dp(context, 76f)
            val frame = FrameLayout(context)
            frame.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                marginEnd = dp(context, 8f)
            }
            frame.setBackgroundResource(R.drawable.bg_duplicate_thumb)
            frame.contentDescription = context.getString(R.string.similar_thumb_desc, item.name)

            val image = ImageView(context)
            image.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            val inset = dp(context, 2f)
            image.setPadding(inset, inset, inset, inset)
            Glide.with(image)
                .load(File(item.path))
                .transform(RoundedCorners(dp(context, 10f)))
                .into(image)
            frame.addView(image)

            if (chosen) {
                val badgeSide = dp(context, 22f)
                val badge = TextView(context)
                badge.layoutParams = FrameLayout.LayoutParams(badgeSide, badgeSide).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(context, 4f)
                    marginEnd = dp(context, 4f)
                }
                badge.setBackgroundResource(R.drawable.bg_similar_badge)
                badge.gravity = Gravity.CENTER
                badge.text = "✓"
                badge.setTextColor(Color.WHITE)
                badge.textSize = 13f
                frame.addView(badge)
            }

            frame.setOnClickListener {
                if (!selected.remove(item.id)) selected.add(item.id)
                val index = similarGroups.indexOfFirst { group ->
                    group.members.any { it.id == item.id }
                }
                if (index >= 0) notifyItemChanged(similarStart() + index)
                onSelectionChanged?.invoke()
            }
            frame.setOnLongClickListener {
                onOpen?.invoke(item)
                true
            }
            return frame
        }
    }
}
