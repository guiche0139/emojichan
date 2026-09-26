package com.aris.emojichan.sender

import android.content.Context

/**
 * 发送模块里「用户自己能选」的那几项。
 *
 * 目前只有一条：微信里的动图走哪条路。两个方案各有代价，而且哪条更好取决于
 * 微信自己的行为（分享通道保不保动画），只有真机能判定 —— 所以做成开关，用户
 * 试一次就知道该留哪个，不用等我重新发版。
 */
object SenderPrefs {

    private const val PREFS = "sender_settings"
    private const val KEY_WECHAT_GIF_ALBUM = "wechat_gif_album"

    /**
     * 微信动图是否走「+ → 相册」。
     *
     * false（默认）= 走分享：只翻一次微信自己的「选择聊天」页，认得出聊天对象就
     * 自动点掉，认不出用户点一下就发。路径短，但动图会不会被微信的分享通道压成
     * 静态图，得实测。
     * true = 走相册：全程在微信的原生发图流程里，动图一定保动画，代价是要连着翻
     * 三个页面（用户实测反馈：太繁琐）。
     */
    fun wechatGifViaAlbum(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WECHAT_GIF_ALBUM, false)

    fun setWechatGifViaAlbum(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WECHAT_GIF_ALBUM, value)
            .apply()
    }
}
