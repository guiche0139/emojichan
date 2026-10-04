package com.aris.emojichan.storage

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aris.emojichan.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.roundToInt

/**
 * 「忽略列表」页里的名单（v0.2.006 一行一对；v0.2.009 起一行一次忽略，用户 m11102）：
 * 左边最多四张缩略图，中间「名字A ↔ 名字B」或「12 张：A、B、C…」加一行「覆盖 N 对」，
 * 末尾一个「撤销」。
 *
 * 名单不长（一次「忽略这组」只记一条，用户点过几次就有几行），所以照 MainActivity 里那份
 * 应用清单的做法写：普通 ListView + 行布局逐控件填值，不引 RecyclerView。
 */
class IgnoreListAdapter(private val context: Context) : BaseAdapter() {

    /** 一行：一次忽略。 */
    data class Row(val record: String, val members: List<DuplicateFinder.Item>, val pairs: Int)

    private val rows = mutableListOf<Row>()

    /** 点「撤销」：（这一行）。写回偏好与重新分组由宿主页面做。 */
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
        view.findViewById<TextView>(R.id.ignoreNames).text = title(row)
        view.findViewById<TextView>(R.id.ignoreDetail).text =
            context.getString(R.string.similar_ignore_row_pairs, row.pairs)
        thumbs(view.findViewById(R.id.ignoreThumbs), row.members)
        view.findViewById<MaterialButton>(R.id.btnIgnoreRemove).setOnClickListener {
            onRemove?.invoke(row)
        }
        return view
    }

    /** 两张的直接报名字（跟从前一样）；更多的报张数 + 头几个名字，行里放不下全部。 */
    private fun title(row: Row): String {
        val names = row.members.map { it.name }
        if (names.size <= 2) {
            return context.getString(R.string.similar_ignore_row_desc, names[0], names[1])
        }
        val head = names.take(3).joinToString("、")
        val preview = if (names.size > 3) head + "…" else head
        return context.getString(R.string.similar_ignore_row_many, names.size, preview)
    }

    /** 最多四张缩略图，多出来的用「+N」顶上 —— 一次忽略几十张时不该把行撑爆。 */
    private fun thumbs(container: LinearLayout, members: List<DuplicateFinder.Item>) {
        container.removeAllViews()
        val side = dp(36)
        members.take(MAX_THUMBS).forEachIndexed { index, item ->
            val image = ImageView(context)
            image.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                if (index > 0) marginStart = dp(4)
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(image)
                .load(File(item.path))
                .transform(RoundedCorners(dp(8)))
                .into(image)
            container.addView(image)
        }
        val rest = members.size - MAX_THUMBS
        if (rest > 0) {
            val more = TextView(context).apply {
                text = context.getString(R.string.similar_ignore_row_more, rest)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, side)
            params.gravity = Gravity.CENTER_VERTICAL
            params.marginStart = dp(4)
            container.addView(more, params)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        /** 行里最多摆几张缩略图，剩下的用「+N」。 */
        const val MAX_THUMBS = 4
    }
}
