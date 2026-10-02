package com.aris.emojichan

import android.content.Context

/**
 * 「大表情压缩怎么压」这一组偏好：有损还是无损、压缩页要不要列出动图。
 *
 * 默认无损（v0.1.409 起，用户 m08399 第 2 条）：压缩是要替换掉原图的，画质先保到底，嫌压得不够
 * 再自己去高级设置换成有损。
 *
 * 为什么单开一份、放在高级设置里：这一条改完不会立刻有感觉，要等到下次压缩才生效；而无损
 * 压出来的文件往往比原图还大（绝大多数原图本身就是有损 JPEG），压不动就跳过、等于白跑一趟。
 * 属于「不知道自己想要什么就别乱动」的那一类，跟发送方式、原文件处理摆在一起。
 *
 * 跟界面长相无关，所以不塞进 [UiPrefs]。
 */
object CompressPrefs {

    private const val PREFS = "compress_settings"
    private const val KEY_LOSSLESS = "lossless"
    private const val KEY_SHOW_ANIMATED = "show_animated"

    /** true = 无损（WebP，Android 11 以下退成 PNG），默认就是它；false = 有损 WebP 质量 80。 */
    fun lossless(context: Context): Boolean = prefs(context).getBoolean(KEY_LOSSLESS, true)

    fun setLossless(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOSSLESS, value).apply()
    }

    /**
     * 压缩页要不要列出动图。默认 false = 不列。
     *
     * 动图（GIF / 动态 WebP）一概不参与压缩，列出来只会让网格更长、还得逐张说明「这张不能选」；
     * 默认藏起来，需要核对「库里那张 GIF 还在不在」的人自己去高级设置打开。
     */
    fun showAnimated(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_ANIMATED, false)

    fun setShowAnimated(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ANIMATED, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
