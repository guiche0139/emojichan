package com.aris.emojichan.sender

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

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
     *
     * @ChecksSdkIntAtLeast 是给 Lint 看的：判断藏在这个函数里，Lint 眼里 34 那条线就
     * 消失了，会去报调用点「API 34 才能调」（NewApi）。标上之后它会认这个参数就是 SDK_INT。
     */
    @ChecksSdkIntAtLeast(parameter = 0, api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun needsPendingIntent(sdkInt: Int): Boolean = sdkInt >= 34

    /**
     * 拉起 [intent] 并收起状态栏。返回 false 表示连兜底都没成（每一步都已写进发送日志）。
     *
     * [tag] 是发送日志的标签，目前只有悬浮球那颗磁贴在用（传的是「磁贴」）。
     */
    fun startActivityAndCollapse(tile: TileService, intent: Intent, tag: String): Boolean {
        // 要走到系统那边去，必须有这个 flag（旧写法里是调用方自己加的，统一挪进来）。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val (how, result) = if (needsPendingIntent(Build.VERSION.SDK_INT)) {
            "PendingIntent" to viaPendingIntent(tile, intent)
        } else {
            "Intent" to viaIntent(tile, intent)
        }
        if (result.isSuccess) return true
        SendLog.e(tag, how + " 方式拉不起界面：" + describe(result.exceptionOrNull()))
        // 兜底：磁贴刚被点过，系统对这一次的 startActivity 多半是放行的。
        val fallback = runCatching { tile.startActivity(intent) }
        if (fallback.isSuccess) {
            SendLog.w(tag, "系统代发没成，兜底 startActivity 把界面拉起来了")
            return true
        }
        SendLog.e(tag, "兜底 startActivity 也失败：" + describe(fallback.exceptionOrNull()))
        return false
    }

    /**
     * API 34 起唯一还能用的那个重载。
     *
     * 版本判断写在 [needsPendingIntent] 里（那样单测钉得住），这里再补一句 @RequiresApi
     * 把「只会在 34+ 上跑到」写进签名，Lint 与后来人都看得见。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun viaPendingIntent(tile: TileService, intent: Intent): Result<Unit> = runCatching {
        val pending = PendingIntent.getActivity(
            tile,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        tile.startActivityAndCollapse(pending)
    }

    /**
     * API 34 **之前**的老重载：同一个 intent 在 targetSdk >= 34 的包上调用会抛
     * UnsupportedOperationException（emc-2-029）。所以这一支只在 SDK_INT < 34 时走，
     * Lint 那句「换 PendingIntent」的提醒在这里不适用。
     */
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun viaIntent(tile: TileService, intent: Intent): Result<Unit> =
        runCatching { tile.startActivityAndCollapse(intent) }

    private fun describe(error: Throwable?): String =
        if (error == null) "未知错误" else error.javaClass.simpleName + " " + error.message

    /** 同一个磁贴反复点，共用一个 requestCode 就够了。 */
    private const val REQUEST_CODE = 4101
}
