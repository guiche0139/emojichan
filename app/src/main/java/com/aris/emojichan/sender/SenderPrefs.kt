package com.aris.emojichan.sender

import android.content.Context

/**
 * 发送模块里「用户自己能选」的那几项。
 *
 * 四条：① 表情发到微信 / QQ 时走哪条路（全局，默认相册）；② 微信里的静态图片要不要单独改走剪贴板；
 * ③ 流程最后那一下「确认」要不要自动点；④ 悬浮球出现在哪些应用里。
 * 每个方案各有代价，而且哪条更好取决于微信自己的行为，只有真机能判定 —— 所以
 * 做成开关，用户试一次就知道该留哪个，不用等我重新发版。
 */
object SenderPrefs {

    private const val PREFS = "sender_settings"
    private const val KEY_SEND_VIA_ALBUM = "send_via_album"
    private const val KEY_WECHAT_IMAGE_CLIPBOARD = "wechat_image_clipboard"

    /** v0.1.318 之前只有「微信动图」这一个开关，这个键只读来迁移一次，不再写入。 */
    private const val KEY_LEGACY_WECHAT_GIF_ALBUM = "wechat_gif_album"
    private const val KEY_AUTO_CONFIRM = "auto_confirm_last_step"
    private const val KEY_BALL_PACKAGES = "ball_packages"
    private const val KEY_BALL_ON = "ball_on"

    /**
     * 全局发送方式：表情发到微信 / QQ 时走哪条路。
     *
     * true（默认，v0.1.319 起）= 相册：让应用自己走「＋ → 相册 → 第一格 → 发送」。
     * 目的地就是当前聊天，不用认人、没有选人页，图片和动图都原样发出；用户实测
     * v0.1.318 起这条路全程不弹系统的隐私确认框。
     * false = 分享：把图交给应用的分享入口，用户（或自动点选）在聊天列表里挑一个。
     * 步骤少，但微信那个列表按最近聊天排序，刚跟别人聊过天时有发错人的风险。
     *
     * 旧版本只有「微信动图」这一个开关：那个键存过就照它来，没存过就用新的默认（相册）。
     */
    fun sendViaAlbum(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SEND_VIA_ALBUM)) return prefs.getBoolean(KEY_SEND_VIA_ALBUM, true)
        return if (prefs.contains(KEY_LEGACY_WECHAT_GIF_ALBUM)) {
            prefs.getBoolean(KEY_LEGACY_WECHAT_GIF_ALBUM, false)
        } else {
            true
        }
    }

    fun setSendViaAlbum(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEND_VIA_ALBUM, value)
            .apply()
    }

    /**
     * 微信里的静态图片要不要改走剪贴板（默认 false = 继承上面那条全局方式）。
     *
     * true = 剪贴板：长按输入框 → 点「粘贴」，最快，目的地就是眼前这个会话。
     * 只对静态图片有意义 —— 微信会把动图粘贴成静图，所以动图永远跟随全局方式。
     * 别的应用没有这个例外：QQ 的输入框根本不吃图片，粘贴是白费。
     */
    fun wechatImageClipboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WECHAT_IMAGE_CLIPBOARD, false)

    fun setWechatImageClipboard(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WECHAT_IMAGE_CLIPBOARD, value)
            .apply()
    }

    /**
     * 流程最后那一下「确认」要不要自动点。
     *
     * false（默认，v0.2.002 起，用户 m09774）= 走到确认框就停下，由用户自己点 ——
     * 这是全程唯一一处「替用户拍板」，点错就是把表情发出去了、撤不回来，所以默认交给用户。
     * true = 看到确认框就自己点掉，用户零操作。微信的确认键叫「发送」、
     * QQ 叫「确定」，两边都认；想这样得在设置页自己打开（打开时有一次二次确认）。
     *
     * 分享 / 相册 / 粘贴三条路都读它。粘贴路线曾是例外（自己点发送，不受这个开关管），
     * v0.1.322 起改为同样返回 `PasteOutcome.STAGED` 交给用户（emc-2-014）。
     */
    fun autoConfirmLastStep(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONFIRM, false)

    fun setAutoConfirmLastStep(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONFIRM, value)
            .apply()
    }

    /** 没设置过时悬浮球出现在哪里：微信 + QQ（和以前的行为一致）。 */
    val DEFAULT_BALL_PACKAGES: Set<String> =
        setOf(AutoSendService.PACKAGE_WECHAT, AutoSendService.PACKAGE_QQ)

    /**
     * 悬浮球出现在哪些应用里。
     *
     * 这件事和「无障碍自动发送」是两回事：自动发送只认微信 / QQ（那两家的分享页是照着实测
     * 一点点写的），而悬浮球可以出现在任何应用里 —— 挑完表情走系统分享面板，照样能发给别人。
     * 所以显示范围交给用户勾，和 [AutoSendService.WATCHED_PACKAGES] 各管各的。
     *
     * 空集合 = 一个都没勾 = 不限制应用（哪里都显示）。
     */
    fun ballPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BALL_PACKAGES, null)
            ?.toSet()
            ?: DEFAULT_BALL_PACKAGES

    fun setBallPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BALL_PACKAGES, packages.toSet())
            .apply()
    }

    /**
     * 悬浮球的开关状态（用户 m09897：默认开着）。
     *
     * 光有 [FloatingBallService.isRunning] 不够 —— 那只是「此刻有没有在跑」，进程被系统收掉之后
     * 就成了 false。用户把球打开过，就是希望它一直开着，所以这里把「愿望」单独记一份：
     * 下次进主界面时按它把球静默挂回来；用户在开关或磁贴里主动收起时会写 false，不会被自动挂回。
     */
    fun ballOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BALL_ON, true)

    fun setBallOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BALL_ON, on)
            .apply()
    }
}
