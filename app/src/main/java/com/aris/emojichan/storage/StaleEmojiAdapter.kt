package com.aris.emojichan.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.aris.emojichan.data.EmojiEntity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import java.io.File

/**
 * 「长时间不使用的表情」页的网格（v0.2.013，用户 m11668 第 5 条）。
 *
 * 跟主页的 [com.aris.emojichan.EmojiGridAdapter] 比只差三点：勾选框常显（这一页进来就是挑东西），
 * 名字下面多一行「多久没发过」（用户挑的就是这行时间），以及没有 DiffUtil ——
 * 这一页要的刷新只有两种：整屏换（换范围 / 读库 / 全选）和自己勾了一下（单格重画），
 * 直接 notifyDataSetChanged / notifyItemChanged 比让 DiffUtil 猜省事，也不会漏掉勾选状态。
 */
class StaleEmojiAdapter(
    private val ageText: (EmojiEntity) -> String,
    private val onEmojiClick: (EmojiEntity) -> Unit,
    private val selectedIds: () -> Set<Long>
) : RecyclerView.Adapter<StaleEmojiAdapter.ViewHolder>() {

    private var items: List<EmojiEntity> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiImage: ImageView = view.findViewById(R.id.emojiImage)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val emojiName: TextView = view.findViewById(R.id.emojiName)
        val emojiAge: TextView = view.findViewById(R.id.emojiAge)
    }

    /** 现在屏幕上这一批（按页面的顺序）。选完东西要按它算「整批都选中了没」。 */
    fun current(): List<EmojiEntity> = items

    /** 整屏换一批：换范围、重新读库、全选都走这儿。 */
    fun submit(list: List<EmojiEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stale_emoji, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = items[position]

        Glide.with(holder.itemView.context)
            .load(File(emoji.filePath))
            .transform(CenterCrop(), RoundedCorners(16))
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
}