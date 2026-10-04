package com.aris.emojichan.sender

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 从快捷开关磁贴里拉起界面的唯一正确姿势（emc-2-029）。
 *
 * Android 14（API 34）起，`targetSdk >= 34` 的应用**不能**再用 `startActivityAndCollapse(Intent)`。
 * AOSP `android/service/quicksettings/TileService.java` 里那个重载第一件事就是
 * `if (CompatChanges.isChangeEnabled(START_ACTIVITY_NEEDS_PENDING_INTENT)) throw new
 * UnsupportedOperationException("startActivityAndCollapse: Starting activity from TileService
 * using an Intent is not allowed.")` —— 于是「点了磁贴毫无反应」，而且异常被 runCatching 吞掉，
 * 界面上一点提示都没有（用户 m12510 报的就是这个）。
 *
 * 新姿势是走 PendingIntent 重载：由系统代发这次跳转，也就不受「后台应用不能启动界面」的限制。
 */
object TileLaunch {

    /**
     * API 34 起必须走 PendingIntent 重载。抽成纯函数，好在单测里把这条分界线钉住，
     * 免得以后有人看着两个重载"顺手"改回 Intent 那个。
     */
    fun needsPendingIntent(sdkInt: Int): Boolean = sdkInt >= 34

    /**
     * 拉起 [intent] 并收起状态栏。返回 false 表示连兜底都没成（每一步都已写进发送日志）。
     *
     * [tag] 是发送日志的标签，目前只有悬浮球那颗磁贴在用（传的是「磁贴」）。
     */
    fun startActivityAndCollapse(tile: TileService, intent: Intent, tag: String): Boolean {
        // 要走到系统那边去，必须有这个 flag（旧写法里是调用方自己加的，统一挪进来）。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (needsPendingIntent(Build.VERSION.SDK_INT)) {
            val pending = PendingIntent.getActivity(
                tile,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val result = runCatching { tile.startActivityAndCollapse(pending) }
            if (result.isSuccess) return true
            SendLog.e(tag, "PendingIntent 方式拉不起界面：" + describe(result.exceptionOrNull()))
        } else {
            val result = runCatching { tile.startActivityAndCollapse(intent) }
            if (result.isSuccess) return true
            SendLog.e(tag, "Intent 方式拉不起界面：" + describe(result.exceptionOrNull()))
        }
        // 兜底：磁贴刚被点过，系统对这一次的 startActivity 多半是放行的。
        val fallback = runCatching { tile.startActivity(intent) }
        if (fallback.isSuccess) {
            SendLog.w(tag, "系统代发没成，兜底 startActivity 把界面拉起来了")
            return true
        }
        SendLog.e(tag, "兜底 startActivity 也失败：" + describe(fallback.exceptionOrNull()))
        return false
    }

    private fun describe(error: Throwable?): String =
        if (error == null) "未知错误" else error.javaClass.simpleName + " " + error.message

    /** 同一个磁贴反复点，共用一个 requestCode 就够了。 */
    private const val REQUEST_CODE = 4101
}
