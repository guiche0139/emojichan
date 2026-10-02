package com.aris.emojichan.storage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aris.emojichan.R
import com.google.android.material.color.MaterialColors

/**
 * 存储概览的环形图。
 *
 * 自己不认数据，只把 [Slice] 按字节数摊到一圈上；哪个颜色对哪种格式由调用方决定，
 * 图例也在外面 —— 这块视图只管画一个圈和圈里那行总数。
 */
class StoragePieView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val label: String, val bytes: Long, val color: Int)

    private val density = resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 26f * density
    }

    private val box = RectF()

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 19f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = MaterialColors.getColor(this@StoragePieView, com.google.android.material.R.attr.colorOnSurface)
    }

    private var slices: List<Slice> = emptyList()
    private var centerText = ""

    fun setSlices(slices: List<Slice>, centerText: String) {
        this.slices = slices
        this.centerText = centerText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width, height) - ring.strokeWidth) / 2f
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.sumOf { it.bytes }
        if (total <= 0L) {
            ring.color = ContextCompat.getColor(context, R.color.divider)
            canvas.drawCircle(cx, cy, radius, ring)
        } else {
            // 相邻两片之间留一道缝，纯色挨着纯色时看不出分界。
            val gap = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { slice ->
                val sweep = slice.bytes * 360f / total
                ring.color = slice.color
                canvas.drawArc(box, start + gap / 2f, (sweep - gap).coerceAtLeast(1f), false, ring)
                start += sweep
            }
        }

        if (centerText.isNotEmpty()) {
            canvas.drawText(centerText, cx, cy + centerPaint.textSize / 3f, centerPaint)
        }
    }
}
