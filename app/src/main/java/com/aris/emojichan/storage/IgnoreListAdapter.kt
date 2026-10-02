package com.aris.emojichan.storage

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.aris.emojichan.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.roundToInt

/**
 * 「忽略列表」对话框里的名单（v0.2.006，用户 m10497）：一行一对，左边两张缩略图，中间
 * 「名字A ↔ 名字B」，末尾一个「不再忽略」。
 *
 * 名单不长（一次「忽略这组」最多添 n(n-1)/2 对，平时也就几十条），所以照 MainActivity 里那份
 * 应用清单的做法写：普通 ListView + 行布局逐控件填值，不引 RecyclerView。
 */
class IgnoreListAdapter(private val context: Context) : BaseAdapter() {

    /** 一行：被忽略的一对。 */
    data class Row(val key: String, val a: DuplicateFinder.Item, val b: DuplicateFinder.Item)

    private val rows = mutableListOf<Row>()

    /** 点「不再忽略」：（这一行）。写回偏好与重新分组由对话框那边做。 */
    var onRemove: ((Row) -> Unit)? = null

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_similar_ignore, parent, false)
        val row = rows.getOrNull(position) ?: return view
        view.findViewById<TextView>(R.id.ignoreNames).text =
            context.getString(R.string.similar_ignore_row_desc, row.a.name, row.b.name)
        thumb(view.findViewById(R.id.ignoreThumbA), row.a.path)
        thumb(view.findViewById(R.id.ignoreThumbB), row.b.path)
        view.findViewById<MaterialButton>(R.id.btnIgnoreRemove).setOnClickListener {
            onRemove?.invoke(row)
        }
        return view
    }

    private fun thumb(image: ImageView, path: String) {
        val radius = (10f * context.resources.displayMetrics.density).roundToInt()
        Glide.with(image)
            .load(File(path))
            .transform(RoundedCorners(radius))
            .into(image)
    }
}
