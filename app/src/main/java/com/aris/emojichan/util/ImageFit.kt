package com.aris.emojichan.util

import kotlin.math.roundToInt

/**
 * 详情页图片尺寸计算（v0.1.409 起，用户 m08399 第 3 条）。
 *
 * 目标是一句话：**整张图都看得见，又别浪费地方**。
 * 在此之前详情页用的是 centerCrop，图会被裁掉两边，长图 / 截图更是只能看见中间一块；
 * 现在改成先按图片自己的长宽比算出该占多大，再把卡片撑成那个尺寸。
 *
 * 三条规则：
 *  1. 等比缩放，长宽比永远不变（不拉伸、不变形）；
 *  2. 小图不放大 —— 原图比可用区域还小就按原尺寸摆，免得一张 32×32 的表情糊成一大块；
 *  3. 永远留一圈 [inset]（对应布局里的 padding），图片不贴着卡片边。
 *
 * 纯函数，跟 Android 无关，好单测；实际尺寸由 [com.aris.emojichan.EmojiDetailActivity] 传进来。
 */
object ImageFit {

    /**
     * @param srcWidth 解出来的原图宽，≤ 0 表示读不到尺寸（按正方形摆）
     * @param srcHeight 原图高，≤ 0 同上
     * @param maxWidth 可用区域的宽（已经扣掉外层 padding）
     * @param maxHeight 可用区域的高
     * @param minSide 算出来的宽高都不小于它（太小了看着像坏了）
     * @param inset 图片四周要留出的空白，宽高各加这么多
     * @return 卡片该用的 (宽, 高)
     */
    fun fit(
        srcWidth: Int,
        srcHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        minSide: Int,
        inset: Int = 0
    ): Pair<Int, Int> {
        // 页面还没排完版 / 尺寸离谱：给个最小方块，等布局监听再算一次
        if (maxWidth <= 0 || maxHeight <= 0) return minSide to minSide

        val safeInset = inset.coerceAtLeast(0)
        val availWidth = (maxWidth - safeInset).coerceAtLeast(1)
        val availHeight = (maxHeight - safeInset).coerceAtLeast(1)

        // 读不到原图尺寸：正方形放到能放的最大
        if (srcWidth <= 0 || srcHeight <= 0) {
            val side = minOf(availWidth, availHeight)
            return clamp(side + safeInset, minSide) to clamp(side + safeInset, minSide)
        }

        val scale = minOf(
            availWidth.toDouble() / srcWidth.toDouble(),
            availHeight.toDouble() / srcHeight.toDouble()
        )
        // 小图不放大：scale > 1 时按 1 算
        val factor = if (scale > 1.0) 1.0 else scale
        val drawWidth = (srcWidth * factor).roundToInt().coerceAtLeast(1)
        val drawHeight = (srcHeight * factor).roundToInt().coerceAtLeast(1)
        return clamp(drawWidth + safeInset, minSide) to clamp(drawHeight + safeInset, minSide)
    }

    private fun clamp(value: Int, min: Int): Int = if (value < min) min else value
}
