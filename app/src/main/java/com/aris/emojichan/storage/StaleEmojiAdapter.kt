package com.aris.emojichan.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.bumptech.glide.Glide
import java.io.File

/**
 * 「长时间不使用的表情」页的网格（v0.2.013，用户 m11668 第 5 条）。
 *
 * 跟主页的 [com.aris.emojichan.EmojiGridAdapter] 比只差两点：勾选框常显（这一页进来就是挑东西），
 * 名字下面多一行「多久没发过」（用户挑的就是这行时间）。
 *
 * v0.2.215（性能 T1-3）改了刷新的口径：整屏换走 DiffUtil，只把真的变了的格子标脏；
 * 勾选态（整页全选 / 取消全选）单独走 [notifySelectionChanged]，只重绑屏幕上看得见的格子 ——
 * 以前整屏换和全选都是 notifyDataSetChanged，全选一下等于整屏重绑一遍。
 */
class StaleEmojiAdapter(
    private val ageText: (EmojiEntity) -> String,
    private val onEmojiClick: (EmojiEntity) -> Unit,
    private val selectedIds: () -> Set<Long>
) : RecyclerView.Adapter<StaleEmojiAdapter.ViewHolder>() {

    private var items: List<EmojiEntity> = emptyList()

    /** 挂着的那个列表（全选 / 取消全选时按它算屏幕上看得见几格）。 */
    private var attachedRecycler: RecyclerView? = null

    /** 图片解码尺寸：3 列等分宽度（扣掉网格左右各 12dp）× 图上那块 118dp，只算一次。 */
    private var targetW = 0
    private var targetH = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiImage: ImageView = view.findViewById(R.id.emojiImage)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val emojiName: TextView = view.findViewById(R.id.emojiName)
        val emojiAge: TextView = view.findViewById(R.id.emojiAge)
    }

    /** 现在屏幕上这一批（按页面的顺序）。选完东西要按它算「整批都选中了没」。 */
    fun current(): List<EmojiEntity> = items

    /**
     * 整屏换一批：换范围、重新读库都走这儿（v0.2.215，性能 T1-3）。
     *
     * 以前是 notifyDataSetChanged —— 换判定范围之后整屏十几格全部重绑（每格重新走一遍 Glide）。
     * 这里按 id 比一遍，只有真的换了内容的格子重建。注意：条目本身没变就不会重绑，
     * 所以「勾选变了」得单独叫 [notifySelectionChanged]，不能指望这一条顺带重画。
     */
    fun submit(list: List<EmojiEntity>) {
        val before = items
        items = list
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = before.size

            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                before[oldItemPosition].id == items[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                before[oldItemPosition] == items[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    /** 勾选变了（全选 / 取消全选）：只重绑屏幕上看得见的那些格子。 */
    fun notifySelectionChanged() {
        val recycler = attachedRecycler ?: return
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val holder = recycler.getChildViewHolder(child)
            if (holder !is ViewHolder) continue
            // 位置问列表要：ViewHolder.bindingAdapterPosition 在这个 RecyclerView 版本里没有
            val position = recycler.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION) onBindViewHolder(holder, position)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecycler === recyclerView) attachedRecycler = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stale_emoji, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = items[position]

        // v0.2.215（性能 T0-5）：显式给解码尺寸（不必等 ImageView 完成一次 layout 才解析目标尺寸，
        // 缓存键也稳定），并去掉 transform(CenterCrop(), RoundedCorners(16)) —— 那两层变换对每张图
        // 都要多跑一遍像素变换、多分配一张位图，而圆角与描边本来就由 MaterialCardView
        // （cardCornerRadius=16dp）+ ImageView 自己的 8dp 内缩 + fitCenter 负责，跟主页网格一个口径。
        ensureTargetSize(holder.itemView)
        Glide.with(holder.itemView.context)
            .load(File(emoji.filePath))
            .override(targetW, targetH)
            .placeholder(R.drawable.ic_emoji_placeholder)
            .error(R.drawable.ic_emoji_placeholder)
            .into(holder.emojiImage)

        holder.emojiName.text = emoji.name
        holder.emojiAge.text = ageText(emoji)

        holder.checkBox.isChecked = selectedIds().contains(emoji.id)
        // 勾选框自己 clickable，会把点击吞掉 —— 跟主页一样，点它也算「点了这一格」
        holder.checkBox.setOnClickListener { onEmojiClick(emoji) }
        holder.itemView.setOnClickListener { onEmojiClick(emoji) }
    }

    /** 3 列等分宽度（扣掉网格左右各 12dp 的内边距）× 图上那块 118dp。 */
    private fun ensureTargetSize(view: View) {
        if (targetW > 0 && targetH > 0) return
        val dm = view.resources.displayMetrics
        val padding = (24f * dm.density).toInt()
        targetW = ((dm.widthPixels - padding) / 3).coerceAtLeast(1)
        targetH = (118f * dm.density).toInt().coerceAtLeast(1)
    }
}