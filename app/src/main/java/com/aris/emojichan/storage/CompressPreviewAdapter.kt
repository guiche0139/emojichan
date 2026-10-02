package com.aris.emojichan.storage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aris.emojichan.R
import com.bumptech.glide.Glide
import java.io.File

/**
 * 预览页的网格：载的是缓存目录里那张压好的新图，下面是「原大小 → 新大小」。
 * 点一格看大图（大图里能按住对比原图）。
 */
class CompressPreviewAdapter(
    private val items: List<PendingCompress>,
    private val onClick: (PendingCompress) -> Unit
) : RecyclerView.Adapter<CompressPreviewAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.previewThumb)
        val name: TextView = view.findViewById(R.id.previewName)
        val sizes: TextView = view.findViewById(R.id.previewSizes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_compress_preview, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // 压完的新图本身就不大，限一下尺寸免得 Glide 按原分辨率铺屏幕。
        Glide.with(holder.thumb)
            .load(File(item.compressed))
            .override(720, 720)
            .fitCenter()
            .into(holder.thumb)

        holder.name.text = item.name
        holder.sizes.text = context.getString(
            R.string.preview_item_sizes,
            StorageUsage.formatBytes(context, item.originalBytes),
            StorageUsage.formatBytes(context, item.compressedBytes)
        )
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
