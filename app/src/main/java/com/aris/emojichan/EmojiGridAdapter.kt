package com.aris.emojichan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.aris.emojichan.data.EmojiEntity
import java.io.File

class EmojiGridAdapter(
    private val onEmojiClick: (EmojiEntity) -> Unit,
    private val onEmojiLongClick: (EmojiEntity) -> Unit,
    private val onFavoriteClick: (EmojiEntity) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val selectedIds: () -> Set<Long>
) : ListAdapter<EmojiEntity, EmojiGridAdapter.ViewHolder>(DiffCallback()) {

    /** 挂上来的网格：勾选变化时只改可见那几格，不再 notifyDataSetChanged（v0.2.215）。 */
    private var attachedRecycler: RecyclerView? = null

    /** v0.2.215：解码尺寸（3 列等分宽度 × 图上那块固定 118dp），第一次绑定时按屏幕算一次。 */
    private var targetW = 0
    private var targetH = 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiImage: ImageView = view.findViewById(R.id.emojiImage)
        val favoriteIcon: ImageView = view.findViewById(R.id.favoriteIcon)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val emojiName: TextView = view.findViewById(R.id.emojiName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emoji, parent, false)
        return ViewHolder(view)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecycler === recyclerView) attachedRecycler = null
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = getItem(position)

        // Load image
        // v0.2.215：① 显式给解码尺寸 —— 不必等 ImageView 完成一次 layout 才去解析目标尺寸，
        // 缓存键也稳定；② 去掉 transform(CenterCrop(), RoundedCorners(16))：那两层变换对每张图
        // 都要多跑一遍像素变换、多分配一张位图，而且 16 是像素不是 dp。圆角与描边本来就由
        // MaterialCardView（cardCornerRadius=16dp）+ ImageView 自己的 8dp 内缩 + fitCenter 负责。
        ensureTargetSize(holder.itemView)
        Glide.with(holder.itemView.context)
            .load(File(emoji.filePath))
            .override(targetW, targetH)
            .placeholder(R.drawable.ic_emoji_placeholder)
            .error(R.drawable.ic_emoji_placeholder)
            .into(holder.emojiImage)

        // Set name
        holder.emojiName.text = emoji.name

        // Show favorite icon
        holder.favoriteIcon.visibility = if (emoji.isFavorite) View.VISIBLE else View.GONE
        holder.favoriteIcon.setOnClickListener {
            onFavoriteClick(emoji)
        }

        // Show/hide checkbox in selection mode
        val selectionMode = isSelectionMode()
        holder.checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedIds().contains(emoji.id)
        // 直接点勾选框也要走「点了这一项」这条线：CheckBox 自己 clickable，
        // 会把点击吞掉 —— 原来只把自己勾上，选中集合不更新，于是删掉的不是用户以为的那批（emc-1-020）。
        holder.checkBox.setOnClickListener { onEmojiClick(emoji) }

        // Click listeners
        holder.itemView.setOnClickListener {
            onEmojiClick(emoji)
        }

        holder.itemView.setOnLongClickListener {
            onEmojiLongClick(emoji)
            true
        }
    }

    /** 3 列等分宽度（扣掉网格左右各 12dp 的内边距）× 图上那块 118dp。 */
    private fun ensureTargetSize(view: View) {
        if (targetW > 0 && targetH > 0) return
        val dm = view.resources.displayMetrics
        val padding = (24f * dm.density).toInt()
        targetW = ((dm.widthPixels - padding) / 3).coerceAtLeast(1)
        targetH = (118f * dm.density).toInt().coerceAtLeast(1)
    }

    /**
     * 只刷新勾选态（v0.2.215）。
     *
     * 原来每点一次都 notifyDataSetChanged：整屏十几格全部重绑，每格重建一次 Glide 请求、
     * 重设四组 listener。勾选态的真相是 [selectedIds]，这里不存本地状态，所以直接改勾选框是安全的。
     */
    fun updateSelection(ids: Set<Long>) {
        val recycler = attachedRecycler ?: return
        val selectionMode = isSelectionMode()
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val holder = recycler.getChildViewHolder(child)
            if (holder !is ViewHolder) continue
            // 位置问列表要：ViewHolder.bindingAdapterPosition 在这个 RecyclerView 版本里没有
            val position = recycler.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position >= itemCount) continue
            val visibility = if (selectionMode) View.VISIBLE else View.GONE
            if (holder.checkBox.visibility != visibility) holder.checkBox.visibility = visibility
            val checked = ids.contains(getItem(position).id)
            if (holder.checkBox.isChecked != checked) holder.checkBox.isChecked = checked
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<EmojiEntity>() {
        override fun areItemsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean {
            return oldItem.id == newItem.id
        }

        /**
         * v0.2.215：只比真正画到界面上的字段。
         *
         * 原来 `oldItem == newItem`（data class 全字段）：发一次表情就会写 usageCount / lastUsedTime
         * （EmojiDao.recordUsage），于是整表被重查一次，这一格也白白重绑 + 重新请求一次 Glide。
         * filePath 必须留着：压缩替换文件之后（updateCompressed）网格靠它换新图。
         */
        override fun areContentsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean {
            return oldItem.name == newItem.name &&
                oldItem.isFavorite == newItem.isFavorite &&
                oldItem.filePath == newItem.filePath &&
                oldItem.fileSize == newItem.fileSize
        }
    }

}
