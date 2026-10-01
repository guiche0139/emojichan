package com.aris.emojichan

import android.net.Uri
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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.aris.emojichan.data.EmojiEntity
import java.io.File

class EmojiGridAdapter(
    private val onEmojiClick: (EmojiEntity) -> Unit,
    private val onEmojiLongClick: (EmojiEntity) -> Unit,
    private val onFavoriteClick: (EmojiEntity) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val selectedIds: () -> Set<Long>
) : ListAdapter<EmojiEntity, EmojiGridAdapter.ViewHolder>(DiffCallback()) {

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

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = getItem(position)

        // Load image
        Glide.with(holder.itemView.context)
            .load(File(emoji.filePath))
            .transform(CenterCrop(), RoundedCorners(16))
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

    class DiffCallback : DiffUtil.ItemCallback<EmojiEntity>() {
        override fun areItemsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: EmojiEntity, newItem: EmojiEntity): Boolean {
            return oldItem == newItem
        }
    }

}
